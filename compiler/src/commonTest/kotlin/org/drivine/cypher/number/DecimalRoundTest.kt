package org.drivine.cypher.number

import org.drivine.cypher.sql.RoundingMode
import org.drivine.cypher.sql.RoundingMode.CEILING
import org.drivine.cypher.sql.RoundingMode.DOWN
import org.drivine.cypher.sql.RoundingMode.FLOOR
import org.drivine.cypher.sql.RoundingMode.HALF_DOWN
import org.drivine.cypher.sql.RoundingMode.HALF_EVEN
import org.drivine.cypher.sql.RoundingMode.HALF_UP
import org.drivine.cypher.sql.RoundingMode.UNNECESSARY
import org.drivine.cypher.sql.RoundingMode.UP
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

/** [DecimalRound] on every target, each result as Java's `BigDecimal.valueOf(x).setScale(places, mode).doubleValue()` gives it. */
class DecimalRoundTest {

    private fun round(x: Double, places: Long, mode: RoundingMode) = DecimalRound.of(x, places, mode)

    @Test
    fun `a double is rounded as the decimal Java writes for it`() {
        // 2.345 is 2.34499999999999997… in binary, and halfway in decimal.
        assertEquals(2.35, round(2.345, 2, HALF_UP))
        assertEquals(2.34, round(2.345, 2, HALF_DOWN))
        assertEquals(2.34, round(2.345, 2, HALF_EVEN))
        assertEquals(2.36, round(2.355, 2, HALF_EVEN))
        assertEquals(2.0, round(2.5, 0, HALF_EVEN))
        assertEquals(4.0, round(3.5, 0, HALF_EVEN))
        assertEquals(3.0, round(2.5, 0, HALF_UP))
        assertEquals(2.0, round(2.5, 0, HALF_DOWN))
        assertEquals(2.6, round(2.56, 1, HALF_DOWN))
    }

    @Test
    fun `each mode rounds a negative number as Java does`() {
        assertEquals(-3.0, round(-2.5, 0, HALF_UP))
        assertEquals(-2.0, round(-2.5, 0, HALF_DOWN))
        assertEquals(-2.0, round(-2.5, 0, HALF_EVEN))
        assertEquals(-3.0, round(-2.1, 0, UP))
        assertEquals(-2.0, round(-2.9, 0, DOWN))
        assertEquals(-2.0, round(-2.9, 0, CEILING))
        assertEquals(-3.0, round(-2.1, 0, FLOOR))
        assertEquals(3.0, round(2.1, 0, CEILING))
        assertEquals(2.0, round(2.9, 0, FLOOR))
    }

    @Test
    fun `a negative precision rounds to tens and hundreds`() {
        assertEquals(1200.0, round(1234.5, -2, HALF_UP))
        assertEquals(1300.0, DecimalRound.of(1250L, -2, HALF_UP))
        assertEquals(1200.0, DecimalRound.of(1250L, -2, HALF_EVEN))
        assertEquals(0.0, DecimalRound.of(49L, -2, HALF_UP))
        assertEquals(1.0E10, round(1.0E10, -2, UP))
        assertEquals(2.0E10, round(1.5E10, -10, HALF_UP))
    }

    @Test
    fun `places past the number's own change nothing and are not written out`() {
        assertEquals(2.5, round(2.5, 1_000_000_000, HALF_UP))
        assertEquals(5.0, DecimalRound.of(5L, 1, UP))
        assertEquals(Long.MAX_VALUE.toDouble(), DecimalRound.of(Long.MAX_VALUE, 0, UP))
        assertEquals(1.0E-300, round(1.0E-300, 400, DOWN))
        assertEquals(1.0E-100, round(1.0E-100, 120, HALF_UP))
        assertEquals(Double.MIN_VALUE, round(Double.MIN_VALUE, 400, HALF_UP))
    }

    @Test
    fun `a result of zero is no negative zero as a decimal holds none`() {
        assertEquals(0.0, round(-0.4, 0, HALF_UP))
        assertEquals(0.0, round(-0.0, 2, HALF_UP))
        assertEquals(0.0, round(-0.4, 0, HALF_UP).let { 1 / it }.let { if (it > 0) 0.0 else -1.0 })
    }

    @Test
    fun `UNNECESSARY fails where rounding is needed as Java's does`() {
        assertEquals(2.5, round(2.5, 1, UNNECESSARY))
        assertFailsWith<ArithmeticException> { round(2.55, 1, UNNECESSARY) }
        assertFailsWith<IllegalArgumentException> { round(Double.POSITIVE_INFINITY, 1, UP) }
    }
}
