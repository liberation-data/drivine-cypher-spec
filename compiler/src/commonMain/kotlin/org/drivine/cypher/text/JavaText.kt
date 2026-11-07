package org.drivine.cypher.text

/**
 * The few calls on text the compiler made of Java, where its answer, or the message it throws, reaches the SQL or a refusal:
 * as Java gives them on every target. Common Kotlin's own differ off the JVM (a lone surrogate's UTF-8, `toInt(16)`'s and
 * `substring`'s messages), and the stand-in fuzz checks each against Java's.
 */
object JavaText {

    /**
     * [text] in UTF-8 as Java's `String.getBytes(UTF_8)` encodes it: a lone surrogate as `?`, where Kotlin/Native and
     * Kotlin/Wasm write U+FFFD's three bytes.
     */
    fun utf8(text: String): ByteArray {
        if (text.none { it.isSurrogate() }) return text.encodeToByteArray()
        val out = StringBuilder(text.length)
        var i = 0
        while (i < text.length) {
            val c = text[i]
            if (c.isHighSurrogate() && i + 1 < text.length && text[i + 1].isLowSurrogate()) {
                out.append(c).append(text[i + 1])
                i += 2
            } else {
                out.append(if (c.isSurrogate()) '?' else c)
                i++
            }
        }
        return out.toString().encodeToByteArray()
    }

    /**
     * As Java's `String.valueOf(value)`, where a refusal writes a value the statement gave: a double (or a float) as Java
     * writes it, in a list or a map too. Kotlin/Wasm's `Double.toString` is JavaScript's (`10000000.0` where Java writes
     * `1.0E7`).
     */
    fun of(value: Any?): String = when (value) {
        is Double -> org.drivine.cypher.json.jvmDoubleText(value)
        is Float -> org.drivine.cypher.json.jvmFloatText(value)
        is List<*> -> value.joinToString(", ", "[", "]", transform = ::of)
        is Map<*, *> -> value.entries.joinToString(", ", "{", "}") { (k, v) -> of(k) + "=" + of(v) }
        else -> value.toString()
    }

    /** As Java's `text.substring(begin, end)`, and the message of the `StringIndexOutOfBoundsException` it throws (JDK 21's). */
    fun substring(text: String, begin: Int, end: Int): String {
        if (begin < 0 || begin > end || end > text.length) throw IndexOutOfBoundsException("Range [$begin, $end) out of bounds for length ${text.length}")
        return text.substring(begin, end)
    }

    /**
     * As Java's `Integer.parseInt(text, radix)`: a sign, then digits as `Character.digit` reads them (any Unicode decimal
     * digit, and fullwidth letters), and the message of the `NumberFormatException` it throws.
     */
    fun parseInt(text: String, radix: Int): Int {
        fun refused(): Nothing = throw NumberFormatException("For input string: \"$text\"" + if (radix == 10) "" else " under radix $radix")
        if (text.isEmpty()) refused()
        var i = 0
        var negative = false
        var limit = -Int.MAX_VALUE
        if (text[0] < '0') {
            when (text[0]) {
                '-' -> { negative = true; limit = Int.MIN_VALUE }
                '+' -> {}
                else -> refused()
            }
            if (text.length == 1) refused()
            i++
        }
        val least = limit / radix
        var result = 0
        while (i < text.length) {
            val digit = text[i++].digitToIntOrNull(radix) ?: refused()
            if (result < least) refused()
            result *= radix
            if (result < limit + digit) refused()
            result -= digit
        }
        return if (negative) result else -result
    }

    /** As Java's `out.appendCodePoint(codePoint)`, and the message of the `IllegalArgumentException` it throws. */
    fun appendCodePoint(out: StringBuilder, codePoint: Int): StringBuilder {
        when {
            codePoint in 0 until 0x10000 -> out.append(codePoint.toChar())
            codePoint in 0x10000..0x10FFFF -> {
                val v = codePoint - 0x10000
                out.append((0xD800 + (v shr 10)).toChar()).append((0xDC00 + (v and 0x3FF)).toChar())
            }
            else -> throw IllegalArgumentException("Not a valid Unicode code point: 0x" + (codePoint.toLong() and 0xFFFFFFFFL).toString(16).uppercase())
        }
        return out
    }

    /**
     * As Java's `Character.isLetter`: Kotlin's `Char.isLetter`, and the letters Unicode 14 and 15 added, which JDK 21 has
     * and Kotlin's own tables off the JVM lack. The stand-in fuzz checks every character against the pinned JDK's.
     */
    fun isLetter(c: Char): Boolean = c.isLetter() || c in NEWER_LETTERS

    /** As Java's `Character.isLetterOrDigit`: see [isLetter]. */
    fun isLetterOrDigit(c: Char): Boolean = c.isLetterOrDigit() || c in NEWER_LETTERS

    /** The letters of the Basic Multilingual Plane that Unicode 14 and 15 added. */
    private val NEWER_LETTERS: Set<Char> = listOf(
        0x870..0x887, 0x889..0x88E, 0x8B5..0x8B5, 0x8C8..0x8C9, 0xC5D..0xC5D, 0xCDD..0xCDD, 0x170D..0x170D, 0x171F..0x171F,
        0x1B4C..0x1B4C, 0x2C2F..0x2C2F, 0x2C5F..0x2C5F, 0x9FFD..0x9FFF, 0xA7C0..0xA7C1, 0xA7D0..0xA7D1, 0xA7D3..0xA7D3,
        0xA7D5..0xA7D9, 0xA7F2..0xA7F4,
    ).flatMap { range -> range.map { it.toChar() } }.toSet()
}
