package org.drivine.cypher

import org.drivine.cypher.json.TemporalValue
import org.drivine.cypher.sql.OracleCypherCompiler
import org.drivine.cypher.sql.PostgresCypherCompiler
import org.drivine.cypher.sql.SqlCypherCompiler
import org.drivine.cypher.sql.SqliteCypherCompiler
import org.drivine.cypher.sql.Temporal
import org.drivine.cypher.sql.TemporalArithmeticFunction
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * drivine4j#418: `+`, `-`, `*` and `/` of a temporal value whose kind the statement doesn't know, a tag read from the store, is
 * worked out as the statement runs by the store's function of temporal arithmetic on Postgres and Oracle, and by the host
 * function on SQLite, where the generic arithmetic gave null; on an Oracle store without the function, it fails by name. What each store gives is the spec's, in `temporal.yaml` ("Arithmetic on
 * temporal values read from the store"): this is what each is given.
 */
class TemporalArithmeticStoredTest {

    private val compilers = listOf(PostgresCypherCompiler("g"), OracleCypherCompiler("g"), SqliteCypherCompiler("g"))

    private val refusal = "compiled to SQL yet (drivine4j#418)"

    /** An Oracle store without the function of temporal arithmetic, as one made by an earlier version is. */
    private val refusing = OracleCypherCompiler("g", true, true, emptySet(), null, false, false, false, false)

    /** Whether [sql] tests a side for a tag: it calls the store's function there, or fails by name. */
    private fun tested(sql: String) = "_temporal_arith('" in sql || "drivine_temporal_arithmetic('" in sql || refusal in sql

    /**
     * The whole rule, by the form of a side (`tagFree`): each form, beside a number in `form - 1`, and whether it is tested for a
     * tag as the statement runs. The forms skipped are a closed set; any other is tested. `v` is a property bound by a WITH,
     * `date` a map's entry bound by one, `w` a number bound by one, `$i` a parameter given as a number.
     */
    private val sides = listOf(
        // Tested: a value whose kind is open, which may be a tag.
        "n.d" to true, "v" to true, "n.m.d" to true, "n.at.minute" to true, "head(n.l)" to true, "coalesce(n.d, 1)" to true,
        "n.l[0]" to true, "[x IN n.l | x][0]" to true, "CASE WHEN n.a THEN n.d ELSE 1 END" to true,
        // A variable named as a temporal namespace is no function: its field may be a tag.
        "date.day" to true,
        // Skipped: a kind known when compiling. A parameter, or its entry, is of the value given: a statement is compiled for each call.
        "1" to false, "-1" to false, "2.5" to false, "\$i" to false, "\$m.d" to false, "w" to false, "1 + 2" to false, "-w" to false,
        "CASE WHEN n.a THEN 1 ELSE 2 END" to false, "floor(n.a)" to false, "toFloat(n.a)" to false,
        // Skipped: a text, a list or a truth value the statement makes.
        "toString(n.a)" to false, "split(n.s, ',')" to false, "nodes(p)" to false, "n.a = 1" to false,
        // Skipped: a count, a COUNT subquery, or a function that gives a number.
        "count(*)" to false, "count(n.d)" to false, "COUNT { MATCH (m:T) }" to false, "size(n.l)" to false, "length(p)" to false, "toInteger(n.a)" to false,
        "abs(n.a)" to false, "sign(n.a)" to false, "timestamp()" to false,
        // Skipped: a field of a temporal value the statement makes, a number or a text.
        "datetime(n.at).minute" to false, "localtime(n.t).hour" to false, "date.truncate('month', n.at).day" to false,
        "duration.between(n.a, n.b).days" to false, "duration(n.d).seconds" to false,
    )

    @Test
    fun `each form of a side is tested for a tag, or not, as the closed rule says, on every store`() {
        sides.forEach { (form, tested) ->
            compilers.forEach { compiler ->
                val cypher = "MATCH p = (n:T)-->() WITH p, n, n.d AS v, n.m AS date, 1 AS w RETURN $form - 1"
                val sql = compiler.compile(cypher, mapOf("i" to 2, "m" to mapOf("d" to "x"))).sql
                assertEquals(tested, tested(sql), "${compiler::class.simpleName} $form: ${if (tested) "tested" else "skipped"} expected:\n$sql")
            }
        }
    }

    @Test
    fun `each side of unknown kind is tested for a temporal tag, where the arithmetic would give null`() {
        listOf("n.d1 + n.d2", "n.d1 - n.d2", "n.at + n.d", "n.at - n.d", "n.d * 2", "n.d / 2", "2 * n.d").forEach { expression ->
            compilers.forEach { compiler ->
                val sql = compiler.compile("MATCH (n:T) RETURN $expression", emptyMap()).sql
                assertTrue(tested(sql), "${compiler::class.simpleName} $expression:\n$sql")
            }
        }
    }

    @Test
    fun `a side known to be temporal is worked out, and arithmetic on known numbers or texts is not tested`() {
        listOf(
            "MATCH (n:T) RETURN n.d + duration('PT1H')", "MATCH (n:T) RETURN duration('PT1H') + n.d", "RETURN 1 + 2", "RETURN 'a' + 'b'",
            "RETURN \$a * \$b", "RETURN [1] + [2]",
            // A function that gives a number.
            "MATCH (n:T) RETURN size(n.l) * 2", "MATCH (n:T) RETURN count(*) + 1", "MATCH (n:T) RETURN toInteger(n.a) - 1", "MATCH (n:T) RETURN 2 / abs(n.a)",
            // A COUNT subquery, and a field of a temporal value the statement makes, namespaced or not: a number or a text (drivine4j#809).
            "MATCH (n:T) RETURN COUNT { MATCH (m:T) } * 2", "MATCH (n:T) RETURN datetime(n.at).minute * 2",
            "MATCH (n:T) RETURN date.truncate('month', n.at).day - 1", "MATCH (n:T) RETURN duration.between(n.a, n.b).days / 2",
        ).forEach { cypher ->
            compilers.forEach { compiler ->
                val sql = compiler.compile(cypher, mapOf("a" to 2, "b" to 3)).sql
                assertFalse(tested(sql), "${compiler::class.simpleName} $cypher:\n$sql")
            }
        }
    }

    @Test
    fun `a plus tests a tag beside a tag or a number only, as a temporal value beside a text is its text and beside a list an item`() {
        val sql = PostgresCypherCompiler("g").compile("MATCH (n:T) RETURN n.a + n.b", emptyMap()).sql
        assertTrue("jsonb_typeof" in sql && "= 'number'" in sql && tested(sql), sql)
        // A side known to be a number: only the other is tested.
        val one = PostgresCypherCompiler("g").compile("MATCH (n:T) RETURN n.a + 1", emptyMap()).sql
        assertTrue(tested(one), one)
        // A field of a value the statement doesn't make temporal may be a map's entry, a tag: still tested.
        val field = PostgresCypherCompiler("g").compile("MATCH (n:T) RETURN n.m.d * 2", emptyMap()).sql
        assertTrue(tested(field), field)
    }

    @Test
    fun `a plus beside a text or a list, written or given, is not tested, as no tag beside it is refused`() {
        listOf("n.name + 'x'", "'x' + n.name", "n.a + [1]", "[1] + n.a", "n.a + \$s", "n.a + \$l").forEach { expression ->
            compilers.forEach { compiler ->
                val sql = compiler.compile("MATCH (n:T) RETURN $expression", mapOf("s" to "x", "l" to listOf(1))).sql
                assertFalse(tested(sql), "${compiler::class.simpleName} $expression:\n$sql")
            }
        }
    }

    @Test
    fun `each store works it out by its function, or SQLite by its host function, told the operator, and an Oracle store without it fails by name`() {
        val calls = mapOf(PostgresCypherCompiler("g") to "g_temporal_arith('", OracleCypherCompiler("g") to "g_temporal_arith('",
            SqliteCypherCompiler("g") to "drivine_temporal_arithmetic('")
        listOf("+", "-", "*", "/").forEach { operator ->
            calls.forEach { (compiler, call) ->
                val sql = compiler.compile("MATCH (n:T) RETURN n.a $operator n.b", emptyMap()).sql
                assertTrue("$call$operator', " in sql && refusal !in sql, "${compiler::class.simpleName} $operator:\n$sql")
            }
            // An Oracle store made before the function keeps none.
            listOf(refusing).forEach { compiler ->
                val sql = compiler.compile("MATCH (n:T) RETURN n.a $operator n.b", emptyMap()).sql
                assertTrue(refusal in sql && "_temporal_arith(" !in sql, "${compiler::class.simpleName} without the function, $operator:\n$sql")
            }
        }
    }

    @Test
    fun `an Oracle statement says it calls the function, so a host whose store lacks it compiles again without`() {
        val calling = OracleCypherCompiler("g").also { it.compile("MATCH (n:T) RETURN n.a - n.b", emptyMap()) }
        assertTrue(calling.callsStoredTemporalArithmetic)
        val known = OracleCypherCompiler("g").also { it.compile("MATCH (n:T) RETURN n.a - 1, duration('P1D') * 2", emptyMap()) }
        assertTrue(known.callsStoredTemporalArithmetic)
        val none = OracleCypherCompiler("g").also { it.compile("MATCH (n:T) RETURN duration('P1D') * 2, size(n.l) + 1", emptyMap()) }
        assertFalse(none.callsStoredTemporalArithmetic)
    }

    /**
     * The function's text on each store: a null on either side, SQL's or JSON's, gives null before any pair is tested; each pair,
     * and each swapped, is one branch, read over the function's arguments with no parameter or sample value left; a pair of
     * no branch fails with [TemporalArithmeticFunction.UNDEFINED], its operator and kinds.
     */
    @Test
    fun `the function gives null for a null side, has one branch for each pair, and fails by name for any other`() {
        val forms = mapOf(
            "postgres" to TemporalArithmeticFunction.postgres("g"),
            "oracle" to TemporalArithmeticFunction.oracle("g"),
        )
        forms.forEach { (store, text) ->
            val branches = Regex("""\n {4}IF cy_op = '+[-+*/]'+ AND""").findAll(text).count()
            // A moment plus or minus a duration, five kinds; a duration plus a moment; two durations; a duration times or by a number; a number times one.
            assertEquals(5 * 2 + 5 + 2 + 2 + 1, branches, store)
            val nulls = text.indexOf("RETURN NULL;")
            assertTrue(nulls in 0 until text.indexOf("IF cy_op ="), store)
            assertTrue("\$_cy_" !in text && "2024-01" !in text && "P1D" !in text, store)
            // No branch declares a function of its own (a statement's `WITH FUNCTION`), which a body selected within can't hold.
            assertEquals(1, Regex("""\bFUNCTION\b""").findAll(text).count(), store)
            assertTrue(TemporalArithmeticFunction.UNDEFINED in text, store)
        }
        val postgres = forms.getValue("postgres")
        assertTrue("IF cy_x IS NULL OR cy_y IS NULL OR jsonb_typeof(cy_x) = ''null'' OR jsonb_typeof(cy_y) = ''null'' THEN" in postgres, postgres)
        // RAISE takes as many arguments as its format has places, and the message itself has none.
        assertTrue('%' !in TemporalArithmeticFunction.UNDEFINED)
        assertEquals(
            "RAISE EXCEPTION ''${TemporalArithmeticFunction.UNDEFINED} %, % and %'', cy_op, COALESCE(kx, jsonb_typeof(cy_x)), COALESCE(ky, jsonb_typeof(cy_y));",
            postgres.lines().single { "RAISE EXCEPTION" in it }.trim(),
        )
        val oracle = forms.getValue("oracle")
        assertTrue("IF tx IS NULL OR ty IS NULL OR tx = 'null' OR ty = 'null' THEN" in oracle, oracle)
        assertTrue("RAISE_APPLICATION_ERROR(-20418, '${TemporalArithmeticFunction.UNDEFINED} ' || cy_op || ', ' || COALESCE(kx, tx) || ' and ' || COALESCE(ky, ty));" in oracle)
    }

    /**
     * The function holds one body for each pair of kinds, compiled for a sample value of each (`TemporalArithmeticFunction`), so
     * it is right for every stored value only while the SQL depends on the kinds alone: values far from the samples, BC, with
     * nanoseconds, an offset, every part of a duration negated, and other whole numbers, compile to the same SQL.
     */
    @Test
    fun `the SQL a body is compiled from depends on the kinds alone, not on the values given`() {
        val samples = mapOf(
            Temporal.DATE to ("2024-01-31" to "-0044-03-15"), Temporal.LOCAL_TIME to ("10:00:00" to "23:59:59.123456789"),
            Temporal.TIME to ("10:00:00Z" to "01:02:03.5-05:30"), Temporal.LOCAL_DATE_TIME to ("2024-01-01T10:00:00" to "-0001-12-31T23:59:59.999"),
            Temporal.DATE_TIME to ("2024-01-01T10:00:00Z" to "1999-12-31T23:59:59.000000001+14:00"), Temporal.DURATION to ("P1D" to "-P1Y2M3W4DT5H6M7.5S"),
        )
        val stores = listOf({ PostgresCypherCompiler("g") }, { OracleCypherCompiler("g", true, true) })
        fun sql(compiler: SqlCypherCompiler, operator: String, x: Any, y: Any) = compiler.compile("RETURN \$x $operator \$y AS r", mapOf("x" to x, "y" to y)).sql
        stores.forEach { compiler ->
            listOf("+", "-").forEach { operator ->
                Temporal.entries.forEach { kind ->
                    val (sample, other) = samples.getValue(kind)
                    val (duration, otherDuration) = samples.getValue(Temporal.DURATION)
                    assertEquals(
                        sql(compiler(), operator, TemporalValue(kind, sample), TemporalValue(Temporal.DURATION, duration)),
                        sql(compiler(), operator, TemporalValue(kind, other), TemporalValue(Temporal.DURATION, otherDuration)),
                        "${compiler()::class.simpleName} $kind $operator duration",
                    )
                }
            }
            listOf("*", "/").forEach { operator ->
                listOf(3L, -7L, 1L).forEach { number ->
                    assertEquals(
                        sql(compiler(), operator, TemporalValue(Temporal.DURATION, "P1D"), 2L),
                        sql(compiler(), operator, TemporalValue(Temporal.DURATION, "-P1Y2M3W4DT5H6M7.5S"), number),
                        "${compiler()::class.simpleName} duration $operator $number",
                    )
                }
            }
        }
    }

    @Test
    fun `the refusal names the tag's kind on either side, where the other side may be a number`() {
        val sql = refusing.compile("MATCH (n:T) RETURN n.a * n.d", emptyMap()).sql
        assertTrue(refusal in sql && "COALESCE(" in sql, sql)
    }
}
