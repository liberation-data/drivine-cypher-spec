package org.drivine.cypher

import org.drivine.cypher.sql.OracleCypherCompiler
import org.drivine.cypher.sql.PostgresCypherCompiler
import org.drivine.cypher.sql.SqliteCypherCompiler
import org.junit.jupiter.api.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * A duration divided by a number is each of its parts divided (#260), and its seconds are rounded to nine places before they are
 * split (#261). What the stores give is the spec's, in `temporal.yaml` ("A duration divided, and its seconds written"): this is
 * what each is given where no case reaches, a dividend past the 16 significant digits Postgres's `numeric` division keeps.
 */
class DurationDividedTest {

    private val cypher = "RETURN toString(duration('PT1000000H') / 7)"

    @Test
    fun `each part is divided, not times the divisor's reciprocal, and the seconds rounded to nine places, on Postgres and Oracle`() {
        listOf(PostgresCypherCompiler("g"), OracleCypherCompiler("g")).forEach { compiler ->
            val sql = compiler.compile(cypher, emptyMap()).sql
            assertFalse("(1.0 / " in sql, "${compiler::class.simpleName}:\n$sql")
            assertTrue(Regex(""", 9\)""").containsMatchIn(sql), "${compiler::class.simpleName}:\n$sql")
        }
    }

    @Test
    fun `on SQLite the host function divides it, in decimals, where its SQL worked in doubles (#262)`() {
        val sql = SqliteCypherCompiler("g").compile(cypher, emptyMap()).sql
        assertTrue("drivine_temporal_arithmetic('/', json_object('\$t', 'duration', 'v', " in sql, sql)
        assertFalse("(1.0 / " in sql, sql)
    }

    @Test
    fun `on Postgres the dividend is given 20 places, so 3600000000 seconds over 7 keep the ninth, not numeric's 8`() {
        val sql = PostgresCypherCompiler("g").compile(cypher, emptyMap()).sql
        assertTrue(Regex("""\bROUND\(\(CASE WHEN .*, 20\) / """).containsMatchIn(sql), sql)
    }

    @Test
    fun `on Postgres the seconds are rounded as numeric, as it has no ROUND of a double precision to places`() {
        val sql = PostgresCypherCompiler("g").compile(cypher, emptyMap()).sql
        assertTrue(Regex("""\bROUND\(CAST\(\(.* AS numeric\), 9\)""").containsMatchIn(sql), sql)
        listOf(OracleCypherCompiler("g"), SqliteCypherCompiler("g")).forEach { compiler ->
            val other = compiler.compile(cypher, emptyMap()).sql
            assertFalse("AS numeric), 9)" in other, "${compiler::class.simpleName}:\n$other")
        }
    }
}
