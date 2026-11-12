@file:OptIn(kotlin.native.concurrent.ObsoleteWorkersApi::class)

package org.drivine.cypher.host.regex

import kotlin.native.concurrent.TransferMode
import kotlin.native.concurrent.Worker
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull

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
        for (pattern in listOf("(a|b)*", "(?:a|b)*\$", "(?=(a|b)*\$)", "(?>(a|b)*)", "(?:(?=a)a)*", "(?:.|\\n)*", "(?i)(?:a|b)*")) {
            refused(pattern, "a".repeat(100_000))
        }
        assertEquals(100, JavaPatterns.compile("(a|b)*").find("a".repeat(100))?.first()?.length)
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
    fun `a pattern nested or long enough that compiling it would overflow the stack is refused`() {
        refused("(".repeat(20_000) + "a" + ")".repeat(20_000))
        refused("(?:".repeat(20_000) + "a" + ")".repeat(20_000))
        refused("(?=".repeat(20_000) + "a" + ")".repeat(20_000))
        refused("[".repeat(20_000) + "a" + "]".repeat(20_000))
        refused("a?".repeat(50_000))
        refused("(?:)".repeat(50_000))
        refused("\$".repeat(50_000))
    }

    @Test
    fun `a long text is matched where the pattern repeats no group`() {
        val text = "a".repeat(1_000_000) + "b"
        for (pattern in listOf(".*b", "a*b", "[ab]+", "(?:a*)*b", "\\w+", "(a+)\\1*")) {
            assertNotNull(JavaPatterns.compile(pattern).find(text), pattern)
        }
    }
}
