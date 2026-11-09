package org.drivine.cypher

import org.drivine.cypher.sql.OracleCypherCompiler
import org.drivine.cypher.sql.PostgresCypherCompiler
import org.drivine.cypher.sql.SqliteCypherCompiler
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * On Postgres, a `MERGE`'s read of its pattern, for each row of the loop that writes, reads each relationship by a probe of
 * one end, `CROSS JOIN LATERAL (SELECT * FROM … e WHERE e.src = n.nid OFFSET 0) e`: the end toward the first node bound
 * before the pattern, or else the first given properties, or else the first node. Joined plainly, it was planned by the
 * relationship table's statistics, which an ANALYZE during the loop (an autovacuum) leaves at no rows while the loop's own
 * are uncommitted: the plan made again read the whole of an index by the relationship's type alone for every row, so
 * `MATCH (n) MERGE (n)-[:R]->(n)` took 18 s over 20,000 nodes where it takes 0.5 s (drivine4j#663, drivine#671). The rows
 * it gives are the spec's MERGE cases'; the plans and times are those of Drivine for TypeScript's `CypherWriteLoopTime` and
 * of the `PostgresMergeEndProbe.spec.ts` drafted on drivine4j#663, which need a store. This pins the shape, and that the
 * probe's one condition an index answers is the bound end's key.
 */
class PostgresMergeEndProbeTest {

    private fun sql(cypher: String) = PostgresCypherCompiler(GRAPH).compile(cypher, emptyMap()).sql

    /** A probe of a relationship's end: the rows read, the alias, the end's column, and the node whose key it is equal to. */
    private val endProbe = Regex(
        """CROSS JOIN LATERAL \(\s*SELECT \* FROM (${GRAPH}_edge|\(SELECT eid[^\n]*\)) (e\d+) WHERE \2\.(src|dst|a|b) = (n\d+)\.nid OFFSET 0\s*\) \2\n""",
    )

    /** The relationship rows joined plainly, as a `MATCH` joins them. */
    private val edgesJoined = Regex("""CROSS JOIN (${GRAPH}_edge|\(SELECT eid[^\n]*\)) e\d+\n""")

    /** A node a write's row holds, read back by its id from the row ([keyJoin]): one bound before the pattern. */
    private fun boundFromRow(sql: String, node: String) =
        Regex("""LEFT JOIN LATERAL \(\s*SELECT \* FROM ${GRAPH}_node $node WHERE $node\.nid = [^\n]*OFFSET 0\s*\) $node ON TRUE""").containsMatchIn(sql)

    /** Each `MERGE`, the end column its relationship is probed by, and whether that end is a node bound before the pattern. */
    private val merges = listOf(
        Triple("MATCH (n:CyLoop) MERGE (n)-[:CY_SELF]->(n)", "src", true),
        Triple("MATCH (n:CyLoop) MERGE (n)-[r:CY_SELF]->(n) ON CREATE SET r.w = n.i", "src", true),
        Triple("MATCH (n:CyLoop) MERGE (n)-[:CY_OUT]->(:CyOther)", "src", true),
        Triple("MATCH (n:CyLoop) MERGE (n)<-[:CY_IN]-(:CyOther)", "dst", true),
        Triple("MATCH (n:CyLoop) MERGE (:CyOther)-[:CY_TO]->(n)", "dst", true),
        Triple("MATCH (n:CyLoop) MERGE (:CyOther)<-[:CY_FROM]-(n)", "src", true),
        Triple("MATCH (n:CyLoop) MERGE (n)-[:CY_ANY]-(:CyOther)", "a", true),
        Triple("MATCH (n:CyLoop) MERGE (:CyOther)-[:CY_ANY]-(n)", "b", true),
        Triple("MATCH (a:CyA), (b:CyB) MERGE (a)-[:CY_AB]->(b)", "src", true),
        Triple("MATCH (a:CyA), (b:CyB) MERGE (a)<-[:CY_BA]-(b)", "dst", true),
        // The bound end, not the one given properties: that node, the same for every row, gains a relationship at each.
        Triple("MATCH (n:CyLoop) MERGE (:CyTag {k: 1})-[:CY_TAGS]->(n)", "dst", true),
        Triple("MATCH (n:CyLoop) MERGE (:CyTag {k: 1})<-[:CY_TAGS]-(n)", "src", true),
        Triple("MATCH (n:CyLoop) MERGE (:CyTag {k: 1})-[:CY_TAGS]-(n)", "b", true),
        Triple("MATCH (n:CyLoop) MERGE (n)-[:CY_TAGS {w: 1}]->(:CyTag {k: 1})", "src", true),
        Triple("MATCH (n:CyLoop) CALL (n) { MERGE (n)-[:CY_SELF]->(n) } RETURN count(n)", "src", true),
        Triple("MATCH (n:CyLoop) FOREACH (i IN [1] | MERGE (n)-[:CY_SELF]->(n))", "src", true),
        Triple("MATCH (n:CyLoop) CALL (n) { FOREACH (i IN [1] | MERGE (:CyOther)-[:CY_TO]->(n)) } RETURN count(n)", "dst", true),
        Triple("MATCH (n:CyLoop) WITH n MERGE (:CyOther)-[:CY_TO]->(n)", "dst", true),
        Triple("MATCH (n:CyLoop) WITH n, count(*) AS c MERGE (:CyOther)-[:CY_TO]->(n)", "dst", true),
        Triple("UNWIND range(1, 3) AS i CREATE (n:CyLoop) MERGE (:CyOther)-[:CY_TO]->(n)", "dst", true),
        Triple("UNWIND range(1, 3) AS i MERGE (a:CyA {k: i})-[:CY_AB]->(b:CyB {k: i})", "src", false),
        Triple("MERGE (a:CyA {k: 1})-[:CY_AB]->(b:CyB)", "src", false),
        Triple("MERGE (a:CyA)-[:CY_AB]->(b:CyB {k: 2})", "dst", false),
    )

    @Test
    fun `a MERGE's read probes the end of its relationship bound before it`() {
        for ((cypher, end, bound) in merges) {
            val sql = sql(cypher)
            val probe = assertNotNull(endProbe.find(sql), "$cypher:\n$sql")
            assertEquals(end, probe.groupValues[3], "$cypher:\n$sql")
            // The probe's one condition an index can answer is the key of the node the row holds: one row's
            // relationships, read by the end's index, whatever the statistics say.
            assertEquals(bound, boundFromRow(sql, probe.groupValues[4]), "$cypher:\n$sql")
            assertFalse(edgesJoined.containsMatchIn(sql), "$cypher:\n$sql")
        }
    }

    @Test
    fun `each relationship of a MERGE's pattern of many is probed`() {
        val cypher = "MATCH (n:CyLoop) MERGE (n)-[:CY_A]->(:CyMid)-[:CY_B]->(:CyEnd)"
        val sql = sql(cypher)
        val probes = endProbe.findAll(sql).toList()
        assertEquals(listOf("src", "src"), probes.map { it.groupValues[3] }, "$cypher:\n$sql")
        assertTrue(boundFromRow(sql, probes[0].groupValues[4]), "$cypher:\n$sql")
        assertFalse(edgesJoined.containsMatchIn(sql), "$cypher:\n$sql")
    }

    @Test
    fun `each relationship of a MERGE's pattern of many is probed by its end toward the bound node`() {
        for ((cypher, ends) in listOf(
            "MATCH (n:CyLoop) MERGE (:CyStart)-[:CY_A]->(:CyMid)-[:CY_B]->(n)" to listOf("dst", "dst"),
            "MATCH (n:CyLoop) MERGE (:CyStart)<-[:CY_A]-(:CyMid)<-[:CY_B]-(n)" to listOf("src", "src"),
            "MATCH (n:CyLoop) MERGE (:CyStart)-[:CY_A]->(n)-[:CY_B]->(:CyEnd)" to listOf("dst", "src"),
            // A node at two positions: probed toward the one bound, its first.
            "MATCH (n:CyLoop) MERGE (m:CyMid)-[:CY_A]->(n)-[:CY_B]->(m)" to listOf("dst", "src"),
            "MATCH (n:CyLoop) MERGE (n)-[:CY_A]->(:CyMid)-[:CY_B]->(n)" to listOf("src", "src"),
            "MATCH (n:CyLoop) MERGE (:CyStart {k: 1})-[:CY_A]->(:CyMid)-[:CY_B]->(n)" to listOf("dst", "dst"),
            "MERGE (:CyStart)-[:CY_A]->(:CyMid)-[:CY_B]->(:CyEnd {k: 1})" to listOf("dst", "dst"),
        )) {
            val sql = sql(cypher)
            val probes = endProbe.findAll(sql).toList()
            assertEquals(ends, probes.map { it.groupValues[3] }, "$cypher:\n$sql")
            assertFalse(edgesJoined.containsMatchIn(sql), "$cypher:\n$sql")
        }
    }

    @Test
    fun `a relationship a MATCH reads is read as a chain, and a MERGE after it probes its own`() {
        // The MATCH's first relationship is read whole, and its node by its key from it: see PostgresMatchChainTest.
        val read = sql("MATCH (n:CyLoop)-[r:CY_SELF]->(n) RETURN count(r)")
        assertTrue("FROM ${GRAPH}_edge e2\n" in read, read)
        assertFalse(endProbe.containsMatchIn(read), read)
        // The MATCH's probe reads from the node the MATCH found; the MERGE's alone from the node the loop's row holds.
        val both = sql("MATCH (n:CyLoop) MATCH (n)-[:CY_OUT]->(m) MERGE (m)-[:CY_SELF]->(m)")
        assertEquals(1, endProbe.findAll(both).count { boundFromRow(both, it.groupValues[4]) }, both)
    }

    @Test
    fun `Oracle and SQLite join a MERGE's relationship plainly`() {
        for (compiler in listOf(OracleCypherCompiler(GRAPH), SqliteCypherCompiler(GRAPH))) {
            for ((cypher) in merges) {
                val sql = compiler.compile(cypher, emptyMap()).sql
                assertFalse(Regex("""FROM ${GRAPH}_edge e\d+ WHERE e\d+\.(src|dst) = """).containsMatchIn(sql), "${compiler::class.simpleName}, $cypher:\n$sql")
            }
        }
    }

    private companion object {
        const val GRAPH = "g"
    }
}
