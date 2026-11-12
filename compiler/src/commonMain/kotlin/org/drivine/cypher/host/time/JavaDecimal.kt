package org.drivine.cypher.host.time

import com.ionspin.kotlin.bignum.integer.BigInteger

/**
 * A decimal as java.math's `BigDecimal` holds one, [unscaled] × 10^-[scale], with the few of its operations drivine4j's
 * temporal host functions use: reading a text as `BigDecimal(String)` does, `setScale` by `HALF_UP`, `stripTrailingZeros`,
 * `toPlainString`, and `longValue`, which keeps a whole number's low 64 bits, wrapping silently past a long's range.
 */
internal class JavaDecimal(val unscaled: BigInteger, val scale: Long) {

    val signum: Int get() = if (unscaled.isZero()) 0 else if (unscaled.isNegative) -1 else 1

    /**
     * This to [places] places, rounded half away from zero (`setScale(places, RoundingMode.HALF_UP)`). A number of fewer
     * digits than the places it drops is less than half the unit kept, so is 0, and 10^n is not written out for it.
     */
    fun halfUp(places: Long): JavaDecimal {
        if (places >= scale) return JavaDecimal(unscaled * BigInteger.TEN.pow(places - scale), places)
        val drop = scale - places
        if (drop > unscaled.abs().toString().length) return JavaDecimal(BigInteger.ZERO, places)
        val divisor = BigInteger.TEN.pow(drop)
        val (quotient, remainder) = unscaled.divrem(divisor).let { it.quotient to it.remainder }
        val away = (remainder.abs() * 2) >= divisor
        val rounded = if (!away) quotient else if (unscaled.isNegative) quotient - BigInteger.ONE else quotient + BigInteger.ONE
        return JavaDecimal(rounded, places)
    }

    /** `stripTrailingZeros`: the same number with no zeros ending [unscaled], its scale lowered for each, past 0 too; zero is 0. */
    fun stripTrailingZeros(): JavaDecimal {
        if (unscaled.isZero()) return JavaDecimal(BigInteger.ZERO, 0)
        val digits = unscaled.abs().toString()
        val zeros = digits.length - digits.trimEnd('0').length
        return if (zeros == 0) this else JavaDecimal(unscaled / BigInteger.TEN.pow(zeros.toLong()), scale - zeros)
    }

    /** `toPlainString`: the digits with a point [scale] places from the end, and no exponent; zeros after them where [scale] is negative. */
    fun toPlainString(): String {
        val digits = unscaled.abs().toString()
        val sign = if (unscaled.isNegative) "-" else ""
        return when {
            scale <= 0 -> sign + digits + "0".repeat((-scale).toInt())
            digits.length > scale -> sign + digits.substring(0, digits.length - scale.toInt()) + "." + digits.substring(digits.length - scale.toInt())
            else -> sign + "0." + digits.padStart(scale.toInt(), '0')
        }
    }

    /** `longValue` of a whole number ([scale] 0): its low 64 bits, as a long, wrapping past a long's range. */
    fun wrappedLong(): Long {
        check(scale <= 0L) { "Not a whole number" }
        val whole = unscaled * BigInteger.TEN.pow(-scale)
        val low = whole.rem(TWO_64).let { if (it.isNegative) it + TWO_64 else it }
        return (if (low >= TWO_63) low - TWO_64 else low).toString().toLong()
    }

    companion object {
        private val TWO_64 = BigInteger.ONE.shl(64)
        private val TWO_63 = BigInteger.ONE.shl(63)

        fun of(value: Long) = JavaDecimal(BigInteger.fromLong(value), 0)

        /**
         * [text] as `BigDecimal(String)` reads it: a sign, digits with a point among them or before them (a digit at least),
         * and an exponent, `e` or `E` with its own sign and digits, which fits an int. Its digits may be any Unicode
         * decimal digits, as Java's `Character.digit` reads them. Anything else, a space or an empty text among them,
         * fails as Java's does, with a [NumberFormatException].
         */
        fun parse(text: String): JavaDecimal {
            fun refuse(): Nothing = throw NumberFormatException("Character array is missing \"exponent\" mark 'e' or 'E', or is no decimal: $text")
            var at = 0
            val negative = text.startsWith('-')
            if (negative || text.startsWith('+')) at++
            val digits = StringBuilder()
            var fraction = 0L
            var point = false
            while (at < text.length) {
                val c = text[at]
                val digit = if (c.isDigit()) c.digitToIntOrNull() else null
                when {
                    digit != null -> { digits.append('0' + digit); if (point) fraction++ }
                    c == '.' && !point -> point = true
                    c == 'e' || c == 'E' -> break
                    else -> refuse()
                }
                at++
            }
            if (digits.isEmpty()) refuse()
            var exponent = 0L
            if (at < text.length) {
                at++
                val sign = if (at < text.length && (text[at] == '-' || text[at] == '+')) text[at++] else '+'
                if (at == text.length) refuse()
                val exponentDigits = StringBuilder()
                while (at < text.length) {
                    val c = text[at++]
                    val digit = if (c.isDigit()) c.digitToIntOrNull() else null
                    exponentDigits.append('0' + (digit ?: refuse()))
                }
                // Java reads the exponent into an int, and fails where it, or the scale it makes, is past one.
                val magnitude = exponentDigits.toString().trimStart('0').ifEmpty { "0" }
                if (magnitude.length > 10) refuse()
                exponent = magnitude.toLong() * if (sign == '-') -1 else 1
                if (exponent !in Int.MIN_VALUE..Int.MAX_VALUE) refuse()
            }
            val scale = fraction - exponent
            if (scale !in Int.MIN_VALUE..Int.MAX_VALUE) refuse()
            val unscaled = BigInteger.parseString(digits.toString())
            return JavaDecimal(if (negative) -unscaled else unscaled, scale)
        }
    }
}
