package org.drivine.cypher

import org.drivine.cypher.spec.CypherSpec
import org.drivine.cypher.sql.OracleCypherCompiler
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * On Oracle a statement's lists of ids, a walk's and a path's, are text (`,1,2,`) where its longest list fits in a
 * `VARCHAR2(4000)`, at 29 bytes an id, and else JSON lists, which have no limit: a walk past 4,000 bytes of ids failed
 * with ORA-01489 (drivine4j#177). The choice is the statement's, so every helper over a list takes the same form. The
 * spec's "A walk of 1,100 hops" runs both on each store; [OracleCypherCompiler.jsonIdsForced] runs the JSON form over
 * every walk the spec has.
 */
class OracleIdListFormTest {

    private fun sql(cypher: String) = OracleCypherCompiler("g").compile(cypher).sql

    private fun forced(cypher: String): String {
        OracleCypherCompiler.jsonIdsForced = true
        try {
            return sql(cypher)
        } finally {
            OracleCypherCompiler.jsonIdsForced = false
        }
    }

    /** What the text form writes, in its walk and its helpers. */
    private val textForm = listOf("CAST(',' AS VARCHAR2(4000))", "CYCLE eids", "REGEXP_COUNT(", "TRIM(BOTH ',' FROM", "LISTAGG(x.id, ',')")

    /** The text form's test of an id among a list's. */
    private val textIn = Regex("""INSTR\([^()]*, ',' \|\| """)

    /** What the JSON form writes, in its walk and its helpers. */
    private val jsonForm = listOf(
        Regex(Regex.escape("JSON('[]'), JSON('[]')")),
        Regex("""JSON_TRANSFORM\(w\d+\.[ne]ids, """),
        Regex("""PASSING \S+ AS "id"\)"""),
        Regex(Regex.escape("WITH ARRAY WRAPPER EMPTY ARRAY ON EMPTY")),
    )

    private fun isText(sql: String) = sql.contains("CAST(',' AS VARCHAR2(4000))")

    private fun isJson(sql: String) = sql.contains("JSON('[]'), JSON('[]')")

    private fun holdsTextForm(sql: String) = textForm.any { sql.contains(it) } || textIn.containsMatchIn(sql)

    private fun holdsJsonForm(sql: String) = jsonForm.any { it.containsMatchIn(sql) }

    @Test
    fun `a walk is text where its path's longest list fits in 4,000 bytes, and JSON past that or with no bound`() {
        // A path of a walk to 136 hops holds 137 node ids: 1 + 137 * 29 = 3,974 bytes.
        assertTrue(isText(sql("MATCH p = (a)-[*1..136]->(b) RETURN nodes(p)")))
        assertTrue(isJson(sql("MATCH p = (a)-[*1..137]->(b) RETURN nodes(p)")))
        assertTrue(isJson(sql("MATCH (a)-[*]->(b) RETURN b")))
        assertTrue(isJson(sql("MATCH (a)-[*2..]->(b) RETURN b")))
        // Each relationship of a pattern takes its place in the path's list, a walk's to its bound.
        assertTrue(isText(sql("MATCH p = (a)-[r]->(b)-[*1..135]->(c) RETURN nodes(p)")))
        assertTrue(isJson(sql("MATCH p = (a)-[r]->(b)-[*1..136]->(c) RETURN nodes(p)")))
        assertTrue(isJson(sql("MATCH p = (a)-[*1..70]->(b)-[*1..70]->(c) RETURN nodes(p)")))
        // A quantified path pattern takes each of its step's relationships, to its bound.
        assertTrue(isText(sql("MATCH (a) ((x)-[:R]->(y)-[:R]->(z)){1,68} (b) RETURN b")))
        assertTrue(isJson(sql("MATCH (a) ((x)-[:R]->(y)-[:R]->(z)){1,69} (b) RETURN b")))
        assertTrue(isJson(sql("MATCH (a) ((x)-[:R]->(y))+ (b) RETURN b")))
        // Anywhere in the statement: a subquery's walk, a union's, a shortest path's.
        assertTrue(isJson(sql("MATCH (a)-[*1..3]->(b) RETURN b, COUNT { (b)-[*]->() } AS n")))
        assertTrue(isJson(sql("MATCH (a)-[*1..3]->(b) RETURN b UNION MATCH (a)-[*]->(b) RETURN b")))
        assertTrue(isJson(sql("MATCH p = shortestPath((a {id: 1})-[*]-(b {id: 2})) RETURN length(p)")))
        assertTrue(isJson(sql("MATCH (a) RETURN [(a)-[*]->(b) | b.x] AS xs")))
        assertTrue(isJson(sql("MATCH (a) WHERE (a)-[*]->(:B) RETURN a")))
        assertTrue(isJson(sql("MATCH (a) WHERE EXISTS { (a)-[*]->(:B) } RETURN a")))
        assertTrue(isJson(sql("MATCH (a) OPTIONAL MATCH (a)-[*]->(b) RETURN b")))
        assertTrue(isJson(sql("MATCH (a) CALL (a) { MATCH (a)-[*]->(b) RETURN b } RETURN b")))
    }

    @Test
    fun `a JSON walk grows its lists RETURNING JSON, takes no relationship twice by JSON_EXISTS, and names its depth its cycle`() {
        val forwards = sql("MATCH (a:A)-[*]->(b) RETURN b")
        assertTrue(Regex("""JSON_TRANSFORM\(w\d+\.eids, APPEND '\$' = e\d+\.eid RETURNING JSON\)""").containsMatchIn(forwards), forwards)
        assertTrue(Regex("""NOT JSON_EXISTS\(w\d+\.eids, '\$\[\*]\?\(@ == \${'$'}id\)' PASSING e\d+\.eid AS "id"\)""").containsMatchIn(forwards), forwards)
        // Oracle takes a node walked to again for a cycle unless one is named (ORA-32044): the JSON walk names its depth.
        assertTrue(forwards.contains(") CYCLE depth SET looped TO 'Y' DEFAULT 'N'"), forwards)
        assertFalse(holdsTextForm(forwards), forwards)
        // Walked backwards, from the pattern's later node, each node and relationship goes first.
        val backwards = sql("MATCH (a)-[*]->(b:B {id: 1}) RETURN a")
        assertTrue(Regex("""JSON_TRANSFORM\(w\d+\.nids, INSERT '\$\[0]' = w\d+\.nid RETURNING JSON\)""").containsMatchIn(backwards), backwards)
        assertFalse(holdsTextForm(backwards), backwards)
    }

    @Test
    fun `a JSON path's length is its list's size, not a count of commas`() {
        val json = sql("MATCH p = (a:A)-[*]->(b) RETURN length(p)")
        assertTrue(json.contains("JSON_SCALAR(JSON_VALUE(p") && json.contains("'\$.size()' RETURNING NUMBER))"), json)
        assertFalse(json.contains("REGEXP_COUNT"), json)
        assertTrue(sql("MATCH p = (a:A)-[*1..3]->(b) RETURN length(p)").contains("REGEXP_COUNT"))
    }

    @Test
    fun `a bounded walk keeps the text form, and the JSON form is the statement's alone`() {
        val text = sql("MATCH p = (a:A)-[*1..6]->(b) RETURN nodes(p)")
        assertTrue(text.contains("CYCLE eids SET looped TO 'Y' DEFAULT 'N'"), text)
        assertFalse(holdsJsonForm(text), text)
        // Forced, on every walk; not past the statement compiled.
        assertTrue(isJson(forced("MATCH p = (a:A)-[*1..6]->(b) RETURN nodes(p)")))
        assertTrue(isText(sql("MATCH p = (a:A)-[*1..6]->(b) RETURN nodes(p)")))
    }

    /**
     * Every statement of the spec compiled for Oracle, in each form: each that compiles in one compiles in the other, and
     * holds nothing of the other's form, so no helper over a list can take the other form from the walk that made it.
     */
    @Test
    fun `every spec statement compiles alike in either form, and holds only its own`() {
        var walks = 0
        for (file in listOf("queries.yaml", "writes.yaml", "temporal.yaml", "refusals.yaml")) {
            for (case in CypherSpec.load(file).cases) for (step in case.steps) {
                val where = "$file, ${case.name}: ${step.cypher}"
                val normal = outcome { OracleCypherCompiler("g").compile(step.cypher, step.parameters).sql }
                val json = outcome {
                    OracleCypherCompiler.jsonIdsForced = true
                    try {
                        OracleCypherCompiler("g").compile(step.cypher, step.parameters).sql
                    } finally {
                        OracleCypherCompiler.jsonIdsForced = false
                    }
                }
                assertEquals(normal.first, json.first, where)
                if (!normal.first) continue
                assertFalse(holdsTextForm(json.second), "$where\n${json.second}")
                if (isText(normal.second)) {
                    walks++
                    assertFalse(holdsJsonForm(normal.second), "$where\n${normal.second}")
                }
                if (isJson(normal.second)) assertFalse(holdsTextForm(normal.second), "$where\n${normal.second}")
            }
        }
        assertTrue(walks > 30, "the spec has $walks walks held as text")
    }

    /** Whether [compile] compiles, and its SQL or the refusal's message: any exception, as [org.drivine.cypher.api.CypherCompiler] takes one. */
    private fun outcome(compile: () -> String): Pair<Boolean, String> = try {
        true to compile()
    } catch (e: Exception) {
        false to (e.message ?: "")
    }
}
