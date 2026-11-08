package org.drivine.cypher

import org.drivine.cypher.sql.OracleCypherCompiler
import org.drivine.cypher.sql.PostgresCypherCompiler
import org.drivine.cypher.sql.SqliteCypherCompiler
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * drivine4j#669: `keys()` of a value that is no map, node or relationship fails, as in Neo4j, on every store, where it gave
 * null; and on Oracle, whose NUMBER holds no infinity, `round()` of a stored one gives it back, as Neo4j does, where it gave
 * null. What they give on each store is the spec's, in `queries.yaml` ("keys()") and, for a stored infinity, `writes.yaml`
 * ("NaN worked out from stored infinities", liberation-data/drivine-cypher-spec#70): this is what each store is given,
 * without one.
 */
class KeysAndInfiniteRoundTest {

    /** SqlCypherCompiler.KEYS_OF_NO_MAP, which its protected companion holds. */
    private val keysOfNoMap = "keys() takes a map, a node or a relationship, as in Neo4j, not a"

    private val compilers = listOf(PostgresCypherCompiler("g"), OracleCypherCompiler("g"), SqliteCypherCompiler("g"))

    @Test
    fun `keys() of a value that is no map fails on every store, naming its kind`() {
        compilers.forEach { compiler ->
            val sql = compiler.compile("MATCH (n:A) RETURN keys(n.x)").sql
            assertTrue(keysOfNoMap in sql, "${compiler::class.simpleName}: $sql")
        }
    }

    @Test
    fun `keys() of a map, a node and null compile as before, with the failure only past them`() {
        compilers.forEach { compiler ->
            listOf("RETURN keys({a: 1})", "MATCH (n:A) RETURN keys(n)", "MATCH (n:A) OPTIONAL MATCH (n)-->(m) RETURN keys(m)").forEach { cypher ->
                val sql = compiler.compile(cypher).sql
                // The kind is tested for a map first, and for JSON's null before anything fails.
                val map = sql.indexOf("= 'object' THEN")
                val notNull = sql.indexOf("<> 'null' THEN")
                assertTrue(map in 0 until notNull && notNull < sql.indexOf(keysOfNoMap), "${compiler::class.simpleName}, $cypher: $sql")
            }
        }
    }

    @Test
    fun `round() on Oracle gives back an infinity, and rounds any other value`() {
        val oracle = OracleCypherCompiler("g")
        listOf("round(n.b)", "round(n.b, 2)", "round(n.b, 2, 'HALF_DOWN')", "round(n.c[0], 0, 'UP')", "round(n.b, n.p)").forEach { round ->
            val sql = oracle.compile("MATCH (n:A) RETURN $round").sql
            val kept = sql.indexOf("RETURNING BINARY_DOUBLE) IS INFINITE THEN")
            assertTrue(kept >= 0, "$round: $sql")
            assertTrue(sql.indexOf(" ELSE JSON_SCALAR(TO_BINARY_DOUBLE(", kept) > kept, "$round: the rounding follows the test: $sql")
        }
    }

    @Test
    fun `round() of an infinity on Oracle still fails a precision out of range, and is null by a null precision or mode`() {
        val oracle = OracleCypherCompiler("g")
        // As Postgres and SQLite read them for an infinity: the infinity is given back only where a round of 0 by them is no null.
        mapOf(
            "round(n.b, -1)" to "round() takes a precision from 0 to 2147483647",
            "round(n.b, -1, 'UP')" to "round() takes a precision from 0 to 2147483647",
            "round(n.b, 2, null)" to "JSON('null')",
            "round(n.b, null)" to "JSON('null')",
        ).forEach { (round, read) ->
            val sql = oracle.compile("MATCH (n:A) RETURN $round").sql
            val kept = sql.indexOf("RETURNING BINARY_DOUBLE) IS INFINITE THEN CASE WHEN NOT (")
            assertTrue(kept >= 0, "$round: $sql")
            val infinity = sql.substring(kept, sql.indexOf(" ELSE JSON", kept + 1).takeIf { it > 0 } ?: sql.length)
            assertTrue(read in infinity, "$round: the infinity's branch reads $read: $sql")
        }
    }

    @Test
    fun `a round of a round on Oracle writes the value out no more often at each level`() {
        val oracle = OracleCypherCompiler("g")
        fun nested(depth: Int) = oracle.compile("MATCH (n:A) RETURN ${"round(".repeat(depth)}n.b${", 2)".repeat(depth)}").sql.length
        // Each level adds about as much as the one before it did, not twice as much.
        val growth = (2..6).map { nested(it) - nested(it - 1) }
        assertTrue(growth.last() < growth.first() * 2, "growth by level: $growth")
    }

    @Test
    fun `round() on Postgres and SQLite, which hold no stored infinity as NUMBER does not, compiles as before`() {
        listOf(PostgresCypherCompiler("g"), SqliteCypherCompiler("g")).forEach { compiler ->
            val sql = compiler.compile("MATCH (n:A) RETURN round(n.b, 2, 'HALF_DOWN')").sql
            assertFalse("IS INFINITE" in sql, "${compiler::class.simpleName}: $sql")
        }
    }

    @Test
    fun `round() still refuses no argument, and more than three`() {
        compilers.forEach { compiler ->
            assertEquals("round() takes one argument.", assertThrows<CompilerException> { compiler.compile("RETURN round()") }.message)
            assertEquals("round() takes one argument.", assertThrows<CompilerException> { compiler.compile("RETURN round(1.5, 1, 'UP', 2)") }.message)
        }
    }
}
