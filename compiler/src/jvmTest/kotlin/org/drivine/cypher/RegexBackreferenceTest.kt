package org.drivine.cypher

import org.drivine.cypher.sql.OracleCypherCompiler
import org.drivine.cypher.sql.Patterns
import org.drivine.cypher.sql.PostgresCypherCompiler
import org.drivine.cypher.sql.SqliteCypherCompiler
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * #236: the SQL stores answer a backreference of `=~` as Java does in a closed set of forms, and refuse every other by name
 * ([Patterns.refusal]). What each store gives is the spec's, in `queries.yaml` ("A regular expression with a backreference"):
 * this is what each is given.
 */
class RegexBackreferenceTest {

    private val compilers = listOf(PostgresCypherCompiler("g"), OracleCypherCompiler("g"), SqliteCypherCompiler("g"))

    /** The forms answered: `\1` to `\8`, no digit after, to a simple group closed before it that takes part in every match. */
    private val allowed = listOf(
        "(A).*\\1.*", "(a)\\1", "(a)\\1\\1", "(a)(b)\\2\\1", "((a)b)\\2", "(?i)(a)\\1", "(a)\\\\\\1",
        "(a)(b)(c)(d)(e)(f)(g)(h)\\8", "()\\1", "(a)\\1?", "(a)\\1x", "([ab])\\1", "(a)[]\\]]\\1",
        // A quantifier's character in a class is none.
        "([+*?{|])\\1",
    )

    /** Each form refused, and what the message names. */
    private val refused = mapOf(
        "(a)[\\1]" to "in a character class", "(a)[\\]\\1]" to "in a character class", "(a)[^]\\1]" to "in a character class",
        "(a)(b)(c)(d)(e)(f)(g)(h)(i)\\9" to "past \\8", "(a)(b)(c)(d)(e)(f)(g)(h)(i)(j)\\10" to "past \\8",
        "(a)\\10" to "with a digit after it",
        "(a)\\2" to "hasn't closed", "\\1(a)" to "hasn't closed", "(a\\1)" to "hasn't closed",
        // A group that holds a quantifier, an alternative or a group, or is repeated: each engine captures its own way there.
        "(a|b)\\1" to "holds a quantifier", "(a+)\\1" to "holds a quantifier", "((a)b)\\1" to "holds a quantifier",
        "(a)+\\1" to "holds a quantifier", "(a){2}\\1" to "holds a quantifier", "(a)?\\1" to "holds a quantifier",
        "(a?)+b\\1" to "holds a quantifier", "(a|b)+\\1" to "holds a quantifier",
        // A group in an alternative or a repeated group may take no part in a match.
        "(a)|\\1" to "in an alternative or a repeated group", "x|(a)\\1" to "in an alternative or a repeated group",
        "((a)|b)\\2" to "in an alternative or a repeated group", "((a)b)+\\2" to "in an alternative or a repeated group",
        // Postgres reads no backreference in a lookaround, and numbers no group there.
        "(a)(?=\\1)a" to "lookaround", "(?=(a))(a)\\2" to "lookaround", "(?<=(a))(a)\\2" to "lookaround",
        // Java reads (?x)(a) ?\1 as (a)?\1: a space may stand between a group and its quantifier.
        "(?x)(a) ?\\1" to "flag x", "(?ix)(a)\\1" to "flag x", "(a)(?x:b)\\1" to "flag x",
        // Oracle reads no group of its own syntax: (?: is written ( there (#242), which numbers the groups otherwise, so a backreference beside one is refused.
        "(a)(?:b|c)\\1" to "own syntax", "(?:(a))\\1" to "own syntax", "(?<x>a)\\1" to "own syntax", "(?i-x)(a)\\1" to "own syntax",
        "((?i)a)\\1" to "own syntax", "(?:(a)|b)\\1" to "own syntax", "(?:(a)b)+\\1" to "own syntax",
        // Java reads acc as [[a](b)](c)\1, where Postgres reads the class as [[a], and (b) as the first group.
        "[[a](b)](c)\\1" to "class within a class",
    )

    @Test
    fun `the forms answered are read as such, and each other form is refused by what it is`() {
        allowed.forEach { assertNull(Patterns.refusal(it), it) }
        refused.forEach { (pattern, why) -> assertTrue(Patterns.refusal(pattern).orEmpty().contains(why), "$pattern: ${Patterns.refusal(pattern)}") }
        // Escaped, quoted and \c's character are no backreference.
        listOf("(a)\\\\1", "\\Q\\1\\E", "\\Q\\1", "\\c\\1", "a").forEach { assertFalse(Patterns.holdsBackreference(it), it) }
    }

    @Test
    fun `every store refuses a form it can't answer, written or bound, and answers one it can`() {
        compilers.forEach { compiler ->
            refused.keys.forEach { pattern ->
                assertThrows<CompilerException>("${compiler::class.simpleName} $pattern") { compiler.compile("RETURN 'a' =~ \$p", mapOf("p" to pattern)) }
            }
            assertThrows<CompilerException> { compiler.compile("RETURN 'a' =~ '(a)\\\\2'", emptyMap()) }
            allowed.forEach { pattern -> compiler.compile("RETURN 'a' =~ \$p", mapOf("p" to pattern)) }
        }
    }

    @Test
    fun `Postgres and SQLite hold the pattern in a group that captures nothing, and Oracle numbers the pattern's own one more`() {
        val parameters = mapOf("p" to "(A).*\\1.*")
        val postgres = PostgresCypherCompiler("g").compile("RETURN 'a' =~ \$p", parameters).sql
        assertTrue("\\\\A(?:\\3)\\\\Z" in postgres, postgres)
        assertTrue("^(?:" in SqliteCypherCompiler("g").compile("RETURN 'a' =~ \$p", parameters).sql)
        val oracle = OracleCypherCompiler("g").compile("RETURN 'a' =~ \$p", parameters).sql
        // Each of \1 to \8 moved twice where the pattern is held: in matches, and in matchesEmpty; the highest first, innermost.
        val moves = (1..8).map { n -> Regex(Regex.escape("\\\\$n', '\\1\\2\\\\${n + 1}')")).findAll(oracle).count() }
        assertTrue(moves.all { it == moves.first() } && moves.first() >= 4 && moves.first() % 2 == 0, "$moves:\n$oracle")
        assertTrue(oracle.indexOf("\\\\8', ") < oracle.indexOf("\\\\1', "), oracle)
    }

    @Test
    fun `Oracle numbers nothing in a pattern the statement gives that writes no backreference`() {
        listOf("RETURN 'a' =~ 'A.*'", "RETURN 'a' =~ \$p").forEach { cypher ->
            val sql = OracleCypherCompiler("g").compile(cypher, mapOf("p" to "A.*")).sql
            assertFalse("\\\\1', '\\1\\2\\\\2'" in sql, sql)
        }
    }

    @Test
    fun `a pattern worked out as the statement runs is refused then where it writes a backreference, on every store`() {
        compilers.forEach { compiler ->
            listOf("WITH '(a)' + \$n AS p RETURN 'aa' =~ p", "RETURN 'aa' =~ \$m.p", "MATCH (n) RETURN n.name =~ n.pattern").forEach { cypher ->
                val sql = compiler.compile(cypher, mapOf("n" to "\\1", "m" to mapOf("p" to "(a)\\1"))).sql
                assertTrue("worked out as the statement runs writes a backreference" in sql, "${compiler::class.simpleName} $cypher:\n$sql")
                assertTrue(Patterns.BACKREFERENCE in sql || Patterns.BACKREFERENCE.replace("'", "''") in sql, sql)
            }
            // A pattern written or bound carries no such test.
            assertFalse("worked out as the statement runs" in compiler.compile("RETURN 'a' =~ \$p", mapOf("p" to "(a)\\1")).sql)
        }
    }

    @Test
    fun `the test for a backreference finds one where no backslash escapes it`() {
        val test = Regex(Patterns.BACKREFERENCE)
        listOf("(a)\\1", "\\\\\\1", "x\\9").forEach { assertTrue(test.containsMatchIn(it), it) }
        listOf("(a)\\\\1", "\\\\\\\\1", "\\0", "a").forEach { assertFalse(test.containsMatchIn(it), it) }
    }
}
