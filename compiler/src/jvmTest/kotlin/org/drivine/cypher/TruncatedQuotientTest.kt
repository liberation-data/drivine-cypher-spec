package org.drivine.cypher

import org.drivine.cypher.sql.OracleCypherCompiler
import org.drivine.cypher.sql.PostgresCypherCompiler
import org.drivine.cypher.sql.SqliteCypherCompiler
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * #275: a quotient cut toward zero, a duration's seconds by an hour's or a minute's, its months by a year's, its days by a week's,
 * is `TemporalSql.truncatedQuotient` in every temporal builder, which Postgres works out with `div`: its numeric `/` keeps 16
 * significant digits or so, and rounded `315575999999.999999999 / 3600` up to the next whole hour.
 */
class TruncatedQuotientTest {

    /** A statement for each builder that cuts a quotient: a duration's text, its fields, a date it moves, and `duration.between`. */
    private val statements = listOf(
        "RETURN toString(duration(\$d) + duration('PT1S'))",
        "WITH duration(\$d) AS d RETURN [d.years, d.quarters, d.weeks, d.hours, d.minutes, d.quartersOfYear, d.monthsOfQuarter, " +
            "d.monthsOfYear, d.daysOfWeek, d.minutesOfHour, d.secondsOfMinute]",
        "RETURN toString(date(\$t) + duration(\$d))",
        "RETURN toString(duration.between(localdatetime(\$a), localdatetime(\$b)))",
    )

    private val parameters = mapOf("d" to "P1Y2M3W4DT5H6M7.5S", "t" to "2024-01-01", "a" to "2024-01-01T00:00", "b" to "2024-03-01T12:00:00.5")

    /** A division by one of the units a duration is cut in, followed by the parenthesis that closes a truncation of it. */
    private val truncated = Regex("""/ (12|3|7|60|3600|86400)\)""")

    @Test
    fun `Postgres cuts each such quotient with div and divides by none of the units in a truncation`() {
        statements.forEach { cypher ->
            val sql = PostgresCypherCompiler("g").compile(cypher, parameters).sql
            assertTrue("div(" in sql, "$cypher:\n$sql")
            assertFalse(truncated.containsMatchIn(sql), "$cypher:\n${truncated.findAll(sql).map { sql.substring(maxOf(0, it.range.first - 80), it.range.last + 1) }.toList()}")
        }
    }

    @Test
    fun `Oracle and SQLite cut it as they did, by their own truncation`() {
        statements.forEach { cypher ->
            assertTrue("TRUNC(" in OracleCypherCompiler("g").compile(cypher, parameters).sql, cypher)
            assertFalse(" div(" in SqliteCypherCompiler("g").compile(cypher, parameters).sql, cypher)
        }
    }
}
