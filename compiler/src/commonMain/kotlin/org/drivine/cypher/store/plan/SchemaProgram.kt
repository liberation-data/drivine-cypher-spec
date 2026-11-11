package org.drivine.cypher.store.plan

import org.drivine.cypher.CompilerException
import org.drivine.cypher.store.ddl.DdlStatement
import org.drivine.cypher.store.ddl.FalkorDbDdl
import org.drivine.cypher.store.ddl.SqlStoreDdl
import org.drivine.cypher.store.ddl.UniqueRecreation
import org.drivine.cypher.store.ddl.unsupportedTextIndex
import org.drivine.cypher.store.marker.MarkerInventory
import org.drivine.cypher.store.marker.MarkerStatements
import org.drivine.cypher.store.marker.SchemaMarker
import org.drivine.cypher.store.schema.ItemForm
import org.drivine.cypher.store.schema.ObjectDrop
import org.drivine.cypher.store.schema.SqlStoreSchema
import org.drivine.cypher.store.schema.StoreError
import org.drivine.cypher.store.schema.StoreSchema
import org.drivine.cypher.store.schema.catalogItem
import org.drivine.schema.ConstraintSpec
import org.drivine.schema.EnsureResult
import org.drivine.schema.IndexSpec
import org.drivine.schema.RangeIndexSpec
import org.drivine.schema.SchemaItemInfo
import org.drivine.schema.SchemaItemKind
import org.drivine.schema.SchemaItemSpec
import org.drivine.schema.SchemaMode
import org.drivine.schema.TextIndexSpec
import org.drivine.schema.VectorIndexSpec
import org.drivine.schema.lowercased
import org.drivine.schema.made

/** A failure drivine4j's schema manager catches for its owner, and goes on with the next (its `DrivineException`). */
internal class OwnerFailure(override val message: String) : Exception(message)

/**
 * A failure drivine4j's schema manager doesn't catch: the store's own error for [action], which drivine4j's schema
 * executor gives as the driver threw it, and which ends the plan. [round] is the round it ran in.
 */
internal class RawFailure(val error: StoreError, val action: PlanAction, val round: Int) : Exception(error.messages)

/** How an index made in the background is waited for: at most [timeoutMillis], and an index not built handed to [unbuilt], or thrown where there is none. */
private class Build(val timeoutMillis: Long, val unbuilt: MutableList<UnbuiltIndex>?) {
    /** The timeout in whole seconds, rounded up, as Neo4j's `db.awaitIndex` takes it: only zero is zero. */
    val timeoutSeconds: Long get() = timeoutMillis / 1000 + if (timeoutMillis % 1000 > 0) 1 else 0
}

/**
 * One run of a plan, from its start, over the outcomes [io] gives: drivine4j's `SchemaManager.applyToDatabase` and
 * `remove`, and the `IndexManager` and `ConstraintManager` calls they make, as straight code. Each statement goes through
 * [io], which gives back what the store said, or ends the round where it hasn't said it yet; the next round runs it all
 * again, as far as the outcomes go. So each method is drivine4j's, line for line, with its reads and writes as the store
 * sees them, and its log lines as it writes them.
 */
internal class SchemaProgram(
    private val store: StoreSchema,
    private val markers: MarkerStatements,
    private val request: PlanRequest,
    private val io: Replay,
) {
    private val database = request.database
    private val policy = request.policy
    private val engine = store.engine
    private val log = mutableListOf<LogLine>()

    /** The listings read since the plan's own DDL last ran, by statement: their rows, or null where the first round's read failed, until it is used. */
    private val listings = mutableMapOf<String, List<Any?>?>()

    /** The markers' properties read, by scope and property: the outcome of each read. */
    private val markerReads = mutableMapOf<Pair<String, String>, PlanOutcome>()

    /** The markers' properties read in the first round, by scope and property. */
    private val prefetched = mutableSetOf<Pair<String, String>>()

    /**
     * What makes an item of another form again, for the line that says it is kept: the owner's recreate, where an owner is
     * applied; the item's own, for one of the public calls.
     */
    private var remake = "Call recreate() to make it again."

    /** The names a mismatch has been said of: each once a plan, though a recreate after it reads it again. */
    private val mismatchesSaid = mutableSetOf<String>()

    fun run(): DatabaseReport = when (val operation = request.operation) {
        is Operation.Enforce -> apply(forceRecreate = false, operation.owners, only = null)
        is Operation.RecreateAll -> apply(forceRecreate = true, operation.owners, only = null)
        // drivine4j's ensure and remove of no items return before anything is read: an ensure would otherwise write the
        // owner's marker, and keep it single, with nothing applied.
        is Operation.Ensure -> if (operation.items.isEmpty()) report() else
            apply(forceRecreate = false, Owners.One(operation.owner), only = operation.items.map { it.inventoryKey }.toSet())
        is Operation.Remove -> if (operation.items.isEmpty()) report() else remove(operation)
        is Operation.Item -> item(operation)
    }

    private fun report(
        owners: List<OwnerReport> = emptyList(),
        item: ItemReport? = null,
        failure: String? = null,
        rethrown: RawFailure? = null,
    ) = DatabaseReport(database, owners, item, failure, rethrown?.let { Rethrown(it.action, it.error, it.round) }, log.toList())

    // ----- SchemaManager -----

    /** `applyToDatabase`: each owner [owners] takes, in order of its name, the unnamed first, each apart from the others. */
    private fun apply(forceRecreate: Boolean, owners: Owners, only: Set<String>?): DatabaseReport {
        val declarations = try {
            Declarations.of(request, owners)
        } catch (refusal: PlanRefusal) {
            return report(failure = refusal.message)
        } ?: return report()
        val applied = declarations.ownerSpecs.keys.filter { owners.includes(it) }.sortedBy { it ?: "" }
        // No owner of those asked for declares anything here: nothing is read or made.
        if (applied.isEmpty()) return report()
        try {
            prefetch(applied, declarations, only)
        } catch (raw: RawFailure) {
            // The store refused what the marker needs made, which drivine4j doesn't catch.
            return report(rethrown = raw)
        }

        val reports = mutableListOf<OwnerReport>()
        val failures = mutableListOf<String>()
        for (owner in applied) {
            val run = OwnerRun(owner)
            try {
                applyOwner(run, declarations, forceRecreate, only)
                reports += run.report()
            } catch (failure: OwnerFailure) {
                // Isolated: the owner's failure is kept, and the other owners are applied, so a library's schema still
                // converges, and records its version, where the application's fails.
                log(LogLevel.ERROR, SCHEMA_MANAGER, "Schema application for owner '${owner ?: "schema"}' on '$database' failed: ${failure.message}")
                failures += failure.message
                reports += run.report(failure.message)
            } catch (raw: RawFailure) {
                reports += run.report()
                return report(reports, rethrown = raw)
            }
        }
        val failure = if (failures.isEmpty()) null else
            "Schema application on database '$database' failed for ${failures.size} owner(s):\n" + failures.joinToString("\n\n")
        return report(reports, failure = failure)
    }

    /** What an owner's apply has done so far. */
    private class OwnerRun(val owner: String?) {
        val results = mutableListOf<ItemOutcome>()
        val unbuilt = mutableListOf<UnbuiltIndex>()
        var orphans: List<String> = emptyList()
        var marker: WrittenMarker? = null
        var removal: Removal? = null

        fun report(failure: String? = null) = OwnerReport(owner, results.toList(), unbuilt.toList(), orphans, marker, failure, removal)
    }

    /**
     * The first round: what the marker's statements need made, each owner's marker as drivine4j reads it (its version
     * where it is versioned or ensuring, and its items), and the listing of each kind of item applied, each statement once.
     */
    private fun prefetch(owners: List<String?>, declarations: Declarations, only: Set<String>?) {
        prepare()
        val specs = mutableListOf<SchemaItemSpec>()
        var singleton = false
        for (owner in owners) {
            val versioned = SchemaMarker.versionToken(declarations.contributing.filter { it.owner == owner }.map { it.version }) != null
            singleton = singleton || versioned
            val scope = SchemaMarker.scopeFor(owner)
            if (versioned || only != null) prefetchMarker(scope, SchemaMarker.VERSION)
            prefetchMarker(scope, SchemaMarker.ITEMS)
            specs += declarations.ownerSpecs.getValue(owner).filter { only == null || it.inventoryKey in only }
        }
        prefetchListings(specs + listOfNotNull(SchemaMarker.CONSTRAINT.takeIf { singleton }))
        io.endPrefetch()
    }

    private fun prepare() {
        val prepare = markers.prepare ?: return
        val action = PlanAction.Execute(DdlStatement.Text(prepare), purpose = Purpose.PREPARE)
        val outcome = io.prefetch(action)
        if (outcome is PlanOutcome.Failed) throw RawFailure(outcome.error, action, io.lastRound)
    }

    /** Reads [property] of the scope's marker in the first round, once: an owner named `schema` shares the unnamed one's. */
    private fun prefetchMarker(scope: String, property: String) {
        if (!prefetched.add(scope to property)) return
        val outcome = io.prefetch(markerRead(scope, property)) ?: return
        markerReads[scope to property] = outcome
    }

    /** The listings [specs] are found in, each statement once: a constraint's where a constraint needs a range index made first too. */
    private fun prefetchListings(specs: List<SchemaItemSpec>) {
        val statements = linkedSetOf<String>()
        for (spec in specs) {
            if (spec is TextIndexSpec && !store.supportsTextIndex) continue
            when (val made = spec.made()) {
                is IndexSpec -> statements += store.ddl.listIndexesQuery(made.kind)
                is ConstraintSpec -> {
                    statements += store.ddl.listConstraintsQuery()
                    if (store.constraintsRequireBackingIndex) statements += store.ddl.listIndexesQuery(SchemaItemKind.RANGE_INDEX)
                }
            }
        }
        for (statement in statements) {
            when (val outcome = io.prefetch(listing(statement))) {
                null -> {}
                is PlanOutcome.Rows -> listings[statement] = outcome.rows
                else -> listings[statement] = null
            }
        }
    }

    /** `applyOwner`: one owner's items, and its marker. [only], for an ensure, the keys of the items it was given. */
    private fun applyOwner(run: OwnerRun, declarations: Declarations, forceRecreate: Boolean, only: Set<String>?) {
        val owner = run.owner
        val ownerLabel = owner ?: "schema"
        val ensuring = only != null
        val specs = declarations.ownerSpecs.getValue(owner)
        val runtimeOnlyKeys = declarations.runtimeOnlyKeys[owner].orEmpty()
        val declaredByOthers = declarations.ownerSpecs.filterKeys { it != owner }.values.flatten().map { it.inventoryKey }.toSet()
        val effectiveVersion = SchemaMarker.versionToken(declarations.contributing.filter { it.owner == owner }.map { it.version })
        val scope = SchemaMarker.scopeFor(owner)

        // The marker's singleton guarded before it is read or written: concurrent enforcing processes could otherwise
        // each make one.
        if (effectiveVersion != null) ensureSingleton(scope)
        // An ensure records the version it finds, so a change it doesn't act on is left to enforce.
        val storedVersion = if (effectiveVersion != null || ensuring) markerValue(scope, SchemaMarker.VERSION) else null
        // A change (a value recorded before, and now another) recreates the owner's items; a first value is adopted.
        val versionChanged = !ensuring && effectiveVersion != null && storedVersion != null && storedVersion != effectiveVersion
        val recreateOwner = versionChanged || (!ensuring && policy.recreateOnStartup)

        // Items an earlier manager ensured at runtime, and this one hasn't been given, are kept, not orphans: unless an
        // item declared now has the same identity under another key (a rename), which makes the one recorded an orphan.
        val recorded = markers.inventory(markerValue(scope, SchemaMarker.ITEMS))
        val declaredKeys = specs.map { it.inventoryKey }.toSet()
        val identities = specs.map { it.inventoryKey.removeSuffix(it.effectiveName) }
        val carried = recorded?.runtime.orEmpty().filter { key -> key !in declaredKeys && identities.none { key.startsWith(it) } }
        val stale = recorded?.stale.orEmpty()

        run.orphans = orphans(ownerLabel, recorded?.keys, declaredKeys + carried, declarations.coOwnedKeys)

        val toApply = if (only == null) specs else specs.filter { it.inventoryKey in only }
        if (ensuring) {
            log(LogLevel.INFO, SCHEMA_MANAGER, "Ensuring schema for owner '$ownerLabel' on '$database': ${toApply.size} runtime items, ${specs.size} declared in all")
        } else {
            log(
                LogLevel.INFO, SCHEMA_MANAGER,
                "Applying schema for owner '$ownerLabel' on '$database': ${specs.size} items (forceRecreate=$forceRecreate, versionChanged=$versionChanged, version=$effectiveVersion)",
            )
        }

        val build = Build(request.buildTimeoutMillis, run.unbuilt)
        remake = "SchemaManager.recreateAll(${owner?.let { "\"$it\"" } ?: "null"}) makes it again."
        for (spec in toApply) {
            // A co-owned item is never dropped by one owner's version change, only by an explicit recreate. A runtime item
            // marked stale missed its owner's recreate, and is recreated now.
            val coOwned = spec.inventoryKey in declarations.coOwnedKeys
            val recreate = forceRecreate || ((recreateOwner || spec.inventoryKey in stale) && !coOwned)
            run.results += ItemOutcome(spec, when (spec) {
                is IndexSpec -> applyIndex(spec, recreate, build)
                is ConstraintSpec -> applyConstraint(spec, recreate)
            })
        }

        finish(ownerLabel, run) {
            // A carried item misses a recreate of its owner, having no spec here: it is marked, to be recreated when next
            // ensured. A version change spares one another owner declares, as it spares a co-owned item. A runtime item
            // not applied this time keeps its mark.
            val appliedKeys = toApply.map { it.inventoryKey }.toSet()
            val staleAfter = stale.filter { it in runtimeOnlyKeys && it !in appliedKeys } +
                carried.filter { it in stale || forceRecreate || (recreateOwner && it !in declaredByOthers) }
            val inventory = MarkerInventory(specs.map { it.inventoryKey } + carried, runtimeOnlyKeys + carried, staleAfter.toSet())
            run.marker = record(scope, if (ensuring) storedVersion else effectiveVersion, inventory)
        }
    }

    /** The owner's items its marker recorded but it no longer declares, none of them co-owned: reported, and left in place. */
    private fun orphans(ownerLabel: String, stored: List<String>?, kept: Set<String>, coOwnedKeys: Set<String>): List<String> {
        // A marker with no inventory, written before inventories were kept, knows of no item.
        if (stored == null) return emptyList()
        val orphans = stored.filter { it !in kept && it !in coOwnedKeys }
        if (orphans.isNotEmpty()) {
            log(
                LogLevel.WARN, SCHEMA_MANAGER,
                "Owner '$ownerLabel' on '$database' previously applied ${orphans.size} item(s) it no longer declares (orphans): $orphans. " +
                    "They are left in place; drop them manually, or via a deliberate recreate, if unwanted.",
            )
        }
        return orphans
    }

    private fun applyIndex(spec: IndexSpec, recreate: Boolean, build: Build): EnsureResult {
        if (recreate) return recreateIndex(spec, build)
        val result = ensureIndex(spec, build)
        return if (result is EnsureResult.Drift && policy.recreateOnDrift) recreateIndex(spec, build) else result
    }

    private fun applyConstraint(spec: ConstraintSpec, recreate: Boolean): EnsureResult {
        if (recreate) return recreateConstraint(spec, policy.violationSampleSize, policy.replaceBlockingIndex)
        val result = ensureConstraint(spec, policy.violationSampleSize, policy.replaceBlockingIndex)
        return if (result is EnsureResult.Drift && policy.recreateOnDrift) {
            recreateConstraint(spec, policy.violationSampleSize, policy.replaceBlockingIndex)
        } else {
            result
        }
    }

    /**
     * `finish`: the owner's summary, and its marker [record]ed, unless a problem fails it under `FAIL_FAST`, every item
     * tried first: its marker is then left as it was, so the next enforce tries again. An index not built is a problem
     * too, while its outcome counts as what was done.
     */
    private fun finish(ownerLabel: String, run: OwnerRun, record: () -> Unit) {
        val outcomes = run.results
        val created = outcomes.count { it.result is EnsureResult.Created }
        val matching = outcomes.count { it.result is EnsureResult.AlreadyMatching }
        val recreated = outcomes.count { it.result is EnsureResult.Recreated }
        val problems = outcomes.filter {
            it.result is EnsureResult.Drift || it.result is EnsureResult.Violation || it.result is EnsureResult.BlockedByIndex
        }.map { describe(it.spec, it.result) } + run.unbuilt.map(::describe)

        log(
            LogLevel.INFO, SCHEMA_MANAGER,
            "Schema for owner '$ownerLabel' on '$database': $created created, $matching already matching, $recreated recreated, ${problems.size} needing attention",
        )

        if (problems.isNotEmpty() && policy.mode == SchemaMode.FAIL_FAST) {
            throw OwnerFailure(
                "Schema application for owner '$ownerLabel' on database '$database' found problems:\n" +
                    problems.joinToString("\n") + "\n" +
                    "Resolve them, set drivine.schema.recreate-on-drift=true (destructive), " +
                    "drivine.schema.replace-blocking-index=true for an index in the way of a constraint, " +
                    "drivine.schema.index-build-timeout longer for an index still being built, " +
                    "or set drivine.schema.mode=WARN to continue with warnings."
            )
        }

        // What the owner applied is recorded, versioned or not, so its inventory ties to the marker.
        record()

        problems.forEach { log(LogLevel.WARN, SCHEMA_MANAGER, it) }
    }

    private fun describe(index: UnbuiltIndex): String =
        if (index.stillBuilding) {
            "  - STILL BUILDING: ${index.message}. It is left to be built, and waited for again on the next enforce"
        } else {
            "  - BUILD FAILED: ${index.message}. It is left in place, failed: drop or recreate it once the data it failed on is fixed"
        }

    private fun describe(spec: SchemaItemSpec, result: EnsureResult): String = when (result) {
        is EnsureResult.Drift ->
            "  - DRIFT: ${spec.kind} on ${spec.label}${spec.properties} exists with a different shape: ${result.existing}"
        is EnsureResult.Violation ->
            "  - VIOLATION: ${spec.kind} on ${spec.label}${spec.properties} cannot be created — " +
                "existing data violates it. Conflicting sample: ${result.conflictingSample}"
        is EnsureResult.BlockedByIndex ->
            "  - BLOCKED: ${spec.kind} on ${spec.label}${spec.properties} cannot be created while " +
                "index ${result.index.name} is in the way of it. Drop the index, or replace it with the constraint"
        else -> ""
    }

    /** `SchemaManager.remove`, on this database: drops what no one else declares here, and takes the items out of the owner's marker. */
    private fun remove(operation: Operation.Remove): DatabaseReport {
        val owner = operation.owner
        // drivine4j removes its catalog's items, merged as `SchemaCatalog.of` merges them: the indexes first.
        val items = try {
            Declarations.merged(operation.items)
        } catch (refusal: PlanRefusal) {
            return report(failure = refusal.message)
        }
        val run = OwnerRun(owner)
        return try {
            run.removal = removeItems(run, operation.copy(items = items))
            report(listOf(run.report()))
        } catch (failure: OwnerFailure) {
            // A remove isn't isolated by owner: what fails is thrown as it is.
            report(listOf(run.report(failure.message)), failure = failure.message)
        } catch (raw: RawFailure) {
            report(listOf(run.report()), rethrown = raw)
        }
    }

    private fun removeItems(run: OwnerRun, operation: Operation.Remove): Removal {
        val owner = operation.owner
        val ownerLabel = owner ?: "schema"
        val scope = SchemaMarker.scopeFor(owner)
        prepare()
        val scopesRead = io.prefetch(scopesQuery())
        prefetchMarker(scope, SchemaMarker.ITEMS)
        prefetchListings(operation.items)
        io.endPrefetch()

        val elsewhere = request.catalogs.filter { it.items.isNotEmpty() } + request.runtime.filter { it.owner != owner && it.items.isNotEmpty() }
        // What other owners' markers record, declared or ensured at runtime: an owner this manager wasn't given, such as
        // an application's, whose catalogs are another manager's or another process's.
        val others = when (scopesRead) {
            is PlanOutcome.Rows -> scopesRead.rows.mapNotNull { value(it) }.filter { it != scope }
            is PlanOutcome.Failed -> throw OwnerFailure(messageOf(scopesRead.error))
            else -> emptyList()
        }
        val recordedElsewhere = linkedMapOf<String, List<String>>()
        for (other in others) recordedElsewhere[other] = markers.inventory(markerValue(other, SchemaMarker.ITEMS))?.keys.orEmpty()

        var dropped = 0
        val kept = mutableListOf<SchemaItemSpec>()
        for (spec in operation.items) {
            val identity = identityOf(spec)
            val declaring = (
                elsewhere.filter { other -> other.items.any { identityOf(it) == identity } }.map { it.owner ?: "schema" } +
                    recordedElsewhere.filterValues { keys -> keys.any { recordsIdentity(it, spec) } }.keys
                ).distinct()
            if (declaring.isNotEmpty()) {
                log(
                    LogLevel.WARN, SCHEMA_MANAGER,
                    "${spec.kind} on ${spec.label}${spec.properties} is removed from owner '$ownerLabel' on '$database' and left in place, as " +
                        "${declaring.joinToString { "'$it'" }} declare(s) it",
                )
                kept += spec
                continue
            }
            val removed = when (spec) {
                is IndexSpec -> dropIndex(spec)
                is ConstraintSpec -> dropConstraint(spec)
            }
            if (removed) dropped++
        }

        markers.inventory(markerValue(scope, SchemaMarker.ITEMS))?.let { recorded ->
            val gone = recorded.keys.filter { key -> operation.items.any { recordsIdentity(key, it) } }.toSet()
            if (gone.isNotEmpty()) {
                val version = markerValue(scope, SchemaMarker.VERSION)
                run.marker = record(scope, version, MarkerInventory(recorded.keys - gone, recorded.runtime - gone, recorded.stale - gone))
            }
        }

        log(LogLevel.INFO, SCHEMA_MANAGER, "Schema for owner '$ownerLabel' on '$database': ${operation.items.size} removed, $dropped dropped, ${kept.size} left in place")
        return Removal(dropped, kept)
    }

    private data class Identity(val kind: SchemaItemKind, val label: String, val properties: Set<String>)

    private fun identityOf(spec: SchemaItemSpec) = Identity(spec.kind, spec.label, spec.made().properties.toSet())

    /**
     * Whether the inventory [key] is of an item of [spec]'s identity, whatever its name: of its kind and label, and of its
     * properties in any order, as [identityOf] compares them.
     */
    private fun recordsIdentity(key: String, spec: SchemaItemSpec): Boolean {
        val made = spec.made()
        val head = "${made.kind}:${made.label}:"
        if (!key.startsWith(head)) return false
        val length = made.properties.sumOf { it.length } + made.properties.size - 1
        val rest = key.substring(head.length)
        if (rest.length <= length || rest[length] != ':') return false
        val properties = rest.substring(0, length)
        return properties == made.properties.joinToString(",") || properties.split(",").sorted() == made.properties.sorted()
    }

    /**
     * One of the public `IndexManager` and `ConstraintManager` calls. Their index waits 60 seconds, whatever the request's
     * [PlanRequest.buildTimeoutMillis]: drivine4j's public manager is made with `IndexManager.DEFAULT_BUILD_TIMEOUT`.
     */
    private fun item(operation: Operation.Item): DatabaseReport {
        val spec = operation.spec
        prefetchListings(listOf(spec))
        io.endPrefetch()
        return try {
            val item = when (operation.call) {
                ItemCall.ENSURE -> ItemReport(result = when (spec) {
                    is IndexSpec -> ensureIndex(spec, Build(PlanRequest.DEFAULT_BUILD_TIMEOUT_MILLIS, null))
                    is ConstraintSpec -> ensureConstraint(spec, policy.violationSampleSize, policy.replaceBlockingIndex)
                })
                ItemCall.RECREATE -> ItemReport(result = when (spec) {
                    is IndexSpec -> recreateIndex(spec, Build(PlanRequest.DEFAULT_BUILD_TIMEOUT_MILLIS, null))
                    is ConstraintSpec -> recreateConstraint(spec, policy.violationSampleSize, policy.replaceBlockingIndex)
                })
                ItemCall.DROP -> ItemReport(dropped = when (spec) {
                    is IndexSpec -> dropIndex(spec)
                    is ConstraintSpec -> dropConstraint(spec)
                })
            }
            report(item = item)
        } catch (failure: OwnerFailure) {
            report(failure = failure.message)
        } catch (raw: RawFailure) {
            report(rethrown = raw)
        }
    }

    // ----- The marker: SchemaVersionStore -----

    private fun markerRead(scope: String, property: String) =
        PlanAction.Query(markers.read(property), mapOf("scope" to scope), Purpose.MARKER)

    private fun scopesQuery(): PlanAction.Query {
        val sql = markers.scopes
        return PlanAction.Query(sql ?: MarkerStatements.SCOPES_CYPHER, purpose = Purpose.SCOPES, compiled = sql == null && store.ddl is SqlStoreDdl)
    }

    /** [property] of the scope's newest marker, as text: null where there is no marker, or it has no such property. */
    private fun markerValue(scope: String, property: String): String? {
        val outcome = markerReads.getOrPut(scope to property) { io.ask(markerRead(scope, property)) }
        return when (outcome) {
            is PlanOutcome.Rows -> outcome.rows.firstOrNull()?.let(::value)
            // Not kept: drivine4j reads it again for the next owner of the scope, an owner named `schema` after the unnamed one.
            is PlanOutcome.Failed -> {
                markerReads.remove(scope to property)
                throw OwnerFailure(messageOf(outcome.error))
            }
            else -> null
        }
    }

    /** A row's one value as text: a single column is given as its value. */
    private fun value(row: Any?): String? = when (row) {
        null -> null
        is String -> row
        is List<*> -> row.firstOrNull()?.let(::value)
        else -> row.toString()
    }

    /**
     * Guards the marker's singleton: duplicates a race left are collapsed, keeping the newest, and its key is backed by
     * a uniqueness constraint. Neo4j refuses the constraint where a plain index over the key is in the way of it: the
     * label is Drivine's, so the index is replaced.
     */
    private fun ensureSingleton(scope: String) {
        val outcome = io.run(PlanAction.Execute(DdlStatement.Text(markers.deleteAllButNewest), mapOf("scope" to scope), Purpose.MARKER))
        if (outcome is PlanOutcome.Failed) throw OwnerFailure(messageOf(outcome.error))
        ensureConstraint(SchemaMarker.CONSTRAINT, DEFAULT_VIOLATION_SAMPLE_SIZE, replaceBlockingIndex = true)
    }

    /**
     * Writes the scope's marker, once again where the first merge fails: it lost a race to make the marker, which a second
     * merge finds. What was read of the scope is read again after: an owner named `schema`, applied after the unnamed one,
     * reads the marker the unnamed one wrote, as drivine4j reads it.
     */
    private fun record(scope: String, version: String?, inventory: MarkerInventory): WrittenMarker {
        val items = inventory.items
        markerReads.keys.removeAll { it.first == scope }
        fun merge() = io.run(PlanAction.WriteMarker(scope, markers.merge, markers.mergeParameters(scope, version, items, io.clock())))
        if (merge() is PlanOutcome.Failed) {
            val again = merge()
            if (again is PlanOutcome.Failed) throw OwnerFailure(messageOf(again.error))
        }
        return WrittenMarker(version, items)
    }

    // ----- IndexManager -----

    /** `IndexManager.ensure`. */
    private fun ensureIndex(spec: IndexSpec, build: Build): EnsureResult {
        requireSupported(spec)
        // An index that ignores case is over copies every node of its label has. One made anew fills them in again,
        // recorded or not: they may have gone out of step while it was gone.
        fillIn(spec) { findIndex(spec) == null }
        return ensureMadeIndex(spec.made(), build)
    }

    private fun ensureMadeIndex(spec: IndexSpec, build: Build): EnsureResult {
        val items = indexes(spec.kind)
        val existing = items.firstOrNull { store.matchesIdentity(it, spec) }
        val form = formOf(spec)
        // Another item under a name Drivine gives this one's objects: never taken as made, and never made over.
        if (form is ItemForm.Mismatch) return mismatched(spec, existing, form)
        // Another library's objects with no record of them: kept as they are, and never made over or recorded as made.
        if (existing == null && form is ItemForm.Stale) return keptUnrecorded(spec, form)
        if (existing == null) {
            // A related item (of the kind and label, not the spec's) lets a store with an index a label (FalkorDB) make
            // only the properties missing.
            val related = items.firstOrNull { it.kind == spec.kind && it.label == spec.label }
            warnUnsupportedTuning(spec)
            ddl(store.ddl.createIndex(spec, related?.catalogItem))
            val created = awaitBuilt(spec, findIndex(spec), build) ?: SchemaItemInfo.fromSpec(spec)
            log(LogLevel.INFO, INDEX_MANAGER, "Created $engine ${spec.kind} on ${spec.label}${spec.properties}")
            logEffectiveVectorConfig(spec, created)
            return EnsureResult.Created(created)
        }
        if (store.isIncomplete(existing)) {
            // Made whole only in this version's form: one of another form would be completed in this version's, which is
            // never reported made. It is kept as it is, and said so, as any of another form is.
            if (form is ItemForm.Stale || form is ItemForm.Newer) {
                keepForm(spec, form)
                return EnsureResult.AlreadyMatching(existing)
            }
            ddl(store.ddl.createIndex(spec, existing.catalogItem))
            val completed = awaitBuilt(spec, findIndex(spec), build) ?: SchemaItemInfo.fromSpec(spec)
            log(LogLevel.INFO, INDEX_MANAGER, "Completed $engine ${spec.kind} on ${spec.label}${spec.properties}: it lacked part of what is made for it")
            return EnsureResult.Created(completed)
        }
        return if (store.matchesShape(existing, spec)) {
            keepForm(spec, form)
            // Made by another process, or an earlier run: it may still be being built, or its build may have failed.
            EnsureResult.AlreadyMatching(awaitBuilt(spec, existing, build) ?: existing)
        } else {
            log(
                LogLevel.WARN, INDEX_MANAGER,
                "Index drift on ${spec.label}${spec.properties}: existing $existing does not match requested $spec. Call recreate() to replace it (destructive).",
            )
            EnsureResult.Drift(existing, spec)
        }
    }

    /** `IndexManager.find`: the index [spec] is, as the store lists it. */
    private fun findIndex(spec: IndexSpec, poll: Long? = null): SchemaItemInfo? {
        requireSupported(spec)
        val made = spec.made()
        return indexes(made.kind, poll).firstOrNull { store.matchesIdentity(it, made) }
    }

    /** `IndexManager.drop`. */
    private fun dropIndex(spec: IndexSpec): Boolean {
        val existing = findIndex(spec) ?: return false
        dropExisting(existing, spec.made())
        return true
    }

    /** `IndexManager.recreate`. */
    private fun recreateIndex(spec: IndexSpec, build: Build): EnsureResult {
        requireSupported(spec)
        // The copies are filled in again too, where a write that bypassed Drivine left one out of step.
        fillIn(spec) { true }
        return recreateMadeIndex(spec.made(), build)
    }

    private fun recreateMadeIndex(spec: IndexSpec, build: Build): EnsureResult {
        // The record, and its aliases, as found before anything under the item's names is dropped: once what stood under
        // its name is gone the listing leaves the record out, and it is still to be forgotten, and written again in this
        // version's form.
        val found = findIndex(spec)
        val aliases = if (found != null) aliasesOfIndex(spec) else emptyList()
        val cleared = clearMismatched(spec, found) { findIndex(spec) }
        cleared.drift?.let { return it }
        val existing = cleared.existing ?: found
        if (existing == null) dropUnrecorded(spec)
        if (existing != null) dropExisting(existing, spec)
        // Read again after the drop: on a store with an index a label, the label may still have one over other
        // properties, which the create takes into account.
        val related = indexes(spec.kind).firstOrNull { it.kind == spec.kind && it.label == spec.label }
        warnUnsupportedTuning(spec)
        ddl(store.ddl.createIndex(spec, related?.catalogItem))
        val created = awaitBuilt(spec, findIndex(spec), build) ?: SchemaItemInfo.fromSpec(spec)
        if (aliases.isNotEmpty()) alias(created, aliases)
        logEffectiveVectorConfig(spec, created)
        log(
            LogLevel.WARN, INDEX_MANAGER,
            "Recreated $engine ${spec.kind} on ${spec.label}${spec.properties} — previously indexed data may be stale " +
                "(vector indexes: stored embeddings need re-embedding)",
        )
        return EnsureResult.Recreated(existing, created)
    }

    /** A TEXT index is Neo4j's: refused elsewhere before anything is read or made, as one of another kind would look like it took effect. */
    private fun requireSupported(spec: IndexSpec) {
        if (spec is TextIndexSpec && !store.supportsTextIndex) throw OwnerFailure(unsupportedTextIndex(spec, engine).message ?: "")
    }

    /** Says what a vector spec pins that the store can't make, which the store then chooses. */
    private fun warnUnsupportedTuning(spec: IndexSpec) {
        if (spec !is VectorIndexSpec || !spec.pinsPhysicalConfig) return
        val unsupported = store.unsupportedVectorTuning(spec).ifEmpty { return }
        log(
            LogLevel.WARN, INDEX_MANAGER,
            "Vector index ${spec.effectiveName} on ${spec.label}${spec.properties} pins $unsupported, which $engine cannot express — the engine will choose these " +
                "instead. Remove the pin or accept engine-chosen values on this backend.",
        )
    }

    /** Says what a vector index was made with, pinned or the store's choice, so a value is attributable later. */
    private fun logEffectiveVectorConfig(spec: IndexSpec, created: SchemaItemInfo) {
        if (spec !is VectorIndexSpec) return
        val effective = created.vectorConfigDescription()
        if (effective.isEmpty()) return
        val pinned = if (spec.pinsPhysicalConfig) "partly pinned" else "engine defaults"
        log(LogLevel.INFO, INDEX_MANAGER, "Vector index ${created.name ?: spec.effectiveName} on ${spec.label}${spec.properties} effective config ($pinned): $effective")
    }

    /** Drops [existing], narrowed to what [spec] asks for, and on a store that drops in the background, waits until it is gone. */
    private fun dropExisting(existing: SchemaItemInfo, spec: IndexSpec) {
        ddl(store.ddl.dropIndex(narrowTo(existing, spec).catalogItem))
        awaitDropped(spec)
        log(LogLevel.INFO, INDEX_MANAGER, "Dropped $engine ${spec.kind} on ${spec.label}${spec.properties}")
    }

    /** [existing] narrowed to the properties [spec] declares, so a store with an index a label never drops more than was asked. */
    private fun narrowTo(existing: SchemaItemInfo, spec: IndexSpec): SchemaItemInfo {
        val narrowed = existing.properties.filter { spec.properties.contains(it) }
        return if (narrowed.isEmpty() || narrowed.size == existing.properties.size) existing else existing.copy(properties = narrowed)
    }

    /**
     * Waits until [item], the index [spec] is as last listed, is built, where the store builds in the background, and
     * gives it as last listed: Neo4j waits on the server, by the index's name; FalkorDB is polled until it lists the index
     * built. An index whose build failed, or didn't end in time, is handed to [build], or thrown where it takes none.
     */
    private fun awaitBuilt(spec: IndexSpec, item: SchemaItemInfo?, build: Build): SchemaItemInfo? {
        val awaitIndex = store.awaitIndexStatement
        if (awaitIndex != null) {
            val name = item?.name ?: spec.effectiveName
            val action = PlanAction.AwaitIndex(awaitIndex, mapOf("name" to name, "timeout" to build.timeoutSeconds))
            val outcome = io.run(action)
            if (outcome is PlanOutcome.Failed) {
                val error = outcome.error
                val round = io.lastRound
                // A timeout is told by its code. Any other error is a failed build only where the index is listed FAILED:
                // one not there, or a connection lost, is thrown as it is.
                val stillBuilding = store.isBuildTimeout(error) || when (indexState(name)?.uppercase()) {
                    "FAILED" -> false
                    "POPULATING" -> true
                    else -> throw RawFailure(error, action, round)
                }
                val what = "$engine ${spec.kind} '$name' on ${spec.label}${spec.properties}"
                val message = if (stillBuilding) {
                    "$what is still being built, having waited ${javaDuration(build.timeoutMillis)}: ${messageOf(error, "null")}"
                } else {
                    "$what failed to build: ${messageOf(error, "null")}"
                }
                return unbuilt(UnbuiltIndex(spec, stillBuilding, message), item, build)
            }
            return item
        }
        if (!store.indexOperationsAreAsync) return item
        val what = "$engine ${spec.kind} on ${spec.label}${spec.properties}"
        val deadline = io.clock() + build.timeoutMillis
        var info = item
        while (info == null || info.buildInProgress) {
            val left = deadline - io.clock()
            if (left <= 0) {
                val message = "$what is still being built, having waited ${javaDuration(build.timeoutMillis)}" + (info?.status?.let { ": it is $it" } ?: "")
                return unbuilt(UnbuiltIndex(spec, stillBuilding = true, message), info, build)
            }
            info = findIndex(spec, poll = minOf(POLL_MILLIS, left).coerceAtLeast(1))
        }
        if (info.buildFailed) {
            return unbuilt(UnbuiltIndex(spec, stillBuilding = false, "$what failed to build: it is ${info.status}"), info, build)
        }
        return info
    }

    /** The state Neo4j gives the index [name]; null where it gives none, or the read fails. */
    private fun indexState(name: String): String? {
        val statement = store.indexStateStatement ?: return null
        val outcome = io.ask(PlanAction.Query(statement, mapOf("name" to name), Purpose.STATE))
        val rows = (outcome as? PlanOutcome.Rows)?.rows ?: return null
        return (rows.singleOrNull() as? Map<*, *>)?.get("state") as? String
    }

    /** Hands [index] to [build], and gives [item]; or throws it, where [build] takes none. */
    private fun unbuilt(index: UnbuiltIndex, item: SchemaItemInfo?, build: Build): SchemaItemInfo? {
        val unbuilt = build.unbuilt ?: throw OwnerFailure(index.message)
        unbuilt += index
        return item
    }

    /** Polls, on a store that drops in the background, until [spec] is no longer listed. */
    private fun awaitDropped(spec: IndexSpec) {
        if (!store.indexOperationsAreAsync) return
        val deadline = io.clock() + ASYNC_TIMEOUT_MILLIS
        var poll = 0L
        while (findIndex(spec, poll) != null) {
            if (io.clock() >= deadline) {
                throw OwnerFailure("Timed out after ${javaDuration(ASYNC_TIMEOUT_MILLIS)} waiting for ${spec.kind} on ${spec.label}${spec.properties} to be dropped")
            }
            poll = POLL_MILLIS
        }
    }

    /** The aliases the record of the index [spec] keeps; none on a store that keeps none. */
    private fun aliasesOfIndex(spec: IndexSpec): List<String> {
        if (store.ddl !is SqlStoreDdl) return emptyList()
        val rows = listingRows(store.ddl.listIndexesQuery(spec.kind)) ?: return emptyList()
        return rows.firstNotNullOfOrNull { row ->
            store.parseIndexRows(listOf(row)).firstOrNull { store.matchesIdentity(it, spec) }?.let { store.aliasesOf(row) }
        }.orEmpty()
    }

    /** Records [aliases] as every alias of [item], whose record is there. */
    private fun alias(item: SchemaItemInfo, aliases: List<String>) {
        val ddl = store.ddl as? SqlStoreDdl ?: return
        updateRecord(ddl.aliased(item.catalogItem, aliases))
    }

    /** Runs [statement], which changes what a SQL store's record keeps of an item (its aliases, its form), and nothing else. */
    private fun updateRecord(statement: String) {
        val action = PlanAction.Execute(DdlStatement.Text(statement), purpose = Purpose.ALIASES)
        val outcome = io.run(action)
        // What a later read lists has changed.
        listings.clear()
        if (outcome is PlanOutcome.Failed) throw RawFailure(outcome.error, action, io.lastRound)
    }

    /**
     * `IndexManager.fillIn` and `LowerCopyCatalog.fillIn`: fills in the lowercased copies [spec] is made over, where it
     * ignores case; [again], asked only then, where they are filled in whether or not recorded. Otherwise the record is
     * read first, on a schema connection, which drivine4j doesn't catch, and those recorded are left out. A fill or its
     * record that fails, through the auto-commit manager, fails the owner.
     */
    private fun fillIn(spec: SchemaItemSpec, again: () -> Boolean) {
        val properties = spec.lowercased.ifEmpty { return }
        val missing = if (again()) properties else {
            val read = PlanAction.ReadLowerCopies(spec.label, properties)
            val outcome = io.ask(read)
            if (outcome is PlanOutcome.Failed) throw RawFailure(outcome.error, read, io.lastRound)
            val recorded = (outcome as PlanOutcome.Rows).rows.mapNotNull { row ->
                (row as? List<*>)?.takeIf { it.size == 2 && it[0] == spec.label }?.get(1) as? String
            }.toSet()
            properties.filter { it !in recorded }
        }
        if (missing.isEmpty()) return
        val outcome = io.run(PlanAction.FillLowerCopies(spec.label, missing))
        if (outcome is PlanOutcome.Failed) throw OwnerFailure(messageOf(outcome.error))
    }

    // ----- ConstraintManager -----

    /** `ConstraintManager.ensure`. */
    private fun ensureConstraint(spec: ConstraintSpec, violationSampleSize: Int, replaceBlockingIndex: Boolean): EnsureResult {
        requireConstrainable(spec.made())
        // A constraint that ignores case is over copies every node of its label has, so a node saved before there were
        // copies is held to it too. One made anew fills them in again, recorded or not.
        fillIn(spec) { findConstraint(spec) == null }
        return ensureMadeConstraint(spec.made(), violationSampleSize, replaceBlockingIndex)
    }

    private fun ensureMadeConstraint(spec: ConstraintSpec, violationSampleSize: Int, replaceBlockingIndex: Boolean): EnsureResult {
        // A constraint still being built, by another process or an earlier run, may yet fail: its build is waited for, as
        // one made here. One dropped meanwhile is made again below.
        val existing = findConstraint(spec)?.let { if (it.buildInProgress) awaitBuild(spec, untilListed = false) else it }
        val form = formOf(spec)
        // Another item under the name Drivine gives this one's index: never taken as made, and never made over.
        if (form is ItemForm.Mismatch) return mismatched(spec, existing, form)
        if (existing == null && form is ItemForm.Stale) return keptUnrecorded(spec, form)
        if (existing != null && existing.buildFailed) {
            // FalkorDB keeps listing a constraint whose build failed, though it enforces nothing: dropped, and built again,
            // which gives a violation where the data still repeats a key.
            dropFailedBuild(spec, existing)
            log(LogLevel.WARN, CONSTRAINT_MANAGER, "Dropped $engine ${spec.kind} on ${spec.label}${spec.properties}, whose build had failed, to build it again")
        } else if (existing != null) {
            return if (store.matchesShape(existing, spec)) {
                if (spec != SchemaMarker.CONSTRAINT) keepForm(spec, form) else adoptForm(form)
                EnsureResult.AlreadyMatching(existing)
            } else {
                log(LogLevel.WARN, CONSTRAINT_MANAGER, "Constraint drift on ${spec.label}${spec.properties}: existing $existing does not match requested $spec")
                EnsureResult.Drift(existing, spec)
            }
        }

        // A recreate cut off after the old index was dropped left only the one built first, which holds the data unique:
        // it is put in its place, and the constraint's create and record finished, rather than made over it.
        val sql = store.ddl as? SqlStoreDdl
        if (existing == null && sql != null) {
            val recreation = sql.recreation(spec)
            if (finishCutOff(spec, recreation, listings[sql.listConstraintsQuery()].orEmpty())) {
                ddl(sql.createConstraint(spec))
                updateRecord(recreation.remade)
                val created = findConstraint(spec) ?: SchemaItemInfo.fromSpec(spec)
                log(LogLevel.INFO, CONSTRAINT_MANAGER, "Created $engine ${spec.kind} on ${spec.label}${spec.properties}")
                return EnsureResult.Created(created)
            }
        }

        // A store (FalkorDB) whose constraint needs an exact-match index over its properties made first; one not built is thrown.
        if (store.constraintsRequireBackingIndex) {
            ensureIndex(RangeIndexSpec(spec.label, spec.properties), Build(PlanRequest.DEFAULT_BUILD_TIMEOUT_MILLIS, null))
        }

        try {
            ddl(store.ddl.createConstraint(spec))
        } catch (refused: RawFailure) {
            if (store.isViolation(refused.error)) {
                confirmedViolation(spec, violationSampleSize)?.let { return it }
            }
            if (store.isBlockedByIndex(refused.error)) {
                val index = blockingIndex(spec) ?: throw refused
                if (!replaceBlockingIndex) {
                    log(LogLevel.WARN, CONSTRAINT_MANAGER, "Could not create constraint on ${spec.label}${spec.properties}: index ${index.name} is in the way of it")
                    return EnsureResult.BlockedByIndex(spec, index)
                }
                // Neo4j refuses for the index before it reads the data, so a key that repeats would refuse the constraint
                // after the index is gone: the data is read first, and the index kept, as where it can't be read.
                val repeated = repeatedKeys(spec, violationSampleSize) ?: throw refused
                if (repeated.isNotEmpty()) return violation(spec, repeated, violationSampleSize)
                ddl(store.ddl.dropIndex(index.catalogItem))
                log(LogLevel.INFO, CONSTRAINT_MANAGER, "Dropped index ${index.name} on ${spec.label}${spec.properties}, in the way of a constraint that replaces it")
                return ensureMadeConstraint(spec, violationSampleSize, replaceBlockingIndex = false)
            }
            throw refused
        }

        if (store.constraintCreationIsAsync) return awaitAsyncCreation(spec, violationSampleSize)

        val created = findConstraint(spec) ?: SchemaItemInfo.fromSpec(spec)
        log(LogLevel.INFO, CONSTRAINT_MANAGER, "Created $engine ${spec.kind} on ${spec.label}${spec.properties}")
        return EnsureResult.Created(created)
    }

    /** FalkorDB refuses a constraint over a property whose name isn't plain, before anything is read. */
    private fun requireConstrainable(spec: ConstraintSpec) {
        if (store.ddl !is FalkorDbDdl) return
        try {
            FalkorDbDdl.requireConstrainable(spec)
        } catch (e: CompilerException) {
            throw OwnerFailure(e.message ?: "")
        }
    }

    /** `ConstraintManager.find`. */
    private fun findConstraint(spec: ConstraintSpec, poll: Long? = null): SchemaItemInfo? {
        val made = spec.made()
        return constraints(poll).firstOrNull { store.matchesIdentity(it, made) }
    }

    /** `ConstraintManager.drop`, a constraint whose build failed too. */
    private fun dropConstraint(spec: ConstraintSpec): Boolean {
        val existing = findConstraint(spec) ?: return false
        ddl(store.ddl.dropConstraint(existing.catalogItem))
        log(LogLevel.INFO, CONSTRAINT_MANAGER, "Dropped $engine ${spec.kind} on ${spec.label}${spec.properties}")
        return true
    }

    /**
     * `ConstraintManager.recreate`. On a SQL store, made again in its place ([recreateInPlace]); on a graph engine, which
     * renames nothing and on Neo4j refuses a second constraint over the same schema, dropped and then made, as drivine4j
     * makes it, so data it refuses leaves it with none.
     */
    private fun recreateConstraint(spec: ConstraintSpec, violationSampleSize: Int, replaceBlockingIndex: Boolean): EnsureResult {
        requireConstrainable(spec.made())
        val ddl = store.ddl
        if (ddl is SqlStoreDdl) return recreateInPlace(spec, ddl, violationSampleSize)
        val existing = findConstraint(spec)
        if (existing != null) ddl(ddl.dropConstraint(existing.catalogItem))
        // The copies are filled in again too, where a write that bypassed Drivine left one out of step.
        fillIn(spec) { true }
        return when (val result = ensureMadeConstraint(spec.made(), violationSampleSize, replaceBlockingIndex)) {
            is EnsureResult.Created -> EnsureResult.Recreated(existing, result.info)
            else -> result
        }
    }

    /**
     * A uniqueness constraint made again on a SQL store so that data it refuses never leaves it with no index
     * (drivine-cypher-spec#135 §4, [SqlStoreDdl.recreation]): a unique index left by a recreate that stopped short is
     * dropped; the lowercased copies are filled in again, a copy the old index refuses being a key the data repeats,
     * sampled as the fill lowercases it; the unique index is built anew under a name of its own while the old one
     * stands, and where the data refuses it, the old index and its record are kept and the violation reported; it is
     * then swapped into the old one's place; and the constraint's `create` runs, finding it made, and its record is made
     * as one made anew would be, keeping its aliases. A recreate cut off after its old index was dropped, only the one
     * built first standing, is finished instead ([finishCutOff]).
     *
     * Another index under a name of the constraint's that isn't its unique index (Oracle's index over the property of
     * every node) is dropped first, by its name, as an index's is: it holds nothing unique. What stands under the unique
     * index's own name, another index or another library's, is what the swap drops. Oracle builds no second index over
     * the expressions of one there (`ORA-01408`), which an index made as this version makes it is (a plain index over
     * them it builds beside, and the data refuses it, or not): it is dropped first and made again, as drivine4j made it,
     * said at `INFO` where it was of this version's form, which held the data unique, and at `WARN` where it wasn't.
     */
    private fun recreateInPlace(spec: ConstraintSpec, ddl: SqlStoreDdl, violationSampleSize: Int): EnsureResult {
        val made = spec.made()
        val recreation = ddl.recreation(made)
        val found = findConstraint(spec)
        val rows = listings[ddl.listConstraintsQuery()].orEmpty()
        // Read before anything runs: the form of what the build may meet.
        val form = formOf(made)
        val mismatches = (store as? SqlStoreSchema)?.mismatchesOf(made, rows).orEmpty()
        // What isn't an index can't be dropped by its name, nor made over: the constraint stays drift.
        mismatches.firstOrNull { !it.droppable }?.let { return mismatched(made, found, it) }
        val inPlace = { name: String -> name.equals(recreation.name, ignoreCase = true) }
        val replaced = mismatches.firstOrNull { inPlace(it.name) }
        val unrecorded = if (replaced == null) store.unrecordedDrops(made, rows) else emptyList()
        for (other in mismatches.filterNot { inPlace(it.name) }) {
            ddl(store.dropMismatched(other.name))
            log(LogLevel.INFO, CONSTRAINT_MANAGER, droppedMismatch(made, other))
        }
        for (drop in unrecorded.filterNot { inPlace(it.name) }) {
            ddl(drop.statements)
            log(LogLevel.INFO, CONSTRAINT_MANAGER, droppedUnrecorded(made, drop, rows))
        }

        // A recreate cut off after the old index was dropped left only the one built first: put in its place, not dropped.
        if (finishCutOff(made, recreation, rows)) return remake(made, recreation, found, violationSampleSize)

        ddl(recreation.leftover)
        // The copies are filled in again too, where a write that bypassed Drivine left one out of step. One the old index
        // refuses is a key the data repeats, as the build would find it: sampled, and the old index and its record kept.
        refill(spec)?.let { refused ->
            if (store.isViolation(refused)) refilledViolation(spec, violationSampleSize)?.let { return it }
            throw OwnerFailure(messageOf(refused))
        }
        try {
            ddl(recreation.build)
        } catch (refused: RawFailure) {
            if (store.isViolation(refused.error)) return confirmedViolation(made, violationSampleSize) ?: throw refused
            if (!store.isAlreadyIndexed(refused.error)) throw refused
            // Over the expressions of the index there (Oracle builds a unique index beside one that isn't, over the same
            // expressions, and refuses it only for the data): dropped first, and made again.
            ddl(store.dropMismatched(recreation.name))
            val what = "Dropped $engine index ${recreation.name} of ${made.kind} on ${made.label}${made.properties} before making it again: " +
                "Oracle builds no second index over the same expressions (ORA-01408), so the new one can't be built beside it."
            val ours = replaced == null && (form is ItemForm.Current || form is ItemForm.Unrecorded)
            if (ours) {
                // Made as this version makes it, it held the data unique: only a write in between could repeat a key.
                log(
                    LogLevel.INFO, CONSTRAINT_MANAGER,
                    "$what It was made as this version makes it, so the data it held unique is unique still, but for a write in between",
                )
            } else {
                log(
                    LogLevel.WARN, CONSTRAINT_MANAGER,
                    "$what It was ${formText(form, replaced)}, not made as this version makes it: where the data repeats a key, " +
                        "${made.label}${made.properties} is left with no index until the constraint is made",
                )
            }
            return remake(made, recreation, found, violationSampleSize)
        }
        ddl(recreation.swap)
        replaced?.let { log(LogLevel.INFO, CONSTRAINT_MANAGER, droppedMismatch(made, it)) }
        unrecorded.filter { inPlace(it.name) }.forEach { log(LogLevel.INFO, CONSTRAINT_MANAGER, droppedUnrecorded(made, it, rows)) }
        return remake(made, recreation, found, violationSampleSize)
    }

    /** What [form], or [replaced] where it is one, says the index under the constraint's name was, for the line that says it was dropped. */
    private fun formText(form: ItemForm, replaced: ItemForm.Mismatch?): String = when {
        replaced != null -> "an index Drivine didn't make (${replaced.reason})"
        form is ItemForm.Stale -> "of ${form.found}"
        form is ItemForm.Newer -> "of form ${form.found}, a newer version's"
        form is ItemForm.Mismatch -> "an index Drivine didn't make (${form.reason})"
        else -> "of another form"
    }

    /**
     * Where [rows] show a recreate of [spec] cut off after its old index was dropped, only the index it built first
     * standing, of the constraint's shape ([SqlStoreSchema.cutOff]): it is put in its place ([UniqueRecreation.finish]),
     * said at `INFO`, and true is given; false where they don't.
     */
    private fun finishCutOff(spec: ConstraintSpec, recreation: UniqueRecreation, rows: List<Any?>): Boolean {
        val schema = store as? SqlStoreSchema ?: return false
        if (!schema.cutOff(spec, rows, recreation.name, recreation.temporary)) return false
        ddl(recreation.finish)
        log(
            LogLevel.INFO, CONSTRAINT_MANAGER,
            "Put $engine index ${recreation.temporary} in the place of ${recreation.name} for ${spec.kind} on ${spec.label}${spec.properties}, finishing a recreate that was cut off",
        )
        return true
    }

    /**
     * Fills in again the lowercased copies [spec] is made over, where it ignores case, as [fillIn] does them all; the
     * store's error where the fill fails, null where it ran.
     */
    private fun refill(spec: ConstraintSpec): StoreError? {
        val properties = spec.lowercased.ifEmpty { return null }
        return (io.run(PlanAction.FillLowerCopies(spec.label, properties)) as? PlanOutcome.Failed)?.error
    }

    /** The violation a refill the old index refused is, where the data, its keys lowercased, confirms one ([StoreSchema.sampleRefilledConflictsQuery]). */
    private fun refilledViolation(spec: ConstraintSpec, limit: Int): EnsureResult.Violation? {
        val outcome = io.ask(PlanAction.Query(store.sampleRefilledConflictsQuery(spec, maxOf(limit, 1)), purpose = Purpose.SAMPLE))
        val rows = (outcome as? PlanOutcome.Rows)?.rows ?: return null
        val repeated = rows.filterIsInstance<Map<*, *>>().map { row -> row.entries.associate { (key, value) -> key.toString() to value } }
        return repeated.takeIf { it.isNotEmpty() }?.let { violation(spec.made(), it, limit) }
    }

    /**
     * The constraint's own `create` once its unique index is in place, which makes what goes with it and records it where
     * no record is, and then its record made this version's form's as [recreation]'s index, its aliases kept. Data that
     * refuses the `create`, where the old index was dropped first, is a violation where the data confirms it.
     */
    private fun remake(spec: ConstraintSpec, recreation: UniqueRecreation, previous: SchemaItemInfo?, violationSampleSize: Int): EnsureResult {
        try {
            ddl(store.ddl.createConstraint(spec))
        } catch (refused: RawFailure) {
            if (store.isViolation(refused.error)) return confirmedViolation(spec, violationSampleSize) ?: throw refused
            throw refused
        }
        updateRecord(recreation.remade)
        val created = findConstraint(spec) ?: SchemaItemInfo.fromSpec(spec)
        log(LogLevel.INFO, CONSTRAINT_MANAGER, "Created $engine ${spec.kind} on ${spec.label}${spec.properties}")
        return EnsureResult.Recreated(previous, created)
    }

    /**
     * FalkorDB returns from a create before the constraint is built, which then ends `OPERATIONAL` or `FAILED`. A failed
     * one is dropped, enforcing nothing while it is listed; it is a violation where the data confirms one, and thrown
     * where it doesn't.
     */
    private fun awaitAsyncCreation(spec: ConstraintSpec, violationSampleSize: Int): EnsureResult {
        val info = awaitBuild(spec, untilListed = true)!!
        if (info.buildFailed) {
            dropFailedBuild(spec, info)
            val failed = "Constraint on ${spec.label}${spec.properties} failed to build on $engine, but no key it covers repeats in the data"
            return confirmedViolation(spec, violationSampleSize) ?: throw OwnerFailure(failed)
        }
        log(LogLevel.INFO, CONSTRAINT_MANAGER, "Created $engine ${spec.kind} on ${spec.label}${spec.properties}")
        return EnsureResult.Created(info)
    }

    /** Drops [failed], whose build failed: another process may have dropped it first, which is no error once it is no longer listed failed. */
    private fun dropFailedBuild(spec: ConstraintSpec, failed: SchemaItemInfo) {
        try {
            ddl(store.ddl.dropConstraint(failed.catalogItem))
        } catch (refused: RawFailure) {
            if (findConstraint(spec)?.buildFailed == true) throw refused
        }
    }

    /**
     * Polls the constraint [spec] is until its build has ended, and gives it as last listed. While it isn't listed,
     * polling goes on where [untilListed] (one just made), and otherwise ends with null (one dropped meanwhile). drivine4j
     * sleeps, then looks at its clock: the poll after a sleep that would end past the deadline isn't run.
     */
    private fun awaitBuild(spec: ConstraintSpec, untilListed: Boolean): SchemaItemInfo? {
        val deadline = io.clock() + ASYNC_TIMEOUT_MILLIS
        var poll = 0L
        while (io.clock() + poll < deadline) {
            val info = findConstraint(spec, poll)
            if (info == null && !untilListed) return null
            if (info != null && !info.buildInProgress) return info
            poll = POLL_MILLIS
        }
        throw OwnerFailure("Timed out after ${javaDuration(ASYNC_TIMEOUT_MILLIS)} waiting for constraint on ${spec.label}${spec.properties} to become operational")
    }

    /** The violation a refusal of [spec] is suspected of, where the data confirms it: null where no key repeats, or the data can't be read. */
    private fun confirmedViolation(spec: ConstraintSpec, limit: Int): EnsureResult.Violation? =
        repeatedKeys(spec, limit)?.takeIf { it.isNotEmpty() }?.let { violation(spec, it, limit) }

    /** The keys of [spec] that repeat in the data, [limit] of them at most and one at least; null where the data can't be read. */
    private fun repeatedKeys(spec: ConstraintSpec, limit: Int): List<Map<String, Any?>>? {
        val outcome = io.ask(PlanAction.Query(store.sampleConflictsQuery(spec, maxOf(limit, 1)), purpose = Purpose.SAMPLE))
        val rows = (outcome as? PlanOutcome.Rows)?.rows ?: return null
        return rows.filterIsInstance<Map<*, *>>().map { row -> row.entries.associate { (key, value) -> key.toString() to value } }
    }

    private fun violation(spec: ConstraintSpec, repeated: List<Map<String, Any?>>, limit: Int): EnsureResult.Violation {
        log(LogLevel.WARN, CONSTRAINT_MANAGER, "Could not create constraint on ${spec.label}${spec.properties}: existing data violates it")
        return EnsureResult.Violation(spec, repeated.take(limit.coerceAtLeast(0)))
    }

    /** The plain range index over [spec]'s label and properties, in its order, that the store said is in the way of it. */
    private fun blockingIndex(spec: ConstraintSpec): SchemaItemInfo? {
        val kinds = listOfNotNull(
            SchemaItemKind.VECTOR_INDEX,
            SchemaItemKind.RANGE_INDEX,
            SchemaItemKind.FULLTEXT_INDEX,
            SchemaItemKind.TEXT_INDEX.takeIf { store.supportsTextIndex },
        )
        return kinds.map { store.ddl.listIndexesQuery(it) }.distinct()
            .flatMap { statement -> listingRows(statement)?.let { store.parseIndexRows(it) }.orEmpty() }
            .distinct()
            .firstOrNull { it.kind == SchemaItemKind.RANGE_INDEX && it.label == spec.label && it.properties == spec.properties }
    }

    // ----- Forms -----

    /**
     * What the listing [spec] was last found in says of its form ([StoreSchema.formOf]): read from the rows a find has just
     * read, and never read again for it; [ItemForm.Current] where that read failed.
     */
    private fun formOf(spec: SchemaItemSpec): ItemForm {
        val statement = when (spec) {
            is IndexSpec -> store.ddl.listIndexesQuery(spec.kind)
            is ConstraintSpec -> store.ddl.listConstraintsQuery()
        }
        return listings[statement]?.let { store.formOf(spec, it) } ?: ItemForm.Current
    }

    /**
     * [spec]'s item, found under one of its names, isn't it ([ItemForm.Mismatch]): drift, as an item of another shape is
     * on any store, which fails the owner under `FAIL_FAST`, is logged under `WARN`, and is recreated with
     * `recreateOnDrift` where what stands under the name is an index. [existing] is the record found for it, where there is
     * one; the item reported is the name's otherwise.
     */
    private fun mismatched(spec: SchemaItemSpec, existing: SchemaItemInfo?, form: ItemForm.Mismatch): EnsureResult.Drift {
        val (logger, what) = if (spec is ConstraintSpec) CONSTRAINT_MANAGER to "Constraint" else INDEX_MANAGER to "Index"
        val then = if (form.droppable) "Call recreate() to replace it (destructive)." else "Drop or rename it: Drivine makes the item under that name, and recreate() leaves it."
        val stranger = if (form.droppable) "an index Drivine didn't make" else "an object Drivine didn't make"
        if (mismatchesSaid.add(form.name.uppercase())) {
            log(
                LogLevel.WARN, logger,
                "$what drift on ${spec.label}${spec.properties}: ${form.name}, $stranger, stands under its name for the ${spec.kind}: it is ${form.reason}, so it is not taken as made. $then",
            )
        }
        return EnsureResult.Drift(existing ?: SchemaItemInfo(spec.kind, spec.label, spec.properties, form.name), spec)
    }

    /**
     * Before [spec] is made again, [found] as its find found it: each index that stands under one of its names and isn't
     * it is dropped, by its name, and the item found again by [find], till none is left, which is given; drift, with
     * nothing more dropped, where what stands there isn't an index, and so can't be made over.
     */
    private fun clearMismatched(spec: SchemaItemSpec, found: SchemaItemInfo?, find: () -> SchemaItemInfo?): Cleared {
        val dropped = mutableSetOf<String>()
        var existing = found
        while (true) {
            val form = formOf(spec) as? ItemForm.Mismatch ?: return Cleared(existing, null)
            // One dropped already, and still there, can't be dropped.
            if (!form.droppable || !dropped.add(form.name.uppercase())) return Cleared(existing, mismatched(spec, existing, form))
            ddl(store.dropMismatched(form.name))
            log(LogLevel.INFO, INDEX_MANAGER, droppedMismatch(spec, form))
            existing = find()
        }
    }

    /** What is said of [form]'s index, dropped by its name before [spec] is made in its place. */
    private fun droppedMismatch(spec: SchemaItemSpec, form: ItemForm.Mismatch): String =
        "Dropped $engine index ${form.name}, an index Drivine didn't make under its name for ${spec.kind} on ${spec.label}${spec.properties}: it was ${form.reason}"

    /**
     * Before the index [spec], of which no record is listed, is made again: the objects under its names that were read as
     * another library's form, and decided it, are dropped by their names ([StoreSchema.unrecordedDrops]), so the item is
     * made in this version's form, and not recorded as made over the other's. Said as a mismatch's drop is. A uniqueness
     * constraint's are dropped as it is made in its place ([recreateInPlace]).
     */
    private fun dropUnrecorded(spec: IndexSpec) {
        val rows = listings[store.ddl.listIndexesQuery(spec.kind)] ?: return
        if (formOf(spec) !is ItemForm.Stale) return
        for (drop in store.unrecordedDrops(spec, rows)) {
            ddl(drop.statements)
            log(LogLevel.INFO, INDEX_MANAGER, droppedUnrecorded(spec, drop, rows))
        }
    }

    /** What is said of [drop], of another library's form under a name of [spec]'s with no record of it, dropped to make [spec] in this version's form. */
    private fun droppedUnrecorded(spec: SchemaItemSpec, drop: ObjectDrop, rows: List<Any?>): String {
        val found = (store.formOf(spec, rows) as? ItemForm.Stale)?.found
        return "Dropped $engine ${drop.what} ${drop.name}, of $found under its name for ${spec.kind} on ${spec.label}${spec.properties}, which no record named, to make it in this version's form"
    }

    /** What [clearMismatched] left: the item found once nothing else stands under its names, or the [drift] where something can't be dropped. */
    private class Cleared(val existing: SchemaItemInfo?, val drift: EnsureResult.Drift?)

    /**
     * An item found in its shape, in [form]: one of another form is kept and used as it is, and said so, once; one in this
     * version's form whose record says none has its form recorded.
     */
    private fun keepForm(spec: SchemaItemSpec, form: ItemForm) {
        if (form is ItemForm.Newer) {
            // Nothing to make again: this version would make it in an older form.
            log(
                LogLevel.WARN, if (spec is ConstraintSpec) CONSTRAINT_MANAGER else INDEX_MANAGER,
                "$engine ${spec.kind} on ${spec.label}${spec.properties} was made by a newer version of Drivine " +
                    "(form ${form.found}; this version makes form ${form.current}): this version uses it as it is.",
            )
        }
        if (form is ItemForm.Stale) {
            log(
                LogLevel.WARN, if (spec is ConstraintSpec) CONSTRAINT_MANAGER else INDEX_MANAGER,
                "$engine ${spec.kind} on ${spec.label}${spec.properties} is of another form than this version makes " +
                    "(form ${form.expected} expected, found ${form.found}): it is kept and used as it is. $remake",
            )
        }
        adoptForm(form)
    }

    /**
     * An item of another form whose objects stand under its names with no record of it: kept and used as it is, and said
     * so, as one recorded is; nothing is made, and no record is written, which would say it is this version's.
     */
    private fun keptUnrecorded(spec: SchemaItemSpec, form: ItemForm.Stale): EnsureResult {
        keepForm(spec, form)
        return EnsureResult.AlreadyMatching(SchemaItemInfo.fromSpec(spec))
    }

    /** Records the form of an item made in this version's form whose record says none ([ItemForm.Unrecorded]). */
    private fun adoptForm(form: ItemForm) {
        if (form is ItemForm.Unrecorded) updateRecord(form.statement)
    }

    // ----- Reads and DDL -----

    private fun listing(statement: String) = PlanAction.Query(statement, purpose = Purpose.LISTING)

    /**
     * The rows of the listing [statement]: as read since the plan's own DDL last ran, or read now; read now, after
     * waiting [poll], where it is given (a poll). Null where the read fails, which drivine4j takes as nothing listed:
     * some stores refuse to list a kind before there is one. A read that failed is used once, as the read it is, and not
     * kept: drivine4j reads the listing again next time.
     */
    private fun listingRows(statement: String, poll: Long? = null): List<Any?>? {
        if (poll == null && statement in listings) return listings.getValue(statement) ?: listings.remove(statement)
        val outcome = io.ask(PlanAction.Query(statement, purpose = Purpose.LISTING, delayMillis = poll ?: 0))
        return ((outcome as? PlanOutcome.Rows)?.rows).also { if (it != null) listings[statement] = it else listings.remove(statement) }
    }

    private fun indexes(kind: SchemaItemKind, poll: Long? = null): List<SchemaItemInfo> =
        listingRows(store.ddl.listIndexesQuery(kind), poll)?.let { store.parseIndexRows(it) }.orEmpty()

    private fun constraints(poll: Long? = null): List<SchemaItemInfo> =
        listingRows(store.ddl.listConstraintsQuery(), poll)?.let { store.parseConstraintRows(it) }.orEmpty()

    /** Runs [statements], DDL, in order, stopping at the first the store refuses; the listings read before are read again after, whatever came of it. */
    private fun ddl(statements: List<DdlStatement>) {
        try {
            for (statement in statements) {
                val action = PlanAction.Execute(statement, purpose = Purpose.DDL)
                val outcome = io.run(action)
                if (outcome is PlanOutcome.Failed) throw RawFailure(outcome.error, action, io.lastRound)
            }
        } finally {
            listings.clear()
        }
    }

    private fun log(level: LogLevel, logger: String, message: String) {
        log += LogLine(level, logger, message)
    }

    /** The message of [error] as drivine4j's error gives it: its outermost's, or [none] where it has none. */
    private fun messageOf(error: StoreError, none: String = "An unknown error occurred"): String = error.causes.firstOrNull()?.message ?: none

    private companion object {
        const val SCHEMA_MANAGER = "SchemaManager"
        const val INDEX_MANAGER = "IndexManager"
        const val CONSTRAINT_MANAGER = "ConstraintManager"

        /** How often a store that builds and drops in the background is polled, as drivine4j polls it. */
        const val POLL_MILLIS = 200L

        /** How long a drop, or a constraint's build, in the background is waited for, as drivine4j waits. */
        const val ASYNC_TIMEOUT_MILLIS = 30_000L

        /** The sample size drivine4j's marker constraint is made with: `ConstraintManager.DEFAULT_VIOLATION_SAMPLE_SIZE`. */
        const val DEFAULT_VIOLATION_SAMPLE_SIZE = 10
    }
}

/**
 * [millis] as Java's `Duration.toString` writes it, as drivine4j's messages name a wait: `PT1M`, `PT30S`, `PT0.5S`,
 * `PT0S`.
 */
internal fun javaDuration(millis: Long): String {
    if (millis == 0L) return "PT0S"
    val seconds = millis / 1000
    val fraction = millis % 1000
    val hours = seconds / 3600
    val minutes = (seconds % 3600) / 60
    val secs = seconds % 60
    val text = StringBuilder("PT")
    if (hours != 0L) text.append(hours).append('H')
    if (minutes != 0L) text.append(minutes).append('M')
    if (secs == 0L && fraction == 0L && text.length > 2) return text.toString()
    text.append(secs)
    if (fraction > 0) text.append('.').append(fraction.toString().padStart(3, '0').trimEnd('0'))
    return text.append('S').toString()
}
