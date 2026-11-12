package org.drivine.cypher.host.regex

import kotlin.concurrent.AtomicInt

/** kotlin.text.Regex, the pattern rewritten to match as Java's does: see [HarmonyRewrite]. */
internal actual fun compileJavaPattern(expression: String): JavaPattern = HarmonyPattern(expression)

/** A spin lock: held for a few map operations alone, as SQLite may call a function from any of a host's threads. */
internal actual class PatternLock actual constructor() {
    private val held = AtomicInt(0)

    actual fun <T> locked(block: () -> T): T {
        while (!held.compareAndSet(0, 1)) { /* held for a map's get or put */ }
        try {
            return block()
        } finally {
            held.value = 0
        }
    }
}
