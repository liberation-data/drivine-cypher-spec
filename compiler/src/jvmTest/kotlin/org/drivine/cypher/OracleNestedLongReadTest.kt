package org.drivine.cypher

import org.drivine.cypher.oracle.OracleReads
import org.drivine.cypher.sql.OracleCypherCompiler
import org.drivine.cypher.sql.OracleLongReads
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.seconds
import kotlin.time.measureTime

/**
 * On Oracle, a long text property read as a direct member of a list or a map, or as a nested property, is routed the CLOB
 * way (drivine4j#670): each of these statements reads its stored values by the guarded route, and none by `JSON_QUERY …
 * RETURNING JSON` off it (see [OracleReads]).
 */
class OracleNestedLongReadTest {

    private fun oracle(cypher: String, parameters: Map<String, Any?> = emptyMap()) = OracleCypherCompiler("g").compile(cypher, parameters).sql

    /** [sql], with no read off the CLOB route. */
    private fun routed(sql: String) = sql.also { assertEquals(emptyList(), OracleReads.unsafe(it).map { r -> "${r.source} ${r.path}" }, it) }

    /** The read of [path] from the properties [props], through its text where it is a long text. */
    private fun clob(props: String, path: String) = "JSON(JSON_QUERY(JSON_SERIALIZE(JSON_TRANSFORM($props, KEEP '$path') RETURNING CLOB EXTENDED), '$path' RETURNING CLOB) EXTENDED)"

    @Test
    fun `a key of a stored map property is read by its whole path, the CLOB way (drivine4j#536)`() {
        val sql = routed(oracle("MATCH (n:X) RETURN n.m.t ORDER BY n.i"))
        assertTrue(sql.contains(clob("n1.props", "\$.m.t")), sql)
        assertTrue(!sql.contains("JSON_QUERY(JSON_QUERY(n1.props"), sql)
    }

    @Test
    fun `a reduce() step reads a property of its context the CLOB way (drivine4j#466)`() {
        val sql = routed(oracle("MATCH (m:CyL) RETURN reduce(t = 0, x IN [1, 2] | t + size(coalesce(m.bio, 'q')))"))
        // The context may hold a parameter written as extended JSON is: a text is read from its text, not as extended JSON.
        assertTrue(sql.contains("CASE WHEN JSON_VALUE(cy_context, '\$.m.bio.type()') = 'string' THEN JSON(JSON_QUERY(JSON_SERIALIZE(cy_context RETURNING CLOB), '\$.m.bio' RETURNING CLOB))"), sql)
    }

    @Test
    fun `a long property compared with a value that may be null is read the CLOB way (drivine4j#534)`() {
        val head = routed(oracle("MATCH (n:CyLt) RETURN head([n]).t = n.t"))
        assertTrue(head.contains(clob("n1.props", "\$.t")), head)
        val unwound = routed(oracle("UNWIND [{t: 'x'}, {t: null}] AS r MATCH (n:CyLt) WHERE r.t = n.t RETURN count(*)"))
        assertTrue(Regex("""JSON_TRANSFORM\(n\d+\.props, KEEP '\$\.t'\)""").containsMatchIn(unwound), unwound)
        assertTrue(unwound.contains("JSON(JSON_QUERY(JSON_SERIALIZE(u1.item RETURNING CLOB), '\$.t' RETURNING CLOB))"), unwound)
    }

    @Test
    fun `a map made of a long property and read by its key is read the CLOB way (drivine4j#533)`() {
        for (cypher in listOf("MATCH (n:X) RETURN head([n {.t}]).t", "MATCH (n:X) RETURN head([{t: n.t}]).t")) {
            val sql = routed(oracle(cypher))
            assertTrue(sql.contains(clob("n1.props", "\$.t")) && !sql.contains("JSON_QUERY(n1.props, '\$.t' RETURNING JSON)"), sql)
        }
    }

    @Test
    fun `a value carried past a write from an unwound parameter is read the CLOB way (drivine4j#599)`() {
        val rows = mapOf("rows" to (1..2).map { mapOf("k" to it, "t" to "y".repeat(40_000)) })
        val returned = routed(oracle("UNWIND \$rows AS r CREATE (n:L {k: r.k}) RETURN r.t", rows))
        // The carried value, and its key, each read from the row by its kind, a text through the row's text.
        assertTrue(Regex("""CASE WHEN JSON_VALUE\((r\d+)\.item, '\$\.v1\.type\(\)'\) = 'string' THEN JSON\(JSON_QUERY\(JSON_SERIALIZE\(\1\.item RETURNING CLOB\), '\$\.v1' RETURNING CLOB\)\)""").containsMatchIn(returned), returned)
        assertTrue(Regex("""CASE WHEN JSON_VALUE\(r\d+\.item, '\$\.v1\.t\.type\(\)'\) = 'string' THEN""").containsMatchIn(returned), returned)
        val pre = "UNWIND \$rows AS r WITH r.k AS i, r.t AS t "
        routed(oracle("${pre}CREATE (:CyWrite {i: i}) RETURN [i, t]", rows))
        routed(oracle("${pre}CALL (i, t) { CREATE (:CyWrite {i: i}) RETURN [i, t] AS row } RETURN row", rows))
    }

    @Test
    fun `a property of a node a WITH, a CALL or an OPTIONAL MATCH carries is read the CLOB way`() {
        // Carried, the node's properties are a column of their own (`w3.c1_props`): stored, as `n1.props` is.
        val member = routed(oracle("MATCH (p:Person) CALL { WITH p RETURN [(p)-->(m) | p.name] AS names } RETURN names"))
        assertTrue(Regex("""JSON_TRANSFORM\(w\d+\.c\d+_props, KEEP '\$\.name'\)""").containsMatchIn(member), member)
        val optional = routed(oracle("OPTIONAL MATCH (p:Person) RETURN [(p)-->() | p.name] AS l"))
        assertTrue(Regex("""JSON_TRANSFORM\(o\d+\.v\d+_props, KEEP '\$\.name'\)""").containsMatchIn(optional), optional)
        val sorted = routed(oracle("MATCH (p:Person) WITH p, count(*) AS c RETURN [p.name, c] ORDER BY p.name"))
        assertTrue(Regex("""JSON_TRANSFORM\(w\d+\.c\d+_props, KEEP '\$\.name'\)""").containsMatchIn(sorted) && !Regex("""JSON_QUERY\(w\d+\.c\d+_props, '\$\.name' RETURNING JSON\) AS s1""").containsMatchIn(sorted), sorted)
    }

    @Test
    fun `a key with a double quote in it is read the CLOB way`() {
        val sql = routed(oracle("MATCH (n:X) RETURN n.m.`a\"b` ORDER BY n.`c\"d`"))
        assertTrue(sql.contains(clob("n1.props", "\$.m.\"a\\\"b\"")) && sql.contains(clob("n1.props", "\$.\"c\\\"d\"")), sql)
    }

    @Test
    fun `the last pass reads SQL as SQL - a literal, a doubled quote, a nested CASE, a worked-out source and a list's item`() {
        // A read written in a literal is no read.
        val literal = "SELECT 'JSON_QUERY(n1.props, ''\$.a'' RETURNING JSON)' AS c1 FROM g_node n1"
        assertEquals(literal, OracleLongReads.routed(literal))
        // A key with a quote in it, doubled in the literal, keeps it.
        val quoted = routed(OracleLongReads.routed("SELECT JSON_QUERY(n1.props, '\$.\"x'' y\"' RETURNING JSON) AS c1 FROM g_node n1"))
        assertTrue(quoted.contains(clob("n1.props", "\$.\"x'' y\"")), quoted)
        // A read a CASE keeps to another kind than a text, inside a nested CASE, is left as it is; one in the text's branch isn't.
        val typed = "JSON_VALUE(n1.props, '\$.a.type()')"
        val cased = "SELECT CASE WHEN $typed = 'string' THEN CASE WHEN 1 = 1 THEN JSON_QUERY(n1.props, '\$.a' RETURNING JSON) END " +
            "ELSE CASE WHEN 1 = 1 THEN JSON_QUERY(n1.props, '\$.a' RETURNING JSON) END END AS c1 FROM g_node n1"
        val routedCase = routed(OracleLongReads.routed(cased))
        assertTrue(routedCase.contains("THEN CASE WHEN 1 = 1 THEN COALESCE(JSON_QUERY(n1.props, '\$?(@.a.type()") &&
            routedCase.contains("ELSE CASE WHEN 1 = 1 THEN JSON_QUERY(n1.props, '\$.a' RETURNING JSON) END END"), routedCase)
        // A read of a value worked out of a stored one: its own reads routed, and it read the CLOB way.
        val worked = routed(OracleLongReads.routed("SELECT JSON_QUERY(COALESCE(JSON_QUERY(n1.props, '\$.m' RETURNING JSON), JSON('{}')), '\$.t' RETURNING JSON) AS c1 FROM g_node n1"))
        assertTrue(worked.contains("JSON_EXISTS(COALESCE(COALESCE(JSON_QUERY(n1.props, '\$?(@.m.type()") && worked.contains(", '\$.t')"), worked)
        // A kind read by a path into a list's item reads the routed list, not the list's own path.
        val item = OracleLongReads.routed("SELECT JSON_VALUE(JSON_QUERY(n1.props, '\$.l' RETURNING JSON), '\$[0].type()') AS c1 FROM g_node n1")
        assertTrue(item.startsWith("SELECT JSON_VALUE(COALESCE(JSON_QUERY(n1.props, '\$?(@.l.type()") && item.endsWith(", '\$[0].type()') AS c1 FROM g_node n1"), item)
        // A kind read by a path of its own is read by the whole path from the stored value.
        val kind = OracleLongReads.routed("SELECT JSON_VALUE(JSON_QUERY(n1.props, '\$.m' RETURNING JSON), '\$.t.type()') AS c1 FROM g_node n1")
        assertEquals("SELECT JSON_VALUE(n1.props, '\$.m.t.type()') AS c1 FROM g_node n1", kind)
        // So is one by a key with a bracket in it, which is no list's item.
        val bracket = OracleLongReads.routed("SELECT JSON_VALUE(JSON_QUERY(n1.props, '\$.m' RETURNING JSON), '\$.\"a[0]\".type()') AS c1 FROM g_node n1")
        assertEquals("SELECT JSON_VALUE(n1.props, '\$.m.\"a[0]\".type()') AS c1 FROM g_node n1", bracket)
    }

    @Test
    fun `a condition keeps a read to no long text only by a term that holds wherever the condition does`() {
        val typed = "JSON_VALUE(n1.props, '\$.a.type()')"
        val short = "JSON_VALUE(n1.props, '\$.a')"
        val read = "JSON_QUERY(n1.props, '\$.a' RETURNING JSON)"
        for (sql in listOf(
            // An AND-ed term of a condition with an OR-ed one: `1 = 1` alone makes it hold.
            "SELECT CASE WHEN $short IS NOT NULL AND 1 = 1 OR 1 = 1 THEN $read END AS c1 FROM g_node n1",
            // Terms of a CASE inside the condition, not the condition's own.
            "SELECT CASE WHEN CASE WHEN 1 = 0 OR $typed = 'string' OR 1 = 0 THEN 0 ELSE 1 END = 1 THEN 1 ELSE $read END AS c1 FROM g_node n1",
            "SELECT CASE WHEN CASE WHEN 1 = 1 AND $short IS NOT NULL AND 1 = 1 THEN 0 ELSE 1 END = 1 THEN $read END AS c1 FROM g_node n1",
            // A simple CASE's values, not conditions: its branch is reached where the subject equals one.
            "SELECT CASE (1 = 0) WHEN $short IS NOT NULL THEN $read END AS c1 FROM g_node n1",
            "SELECT CASE (1 = 0) WHEN $typed = 'string' THEN 1 ELSE $read END AS c1 FROM g_node n1",
            // A simple CASE on the kind, for a value that may be a text's.
            "SELECT CASE $typed WHEN LOWER('STRING') THEN $read END AS c1 FROM g_node n1",
        )) {
            assertEquals(1, OracleReads.unsafe(sql).size, sql)
            val routed = routed(OracleLongReads.routed(sql))
            assertTrue(routed.contains("COALESCE(JSON_QUERY(n1.props, '\$?(@.a.type()") && !routed.contains(read), routed)
        }
        // The terms that do hold wherever it does keep it so.
        val kept = "SELECT CASE WHEN 1 = 1 AND $short IS NOT NULL AND CASE WHEN 1 = 0 OR 1 = 1 THEN TRUE END THEN $read END AS c1 FROM g_node n1"
        assertEquals(kept, OracleLongReads.routed(kept))
    }

    @Test
    fun `a condition on a key keeps no read of a key that differs from it only in its spaces to no long text`() {
        val other = "SELECT CASE WHEN JSON_VALUE(n1.props, '\$.\"a  b\"') IS NOT NULL OR NVL(JSON_VALUE(n1.props, '\$.\"a  b\".type()'), 'null') <> 'string' " +
            "THEN JSON_QUERY(n1.props, '\$.\"a b\"' RETURNING JSON) END AS c1 FROM g_node n1"
        assertEquals(1, OracleReads.unsafe(other).size, other)
        val sql = routed(oracle("MATCH (n:X) WHERE n.`a  b` = [n.`a b`] RETURN 1"))
        assertTrue(sql.contains(clob("n1.props", "\$.\"a b\"")) && !sql.contains("JSON_QUERY(n1.props, '\$.\"a b\"' RETURNING JSON)"), sql)
    }

    @Test
    fun `the last pass reads a large statement in one pass - many reads, and a CASE of many branches`() {
        val many = "SELECT " + (0 until 40_000).joinToString(", ") { "JSON_QUERY(n1.props, '\$.a$it' RETURNING JSON) AS c$it" } + " FROM g_node n1"
        val branches = "SELECT CASE" + (0 until 40_000).joinToString("") {
            " WHEN JSON_VALUE(n1.props, '\$.x' RETURNING NUMBER) = $it OR JSON_VALUE(n1.props, '\$.y' RETURNING NUMBER) = $it THEN JSON_QUERY(n1.props, '\$.a$it' RETURNING JSON)"
        } + " END AS c1 FROM g_node n1"
        for ((sql, reads) in listOf(many to 40_000, branches to 40_000)) {
            lateinit var routed: String
            // Each about a second at most; a pass that splices or splits again for each read takes minutes, so the bound is
            // far from either, for a slow runner.
            val took = measureTime { routed = OracleLongReads.routed(sql) }
            assertEquals(reads, Regex("JSON_EXISTS\\(n1\\.props").findAll(routed).count())
            assertTrue(took < 30.seconds, "$took")
        }
    }
}
