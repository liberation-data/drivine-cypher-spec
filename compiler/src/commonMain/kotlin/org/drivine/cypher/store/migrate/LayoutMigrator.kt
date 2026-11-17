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
 *    declared kind, and names the properties whose values all read as one kind, the candidates to declare. A dry run ends
 *    here, read-only. A graph with a value not of its kind is refused here, unless the request keeps such values.
 * 3. **Begin.** The checkpoint table is made, first, so a graph stopped at any later moment is refused as part way through
 *    a migration; then the store's tables as layout 2 makes them (the library's `prepare`, run as at a first connection,
 *    without the verdict), and the lock a schema change takes is taken and held to the end. The checkpoint table is then
 *    read under the lock: a checkpoint already there is another migration's, which is resumed, not begun again.
 * 4. **Write.** Every node, then every relationship, is read again in batches after the checkpoint's id, and each batch's
 *    rewritten rows, the values it keeps, and the checkpoint after its last id are written in one transaction: so a row is
 *    rewritten exactly once, as a rewrite is not to be run again on a row it wrote (it would escape a tag's `$t`), and a
 *    migration stopped at any moment resumes strictly after the last batch it committed.
 *
 * What the checkpoint holds: where the write is, the digest of the declarations, whether it keeps values not of their kind,
 * and the fingerprint of the graph as the migration found it (Drivine's own nodes and its size, none of which a rewrite
 * changes). A resume reads the graph's again,
 * and refuses a graph whose fingerprint differs, a graph changed under the migration, and declarations, or an option to keep
 * values not of their kind, that differ from those it began with. The indexes, the
 * count store and the stamp come in the next step of the migration (#256); until then a graph whose values are all
 * rewritten keeps its checkpoint, and [StoreLayout.verdict] refuses it as part way through a migration.
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
            Phase.RESUME -> resumed(state, rows(outcomes.last()))
            Phase.SCAN -> scanned(state, rows(outcomes.last()))
            Phase.BEGIN -> begun(state, rows(outcomes.last()))
            Phase.WRITE -> written(state, rows(outcomes.last()))
            Phase.FINAL -> MigrationStep.Done(state.report.copy(kept = keptOf(rows(outcomes.last()))).ended(Status.REWRITTEN, REWRITTEN))
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
            return run(judged.copy(phase = Phase.RESUME, locked = true), MigrationAction.Prepare, *lockAnd(MigrationAction.Query(statements.readCheckpoint, purpose = "checkpoint")))
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
    private fun resumed(state: MigrationState, rows: List<Any?>): MigrationStep {
        val checkpoint = checkpointOf(rows)
            // Made, and nothing written: a migration stopped as it began, which begins again from its scan.
            ?: return scan(state.copy(phase = Phase.SCAN), Table.NODES, 0)
        refusalToResume(state, checkpoint)?.let { return MigrationStep.Done(state.report.ended(Status.REFUSED, it)) }
        val at = state.copy(phase = Phase.WRITE, table = checkpoint.table, after = checkpoint.after, report = checkpoint.report)
        return if (checkpoint.table == null) run(at.copy(phase = Phase.FINAL), MigrationAction.Query(statements.readCheckpoint, purpose = "checkpoint"))
        else run(at, read(checkpoint.table, checkpoint.after, state.request.batchSize))
    }

    private fun refusalToResume(state: MigrationState, checkpoint: Checkpoint): String? = when {
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
            state.request.dryRun -> MigrationStep.Done(scannedAll.ended(Status.DRY_RUN, "A dry run: nothing was written."))
            else -> run(
                next.copy(phase = Phase.BEGIN, table = Table.NODES, after = 0, report = scannedAll.copy(tables = emptyMap()), locked = true),
                // The checkpoint table first, so a graph stopped at any later action is refused as part way through a migration
                // (the verdict's row 4), never read by this layout's lock table, which Prepare makes, as one of this layout whose stamp is gone.
                MigrationAction.Execute(statements.createCheckpoint, purpose = "checkpoint"),
                MigrationAction.Prepare,
                // A migration begun again from its scan, its checkpoint table made and nothing written, holds the lock already.
                *lockAnd(MigrationAction.Query(statements.readCheckpoint, purpose = "checkpoint"), held = state.locked),
            )
        }
    }

    /** The lock's action, where the store takes one and the migration doesn't hold it already ([held]), before [actions]. */
    private fun lockAnd(vararg actions: MigrationAction, held: Boolean = false): Array<MigrationAction> =
        (listOfNotNull(statements.lock?.takeUnless { held }?.let { MigrationAction.Lock(it) }) + actions).toTypedArray()

    private fun begun(state: MigrationState, rows: List<Any?>): MigrationStep {
        // Read under the lock: another migration's checkpoint is there where two began at once, and it is resumed, not begun again.
        if (checkpointOf(rows) != null) {
            return MigrationStep.Done(state.report.ended(Status.REFUSED,
                "Another migration of the graph '${ddl.name}' to store layout ${LayoutMigration.TO} began meanwhile: resume it, once it has stopped."))
        }
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
        else run(next.copy(phase = Phase.FINAL), transaction, MigrationAction.Query(statements.readCheckpoint, purpose = "checkpoint"))
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
        const val CHECKPOINT = "checkpoint"
        const val KEPT = "kept:"
        const val IN_PROGRESS = "A migration of the graph is part way through: a dry run reads a graph that hasn't begun one. Resume the migration."
        const val REWRITTEN = "Every value is rewritten in layout 2. The indexes, the count store and the stamp come in the migration's next step, which this version doesn't run yet: the graph keeps its checkpoint, and is refused until they are done."

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

    /** A statement whose rows the migration reads, each as one JSON object a library reads into a map, as it reads `readStamps`'. [purpose] says what it is for (`stamps`, `batch`, `checkpoint` …), for the library to name where it fails: the report of a failure gives the store's message alone. */
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
    REWRITTEN("rewritten"),
    FAILED("failed"),
}

/** Where a migration is: see [LayoutMigrator]. */
@DrivineInternalApi
enum class Phase { JUDGE, RESUME, SCAN, BEGIN, WRITE, FINAL }

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
) {
    fun toJson(): String = PlanJson.writePlain(linkedMapOf(
        "request" to linkedMapOf(
            "declarations" to request.declarations.map { linkedMapOf("label" to it.label, "property" to it.property, "kind" to it.kind.word) },
            "batchSize" to request.batchSize, "dryRun" to request.dryRun, "keepInvalid" to request.keepInvalid,
        ),
        "phase" to phase.name.lowercase(), "table" to table?.word, "after" to after, "fingerprint" to fingerprint,
        "report" to report.toMap(), "locked" to locked,
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

