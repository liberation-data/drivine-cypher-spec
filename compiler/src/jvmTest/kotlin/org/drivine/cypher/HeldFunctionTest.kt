package org.drivine.cypher

import org.drivine.cypher.sql.OracleCypherCompiler
import org.drivine.cypher.sql.PostgresCypherCompiler
import org.drivine.cypher.sql.Temporals
import org.drivine.cypher.store.ddl.PostgresDdl
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * drivine4j#421: the store's function that refuses a nanosecond past the microsecond as a text is read (Postgres's `_held`,
 * Oracle's era function) names the same refusal, word for word, as the test the SQL writes where the store keeps none.
 */
class HeldFunctionTest {

    /** The message a function's body raises, its quotes as the body writes them taken out. */
    private fun raised(body: String, quote: String): String =
        Regex("""$quote([^']*(?:$quote$quote[^']*)*)$quote""").findAll(body).map { it.groupValues[1].replace(quote + quote, "'") }
            .first { it.startsWith("A fraction") }

    @Test
    fun `Postgres's function names the refusal word for word as the SQL's own test does`() {
        val function = PostgresDdl("g").ddl.single { "FUNCTION g_held(" in it }
        // In the body's quotes, a text's quote is written twice, and one in the text four times.
        assertEquals(Temporals.SUB_MICRO, raised(function, "''"))
        assertTrue("strpos(t, ''.'')" in function && "FOR i IN 1 .. 9 LOOP" in function && "i > 6" in function, function)
        assertFalse("REGEXP" in function.uppercase(), function)
    }

    @Test
    fun `Oracle's era function names it word for word too`() {
        val era = OracleCypherCompiler.eraFunction("g_era")
        assertTrue("'${Temporals.SUB_MICRO.replace("'", "''")} ' || t" in era, era)
    }

    @Test
    fun `Postgres reads a stored text through the function, and inline where it has none`() {
        val cypher = "MATCH (n) RETURN n.at + duration('PT1S')"
        val stored = PostgresCypherCompiler("g").compile(cypher).sql
        assertTrue("g_held(" in stored, stored)
        assertFalse("past the microsecond (drivine4j#421)" in stored, stored)
    }

    @Test
    fun `Oracle reads a stored time of day and a stored duration through its era function`() {
        // Its era function refuses either as it reads the text: a time of day has no year's test to leave it there, and a
        // duration no store's read of its own.
        val time = OracleCypherCompiler("g").compile("MATCH (n) RETURN n.t + duration('PT1S')").sql
        assertTrue("'1970-01-01T' || g_era(JSON_VALUE(n1.props, '\$.t.v'))" in time, time)
        val moved = OracleCypherCompiler("g").compile("MATCH (n) RETURN localdatetime('2024-01-01T00:00:00') + n.d").sql
        assertTrue("g_era(JSON_VALUE(n1.props, '\$.d.v'))" in moved, moved)
        assertFalse(Temporals.SUB_MICRO.replace("'", "''") in moved, moved)
    }

    @Test
    fun `Oracle renumbers a stored year BC once where a time of day may be a moment`() {
        // A base that gives the time may be a time of day or a moment: its era function renumbers a moment's year BC each time
        // it reads it, so the text read through it is not read through it again, where `-0044-02-29` would be `-0046-02-29`.
        for (cypher in listOf("MATCH (n) RETURN localtime({time: n.at})", "MATCH (n) RETURN localdatetime({date: n.at, time: n.at})")) {
            val sql = OracleCypherCompiler("g").compile(cypher).sql
            assertFalse("g_era(g_era(" in sql || "g_era(REGEXP_REPLACE(g_era(" in sql, sql)
            assertTrue(Temporals.SUB_MICRO.replace("'", "''") in sql, sql)
        }
    }

    @Test
    fun `the test of a year from 1000 on turns a nanosecond past the microsecond away, to where the store refuses it`() {
        val pattern = Regex(org.drivine.cypher.sql.Temporal.LOCAL_DATE_TIME.adPattern!!)
        listOf("2024-01-01T00:00:00", "2024-01-01T00:00:00.5", "2024-01-01T00:00:00.123456", "2024-01-01T00:00:00.123456000", "2024-01-01T00:00")
            .forEach { assertTrue(pattern.containsMatchIn(it), it) }
        listOf("2024-01-01T00:00:00.123456789", "2024-01-01T00:00:00.000000001", "2024-01-01T00:00:00.1234567")
            .forEach { assertFalse(pattern.containsMatchIn(it), it) }
        val zoned = Regex(org.drivine.cypher.sql.Temporal.DATE_TIME.adPattern!!)
        assertTrue(zoned.containsMatchIn("2024-01-01T00:00:00.5Z") && zoned.containsMatchIn("2024-01-01T00:00:00.123456+10:00"))
        assertFalse(zoned.containsMatchIn("2024-01-01T00:00:00.123456789+10:00"))
        // A tag's text, canonical, is told by its 27th character: a digit only in a fraction of nine.
        val oracle = OracleCypherCompiler("g").compile("MATCH (n) RETURN n.at + duration('PT1S')").sql
        assertTrue("COALESCE(SUBSTR(" in oracle && ", 27, 1), 'x') NOT BETWEEN '0' AND '9'" in oracle, oracle)
    }

    @Test
    fun `a store with no such function tests a stored text inline`() {
        // Oracle without its era functions: the CASE names the refusal itself.
        val oracle = OracleCypherCompiler("g", eraStored = false).compile("MATCH (n) RETURN n.at + duration('PT1S')").sql
        assertTrue(Temporals.SUB_MICRO.replace("'", "''") in oracle && "g_era(" !in oracle, oracle)
        // SQLite: the host function's read fails on the text, the nanoseconds replaced by words that say why.
        val sqlite = org.drivine.cypher.sql.SqliteCypherCompiler("g").compile("MATCH (n) RETURN n.t + duration('PT1S')").sql
        assertTrue("past the microsecond (drivine4j#421)" in sqlite, sqlite)
    }

    @Test
    fun `a value written in the statement or made of its fields is refused where it is worked out but a date's duration is not`() {
        fun postgres(cypher: String) = PostgresCypherCompiler("g").compile(cypher, mapOf("n" to 645876123)).sql
        // A time of day and a duration written in the statement are read through the store's function, which refuses them as
        // the statement runs.
        val time = postgres("RETURN localtime('12:00:00.000000001') + duration('PT1S')")
        assertTrue("g_held(CAST('12:00:00.000000001' AS text))" in time, time)
        val moved = postgres("RETURN localdatetime('2024-01-01T00:00:00') + duration('PT0.000000001S')")
        assertTrue("g_held(CAST('PT0.000000001S' AS text))" in moved, moved)
        // A date takes the whole days of a duration's time alone: its nanoseconds are not tested.
        val date = postgres("RETURN date('2024-01-01') + duration('PT0.000000001S')")
        assertFalse("g_held(CAST('PT0.000000001S' AS text))" in date, date)
        // A value made of a nanosecond field keeps its nine digits, written into its text, and is read through the store's
        // function only where it is worked out (drivine-cypher-spec#302); the refusal it writes is of a nanosecond out of range.
        val made = postgres("RETURN localtime({hour: 12, minute: 0, second: 0, nanosecond: \$n})")
        assertFalse("g_held(" in made, made)
        assertTrue("< 0 OR " in made && " > 999999999 THEN " in made, made)
        val madeMoved = postgres("RETURN localtime({hour: 12, minute: 0, second: 0, nanosecond: \$n}) + duration('PT1S')")
        assertTrue("g_held(" in madeMoved, madeMoved)
    }

    @Test
    fun `a field read off the store's timestamp of a stored text is refused but one read off its text is not`() {
        fun sqlite(cypher: String) = org.drivine.cypher.sql.SqliteCypherCompiler("g").compile(cypher).sql
        // The day of the week and the epoch's seconds are read off the timestamp, which would cut or round the nanoseconds.
        listOf("n.at.dayOfWeek", "n.at.epochSeconds", "n.at.week").forEach {
            val sql = sqlite("MATCH (n) RETURN $it")
            assertTrue("past the microsecond (drivine4j#421)" in sql, "$it: $sql")
        }
        // The nanosecond and the hour are read off the text, which keeps all nine digits.
        listOf("n.at.nanosecond", "n.at.hour").forEach {
            val sql = sqlite("MATCH (n) RETURN $it")
            assertFalse("past the microsecond (drivine4j#421)" in sql, "$it: $sql")
        }
    }

}
