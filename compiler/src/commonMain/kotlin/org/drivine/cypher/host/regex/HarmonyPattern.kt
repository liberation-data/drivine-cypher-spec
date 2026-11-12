package org.drivine.cypher.host.regex

/**
 * A Java regular expression on `kotlin.text.Regex`, as Kotlin/Native and Kotlin/Wasm run one: the pattern rewritten so
 * their engine matches as Java's does with `UNICODE_CHARACTER_CLASS` ([HarmonyRewrite]), and its matches taken in turn as
 * Java's `Matcher.find` takes them. It runs on the JVM too, in the tests, where kotlin.text.Regex is java.util.regex
 * without `UNICODE_CHARACTER_CLASS`: the rewrite holds there as well (RegexOracleTest).
 */
internal class HarmonyPattern(java: String) : JavaPattern {

    private val rewritten = HarmonyRewrite.rewritten(java)

    private val regex = StackRoom.check(StackRoom.reserve(rewritten.path)).let { Regex(rewritten.text) }

    override fun find(text: String): List<String?>? = find(text, 0)?.let { groups(it).map { group -> group?.value } }

    /** The match's groups, Java's alone: without the groups the rewrite wraps around some of them ([HarmonyRewrite.Rewritten.hidden]). */
    private fun groups(match: MatchResult): List<MatchGroup?> =
        if (rewritten.hidden.isEmpty()) match.groups.toList() else match.groups.filterIndexed { i, _ -> i !in rewritten.hidden }

    /** The match from [start], the engine reading [text] through [StackRoom.guarded]. */
    private fun find(text: String, start: Int): MatchResult? = regex.find(StackRoom.guarded(text, rewritten), start)

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
            val match = find(text, start) ?: break
            yield(match)
            start = if (match.range.isEmpty()) match.range.first + 1 else match.range.last + 1
        }
    }

    /** An ASCII digit's value, as Java's replacement reads one; null of any other character. */
    private fun digit(c: Char): Int? = if (c in '0'..'9') c - '0' else null

    /** Java's `Matcher.appendExpandedReplacement` of [replacement] for [match], into [out]. */
    private fun expand(replacement: String, match: MatchResult, out: StringBuilder) {
        val groups = groups(match)
        val groupCount = groups.size - 1
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
                    groups[group]?.let { out.append(it.value) }
                }
                else -> {
                    out.append(c)
                    cursor++
                }
            }
        }
    }
}

/**
 * kotlin.text.Regex's engine recurses: as it compiles a pattern, into its groups and along its nodes, and as it matches,
 * a frame or more for each node it passes, and again each time a group repeats, so `(a|b)*` over a text recurses for each
 * character. Java's does too, and throws StackOverflowError where its thread's stack runs out (`(a|b)*` over some 7,800
 * characters on a 1 MiB stack, 73,000 on 8 MiB); on Kotlin/Native an overflow is a crash that ends the host's process. So
 * on Kotlin/Native the engine compiles a pattern only where the thread has the stack left for the pattern's longest path
 * ([HarmonyRewrite.Rewritten.path]), and reads the text through [guarded], which checks the stack left as it reads; each
 * throws [StackRoomException] where there isn't. The JVM, where the tests run the rewrite, and wasm, whose overflow is an
 * error the host's JavaScript catches, check nothing.
 */
internal object StackRoom {

    /** Stack kept beyond a path's nodes: the frames of the reads between two checks, the throw's unwinding, and SQLite's. */
    private const val MARGIN = 64L * 1024

    /** The stack a pattern of that path needs past the reads that are checked. */
    fun reserve(path: Int): Long = MARGIN + path * stackBytesPerNode()

    /** Throws where the thread has less than [reserve] bytes of stack left past this call; on Kotlin/Native alone. */
    fun check(reserve: Long) {
        val low = stackLow()
        if (low != 0L && stackHere() - low < reserve) throw StackRoomException()
    }

    /**
     * [text] as the engine reads it for [pattern]: on Kotlin/Native, checking as it reads that the stack keeps the
     * pattern's reserve, where the thread hasn't stack enough for the most the engine can take. A group repeats only past a
     * character, so the engine takes at most the pattern's path for each character and the end, and again in each
     * lookaround it nests: that fitting, the text is read as it is, as a check at each read costs about
     * half the time of a short match.
     */
    fun guarded(text: String, pattern: HarmonyRewrite.Rewritten): CharSequence {
        val low = stackLow()
        if (low == 0L) return text
        val reserve = reserve(pattern.path)
        val left = stackHere() - low
        if (left < reserve) throw StackRoomException()
        val most = (text.length + 1.0) * (pattern.scans + 1) * pattern.path * stackBytesPerNode() + reserve
        return if (most <= left) text else Guarded(text, low + reserve)
    }

    /**
     * The text, each read of a character, which the engine makes at each node that takes one and each time a group repeats
     * (a group repeats only past a character), checking first that the stack it is read at is above [floor].
     */
    private class Guarded(private val text: String, private val floor: Long) : CharSequence {
        override val length: Int get() = text.length

        override fun get(index: Int): Char {
            if (stackHere() < floor) throw StackRoomException()
            return text[index]
        }

        override fun subSequence(startIndex: Int, endIndex: Int): CharSequence = text.subSequence(startIndex, endIndex)

        override fun toString(): String = text
    }
}

/** Thrown where the engine would take more of the thread's stack than it has left ([StackRoom]). */
internal class StackRoomException : IllegalStateException(
    "the regular expression takes more of this thread's stack than is left on this platform, as Java's overflows its own: " +
        "repeat a group over fewer characters, or give the pattern fewer groups, nested less deeply.",
)

/** The lowest address of this thread's stack: 0 where nothing is checked ([StackRoom]), on the JVM and wasm. */
internal expect fun stackLow(): Long

/** An address in the caller's frame, as the stack grows down; 0 where [stackLow] is. */
internal expect fun stackHere(): Long

/** The stack Harmony's nodes take at most, for each on a pattern's path ([HarmonyRewrite.Rewritten.path]). */
internal expect fun stackBytesPerNode(): Long
