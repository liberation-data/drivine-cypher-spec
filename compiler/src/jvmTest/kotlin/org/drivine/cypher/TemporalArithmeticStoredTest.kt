package org.drivine.cypher

import org.drivine.cypher.sql.OracleCypherCompiler
import org.drivine.cypher.sql.PostgresCypherCompiler
import org.drivine.cypher.sql.SqliteCypherCompiler
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * drivine4j#418: `+`, `-`, `*` and `/` of a temporal value whose kind the statement doesn't know, a tag read from the store, is
 * worked out as the statement runs by the store's function of temporal arithmetic on Postgres and Oracle, where the generic
 * arithmetic gave null; on SQLite, which has none yet, and on an Oracle store without it, it fails by name. What each store gives is the spec's, in `temporal.yaml` ("Arithmetic on
 * temporal values read from the store"): this is what each is given.
 */
class TemporalArithmeticStoredTest {

    private val compilers = listOf(PostgresCypherCompiler("g"), OracleCypherCompiler("g"), SqliteCypherCompiler("g"))

    private val refusal = "compiled to SQL yet (drivine4j#418)"

    /** An Oracle store without the function of temporal arithmetic, as one made by an earlier version is. */
    private val refusing = OracleCypherCompiler("g", true, true, emptySet(), null, false, false, false, false)

    /** Whether [sql] tests a side for a tag: it calls the store's function there, or fails by name. */
    private fun tested(sql: String) = "_temporal_arithmetic('" in sql || refusal in sql

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
    fun `Postgres and Oracle work it out by their function, told the operator, and SQLite and an Oracle store without it fail by name`() {
        val calls = mapOf(PostgresCypherCompiler("g") to "g_temporal_arithmetic('", OracleCypherCompiler("g") to "g_temporal_arithmetic('")
        listOf("+", "-", "*", "/").forEach { operator ->
            calls.forEach { (compiler, call) ->
                val sql = compiler.compile("MATCH (n:T) RETURN n.a $operator n.b", emptyMap()).sql
                assertTrue("$call$operator', " in sql && refusal !in sql, "${compiler::class.simpleName} $operator:\n$sql")
            }
            // SQLite keeps no function yet, nor an Oracle store made before it.
            listOf(refusing, SqliteCypherCompiler("g")).forEach { compiler ->
                val sql = compiler.compile("MATCH (n:T) RETURN n.a $operator n.b", emptyMap()).sql
                assertTrue(refusal in sql && "_temporal_arithmetic(" !in sql, "${compiler::class.simpleName} without the function, $operator:\n$sql")
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

    @Test
    fun `the refusal names the tag's kind on either side, where the other side may be a number`() {
        val sql = refusing.compile("MATCH (n:T) RETURN n.a * n.d", emptyMap()).sql
        assertTrue(refusal in sql && "COALESCE(" in sql, sql)
    }
}
