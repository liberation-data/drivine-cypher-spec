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

}
