package org.drivine.cypher

import org.drivine.cypher.sql.PostgresCypherCompiler
import org.junit.jupiter.api.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * A failure by name written of constants alone reads the value it fails for too, on Postgres: its planner works out a stable
 * function of constants in a `CASE` it estimates, a relationship's properties matched against `1 / n.i`, before any row, so
 * every such `MERGE` and `OPTIONAL MATCH` failed as a truth value beside a number (drivine-cypher-spec#399). What the stores
 * give is the spec's, in `writes.yaml`, "Arithmetic of a stored value in the properties of a relationship matched".
 */
class PostgresFailureOfRowTest {

    private fun sql(cypher: String) = PostgresCypherCompiler("g").compile(cypher).sql

    /** A failure of constants alone: a cast of a `concat` of texts written, or of the kind of a value written, with nothing read. */
    private val constant = Regex("""CAST\(concat\('[^']*', ('[^']*'|jsonb_typeof\(CAST\('[^']*' AS jsonb\)\))\) AS integer\)""")

    @Test
    fun `a failure in a relationship's properties matched reads the value it fails for`() {
        for (cypher in listOf(
            "MATCH (n:A) MERGE (n)-[r:R {w: 1 / n.i}]->(n) RETURN r.w",
            "MATCH (n:A) MERGE (n)-[r:R {w: n.i + 1, v: n.i * 3}]->(n) RETURN r.w",
            "MATCH (n:A) OPTIONAL MATCH (n)-[r:R {w: 1 / n.i}]->(n) RETURN r.w",
            "MATCH (n:A) MERGE (n)-[r:R {d: date(n.s)}]->(n) RETURN r.d",
            "MATCH (n:A) RETURN toString([n.l])",
            "MATCH (n:A) RETURN sum(n.x)",
            "MATCH (n:A) MERGE (n)-[r:R {w: n.l[0]}]->(n) RETURN r.w",
            "MATCH (n:A) OPTIONAL MATCH (n)-[r:R {w: round(n.x, -1)}]->(n) RETURN r.w",
        )) {
            val sql = sql(cypher)
            assertTrue(sql.contains("left(CAST("), "$cypher\n$sql")
            assertFalse(constant.containsMatchIn(sql), "$cypher\n$sql")
        }
    }
}
