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
        refused("\$".repeat(50_000))
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
