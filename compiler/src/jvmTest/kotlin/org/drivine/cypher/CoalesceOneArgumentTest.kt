package org.drivine.cypher

import org.drivine.cypher.sql.OracleCypherCompiler
import org.drivine.cypher.sql.PostgresCypherCompiler
import org.drivine.cypher.sql.SqliteCypherCompiler
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * `coalesce()` of one argument was compiled to `COALESCE(x)`, which Postgres takes but Oracle (ORA-00938) and SQLite
 * ("wrong number of arguments to function COALESCE()") refuse (drivine4j#530). It is its argument alone, and of none it
 * is refused, as Neo4j refuses it.
 */
class CoalesceOneArgumentTest {

    private val compilers = listOf(PostgresCypherCompiler("g"), OracleCypherCompiler("g"), SqliteCypherCompiler("g"))

    private val statements = listOf(
        "MATCH (n:Pr) WITH n ORDER BY n.i RETURN coalesce(n.u) IS NULL",
        "MATCH (n:Pr) RETURN coalesce(n.name) AS n ORDER BY n",
        "RETURN coalesce(null)",
        "MATCH (n:Pr) RETURN [coalesce(n.i) + 1, toUpper(coalesce(n.name)), size(coalesce(n.name))]",
        "MATCH (n:Pr) WHERE coalesce(n.i) > 0 RETURN n.i",
        "MATCH ()-[r:R]->() RETURN coalesce(r.role)",
        "MATCH (n:Pr) RETURN coalesce(max(n.i))",
        "MATCH (n:Pr) RETURN coalesce(n.name) AS m, count(*)",
        "MATCH (n:Pr) RETURN DISTINCT coalesce(n.name)",
        "MATCH (n:Pr) RETURN n.i ORDER BY coalesce(n.name)",
        "RETURN [coalesce(\$x), coalesce([1, null]), coalesce({a: 1}).a, coalesce(date('2024-03-15')).year]",
        "CREATE (n:Pr {a: 1}) SET n.b = coalesce(n.a), n.c = coalesce(n.missing) RETURN coalesce(n.b)",
    )

    /** The number of arguments of each `COALESCE(…)` in [sql], read past its quoted texts and nested parentheses. */
    private fun coalesceArities(sql: String): List<Int> =
        Regex("COALESCE\\(").findAll(sql).map { match ->
            var depth = 0
            var commas = 0
            var quoted = false
            var empty = true
            for (c in sql.substring(match.range.last + 1)) {
                if (quoted) { if (c == '\'') quoted = false; continue }
                when {
                    c == '\'' -> quoted = true
                    c == '(' -> depth++
                    c == ')' && depth == 0 -> break
                    c == ')' -> depth--
                    c == ',' && depth == 0 -> commas++
                }
                if (!c.isWhitespace()) empty = false
            }
            if (empty) 0 else commas + 1
        }.toList()

    @Test
    fun `coalesce() of one argument compiles to no COALESCE of one argument, on each store`() {
        compilers.forEach { compiler ->
            statements.forEach { cypher ->
                val sql = compiler.compile(cypher, mapOf("x" to "y")).sql
                val arities = coalesceArities(sql)
                assertTrue(arities.all { it >= 2 }, "${compiler::class.simpleName}: $cypher gave a COALESCE of fewer than two arguments ($arities): $sql")
            }
        }
    }

    @Test
    fun `coalesce() of no argument is refused, as Neo4j refuses it`() {
        compilers.forEach { compiler ->
            val refused = assertThrows<CompilerException>(compiler::class.simpleName!!) { compiler.compile("RETURN coalesce()") }.message
            assertEquals("coalesce() takes at least one argument.", refused)
        }
    }
}
