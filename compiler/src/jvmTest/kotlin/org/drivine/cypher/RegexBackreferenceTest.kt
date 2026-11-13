package org.drivine.cypher

import org.drivine.cypher.sql.OracleCypherCompiler
import org.drivine.cypher.sql.PostgresCypherCompiler
import org.drivine.cypher.sql.SqliteCypherCompiler
import org.drivine.cypher.sql.Patterns
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * #236: the group a store holds a pattern to the whole text in leaves the pattern's own groups their numbers, as a
 * backreference reads them. What each store gives is the spec's, in `queries.yaml` ("A regular expression with a
 * backreference"): this is what each is given.
 */
class RegexBackreferenceTest {

    private val cypher = "RETURN \$t =~ \$p"
    private val parameters = mapOf("t" to "Ada founded Acme", "p" to "(A).*\\1.*")

    @Test
    fun `Postgres and SQLite hold the pattern in a group that captures nothing`() {
        val postgres = PostgresCypherCompiler("g").compile(cypher, parameters).sql
        assertTrue("\\\\A(?:\\3)\\\\Z" in postgres, postgres)
        assertFalse("\\\\A(\\3)" in postgres, postgres)
        val sqlite = SqliteCypherCompiler("g").compile(cypher, parameters).sql
        assertTrue("^(?:" in sqlite, sqlite)
    }

    @Test
    fun `Oracle numbers each backreference one more, from the highest down and twice over, where it matches and over the empty text`() {
        val oracle = OracleCypherCompiler("g").compile(cypher, parameters).sql
        // Each of \1 to \8 moved twice wherever the pattern is held: in matches, and in matchesEmpty.
        val moves = (1..8).map { n -> Regex(Regex.escape("\\\\$n', '\\1\\2\\\\${n + 1}')")).findAll(oracle).count() }
        assertTrue(moves.all { it == moves.first() } && moves.first() >= 4 && moves.first() % 2 == 0, "$moves:\n$oracle")
        // The highest is moved first, so a lower one moved up is not moved again: its call is innermost, so its pattern is
        // written first.
        assertTrue(oracle.indexOf("\\\\8', ") < oracle.indexOf("\\\\1', "), "the highest is not moved innermost:\n$oracle")
    }

    @Test
    fun `a backreference is a backslash no backslash escapes, then a digit, and one in a class is told apart`() {
        fun read(pattern: String) = Patterns.backreferences(pattern).map { it.number to it.inClass }
        assertEquals(listOf(1 to false), read("(a)\\1"))
        assertEquals(listOf(1 to false), read("(a)\\\\\\1"), "three backslashes: an escaped one, then a backreference")
        assertEquals(emptyList(), read("(a)\\\\\\\\1"), "four: two escaped backslashes, then a digit")
        assertEquals(listOf(1 to true), read("(a)[\\1]"))
        assertEquals(listOf(1 to false), read("(a)[]\\]]\\1"), "a class's first ] is in it, and an escaped ] too")
        assertEquals(listOf(1 to true), read("(a)[\\]\\1]"), "an escaped ] leaves the class open")
        assertEquals(listOf(1 to false), read("(a)[^]]\\1"))
        assertEquals(emptyList(), read("(a)\\Q\\1\\E"), "quoted")
        assertEquals(listOf(1 to false, 1 to false), read("(a)\\1\\1"))
        assertEquals(listOf(1 to true), read("(a)[a[b]\\1]"), "a class in a class closes only itself")
        assertEquals(listOf(1 to false), read("(a)[a[b]]\\1"))
        assertEquals(listOf(1 to true), read("(a)[a&&[\\1]]"))
        assertEquals(listOf(1 to true), read("(a)[\\Q]\\E\\1]"), "a ] quoted in a class leaves it open")
        assertEquals(listOf(1 to false), read("(a)\\c[\\1"), "a control character's [ opens no class")
    }

    @Test
    fun `Oracle numbers nothing in a pattern the statement gives that writes no backreference`() {
        listOf("RETURN \$t =~ 'A.*'", "RETURN \$t =~ \$p").forEach { cypher ->
            val oracle = OracleCypherCompiler("g").compile(cypher, mapOf("t" to "Ada", "p" to "A.*")).sql
            assertFalse("\\\\1', " in oracle || "Oracle names groups" in oracle, "$cypher:\n$oracle")
        }
        val written = OracleCypherCompiler("g").compile("RETURN \$t =~ '(A).*\\\\1'", mapOf("t" to "Ada")).sql
        assertTrue("\\\\1', " in written, written)
    }

    @Test
    fun `a backreference in a class is refused on every store, and Oracle refuses a ninth by name, given or written`() {
        listOf(PostgresCypherCompiler("g"), OracleCypherCompiler("g"), SqliteCypherCompiler("g")).forEach { compiler ->
            val refused = assertThrows<CompilerException> { compiler.compile("RETURN 'a1' =~ \$p", mapOf("p" to "(a)[\\1]")) }
            assertTrue("character class" in refused.message.orEmpty(), refused.message)
        }
        val nine = "(a)(b)(c)(d)(e)(f)(g)(h)(i)\\9"
        assertThrows<CompilerException> { OracleCypherCompiler("g").compile("RETURN 'a9' =~ '${nine.replace("\\", "\\\\")}'", emptyMap()) }
        assertThrows<CompilerException> { OracleCypherCompiler("g").compile("RETURN 'a9' =~ \$p", mapOf("p" to nine)) }
        PostgresCypherCompiler("g").compile("RETURN 'a9' =~ \$p", mapOf("p" to nine))
        // One worked out as the statement runs is failed then.
        val oracle = OracleCypherCompiler("g").compile("WITH '(a)' + \$n AS p RETURN 'aa' =~ p", mapOf("n" to "\\9")).sql
        assertTrue("Oracle names groups" in oracle, oracle)
    }

    @Test
    fun `a backreference to a group the pattern doesn't have is asked as a pattern that matches nothing, on every store`() {
        assertEquals(1, Patterns.groups("(a)(?:b)(?=c)"))
        assertEquals(2, Patterns.groups("(a)(?<n>b)[(]\\(\\Q(\\E"))
        assertTrue(Patterns.dangling("(a)\\2") && !Patterns.dangling("(a)\\1") && !Patterns.dangling("(a)[\\]\\2]"))
        listOf(PostgresCypherCompiler("g"), OracleCypherCompiler("g"), SqliteCypherCompiler("g")).forEach { compiler ->
            val sql = compiler.compile("RETURN 'a9' =~ \$p", mapOf("p" to "(a)\\9")).sql
            assertTrue("\"a^\"" in sql || "'a^'" in sql, "${compiler::class.simpleName}:\n$sql")
        }
    }
}
