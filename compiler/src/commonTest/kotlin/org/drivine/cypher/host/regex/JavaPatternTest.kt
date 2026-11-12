package org.drivine.cypher.host.regex

import org.drivine.cypher.host.HostArgs
import org.drivine.cypher.host.HostFunction
import org.drivine.cypher.host.HostFunctions
import org.drivine.cypher.host.HostResult
import org.drivine.cypher.host.SqlType
import org.drivine.cypher.number.target
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertSame
import kotlin.test.assertTrue

/**
 * What the regular expression host functions give beyond host.yaml's steps, on every target: a replacement's groups as
 * Java's `Matcher` reads them, a group's number as drivine4j reads it, what is refused off the JVM, and the cache.
 */
class JavaPatternTest {

    private val twelve = "(a)(b)(c)(d)(e)(f)(g)(h)(i)(j)(k)(l)"

    private fun replace(text: String, pattern: String, replacement: String) = call(HostFunctions.regexpReplace, text, pattern, replacement)

    @Test
    fun `a replacement names a group by a backslash and a digit and the digits after it while the pattern has that many groups`() {
        assertEquals("<l>", replace("abcdefghijkl", twelve, "<\\12>"))
        assertEquals("<l3>", replace("abcdefghijkl", twelve, "<\\123>"))
        assertEquals("<a3>", replace("abc", "(a)bc", "<\\13>"))
        // A dollar and a backslash are themselves, and a backslash before a backslash and a digit is itself.
        assertEquals("\$1\\x", replace("a", "(a)", "\$1\\x"))
        assertEquals("\\a", replace("a", "(a)", "\\\\1"))
        // A group that takes no part is nothing.
        assertEquals("<>b", replace("b", "(a)?b", "<\\1>b"))
        // No match: the replacement is never read, as Java never reads it.
        assertEquals("xyz", replace("xyz", "(a)", "\\5"))
        assertFailsWith<IndexOutOfBoundsException> { replace("a", "(a)", "\\5") }
        // Each match after the last, and an empty one at the end after one that ends there.
        assertEquals("<>u<><>", replace("ux", "x*", "<>"))
    }

    @Test
    fun `a group past the pattern's or of no match is null and a negative one fails as Java's does`() {
        assertEquals("b", call(HostFunctions.regexGroup, "ab", "(a)(b)", 2L))
        assertEquals(null, call(HostFunctions.regexGroup, "ab", "(a)(b)", 3L))
        assertEquals("ab", call(HostFunctions.regexGroup, "ab", "(a)(b)", null), "SQLite reads a null group as 0")
        assertEquals(null, call(HostFunctions.regexGroup, "xy", "(a)(b)", -1L), "no match decides first")
        assertFailsWith<IndexOutOfBoundsException> { call(HostFunctions.regexGroup, "ab", "(a)(b)", -1L) }
    }

    @Test
    fun `a pattern Java refuses fails and what this platform can't match as Java does is refused by name`() {
        assertFailsWith<IllegalArgumentException> { call(HostFunctions.regexp, "(", "a") }
        assertFailsWith<IllegalArgumentException> { call(HostFunctions.regexp, "\\p{Nope}", "a") }
        for (construct in listOf("\\G" to "\\Ga", "\\X" to "\\X", "\\N{…}" to "\\N{LATIN SMALL LETTER A}", "\\b{g}" to "\\b{g}", "(?-U)" to "(?-U)a", "(?-u)" to "(?-u)a")) {
            if (target == "jvm") {
                call(HostFunctions.regexp, construct.second, "a")
            } else {
                val refused = assertFailsWith<UnsupportedPatternException> { call(HostFunctions.regexp, construct.second, "a") }
                assertEquals(construct.first, refused.construct)
                assertEquals("${construct.first} is not supported on this platform; rewrite the pattern without it.", refused.message)
            }
        }
    }

    @Test
    fun `the patterns compiled are kept the most used last`() {
        val first = JavaPatterns.of("^kept\\z")
        assertSame(first, JavaPatterns.of("^kept\\z"))
        repeat(JavaPatterns.MAX + 10) { i ->
            JavaPatterns.of("^p$i\\z")
            // Used again, so it stays among the most used.
            JavaPatterns.of("^kept\\z")
        }
        assertTrue(JavaPatterns.size <= JavaPatterns.MAX)
        assertSame(first, JavaPatterns.of("^kept\\z"))
    }

    /** [function] called as SQLite calls it, with [args] (a text, a whole number or null): its result, or what it threw. */
    private fun call(function: HostFunction, vararg args: Any?): Any? {
        val result = Captured()
        function.body.call(Given(args.toList()), args.size, result)
        return result.value
    }

    private class Given(private val values: List<Any?>) : HostArgs {
        override fun type(i: Int) = when (values[i]) {
            null -> SqlType.NULL
            is Long -> SqlType.INTEGER
            else -> SqlType.TEXT
        }

        override fun long(i: Int) = (values[i] as Long?) ?: 0L

        override fun int(i: Int) = long(i).toInt()

        override fun double(i: Int) = long(i).toDouble()

        override fun text(i: Int) = values[i]?.toString()

        override fun text16(i: Int) = text(i)

        override fun blob(i: Int) = text(i)?.encodeToByteArray()
    }

    private class Captured : HostResult {
        var value: Any? = null

        override fun nullValue() { value = null }

        override fun long(value: Long) { this.value = value }

        override fun int(value: Int) { this.value = value.toLong() }

        override fun double(value: Double) { this.value = value }

        override fun text(value: String) { this.value = value }

        override fun text16(value: String) { this.value = value }

        override fun blob(value: ByteArray) { this.value = value }

        override fun error(message: String) = throw AssertionError(message)
    }
}
