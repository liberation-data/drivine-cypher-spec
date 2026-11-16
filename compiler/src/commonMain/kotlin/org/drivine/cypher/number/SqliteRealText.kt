package org.drivine.cypher.number

import com.ionspin.kotlin.bignum.integer.BigInteger
import org.drivine.DrivineInternalApi

/**
 * The text SQLite gives a REAL as, where a function reads it as text (`sqlite3_value_text`, `CAST(x AS TEXT)`), as SQLite
 * 3.43 and later write it: a text that reads back as the same double. Its value rounded half up to 15 significant digits
 * where those read back as it, as SQLite's are; else to the fewest of 16 and 17 that do. Written as a decimal, or as an
 * exponent where that is below -4 or at least 17, trailing zeros left out but one digit kept after the point (`0.1`, `1.0`,
 * `10000000000000000.0`, `1.0e+17`, `1.5e-05`).
 *
 * Where 15 digits don't read back, SQLite works its 17 out in long double, or in double-double arithmetic where the platform
 * has no long double, so its last digit can differ by platform from the exact value's (it writes 1/3 as `0.33333333333333332`
 * on an ARM Mac, whose exact 17 digits end in 1). There this gives the same value, not always SQLite's spelling: SqliteRealTextTest
 * pins both against the JVM's SQLite, value by value. A subnormal, below 2.2250738585072014e-308, SQLite writes in 17 where
 * its own reading of the 15 doesn't give the value back, so there too only the value is SQLite's.
 * [org.drivine.cypher.host.HostCall] reads a REAL it is given as text so, as SQLite would hand it to the function.
 */
@DrivineInternalApi
object SqliteRealText {

    /** The digits SQLite tries first, and the most it writes, as many as read any double back. */
    private const val SHORT = 15
    private const val DIGITS = 17

    fun of(value: Double): String {
        // SQLite's Inf, and NaN, read back as no double: written as they are.
        if (!value.isFinite()) return written(value, DIGITS)
        for (significant in SHORT until DIGITS) {
            val text = written(value, significant)
            // parseDouble, not toDouble: Kotlin/Wasm's is an ulp off for some, and would keep digits that don't read back.
            if (org.drivine.cypher.json.parseDouble(text) == value) return text
        }
        return written(value, DIGITS)
    }

    private fun written(value: Double, significant: Int): String {
        if (value.isNaN()) return "NaN"
        if (value.isInfinite()) return if (value > 0) "Inf" else "-Inf"
        // Minus zero too: SQLite writes no sign of a zero.
        if (value == 0.0) return "0.0"
        val negative = value < 0
        val (digits, point) = rounded(kotlin.math.abs(value), significant)
        // digits: [significant] decimal digits, d1 d2 …; the value is 0.d1d2… × 10^point.
        val exponent = point - 1
        val body = if (exponent < -4 || exponent >= DIGITS) {
            val mantissa = trimmed(digits.substring(0, 1) + "." + digits.substring(1))
            val e = kotlin.math.abs(exponent).toString().padStart(2, '0')
            mantissa + "e" + (if (exponent < 0) "-" else "+") + e
        } else if (point <= 0) {
            trimmed("0." + "0".repeat(-point) + digits)
        } else {
            val all = digits.padEnd(point + 1, '0')
            trimmed(all.substring(0, point) + "." + all.substring(point))
        }
        return if (negative) "-$body" else body
    }

    /** [text], a number with a point, without the zeros that end its fraction, but one digit kept after the point. */
    private fun trimmed(text: String): String {
        val cut = text.trimEnd('0')
        return if (cut.endsWith('.')) cut + "0" else cut
    }

    /**
     * The [significant] digits of the positive finite [value], rounded half up from its exact decimal value, and the
     * place of its point: the value is `0.<digits> × 10^point`.
     */
    private fun rounded(value: Double, significant: Int): Pair<String, Int> {
        val bits = value.toRawBits()
        val exponentBits = ((bits ushr 52) and 0x7ff).toInt()
        val fraction = bits and 0xfffffffffffffL
        val mantissa = if (exponentBits == 0) fraction else fraction or (1L shl 52)
        val e2 = (if (exponentBits == 0) 1 else exponentBits) - 1075
        // The exact value as an integer [scaled] over 10^[scale].
        val (scaled, scale) = if (e2 >= 0) {
            BigInteger.fromLong(mantissa).shl(e2) to 0
        } else {
            BigInteger.fromLong(mantissa).multiply(BigInteger.fromInt(5).pow(-e2)) to -e2
        }
        val all = scaled.toString()
        var point = all.length - scale
        val kept = if (all.length > significant) {
            val head = BigInteger.parseString(all.substring(0, significant))
            val up = all[significant] >= '5'
            val rounded = if (up) head + BigInteger.ONE else head
            val text = rounded.toString()
            if (text.length > significant) { point += 1; text.substring(0, significant) } else text
        } else {
            all.padEnd(significant, '0')
        }
        return kept to point
    }
}
