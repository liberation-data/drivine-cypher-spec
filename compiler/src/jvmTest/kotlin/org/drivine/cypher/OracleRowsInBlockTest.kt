package org.drivine.cypher

import org.drivine.cypher.sql.OracleCypherCompiler
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * On Oracle, a statement that writes works out the rows it returns inside its block, before it opens the cursor it returns
 * them by (liberation-data/drivine-cypher-spec#292). A cursor opened over the rows' query alone ran it only as the host
 * fetched, after the block's writes were done: a returned item that failed as it ran then failed the fetch and kept the
 * writes. A row each, the block counts the rows and the cursor runs the query again, as before; with `rowsAsText` the block
 * gathers them into the list `cy_result`, and the cursor reads that list alone.
 */
class OracleRowsInBlockTest {

    private val parameters = mapOf("long" to "x".repeat(40000))

    private fun sql(cypher: String, rowsAsText: Boolean) =
        OracleCypherCompiler("g", true, true, emptySet(), null, rowsAsText).compile(cypher, parameters).sql

    private val writes = listOf(
        "MATCH (n:N) SET n.x = 1 RETURN keys(n.num)",
        "MATCH (n:N) WITH n, n.num AS num DETACH DELETE n RETURN keys(num)",
        "UNWIND [3, 1, 2] AS i CREATE (n:N {i: i}) RETURN n.i ORDER BY n.i DESC",
        "MATCH (n:N) SET n.x = 1 RETURN n.num ORDER BY left('a', -n.num)",
        "MERGE (n:N {id: 1}) ON CREATE SET n.made = true RETURN n",
        "MATCH (a:N), (b:M) SET a.x = 1 RETURN a.n + b.n",
        "UNWIND [1, 2] AS i CALL { WITH i CREATE (n:N {i: i}) RETURN n } RETURN n.i",
        "MATCH (n:N) CALL (n) { SET n.x = 1 RETURN n.i AS j } RETURN j",
        "UNWIND [1, 2] AS i CREATE (n:N {i: i, long: \$long}) RETURN n.long",
        "CREATE (n:N {d: date('2026-11-09'), x: 2.0}) RETURN n.d, n.x, 0.0 / 0.0 AS nan",
    )

    /** The statements of the block after its last write: from the read of the rows before the cursor to its end. */
    private fun returned(sql: String): List<String> {
        val at = sql.indexOf("\n    OPEN cy_cursor FOR")
        assertTrue(at > 0, sql)
        val start = sql.lastIndexOf("\n    SELECT ", at)
        return sql.substring(start + 1).removeSuffix("\nEND;").split(";\n").map { it.trim().removeSuffix(";") }.filter { it.isNotEmpty() }
    }

    /** [query] numbered by `ROWNUM` in its order, as the block reads it, each of its lines indented by [indent]. */
    private fun numbered(query: String, indent: String) =
        "${indent}SELECT cy_q.result, ROWNUM AS cy_n FROM (\n${query.prependIndent("$indent    ")}\n$indent) cy_q"

    @Test
    fun `a row each, the block counts the rows of the query its cursor then runs as before`() {
        for (cypher in writes) {
            val sql = sql(cypher, rowsAsText = false)
            val steps = returned(sql)
            assertEquals(3, steps.size, "$cypher: $steps")
            val (read, open, result) = steps
            assertTrue(open.startsWith("OPEN cy_cursor FOR\n        SELECT "), "$cypher: $open")
            val query = open.removePrefix("OPEN cy_cursor FOR\n").trimIndent()
            // Every row's result, and its order with each sort key, worked out in the block: one that fails fails the block.
            assertTrue(read.matches(Regex("""SELECT (/\*\+ [^\n]* \*/ )?COUNT\(cy_r\.result\) \+ MAX\(cy_r\.cy_n\) INTO cy_count FROM \(\n[\s\S]*""")), "$cypher: $read")
            assertTrue(read.endsWith("(\n${numbered(query, "        ")}\n    ) cy_r"), "$cypher: $read")
            assertEquals("DBMS_SQL.RETURN_RESULT(cy_cursor)", result, cypher)
            assertFalse("cy_result" in sql || "JSON_ARRAYAGG(cy_r" in sql, sql)
        }
    }

    @Test
    fun `as text, a write gathers its rows in the block, and its cursor reads only the list it gathered them into`() {
        for (cypher in writes) {
            val sql = sql(cypher, rowsAsText = true)
            val steps = returned(sql)
            assertEquals(3, steps.size, "$cypher: $steps")
            val (read, open, result) = steps
            assertTrue(" INTO cy_result FROM (\n" in read && "ROWNUM AS cy_n" in read, "$cypher: $read")
            assertEquals("OPEN cy_cursor FOR SELECT JSON_SERIALIZE(cy_result RETURNING CLOB EXTENDED) AS \"rows:json\" FROM DUAL", open, cypher)
            assertEquals("DBMS_SQL.RETURN_RESULT(cy_cursor)", result, cypher)
            assertEquals(1, Regex("""\bcy_result JSON;""").findAll(sql).count(), sql)
        }
    }

    @Test
    fun `the statement's own hints start the read of the rows, and its query's stay in it`() {
        for (rowsAsText in listOf(false, true)) {
            val (read) = returned(sql("MATCH (a:N), (b:M) SET a.x = 1 RETURN a.n + b.n", rowsAsText))
            assertTrue(read.startsWith("SELECT /*+ NO_JSON_TABLE_TRANSFORM */ "), read)
            // A later end read by its key: planned without statistics feedback too, in the same hint.
            val (keyedRead) = returned(sql("CREATE (n:N) WITH n MATCH (a:N1)-->(b:N2) RETURN b.n + a.n", rowsAsText))
            assertTrue(keyedRead.startsWith("SELECT /*+ NO_JSON_TABLE_TRANSFORM ${OracleCypherCompiler.NO_FEEDBACK} */ "), keyedRead)
            val (plainRead) = returned(sql("CREATE (n:N {i: 1}) RETURN n.i", rowsAsText))
            assertTrue(plainRead.startsWith("SELECT COALESCE(") || plainRead.startsWith("SELECT COUNT("), plainRead)
        }
    }

    @Test
    fun `a read, and a write that returns nothing, have no list of rows`() {
        for (rowsAsText in listOf(false, true)) {
            for (cypher in listOf("MATCH (p:Person) RETURN p.name ORDER BY p.name", "UNWIND [1, 2] AS i RETURN i")) {
                val sql = sql(cypher, rowsAsText)
                assertFalse("cy_result" in sql || "cy_cursor" in sql, sql)
            }
            for (cypher in listOf("CREATE (n:N {i: 1})", "MATCH (n:N) DETACH DELETE n", "UNWIND [1, 2] AS i CALL { WITH i CREATE (:N {i: i}) }")) {
                val sql = sql(cypher, rowsAsText)
                assertFalse("cy_result" in sql || "OPEN cy_cursor" in sql, sql)
            }
        }
    }
}
