package org.drivine.cypher.text

/**
 * The calls on text a full-text query's reading makes of Java (`org.drivine.cypher.search.TextQuery`), as Java gives them on
 * every target: on the JVM, Java's own; elsewhere [CommonUnicode], from the pinned JDK's tables. The stand-in fuzz checks
 * each target's against Java's.
 */
internal object SearchText {

    /** Whether [cp] is in a word: Java's `\p{IsAlphabetic}`, `\p{Nd}` or `_`, as the query's `[^\p{IsAlphabetic}\p{Nd}_]` reads it. */
    fun isWordChar(cp: Int): Boolean = platformIsWordChar(cp)

    /** As `Character.isLetter`, `isDigit`, or a letter number, a non-spacing or a spacing mark: Oracle's `[[:alnum:]]` as measured. */
    fun isOracleAlnum(cp: Int): Boolean = platformIsOracleAlnum(cp)

    /** As Java's `String.toLowerCase(Locale.ROOT)`, Kotlin's `lowercase()` on the JVM. */
    fun lowercase(text: String): String = platformLowercase(text)

    /** The code points of [text], a lone surrogate as itself, as Java's `codePoints()` gives them. */
    fun codePoints(text: String): IntArray = CommonUnicode.codePoints(text)
}

internal expect fun platformIsWordChar(cp: Int): Boolean

internal expect fun platformIsOracleAlnum(cp: Int): Boolean

internal expect fun platformLowercase(text: String): String
