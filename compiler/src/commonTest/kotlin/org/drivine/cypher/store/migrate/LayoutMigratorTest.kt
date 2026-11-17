package org.drivine.cypher.store.migrate

import org.drivine.cypher.api.CompileJson
import org.drivine.cypher.json.CypherJson
import org.drivine.cypher.json.ObjectNode
import org.drivine.cypher.store.ddl.PostgresDdl
import org.drivine.cypher.store.ddl.SqlStoreDdl
import org.drivine.cypher.store.marker.StoreStampStatements
import org.drivine.cypher.store.migrate.LayoutMigration.Declaration
import org.drivine.cypher.store.migrate.LayoutMigration.Kind
import org.drivine.cypher.store.migrate.MigrationStatements.Table
import org.drivine.cypher.store.plan.PlanJson
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.test.fail

/**
 * [LayoutMigrator] run against a store kept in memory, which answers each of its statements as the store would: a migration
 * stopped after any action of any round, and begun again from the start, rewrites every row exactly once, and ends with the
 * rows a migration that was never stopped gives (liberation-data/drivine-cypher-spec#256).
 */
class LayoutMigratorTest {

    private val ddl: SqlStoreDdl = PostgresDdl("drivine")
    private val statements = MigrationStatements.of(ddl)
    private val stamps = StoreStampStatements.of(ddl)

    /** A store's rows, its checkpoint table, and what it was asked, as the statements the migrator gives would leave it: stamped [stampLayout], or with no stamp where null. */
    private inner class Store(nodes: Map<Long, Pair<List<String>, String>>, edges: Map<Long, Pair<List<String>, String>>, var stampLayout: Int? = 1) {
        val rows = mapOf(Table.NODES to nodes.toMutableMap(), Table.EDGES to edges.toMutableMap())
        var checkpoint: MutableMap<String, String>? = null
        /** Objects beside the store's own, as a later Drivine's would be listed. */
        val extraObjects = mutableListOf<Map<String, Any?>>()
        var prepared = false
        /** Whether a migration's process holds the lock: released when the process stops, and never taken twice by one. */
        var locked = false
        val rewrites = mutableMapOf<Pair<Table, Long>, Int>()
        /** Layout 1's index, until the drops before the writes drop it, and the uniqueness constraints the catalog records, each a label and its properties. */
        var oldIndex = true
        val uniques = mutableListOf("Person" to listOf("name"))
        /** The uniqueness constraints as declared, which the samples are of, whether or not their records are dropped yet. */
        val declaredUniques: List<Pair<String, List<String>>> get() = declared
        private val declared = mutableListOf<Pair<String, List<String>>>()
        /** Layout 1's unique index, over its label's property's JSON text, refusing a write that repeats a key while it stands. */
        var oldUnique: Pair<String, String>? = null
        private val schema = org.drivine.cypher.store.schema.StoreSchema.of(ddl)

        fun own() = rows[Table.NODES]!!.filter { (_, row) -> row.first.any { it == "DrivineStore" || it == "_DrivineSchema" } }

        fun answer(action: MigrationAction): MigrationOutcome = when (action) {
            // A checkpoint table another migration dropped is read as the store reads a table that isn't there.
            is MigrationAction.Query -> if (action.statement == statements.readCheckpoint && checkpoint == null) MigrationOutcome.Failed("relation \"${ddl.migrate}\" does not exist")
            else MigrationOutcome.Rows(query(action))
            is MigrationAction.Execute -> { execute(action, checkpoint, rows); MigrationOutcome.Ran }
            is MigrationAction.Transaction -> {
                // All or none: applied to copies, which replace the store's once every statement has run.
                val copy = checkpoint?.toMutableMap()
                val copies = rows.mapValues { it.value.toMutableMap() }
                try {
                    action.statements.forEach { execute(it, copy, copies) }
                    checkpoint = copy
                    rows.forEach { (table, live) -> live.clear(); live.putAll(copies[table]!!) }
                    MigrationOutcome.Ran
                } catch (refused: IllegalStateException) {
                    MigrationOutcome.Failed(refused.message!!)
                }
            }
            MigrationAction.Prepare -> { prepared = true; MigrationOutcome.Ran }
            MigrationAction.AssignStamp -> { assertNull(stampLayout, "a stamp assigned where there is one"); stampLayout = 2; MigrationOutcome.Ran }
            is MigrationAction.Lock -> {
                assertEquals(statements.lock, action.statement)
                assertTrue(prepared, "the lock taken before its table is made")
                // A second lock from another connection of the same process would wait on its first forever.
                assertTrue(!locked, "the lock taken twice by one migration")
                locked = true
                MigrationOutcome.Ran
            }
        }

        private fun query(action: MigrationAction.Query): List<Any?> = when (action.statement) {
            stamps.readStamps -> listOfNotNull(stampLayout?.let { mapOf("id" to "s", "assignedAt" to "2026-11-17T00:00:00Z", "layout" to it.toLong()) })
            stamps.foreignNodes -> if (rows[Table.NODES]!!.size > own().size) listOf(1L) else emptyList()
            stamps.layoutObjects -> listOfNotNull(
                if (oldIndex) mapOf("name" to "drivine_ix_personname_1a2b3c4d", "type" to "INDEX") else null,
                if (prepared) mapOf("name" to ddl.lock, "type" to "TABLE") else null,
                if (checkpoint != null) mapOf("name" to ddl.migrate, "type" to "TABLE") else null,
            ) + extraObjects
            statements.own -> own().map { (id, row) -> mapOf("id" to id, "props" to row.second) }
            statements.sizes -> listOf(mapOf(
                "nodes" to rows[Table.NODES]!!.size.toLong(), "lastNode" to rows[Table.NODES]!!.keys.maxOrNull(),
                "edges" to rows[Table.EDGES]!!.size.toLong(), "lastEdge" to rows[Table.EDGES]!!.keys.maxOrNull(),
            ))
            statements.readCheckpoint -> checkpoint!!.entries.sortedBy { it.key }.map { mapOf("name" to it.key, "state" to it.value) }
            statements.uniques -> uniques.map { (label, properties) -> mapOf("label" to label, "properties" to PlanJson.writePlain(properties)) }
            else -> if (action.purpose == "collisions") {
                // The store's own SQL, as the schema plan runs its sample: not Cypher to compile.
                assertTrue(action.statement.startsWith("SELECT"), "a sample of collisions run as the store's SQL: ${action.statement}")
                collisions(action.statement)
            } else {
                val table = Table.entries.first { statements.readBatch(it) == action.statement }
                val after = (action.parameters["after"] as Number).toLong()
                val limit = (action.parameters["limit"] as Number).toInt()
                rows[table]!!.entries.filter { it.key > after }.sortedBy { it.key }.take(limit)
                    .map { (id, row) -> mapOf("id" to id, "labels" to row.first, "props" to row.second) }
            }
        }

        /** The rows the sample of [statement], a uniqueness constraint's, gives: each key of its properties that repeats among its label's nodes. */
        private fun collisions(statement: String): List<Any?> {
            val (label, properties) = (declared + uniques).first { (label, properties) ->
                schema.sampleConflictsQuery(org.drivine.schema.UniquenessConstraintSpec(label, properties), 5) == statement
            }
            return rows[Table.NODES]!!.entries.filter { label in it.value.first }.mapNotNull { (id, row) ->
                val props = PlanJson.plain(CypherJson.readTree(row.second)) as Map<*, *>
                properties.map { props[it] }.takeIf { key -> key.none { it == null } }?.let { key -> PlanJson.writePlain(key) to id }
            }.groupBy({ it.first }, { it.second }).filterValues { it.size > 1 }.map { (key, ids) ->
                mapOf("properties" to key, "count" to ids.size.toLong(), "nodes" to ids.map { mapOf("elementId" to it.toString()) })
            }
        }

        private fun execute(action: MigrationAction.Execute, checkpoint: MutableMap<String, String>?, rows: Map<Table, MutableMap<Long, Pair<List<String>, String>>>) {
            when (action.statement) {
                statements.createCheckpoint -> if (this.checkpoint == null) this.checkpoint = mutableMapOf()
                statements.writeCheckpoint -> (checkpoint ?: fail("A checkpoint written before its table is made"))[action.parameters["name"] as String] = action.parameters["state"] as String
                statements.stampLayout -> stampLayout = 2
                statements.dropCheckpoint -> this.checkpoint = null
                else -> if (action.statement.startsWith("DROP INDEX IF EXISTS drivine_ix_personname")) oldIndex = false
                else if (action.statement.startsWith("DELETE FROM ${ddl.catalog}")) { declared += uniques; uniques.clear() }
                else {
                    val table = Table.entries.first { statements.update(it) == action.statement }
                    val id = (action.parameters["id"] as Number).toLong()
                    val row = rows[table]!![id] ?: fail("No row $id")
                    val props = action.parameters["props"] as String
                    oldUnique?.takeIf { oldIndex && table == Table.NODES && it.first in row.first }?.let { (label, property) ->
                        fun key(text: String) = (PlanJson.plain(CypherJson.readTree(text)) as Map<*, *>)[property]?.let { PlanJson.writePlain(it) }
                        val repeats = rows[table]!!.any { (other, o) -> other != id && label in o.first && key(o.second) != null && key(o.second) == key(props) }
                        check(!repeats) { "duplicate key value violates unique constraint \"drivine_ix_personname_1a2b3c4d\"" }
                    }
                    rows[table]!![id] = row.first to props
                    rewrites[table to id] = (rewrites[table to id] ?: 0) + 1
                }
            }
        }
    }

    private fun store() = Store(
        nodes = mapOf(
            1L to (listOf("DrivineStore") to """{"key": "singleton", "layout": 1, "engine": "POSTGRES", "storeId": "s"}"""),
            2L to (listOf("Person") to """{"${'$'}t": 1, "born": "2024-03-15", "name": "Ada"}"""),
            3L to (listOf("Person") to """{"born": "2020-01-01"}"""),
            4L to (listOf("Person") to """{"name": "Bob", "met": "2021-05-06"}"""),
            6L to (listOf("Robot") to """{"${'$'}x": "y"}"""),
            7L to (listOf("Person", "Robot") to """{"name": "Cy"}"""),
        ),
        edges = mapOf(
            1L to (listOf("KNOWS") to """{"since": "2020-01-01"}"""),
            2L to (listOf("KNOWS") to """{}"""),
            5L to (listOf("KNOWS") to """{"since": "2019-02-03"}"""),
        ),
    )

    private val declarations = listOf(Declaration("Person", "born", Kind.DATE), Declaration("KNOWS", "since", Kind.DATE))

    /** Runs the migration on [store] to its end, or to the action [stopAt] counts down to, where it stops as a process stopped there would. */
    private fun migrate(store: Store, request: MigrationRequest, stopAt: Int? = null): MigrationReport? =
        drive(store, LayoutMigrator(ddl).start(request), stopAt)

    /** Runs [first] and the rounds after it on [store], as [migrate] does, its process holding no lock as it begins. */
    private fun drive(store: Store, first: MigrationStep, stopAt: Int? = null, until: (MigrationStep.Run) -> Boolean = { false }): MigrationReport? {
        val migrator = LayoutMigrator(ddl)
        store.locked = false
        var actions = stopAt
        var step = first
        var rounds = 0
        while (true) {
            val run = when (step) {
                is MigrationStep.Done -> return step.report
                is MigrationStep.Run -> step
            }
            if (until(run)) {
                paused = run
                return null
            }
            assertTrue(++rounds < 200, "the migration ends")
            val outcomes = mutableListOf<MigrationOutcome>()
            for (action in run.actions) {
                if (actions != null && actions-- == 0) return null
                outcomes += store.answer(action)
            }
            // A state crosses a boundary as JSON: what the next round is given is what the JSON gives back.
            val state = MigrationState.fromJson(run.state.toJson())
            assertEquals(run.state, state, "the state reads back as it was written")
            step = migrator.resume(state, outcomes)
        }
    }

    /** The round [drive] stopped before, where its `until` said to. */
    private var paused: MigrationStep.Run? = null

    /** What every row is once migrated, as each is rewritten once: the rows of a migration never stopped. */
    private fun expected(): Map<Table, Map<Long, String>> {
        val original = store()
        return original.rows.mapValues { (_, rows) ->
            rows.mapValues { (_, row) -> LayoutMigration.rewrite(row.second, org.drivine.cypher.api.Store.POSTGRES, row.first, declarations).let { if (it.changed) it.props else row.second } }
        }
    }

    @Test
    fun aMigrationRewritesEveryRowOnceAndReportsWhatItDid() {
        val store = store()
        val report = migrate(store, MigrationRequest(declarations, batchSize = 2))!!
        assertEquals(Status.MIGRATED, report.status, report.message)
        assertEquals(expected(), store.rows.mapValues { (_, rows) -> rows.mapValues { it.value.second } })
        assertTrue(store.rewrites.values.all { it == 1 }, "each row rewritten once: ${store.rewrites}")
        assertEquals(setOf(Table.NODES to 2L, Table.NODES to 3L, Table.NODES to 6L, Table.EDGES to 1L, Table.EDGES to 5L), store.rewrites.keys)
        assertEquals(MigrationReport.Counted(6, 3), report.tables["nodes"])
        assertEquals(MigrationReport.Counted(3, 2), report.tables["edges"])
        assertTrue(store.prepared, "the tables made as layout 2 makes them")
        assertNull(store.checkpoint, "the checkpoint dropped, last")
        assertEquals(2, store.stampLayout, "stamped layout 2")
        assertTrue(!store.oldIndex && store.uniques.isEmpty(), "layout 1's index and its records dropped")
        assertTrue(report.notes.size == 2 && report.collisions.isEmpty(), "the indexes and the counts said to be left to others")
    }

    @Test
    fun aMigrationStoppedAfterAnyActionAndBegunAgainRewritesEveryRowExactlyOnce() = stoppedAfterEachAction(::store)

    @Test
    fun aMigrationOfAGraphWithNoStampStoppedAfterAnyActionAndBegunAgainRewritesEveryRowExactlyOnce() =
        // Layout 1's graph with no stamp: its lock table, which only layout 2 makes, made before its checkpoint table would read
        // as layout 2's with its stamp gone (the verdict's rows 11 and 12), were the migration stopped between them.
        stoppedAfterEachAction { Store(store().rows[Table.NODES]!!.filterKeys { it != 1L }, store().rows[Table.EDGES]!!, stampLayout = null) }

    /** A migration of the store [make] makes, stopped after each action in turn and begun again from the start. */
    private fun stoppedAfterEachAction(make: () -> Store) {
        var stops = 0
        var stopAt = 0
        while (true) {
            val store = make()
            val stopped = migrate(store, MigrationRequest(declarations, batchSize = 2), stopAt = stopAt)
            if (stopped != null) break
            stops++
            // Begun again from the start, as a library begins it, by the same declarations: it resumes where the store says.
            val report = migrate(store, MigrationRequest(declarations, batchSize = 2))!!
            // Stopped after its last action, the checkpoint dropped, a migration begun again finds the graph current.
            assertTrue(report.status == Status.MIGRATED || (report.status == Status.CURRENT && store.checkpoint == null), "stopped at action $stopAt: ${report.status} ${report.message}")
            assertEquals(expected().mapValues { (table, rows) -> rows.filterKeys { it in make().rows[table]!! } }, store.rows.mapValues { (_, rows) -> rows.mapValues { it.value.second } }, "stopped at action $stopAt")
            assertTrue(store.rewrites.values.all { it == 1 }, "stopped at action $stopAt, a row rewritten twice: ${store.rewrites}")
            // Stopped after any drop, the records of layout 1's objects are forgotten all the same.
            assertTrue(!store.oldIndex && store.uniques.isEmpty() && store.stampLayout == 2, "stopped at action $stopAt: layout 1's index and its records dropped, stamped layout 2")
            stopAt++
        }
        assertTrue(stops > 15, "stopped at each of $stops actions")
    }

    /**
     * Two spellings of one moment, distinct texts in layout 1, one value in layout 2, under a uniqueness constraint, and
     * layout 1's unique index over the texts: dropped before the writes, it can't refuse the rewrite that makes them one.
     */
    private fun colliding() = Store(
        nodes = mapOf(
            1L to (listOf("DrivineStore") to """{"key": "singleton", "layout": 1, "engine": "POSTGRES", "storeId": "s"}"""),
            2L to (listOf("Event") to """{"at": "2024-03-15T10:30:00.5Z"}"""),
            3L to (listOf("Event") to """{"at": "2024-03-15T10:30:00.500Z"}"""),
        ),
        edges = emptyMap(),
    ).apply {
        uniques.clear(); uniques += "Event" to listOf("at")
        oldUnique = "Event" to "at"
    }

    private val collidingRequest = MigrationRequest(listOf(Declaration("Event", "at", Kind.DATE_TIME)), batchSize = 1)

    @Test
    fun keysThatCollideOnlyOnceRewrittenStopTheMigrationBeforeItsFinishAndAResumeAfterTheyAreCorrectedFinishesIt() {
        val store = colliding()
        val request = collidingRequest
        // The dry run, over layout 1's texts, can't see it: its check is of possible collisions.
        assertTrue(migrate(store, request.copy(dryRun = true))!!.collisions.isEmpty())
        val stopped = migrate(store, request)!!
        assertEquals(Status.COLLISIONS, stopped.status, stopped.message)
        assertEquals(listOf("Event" to listOf("at")), stopped.collisions.map { it.label to it.properties })
        assertTrue(stopped.message!!.contains("Correct the rows"), stopped.message)
        assertTrue(store.checkpoint != null && store.stampLayout == 1, "kept refused, part way through, not stamped")
        assertTrue(!store.oldIndex, "layout 1's unique index dropped before the writes, so the rewrite that made the keys one wasn't refused")
        // Resumed as it is, it is stopped again; the user corrects a row with the store's own tools, in layout 2, and resumes.
        assertEquals(Status.COLLISIONS, migrate(store, request)!!.status)
        store.rows[Table.NODES]!![3L] = listOf("Event") to """{"at": {"${'$'}t": "datetime", "v": "2024-03-15T10:31:00Z", "s": "10000020240315103100000000000064800"}}"""
        val finished = migrate(store, request)!!
        assertEquals(Status.MIGRATED, finished.status, finished.message)
        assertTrue(store.checkpoint == null && !store.oldIndex && store.stampLayout == 2)
        assertTrue(store.rewrites.values.all { it == 1 }, "each row rewritten once: ${store.rewrites}")
    }

    @Test
    fun aMigrationOfCollidingKeysStoppedAfterAnyActionAndBegunAgainStillChecksTheConstraintItsCheckpointKept() {
        // Stopped after the first checkpoint, among the drops that forget the constraint's record, or anywhere after: begun
        // again, the check reads the constraint from the checkpoint, and the collision is named, never missed.
        var stops = 0
        var stopAt = 0
        while (true) {
            val store = colliding()
            if (migrate(store, collidingRequest, stopAt = stopAt) != null) break
            stops++
            val report = migrate(store, collidingRequest)!!
            assertEquals(Status.COLLISIONS, report.status, "stopped at action $stopAt: ${report.message}")
            assertEquals(listOf("Event" to listOf("at")), report.collisions.map { it.label to it.properties }, "stopped at action $stopAt")
            assertTrue(store.checkpoint != null && store.stampLayout == 1, "stopped at action $stopAt: kept part way through, not stamped")
            assertTrue(store.rewrites.values.all { it == 1 }, "stopped at action $stopAt, a row rewritten twice: ${store.rewrites}")
            stopAt++
        }
        assertTrue(stops > 15, "stopped at each of $stops actions")
    }

    @Test
    fun aDryRunListsKeysThatMayCollide() {
        val store = store()
        store.rows[Table.NODES]!![8L] = listOf("Person") to """{"name": "Ada"}"""
        val report = migrate(store, MigrationRequest(declarations, dryRun = true))!!
        assertEquals(Status.DRY_RUN, report.status)
        assertEquals(listOf("Person" to listOf("name")), report.collisions.map { it.label to it.properties })
        assertTrue(report.message!!.contains("may collide"), report.message)
        assertEquals(2, report.notes.size, "the indexes and the counts said to be left to others")
    }

    @Test
    fun aDryRunWritesNothingAndNamesWhatToDeclare() {
        val store = store()
        val report = migrate(store, MigrationRequest(declarations, batchSize = 2, dryRun = true))!!
        assertEquals(Status.DRY_RUN, report.status)
        assertTrue(store.rewrites.isEmpty() && store.checkpoint == null && !store.prepared, "nothing written")
        assertTrue(store.oldIndex && store.uniques.isNotEmpty(), "nothing dropped: layout 1's index and its records stand")
        assertEquals(MigrationReport.Counted(6, 3), report.tables["nodes"])
        // Person.met is a date in every row that has it, and declared nothing: a candidate.
        assertEquals(listOf(MigrationReport.Candidate("Person", "met", "date", 1)), report.candidates)
        assertEquals(mapOf("escapedKey" to 1L), report.properties["Person\u0000\$t"]!!.changes)
        assertEquals(mapOf("temporal" to 2L), report.properties["Person\u0000born"]!!.changes)
        assertEquals(mapOf("escapedKey" to 1L), report.properties["Robot\u0000\$x"]!!.changes)
    }

    @Test
    fun aValueNotOfItsKindRefusesTheGraphUnlessKeptAndAKeptValueIsNamedInTheEnd() {
        val store = store()
        store.rows[Table.NODES]!![8L] = listOf("Person") to """{"born": "soon"}"""
        val refused = migrate(store, MigrationRequest(declarations))!!
        assertEquals(Status.INVALID, refused.status)
        assertEquals(listOf("'soon' is no date."), refused.properties["Person\u0000born"]!!.invalid.map { it.message })
        assertTrue(store.rewrites.isEmpty() && store.checkpoint == null, "nothing written")
        assertTrue(refused.notes.isEmpty(), "no notes of a migration that wrote nothing")
        val kept = migrate(store, MigrationRequest(declarations, keepInvalid = true))!!
        assertEquals(Status.MIGRATED, kept.status)
        assertEquals(listOf(Kept("nodes", 8, listOf("Person"), "born", "date", "\"soon\"", "'soon' is no date.")), kept.kept)
        assertEquals("""{"born": "soon"}""", store.rows[Table.NODES]!![8L]!!.second, "kept as it was")
    }

    @Test
    fun aResumeOfAGraphChangedUnderTheMigrationOrByOtherDeclarationsIsRefused() {
        // Judged (5 actions), scanned (6 batches), begun (7), the first checkpoint, the earlier layout's drops (2) and the first
        // batch's read, then its write and the second's read: stopped before the second batch is written.
        val stopAt = 24
        val changed = store()
        assertNull(migrate(changed, MigrationRequest(declarations, batchSize = 2), stopAt = stopAt))
        assertTrue(changed.checkpoint?.containsKey("checkpoint") == true, "stopped part way through its writes")
        changed.rows[Table.NODES]!![9L] = listOf("Person") to "{}"
        assertEquals(Status.REFUSED, migrate(changed, MigrationRequest(declarations, batchSize = 2))!!.status)
        val redeclared = store()
        assertNull(migrate(redeclared, MigrationRequest(declarations, batchSize = 2), stopAt = stopAt))
        val other = migrate(redeclared, MigrationRequest(declarations.take(1), batchSize = 2))!!
        assertEquals(Status.REFUSED, other.status)
        assertTrue(other.message!!.contains("other declarations"), other.message)
        // A dry run of a graph part way through reads nothing more.
        assertEquals(Status.IN_PROGRESS, migrate(redeclared, MigrationRequest(declarations, dryRun = true))!!.status)
    }

    @Test
    fun aResumeWithoutTheOptionToKeepInvalidValuesThatItBeganWithIsRefused() {
        // Begun keeping a value not of its kind, stopped once its first batches are written, before the one that keeps it.
        val store = store()
        store.rows[Table.EDGES]!![6L] = listOf("KNOWS") to """{"since": "soon"}"""
        assertNull(migrate(store, MigrationRequest(declarations, batchSize = 2, keepInvalid = true), stopAt = 24))
        assertTrue(store.checkpoint?.containsKey("checkpoint") == true, "stopped part way through its writes")
        val refused = migrate(store, MigrationRequest(declarations, batchSize = 2))!!
        assertEquals(Status.REFUSED, refused.status, refused.message)
        assertTrue(refused.message!!.contains("began with the option to keep invalid values as text"), refused.message)
        assertNull(store.rewrites[Table.EDGES to 1L], "nothing written by the refused resume")
        val kept = migrate(store, MigrationRequest(declarations, batchSize = 2, keepInvalid = true))!!
        assertEquals(Status.MIGRATED, kept.status, kept.message)
        assertEquals(listOf(6L), kept.kept.map { it.id })
    }

    @Test
    fun aGraphPartWayThroughAMigrationThatALaterDrivineWroteToIsRefusedNotResumed() {
        val store = store()
        // Stopped after its first batch is written, then written to by a later Drivine, which made its own lock table.
        assertNull(migrate(store, MigrationRequest(declarations, batchSize = 2), stopAt = 24))
        store.extraObjects += mapOf("name" to "drivine_lock3", "type" to "TABLE")
        val report = migrate(store, MigrationRequest(declarations, batchSize = 2))!!
        assertEquals(Status.REFUSED, report.status)
        assertTrue(report.message!!.contains("by a later version of Drivine"), report.message)
    }

    @Test
    fun aGraphOfThisLayoutOrOfNoValuesHasNothingToMigrate() {
        val current = Store(store().rows[Table.NODES]!!, emptyMap(), stampLayout = 2)
        assertEquals(Status.CURRENT, migrate(current, MigrationRequest(declarations))!!.status)
        val empty = Store(mapOf(1L to (listOf("DrivineStore") to "{}")), emptyMap())
        assertEquals(Status.EMPTY, migrate(empty, MigrationRequest(declarations))!!.status)
        // Layout 2's graph whose stamp a clear deleted, loaded again (the verdict's row 12): it holds values, of this layout.
        val migrator = LayoutMigrator(ddl)
        val first = migrator.start(MigrationRequest(declarations)) as MigrationStep.Run
        val judged = listOf(emptyList(), listOf(1L), listOf(mapOf("name" to ddl.lock, "type" to "TABLE")), emptyList(),
            listOf(mapOf("nodes" to 1L, "lastNode" to 1L, "edges" to 0L, "lastEdge" to null)))
        val report = (migrator.resume(first.state, judged.map { MigrationOutcome.Rows(it) }) as MigrationStep.Done).report
        assertEquals(Status.CURRENT, report.status, report.message)
        assertTrue(report.message!!.contains("holds values of store layout 2"), report.message)
    }

    @Test
    fun aMigrationBegunAgainFromItsScanUnderTheLockOfItsResumeTakesTheLockOnce() {
        // Stopped as it began: its checkpoint table made, nothing written in it.
        val store = store()
        assertNull(drive(store, LayoutMigrator(ddl).start(MigrationRequest(declarations, batchSize = 2))) { run -> run.actions.any { it is MigrationAction.Transaction } })
        assertTrue(store.checkpoint != null && store.checkpoint!!.isEmpty(), "the checkpoint table made, and nothing in it")
        val report = migrate(store, MigrationRequest(declarations, batchSize = 2))!!
        assertEquals(Status.MIGRATED, report.status, report.message)
        assertEquals(expected(), store.rows.mapValues { (_, rows) -> rows.mapValues { it.value.second } })
    }

    @Test
    fun aMigrationThatFindsAnothersCheckpointUnderTheLockIsRefusedWritingAndDroppingNothing() {
        val store = store()
        val request = MigrationRequest(declarations, batchSize = 2)
        // The second judges and scans the graph before the first begins; the first stops part way through its writes.
        assertNull(drive(store, LayoutMigrator(ddl).start(request)) { run -> run.actions.contains(MigrationAction.Prepare) })
        val second = paused!!
        assertNull(migrate(store, request, stopAt = 24))
        val checkpoint = store.checkpoint!!.toMap()
        val rewrites = store.rewrites.toMap()
        store.oldIndex = true
        val refused = drive(store, second)!!
        assertEquals(Status.REFUSED, refused.status, refused.message)
        assertTrue(refused.message!!.contains("began meanwhile"), refused.message)
        assertEquals<Map<String, String>?>(checkpoint, store.checkpoint, "the first's checkpoint kept as it was")
        assertEquals(rewrites, store.rewrites, "nothing written")
        assertTrue(store.oldIndex, "nothing dropped")
        val resumed = migrate(store, request)!!
        assertEquals(Status.MIGRATED, resumed.status, resumed.message)
        assertEquals(expected(), store.rows.mapValues { (_, rows) -> rows.mapValues { it.value.second } })
        assertTrue(store.rewrites.values.all { it == 1 }, "each row rewritten once: ${store.rewrites}")
    }

    @Test
    /**
     * The race the stamps read under the lock guards: two migrations judge the graph, the first finishes and drops its
     * checkpoint, and the second, taking the lock after it, would find no checkpoint and rewrite layout 2's values again,
     * which a rewrite is not to be run on twice. It reads the stamp of layout 2 under the lock and aborts, writing nothing.
     * Keep it named so: the read of the stamps in [LayoutMigrator]'s begin round is what this test holds in place.
     */
    fun aMigrationThatReadsALayoutTwoStampUnderTheLockAbortsWithoutWriting() {
        val store = store()
        val request = MigrationRequest(declarations, batchSize = 2)
        // The second judges and scans the graph before the first begins, and waits on the lock while the first runs.
        assertNull(drive(store, LayoutMigrator(ddl).start(request)) { run -> run.actions.contains(MigrationAction.Prepare) })
        val second = paused!!
        assertEquals(Status.MIGRATED, migrate(store, request)!!.status)
        val refused = drive(store, second)!!
        assertEquals(Status.REFUSED, refused.status)
        // The first finished, its checkpoint dropped, before the second took the lock: the second drops the table it made, and writes nothing.
        assertTrue(refused.message!!.contains("finished meanwhile"), refused.message)
        assertNull(store.checkpoint, "the second's empty checkpoint table dropped")
        assertTrue(store.rewrites.values.all { it == 1 }, "each row rewritten once: ${store.rewrites}")
    }

    @Test
    fun aMigrationThatWaitedOnTheLockWhileAnotherFinishedWritesNothingAndLeavesTheGraphCurrent() {
        for (resuming in listOf(false, true)) {
            val store = store()
            val request = MigrationRequest(declarations, batchSize = 2)
            // The second judges the graph before the first begins (as layout 1's, to scan), or once it has begun (as part way
            // through, to resume); it is paused before the round that takes the lock.
            val first = if (resuming) {
                assertNull(drive(store, LayoutMigrator(ddl).start(request)) { run -> run.actions.any { it is MigrationAction.Transaction } })
                paused!!
            } else null
            assertNull(drive(store, LayoutMigrator(ddl).start(request)) { run -> run.actions.any { it is MigrationAction.Lock } })
            val second = paused!!
            val running = first ?: run {
                assertNull(drive(store, LayoutMigrator(ddl).start(request)) { run -> run.actions.any { it is MigrationAction.Transaction } })
                paused!!
            }
            // The first holds the lock; the second runs up to it and waits there, the first's checkpoint table there.
            val lockAt = second.actions.indexOfFirst { it is MigrationAction.Lock }
            val before = second.actions.take(lockAt).map { store.answer(it) }
            // The first finishes, dropping its checkpoint table, and lets the lock go; the second takes it.
            assertEquals(Status.MIGRATED, drive(store, running)!!.status)
            store.locked = false
            val outcomes = before + second.actions.drop(lockAt).map { store.answer(it) }
            val report = drive(store, LayoutMigrator(ddl).resume(MigrationState.fromJson(second.state.toJson()), outcomes))!!
            assertEquals(Status.REFUSED, report.status, "resuming $resuming: ${report.message}")
            assertTrue(report.message!!.contains("finished meanwhile"), report.message)
            assertNull(store.checkpoint, "resuming $resuming: the table it made again dropped")
            assertTrue(store.rewrites.values.all { it == 1 }, "each row rewritten once: ${store.rewrites}")
            assertEquals(Status.CURRENT, migrate(store, request)!!.status)
        }
    }

    @Test
    fun aMigrationStoppedAfterItMadeItsCheckpointTableOnAGraphAnotherFinishedDropsItWhenBegunAgain() {
        val store = store()
        val request = MigrationRequest(declarations, batchSize = 2)
        assertNull(drive(store, LayoutMigrator(ddl).start(request)) { run -> run.actions.contains(MigrationAction.Prepare) })
        val second = paused!!
        assertEquals(Status.MIGRATED, migrate(store, request)!!.status)
        // The second makes its checkpoint table on the migrated graph, and stops: the graph reads as part way through a migration.
        store.answer(second.actions.first())
        assertTrue(store.checkpoint != null && store.checkpoint!!.isEmpty())
        val report = migrate(store, request)!!
        assertEquals(Status.REFUSED, report.status, report.message)
        assertTrue(report.message!!.contains("finished meanwhile"), report.message)
        assertNull(store.checkpoint, "the empty checkpoint table dropped")
        assertTrue(store.rewrites.values.all { it == 1 }, "each row rewritten once: ${store.rewrites}")
        assertEquals(Status.CURRENT, migrate(store, request)!!.status)
    }

    @Test
    fun aValueNotOfItsKindWrittenAfterTheScanRefusesItsBatchUnwritten() {
        // Stopped before the second batch is written, as in the test of a changed graph; then a row of a later batch changed.
        val store = store()
        assertNull(migrate(store, MigrationRequest(declarations, batchSize = 2), stopAt = 24))
        store.rows[Table.EDGES]!![5L] = listOf("KNOWS") to """{"since": "soon"}"""
        val before = store.rows.mapValues { it.value.toMap() }
        val checkpoint = store.checkpoint!!.toMap()
        val report = migrate(store, MigrationRequest(declarations, batchSize = 2))!!
        assertEquals(Status.INVALID, report.status, report.message)
        assertTrue(report.message!!.contains("row 5"), report.message)
        // The batches before edge 5's are written; edge 5's batch, the last, is not, and its checkpoint stays before it.
        assertEquals(before[Table.EDGES]!![5L], store.rows[Table.EDGES]!![5L])
        assertTrue(store.checkpoint != checkpoint, "the batches before it written")
        assertEquals(1, store.rewrites[Table.EDGES to 1L])
        assertNull(store.rewrites[Table.EDGES to 5L])
        // Resumed as it is, it is refused again; nor is it kept, as the migration began without keeping such values: the value
        // is corrected, as the message says, and the migration resumed finishes.
        val again = migrate(store, MigrationRequest(declarations, batchSize = 2))!!
        assertEquals(Status.INVALID, again.status, again.message)
        assertTrue(again.message!!.contains("correct it"), again.message)
        assertEquals(Status.REFUSED, migrate(store, MigrationRequest(declarations, batchSize = 2, keepInvalid = true))!!.status)
        store.rows[Table.EDGES]!![5L] = listOf("KNOWS") to """{"since": "2019-02-03"}"""
        assertEquals(Status.MIGRATED, migrate(store, MigrationRequest(declarations, batchSize = 2))!!.status)
        assertEquals(expected(), store.rows.mapValues { (_, rows) -> rows.mapValues { it.value.second } })
        assertTrue(store.rewrites.values.all { it == 1 }, "each row rewritten once: ${store.rewrites}")
    }

    @Test
    fun aValueNotOfItsKindWrittenAfterTheScanIsReportedWithOnlyTheRowsWritten() {
        // Stopped before the second batch, nodes 3 and 4, is written; then node 4 changed, after node 3, which its batch would rewrite.
        val store = store()
        assertNull(migrate(store, MigrationRequest(declarations, batchSize = 2), stopAt = 24))
        store.rows[Table.NODES]!![4L] = listOf("Person") to """{"born": "soon"}"""
        val report = migrate(store, MigrationRequest(declarations, batchSize = 2))!!
        assertEquals(Status.INVALID, report.status, report.message)
        assertNull(store.rewrites[Table.NODES to 3L], "nothing of node 4's batch written")
        // The report counts the first batch, which was written, and nothing of node 4's, which wasn't.
        assertEquals(MigrationReport.Counted(2, 1), report.tables["nodes"])
    }

    @Test
    fun aFailedActionEndsTheMigrationWithTheStoresMessage() {
        val migrator = LayoutMigrator(ddl)
        val first = migrator.start(MigrationRequest(declarations)) as MigrationStep.Run
        val report = (migrator.resume(first.state, listOf(MigrationOutcome.Rows(emptyList()), MigrationOutcome.Failed("relation does not exist"))) as MigrationStep.Done).report
        assertEquals(Status.FAILED, report.status)
        assertEquals("relation does not exist", report.message)
    }

    @Test
    fun theJsonRequestRunsTheSameRounds() {
        val store = store()
        fun ask(given: Map<String, Any?>): ObjectNode =
            CypherJson.readTree(CompileJson.compile(PlanJson.writePlain(mapOf("migrate" to linkedMapOf("store" to "postgres", "graph" to "drivine") + given)))) as ObjectNode
        var answer = ask(mapOf("start" to mapOf("declarations" to declarations.map { mapOf("label" to it.label, "property" to it.property, "kind" to it.kind.word) }, "batchSize" to 2)))
        var rounds = 0
        while (answer.has("run")) {
            assertTrue(++rounds < 200)
            val outcomes = answer["run"]!!.map { node ->
                val action = actionOf(node)
                when (val outcome = store.answer(action)) {
                    is MigrationOutcome.Rows -> mapOf("type" to "rows", "rows" to outcome.rows)
                    MigrationOutcome.Ran -> mapOf("type" to "ran")
                    is MigrationOutcome.Failed -> mapOf("type" to "failed", "message" to outcome.message)
                }
            }
            answer = ask(mapOf("resume" to PlanJson.plain(answer["state"]!!), "outcomes" to outcomes))
        }
        assertEquals("migrated", answer["done"]!!["status"]!!.textValue(), CypherJson.writeValueAsString(answer))
        assertEquals(expected(), store.rows.mapValues { (_, rows) -> rows.mapValues { it.value.second } })
    }

    private fun actionOf(node: org.drivine.cypher.json.JsonNode): MigrationAction {
        @Suppress("UNCHECKED_CAST")
        fun parameters() = PlanJson.plain(node["parameters"]!!) as Map<String, Any?>
        return when (node["type"]!!.textValue()) {
            "query" -> MigrationAction.Query(node["statement"]!!.textValue()!!, parameters(), node["purpose"]!!.textValue()!!)
            "execute" -> MigrationAction.Execute(node["statement"]!!.textValue()!!, parameters(), node["purpose"]!!.textValue()!!)
            "transaction" -> MigrationAction.Transaction(node["statements"]!!.map { actionOf(it) as MigrationAction.Execute })
            "prepare" -> MigrationAction.Prepare
            "assignStamp" -> MigrationAction.AssignStamp
            "lock" -> MigrationAction.Lock(node["statement"]!!.textValue()!!)
            else -> fail("No action ${node["type"]}")
        }
    }
}
