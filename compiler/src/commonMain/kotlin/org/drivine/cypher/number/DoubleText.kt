package org.drivine.cypher.number

/**
 * A double as the JVM's `Double.toString` writes it (JDK 19 and later), on every platform: Kotlin/Wasm and Kotlin/Native
 * write some doubles otherwise (Kotlin/Native about one in 1,800, Kotlin/Wasm `1e23` as `9.999999999999999E22`).
 *
 * Java's text is the decimal that `Double.toString`'s specification picks: of the decimals that round to the double, those
 * of the fewest digits (two at least), and of those the one nearest the double, an even last digit breaking a tie. That is
 * what R. Giulietti's Schubfach algorithm gives, which this implements from his paper, "The Schubfach way to render
 * doubles" (2020, revised 2022), with the paper's notation. It is not a copy of OpenJDK's source, which is GPLv2. Its
 * tables of powers of ten are worked out exactly by the build (gen/Generate.java).
 */
object DoubleText {

    /** The text `Double.toString` gives [v]. */
    fun of(v: Double): String {
        val bits = v.toRawBits()
        val t = bits and T_MASK
        val bq = ((bits ushr 52) and 0x7FF).toInt()
        if (bq == 0x7FF) return if (t != 0L) "NaN" else if (bits < 0) "-Infinity" else "Infinity"
        val sign = if (bits < 0) "-" else ""
        if (bq == 0) {
            if (t == 0L) return sign + "0.0"
            // A subnormal too small for two digits at its own precision is worked out at ten times it.
            return sign + if (t < C_TINY) decimal(Q_MIN, 10 * t, -1) else decimal(Q_MIN, t, 0)
        }
        val q = bq - 1075
        val c = C_MIN or t
        // A whole number below 2^53 is its own digits.
        if (q < 0 && q > -P) {
            val f = c shr -q
            if (f shl -q == c) return sign + layout(f, 0)
        }
        return sign + decimal(q, c, 0)
    }

    /**
     * The decimal of c·2^q, where [dk] is -1 if c is ten times the double's own: the decimal's digits and exponent, as
     * [layout] writes them. Section 9 of the paper: the double's rounding interval, scaled by 10^-k so that it is 1 to
     * 10 wide, holds one or two whole numbers, s and s + 1, and maybe a multiple of ten, which is one digit fewer.
     */
    private fun decimal(q: Int, c: Long, dk: Int): String {
        // The interval's bounds are in it where c is even (round half to even), and out of it where c is odd.
        val out = (c and 1L)
        val cb = c shl 2
        val cbr = cb + 2
        val cbl: Long
        val k: Int
        if (c != C_MIN || q == Q_MIN) {
            cbl = cb - 2
            k = floorLog10Pow2(q)
        } else {
            // A power of two's interval is narrower below it than above.
            cbl = cb - 1
            k = floorLog10ThreeQuartersPow2(q)
        }
        val h = q + floorLog2Pow10(-k) + 2
        val at = 2 * (k - DoubleTables.K_MIN)
        val g1 = DoubleTables.G[at]
        val g0 = DoubleTables.G[at + 1]
        // 4·c·2^q·10^-k and the interval's bounds, with a bit that says whether each is inexact.
        val vb = scaled(g1, g0, cb shl h)
        val vbl = scaled(g1, g0, cbl shl h)
        val vbr = scaled(g1, g0, cbr shl h)
        val s = vb shr 2
        if (s >= 100) {
            val sp10 = 10 * (s / 10)
            val tp10 = sp10 + 10
            val upIn = vbl + out <= sp10 shl 2
            val wpIn = (tp10 shl 2) + out <= vbr
            if (upIn != wpIn) return layout(if (upIn) sp10 else tp10, k + dk)
        }
        val u = s
        val w = s + 1
        val uIn = vbl + out <= u shl 2
        val wIn = (w shl 2) + out <= vbr
        if (uIn != wIn) return layout(if (uIn) u else w, k + dk)
        // Both are in: the nearer, the even one where they are as near.
        val cmp = vb - ((u + w) shl 1)
        return layout(if (cmp < 0 || cmp == 0L && (u and 1L) == 0L) u else w, k + dk)
    }

    /**
     * floor(g·cp / 2^127), with its lowest bit set where that floor is inexact, g being g1·2^63 + g0: the paper's
     * "rop", rounding to odd, which keeps whether a product compared with a bound is exact.
     */
    private fun scaled(g1: Long, g0: Long, cp: Long): Long {
        val x1 = Wide.multiplyHigh(g0, cp)
        val y0 = g1 * cp
        val y1 = Wide.multiplyHigh(g1, cp)
        val z = (y0 ushr 1) + x1
        val vbp = y1 + (z ushr 63)
        return vbp or (((z and MASK_63) + MASK_63) ushr 63)
    }

    /**
     * The decimal f·10^e as Java writes it: plain from 10^-3 up to 10^7, with a digit after the point at least
     * (`100.0`, `0.001`); otherwise one digit, the point, the rest (`0` where there is none) and `E` with the exponent
     * (`1.0E7`, `4.9E-324`).
     */
    internal fun layout(f: Long, e: Int): String {
        var digits = f.toString()
        var exponent = e
        val kept = digits.trimEnd('0')
        exponent += digits.length - kept.length
        digits = kept
        // The decimal is 0.digits × 10^point.
        val point = digits.length + exponent
        val out = StringBuilder(26)
        if (point in -2..7) {
            when {
                point <= 0 -> out.append("0.").append("0".repeat(-point)).append(digits)
                point >= digits.length -> out.append(digits).append("0".repeat(point - digits.length)).append(".0")
                else -> out.append(digits, 0, point).append('.').append(digits, point, digits.length)
            }
        } else {
            out.append(digits[0]).append('.')
            if (digits.length > 1) out.append(digits, 1, digits.length) else out.append('0')
            out.append('E').append(point - 1)
        }
        return out.toString()
    }

    /** floor(q·log10(2)), for |q| up to 5,456 or so. */
    internal fun floorLog10Pow2(q: Int): Int = ((q.toLong() * 661_971_961_083L) shr 41).toInt()

    /** floor(q·log10(2) + log10(3/4)), for |q| up to 5,456 or so. */
    internal fun floorLog10ThreeQuartersPow2(q: Int): Int = ((q.toLong() * 661_971_961_083L - 274_743_187_321L) shr 41).toInt()

    /** floor(e·log2(10)), for |e| up to 1,233 or so. */
    internal fun floorLog2Pow10(e: Int): Int = ((e.toLong() * 913_124_641_741L) shr 38).toInt()

    private const val P = 53
    private const val Q_MIN = -1074
    private const val C_MIN = 1L shl 52
    private const val C_TINY = 3L
    private const val T_MASK = (1L shl 52) - 1
    private const val MASK_63 = (1L shl 63) - 1
}

/** 64-bit arithmetic common Kotlin has no function for. */
internal object Wide {
    /** The upper 64 bits of the 128-bit product of [x] and [y], each read as unsigned. */
    fun multiplyHigh(x: Long, y: Long): Long {
        val x0 = x and 0xFFFFFFFFL
        val x1 = x ushr 32
        val y0 = y and 0xFFFFFFFFL
        val y1 = y ushr 32
        val w0 = x0 * y0
        val t = x1 * y0 + (w0 ushr 32)
        val w1 = (t and 0xFFFFFFFFL) + x0 * y1
        return x1 * y1 + (t ushr 32) + (w1 ushr 32)
    }

    /** Whether [x] is below [y], each read as unsigned. */
    fun unsignedLess(x: Long, y: Long): Boolean = (x xor Long.MIN_VALUE) < (y xor Long.MIN_VALUE)
}
