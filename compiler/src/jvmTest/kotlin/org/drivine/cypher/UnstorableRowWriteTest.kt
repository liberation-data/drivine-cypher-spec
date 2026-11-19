package org.drivine.cypher

import org.drivine.cypher.sql.OracleCypherCompiler
import org.drivine.cypher.sql.PostgresCypherCompiler
import org.drivine.cypher.sql.SqlCypherCompiler
import org.drivine.cypher.sql.SqliteCypherCompiler
import org.junit.jupiter.api.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * A write of a value the statement works out as it runs, and that is no property Neo4j stores, fails as the row is written
 * (drivine4j#820): where each store compiles the test, and where it compiles none, as the value's kind is known when compiling
 * or it is seen as given. What each store gives is the spec's, in `writes.yaml`, "A value worked out as it runs that Neo4j
 * stores as no property".
 */
class UnstorableRowWriteTest {

    private fun compilers(): List<SqlCypherCompiler> = listOf(PostgresCypherCompiler("g"), OracleCypherCompiler("g"), SqliteCypherCompiler("g"))

    /** What each store tests a list's items by as it runs, as its SQL writes it: see each one's `unstorableItem`. */
    private val tests = mapOf(
        PostgresCypherCompiler::class to "jsonb_typeof(ui) = 'array'",
        OracleCypherCompiler::class to "REGEXP_REPLACE(ns.c, ",
        SqliteCypherCompiler::class to "CASE ui.type WHEN ''array'' THEN 1",
    )

    private fun sql(compiler: SqlCypherCompiler, cypher: String, parameters: Map<String, Any?>) = compiler.compile(cypher, parameters).sql

    private fun tests(compiler: SqlCypherCompiler, sql: String) = sql.contains(tests.getValue(compiler::class))

    private val refusals = listOf("a map", "a list of maps or lists", "a list of more than one kind")
        .map { "A property is a number, a text, a truth value, a temporal value or a list of them, not $it, as in Neo4j." }

    @Test
    fun `a value a row gives as the statement runs is tested as the row is written`() {
        val rows = mapOf("rows" to listOf(mapOf("m" to 1), mapOf("m" to mapOf("a" to 1))))
        val tested = listOf(
            "UNWIND \$rows AS row WITH row CREATE (:X {p: row.m})" to rows,
            "UNWIND \$rows AS row WITH row WHERE row.m IS NOT NULL CREATE (:X {p: row.m})" to rows,
            "UNWIND \$rows AS row WITH row MATCH (n:A) SET n.p = row.m" to rows,
            "UNWIND \$rows AS row WITH row MATCH (n:A) SET n += {p: row.m}" to rows,
            "UNWIND \$rows AS row WITH row MATCH (n:A) SET n = row" to rows,
            "WITH \$m AS m MATCH (n:A) SET n.p = m" to mapOf("m" to mapOf("a" to 1)),
            "UNWIND \$rows AS row WITH row AS r WITH r LIMIT 5 CREATE (:X {p: r.m})" to rows,
            // A WITH that works the value out is no row given passed on.
            "UNWIND \$rows AS row WITH {m: row.m} AS r CREATE (:X {p: r.m})" to mapOf("rows" to listOf(mapOf("m" to 1))),
            "MATCH (n:A) SET n.p = \$m.a" to mapOf("m" to mapOf("a" to mapOf("b" to 1))),
            "WITH [{a: 1}] AS l MATCH (n:A) SET n.p = l" to emptyMap(),
            "MATCH (n:A) WITH n, {p: {a: 1}} AS m SET n += m" to emptyMap(),
            // A list with an item worked out as it runs, as UnstorableGivenWriteTest's lists given are not.
            "MATCH (n:A) SET n.p = [1, n.q]" to emptyMap(),
            "MATCH (n:A) MERGE (n)-[r:R]->(:A {id: 2}) ON CREATE SET r.p = coalesce(n.q, \$m.a)" to mapOf("m" to mapOf("a" to listOf(1, "a"))),
            // Rows given, in a map or a list written: a row a MATCH leaves out fails nothing, as `SET n.q = row.m` fails none.
            "UNWIND \$rows AS row MATCH (n:A {id: row.id}) SET n += {q: row.m}" to rows,
            "UNWIND \$rows AS row MATCH (n:A {id: row.id}) SET n.q = [row.m]" to rows,
            "UNWIND \$rows AS row MATCH (n:A {id: row.id}) CREATE (n)-[:R {p: row.m}]->(:A)" to rows,
        )
        for (compiler in compilers()) for ((cypher, parameters) in tested) {
            val sql = sql(compiler, cypher, parameters)
            assertTrue(tests(compiler, sql), "$cypher\n$sql")
            // Raised in the loop over the rows, by the number the test gives, so a statement of no rows does not fail.
            refusals.forEach { assertTrue(sql.contains(it), "$cypher: $it\n$sql") }
        }
    }

    @Test
    fun `Postgres tests a row only where its value is a list or a map, which PL-pgSQL tells with no query`() {
        val sql = sql(PostgresCypherCompiler("g"), "UNWIND \$rows AS row WITH {m: row.m} AS r CREATE (:X {p: r.m})", mapOf("rows" to listOf(mapOf("m" to 1))))
        assertTrue(sql.contains("IF jsonb_typeof(cy_value) IN ('array', 'object') THEN"), sql)
    }

    @Test
    fun `a value of a kind known when compiling, or seen as given, is not tested`() {
        val known = listOf(
            "UNWIND \$rows AS row CREATE (:X {p: row.v})" to mapOf("rows" to listOf(mapOf("v" to 1))),
            // A WITH that passes a row given on as it is keeps it given, so rows that are all properties cost no test.
            "UNWIND \$rows AS row WITH row CREATE (:X {p: row.v})" to mapOf("rows" to listOf(mapOf("v" to 1), mapOf("v" to "x"))),
            "UNWIND \$rows AS row WITH row AS r WHERE r.v > 0 MATCH (n:A) SET n += r" to mapOf("rows" to listOf(mapOf("v" to 1))),
            "UNWIND \$rows AS row WITH * LIMIT 1 CREATE (:X {p: row.l})" to mapOf("rows" to listOf(mapOf("l" to listOf(1, 2)))),
            "WITH \$m AS m MATCH (n:A) SET n.p = m.a, n += m" to mapOf("m" to mapOf("a" to listOf(1, 2))),
            "CREATE (:X {p: \$v})" to mapOf("v" to "x"),
            "MATCH (n:A) SET n.p = 1",
            "MATCH (n:A) SET n.p = n.q",
            "MATCH (n:A) SET n.p = date('2026-01-05')",
            "MATCH (n:A) SET n.p = toString(n.q)",
            "MATCH (n:A), (m:A) SET n = m",
            "MATCH (n:A), (m:A) SET n += properties(m)",
            "MATCH (n:A), (m:A) SET n += {p: m.q, q: 2}",
            "WITH 1 AS x MATCH (n:A) SET n.p = x",
            "WITH [1, 2] AS l MATCH (n:A) SET n.p = l",
            "MATCH (n:A) SET n.p = \$m.a, n.q = \$m['b'], n.r = coalesce(n.x, \$m.a)" to mapOf("m" to mapOf("a" to listOf(1, 2), "b" to "x")),
            "MATCH (n:A) SET n.p = toString(n.q) + 'x', n.r = CASE WHEN n.q > 1 THEN 'a' ELSE toString(n.q) END",
        ).map { if (it is Pair<*, *>) @Suppress("UNCHECKED_CAST") (it as Pair<String, Map<String, Any?>>) else (it as String) to emptyMap() }
        for (compiler in compilers()) for ((cypher, parameters) in known) {
            val sql = sql(compiler, cypher, parameters)
            assertFalse(tests(compiler, sql), "$cypher\n$sql")
        }
    }
}
