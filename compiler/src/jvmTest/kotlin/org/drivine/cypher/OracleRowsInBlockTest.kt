package org.drivine.cypher

import org.drivine.cypher.sql.OracleCypherCompiler
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * On Oracle, a statement that writes reads the rows it returns inside its block, into the list `cy_result`, and opens the
 * cursor it returns them by over that list alone (liberation-data/drivine-cypher-spec#292). A cursor opened over the rows'
 * query ran it only as the host fetched, after the block's writes were done: a returned item that failed as it ran then
 * failed the fetch and kept the writes. With and without `rowsAsText`.
 */
class OracleRowsInBlockTest {

    private val parameters = mapOf("long" to "x".repeat(40000))

    private fun sql(cypher: String, rowsAsText: Boolean) =
        OracleCypherCompiler("g", true, true, emptySet(), null, rowsAsText).compile(cypher, parameters).sql

    private val writes = listOf(
        "MATCH (n:N) SET n.x = 1 RETURN keys(n.num)",
        "MATCH (n:N) WITH n, n.num AS num DETACH DELETE n RETURN keys(num)",
        "UNWIND [3, 1, 2] AS i CREATE (n:N {i: i}) RETURN n.i ORDER BY n.i DESC",
        "MERGE (n:N {id: 1}) ON CREATE SET n.made = true RETURN n",
        "MATCH (a:N), (b:M) SET a.x = 1 RETURN a.n + b.n",
        "UNWIND [1, 2] AS i CALL { WITH i CREATE (n:N {i: i}) RETURN n } RETURN n.i",
        "MATCH (n:N) CALL (n) { SET n.x = 1 RETURN n.i AS j } RETURN j",
        "UNWIND [1, 2] AS i CREATE (n:N {i: i, long: \$long}) RETURN n.long",
        "CREATE (n:N {d: date('2026-11-09'), x: 2.0}) RETURN n.d, n.x, 0.0 / 0.0 AS nan",
    )

    /** The statements of the block after its last write: from the read of the rows into `cy_result` to its end. */
    private fun returned(sql: String): List<String> {
        val at = sql.indexOf(" INTO cy_result FROM (\n")
        assertTrue(at > 0, sql)
        val start = sql.lastIndexOf("\n    SELECT ", at)
        return sql.substring(start + 1).removeSuffix("\nEND;").split(";\n").map { it.trim().removeSuffix(";") }.filter { it.isNotEmpty() }
    }

    @Test
    fun `a write reads its rows in the block, and its cursor reads only the list it read them into`() {
        for (rowsAsText in listOf(false, true)) for (cypher in writes) {
            val sql = sql(cypher, rowsAsText)
            val steps = returned(sql)
            assertEquals(3, steps.size, "$cypher: $steps")
            val (read, open, result) = steps
            assertTrue(read.startsWith("SELECT ") && "ROWNUM AS cy_n" in read, "$cypher: $read")
            assertTrue(open.startsWith("OPEN cy_cursor FOR SELECT "), "$cypher: $open")
            assertEquals("DBMS_SQL.RETURN_RESULT(cy_cursor)", result, cypher)
            // The cursor's query reads no table and none of the block's other variables: nothing that runs as it is fetched can fail.
            for (other in listOf("g_node", "g_edge", "cy_rows", "cy_params", "cy_row", "cy_caller", "cy_out", "cy_value", "TO_NUMBER")) {
                assertFalse(other in open, "$cypher, rowsAsText $rowsAsText: the cursor reads $other: $open")
            }
            assertTrue("cy_result" in open, open)
            assertEquals(1, Regex("""\bcy_result JSON;""").findAll(sql).count(), sql)
        }
    }

    @Test
    fun `a row each is held as a list of it, so SQL's null and JSON's come back as they were`() {
        val sql = sql(writes.first(), rowsAsText = false)
        assertTrue("JSON_ARRAYAGG(JSON_ARRAY(cy_r.result ABSENT ON NULL RETURNING JSON) ORDER BY cy_r.cy_n RETURNING JSON) INTO cy_result" in sql, sql)
        // Taken out of its list as a list's item is, a text through its text: see OracleCypherCompiler.listItem.
        assertTrue(
            "SELECT CASE WHEN JSON_VALUE(cy_l.item, '\$[0].type()') = 'string' THEN JSON(JSON_QUERY(JSON_SERIALIZE(cy_l.item RETURNING CLOB), '\$[0]' RETURNING CLOB)) " +
                "ELSE JSON_QUERY(cy_l.item, '\$[0]' RETURNING JSON) END AS result FROM JSON_TABLE(cy_result, '\$[*]' COLUMNS (cy_n FOR ORDINALITY, item JSON PATH '\$')) cy_l ORDER BY cy_l.cy_n" in sql,
            sql,
        )
    }

    @Test
    fun `the statement's own hints start the read of the rows, and its query's stay in it`() {
        for (rowsAsText in listOf(false, true)) {
            val (read) = returned(sql("MATCH (a:N), (b:M) SET a.x = 1 RETURN a.n + b.n", rowsAsText))
            assertTrue(read.startsWith("SELECT /*+ NO_JSON_TABLE_TRANSFORM */ "), read)
            val (plainRead) = returned(sql("CREATE (n:N {i: 1}) RETURN n.i", rowsAsText))
            assertTrue(plainRead.startsWith("SELECT COALESCE(") || plainRead.startsWith("SELECT JSON_ARRAYAGG("), plainRead)
        }
    }

    @Test
    fun `a read, and a write that returns nothing, have no list of rows`() {
        for (cypher in listOf("MATCH (p:Person) RETURN p.name ORDER BY p.name", "UNWIND [1, 2] AS i RETURN i")) {
            val sql = sql(cypher, rowsAsText = false)
            assertFalse("cy_result" in sql || "cy_cursor" in sql, sql)
        }
        for (cypher in listOf("CREATE (n:N {i: 1})", "MATCH (n:N) DETACH DELETE n", "UNWIND [1, 2] AS i CALL { WITH i CREATE (:N {i: i}) }")) {
            val sql = sql(cypher, rowsAsText = false)
            assertFalse("cy_result" in sql || "OPEN cy_cursor" in sql, sql)
        }
    }
}
