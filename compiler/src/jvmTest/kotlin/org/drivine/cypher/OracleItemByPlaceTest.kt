package org.drivine.cypher

import org.drivine.cypher.sql.OracleCypherCompiler
import org.junit.jupiter.api.Test
import kotlin.test.assertTrue

/**
 * On Oracle, a statement that reads an item of a list by its place, a path's `$i` passed in, is not rewritten by the JSON
 * table transformation, which lost the value passed in where the list was a stored column: `labels(n)[0]` was null
 * (drivine4j#784).
 */
class OracleItemByPlaceTest {

    private val hint = "NO_JSON_TABLE_TRANSFORM"

    private fun sql(cypher: String) = OracleCypherCompiler("g").compile(cypher, mapOf("i" to 0)).sql

    @Test
    fun `an item read by its place turns the JSON table transformation off`() {
        for (cypher in listOf(
            "MATCH (n:Kept) RETURN labels(n)[0]",
            "MATCH (n:Kept) RETURN labels(n)[\$i]",
            "MATCH (n:Kept) WITH labels(n) AS ls RETURN ls[0]",
            "MATCH (n:Kept) WITH n, 0 AS i RETURN labels(n)[i]",
        )) {
            val sql = sql(cypher)
            assertTrue(sql.startsWith("SELECT /*+ $hint */ "), "$cypher: $sql")
            assertTrue("PASSING" in sql, "$cypher: $sql")
        }
    }

    @Test
    fun `a statement that writes has the hint in its query that reads the item`() {
        val sql = sql("MATCH (n:Kept) SET n.first = labels(n)[0]")
        assertTrue(sql.startsWith("DECLARE"), sql)
        assertTrue(Regex("SELECT /\\*\\+ $hint \\*/ [^;]*PASSING[^;]*INTO").containsMatchIn(sql), sql)
    }

    @Test
    fun `an item read by a path of no variable, as head() reads it, or from no stored column, leaves it on`() {
        for (cypher in listOf("MATCH (n:Kept) RETURN head(labels(n))", "MATCH (n:Kept) RETURN labels(n)", "WITH ['a', 'b'] AS l RETURN l[0]")) {
            val sql = sql(cypher)
            assertTrue(hint !in sql, "$cypher: $sql")
        }
    }
}
