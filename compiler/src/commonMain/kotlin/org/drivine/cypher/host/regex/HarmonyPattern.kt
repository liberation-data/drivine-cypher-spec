package org.drivine.cypher.host.regex

/**
 * A Java regular expression on `kotlin.text.Regex`, as Kotlin/Native and Kotlin/Wasm run one: the pattern rewritten so
 * their engine matches as Java's does with `UNICODE_CHARACTER_CLASS` ([HarmonyRewrite]), and its matches taken in turn as
 * Java's `Matcher.find` takes them. It runs on the JVM too, in the tests, where kotlin.text.Regex is java.util.regex
 * without `UNICODE_CHARACTER_CLASS`: the rewrite holds there as well (RegexOracleTest).
 */
internal class HarmonyPattern(java: String) : JavaPattern {

    private val regex = Regex(HarmonyRewrite.rewrite(java))

    override fun find(text: String): List<String?>? = regex.find(text)?.groups?.map { it?.value }

    override fun count(text: String): Int = matches(text).count()

    /**
     * As Java's `Matcher.replaceAll`: each match's replacement read as `appendExpandedReplacement` reads it. Not Kotlin's
     * `Regex.replace`, which on Kotlin/Native and Kotlin/Wasm drops an empty match at the text's end after a match that ends
     * there (`x*` in "ux": Java's `<>u<><>`, theirs `<>u<>`) in Kotlin 2.2.20; and whose replacement is not Java's.
     */
    override fun replaceAll(text: String, replacement: String): String {
        val out = StringBuilder()
        var at = 0
        var any = false
        for (match in matches(text)) {
            any = true
            out.append(text, at, match.range.first)
            expand(replacement, match, out)
            at = match.range.last + 1
        }
        if (!any) return text
        return out.append(text, at, text.length).toString()
    }

    /**
     * Each match in [text], as Java's `Matcher.find` takes them in turn: the next search from the last match's end, or one
     * character past an empty match, which may be inside a surrogate pair as it is in Java.
     */
    private fun matches(text: String): Sequence<MatchResult> = sequence {
        var start = 0
        while (start <= text.length) {
            val match = regex.find(text, start) ?: break
            yield(match)
            start = if (match.range.isEmpty()) match.range.first + 1 else match.range.last + 1
        }
    }

    /** An ASCII digit's value, as Java's replacement reads one; null of any other character. */
    private fun digit(c: Char): Int? = if (c in '0'..'9') c - '0' else null

    /** Java's `Matcher.appendExpandedReplacement` of [replacement] for [match], into [out]. */
    private fun expand(replacement: String, match: MatchResult, out: StringBuilder) {
        val groupCount = match.groups.size - 1
        var cursor = 0
        while (cursor < replacement.length) {
            val c = replacement[cursor]
            when (c) {
                '\\' -> {
                    cursor++
                    require(cursor < replacement.length) { "character to be escaped is missing" }
                    out.append(replacement[cursor++])
                }
                '$' -> {
                    cursor++
                    require(cursor < replacement.length) { "Illegal group reference: group index is missing" }
                    // Named groups ("${name}") are Java's too, but a host's replacement never writes one (JavaPatterns.javaReplacement).
                    var group = digit(replacement[cursor]) ?: throw IllegalArgumentException("Illegal group reference")
                    cursor++
                    // The largest number of a group the pattern has.
                    while (cursor < replacement.length) {
                        val next = digit(replacement[cursor]) ?: break
                        if (group * 10 + next > groupCount) break
                        group = group * 10 + next
                        cursor++
                    }
                    if (group > groupCount) throw IndexOutOfBoundsException("No group $group")
                    match.groups[group]?.let { out.append(it.value) }
                }
                else -> {
                    out.append(c)
                    cursor++
                }
            }
        }
    }
}
