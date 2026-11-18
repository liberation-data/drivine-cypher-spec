package org.drivine.cypher

import org.drivine.cypher.sql.OracleCypherCompiler
import org.drivine.cypher.sql.PostgresCypherCompiler
import org.drivine.cypher.sql.SqlCypherCompiler
import org.drivine.cypher.sql.SqliteCypherCompiler
import org.junit.jupiter.api.Test
import java.time.LocalDate
import java.time.LocalDateTime
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * A write of a property the statement gives as no property Neo4j stores, a map, a list that holds a map or a list, or a list of
 * more than one kind, fails as each row is written, as Neo4j fails it (drivine4j#820): known when the statement compiles, so
 * with no test as it runs. What each store gives is the spec's, in `writes.yaml`, "A value given that Neo4j stores as no property".
 */
class UnstorableGivenWriteTest {

    private fun compilers(): List<SqlCypherCompiler> = listOf(PostgresCypherCompiler("g"), OracleCypherCompiler("g"), SqliteCypherCompiler("g"))

    private fun refusal(what: String) = "A property is a number, a text, a truth value, a temporal value or a list of them, not $what, as in Neo4j."

    private val refusals = listOf("a map", "a list of maps or lists", "a list of more than one kind").map(::refusal)

    private fun sql(compiler: SqlCypherCompiler, cypher: String, parameters: Map<String, Any?>) = compiler.compile(cypher, parameters).sql

    @Test
    fun `a value given that is no property fails each row written, by name`() {
        val rows = mapOf("rows" to listOf(mapOf("m" to 1, "l" to listOf(1)), mapOf("m" to mapOf("a" to 1), "l" to listOf(1, "a"))))
        val given = listOf(
            Triple("MATCH (n:A) SET n.p = \$m", mapOf("m" to mapOf("a" to 1)), "a map"),
            Triple("CREATE (:A {p: \$m})", mapOf("m" to mapOf("a" to 1)), "a map"),
            Triple("MERGE (:A {p: \$m})", mapOf("m" to mapOf("a" to 1)), "a map"),
            Triple("MATCH (n:A) CREATE (n)-[:R {p: \$m}]->(:A)", mapOf("m" to mapOf("a" to 1)), "a map"),
            Triple("MATCH (n:A) SET n.p = \$l", mapOf("l" to listOf(mapOf("a" to 1))), "a list of maps or lists"),
            Triple("MATCH (n:A) SET n.p = \$l", mapOf("l" to listOf(listOf(1, 2))), "a list of maps or lists"),
            Triple("MATCH (n:A) SET n.p = \$l", mapOf("l" to listOf(1, "a")), "a list of more than one kind"),
            Triple("MATCH (n:A) SET n.p = \$l", mapOf("l" to listOf(LocalDate.of(2026, 11, 18), LocalDateTime.of(2026, 11, 18, 1, 0))), "a list of more than one kind"),
            Triple("MATCH (n:A) SET n.p = [1, \$s]", mapOf("s" to "a"), "a list of more than one kind"),
            Triple("MATCH (n:A) SET n.p = [\$m]", mapOf("m" to mapOf("a" to 1)), "a list of maps or lists"),
            Triple("MATCH (n:A) SET n = \$props", mapOf("props" to mapOf("id" to 1, "p" to mapOf("a" to 1))), "a map"),
            Triple("MATCH (n:A) SET n += \$props", mapOf("props" to mapOf("p" to listOf(true, 1))), "a list of more than one kind"),
            Triple("CREATE (n:A \$props)", mapOf("props" to mapOf("p" to listOf(listOf(1)))), "a list of maps or lists"),
            Triple("MATCH (n:A) SET n += {p: \$m}", mapOf("m" to mapOf("a" to 1)), "a map"),
            Triple("UNWIND \$rows AS row CREATE (:A {p: row.m})", rows, "a map"),
            Triple("UNWIND \$rows AS row CREATE (:A {p: row.l})", rows, "a list of more than one kind"),
            Triple("UNWIND \$rows AS row CREATE (n:A) SET n += row", rows, "a map"),
            Triple("UNWIND \$rows AS row CREATE (:A {p: [row.m]})", rows, "a list of maps or lists"),
            Triple("UNWIND \$ids AS id CREATE (:A {p: [id, 'x']})", mapOf("ids" to listOf(1, 2)), "a list of more than one kind"),
            // Of rows whose item is of a kind each, a truth value beside it makes each row's list of more than one.
            Triple("UNWIND \$rows AS row CREATE (:A {p: [row.a, true]})", mapOf("rows" to listOf(mapOf("a" to 1), mapOf("a" to "x"))), "a list of more than one kind"),
        )
        for (compiler in compilers()) for ((cypher, parameters, what) in given) {
            val sql = sql(compiler, cypher, parameters)
            assertTrue(sql.contains(refusal(what)), "$cypher\n$sql")
        }
    }

    @Test
    fun `a value given that Neo4j stores is written as before`() {
        val stored = listOf(
            "MATCH (n:A) SET n.p = \$l" to mapOf("l" to listOf(1, 2.5)),
            "MATCH (n:A) SET n.p = \$l" to mapOf("l" to emptyList<Any>()),
            "MATCH (n:A) SET n.p = \$l" to mapOf("l" to listOf(LocalDate.of(2026, 11, 18), LocalDate.of(2026, 11, 19))),
            "MATCH (n:A) SET n.p = \$d" to mapOf("d" to LocalDate.of(2026, 11, 18)),
            "MATCH (n:A) SET n.p = \$b" to mapOf("b" to byteArrayOf(1, -1)),
            "MATCH (n:A) SET n.p = [1, \$x, 2.5]" to mapOf("x" to 3),
            "MATCH (n:A) SET n = \$props" to mapOf("props" to mapOf("id" to 1, "tags" to listOf("a", "b"), "none" to null)),
            "UNWIND \$rows AS row CREATE (n:A) SET n += row" to mapOf("rows" to listOf(mapOf("a" to 1), mapOf("a" to "x"))),
            "UNWIND \$rows AS row CREATE (:A {p: [row.a, 2]})" to mapOf("rows" to listOf(mapOf("a" to 1), mapOf("a" to 1.5))),
            // Each row's list is of one kind, though the rows are of different kinds: [1], then ['x'].
            "UNWIND \$rows AS row CREATE (:A {p: [row.a]})" to mapOf("rows" to listOf(mapOf("a" to 1), mapOf("a" to "x"))),
            "UNWIND \$rows AS row CREATE (:A {p: [row.a, row.b]})" to mapOf("rows" to listOf(mapOf("a" to 1, "b" to 2), mapOf("a" to "x", "b" to "y"))),
            // A map given whose value is read by key is that value.
            "MATCH (n:A) SET n.p = \$m.a" to mapOf("m" to mapOf("a" to 1)),
            // A list with an item worked out as it runs is left to the store.
            "MATCH (n:A) SET n.p = [1, n.id]" to emptyMap(),
        )
        for (compiler in compilers()) for ((cypher, parameters) in stored) {
            val sql = sql(compiler, cypher, parameters)
            assertFalse(refusals.any(sql::contains), "$cypher\n$sql")
        }
    }

    @Test
    fun `a list given that holds null is refused for the null, not for its kinds`() {
        val nullItem = refusal("a list that holds null")
        for (compiler in compilers()) for ((cypher, parameters) in listOf(
            "MATCH (n:A) SET n.p = [1, \$x]" to mapOf("x" to null),
            "UNWIND \$rows AS row CREATE (:A {p: [row.a, 'x']})" to mapOf("rows" to listOf(emptyMap<String, Any?>())),
        )) {
            val sql = sql(compiler, cypher, parameters)
            assertTrue(sql.contains(nullItem), "$cypher\n$sql")
            assertFalse(refusals.any(sql::contains), "$cypher\n$sql")
        }
    }
}
