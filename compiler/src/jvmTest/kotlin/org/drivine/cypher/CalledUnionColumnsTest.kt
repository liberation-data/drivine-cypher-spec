package org.drivine.cypher

import org.drivine.cypher.sql.OracleCypherCompiler
import org.drivine.cypher.sql.PostgresCypherCompiler
import org.drivine.cypher.sql.SqliteCypherCompiler
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * The queries of a `UNION` in `CALL { … }` give it columns of one number and one type: each query's own columns, not what it
 * orders its rows by too (drivine4j#506), and on Postgres, a `jsonb` one query gives is `json` where another query builds a list
 * or a map as `json` (drivine4j#516). What the stores give is the spec's, in `queries.yaml` (a UNION ALL in a CALL).
 */
class CalledUnionColumnsTest {

    private val compilers = listOf(PostgresCypherCompiler("g"), OracleCypherCompiler("g"), SqliteCypherCompiler("g"))

    /** The select list each query of the statement's one `UNION` in a `CALL` is read with, in order. */
    private fun partHeads(sql: String): List<String> =
        Regex("""SELECT (.*?) FROM \(\n""").findAll(sql).map { it.groupValues[1] }.filter { it.matches(Regex("""q\d+\..*|CAST\(q\d+\..*""")) }.toList()

    @Test
    fun `each query of a UNION in a CALL gives only its own columns, where one also orders its rows by an UNWIND's places`() {
        val cypher = "CALL { UNWIND [{a: null}] AS x RETURN x.a AS v UNION ALL " +
            "CALL { UNWIND [{a: null}] AS y RETURN y.a AS w UNION UNWIND [{b: 1}] AS z RETURN z.a AS w } RETURN w AS v } RETURN v, count(*)"
        compilers.forEach { compiler ->
            val heads = partHeads(compiler.compile(cypher).sql)
            assertTrue(heads.size >= 2, "${compiler::class.simpleName}: $heads")
            heads.forEach { head -> assertEquals(1, head.split(", ").size, "${compiler::class.simpleName}: $head") }
        }
    }

    @Test
    fun `on Postgres, a jsonb one query gives is cast to json where another query builds a list or a map`() {
        val postgres = PostgresCypherCompiler("g")
        listOf("[10]", "{b: 1, a: 2}").forEach { built ->
            val heads = partHeads(postgres.compile("CALL { RETURN $built AS v UNION ALL RETURN 10 AS v } RETURN v").sql)
            assertEquals(listOf(false, true), heads.map { it.startsWith("CAST(") && it.endsWith(" AS json) AS c1") }, "$built: $heads")
        }
    }

    @Test
    fun `on Postgres, what a UNION in a CALL gives as json where any query builds it is compared as jsonb after`() {
        val sql = PostgresCypherCompiler("g").compile("CALL { RETURN 10 AS v UNION ALL RETURN {b: 1, a: 2} AS v } RETURN DISTINCT v").sql
        assertTrue(Regex("""CAST\(c\d+\.c1 AS jsonb\)""").containsMatchIn(sql), sql)
    }

    @Test
    fun `on SQLite, whose JSON is text, a map one query builds beside a number is read as it is`() {
        val sql = SqliteCypherCompiler("g").compile("CALL { RETURN {b: 1, a: 2} AS v UNION ALL RETURN 10 AS v } RETURN v").sql
        assertEquals(listOf("q", "q"), partHeads(sql).map { it.take(1) }, sql)
        assertFalse(sql.contains(Regex("""drivine_canonical\(q\d+""")), sql)
    }

    @Test
    fun `on Postgres, queries that all build json are left json, which keeps a map's keys in their order`() {
        val sql = PostgresCypherCompiler("g").compile("CALL { RETURN {b: 1, a: 2} AS v UNION ALL RETURN [1] AS v } RETURN v").sql
        assertFalse(partHeads(sql).any { it.startsWith("CAST(") }, sql)
    }

    @Test
    fun `on Oracle, whose JSON is of one type, the queries' columns are read as they are`() {
        val sql = OracleCypherCompiler("g").compile("CALL { RETURN [10] AS v UNION ALL RETURN 10 AS v } RETURN v").sql
        assertEquals(listOf(false, false), partHeads(sql).map { it.startsWith("CAST(") || it.startsWith("JSON") }, sql)
    }
}
