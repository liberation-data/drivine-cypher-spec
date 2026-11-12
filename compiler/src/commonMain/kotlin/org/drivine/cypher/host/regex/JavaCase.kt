package org.drivine.cypher.host.regex

/**
 * What Java's case-insensitive matching (`CASE_INSENSITIVE` with `UNICODE_CASE`, which `UNICODE_CHARACTER_CLASS` turns on)
 * takes a literal for, worked out as JDK 21's `Pattern` works it out, from `Character.toUpperCase` and `toLowerCase` as the
 * pinned JDK answers them ([RegexTables.CASE_MAP]). Harmony's case-insensitivity is of its own: it takes `µ` in `(?i)[µ]`
 * for nothing, and `ẞ` for `ß` alone, where Java does the reverse; so [HarmonyRewrite] writes each literal and class as the
 * code points Java takes, and hands Harmony no `(?i)`.
 */
internal object JavaCase {

    private class Maps(val upper: Map<Int, Int>, val lower: Map<Int, Int>, val byFold: Map<Int, List<Int>>)

    private val maps: Maps by lazy {
        val upper = HashMap<Int, Int>()
        val lower = HashMap<Int, Int>()
        for (entry in RegexTables.CASE_MAP.split(' ')) {
            val (cp, up, low) = entry.split(':').map { it.toInt(16) }
            if (up != cp) upper[cp] = up
            if (low != cp) lower[cp] = low
        }
        fun fold(cp: Int) = (upper[cp] ?: cp).let { lower[it] ?: it }
        val byFold = HashMap<Int, MutableList<Int>>()
        for (cp in upper.keys + lower.keys) {
            val f = fold(cp)
            if (f != cp) byFold.getOrPut(f) { mutableListOf() } += cp
        }
        Maps(upper, lower, byFold)
    }

    private fun upper(cp: Int) = maps.upper[cp] ?: cp

    private fun lower(cp: Int) = maps.lower[cp] ?: cp

    private fun fold(cp: Int) = lower(upper(cp))

    /** [f] and every code point whose upper case's lower case is [f], as Java's SingleU and SliceU take for [f]. */
    private fun folded(f: Int): List<Int> = (listOf(f) + maps.byFold[f].orEmpty()).distinct().sorted()

    /** What a literal alone takes (Pattern.single): those that fold as it does, where its upper case's lower case is not its upper case. */
    fun single(cp: Int): List<Int> {
        val up = upper(cp)
        val low = lower(up)
        return if (up == low) listOf(cp) else folded(low)
    }

    /** What a literal of a run of two or more takes (Pattern.SliceU): those that fold as it does, itself among them. */
    fun inSlice(cp: Int): List<Int> = folded(fold(cp))

    /**
     * What a literal in a class takes (Pattern.bitsOrSingle): one of Latin-1's itself and its upper and lower case within
     * Latin-1 (ASCII's alone for ASCII), but for those whose case leaves Latin-1, which are taken as [single] takes them.
     */
    fun inClass(cp: Int): List<Int> {
        if (!inBits(cp, caseInsensitive = true)) return single(cp)
        val cased = if (cp < 0x80) {
            listOf(cp, asciiUpper(cp), asciiLower(cp))
        } else {
            listOf(cp, lower(cp), upper(cp))
        }
        return cased.filter { it < 256 }.distinct().sorted()
    }

    /** Whether Pattern.bitsOrSingle holds [cp], a class's single member, in its BitClass, as it does Latin-1's but [LATIN1_SINGLES]. */
    fun inBits(cp: Int, caseInsensitive: Boolean): Boolean = cp < 256 && !(caseInsensitive && cp in LATIN1_SINGLES)

    /** What a class's range takes beside its own code points (Pattern.CIRangeU): those whose upper case, or its lower case, is in it. */
    fun range(range: IntRange): List<Int> =
        (maps.upper.keys + maps.lower.keys).filter { cp -> cp !in range && (upper(cp) in range || lower(upper(cp)) in range) }.sorted()

    private fun asciiUpper(cp: Int) = if (cp in 'a'.code..'z'.code) cp - 32 else cp

    private fun asciiLower(cp: Int) = if (cp in 'A'.code..'Z'.code) cp + 32 else cp

    /** The code points under 256 that Pattern.bitsOrSingle takes as single takes them: their case leaves Latin-1, or a sign's folds into it. */
    private val LATIN1_SINGLES = setOf(0xff, 0xb5, 0x49, 0x69, 0x53, 0x73, 0x4b, 0x6b, 0xc5, 0xe5)
}
