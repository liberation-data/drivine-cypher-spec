package org.drivine.cypher.store.plan

import org.drivine.cypher.store.ddl.DdlStatement
import org.drivine.cypher.store.ddl.OracleDdl
import org.drivine.cypher.store.ddl.PostgresDdl
import org.drivine.cypher.store.ddl.SqliteDdl
import org.drivine.cypher.store.schema.Cause
import org.drivine.cypher.store.schema.OracleSchema
import org.drivine.cypher.store.schema.PostgresSchema
import org.drivine.cypher.store.schema.StoreError
import org.drivine.schema.EnsureResult
import org.drivine.schema.FullTextIndexSpec
import org.drivine.schema.RangeIndexSpec
import org.drivine.schema.SchemaItemInfo
import org.drivine.schema.SchemaItemKind
import org.drivine.schema.SchemaItemSpec
import org.drivine.schema.SchemaMode
import org.drivine.schema.SchemaPolicy
import org.drivine.schema.UniquenessConstraintSpec
import org.drivine.schema.VectorIndexSpec
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * An index built online, so that writes to the graph go on while it is built: Postgres's `CONCURRENTLY` and Oracle's
 * `ONLINE`, and what an ensure does where the store refuses that, or where a build was cut short.
 */
class OnlineIndexBuildTest {

    private val postgresDdl = PostgresDdl("drivine")
    private val postgres = SchemaPlanner(PostgresSchema(postgresDdl))
    private val oracleDdl = OracleDdl("drivine")
    private val oracle = SchemaPlanner(OracleSchema(oracleDdl))
    private val name = RangeIndexSpec("Person", "name")
    private val born = RangeIndexSpec("Person", "born")

    /** Postgres's refusal of `CONCURRENTLY` in a transaction. */
    private val inTransaction = StoreError(Cause("25001", 0, null, null, "CREATE INDEX CONCURRENTLY cannot run inside a transaction block"))

    /** Oracle's refusal of `ONLINE` on an edition without it. */
    private val noOnline = StoreError(Cause("72000", 439, null, null, "ORA-00439: feature not enabled: Online Index Build"))

    /** What ran, each DDL statement's text in order, and the report, of [planner] driven to its end as a library drives it. */
    private fun drive(
        planner: SchemaPlanner,
        vararg items: SchemaItemSpec,
        listed: (statement: String) -> List<Any?> = { emptyList() },
        sampled: List<Any?> = emptyList(),
        execute: (statement: String) -> StoreError? = { null },
        call: ItemCall = ItemCall.ENSURE,
        policy: SchemaPolicy? = null,
        claimed: (take: Int) -> List<Any?>? = { listOf("claim $it") },
    ): Pair<List<String>, DatabaseReport> {
        val ran = mutableListOf<String>()
        turns.clear()
        // On Postgres, each uniqueness constraint whose create ran, by its index's name, listed after it, as the store lists
        // its record, until a statement drops it: where [listed] gives no rows.
        val made = linkedMapOf<String, UniquenessConstraintSpec>()
        val constraints = items.filterIsInstance<UniquenessConstraintSpec>().associateBy { postgresDdl.objectName(PostgresDdl.UNIQUE, listOf(it.label) + it.properties) }
        fun madeBy(text: String) {
            if (planner !== postgres) return
            for ((name, spec) in constraints) {
                if (text.startsWith("CREATE UNIQUE INDEX") && " $name ON " in text || "RENAME TO $name" in text) made[name] = spec
                else if (shown(text).startsWith("DROP INDEX") && shown(text).trimEnd().endsWith(" $name")) made.remove(name)
            }
        }
        // One item as IndexManager makes it; more, or any under a [policy], as an owner's enforce does.
        val request = items.singleOrNull()?.takeIf { policy == null }?.let { PlanRequest("default", Operation.Item(call, it)) }
            ?: PlanRequest("default", Operation.Enforce(), listOf(DeclaredCatalog(null, items = items.toList())), policy = policy ?: SchemaPolicy())
        // The store's clock, as each poll's wait moves it on.
        var now = 0L
        var step = planner.start(request, now)
        while (step is PlanStep.Run) {
            val outcomes = mutableListOf<PlanOutcome>()
            for (action in step.actions) {
                if (action is PlanAction.Query) now += action.delayMillis
                val outcome = when {
                    // A take of a turn to build ([PostgresDdl.buildClaim]): its row where [claimed] gives one, and failed where it gives null.
                    action is PlanAction.Query && action.statement.startsWith("INSERT INTO drivine_schema AS c") ->
                        claimed(turns.count { it.startsWith("take") }).also { turns += "take ${it?.size ?: "failed"}" }
                            ?.let { PlanOutcome.Rows(it) } ?: PlanOutcome.Failed(StoreError(Cause("42501", 0, null, null, "permission denied for table drivine_schema")))
                    // Oracle's take and release of a turn, each a block of its own transaction marked `at`, run as a query that
                    // gives no rows: answered as [execute] answers a statement, failed where it gives an error.
                    action is PlanAction.Query && action.statement.startsWith("DECLARE") && "'CLAIM:" in action.statement -> {
                        turns += (if ("INSERT INTO" in action.statement) "take '" else "release '") + action.parameters["at"] + "'"
                        execute(action.statement)?.let { PlanOutcome.Failed(it) } ?: PlanOutcome.Rows(emptyList())
                    }
                    // Oracle's count of the claims held: as [listed] gives it, failed where [execute] gives an error.
                    action is PlanAction.Query && action.statement.startsWith("SELECT COUNT(*) FROM drivine_schema WHERE kind = 'CLAIM:") ->
                        execute(action.statement)?.let { PlanOutcome.Failed(it) } ?: PlanOutcome.Rows(listed(action.statement))
                    // Its release, a query too: answered as [execute] answers a statement, failed where it gives an error.
                    action is PlanAction.Query && action.statement.startsWith("DELETE FROM drivine_schema WHERE kind = 'CLAIM:") -> {
                        turns += "release '${action.parameters["at"]}'"
                        execute(action.statement)?.let { PlanOutcome.Failed(it) } ?: PlanOutcome.Rows(emptyList())
                    }
                    else -> when (action) {
                    is PlanAction.Query -> PlanOutcome.Rows(
                        when {
                            action.purpose == Purpose.LISTING -> listed(action.statement).ifEmpty {
                                if (action.statement != postgresDdl.listConstraintsQuery()) emptyList()
                                else ListingRows.rows("postgres", made.values.map(SchemaItemInfo::fromSpec), constraints = true)
                            }
                            action.purpose == Purpose.SAMPLE -> sampled
                            else -> emptyList()
                        },
                    )
                    is PlanAction.Execute -> {
                        val text = (action.statement as DdlStatement.Text).statement
                        if (action.purpose == Purpose.DDL) ran += text
                        execute(text)?.let { PlanOutcome.Failed(it) } ?: PlanOutcome.Ran.also { madeBy(text) }
                    }
                    is PlanAction.ReadLowerCopies -> PlanOutcome.Rows(emptyList())
                    else -> PlanOutcome.Ran
                    }
                }
                outcomes += outcome
                // A library stops a round at its first failure.
                if (outcome is PlanOutcome.Failed) break
            }
            step = planner.resume(step.state, outcomes, now)
        }
        return ran to (step as PlanStep.Done).report
    }

    /** The turns the last [drive] claimed, `take <rows>` for each take and `release '<at>'` for each release, in order. */
    private val turns = mutableListOf<String>()

    private fun indexBuilds(ran: List<String>) =
        ran.map(::shown).filter { it.startsWith("CREATE INDEX") || it.startsWith("CREATE UNIQUE INDEX") || it.startsWith("DROP INDEX") }

    /** [text], a statement that ran, as [indexBuilds] gives it: Postgres's drop of an index left invalid ([PostgresDdl.droppedIncomplete]) as `DROP INDEX IF INVALID <name>`. */
    private fun shown(text: String): String =
        if (!text.startsWith("DO \$incomplete\$")) text else "DROP INDEX IF INVALID " + Regex("""DROP INDEX (\S+);""").find(text)!!.groupValues[1]

    @Test
    fun aPostgresIndexIsBuiltConcurrently() {
        val (ran, report) = drive(postgres, name)
        assertEquals(listOf("CREATE INDEX CONCURRENTLY IF NOT EXISTS ${postgresDdl.objectName(PostgresDdl.RANGE, listOf("Person", "name"))} ON"), indexBuilds(ran).map { it.substringBefore(" drivine_node") })
        assertIs<EnsureResult.Created>(report.item!!.result)
        assertEquals(emptyList(), report.log.filter { it.level == LogLevel.WARN })
    }

    @Test
    fun aPostgresIndexARefusedConcurrentBuildIsBuiltBlockingWritesAndSaidSoOnce() {
        val (ran, report) = drive(postgres, name, born, execute = { if (" CONCURRENTLY " in it) inTransaction else null })
        val names = listOf(name, born).map { postgresDdl.objectName(PostgresDdl.RANGE, listOf("Person") + it.properties) }
        // Tried online once; then each build blocks writes, the second with no try online first.
        assertEquals(
            listOf("CREATE INDEX CONCURRENTLY IF NOT EXISTS ${names[0]}", "CREATE INDEX IF NOT EXISTS ${names[0]}", "CREATE INDEX IF NOT EXISTS ${names[1]}"),
            indexBuilds(ran).map { it.substringBefore(" ON ") },
        )
        assertEquals(2, report.owners.single().results.count { it.result is EnsureResult.Created })
        assertNull(report.failure)
        assertEquals(
            listOf(
                LogLine(
                    LogLevel.WARN, "IndexManager",
                    "Postgres can't build an index online here (CREATE INDEX CONCURRENTLY cannot run inside a transaction block), so it is built by a " +
                        "statement that blocks writes to the graph's nodes until it is done. Ensure the schema outside a transaction to build it with writes going on.",
                ),
            ),
            report.log.filter { it.level == LogLevel.WARN },
        )
    }

    @Test
    fun aPostgresBuildRefusedForAnotherReasonFailsAndIsNotRunAgain() {
        val refused = StoreError(Cause("42501", 0, null, null, "permission denied for table drivine_node"))
        val (ran, report) = drive(postgres, name, execute = { if (it.startsWith("CREATE INDEX")) refused else null })
        assertEquals(1, indexBuilds(ran).size)
        // The store's own error, which the library throws as it got it.
        assertEquals(refused, report.rethrown!!.error)
    }

    @Test
    fun aPostgresIndexLeftInvalidIsDroppedAndBuiltAgain() {
        val invalid = ListingRows.rows("postgres", listOf(SchemaItemInfo(SchemaItemKind.RANGE_INDEX, "Person", listOf("name"), "Person_name_range", status = PostgresDdl.INVALID)), constraints = false)
        var rebuilt = false
        val (ran, report) = drive(
            postgres, name,
            listed = { if (it == postgresDdl.listIndexesQuery(SchemaItemKind.RANGE_INDEX) && !rebuilt) invalid else emptyList() },
            execute = { if (it.startsWith("CREATE INDEX")) rebuilt = true; null },
        )
        val index = postgresDdl.objectName(PostgresDdl.RANGE, listOf("Person", "name"))
        // Only the invalid index is dropped, by its name, and then it is built again online.
        assertEquals(listOf("DROP INDEX IF INVALID $index", "CREATE INDEX CONCURRENTLY IF NOT EXISTS $index"), indexBuilds(ran).map { it.substringBefore(" ON ") })
        assertIs<EnsureResult.Created>(report.item!!.result)
        assertTrue(
            LogLine(
                LogLevel.INFO, "IndexManager",
                "Completed Postgres RANGE_INDEX on Person[name]: its build had been cut short or had failed, which left it invalid, so it was dropped and built again",
            ) in report.log,
            report.log.toString(),
        )
        // Completed in its turn, as one made anew is (#382).
        assertEquals(listOf("take 1", "release 'claim 0'"), turns)
    }

    @Test
    fun anOracleIndexIsBuiltOnlineAndOneTheEditionRefusesIsBuiltBlockingWrites() {
        val (online, built) = drive(oracle, name)
        assertEquals(2, online.count { ") ONLINE'" in it }, online.joinToString("\n"))
        assertIs<EnsureResult.Created>(built.item!!.result)

        val (ran, report) = drive(oracle, name, execute = { if (") ONLINE'" in it) noOnline else null })
        // The shared index tried online, then both of the range index's indexes built without it.
        assertEquals(1, ran.count { ") ONLINE'" in it })
        assertEquals(2, ran.count { "EXECUTE IMMEDIATE 'CREATE INDEX IF NOT EXISTS" in it && ") ONLINE'" !in it })
        assertIs<EnsureResult.Created>(report.item!!.result)
        assertEquals(
            listOf(
                LogLine(
                    LogLevel.WARN, "IndexManager",
                    "Oracle can't build an index online here (ORA-00439: feature not enabled: Online Index Build), so it is built by a statement that blocks " +
                        "writes to the graph's nodes until it is done. An edition with online index builds (Enterprise Edition) builds it with writes going on.",
                ),
            ),
            report.log.filter { it.level == LogLevel.WARN },
        )
    }

    @Test
    fun eachStoreReadsOnlyItsOwnStatus() {
        fun status(schema: org.drivine.cypher.store.schema.StoreSchema, value: String) =
            schema.parseIndexRows(listOf(mapOf("kind" to "RANGE_INDEX", "label" to "Person", "properties" to listOf("name"), "status" to value))).single().status
        val postgresSchema = PostgresSchema(postgresDdl)
        val oracleSchema = OracleSchema(oracleDdl)
        assertEquals(PostgresDdl.INVALID, status(postgresSchema, PostgresDdl.INVALID))
        assertNull(status(postgresSchema, OracleDdl.INCOMPLETE))
        assertEquals(OracleDdl.INCOMPLETE, status(oracleSchema, OracleDdl.INCOMPLETE))
        assertNull(status(oracleSchema, PostgresDdl.INVALID))
    }

    @Test
    fun aBlockingFormIsGivenOnlyForAnIndexBuiltOnline() {
        val postgresCreate = postgresDdl.createIndex(name).map { (it as DdlStatement.Text).statement }.single { it.startsWith("CREATE INDEX") }
        assertEquals(DdlStatement.Text(postgresCreate.replace("CREATE INDEX CONCURRENTLY ", "CREATE INDEX ")), postgresDdl.blocking(DdlStatement.Text(postgresCreate)))
        assertEquals(DdlStatement.Text("DROP INDEX IF EXISTS x"), postgresDdl.blocking(DdlStatement.Text("DROP INDEX CONCURRENTLY IF EXISTS x")))
        assertNull(postgresDdl.blocking(postgresDdl.createIndex(name).first()))
        val oracleBlocks = oracleDdl.createIndex(name).mapNotNull { oracleDdl.blocking(it) }
        assertEquals(2, oracleBlocks.size)
        assertTrue(oracleBlocks.none { "ONLINE" in (it as DdlStatement.Text).statement })
        assertTrue(SqliteDdl("drivine").createIndex(name).none { SqliteDdl("drivine").blocking(it) != null })
    }

    @Test
    fun aPostgresVectorOrFullTextIndexARefusedConcurrentBuildIsBuiltBlockingWrites() {
        for (spec in listOf(VectorIndexSpec("Doc", "embedding", 3), FullTextIndexSpec("Doc", listOf("body")))) {
            val (ran, report) = drive(postgres, spec, execute = { if (" CONCURRENTLY " in it) inTransaction else null })
            val builds = indexBuilds(ran).map { it.substringBefore(" drivine_") }
            assertEquals(listOf("CREATE INDEX CONCURRENTLY IF NOT EXISTS", "CREATE INDEX IF NOT EXISTS"), builds, "$spec")
            assertIs<EnsureResult.Created>(report.item!!.result, "$spec")
            assertEquals(1, report.log.count { it.level == LogLevel.WARN }, "$spec: ${report.log}")
        }
    }

    @Test
    fun aPostgresIndexLeftInvalidInATransactionIsDroppedAndBuiltBlockingWrites() {
        val invalid = ListingRows.rows("postgres", listOf(SchemaItemInfo(SchemaItemKind.RANGE_INDEX, "Person", listOf("name"), "Person_name_range", status = PostgresDdl.INVALID)), constraints = false)
        var rebuilt = false
        val (ran, report) = drive(
            postgres, name,
            listed = { if (it == postgresDdl.listIndexesQuery(SchemaItemKind.RANGE_INDEX) && !rebuilt) invalid else emptyList() },
            execute = { if (" CONCURRENTLY " in it) inTransaction else { if (it.startsWith("CREATE INDEX")) rebuilt = true; null } },
        )
        val index = postgresDdl.objectName(PostgresDdl.RANGE, listOf("Person", "name"))
        // The drop, a block, runs in the transaction as it is; the build is refused online and run blocking.
        assertEquals(
            listOf("DROP INDEX IF INVALID $index", "CREATE INDEX CONCURRENTLY IF NOT EXISTS $index", "CREATE INDEX IF NOT EXISTS $index"),
            indexBuilds(ran).map { it.substringBefore(" ON ") },
        )
        assertIs<EnsureResult.Created>(report.item!!.result)
        assertEquals(1, report.log.count { it.level == LogLevel.WARN }, report.log.toString())
    }

    @Test
    fun aPostgresVectorOrFullTextIndexLeftInvalidIsDroppedAndBuiltAgain() {
        val vector = VectorIndexSpec("Doc", "embedding", 3)
        val fullText = FullTextIndexSpec("Doc", listOf("body"))
        // The one invalid index dropped by its name, then the create as it is.
        assertEquals(
            "DROP INDEX IF INVALID ${postgresDdl.objectName(PostgresDdl.VECTOR, listOf("Doc", "embedding"))}",
            shown((postgresDdl.completeIndex(vector, null).first() as DdlStatement.Text).statement),
        )
        assertEquals(
            "DROP INDEX IF INVALID ${postgresDdl.objectName(PostgresDdl.FULLTEXT, listOf("Doc", "body"))}",
            shown((postgresDdl.completeIndex(fullText, null).first() as DdlStatement.Text).statement),
        )
        assertEquals(postgresDdl.createIndex(vector), postgresDdl.completeIndex(vector, null).drop(1))
        assertEquals(postgresDdl.createIndex(fullText), postgresDdl.completeIndex(fullText, null).drop(1))
    }

    /** The listing's row of the object a range index on Person[name] is made as, as Postgres writes it, with [status]. */
    private fun nameIndexObject(status: String?) = mapOf(
        "object" to postgresDdl.objectName(PostgresDdl.RANGE, listOf("Person", "name")).lowercase(), "type" to "i", "table" to "drivine_node",
        "unique" to false, "method" to "btree", "where" to "labels @> '[\"Person\"]'::jsonb", "columns" to listOf("(props -> 'name'::text)"), "status" to status,
    )

    @Test
    fun aPostgresIndexLeftInvalidBeforeItsRecordWasWrittenIsDroppedAndBuiltAgain() {
        var rebuilt = false
        val (ran, report) = drive(
            postgres, name,
            listed = { if (it == postgresDdl.listIndexesQuery(SchemaItemKind.RANGE_INDEX) && !rebuilt) listOf(nameIndexObject(PostgresDdl.INVALID)) else emptyList() },
            execute = { if (it.startsWith("CREATE INDEX")) rebuilt = true; null },
        )
        val index = postgresDdl.objectName(PostgresDdl.RANGE, listOf("Person", "name"))
        assertEquals(listOf("DROP INDEX IF INVALID $index", "CREATE INDEX CONCURRENTLY IF NOT EXISTS $index"), indexBuilds(ran).map { it.substringBefore(" ON ") })
        assertIs<EnsureResult.Created>(report.item!!.result)
        assertTrue(
            LogLine(
                LogLevel.INFO, "IndexManager",
                "Completed Postgres RANGE_INDEX on Person[name]: its build had been cut short or had failed, which left it invalid, so it was dropped and built again",
            ) in report.log,
            report.log.toString(),
        )

        // One valid, whose record the process that built it hasn't written yet, is left to it.
        val (building, _) = drive(postgres, name, listed = { if (it == postgresDdl.listIndexesQuery(SchemaItemKind.RANGE_INDEX)) listOf(nameIndexObject(null)) else emptyList() })
        assertEquals(listOf("CREATE INDEX CONCURRENTLY IF NOT EXISTS $index"), indexBuilds(building).map { it.substringBefore(" ON ") })

        // A recreate finds no record to drop, and still builds the invalid one again.
        var remade = false
        val (recreated, recreate) = drive(
            postgres, name, call = ItemCall.RECREATE,
            listed = { if (it == postgresDdl.listIndexesQuery(SchemaItemKind.RANGE_INDEX) && !remade) listOf(nameIndexObject(PostgresDdl.INVALID)) else emptyList() },
            execute = { if (it.startsWith("CREATE INDEX")) remade = true; null },
        )
        assertEquals(listOf("DROP INDEX IF INVALID $index", "CREATE INDEX CONCURRENTLY IF NOT EXISTS $index"), indexBuilds(recreated).map { it.substringBefore(" ON ") })
        assertNull(recreate.failure)
    }

    @Test
    fun anInvalidIndexOfAnotherShapeUnderTheNameIsDriftAndNotDropped() {
        val other = nameIndexObject(PostgresDdl.INVALID) + ("columns" to listOf("(props -> 'nickname'::text)"))
        val (ran, report) = drive(postgres, name, listed = { if (it == postgresDdl.listIndexesQuery(SchemaItemKind.RANGE_INDEX)) listOf(other) else emptyList() })
        // Not Drivine's index, invalid or not: never dropped, nor built over.
        assertEquals(emptyList(), indexBuilds(ran))
        assertIs<EnsureResult.Drift>(report.item!!.result)
    }

    @Test
    fun aPostgresRecreateRefusedOnlineIsBuiltBlockingWrites() {
        val record = ListingRows.rows("postgres", listOf(SchemaItemInfo(SchemaItemKind.RANGE_INDEX, "Person", listOf("name"), "Person_name_range")), constraints = false)
        var dropped = false
        val (ran, report) = drive(
            postgres, name, call = ItemCall.RECREATE,
            listed = { if (it == postgresDdl.listIndexesQuery(SchemaItemKind.RANGE_INDEX) && !dropped) record else emptyList() },
            execute = { if (" CONCURRENTLY " in it) inTransaction else { if ("DO \$forget\$" in it) dropped = true; null } },
        )
        val index = postgresDdl.objectName(PostgresDdl.RANGE, listOf("Person", "name"))
        // The drop forgets the record and drops the index in a block, as before; only the build is tried online.
        assertTrue(ran.any { "DO \$forget\$" in it }, ran.joinToString("\n"))
        assertEquals(listOf("CREATE INDEX CONCURRENTLY IF NOT EXISTS $index", "CREATE INDEX IF NOT EXISTS $index"), indexBuilds(ran).map { it.substringBefore(" ON ") })
        assertIs<EnsureResult.Recreated>(report.item!!.result)
        assertEquals(1, report.log.count { it.level == LogLevel.WARN && "can't build an index online" in it.message }, report.log.toString())
    }

    @Test
    fun aLabelHoldingTheWordsIsNotTakenForAnOnlineBuild() {
        val odd = RangeIndexSpec("A INDEX CONCURRENTLY B) ONLINE", "name")
        val postgresStatements = postgresDdl.createIndex(odd)
        // Only the build is blocking's: the record, which holds the label, is left as it is.
        assertEquals(listOf(true), postgresStatements.filter { (it as DdlStatement.Text).statement.trim().startsWith("INSERT INTO") }.map { postgresDdl.blocking(it) == null })
        val build = postgresStatements.map { (it as DdlStatement.Text).statement }.single { it.startsWith("CREATE INDEX") }
        assertEquals(build.replaceFirst("CREATE INDEX CONCURRENTLY ", "CREATE INDEX "), (postgresDdl.blocking(DdlStatement.Text(build)) as DdlStatement.Text).statement)
        assertTrue("A INDEX CONCURRENTLY B" in (postgresDdl.blocking(DdlStatement.Text(build)) as DdlStatement.Text).statement)
        val oracleStatements = oracleDdl.createIndex(odd)
        assertEquals(2, oracleStatements.count { oracleDdl.blocking(it) != null })
        // The label's literal, "…B) ONLINE'", is not the end of a build's DDL.
        assertTrue(oracleStatements.filter { ") ONLINE';" !in (it as DdlStatement.Text).statement }.all { oracleDdl.blocking(it) == null })
        assertTrue(oracleStatements.mapNotNull { oracleDdl.blocking(it) }.none { ") ONLINE';" in (it as DdlStatement.Text).statement })
    }

    private val email = UniquenessConstraintSpec("Person", listOf("email"))
    private val emailIndex = postgresDdl.objectName(PostgresDdl.UNIQUE, listOf("Person", "email"))

    /** Postgres's refusal of a unique index over data that repeats a key. */
    private val repeated = StoreError(Cause("23505", 0, null, null, "could not create unique index"))

    /** A sample of a key two nodes hold. */
    private val sample = listOf(mapOf("properties" to mapOf("email" to "a@x"), "count" to 2L, "nodes" to listOf(mapOf("elementId" to 1L), mapOf("elementId" to 2L))))

    /** The listing's row of the unique index the constraint on Person[email] is made as, as Postgres writes it, with [status]. */
    private fun emailIndexObject(status: String?) = mapOf(
        "object" to emailIndex.lowercase(), "type" to "i", "table" to "drivine_node",
        "unique" to true, "method" to "btree", "where" to "labels @> '[\"Person\"]'::jsonb", "columns" to listOf("(props -> 'email'::text)"), "status" to status,
    )

    @Test
    fun aPostgresUniquenessConstraintIsBuiltConcurrently() {
        val (ran, report) = drive(postgres, email)
        assertEquals(listOf("CREATE UNIQUE INDEX CONCURRENTLY IF NOT EXISTS $emailIndex"), indexBuilds(ran).map { it.substringBefore(" ON ") })
        assertIs<EnsureResult.Created>(report.item!!.result)
    }

    @Test
    fun aPostgresUniquenessConstraintARefusedConcurrentBuildIsBuiltBlockingWrites() {
        val (ran, report) = drive(postgres, email, execute = { if (" CONCURRENTLY " in it) inTransaction else null })
        assertEquals(
            listOf("CREATE UNIQUE INDEX CONCURRENTLY IF NOT EXISTS $emailIndex", "CREATE UNIQUE INDEX IF NOT EXISTS $emailIndex"),
            indexBuilds(ran).map { it.substringBefore(" ON ") },
        )
        assertIs<EnsureResult.Created>(report.item!!.result)
        assertEquals(1, report.log.count { it.level == LogLevel.WARN && "can't build an index online" in it.message }, report.log.toString())
    }

    @Test
    fun aPostgresUniquenessConstraintTheDataRefusesHasItsInvalidIndexDropped() {
        val (ran, report) = drive(postgres, email, sampled = sample, execute = { if (it.startsWith("CREATE UNIQUE INDEX")) repeated else null })
        // The build the data refused left its index invalid under the name: dropped, so the next ensure builds it again.
        assertEquals(
            listOf("CREATE UNIQUE INDEX CONCURRENTLY IF NOT EXISTS $emailIndex", "DROP INDEX IF INVALID $emailIndex"),
            indexBuilds(ran).map { it.substringBefore(" ON ") },
        )
        assertEquals(sample, assertIs<EnsureResult.Violation>(report.item!!.result).conflictingSample)
    }

    @Test
    fun anotherSessionsBuildOfTheConstraintsIndexIsNotDropped() {
        // Two processes ensuring the constraint at once: the second's create meets the first's index name in the catalog,
        // 23505 too, but not the data's. The sample finds no key that repeats, so the index, the other session's, is left.
        val catalog = StoreError(Cause("23505", 0, null, null, "duplicate key value violates unique constraint \"pg_class_relname_nsp_index\""))
        val (ran, report) = drive(postgres, email, execute = { if (it.startsWith("CREATE UNIQUE INDEX")) catalog else null })
        assertTrue(ran.none { it.startsWith("DROP INDEX") }, ran.joinToString("\n"))
        assertTrue(report.rethrown != null, "$report")
    }

    @Test
    fun aCreateWhoseIndexAnotherSessionDroppedSinceIsMadeAgainNotReportedMade() {
        // Another session's build, refused by the data, left its index invalid under the name; this create passed over it
        // (`IF NOT EXISTS`), and that session dropped it before this one listed the constraints: the record this create wrote
        // names an index that isn't there, which the listing leaves out. Until this ensure's own build has run, the listing
        // holds only an object of another name.
        var creates = 0
        val elsewhere = emailIndexObject(null) + ("object" to "drivine_elsewhere")
        val (ran, report) = drive(
            postgres, email,
            listed = { if (it == postgresDdl.listConstraintsQuery() && creates < 2) listOf(elsewhere) else emptyList() },
            execute = { if (it.startsWith("CREATE UNIQUE INDEX")) creates++; null },
        )
        // Made again here, once, and reported only then.
        assertEquals(2, indexBuilds(ran).count { it.startsWith("CREATE UNIQUE INDEX CONCURRENTLY IF NOT EXISTS $emailIndex") }, ran.joinToString("\n"))
        assertIs<EnsureResult.Created>(report.item!!.result)
        assertEquals(1, report.log.count { it.message.startsWith("Created Postgres UNIQUENESS_CONSTRAINT") }, report.log.toString())
    }

    @Test
    fun aCreateThatPassedOverAnotherSessionsRefusedBuildIsNotReportedMade() {
        // Two processes ensuring the constraint at once over data that repeats a key: the second's create waits on the
        // table's lock for the first's online build, which the data refuses, and then passes over the index it left invalid
        // (IF NOT EXISTS). The listing read after says so: never Created, but made here, which the data refuses.
        val recorded = ListingRows.rows("postgres", listOf(SchemaItemInfo(SchemaItemKind.UNIQUENESS_CONSTRAINT, "Person", listOf("email"), "Person_email_unique", status = PostgresDdl.INVALID)), constraints = true)
        var creates = 0
        var dropped = false
        val (ran, report) = drive(
            postgres, email, sampled = sample,
            listed = { if (it == postgresDdl.listConstraintsQuery() && creates > 0 && !dropped) recorded else emptyList() },
            execute = {
                if (shown(it).startsWith("DROP INDEX")) dropped = true
                if (it.startsWith("CREATE UNIQUE INDEX")) { dropped = false; if (++creates > 1) repeated else null } else null
            },
        )
        assertEquals(
            listOf(
                "CREATE UNIQUE INDEX CONCURRENTLY IF NOT EXISTS $emailIndex", "DROP INDEX IF INVALID $emailIndex",
                "CREATE UNIQUE INDEX CONCURRENTLY IF NOT EXISTS $emailIndex", "DROP INDEX IF INVALID $emailIndex",
            ),
            indexBuilds(ran).map { it.substringBefore(" ON ") },
        )
        assertEquals(sample, assertIs<EnsureResult.Violation>(report.item!!.result).conflictingSample)
        assertTrue(report.log.none { it.message.startsWith("Created") }, report.log.toString())
    }

    @Test
    fun aPostgresUniquenessConstraintLeftInvalidIsDroppedAndBuiltAgain() {
        val recorded = ListingRows.rows("postgres", listOf(SchemaItemInfo(SchemaItemKind.UNIQUENESS_CONSTRAINT, "Person", listOf("email"), "Person_email_unique", status = PostgresDdl.INVALID)), constraints = true)
        // Recorded, or left before its record was written.
        for (invalid in listOf(recorded, listOf(emailIndexObject(PostgresDdl.INVALID)))) {
            var rebuilt = false
            val (ran, report) = drive(
                postgres, email,
                listed = { if (it == postgresDdl.listConstraintsQuery() && !rebuilt) invalid else emptyList() },
                execute = { if (it.startsWith("CREATE UNIQUE INDEX")) rebuilt = true; null },
            )
            assertEquals(
                listOf("DROP INDEX IF INVALID $emailIndex", "CREATE UNIQUE INDEX CONCURRENTLY IF NOT EXISTS $emailIndex"),
                indexBuilds(ran).map { it.substringBefore(" ON ") }, "$invalid",
            )
            assertIs<EnsureResult.Created>(report.item!!.result, "$invalid")
            assertTrue(
                LogLine(
                    LogLevel.INFO, "ConstraintManager",
                    "Completed Postgres UNIQUENESS_CONSTRAINT on Person[email]: its build had been cut short or had failed, which left it invalid, so it was dropped and built again",
                ) in report.log,
                report.log.toString(),
            )
        }
        // One valid, whose record the process that built it hasn't written yet, is not dropped.
        val (building, _) = drive(postgres, email, listed = { if (it == postgresDdl.listConstraintsQuery()) listOf(emailIndexObject(null)) else emptyList() })
        assertTrue(building.none { shown(it).startsWith("DROP INDEX") }, building.joinToString("\n"))
    }

    @Test
    fun aUniquenessConstraintLeftInvalidInAnotherFormIsDriftAndKeptAsItIs() {
        @Suppress("UNCHECKED_CAST")
        val record = ListingRows.rows("postgres", listOf(SchemaItemInfo(SchemaItemKind.UNIQUENESS_CONSTRAINT, "Person", listOf("email"), "Person_email_unique", status = PostgresDdl.INVALID)), constraints = true)
            .single() as Map<String, Any?>
        // An older version's, and a newer one's: made whole only in this version's form, so never touched here; and as nothing
        // holds the data unique, drift, never AlreadyMatching. Recorded, or still being built when listed and then left so.
        for (form in listOf(1L, 3L)) {
            val invalid = record + ("details" to mapOf("form" to form))
            for (building in listOf(false, true)) {
                var reads = 0
                val (ran, report) = drive(
                    postgres, email,
                    listed = {
                        if (it != postgresDdl.listConstraintsQuery()) emptyList()
                        else listOf(if (building && ++reads <= 2) invalid + ("status" to PostgresDdl.BUILDING) else invalid)
                    },
                )
                assertEquals(emptyList(), indexBuilds(ran), "form $form")
                assertIs<EnsureResult.Drift>(report.item!!.result, "form $form")
                assertTrue(report.log.none { "Completed" in it.message }, report.log.toString())
                assertTrue(
                    LogLine(
                        LogLevel.WARN, "ConstraintManager",
                        "Postgres UNIQUENESS_CONSTRAINT on Person[email]: its index was left invalid in another version's form, so nothing holds the data unique. " +
                            "Call recreate() to make it again in this version's form (destructive).",
                    ) in report.log,
                    report.log.toString(),
                )
            }
        }
    }

    @Test
    fun anOwnersUniquenessConstraintLeftInvalidInAnotherFormIsDriftAsAnyOtherIsAndRecreatedInThisForm() {
        val recreation = postgresDdl.recreation(email)
        @Suppress("UNCHECKED_CAST")
        val record = ListingRows.rows("postgres", listOf(SchemaItemInfo(SchemaItemKind.UNIQUENESS_CONSTRAINT, "Person", listOf("email"), "Person_email_unique", status = PostgresDdl.INVALID)), constraints = true)
            .single() as Map<String, Any?>
        for (form in listOf(1L, 3L)) {
            val invalid = listOf(record + ("details" to mapOf("form" to form)), emailIndexObject(PostgresDdl.INVALID))
            // Under FAIL_FAST the owner fails, naming it, and its marker is left as it was.
            val (failing, failed) = drive(postgres, email, policy = SchemaPolicy(), listed = { if (it == postgresDdl.listConstraintsQuery()) invalid else emptyList() })
            assertEquals(emptyList(), indexBuilds(failing), "form $form")
            val owner = failed.owners.single()
            assertIs<EnsureResult.Drift>(owner.results.single().result, "form $form")
            assertTrue("DRIFT: UNIQUENESS_CONSTRAINT on Person[email]" in owner.failure.orEmpty(), "${owner.failure}")
            assertNull(owner.marker, "form $form")
            // Under WARN it is logged and the marker written, as other drift is.
            val (warning, warned) = drive(postgres, email, policy = SchemaPolicy(SchemaMode.WARN), listed = { if (it == postgresDdl.listConstraintsQuery()) invalid else emptyList() })
            assertEquals(emptyList(), indexBuilds(warning), "form $form")
            assertNull(warned.owners.single().failure, "form $form")
            assertTrue(warned.owners.single().marker != null, "form $form")
            // recreateOnDrift makes it again in its place, in this version's form: built beside it, swapped in, its record remade.
            var swapped = false
            var remade = false
            val (recreating, recreated) = drive(
                postgres, email, policy = SchemaPolicy(SchemaMode.WARN, recreateOnDrift = true),
                listed = { if (it == postgresDdl.listConstraintsQuery() && !swapped) invalid else emptyList() },
                execute = {
                    if (it in recreation.swap.map { s -> (s as DdlStatement.Text).statement }) swapped = true
                    if (it == recreation.remade) remade = true
                    null
                },
            )
            assertEquals(
                listOf(
                    "DROP INDEX IF EXISTS ${recreation.temporary}", "CREATE UNIQUE INDEX ${recreation.temporary}",
                    "CREATE UNIQUE INDEX CONCURRENTLY IF NOT EXISTS $emailIndex",
                ),
                indexBuilds(recreating).map { it.substringBefore(" ON ") }, "form $form",
            )
            assertTrue(swapped, recreating.joinToString("\n"))
            assertTrue(recreating.none { shown(it).startsWith("DROP INDEX IF INVALID") }, recreating.joinToString("\n"))
            assertIs<EnsureResult.Recreated>(recreated.owners.single().results.single().result, "form $form")
            assertTrue(remade, "form $form: its record is not made this version's form")
        }
    }

    @Test
    fun anInvalidUniqueIndexOfAnotherShapeUnderTheConstraintsNameIsNotDropped() {
        val other = emailIndexObject(PostgresDdl.INVALID) + ("columns" to listOf("(props -> 'nickname'::text)"))
        val (ran, report) = drive(postgres, email, listed = { if (it == postgresDdl.listConstraintsQuery()) listOf(other) else emptyList() })
        // Not Drivine's index, invalid or not: never dropped, nor built over.
        assertEquals(emptyList(), indexBuilds(ran))
        assertTrue(report.item!!.result !is EnsureResult.Created, "${report.item!!.result}")
    }

    @Test
    fun aUniqueIndexEnsuredAfterAnotherItemsDdlIsToldByTheListingReadAgain() {
        // The index ensured first runs DDL, which clears the listings: the constraint's are read again before its form is.
        val other = emailIndexObject(PostgresDdl.INVALID) + ("columns" to listOf("(props -> 'nickname'::text)"))
        val (mismatched, drift) = drive(postgres, name, email, listed = { if (it == postgresDdl.listConstraintsQuery()) listOf(other) else emptyList() })
        assertTrue(mismatched.none { it.startsWith("CREATE UNIQUE INDEX") || shown(it).startsWith("DROP INDEX") }, mismatched.joinToString("\n"))
        assertIs<EnsureResult.Drift>(drift.owners.single().results.last().result)
        // And one left invalid before its record was written is dropped and built again.
        var rebuilt = false
        val (ran, report) = drive(
            postgres, name, email,
            listed = { if (it == postgresDdl.listConstraintsQuery() && !rebuilt) listOf(emailIndexObject(PostgresDdl.INVALID)) else emptyList() },
            execute = { if (it.startsWith("CREATE UNIQUE INDEX")) rebuilt = true; null },
        )
        assertEquals(
            listOf("DROP INDEX IF INVALID $emailIndex", "CREATE UNIQUE INDEX CONCURRENTLY IF NOT EXISTS $emailIndex"),
            indexBuilds(ran).filter { emailIndex in it }.map { it.substringBefore(" ON ") },
        )
        assertIs<EnsureResult.Created>(report.owners.single().results.last().result)
    }

    @Test
    fun aPostgresUniquenessConstraintTheDataRefusesInATransactionIsDroppedBlockingWrites() {
        val (ran, report) = drive(
            postgres, email, sampled = sample,
            execute = { if (" CONCURRENTLY " in it) inTransaction else if (it.startsWith("CREATE UNIQUE INDEX")) repeated else null },
        )
        // Built blocking writes once refused online; the drop after the data refuses it is a block, which runs in the transaction as it is.
        assertEquals(
            listOf(
                "CREATE UNIQUE INDEX CONCURRENTLY IF NOT EXISTS $emailIndex", "CREATE UNIQUE INDEX IF NOT EXISTS $emailIndex",
                "DROP INDEX IF INVALID $emailIndex",
            ),
            indexBuilds(ran).map { it.substringBefore(" ON ") },
        )
        assertEquals(sample, assertIs<EnsureResult.Violation>(report.item!!.result).conflictingSample)
    }

    @Test
    fun aPostgresUniqueRecreateTheDataRefusesBuildsBlockingWritesAndLeavesNothing() {
        val record = ListingRows.rows("postgres", listOf(SchemaItemInfo(SchemaItemKind.UNIQUENESS_CONSTRAINT, "Person", listOf("email"), "Person_email_unique")), constraints = true)
        val (ran, report) = drive(
            postgres, email, call = ItemCall.RECREATE, sampled = sample,
            listed = { if (it == postgresDdl.listConstraintsQuery()) record else emptyList() },
            execute = { if ("CREATE UNIQUE INDEX" in it) repeated else null },
        )
        val recreation = postgresDdl.recreation(email)
        val build = ran.single { it.startsWith("CREATE UNIQUE INDEX") }
        // Built beside the old index blocking writes, so no other session sees it part built, nor one left invalid: refused,
        // it leaves nothing, and nothing is run after it; the old index and its record stand.
        assertTrue(build.startsWith("CREATE UNIQUE INDEX ${recreation.temporary} ON "), build)
        assertEquals(emptyList(), ran.drop(ran.indexOf(build) + 1))
        assertIs<EnsureResult.Violation>(report.item!!.result)
    }

    @Test
    fun theIndexARecreateBuiltFirstLeftInvalidIsNotTakenAsHoldingTheConstraint() {
        val recreation = postgresDdl.recreation(email)
        // An index under the name a recreate builds first, left invalid (no recreate of this version leaves one so, but a session
        // of another, or a REINDEX CONCURRENTLY cut short, can), where nothing stood under the constraint's name: it holds nothing unique.
        var leftover = emailIndexObject(PostgresDdl.INVALID) + ("object" to recreation.temporary.lowercase())
        var built = false
        val listed = { statement: String -> if (statement == postgresDdl.listConstraintsQuery() && !built) listOf(leftover) else emptyList() }
        val execute = { statement: String -> if (statement.startsWith("CREATE UNIQUE INDEX")) built = true; null }
        // An ensure doesn't keep it as the constraint's index: it makes the constraint. Nor one still being built online,
        // which another session's build may yet leave invalid.
        for (status in listOf(PostgresDdl.INVALID, PostgresDdl.BUILDING)) {
            leftover = emailIndexObject(status) + ("object" to recreation.temporary.lowercase())
            built = false
            val (ensured, report) = drive(postgres, email, listed = listed, execute = execute)
            assertEquals(listOf("CREATE UNIQUE INDEX CONCURRENTLY IF NOT EXISTS $emailIndex"), indexBuilds(ensured).map { it.substringBefore(" ON ") }, status)
            assertIs<EnsureResult.Created>(report.item!!.result, status)
            assertTrue(report.log.none { "is held by" in it.message }, report.log.toString())
        }
        leftover = emailIndexObject(PostgresDdl.INVALID) + ("object" to recreation.temporary.lowercase())
        // A recreate doesn't put it in the constraint's place: it drops it, and builds the index again.
        built = false
        val (recreated, _) = drive(postgres, email, call = ItemCall.RECREATE, listed = listed, execute = execute)
        assertTrue(recreated.none { it == recreation.finish.map { f -> (f as DdlStatement.Text).statement }.single() }, recreated.joinToString("\n"))
        assertEquals(
            listOf("DROP INDEX IF EXISTS ${recreation.temporary}", "CREATE UNIQUE INDEX ${recreation.temporary}"),
            indexBuilds(recreated).take(2).map { it.substringBefore(" ON ") },
        )
        // A remove still drops it, as it may go on refusing keys for a constraint no one declares.
        built = false
        val (dropped, _) = drive(postgres, email, call = ItemCall.DROP, listed = listed, execute = execute)
        assertTrue("DROP INDEX IF EXISTS ${recreation.temporary}" in dropped, dropped.joinToString("\n"))
    }

    @Test
    fun aRecreateOfAUniquenessConstraintLeftInvalidBuildsItsIndexBesideItAndSwapsItIn() {
        val recreation = postgresDdl.recreation(email)
        val record = ListingRows.rows("postgres", listOf(SchemaItemInfo(SchemaItemKind.UNIQUENESS_CONSTRAINT, "Person", listOf("email"), "Person_email_unique", status = PostgresDdl.INVALID)), constraints = true)
        val (ran, report) = drive(
            postgres, email, call = ItemCall.RECREATE,
            listed = { if (it == postgresDdl.listConstraintsQuery()) record + listOf(emailIndexObject(PostgresDdl.INVALID)) else emptyList() },
        )
        // Not dropped on its own, nor finished as a cut-off recreate: the new index is built beside it, and the swap drops it;
        // the constraint's create then finds it made.
        assertTrue(ran.none { shown(it).startsWith("DROP INDEX IF INVALID") }, ran.joinToString("\n"))
        assertEquals(
            listOf(
                "DROP INDEX IF EXISTS ${recreation.temporary}", "CREATE UNIQUE INDEX ${recreation.temporary}",
                "CREATE UNIQUE INDEX CONCURRENTLY IF NOT EXISTS $emailIndex",
            ),
            indexBuilds(ran).map { it.substringBefore(" ON ") },
        )
        assertTrue(recreation.swap.map { (it as DdlStatement.Text).statement }.single() in ran, ran.joinToString("\n"))
        assertIs<EnsureResult.Recreated>(report.item!!.result)
    }

    @Test
    fun anOracleUniquenessConstraintsIndexesAreAllBuiltBlockingWrites() {
        // Its range index too, as before #382, where online builds of its range index beside the blocking build of its
        // unique index deadlocked in the library cache (ORA-04020); sessions ensuring it at once build it in their turns
        // (drivine4j#777). Its unique index blocks writes, as Oracle's online build of it over keys the data repeats is
        // not refused as a blocking one is.
        val (ran, report) = drive(oracle, email)
        assertTrue(ran.none { "ONLINE" in it }, ran.joinToString("\n"))
        assertTrue(ran.any { "CREATE UNIQUE INDEX IF NOT EXISTS" in it }, ran.joinToString("\n"))
        assertIs<EnsureResult.Created>(report.item!!.result)
        assertEquals(emptyList(), report.log.filter { it.level == LogLevel.WARN })
    }

    @Test
    fun anOracleUniquenessConstraintOverTwoPropertiesBuildsItsRangeIndexBlockingWritesAndAnIndexOfItsOwnOnline() {
        val pair = UniquenessConstraintSpec("Person", listOf("first", "last")).made()
        val (constrained, built) = drive(oracle, pair)
        // The range index under the constraint, its index over every node's properties and its labelled one, as its unique index.
        assertTrue(constrained.none { "ONLINE" in it }, constrained.joinToString("\n"))
        assertIs<EnsureResult.Created>(built.item!!.result)
        // The same range index ensured on its own is built online (#381).
        val (own, _) = drive(oracle, RangeIndexSpec("Person", listOf("first", "last")))
        assertEquals(2, own.count { ") ONLINE'" in it }, own.joinToString("\n"))
        // The unique index a recreate builds beside the old one blocks writes, so no other session ever sees it part built.
        val build = oracleDdl.recreation(pair).build.single() as DdlStatement.Text
        assertTrue("ONLINE" !in build.statement, build.statement)
        assertNull(oracleDdl.blocking(build))
    }

    @Test
    fun aUniqueIndexsBlockingFormKeepsItsUniqueness() {
        val create = postgresDdl.createConstraint(email).map { (it as DdlStatement.Text).statement }.single { it.startsWith("CREATE UNIQUE INDEX") }
        assertEquals(DdlStatement.Text(create.replace("CREATE UNIQUE INDEX CONCURRENTLY ", "CREATE UNIQUE INDEX ")), postgresDdl.blocking(DdlStatement.Text(create)))
        // A recreate's build blocks writes already: there is no other form of it.
        assertNull(postgresDdl.blocking(postgresDdl.recreation(email).build.single()))
    }

    /** The listing's record of the constraint on Person[email], with [status]. */
    private fun emailRecord(status: String?) =
        ListingRows.rows("postgres", listOf(SchemaItemInfo(SchemaItemKind.UNIQUENESS_CONSTRAINT, "Person", listOf("email"), "Person_email_unique", status = status)), constraints = true)

    /** The listing's record of the range index on Person[name], with [status]. */
    private fun nameRecord(status: String?) =
        ListingRows.rows("postgres", listOf(SchemaItemInfo(SchemaItemKind.RANGE_INDEX, "Person", listOf("name"), "Person_name_range", status = status)), constraints = false)

    @Test
    fun theListingTellsABuildUnderWayFromAnIndexLeftInvalid() {
        // Invalid while a build of it is under way: BUILDING, waited for; with none, INVALID, dropped and built again.
        for (query in listOf(postgresDdl.listConstraintsQuery(), postgresDdl.listIndexesQuery(SchemaItemKind.RANGE_INDEX))) {
            assertTrue("WHEN EXISTS (SELECT 1 FROM pg_stat_progress_create_index p WHERE p.index_relid = x.indexrelid" in query, query)
            assertTrue("THEN 'BUILDING' ELSE 'INVALID' END" in query, query)
            assertTrue("CASE WHEN i.indisvalid THEN NULL" in query, query)
        }
        assertEquals(setOf(PostgresDdl.INVALID, PostgresDdl.BUILDING), PostgresSchema(postgresDdl).parseConstraintRows(emailRecord(PostgresDdl.BUILDING) + emailRecord(PostgresDdl.INVALID)).map { it.status }.toSet())
    }

    @Test
    fun theDropOfAnIndexLeftInvalidLeavesOneAnotherSessionBuiltValidMeanwhile() {
        // Its table held first, and only then is it dropped, where it is still invalid.
        val drop = (postgresDdl.droppedIncomplete(email).single() as DdlStatement.Text).statement
        val lock = drop.indexOf("LOCK TABLE")
        val check = drop.indexOf("IF EXISTS (SELECT 1 FROM pg_index WHERE indexrelid = to_regclass('$emailIndex') AND NOT indisvalid)")
        assertTrue(lock in 0 until check && check < drop.indexOf("DROP INDEX $emailIndex;"), drop)
        // Not held at all while a build of it is under way, which the ensure waits for in the listing: a lock queued behind
        // the build would hold up every read and write of the table, and the block's snapshot the build's last phase.
        val unbuilt = drop.indexOf("p.index_relid = x.indexrelid")
        assertTrue(unbuilt in 0 until lock && drop.indexOf("THEN 'BUILDING' ELSE 'INVALID' END = 'INVALID'") in unbuilt until lock, drop)
        assertTrue("CONCURRENTLY" !in drop, drop)
        // A block, which a transaction runs as it is: nothing to run blocking writes in its place.
        assertNull(postgresDdl.blocking(postgresDdl.droppedIncomplete(email).single()))
    }

    @Test
    fun aConstraintAnotherSessionIsBuildingIsWaitedForAndTakenAsMadeOnceBuilt() {
        // Recorded, or not yet: its record is written once the build is done.
        for (building in listOf(emailRecord(PostgresDdl.BUILDING), listOf(emailIndexObject(PostgresDdl.BUILDING)))) {
            var reads = 0
            val (ran, report) = drive(
                postgres, email,
                listed = { if (it == postgresDdl.listConstraintsQuery()) (if (++reads <= 3) building else emailRecord(null)) else emptyList() },
            )
            assertEquals(emptyList(), indexBuilds(ran), "$building")
            assertIs<EnsureResult.AlreadyMatching>(report.item!!.result, "$building")
            assertEquals(4, reads, "$building")
        }
    }

    @Test
    fun aConstraintWhoseBuildInAnotherSessionTheDataRefusesIsDroppedAndMadeHere() {
        // Two ensures at once over an index left invalid: the other session's rebuild is waited for, and the index its
        // refused build leaves is dropped and built here, so the data's refusal is this ensure's too. Never AlreadyMatching
        // over a build under way, nor Completed over one that then fails.
        var reads = 0
        var dropped = false
        val (ran, report) = drive(
            postgres, email, sampled = sample,
            listed = {
                if (it != postgresDdl.listConstraintsQuery() || dropped) emptyList()
                else if (++reads <= 2) emailRecord(PostgresDdl.BUILDING) else emailRecord(PostgresDdl.INVALID)
            },
            execute = {
                if (shown(it).startsWith("DROP INDEX IF INVALID")) dropped = true
                if (it.startsWith("CREATE UNIQUE INDEX")) repeated else null
            },
        )
        assertEquals(
            listOf("DROP INDEX IF INVALID $emailIndex", "CREATE UNIQUE INDEX CONCURRENTLY IF NOT EXISTS $emailIndex", "DROP INDEX IF INVALID $emailIndex"),
            indexBuilds(ran).map { it.substringBefore(" ON ") },
        )
        assertEquals(sample, assertIs<EnsureResult.Violation>(report.item!!.result).conflictingSample)
        assertTrue(report.log.none { it.message.startsWith("Created") || it.message.startsWith("Completed") }, report.log.toString())
    }

    @Test
    fun aBuildAnotherSessionBeganAfterTheDropIsWaitedForNotBuiltOver() {
        // Listed invalid, and dropped; meanwhile another session built it again, which the drop leaves: waited for, and taken
        // as made once built, with no create queued behind it.
        var dropped = false
        var reads = 0
        val (ran, report) = drive(
            postgres, email,
            listed = {
                if (it != postgresDdl.listConstraintsQuery()) emptyList()
                else if (!dropped) emailRecord(PostgresDdl.INVALID) else if (++reads <= 2) emailRecord(PostgresDdl.BUILDING) else emailRecord(null)
            },
            execute = { if (shown(it).startsWith("DROP INDEX IF INVALID")) dropped = true; null },
        )
        assertEquals(listOf("DROP INDEX IF INVALID $emailIndex"), indexBuilds(ran).map { it.substringBefore(" ON ") })
        assertIs<EnsureResult.AlreadyMatching>(report.item!!.result)
        assertEquals(3, reads)
        // And one that build left invalid is built here.
        dropped = false
        reads = 0
        var built = false
        val (rebuilt, made) = drive(
            postgres, email,
            listed = {
                if (it != postgresDdl.listConstraintsQuery() || built) emptyList()
                else if (!dropped) emailRecord(PostgresDdl.INVALID) else if (++reads <= 2) emailRecord(PostgresDdl.BUILDING) else emailRecord(PostgresDdl.INVALID)
            },
            execute = { if (shown(it).startsWith("DROP INDEX IF INVALID")) dropped = true; if (it.startsWith("CREATE UNIQUE INDEX")) built = true; null },
        )
        assertEquals(listOf("DROP INDEX IF INVALID $emailIndex", "CREATE UNIQUE INDEX CONCURRENTLY IF NOT EXISTS $emailIndex"), indexBuilds(rebuilt).map { it.substringBefore(" ON ") })
        assertIs<EnsureResult.Created>(made.item!!.result)
    }

    @Test
    fun aCreateThatPassedOverAnotherSessionsBuildUnderWayWaitsForIt() {
        // The create passes over the index (IF NOT EXISTS) while the other session's build is under way: waited for, and
        // made only once it is built.
        var creates = 0
        var reads = 0
        val (ran, report) = drive(
            postgres, email,
            listed = {
                if (it != postgresDdl.listConstraintsQuery() || creates == 0) emptyList()
                else if (++reads <= 2) listOf(emailIndexObject(PostgresDdl.BUILDING)) else emailRecord(null)
            },
            execute = { if (it.startsWith("CREATE UNIQUE INDEX")) creates++; null },
        )
        assertEquals(1, indexBuilds(ran).size, ran.joinToString("\n"))
        assertIs<EnsureResult.Created>(report.item!!.result)
        assertEquals(3, reads)
    }

    @Test
    fun aConstraintNotMadeTwiceOverAnotherSessionsBuildsIsThrownNotReportedMade() {
        val elsewhere = emailIndexObject(null) + ("object" to "drivine_elsewhere")
        val (ran, report) = drive(postgres, email, listed = { if (it == postgresDdl.listConstraintsQuery()) listOf(elsewhere) else emptyList() })
        assertEquals(2, indexBuilds(ran).count { it.startsWith("CREATE UNIQUE INDEX") }, ran.joinToString("\n"))
        assertTrue(report.item?.result !is EnsureResult.Created, "${report.item}")
        assertTrue("UNIQUENESS_CONSTRAINT on Person[email] was not made" in report.failure.orEmpty(), "$report")
    }

    @Test
    fun aConstraintAnotherSessionIsStillBuildingPastTheWaitIsThrown() {
        val (ran, report) = drive(postgres, email, listed = { if (it == postgresDdl.listConstraintsQuery()) emailRecord(PostgresDdl.BUILDING) else emptyList() })
        assertEquals(emptyList(), indexBuilds(ran))
        assertTrue("waiting for constraint on Person[email] to become operational" in report.failure.orEmpty(), "$report")
    }

    @Test
    fun anIndexAnotherSessionIsBuildingIsWaitedForAndReadAsItEnded() {
        val index = postgresDdl.objectName(PostgresDdl.RANGE, listOf("Person", "name"))
        val listing = postgresDdl.listIndexesQuery(SchemaItemKind.RANGE_INDEX)
        // Built: taken as made, nothing run.
        for (building in listOf(nameRecord(PostgresDdl.BUILDING), listOf(nameIndexObject(PostgresDdl.BUILDING)))) {
            var reads = 0
            val (ran, report) = drive(postgres, name, listed = { if (it == listing) (if (++reads <= 3) building else nameRecord(null)) else emptyList() })
            assertEquals(emptyList(), indexBuilds(ran), "$building")
            assertIs<EnsureResult.AlreadyMatching>(report.item!!.result, "$building")
        }
        // Cut short, or refused: dropped and built here.
        var reads = 0
        var rebuilt = false
        val (ran, report) = drive(
            postgres, name,
            listed = { if (it != listing || rebuilt) emptyList() else if (++reads <= 2) nameRecord(PostgresDdl.BUILDING) else nameRecord(PostgresDdl.INVALID) },
            execute = { if (it.startsWith("CREATE INDEX")) rebuilt = true; null },
        )
        assertEquals(listOf("DROP INDEX IF INVALID $index", "CREATE INDEX CONCURRENTLY IF NOT EXISTS $index"), indexBuilds(ran).map { it.substringBefore(" ON ") })
        assertIs<EnsureResult.Created>(report.item!!.result)
        // Still building past the ensure's timeout: said so, as an index of its own still being built is.
        val (still, late) = drive(postgres, name, listed = { if (it == listing) nameRecord(PostgresDdl.BUILDING) else emptyList() })
        assertEquals(emptyList(), indexBuilds(still))
        assertTrue("is still being built by another session" in late.failure.orEmpty(), "$late")
    }

    @Test
    fun anOwnersIndexAnotherSessionIsStillBuildingPastTheWaitIsLeftToBeBuiltAsAnUnbuiltIndexIs() {
        // An owner's enforce takes it as one of its own still being built: left, and waited for again on the next enforce;
        // under FAIL_FAST the owner fails, its marker unwritten, and under WARN it is logged and the marker written.
        val listing = postgresDdl.listIndexesQuery(SchemaItemKind.RANGE_INDEX)
        for (building in listOf(nameRecord(PostgresDdl.BUILDING), listOf(nameIndexObject(PostgresDdl.BUILDING)))) {
            val (failing, failed) = drive(postgres, name, policy = SchemaPolicy(), listed = { if (it == listing) building else emptyList() })
            assertEquals(emptyList(), indexBuilds(failing), "$building")
            val owner = failed.owners.single()
            assertIs<EnsureResult.AlreadyMatching>(owner.results.single().result, "$building")
            assertTrue(owner.unbuilt.single().stillBuilding, "$building")
            assertTrue("STILL BUILDING: Postgres RANGE_INDEX on Person[name] is still being built by another session" in owner.failure.orEmpty(), "${owner.failure}")
            assertNull(owner.marker, "$building")
            val (warning, warned) = drive(postgres, name, policy = SchemaPolicy(SchemaMode.WARN), listed = { if (it == listing) building else emptyList() })
            assertEquals(emptyList(), indexBuilds(warning), "$building")
            assertNull(warned.owners.single().failure, "$building")
            assertTrue(warned.owners.single().marker != null, "$building")
        }
    }

    @Test
    fun anIndexOfAnotherShapeBeingBuiltUnderTheNameIsDriftNotWaitedFor() {
        // Not Drivine's index, built or not: drift at once, as one left invalid is, never waited for to the timeout and
        // then taken as still being built.
        val rangeListing = postgresDdl.listIndexesQuery(SchemaItemKind.RANGE_INDEX)
        val other = nameIndexObject(PostgresDdl.BUILDING) + ("columns" to listOf("(props -> 'nickname'::text)"))
        for (listing in listOf(listOf(other), nameRecord(PostgresDdl.BUILDING) + listOf(other))) {
            var reads = 0
            val (ran, report) = drive(postgres, name, listed = { if (it == rangeListing) listing.also { reads++ } else emptyList() })
            assertEquals(emptyList(), indexBuilds(ran), "$listing")
            assertIs<EnsureResult.Drift>(report.item!!.result, "$listing")
            assertEquals(1, reads, "$listing")
        }
        val unique = emailIndexObject(PostgresDdl.BUILDING) + ("columns" to listOf("(props -> 'nickname'::text)"))
        for (listing in listOf(listOf(unique), emailRecord(PostgresDdl.BUILDING) + listOf(unique))) {
            var reads = 0
            val (ran, report) = drive(postgres, email, listed = { if (it == postgresDdl.listConstraintsQuery()) listing.also { reads++ } else emptyList() })
            assertEquals(emptyList(), indexBuilds(ran), "$listing")
            assertNull(report.failure, "$listing")
            assertTrue(report.item!!.result !is EnsureResult.Created && report.item!!.result !is EnsureResult.AlreadyMatching, "${report.item!!.result}")
            assertEquals(1, reads, "$listing")
        }
    }

    @Test
    fun aBuildUnderWayBeforeItsRecordWasWrittenThatIsCutShortIsDroppedAndBuiltHere() {
        // Another session's build with no record yet, which then ends invalid, its session gone: dropped and built here, the
        // index as the constraint, never taken as made over the invalid one it left.
        val index = postgresDdl.objectName(PostgresDdl.RANGE, listOf("Person", "name"))
        val rangeListing = postgresDdl.listIndexesQuery(SchemaItemKind.RANGE_INDEX)
        var reads = 0
        var rebuilt = false
        val (ran, report) = drive(
            postgres, name,
            listed = { if (it != rangeListing || rebuilt) emptyList() else listOf(nameIndexObject(if (++reads <= 2) PostgresDdl.BUILDING else PostgresDdl.INVALID)) },
            execute = { if (it.startsWith("CREATE INDEX")) rebuilt = true; null },
        )
        assertEquals(listOf("DROP INDEX IF INVALID $index", "CREATE INDEX CONCURRENTLY IF NOT EXISTS $index"), indexBuilds(ran).map { it.substringBefore(" ON ") })
        assertIs<EnsureResult.Created>(report.item!!.result)
        // Twice building, once invalid before the drop, and again in the ensure's turn to build it (#382).
        assertEquals(4, reads)

        reads = 0
        var dropped = false
        var built = false
        val (constraint, made) = drive(
            postgres, email,
            listed = {
                if (it != postgresDdl.listConstraintsQuery() || dropped) emptyList()
                else listOf(emailIndexObject(if (++reads <= 2) PostgresDdl.BUILDING else PostgresDdl.INVALID))
            },
            execute = { if (shown(it).startsWith("DROP INDEX IF INVALID")) dropped = true; if (it.startsWith("CREATE UNIQUE INDEX")) built = true; null },
        )
        assertEquals(
            listOf("DROP INDEX IF INVALID $emailIndex", "CREATE UNIQUE INDEX CONCURRENTLY IF NOT EXISTS $emailIndex"),
            indexBuilds(constraint).map { it.substringBefore(" ON ") },
        )
        assertTrue(built)
        assertIs<EnsureResult.Created>(made.item!!.result)
        assertTrue(made.log.any { it.message.startsWith("Completed Postgres UNIQUENESS_CONSTRAINT on Person[email]") }, made.log.toString())
    }

    /** Postgres's cancel of a statement deadlocked with another session's online build on the same table. */
    private val deadlocked = StoreError(Cause("40P01", 0, null, null, "deadlock detected"))

    @Test
    fun aConstraintsBuildCancelledAsADeadlockIsEnsuredAgainAndWaitsForTheOtherSessionsBuild() {
        // Two ensures at once: the other session's build goes on, and this one's, cancelled, is ensured again once no build
        // is under way on the table, which finds the other's built.
        val builds = postgresDdl.onlineBuildsQuery
        var creates = 0
        var polls = 0
        val (ran, report) = drive(
            postgres, email,
            listed = {
                when {
                    it == builds -> if (++polls <= 2) listOf(mapOf("pid" to 70)) else emptyList()
                    it == postgresDdl.listConstraintsQuery() && polls > 2 -> emailRecord(null)
                    else -> emptyList()
                }
            },
            execute = { if (it.startsWith("CREATE UNIQUE INDEX") && ++creates == 1) deadlocked else null },
        )
        assertEquals(1, indexBuilds(ran).size, ran.joinToString("\n"))
        assertEquals(3, polls)
        assertIs<EnsureResult.AlreadyMatching>(report.item!!.result)
        assertTrue(
            LogLine(
                LogLevel.INFO, "ConstraintManager",
                "Postgres cancelled a statement of the ensure of UNIQUENESS_CONSTRAINT on Person[email], deadlocked with another session's online build " +
                    "on the same table: ensuring it again once no build is under way there",
            ) in report.log,
            report.log.toString(),
        )
    }

    @Test
    fun anIndexsBuildCancelledAsADeadlockWithAnotherIndexsIsBuiltOnceThatBuildHasEnded() {
        // The other session builds another index on the table: this one's is not under way, so it is built again only once
        // no build is, where building it at once would deadlock again.
        val builds = postgresDdl.onlineBuildsQuery
        var creates = 0
        var polls = 0
        var pollsAtSecondCreate = -1
        val (ran, report) = drive(
            postgres, name,
            listed = { if (it == builds) (if (++polls <= 3) listOf(mapOf("pid" to 70)) else emptyList()) else emptyList() },
            execute = {
                if (it.startsWith("CREATE INDEX") && ++creates == 2) pollsAtSecondCreate = polls
                if (it.startsWith("CREATE INDEX") && creates == 1) deadlocked else null
            },
        )
        assertEquals(2, indexBuilds(ran).count { it.startsWith("CREATE INDEX CONCURRENTLY") }, ran.joinToString("\n"))
        assertEquals(4, pollsAtSecondCreate)
        assertIs<EnsureResult.Created>(report.item!!.result)
    }

    @Test
    fun anEnsureCancelledAsADeadlockEveryTimeIsThrownAfterItsTries() {
        val (ran, report) = drive(postgres, name, execute = { if (it.startsWith("CREATE INDEX")) deadlocked else null })
        // Three tries in all.
        assertEquals(3, indexBuilds(ran).size, ran.joinToString("\n"))
        // Said as what it is, never the store's bare deadlock.
        assertNull(report.rethrown, "$report")
        assertTrue(
            "Postgres cancelled the ensure of RANGE_INDEX on Person[name] 3 times, deadlocked each time with another session's online build on the same table " +
                "(deadlock detected). Nothing was made: ensure it again once those builds have ended." in report.failure.orEmpty(),
            "$report",
        )
        // Only a deadlock is ensured again: another refusal is thrown at once.
        val refused = StoreError(Cause("42501", 0, null, null, "permission denied for table drivine_node"))
        val (once, _) = drive(postgres, name, execute = { if (it.startsWith("CREATE INDEX")) refused else null })
        assertEquals(1, indexBuilds(once).size)
    }

    @Test
    fun onlyPostgresListsTheOnlineBuildsOnItsNodeTable() {
        assertTrue("pg_stat_progress_create_index p WHERE p.relid = to_regclass('drivine_node')" in postgresDdl.onlineBuildsQuery, postgresDdl.onlineBuildsQuery)
        assertNull(oracleDdl.onlineBuildsQuery)
        assertNull(SqliteDdl("drivine").onlineBuildsQuery)
    }

    @Test
    fun anEnsureCancelledAsADeadlockIsRunAgainWhereTheWaitForTheTablesBuildsRunsOut() {
        // A build on the table that outlasts the wait, as another role's hidden one may: the ensure is run again anyway,
        // and made where the store lets it, or said as cancelled where it deadlocks each time.
        val builds = postgresDdl.onlineBuildsQuery
        var creates = 0
        val (ran, report) = drive(
            postgres, name,
            listed = { if (it == builds) listOf(mapOf("pid" to 70)) else emptyList() },
            execute = { if (it.startsWith("CREATE INDEX") && ++creates == 1) deadlocked else null },
        )
        assertEquals(2, indexBuilds(ran).size, ran.joinToString("\n"))
        assertIs<EnsureResult.Created>(report.item!!.result)
        val (always, cancelled) = drive(
            postgres, name,
            listed = { if (it == builds) listOf(mapOf("pid" to 70)) else emptyList() },
            execute = { if (it.startsWith("CREATE INDEX")) deadlocked else null },
        )
        assertEquals(3, indexBuilds(always).size)
        assertNull(cancelled.rethrown, "$cancelled")
        assertTrue("deadlocked each time with another session's online build" in cancelled.failure.orEmpty(), "$cancelled")
    }

    @Test
    fun aBuildCancelledAsADeadlockMidBuildLeavesItsIndexInvalidWhichTheEnsureRunAgainDropsAndBuilds() {
        // The cancelled build is this session's, under way: Postgres leaves its index invalid under the name, unrecorded,
        // which the ensure run again drops and builds, as one cut short, rather than pass over it (IF NOT EXISTS).
        val index = postgresDdl.objectName(PostgresDdl.RANGE, listOf("Person", "name"))
        var creates = 0
        val (ran, report) = drive(
            postgres, name,
            listed = { if (it == postgresDdl.listIndexesQuery(SchemaItemKind.RANGE_INDEX) && creates == 1) listOf(nameIndexObject(PostgresDdl.INVALID)) else emptyList() },
            execute = { if (it.startsWith("CREATE INDEX") && ++creates == 1) deadlocked else null },
        )
        assertEquals(
            listOf("CREATE INDEX CONCURRENTLY IF NOT EXISTS $index", "DROP INDEX IF INVALID $index", "CREATE INDEX CONCURRENTLY IF NOT EXISTS $index"),
            indexBuilds(ran).map { it.substringBefore(" ON ") },
        )
        assertIs<EnsureResult.Created>(report.item!!.result)
        var uniqueCreates = 0
        val (constraint, made) = drive(
            postgres, email,
            listed = { if (it == postgresDdl.listConstraintsQuery() && uniqueCreates == 1) listOf(emailIndexObject(PostgresDdl.INVALID)) else emptyList() },
            execute = { if (it.startsWith("CREATE UNIQUE INDEX") && ++uniqueCreates == 1) deadlocked else null },
        )
        assertEquals(
            listOf("CREATE UNIQUE INDEX CONCURRENTLY IF NOT EXISTS $emailIndex", "DROP INDEX IF INVALID $emailIndex", "CREATE UNIQUE INDEX CONCURRENTLY IF NOT EXISTS $emailIndex"),
            indexBuilds(constraint).map { it.substringBefore(" ON ") },
        )
        assertIs<EnsureResult.Created>(made.item!!.result)
    }

    @Test
    fun theDropOfAnIndexLeftInvalidWaitsUntilNoBuildIsUnderWayOnTheTable() {
        // Another session builds another index on the table: the drop, which holds the table, waits for it rather than
        // queue behind it, which would hold up every read and write of the graph's nodes until it ended.
        val builds = postgresDdl.onlineBuildsQuery
        var polls = 0
        var pollsAtDrop = -1
        var rebuilt = false
        val (ran, report) = drive(
            postgres, email,
            listed = {
                when {
                    it == builds -> if (++polls <= 2) listOf(mapOf("pid" to 70)) else emptyList()
                    it == postgresDdl.listConstraintsQuery() && !rebuilt -> emailRecord(PostgresDdl.INVALID)
                    else -> emptyList()
                }
            },
            execute = {
                if (shown(it).startsWith("DROP INDEX IF INVALID")) pollsAtDrop = polls
                if (it.startsWith("CREATE UNIQUE INDEX")) rebuilt = true
                null
            },
        )
        assertEquals(listOf("DROP INDEX IF INVALID $emailIndex", "CREATE UNIQUE INDEX CONCURRENTLY IF NOT EXISTS $emailIndex"), indexBuilds(ran).map { it.substringBefore(" ON ") })
        assertEquals(3, pollsAtDrop)
        assertIs<EnsureResult.Created>(report.item!!.result)

        // So does a recreate's, of an index left invalid before its record was written.
        polls = 0
        pollsAtDrop = -1
        rebuilt = false
        val index = postgresDdl.objectName(PostgresDdl.RANGE, listOf("Person", "name"))
        val (recreated, recreate) = drive(
            postgres, name, call = ItemCall.RECREATE,
            listed = {
                when {
                    it == builds -> if (++polls <= 2) listOf(mapOf("pid" to 70)) else emptyList()
                    it == postgresDdl.listIndexesQuery(SchemaItemKind.RANGE_INDEX) && !rebuilt -> listOf(nameIndexObject(PostgresDdl.INVALID))
                    else -> emptyList()
                }
            },
            execute = {
                if (shown(it).startsWith("DROP INDEX IF INVALID")) pollsAtDrop = polls
                if (it.startsWith("CREATE INDEX")) rebuilt = true
                null
            },
        )
        assertEquals(listOf("DROP INDEX IF INVALID $index", "CREATE INDEX CONCURRENTLY IF NOT EXISTS $index"), indexBuilds(recreated).map { it.substringBefore(" ON ") })
        assertEquals(3, pollsAtDrop)
        assertNull(recreate.failure)
        // Off Postgres there is nothing to wait for: Oracle's builds of an index left incomplete read no such listing.
        assertNull(oracleDdl.onlineBuildsQuery)
    }

    @Test
    fun anIndexsEnsureDropsItsIndexLeftInvalidOnlyOnceNoBuildIsUnderWayOnTheTable() {
        // Left invalid with its record written, and before it was: each ensure's drop, which holds the table, waits for
        // another session's build of another index on it rather than queue behind it.
        val builds = postgresDdl.onlineBuildsQuery
        val listing = postgresDdl.listIndexesQuery(SchemaItemKind.RANGE_INDEX)
        val index = postgresDdl.objectName(PostgresDdl.RANGE, listOf("Person", "name"))
        for (invalid in listOf(nameRecord(PostgresDdl.INVALID), listOf(nameIndexObject(PostgresDdl.INVALID)))) {
            var polls = 0
            var pollsAtDrop = -1
            var rebuilt = false
            val (ran, report) = drive(
                postgres, name,
                listed = {
                    when {
                        it == builds -> if (++polls <= 2) listOf(mapOf("pid" to 70)) else emptyList()
                        it == listing && !rebuilt -> invalid
                        else -> emptyList()
                    }
                },
                execute = {
                    if (shown(it).startsWith("DROP INDEX IF INVALID")) pollsAtDrop = polls
                    if (it.startsWith("CREATE INDEX")) rebuilt = true
                    null
                },
            )
            assertEquals(listOf("DROP INDEX IF INVALID $index", "CREATE INDEX CONCURRENTLY IF NOT EXISTS $index"), indexBuilds(ran).map { it.substringBefore(" ON ") }, "$invalid")
            assertEquals(3, pollsAtDrop, "$invalid")
            assertIs<EnsureResult.Created>(report.item!!.result)
        }
    }

    // ----- Turns to build (#382) -----

    @Test
    fun aPostgresEnsureThatBuildsTakesItsTurnAndGivesItUpOnceMade() {
        for (item in listOf<SchemaItemSpec>(email, name)) {
            val (ran, report) = drive(postgres, item)
            assertIs<EnsureResult.Created>(report.item!!.result, "$item")
            // Taken once it would build, then built, then given up, by when it was taken.
            assertEquals(listOf("take 1", "release 'claim 0'"), turns, "$item")
            assertEquals(1, indexBuilds(ran).size, ran.joinToString("\n"))
        }
    }

    @Test
    fun aClaimGivenAsARowOfOneColumnIsReadAsItsValue() {
        // A library that gives the take's row as its columns: the one column is when it was claimed.
        val (ran, report) = drive(postgres, email, claimed = { listOf(listOf("claim $it")) })
        assertIs<EnsureResult.Created>(report.item!!.result)
        assertEquals(listOf("take 1", "release 'claim 0'"), turns)
        assertEquals(1, indexBuilds(ran).size, ran.joinToString("\n"))
    }

    @Test
    fun aTurnAnotherSessionHoldsIsWaitedForAndTheItemItMadeIsTakenAsMade() {
        // The turn is another session's for two takes; by the third it has made the constraint, which the ensure, in its
        // turn, reads and builds nothing.
        val (ran, report) = drive(
            postgres, email,
            claimed = { if (it < 2) emptyList() else listOf("claim $it") },
            listed = { if (it == postgresDdl.listConstraintsQuery() && turns.size >= 3) emailRecord(null) else emptyList() },
        )
        assertEquals(emptyList(), indexBuilds(ran))
        assertIs<EnsureResult.AlreadyMatching>(report.item!!.result)
        assertEquals(listOf("take 0", "take 0", "take 1", "release 'claim 2'"), turns)
    }

    @Test
    fun aTurnHeldElsewherePastTheWaitIsEnsuredWithoutItAndSaidSo() {
        val (ran, report) = drive(postgres, email, claimed = { emptyList() })
        assertIs<EnsureResult.Created>(report.item!!.result)
        assertEquals(1, indexBuilds(ran).count { it.startsWith("CREATE UNIQUE INDEX") })
        // Polled for the wait, nothing given up, as nothing was taken.
        // Every 200 ms (the planner's poll) for the 60 s of a build's default timeout.
        assertEquals(300, turns.size)
        assertTrue(turns.none { it.startsWith("release") })
        assertEquals(
            listOf("Postgres UNIQUENESS_CONSTRAINT on Person[email]: another session's ensure of it held its turn to build it for PT1M: ensuring it without"),
            report.log.filter { "its turn" in it.message }.map { it.message },
        )
    }

    @Test
    fun aBuildThatOutlastsTheWaitForItsTurnIsWaitedForNotBuiltAgain() {
        // Another session holds the turn and builds the constraint for longer than the 60 s this ensure waits for its turn:
        // ensured without it, the ensure finds that build under way, waits for it, and builds nothing of its own.
        var reads = 0
        val (ran, report) = drive(
            postgres, email,
            claimed = { emptyList() },
            listed = {
                when {
                    it != postgresDdl.listConstraintsQuery() || turns.isEmpty() -> emptyList()
                    ++reads <= 3 -> emailRecord(PostgresDdl.BUILDING)
                    else -> emailRecord(null)
                }
            },
        )
        assertEquals(emptyList(), indexBuilds(ran))
        assertIs<EnsureResult.AlreadyMatching>(report.item!!.result)
        assertEquals(300, turns.count { it.startsWith("take") })
        assertTrue(turns.none { it.startsWith("release") })
    }

    @Test
    fun aTurnThatCantBeTakenIsEnsuredWithoutAtOnce() {
        val (ran, report) = drive(postgres, email, claimed = { null })
        assertIs<EnsureResult.Created>(report.item!!.result)
        assertEquals(1, indexBuilds(ran).count { it.startsWith("CREATE UNIQUE INDEX") })
        assertEquals(listOf("take failed"), turns)
        assertNull(report.failure)
    }

    @Test
    fun aTurnIsGivenUpWhereTheEnsureFails() {
        // Not made twice over another session's builds: thrown, and the turn given up all the same.
        val elsewhere = emailIndexObject(null) + ("object" to "drivine_elsewhere")
        val (_, report) = drive(postgres, email, listed = { if (it == postgresDdl.listConstraintsQuery()) listOf(elsewhere) else emptyList() })
        assertTrue("UNIQUENESS_CONSTRAINT on Person[email] was not made" in report.failure.orEmpty(), "$report")
        assertEquals(listOf("take 1", "release 'claim 0'"), turns)
    }

    @Test
    fun aReleaseTheStoreRefusesIsLetBe() {
        // A claim left behind is taken over once it is old: the ensure is made all the same.
        val refused = StoreError(Cause("08006", 0, null, null, "connection lost"))
        val (_, report) = drive(postgres, email, execute = { if (it.startsWith("DELETE FROM drivine_schema WHERE kind = 'CLAIM:")) refused else null })
        assertIs<EnsureResult.Created>(report.item!!.result)
        assertNull(report.failure)
        assertNull(report.rethrown)
        assertEquals(listOf("take 1", "release 'claim 0'"), turns)
    }

    @Test
    fun whatAnEnsureSaysBeforeItTakesItsTurnIsSaidOnce() {
        // The ensure is run again in its turn: a pin the store can't make is said once all the same.
        val vector = VectorIndexSpec("Doc", "embedding", 3, hnswM = 16)
        val (_, report) = drive(postgres, vector)
        assertIs<EnsureResult.Created>(report.item!!.result)
        assertEquals(listOf("take 1", "release 'claim 0'"), turns)
        assertEquals(1, report.log.count { it.level == LogLevel.WARN && "pins" in it.message }, report.log.toString())
    }

    @Test
    fun anItemAlreadyMadeAndAStoreThatBuildsInTurnsOfItsOwnTakeNoTurn() {
        val (_, made) = drive(postgres, email, listed = { if (it == postgresDdl.listConstraintsQuery()) emailRecord(null) else emptyList() })
        assertIs<EnsureResult.AlreadyMatching>(made.item!!.result)
        assertEquals(emptyList(), turns)
        // SQLite's builds lock the database.
        val (_, report) = drive(SchemaPlanner(org.drivine.cypher.store.schema.SqliteSchema(SqliteDdl("drivine"))), email)
        assertIs<EnsureResult.Created>(report.item!!.result)
        assertEquals(emptyList(), turns)
        assertNull(SqliteDdl("drivine").buildClaim(email))
    }

    /** Oracle's count of the claims of [spec] another session holds, as [drive]'s listing gives it. */
    private fun oracleHeld(spec: SchemaItemSpec) = oracleDdl.buildClaim(spec).held!!

    @Test
    fun anOracleEnsureThatBuildsTakesItsTurnMarkedByItsClockAndGivesItUpOnceMade() {
        // Sessions issuing the same CREATE INDEX at once on one Oracle table deadlock in the library cache (drivine4j#777).
        for (item in listOf<SchemaItemSpec>(email, name)) {
            val (ran, report) = drive(oracle, item)
            assertIs<EnsureResult.Created>(report.item!!.result, "$item")
            assertEquals(listOf("take '0'", "release '0'"), turns, "$item")
            assertTrue(ran.isNotEmpty())
        }
    }

    @Test
    fun aTurnAnotherOracleSessionHoldsIsPolledForThenTaken() {
        var polls = 0
        val (_, report) = drive(oracle, email, listed = { if (it == oracleHeld(email)) listOf(if (++polls <= 2) 1L else 0L) else emptyList() })
        assertIs<EnsureResult.Created>(report.item!!.result)
        assertEquals(3, polls)
        // Taken once none is held, after two polls: marked with the clock then.
        assertEquals(listOf("take '400'", "release '400'"), turns)
    }

    @Test
    fun anOracleTakeRefusedAsAKeyTheCatalogHasIsPolledForAgain() {
        // Another session took it between the count and the take.
        var takes = 0
        val taken = StoreError(Cause("23000", 1, null, null, "ORA-00001: unique constraint violated"))
        val (_, report) = drive(oracle, email, execute = { if ("'CLAIM:" in it && "INSERT INTO" in it && ++takes == 1) taken else null })
        assertIs<EnsureResult.Created>(report.item!!.result)
        assertEquals(listOf("take '0'", "take '200'", "release '200'"), turns)
    }

    @Test
    fun anOracleTakeThatFailsOtherwiseIsEnsuredWithoutItsTurnAtOnce() {
        val refused = StoreError(Cause("42000", 1031, null, null, "ORA-01031: insufficient privileges"))
        val (_, report) = drive(oracle, email, execute = { if ("'CLAIM:" in it && "INSERT INTO" in it) refused else null })
        assertIs<EnsureResult.Created>(report.item!!.result)
        assertEquals(listOf("take '0'"), turns)
        assertNull(report.failure)
    }

    @Test
    fun anOracleCountOfClaimsTheStoreRefusesIsEnsuredWithoutItsTurnAtOnce() {
        val refused = StoreError(Cause("42000", 942, null, null, "ORA-00942: table or view does not exist"))
        val (ran, report) = drive(oracle, email, execute = { if (it == oracleHeld(email)) refused else null })
        assertIs<EnsureResult.Created>(report.item!!.result)
        assertTrue(ran.isNotEmpty())
        // Never taken, so nothing given up, and not waited for.
        assertEquals(emptyList(), turns)
        assertNull(report.failure)
        assertEquals(emptyList(), report.log.filter { "its turn" in it.message })
    }

    @Test
    fun anOracleTurnHeldElsewherePastTheWaitIsEnsuredWithoutItAndSaidSo() {
        val (ran, report) = drive(oracle, email, listed = { if (it == oracleHeld(email)) listOf(1L) else emptyList() })
        assertIs<EnsureResult.Created>(report.item!!.result)
        assertTrue(ran.isNotEmpty())
        // Counted every 200 ms for the 60 s of a build's default timeout, never taken, so nothing given up.
        assertEquals(emptyList(), turns)
        assertEquals(
            listOf("Oracle UNIQUENESS_CONSTRAINT on Person[email]: another session's ensure of it held its turn to build it for PT1M: ensuring it without"),
            report.log.filter { "its turn" in it.message }.map { it.message },
        )
    }

    @Test
    fun anOracleTurnIsGivenUpWhereTheEnsureFails() {
        val full = StoreError(Cause("72000", 1652, null, null, "ORA-01652: unable to extend temp segment"))
        val (_, report) = drive(oracle, email, execute = { if ("'CLAIM:" !in it && "CREATE" in it) full else null })
        assertTrue(report.failure != null || report.rethrown != null, "$report")
        assertEquals(listOf("take '0'", "release '0'"), turns)
    }

    @Test
    fun anOracleReleaseTheStoreRefusesIsLetBe() {
        val lost = StoreError(Cause("08006", 17410, null, null, "No more data to read from socket"))
        val (_, report) = drive(oracle, email, execute = { if (it.startsWith("DECLARE") && "'CLAIM:" in it && "INSERT INTO" !in it) lost else null })
        assertIs<EnsureResult.Created>(report.item!!.result)
        assertNull(report.failure)
        assertNull(report.rethrown)
        assertEquals(listOf("take '0'", "release '0'"), turns)
    }

    @Test
    fun anOracleClaimCommitsOnlyItselfAndIsTakenOverOnceOld() {
        val claim = oracleDdl.buildClaim(email)
        assertEquals(org.drivine.cypher.store.ddl.BuildClaim.Taken.RAN, claim.taken)
        for (statement in listOf(claim.take, claim.release)) {
            assertTrue(statement.startsWith("DECLARE\n    PRAGMA AUTONOMOUS_TRANSACTION;"), statement)
            assertTrue("COMMIT;" in statement, statement)
        }
        assertTrue("JSON_OBJECT('at' VALUE \$at, 'since' VALUE v_now)" in claim.take, claim.take)
        assertTrue("< v_now - 60 AND NOT EXISTS (" in claim.take && ">= (CAST(SYS_EXTRACT_UTC(SYSTIMESTAMP) AS DATE) - DATE '1970-01-01') * 86400 - 60 OR EXISTS (" in claim.held!!, claim.take)
        // Taken over only where no online build is under way on the node table, as on Postgres: an ONLINE build's journal.
        for (statement in listOf(claim.take, claim.held!!)) {
            assertTrue("'SYS_JOURNAL_' || x.object_id" in statement && "i.table_name = UPPER('drivine_node')" in statement, statement)
        }
        assertTrue("JSON_VALUE(details, '\$.at') = \$at" in claim.release, claim.release)
    }

    @Test
    fun aTurnIsClaimedByTheItemAndTakenOverOnlyOnceOldWithNoBuildUnderWay() {
        val claim = postgresDdl.buildClaim(email)
        assertTrue("VALUES ('CLAIM:UNIQUENESS_CONSTRAINT', 'Person', '[\"email\"]'" in claim.take, claim.take)
        assertTrue("interval '60 seconds'" in claim.take && "NOT EXISTS (${postgresDdl.onlineBuildsQuery})" in claim.take, claim.take)
        assertEquals(
            "DELETE FROM drivine_schema WHERE kind = 'CLAIM:UNIQUENESS_CONSTRAINT' AND label = 'Person' AND properties = '[\"email\"]' AND details ->> 'at' = CAST(\$at AS text) RETURNING kind",
            claim.release,
        )
        // An index and a constraint over the same properties take turns of their own.
        assertTrue("'CLAIM:RANGE_INDEX'" in postgresDdl.buildClaim(RangeIndexSpec("Person", "email")).take)
    }
}
