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
    ): Pair<List<String>, DatabaseReport> {
        val ran = mutableListOf<String>()
        // On Postgres, each uniqueness constraint whose create ran, by its index's name, listed after it, as the store lists
        // its record, until a statement drops it: where [listed] gives no rows.
        val made = linkedMapOf<String, UniquenessConstraintSpec>()
        val constraints = items.filterIsInstance<UniquenessConstraintSpec>().associateBy { postgresDdl.objectName(PostgresDdl.UNIQUE, listOf(it.label) + it.properties) }
        fun madeBy(text: String) {
            if (planner !== postgres) return
            for ((name, spec) in constraints) {
                if (text.startsWith("CREATE UNIQUE INDEX") && " $name ON " in text || "RENAME TO $name" in text) made[name] = spec
                else if (text.startsWith("DROP INDEX") && text.trimEnd().endsWith(" $name")) made.remove(name)
            }
        }
        // One item as IndexManager makes it; more as an owner's enforce does.
        val request = items.singleOrNull()?.let { PlanRequest("default", Operation.Item(call, it)) }
            ?: PlanRequest("default", Operation.Enforce(), listOf(DeclaredCatalog(null, items = items.toList())))
        var step = planner.start(request, 0)
        while (step is PlanStep.Run) {
            val outcomes = mutableListOf<PlanOutcome>()
            for (action in step.actions) {
                val outcome = when (action) {
                    is PlanAction.Query -> PlanOutcome.Rows(
                        when (action.purpose) {
                            Purpose.LISTING -> listed(action.statement).ifEmpty {
                                if (action.statement != postgresDdl.listConstraintsQuery()) emptyList()
                                else ListingRows.rows("postgres", made.values.map(SchemaItemInfo::fromSpec), constraints = true)
                            }
                            Purpose.SAMPLE -> sampled
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
                outcomes += outcome
                // A library stops a round at its first failure.
                if (outcome is PlanOutcome.Failed) break
            }
            step = planner.resume(step.state, outcomes, 0)
        }
        return ran to (step as PlanStep.Done).report
    }

    private fun indexBuilds(ran: List<String>) = ran.filter { it.startsWith("CREATE INDEX") || it.startsWith("CREATE UNIQUE INDEX") || it.startsWith("DROP INDEX") }

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
        assertEquals(listOf("DROP INDEX CONCURRENTLY IF EXISTS $index", "CREATE INDEX CONCURRENTLY IF NOT EXISTS $index"), indexBuilds(ran).map { it.substringBefore(" ON ") })
        assertIs<EnsureResult.Created>(report.item!!.result)
        assertTrue(
            LogLine(
                LogLevel.INFO, "IndexManager",
                "Completed Postgres RANGE_INDEX on Person[name]: its build had been cut short or had failed, which left it invalid, so it was dropped and built again",
            ) in report.log,
            report.log.toString(),
        )
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
    fun aPostgresIndexLeftInvalidWhoseDropIsRefusedOnlineIsDroppedAndBuiltBlockingWrites() {
        val invalid = ListingRows.rows("postgres", listOf(SchemaItemInfo(SchemaItemKind.RANGE_INDEX, "Person", listOf("name"), "Person_name_range", status = PostgresDdl.INVALID)), constraints = false)
        var rebuilt = false
        val (ran, report) = drive(
            postgres, name,
            listed = { if (it == postgresDdl.listIndexesQuery(SchemaItemKind.RANGE_INDEX) && !rebuilt) invalid else emptyList() },
            execute = { if (" CONCURRENTLY " in it) inTransaction else { if (it.startsWith("CREATE INDEX")) rebuilt = true; null } },
        )
        val index = postgresDdl.objectName(PostgresDdl.RANGE, listOf("Person", "name"))
        // The drop refused online and run blocking; the build after it with no try online.
        assertEquals(
            listOf("DROP INDEX CONCURRENTLY IF EXISTS $index", "DROP INDEX IF EXISTS $index", "CREATE INDEX IF NOT EXISTS $index"),
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
            DdlStatement.Text("DROP INDEX CONCURRENTLY IF EXISTS ${postgresDdl.objectName(PostgresDdl.VECTOR, listOf("Doc", "embedding"))}"),
            postgresDdl.completeIndex(vector, null).first(),
        )
        assertEquals(
            DdlStatement.Text("DROP INDEX CONCURRENTLY IF EXISTS ${postgresDdl.objectName(PostgresDdl.FULLTEXT, listOf("Doc", "body"))}"),
            postgresDdl.completeIndex(fullText, null).first(),
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
        assertEquals(listOf("DROP INDEX CONCURRENTLY IF EXISTS $index", "CREATE INDEX CONCURRENTLY IF NOT EXISTS $index"), indexBuilds(ran).map { it.substringBefore(" ON ") })
        assertIs<EnsureResult.Created>(report.item!!.result)
        assertTrue(
            LogLine(
                LogLevel.INFO, "IndexManager",
                "Completed Postgres RANGE_INDEX on Person[name]: its build had been cut short or had failed, which left it invalid, so it was dropped and built again",
            ) in report.log,
            report.log.toString(),
        )

        // One still being built, by another process that hasn't recorded it yet, is left to it.
        val (building, _) = drive(postgres, name, listed = { if (it == postgresDdl.listIndexesQuery(SchemaItemKind.RANGE_INDEX)) listOf(nameIndexObject(null)) else emptyList() })
        assertEquals(listOf("CREATE INDEX CONCURRENTLY IF NOT EXISTS $index"), indexBuilds(building).map { it.substringBefore(" ON ") })

        // A recreate finds no record to drop, and still builds the invalid one again.
        var remade = false
        val (recreated, recreate) = drive(
            postgres, name, call = ItemCall.RECREATE,
            listed = { if (it == postgresDdl.listIndexesQuery(SchemaItemKind.RANGE_INDEX) && !remade) listOf(nameIndexObject(PostgresDdl.INVALID)) else emptyList() },
            execute = { if (it.startsWith("CREATE INDEX")) remade = true; null },
        )
        assertEquals(listOf("DROP INDEX CONCURRENTLY IF EXISTS $index", "CREATE INDEX CONCURRENTLY IF NOT EXISTS $index"), indexBuilds(recreated).map { it.substringBefore(" ON ") })
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
            listOf("CREATE UNIQUE INDEX CONCURRENTLY IF NOT EXISTS $emailIndex", "DROP INDEX CONCURRENTLY IF EXISTS $emailIndex"),
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
                if (it.startsWith("DROP INDEX")) dropped = true
                if (it.startsWith("CREATE UNIQUE INDEX")) { dropped = false; if (++creates > 1) repeated else null } else null
            },
        )
        assertEquals(
            listOf(
                "CREATE UNIQUE INDEX CONCURRENTLY IF NOT EXISTS $emailIndex", "DROP INDEX CONCURRENTLY IF EXISTS $emailIndex",
                "CREATE UNIQUE INDEX CONCURRENTLY IF NOT EXISTS $emailIndex", "DROP INDEX CONCURRENTLY IF EXISTS $emailIndex",
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
                listOf("DROP INDEX CONCURRENTLY IF EXISTS $emailIndex", "CREATE UNIQUE INDEX CONCURRENTLY IF NOT EXISTS $emailIndex"),
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
        // One still being built, by another process that hasn't recorded it yet, is not dropped.
        val (building, _) = drive(postgres, email, listed = { if (it == postgresDdl.listConstraintsQuery()) listOf(emailIndexObject(null)) else emptyList() })
        assertTrue(building.none { it.startsWith("DROP INDEX") }, building.joinToString("\n"))
    }

    @Test
    fun aUniquenessConstraintLeftInvalidInAnotherFormIsKeptAsItIs() {
        @Suppress("UNCHECKED_CAST")
        val record = ListingRows.rows("postgres", listOf(SchemaItemInfo(SchemaItemKind.UNIQUENESS_CONSTRAINT, "Person", listOf("email"), "Person_email_unique", status = PostgresDdl.INVALID)), constraints = true)
            .single() as Map<String, Any?>
        // An older version's, and a newer one's: made whole only in this version's form, which is never reported made.
        for (form in listOf(1L, 3L)) {
            val (ran, report) = drive(
                postgres, email,
                listed = { if (it == postgresDdl.listConstraintsQuery()) listOf(record + ("details" to mapOf("form" to form))) else emptyList() },
            )
            assertEquals(emptyList(), indexBuilds(ran), "form $form")
            assertIs<EnsureResult.AlreadyMatching>(report.item!!.result, "form $form")
            assertTrue(report.log.none { "Completed" in it.message }, report.log.toString())
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
        assertTrue(mismatched.none { it.startsWith("CREATE UNIQUE INDEX") || it.startsWith("DROP INDEX") }, mismatched.joinToString("\n"))
        assertIs<EnsureResult.Drift>(drift.owners.single().results.last().result)
        // And one left invalid before its record was written is dropped and built again.
        var rebuilt = false
        val (ran, report) = drive(
            postgres, name, email,
            listed = { if (it == postgresDdl.listConstraintsQuery() && !rebuilt) listOf(emailIndexObject(PostgresDdl.INVALID)) else emptyList() },
            execute = { if (it.startsWith("CREATE UNIQUE INDEX")) rebuilt = true; null },
        )
        assertEquals(
            listOf("DROP INDEX CONCURRENTLY IF EXISTS $emailIndex", "CREATE UNIQUE INDEX CONCURRENTLY IF NOT EXISTS $emailIndex"),
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
        // Built blocking writes once refused online; the drop after the data refuses it blocks too, as each after it does.
        assertEquals(
            listOf(
                "CREATE UNIQUE INDEX CONCURRENTLY IF NOT EXISTS $emailIndex", "CREATE UNIQUE INDEX IF NOT EXISTS $emailIndex",
                "DROP INDEX IF EXISTS $emailIndex",
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
        val leftover = emailIndexObject(PostgresDdl.INVALID) + ("object" to recreation.temporary.lowercase())
        var built = false
        val listed = { statement: String -> if (statement == postgresDdl.listConstraintsQuery() && !built) listOf(leftover) else emptyList() }
        val execute = { statement: String -> if (statement.startsWith("CREATE UNIQUE INDEX")) built = true; null }
        // An ensure doesn't keep it as the constraint's index: it makes the constraint.
        val (ensured, report) = drive(postgres, email, listed = listed, execute = execute)
        assertEquals(listOf("CREATE UNIQUE INDEX CONCURRENTLY IF NOT EXISTS $emailIndex"), indexBuilds(ensured).map { it.substringBefore(" ON ") })
        assertIs<EnsureResult.Created>(report.item!!.result)
        assertTrue(report.log.none { "is held by" in it.message }, report.log.toString())
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
        assertTrue(ran.none { it.startsWith("DROP INDEX CONCURRENTLY") }, ran.joinToString("\n"))
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
    fun anOracleUniquenessConstraintIsBuiltOnlineAndOneTheEditionRefusesIsBuiltBlockingWrites() {
        val (online, built) = drive(oracle, email)
        // The range index under the constraint and its unique index.
        assertEquals(2, online.count { ") ONLINE'" in it }, online.joinToString("\n"))
        assertIs<EnsureResult.Created>(built.item!!.result)

        val (ran, report) = drive(oracle, email, execute = { if (") ONLINE'" in it) noOnline else null })
        assertEquals(1, ran.count { ") ONLINE'" in it })
        assertIs<EnsureResult.Created>(report.item!!.result)
        assertEquals(1, report.log.count { it.level == LogLevel.WARN && "can't build an index online" in it.message }, report.log.toString())
    }

    @Test
    fun anOracleUniquenessConstraintOverTwoPropertiesIsBuiltOnlineAndItsRecreateIsNot() {
        val pair = UniquenessConstraintSpec("Person", listOf("first", "last")).made()
        val (online, built) = drive(oracle, pair)
        // The range index under the constraint, its index over every node's properties and its labelled one, and its unique index.
        assertEquals(3, online.count { ") ONLINE'" in it }, online.joinToString("\n"))
        assertIs<EnsureResult.Created>(built.item!!.result)
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
}
