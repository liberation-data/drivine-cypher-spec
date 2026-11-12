package org.drivine.cypher.host.regex

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds
import kotlin.time.measureTimedValue

/**
 * `$`, `\Z`, `\b` and `\B` over long texts take time linear in the text on every target (#204). On native and wasm the
 * rewrite once wrote them with lookbehinds, which Harmony tries in time proportional to how far into the text they are, so
 * a pattern holding one took time quadratic in the text: on macOS arm64's debug test binary, a `\b` replace over 40,000
 * characters took 133 s and `graph$` replaced over 700,000 characters 47 s. Each now takes about 0.4 s there at most (wasm
 * 0.1 s, the JVM 0.03 s; the release library 39 ms and 11 ms). So each ceiling, 5 s, is over ten times what a debug binary
 * takes now, room for a slow or busy machine, and far below the quadratic time, which comes back as a failure here.
 */
class LongTextAnchorsTest {

    private fun <T> within(ceiling: Duration, what: String, block: () -> T): T {
        val (value, took) = measureTimedValue(block)
        assertTrue(took < ceiling, "$what took $took, over its ceiling of $ceiling: is a lookbehind back in the rewrite?")
        return value
    }

    @Test
    fun `a word boundary replaced over forty thousand characters takes time linear in the text`() {
        val text = "a graph, database! ".repeat(40_000 / 19 + 1).take(40_000)
        val replaced = within(5.seconds, "\\b replaced over 40,000 characters") { JavaPatterns.compile("\\b").replaceAll(text, "|") }
        // Four words a repetition, a boundary either side of each.
        assertEquals(text.length + Regex("[a-z]+").findAll(text).count() * 2, replaced.length)
        val unbounded = within(5.seconds, "\\B replaced over 40,000 characters") { JavaPatterns.compile("\\B").replaceAll(text, "") }
        assertEquals(text, unbounded)
    }

    @Test
    fun `an end of line found over seven hundred thousand characters takes time linear in the text`() {
        val text = "graph data\r\n".repeat(700_000 / 12).dropLast(7)
        val pattern = JavaPatterns.compile("graph\$")
        assertEquals(listOf("graph"), within(5.seconds, "graph\$ found over ${text.length} characters") { pattern.find(text) })
        // Each line's \r taken, then given back: Java's $ isn't between a \r and a \n, which the rewrite looks back to see.
        val lines = JavaPatterns.compile("(?m)data\\r?\$")
        assertEquals(700_000 / 12 - 1, within(5.seconds, "(?m)data\\r?\$ counted over ${text.length} characters") { lines.count(text) })
    }
}
