package org.drivine.cypher

import org.drivine.cypher.sql.OracleCypherCompiler
import org.drivine.cypher.sql.PostgresCypherCompiler
import org.drivine.cypher.sql.SqlCypherCompiler
import org.drivine.cypher.sql.SqliteCypherCompiler
import org.junit.jupiter.api.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * A list of numbers that holds a float and a whole number, given to a statement that stores it, is stored with each of its
 * numbers a float, as Neo4j stores it (#377): widened as the write works it out, so the same list given to the statement to read
 * is still the one given. Only where the statement gives such a list: any other write costs nothing. What each store gives is
 * the spec's, in `writes.yaml`, "A list of whole and fractional numbers stored as a property".
 */
class WidenedListWriteTest {

    /** Each compiler, and a text only its widening of a list writes. */
    private val compilers: List<Pair<SqlCypherCompiler, String>> = listOf(
        PostgresCypherCompiler("g") to "|| '.0' AS numeric",
        SqliteCypherCompiler("g") to "wi.atom || ",
        OracleCypherCompiler("g") to "TO_BINARY_DOUBLE(wi.n)",
    )

    private val mixed = listOf(1, 2.5)

    @Test
    fun `a list of whole and fractional numbers the statement gives to store is widened`() {
        val widened = listOf(
            "CREATE (:A {f: [1, 2.5]})" to emptyMap(),
            "CREATE (:A {f: [2.5, 1]})" to emptyMap(),
            "MATCH (n:A) SET n.f = \$l" to mapOf("l" to mixed),
            "MATCH (n:A) SET n.f = \$l RETURN \$l" to mapOf("l" to mixed),
            "MATCH (n:A) SET n.f = [1, \$x]" to mapOf("x" to 2.5),
            "MATCH (n:A) SET n += {v: [4, 0.25]}" to emptyMap(),
            "CREATE (n:A \$props)" to mapOf("props" to mapOf("f" to mixed, "h" to listOf(1, 2))),
            "MATCH (n:A) SET n = \$props" to mapOf("props" to mapOf("f" to mixed)),
            "UNWIND \$rows AS row CREATE (:A {f: row.f})" to mapOf("rows" to listOf(mapOf("f" to listOf(1, 2)), mapOf("f" to mixed))),
            "UNWIND \$rows AS row CREATE (:A {f: [row.a, 2.5]})" to mapOf("rows" to listOf(mapOf("a" to 1))),
            "UNWIND \$rows AS row MATCH (n:A {id: row.id}) SET n = row" to mapOf("rows" to listOf(mapOf("id" to 1, "f" to mixed))),
            "UNWIND \$rows AS row MATCH (n:A {id: row.id}) SET n += row" to mapOf("rows" to listOf(mapOf("id" to 1, "f" to mixed))),
            "MERGE (:A {f: [1, 2.5]})" to emptyMap(),
            "MATCH (a:A) CREATE (a)-[:R {w: [1, 0.5]}]->(:A)" to emptyMap(),
        )
        for ((compiler, mark) in compilers) for ((cypher, parameters) in widened) {
            val sql = compiler.compile(cypher, parameters).sql
            assertTrue(mark in sql, "${compiler::class.simpleName}: $cypher\n$sql")
        }
    }

    @Test
    fun `a write of no such list given costs nothing`() {
        val plain = listOf(
            "CREATE (:A {f: [1, 2]})" to emptyMap(),
            "CREATE (:A {f: [1.0, 2.5]})" to emptyMap(),
            "CREATE (:A {f: [1, 'x']})" to emptyMap(),
            "MATCH (n:A) SET n.f = \$l" to mapOf("l" to listOf(1, 2)),
            "MATCH (n:A) SET n.f = \$l" to mapOf("l" to listOf(0.5, 2.5)),
            "MATCH (n:A) SET n.f = 2.5" to emptyMap(),
            "CREATE (n:A \$props)" to mapOf("props" to mapOf("f" to listOf(1, 2), "g" to 2.5)),
            "UNWIND \$rows AS row CREATE (:A {f: row.f})" to mapOf("rows" to listOf(mapOf("f" to listOf(1, 2)))),
            // Read, not stored: Neo4j gives [1, 2.5].
            "RETURN [1, 2.5]" to emptyMap(),
            "MATCH (n:A) RETURN n, \$l" to mapOf("l" to mixed),
            // Worked out as it runs: #380's.
            "MATCH (n:A) SET n.f = [1, n.x]" to emptyMap(),
        )
        for ((compiler, mark) in compilers) for ((cypher, parameters) in plain) {
            val sql = compiler.compile(cypher, parameters).sql
            assertFalse(mark in sql, "${compiler::class.simpleName}: $cypher\n$sql")
        }
    }
}
