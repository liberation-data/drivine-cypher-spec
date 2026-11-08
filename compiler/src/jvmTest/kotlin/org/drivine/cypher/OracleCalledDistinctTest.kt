package org.drivine.cypher

import org.drivine.cypher.sql.OracleCypherCompiler
import org.drivine.cypher.sql.PostgresCypherCompiler
import org.drivine.cypher.sql.SqliteCypherCompiler
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * On Oracle, a `CALL { … }` query that keeps its rows distinct is not decorrelated (`NO_DECORRELATE`): so rewritten, a value of
 * the row's own `UNWIND` it kept distinct was read as null (drivine4j#502). What the stores give is the spec's, in
 * `queries.yaml` ("DISTINCT in a CALL").
 */
class OracleCalledDistinctTest {

    /** The hints of each `CALL { … }` query of [sql], the outermost first. */
    private fun calledHints(sql: String) = Regex("""LATERAL \(\n\s*SELECT /\*\+ (.*?) \*/ \* FROM \(""").findAll(sql).map { it.groupValues[1] }.toList()

    @Test
    fun `a CALL that keeps its rows distinct is not decorrelated, one in it too`() {
        listOf(
            "UNWIND [1, 1, 2] AS i CALL (i) { WITH DISTINCT i RETURN i AS j } RETURN i, j" to listOf("NO_MERGE NO_DECORRELATE"),
            "UNWIND [1, 1, 2] AS i CALL (i) { RETURN DISTINCT i AS j } RETURN i, j" to listOf("NO_MERGE NO_DECORRELATE"),
            "UNWIND [1, 1, 2] AS i OPTIONAL CALL (i) { UNWIND [i, i] AS y WITH DISTINCT y RETURN y AS j } RETURN i, j" to listOf("NO_MERGE NO_DECORRELATE"),
            // Its `SELECT DISTINCT` holds a hint of its own: `SELECT /*+ CARDINALITY(u2 10) */ DISTINCT`.
            "UNWIND [1, 1, 2] AS i CALL (i) { UNWIND [i, i] AS y MATCH (n:Person) WHERE id(n) = y RETURN DISTINCT y AS j } RETURN i, j" to
                listOf("NO_MERGE NO_DECORRELATE"),
            "UNWIND [1, 1, 2] AS i CALL (i) { CALL (i) { WITH DISTINCT i RETURN i AS j } RETURN j } RETURN i, j" to
                listOf("NO_MERGE NO_DECORRELATE", "NO_MERGE NO_DECORRELATE"),
            "UNWIND [1, 1, 2] AS i CALL (i) { CALL (i) { WITH DISTINCT i RETURN i AS j } RETURN j } CALL (i) { RETURN i AS k } RETURN i, j, k" to
                listOf("NO_MERGE NO_DECORRELATE", "NO_MERGE NO_DECORRELATE", "NO_MERGE"),
            "UNWIND [1, 1, 2] AS i CALL (i) { WITH DISTINCT i RETURN i AS j UNION WITH DISTINCT i RETURN i * 10 AS j } RETURN i, j" to
                listOf("NO_MERGE NO_DECORRELATE"),
        ).forEach { (cypher, hints) -> assertEquals(hints, calledHints(OracleCypherCompiler("g").compile(cypher).sql), cypher) }
    }

    @Test
    fun `a CALL that keeps a row whose item may be NaN apart is not decorrelated either`() {
        // drivine4j#685 numbers such a row apart beside its SELECT DISTINCT: the hint is kept.
        val nan = mapOf("p" to Double.NaN)
        listOf(
            "UNWIND [\$p, \$p, 1.0] AS i CALL (i) { WITH DISTINCT i RETURN i AS j } RETURN i, j",
            "UNWIND [0.0, 0.0, 1.0] AS z CALL (z) { RETURN DISTINCT z / z AS v } RETURN z, v",
            "UNWIND [0.0, 1.0] AS z CALL (z) { UNWIND [z / z, z / z] AS y WITH DISTINCT y RETURN count(*) AS c } RETURN z, c",
        ).forEach { cypher ->
            val sql = OracleCypherCompiler("g").compile(cypher, nan).sql
            assertTrue(" AS d1" in sql, cypher)
            assertEquals(listOf("NO_MERGE NO_DECORRELATE"), calledHints(sql), cypher)
            // Whether the row is NaN binds no value in a subquery: one so bound beside the DISTINCT read the outer row's
            // values as null on Oracle, hinted or not, as `RETURN DISTINCT z / z AS v` did.
            assertFalse(Regex("""\) sh\d+\)""").containsMatchIn(sql.lines().single { " AS d1" in it }), cypher)
        }
    }

    @Test
    fun `a CALL that keeps nothing distinct, or only what it counts, is decorrelated as before`() {
        listOf(
            "UNWIND [1, 1, 2] AS i CALL (i) { WITH i AS v RETURN v AS j } RETURN i, j",
            "UNWIND [1, 1, 2] AS i CALL (i) { RETURN count(DISTINCT i) AS j } RETURN i, j",
            "MATCH (p:Person) CALL (p) { MATCH (p)-[:KNOWS]->(f) RETURN f.name AS friend } RETURN p.name, friend",
        ).forEach { cypher -> assertEquals(listOf("NO_MERGE"), calledHints(OracleCypherCompiler("g").compile(cypher).sql), cypher) }
    }

    @Test
    fun `Postgres and SQLite give no hint`() {
        val cypher = "UNWIND [1, 1, 2] AS i CALL (i) { WITH DISTINCT i RETURN i AS j } RETURN i, j"
        listOf(PostgresCypherCompiler("g"), SqliteCypherCompiler("g")).forEach { compiler ->
            assertFalse("NO_DECORRELATE" in compiler.compile(cypher).sql, compiler::class.simpleName)
        }
    }
}
