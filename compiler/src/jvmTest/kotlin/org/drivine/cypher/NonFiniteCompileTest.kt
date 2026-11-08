package org.drivine.cypher

import org.drivine.cypher.CompilerException
import org.drivine.cypher.sql.OracleCypherCompiler
import org.drivine.cypher.sql.PostgresCypherCompiler
import org.drivine.cypher.sql.SqliteCypherCompiler
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import java.math.BigDecimal
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * NaN and the infinities given to a compiled statement (#295): what each store binds and compiles, without a store. What they
 * give on each store is the spec's, in `cypher/queries.yaml` and `cypher/writes.yaml`.
 */
class NonFiniteCompileTest {

    private val statement = "RETURN [\$p = \$p, \$p > 1.0, \$p + 1, toString(\$p)]"

    @Test
    fun `a statement given no NaN or infinity compiles as it would without them`() {
        // Postgres and SQLite work NaN out only where the statement is given one, so no other statement grows.
        val finite = PostgresCypherCompiler("g").compile(statement, mapOf("p" to 1.5))
        assertFalse(finite.nonFinite)
        assertFalse(finite.sql.contains("2e309"), finite.sql)
        val sqlite = SqliteCypherCompiler("g").compile(statement, mapOf("p" to 1.5))
        assertFalse(sqlite.sql.contains("numberDouble"), sqlite.sql)
    }

    @Test
    fun `a statement that may work NaN or an infinity out is compiled as one given one, and any other as it was`() {
        // A float divided by zero or a text toFloat takes as NaN (#408, #545): decided as the statement is compiled.
        fun postgres(cypher: String, parameters: Map<String, Any?> = emptyMap()) = PostgresCypherCompiler("g").compile(cypher, parameters).nonFinite
        fun sqlite(cypher: String, parameters: Map<String, Any?> = emptyMap()) = SqliteCypherCompiler("g").compile(cypher, parameters).nonFinite
        listOf(
            "RETURN 0.0 / 0.0", "MATCH (n:A) RETURN n.x / n.y", "RETURN 5.0 % 0", "MATCH (n:A) WHERE n.x % n.y = 1 RETURN n",
            "UNWIND [1, 0] AS z RETURN [x IN [1.0] | x / z]", "MATCH (n:A) RETURN toFloat(n.s)", "RETURN toFloat(' NaN ')",
            "RETURN toFloat('-Infinity')", "CALL { MATCH (n:A) RETURN n.x / n.y AS q } RETURN q", "RETURN 1 AS x UNION RETURN 1.0 / 0 AS x",
        ).forEach { cypher ->
            assertTrue(postgres(cypher), cypher)
            assertTrue(sqlite(cypher), cypher)
        }
        assertTrue(postgres("RETURN 1.0 / \$z", mapOf("z" to 0)))
        assertTrue(postgres("RETURN toFloat(\$s)", mapOf("s" to "Infinity")))
        listOf(
            "RETURN 1.0 / 2", "RETURN 1.0 / -2.5", "RETURN toFloat('1.5')", "RETURN toFloat(2)",
            "RETURN toFloat(null)", "MATCH (n:A) WHERE n.x = 1 RETURN n.x * 2",
        ).forEach { cypher ->
            assertFalse(postgres(cypher), cypher)
            assertFalse(sqlite(cypher), cypher)
        }
        assertFalse(postgres("RETURN [1.0 / \$z, toFloat(\$s)]", mapOf("z" to 4, "s" to "1e3")))
        // `%` of a stored infinity is NaN, which SQLite, whose JSON holds one, works out (drivine4j#668): Postgres's holds none.
        assertFalse(postgres("MATCH (n:A) RETURN n.x / 3 + n.x % 2"))
        assertTrue(sqlite("MATCH (n:A) RETURN n.x / 3 + n.x % 2"))
        // A statement that writes on Postgres stores no such number, and fails as `numeric` does; SQLite stores an infinity.
        assertFalse(postgres("MATCH (n:A) SET n.q = n.x / n.y"))
        assertTrue(sqlite("MATCH (n:A) SET n.q = n.x / n.y"))
        // The SQL of a statement that works none out is as it was.
        val plain = PostgresCypherCompiler("g").compile("MATCH (n:A) WHERE n.x = \$x RETURN [n.x / 2, toFloat('3') > 1]", mapOf("x" to 1))
        assertFalse(plain.sql.contains("2e309"), plain.sql)
        // Oracle's floats hold them, so it compiles nothing more.
        assertFalse(OracleCypherCompiler("g").compile("RETURN 0.0 / 0.0").nonFinite)
    }

    @Test
    fun `count(DISTINCT …) and collect(DISTINCT …) keep NaN apart only where a value may be NaN, by its identity too`() {
        // drivine4j#558: what each gives is the spec's, in "NaN worked out"; here, where the test of NaN is compiled at all.
        val nan = mapOf("l" to listOf(Double.NaN, 1.0))
        fun counted(sql: String) = sql.contains("count(CASE WHEN")
        assertTrue(counted(PostgresCypherCompiler("g").compile("UNWIND \$l AS x RETURN count(DISTINCT x)", nan).sql))
        assertTrue(counted(SqliteCypherCompiler("g").compile("UNWIND \$l AS x RETURN count(DISTINCT x)", nan).sql))
        assertTrue(counted(OracleCypherCompiler("g").compile("UNWIND [0.0, 1.0] AS z RETURN count(DISTINCT z / z)").sql))
        // So is NaN worked out of an infinity Oracle stores, as ∞ - ∞ is.
        assertTrue(counted(OracleCypherCompiler("g").compile("MATCH (n:A) RETURN count(DISTINCT n.x - n.x)").sql))
        // Not where the statement works out none, nor of a value made a text, a list or a map: on Oracle, which may store NaN, too,
        // as testing each value read cost a count(DISTINCT …) 2.6 times as long.
        assertFalse(counted(OracleCypherCompiler("g").compile("MATCH (n:A) RETURN count(DISTINCT n.x)").sql))
        assertFalse(counted(PostgresCypherCompiler("g").compile("MATCH (n:A) RETURN count(DISTINCT n.x)").sql))
        assertFalse(counted(SqliteCypherCompiler("g").compile("MATCH (n:A) RETURN count(DISTINCT n.x)").sql))
        listOf("toString(x)", "[x]", "{a: x}").forEach { value ->
            val cypher = "UNWIND \$l AS x RETURN count(DISTINCT $value)"
            assertFalse(counted(PostgresCypherCompiler("g").compile(cypher, nan).sql), cypher)
        }
        // A value kept by its identity, as one read from a map that holds a node, keeps NaN apart as its identity does, so the two stay as long.
        val identified = OracleCypherCompiler("g").compile("UNWIND [0.0] AS z MATCH (p:A) WITH {p: p, x: z / z} AS m RETURN collect(DISTINCT m.x)").sql
        assertEquals(2, Regex("THEN e\\.item_no END").findAll(identified).count(), identified)
    }

    @Test
    fun `WITH DISTINCT and RETURN DISTINCT number a row apart only where an item may be NaN`() {
        // drivine4j#685: what each gives is the spec's, in "NaN worked out"; here, where the row's number is compiled at all.
        val nan = mapOf("l" to listOf(Double.NaN, 1.0))
        fun numbered(sql: String) = sql.contains(" AS d1")
        assertTrue(numbered(PostgresCypherCompiler("g").compile("UNWIND \$l AS x RETURN DISTINCT x", nan).sql))
        assertTrue(numbered(SqliteCypherCompiler("g").compile("UNWIND \$l AS x WITH DISTINCT x RETURN count(*)", nan).sql))
        assertTrue(numbered(OracleCypherCompiler("g").compile("UNWIND [0.0, 1.0] AS z RETURN DISTINCT z / z").sql))
        // Where the projection aggregates, by its group's value made apart (drivine4j#688): DISTINCT would take the groups as one.
        assertTrue(PostgresCypherCompiler("g").compile("UNWIND \$l AS x RETURN DISTINCT x, count(*)", nan).sql.contains("MIN(CASE WHEN"))
        // NaN worked out by % of a float zero, or by / of one beside the test for an integer zero (drivine4j#554, drivine4j#559).
        listOf(SqliteCypherCompiler("g"), OracleCypherCompiler("g")).forEach { compiler ->
            listOf("UNWIND [5.0, 5.0] AS x WITH DISTINCT x % 0.0 AS v RETURN count(*)", "UNWIND [0.0, 0.0] AS z RETURN DISTINCT 0.0 / z AS v").forEach { cypher ->
                assertTrue(numbered(compiler.compile(cypher, emptyMap()).sql), cypher)
            }
        }
        // Not where the statement works out none, nor of a value made a text or a list.
        listOf(PostgresCypherCompiler("g"), SqliteCypherCompiler("g"), OracleCypherCompiler("g")).forEach { compiler ->
            assertFalse(numbered(compiler.compile("MATCH (n:A) RETURN DISTINCT n.x").sql))
            assertFalse(numbered(compiler.compile("MATCH (n:A) RETURN DISTINCT n.x, count(*)").sql))
        }
        listOf("toString(x)", "[x]", "toString(x), count(*)").forEach { items ->
            val cypher = "UNWIND \$l AS x RETURN DISTINCT $items"
            assertFalse(numbered(PostgresCypherCompiler("g").compile(cypher, nan).sql), cypher)
        }
    }

    @Test
    fun `a grouping key and a UNION's row are made apart only where one may be NaN, by one value for the row`() {
        // drivine4j#688: what each gives is the spec's, in "NaN worked out"; here, where the value made apart is compiled at all.
        val nan = mapOf("l" to listOf(Double.NaN, 1.0))
        fun apart(sql: String) = Regex("CASE WHEN .* THEN (CAST\\(gen_random_uuid|drivine_uuid|LOWER\\(REGEXP_REPLACE\\(RAWTOHEX\\(UUID)").containsMatchIn(sql)
        val postgres = PostgresCypherCompiler("g").compile("UNWIND \$l AS x RETURN x, x + 1 AS y, count(*)", nan).sql
        assertEquals(1, Regex("gen_random_uuid").findAll(postgres).count(), postgres)
        assertTrue(apart(SqliteCypherCompiler("g").compile("UNWIND \$l AS x RETURN x, count(*)", nan).sql))
        assertTrue(apart(OracleCypherCompiler("g").compile("UNWIND [0.0, 1.0] AS z RETURN z / z, count(*)").sql))
        assertTrue(PostgresCypherCompiler("g").compile("RETURN \$p AS v UNION RETURN 1.0 AS v", mapOf("p" to Double.NaN)).sql.contains("THEN u.part"))
        // Not where the statement works out none, nor of a key made a text or a list, nor of an aggregate.
        listOf(PostgresCypherCompiler("g"), SqliteCypherCompiler("g"), OracleCypherCompiler("g")).forEach { compiler ->
            assertFalse(apart(compiler.compile("MATCH (n:A) RETURN n.x, count(*)").sql))
            assertFalse(compiler.compile("MATCH (n:A) RETURN n.x AS v UNION RETURN 1.0 AS v").sql.contains("THEN u.part"))
        }
        listOf("toString(x), count(*)", "[x], count(*)", "sum(x)").forEach { items ->
            val cypher = "UNWIND \$l AS x RETURN $items"
            assertFalse(apart(PostgresCypherCompiler("g").compile(cypher, nan).sql), cypher)
        }
    }

    @Test
    fun `NaN and the infinities are bound as each store holds them, alone and in a list or a map`() {
        val given = mapOf("p" to Double.NaN, "l" to listOf(1.0, Double.POSITIVE_INFINITY), "m" to mapOf("x" to Double.NEGATIVE_INFINITY))
        val cypher = "RETURN [\$p, \$l, \$m]"
        val postgres = PostgresCypherCompiler("g").compile(cypher, given)
        assertTrue(postgres.nonFinite)
        val nan = "2" + "0".repeat(309) + ".5"
        val infinity = "1" + "0".repeat(309) + ".5"
        assertEquals(listOf(nan, "[1.0,$infinity]", "{\"x\":-$infinity}"), postgres.parameters.values.toList())
        val sqlite = SqliteCypherCompiler("g").compile(cypher, given)
        assertEquals(listOf("{\"\$numberDouble\":\"NaN\"}", "[1.0,9e999]", "{\"x\":-9e999}"), sqlite.parameters.values.toList())
        val oracle = OracleCypherCompiler("g").compile(cypher, given)
        assertEquals(
            listOf("{\"\$numberDouble\":\"NaN\"}", "[{\"\$numberDouble\":\"1.0\"},{\"\$numberDouble\":\"Infinity\"}]", "{\"x\":{\"\$numberDouble\":\"-Infinity\"}}"),
            oracle.parameters.values.toList(),
        )
        assertTrue(oracle.sql.contains("EXTENDED"), oracle.sql)
    }

    @Test
    fun `a statement is given NaN or an infinity in a float, an array, a union and a write alike`() {
        // Each says so, as its rows are read by it.
        val postgres = PostgresCypherCompiler("g")
        assertTrue(postgres.compile("RETURN \$p", mapOf("p" to Float.NaN)).nonFinite)
        assertTrue(postgres.compile("RETURN \$p", mapOf("p" to floatArrayOf(1f, Float.POSITIVE_INFINITY))).nonFinite)
        assertTrue(postgres.compile("RETURN \$p", mapOf("p" to doubleArrayOf(Double.NEGATIVE_INFINITY))).nonFinite)
        assertTrue(postgres.compile("RETURN \$p", mapOf("p" to arrayOf<Any?>(mapOf("x" to Double.NaN)))).nonFinite)
        assertTrue(postgres.compile("RETURN \$p AS x UNION RETURN 1 AS x", mapOf("p" to Double.NaN)).nonFinite)
        assertFalse(postgres.compile("RETURN \$p AS x UNION RETURN 1 AS x", mapOf("p" to 1.5)).nonFinite)
        assertTrue(OracleCypherCompiler("g").compile("CREATE (n:A {x: \$p}) RETURN n.x", mapOf("p" to Double.NaN)).nonFinite)
        assertTrue(SqliteCypherCompiler("g").compile("CREATE (n:A {x: \$p}) RETURN n.x", mapOf("p" to Double.POSITIVE_INFINITY)).nonFinite)
    }

    @Test
    fun `on SQLite a number divided or taken % by an integer zero fails, where a divisor may be zero, and else compiles as before`() {
        // drivine4j#554: SQLite gave null where Neo4j fails. What each gives is the spec's, in queries.yaml ("NaN worked out").
        val sqlite = SqliteCypherCompiler("g")
        fun sql(cypher: String, parameters: Map<String, Any?> = emptyMap()) = sqlite.compile(cypher, parameters).sql
        val fails = "drivine_fail('/ by zero')"
        listOf(
            "RETURN 1 / 0" to emptyMap(), "RETURN 5 % 0" to emptyMap(), "RETURN \$x / \$z" to mapOf("x" to 1.0, "z" to 0L),
            "WITH 0 AS z RETURN 1.0 / z" to emptyMap(), "MATCH (n:A) RETURN n.x / n.y, n.x % n.y" to emptyMap(),
            // A divisor worked out as the statement runs, with no parameters.
            "RETURN 1 / size([])" to emptyMap(), "UNWIND [0] AS z RETURN 1 / z" to emptyMap(), "MATCH (n:A) RETURN count(*) / (count(*) - 1)" to emptyMap(),
            "UNWIND [1, 2] AS i RETURN sum(i / 0)" to emptyMap(), "UNWIND [1, 2] AS i RETURN i ORDER BY i % 0" to emptyMap(),
            // A write, which SQLite carries out as a procedure.
            "CREATE (w:W {a: \$x / \$z, b: \$x % \$z}) RETURN w.a" to mapOf("x" to 1L, "z" to 0L),
        ).forEach { (cypher, parameters) ->
            // The procedure's SQL is a text in it, its quotes doubled.
            val compiled = sql(cypher, parameters).replace("''", "'")
            assertTrue(fails in compiled, "$cypher: $compiled")
        }
        // Each divisor given a number not zero: no division may be by zero, so none is tested.
        listOf("RETURN [7 / 2, 7 % 2, \$x / \$y]", "UNWIND [1, 2] AS i RETURN i / 2, i % \$y").forEach { cypher ->
            val compiled = sql(cypher, mapOf("x" to 7L, "y" to 2L))
            assertFalse("drivine_fail" in compiled, "$cypher: $compiled")
        }
    }

    @Test
    fun `% on Oracle of a side that may be NaN or an infinity works it out as Java does, and of two written numbers as before`() {
        // drivine4j#683: a number taken % by an infinity is that number as a float, and NaN or an infinity taken % is NaN, where
        // MOD's NUMBERs, which hold neither, gave null. What each gives on Oracle is the spec's, in queries.yaml ("NaN worked out").
        val oracle = OracleCypherCompiler("g")
        listOf(
            "RETURN 5 % \$i" to mapOf("i" to Double.POSITIVE_INFINITY),
            "WITH toFloat('Infinity') AS i RETURN [7 % i, 7 % -i]" to emptyMap(),
            "MATCH (n:A) RETURN n.x % n.y" to emptyMap(),
            // A write, which Oracle compiles as a procedure.
            "CREATE (w:W {a: \$x % \$i}) RETURN w.a" to mapOf("x" to 5L, "i" to Double.POSITIVE_INFINITY),
        ).forEach { (cypher, parameters) ->
            val sql = oracle.compile(cypher, parameters).sql
            assertTrue("RETURNING BINARY_DOUBLE) IS INFINITE THEN CASE WHEN" in sql, "$cypher: the number taken is given back by an infinity: $sql")
            assertTrue("JSON_SCALAR(BINARY_DOUBLE_NAN)" in sql, "$cypher: $sql")
            assertTrue("MOD(" in sql, "$cypher: any other is taken % by MOD: $sql")
        }
        // Taken % by a number written, only the side read is tested: it may be a stored infinity (drivine4j#668).
        val written = oracle.compile("MATCH (n:A) RETURN n.x % 2").sql
        assertTrue("IS INFINITE))" in written, written)
        assertFalse("IS INFINITE THEN" in written, written)
        // Of two numbers written, neither is NaN or an infinity, so the SQL is MOD's alone, of NUMBERs or of BINARY_DOUBLEs.
        listOf("RETURN 5 % 2", "RETURN 5.5 % 2.0", "RETURN [7 % 3, 0 % 2.5]").forEach { cypher ->
            val sql = oracle.compile(cypher).sql
            assertTrue("MOD(" in sql, "$cypher: $sql")
            assertFalse("IS NAN" in sql || "IS INFINITE" in sql, "$cypher: $sql")
        }
    }

    @Test
    fun `% on Oracle fails by an integer zero, is NaN by a float zero, and is a float where either side is one`() {
        // drivine4j#550 and drivine4j#427: MOD gave the number taken by zero, and a whole remainder of floats as an integer. What
        // each gives on Oracle is the spec's, in queries.yaml ("NaN worked out" and "Remainder of floats").
        val oracle = OracleCypherCompiler("g")
        fun sql(cypher: String, parameters: Map<String, Any?> = emptyMap()) = oracle.compile(cypher, parameters).sql
        val byZero = "THEN TRUNC(1 / "
        val floats = "THEN BINARY_DOUBLE_NAN ELSE MOD("
        // An integer zero written: NUMBER's division by it, which fails as 1 / 0 does, and no MOD. A float written too, as
        // pi() is written, which Neo4j fails on where it gives NaN for 5.0 % 0 (drivine4j#686).
        listOf("RETURN 5 % 0", "MATCH (n:A) RETURN n.x % 0", "RETURN \$x % 0", "RETURN 5.0 % 0", "RETURN 0.0 % 0", "RETURN pi() % 0", "RETURN e() % 0").forEach { cypher ->
            val compiled = sql(cypher, mapOf("x" to 5.0))
            assertTrue(byZero in compiled, "$cypher: $compiled")
            assertFalse("MOD(" in compiled || "BINARY_DOUBLE_NAN" in compiled, "$cypher: $compiled")
        }
        // A divisor not written may be an integer zero, tested first, and either side may be a float, taken % as BINARY_DOUBLEs.
        listOf(
            "RETURN \$x % \$z" to mapOf("x" to 5L, "z" to 0L),
            "WITH 0 AS z RETURN 5.0 % z" to emptyMap(),
            "MATCH (n:A) RETURN n.x % n.y" to emptyMap(),
            // A write, which Oracle compiles as a procedure.
            "CREATE (w:W {a: \$x % \$z}) RETURN w.a" to mapOf("x" to 5.0, "z" to 0L),
        ).forEach { (cypher, parameters) ->
            val compiled = sql(cypher, parameters)
            assertTrue(byZero in compiled, "$cypher: $compiled")
            assertTrue(floats in compiled, "$cypher: $compiled")
        }
        // A divisor written, an integer not zero, isn't tested for either zero, and its double is written out.
        val written = sql("MATCH (n:A) RETURN n.x % 2")
        assertFalse(byZero in written || floats in written, written)
        assertTrue(", TO_BINARY_DOUBLE('2')))" in written, written)
        // Where a float is written, the remainder is a float whatever the other side: neither is tested for being an integer.
        listOf("RETURN 64.0 % 8.0", "RETURN 7 % 2.5", "MATCH (n:A) RETURN n.x % 2.5").forEach { cypher ->
            val compiled = sql(cypher)
            assertTrue(floats in compiled, "$cypher: $compiled")
            assertFalse("= TRUNC(" in compiled, "$cypher: $compiled")
        }
        // Of two integers written, the divisor not zero, MOD's of NUMBERs alone, as before.
        assertFalse("BINARY_DOUBLE" in sql("RETURN [7 % 3, 70 % 4]"))
    }

    @Test
    fun `a number divided by an integer zero fails on Oracle, a float too, and by any other number divides as before`() {
        // drivine4j#559: BINARY_DOUBLE's division gave a float divided by an integer zero as an infinity or NaN, where Neo4j fails at
        // run time. What each gives on Oracle is the spec's, in queries.yaml ("NaN worked out").
        val oracle = OracleCypherCompiler("g")
        fun sql(cypher: String, parameters: Map<String, Any?> = emptyMap()) = oracle.compile(cypher, parameters).sql
        val byZero = "THEN TRUNC(1 / "
        val divided = "= 'double' OR "
        // An integer zero written: NUMBER's division by it, which fails as 1 / 0 does, and no BINARY_DOUBLE's. A float written too,
        // as pi() is written, which Neo4j fails on where it gives an infinity for 1.0 / 0 (drivine4j#686).
        listOf("RETURN 1.0 / 0", "RETURN 0.0 / 0", "RETURN pi() / 0", "RETURN e() / 0", "MATCH (n:A) RETURN n.x / 0", "RETURN \$x / 0").forEach { cypher ->
            val compiled = sql(cypher, mapOf("x" to 3.0))
            assertTrue(byZero in compiled, "$cypher: $compiled")
            assertFalse(divided in compiled, "$cypher: $compiled")
        }
        // A divisor not written may be an integer zero, tested first, and else divides as before.
        listOf(
            "RETURN \$x / \$z" to mapOf("x" to 3.0, "z" to 0L),
            "WITH 0 AS z RETURN 1.0 / z" to emptyMap(),
            "MATCH (n:A) RETURN n.x / n.y" to emptyMap(),
            "RETURN 1.0 / (1 - 1)" to emptyMap(),
            // A dividend long enough to be bound binds the divisor beside it, which is read as any other there.
            "RETURN -1.0 / 0" to emptyMap(),
            // A write, which Oracle compiles as a procedure.
            "CREATE (w:W {a: \$x / \$z}) RETURN w.a" to mapOf("x" to 3.0, "z" to 0L),
        ).forEach { (cypher, parameters) ->
            val compiled = sql(cypher, parameters)
            assertTrue("= 0 THEN JSON_SCALAR(CASE WHEN" in compiled && byZero in compiled, "$cypher: $compiled")
            assertTrue(divided in compiled, "$cypher: $compiled")
        }
        // A divisor written as any other number, a float zero among them, isn't tested for a zero.
        listOf("MATCH (n:A) RETURN n.x / 2", "MATCH (n:A) RETURN n.x / 2.5", "RETURN 1.0 / 0.0", "MATCH (n:A) RETURN n.x / 0.0").forEach { cypher ->
            val compiled = sql(cypher)
            assertFalse(byZero in compiled, "$cypher: $compiled")
            assertTrue(divided in compiled, "$cypher: $compiled")
        }
        // The other operators are as before.
        listOf("MATCH (n:A) RETURN n.x * 0", "MATCH (n:A) RETURN n.x - 0").forEach { cypher -> assertFalse(byZero in sql(cypher), cypher) }
    }

    @Test
    fun `x IN a list given with NaN in it finds NaN equal to nothing on Oracle, and a list without compares plainly`() {
        // Oracle's JSON finds NaN equal to itself; a given list of numbers is compared plainly, as #458 compares it, only without NaN.
        val cypher = "MATCH (n:A) WHERE n.x IN \$l RETURN n.x"
        val nan = OracleCypherCompiler("g").compile(cypher, mapOf("l" to listOf(1.0, Double.NaN))).sql
        assertTrue(nan.contains("IS NAN"), nan)
        val finite = OracleCypherCompiler("g").compile(cypher, mapOf("l" to listOf(1.0, 2.0))).sql
        assertFalse(finite.contains("IS NAN"), finite)
    }

    @Test
    fun `a MERGE fails as it makes a property that is null, or NaN on Oracle, which holds NaN, and a CREATE does not`() {
        // #543: what each store gives is the spec's, whose stores wrap the message each in its own.
        val cypher = "MATCH (s:A) MERGE (s)-[:R {y: s.y}]->(:B {x: s.x})"
        val oracle = OracleCypherCompiler("g").compile(cypher, mapOf("p" to 1.5)).sql
        assertTrue(oracle.contains("Cannot merge the following node because of NaN property value for ''x''."), oracle)
        assertTrue(oracle.contains("Cannot merge the following relationship because of null property value for ''y''."), oracle)
        for (sql in listOf(PostgresCypherCompiler("g").compile(cypher, mapOf("p" to 1.5)).sql, SqliteCypherCompiler("g").compile(cypher, mapOf("p" to 1.5)).sql)) {
            assertTrue(sql.contains("because of null property value for"), sql)
            assertFalse(sql.contains("because of NaN property value"), sql)
        }
        assertFalse(OracleCypherCompiler("g").compile("CREATE (:B {x: \$p})", mapOf("p" to 1.5)).sql.contains("Cannot merge"))
    }

    @Test
    fun `a MERGE tests no property it writes, or is given as other than NaN, as it makes it`() {
        // Neither can be null or NaN, so the MERGE costs what it did before #543.
        val cypher = "MERGE (n:B {id: 1, l: [1], x: \$p})"
        for (compiler in listOf(OracleCypherCompiler("g"), PostgresCypherCompiler("g"), SqliteCypherCompiler("g"))) {
            val sql = compiler.compile(cypher, mapOf("p" to 1.5)).sql
            assertFalse(sql.contains("Cannot merge"), sql)
        }
        val nan = OracleCypherCompiler("g").compile(cypher, mapOf("p" to Double.NaN)).sql
        assertTrue(nan.contains("Cannot merge the following node because of NaN property value for ''x''."), nan)
        assertFalse(nan.contains("value for ''id''"), nan)
        val unbound = OracleCypherCompiler("g").compile(cypher, mapOf("p" to null)).sql
        assertTrue(unbound.contains("Cannot merge the following node because of null property value for ''x''."), unbound)
    }

    @Test
    fun `the numbers Postgres writes for NaN and the infinities are read back as them`() {
        assertTrue(PostgresCypherCompiler.read(com.ionspin.kotlin.bignum.decimal.BigDecimal.parseString("2e309").add(com.ionspin.kotlin.bignum.decimal.BigDecimal.parseString("0.5"))).isNaN())
        assertEquals(Double.POSITIVE_INFINITY, PostgresCypherCompiler.read(com.ionspin.kotlin.bignum.decimal.BigDecimal.parseString("1e309").add(com.ionspin.kotlin.bignum.decimal.BigDecimal.parseString("0.5"))))
        assertEquals(Double.NEGATIVE_INFINITY, PostgresCypherCompiler.read(com.ionspin.kotlin.bignum.decimal.BigDecimal.parseString("-1e309").subtract(com.ionspin.kotlin.bignum.decimal.BigDecimal.parseString("0.5"))))
        // SQLite's number for an infinity, read in the same rows.
        assertEquals(Double.POSITIVE_INFINITY, PostgresCypherCompiler.read(com.ionspin.kotlin.bignum.decimal.BigDecimal.parseString("9.0e+999")))
        assertEquals(1.5, PostgresCypherCompiler.read(com.ionspin.kotlin.bignum.decimal.BigDecimal.parseString("1.5")))
    }

    @Test
    fun `a statement that writes NaN is refused where the store's JSON holds none`() {
        val nan = mapOf("p" to Double.NaN)
        val infinity = mapOf("p" to Double.POSITIVE_INFINITY)
        assertThrows<CompilerException> { PostgresCypherCompiler("g").compile("CREATE (:A {x: \$p})", nan) }
        assertThrows<CompilerException> { PostgresCypherCompiler("g").compile("CREATE (:A {x: \$p})", infinity) }
        assertThrows<CompilerException> { SqliteCypherCompiler("g").compile("CREATE (:A {x: [\$p]})", nan) }
        // SQLite holds an infinity, and Oracle both.
        SqliteCypherCompiler("g").compile("CREATE (:A {x: \$p})", infinity)
        OracleCypherCompiler("g").compile("CREATE (:A {x: \$p})", nan)
    }
}
