package org.drivine.cypher

import org.drivine.cypher.sql.OracleCypherCompiler
import org.drivine.cypher.sql.PostgresCypherCompiler
import org.drivine.cypher.sql.SqliteCypherCompiler
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * A statement's `UNION`, and a subquery's, gives each query's result as one type: on Postgres a list or a map one query builds
 * is `json` and a number another gives `jsonb`, so each is written as `json`, or where the rows are compared as `jsonb`
 * (drivine4j#679, as drivine4j#516 for a `UNION` in `CALL { … }`). What the stores give is the spec's, in `queries.yaml` ("A null in a map and a missing key are one null").
 */
class StatementUnionTypesTest {

    @Test
    fun `on Postgres, each query of a statement's UNION ALL gives its result as json, and of a UNION as it compares`() {
        val all = PostgresCypherCompiler("g").compile("RETURN [10] AS v UNION ALL RETURN 10 AS v").sql
        assertEquals(2, Regex("""CAST\(q\.result AS json\) AS result""").findAll(all).count(), all)
        val distinct = PostgresCypherCompiler("g").compile("RETURN [10] AS v UNION RETURN 10 AS v").sql
        assertEquals(2, Regex("""CAST\(q\.result AS jsonb\) AS result""").findAll(distinct).count(), distinct)
        // Compared as each query gives it, not cast to json and back.
        assertTrue("GROUP BY u.result" in distinct, distinct)
    }

    @Test
    fun `on Postgres, each query of a subquery's UNION ALL gives its result as json, and of a UNION as it compares`() {
        val all = PostgresCypherCompiler("g").compile("MATCH (p) WHERE EXISTS { RETURN [1] AS v UNION ALL RETURN 1 AS v } RETURN p").sql
        assertEquals(2, Regex("""CAST\(q\.result AS json\) AS result""").findAll(all).count(), all)
        val distinct = PostgresCypherCompiler("g").compile("MATCH (p) WHERE EXISTS { RETURN [1] AS v UNION RETURN 1 AS v } RETURN p").sql
        assertEquals(2, Regex("""CAST\(q\.result AS jsonb\) AS result""").findAll(distinct).count(), distinct)
    }

    @Test
    fun `on Oracle and SQLite, whose JSON is of one type, each query's result is read as it is`() {
        listOf(OracleCypherCompiler("g"), SqliteCypherCompiler("g")).forEach { compiler ->
            val sql = compiler.compile("RETURN [10] AS v UNION ALL RETURN 10 AS v").sql
            assertTrue("q.result AS result" in sql, "${compiler::class.simpleName}: $sql")
            assertFalse("CAST(q.result" in sql, "${compiler::class.simpleName}: $sql")
        }
    }
}
