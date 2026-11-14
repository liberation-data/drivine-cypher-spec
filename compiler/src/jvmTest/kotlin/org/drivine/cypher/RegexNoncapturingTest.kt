package org.drivine.cypher

import org.drivine.cypher.sql.OracleCypherCompiler
import org.drivine.cypher.sql.Patterns
import org.drivine.cypher.sql.PostgresCypherCompiler
import org.drivine.cypher.sql.SqliteCypherCompiler
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * #242: Oracle reads no group of its own syntax but a leading flag group, so it matched nothing where a pattern of `=~` wrote
 * `(?:…)`. It now reads each `(?:` as `(`, and refuses every other such group by name. What each store gives is the spec's, in
 * `queries.yaml` ("A regular expression with a group that captures nothing"): this is what Oracle is given.
 */
class RegexNoncapturingTest {

    /** The rewrite Oracle's SQL makes of each `(?:` no backslash escapes, as Java's regular expressions read it: twice over. */
    private val rewrite = Regex("""(^|[^\\])((\\\\)*)\(\?:""")

    private fun rewritten(pattern: String) = (1..2).fold(pattern) { x, _ -> rewrite.replace(x, "$1$2(") }

    @Test
    fun `Oracle answers a group that captures nothing and a leading flag group, and refuses every other group of its own syntax`() {
        listOf("(?:x)", "x(?:b)", "(?:(?:a)b)+", "(?i)x(?:b)", "a\\(?:b", "\\Q(?=\\E", "[(]?:", "(a)", "a")
            .forEach { assertNull(Patterns.oracleRefusal(it), it) }
        mapOf(
            "a(?=b)b" to "(?=", "(?<=a)b" to "(?<", "(?<n>a)" to "(?<", "a(?i)b" to "(?i", "(?i:a)" to "(?i", "(?:a)(?!b)" to "(?!",
            "[(?:]" to "character class", "[a(?:]x(?:y)" to "character class",
        ).forEach { (pattern, named) ->
            val why = assertNotNull(Patterns.oracleRefusal(pattern), pattern)
            assertTrue(named in why && "#242" in why, why)
        }
    }

    @Test
    fun `a group that captures nothing is found where no backslash escapes it, outside a class`() {
        listOf("(?:x)", "x(?:b)", "\\\\(?:b)", "(?i)(?:x)").forEach { assertTrue(Patterns.holdsNoncapturing(it), it) }
        listOf("\\(?:b", "[(?:]", "(?i)x", "\\Q(?:\\E", "x").forEach { assertFalse(Patterns.holdsNoncapturing(it), it) }
    }

    @Test
    fun `the rewrite writes each group that captures nothing as one that captures, and leaves an escaped one`() {
        mapOf(
            "(?:x)" to "(x)", "x(?:b)" to "x(b)", "(?:(?:(?:a)))" to "(((a)))", "(?:a)(?:b)" to "(a)(b)",
            "a\\(?:b" to "a\\(?:b", "a\\\\(?:b)" to "a\\\\(b)", "a\\\\\\(?:b" to "a\\\\\\(?:b",
        ).forEach { (pattern, expected) -> assertEquals(expected, rewritten(pattern), pattern) }
        // The same match, where the pattern writes no backreference.
        listOf("x" to "(?:x)", "abab" to "(?:(?:a)b)+", "ac" to "a(?:b|c)", "x?:b" to "x(?:b)").forEach { (text, pattern) ->
            assertEquals(Regex(pattern).matches(text), Regex(rewritten(pattern)).matches(text), pattern)
        }
    }

    @Test
    fun `Oracle rewrites a pattern the statement gives where it writes one, and is given the rewrite as its SQL makes it`() {
        listOf("RETURN 'x' =~ '(?:x)'", "RETURN 'x' =~ \$p").forEach { cypher ->
            val sql = OracleCypherCompiler("g").compile(cypher, mapOf("p" to "(?:x)")).sql
            assertTrue("""'(^|[^\\])((\\\\)*)\(\?:', '\1\2(')""" in sql, sql)
        }
        val plain = OracleCypherCompiler("g").compile("RETURN 'x' =~ \$p", mapOf("p" to "x.*")).sql
        assertFalse("""\(\?:'""" in plain, plain)
    }

    @Test
    fun `Oracle refuses as it compiles a pattern given that it can't answer, and the other stores answer it`() {
        listOf("a(?=b)b", "[(?:]").forEach { pattern ->
            assertThrows<CompilerException>(pattern) { OracleCypherCompiler("g").compile("RETURN 'ab' =~ \$p", mapOf("p" to pattern)) }
            PostgresCypherCompiler("g").compile("RETURN 'ab' =~ \$p", mapOf("p" to pattern))
            SqliteCypherCompiler("g").compile("RETURN 'ab' =~ \$p", mapOf("p" to pattern))
        }
    }

    @Test
    fun `Oracle refuses as the statement runs a pattern worked out then that writes a group of its own syntax, but a leading flag group`() {
        val cypher = "WITH '(?:' + \$b AS p RETURN 'x' =~ p"
        val oracle = OracleCypherCompiler("g").compile(cypher, mapOf("b" to "x)")).sql
        assertTrue("writes a group of its own syntax" in oracle, oracle)
        listOf(PostgresCypherCompiler("g"), SqliteCypherCompiler("g")).forEach { compiler ->
            assertFalse("writes a group of its own syntax" in compiler.compile(cypher, mapOf("b" to "x)")).sql)
        }
        // The test Oracle's SQL makes, as Java reads it: a `(?` no backslash escapes, once a leading flag group is taken off.
        val test = Regex("""(^|[^\\])(\\\\)*\(\?""")
        fun refused(pattern: String) = test.containsMatchIn(pattern.replaceFirst(Regex("""^\(\?[a-z]+\)"""), ""))
        listOf("(?:x)", "a(?=b)", "(?i)(?:x)", "\\\\(?:x)").forEach { assertTrue(refused(it), it) }
        listOf("(?i)x", "\\(?:x", "x", "(x)").forEach { assertFalse(refused(it), it) }
        assertTrue("""'(^|[^\\])(\\\\)*\(\?'""" in oracle, oracle)
    }
}
