package org.drivine.cypher.store.plan

import org.drivine.cypher.store.ddl.DdlStatement
import org.drivine.cypher.store.ddl.Neo4jDdl
import org.drivine.cypher.store.ddl.SqliteDdl
import org.drivine.cypher.store.schema.Cause
import org.drivine.cypher.store.schema.Neo4jSchema
import org.drivine.cypher.store.schema.SqliteSchema
import org.drivine.cypher.store.schema.StoreError
import org.drivine.schema.EnsureResult
import org.drivine.schema.FalkorDbVectorOptions
import org.drivine.schema.FullTextIndexSpec
import org.drivine.schema.Neo4jVectorOptions
import org.drivine.schema.RangeIndexSpec
import org.drivine.schema.SchemaItemKind
import org.drivine.schema.SchemaMode
import org.drivine.schema.SchemaPolicy
import org.drivine.schema.SimilarityFunction
import org.drivine.schema.TextIndexSpec
import org.drivine.schema.UniquenessConstraintSpec
import org.drivine.schema.VectorIndexSpec
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * What `plans.yaml`'s cases don't run: the public `IndexManager` and `ConstraintManager` calls, the refusals the planner
 * gives before a plan, a state written as JSON with every kind of spec, action and outcome, and a state that isn't its
 * request's.
 */
class SchemaPlannerTest {

    private val sqlite = SqliteDdl("drivine")
    private val planner = SchemaPlanner(SqliteSchema(sqlite))
    private val name = RangeIndexSpec("Person", "name")

    private fun request(operation: Operation, vararg catalogs: DeclaredCatalog) = PlanRequest("default", operation, catalogs.toList())

    @Test
    fun aConflictAcrossOwnersIsRefusedNamingBothAndOnlyWhereOneIsApplied() {
        val small = VectorIndexSpec("Chunk", "embedding", 384)
        val large = VectorIndexSpec("Chunk", "embedding", 768)
        val conflicting = request(Operation.Enforce(), DeclaredCatalog(null, items = listOf(small)), DeclaredCatalog("rag", items = listOf(large)))
        val refusal = planner.validate(conflicting)
        assertTrue(refusal!!.startsWith("Conflicting schema declarations for VECTOR_INDEX on Chunk[embedding] on database 'default', across owners: 'schema' declares"), refusal)
        val done = assertIs<PlanStep.Done>(planner.start(conflicting, 0)).report
        assertEquals(refusal, done.failure)
        assertNull(planner.validate(conflicting.copy(operation = Operation.Enforce(Owners.One("other")))))
        assertNull(planner.validate(conflicting.copy(operation = Operation.Remove(null, listOf(small)))))
    }

    @Test
    fun aConflictWithinAnOwnerIsRefusedWithTheMergesMessage() {
        val refusal = planner.validate(
            request(Operation.Enforce(), DeclaredCatalog(null, items = listOf(VectorIndexSpec("Chunk", "embedding", 384))), DeclaredCatalog(null, items = listOf(VectorIndexSpec("Chunk", "embedding", 768)))),
        )
        assertTrue(refusal!!.startsWith("Conflicting schema declarations for VECTOR_INDEX on Chunk[embedding] (they disagree on portable parameters)"), refusal)
    }

    @Test
    fun anIndexEnsuredAloneIsMadeAndFoundWithNoMarker() {
        val listing = PlanAction.Query(sqlite.listIndexesQuery(SchemaItemKind.RANGE_INDEX), purpose = Purpose.LISTING)
        val first = assertIs<PlanStep.Run>(planner.start(request(Operation.Item(ItemCall.ENSURE, name)), 1))
        assertEquals(listOf(listing), first.actions)
        val second = assertIs<PlanStep.Run>(planner.resume(first.state, listOf(PlanOutcome.Rows(emptyList())), 2))
        assertEquals(sqlite.createIndex(name).map { PlanAction.Execute(it, purpose = Purpose.DDL) } + listing, second.actions)
        val made = mapOf("kind" to "RANGE_INDEX", "label" to "Person", "properties" to listOf("name"), "name" to "Person_name_range", "details" to emptyMap<String, Any?>())
        val outcomes = second.actions.dropLast(1).map { PlanOutcome.Ran } + PlanOutcome.Rows(listOf(made))
        val report = assertIs<PlanStep.Done>(planner.resume(second.state, outcomes, 3)).report
        assertIs<EnsureResult.Created>(report.item!!.result)
        assertEquals(listOf(LogLine(LogLevel.INFO, "IndexManager", "Created SQLite RANGE_INDEX on Person[name]")), report.log)
        assertNull(report.failure)
    }

    @Test
    fun anIndexEnsuredAloneThatIsNotBuiltFailsAsTheIndexManagersEnsureThrows() {
        val neo4j = SchemaPlanner(Neo4jSchema())
        val item = mapOf("name" to "Person_name_range", "type" to "RANGE", "entityType" to "NODE", "labelsOrTypes" to listOf("Person"), "properties" to listOf("name"))
        var step = neo4j.start(PlanRequest("default", Operation.Item(ItemCall.ENSURE, name), buildTimeoutMillis = 500), 0)
        val timedOut = StoreError(Cause(code = "Neo.ClientError.Procedure.ProcedureTimedOut", message = "did not complete"))
        while (step is PlanStep.Run) {
            val outcomes = step.actions.map { action ->
                when (action) {
                    is PlanAction.Query -> PlanOutcome.Rows(listOf(item))
                    is PlanAction.AwaitIndex -> PlanOutcome.Failed(timedOut).also { assertEquals(1L, action.parameters["timeout"]) }
                    else -> PlanOutcome.Ran
                }
            }
            step = neo4j.resume(step.state, outcomes.take(outcomes.indexOfFirst { it is PlanOutcome.Failed }.let { if (it < 0) outcomes.size else it + 1 }), 0)
        }
        val report = (step as PlanStep.Done).report
        assertEquals("Neo4j RANGE_INDEX 'Person_name_range' on Person[name] is still being built, having waited PT0.5S: did not complete", report.failure)
        assertNull(report.item)
    }

    @Test
    fun aWaitIsNamedAsJavasDurationNamesIt() {
        assertEquals(
            listOf("PT0S", "PT0.5S", "PT1.5S", "PT0.001S", "PT30S", "PT1M", "PT1M30S", "PT1H", "PT1H0.25S"),
            listOf(0L, 500, 1500, 1, 30_000, 60_000, 90_000, 3_600_000, 3_600_250).map(::javaDuration),
        )
    }

    @Test
    fun aStateIsWrittenAsJsonAndReadBackTheSame() {
        val specs = listOf(
            RangeIndexSpec("Person", listOf("name", "born"), "people", ignoreCase = true),
            UniquenessConstraintSpec("Person", listOf("email"), null, nodeIdProperty = "uuid", ignoreCase = true),
            FullTextIndexSpec("Doc", listOf("title", "body"), "docs", "english"),
            TextIndexSpec("Doc", "title", null, ignoreCase = true),
            VectorIndexSpec(
                "Chunk", "embedding", 8, SimilarityFunction.EUCLIDEAN, "chunks", 16, 200,
                listOf(Neo4jVectorOptions(quantizationEnabled = false, hnswM = 32), FalkorDbVectorOptions(efRuntime = 10)),
            ),
        )
        val request = PlanRequest(
            "db", Operation.Ensure("rag", specs.take(2)), listOf(DeclaredCatalog(null, "v1", specs)), listOf(DeclaredCatalog("rag", null, specs.take(2))),
            SchemaPolicy(SchemaMode.WARN, recreateOnDrift = true, recreateOnStartup = true, violationSampleSize = 3, replaceBlockingIndex = true), 1234,
        )
        val state = PlannerState(
            request,
            listOf(
                PlanRound(
                    1,
                    listOf(
                        PlanAction.Query("SELECT 1", mapOf("scope" to "rag"), Purpose.MARKER, 200, compiled = true),
                        PlanAction.Execute(DdlStatement.Native(listOf("GRAPH.CONSTRAINT", "CREATE", "{graphName}")), purpose = Purpose.DDL, retry = Retry.CONTENDED),
                        PlanAction.AwaitIndex("CALL db.awaitIndex(\$name, \$timeout)", mapOf("name" to "x", "timeout" to 60L)),
                        PlanAction.FillLowerCopies("Person", listOf("email"), again = true),
                        PlanAction.WriteMarker("rag", "MERGE", mapOf("scope" to "rag", "version" to null, "items" to "a\nb", "now" to 5L)),
                    ),
                    listOf(
                        PlanOutcome.Rows(listOf(mapOf("a" to listOf(1L, 1.5, true, null, "x\"y")), "v1")),
                        PlanOutcome.Ran,
                        PlanOutcome.Failed(StoreError(Cause("23505", 0, null, null, "duplicate"), Cause(code = "N/A", gqlStatus = "50N42"))),
                    ),
                ),
                PlanRound(2, listOf(PlanAction.Query("SHOW INDEXES", purpose = Purpose.LISTING))),
            ),
        )
        assertEquals(state, PlannerState.fromJson(state.toJson()))
    }

    @Test
    fun aRoundIsResumedOnlyWithWhatItsActionsGave() {
        val first = assertIs<PlanStep.Run>(planner.start(request(Operation.Enforce(), DeclaredCatalog(null, items = listOf(name))), 0))
        assertFailsWith<IllegalArgumentException> { planner.resume(first.state, emptyList(), 0) }
        assertFailsWith<IllegalArgumentException> { planner.resume(first.state, listOf(PlanOutcome.Ran), 0) }
        assertFailsWith<IllegalArgumentException> { planner.resume(first.state, first.actions.map { PlanOutcome.Failed(StoreError()) }, 0) }
        val resumed = assertIs<PlanStep.Run>(planner.resume(first.state, first.actions.map { PlanOutcome.Rows(emptyList()) }, 0))
        assertFailsWith<IllegalArgumentException> { planner.resume(first.state.copy(rounds = resumed.state.rounds.dropLast(1)), listOf(PlanOutcome.Ran), 0) }
        // A state that isn't its request's is refused, rather than planned from.
        val other = resumed.state.copy(request = resumed.state.request.copy(operation = Operation.Remove(null, listOf(name))))
        assertFailsWith<IllegalStateException> { planner.resume(other, resumed.actions.map { PlanOutcome.Ran }, 0) }
    }

    @Test
    fun aStoreThatRefusesWhatTheMarkerNeedsMadeEndsThePlanWithItsError() {
        val first = assertIs<PlanStep.Run>(planner.start(request(Operation.Enforce(), DeclaredCatalog(null, items = listOf(name))), 0))
        assertEquals(Purpose.PREPARE, (first.actions.first() as PlanAction.Execute).purpose)
        val refused = StoreError(Cause("42501", 0, message = "permission denied"))
        val report = assertIs<PlanStep.Done>(planner.resume(first.state, listOf(PlanOutcome.Failed(refused)), 0)).report
        assertEquals(Rethrown(first.actions.first(), refused, 0), report.rethrown)
        assertNull(report.failure)
    }

    @Test
    fun nothingDeclaredOnTheDatabaseIsNothingRun() {
        val done = assertIs<PlanStep.Done>(planner.start(request(Operation.Enforce(), DeclaredCatalog(null, items = emptyList())), 0)).report
        assertEquals(DatabaseReport("default"), done)
        val neo4j = SchemaPlanner(Neo4jSchema())
        assertEquals(Neo4jDdl, Neo4jSchema().ddl)
        assertIs<PlanStep.Run>(neo4j.start(request(Operation.Enforce(), DeclaredCatalog(null, items = listOf(name))), 0))
    }
}
