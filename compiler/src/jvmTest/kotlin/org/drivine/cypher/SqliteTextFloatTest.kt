package org.drivine.cypher

import org.drivine.cypher.sql.SqliteCypherCompiler
import org.junit.jupiter.api.Test
import java.util.regex.Pattern
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * On SQLite, `toFloat()` of a text reads it as Neo4j does or gives null (drivine4j#693): `CAST` skips ASCII's spaces alone,
 * and read a text with a Unicode space before its number as 0.0. Each pattern names its spaces, as `\s` is each host's
 * `REGEXP`'s: drivine4j's matches Unicode's spaces with it. What each spelling gives on SQLite is the spec's.
 */
class SqliteTextFloatTest {

    private fun sql(cypher: String, parameters: Map<String, Any?> = emptyMap()) = SqliteCypherCompiler("g").compile(cypher, parameters).sql

    /** The patterns the statement's text is matched with, in turn. */
    private fun patterns(sql: String) = Regex("""REGEXP '([^']*+(?:''[^']*+)*+)'""").findAll(sql).map { it.groupValues[1].replace("''", "'") }.toList()

    /** Which of [patterns] [text] matches first, as drivine4j's `REGEXP` reads them, with Unicode's classes, and as plain Java does. */
    private fun first(patterns: List<String>, text: String): Int {
        val unicode = patterns.indexOfFirst { Pattern.compile(it, Pattern.UNICODE_CHARACTER_CLASS).matcher(text).find() }
        val plain = patterns.indexOfFirst { Pattern.compile(it).matcher(text).find() }
        assertEquals(unicode, plain, "each host's REGEXP reads ${text.codes()} alike")
        return unicode
    }

    private fun String.codes() = map { "U+%04X".format(it.code) }.toString()

    @Test
    fun `a decimal is read by CAST only with ASCII's spaces around it`() {
        val sql = sql("MATCH (n:A) RETURN toFloat(n.s)")
        val patterns = patterns(sql)
        assertEquals(5, patterns.size, sql)
        for (decimal in listOf("1.5", " 1.5 ", "\t1.5\n", "\u000B1.5\u000C", "\r3\r", "5.", "+.5", "-.5e1", "1e400", "9223372036854775808")) {
            assertEquals(0, first(patterns, decimal), decimal.codes())
        }
        assertTrue(sql.contains("WHEN 'text' THEN CASE WHEN ((n1.props -> '\$.\"s\"') ->> '\$') REGEXP '^[\\t\\n\\x0B\\f\\r ]*[+-]?"), sql)
    }

    @Test
    fun `a name is NaN or an infinity with what Java trims around it, and no Unicode space`() {
        val patterns = patterns(sql("MATCH (n:A) RETURN toFloat(n.s)"))
        assertEquals(1, first(patterns, " NaN "))
        assertEquals(1, first(patterns, "\u0001-NaN\u001F"))
        assertEquals(2, first(patterns, "\tInfinity\n"))
        assertEquals(3, first(patterns, "\u001C-Infinity\u000E"))
        for (none in listOf("\u00A0NaN", "\u3000Infinity", "NaN\u00A0", "\u3000-Infinity", "nan", "inf", "- Infinity")) assertEquals(-1, first(patterns, none), none.codes())
    }

    @Test
    fun `a number Cypher writes is read with Cypher's spaces around it, and other texts are null`() {
        val patterns = patterns(sql("MATCH (n:A) RETURN toFloat(n.s)"))
        for (cypher in listOf(
            "\u00A01.5", "1.5\u00A0", "\u20031.5", "\u30001.5", "\u00851.5", "\u20281.5", "\u16801.5", "\u20001.5", "\u200A1.5", "\u20291.5",
            "\u202F1.5", "\u205F1.5", "\u001C1.5", "\u00A01E3", "\u00A01e-3", "\u00A01e+3", "\u00A01e400", "\u00A0-1e400", "\u00A0-0",
            "\u00A0.5", "\u00A00", "\u00A0-.5e1", "\u00A0123456789012345678", "\u00A0-8999999999999999999", "\u00A00.5", "\u00A012.5e00003",
        )) assertEquals(4, first(patterns, cypher), cypher.codes())
        for (none in listOf(
            "\u00A0+1.5", "\u00A05.", "\u00A0", "\uFEFF1.5", "\u200B1.5", "\u180E1.5", "\u007F1.5", "\u00801.5", "\u009F1.5", "\u00A001",
            "\u00A01.5\u000E", "\u0001\u00A01.5", "\u00A09223372036854775808", "\u00A09000000000000000000", "\u00A0-9223372036854775808",
            "\u00A001.5", "\u00A01.", "\u00A01e", "\u00A0.", "\u00A0-", "\u00A01.5.5",
            "-\u00A01.5", "\u00A01_000", "\u00A00x10", "\u00A01d", "\u00A0NaN", "1+1", "1.5 F", "\u00011.5",
        )) assertEquals(-1, first(patterns, none), none.codes())
    }

    @Test
    fun `a number Cypher writes is read by CAST once the spaces before it are taken off`() {
        val sql = sql("MATCH (n:A) RETURN toFloat(n.s)")
        val spaces = "char(9, 10, 11, 12, 13, 28, 29, 30, 31, 32, 133, 160, 5760, 8192, 8193, 8194, 8195, 8196, 8197, 8198, 8199, 8200, 8201, 8202, " +
            "8232, 8233, 8239, 8287, 12288)"
        assertTrue(sql.contains("THEN json_quote(CAST(ltrim(((n1.props -> '\$.\"s\"') ->> '\$'), $spaces) AS REAL)) END END"), sql)
    }

    @Test
    fun `toInteger reads a number Cypher writes alike, a whole number as itself and a float cut`() {
        val sql = sql("MATCH (n:A) RETURN toInteger(n.s)")
        val patterns = patterns(sql)
        assertEquals(3, patterns.size, sql)
        assertEquals(2, first(patterns, " 1.5"))
        assertEquals(-1, first(patterns, " +1.5"))
        val t = "((n1.props -> '\$.\"s\"') ->> '\$')"
        assertTrue(sql.contains("THEN json_quote(CASE WHEN $t GLOB '*[.eE]*' THEN CAST(CAST(ltrim($t, char(9, "), sql)
        assertTrue(sql.contains(" AS REAL) AS INTEGER) ELSE CAST(ltrim($t, char(9, "), sql)
    }

    @Test
    fun `a line break that ends a text is no space where a pattern takes none, as each host reads the text's end`() {
        // Java's `$`, and Python's as drivine-python writes it, matches before a line break that ends the text, and
        // JavaScript's does not: each pattern ends at `\z`, the text's end, as `=~`'s does.
        val float = patterns(sql("MATCH (n:A) RETURN toFloat(n.s)"))
        for (none in listOf("5.\u0085", "+1.5 ", "NaN ", "Infinity\u0085", "-Infinity ")) {
            assertEquals(-1, first(float, none), none.codes())
        }
        val integer = patterns(sql("MATCH (n:A) RETURN toInteger(n.s)"))
        for (none in listOf("+5 ", "5.\u0085", "+1.5 ")) assertEquals(-1, first(integer, none), none.codes())
        assertEquals(2, first(integer, "5 "))
        assertTrue((float + integer).all { it.endsWith("\\z") }, (float + integer).toString())
    }

    @Test
    fun `toInteger reads a whole number with ASCII's spaces around it as itself`() {
        val sql = sql("MATCH (n:A) RETURN toInteger(n.s)")
        val integer = patterns(sql)
        for (whole in listOf("9007199254740993", " 9007199254740993\n", "\t-5\r", "\u000B+5\u000C")) assertEquals(0, first(integer, whole), whole.codes())
        assertTrue(sql.contains("THEN json_quote(CAST(((n1.props -> '\$.\"s\"') ->> '\$') AS INTEGER))"), sql)
    }

    @Test
    fun `a name is NaN or an infinity with U+0000 around it, as Java trims it`() {
        val patterns = patterns(sql("MATCH (n:A) RETURN toFloat(n.s)"))
        assertEquals(1, first(patterns, "\u0000NaN"))
        assertEquals(2, first(patterns, "Infinity\u0000"))
        assertEquals(3, first(patterns, "\u0000-Infinity\u0000"))
    }

    @Test
    fun `a statement given no NaN or infinity names none`() {
        val patterns = patterns(sql("RETURN toFloat(\$s)", mapOf("s" to "1.5")))
        assertEquals(2, patterns.size)
        assertEquals(1, first(patterns, "\u00A01.5"))
    }

    @Test
    fun `toFloat of toFloat compiles to a statement that grows with each, not exponentially`() {
        fun nested(times: Int) = "MATCH (n:A) RETURN " + "toFloat(".repeat(times) + "n.s" + ")".repeat(times)
        val (two, four) = listOf(2, 4).map { sql(nested(it)).length }
        assertTrue(four < 3 * two, "$two, then $four characters")
    }
}
