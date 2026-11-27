package org.drivine.cypher

import org.drivine.cypher.sql.OracleCypherCompiler
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * On Oracle a text or a timestamp whose year may be BC is renumbered by the functions the store keeps beside the graph's
 * tables (see `OracleGraph.era`), each operand written once: the test and the regular expression they stand for wrote it
 * three or four times, and Oracle took up to a minute to plan a statement of temporal arithmetic in a subquery
 * (drivine#572). A store without them has each statement compiled as before.
 */
class OracleEraFunctionTest {

    private fun stored(cypher: String) = OracleCypherCompiler("g").let { it.compile(cypher).sql to it.callsStoredEra }

    private fun inline(cypher: String) = OracleCypherCompiler("g", eraStored = false).let { it.compile(cypher).sql to it.callsStoredEra }

    @Test
    fun `a year that may be BC is read and written by the store's functions, with no test of its own`() {
        val (sql, calls) = stored("RETURN toString(date('-0044-03-15') + duration('P1D'))")
        assertTrue(calls)
        assertTrue("TO_TIMESTAMP(g_era(" in sql && "g_era_date(LTRIM(TO_CHAR(" in sql, sql)
        assertFalse("ASCII(" in sql || "TIMESTAMP '0001-01-01 00:00:00'" in sql || "'^-?[0-9]{4}'" in sql || "REGEXP_REPLACE(LTRIM(" in sql, sql)
    }

    @Test
    fun `a store without the functions has the year renumbered inline, as before`() {
        val (sql, calls) = inline("RETURN toString(date('-0044-03-15') + duration('P1D'))")
        assertFalse(calls)
        assertFalse("_era" in sql, sql)
        assertTrue(") < 49 THEN REGEXP_REPLACE(" in sql && "REGEXP_REPLACE(LTRIM(TO_CHAR(" in sql, sql)
    }

    @Test
    fun `a store without the functions is given the SQL it was given before them, byte for byte`() {
        // A store an earlier version made gives the same rows until it is ensured: in `oracle/era-inline`, what the compiler
        // gave before drivine#572 for the graph g. A change that compiles one of these otherwise writes its file again.
        val at = "datetime(CASE k WHEN 0 THEN '2024-03-15T10:00:00+02:00' ELSE '2024-03-15T09:00:00Z' END)"
        val pinned = mapOf(
            "zls5" to "UNWIND [0, 1] AS k UNWIND range(1, 3) AS i WITH k, $at + duration({minutes: i}) AS t WITH k, collect(t) AS l RETURN [min(l)[0].hour, max(l)[0].hour]",
            "call5" to "MATCH (n:CyTemporal) CALL { WITH n CALL { WITH n RETURN n.day + duration('P1D') AS e } RETURN e AS d } RETURN (d + duration('P1D')).day",
            "bc-read" to "RETURN toString(date('-0044-03-15') + duration('P1D'))",
            "date-write" to "CREATE (n:CyTemporal {id: 'w', day: date('-0044-03-15') + duration('P1D')}) RETURN toString(n.day)",
        )
        for ((file, cypher) in pinned) {
            val expected = requireNotNull(javaClass.getResource("/oracle/era-inline/$file.sql")) { file }.readText()
            val (sql, calls) = inline(cypher)
            assertFalse(calls, file)
            assertEquals(expected, sql, file)
            assertTrue(stored(cypher).second, file)
        }
    }

    @Test
    fun `an item of a list of moments is read as JSON, with no test of whether it is a text`() {
        // A list's item is read through its text where it may be one (#388); a moment is a tag, never a text, and that test
        // made the hard parse of the min and max of a list of moments, each read by index, 6.2 seconds where 0.53 without (#244).
        val text = "THEN JSON(JSON_QUERY(JSON_SERIALIZE("
        val moments = "UNWIND range(1, 3) AS i WITH k, datetime('2024-03-15T10:00:00Z') + duration({minutes: i}) AS t WITH k, collect(t) AS l"
        for (item in listOf("min(l)[0].hour", "l[0]", "l[k]", "l[-1]")) {
            val sql = stored("UNWIND [0, 1] AS k $moments RETURN $item").first
            assertFalse(text in sql, item)
            assertTrue("JSON_QUERY(" in sql && "'\$[\$i]' PASSING" in sql, sql)
        }
        // So is one of a slice of it, by a number: the slice's key read by a text is read as before.
        assertTrue("WHEN 'number' THEN JSON_QUERY(" in stored("UNWIND [0, 1] AS k $moments RETURN l[0..2][k]").first)
        // A list whose items may be texts is read as before: of texts, of moments beside a text, stored, given or split.
        for (item in listOf("l[0]", "l[k]")) assertTrue(text in stored("UNWIND [0, 1] AS k MATCH (n:N) WITH k, collect(n.name) AS l RETURN $item").first, item)
        val texts = listOf(
            "WITH k, [datetime('2024-03-15T10:00:00Z'), 'x'] AS l", "MATCH (n:N) WITH k, n.l AS l", "WITH k, \$l AS l", "WITH k, split('a,b', ',') AS l",
        )
        for (list in texts) for (item in listOf("l[0]", "l[k]")) {
            val sql = OracleCypherCompiler("g").compile("UNWIND [0, 1] AS k $list RETURN $item", mapOf("l" to listOf("a"))).sql
            assertTrue(text in sql, "$list $item")
        }
    }

    @Test
    fun `a statement that writes, and the step of a reduce, call them too`() {
        // A schema function, unlike one a query declares, is called from a block's queries and from a reduce's own function.
        for (cypher in listOf(
            "CREATE (n:CyTemporal {day: date('-0044-03-15') + duration('P1D')}) RETURN toString(n.day)",
            "RETURN reduce(s = '', d IN [date('-0044-03-15')] | s + toString(d + duration('P1D')))",
        )) {
            val (sql, calls) = stored(cypher)
            assertTrue(calls && "g_era(" in sql && "g_era_date(" in sql, sql)
            assertFalse("FUNCTION g_era" in sql || "ASCII(" in sql, sql)
            val (fallback, fallbackCalls) = inline(cypher)
            assertTrue(!fallbackCalls && "_era" !in fallback, fallback)
        }
    }

    @Test
    fun `a statement with no year to renumber compiles as it would without the functions`() {
        for (cypher in listOf(
            "MATCH (n:Person) RETURN n.name",
            "RETURN date('2024-03-15').year",
            "UNWIND [1, 2] AS i RETURN i + 1",
        )) {
            val (sql, calls) = stored(cypher)
            assertFalse(calls, sql)
            assertEquals(inline(cypher).first, sql)
        }
    }
}
