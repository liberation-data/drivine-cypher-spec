package org.drivine.cypher

import org.drivine.cypher.sql.PostgresCypherCompiler
import org.drivine.cypher.sql.SqliteCypherCompiler
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * drivine#654: on SQLite the source after an `UNWIND`'s rows is joined with `CROSS JOIN`, so SQLite reads each row before
 * it. Joined as any other source, SQLite read the rows innermost, and scanned the nodes of each `MATCH` after them around
 * them: two `MATCH`es by a key from each row read nodes × nodes × rows, 78 s for 40 rows over 800 nodes. Every other join
 * stays reorderable (drivine4j#571).
 */
class SqliteRowsJoinTest {

    private val rows = mapOf("rows" to listOf(mapOf("s" to 1, "d" to 2)))

    private fun sql(cypher: String) = SqliteCypherCompiler("g").compile(cypher, rows).sql

    @Test
    fun `the source after an UNWIND's rows is read after them, and the sources after it are still reordered`() {
        listOf(
            "UNWIND \$rows AS row MATCH (a:Person {id: row.s}) MATCH (b:Person {id: row.d}) RETURN count(*)",
            "UNWIND \$rows AS row MATCH (a:Person {id: row.s}) MATCH (b:Person {id: row.d}) CREATE (a)-[:FOLLOWS]->(b)",
            "MATCH (x:Person {id: 0}) UNWIND \$rows AS row MATCH (b:Person {id: row.d}) RETURN count(*)",
        ).forEach { cypher ->
            val sql = sql(cypher)
            assertEquals(1, Regex("""CROSS JOIN g_node""").findAll(sql).count(), "$cypher\n$sql")
            // A write's query is held in its procedure as JSON text, where its line break is written `\n`.
            assertTrue(Regex("""AS u\d+( ON TRUE)?(\s|\\n)+CROSS JOIN g_node""").containsMatchIn(sql), "$cypher\n$sql")
        }
        // A `WITH` of the rows, which SQLite merges into the query that reads it, keeps them read first: 5.7 s at 300 nodes
        // and 40 rows, the rows read innermost, before; 0.04 s after. So does an OPTIONAL MATCH between, joined to the rows.
        listOf(
            "UNWIND \$rows AS row WITH row MATCH (a:Person {id: row.s}) MATCH (b:Person {id: row.d}) RETURN count(*)",
            "UNWIND \$rows AS row WITH row WHERE row.s >= 0 WITH row MATCH (a:Person {id: row.s}) MATCH (b:Person {id: row.d}) RETURN count(*)",
            "UNWIND \$rows AS row OPTIONAL MATCH (z:Person {id: 0}) MATCH (a:Person {id: row.s}) MATCH (b:Person {id: row.d}) RETURN count(*)",
        ).forEach { cypher ->
            val sql = sql(cypher)
            assertEquals(1, Regex("""CROSS JOIN g_node""").findAll(sql).count(), "$cypher\n$sql")
            assertTrue(Regex("""(\) w\d+|\) AS o\d+ ON TRUE)\s+CROSS JOIN g_node""").containsMatchIn(sql), "$cypher\n$sql")
        }
        assertFalse("CROSS JOIN" in sql("MATCH (x:Person) WITH x MATCH (a:Person {id: x.id}) RETURN count(*)"))
        // A relationship's pattern after the rows keeps its own joins reorderable, so its nodes aren't paired before it is read.
        val step = sql("UNWIND \$rows AS row MATCH (a:Person {id: row.s})-[:F]->(b:Person) RETURN count(*)")
        assertEquals(1, Regex("""CROSS JOIN""").findAll(step).count(), step)
    }

    @Test
    fun `a source read by no condition on the rows is not pinned after them`() {
        // Pinned, each row read every node: 5.8 s against 0.007 s for 1,000 rows over 10,000 nodes.
        listOf(
            "UNWIND \$rows AS row MATCH (c:Config {key: 'k'}) RETURN row.s, c",
            "UNWIND \$rows AS row WITH row MATCH (c:Config {key: 'k'}) RETURN row.s, c",
            "UNWIND \$rows AS row MATCH (c:Config {key: 'k'}) CREATE (c)-[:HAS]->(:Item {id: row.s})",
        ).forEach { cypher -> sql(cypher).let { assertFalse("CROSS JOIN" in it, "$cypher\n$it") } }
        // Read by a WHERE, it is pinned as by its properties.
        val where = sql("UNWIND \$rows AS row MATCH (a:Person) WHERE a.id = row.s RETURN count(*)")
        assertTrue(Regex("""AS u\d+\s+CROSS JOIN g_node""").containsMatchIn(where), where)
    }

    @Test
    fun `the rows a write left are read before the MATCHes after it`() {
        // Read innermost, as the rows of an UNWIND were, the MATCHes after the CREATE took 37 s at 800 nodes and 40 rows; 0.08 s after.
        listOf(
            "UNWIND \$rows AS row CREATE (:Log {id: row.s}) MATCH (a:Person {id: row.s}) MATCH (b:Person {id: row.d}) RETURN count(*)",
            "UNWIND \$rows AS row CREATE (:Log {id: row.s}) WITH row MATCH (a:Person {id: row.s}) MATCH (b:Person {id: row.d}) CREATE (a)-[:F]->(b)",
        ).forEach { cypher ->
            val sql = sql(cypher)
            assertEquals(1, Regex("""CROSS JOIN g_node""").findAll(sql).count(), "$cypher\n$sql")
            assertTrue(Regex("""(json_each\(\?1\) AS r\d+|\) w\d+)(\s|\\n)+CROSS JOIN g_node""").containsMatchIn(sql), "$cypher\n$sql")
        }
    }

    @Test
    fun `with no UNWIND, and on the other stores, nothing is pinned`() {
        assertFalse("CROSS JOIN" in sql("MATCH (a:Person {id: 1}) MATCH (b:Person {id: 2}) RETURN count(*)"))
        val postgres = PostgresCypherCompiler("g").compile("UNWIND \$rows AS row MATCH (a:Person {id: row.s}) RETURN count(*)", rows).sql
        assertTrue("CROSS JOIN g_node" in postgres, postgres)
    }
}
