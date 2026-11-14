package org.drivine.cypher

import org.drivine.cypher.sql.PostgresCypherCompiler
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * On Postgres, `toFloat()` of a text reads it as Neo4j does or gives null, and fails on none (drivine4j#692): `CAST` skips
 * ASCII's spaces alone, and Postgres's `\s` takes Unicode's, so each pattern names its spaces; and `numeric` overflows past
 * an exponent near 131072, so a larger one is held to 10000. The patterns are matched here as Java reads them, which reads
 * these as Postgres's ARE does: classes written out with `\xNN` and `\uNNNN` escapes, and `$`, which is the text's end where
 * the match is not newline-sensitive. What each spelling gives on Postgres is the spec's.
 */
class PostgresTextFloatTest {

    private fun sql(cypher: String, parameters: Map<String, Any?> = emptyMap()) = PostgresCypherCompiler("g").compile(cypher, parameters).sql

    /** The patterns the statement's text is matched with by `~`, in turn. */
    private fun patterns(sql: String) = Regex("""~ '([^']*+(?:''[^']*+)*+)'""").findAll(sql).map { it.groupValues[1].replace("''", "'") }.toList()

    /** Which of [patterns] [text] matches first. */
    private fun first(patterns: List<String>, text: String) = patterns.indexOfFirst { Regex(it).containsMatchIn(text) }

    private fun String.codes() = map { "U+%04X".format(it.code) }.toString()

    private val stored = "MATCH (n:A) RETURN toFloat(n.s)"

    @Test
    fun `each pattern names its spaces, and no escape runs into a digit after it`() {
        val patterns = patterns(sql(stored))
        // Java's three names, then a decimal and a number Cypher writes, then a decimal again where the value is no text.
        assertEquals(6, patterns.size, patterns.toString())
        for (pattern in patterns) {
            assertFalse("\\s" in pattern, pattern)
            // ARE reads `\x` with every hex digit after it: an escape followed by one would name another character.
            assertFalse(Regex("""\\x[0-9a-fA-F]{3}""").containsMatchIn(pattern), pattern)
            assertTrue(pattern.startsWith("^") && pattern.endsWith("$"), pattern)
        }
    }

    @Test
    fun `a decimal is read by CAST only with ASCII's spaces around it, and a number Cypher writes once btrim takes Cypher's off`() {
        val patterns = patterns(sql(stored))
        for (decimal in listOf("1.5", " 1.5 ", "\t1.5\n", "\u000B1.5\u000C", "\r3\r", "5.", "+.5", "-.5e1", "1e400", "1e5000000000")) {
            assertEquals(3, first(patterns, decimal), decimal.codes())
        }
        for (cypher in listOf("\u00A01.5", "1.5\u00A0", "\u20031.5", "\u30001.5", "\u00851.5", "\u20281.5", "\u16801.5", "\u001C1.5",
            "\u00A01e400", "\u00A0-0", "\u00A0.5", "\u00A0123456789012345678", "\u00A0 1.5")) {
            assertEquals(4, first(patterns, cypher), cypher.codes())
        }
        for (none in listOf("\u00A0+1.5", "\u00A05.", "\uFEFF1.5", "\u200B1.5", "\u00011.5", "-\u00A01.5", "\u00A01_000", "\u00A09223372036854775808", "1.5\n\n1")) {
            assertEquals(-1, first(patterns, none), none.codes())
        }
        val spaces = "(chr(9) || chr(10) || chr(11) || chr(12) || chr(13) || chr(28) || chr(29) || chr(30) || chr(31) || chr(32) || chr(133) || " +
            "chr(160) || chr(5760) || chr(8192) || chr(8193) || chr(8194) || chr(8195) || chr(8196) || chr(8197) || chr(8198) || chr(8199) || " +
            "chr(8200) || chr(8201) || chr(8202) || chr(8232) || chr(8233) || chr(8239) || chr(8287) || chr(12288))"
        assertTrue(sql(stored).contains("btrim((n1.props -> 's') #>> '{}', $spaces)"), sql(stored))
    }

    @Test
    fun `a name is NaN or an infinity with what Java trims around it, and no Unicode space`() {
        val patterns = patterns(sql(stored))
        assertEquals(0, first(patterns, " NaN "))
        assertEquals(0, first(patterns, "\u0001-NaN\u001F"))
        assertEquals(1, first(patterns, "\tInfinity\n"))
        assertEquals(2, first(patterns, "\u001C-Infinity\u000E"))
        for (none in listOf("\u00A0NaN", "\u3000Infinity", "NaN\u00A0", "\u3000-Infinity", "nan", "- Infinity")) assertEquals(-1, first(patterns, none), none.codes())
    }

    @Test
    fun `toFloat holds an exponent of 10000 or more to 10000, as group 1 and then the bound, and toInteger holds none`() {
        val sql = sql(stored)
        val bound = Regex("""#>> '\{}'\)?, '([^']*)', '\\1([0-9]+)'\)""").find(sql)
        assertTrue(bound != null, sql)
        // Postgres's replacement reads `\1` as group 1, a single digit, and the 0s after it as text: Java's `$1` reads alike here.
        fun held(text: String) = Regex(bound.groupValues[1]).replaceFirst(text, "\$1${bound.groupValues[2]}")
        assertEquals("1e10000", held("1e5000000000"))
        assertEquals(" 1E-10000 ", held(" 1E-0012345 "))
        assertEquals("1.5e+10000", held("1.5e+10000"))
        assertEquals(2, Regex("""regexp_replace\(""").findAll(sql).count(), sql)
        // Nearer 0 than half the least double, 0; else rounded to the places a double has, in few enough digits to read.
        assertEquals(2, Regex("""WHEN abs\([^']*?\) <= 2.4703282292062327209e-324 THEN 0 ELSE round\(.*?, 400\) END""").findAll(sql).count(), sql)
        // From where Java rounds to an infinity, 1.7976931348623159e308 among them, the infinity: numeric's where the statement
        // may work one out, as this one may, so it is written as the number that stands for it and is equal to one given;
        // else 1e309, not written in 10,001 digits.
        val overflow = "1.79769313486231580794e308"
        assertEquals(2, Regex(""" >= $overflow THEN 'Infinity'::numeric WHEN [^']*? <= -$overflow THEN '-Infinity'::numeric WHEN abs\(""").findAll(sql).count(), sql)
        val given = sql("RETURN toFloat(\$s)", mapOf("s" to "1.5"))
        assertEquals(2, Regex(""" >= $overflow THEN 1e309 WHEN [^']*? <= -$overflow THEN -1e309 WHEN abs\(""").findAll(given).count(), given)
        for (kept in listOf("1e9999", "1e09999", "1e400", "-1.5e-400", "10000")) assertEquals(kept, held(kept))
        assertFalse(sql("MATCH (n:A) RETURN toInteger(n.s)").contains("regexp_replace"))
    }

    @Test
    fun `toFloat of a text the statement names no NaN in reads a number Cypher writes too`() {
        val patterns = patterns(sql("RETURN toFloat(\$s)", mapOf("s" to "\u00A01.5")))
        assertEquals(2, patterns.size, patterns.toString())
        assertEquals(1, first(patterns, "\u00A01.5"))
    }
}
