package org.drivine.cypher.text

/**
 * What a full-text query's reading asks of Java's Unicode (`org.drivine.cypher.search.TextQuery`), as the pinned JDK answers
 * it, from its tables (`UnicodeTables`, which `gen/Generate.java` writes on that JDK): for the platforms whose own tables are
 * of another Unicode release, Kotlin/Native's and Kotlin/Wasm's. The JVM asks Java itself ([SearchText]); the stand-in fuzz
 * checks each target against Java's answers, and the JVM's tests check this against Java on every code point.
 */
internal object CommonUnicode {

    /** Whether [cp] is in a word: Java's `\p{IsAlphabetic}`, `\p{Nd}` or `_`. */
    fun isWordChar(cp: Int): Boolean = WORD.holds(cp)

    /** Whether Oracle's `[[:alnum:]]` was measured to hold [cp]: a letter, a decimal digit, a letter number or a mark. */
    fun isOracleAlnum(cp: Int): Boolean = ORACLE_ALNUM.holds(cp)

    /**
     * [text] as Java's `String.toLowerCase(Locale.ROOT)` writes it: each code point's lower case, and a capital sigma
     * final (`ς`) where Java reads it so: after a cased character of its word, and before none. Java reads the word by
     * `BreakIterator.getWordInstance`'s boundaries, and by `isBoundary`, which also reads one after each supplementary
     * character but one that opens the text ([finalSigma]).
     */
    fun lowercase(text: String): String {
        val cps = codePoints(text)
        if (cps.none { it == CAPITAL_SIGMA || LOWER.containsKey(it) }) return text
        val out = StringBuilder(text.length)
        var words: WordBreaks? = null
        for (i in cps.indices) {
            val cp = cps[i]
            if (cp == CAPITAL_SIGMA) {
                val breaks = words ?: WordBreaks(cps).also { words = it }
                out.append(if (breaks.finalSigma(i)) 'ς' else 'σ')
            } else {
                val lower = LOWER[cp]
                if (lower == null) appendCodePoint(out, cp) else lower.forEach { appendCodePoint(out, it) }
            }
        }
        return out.toString()
    }

    /** The code points of [text], a lone surrogate as itself, as Java's `codePoints()` gives them. */
    fun codePoints(text: String): IntArray {
        val out = IntArray(text.length)
        var n = 0
        var i = 0
        while (i < text.length) {
            val c = text[i]
            if (c.isHighSurrogate() && i + 1 < text.length && text[i + 1].isLowSurrogate()) {
                out[n++] = 0x10000 + ((c.code - 0xD800) shl 10) + (text[i + 1].code - 0xDC00)
                i += 2
            } else {
                out[n++] = c.code
                i++
            }
        }
        return out.copyOf(n)
    }

    private fun appendCodePoint(out: StringBuilder, cp: Int) {
        if (cp < 0x10000) {
            out.append(cp.toChar())
        } else {
            val v = cp - 0x10000
            out.append((0xD800 + (v shr 10)).toChar()).append((0xDC00 + (v and 0x3FF)).toChar())
        }
    }

    private const val CAPITAL_SIGMA = 0x3A3

    /** Code points as sorted, disjoint ranges: `starts[i]..ends[i]`. */
    private class Ranges(text: String) {
        private val starts: IntArray
        private val ends: IntArray

        init {
            val parts = text.split(' ')
            starts = IntArray(parts.size) { parts[it].substringBefore('-').toInt(16) }
            ends = IntArray(parts.size) { parts[it].substringAfter('-').toInt(16) }
        }

        fun holds(cp: Int): Boolean {
            var lo = 0
            var hi = starts.size - 1
            while (lo <= hi) {
                val mid = (lo + hi) ushr 1
                when {
                    cp < starts[mid] -> hi = mid - 1
                    cp > ends[mid] -> lo = mid + 1
                    else -> return true
                }
            }
            return false
        }
    }

    private val WORD by lazy { Ranges(UnicodeTables.WORD) }
    private val ORACLE_ALNUM by lazy { Ranges(UnicodeTables.ORACLE_ALNUM) }
    private val CASED by lazy { Ranges(UnicodeTables.CASED) }

    private val LOWER: Map<Int, IntArray> by lazy {
        UnicodeTables.LOWER.split(' ').associate { entry ->
            entry.substringBefore(':').toInt(16) to entry.substringAfter(':').split(',').map { it.toInt(16) }.toIntArray()
        }
    }

    /** Each run of code points of one break class: its first code point, and the class, as `gen/Generate.java` gives it. */
    private val CLASS_STARTS: IntArray by lazy { UnicodeTables.BREAK_CLASSES.split(' ').map { it.substringBefore(':').toInt(16) }.toIntArray() }
    private val CLASSES: IntArray by lazy { UnicodeTables.BREAK_CLASSES.split(' ').map { it.substringAfter(':').toInt(16) }.toIntArray() }

    private fun breakClass(cp: Int): Int {
        var lo = 0
        var hi = CLASS_STARTS.size - 1
        while (lo < hi) {
            val mid = (lo + hi + 1) ushr 1
            if (CLASS_STARTS[mid] <= cp) lo = mid else hi = mid - 1
        }
        return CLASSES[lo]
    }

    // The break classes, as gen/Generate.java numbers them.
    private const val LET = 1
    private const val DGT = 2
    private const val MID_WORD = 4
    private const val MID_NUM = 8
    private const val PRE_NUM = 16
    private const val POST_NUM = 32
    private const val DANDA = 64
    private const val MARK = 128
    private const val FORMAT = 256
    private const val KANJI = 512
    private const val KATA = 1024
    private const val HIRA = 2048
    private const val SPACE = 4096
    private const val CR = 8192
    private const val LINE = 16384

    /**
     * The word boundaries of [cps] as `BreakIterator.getWordInstance` gives them, read forward from the start, each boundary
     * the end of the longest of: one character with the marks after it; a word, a number, or several of each in turn, a
     * word of letters joined by single characters that may stand between them and ended by a danda, a number of digits
     * joined likewise, with a character that may stand before a number first and one after a number last; a run of ideographs,
     * of katakana or of hiragana; or of spaces, then a line's end. A format character is passed over wherever it stands, and
     * a mark goes with the letter or digit before it.
     */
    private class WordBreaks(private val cps: IntArray) {
        private val n = cps.size
        private val classes = IntArray(n) { breakClass(cps[it]) }

        /** Each code point's index in the text's UTF-16, and the text's length last. */
        private val at = IntArray(n + 1).also { at ->
            var k = 0
            for (i in 0 until n) { at[i] = k; k += if (cps[i] >= 0x10000) 2 else 1 }
            at[n] = k
        }

        /**
         * The boundaries, as indexes into the UTF-16 text: those of the segments, and, as Java's `isBoundary` answers, the
         * index after each supplementary code point that doesn't open the text.
         */
        private val boundaries: Set<Int> = buildSet {
            var p = 0
            add(0)
            while (p < n) {
                p = segmentEnd(p)
                add(at[p])
            }
            for (i in 0 until n) if (cps[i] >= 0x10000 && at[i] > 0) add(at[i] + 2)
        }

        /**
         * Whether the capital sigma at [i] is final, as Java's `String.toLowerCase` reads it: going back from it to a
         * boundary there is a cased character, and going on from it to a boundary there is none.
         */
        fun finalSigma(i: Int): Boolean {
            var j = i
            while (j > 0 && at[j] !in boundaries) {
                if (CASED.holds(cps[j - 1])) {
                    var k = i + 1
                    while (k < n && at[k] !in boundaries) {
                        if (CASED.holds(cps[k])) return false
                        k++
                    }
                    return true
                }
                j--
            }
            return false
        }

        private fun has(i: Int, flag: Int) = classes[i] and flag != 0

        private fun pastFormats(from: Int): Int {
            var i = from
            while (i < n && has(i, FORMAT)) i++
            return i
        }

        /** After the character at [i], and the format characters after it. */
        private fun after(i: Int) = pastFormats(i + 1)

        private fun segmentEnd(p: Int): Int {
            val q = pastFormats(p)
            if (q >= n) return n
            var end = if (has(q, MARK)) pastFormats(q + 1) else {
                var i = q + 1
                while (i < n && classes[i] and (MARK or FORMAT) != 0) i++
                i
            }
            end = maxOf(end, wordOrNumber(q))
            for (run in intArrayOf(KANJI, KATA, HIRA)) if (has(q, run)) end = maxOf(end, runOf(q, run))
            if (classes[q] and (SPACE or CR or LINE) != 0) {
                var i = runOf(q, SPACE)
                if (i < n && has(i, CR)) i++
                if (i < n && has(i, LINE)) i++
                end = maxOf(end, i)
            }
            return end
        }

        private fun runOf(from: Int, flag: Int): Int {
            var i = from
            while (i < n && has(i, flag)) i++
            return i
        }

        /**
         * The end of the longest word-and-number segment from [p], or -1: `word? (number word)* (number post?)?`, or
         * `pre (number word)* (number post?)?`, where a word is `letter+ (mid letter+)* danda?` and a number
         * `digit+ (mid digit+)*`. The states: 0 the start, 1 in a word's letters, 2 after what stands between letters, 3 after a
         * danda, 4 in a number's digits, 5 after what stands between digits, 6 after what follows a number, 7 after what goes
         * before one.
         */
        private fun wordOrNumber(p: Int): Int {
            var best = -1
            var states = 1 shl 0
            var i = p
            while (i < n && states != 0) {
                val f = classes[i]
                var next = 0
                fun on(flag: Int, state: Int) { if (f and flag != 0) next = next or (1 shl state) }
                if (states and (1 shl 0) != 0) { on(LET, 1); on(DGT, 4); on(PRE_NUM, 7) }
                if (states and (1 shl 1) != 0) { on(LET or MARK, 1); on(MID_WORD, 2); on(DANDA, 3); on(DGT, 4) }
                if (states and (1 shl 2) != 0) on(LET, 1)
                if (states and (1 shl 3) != 0) on(DGT, 4)
                if (states and (1 shl 4) != 0) { on(DGT or MARK, 4); on(MID_NUM, 5); on(LET, 1); on(POST_NUM, 6) }
                if (states and (1 shl 5) != 0) on(DGT, 4)
                if (states and (1 shl 7) != 0) on(DGT, 4)
                i = after(i)
                states = next
                if (states and ((1 shl 1) or (1 shl 3) or (1 shl 4) or (1 shl 6)) != 0) best = i
            }
            return best
        }
    }
}
