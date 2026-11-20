package org.drivine.cypher.api

import org.drivine.cypher.json.CypherJson
import org.drivine.cypher.json.JsonNode
import org.drivine.cypher.number.target
import org.drivine.cypher.store.ddl.PostgresDdl
import org.drivine.cypher.store.plan.DeclaredCatalog
import org.drivine.cypher.store.plan.Operation
import org.drivine.cypher.store.plan.Owners
import org.drivine.cypher.store.plan.PlanJson
import org.drivine.cypher.store.plan.PlanRequest
import org.drivine.cypher.store.plan.RuntimeCatalog
import org.drivine.cypher.store.plan.SchemaPlanner
import org.drivine.cypher.store.schema.StoreSchema
import org.drivine.cypher.store.plan.PlanAction
import org.drivine.cypher.store.plan.PlanOutcome
import org.drivine.cypher.store.plan.PlanStep
import org.drivine.cypher.store.plan.PlannerState
import org.drivine.cypher.store.plan.Purpose
import org.drivine.cypher.store.schema.Cause
import org.drivine.cypher.store.schema.StoreError
import org.drivine.schema.EnsureResult
import org.drivine.schema.RangeIndexSpec
import org.drivine.schema.SchemaItemInfo
import org.drivine.schema.SchemaItemKind
import org.drivine.schema.SchemaMode
import org.drivine.schema.SchemaPolicy
import org.drivine.schema.UniquenessConstraintSpec
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

/**
 * The schema planner's questions through the JSON entry ([SchemaPlanJson]) that `plans.yaml`'s replays don't ask (those run
 * through it in `StorePlanTest`): a request validated, a host's runtime catalogs kept and taken for a database, and a request
 * the planner can't answer (liberation-data/drivine-cypher-spec#107).
 */
class SchemaPlanJsonTest {

    private fun json(value: Any?) = CypherJson.writeValueAsString(CypherJson.valueToTree(value))

    private fun ask(rest: String): JsonNode =
        CypherJson.readTree(CompileJson.compile("{\"schemaPlan\": {\"store\": \"postgres\", \"graph\": \"g\", $rest}}"))

    private val range = RangeIndexSpec("Person", listOf("name"))

    private val unique = UniquenessConstraintSpec("Person", listOf("name"))

    @Test
    fun aRequestIsValidatedAsThePlannerValidatesIt() {
        val planner = SchemaPlanner(StoreSchema.of(PostgresDdl("g")))
        // Two owners declaring one vector index of two sizes conflict (SchemaPlannerTest's case); one owner declaring each once does not.
        val conflicting = PlanRequest(
            "default", Operation.Enforce(),
            listOf(
                DeclaredCatalog(null, items = listOf(org.drivine.schema.VectorIndexSpec("Chunk", "embedding", 384))),
                DeclaredCatalog("rag", items = listOf(org.drivine.schema.VectorIndexSpec("Chunk", "embedding", 768))),
            ),
        )
        val fine = PlanRequest("neo4j", Operation.Enforce(Owners.All), listOf(DeclaredCatalog("a", items = listOf(range, unique))))
        for (request in listOf(conflicting, fine)) {
            val got = ask("\"validate\": ${json(PlanJson.requestTree(request))}")
            assertEquals(planner.validate(request), got["refusal"]?.takeUnless { it.isNull }?.textValue(), "on $target: $got")
        }
        assertTrue(planner.validate(conflicting) != null, "the conflicting request is refused")
    }

    @Test
    fun aHostsRuntimeCatalogsAreKeptAndTakenAsThePlannerKeepsThem() {
        val runtime = listOf(RuntimeCatalog("a", null, listOf(range)), RuntimeCatalog("b", setOf("other"), listOf(unique)))
        val ensure = Operation.Ensure("a", listOf(unique))
        for (databases in listOf(null, setOf("neo4j"))) {
            val got = ask("\"runtimeAfter\": {\"operation\": ${json(PlanJson.operationTree(ensure))}, \"runtime\": ${json(runtime.map(PlanJson::runtimeTree))}, \"databases\": ${json(databases?.toList())}}")
            assertEquals(json(SchemaPlanner.runtimeAfter(ensure, runtime, databases).map(PlanJson::runtimeTree)), CypherJson.writeValueAsString(got["runtime"]!!), "on $target, $databases: $got")
        }
        for (database in listOf("neo4j", "other")) {
            val got = ask("\"runtimeOn\": {\"database\": \"$database\", \"runtime\": ${json(runtime.map(PlanJson::runtimeTree))}}")
            assertEquals(json(SchemaPlanner.runtimeOn(database, runtime).map(PlanJson::catalogTree)), CypherJson.writeValueAsString(got["catalogs"]!!), "on $target, $database: $got")
        }
    }

    @Test
    fun aSampleAndAnActionAreGivenAsThePlannerGivesThemNotAsTheLayoutWritesThem() {
        // A violation sampled on Postgres, its repeated key a date: the row holds the date as the layout keeps it, a tag with
        // `$t`, and a property whose name begins with `$`. A host reads the sample, and a row it gave crosses the state to
        // later rounds, as the Kotlin planner holds them: no `$` doubled, no tag rewritten.
        val spec = UniquenessConstraintSpec("Person", listOf("\$born"))
        val row = mapOf(
            "properties" to mapOf("\$born" to mapOf("\$t" to "date", "v" to "2024-01-01")),
            "count" to 2L, "nodes" to listOf(mapOf("elementId" to 1L), mapOf("elementId" to 2L)),
        )
        val request = PlanRequest("default", Operation.Enforce(), listOf(DeclaredCatalog(null, items = listOf(spec))), policy = SchemaPolicy(SchemaMode.WARN))
        fun outcome(action: PlanAction): PlanOutcome = when {
            // The create is refused; dropping the invalid index it leaves runs.
            action is PlanAction.Execute && action.purpose == Purpose.DDL && !"${action.statement}".contains("DROP INDEX") -> PlanOutcome.Failed(StoreError(Cause(sqlState = "23505", vendorCode = 0, message = "could not create unique index")))
            action is PlanAction.Query && action.purpose == Purpose.SAMPLE -> PlanOutcome.Rows(listOf(row))
            action is PlanAction.Query || action is PlanAction.ReadLowerCopies -> PlanOutcome.Rows(emptyList())
            else -> PlanOutcome.Ran
        }
        // A round's outcomes, up to and with the first that failed.
        fun outcomes(actions: List<PlanAction>): List<PlanOutcome> {
            val all = actions.map(::outcome)
            val failed = all.indexOfFirst { it is PlanOutcome.Failed }
            return if (failed < 0) all else all.take(failed + 1)
        }
        val planner = SchemaPlanner(StoreSchema.of(PostgresDdl("g")))
        var step = planner.start(request, 1)
        var answer = ask("\"start\": ${json(PlanJson.requestTree(request))}, \"now\": 1")
        var rounds = 0
        while (step is PlanStep.Run) {
            val run = answer["run"] ?: throw AssertionError("on $target, round $rounds: ${CypherJson.writeValueAsString(answer)}")
            val actions = run.map(PlanJson::action)
            assertEquals(step.actions, actions, "on $target, round $rounds")
            val given = outcomes(step.actions)
            // The state a host keeps reads back as it was written.
            assertEquals(step.state, PlannerState.fromJson(step.state.toJson()), "on $target, round $rounds's state")
            step = planner.resume(step.state, given, 1)
            answer = ask("\"resume\": ${CypherJson.writeValueAsString(answer["state"]!!)}, \"outcomes\": ${PlanJson.writePlain(given.map(PlanJson::outcomeTree))}, \"now\": 1")
            assertTrue(++rounds < 20, "on $target: the plan ends")
        }
        val report = assertIs<PlanStep.Done>(step).report
        val violation = report.owners.single().results.single().result
        assertEquals(listOf(row), assertIs<EnsureResult.Violation>(violation).conflictingSample, "on $target: the planner's sample")
        val sample = answer["done"]?.get("owners")?.get(0)?.get("results")?.get(0)?.get("result")?.get("violation")?.get("sample")
        assertEquals(
            "[{\"properties\":{\"\$born\":{\"\$t\":\"date\",\"v\":\"2024-01-01\"}},\"count\":2,\"nodes\":[{\"elementId\":1},{\"elementId\":2}]}]",
            sample?.let(CypherJson::writeValueAsString), "on $target: ${CypherJson.writeValueAsString(answer)}",
        )
        assertEquals(PlanJson.writePlain(PlanJson.report(report)), answer["done"]?.let(CypherJson::writeValueAsString), "on $target: the report")
    }

    @Test
    fun theReadmesStartIsAnswered() {
        // compiler/README.md's example, as a host would send it: a first round to run.
        val request = "{\"schemaPlan\": {\"store\": \"postgres\", \"graph\": \"g\", \"start\": {\"database\": \"neo4j\", \"operation\": {\"type\": \"enforce\", \"owners\": {\"all\": true}}, \"catalogs\": [], \"runtime\": [], " +
            "\"policy\": {\"mode\": \"FAIL_FAST\", \"recreateOnDrift\": false, \"recreateOnStartup\": false, \"violationSampleSize\": 10, \"replaceBlockingIndex\": false}, \"buildTimeoutMillis\": 60000}, \"now\": 1700000000000}}"
        val got = CypherJson.readTree(CompileJson.compile(request))
        assertTrue(got["run"] != null || got["done"] != null, "on $target: $got")
    }

    @Test
    fun anItemsOwningConstraintIsWritten() {
        // A host reads a range index left to, or served by, a uniqueness constraint as the constraint's (#325).
        val item = SchemaItemInfo(SchemaItemKind.RANGE_INDEX, "Person", listOf("name"), name = "Person_name_unique", owningConstraint = "Person_name_unique")
        assertEquals("Person_name_unique", PlanJson.item(item)?.get("owningConstraint"), "on $target")
    }

    @Test
    fun aRequestThePlannerCantAnswerIsAnError() {
        val request = json(PlanJson.requestTree(PlanRequest("neo4j", Operation.Enforce(Owners.All))))
        for ((rest, message) in listOf(
            "\"start\": $request" to "A start or a resume gives now",
            "\"resume\": {}, \"outcomes\": [], \"now\": 1" to "A planner state has no request",
            "\"resume\": ${json(mapOf("request" to PlanJson.requestTree(PlanRequest("neo4j", Operation.Enforce(Owners.All))), "rounds" to emptyList<Any>()))}, \"outcomes\": 1, \"now\": 1" to "A resume gives its outcomes as a list",
            "\"runtimeOn\": {\"runtime\": []}" to "runtimeOn gives its database as text",
            "\"plan\": 1" to "A schemaPlan request gives one of validate",
            "\"validate\": $request, \"start\": $request, \"now\": 1" to "A schemaPlan request gives one of validate",
            "\"runtimeAfter\": 1" to "runtimeAfter gives {",
            "\"runtimeAfter\": {\"runtime\": []}" to "runtimeAfter gives its operation",
            "\"runtimeAfter\": {\"operation\": {\"type\": \"ensure\", \"owner\": \"a\", \"items\": []}}" to "runtimeAfter gives runtime as a list",
            "\"runtimeAfter\": {\"operation\": {\"type\": \"ensure\", \"owner\": \"a\", \"items\": []}, \"runtime\": [], \"databases\": [1]}" to "runtimeAfter's databases are names",
            "\"runtimeOn\": 1" to "runtimeOn gives {",
            "\"runtimeOn\": {\"database\": \"neo4j\"}" to "runtimeOn gives runtime as a list",
            "\"start\": $request, \"now\": 1.5" to "A start or a resume gives now",
        )) {
            val error = ask(rest)["error"]?.textValue()
            assertTrue(error != null && error.startsWith(message), "on $target, $rest: $error")
        }
        val noStore = CypherJson.readTree(CompileJson.compile("{\"schemaPlan\": {\"start\": $request, \"now\": 1}}"))["error"]?.textValue()
        assertTrue(noStore != null && noStore.startsWith("A schemaPlan request names its store"), "on $target: $noStore")
    }
}
