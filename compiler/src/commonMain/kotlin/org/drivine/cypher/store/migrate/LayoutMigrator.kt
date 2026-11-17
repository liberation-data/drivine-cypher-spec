package org.drivine.cypher.store.migrate

import org.drivine.DrivineInternalApi
import org.drivine.cypher.json.ArrayNode
import org.drivine.cypher.json.CypherJson
import org.drivine.cypher.json.JsonNode
import org.drivine.cypher.json.ObjectNode
import org.drivine.cypher.json.Sha256
import org.drivine.cypher.store.ddl.SqlStoreDdl
import org.drivine.cypher.store.marker.StoreLayout
import org.drivine.cypher.store.marker.StoreStamp
import org.drivine.cypher.store.marker.StoreStampStatements
import org.drivine.cypher.store.migrate.MigrationStatements.Table
import org.drivine.cypher.store.plan.PlanJson
import org.drivine.cypher.store.schema.StoreSchema
import org.drivine.schema.UniquenessConstraintSpec

/**
 * A graph migrated from store layout 1 to layout 2 (liberation-data/drivine-cypher-spec#256), as a planner with no store: it
 * decides what to run, round by round, and the library runs each round and gives back what came of it, as the schema
 * planner's rounds run ([org.drivine.cypher.store.plan.SchemaPlanner]). Every library migrates alike, as `migrate.yaml`'s
 * runs pin it.
 *
 * A migration runs while nothing else uses the graph, and in these steps:
 * 1. **Judge.** The stamps, whether the graph holds values, the objects the store lists, Drivine's own nodes and the size
 *    of the graph are read. A graph [StoreLayout.verdict] refuses as holding an earlier layout's values is migrated; one
 *    with a checkpoint ([SqlStoreDdl.migrate]) is resumed; any other is done with, as current, empty or refused.
 * 2. **Scan.** Every node, then every relationship, is read in batches of the request's size, by its id, and rewritten
 *    ([LayoutMigration.rewrite]) with nothing written: the report counts what would change, lists every value not of its
 *    declared kind, and names the properties whose values all read as one kind, the candidates to declare. A graph with a
 *    value not of its kind is refused here, unless the request keeps such values. A dry run goes on to the check (5) over
 *    layout 1's values, of possible collisions, and ends there, read-only.
 * 3. **Begin.** The checkpoint table is made, first, so a graph stopped at any later moment is refused as part way through
 *    a migration; then the store's tables as layout 2 makes them (the library's `prepare`, run as at a first connection,
 *    without the verdict), and the lock a schema change takes is taken and held to the end. The checkpoint table is then
 *    made again and read under the lock, with the stamps: a checkpoint already there is another migration's, which is
 *    resumed, not begun again; a stamp of layout 2 and no checkpoint, beginning or resuming, is another's that finished
 *    meanwhile, and the empty checkpoint table is dropped.
 * 4. **Write.** Every node, then every relationship, is read again in batches after the checkpoint's id, and each batch's
 *    rewritten rows, the values it keeps, and the checkpoint after its last id are written in one transaction: so a row is
 *    rewritten exactly once, as a rewrite is not to be run again on a row it wrote (it would escape a tag's `$t`), and a
 *    migration stopped at any moment resumes strictly after the last batch it committed.
 * 5. **Check.** Each uniqueness constraint the catalog records is checked for keys that repeat in layout 2, by the schema
 *    plan's own sample of a constraint's conflicts, the store's SQL: a graph with any ends `collisions`, its checkpoint kept, for
 *    the user to correct with the store's own tools and resume.
 * 6. **Finish.** The earlier layout's objects are dropped with their records (the records first), the stamp given layout 2 (or assigned), the
 *    checkpoint table dropped last, and the graph judged again: it must be current. No index is made, as the application's
 *    schema plan makes its declared ones at its next start, and the count store is left empty, as a fresh store's; every
 *    report after a scan says both.
 *
 * What the checkpoint holds: where the write is, the digest of the declarations, whether it keeps values not of their kind,
 * and the fingerprint of the graph as the migration found it (Drivine's own nodes and its size, none of which a rewrite
 * changes). A resume reads the graph's again,
 * and refuses a graph whose fingerprint differs, a graph changed under the migration, and declarations, or an option to keep
 * values not of their kind, that differ from those it began with, until every row is written: from then on what is left
 * reads the graph as it is, a row corrected after a collision, or a stamp given layout 2 before the checkpoint was dropped.
 *
 * The planner keeps nothing between rounds but [MigrationState], which crosses any boundary as JSON.
 */
@DrivineInternalApi
class LayoutMigrator(private val ddl: SqlStoreDdl) {

    private val statements = MigrationStatements.of(ddl)
    private val stamps = StoreStampStatements.of(ddl)
    private val engine = StoreStamp.engine(ddl)

    /** Why [request] can't be run: see [LayoutMigration.refusal], and a batch of no rows. Null where it can. */
    fun validate(request: MigrationRequest): String? =
        if (request.batchSize < 1) "A migration reads its rows in batches of 1 or more, not ${request.batchSize}." else LayoutMigration.refusal(request.declarations)

    /** The first round of [request]. */
    fun start(request: MigrationRequest): MigrationStep {
        validate(request)?.let { throw IllegalArgumentException(it) }
        val state = MigrationState(request, Phase.JUDGE, Table.NODES, 0, null, MigrationReport())
        return run(
            state,
            MigrationAction.Query(stamps.readStamps!!, purpose = "stamps"),
            MigrationAction.Query(stamps.foreignNodes!!, purpose = "foreignNodes"),
            MigrationAction.Query(stamps.layoutObjects!!, purpose = "objects"),
            MigrationAction.Query(statements.own, purpose = "own"),
            MigrationAction.Query(statements.sizes, purpose = "sizes"),
        )
    }

    /**
     * The next round, after the last round of [state] gave [outcomes], one for each of its actions that ran, in order: all of
     * them, or those up to and with the first that failed, which ends the migration with the store's error.
     */
    fun resume(state: MigrationState, outcomes: List<MigrationOutcome>): MigrationStep {
        outcomes.firstOrNull { it is MigrationOutcome.Failed }?.let { failed ->
            return MigrationStep.Done(state.report.ended(Status.FAILED, (failed as MigrationOutcome.Failed).message))
        }
        return when (state.phase) {
            Phase.JUDGE -> judged(state, outcomes)
            Phase.RESUME -> resumed(state, rows(outcomes[outcomes.size - 2]), rows(outcomes.last()))
            Phase.SCAN -> scanned(state, rows(outcomes.last()))
            Phase.BEGIN -> begun(state, rows(outcomes[outcomes.size - 2]), rows(outcomes.last()))
            Phase.ABANDON -> MigrationStep.Done(state.report.ended(Status.REFUSED, FINISHED_MEANWHILE.replace("{graph}", ddl.name)))
            Phase.WRITE -> written(state, rows(outcomes.last()))
            Phase.CHECK_LIST -> checkList(state, rows(outcomes.last()))
            Phase.CHECK -> checked(state, outcomes)
            Phase.FINISH_READ -> finishRead(state, outcomes)
            Phase.FINISH -> finished(state, outcomes)
        }
    }

    private fun run(state: MigrationState, vararg actions: MigrationAction) = MigrationStep.Run(actions.toList(), state)

    private fun rows(outcome: MigrationOutcome): List<Any?> =
        (outcome as? MigrationOutcome.Rows)?.rows ?: throw IllegalArgumentException("A query is answered with its rows, not $outcome.")

    private fun judged(state: MigrationState, outcomes: List<MigrationOutcome>): MigrationStep {
        require(outcomes.size == 5) { "The first round gives back 5 outcomes, not ${outcomes.size}." }
        val stampRows = rows(outcomes[0]).map { map(it) }
        val foreign = rows(outcomes[1]).isNotEmpty()
        val objects = rows(outcomes[2]).map { map(it) }
        val fingerprint = fingerprint(rows(outcomes[3]), rows(outcomes[4]))
        val judged = state.copy(fingerprint = fingerprint)
        val verdict = StoreLayout.verdict(engine, ddl.name, stampRows, foreign, objects, ddl)
        // A checkpoint is resumed, but a graph a later Drivine wrote, or two stamped at once, is refused first, as the verdict's rows order them.
        if (verdict is StoreLayout.Verdict.Refuse && verdict.inProgress) {
            if (state.request.dryRun) return MigrationStep.Done(state.report.ended(Status.IN_PROGRESS, IN_PROGRESS))
            // Prepared again, as a migration stopped once its checkpoint table was made may not have made the lock table yet.
            return run(judged.copy(phase = Phase.RESUME, locked = true), MigrationAction.Prepare, *underLock())
        }
        return when (verdict) {
            StoreLayout.Verdict.Current -> MigrationStep.Done(state.report.ended(Status.CURRENT, "The graph is in store layout ${LayoutMigration.TO}: there is nothing to migrate."))
            // A graph of this layout whose stamp a clear deleted (the verdict's row 12) holds values, of this layout.
            is StoreLayout.Verdict.Stamp ->
                if (verdict.assign) MigrationStep.Done(state.report.ended(Status.CURRENT,
                    "The graph holds values of store layout ${LayoutMigration.TO}, its stamp gone: a first connection stamps it again, with nothing to migrate."))
                else MigrationStep.Done(state.report.ended(Status.EMPTY, "The graph holds no values: a first connection stamps it layout ${LayoutMigration.TO}, with nothing to migrate."))
            is StoreLayout.Verdict.Refuse ->
                if (verdict.from == LayoutMigration.FROM) scan(judged.copy(phase = Phase.SCAN), Table.NODES, 0)
                else MigrationStep.Done(state.report.ended(Status.REFUSED, verdict.message))
        }
    }

    /** The checkpoint read under the lock, as a migration part way through is resumed. */
    private fun resumed(state: MigrationState, rows: List<Any?>, stampRows: List<Any?>): MigrationStep {
        val checkpoint = checkpointOf(rows)
            // Made, and nothing written: a migration stopped as it began, which begins again from its scan; or one that made
            // its table, or waited on the lock, as another finished, whose empty table is dropped.
            ?: return if (finishedMeanwhile(stampRows)) abandon(state) else scan(state.copy(phase = Phase.SCAN), Table.NODES, 0)
        refusalToResume(state, checkpoint)?.let { return MigrationStep.Done(state.report.ended(Status.REFUSED, it)) }
        val at = state.copy(phase = Phase.WRITE, table = checkpoint.table, after = checkpoint.after, report = checkpoint.report)
        return if (checkpoint.table == null) checkFor(at) else run(at, read(checkpoint.table, checkpoint.after, state.request.batchSize))
    }

    private fun refusalToResume(state: MigrationState, checkpoint: Checkpoint): String? = when {
        // Once every row is written, what is left (the collision check, the drops, the stamp) reads the graph as it then is:
        // a user who corrected colliding rows, or a stamp moved before the checkpoint was dropped, changes no value to rewrite.
        checkpoint.table == null -> null
        checkpoint.fingerprint != state.fingerprint ->
            "The graph '${ddl.name}' has changed since its migration to store layout ${LayoutMigration.TO} began: its stamp, its schema marker or its size is not as the migration found it. Restore it from the backup taken before the migration."
        checkpoint.declarations != digest(state.request.declarations) ->
            "The migration of the graph '${ddl.name}' to store layout ${LayoutMigration.TO} began with other declarations: resume it with the same ones, as it rewrote values by them."
        checkpoint.keepInvalid != state.request.keepInvalid ->
            "The migration of the graph '${ddl.name}' to store layout ${LayoutMigration.TO} began ${if (checkpoint.keepInvalid) "with" else "without"} the option to keep invalid values as text: " +
                "resume it ${if (checkpoint.keepInvalid) "with" else "without"} it too, as the scan before its writes found the graph's values by it."
        else -> null
    }

    private fun scan(state: MigrationState, table: Table, after: Long) =
        run(state.copy(table = table, after = after), read(table, after, state.request.batchSize))

    private fun read(table: Table, after: Long, limit: Int) =
        MigrationAction.Query(statements.readBatch(table), linkedMapOf("after" to after, "limit" to limit), purpose = "batch")

    private fun scanned(state: MigrationState, rows: List<Any?>): MigrationStep {
        val table = state.table!!
        var report = state.report
        var last = state.after
        for (row in rows.map { it.toRow() }) {
            report = report.scanned(table, row, LayoutMigration.rewrite(row.props, statements.store, row.labels, state.request.declarations),
                LayoutMigration.observe(row.props, statements.store), state.request.declarations)
            last = row.id
        }
        val next = state.copy(report = report, after = last)
        if (rows.size >= state.request.batchSize) return scan(next, table, last)
        if (table == Table.NODES) return scan(next, Table.EDGES, 0)
        val scannedAll = next.report.withCandidates(state.request.declarations)
        return when {
            scannedAll.invalidCount > 0 && !state.request.keepInvalid -> MigrationStep.Done(scannedAll.ended(Status.INVALID, invalidMessage(scannedAll)))
            // A dry run checks the uniqueness constraints over the values as layout 1 holds them: possible collisions.
            state.request.dryRun -> checkFor(next.copy(report = scannedAll))
            else -> run(
                next.copy(phase = Phase.BEGIN, table = Table.NODES, after = 0, report = scannedAll.copy(tables = emptyMap()), locked = true),
                // The checkpoint table first, so a graph stopped at any later action is refused as part way through a migration
                // (the verdict's row 4), never read by this layout's lock table, which Prepare makes, as one of this layout whose stamp is gone.
                MigrationAction.Execute(statements.createCheckpoint, purpose = "checkpoint"),
                MigrationAction.Prepare,
                // A migration begun again from its scan, its checkpoint table made and nothing written, holds the lock already.
                *underLock(held = state.locked),
            )
        }
    }

    /** The lock's action, where the store takes one and the migration doesn't hold it already ([held]), before [actions]. */
    private fun lockAnd(vararg actions: MigrationAction, held: Boolean = false): Array<MigrationAction> =
        (listOfNotNull(statements.lock?.takeUnless { held }?.let { MigrationAction.Lock(it) }) + actions).toTypedArray()

    /**
     * The lock, and under it the checkpoint table made again and read, with the stamps: a migration that waited on the lock
     * while another finished finds that one's table dropped, and makes its own to read, empty, beside a stamp of this layout.
     */
    private fun underLock(held: Boolean = false) = lockAnd(
        MigrationAction.Execute(statements.createCheckpoint, purpose = "checkpoint"),
        MigrationAction.Query(statements.readCheckpoint, purpose = "checkpoint"),
        MigrationAction.Query(stamps.readStamps!!, purpose = "stamps"),
        held = held,
    )

    /** Whether [stampRows], read under the lock with no checkpoint, are another migration's that finished: of this layout. */
    private fun finishedMeanwhile(stampRows: List<Any?>) = stampRows.any { layoutOf(map(it)["layout"]) == LayoutMigration.TO }

    /** The empty checkpoint table dropped, of a migration another finished before it: see [finishedMeanwhile]. */
    private fun abandon(state: MigrationState) =
        run(state.copy(phase = Phase.ABANDON), MigrationAction.Execute(statements.dropCheckpoint, purpose = "checkpoint"))

    private fun begun(state: MigrationState, rows: List<Any?>, stampRows: List<Any?>): MigrationStep {
        // Read under the lock: another migration's checkpoint is there where two began at once, and it is resumed, not begun again.
        if (checkpointOf(rows) != null) {
            return MigrationStep.Done(state.report.ended(Status.REFUSED,
                "Another migration of the graph '${ddl.name}' to store layout ${LayoutMigration.TO} began meanwhile: resume it, once it has stopped."))
        }
        // Another migration that judged the graph with this one, and finished, dropping its checkpoint, before this one took the
        // lock: the graph is stamped this layout, its values rewritten, and would be rewritten again. The empty checkpoint table
        // this one made is dropped, and the graph left as the other left it.
        if (finishedMeanwhile(stampRows)) return abandon(state)
        val checkpoint = Checkpoint(Table.NODES, 0, state.fingerprint!!, digest(state.request.declarations), state.request.keepInvalid, state.report.copy(tables = emptyMap()))
        return run(state.copy(phase = Phase.WRITE),
            MigrationAction.Transaction(listOf(writeCheckpoint(checkpoint))),
            read(Table.NODES, 0, state.request.batchSize))
    }

    private fun written(state: MigrationState, rows: List<Any?>): MigrationStep {
        val table = state.table!!
        var report = state.report
        var last = state.after
        val writes = mutableListOf<MigrationAction.Execute>()
        for (row in rows.map { it.toRow() }) {
            val done = LayoutMigration.rewrite(row.props, statements.store, row.labels, state.request.declarations)
            if (done.invalid.isNotEmpty() && !state.request.keepInvalid) {
                // A value the scan didn't find: the graph was written to since. Nothing of this batch is written, nor counted.
                return MigrationStep.Done(state.report.ended(Status.INVALID,
                    "The graph '${ddl.name}' holds a value of row ${row.id} not of its declared kind, which the scan before the writes didn't find: it was written to during the migration. Nothing of that row's batch was written: correct it, and resume the migration once nothing else writes to the graph."))
            }
            if (done.changed) writes += MigrationAction.Execute(statements.update(table), linkedMapOf("id" to row.id, "props" to done.props), purpose = "rewrite")
            for (kept in done.invalid) writes += MigrationAction.Execute(statements.writeCheckpoint,
                linkedMapOf("name" to keptName(table, row.id, kept.property), "state" to keptState(table, row, kept)), purpose = "kept")
            report = report.written(table, done.changed)
            last = row.id
        }
        val more = rows.size >= state.request.batchSize
        val nextTable = if (more) table else if (table == Table.NODES) Table.EDGES else null
        val nextAfter = if (more) last else 0L
        val checkpoint = Checkpoint(nextTable, nextAfter, state.fingerprint!!, digest(state.request.declarations), state.request.keepInvalid, report)
        val next = state.copy(table = nextTable, after = nextAfter, report = report)
        val transaction = MigrationAction.Transaction(writes + writeCheckpoint(checkpoint))
        return if (nextTable != null) run(next, transaction, read(nextTable, nextAfter, state.request.batchSize))
        else run(next.copy(phase = Phase.CHECK_LIST), transaction, MigrationAction.Query(statements.uniques, purpose = "uniques"))
    }

    /** The uniqueness constraints' records read, to check each for keys that collide: see [checked]. */
    private fun checkFor(state: MigrationState) = run(state.copy(phase = Phase.CHECK_LIST), MigrationAction.Query(statements.uniques, purpose = "uniques"))

    /**
     * Each uniqueness constraint the catalog records, checked for keys that repeat, by the schema plan's own sample of what a
     * constraint would refuse, the store's SQL, run as the plan runs it: after the writes, over layout 2's values, exactly as the
     * index the application's next schema plan makes would refuse them; in a dry run, over layout 1's, as possible collisions.
     */
    private fun checkList(state: MigrationState, rows: List<Any?>): MigrationStep {
        val uniques = rows.map { map(it) }.map { row ->
            val label = row["label"] as? String ?: throw IllegalArgumentException("A uniqueness record gives its label as a text.")
            val properties = (CypherJson.readTree(row["properties"] as? String ?: throw IllegalArgumentException("A uniqueness record gives its properties as their JSON text.")) as ArrayNode)
                .map { it.textValue()!! }
            listOf(label) + properties
        }.distinct()
        if (uniques.isEmpty()) return afterCheck(state.copy(uniques = emptyList()), emptyList())
        val schema = StoreSchema.of(ddl)
        return run(state.copy(phase = Phase.CHECK, uniques = uniques), *uniques.map { unique ->
            MigrationAction.Query(schema.sampleConflictsQuery(UniquenessConstraintSpec(unique.first(), unique.drop(1)), SAMPLES), purpose = "collisions")
        }.toTypedArray())
    }

    private fun checked(state: MigrationState, outcomes: List<MigrationOutcome>): MigrationStep {
        require(outcomes.size == state.uniques.size) { "A check gives back an outcome for each uniqueness constraint, ${state.uniques.size}, not ${outcomes.size}." }
        val collisions = state.uniques.zip(outcomes).flatMap { (unique, outcome) ->
            rows(outcome).map { MigrationReport.Collision(unique.first(), unique.drop(1), PlanJson.writePlain(it)) }
        }
        return afterCheck(state, collisions)
    }

    private fun afterCheck(state: MigrationState, collisions: List<MigrationReport.Collision>): MigrationStep {
        val report = state.report.copy(collisions = collisions, notes = NOTES)
        if (state.request.dryRun) return MigrationStep.Done(report.ended(Status.DRY_RUN,
            if (collisions.isEmpty()) "A dry run: nothing was written." else "A dry run: nothing was written. Some uniqueness constraints' keys may collide once rewritten, listed as possible collisions: the migration checks them exactly after it writes."))
        if (collisions.isNotEmpty()) return MigrationStep.Done(report.ended(Status.COLLISIONS,
            "The graph '${ddl.name}' holds values a uniqueness constraint refuses in store layout ${LayoutMigration.TO}, listed with samples: every value is rewritten, and the graph keeps its checkpoint, refused as part way through its migration. " +
                "Correct the rows with the store's own tools, keeping their values in layout 2, then resume the migration to finish it."))
        return run(state.copy(phase = Phase.FINISH_READ, report = report),
            MigrationAction.Query(stamps.layoutObjects!!, purpose = "objects"),
            MigrationAction.Query(stamps.readStamps!!, purpose = "stamps"),
            MigrationAction.Query(statements.readCheckpoint, purpose = "checkpoint"),
        )
    }

    /** What the finish drops, stamps and reads again, from the objects, the stamps and the checkpoint's kept values read. */
    private fun finishRead(state: MigrationState, outcomes: List<MigrationOutcome>): MigrationStep {
        require(outcomes.size == 3) { "The finish's first round gives back 3 outcomes, not ${outcomes.size}." }
        val objects = rows(outcomes[0]).map { map(it) }
        val stamped = rows(outcomes[1]).isNotEmpty()
        val kept = keptOf(rows(outcomes[2]))
        // The records first: a finish stopped after an object's drop lists it no more, so begun again would never forget its
        // record, which keeps the application's schema plan from recording the item it makes again (a record is made once).
        val drops = StoreLayout.drops(ddl, objects).partition { it.startsWith("DELETE ") }.let { (records, objects) -> records + objects }
            .map { MigrationAction.Execute(it, purpose = "drop") }
        val stamp = if (stamped) MigrationAction.Execute(statements.stampLayout, purpose = "stamp") else MigrationAction.AssignStamp
        return run(state.copy(phase = Phase.FINISH, report = state.report.copy(kept = kept)),
            *drops.toTypedArray(), stamp,
            MigrationAction.Execute(statements.dropCheckpoint, purpose = "checkpoint"),
            MigrationAction.Query(stamps.readStamps!!, purpose = "stamps"),
            MigrationAction.Query(stamps.foreignNodes!!, purpose = "foreignNodes"),
            MigrationAction.Query(stamps.layoutObjects!!, purpose = "objects"),
        )
    }

    /** The graph judged again, once dropped and stamped: it must be current, an ordinary graph of layout 2. */
    private fun finished(state: MigrationState, outcomes: List<MigrationOutcome>): MigrationStep {
        val (stampRows, foreign, objects) = outcomes.takeLast(3).map { rows(it) }
        return when (val verdict = StoreLayout.verdict(engine, ddl.name, stampRows.map { map(it) }, foreign.isNotEmpty(), objects.map { map(it) }, ddl)) {
            StoreLayout.Verdict.Current -> MigrationStep.Done(state.report.ended(Status.MIGRATED, MIGRATED))
            else -> MigrationStep.Done(state.report.ended(Status.FAILED,
                "The graph '${ddl.name}' was migrated, but is not judged as of store layout ${LayoutMigration.TO} after: $verdict. Nothing more was changed."))
        }
    }

    private fun writeCheckpoint(checkpoint: Checkpoint) =
        MigrationAction.Execute(statements.writeCheckpoint, linkedMapOf("name" to CHECKPOINT, "state" to checkpoint.toJson()), purpose = "checkpoint")

    /** The checkpoint's own row of [rows], as [MigrationStatements.readCheckpoint] reads them, or null where it has none. */
    private fun checkpointOf(rows: List<Any?>): Checkpoint? =
        rows.map { map(it) }.firstOrNull { it["name"] == CHECKPOINT }?.let { Checkpoint.fromJson(it["state"] as String) }

    private fun keptOf(rows: List<Any?>): List<Kept> = rows.map { map(it) }.filter { (it["name"] as? String)?.startsWith(KEPT) == true }
        .map { Kept.fromJson(it["state"] as String) }

    private fun invalidMessage(report: MigrationReport) =
        "The graph '${ddl.name}' holds ${report.invalidCount} value(s) not of their declared kind, listed by property: correct them, or declare their properties otherwise, " +
            "or keep them as they are, named, with the option to keep invalid values as text. Nothing was written."

    private companion object {
        const val MIGRATED = "The graph is migrated to store layout 2: every value is rewritten, its earlier layout's indexes are dropped, and it is stamped layout 2."

        /**
         * What every report after a scan says of what a migration doesn't do, which a user must know: the indexes are the
         * application's schema plan's to make, and the count store is unseeded, as a fresh store's is.
         */
        val NOTES = listOf(
            "No index or constraint is made: the graph's earlier layout's are dropped, and an application's schema plan makes its declared ones in layout 2 at its next start. Until one does, statements give the same answers, unindexed, so slowly on a large graph.",
            "The count store is left empty, as on any fresh store of layout 2: its counts are seeded when Drivine begins to keep them (drivine-cypher-spec#349).",
        )
        const val FINISHED_MEANWHILE =
            "Another migration of the graph '{graph}' to store layout 2 finished meanwhile: the graph is migrated, and this one wrote nothing."
        const val CHECKPOINT = "checkpoint"
        const val KEPT = "kept:"
        const val IN_PROGRESS = "A migration of the graph is part way through: a dry run reads a graph that hasn't begun one. Resume the migration."
        const val SAMPLES = 5

        fun keptName(table: Table, id: Long, property: String) = "$KEPT${table.word}:$id:$property"
    }

    private fun keptState(table: Table, row: Row, kept: LayoutMigration.Invalid) =
        Kept(table.word, row.id, row.labels, kept.property, kept.kind.word, kept.value, kept.message).toJson()

    /** The fingerprint of the graph: the digest of Drivine's own nodes and its size, as [MigrationStatements.own] and [MigrationStatements.sizes] read them. */
    private fun fingerprint(own: List<Any?>, sizes: List<Any?>): String = hex(canonical(listOf(own.map { map(it) }, sizes.map { map(it) })))

    private fun digest(declarations: List<LayoutMigration.Declaration>): String =
        hex(canonical(declarations.map { listOf(it.label, it.property, it.kind.word) }.sortedBy { it.joinToString("\u0000") }))

    private fun hex(text: String) = Sha256.digest(text.encodeToByteArray()).joinToString("") { (it.toInt() and 0xff).toString(16).padStart(2, '0') }
}

/** What a library is asked to run for a migration: see [LayoutMigrator]. */
@DrivineInternalApi
sealed interface MigrationAction {

    /** A statement whose rows the migration reads, each as one JSON object a library reads into a map, as it reads `readStamps`'. [purpose] says what it is for (`stamps`, `batch`, `checkpoint`, `collisions` …), for the library to name where it fails: the report of a failure gives the store's message alone. */
    data class Query(val statement: String, val parameters: Map<String, Any?> = emptyMap(), val purpose: String) : MigrationAction

    /** A statement run for what it does, in auto-commit: DDL, on Oracle committed as it runs. */
    data class Execute(val statement: String, val parameters: Map<String, Any?> = emptyMap(), val purpose: String) : MigrationAction

    /** [statements] run in one transaction, all of them or none: a batch's rewrites, the values it keeps, and its checkpoint. */
    data class Transaction(val statements: List<Execute>) : MigrationAction

    /**
     * The store's tables made as this layout makes them, all of what a first connection runs to make them: Postgres's and
     * Oracle's `ensuring`, and every statement of SQLite's `ddl` (not only the stamp's `prepare`, which on SQLite makes the
     * node table alone), run without the verdict, which would refuse the graph.
     */
    data object Prepare : MigrationAction

    /**
     * The store's stamp made where the graph has none, as a first connection makes it: the stamp's `assignIfAbsent`, with the
     * library's own id and moment ([org.drivine.cypher.store.marker.StoreStamp.assignParameters]), of this layout.
     */
    data object AssignStamp : MigrationAction

    /** [statement] run on a connection of the library's own, in a transaction it holds open until the migration is done, so its row lock is held to the end. */
    data class Lock(val statement: String) : MigrationAction
}

/** What came of a [MigrationAction], as the library gives it back. */
@DrivineInternalApi
sealed interface MigrationOutcome {
    /** A [MigrationAction.Query]'s rows, each read into a map. */
    data class Rows(val rows: List<Any?>) : MigrationOutcome

    /** An action that ran, whose rows aren't read. */
    data object Ran : MigrationOutcome

    /** An action that failed, with the store's message. */
    data class Failed(val message: String) : MigrationOutcome
}

/** What [LayoutMigrator.start] and [LayoutMigrator.resume] give. */
@DrivineInternalApi
sealed interface MigrationStep {
    /** The [actions] to run, in order, stopping at the first that fails; their outcomes go to [LayoutMigrator.resume] with [state]. */
    data class Run(val actions: List<MigrationAction>, val state: MigrationState) : MigrationStep

    /** The migration, or the dry run, is done, and [report] says what came of it. */
    data class Done(val report: MigrationReport) : MigrationStep
}

/**
 * What a migration is asked: the [declarations] it rewrites values by, the [batchSize] of rows it reads and writes at once,
 * whether it is a [dryRun], which writes nothing, and whether it keeps a value not of its declared kind as it is ([keepInvalid]),
 * naming each, where it would refuse the graph.
 */
@DrivineInternalApi
data class MigrationRequest(
    val declarations: List<LayoutMigration.Declaration>,
    val batchSize: Int = 1000,
    val dryRun: Boolean = false,
    val keepInvalid: Boolean = false,
)

/** How a migration ended: see [MigrationReport]. */
@DrivineInternalApi
enum class Status(val word: String) {
    CURRENT("current"),
    EMPTY("empty"),
    REFUSED("refused"),
    IN_PROGRESS("inProgress"),
    INVALID("invalid"),
    DRY_RUN("dryRun"),
    COLLISIONS("collisions"),
    MIGRATED("migrated"),
    FAILED("failed"),
}

/** Where a migration is: see [LayoutMigrator]. */
@DrivineInternalApi
enum class Phase { JUDGE, RESUME, SCAN, BEGIN, ABANDON, WRITE, CHECK_LIST, CHECK, FINISH_READ, FINISH }

/**
 * Where a migration has got to: the [request], the [phase], the [table] and the id [after] which the next batch is read, the
 * [fingerprint] of the graph as the migration found it, the [report] so far, and whether the migration has taken its lock
 * ([MigrationAction.Lock], taken once: a second, from another connection, would wait on the first). A plain value, written as JSON by [toJson]
 * and read back by [fromJson], so a library holds it between rounds as it likes.
 */
@DrivineInternalApi
data class MigrationState(
    val request: MigrationRequest,
    val phase: Phase,
    val table: Table?,
    val after: Long,
    val fingerprint: String?,
    val report: MigrationReport,
    val locked: Boolean = false,
    /** The uniqueness constraints a check reads samples of, each its label and then its properties. */
    val uniques: List<List<String>> = emptyList(),
) {
    fun toJson(): String = PlanJson.writePlain(linkedMapOf(
        "request" to linkedMapOf(
            "declarations" to request.declarations.map { linkedMapOf("label" to it.label, "property" to it.property, "kind" to it.kind.word) },
            "batchSize" to request.batchSize, "dryRun" to request.dryRun, "keepInvalid" to request.keepInvalid,
        ),
        "phase" to phase.name.lowercase(), "table" to table?.word, "after" to after, "fingerprint" to fingerprint,
        "report" to report.toMap(), "locked" to locked, "uniques" to uniques,
    ))

    companion object {
        fun fromJson(text: String): MigrationState {
            val tree = CypherJson.readTree(text) as ObjectNode
            val request = tree["request"] as ObjectNode
            return MigrationState(
                MigrationRequest(
                    declarationsOf(request["declarations"]),
                    request["batchSize"]!!.longValue().toInt(), request["dryRun"]!!.booleanValue(), request["keepInvalid"]!!.booleanValue(),
                ),
                Phase.valueOf(tree["phase"]!!.textValue()!!.uppercase()),
                tableOf(tree["table"]), tree["after"]!!.longValue(), tree["fingerprint"]?.textValue(),
                MigrationReport.of(tree["report"] as ObjectNode), tree["locked"]?.booleanValue() ?: false,
                tree["uniques"]?.map { unique -> unique.map { it.textValue()!! } }.orEmpty(),
            )
        }
    }
}

/** Declarations as a request and a checkpoint write them. */
internal fun declarationsOf(node: JsonNode?): List<LayoutMigration.Declaration> = (node as? ArrayNode)?.map {
    fun text(key: String) = it[key]?.textValue() ?: throw IllegalArgumentException("A declaration gives its $key as a text.")
    val word = text("kind")
    LayoutMigration.Declaration(text("label"), text("property"),
        LayoutMigration.Kind.of(word) ?: throw IllegalArgumentException("No kind $word to declare: ${LayoutMigration.Kind.entries.joinToString { k -> k.word }}."))
} ?: emptyList()

private fun tableOf(node: JsonNode?): Table? = node?.textValue()?.let { word -> Table.entries.first { it.word == word } }

/** The checkpoint a migration keeps: see [LayoutMigrator]. [table] is null once every row is written; [keepInvalid] is the request's, which a resume is to give alike. */
internal data class Checkpoint(val table: Table?, val after: Long, val fingerprint: String, val declarations: String, val keepInvalid: Boolean, val report: MigrationReport) {
    fun toJson(): String = PlanJson.writePlain(linkedMapOf(
        "from" to LayoutMigration.FROM, "to" to LayoutMigration.TO, "table" to table?.word, "after" to after,
        "fingerprint" to fingerprint, "declarations" to declarations, "keepInvalid" to keepInvalid, "report" to report.toMap(),
    ))

    companion object {
        fun fromJson(text: String): Checkpoint {
            val tree = CypherJson.readTree(text) as ObjectNode
            require(tree["from"]?.longValue() == LayoutMigration.FROM.toLong() && tree["to"]?.longValue() == LayoutMigration.TO.toLong()) {
                "The checkpoint is of a migration from layout ${tree["from"]} to ${tree["to"]}, which this version doesn't run."
            }
            return Checkpoint(tableOf(tree["table"]), tree["after"]!!.longValue(), tree["fingerprint"]!!.textValue()!!,
                tree["declarations"]!!.textValue()!!, tree["keepInvalid"]!!.booleanValue(), MigrationReport.of(tree["report"] as ObjectNode))
        }
    }
}

/** A value a migration kept as it was, not of its declared kind: of the row [id] of [table], of [labels]. */
@DrivineInternalApi
data class Kept(val table: String, val id: Long, val labels: List<String>, val property: String, val kind: String, val value: String, val message: String) {
    fun toMap(): Map<String, Any?> = linkedMapOf("table" to table, "id" to id, "labels" to labels, "property" to property, "kind" to kind, "value" to value, "message" to message)

    fun toJson(): String = PlanJson.writePlain(toMap())

    companion object {
        fun fromJson(text: String): Kept = of(CypherJson.readTree(text) as ObjectNode)

        fun of(tree: JsonNode): Kept = Kept(tree["table"]!!.textValue()!!, tree["id"]!!.longValue(), tree["labels"]!!.map { it.textValue()!! },
            tree["property"]!!.textValue()!!, tree["kind"]!!.textValue()!!, tree["value"]!!.textValue()!!, tree["message"]!!.textValue()!!)
    }
}

/** The layout a stamp's `layout` names, as the verdict reads it: a number, or its text; null for none. */
private fun layoutOf(value: Any?): Int? = when (value) {
    is Number -> value.toInt()
    is String -> value.toIntOrNull()
    else -> null
}

/** A row a batch reads: see [MigrationStatements.readBatch]. */
internal data class Row(val id: Long, val labels: List<String>, val props: String)

private fun Any?.toRow(): Row {
    val row = map(this)
    val id = (row["id"] as? Number)?.toLong() ?: throw IllegalArgumentException("A batch's row gives its id as a number, not ${row["id"]}.")
    val labels = (row["labels"] as? List<*>)?.map { it as? String ?: throw IllegalArgumentException("A row's label is a text, not $it.") }
        ?: throw IllegalArgumentException("A batch's row gives its labels as a list.")
    val props = row["props"] as? String ?: throw IllegalArgumentException("A batch's row gives its props as their JSON text.")
    return Row(id, labels, props)
}

/** A row read into a map, as a library gives one. */
@Suppress("UNCHECKED_CAST")
internal fun map(row: Any?): Map<String, Any?> = row as? Map<String, Any?> ?: throw IllegalArgumentException("A row is read as a map, not $row.")

/**
 * [value] as canonical JSON text: each map's keys in order, a whole float as its integer, so two libraries that read one row
 * into their own maps give one text of it.
 */
internal fun canonical(value: Any?): String = PlanJson.writePlain(canon(value))

private fun canon(value: Any?): Any? = when (value) {
    is Map<*, *> -> value.entries.sortedBy { it.key.toString() }.associate { it.key.toString() to canon(it.value) }
    is List<*> -> value.map(::canon)
    is Double -> if (value.isFinite() && value == kotlin.math.floor(value) && kotlin.math.abs(value) < 9.0E15) value.toLong() else value
    is Float -> canon(value.toDouble())
    is Int -> value.toLong()
    else -> value
}
