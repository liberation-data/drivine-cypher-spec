package org.drivine.cypher.number

import com.ionspin.kotlin.bignum.integer.BigInteger
import org.drivine.DrivineInternalApi
import org.drivine.cypher.sql.RoundingMode

/**
 * A number rounded in decimal, as Java's `BigDecimal.valueOf(number).setScale(places, mode).doubleValue()` rounds it, as
 * Neo4j's `round(value, precision, mode)` does, on every platform: a double is the decimal Java writes for it
 * ([DoubleText]), so 2.345 is halfway between 2.34 and 2.35, where its binary value is just below.
 */
@DrivineInternalApi
object DecimalRound {

    /** The whole number [value] rounded to [places] decimal places (none changes it, past the point) by [mode]. */
    fun of(value: Long, places: Long, mode: RoundingMode): Double = round(BigInteger.fromLong(value), 0, places, mode)

    /** The finite double [value] rounded to [places] decimal places by [mode]. */
    fun of(value: Double, places: Long, mode: RoundingMode): Double {
        require(value.isFinite()) { "$value has no decimal to round." }
        val (unscaled, scale) = decimal(DoubleText.of(value))
        return round(unscaled, scale, places, mode)
    }

    /**
     * [unscaled] × 10^-[scale] to [places] places. Places at or past [scale] change nothing, as `setScale` would only write
     * more zeros, and are not written out, so a precision of a billion places is no billion digits.
     */
    private fun round(unscaled: BigInteger, scale: Long, places: Long, mode: RoundingMode): Double {
        if (places >= scale) return double(unscaled, scale)
        val divisor = BigInteger.TEN.pow(scale - places)
        val (quotient, remainder) = unscaled.divrem(divisor).let { it.quotient to it.remainder }
        if (remainder.isZero()) return double(quotient, places)
        val negative = unscaled.isNegative
        val twice = remainder.abs() * 2
        val half = twice.compareTo(divisor)
        val away = when (mode) {
            RoundingMode.UP -> true
            RoundingMode.DOWN -> false
            RoundingMode.CEILING -> !negative
            RoundingMode.FLOOR -> negative
            RoundingMode.HALF_UP -> half >= 0
            RoundingMode.HALF_DOWN -> half > 0
            RoundingMode.HALF_EVEN -> half > 0 || (half == 0 && (quotient.abs() % BigInteger.TWO).isZero().not())
            RoundingMode.UNNECESSARY -> throw ArithmeticException("Rounding necessary")
        }
        val rounded = if (!away) quotient else if (negative) quotient - BigInteger.ONE else quotient + BigInteger.ONE
        return double(rounded, places)
    }

    /** [unscaled] × 10^-[scale] as the nearest double, as `BigDecimal.doubleValue` gives it: no negative zero. */
    private fun double(unscaled: BigInteger, scale: Long): Double =
        if (unscaled.isZero()) 0.0 else DoubleParse.parseOrNull("${unscaled}E${-scale}")!!

    /**
     * Java's text of a double (`2.345`, `1.0E10`, `-1.5E-7`) as `BigDecimal(String)` reads it: its digits as a whole
     * number, and its scale, the places of its fraction less its exponent.
     */
    private fun decimal(text: String): Pair<BigInteger, Long> {
        val e = text.indexOf('E')
        val mantissa = if (e < 0) text else text.substring(0, e)
        val exponent = if (e < 0) 0L else text.substring(e + 1).toLong()
        val point = mantissa.indexOf('.')
        val fraction = if (point < 0) 0 else mantissa.length - point - 1
        return BigInteger.parseString(mantissa.replace(".", "")) to fraction - exponent
    }
}
