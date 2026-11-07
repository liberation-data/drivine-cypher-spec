package org.drivine.cypher.number

import com.ionspin.kotlin.bignum.integer.BigInteger

/**
 * A text read as the JVM's `Double.parseDouble` reads it, on every platform: the double nearest its value, ties to even.
 * Kotlin/Wasm's own `String.toDouble` is an ulp off for about one double in 400.
 *
 * The text is Java's: whitespace (any character up to U+0020) around it, a sign, then `NaN`, `Infinity`, a decimal
 * (`1`, `1.`, `.5`, `1.5e-3`) or a hexadecimal (`0x1.8p3`), and after a number `f`, `F`, `d` or `D`, which changes
 * nothing. A decimal is read by Eisel and Lemire's algorithm (D. Lemire, "Number Parsing at a Gigabyte per Second",
 * 2021), and where that can't tell which way it rounds, exactly, with big integers.
 */
object DoubleParse {

    /** [text] as `Double.parseDouble` reads it; null where it throws `NumberFormatException`. */
    fun parseOrNull(text: String): Double? {
        var start = 0
        var end = text.length
        while (start < end && text[start] <= ' ') start++
        while (end > start && text[end - 1] <= ' ') end--
        if (start == end) return null
        var i = start
        val negative = text[i] == '-'
        if (text[i] == '-' || text[i] == '+') i++
        if (i == end) return null
        val value = when (text[i]) {
            // NaN has no sign: Java reads -NaN as NaN.
            'N' -> return if (text.regionMatches(i, "NaN", 0, 3) && i + 3 == end) Double.NaN else null
            'I' -> if (text.regionMatches(i, "Infinity", 0, 8) && i + 8 == end) Double.POSITIVE_INFINITY else return null
            else -> {
                if (end - i > 1 && "fFdD".indexOf(text[end - 1]) >= 0 && !hex(text, i, end)) end--
                if (hex(text, i, end)) hexadecimal(text, i + 2, end) ?: return null
                else decimal(text, i, end) ?: return null
            }
        }
        return if (negative) -value else value
    }

    private fun hex(text: String, i: Int, end: Int) = end - i > 2 && text[i] == '0' && (text[i + 1] == 'x' || text[i + 1] == 'X')

    /** A decimal from [from] to [end], unsigned: null where it is none. */
    private fun decimal(text: String, from: Int, end: Int): Double? {
        var i = from
        // The significant digits, from the first that isn't 0, and the point's place among them.
        var digits = 0
        var w = 0L
        var exponent = 0L
        var seen = false
        var truncated = false
        // Whether a digit past those kept for the exact reading isn't 0.
        var beyond = false
        val first = StringBuilder()
        fun digit(d: Int, afterPoint: Boolean) {
            seen = true
            if (digits == 0 && d == 0) {
                if (afterPoint) exponent--
                return
            }
            if (digits < 19) w = w * 10 + d
            else if (d != 0) truncated = true
            if (digits >= 19 && !afterPoint) exponent++
            if (digits < 19 && afterPoint) exponent--
            digits++
            if (digits <= MAX_EXACT_DIGITS) first.append(('0' + d)) else if (d != 0) beyond = true
        }
        while (i < end && text[i] in '0'..'9') { digit(text[i] - '0', false); i++ }
        if (i < end && text[i] == '.') {
            i++
            while (i < end && text[i] in '0'..'9') { digit(text[i] - '0', true); i++ }
        }
        if (!seen) return null
        if (i < end && (text[i] == 'e' || text[i] == 'E')) {
            i++
            var negativeExponent = false
            if (i < end && (text[i] == '-' || text[i] == '+')) { negativeExponent = text[i] == '-'; i++ }
            if (i == end) return null
            var written = 0L
            val digitsFrom = i
            while (i < end && text[i] in '0'..'9') {
                // Past any exponent a double can have, it is the same.
                if (written < 1_000_000_000L) written = written * 10 + (text[i] - '0')
                i++
            }
            if (i == digitsFrom) return null
            exponent += if (negativeExponent) -written else written
        }
        if (i != end) return null
        if (digits == 0) return 0.0
        // w·10^exponent, w the first 19 digits; the value is between it and (w + 1)·10^exponent where it is truncated.
        val q = exponent.coerceIn(-100_000L, 100_000L).toInt()
        val fast = eiselLemire(w, q)
        if (fast != null && (!truncated || eiselLemire(w + 1, q) == fast)) return fast
        return exact(first.toString(), digits, q, beyond)
    }

    /**
     * w·10^q as the nearest double, by Eisel-Lemire: w normalized, times 5^q from a 128-bit table, gives the double's
     * 53 bits and their rounding, unless the product is too near a halfway point to tell: then null.
     */
    private fun eiselLemire(w0: Long, q: Int): Double? {
        if (w0 == 0L || q < DoubleTables.Q_MIN) return 0.0
        if (q > DoubleTables.Q_MAX) return Double.POSITIVE_INFINITY
        val lz = w0.countLeadingZeroBits()
        val w = w0 shl lz
        val at = 2 * (q - DoubleTables.Q_MIN)
        var lo = w * DoubleTables.FIVE[at]
        var hi = Wide.multiplyHigh(w, DoubleTables.FIVE[at])
        // Where the bits below the 55 that matter are all ones, the table's lower half may carry into them.
        if (hi and 0x1FFL == 0x1FFL) {
            val carry = Wide.multiplyHigh(w, DoubleTables.FIVE[at + 1])
            val sum = lo + carry
            if (Wide.unsignedLess(sum, carry)) hi++
            lo = sum
        }
        if (lo == -1L && (q < -27 || q > 55)) return null
        val upper = (hi ushr 63).toInt()
        var mantissa = hi ushr (upper + 64 - 52 - 3)
        var power2 = (((152_170 + 65_536) * q) shr 16) + 63 + upper - lz + 1023
        if (power2 <= 0) {
            // Subnormal.
            if (-power2 + 1 >= 64) return 0.0
            mantissa = mantissa ushr (-power2 + 1)
            mantissa += mantissa and 1L
            mantissa = mantissa ushr 1
            power2 = if (mantissa < (1L shl 52)) 0 else 1
            return Double.fromBits((power2.toLong() shl 52) or mantissa)
        }
        // A halfway point exactly, which rounds to even: only small exponents can give one.
        if ((lo == 0L || lo == 1L) && q >= -4 && q <= 23 && (mantissa and 3L) == 1L &&
            (mantissa shl (upper + 64 - 52 - 3)) == hi
        ) {
            mantissa = mantissa and 1L.inv()
        }
        mantissa += mantissa and 1L
        mantissa = mantissa ushr 1
        if (mantissa >= (2L shl 52)) {
            mantissa = 1L shl 52
            power2++
        }
        mantissa = mantissa and (1L shl 52).inv()
        if (power2 >= 0x7FF) return Double.POSITIVE_INFINITY
        return Double.fromBits((power2.toLong() shl 52) or mantissa)
    }

    /**
     * A decimal exactly, as the nearest double: [written] holds its first significant digits, [count] of them in all, and
     * [q] is the exponent of the 19th where there are more than 19, of the last where there are fewer. Where digits past
     * [written] aren't all 0 ([inexact]), they only put the decimal above [written]'s, which is all a tie needs to know:
     * a halfway point between two doubles has fewer significant digits than [written] holds.
     */
    private fun exact(written: String, count: Int, q: Int, inexact: Boolean): Double {
        // q is the exponent of the 19th digit where there are more; the exponent of the digits written is then lower.
        val kept = written.length
        val q10 = if (count > 19) q + 19 - kept else q
        // Its leading digit is 10^(kept - 1 + q10).
        val lead = kept - 1 + q10
        if (lead < -325) return 0.0
        if (lead > 309) return Double.POSITIVE_INFINITY
        val digits = BigInteger.parseString(written)
        return if (q10 >= 0) binary(digits * BigInteger.TEN.pow(q10), 0, inexact)
        else {
            val divisor = BigInteger.TEN.pow(-q10)
            // Enough bits of quotient for 53 and two to round with.
            val shift = maxOf(0, 56 + divisor.bitLength() - digits.bitLength())
            val (quotient, remainder) = (digits shl shift).divideAndRemainder(divisor)
            binary(quotient, -shift, inexact || !remainder.isZero())
        }
    }

    /** [hex] digits from [from] to [end], then `p` and the binary exponent: null where it is none. */
    private fun hexadecimal(text: String, from: Int, end: Int): Double? {
        var i = from
        var mantissa = BigInteger.ZERO
        var exponent = 0L
        var seen = false
        fun hexDigit(c: Char) = when (c) { in '0'..'9' -> c - '0'; in 'a'..'f' -> c - 'a' + 10; in 'A'..'F' -> c - 'A' + 10; else -> -1 }
        while (i < end && hexDigit(text[i]) >= 0) { mantissa = mantissa * 16 + hexDigit(text[i]); seen = true; i++ }
        if (i < end && text[i] == '.') {
            i++
            while (i < end && hexDigit(text[i]) >= 0) { mantissa = mantissa * 16 + hexDigit(text[i]); exponent -= 4; seen = true; i++ }
        }
        if (!seen || i == end || (text[i] != 'p' && text[i] != 'P')) return null
        i++
        var negativeExponent = false
        if (i < end && (text[i] == '-' || text[i] == '+')) { negativeExponent = text[i] == '-'; i++ }
        if (i == end) return null
        var written = 0L
        val digitsFrom = i
        while (i < end && text[i] in '0'..'9') {
            if (written < 1_000_000_000L) written = written * 10 + (text[i] - '0')
            i++
        }
        if (i == digitsFrom) return null
        // A suffix, which changes nothing.
        if (i == end - 1 && "fFdD".indexOf(text[i]) >= 0) i++
        if (i != end) return null
        exponent += if (negativeExponent) -written else written
        if (mantissa.isZero()) return 0.0
        val lead = mantissa.bitLength() - 1 + exponent
        if (lead < -1080) return 0.0
        if (lead > 1030) return Double.POSITIVE_INFINITY
        return binary(mantissa, exponent.toInt(), false)
    }

    /**
     * [m]·2^[e], with a little more where [sticky], as the nearest double, ties to even: m's leading 53 bits, fewer for
     * a subnormal, rounded by the bits below them.
     */
    private fun binary(m: BigInteger, e: Int, sticky: Boolean): Double {
        if (m.isZero()) return 0.0
        val lead = m.bitLength() - 1 + e
        if (lead > 1023) return Double.POSITIVE_INFINITY
        // The exponent of the double's last bit: 52 below its leading one, and no lower than a subnormal's.
        var last = maxOf(lead - 52, -1074)
        val drop = last - e
        var mantissa: Long
        if (drop <= 0) {
            mantissa = (m shl -drop).longValue(exactRequired = false)
        } else {
            val kept = m shr drop
            mantissa = kept.longValue(exactRequired = false)
            val rest = m - (kept shl drop)
            val half = BigInteger.ONE shl (drop - 1)
            val c = rest.compareTo(half)
            if (c > 0 || c == 0 && (sticky || (mantissa and 1L) == 1L)) mantissa++
        }
        if (mantissa == 1L shl 53) {
            mantissa = 1L shl 52
            last++
        }
        if (last > 971) return Double.POSITIVE_INFINITY
        if (mantissa < (1L shl 52)) return Double.fromBits(mantissa)
        return Double.fromBits(((last + 1075).toLong() shl 52) or (mantissa and ((1L shl 52) - 1)))
    }

    /** The digits past which a decimal is read as these and more: past them, its digits can't make a tie. */
    private const val MAX_EXACT_DIGITS = 800
}
