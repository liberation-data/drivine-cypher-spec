package org.drivine.cypher

import org.drivine.cypher.sql.OracleCypherCompiler
import org.drivine.cypher.sql.PostgresCypherCompiler
import org.drivine.cypher.sql.SqliteCypherCompiler
import org.drivine.cypher.sql.SqlCypherCompiler
import org.drivine.cypher.sql.Temporals
import org.junit.jupiter.api.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * #263: a date or a moment worked out as the statement runs whose year the store doesn't hold fails by name, where it gave a
 * wrong value. What the stores give is the spec's, in `temporal.yaml` ("A year past 9999 or before -9999"): this is where each
 * store is given the refusal, and where not.
 */
class TemporalLongYearTest {

    private val compilers = listOf(PostgresCypherCompiler("g"), OracleCypherCompiler("g"), SqliteCypherCompiler("g"))

    /** The test of a year the statement gives, against the last year and the store's first. */
    private val givenYearTest = Regex("""> 9999 OR .* < -\d{4}""")

    /** [Temporals.LONG_YEAR] as the SQL writes it, a text literal. */
    private val refusal = Temporals.LONG_YEAR.replace("'", "''")

    private fun sql(compiler: SqlCypherCompiler, cypher: String) = compiler.compile(cypher).sql

    @Test
    fun `a year the statement gives is tested on every store, against its first`() {
        listOf(PostgresCypherCompiler("g") to "-4712", OracleCypherCompiler("g") to "-4711", SqliteCypherCompiler("g") to "-9999").forEach { (compiler, first) ->
            listOf(
                "UNWIND [10000] AS y RETURN toString(date({year: y, month: 1, day: 1}))",
                "UNWIND [10000] AS y RETURN toString(localdatetime({year: y, week: 1, dayOfWeek: 1}))",
                "UNWIND [10000] AS y RETURN toString(datetime({date: date('2024-01-01'), year: y}))",
                "UNWIND [10000] AS y RETURN toString(date.truncate('month', date('2024-05-05'), {year: y}))",
            ).forEach { cypher ->
                val sql = sql(compiler, cypher)
                assertTrue(refusal in sql && "> 9999 OR" in sql && "< $first " in sql, "${compiler::class.simpleName}: $cypher: $sql")
            }
        }
    }

    @Test
    fun `a time of day, and a value whose year is read off one the store holds, have no year to test`() {
        compilers.forEach { compiler ->
            listOf(
                "UNWIND [10] AS h RETURN toString(localtime({hour: h}))",
                "UNWIND [10] AS h RETURN toString(time({hour: h, timezone: '+01:00'}))",
                "UNWIND ['2024-05-05'] AS d RETURN toString(date.truncate('month', date(d), {day: 2}))",
                "UNWIND ['2024-05-05'] AS d RETURN toString(datetime({date: date(d), hour: 1}))",
                "UNWIND ['2024-05-05'] AS d RETURN toString(datetime({date: date(d), month: 2}))",
                "UNWIND ['2024-05-05'] AS d RETURN toString(date({date: date(d), ordinalDay: 2}))",
            ).forEach { cypher ->
                val sql = sql(compiler, cypher)
                assertFalse(givenYearTest.containsMatchIn(sql), "${compiler::class.simpleName}: $cypher: $sql")
            }
        }
    }

    @Test
    fun `a moment's text is tested for a long year on Postgres and SQLite, and not on Oracle, whose timestamp holds none`() {
        val cypher = "UNWIND ['9999-12-01'] AS d RETURN toString(date(d) + duration('P1M'))"
        assertTrue(">= TIMESTAMP '10000-01-01 00:00:00'" in sql(PostgresCypherCompiler("g"), cypher))
        assertTrue("GLOB '[-+][0-9][0-9][0-9][0-9][0-9]*'" in sql(SqliteCypherCompiler("g"), cypher))
        assertFalse(refusal in sql(OracleCypherCompiler("g"), cypher))
        // A time of day has no year.
        compilers.forEach { compiler ->
            assertFalse(refusal in sql(compiler, "UNWIND ['10:00'] AS t RETURN toString(localtime(t) + duration('PT1H'))"), compiler::class.simpleName)
        }
    }
}
