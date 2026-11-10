package org.drivine.cypher.store.plan

import org.drivine.cypher.CompilerException
import org.drivine.cypher.store.ddl.DdlStatement
import org.drivine.cypher.store.ddl.FalkorDbDdl
import org.drivine.cypher.store.ddl.SqlStoreDdl
import org.drivine.cypher.store.ddl.unsupportedTextIndex
import org.drivine.cypher.store.marker.MarkerInventory
import org.drivine.cypher.store.marker.MarkerStatements
import org.drivine.cypher.store.marker.SchemaMarker
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

    /** The listings read since the plan's own DDL last ran, by statement: their rows, or null where the read failed. */
    private val listings = mutableMapOf<String, List<Any?>?>()

    /** The markers' properties read, by scope and property: the outcome of each read. */
    private val markerReads = mutableMapOf<Pair<String, String>, PlanOutcome>()

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

    private fun prefetchMarker(scope: String, property: String) {
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
        val run = OwnerRun(owner)
        return try {
            run.removal = removeItems(run, operation)
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
            is PlanOutcome.Failed -> throw OwnerFailure(messageOf(outcome.error))
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

    /** Writes the scope's marker, once again where the first merge fails: it lost a race to make the marker, which a second merge finds. */
    private fun record(scope: String, version: String?, inventory: MarkerInventory): WrittenMarker {
        val items = inventory.items
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
            ddl(store.ddl.createIndex(spec, existing.catalogItem))
            val completed = awaitBuilt(spec, findIndex(spec), build) ?: SchemaItemInfo.fromSpec(spec)
            log(LogLevel.INFO, INDEX_MANAGER, "Completed $engine ${spec.kind} on ${spec.label}${spec.properties}: it lacked part of what is made for it")
            return EnsureResult.Created(completed)
        }
        return if (store.matchesShape(existing, spec)) {
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
        val existing = findIndex(spec)
        val aliases = if (existing != null) aliasesOfIndex(spec) else emptyList()
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
        val action = PlanAction.Execute(DdlStatement.Text(ddl.aliased(item.catalogItem, aliases)), purpose = Purpose.ALIASES)
        val outcome = io.run(action)
        // What a later read lists has changed.
        listings.clear()
        if (outcome is PlanOutcome.Failed) throw RawFailure(outcome.error, action, io.lastRound)
    }

    /** Fills in the lowercased copies [spec] is made over, where it ignores case; [again], asked only then, where they are filled in whether or not recorded. */
    private fun fillIn(spec: SchemaItemSpec, again: () -> Boolean) {
        val properties = spec.lowercased.ifEmpty { return }
        val outcome = io.run(PlanAction.FillLowerCopies(spec.label, properties, again()))
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
        if (existing != null && existing.buildFailed) {
            // FalkorDB keeps listing a constraint whose build failed, though it enforces nothing: dropped, and built again,
            // which gives a violation where the data still repeats a key.
            dropFailedBuild(spec, existing)
            log(LogLevel.WARN, CONSTRAINT_MANAGER, "Dropped $engine ${spec.kind} on ${spec.label}${spec.properties}, whose build had failed, to build it again")
        } else if (existing != null) {
            return if (store.matchesShape(existing, spec)) {
                EnsureResult.AlreadyMatching(existing)
            } else {
                log(LogLevel.WARN, CONSTRAINT_MANAGER, "Constraint drift on ${spec.label}${spec.properties}: existing $existing does not match requested $spec")
                EnsureResult.Drift(existing, spec)
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

    /** `ConstraintManager.recreate`: the constraint made again keeps the aliases its record kept. */
    private fun recreateConstraint(spec: ConstraintSpec, violationSampleSize: Int, replaceBlockingIndex: Boolean): EnsureResult {
        requireConstrainable(spec.made())
        val existing = findConstraint(spec)
        val aliases = if (existing != null && store.ddl is SqlStoreDdl) {
            val rows = listingRows(store.ddl.listConstraintsQuery()).orEmpty()
            rows.firstNotNullOfOrNull { row ->
                store.parseConstraintRows(listOf(row)).firstOrNull { store.matchesIdentity(it, spec.made()) }?.let { store.aliasesOf(row) }
            }.orEmpty()
        } else {
            emptyList()
        }
        if (existing != null) ddl(store.ddl.dropConstraint(existing.catalogItem))
        // The copies are filled in again too, where a write that bypassed Drivine left one out of step.
        fillIn(spec) { true }
        return when (val result = ensureMadeConstraint(spec.made(), violationSampleSize, replaceBlockingIndex)) {
            is EnsureResult.Created -> {
                if (aliases.isNotEmpty()) alias(result.info, aliases)
                EnsureResult.Recreated(existing, result.info)
            }
            else -> result
        }
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

    // ----- Reads and DDL -----

    private fun listing(statement: String) = PlanAction.Query(statement, purpose = Purpose.LISTING)

    /**
     * The rows of the listing [statement]: as read since the plan's own DDL last ran, or read now; read now, after
     * waiting [poll], where it is given (a poll). Null where the read fails, which drivine4j takes as nothing listed:
     * some stores refuse to list a kind before there is one.
     */
    private fun listingRows(statement: String, poll: Long? = null): List<Any?>? {
        if (poll == null && statement in listings) return listings.getValue(statement)
        val outcome = io.ask(PlanAction.Query(statement, purpose = Purpose.LISTING, delayMillis = poll ?: 0))
        return ((outcome as? PlanOutcome.Rows)?.rows).also { listings[statement] = it }
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
