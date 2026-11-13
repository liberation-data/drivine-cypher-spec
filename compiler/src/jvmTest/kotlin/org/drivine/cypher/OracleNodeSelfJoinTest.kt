package org.drivine.cypher

import com.fasterxml.jackson.databind.ObjectMapper
import org.drivine.cypher.api.CompileJson
import org.drivine.cypher.oracle.Oracle
import org.junit.jupiter.api.Test
import java.io.File
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * No statement compiled for Oracle joins the node table to an inline view that reads it, on a bare `nid` equality.
 *
 * Oracle 23.26 eliminates such a join together with any filter on the side it eliminates, where an index over a `JSON_VALUE`
 * of a node's property is made, as a uniqueness constraint makes one. Given a bound value, the server process ends as it
 * parses the statement (ORA-07445 [kkocfbMarkBindFroCB], ORA-03113 at the client). Given a literal value, the filter is
 * dropped and every row comes back (liberation-data/drivine-cypher-spec#160). The compiler's own node equality is written
 * `nid = x + 0`, which Oracle does not eliminate. A recursive CTE, a view of the edge table, and a key that is an
 * expression are not that shape either.
 */
class OracleNodeSelfJoinTest {

    private val json = ObjectMapper()

    /** The spec's files, as the test's resources hold them. */
    private val spec = File(requireNotNull(javaClass.getResource("/cypher/queries.yaml")) { "No spec file cypher/queries.yaml" }.toURI()).parentFile

    @Test
    fun `no statement compiled for Oracle joins the node table to a view of it on a bare nid`() {
        val oracle = Oracle.corpusRequests(spec).mapNotNull { request ->
            val read = runCatching { json.readTree(request) }.getOrNull()?.takeIf { it.isObject } ?: return@mapNotNull null
            if (read["store"]?.textValue() != "oracle") return@mapNotNull null
            val sql = json.readTree(CompileJson.compile(request))["sql"]?.textValue() ?: return@mapNotNull null
            (read["cypher"]?.textValue() ?: read["vector"]?.toString() ?: read.toString()) to sql
        }
        val searches = oracle.count { (_, sql) -> "VECTOR_DISTANCE" in sql }
        assertTrue(oracle.size > 1_000 && searches > 50, "${oracle.size} Oracle statements, $searches vector searches among them")
        val found = oracle.mapNotNull { (cypher, sql) -> NodeSelfJoins.of(sql).takeIf { it.isNotEmpty() }?.let { "$cypher\n    $it" } }
        assertEquals(emptyList(), found.take(20), "${found.size} statements join the node table to a view of it on a bare nid")
    }

    @Test
    fun `the check finds the join a vector search's candidates had, and not the compiler's own forms`() {
        val joinedBack = """
            SELECT c.nid FROM (
                SELECT c.nid, (2 - k.distance) / 2 AS score
                FROM (SELECT s.nid FROM g_v_x s ORDER BY 1 FETCH APPROX FIRST 3 ROWS ONLY) k
                JOIN g_node c ON c.nid = k.nid
            ) m
            JOIN g_node c ON c.nid = m.nid
            WHERE JSON_VALUE(c.props, '$.id') = :1
        """.trimIndent()
        assertEquals(listOf("c.nid = m.nid"), NodeSelfJoins.of(joinedBack))
        // The same join written as Oracle reads it alike: its condition in brackets, on a line of its own, or in the WHERE of
        // a cross join, as the compiler binds a node it matches; and the view joined to the node table rather than it to the view.
        assertEquals(listOf("c.nid = m.nid"), NodeSelfJoins.of(joinedBack.replace("ON c.nid = m.nid", "ON (c.nid = m.nid)")))
        assertEquals(listOf("c.nid = m.nid"), NodeSelfJoins.of(joinedBack.replace("JOIN g_node c ON", "JOIN g_node c\n    ON")))
        assertEquals(listOf("c.nid = m.nid"), NodeSelfJoins.of(joinedBack.replace("JOIN g_node c ON c.nid = m.nid\nWHERE", "CROSS JOIN g_node c\nWHERE c.nid = m.nid AND")))
        assertEquals(listOf("m.nid = c.nid"), NodeSelfJoins.of("SELECT c.nid FROM g_node c JOIN (SELECT n.nid FROM g_node n) m ON m.nid = c.nid WHERE JSON_VALUE(c.props, '$.id') = :1"))
        // A view of a recursive walk of the graph, which Oracle works out apart and does not merge, as the compiler's walks are read.
        val walked = "SELECT n2.nid FROM g_node n2 CROSS JOIN (WITH w5 (src, nid) AS (SELECT p.nid, p.nid FROM g_node p UNION ALL " +
            "SELECT w5.src, e.dst FROM w5 JOIN g_edge e ON e.src = w5.nid) SELECT src, nid FROM w5) p4 WHERE p4.nid = n2.nid"
        assertEquals(emptyList(), NodeSelfJoins.of(walked))
        // The same with the view's columns read, or the key compared as an expression, or the view one of the edge table.
        assertEquals(emptyList(), NodeSelfJoins.of(joinedBack.replace("JOIN g_node c ON c.nid = m.nid", "")))
        assertEquals(emptyList(), NodeSelfJoins.of(joinedBack.replace("c.nid = m.nid", "c.nid = m.nid + 0 AND m.nid = c.nid + 0")))
        assertEquals(emptyList(), NodeSelfJoins.of("SELECT n.nid FROM (SELECT e.src AS nid FROM g_edge e) u JOIN g_node n ON n.nid = u.nid"))
        // A CTE is named, not written in brackets beside its alias, and a recursive one Oracle does not merge.
        assertEquals(emptyList(), NodeSelfJoins.of("WITH w (nid) AS (SELECT nid FROM g_node) SELECT n.nid FROM w JOIN g_node n ON n.nid = w.nid"))
    }
}

/**
 * Finds, in a statement compiled for Oracle, each join of the node table to an inline view that reads it, on a bare `nid`:
 * its condition wherever it is written, in an `ON`, bracketed or not, or in a `WHERE`, as Oracle reads an inner join alike.
 */
internal object NodeSelfJoins {

    /** The node table read as `<a>`: `FROM <graph>_node <a>` or `JOIN <graph>_node <a>`. */
    private val NODES = Regex("""\b(?:FROM|JOIN)\s+(\w+_node)\s+(\w+)\b""")

    /** `<a>.nid = <b>.nid`, not compared as an expression (`+ 0`). */
    private val EQUAL = Regex("""\b(\w+)\.nid\s*=\s*(\w+)\.nid\b(?!\s*\+)""")

    /** A view whose query is a recursive walk, `WITH <w> (<columns>) AS …`, which Oracle works out apart and does not merge. */
    private val RECURSIVE = Regex("""^\s*WITH\s+\w+\s*\(""")

    /** Each such equality in [sql], either way round, whose one side is the node table and other an inline view, `( … ) <b>`, that reads it. */
    fun of(sql: String): List<String> {
        val tables = NODES.findAll(sql).groupBy({ it.groupValues[2] }, { it.groupValues[1] })
        return EQUAL.findAll(sql).filter { match ->
            val (left, right) = match.destructured
            listOf(left to right, right to left).any { (node, view) ->
                tables[node].orEmpty().any { table -> viewBodies(sql, view).any { table in it && !RECURSIVE.containsMatchIn(it) } }
            }
        }.map { it.value }.toList()
    }

    /** The text of each inline view named [alias] in [sql]: what the brackets before `) <alias>` hold. */
    private fun viewBodies(sql: String, alias: String): List<String> = Regex("""\)\s*$alias\b""").findAll(sql).mapNotNull { close ->
        var depth = 0
        var at = close.range.first
        while (at >= 0) {
            when (sql[at]) {
                ')' -> depth++
                '(' -> if (--depth == 0) return@mapNotNull sql.substring(at + 1, close.range.first)
            }
            at--
        }
        null
    }.toList()
}
