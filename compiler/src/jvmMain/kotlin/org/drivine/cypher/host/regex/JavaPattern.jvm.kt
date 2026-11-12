package org.drivine.cypher.host.regex

import java.util.regex.Pattern

/** java.util.regex itself, as drivine4j's SqlitePatterns compiles it. */
internal actual fun compileJavaPattern(expression: String): JavaPattern = JvmPattern(Pattern.compile(expression, Pattern.UNICODE_CHARACTER_CLASS))

private class JvmPattern(private val pattern: Pattern) : JavaPattern {
    override fun find(text: String): List<String?>? {
        val matcher = pattern.matcher(text)
        return if (matcher.find()) (0..matcher.groupCount()).map { matcher.group(it) } else null
    }

    override fun count(text: String): Int {
        val matcher = pattern.matcher(text)
        var count = 0
        while (matcher.find()) count++
        return count
    }

    override fun replaceAll(text: String, replacement: String): String = pattern.matcher(text).replaceAll(replacement)
}

internal actual class PatternLock actual constructor() {
    actual fun <T> locked(block: () -> T): T = synchronized(this) { block() }
}

/** None checked: the JVM's overflow is an error, and HarmonyPattern runs here only in the tests. */
internal actual fun stackLow(): Long = 0

internal actual fun stackHere(): Long = 0

internal actual fun stackBytesPerNode(): Long = 0
