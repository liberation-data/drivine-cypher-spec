package org.drivine.cypher

import org.drivine.cypher.sql.OracleCypherCompiler
import org.drivine.cypher.sql.PostgresCypherCompiler
import org.drivine.cypher.sql.SqliteCypherCompiler
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * On SQLite, a walk's step tests a relationship's one type as a range, `e.type BETWEEN 'T' AND 'T'`, not with `=`
 * (drivine4j#766). SQLite planned the walk, gathered in `json_each`'s argument, as one run many times, and built an
 * automatic index for its step keyed on the type alone, which it may key on `=` or `IS` only: each step read every
 * relationship of the type, and a walk of 2,000 hops along a chain took 18.9 s. The rows are pinned by the Cypher spec's
 * walks; this pins the shape of the step, and that nothing else changed.
 */
class SqliteWalkStepTypeTest {

    private val sqlite = SqliteCypherCompiler(GRAPH)

    private fun sql(cypher: String) = sqlite.compile(cypher, emptyMap()).sql

    /** The recursive steps of the walks in [sql]: what follows each `UNION ALL` of a `WITH RECURSIVE`, to its end. */
    private fun steps(sql: String): List<String> =
        Regex("""WITH RECURSIVE w\d+ \(nid, depth, nids, eids\) AS \(\n.*?\n\s*UNION ALL\n(.*?)\n\s*\)\n""", RegexOption.DOT_MATCHES_ALL)
            .findAll(sql).map { it.groupValues[1] }.toList()

    private val typeTest = Regex("""\be\d+\.type (=|BETWEEN|IN)\b""")

    @Test
    fun `a walk's step tests its relationship's one type as a range`() {
        val walks = mapOf(
            "MATCH p = (:CyWrite {i: 0})-[:NEXT*]->(b) RETURN length(p)" to 1,
            "MATCH (a:Person {i: 1})-[:KNOWS*1..3]->(b) RETURN b" to 1,
            "MATCH (b:Person {i: 1})<-[:KNOWS*..6]-(a) RETURN a" to 1,
            "MATCH (a:Person {i: 1})-[:KNOWS*1..3]-(b) RETURN b" to 1,
            "MATCH (a:Person {i: 1})-[:KNOWS*2 {since: 1}]->(b) RETURN b" to 1,
            "MATCH (a:Person {i: 1}) ((x)-[:KNOWS]->(y:Person)-[:LIKES]->(z)){1,2} (b) RETURN b" to 2,
            "MATCH (a:Person) OPTIONAL MATCH (a)-[:KNOWS*1..2]->(b) RETURN count(b)" to 1,
            "MATCH (a:Person) WHERE EXISTS { (a)-[:KNOWS*2]->() } RETURN a" to 1,
            "MATCH (a:Person) RETURN COUNT { (a)-[:KNOWS*1..2]->() }" to 1,
            "MATCH (a:Person) RETURN [(a)-[:KNOWS*1..2]->(b) | b.i]" to 1,
            "MATCH (a:Person {i: 1}), (b:Person {i: 2}) MATCH p = shortestPath((a)-[:KNOWS*]->(b)) RETURN length(p)" to 1,
        )
        walks.forEach { (cypher, types) ->
            val sql = sql(cypher)
            val steps = steps(sql)
            assertEquals(1, steps.size, "$cypher:\n$sql")
            val tests = typeTest.findAll(steps.single()).map { it.value }.toList()
            assertEquals(types, tests.size, "$cypher:\n$sql")
            assertTrue(tests.all { it.endsWith("BETWEEN") }, "$cypher:\n$sql")
            assertTrue(Regex("""\be\d+\.type BETWEEN '(KNOWS|LIKES|NEXT)' AND '\1'""").containsMatchIn(steps.single()), "$cypher:\n$sql")
        }
    }

    @Test
    fun `a step of several types, or of any, tests them as before`() {
        assertTrue(Regex("""\be\d+\.type IN \('KNOWS', 'LIKES'\)""").containsMatchIn(steps(sql("MATCH (a {i: 1})-[:KNOWS|LIKES*1..3]->(b) RETURN b")).single()))
        assertTrue(typeTest.find(steps(sql("MATCH (a {i: 1})-[*1..3]->(b) RETURN b")).single()) == null)
    }

    @Test
    fun `a relationship outside a walk, and a walk on Postgres and Oracle, test the type with an equality`() {
        listOf(
            "MATCH (a:Person)-[:KNOWS]->(b) RETURN b",
            "MATCH (a:Person) OPTIONAL MATCH (a)-[:KNOWS]->(b) RETURN count(b)",
            "MATCH (a:Person) RETURN COUNT { (a)-[:KNOWS]->() }",
        ).forEach { cypher ->
            val sql = sql(cypher)
            assertTrue("BETWEEN" !in sql && Regex("""\be\d+\.type = 'KNOWS'""").containsMatchIn(sql), "$cypher:\n$sql")
        }
        listOf(PostgresCypherCompiler(GRAPH), OracleCypherCompiler(GRAPH)).forEach { compiler ->
            val sql = compiler.compile("MATCH (a:Person {i: 1})-[:KNOWS*1..3]->(b) RETURN b", emptyMap()).sql
            assertTrue("BETWEEN" !in sql && Regex("""\be\d+\.type = 'KNOWS'""").containsMatchIn(sql), "${compiler::class.simpleName}:\n$sql")
        }
    }

    private companion object {
        const val GRAPH = "g"
    }
}
