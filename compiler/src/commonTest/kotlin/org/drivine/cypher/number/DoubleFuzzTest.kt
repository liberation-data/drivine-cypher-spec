package org.drivine.cypher.number

import org.drivine.cypher.json.jvmDoubleText
import org.drivine.cypher.json.parseDoubleOrNull
import kotlin.test.Test
import kotlin.test.assertTrue

/**
 * The double fuzz: on each target, doubles are written and texts read exactly as Java writes and reads them, checked
 * against what the JVM wrote (doubles.txt: see the JVM's Oracle). [DoubleText] and [DoubleParse] are checked on every
 * target, the JVM included, and so are the functions the compiler calls, which on the JVM are Java's own.
 */
class DoubleFuzzTest {

    @Test
    fun doublesAreWrittenAndReadAsJavaWritesAndReadsThem() {
        var texts = 0
        var parses = 0
        val wrong = mutableListOf<String>()
        for (line in oracleFile("doubles.txt").lineSequence()) {
            if (line.isEmpty()) continue
            val (kind, bits, text) = line.split('\t', limit = 3)
            when (kind) {
                "T" -> {
                    texts++
                    val d = Double.fromBits(bits.toULong(16).toLong())
                    val common = DoubleText.of(d)
                    val platform = jvmDoubleText(d)
                    if (common != text || platform != text) wrong += "text of $bits: Java $text, DoubleText $common, platform $platform"
                }
                "P" -> {
                    parses++
                    val written = unescape(text)
                    val common = hex(DoubleParse.parseOrNull(written))
                    val platform = hex(parseDoubleOrNull(written))
                    if (common != bits || platform != bits) wrong += "reading of \"$text\": Java $bits, DoubleParse $common, platform $platform"
                }
            }
        }
        println("Double fuzz on $target: $texts texts and $parses readings, ${wrong.size} unlike Java's")
        assertTrue(texts > 0 && parses > 0, "doubles.txt holds no cases")
        assertTrue(wrong.isEmpty(), "${wrong.size} unlike Java's, such as:\n" + wrong.take(40).joinToString("\n"))
    }

    private fun hex(d: Double?) = d?.toRawBits()?.toULong()?.toString(16) ?: "-"

    private fun unescape(s: String): String {
        if ('\\' !in s) return s
        val out = StringBuilder()
        var i = 0
        while (i < s.length) {
            if (s[i] == '\\' && i + 1 < s.length) {
                out.append(when (s[i + 1]) { 't' -> '\t'; 'n' -> '\n'; else -> s[i + 1] })
                i += 2
            } else {
                out.append(s[i++])
            }
        }
        return out.toString()
    }
}
