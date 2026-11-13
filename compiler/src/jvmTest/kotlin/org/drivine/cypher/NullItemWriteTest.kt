package org.drivine.cypher

import org.drivine.cypher.sql.OracleCypherCompiler
import org.drivine.cypher.sql.PostgresCypherCompiler
import org.drivine.cypher.sql.SqlCypherCompiler
import org.drivine.cypher.sql.SqliteCypherCompiler
import org.junit.jupiter.api.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * A write of a property that is a list that holds null fails, as Neo4j fails it (drivine4j#557): where each store compiles
 * the failure, and its test, without a store. What each store gives is the spec's, in `writes.yaml`, "A list that holds null
 * as a property".
 */
class NullItemWriteTest {

    private fun compilers(): List<SqlCypherCompiler> = listOf(PostgresCypherCompiler("g"), OracleCypherCompiler("g"), SqliteCypherCompiler("g"))

    /** What each store tests a value for as it runs, as its SQL writes it: see each one's `nullItem`. */
    private val tests = mapOf(
        PostgresCypherCompiler::class to "@> '[null]'",
        OracleCypherCompiler::class to "'[[,]null[],]'",
        SqliteCypherCompiler::class to "nj.type = ''null''",
    )

    private fun sql(compiler: SqlCypherCompiler, cypher: String, parameters: Map<String, Any?> = emptyMap()) = compiler.compile(cypher, parameters).sql

    /** Whether [sql] fails the statement with the refusal: SQLite's steps are a text literal, which doubles nothing in it. */
    private fun refuses(sql: String) = sql.contains(refusal)

    private val refusal = "A property is a number, a text, a truth value, a temporal value or a list of them, not a list that holds null, as in Neo4j."

    private fun tests(compiler: SqlCypherCompiler, sql: String) = sql.contains(tests.getValue(compiler::class))

    @Test
    fun `a list written or given that holds null fails each row written, with no test`() {
        val surely = listOf(
            "CREATE (n:A {id: 2, x: [null]})" to emptyMap(),
            "CREATE (n:A {id: 3, x: [1, null, 2]})" to emptyMap(),
            "MERGE (n:A {id: 4, x: [null]})" to emptyMap(),
            "MATCH (n:A) SET n.x = [null]" to emptyMap(),
            "MATCH (n:A) SET n.x = [1, \$p]" to mapOf("p" to null),
            "MATCH (n:A) SET n.x = \$p" to mapOf("p" to listOf(1, null)),
            "MATCH (n:A) SET n += {x: [null]}" to emptyMap(),
            "MATCH (n:A) SET n = \$m" to mapOf("m" to mapOf("x" to listOf(null), "y" to 1)),
            "CREATE (n:A \$m)" to mapOf("m" to mapOf("x" to listOf(null))),
            "MATCH (n:A) CREATE (n)-[r:R {x: [null]}]->(:A {id: 5})" to emptyMap(),
            "MATCH (n:A) MERGE (n)-[r:R]->(m:A {id: 6}) ON CREATE SET r.x = \$q" to mapOf("q" to listOf(null)),
            "MATCH (n:A) MERGE (n)-[r:R]->(m:A {id: 6}) ON MATCH SET r.x = [null]" to emptyMap(),
            "MATCH (n:A) FOREACH (i IN [1] | SET n.x = [null])" to emptyMap(),
        )
        for (compiler in compilers()) for ((cypher, parameters) in surely) {
            // Raised in the loop over the rows, so a statement of no rows does not fail, as in Neo4j.
            val sql = sql(compiler, cypher, parameters)
            assertTrue(refuses(sql), "$cypher\n$sql")
            assertFalse(tests(compiler, sql), "$cypher\n$sql")
        }
    }

    @Test
    fun `a list worked out as it runs is tested as it is written, on every path that writes`() {
        val worked = listOf(
            "MATCH (n:A) SET n.x = [null] + [1]",
            "MATCH (n:A) WITH n, [x IN [1, 2] | CASE WHEN x = 2 THEN null ELSE x END] AS l SET n.x = l",
            "UNWIND [[1], [null]] AS l CREATE (n:A {x: l})",
            "MATCH (s:A) MERGE (n:A {x: [s.y]})",
            "MATCH (s:A) CREATE (s)-[:R {x: [s.y]}]->(:A)",
            "MATCH (n:A) WITH n, {x: [n.y]} AS m SET n += m",
            "MATCH (n:A) WITH n, {x: [n.y]} AS m SET n = m",
            "MATCH (s:A) WITH s, {x: [s.y]} AS m CREATE (n:A) SET n = m",
            "MATCH (n:A)-[r:R]->() SET r.x = [n.y]",
        )
        // Rows the statement gives, one of which holds null: the list given is read, and its rows tested as each is written.
        val given = mapOf("rows" to listOf(mapOf("x" to listOf(1)), mapOf("x" to listOf(1, null))))
        val rows = listOf("UNWIND \$rows AS row CREATE (n:A) SET n += row", "UNWIND \$rows AS row CREATE (:A {x: row.x})")
        // A row given without the key is null there, so the list written of it holds null.
        val missing = "UNWIND \$rows AS row CREATE (:A {x: [row.t]})" to mapOf("rows" to listOf(mapOf("t" to "a"), emptyMap<String, Any?>()))
        for (compiler in compilers()) for ((cypher, parameters) in worked.map { it to emptyMap<String, Any?>() } + rows.map { it to given } + missing) {
            val sql = sql(compiler, cypher, parameters)
            assertTrue(refuses(sql), "$cypher\n$sql")
            assertTrue(tests(compiler, sql), "$cypher\n$sql")
        }
    }

    @Test
    fun `a value that can hold no null as an item is not tested, so the write costs what it did`() {
        val rows = mapOf("rows" to listOf(mapOf("a" to null, "b" to listOf(1), "t" to "x"), mapOf("a" to 1, "b" to emptyList<Any>(), "t" to "y")))
        val plain = listOf(
            "CREATE (n:A {id: 1, x: [1, 2], s: 'a', none: null})" to emptyMap(),
            "MATCH (n:A) SET n.x = [], n.y = null, n.z = 1" to emptyMap(),
            "MATCH (n:A) SET n.x = \$p, n.y = \$none" to mapOf("p" to listOf(1, 2), "none" to null),
            "MATCH (n:A), (m:A) SET n.x = m.y, n += m" to emptyMap(),
            "MATCH (n:A)-[r:R]->(:A) SET n = r" to emptyMap(),
            "MATCH (n:A) SET n = \$m" to mapOf("m" to mapOf("x" to listOf(1), "y" to null)),
            // A temporal value is a tagged map, which holds no null as an item.
            "MATCH (n:A) SET n.d = \$d, n.e = [\$t]" to mapOf("d" to listOf(java.time.LocalDate.of(2026, 11, 13)), "t" to java.time.LocalDate.of(2026, 11, 13)),
            "UNWIND \$ids AS id CREATE (:A {id: id})" to mapOf("ids" to listOf(1, 2)),
            "UNWIND \$rows AS row CREATE (n:A) SET n += row" to rows,
            "UNWIND \$rows AS row CREATE (n:A {a: row.a, b: row.b, tags: [row.t, 'z']})" to rows,
            "UNWIND [1, 2] AS i CREATE (:A {x: [i]})" to emptyMap(),
            "CREATE (:A {d: [date('2026-11-13'), datetime({year: 2026})]})" to emptyMap(),
            "MATCH (n:A) WITH n, toString(n.x) AS t, n.x + 1 AS i, 2.5 AS f SET n.t = t, n.i = i, n.f = f" to emptyMap(),
            // A variable of a kind known that is no list, or a temporal value, though neither is known to hold no null.
            "MATCH (n:A) WITH n, sqrt(n.x) AS r, date() + duration('P1D') AS d SET n.r = r, n.d = d" to emptyMap(),
        )
        for (compiler in compilers()) for ((cypher, parameters) in plain) {
            val sql = sql(compiler, cypher, parameters)
            assertFalse(refuses(sql), "$cypher\n$sql")
        }
    }

    @Test
    fun `a SET of a node or a relationship that is null sets nothing, so it fails on no list it would write`() {
        // Neo4j sets nothing on null, and so never works out the value (openCypher's Set1 [8]): the raise is only on a row
        // whose node or relationship is there, its id read in the SELECT that may raise, as the UPDATE reads it.
        val set = listOf(
            "OPTIONAL MATCH (n:A) SET n.x = [null]" to emptyMap(),
            "OPTIONAL MATCH (n:A) SET n += {x: [null]}" to emptyMap(),
            "OPTIONAL MATCH (n:A) SET n = \$m" to mapOf("m" to mapOf("x" to listOf(null))),
            "OPTIONAL MATCH ()-[r:R]->() SET r.x = [null]" to emptyMap(),
            "OPTIONAL MATCH (n:A) WITH n, {x: [n.y]} AS m SET n += m" to emptyMap(),
        )
        val present = Regex("""CASE WHEN (CAST\(\S+ ->> \S+ AS (bigint|INTEGER)\)|JSON_VALUE\(cy_row, \S+ RETURNING NUMBER\)) IS NOT NULL""")
        for (compiler in compilers()) for ((cypher, parameters) in set) {
            val sql = sql(compiler, cypher, parameters)
            assertTrue(refuses(sql), "$cypher\n$sql")
            assertTrue(present.containsMatchIn(sql), "$cypher\n$sql")
        }
    }

    @Test
    fun `Oracle tests the value written only through its text as a CLOB, which a text past 32 KB is read through safely`() {
        // A text past 32 KB read by `$.type()`, as a VARCHAR2 or taken out as JSON, on two rows or more, closed the session
        // (ORA-03113, drivine4j#531, #255): the test reads the value once, by JSON_SERIALIZE … RETURNING CLOB, and nothing else.
        val worked = listOf(
            "UNWIND [1, 2] AS i MATCH (n:A) SET n.x = [n.y], n.t = \$long",
            "UNWIND [1, 2] AS i MATCH (n:A) WITH n, {x: [n.y], t: \$long} AS m SET n += m",
            "UNWIND [1, 2] AS i MATCH (s:A) CREATE (:A {x: [s.y], t: \$long})",
            "UNWIND [1, 2] AS i MATCH (s:A) CREATE (s)-[:R {x: [s.y], t: \$long}]->(:A)",
        )
        for (cypher in worked) {
            val sql = sql(OracleCypherCompiler("g"), cypher, mapOf("long" to "x".repeat(40_000)))
            val checks = sql.lines().filter { "INTO cy_count" in it && "[[,]null[],]" in it }
            assertTrue(checks.isNotEmpty(), "$cypher\n$sql")
            for (check in checks) {
                val read = Regex("""cy_value\b""").findAll(check).count()
                assertTrue(read == 1 && "JSON_SERIALIZE(cy_value RETURNING CLOB)" in check, "$cypher\n$check")
                // A SET reads the id of the node it writes to as its UPDATE reads it, a number out of the row: not the value.
                val tested = check.replace(Regex("""JSON_VALUE\(cy_row, '[^']*' RETURNING NUMBER\) IS NOT NULL AND """), "")
                listOf("type()", "VARCHAR2", "JSON_QUERY", "JSON_VALUE").forEach { assertFalse(it in tested, "$it in $cypher\n$check") }
            }
        }
    }
}
