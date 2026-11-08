package org.drivine.cypher

import org.drivine.cypher.sql.OracleCypherCompiler
import org.drivine.cypher.sql.PostgresCypherCompiler
import org.drivine.cypher.sql.SqliteCypherCompiler
import org.junit.jupiter.api.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * drivine4j#668: on SQLite, whose JSON holds an infinity, a statement given no NaN or infinity may work NaN out of infinities
 * it reads (`n.b + n.c[0]`, with ∞ and -∞ stored), and is compiled as one given NaN, so its NaN is the float it is, not a map.
 * What it gives is the spec's, in `writes.yaml` ("NaN worked out from stored infinities", liberation-data/drivine-cypher-spec#70),
 * which drivine4j runs on each store: this is what each store compiles, without one.
 */
class StoredNonFiniteTest {

    private fun sqlite(cypher: String, parameters: Map<String, Any?> = emptyMap()) = SqliteCypherCompiler("g").compile(cypher, parameters)

    /** The spec case's statements, each but the first, which writes the infinities. */
    private val specCase = listOf(
        "MATCH (n:CyWrite) WITH n.b + n.c[0] AS x RETURN [x, x = x, x <> x, x > 1.0, x IN [x], [x] = [x], {k: x} = {k: x}, [x, 1] < [x, 2], [1, x] < [2, x]]",
        "MATCH (n:CyWrite) WITH n, n.b + n.c[0] AS x RETURN [x + 1, n.s + x, x + n.s, n.b + 1, [x] + x, toString(x), toString(n.b)]",
        "MATCH (n:CyWrite) WITH n.b + n.c[0] AS x RETURN keys(x)",
        "MATCH (n:CyWrite) UNWIND [n.b + n.c[0], n.b, 1.0, n.c[0]] AS x RETURN x ORDER BY x",
        "MATCH (n:CyWrite) UNWIND [[n.b + n.c[0]], [n.b], [1.0], [n.c[0]], [1.0, n.b + n.c[0]]] AS l RETURN l ORDER BY l",
        "MATCH (n:CyWrite) UNWIND [n.b + n.c[0], 1.0, 2.0] AS x RETURN [min(x), max(x), sum(x), count(x)]",
        "MATCH (n:CyWrite) SET n.x = n.b + n.c[0] RETURN [n.x, n.x = n.x]",
        "MATCH (n:CyWrite) SET n.x = n.b + n.c[0]",
        "MATCH (n:CyWrite) CREATE (:CyWrite {l: [n.b + n.c[0]]})",
    )

    @Test
    fun `the spec case's statements are compiled on SQLite as ones given NaN, and on Postgres and Oracle as they were`() {
        specCase.forEach { cypher ->
            assertTrue(sqlite(cypher).nonFinite, cypher)
            // Postgres stores no infinity, and Oracle's floats hold NaN: neither compiles anything more.
            assertFalse(PostgresCypherCompiler("g").compile(cypher).nonFinite, cypher)
            assertFalse(OracleCypherCompiler("g").compile(cypher).nonFinite, cypher)
        }
    }

    @Test
    fun `NaN worked out of stored infinities is guarded where the spec case's steps failed`() {
        // The test for NaN, as the statement writes it.
        val nan = "'\$.\"\$numberDouble\"') = 'NaN'"
        fun guarded(cypher: String, guard: String) {
            val sql = sqlite(cypher).sql
            assertTrue(guard in sql, "$cypher: $sql")
            assertTrue(nan in sql, "$cypher: $sql")
        }
        // `x > 1.0`: false where either is NaN, not null.
        guarded("MATCH (n:A) WITH n.b + n.c[0] AS x RETURN x > 1.0", "'NaN') THEN 0 ELSE ((")
        // toString(x): "NaN", not the map's text.
        guarded("MATCH (n:A) WITH n.b + n.c[0] AS x RETURN toString(x)", "THEN '\"NaN\"' ELSE")
        // keys(x): NaN is a number, so keys() fails.
        guarded("MATCH (n:A) WITH n.b + n.c[0] AS x RETURN keys(x)", "THEN 'number' ELSE")
        // ORDER BY x: NaN ranks with the numbers, and after them.
        guarded("MATCH (n:A) UNWIND [n.b + n.c[0], 1.0] AS x RETURN x ORDER BY x", "THEN '5' ELSE")
        // min, max and sum: NaN a number, and NaN in the group makes the sum NaN.
        guarded("MATCH (n:A) UNWIND [n.b + n.c[0], 1.0] AS x RETURN [min(x), max(x), sum(x)]", "'NaN')) = 1 OR")
        // `∞ - ∞`, which SQLite gives as null: NaN.
        guarded("MATCH (n:A) RETURN n.b - n.c", "THEN '{\"\$numberDouble\":\"NaN\"}'")
    }

    @Test
    fun `a statement that may work NaN out of an infinity it reads is found, and one that can't is compiled as it was`() {
        listOf(
            "MATCH (n:A) RETURN n.a + n.b", "MATCH (n:A) RETURN n.a - n.b", "MATCH (n:A) RETURN n.a * n.b", "MATCH (n:A) RETURN n.a * 0",
            "MATCH (n:A) RETURN n.a % 2", "MATCH (n:A) RETURN sum(n.a)", "MATCH (n:A) RETURN avg(n.a)", "MATCH (n:A) WITH n.a AS x RETURN x + x",
            "MATCH (n:A) UNWIND [n.a, n.b] AS x RETURN x - x", "MATCH (n:A) RETURN reduce(t = 0.0, x IN n.l | t + x)",
            "MATCH (n:A) RETURN [x IN n.l | x * n.b]", "MATCH (n:A) RETURN (n.a + 1) - n.b",
            "MATCH (n:A) WHERE n.a - n.b > 0 RETURN n", "MATCH (n:A) RETURN n ORDER BY n.a + n.b", "MATCH (n:A) SET n.x = n.a + n.b",
            "MATCH (n:A) CALL (n) { RETURN n.a AS a } RETURN a - n.b",
            // A function, a branch, a map's value or a list's item may be an infinity read.
            "MATCH (n:A) RETURN abs(n.a) - n.b", "MATCH (n:A) RETURN coalesce(n.a, 0) - n.b", "MATCH (n:A) RETURN head(collect(n.a)) - last(collect(n.b))",
            "MATCH (n:A) RETURN CASE WHEN n.k THEN n.a ELSE 0 END - n.b", "MATCH (n:A) WITH {a: n.a} AS m RETURN m.a - m.a",
            "MATCH (n:A) RETURN reduce(t = 0.0, x IN collect(n.a) | t + x)", "MATCH (n:A) FOREACH (x IN [n.a, n.b] | SET n.y = x - x)",
            "MATCH (n:A) RETURN any(x IN [n.a, n.b] WHERE x - x = 0)", "RETURN 1.0 AS v UNION MATCH (n:A) RETURN n.a - n.b AS v",
            // A value negated, divided or raised may be an infinity still, as may an item a comprehension makes.
            "MATCH (n:A) RETURN -n.a - n.b", "MATCH (n:A) RETURN n.a / 2 - n.b / 2", "MATCH (n:A) RETURN n.a ^ 2 - n.b ^ 2",
            "MATCH (n:A) UNWIND [x IN n.l | x * 2] AS y RETURN y - y",
        ).forEach { cypher ->
            assertTrue(sqlite(cypher).nonFinite, cypher)
            assertFalse(PostgresCypherCompiler("g").compile(cypher).nonFinite, cypher)
        }
        // An operand given finite, no number, or a function whose value is no infinity, makes no NaN.
        listOf(
            "MATCH (n:A) RETURN n.a + 1", "MATCH (n:A) RETURN n.a - \$p", "MATCH (n:A) RETURN n.a * 2", "MATCH (n:A) RETURN n.a * -1.5",
            "MATCH (n:A) RETURN n.first + ' ' + n.last", "MATCH (n:A) RETURN n.s + toString(n.a)", "MATCH (n:A) RETURN n.l + [1]",
            "MATCH (n:A) WITH n.a AS a, count(*) AS c RETURN c + a", "MATCH (n:A) RETURN size(n.l) - n.a", "MATCH (n:A) RETURN n.day + duration('P1D')",
            "UNWIND [1, 2.5] AS x RETURN sum(x)", "UNWIND \$xs AS x RETURN avg(x)", "RETURN reduce(t = 0, x IN [1, 2] | t + x)",
            "WITH 1.5 AS a RETURN a - a", "MATCH (n:A) RETURN [x IN range(1, 3) | x - n.a]",
            "MATCH (n:A) RETURN min(n.a), max(n.a)", "MATCH (n:A) RETURN round(n.a) > 1", "MATCH (n:A) RETURN any(x IN [1.0, 2.0] WHERE x - x = 0)",
            "UNWIND [x IN range(1, 3) | x * 2] AS y RETURN y - y",
            // drivine_math fails where its value is not finite.
            "MATCH (n:A) RETURN exp(n.a) - log(n.b)",
        ).forEach { cypher ->
            val compiled = sqlite(cypher, mapOf("p" to 3, "xs" to listOf(1.0, 2.0)))
            assertFalse(compiled.nonFinite, cypher)
            // No value is tested for NaN (a sum gives NaN of both infinities whatever it is given, as before).
            assertFalse("'\$.\"\$numberDouble\"')" in compiled.sql, "$cypher: ${compiled.sql}")
        }
    }

    @Test
    fun `a name is an infinity where any of its bindings may be one`() {
        // x is bound to a literal in one place and to a stored value in another.
        assertTrue(sqlite("UNWIND [1.0] AS x WITH x MATCH (n:A) WITH n.a AS x RETURN x - x").nonFinite)
        // A comprehension's variable named as an outer one is an infinity where either is.
        assertTrue(sqlite("MATCH (n:A) WITH n.a AS x RETURN [x IN [1, 2] | x - x]").nonFinite)
        assertTrue(sqlite("MATCH (n:A) WITH n, 1.0 AS x RETURN [x IN [n.a, n.b] | x - x]").nonFinite)
        // A reduce's accumulator is what its expression makes it.
        assertTrue(sqlite("MATCH (n:A) RETURN reduce(t = 0, x IN [1, 2] | t + n.a) - n.b").nonFinite)
        assertFalse(sqlite("MATCH (n:A) RETURN reduce(t = 0, x IN [1, 2] | t + x) - n.b").nonFinite)
    }
}
