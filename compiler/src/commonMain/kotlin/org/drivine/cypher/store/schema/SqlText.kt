package org.drivine.cypher.store.schema

import org.drivine.cypher.json.ArrayNode
import org.drivine.cypher.json.CypherJson
import org.drivine.cypher.json.TextNode

/**
 * The few readings of SQL text that tell what a store's dictionary says an index is over: no parser of SQL, only what
 * finds the columns of an index's text, and reads a literal. Quotes are SQL's, `'…'` with a quote doubled, and `"…"`.
 */
internal object SqlText {

    /** [text] without the parentheses that wrap it whole, as many pairs as there are, and the spaces around it. */
    fun unwrapped(text: String): String {
        var t = text.trim()
        while (t.startsWith("(") && closing(t, 0) == t.length - 1) t = t.substring(1, t.length - 1).trim()
        return t
    }

    /** Where the parenthesis that [open] opens in [text] closes, past what is quoted; null where it doesn't. */
    fun closing(text: String, open: Int): Int? {
        var depth = 0
        var i = open
        while (i < text.length) {
            when (val c = text[i]) {
                '\'', '"' -> i = endOfQuote(text, i, c) ?: return null
                '(' -> depth++
                ')' -> if (--depth == 0) return i
            }
            i++
        }
        return null
    }

    /** [text], a list, split at each comma outside parentheses and quotes. */
    fun split(text: String): List<String> {
        val parts = mutableListOf<String>()
        var depth = 0
        var start = 0
        var i = 0
        while (i < text.length) {
            when (val c = text[i]) {
                '\'', '"' -> i = endOfQuote(text, i, c) ?: text.length
                '(' -> depth++
                ')' -> depth--
                ',' -> if (depth == 0) {
                    parts += text.substring(start, i)
                    start = i + 1
                }
            }
            i++
        }
        parts += text.substring(start)
        return parts.map { it.trim() }
    }

    /** Where the quote [quote] that opens at [at] in [text] closes, a doubled one being part of it. */
    private fun endOfQuote(text: String, at: Int, quote: Char): Int? {
        var i = at + 1
        while (i < text.length) {
            if (text[i] == quote) {
                if (i + 1 < text.length && text[i + 1] == quote) i += 2 else return i
            } else {
                i++
            }
        }
        return null
    }

    /** The text of a SQL literal's body, [body], its doubled quotes made one. */
    fun unquoted(body: String): String = body.replace("''", "'")

    /** The string [json], a JSON string's text; null where it is none. */
    fun jsonString(json: String): String? = runCatching { (CypherJson.readTree(json) as? TextNode)?.value }.getOrNull()

    /** The one string of [json], a JSON array of one string's text; null where it is none. */
    fun jsonLabel(json: String): String? = runCatching {
        ((CypherJson.readTree(json) as? ArrayNode)?.singleOrNull() as? TextNode)?.value
    }.getOrNull()

    /**
     * A SQL literal's body, quotes doubled, as a pattern: a run of other characters, then each doubled quote and the run
     * after it. Unrolled, not `(?:[^']|'')*`: Java's regex engine, and Kotlin/Native's, recurse once each time a group
     * repeats, and a group repeated once a character overflowed the stack on a long key or text (drivine-cypher-spec#258).
     * Not possessive (`*+`) either, which wasmJs's engine hasn't. It still recurses once a doubled quote.
     */
    const val BODY = "[^']*(?:''[^']*)*"

    /** A JSON string's body, between its quotes, a `\` escaping the character after it: unrolled as [BODY] is. */
    const val JSON_BODY = """[^"\\]*(?:\\.[^"\\]*)*"""

    /** A SQL literal's body, quotes doubled, as a pattern's group: see [BODY]. */
    const val LITERAL = "($BODY)"
}
