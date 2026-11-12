package org.drivine.cypher.host

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.seconds
import kotlin.time.measureTimedValue

/**
 * A decimal text of an exponent near an int's ends, or of a million digits, read by `drivine_to_char` or as an amount of
 * `drivine_temporal`'s `add_months` and `add_time`, is answered at once, as Java answers it or as Java fails: a power of
 * ten written out for it once hung the database the function ran in. Each call has a ceiling of 2 s, far over the
 * milliseconds each takes; a hang that never returns is ended by the test task's timeout (build.gradle.kts).
 *
 * The exponents here at `BigInteger`'s range of 2^31 - 1 bits (10^646456993 is past it, 10^646456992 within) are ones
 * Java takes minutes over, so they are here, not in the oracle; the others the oracle has from Java too.
 */
class HostDecimalTimeTest {

    private val toChar = HostFunctions.all.filter { it.name == "drivine_to_char" }
    private val temporal = HostFunctions.all.filter { it.name == "drivine_temporal" }

    private fun call(functions: List<HostFunction>, vararg args: Any?): Result<Any?> {
        val where = "${functions.first().name}(${args.joinToString { it.toString().take(30) }})"
        val (value, took) = measureTimedValue { runCatching { callHost(functions, args.toList(), where) } }
        assertTrue(took < 2.seconds, "$where took $took, over its ceiling of 2 s: is a power of ten written out?")
        return value
    }

    private fun assertFails(message: String, result: Result<Any?>) =
        assertTrue(result.exceptionOrNull()?.message?.endsWith(message) == true, "Expected a failure with $message, got $result")

    /** [amount] as each of `add_months`' and `add_time`'s amounts. */
    private fun amounts(amount: String): List<Result<Any?>> = listOf(
        call(temporal, "add_months", STAMP, amount),
        call(temporal, "add_time", STAMP, amount, 0L, 0L),
        call(temporal, "add_time", STAMP, 0L, amount, 0L),
        call(temporal, "add_time", STAMP, 0L, 0L, amount),
    )

    @Test
    fun `an exponent past an int's places fails as Java's does`() {
        for (text in listOf("1e2147483647", "-1e2147483647", "1e-2147483647", "1e646456993", "1e-646456993")) {
            assertFails(OVERFLOW, call(toChar, text, "FM0."))
            assertFails(OVERFLOW, call(toChar, text, "FM0000"))
            amounts(text).forEach { assertFails(OVERFLOW, it) }
        }
        assertFails("Underflow", call(toChar, "1e2147483647", "FM0.999"))
    }

    @Test
    fun `an exponent within BigInteger's range is answered without writing it out`() {
        // 10^646456992 is in a long's low 64 bits 0: no months, days, seconds or nanoseconds.
        amounts("1e646456992").forEach { assertEquals(STAMP, it.getOrThrow()) }
        assertEquals("0", call(toChar, "1e-646456992", "FM0").getOrThrow())
        // To three places, Java divides 1e-646456995 by 10^646456992, within the range, and 1e-646456996 by 10^646456993, past it.
        assertEquals("0.", call(toChar, "-1e-646456995", "FM0.999").getOrThrow())
        assertFails(OVERFLOW, call(toChar, "-1e-646456996", "FM0.999"))
    }

    @Test
    fun `a zero of any exponent is zero`() {
        for (text in listOf("0e999999999", "0e2147483647", "-0e-2147483647")) {
            assertEquals("0.", call(toChar, text, "FM0.").getOrThrow())
            amounts(text).forEach { assertEquals(STAMP, it.getOrThrow()) }
        }
    }

    @Test
    fun `a million digits are answered in time linear in them`() {
        val digits = "7".repeat(1_000_000)
        assertEquals(digits, call(toChar, digits, "FM0").getOrThrow())
        assertEquals("$digits.", call(toChar, digits, "FM0.999").getOrThrow())
        assertEquals("777.778", call(toChar, digits + "e-999997", "FM0.999").getOrThrow())
        // 7…7's low 64 bits are 2049638230412172401 months: far past the calendar.
        assertTrue(call(temporal, "add_months", STAMP, digits).isFailure)
    }

    private companion object {
        const val STAMP = "2024-01-31T00:00:00.000000"
        const val OVERFLOW = "BigInteger would overflow supported range"
    }
}
