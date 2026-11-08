package org.drivine.cypher

import org.drivine.cypher.sql.OracleCypherCompiler
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * On Oracle, `toFloat()` reads a text as Neo4j does, as Java's `Double.parseDouble` reads it and else as a number Cypher
 * writes, and not as `TO_BINARY_DOUBLE` alone reads it, which takes `'nan'`, `'inf'` and `'INFINITY'` too (drivine4j#551).
 * What each spelling gives is the spec's, in `queries.yaml`.
 */
class OracleTextFloatTest {

    private fun sql(cypher: String, parameters: Map<String, Any?> = emptyMap()) = OracleCypherCompiler("g").compile(cypher, parameters).sql

    @Test
    fun `a text is read by Java's names and forms, then Cypher's, and a plain decimal without the subquery`() {
        val sql = sql("RETURN toFloat(\$s)", mapOf("s" to "nan"))
        // A plain decimal first, as TO_BINARY_DOUBLE reads it.
        assertTrue(sql.contains("CASE WHEN REGEXP_LIKE(JSON_VALUE(JSON(\$_cy_s), '\$'), '^[+-]?([0-9]+\\.?[0-9]*|\\.[0-9]+)([eE][+-]?[0-9]+)?\\z', 'c') THEN TO_BINARY_DOUBLE(JSON_VALUE(JSON(\$_cy_s), '\$'))"), sql)
        // NaN and the infinities by Java's names alone, matched as cased.
        assertTrue(sql.contains("'^[+-]?NaN\$', 'c') THEN BINARY_DOUBLE_NAN"), sql)
        assertTrue(sql.contains("'^\\+?Infinity\$', 'c') THEN BINARY_DOUBLE_INFINITY"), sql)
        assertTrue(sql.contains(" = '-Infinity' THEN -BINARY_DOUBLE_INFINITY"), sql)
        // Java's spaces are U+0000 to the space; Cypher's take Unicode's in too.
        assertTrue(sql.contains("UNISTR('\\0000\\0001"), sql)
        assertTrue(sql.contains("\\0020\\0085\\00a0\\1680\\2000"), sql)
        // A hexadecimal float, an octal whole number, and nowhere TO_BINARY_DOUBLE of the text read otherwise.
        assertTrue(sql.contains("[pP]([+-]?[0-9]+)[fFdD]?\$', 1, 1, 'c', 4)"), sql)
        assertTrue(sql.contains("CONNECT BY LEVEL <= LENGTH("), sql)
        assertEquals(1, Regex("DEFAULT NULL ON CONVERSION ERROR").findAll(sql).count(), sql)
    }

    @Test
    fun `a number is read as before, and a stored text by its own path`() {
        val stored = sql("MATCH (n:A) RETURN toFloat(n.s)")
        assertTrue(stored.contains("CASE WHEN JSON_VALUE(n1.props, '\$.s.type()') = 'string' THEN JSON_SCALAR(CASE WHEN REGEXP_LIKE(JSON_VALUE(n1.props, '\$.s')"), stored)
        assertTrue(stored.contains("ELSE JSON_SCALAR(TO_BINARY_DOUBLE(JSON_VALUE(n1.props, '\$.s') DEFAULT NULL ON CONVERSION ERROR)) END"), stored)
    }
}
