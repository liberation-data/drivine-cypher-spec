package org.drivine.cypher.oracle

import java.io.File
import java.io.PrintWriter
import java.math.BigDecimal
import java.math.BigInteger
import kotlin.random.Random

/**
 * Doubles as Java writes and reads them: the cases the double fuzz checks each target's [org.drivine.cypher.number]
 * against. The bit patterns are random (subnormals, NaNs and infinities among them), and so are the texts: Java's own
 * text of a random double, random decimals of up to 30 digits over the whole range of exponents and past it, the exact
 * halfway point between two doubles and texts a digit either side of it, and hexadecimals; then the edge cases.
 */
object Doubles {

    /** Writes about [count] cases of each kind, from [seed], to [into]: how many cases in all. */
    fun write(into: File, count: Int, seed: Long): Int {
        val random = Random(seed)
        var cases = 0
        into.printWriter().use { w ->
            fun text(d: Double) { w.text(d); cases++ }
            fun parse(s: String) { w.parse(s); cases++ }
            texts(random, count).forEach(::text)
            parses(random, count).forEach(::parse)
        }
        return cases
    }

    /** The doubles whose text is checked. */
    fun texts(random: Random, count: Int): Sequence<Double> = sequence {
        repeat(count) { yield(Double.fromBits(random.nextLong())) }
        // Subnormals, and the least of them.
        repeat(count / 4) { yield(Double.fromBits(random.nextLong() and 0x000FFFFFFFFFFFFFL)) }
        for (t in 1L..2000L) yield(Double.fromBits(t))
        // Each power of two and of ten, and the doubles either side.
        for (e in -1074..1023) yield(Math.scalb(1.0, e))
        for (e in -325..309) yield("1e$e".toDouble())
        for (d in listOf(1e-3, 1e7, 1e23, 1e22, 2.0, 0.1, 1.0 / 3, 9007199254740991.0, 9007199254740992.0, Double.MAX_VALUE, java.lang.Double.MIN_NORMAL)) yield(d)
        val edges = (-1074..1023).map { Math.scalb(1.0, it) } + (-325..309).map { "1e$it".toDouble() }
        for (d in edges) { yield(Math.nextUp(d)); yield(Math.nextDown(d)) }
        // Whole numbers, and numbers of few digits.
        repeat(count / 4) { yield(random.nextLong(-(1L shl 54), 1L shl 54).toDouble()) }
        repeat(count / 4) { yield("${random.nextInt(1, 1000)}e${random.nextInt(-330, 310)}".toDouble()) }
        for (d in listOf(0.0, -0.0, Double.NaN, Double.POSITIVE_INFINITY, Double.NEGATIVE_INFINITY)) yield(d)
    }

    /** The texts whose reading is checked. */
    fun parses(random: Random, count: Int): Sequence<String> = sequence {
        repeat(count) { yield(Double.fromBits(random.nextLong()).toString()) }
        repeat(count) { yield(randomDecimal(random)) }
        repeat(count / 4) {
            val a = Double.fromBits(random.nextLong() and Long.MAX_VALUE).takeIf { it.isFinite() && it != Double.MAX_VALUE } ?: 1.0
            val half = BigDecimal(a).add(BigDecimal(Math.nextUp(a))).divide(BigDecimal(2))
            val text = half.toString()
            yield(text)
            yield(half.toPlainString().take(400))
            // A digit's last place either side of the halfway point.
            val unit = BigDecimal.ONE.movePointLeft(half.scale())
            yield(half.add(unit).toString())
            yield(half.subtract(unit).toString())
            // The first digits of it, which a reading of the first 19 alone can't round.
            val digits = half.unscaledValue().toString()
            for (n in listOf(17, 18, 19, 20, 21, 25, 40)) if (digits.length > n) {
                yield(BigDecimal(BigInteger(digits.take(n)), half.scale() - (digits.length - n)).toString())
                yield(BigDecimal(BigInteger(digits.take(n) + "1"), half.scale() - (digits.length - n) + 1).toString())
            }
        }
        // Subnormal halfway points, the least of them included.
        repeat(count / 20) {
            val a = Double.fromBits(random.nextLong(0L, 0x0010000000000000L))
            yield(BigDecimal(a).add(BigDecimal(Math.nextUp(a))).divide(BigDecimal(2)).toString())
        }
        repeat(count / 4) { yield(randomHex(random)) }
        yieldAll(EDGES)
    }

    /** A decimal of 1 to 30 digits, a point somewhere or nowhere, and an exponent or none. */
    private fun randomDecimal(random: Random): String {
        val digits = (1..random.nextInt(1, 31)).joinToString("") { random.nextInt(10).toString() }
        val at = random.nextInt(-1, digits.length + 1)
        val body = when {
            at < 0 -> digits
            at == 0 -> ".$digits"
            else -> digits.substring(0, at) + "." + digits.substring(at)
        }
        val sign = listOf("", "", "-", "+")[random.nextInt(4)]
        val exponent = if (random.nextInt(4) == 0) "" else listOf("e", "E")[random.nextInt(2)] + random.nextInt(-360, 330)
        return sign + body + exponent
    }

    private fun randomHex(random: Random): String {
        val digits = (1..random.nextInt(1, 20)).joinToString("") { "0123456789abcdefABCDEF"[random.nextInt(22)].toString() }
        val at = random.nextInt(-1, digits.length + 1)
        val body = when {
            at < 0 -> digits
            at == 0 -> ".$digits"
            else -> digits.substring(0, at) + "." + digits.substring(at)
        }
        return "0x" + body + "p" + random.nextInt(-1100, 1100)
    }

    private val EDGES = listOf(
        "0", "-0", "0.0", "-0.0", "+0", "00", ".0", "0.", "1", "1.", ".5", "-.5", "+1.5", "1e0", "1E+0", "1e-0",
        "NaN", "-NaN", "+NaN", "Infinity", "-Infinity", "+Infinity", " 1.5 ", "\t2\n", "1.5f", "1.5F", "1.5d", "1.5D", "1e5f",
        "1.f", "0x1p0", "0x1.8p3", "0X1P-1074", "0x1p-1075", "0x1.0000000000001p-1075", "0x1.fffffffffffffp1023",
        "0x1.fffffffffffff8p1023", "0x.8p1", "0x1p1f", "0x1p1d", "0x10", "0x", "0xp1", "0x1p", "0x1p+",
        "", " ", "e5", "1e", "1e+", "1e-", ".", "-", "+", "1.2.3", "1e5.5", "NaNd", "Infinityf", "nan", "infinity", "inf",
        "1_000", "1,5", "--1", "+-1", "1 2", "0.5e", "ff", "d", "1ef", "\u0661", "1e2147483648", "1e-2147483649",
        "1e99999999999999999999", "1e-99999999999999999999", "0e99999999999999999999",
        "4.9e-324", "4.9E-324", "2.4703282292062327e-324", "2.4703282292062328e-324", "2.470328229206232720882538e-324",
        "1e-324", "3e-324", "2.2250738585072011e-308", "2.2250738585072012e-308", "2.2250738585072014E-308",
        "1.7976931348623157e308", "1.7976931348623158e308", "1.7976931348623159e308", "1.797693134862315807e308", "1e309",
        "9007199254740993", "9007199254740992.5", "9007199254740993.0000000000000000000001", "1e23", "8.98846567431158e307",
        "0.1", "0.30000000000000004", "123456789012345678901234567890", "0." + "0".repeat(400) + "1",
        "1" + "0".repeat(400), "1" + "0".repeat(320) + "e-10", "0.000000000000000000000000000000000000001e40",
    )

    private fun PrintWriter.text(d: Double) {
        println("T\t${java.lang.Long.toHexString(d.toRawBits())}\t$d")
    }

    private fun PrintWriter.parse(s: String) {
        val bits = try {
            java.lang.Long.toHexString(java.lang.Double.parseDouble(s).toRawBits())
        } catch (e: NumberFormatException) {
            "-"
        }
        // A text is written with its whitespace escaped, as the line is split at tabs and newlines.
        println("P\t$bits\t${escape(s)}")
    }

    fun escape(s: String) = s.replace("\\", "\\\\").replace("\t", "\\t").replace("\n", "\\n")

    fun unescape(s: String): String {
        val out = StringBuilder()
        var i = 0
        while (i < s.length) {
            if (s[i] == '\\' && i + 1 < s.length) {
                out.append(when (s[i + 1]) { 't' -> '\t'; 'n' -> '\n'; else -> s[i + 1] }); i += 2
            } else out.append(s[i++])
        }
        return out.toString()
    }
}
