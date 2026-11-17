package org.drivine.cypher

import org.drivine.cypher.sql.OracleCypherCompiler
import org.drivine.cypher.sql.PostgresCypherCompiler
import org.drivine.cypher.sql.SqliteCypherCompiler
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import kotlin.test.assertContains
import kotlin.test.assertEquals

/**
 * drivine-cypher-spec#345: what #341 left giving null on the SQL stores, where Neo4j 2026.10.2 refuses it as it plans the
 * statement or fails as it runs. A map beside a text or a temporal value in `+`, `*` or `/` of a temporal value other than a
 * duration, and `NOT` of a parameter given a text or of a text put together, are refused as the statement compiles
 * (refusals.yaml). A truth value read as the statement runs beside a number the statement makes or gives fails it there,
 * tested once at the side read, so a sum of sums grows by one test for each value it reads (queries.yaml's "A truth value
 * put together with a text or a list"). This runs those on SQLite in memory, and checks the SQL each store is given.
 */
class ArithmeticOfOpenKindTest {

    private val compilers = listOf(PostgresCypherCompiler("g"), OracleCypherCompiler("g"), SqliteCypherCompiler("g"))

    /** What [cypher] gives on SQLite in memory: its rows' values as JSON text, or `fails: <message>`. */
    private fun sqlite(cypher: String, parameters: Map<String, Any?> = emptyMap()): String = SqliteHost.connection().use { c ->
        runCatching {
            val compiled = SqliteCypherCompiler("g").compile(cypher, parameters)
            // SQLite numbers each name it binds by where it first comes: these statements bind one at most.
            c.prepareStatement(compiled.sql).use { s ->
                compiled.parameters.values.forEachIndexed { i, v -> s.setObject(i + 1, v) }
                s.executeQuery().use { r -> generateSequence { if (r.next()) r.getString(1) ?: "null" else null }.toList().joinToString(", ") }
            }
        }.getOrElse { e -> "fails: ${e.message}" }
    }

    private fun refused(cypher: String, parameters: Map<String, Any?> = emptyMap()): List<String> = compilers.map { compiler ->
        assertThrows<CompilerException>("${compiler::class.simpleName}: $cypher") { compiler.compile(cypher, parameters) }.message!!
    }

    private val failure = "Arithmetic of a truth value and a number fails, as in Neo4j:"

    @Test
    fun `a map beside a text or a temporal value is refused on either side`() {
        val forms = mapOf(
            "RETURN {a: 1} + 'a'" to "a text", "RETURN 'a' + {a: 1}" to "a text",
            "RETURN {a: 1} + date('2024-01-01')" to "a temporal value", "RETURN date('2024-01-01') + {a: 1}" to "a temporal value",
            "RETURN {a: 1} + duration('P1D')" to "a temporal value", "RETURN duration('P1D') + {a: 1}" to "a temporal value",
            "UNWIND [{a: 1}] AS m RETURN m + 'a'" to "a text", "UNWIND [{a: 1}] AS m RETURN m + 1" to "an integer",
        )
        forms.forEach { (cypher, not) -> refused(cypher).forEach { assertEquals("+ of a map takes a map or a list, not $not, as in Neo4j.", it, cypher) } }
        refused("RETURN \$m + 'a'", mapOf("m" to mapOf("a" to 1))).forEach { assertEquals("+ of a map takes a map or a list, not a text, as in Neo4j.", it) }
        // A truth value beside a map is refused as a truth value, as #341 refuses it.
        refused("RETURN {a: 1} + true").forEach { assertEquals("+ of a truth value takes a text or a list, not a map, as in Neo4j.", it) }
    }

    @Test
    fun `a map beside a map or a list or null is compiled as before`() {
        compilers.forEach { it.compile("RETURN {a: 1} + {b: 1}") }
        assertEquals("[{\"a\":1},1]", sqlite("RETURN {a: 1} + [1]"))
        assertEquals("null", sqlite("RETURN {a: 1} + null"))
    }

    @Test
    fun `a temporal value other than a duration multiplied or divided is refused`() {
        refused("RETURN date('2024-01-01') * 2").forEach { assertEquals("* takes numbers or a duration, not a date, as in Neo4j.", it) }
        refused("UNWIND [date('2024-01-01')] AS b RETURN b * 2").forEach { assertEquals("* takes numbers or a duration, not a date, as in Neo4j.", it) }
        refused("RETURN 2 * datetime('2024-01-01T00:00Z')").forEach { assertEquals("* takes numbers or a duration, not a datetime, as in Neo4j.", it) }
        refused("RETURN localtime('12:00') / 2").forEach { assertEquals("/ takes numbers or a duration, not a localtime, as in Neo4j.", it) }
        compilers.forEach { compiler -> listOf("RETURN duration('P1D') * 2", "RETURN duration('P1D') / 2").forEach { compiler.compile(it) } }
    }

    @Test
    fun `NOT of a parameter given a text or of a text put together is refused`() {
        refused("RETURN NOT \$s", mapOf("s" to "a")).forEach { assertEquals("NOT takes a truth value, not a text, as in Neo4j.", it) }
        refused("RETURN NOT \$s", mapOf("s" to 1)).forEach { assertEquals("NOT takes a truth value, not an integer, as in Neo4j.", it) }
        listOf("RETURN NOT true + 'a'", "RETURN NOT 'a' + 1").forEach { cypher ->
            refused(cypher).forEach { assertEquals("NOT takes a truth value, not a text, as in Neo4j.", it, cypher) }
        }
        refused("RETURN ('a' + 1) - 1").forEach { assertEquals("- takes numbers, not a text, as in Neo4j.", it) }
        assertEquals("false", sqlite("RETURN NOT \$s", mapOf("s" to true)))
        assertEquals("null", sqlite("RETURN NOT \$s", mapOf("s" to null)))
    }

    @Test
    fun `a truth value read beside a number fails the statement as it runs`() {
        val l = mapOf("l" to listOf(true))
        for ((cypher, operator) in listOf(
            "UNWIND \$l AS b RETURN b + 1" to "+", "UNWIND \$l AS b RETURN 1 + b" to "+", "UNWIND \$l AS b RETURN b - 1" to "-",
            "UNWIND \$l AS b RETURN b * 2" to "*", "UNWIND \$l AS b RETURN 2 / b" to "/",
        )) {
            assertContains(sqlite(cypher, l), "$failure $operator", message = cypher)
        }
        assertContains(sqlite("WITH collect(true) AS l RETURN l[0] - 1"), "$failure -")
        assertContains(sqlite("UNWIND [1, true] AS b RETURN b + 1"), "$failure +")
        assertContains(sqlite("RETURN \$m.b + 1", mapOf("m" to mapOf("b" to true))), "$failure +")
    }

    @Test
    fun `a value of another kind read beside a number is worked out as before`() {
        assertEquals("2, 2.5, \"a1\", [2,1], null", sqlite("UNWIND \$l AS b RETURN b + 1", mapOf("l" to listOf(1, 1.5, "a", listOf(2), null))))
        assertEquals("0, null", sqlite("UNWIND \$l AS b RETURN b - 1", mapOf("l" to listOf(1, null))))
        assertEquals("null", sqlite("UNWIND [null] AS b RETURN b + 1"))
        // Put together with a text or a list, as #341 has it, and beside a value of open kind, null as before.
        assertEquals("[\"truea\",[true,1]]", sqlite("UNWIND \$l AS b RETURN [b + 'a', b + [1]]", mapOf("l" to listOf(true))))
        assertEquals("null", sqlite("UNWIND \$l AS b UNWIND \$l AS c RETURN b + c", mapOf("l" to listOf(true))))
    }

    @Test
    fun `a key of a map read is tested beside its temporal tag test`() {
        // A side that may be a tag is tested for one first, then for a truth value, in the one CASE: see temporalRefused.
        val truth = mapOf("l" to listOf(mapOf("flag" to true)))
        for ((cypher, operator) in listOf(
            "UNWIND \$l AS m RETURN m.flag + 1" to "+", "UNWIND \$l AS m RETURN m.flag - 1" to "-",
            "UNWIND \$l AS m RETURN 2 * m.flag" to "*", "UNWIND \$l AS m RETURN m.flag / 2" to "/",
        )) {
            assertContains(SqliteCypherCompiler("g").compile(cypher, truth).sql, "\$t", message = cypher)
            assertContains(sqlite(cypher, truth), "$failure $operator", message = cypher)
        }
        assertEquals("2", sqlite("UNWIND \$l AS m RETURN m.flag + 1", mapOf("l" to listOf(mapOf("flag" to 1)))))
        assertEquals("{\"\$t\":\"duration\",\"v\":\"PT48H\"}", sqlite("UNWIND \$l AS m RETURN m.d * 2", mapOf("l" to listOf(mapOf("d" to java.time.Duration.ofDays(1))))))
    }

    @Test
    fun `each store tests a side read once beside a number and nothing else`() {
        compilers.forEach { compiler ->
            fun tests(compiler: org.drivine.cypher.sql.SqlCypherCompiler, cypher: String, parameters: Map<String, Any?> = emptyMap()) =
                compiler.compile(cypher, parameters).sql.split(failure).size - 1
            val name = compiler::class.simpleName
            assertEquals(1, tests(compiler, "MATCH (n) RETURN n.flag + 1"), name)
            assertEquals(1, tests(compiler, "MATCH (n) RETURN n.flag - 1"), name)
            // A parameter given a number is a number the statement gives; given a text or null, it is no number.
            assertEquals(1, tests(compiler, "MATCH (n) RETURN n.flag + \$n", mapOf("n" to 1)), name)
            assertEquals(1, tests(compiler, "MATCH (n) RETURN \$n * n.flag", mapOf("n" to 1.5)), name)
            listOf("x", null).forEach { assertEquals(0, tests(compiler, "MATCH (n) RETURN n.flag + \$n", mapOf("n" to it)), "$name: $it") }
            // Arithmetic of arithmetic: one test at the value read, none around each level.
            assertEquals(1, tests(compiler, "MATCH (c) RETURN (((c.a + 1) + 1) + 1) + 1"), name)
            assertEquals(1, tests(compiler, "MATCH (c) RETURN (((c.a * 2) - 1) / 3) + 1"), name)
            assertEquals(4, tests(compiler, "MATCH (c) RETURN ((c.a + 1) + (c.b + 1)) + ((c.c + 1) + (c.d + 1))"), name)
            // No number beside it, or a side known: no test.
            listOf(
                "MATCH (c) RETURN c.a + c.b + c.c + c.d", "MATCH (c) RETURN c.day + duration('P1D') + duration('P1D') + duration('P1D') + duration('P1D')",
                "MATCH (c) RETURN c.a + 'x'", "MATCH (c) RETURN c.a + [1]", "MATCH (c) RETURN c.a + {k: 1}", "MATCH (c) RETURN c.a + date('2024-01-01')", "RETURN 1 + 2", "UNWIND [1, 2] AS i RETURN i + 1", "MATCH (c) RETURN size(c.l) + 1",
            ).forEach { assertEquals(0, tests(compiler, it), "$name: $it") }
        }
    }
}
