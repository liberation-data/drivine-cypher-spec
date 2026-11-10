package org.drivine.cypher

import org.drivine.cypher.sql.OracleCypherCompiler
import org.drivine.cypher.sql.PostgresCypherCompiler
import org.drivine.cypher.sql.SqliteCypherCompiler
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * On Postgres, a `MATCH` that nothing pins (no node bound before it, given properties of the row, or pinned by its `WHERE`)
 * reads a pattern of relationships of one hop each as a chain of probes, from the node it starts at: the first an earlier
 * pattern of the match bound, else the first with a label, else its first relationship, read whole. Each relationship is
 * read by a probe of its end toward the start, `CROSS JOIN LATERAL (SELECT * FROM … e WHERE e.src = n.nid OFFSET 0) e`, and
 * each node it reaches by a probe of its key, `CROSS JOIN LATERAL (SELECT * FROM g_node n WHERE n.nid = e.dst OFFSET 0) n`.
 * Joined plainly, Postgres planned `MATCH (:CyLoop)-[r:CY_SELF]->(:CyLoop) RETURN count(r)`, with the node table analyzed
 * just after it was emptied (statistics of no rows over its pages), as a nested loop that read every node again for each
 * node: 475 s over 20,000 relationships, where the chain takes 30 ms (drivine#680, drivine4j#673). A pinned match is
 * selective, which Postgres plans well joined plainly, so it is compiled exactly as before: chaining it too is
 * drivine4j#742. The rows are the spec's; the plan and its time need a store, and are drivine4j's. This pins the shape: that
 * one table is read whole, the start, and every other read is a probe whose one condition an index answers, of a row read
 * before it; and that a pinned match's SQL is the plain join's, byte for byte.
 */
class PostgresMatchChainTest {

    private fun sql(cypher: String) = PostgresCypherCompiler(GRAPH).compile(cypher, emptyMap()).sql

    /** A probe of a relationship's end or of a node's key: the rows read, the alias, the column, and what it equals. */
    private val probe = Regex(
        """CROSS JOIN LATERAL \(\s*SELECT \* FROM (${GRAPH}_node|${GRAPH}_edge|\(SELECT eid[^\n]*\)) ([en]\d+) WHERE \2\.(nid|src|dst|a|b) = ([a-z]+\d+)\.(\w+) OFFSET 0\s*\) \2\n""",
    )

    /** A table read whole: the alias of each node or relationship table, or the rows of both ways round, joined plainly. */
    private val whole = Regex("""(?:FROM|CROSS JOIN) (?:${GRAPH}_node|${GRAPH}_edge|\(SELECT eid[^\n]*\)) ([en]\d+)\n""")

    /**
     * The chain [sql] reads, checked: [whole] tables read whole, the chain's start or the node a `MATCH` before it read, and
     * each probe, of a node by `nid` or of a relationship by an end, equal to a column of an alias read before it: the
     * relationship's end, or the node's key. Gives the probes in the order read: alias, column, the alias it reads, its column.
     */
    private fun chain(sql: String, whole: Int = 1): List<String> {
        assertEquals(whole, this.whole.findAll(sql).count(), sql)
        val probes = probe.findAll(sql).toList()
        for (found in probes) {
            val (rows, alias, column, from, fromColumn) = found.groupValues.drop(1)
            val read = sql.substring(0, found.range.first)
            assertTrue(Regex("""\b$from\b""").containsMatchIn(read), "$alias reads $from before it is read:\n$sql")
            if (rows == "${GRAPH}_node") {
                assertEquals("nid", column, sql)
                assertTrue(fromColumn in setOf("src", "dst", "a", "b"), sql)
            } else {
                assertTrue(column in setOf("src", "dst", "a", "b"), sql)
                assertEquals("nid", fromColumn, sql)
            }
        }
        return probes.map { it.groupValues.drop(2).take(4).joinToString(" ") }
    }

    @Test
    fun `the count of drivine#680 reads each node of the label once, its relationships by their end, and each far end by its key`() {
        val sql = sql("MATCH (:CyLoop)-[r:CY_SELF]->(:CyLoop) RETURN count(r)")
        assertTrue("FROM ${GRAPH}_node n1\n" in sql, sql)
        assertEquals(listOf("e3 src n1 nid", "n2 nid e3 dst"), chain(sql))
        // Every condition it had is kept, the probes' among them, so the rows can't change.
        assertTrue("AND e3.src = n1.nid AND e3.dst = n2.nid\n" in sql, sql)
        assertTrue("AND e3.type = 'CY_SELF'" in sql, sql)
    }

    @Test
    fun `an unpinned chain starts at the first node an earlier pattern bound, else the first with a label, else its first relationship`() {
        val chains = listOf(
            // None with a label: the first relationship read whole.
            "MATCH (a)-[:R]->(b) RETURN a, b" to listOf("n1 nid e3 src", "n2 nid e3 dst"),
            "MATCH (a)<-[:R]-(b) RETURN a, b" to listOf("n1 nid e3 dst", "n2 nid e3 src"),
            "MATCH (a)-[:R]-(b) RETURN a, b" to listOf("n1 nid e3 a", "n2 nid e3 b"),
            "MATCH (a)-[:R]->(b)-[:S]->(c) RETURN c" to listOf("n1 nid e4 src", "n2 nid e4 dst", "e5 src n2 nid", "n3 nid e5 dst"),
            "MATCH (a)-[:R]->(b)<-[:S]-(a) RETURN b" to listOf("n1 nid e3 src", "n2 nid e3 dst", "e4 dst n2 nid"),
            // The first with a label, read by the labels' index, and the relationships before it backwards.
            "MATCH (a:Rare)-[:COMMON]->(b) RETURN b" to listOf("e3 src n1 nid", "n2 nid e3 dst"),
            "MATCH (a)-[:R]->(b:L) RETURN a" to listOf("e3 dst n2 nid", "n1 nid e3 src"),
            "MATCH (a)-[:R]->(b:L)-[:S]->(c:M) RETURN a, c" to listOf("e4 dst n2 nid", "n1 nid e4 src", "e5 src n2 nid", "n3 nid e5 dst"),
            "MATCH (n:CyLoop)-[r:CY_SELF]->(n) RETURN count(r)" to listOf("e2 src n1 nid"),
            // A variable given its label where it is met again: the chain starts where it first appears.
            "MATCH (a)-[:R]->(b)<-[:S]-(a:L) RETURN b" to listOf("e3 src n1 nid", "n2 nid e3 dst", "e4 dst n2 nid"),
            // A WHERE that pins no node: equal to another of the pattern's nodes, in an OR, or a value in the node's list.
            "MATCH (a)-[:R]->(b) WHERE b.k = a.k RETURN a" to listOf("n1 nid e3 src", "n2 nid e3 dst"),
            "MATCH (a)-[:R]->(b) WHERE b.k = a.k OR b.id = 1 RETURN a" to listOf("n1 nid e3 src", "n2 nid e3 dst"),
            "MATCH (a)-[:R]->(b) WHERE 1 IN b.ks RETURN a" to listOf("n1 nid e3 src", "n2 nid e3 dst"),
            // Properties that read another of the match's nodes pin nothing: no index finds the node before that one is read.
            "MATCH (a:L)-[:R]->(b {k: a.k}) RETURN b" to listOf("e3 src n1 nid", "n2 nid e3 dst"),
            "MATCH (a:L)-[:R]->(b), (b {k: a.k}) RETURN b" to listOf("e3 src n1 nid", "n2 nid e3 dst"),
            // A second pattern starts at the node the first bound.
            "MATCH (a:L)-[:R]->(b), (c)-[:S]->(b) RETURN c" to listOf("e3 src n1 nid", "n2 nid e3 dst", "e5 dst n2 nid", "n4 nid e5 src"),
        )
        for ((cypher, expected) in chains) assertEquals(expected, chain(sql(cypher)), cypher)
    }

    @Test
    fun `a pinned match is joined plainly, its SQL byte for byte what it was before chains`() {
        // Each statement and the SQL origin/main compiled it to: a node bound before the match (by a MATCH, an OPTIONAL
        // MATCH's row, a WITH), given properties of a value or of the row, pinned by the WHERE, by a property or the key, and
        // given properties in another pattern of the match.
        val before = javaClass.getResource("/postgres-pinned-match.txt")!!.readText().split("=== ").drop(1).map { part ->
            part.substringBefore('\n') to part.substringAfter('\n').removeSuffix("\n")
        }
        assertEquals(9, before.size)
        for ((cypher, sql) in before) assertEquals(sql, sql(cypher), cypher)
    }

    @Test
    fun `a match pinned on a relationship, or by a later clause, is joined plainly`() {
        // A relationship's key or properties, which an index may find, or a node pinned by a later MATCH or a WITH's WHERE,
        // whose condition is met in the same query: chained from `a`, every node of the label was read for the one row.
        for (cypher in listOf(
            "MATCH (a:L)-[r]->(b) WHERE id(r) = 1 RETURN b",
            "MATCH (a:L)-[r]->(b) WHERE r.k IN [1, 2] RETURN b",
            "MATCH (a:L)-[r:R {k: 1}]->(b) RETURN b",
            "MATCH (a:L)-[:R]->(b) MATCH (b {k: 1}) RETURN a",
            "MATCH (a:L)-[:R]->(b) MATCH (b) WHERE b.k = 1 RETURN a",
            "MATCH (a:L)-[:R]->(b) WITH a, b WHERE b.k = 1 RETURN a",
            "MATCH (a:L)-[:R]->(b) UNWIND [1, 2] AS i MATCH (c)-[:S]->(b) WHERE id(b) = i RETURN c",
        )) {
            val sql = sql(cypher)
            assertFalse(Regex("""SELECT \* FROM ${GRAPH}_node n\d+ WHERE n\d+\.nid = e\d+\.""").containsMatchIn(sql), "$cypher:\n$sql")
        }
        // One that reads the match's own node pins nothing, there or later.
        assertEquals(listOf("e3 src n1 nid", "n2 nid e3 dst"), chain(sql("MATCH (a:L)-[r:R {k: a.k}]->(b) RETURN b")))
        assertEquals(listOf("e3 src n1 nid", "n2 nid e3 dst"), chain(sql("MATCH (a:L)-[:R]->(b) MATCH (b {k: a.k}) RETURN b")))
    }

    @Test
    fun `an unpinned OPTIONAL MATCH reads its chain within the lateral query the row is kept beside`() {
        val optional = sql("MATCH (p:A) OPTIONAL MATCH (a:B)-[:R]->(b) RETURN a")
        assertTrue("LEFT JOIN LATERAL" in optional && "FROM ${GRAPH}_node n2\n" in optional, optional)
        assertEquals(listOf("e4 src n2 nid", "n3 nid e4 dst"), chain(optional, whole = 2), optional)
    }

    @Test
    fun `a walk, a quantified pattern, a shortest path, a pattern of no relationship and a pattern in an expression join as before`() {
        for (cypher in listOf(
            "MATCH (a)-[:R*1..2]->(b) RETURN b",
            "MATCH (a)-[:R]->(b)-[:S*1..2]->(c) RETURN c",
            "MATCH (a) ((x)-[:R]->(y)){1,2} (b) RETURN b",
            "MATCH (a {id: 1}), (b {id: 2}) MATCH p = shortestPath((a)-[:R*]-(b)) RETURN length(p)",
            "MATCH (a:A) RETURN a",
            "MATCH (a:A) WHERE (a)-[:R]->(:B) RETURN a",
            "MATCH (a:A) RETURN [(a)-[:R]->(b) | b.k]",
        )) {
            val sql = sql(cypher)
            assertFalse(Regex("""SELECT \* FROM ${GRAPH}_node n\d+ WHERE n\d+\.nid = e\d+\.""").containsMatchIn(sql), "$cypher:\n$sql")
        }
    }

    @Test
    fun `a MERGE reads its pattern by its end probe alone, as before`() {
        val sql = sql("MATCH (n:CyLoop) MERGE (n)-[:CY_OUT]->(:CyOther)")
        assertFalse(Regex("""SELECT \* FROM ${GRAPH}_node n\d+ WHERE n\d+\.nid = e\d+\.""").containsMatchIn(sql), sql)
        assertTrue(Regex("""SELECT \* FROM ${GRAPH}_edge e\d+ WHERE e\d+\.src = n\d+\.nid OFFSET 0""").containsMatchIn(sql), sql)
    }

    @Test
    fun `Oracle and SQLite join a MATCH's pattern plainly`() {
        val cypher = "MATCH (:CyLoop)-[r:CY_SELF]->(:CyLoop) RETURN count(r)"
        for (sql in listOf(OracleCypherCompiler(GRAPH).compile(cypher, emptyMap()).sql, SqliteCypherCompiler(GRAPH).compile(cypher, emptyMap()).sql)) {
            assertFalse("LATERAL" in sql || "OFFSET 0" in sql, sql)
            assertTrue("${GRAPH}_node n2" in sql && "${GRAPH}_edge e3" in sql, sql)
        }
    }

    private companion object {
        const val GRAPH = "g"
    }
}
