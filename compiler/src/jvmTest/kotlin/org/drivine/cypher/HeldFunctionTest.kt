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
}
