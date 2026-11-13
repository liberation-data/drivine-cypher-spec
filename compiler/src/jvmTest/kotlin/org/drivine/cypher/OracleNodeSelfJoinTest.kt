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
        assertEquals(listOf("JOIN g_node c ON c.nid = m.nid"), NodeSelfJoins.of(joinedBack))
        // The same with the view's columns read, or the key compared as an expression, or the view one of the edge table.
        assertEquals(emptyList(), NodeSelfJoins.of(joinedBack.replace("JOIN g_node c ON c.nid = m.nid", "")))
        assertEquals(emptyList(), NodeSelfJoins.of(joinedBack.replace("c.nid = m.nid", "c.nid = m.nid + 0 AND m.nid = c.nid + 0")))
        assertEquals(emptyList(), NodeSelfJoins.of("SELECT n.nid FROM (SELECT e.src AS nid FROM g_edge e) u JOIN g_node n ON n.nid = u.nid"))
        // A CTE is named, not written in brackets beside its alias, and a recursive one Oracle does not merge.
        assertEquals(emptyList(), NodeSelfJoins.of("WITH w (nid) AS (SELECT nid FROM g_node) SELECT n.nid FROM w JOIN g_node n ON n.nid = w.nid"))
    }
}

/** Finds, in a statement compiled for Oracle, each join of the node table to an inline view that reads it, on a bare `nid`. */
internal object NodeSelfJoins {

    /** `JOIN <graph>_node <a> ON <a>.nid = <b>.nid`, either way round, and not compared as an expression (`+ 0`). */
    private val JOIN = Regex("""JOIN (\w+_node) (\w+) ON (\w+)\.nid = (\w+)\.nid(?!\s*\+)""")

    /** Each such join in [sql] whose other side is an inline view, `( … ) <b>`, that reads the node table. */
    fun of(sql: String): List<String> = JOIN.findAll(sql).mapNotNull { match ->
        val (table, alias, left, right) = match.destructured
        val view = when (alias) {
            left -> right
            right -> left
            else -> return@mapNotNull null
        }
        match.value.takeIf { viewBodies(sql, view).any { table in it } }
    }.toList()

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
