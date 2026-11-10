package org.drivine.cypher

import org.drivine.cypher.sql.OracleCypherCompiler
import org.drivine.cypher.sql.PostgresCypherCompiler
import org.drivine.cypher.sql.SqliteCypherCompiler
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * On Postgres, a `MATCH` reads a pattern of relationships of one hop each as a chain of probes, from the node it starts at:
 * the first bound before it, else the first given properties, else the first with a label, else its first relationship,
 * read whole. Each relationship is
 * read by a probe of its end toward the start, `CROSS JOIN LATERAL (SELECT * FROM … e WHERE e.src = n.nid OFFSET 0) e`, and
 * each node it reaches by a probe of its key, `CROSS JOIN LATERAL (SELECT * FROM g_node n WHERE n.nid = e.dst OFFSET 0) n`.
 * Joined plainly, Postgres planned `MATCH (:CyLoop)-[r:CY_SELF]->(:CyLoop) RETURN count(r)`, with the node table analyzed
 * just after it was emptied (statistics of no rows over its pages), as a nested loop that read every node again for each
 * node: 475 s over 20,000 relationships, where the chain takes 30 ms (drivine#680, drivine4j#673). The rows are the spec's;
 * the plan and its time need a store, and are drivine4j's `CompiledWriteLoopTimePostgresTest`'s. This pins the shape: that
 * one table is read whole, the start, and every other read is a probe whose one condition an index answers, of a row read
 * before it.
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
    private fun chain(sql: String, whole: Int = 1): List<List<String>> {
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
                // A node's key, or the column a WITH passed it on in.
                assertTrue(fromColumn == "nid" || fromColumn.endsWith("_nid"), sql)
            }
        }
        return probes.map { it.groupValues.drop(2).let { (a, c, f, fc) -> listOf(a, c, f, fc) } }
    }

    @Test
    fun `the count of drivine#680 reads each node of the label once, its relationships by their end, and each far end by its key`() {
        val sql = sql("MATCH (:CyLoop)-[r:CY_SELF]->(:CyLoop) RETURN count(r)")
        assertTrue("FROM ${GRAPH}_node n1\n" in sql, sql)
        assertEquals(listOf(listOf("e3", "src", "n1", "nid"), listOf("n2", "nid", "e3", "dst")), chain(sql))
        // Every condition it had is kept, the probes' among them, so the rows can't change.
        assertTrue("AND e3.src = n1.nid AND e3.dst = n2.nid\n" in sql, sql)
        assertTrue("AND e3.type = 'CY_SELF'" in sql, sql)
    }

    @Test
    fun `a chain starts at the node bound before the pattern, else the first given properties, else the first with a label, else its first relationship`() {
        val chains = listOf(
            // No node pinned and none with a label: the first relationship read whole.
            "MATCH (a)-[:R]->(b) RETURN a, b" to listOf("n1 nid e3 src", "n2 nid e3 dst"),
            // None pinned: the first with a label, read by the labels' index, and the relationships before it backwards.
            "MATCH (a:Rare)-[:COMMON]->(b) RETURN b" to listOf("e3 src n1 nid", "n2 nid e3 dst"),
            "MATCH (a)-[:R]->(b:L) RETURN a" to listOf("e3 dst n2 nid", "n1 nid e3 src"),
            "MATCH (a)-[:R]->(b:L)-[:S]->(c:M) RETURN a, c" to listOf("e4 dst n2 nid", "n1 nid e4 src", "e5 src n2 nid", "n3 nid e5 dst"),
            "MATCH (n:CyLoop)-[r:CY_SELF]->(n) RETURN count(r)" to listOf("e2 src n1 nid"),
            "MATCH (a)-[:R]->(b)<-[:S]-(a:L) RETURN b" to listOf("e3 src n1 nid", "n2 nid e3 dst", "e4 dst n2 nid"),
            // One pinned wins over one with a label.
            "MATCH (a:L)-[:R]->(b {id: 1}) RETURN a" to listOf("e3 dst n2 nid", "n1 nid e3 src"),
            "MATCH (a)<-[:R]-(b) RETURN a, b" to listOf("n1 nid e3 dst", "n2 nid e3 src"),
            "MATCH (a)-[:R]-(b) RETURN a, b" to listOf("n1 nid e3 a", "n2 nid e3 b"),
            "MATCH (a)-[:R]->(b)-[:S]->(c) RETURN c" to listOf("n1 nid e4 src", "n2 nid e4 dst", "e5 src n2 nid", "n3 nid e5 dst"),
            "MATCH (a)-[:R]->(b)<-[:S]-(a) RETURN b" to listOf("n1 nid e3 src", "n2 nid e3 dst", "e4 dst n2 nid"),
            // Given properties: the walk starts there, and reads the relationships before it backwards.
            "MATCH (a)-[:R]->(b {id: 1}) RETURN a" to listOf("e3 dst n2 nid", "n1 nid e3 src"),
            "MATCH (a)<-[:R]-(b {id: 1}) RETURN a" to listOf("e3 src n2 nid", "n1 nid e3 dst"),
            "MATCH (a)-[:R]-(b {id: 1}) RETURN a" to listOf("e3 b n2 nid", "n1 nid e3 a"),
            "MATCH (a)-[:R]->(b {id: 1})-[:S]->(c) RETURN a, c" to listOf("e4 dst n2 nid", "n1 nid e4 src", "e5 src n2 nid", "n3 nid e5 dst"),
            "MATCH (a {id: 1})-[:R]->(b {id: 2}) RETURN b" to listOf("e3 src n1 nid", "n2 nid e3 dst"),
            // Pinned by the WHERE, a property or the key equal to a value: started there, where an index finds it, not
            // read by its key from every relationship. One equal to another of the pattern's nodes, or in an OR, pins none.
            "MATCH (a)-[:R]->(b) WHERE b.id = 1 RETURN a" to listOf("e3 dst n2 nid", "n1 nid e3 src"),
            "MATCH (a)-[:R]->(b)-[:S]->(c) WHERE c.k > 1 AND id(b) = 7 RETURN a" to
                listOf("e4 dst n2 nid", "n1 nid e4 src", "e5 src n2 nid", "n3 nid e5 dst"),
            "MATCH (a)-[:R]->(b) WHERE id(b) IN [1, 2] RETURN a" to listOf("e3 dst n2 nid", "n1 nid e3 src"),
            "MATCH (a)-[:R]->(b) WHERE b.k = a.k OR b.id = 1 RETURN a" to listOf("n1 nid e3 src", "n2 nid e3 dst"),
            "MATCH (a)-[:R]->(b) WHERE b.k = a.k RETURN a" to listOf("n1 nid e3 src", "n2 nid e3 dst"),
            // The value on the left, the key by elementId(); and a value in the node's list, which no index finds, pins none.
            "MATCH (a)-[:R]->(b) WHERE 1 = b.id RETURN a" to listOf("e3 dst n2 nid", "n1 nid e3 src"),
            "MATCH (a)-[:R]->(b) WHERE elementId(b) = '7' RETURN a" to listOf("e3 dst n2 nid", "n1 nid e3 src"),
            "MATCH (a)-[:R]->(b) WHERE 1 IN b.ks RETURN a" to listOf("n1 nid e3 src", "n2 nid e3 dst"),
            // Given properties in another of the match's patterns.
            "MATCH (a)-[:R]->(b), (b {id: 1}) RETURN a" to listOf("e3 dst n2 nid", "n1 nid e3 src"),
            // A variable given properties where it appears again: the chain starts where it first appears.
            "MATCH (a)-[:R]->(a {id: 1}) RETURN a" to listOf("e2 src n1 nid"),
            "MATCH (a)-[:R]->(b)<-[:S]-(c)-[:T]->(b {id: 1}) RETURN a" to
                listOf("e4 dst n2 nid", "n1 nid e4 src", "e5 dst n2 nid", "n3 nid e5 src", "e6 src n3 nid"),
        )
        for ((cypher, expected) in chains) assertEquals(expected, chain(sql(cypher)).map { it.joinToString(" ") }, cypher)
        // Bound before: the walk starts there, and reads nothing whole of its own.
        val bound = sql("MATCH (a:A) MATCH (b)-[:R]->(a)-[:S]->(c) RETURN b, c")
        assertEquals(listOf("e4 dst n1 nid", "n2 nid e4 src", "e5 src n1 nid", "n3 nid e5 dst"), chain(bound).map { it.joinToString(" ") }, bound)
        // A node bound before wins over one given properties, as a MERGE's does.
        val both = sql("MATCH (a:A) MATCH (b {id: 1})-[:R]->(a) RETURN b")
        assertEquals(listOf("e3 dst n1 nid", "n2 nid e3 src"), chain(both).map { it.joinToString(" ") }, both)
    }

    @Test
    fun `an OPTIONAL MATCH and a MATCH after a WITH read the chain from the row's node`() {
        val optional = sql("MATCH (a:A) OPTIONAL MATCH (a)-[:R]->(b) RETURN b")
        assertTrue("LEFT JOIN LATERAL" in optional, optional)
        assertEquals(listOf("e3 src n1 nid", "n2 nid e3 dst"), chain(optional).map { it.joinToString(" ") }, optional)
        // One whose WHERE pins a node by the row's: started there, within the lateral query the row is kept beside.
        val pinned = sql("MATCH (p:A) OPTIONAL MATCH (a)-[:R]->(b) WHERE b.k = p.k RETURN a")
        assertTrue("LEFT JOIN LATERAL" in pinned && "FROM ${GRAPH}_node n3\n" in pinned, pinned)
        assertEquals(listOf("e4 dst n3 nid", "n2 nid e4 src"), chain(pinned, whole = 2).map { it.joinToString(" ") }, pinned)
        val with = sql("MATCH (a:A) WITH a, count(*) AS c MATCH (a)-[:R]->(b) RETURN b, c")
        assertEquals(listOf("n", "nid", "e", "dst"), chain(with).last().map { it.trimEnd { ch -> ch.isDigit() } }, with)
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
