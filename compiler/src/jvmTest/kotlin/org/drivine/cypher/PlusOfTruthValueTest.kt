package org.drivine.cypher

import org.drivine.cypher.sql.OracleCypherCompiler
import org.drivine.cypher.sql.PostgresCypherCompiler
import org.drivine.cypher.sql.SqliteCypherCompiler
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertFalse

/**
 * drivine-cypher-spec#341: `+` of a truth value and a number, a truth value, a map or a temporal value, and of a map and a
 * number, gave null on the SQL stores, where Neo4j refuses it as it plans the statement. Where the statement makes, writes
 * or binds the kinds, each SQL store refuses it as it compiles (refusals.yaml); where a truth value is read as the statement
 * runs, it still gives null (a test of each side's kind in every `+` made a sum of sums too long). A truth value put together with a text, which gave null, is its text, and with a list an item
 * (queries.yaml's "A truth value put together with a text or a list"). This runs those on SQLite in memory, and checks the
 * SQL Postgres and Oracle are given.
 */
class PlusOfTruthValueTest {

    private val compilers = listOf(PostgresCypherCompiler("g"), OracleCypherCompiler("g"), SqliteCypherCompiler("g"))

    /** What [cypher] gives on SQLite in memory: its first row's value as JSON text, or `fails: <message>`. */
    private fun sqlite(cypher: String, parameters: Map<String, Any?> = emptyMap()): String = SqliteHost.connection().use { c ->
        runCatching {
            val compiled = SqliteCypherCompiler("g").compile(cypher, parameters)
            // SQLite numbers each name it binds by where it first comes: these statements bind one at most.
            c.prepareStatement(compiled.sql).use { s ->
                compiled.parameters.values.forEachIndexed { i, v -> s.setObject(i + 1, v) }
                s.executeQuery().use { r -> r.next(); r.getString(1) ?: "null" }
            }
        }.getOrElse { e -> "fails: ${e.message}" }
    }

    private fun refused(cypher: String, parameters: Map<String, Any?> = emptyMap()): List<String> = compilers.map { compiler ->
        assertThrows<CompilerException>("${compiler::class.simpleName}: $cypher") { compiler.compile(cypher, parameters) }.message!!
    }

    private val truth = "+ of a truth value takes a text or a list"

    @Test
    fun `a truth value the statement knows beside a number or a truth value or a map or a temporal value is refused`() {
        val forms = mapOf(
            "RETURN true + 1" to "not an integer", "RETURN 1 + true" to "not an integer", "RETURN true + true" to "not a truth value",
            "RETURN true + 1.5" to "not a float", "WITH true AS b RETURN b + 1" to "not an integer",
            "UNWIND [true, false] AS b RETURN b + 1" to "not an integer", "RETURN (1 > 0) + 1" to "not an integer",
            "RETURN date(true + 1)" to "not an integer", "RETURN true + {a: 1}" to "not a map", "RETURN {a: 1} + true" to "not a map",
            "RETURN date('2024-01-01') + true" to "not a temporal value", "RETURN true + duration('P1D')" to "not a temporal value",
        )
        forms.forEach { (cypher, not) -> refused(cypher).forEach { assertEquals("$truth, $not, as in Neo4j.", it, cypher) } }
        refused("RETURN \$b + 1", mapOf("b" to true)).forEach { assertEquals("$truth, not an integer, as in Neo4j.", it) }
    }

    @Test
    fun `a map beside a number is refused`() {
        refused("RETURN {a: 1} + 1").forEach { assertEquals("+ of a map takes a map or a list, not an integer, as in Neo4j.", it) }
        refused("RETURN 1.5 + {a: 1}").forEach { assertEquals("+ of a map takes a map or a list, not a float, as in Neo4j.", it) }
    }

    @Test
    fun `a truth value beside a text or a list or null is put together as Neo4j does`() {
        assertEquals("\"atrue\"", sqlite("RETURN 'a' + true"))
        assertEquals("\"truea\"", sqlite("RETURN true + 'a'"))
        assertEquals("[1,true]", sqlite("RETURN [1] + true"))
        assertEquals("[true,1]", sqlite("RETURN true + [1]"))
        assertEquals("null", sqlite("RETURN true + null"))
        assertEquals("\"afalse\"", sqlite("RETURN 'a' + \$b", mapOf("b" to false)))
        assertEquals("\"falsea\"", sqlite("RETURN \$b + 'a'", mapOf("b" to false)))
        assertEquals("\"atrue\"", sqlite("UNWIND \$l AS b RETURN 'a' + b", mapOf("l" to listOf(true))))
    }

    @Test
    fun `a truth value read as the statement runs beside a number is null as before`() {
        listOf("UNWIND \$l AS b RETURN b + 1", "UNWIND \$l AS b RETURN 1.5 + b", "UNWIND \$l AS b RETURN b + b").forEach { cypher ->
            assertEquals("null", sqlite(cypher, mapOf("l" to listOf(true))), cypher)
        }
        assertEquals("2", sqlite("UNWIND \$l AS b RETURN b + 1", mapOf("l" to listOf(1))))
    }

    @Test
    fun `Postgres and Oracle put a truth value together with a text reading each side's kind once where a side may be one`() {
        listOf(PostgresCypherCompiler("g"), OracleCypherCompiler("g")).forEach { compiler ->
            val sql = compiler.compile("MATCH (n) RETURN n.a + 'x'").sql
            assertContains(sql, "IN ('string+string', 'string+number', 'number+string', 'string+boolean', 'boolean+string')", message = compiler::class.simpleName)
            // Beside a number, a list or a temporal value, no truth value is put together with a text: the SQL is as it was.
            listOf("n.a + 1", "n.a + [1]", "n.a + duration('P1D')", "'x' + 'y'").forEach { sum ->
                assertFalse("'string+boolean'" in compiler.compile("MATCH (n) RETURN $sum").sql, "${compiler::class.simpleName}: $sum")
            }
        }
    }

    @Test
    fun `a side of open kind is compiled as before`() {
        compilers.forEach { compiler ->
            listOf("MATCH (n) RETURN n.flag + 1", "MATCH (n) RETURN true + n.a", "RETURN \$m.b + 1").forEach { cypher ->
                compiler.compile(cypher, mapOf("m" to mapOf("b" to true)))
            }
            assertFalse(runCatching { compiler.compile("RETURN {a: 1} + {b: 1}") }.isFailure, "${compiler::class.simpleName}: a map beside a map")
        }
    }
}
