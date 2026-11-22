package org.drivine.cypher.store.plan

import org.drivine.cypher.json.CypherJson
import org.drivine.cypher.json.ObjectNode
import org.drivine.cypher.number.oracleFile
import kotlin.test.Test
import kotlin.test.assertEquals
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
            val written = CypherJson.readTree(PlanRequest.fromJson(given).toJson())
            assertEquals(CypherJson.writeValueAsString(case["catalogs"]!!), CypherJson.writeValueAsString(written["catalogs"]!!), "$name: catalogs")
            assertEquals(CypherJson.writeValueAsString(case["runtime"]!!), CypherJson.writeValueAsString(written["runtime"]!!), "$name: runtime")
        }
    }
}
