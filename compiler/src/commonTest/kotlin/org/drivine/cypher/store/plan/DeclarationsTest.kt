package org.drivine.cypher.store.plan

import org.drivine.cypher.json.CypherJson
import org.drivine.cypher.json.ObjectNode
import org.drivine.cypher.number.oracleFile
import org.drivine.cypher.store.ddl.PostgresDdl
import org.drivine.cypher.store.schema.StoreSchema
import org.drivine.schema.FalkorDbVectorOptions
import org.drivine.schema.FullTextIndexSpec
import org.drivine.schema.Neo4jVectorOptions
import org.drivine.schema.RangeIndexSpec
import org.drivine.schema.SchemaMode
import org.drivine.schema.SchemaPolicy
import org.drivine.schema.SimilarityFunction
import org.drivine.schema.UniquenessConstraintSpec
import org.drivine.schema.VectorIndexSpec
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFails
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * `declarations.yaml`, what each library's schema declarations give the planner (liberation-data/drivine-cypher-spec#135),
 * is in the planner's own JSON: each case's catalogs and runtime items, read as a request ([PlanRequest.fromJson]) and
 * written again ([PlanRequest.toJson]), are what the case pins, every field there and none left out. A library checks
 * its declarations give them; here, that they are a request the planner reads as pinned.
 */
class DeclarationsTest {

    private val declarations = CypherJson.readTree(oracleFile("declarations.json"))

    @Test
    fun eachCaseIsARequestThePlannerReadsAndWritesAsPinned() {
        val cases = declarations["cases"]!!.toList()
        assertTrue(cases.size >= 24, "declarations.yaml has ${cases.size} cases")
        assertEquals(cases.size, cases.map { it["case"]!!.textValue() }.toSet().size, "each case once")
        for (case in cases) {
            val name = case["case"]!!.textValue()
            assertTrue(!case["declare"]!!.textValue().isNullOrBlank(), "$name: says what to declare")
            // An enforce of every owner, under the default policy, as the request's own JSON writes it, with the case's catalogs.
            val enforce = CypherJson.readTree(PlanRequest("default", Operation.Enforce()).toJson()) as ObjectNode
            val given = CypherJson.writeValueAsString(
                enforce.properties().associate { (key, value) ->
                    key to when (key) {
                        "catalogs" -> case["catalogs"]
                        "runtime" -> case["runtime"]
                        else -> value
                    }
                },
            )
            val request = PlanRequest.fromJson(given)
            // What a library's declarations give is a request the planner takes, not one it refuses.
            assertNull(SchemaPlanner(StoreSchema.of(PostgresDdl("g"))).validate(request), "$name: the planner refuses it")
            val written = CypherJson.readTree(request.toJson())
            assertEquals(CypherJson.writeValueAsString(case["catalogs"]!!), CypherJson.writeValueAsString(written["catalogs"]!!), "$name: catalogs")
            assertEquals(CypherJson.writeValueAsString(case["runtime"]!!), CypherJson.writeValueAsString(written["runtime"]!!), "$name: runtime")
        }
    }

    @Test
    fun aWholeRequestIsReadBackAsWritten() {
        val vector = VectorIndexSpec(
            "Chunk", "embedding", 384, SimilarityFunction.EUCLIDEAN, "chunk_vec", 16, 200,
            listOf(Neo4jVectorOptions(false, 8, 100), FalkorDbVectorOptions(4, 50, 10)),
        )
        val unique = UniquenessConstraintSpec("Person", listOf("email", "tenant"), "person_key", "id", ignoreCase = true)
        val catalogs = listOf(DeclaredCatalog(null, "v1", listOf(vector, unique)), DeclaredCatalog("rag", null, emptyList()))
        val runtime = listOf(DeclaredCatalog("dice", null, listOf(RangeIndexSpec("Tenant", listOf("code"), null, true))))
        val policy = SchemaPolicy(SchemaMode.WARN, recreateOnDrift = true, recreateOnStartup = true, violationSampleSize = 3, replaceBlockingIndex = true)
        val operations = listOf(
            Operation.Enforce(), Operation.Enforce(Owners.One(null)), Operation.RecreateAll(Owners.One("rag")),
            Operation.Ensure("dice", listOf(vector)), Operation.Remove(null, listOf(unique)), Operation.Item(ItemCall.RECREATE, unique),
        )
        for (operation in operations) {
            val request = PlanRequest("neo4j", operation, catalogs, runtime, policy, 5_000)
            assertEquals(request, PlanRequest.fromJson(request.toJson()), "$operation")
            assertEquals(request.toJson(), PlanRequest.fromJson(request.toJson()).toJson(), "$operation: written again")
        }
    }

    @Test
    fun aFieldThatMayBeNullAndAnItemsIgnoreCaseMayBeLeftOutButNoOtherField() {
        val policy = """{"mode": "WARN", "recreateOnDrift": false, "recreateOnStartup": false, "violationSampleSize": 5, "replaceBlockingIndex": false}"""
        val items = """[{"kind": "RANGE_INDEX", "label": "Person", "properties": ["name"]},
            {"kind": "UNIQUENESS_CONSTRAINT", "label": "Person", "properties": ["email"]},
            {"kind": "FULLTEXT_INDEX", "label": "Article", "properties": ["body"]},
            {"kind": "VECTOR_INDEX", "label": "Chunk", "properties": ["embedding"], "dimensions": 384, "similarity": "COSINE",
             "engineOptions": [{"engine": "Neo4j"}, {"engine": "FalkorDB"}]}]"""
        val request = PlanRequest.fromJson(
            """{"database": "g", "operation": {"type": "enforce", "owners": {"all": false}}, "catalogs": [{"items": $items}],
              "runtime": [], "policy": $policy, "buildTimeoutMillis": 0}""",
        )
        val declared = listOf(
            RangeIndexSpec("Person", listOf("name"), null, false),
            UniquenessConstraintSpec("Person", listOf("email"), null, null, false),
            FullTextIndexSpec("Article", listOf("body"), null, null),
            VectorIndexSpec(
                "Chunk", "embedding", 384, SimilarityFunction.COSINE, null, null, null,
                listOf(Neo4jVectorOptions(null, null, null), FalkorDbVectorOptions(null, null, null)),
            ),
        )
        val expected = PlanRequest(
            "g", Operation.Enforce(Owners.One(null)), listOf(DeclaredCatalog(null, null, declared)), emptyList(),
            SchemaPolicy(SchemaMode.WARN, violationSampleSize = 5), 0,
        )
        assertEquals(expected, request)
        // A field that may not be null has no default: a request without one is refused, not read.
        val enforce = """{"type": "enforce", "owners": {"all": true}}"""
        assertFails { PlanRequest.fromJson("""{"database": "g", "operation": $enforce, "catalogs": [], "runtime": [], "policy": $policy}""") }
        assertFails {
            PlanRequest.fromJson(
                """{"database": "g", "operation": $enforce, "catalogs": [{"owner": null, "version": null}], "runtime": [], "policy": $policy, "buildTimeoutMillis": 0}""",
            )
        }
        assertFails {
            PlanRequest.fromJson(
                """{"database": "g", "operation": $enforce, "catalogs": [{"items": [{"kind": "VECTOR_INDEX", "label": "Chunk", "properties": ["embedding"], "similarity": "COSINE", "engineOptions": []}]}],
                  "runtime": [], "policy": $policy, "buildTimeoutMillis": 0}""",
            )
        }
    }
}
