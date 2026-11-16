package org.drivine.cypher.json

import com.ionspin.kotlin.bignum.integer.BigInteger

/**
 * A one-pass reader of plain JSON text into [JsonNode]s, for [CypherJson.readTree]: the tree straight from the text, with no
 * tree of kotlinx.serialization's between, no pass of its own for the nesting, and no exception where it can't read a text.
 * A host function reads its arguments so on every row it is called for (liberation-data/drivine#757), where the full read
 * took most of the call's time on wasm.
 *
 * It reads only what is plainly JSON, and gives null for anything else: a fault, a raw control character in a string, a
 * key given twice, a nesting past [MAX_DEPTH], an empty text. [CypherJson.readTree] then reads that text as it did before
 * this reader was, so every text it can't read gets the same tree, or fails with the same message, as it did; the reader
 * decides nothing of its own. FastJsonTest pins that it gives the same tree as the full read wherever it gives one.
 */
internal class FastJson private constructor(private val text: String) {
    private var at = 0

    companion object {
        /** [text] read, or null where it is not plainly JSON, for the full read to decide. */
        fun read(text: String): JsonNode? {
            val reader = FastJson(text)
            val node = reader.value(0) ?: return null
            reader.space()
            return if (reader.at == text.length) node else null
        }
    }

    private fun space() {
        while (at < text.length) {
            val c = text[at]
            if (c == ' ' || c == '\t' || c == '\n' || c == '\r') at++ else return
        }
    }

    private fun value(depth: Int): JsonNode? {
        space()
        if (at >= text.length) return null
        return when (val c = text[at]) {
            '{' -> if (depth >= MAX_DEPTH) null else obj(depth + 1)
            '[' -> if (depth >= MAX_DEPTH) null else array(depth + 1)
            '"' -> string()?.let(::TextNode)
            't' -> word("true", BooleanNode(true))
            'f' -> word("false", BooleanNode(false))
            'n' -> word("null", NullNode)
            else -> if (c == '-' || c in '0'..'9') number() else null
        }
    }

    /** Whether [c] may follow a word or a number: kotlinx.serialization reads a word to the next of these. */
    private fun ends(c: Char) = c == ' ' || c == '\t' || c == '\n' || c == '\r' || c == ',' || c == ']' || c == '}' || c == ':'

    private fun word(word: String, node: JsonNode): JsonNode? {
        if (!text.startsWith(word, at)) return null
        val end = at + word.length
        if (end < text.length && !ends(text[end])) return null
        at = end
        return node
    }

    private fun digits(): Int {
        val start = at
        while (at < text.length && text[at] in '0'..'9') at++
        return at - start
    }

    /** A number, read as JSON writes one: `-?(0|[1-9][0-9]*)([.][0-9]+)?([eE][+-]?[0-9]+)?`. */
    private fun number(): JsonNode? {
        val start = at
        if (text[at] == '-') at++
        if (at >= text.length) return null
        if (text[at] == '0') at++ else if (digits() == 0) return null
        var real = false
        if (at < text.length && text[at] == '.') { at++; real = true; if (digits() == 0) return null }
        if (at < text.length && (text[at] == 'e' || text[at] == 'E')) {
            at++; real = true
            if (at < text.length && (text[at] == '+' || text[at] == '-')) at++
            if (digits() == 0) return null
        }
        if (at < text.length && !ends(text[at])) return null
        val number = text.substring(start, at)
        return when {
            real -> parseDoubleOrNull(number)?.let(::DoubleNode)
            else -> number.toLongOrNull()?.let(::LongNode) ?: BigIntegerNode(BigInteger.parseString(number))
        }
    }

    private fun string(): String? {
        at++
        val start = at
        // The common case, a string with no escape: its text as it stands.
        while (at < text.length) {
            val c = text[at]
            if (c == '"') return text.substring(start, at++)
            if (c == '\\') break
            if (c < ' ') return null
            at++
        }
        if (at >= text.length) return null
        val out = StringBuilder().appendRange(text, start, at)
        while (at < text.length) {
            val c = text[at++]
            when {
                c == '"' -> return out.toString()
                c < ' ' -> return null
                c != '\\' -> out.append(c)
                at >= text.length -> return null
                else -> when (text[at++]) {
                    '"' -> out.append('"')
                    '\\' -> out.append('\\')
                    '/' -> out.append('/')
                    'b' -> out.append('\b')
                    'f' -> out.append('\u000C')
                    'n' -> out.append('\n')
                    'r' -> out.append('\r')
                    't' -> out.append('\t')
                    'u' -> {
                        if (at + 4 > text.length) return null
                        var code = 0
                        repeat(4) {
                            // ASCII hex digits alone, as kotlinx.serialization reads them: digitToIntOrNull takes any
                            // Unicode digit ('١') and full-width letter too.
                            val digit = when (val h = text[at++]) {
                                in '0'..'9' -> h - '0'
                                in 'a'..'f' -> h - 'a' + 10
                                in 'A'..'F' -> h - 'A' + 10
                                else -> return null
                            }
                            code = code * 16 + digit
                        }
                        out.append(code.toChar())
                    }
                    else -> return null
                }
            }
        }
        return null
    }

    private fun array(depth: Int): JsonNode? {
        at++
        val node = ArrayNode()
        space()
        if (at < text.length && text[at] == ']') { at++; return node }
        while (true) {
            node.add(value(depth) ?: return null)
            space()
            if (at >= text.length) return null
            when (text[at++]) {
                ',' -> continue
                ']' -> return node
                else -> return null
            }
        }
    }

    private fun obj(depth: Int): JsonNode? {
        at++
        val node = ObjectNode()
        space()
        if (at < text.length && text[at] == '}') { at++; return node }
        while (true) {
            space()
            if (at >= text.length || text[at] != '"') return null
            val key = string() ?: return null
            if (node.has(key)) return null
            space()
            if (at >= text.length || text[at++] != ':') return null
            node.set<JsonNode>(key, value(depth) ?: return null)
            space()
            if (at >= text.length) return null
            when (text[at++]) {
                ',' -> continue
                '}' -> return node
                else -> return null
            }
        }
    }
}
