package org.drivine.cypher

import org.drivine.cypher.sql.OracleCypherCompiler
import org.drivine.cypher.sql.PostgresCypherCompiler
import org.drivine.cypher.sql.SqliteCypherCompiler
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * A `UNION` without `ALL` compares a row that holds a node or a relationship by its identity, carried beside the row's
 * `result`, so two different nodes or relationships with equal properties are two rows, as on a graph engine
 * (drivine4j#213); a row that holds none is compared by its value alone, its SQL as it was. What the stores give is the
 * spec's, in `writes.yaml` ("A UNION keeps different nodes with equal properties apart" and the cases after it) and
 * `queries.yaml` ("UNION of different relationships with equal properties").
 */
class UnionIdentityTest {

    private val compilers = listOf(PostgresCypherCompiler("g"), OracleCypherCompiler("g"), SqliteCypherCompiler("g"))

    private fun each(check: (name: String, sql: (String) -> String) -> Unit) =
        compilers.forEach { compiler -> check(compiler::class.simpleName!!) { cypher -> compiler.compile(cypher).sql } }

    /** The select list of each query of the statement's `UNION`, as the union reads it. */
    private fun partHeads(sql: String): List<String> =
        Regex("""SELECT (\d+ AS part, .*?) FROM \(\n""").findAll(sql).map { it.groupValues[1] }.toList()

    @Test
    fun `a UNION of values holds no identity, and groups its rows by their result alone`() {
        each { store, sql ->
            listOf(
                "UNWIND [3, 1] AS x RETURN x AS v UNION UNWIND [2, 3] AS x RETURN x AS v",
                "MATCH (p:Person) RETURN {name: p.name} AS row UNION MATCH (m:Movie) RETURN {name: m.title} AS row",
                "MATCH (p:Person) RETURN p.name AS n, p.born AS b UNION MATCH (p:Person) RETURN p.name AS n, null AS b",
            ).forEach { cypher ->
                val compiled = sql(cypher)
                assertFalse("identity" in compiled, "$store: $compiled")
                assertTrue("GROUP BY u.result\n" in compiled, "$store: $compiled")
            }
        }
    }

    @Test
    fun `a UNION of nodes or relationships gives each row's identity after its result, and groups by both`() {
        each { store, sql ->
            listOf(
                "MATCH (a:A) RETURN a AS n UNION MATCH (b:B) RETURN b AS n",
                "MATCH ()-[r:R]->() RETURN r AS e UNION MATCH ()-[s:S]->() RETURN s AS e",
                "MATCH (a:A) RETURN {x: a} AS m UNION MATCH (b:B) RETURN {x: b} AS m",
                "MATCH (c:C) RETURN [{x: [c]}] AS m UNION MATCH (d:D) RETURN [{x: [d]}] AS m",
                "MATCH (c:C) RETURN collect(c) AS m UNION MATCH (d:D) RETURN collect(d) AS m",
                "MATCH (c:C) RETURN c AS n, c.x AS k UNION MATCH (d:D) RETURN d AS n, d.x AS k",
            ).forEach { cypher ->
                val compiled = sql(cypher)
                val heads = partHeads(compiled)
                assertEquals(2, heads.size, "$store: $compiled")
                heads.forEachIndexed { i, head ->
                    assertTrue(head.startsWith("${i + 1} AS part, "), "$store: $head")
                    assertTrue(head.endsWith(" AS result, q.identity AS identity"), "$store: $head")
                }
                assertTrue("GROUP BY u.result, u.identity\n" in compiled, "$store: $compiled")
                // What the statement gives is the result alone, as before.
                assertTrue(Regex("""^SELECT (/\*\+ [A-Z_ ]+ \*/ )?d\.result FROM \(\n""").containsMatchIn(compiled), "$store: $compiled")
            }
        }
    }

    @Test
    fun `a query whose rows hold no node beside one whose rows do gives its value as its identity`() {
        each { store, sql ->
            val compiled = sql("MATCH (c:C) RETURN c AS n UNION RETURN {x: 1} AS n")
            val (first, second) = partHeads(compiled)
            assertTrue(first.endsWith(" AS result, q.identity AS identity"), "$store: $first")
            val result = second.removePrefix("2 AS part, ").substringAfter(" AS n, ").substringBefore(" AS result")
            assertEquals("$result AS result, $result AS identity", second.substringAfter(" AS n, "), "$store: $second")
        }
    }

    @Test
    fun `where a query may give a node without its identity, the UNION compares each by its value, as before`() {
        each { store, sql ->
            listOf(
                "MATCH (c:C) RETURN c AS n UNION MATCH (c:C) RETURN head(collect(c)) AS n",
                "MATCH (c:C) RETURN c AS n UNION MATCH (c:C) RETURN coalesce(c, c) AS n",
                "MATCH (c:C) RETURN COUNT { RETURN c AS n UNION RETURN coalesce(c, c) AS n }",
            ).forEach { cypher ->
                val compiled = sql(cypher)
                assertFalse("AS identity FROM" in compiled, "$store: $compiled")
            }
            // In a CALL, each query gives its value as its identity, and the CALL's value has none.
            val called = sql("MATCH (c:C) CALL (c) { RETURN {x: c} AS m UNION RETURN {x: head(collect(c))} AS m } RETURN count(DISTINCT m)")
            assertEquals(2, Regex("""q\d+\.c1\)? AS c1_identity""").findAll(called).count(), "$store: $called")
            assertFalse(Regex("""DISTINCT [^\n]*c1_identity""").containsMatchIn(called), "$store: $called")
        }
    }

    @Test
    fun `UNION ALL holds no identity`() {
        each { store, sql ->
            val compiled = sql("MATCH (a:A) RETURN {x: a} AS m UNION ALL MATCH (a:A) RETURN {x: a} AS m")
            assertFalse(" AS identity" in compiled, "$store: $compiled")
        }
    }

    @Test
    fun `a UNION in COUNT compares its rows by identity, and one in EXISTS, which keeps no row once, holds none`() {
        each { store, sql ->
            val counted = sql("MATCH (c:C), (d:D) RETURN COUNT { RETURN c AS n UNION RETURN d AS n }")
            assertEquals(2, Regex("""AS result, q\.identity AS identity FROM""").findAll(counted).count(), "$store: $counted")
            val plain = sql("MATCH (p:Person) RETURN COUNT { MATCH (p)-[:KNOWS]-(f) RETURN f.name AS n UNION MATCH (p)-[:ACTED_IN]->(m) RETURN m.title AS n }")
            assertFalse("identity" in plain, "$store: $plain")
            val exists = sql("MATCH (p:Person) WHERE EXISTS { MATCH (p)-[:KNOWS]->(f) RETURN f UNION MATCH (p)-[:ACTED_IN]->(f) RETURN f } RETURN p.name")
            assertFalse("identity" in exists, "$store: $exists")
        }
    }

    @Test
    fun `in a CALL, a query whose map holds no node gives its value as its identity beside one whose map does`() {
        val postgres = PostgresCypherCompiler("g")
        fun heads(cypher: String) = Regex("""SELECT (q\d+\..*?) FROM \(\n""").findAll(postgres.compile(cypher).sql).map { it.groupValues[1].replace(Regex("""q\d+\."""), "q.") }.toList()
        assertEquals(
            listOf("q.c1, q.c1_identity", "q.c1, q.c1 AS c1_identity"),
            heads("MATCH (c:C) CALL (c) { RETURN {x: c} AS m UNION RETURN {x: {x: 1}} AS m } RETURN count(*)"),
        )
        // The first query's too, where it is the one without, and after the CALL the value is read by that identity.
        val first = postgres.compile("MATCH (c:C) CALL (c) { RETURN {x: 1} AS m UNION ALL RETURN {x: c} AS m } RETURN count(DISTINCT m)").sql
        assertTrue("CAST(q" in first && ".c1 AS jsonb) AS c1_identity" in first, first)
        assertTrue(Regex("""count\(DISTINCT NULLIF\(c\d+\.c1_identity""").containsMatchIn(first), first)
    }
}
