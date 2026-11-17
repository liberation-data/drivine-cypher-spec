package org.drivine.cypher

import org.drivine.cypher.sql.OracleCypherCompiler
import org.drivine.cypher.sql.PostgresCypherCompiler
import org.drivine.cypher.sql.SqliteCypherCompiler
import org.junit.jupiter.api.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertFalse

/**
 * liberation-data/drivine-cypher-spec#297: `toString()` of a value whose kind the statement leaves open, which turns out a list
 * as the statement runs, gives Neo4j's text of a list, item by item, where it gave the store's JSON text (`[1,2]`). A map so
 * read, on its own or inside such a list, fails by name, as its keys aren't known here (#301). A number, a text, a truth
 * value or null so read gives the text it gave. A store's property keeps the store's JSON text of a list (SqlCypherCompiler's mayHoldList).
 */
class OpenListTextTest {

    /** What [cypher] gives on SQLite in memory: its first row's value, read from its JSON, or `fails: <message>`. */
    private fun sqlite(cypher: String, parameters: Map<String, Any?> = emptyMap()): String = SqliteHost.connection().use { c ->
        runCatching {
            val compiled = SqliteCypherCompiler("g").compile(cypher, parameters)
            // SQLite numbers each name it binds by where it first comes: these statements bind one at most.
            c.prepareStatement(compiled.sql).use { s ->
                compiled.parameters.values.forEachIndexed { i, v -> s.setObject(i + 1, v) }
                s.executeQuery().use { r -> r.next(); r.getString(1)?.let { kotlinx.serialization.json.Json.parseToJsonElement(it) }?.let { (it as? kotlinx.serialization.json.JsonPrimitive)?.content ?: it.toString() } ?: "null" }
            }
        }.getOrElse { e -> "fails: ${e.message}" }
    }

    @Test
    fun `a list of open kind is written as Neo4j writes a list`() {
        assertEquals("[1, a]", sqlite("WITH [[1, 'a'], 2] AS l RETURN toString(l[0])"))
        assertEquals("[1, a]", sqlite("RETURN toString(head([[1, 'a'], 2]))"))
        assertEquals("[1, a]", sqlite("RETURN toString(CASE WHEN true THEN [1, 'a'] ELSE \$x END)", mapOf("x" to 1)))
        assertEquals("[1, 2]", sqlite("WITH \$m AS m RETURN toString(m.l)", mapOf("m" to mapOf("l" to listOf(1, 2)))))
        assertEquals("[1, a]", sqlite("RETURN toString(coalesce([1, 'a'], [2]))"))
        assertEquals("[1, 2]", sqlite("RETURN toString(coalesce(null, [1, 2]))"))
        assertEquals("[1, [2, x], null]", sqlite("WITH \$m AS m RETURN toString(m.l)", mapOf("m" to mapOf("l" to listOf(1, listOf(2, "x"), null)))))
        assertEquals("[]", sqlite("WITH \$m AS m RETURN toString(m.l)", mapOf("m" to mapOf("l" to emptyList<Any>()))))
        assertEquals("[1, a]", sqlite("RETURN toString([[1, 'a'], 2][0])"))
        assertEquals("[1, x]", sqlite("RETURN toString({a: [1, 'x']}.a)"))
        assertEquals("[1, 2]", sqlite("RETURN toString(\$m.a.l)", mapOf("m" to mapOf("a" to mapOf("l" to listOf(1, 2))))))
        assertEquals("[1, 2]", sqlite("WITH \$m AS m RETURN toString(m['l'])", mapOf("m" to mapOf("l" to listOf(1, 2)))))
    }

    @Test
    fun `a temporal value of open kind gives its text, not a failure as a map`() {
        assertEquals("2024-01-02", sqlite("WITH \$m AS m RETURN toString(m.d)", mapOf("m" to mapOf("d" to java.time.LocalDate.of(2024, 1, 2)))))
    }

    @Test
    fun `a value known to be no list, or an item of a list of none, is compiled with no read of a list`() {
        val parameters = mapOf("m" to mapOf("n" to 1), "l" to listOf(1, 2))
        listOf(
            "RETURN toString([1, 2][0])", "RETURN toString(head([1, 2]))", "RETURN toString(last(['a', null]))",
            "WITH [1, 2] AS l RETURN toString(l[0])", "RETURN toString(coalesce(\$m.n, 1))", "RETURN toString(\$l[0])",
        ).forEach { cypher ->
            val sql = SqliteCypherCompiler("g").compile(cypher, parameters).sql
            assertFalse("#301" in sql, "$cypher: $sql")
            assertEquals(if ("last" in cypher) "null" else "1", sqlite(cypher, parameters), cypher)
        }
    }

    @Test
    fun `a map of open kind, or one inside such a list, fails by name`() {
        assertContains(
            sqlite("WITH \$m AS m RETURN toString(m.a)", mapOf("m" to mapOf("a" to mapOf("b" to 1)))),
            "toString() of a map read as the statement runs is not compiled to SQL yet (liberation-data/drivine-cypher-spec#301): it is a map",
        )
        assertContains(
            sqlite("WITH \$m AS m RETURN toString(m.l)", mapOf("m" to mapOf("l" to listOf(1, mapOf("k" to 1))))),
            "toString() of a list or a map that holds a map read as the statement runs",
        )
    }

    @Test
    fun `a number, a text, a truth value or null of open kind gives the text it gave`() {
        val m = mapOf("m" to mapOf("n" to 5, "f" to 2.5, "s" to "x", "b" to true, "z" to null))
        assertEquals("5", sqlite("WITH \$m AS m RETURN toString(m.n)", m))
        assertEquals("2.5", sqlite("WITH \$m AS m RETURN toString(m.f)", m))
        assertEquals("x", sqlite("WITH \$m AS m RETURN toString(m.s)", m))
        assertEquals("true", sqlite("WITH \$m AS m RETURN toString(m.b)", m))
        assertEquals("null", sqlite("WITH \$m AS m RETURN toString(m.z)", m))
        assertEquals("null", sqlite("WITH \$m AS m RETURN toString(m.missing)", m))
    }

    @Test
    fun `a value of a known kind is compiled as before, with no read of a list`() {
        listOf(PostgresCypherCompiler("g"), OracleCypherCompiler("g"), SqliteCypherCompiler("g")).forEach { compiler ->
            listOf(
                "RETURN toString(1)", "RETURN toString('a')", "RETURN toString(\$p)", "RETURN toString(1 + 2)", "MATCH (n) RETURN toString(count(*))",
                // A store's property keeps the store's JSON text of a list, as Neo4j's text would cost every toString() of a property
                // thousands of characters of SQL; so does a function of one, or a sort or a condition by one.
                "MATCH (n) RETURN toString(n.tags)", "MATCH ()-[r]->() RETURN toString(r.tags)", "MATCH (n) RETURN toString(coalesce(n.tags, 'x'))",
                "MATCH (n) RETURN n ORDER BY toString(n.name)", "MATCH (n) WHERE toString(n.age) = '1' RETURN n",
                // A variable alone keeps no kind to tell a list by: an aggregate read by its item's name is one.
                "MATCH (n) RETURN count(*) AS c ORDER BY toString(c)",
            ).forEach { cypher ->
                val sql = compiler.compile(cypher, mapOf("p" to 3)).sql
                assertFalse("#301" in sql, "${compiler::class.simpleName}: $cypher: $sql")
            }
        }
    }
}
