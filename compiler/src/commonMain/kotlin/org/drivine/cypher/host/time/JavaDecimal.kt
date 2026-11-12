package org.drivine.cypher.host.time

import kotlin.math.log2

/**
 * A decimal as java.math's `BigDecimal` holds one, unscaled × 10^-[scale], with the few of its operations drivine4j's
 * temporal host functions use: reading a text as `BigDecimal(String)` does, `setScale` by `HALF_UP`, `stripTrailingZeros`,
 * `toPlainString`, and `longValue`, which keeps a whole number's low 64 bits, wrapping silently past a long's range.
 *
 * The unscaled value is held as its [digits] and a count of [zeros] after them, never as a number: a text's exponent
 * can be near ±2^31, and a power of ten that large, which Java refuses or takes minutes over, would hang the database
 * the function runs in. Each operation works on the digits, and fails as Java's does, as fast, where Java's
 * `BigInteger` would pass its range of [Int.MAX_VALUE] bits, or `BigInteger.pow` would refuse its power of ten.
 */
internal class JavaDecimal private constructor(
    private val negative: Boolean,
    /** The unscaled value's digits, without leading zeros: `0` for zero. */
    private val digits: String,
    /** How many zeros follow [digits] in the unscaled value. */
    private val zeros: Long,
    val scale: Long,
) {

    val signum: Int get() = if (digits == "0") 0 else if (negative) -1 else 1

    /**
     * This to [places] places, rounded half away from zero (`setScale(places, RoundingMode.HALF_UP)`), failing as Java's
     * does: "Underflow" where the places raise the scale by more than an int holds, and `BigInteger`'s overflow where
     * the power of ten Java multiplies or divides by is one `BigInteger.pow` refuses ([TEN_POW_REFUSED] or more), or
     * the unscaled value raised would pass its range.
     */
    fun halfUp(places: Long): JavaDecimal {
        if (places == scale) return this
        if (signum == 0) return JavaDecimal(false, "0", 0, places)
        if (places > scale) {
            val raise = places - scale
            if (raise > Int.MAX_VALUE) throw ArithmeticException("Underflow")
            if (raise >= TEN_POW_REFUSED || log2Of(digits.length + zeros + raise) >= Int.MAX_VALUE) overflow()
            return JavaDecimal(negative, digits, zeros + raise, places)
        }
        val drop = scale - places
        if (drop >= TEN_POW_REFUSED) overflow()
        if (drop <= zeros) return JavaDecimal(negative, digits, zeros - drop, places)
        // Half up: away from zero where the first digit dropped is 5 or more.
        val cut = drop - zeros
        if (cut > digits.length) return JavaDecimal(false, "0", 0, places)
        val kept = digits.substring(0, digits.length - cut.toInt())
        val rounded = if (digits[kept.length] >= '5') increment(kept) else kept.ifEmpty { "0" }
        return if (rounded == "0") JavaDecimal(false, "0", 0, places) else JavaDecimal(negative, rounded, 0, places)
    }

    /** `stripTrailingZeros`: the same number with no zeros ending the unscaled value, its scale lowered for each, past 0 too; zero is 0. */
    fun stripTrailingZeros(): JavaDecimal {
        if (signum == 0) return JavaDecimal(false, "0", 0, 0)
        val trimmed = digits.trimEnd('0')
        return JavaDecimal(negative, trimmed, 0, scale - zeros - (digits.length - trimmed.length))
    }

    /** `toPlainString`: the digits with a point [scale] places from the end, and no exponent; zeros after them where [scale] is negative. */
    fun toPlainString(): String {
        val sign = if (negative) "-" else ""
        val unscaled = digits + "0".repeat(zeros.toInt())
        return when {
            scale <= 0 -> sign + unscaled + "0".repeat((-scale).toInt())
            unscaled.length > scale -> sign + unscaled.substring(0, unscaled.length - scale.toInt()) + "." + unscaled.substring(unscaled.length - scale.toInt())
            else -> sign + "0." + unscaled.padStart(scale.toInt(), '0')
        }
    }

    /** `longValue` of a whole number ([scale] 0): its low 64 bits, as a long, wrapping past a long's range. */
    fun wrappedLong(): Long {
        check(scale == 0L) { "Not a whole number" }
        // A long's arithmetic wraps as the low 64 bits do; 10^64 and past are 0 in them, as 2^64 divides them.
        var low = 0L
        for (c in digits) low = low * 10 + (c - '0')
        repeat(minOf(zeros, 64L).toInt()) { low *= 10 }
        return if (negative) -low else low
    }

    /** log2 of [digits] followed by zeros to [length] digits: `bitLength - 1` is its floor, near enough to tell where it passes an int. */
    private fun log2Of(length: Long): Double {
        val lead = digits.take(17)
        return log2(lead.toDouble()) + (length - lead.length) * LOG2_10
    }

    companion object {
        private val LOG2_10 = log2(10.0)

        /**
         * The least n for which `BigInteger.TEN.pow(n)` fails at once, before writing anything out: its check
         * `bitLength() * n / Integer.SIZE > MAX_MAG_LENGTH` is 4n / 32 > 2^26 for ten. Below it, Java writes 10^n out.
         */
        private const val TEN_POW_REFUSED = 8L * ((1L shl 26) + 1)

        private fun overflow(): Nothing = throw ArithmeticException("BigInteger would overflow supported range")

        /** [digits] plus one: `""` is `1`, `999` is `1000`. */
        private fun increment(digits: String): String {
            val last = digits.indexOfLast { it != '9' }
            val head = if (last < 0) "1" else digits.substring(0, last) + (digits[last] + 1)
            return head + "0".repeat(digits.length - last - 1)
        }

        fun of(value: Long) = JavaDecimal(value < 0, value.toString().removePrefix("-"), 0, 0)

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
            val unscaled = digits.toString().trimStart('0').ifEmpty { "0" }
            return JavaDecimal(negative && unscaled != "0", unscaled, 0, scale)
        }
    }
}
