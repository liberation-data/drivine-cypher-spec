package org.drivine.cypher.host.regex

import org.drivine.cypher.number.target
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * The bugs of kotlin.text.Regex on Kotlin/Native and Kotlin/Wasm (Kotlin 2.2.20) that HarmonyRewrite and HarmonyPattern
 * work around, as they are: once one of these fails, Kotlin has fixed that bug, and its workaround (named in the message)
 * can come out. The JVM's Regex is java.util.regex, which has none of them.
 */
class HarmonyBugsTest {

    private val harmony = target != "jvm"

    @Test
    fun `a class's range of code points past the BMP alone matches nothing`() {
        if (!harmony) return
        val emoji = "\uD83D\uDE00"
        assertFalse(Regex("[\uD83D\uDE00-\uD83D\uDE02]").matches(emoji), "Fixed: take the single out of HarmonyRewrite.write and characterClass.")
        assertTrue(Regex("[\uD83D\uDE00-\uD83D\uDE02\uD83D\uDE00]").matches(emoji))
    }

    @Test
    fun `a code point past the BMP whose low 16 bits are syntax is read as that character`() {
        if (!harmony) return
        // U+10028: its low 16 bits are "(".
        val refused = runCatching { Regex("\uD800\uDC28") }.isFailure
        assertTrue(refused, "Fixed: take \\Q…\\E out of HarmonyRewrite.astral, and the ends it takes off a range in write.")
        assertTrue(Regex("\\Q\uD800\uDC28\\E").matches("\uD800\uDC28"))
    }

    @Test
    fun `replace drops an empty match at the end after a match that ends there`() {
        if (!harmony) return
        assertEquals("<>u<>", Regex("x*").replace("ux", "<>"), "Fixed: HarmonyPattern.replaceAll may use Regex.replace's matches.")
    }
}
