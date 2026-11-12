@file:OptIn(kotlin.native.concurrent.ObsoleteWorkersApi::class)

package org.drivine.cypher.host.regex

import kotlin.native.concurrent.TransferMode
import kotlin.native.concurrent.Worker
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNotSame
import kotlin.test.assertSame
import kotlin.test.assertTrue

/**
 * kotlin.text.Regex's recursion, which on Kotlin/Native would overflow the stack and end the host's process, refused before
 * it does (StackRoom): each of these crashed the process before. A regression crashes the test process, which fails too.
 */
class HarmonyStackTest {

    private fun refused(pattern: String, text: String = "a") {
        assertFailsWith<StackRoomException>(pattern.take(40)) { JavaPatterns.compile(pattern).find(text) }
    }

    @Test
    fun `a group repeated over a long text fails its statement where the stack would run out`() {
        // Each repetition of a group recurses: about 30,000 of (a|b)* on a release binary's 8 MiB, and fewer than 1,000 on 512 KiB.
        for (pattern in listOf("(a|b)*", "(?:a|b)*\$", "(?:x|(a|b)*)\$", "(?:(?=a)a)*", "(?:.|\\n)*", "(?i)(?:a|b)*")) {
            refused(pattern, "a".repeat(100_000))
        }
        assertEquals(100, JavaPatterns.compile("(a|b)*").find("a".repeat(100))?.first()?.length)
    }

    @Test
    fun `each kind of group written repeated over a long text is refused where the stack would run out`() {
        for ((pattern, text) in repeatedGroups(300_000)) {
            assertTrue(HarmonyRewrite.rewritten(pattern).deepens, pattern)
            refused(pattern, text)
        }
    }

    @Test
    fun `a pattern that repeats no group reads a long text as it is with no check at each read`() {
        val text = "a graph ".repeat(125_000)
        val patterns = listOf(
            "(^|[^[:alnum:]_])graph(?=\$|[^[:alnum:]_])", "(?i)\\bGRAPH\\b", ".*?h", "\\w+\$", "(?:a)?(?:x|a*)b??", "[^\\x{0}-\\x{10FFFF}]?",
        )
        for (pattern in patterns) {
            val rewritten = HarmonyRewrite.rewritten(pattern)
            assertFalse(rewritten.deepens, pattern)
            assertSame<CharSequence>(text, StackRoom.guarded(text, rewritten), pattern)
        }
        assertEquals(2_000, JavaPatterns.compile("(^|[^[:alnum:]_])graph(?=\$|[^[:alnum:]_])").count(text.take(16_000)))
        // A group repeated is read through the check where the text is long, and not where it is short.
        val repeated = HarmonyRewrite.rewritten("(?:a|b)*")
        assertNotSame<CharSequence>(text, StackRoom.guarded(text, repeated))
        assertSame<CharSequence>("ab", StackRoom.guarded("ab", repeated))
        // A class with nothing in it is written as a group, and repeated as one.
        assertTrue(HarmonyRewrite.rewritten("[^\\x{0}-\\x{10FFFF}]*").deepens)
    }

    @Test
    fun `on a thread of a small stack too`() {
        val worker = Worker.start()
        try {
            val answers = worker.execute(TransferMode.SAFE, { }) {
                listOf(100_000, 100).map { n ->
                    try {
                        JavaPatterns.compile("(a|b)*").find("a".repeat(n))?.first()?.length.toString()
                    } catch (e: StackRoomException) {
                        "refused"
                    }
                }
            }.result
            assertEquals(listOf("refused", "100"), answers)
        } finally {
            worker.requestTermination().result
        }
    }

    @Test
    fun `on a thread of a small stack a long text is matched where the pattern repeats no group and refused where it repeats one`() {
        val worker = Worker.start()
        try {
            val answers = worker.execute(TransferMode.SAFE, { }) {
                val text = "a".repeat(300_000) + "d"
                val plain = listOf(".*?d", "a*d", "\\w+?d", "(?i)A+d", "(a+)d", "(?:x|a*)d", "a*(?=d)d").map { pattern ->
                    JavaPatterns.compile(pattern).find(text)?.first()?.length.toString()
                }
                val repeated = repeatedGroups(300_000).map { (pattern, long) ->
                    try {
                        JavaPatterns.compile(pattern).find(long)?.first()?.length.toString()
                    } catch (e: StackRoomException) {
                        "refused"
                    }
                }
                plain + repeated
            }.result
            assertEquals(List(7) { "300001" } + List(repeatedGroups(1).size) { "refused" }, answers)
        } finally {
            worker.requestTermination().result
        }
    }

    @Test
    fun `a pattern nested or long enough that compiling it would overflow the stack is refused`() {
        refused("(".repeat(20_000) + "a" + ")".repeat(20_000))
        refused("(?:".repeat(20_000) + "a" + ")".repeat(20_000))
        refused("[".repeat(20_000) + "a" + "]".repeat(20_000))
        refused("a?".repeat(50_000))
        refused("(?:)".repeat(50_000))
        // Each $ is written as some 70 characters (Writer.dollar), so 30,000 of them are more than the rewrite writes at all.
        refused("\$".repeat(20_000))
    }

    @Test
    fun `the path counted for each anchor written is no shorter than Harmony's nodes on it`() {
        // Flags alone, as (?i) is written before an anchor, are no group and no node: \A one, (?:a) three.
        assertEquals(4, harmonyNodes("(?i)\\A(?:a)"))
        for (anchor in listOf("\$", "\\Z", "(?m)\$", "\\b", "\\B", "(?i)\\b")) {
            val written = HarmonyRewrite.rewritten(anchor)
            assertTrue(written.text.endsWith(SNAPSHOT), anchor)
            val nodes = harmonyNodes(written.text.removeSuffix(SNAPSHOT)) + SNAPSHOT_NODES
            assertTrue(written.path >= nodes, "$anchor: a path of ${written.path}, where Harmony has $nodes nodes")
        }
    }

    @Test
    fun `on a thread of a small stack anchors in a row are refused before they would overflow it`() {
        val worker = Worker.start()
        try {
            val answers = worker.execute(TransferMode.SAFE, { }) {
                // Each found first where it takes its longest path, through its lookbehind: not at the text's start.
                listOf("\$" to "a\n", "\\b" to " a", "\\B" to "aa", "\\b\$" to "\na\n").map { (anchor, text) ->
                    // The most in a row that compile, each count matched up to it: the last at the edge of the stack.
                    var n = 1
                    while (true) {
                        val found = try {
                            JavaPatterns.compile(anchor.repeat(n)).find(text)?.first()
                        } catch (e: StackRoomException) {
                            break
                        }
                        if (found != "") return@map "$anchor x$n: $found"
                        n++
                    }
                    if (n > 1) "refused" else "$anchor refused alone"
                }
            }.result
            assertEquals(List(4) { "refused" }, answers)
        } finally {
            worker.requestTermination().result
        }
    }

    @Test
    fun `a long text is matched where the pattern repeats no group`() {
        val text = "a".repeat(1_000_000) + "b"
        for (pattern in listOf(".*b", "a*b", "[ab]+", "(?:a*)*b", "\\w+", "(a+)b")) {
            assertNotNull(JavaPatterns.compile(pattern).find(text), pattern)
        }
    }
}

/** Each written group repeated over [n] characters, as each crashed a release library on 512 KiB unguarded: a, b or c, then d. */
private fun repeatedGroups(n: Int) = listOf(
    "(a)*d", "(?:a)*d", "(?<n>a)*d", "(a)*?d", "(?:a)*?d", "(?:a)+d", "(?:a){2,}d", "(?:a){0,2000000}d", "(?:.)*d",
    "(?:\\w)*d", "(?i)(?:A)*d", "(?:a|b)*d", "(?:[ab]|c)*d", "(?:a(?=a|d))*d", "(?:a(?!b))*d", "(?:a\\b|a)*d",
    "(?:a\$|a)*d",
).map { it to "a".repeat(n) + "d" }

/** HarmonyRewrite's SNAPSHOT, written after each anchor, and the nodes HarmonyRewrite counts it as. */
private const val SNAPSHOT = "(?:|(?!))"
private const val SNAPSHOT_NODES = 3

/**
 * Harmony's nodes on the longest path through [written], counted as HarmonyRewrite's Writer counts a group's (closePath):
 * a group, a lookaround too, two (its set and its end) and those of its longest alternative; an empty alternative one (an
 * EmptySet); a class one, or two where it holds a lone surrogate (a CompositeRangeSet, which calls the set it holds); any
 * other token one, and a quantifier none.
 */
private fun harmonyNodes(written: String): Int {
    val outer = ArrayDeque<Pair<Int, Int>>()
    var nodes = 0
    var longest = 0
    var at = 0
    fun alternative() = if (nodes == 0) 1 else nodes
    while (at < written.length) {
        when (val c = written[at]) {
            '(' -> {
                // Flags alone ((?i)) open no group.
                val flags = written.indexOfAny(charArrayOf(':', '=', '!', '>', ')'), at)
                if (written.getOrNull(at + 1) == '?' && written[flags] == ')') {
                    at = flags + 1
                    continue
                }
                outer.addLast(nodes to longest)
                nodes = 0
                longest = 0
                at++
                if (written.getOrNull(at) == '?') at = written.indexOfAny(charArrayOf(':', '=', '!', '>'), at) + 1
            }
            '|' -> {
                longest = maxOf(longest, alternative())
                nodes = 0
                at++
            }
            ')' -> {
                val inner = maxOf(longest, alternative()) + 2
                outer.removeLast().let { (n, l) ->
                    nodes = n + inner
                    longest = l
                }
                at++
            }
            '[' -> {
                val (end, surrogate) = classEnd(written, at)
                nodes += if (surrogate) 2 else 1
                at = end
            }
            '?', '*', '+' -> at++
            else -> {
                nodes++
                at += when {
                    c != '\\' -> 1
                    written[at + 1] == 'k' -> written.indexOf('>', at) + 1 - at
                    else -> 2
                }
            }
        }
    }
    return maxOf(longest, nodes)
}

/** The index past the class that begins at [at] in [written], and whether it holds a code point from U+D800 to U+DFFF. */
private fun classEnd(written: String, at: Int): Pair<Int, Boolean> {
    var i = at + 1
    var surrogate = false
    var from: Int? = null
    while (written[i] != ']') {
        val cp: Int
        when {
            written.startsWith("\\u", i) -> {
                cp = written.substring(i + 2, i + 6).toInt(16)
                i += 6
            }
            written.startsWith("\\Q", i) -> {
                val end = written.indexOf("\\E", i)
                cp = written.substring(i + 2, end).let { if (it.length == 2) 0x10000 else it[0].code }
                i = end + 2
            }
            written[i] == '-' -> {
                i++
                continue
            }
            else -> {
                cp = if (written[i].isHighSurrogate()) 0x10000 else written[i].code
                i += if (written[i].isHighSurrogate()) 2 else 1
            }
        }
        surrogate = surrogate || cp in 0xD800..0xDFFF || (from != null && from <= 0xDFFF && cp >= 0xD800)
        from = if (written.getOrNull(i) == '-') cp else null
    }
    return i + 1 to surrogate
}
