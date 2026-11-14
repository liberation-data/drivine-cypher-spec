package org.drivine.cypher.store.schema

import org.drivine.cypher.json.ArrayNode
import org.drivine.cypher.json.CypherJson
import org.drivine.cypher.json.TextNode

/**
 * The few readings of SQL text that tell what a store's dictionary says an index is over, and that the compilers make of
 * SQL they have written: no parser of SQL, only what finds the columns of an index's text, and reads a literal. Quotes
 * are SQL's, `'…'` with a quote doubled, and `"…"`, and JSON's in a literal, `"…"` with a `\` escaping. A quoted text is
 * read with [scanQuoted]'s loop, never a regex, which recursed on a long one (drivine-cypher-spec#258).
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
    private fun endOfQuote(text: String, at: Int, quote: Char): Int? =
        scanQuoted(text, at, quote, Escape.DOUBLED).takeIf { it >= 0 }?.minus(1)

    /** The text of a SQL literal's body, [body], its doubled quotes made one. */
    fun unquoted(body: String): String = body.replace("''", "'")

    /** The string [json], a JSON string's text; null where it is none. */
    fun jsonString(json: String): String? = runCatching { (CypherJson.readTree(json) as? TextNode)?.value }.getOrNull()

    /** The one string of [json], a JSON array of one string's text; null where it is none. */
    fun jsonLabel(json: String): String? = runCatching {
        ((CypherJson.readTree(json) as? ArrayNode)?.singleOrNull() as? TextNode)?.value
    }.getOrNull()

    /** How a quoted text escapes its own quote: see [scanQuoted]. */
    enum class Escape {
        /** SQL's: the quote doubled, `'it''s'`. */
        DOUBLED,

        /** JSON's: a `\` escapes whatever character follows it, the quote among them, `"a\"b"`. */
        BACKSLASH,

        /**
         * A JSON string inside a SQL literal, as a quoted key of an Oracle path is, `'$."it''s"'`: [BACKSLASH]'s escapes, and
         * the literal's quote doubled. A lone `'` ends the literal, so the string is unclosed.
         */
        BACKSLASH_IN_LITERAL,
    }

    /**
     * Where the text quoted by [quote] that opens at [at] in [text] closes: the index just past its closing quote, or -1
     * where it is unclosed, or there is no [quote] at [at].
     *
     * The compiler reads each quoted text in SQL it has written, or a store has given back, with this loop, not a regex.
     * The JVM's regex engine, and the stdlib's on Kotlin/Native and Kotlin/Wasm, recurse each time a group repeats: once a
     * character for `'(?:[^']|'')*'`, and still once a doubled quote or an escape for the unrolled `'[^']*(?:''[^']*)*'`. A
     * key of a few thousand quotes or backslashes overflowed the stack, which on Kotlin/Native ends the process
     * (drivine-cypher-spec#258). Cypher reaches the compiler from Studio, the CLI and embedded apps, so no input may crash it.
     */
    fun scanQuoted(text: String, at: Int, quote: Char, escape: Escape): Int {
        if (at < 0 || at >= text.length || text[at] != quote) return -1
        var i = at + 1
        while (i < text.length) {
            val c = text[i]
            when {
                c == '\\' && escape != Escape.DOUBLED -> i += 2
                c == quote -> if (escape == Escape.DOUBLED && i + 1 < text.length && text[i + 1] == quote) i += 2 else return i + 1
                c == '\'' && escape == Escape.BACKSLASH_IN_LITERAL -> if (i + 1 < text.length && text[i + 1] == '\'') i += 2 else return -1
                else -> i++
            }
        }
        return -1
    }

    /**
     * Where the SQL literal that opens at [at] in [text] closes as the regex `'[^']*(?:''[^']*)*'` that read it found, with
     * nothing after it: at its closing quote, or where it is unclosed, at the first quote of its last doubled one, as the
     * regex gave back; -1 where it has neither. A reading that has nothing after the literal keeps its answers on an
     * unclosed one so.
     */
    fun greedyClose(text: String, at: Int): Int {
        if (at < 0 || at >= text.length || text[at] != '\'') return -1
        var lastPair = -1
        var i = at + 1
        while (true) {
            val q = text.indexOf('\'', i)
            if (q < 0) return lastPair
            if (q + 1 < text.length && text[q + 1] == '\'') {
                lastPair = q
                i = q + 2
            } else {
                return q
            }
        }
    }

    /**
     * A SQL literal [literal] found in [text]: what its head matched before it, where its quotes are, and where the match
     * ends, past its tail.
     */
    class Literal(private val text: String, val head: MatchResult, val open: Int, val close: Int, val end: Int) {
        /** The literal's body, between its quotes, its doubled quotes as they are. */
        val body: String get() = text.substring(open + 1, close)
    }

    /**
     * The first SQL literal in [text], from [from] on, whose opening quote [head] matches up to, and that [tail] follows
     * at once: as `Regex(head + "'([^']*(?:''[^']*)*)'" + tail).find(text, from)` found it, without a regex over the literal
     * (see [scanQuoted]). [head] is a regex of what is around the literal, no quoted text in it, that ends with the opening
     * `'` and matches it at one place from each start; [tail] is plain text, and doesn't start with a quote. With no
     * [tail], an unclosed literal is read as [greedyClose] reads it.
     */
    fun literal(text: String, head: Regex, tail: String = "", from: Int = 0): Literal? {
        var start = from
        while (start <= text.length) {
            val found = head.find(text, start) ?: return null
            val open = found.range.last
            val close = if (tail.isEmpty()) greedyClose(text, open) else scanQuoted(text, open, '\'', Escape.DOUBLED) - 1
            if (close > open && text.startsWith(tail, close + 1)) return Literal(text, found, open, close, close + 1 + tail.length)
            start = found.range.first + 1
        }
        return null
    }

    /** Each SQL literal [literal] finds in [text], one after the other, as the regex's `findAll` found them. */
    fun literals(text: String, head: Regex, tail: String = ""): Sequence<Literal> =
        generateSequence(literal(text, head, tail)) { literal(text, head, tail, it.end) }

    /**
     * A property read as Postgres's and SQLite's compilers write one, `(n1.props -> 'name')`, the whole of [text]: the column
     * and the key's literal, in its quotes; null where [text] is no such read. As `^\(([\w.]+) -> ('…')\)$` read it.
     */
    fun arrowRead(text: String): Pair<String, String>? {
        if (!text.startsWith("(")) return null
        var i = 1
        while (i < text.length && (word(text[i]) || text[i] == '.')) i++
        if (i == 1 || !text.startsWith(" -> '", i)) return null
        val open = i + " -> ".length
        if (scanQuoted(text, open, '\'', Escape.DOUBLED) != text.length - 1 || text.last() != ')') return null
        return text.substring(1, i) to text.substring(open, text.length - 1)
    }

    /** Whether [c] is a character of a regex's `\w`: an ASCII letter or digit, or `_`. */
    fun word(c: Char): Boolean = c in 'a'..'z' || c in 'A'..'Z' || c in '0'..'9' || c == '_'
}
