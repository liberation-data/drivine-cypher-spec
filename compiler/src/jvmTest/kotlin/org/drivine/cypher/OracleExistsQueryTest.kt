package org.drivine.cypher

import org.drivine.cypher.sql.OracleCypherCompiler
import org.drivine.cypher.sql.PostgresCypherCompiler
import org.drivine.cypher.sql.SqliteCypherCompiler
import org.junit.jupiter.api.Test
import kotlin.test.assertTrue

/**
 * On Oracle, the query of an `EXISTS { … }` is read from a derived table, as a `COUNT { … }`'s is: Oracle refuses an `ORDER BY`
 * that ends the query of an `EXISTS` (ORA-00907), and one whose body unwinds a list or sorts its rows ends in one
 * (drivine4j#449). A body of a `UNION` is read the same way. The spec's "EXISTS { } over UNWIND" case runs them on each store. Postgres and SQLite read the query as it is.
 */
class OracleExistsQueryTest {

    private val statements = listOf(
        "RETURN EXISTS { UNWIND [1, 2, 3] AS j RETURN j }",
        "MATCH (p:Person) WHERE EXISTS { UNWIND keys(p) AS k WITH k WHERE k = 'born' RETURN k } RETURN p.name",
        "RETURN EXISTS { UNWIND [1, 2, 3] AS j RETURN j ORDER BY j }",
        "UNWIND [1, 2, 3] AS o WITH o WHERE NOT EXISTS { UNWIND [1, 2] AS j WITH j WHERE j = o RETURN j } RETURN o",
        "UNWIND [1, 2, 3] AS o RETURN o, EXISTS { UNWIND range(1, o) AS j WITH j WHERE EXISTS { UNWIND [2, 3] AS k WITH k WHERE k = j RETURN k } RETURN j }",
        "UNWIND [1, 2, 3] AS o RETURN o, EXISTS { UNWIND [1] AS j WITH j WHERE j = o RETURN j UNION UNWIND [3] AS j WITH j WHERE j = o RETURN j }",
        "UNWIND [1, 2, 3] AS o CALL (o) { RETURN EXISTS { UNWIND [1, 2] AS j WITH j WHERE j = o RETURN j } AS e } RETURN o, e",
    )

    /** Each `EXISTS (` of [sql] that opens a subquery, and the text that follows it. */
    private fun existsBodies(sql: String) = Regex("""EXISTS \(\n(\s*)(\S[^\n]*)""").findAll(sql).map { it.groupValues[2] }.toList()

    @Test
    fun `each EXISTS query is read from a derived table on Oracle`() {
        for (cypher in statements) {
            val sql = OracleCypherCompiler("g").compile(cypher).sql
            val bodies = existsBodies(sql)
            assertTrue(bodies.isNotEmpty() && bodies.all { it == "SELECT 1 FROM (" }, sql)
        }
    }

    @Test
    fun `an ORDER BY that a LIMIT reads is kept beside it inside the derived table`() {
        val sql = OracleCypherCompiler("g").compile(
            "UNWIND [1, 2, 3] AS o RETURN o, EXISTS { UNWIND [3, 1, 2] AS j WITH j ORDER BY j LIMIT 1 WITH j WHERE j = o RETURN j }"
        ).sql
        val inside = sql.substring(sql.indexOf("SELECT 1 FROM ("))
        assertTrue(Regex("""ORDER BY [^\n]*\n\s*FETCH NEXT 1 ROWS ONLY""").containsMatchIn(inside), sql)
    }

    @Test
    fun `Postgres and SQLite read the EXISTS query as it is`() {
        for (cypher in statements) {
            for (sql in listOf(PostgresCypherCompiler("g").compile(cypher).sql, SqliteCypherCompiler("g").compile(cypher).sql)) {
                val bodies = existsBodies(sql)
                assertTrue(bodies.isNotEmpty() && bodies.none { it.startsWith("SELECT 1 FROM (") }, sql)
            }
        }
    }
}
