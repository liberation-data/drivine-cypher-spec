package org.drivine.cypher

import org.drivine.cypher.oracle.OracleReads
import org.drivine.cypher.sql.OracleCypherCompiler
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * On Oracle, a map, a node or a relationship read by a key an expression gives reads the entry at that key from the map's
 * entries as `keyvalue()` lists them, matched by the key's text, and not null (drivine4j#417). A text is read from that
 * list written out as text, so a text past 32 KB is never taken out as JSON (see [OracleReads]).
 */
class OracleRuntimeKeyTest {

    private fun oracle(cypher: String, parameters: Map<String, Any?> = emptyMap()) = OracleCypherCompiler("g").compile(cypher, parameters).sql

    /** [sql], with no read off the CLOB route. */
    private fun routed(sql: String) = sql.also { assertEquals(emptyList(), OracleReads.unsafe(it).map { r -> "${r.source} ${r.path}" }, it) }

    private val entries = "'\$.keyvalue()' RETURNING JSON WITH ARRAY WRAPPER)"

    @Test
    fun `a key an expression gives is matched among the map's entries, of a map, a node and a relationship`() {
        for (cypher in listOf(
            "WITH {name: 'Apa'} AS m, 'name' AS k RETURN m[k]",
            "MATCH (n) RETURN n['nam' + 'e']",
            "MATCH (n) RETURN [k IN keys(n) | n[k]]",
            "MATCH ()-[r]->() RETURN [key IN keys(r) | key + '->' + r[key]]",
            "WITH \$expr AS expr, \$idx AS idx RETURN expr[idx]",
        )) {
            val sql = routed(oracle(cypher, mapOf("expr" to mapOf("name" to "Apa"), "idx" to "name")))
            assertTrue(sql.contains(entries) && sql.contains("'\$[*]?(@.key == \$k).value'"), sql)
            // Only of a map: a list read by a text is null, and not its items' entries.
            assertTrue(sql.contains("'\$.type()') = 'object' THEN"), sql)
        }
    }

    @Test
    fun `an entry that is a text is read through its text, and anything else as JSON`() {
        val sql = routed(oracle("MATCH (n:Doc) WITH n, 'long' AS k RETURN n[k]"))
        assertTrue(sql.contains("'\$[*]?(@.key == \$k).value.type()' PASSING "), sql)
        val throughText = Regex(Regex.escape("JSON(JSON_QUERY(JSON_SERIALIZE(JSON_QUERY(") + ".*?" + Regex.escape("$entries RETURNING CLOB), '\$[*]?(@.key == \$k).value' PASSING ") + ".*?" + Regex.escape(" AS \"k\" RETURNING CLOB))"))
        assertTrue(throughText.containsMatchIn(sql), sql)
    }

    @Test
    fun `a key written in the statement is read by its path, as before`() {
        val sql = oracle("MATCH (n) RETURN n['name']")
        assertTrue(!sql.contains("keyvalue()"), sql)
    }
}
