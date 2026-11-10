package org.drivine.cypher.text

import org.drivine.cypher.oracle.StandIns
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertTrue

/**
 * [CommonUnicode], what the targets but the JVM read a full-text query by, against Java itself on the JVM: every code point's
 * classes and lower case, every code point beside a capital sigma in each place its word may hold it, and random texts of
 * the characters a final sigma turns on. The stand-in fuzz checks each target against Java's answers over fewer texts.
 */
class CommonUnicodeTest {

    private fun show(s: String) = s.codePoints().toArray().joinToString(" ") { it.toString(16) }

    @Test
    fun everyCodePointIsClassedAndLoweredAsJavaClassesAndLowersIt() {
        val wrong = mutableListOf<String>()
        val oracle = setOf(Character.LETTER_NUMBER, Character.NON_SPACING_MARK, Character.COMBINING_SPACING_MARK)
        for (cp in 0..0x10FFFF) {
            val word = Character.isAlphabetic(cp) || Character.getType(cp) == Character.DECIMAL_DIGIT_NUMBER.toInt() || cp == '_'.code
            if (CommonUnicode.isWordChar(cp) != word) wrong += "word U+${cp.toString(16)}"
            val alnum = Character.isLetter(cp) || Character.isDigit(cp) || Character.getType(cp).toByte() in oracle
            if (CommonUnicode.isOracleAlnum(cp) != alnum) wrong += "alnum U+${cp.toString(16)}"
            val text = if (cp in 0xD800..0xDFFF) cp.toChar().toString() else String(Character.toChars(cp))
            if (CommonUnicode.lowercase(text) != text.lowercase()) wrong += "lower U+${cp.toString(16)}"
        }
        assertTrue(wrong.isEmpty(), "${wrong.size} unlike Java's, such as ${wrong.take(20)}")
    }

    @Test
    fun aCapitalSigmaBesideEachCodePointIsFinalWhereJavaReadsItFinal() {
        val wrong = mutableListOf<String>()
        for (cp in 0..0x10FFFF) {
            val c = if (cp in 0xD800..0xDFFF) cp.toChar().toString() else String(Character.toChars(cp))
            // Beside it, and with a cased mark (U+0345) that only a word or a number takes in after it.
            val contexts = listOf("${c}Σ", "1${c}Σ", "Α${c}Σ", "ΑΣ${c}", "ΑΣ${c}Β", "Σ${c}", "a-${c}Σ", "Α${c}-Σ", "1,${c}Σ", "${c}ͅΣ", "ΑΣ${c}ͅ", "\$${c}ͅΣ")
            for (text in contexts) {
                if (CommonUnicode.lowercase(text) != text.lowercase()) wrong += "${show(text)}: Java ${show(text.lowercase())}, ours ${show(CommonUnicode.lowercase(text))}"
            }
        }
        assertTrue(wrong.isEmpty(), "${wrong.size} unlike Java's, such as:\n${wrong.take(20).joinToString("\n")}")
    }

    @Test
    fun textsOfWhatAFinalSigmaTurnsOnAreLoweredAsJavaLowersThem() {
        val random = Random(665)
        val wrong = mutableListOf<String>()
        repeat(1_000_000) {
            val text = StandIns.searchText(random)
            if (CommonUnicode.lowercase(text) != text.lowercase()) wrong += "${show(text)}: Java ${show(text.lowercase())}, ours ${show(CommonUnicode.lowercase(text))}"
        }
        assertTrue(wrong.isEmpty(), "${wrong.size} unlike Java's, such as:\n${wrong.take(20).joinToString("\n")}")
    }
}
