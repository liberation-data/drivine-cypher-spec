package org.drivine.cypher

import org.drivine.cypher.sql.OracleCypherCompiler
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * On Oracle, `toFloat()` of a text gives NaN or an infinity only by Java's names, as Neo4j does, matched with case on the text
 * with U+0000 to the space taken off its ends; `TO_BINARY_DOUBLE` alone read `'nan'`, `'inf'` and `'INFINITY'` too
 * (drivine4j#551). Any other text, and any value but a text, is read as before. What each spelling gives is the spec's.
 */
class OracleTextFloatTest {

    private fun sql(cypher: String, parameters: Map<String, Any?> = emptyMap()) = OracleCypherCompiler("g").compile(cypher, parameters).sql

    private val read = "TO_BINARY_DOUBLE(JSON_VALUE(JSON(\$_cy_s), '\$') DEFAULT NULL ON CONVERSION ERROR)"

    @Test
    fun `a text with an N in it is NaN or an infinity only by Java's names, matched with case`() {
        val sql = sql("RETURN toFloat(\$s)", mapOf("s" to "nan"))
        val names = Regex("""'(\^\(\[\+-]\?NaN[^']*)', 'c'\) THEN """).find(sql)?.groupValues?.get(1)
        assertEquals("^([+-]?NaN|[+]?Infinity|-Infinity)\$", names, sql)
        // Oracle reads the pattern as Java's regex does: Java's names alone, each sign as Java takes it.
        val pattern = Regex(names!!)
        for (name in listOf("NaN", "+NaN", "-NaN", "Infinity", "+Infinity", "-Infinity")) assertTrue(pattern.matches(name), name)
        for (other in listOf("nan", "NAN", "inf", "Inf", "INF", "infinity", "INFINITY", "+inf", "-inf", "+-NaN", "Infinityx", "NaNd", "- Infinity")) {
            assertFalse(pattern.matches(other), other)
        }
    }

    @Test
    fun `the name is matched on the text with U+0000 to the space taken off its ends`() {
        val sql = sql("RETURN toFloat(\$s)", mapOf("s" to "nan"))
        val spaces = "UNISTR('${(0..0x20).joinToString("") { "\\" + it.toString(16).padStart(4, '0') }}')"
        assertTrue(sql.contains("REGEXP_LIKE(LTRIM(RTRIM(JSON_VALUE(JSON(\$_cy_s), '\$'), $spaces), $spaces), '^("), sql)
        assertFalse(sql.contains("\\0021") || sql.contains("\\00a0"), sql)
    }

    @Test
    fun `a name is read from the text with U+0000 to the space taken off its ends, which TO_BINARY_DOUBLE alone gives null for`() {
        // TO_BINARY_DOUBLE takes only ASCII's spaces off: '\u0001NaN', which Neo4j reads as NaN, it gave null.
        val sql = sql("RETURN toFloat(\$s)", mapOf("s" to "\u0001NaN"))
        val spaces = "UNISTR('${(0..0x20).joinToString("") { "\\" + it.toString(16).padStart(4, '0') }}')"
        val trimmed = "LTRIM(RTRIM(JSON_VALUE(JSON(\$_cy_s), '\$'), $spaces), $spaces)"
        assertTrue(sql.contains("'c') THEN TO_BINARY_DOUBLE($trimmed) END ELSE $read END)"), sql)
    }

    @Test
    fun `toFloat of toFloat compiles to a statement that grows with each, not exponentially`() {
        fun nested(times: Int) = "MATCH (n:A) RETURN " + "toFloat(".repeat(times) + "n.s" + ")".repeat(times)
        val (two, four) = listOf(2, 4).map { OracleCypherCompiler("g").compile(nested(it)).sql.length }
        assertTrue(four < 3 * two, "$two, then $four characters")
    }

    @Test
    fun `a text with no N in it, and any value but a text, is read as before`() {
        val sql = sql("RETURN toFloat(\$s)", mapOf("s" to "1.5"))
        assertTrue(sql.contains("JSON_SCALAR(CASE WHEN JSON_VALUE(JSON(\$_cy_s), '\$.type()') = 'string' AND INSTR(UPPER(JSON_VALUE(JSON(\$_cy_s), '\$')), 'N') > 0 THEN "), sql)
        assertTrue(sql.contains(" END ELSE $read END)"), sql)
        // A stored text is read by its own path, as before.
        val stored = sql("MATCH (n:A) RETURN toFloat(n.s)")
        assertTrue(stored.contains("JSON_SCALAR(CASE WHEN JSON_VALUE(n1.props, '\$.s.type()') = 'string' AND INSTR(UPPER(JSON_VALUE(n1.props, '\$.s')), 'N') > 0 THEN "), stored)
        assertTrue(stored.contains(" END ELSE TO_BINARY_DOUBLE(JSON_VALUE(n1.props, '\$.s') DEFAULT NULL ON CONVERSION ERROR) END)"), stored)
    }
}
