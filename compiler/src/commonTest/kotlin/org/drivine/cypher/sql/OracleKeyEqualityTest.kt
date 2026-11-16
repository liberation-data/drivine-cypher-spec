package org.drivine.cypher.sql

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * A property compared on Oracle with a text, a truth value or a whole number a statement gives: by one read of the property
 * that only a value of that kind passes, compared with the value's key bound once, where it read the property three times
 * and parsed the parameter for each row (liberation-data/drivine-cypher-spec#287).
 */
class OracleKeyEqualityTest {

    private val compiler = OracleCypherCompiler("g", true, true, emptySet())

    private fun reads(sql: String, path: String) = Regex("JSON_VALUE\\(n1\\.props, '\\$\\.$path'").findAll(sql).count()

    @Test
    fun aPropertyComparedWithAGivenTextNumberOrTruthValueIsReadOnceAsThatKind() {
        val compiled = compiler.compile(
            "MATCH (i:Issue) WHERE i.state = \$s AND i.priority = \$p AND i.open = \$b RETURN count(*)",
            mapOf("s" to "open", "p" to 1L, "b" to true),
        )
        println(compiled.sql)
        val sql = compiled.sql
        assertEquals(mapOf("_cyk_s" to "open", "_cyk_p" to "1", "_cyk_b" to "true"), compiled.parameters.filterKeys { it.startsWith("_cyk_") })
        assertTrue("JSON_VALUE(n1.props, '\$.state' RETURNING VARCHAR2(4000) NULL ON ERROR TYPE(STRICT)) = \$_cyk_s" in sql, sql)
        assertTrue("JSON_VALUE(n1.props, '\$.priority' RETURNING NUMBER NULL ON ERROR TYPE(STRICT)) = TO_NUMBER(\$_cyk_p)" in sql, sql)
        assertTrue("JSON_VALUE(n1.props, '\$.open' RETURNING BOOLEAN NULL ON ERROR TYPE(STRICT)) = (\$_cyk_b = 'true')" in sql, sql)
        // The value once, and its kind where that read finds nothing: no JSON_QUERY of it, and no parse of the parameter.
        for (path in listOf("state", "priority", "open")) {
            assertEquals(1, reads(sql, path), "$path: $sql")
            assertEquals(1, Regex("JSON_VALUE\\(n1\\.props, '\\$\\.$path\\.type\\(\\)'\\)").findAll(sql).count(), "$path: $sql")
        }
        assertTrue("JSON(\$_cy_" !in sql && "JSON_QUERY(n1.props" !in sql, sql)
        // Each name bound is one the SQL holds: the parameters' own JSON, which no comparison reads, is bound nowhere.
        assertEquals(setOf("_cyk_s", "_cyk_p", "_cyk_b"), compiled.parameters.keys, sql)
        compiled.parameters.keys.forEach { assertTrue(Regex("\\\$$it\\b").containsMatchIn(sql), "$it: $sql") }
    }

    @Test
    fun aParameterComparedByKeyAndReadElsewhereIsBoundAsBoth() {
        for (cypher in listOf(
            "MATCH (i:Issue) WHERE i.state = \$s RETURN \$s",
            "MATCH (i:Issue) WHERE i.state = \$s RETURN i.id AS x UNION MATCH (i:Issue) RETURN \$s AS x",
        )) {
            val compiled = compiler.compile(cypher, mapOf("s" to "open"))
            assertEquals(setOf("_cy_s", "_cyk_s"), compiled.parameters.keys, compiled.sql)
            compiled.parameters.keys.forEach { assertTrue(Regex("\\\$$it\\b").containsMatchIn(compiled.sql), "$it: ${compiled.sql}") }
        }
        // A union whose every comparison reads the key binds the key alone.
        val union = compiler.compile("MATCH (i:Issue) WHERE i.state = \$s RETURN i.id AS x UNION MATCH (i:Issue) WHERE i.k = \$s RETURN i.id AS x", mapOf("s" to "open"))
        assertEquals(setOf("_cyk_s"), union.parameters.keys, union.sql)
    }

    @Test
    fun aLiteralIsComparedSoAndANegationKeepsCyphersNull() {
        val sql = compiler.compile("MATCH (i:Issue) WHERE NOT i.state = 'open' RETURN count(*)", emptyMap()).sql
        println(sql)
        // Three-valued: the COALESCE gives null where the property isn't there, so NOT keeps no node without one.
        assertTrue("NOT (COALESCE(JSON_VALUE(n1.props, '\$.state' RETURNING VARCHAR2(4000) NULL ON ERROR TYPE(STRICT)) = 'open', CASE WHEN NVL(JSON_VALUE(n1.props, '\$.state.type()'), 'null') <> 'null' THEN FALSE END)" in sql, sql)
    }

    @Test
    fun aValueWithNoKeyOrAWriteComparesAsBefore() {
        for ((given, why) in listOf<Pair<Any, String>>(2.5 to "a float", "" to "an empty text", "x".repeat(4_001) to "a text past 4,000 bytes", 9_007_199_254_740_993L to "a whole number past 2^53")) {
            val compiled = compiler.compile("MATCH (i:Issue) WHERE i.k = \$v RETURN count(*)", mapOf("v" to given))
            assertTrue("TYPE(STRICT)" !in compiled.sql && compiled.parameters.keys.none { it.startsWith("_cyk_") }, "$why: ${compiled.sql}")
        }
        val write = compiler.compile("MATCH (i:Issue) WHERE i.state = \$s SET i.seen = true", mapOf("s" to "open"))
        assertTrue("TYPE(STRICT)" !in write.sql, write.sql)
    }
}
