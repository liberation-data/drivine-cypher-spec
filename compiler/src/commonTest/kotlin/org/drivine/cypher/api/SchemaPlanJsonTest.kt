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
import org.drivine.schema.RangeIndexSpec
import org.drivine.schema.UniquenessConstraintSpec
import kotlin.test.Test
import kotlin.test.assertEquals
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
    fun aRequestThePlannerCantAnswerIsAnError() {
        val request = json(PlanJson.requestTree(PlanRequest("neo4j", Operation.Enforce(Owners.All))))
        for ((rest, message) in listOf(
            "\"start\": $request" to "A start or a resume gives now",
            "\"resume\": {}, \"outcomes\": [], \"now\": 1" to "A planner state has no request",
            "\"resume\": ${json(mapOf("request" to PlanJson.requestTree(PlanRequest("neo4j", Operation.Enforce(Owners.All))), "rounds" to emptyList<Any>()))}, \"outcomes\": 1, \"now\": 1" to "A resume gives its outcomes as a list",
            "\"runtimeOn\": {\"runtime\": []}" to "runtimeOn gives its database as text",
            "\"plan\": 1" to "A schemaPlan request gives one of validate",
        )) {
            val error = ask(rest)["error"]?.textValue()
            assertTrue(error != null && error.startsWith(message), "on $target, $rest: $error")
        }
        val noStore = CypherJson.readTree(CompileJson.compile("{\"schemaPlan\": {\"start\": $request, \"now\": 1}}"))["error"]?.textValue()
        assertTrue(noStore != null && noStore.startsWith("A schemaPlan request names its store"), "on $target: $noStore")
    }
}
