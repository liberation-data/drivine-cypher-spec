package org.drivine.cypher.host.regex

/** kotlin.text.Regex, the stdlib's own engine and not JavaScript's RegExp, the pattern rewritten to match as Java's does: see [HarmonyRewrite]. */
internal actual fun compileJavaPattern(expression: String): JavaPattern = HarmonyPattern(expression)

/** None: wasm runs one thread. */
internal actual class PatternLock actual constructor() {
    actual fun <T> locked(block: () -> T): T = block()
}

/** None checked: wasm's overflow is a RangeError the host's JavaScript catches. */
internal actual fun stackLow(): Long = 0

internal actual fun stackHere(): Long = 0

internal actual fun stackBytesPerNode(): Long = 0
