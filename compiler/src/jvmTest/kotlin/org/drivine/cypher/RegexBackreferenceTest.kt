package org.drivine.cypher

import org.drivine.cypher.sql.OracleCypherCompiler
import org.drivine.cypher.sql.PostgresCypherCompiler
import org.drivine.cypher.sql.SqliteCypherCompiler
import org.junit.jupiter.api.Test
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
}
