package org.drivine.cypher.host.regex

import org.drivine.DrivineInternalApi

/**
 * A regular expression as drivine4j's SQLite functions read one (its `SqlitePatterns`): Java's, compiled with
 * `UNICODE_CHARACTER_CLASS`, a POSIX class (`[:alnum:]`) read as Java names it (`\p{Alnum}`), so that a full-text search's
 * expressions, written for Postgres, read the same. On the JVM it is `java.util.regex` itself; on Kotlin/Native and
 * Kotlin/Wasm, whose regular expressions are not Java's, `kotlin.text.Regex` given the pattern rewritten to match as
 * Java's does ([HarmonyRewrite]), or a refusal by name of what can't be ([UnsupportedPatternException]). Get one from
 * [JavaPatterns.of], which keeps those compiled.
 */
@DrivineInternalApi
interface JavaPattern {
    /** The groups of the first match in [text], the whole match first and a group that took no part null; null where none. */
    fun find(text: String): List<String?>?

    /** How many times it matches in [text], each search from the last match's end, or a character past an empty one. */
    fun count(text: String): Int

    /**
     * [text] with each match replaced, as Java's `Matcher.replaceAll` replaces it: [replacement] as Java writes one
     * (`$1` a group, `\` before a character it makes itself; see [JavaPatterns.javaReplacement]). It throws, as Java does,
     * where a match's replacement names a group the pattern hasn't. Off the JVM a group is named by number alone: `${name}`
     * throws there, as [JavaPatterns.javaReplacement], the host functions' replacement, never writes one.
     */
    fun replaceAll(text: String, replacement: String): String
}

/** [expression], POSIX's classes already named as Java names them, compiled: java.util.regex on the JVM, [HarmonyPattern] elsewhere. */
internal expect fun compileJavaPattern(expression: String): JavaPattern

/** A lock, held while [JavaPatterns]'s cache is read or written: synchronized on the JVM, a spin lock on Kotlin/Native, none on wasm. */
internal expect class PatternLock() {
    fun <T> locked(block: () -> T): T
}

/**
 * The patterns compiled so far, the [MAX] used last, for every connection of the process: a search asks the same few of
 * every node it reads, and compiling a pattern rewritten for kotlin.text.Regex takes about a millisecond (its classes
 * written out), where finding takes about a microsecond.
 */
@DrivineInternalApi
object JavaPatterns {

    /** The patterns a process keeps: more than any one statement asks. drivine4j keeps as many a connection. */
    const val MAX = 256

    /** drivine4j's SqlitePatterns.POSIX: the POSIX classes a bracket expression may name, as Java names them. */
    private val POSIX = listOf(
        "[:alnum:]" to "\\p{Alnum}",
        "[:alpha:]" to "\\p{Alpha}",
        "[:digit:]" to "\\p{Digit}",
        "[:space:]" to "\\p{Space}",
        "[:upper:]" to "\\p{Upper}",
        "[:lower:]" to "\\p{Lower}",
        "[:punct:]" to "\\p{Punct}",
    )

    private val lock = PatternLock()

    /** Least used first: a hit is taken out and put back last. */
    private val compiled = LinkedHashMap<String, JavaPattern>()

    /** [expression], compiled once and kept: it throws where Java refuses it, or where this platform can't match it as Java does. */
    fun of(expression: String): JavaPattern {
        lock.locked { compiled.remove(expression)?.also { compiled[expression] = it } }?.let { return it }
        // Compiled outside the lock: two threads may compile one pattern at once, and the second's is kept.
        val pattern = compile(expression)
        lock.locked {
            compiled[expression] = pattern
            if (compiled.size > MAX) compiled.remove(compiled.keys.first())
        }
        return pattern
    }

    /** [expression], compiled anew, as [of] compiles it. */
    fun compile(expression: String): JavaPattern = compileJavaPattern(java(expression))

    /** [expression] with each POSIX class named as Java names it, as drivine4j's SqlitePatterns writes it before compiling. */
    internal fun java(expression: String): String = POSIX.fold(expression) { text, (posix, java) -> text.replace(posix, java) }

    /** How many patterns are kept, for a test. */
    internal val size: Int get() = lock.locked { compiled.size }

    /**
     * A host's replacement as Java writes one (drivine4j's SqliteRegexReplaceFunction): a backslash and a digit name a group,
     * as Postgres's and Oracle's replacements do, which Java names with a dollar; anything else is itself.
     */
    fun javaReplacement(replacement: String): String {
        val written = StringBuilder()
        var at = 0
        for (match in GROUP.findAll(replacement)) {
            quote(replacement.substring(at, match.range.first), written)
            written.append('$').append(match.groupValues[1])
            at = match.range.last + 1
        }
        quote(replacement.substring(at), written)
        return written.toString()
    }

    /** Java's Matcher.quoteReplacement: a backslash before each backslash and dollar. */
    private fun quote(text: String, into: StringBuilder) {
        for (c in text) {
            if (c == '\\' || c == '$') into.append('\\')
            into.append(c)
        }
    }

    private val GROUP = Regex("""\\([0-9])""")
}
