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
        // A relationship's pattern after the rows keeps its own joins reorderable, so its nodes aren't paired before it is read.
        val step = sql("UNWIND \$rows AS row MATCH (a:Person {id: row.s})-[:F]->(b:Person) RETURN count(*)")
        assertEquals(1, Regex("""CROSS JOIN""").findAll(step).count(), step)
    }

    @Test
    fun `with no UNWIND, and on the other stores, nothing is pinned`() {
        assertFalse("CROSS JOIN" in sql("MATCH (a:Person {id: 1}) MATCH (b:Person {id: 2}) RETURN count(*)"))
        val postgres = PostgresCypherCompiler("g").compile("UNWIND \$rows AS row MATCH (a:Person {id: row.s}) RETURN count(*)", rows).sql
        assertTrue("CROSS JOIN g_node" in postgres, postgres)
    }
}
