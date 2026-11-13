package org.drivine.cypher.store.schema

import org.drivine.DrivineInternalApi

/**
 * An index column's text made comparable: what the module writes for a column ([org.drivine.cypher.store.ddl]'s builders)
 * and what a store's dictionary gives back for it, each put in the one canonical form of its store's [Dialect], so the two
 * are equal where the store holds what the module made. No parser of SQL: the text is cut into tokens, literals kept as
 * they are, and what each store adds or drops as it prints a column again is taken out of both.
 */
@DrivineInternalApi
object IndexText {

    /** How a store prints an index column again. */
    enum class Dialect {
        /** `pg_get_indexdef`: casts added (`'name'::text`), parentheses added or dropped; identifiers in lower case. */
        POSTGRES,

        /**
         * `user_tab_cols.data_default_vc`: identifiers in upper case and quoted, a table's alias dropped, `FORMAT OSON`,
         * `RETURNING VARCHAR2(4000)` and `NULL ON ERROR`/`FALSE ON ERROR`/`TYPE(LAX)` added, `JSON_EXISTS` written
         * `JSON_EXISTS2(…)=1`, a searched `CASE WHEN JSON_EXISTS(…) THEN` written `CASE JSON_EXISTS2(…) WHEN 1 THEN`, a
         * comment of the path added, numbers written as binary doubles (`4.294967296E+009D`), parentheses added.
         */
        ORACLE,

        /** `sqlite_master.sql`: the statement as it was written, so only the spaces between tokens can differ. */
        SQLITE,
    }

    /** [text], a column's expression, in [dialect]'s canonical form: its tokens, each one space apart. */
    fun canonical(text: String, dialect: Dialect): String {
        var tokens = tokens(text, dialect)
        when (dialect) {
            Dialect.POSTGRES -> {
                tokens = withoutCasts(tokens)
                tokens = tokens.filter { it != "(" && it != ")" }
            }
            Dialect.ORACLE -> {
                tokens = withoutAliases(tokens)
                for (dropped in ORACLE_ADDED) tokens = without(tokens, dropped)
                tokens = tokens.map { if (it == "JSON_EXISTS2") "JSON_EXISTS" else it }
                tokens = existsWithoutEqualsOne(tokens)
                tokens = searchedExists(tokens)
                tokens = tokens.filter { it != "(" && it != ")" }
            }
            Dialect.SQLITE -> Unit
        }
        return tokens.joinToString(" ")
    }

    /** What Oracle adds to a column as it prints it again, whatever the module wrote. */
    private val ORACLE_ADDED = listOf(
        listOf("FORMAT", "OSON"),
        listOf("NULL", "ON", "ERROR"),
        listOf("FALSE", "ON", "ERROR"),
        listOf("TYPE", "(", "LAX", ")"),
        listOf("RETURNING", "VARCHAR2", "(", "N4E3", ")"),
    )

    /**
     * [text]'s tokens: a literal `'…'` as it is, a quoted identifier as its name, a word, a number (on Oracle in a form
     * that reads `4294967296` and `4.294967296E+009D` alike: its first 15 significant digits and its exponent), and each
     * other character, `||`, `->`, `->>`, `::`, `>=`, `<=` and `<>` as one. A comment is no token. On Oracle and Postgres a
     * word is in upper case.
     */
    internal fun tokens(text: String, dialect: Dialect): List<String> {
        val out = mutableListOf<String>()
        var i = 0
        while (i < text.length) {
            val c = text[i]
            when {
                c.isWhitespace() -> i++
                c == '/' && text.startsWith("/*", i) -> i = text.indexOf("*/", i + 2).let { if (it < 0) text.length else it + 2 }
                c == '\'' -> {
                    var j = i + 1
                    while (j < text.length) {
                        if (text[j] == '\'') { if (j + 1 < text.length && text[j + 1] == '\'') j += 2 else break } else j++
                    }
                    out += text.substring(i, minOf(j + 1, text.length))
                    i = j + 1
                }
                c == '"' -> {
                    val j = text.indexOf('"', i + 1).let { if (it < 0) text.length else it }
                    out += word(text.substring(i + 1, j), dialect)
                    i = j + 1
                }
                c.isLetter() || c == '_' -> {
                    var j = i
                    while (j < text.length && (text[j].isLetterOrDigit() || text[j] == '_' || text[j] == '$' || text[j] == '#')) j++
                    out += word(text.substring(i, j), dialect)
                    i = j
                }
                c.isDigit() || (c == '.' && i + 1 < text.length && text[i + 1].isDigit()) -> {
                    var j = i
                    while (j < text.length && (text[j].isDigit() || text[j] == '.')) j++
                    if (j < text.length && (text[j] == 'E' || text[j] == 'e') && j + 1 < text.length && (text[j + 1].isDigit() || text[j + 1] == '+' || text[j + 1] == '-')) {
                        j += 2
                        while (j < text.length && text[j].isDigit()) j++
                    }
                    val number = text.substring(i, j)
                    if (dialect == Dialect.ORACLE && j < text.length && (text[j] == 'D' || text[j] == 'F') && (j + 1 == text.length || !text[j + 1].isLetterOrDigit())) j++
                    out += if (dialect == Dialect.ORACLE) number(number) else number
                    i = j
                }
                else -> {
                    val two = if (i + 1 < text.length) text.substring(i, i + 2) else ""
                    val three = if (i + 2 < text.length) text.substring(i, i + 3) else ""
                    val op = when {
                        three == "->>" -> three
                        two in setOf("||", "->", "::", ">=", "<=", "<>", "!=") -> two
                        else -> c.toString()
                    }
                    out += op
                    i += op.length
                }
            }
        }
        return out
    }

    private fun word(word: String, dialect: Dialect): String = when (dialect) {
        Dialect.ORACLE -> word.uppercase()
        Dialect.POSTGRES -> word.lowercase()
        Dialect.SQLITE -> word
    }

    /** A number as its first 15 significant digits, zeros after them dropped, and the power of ten of its first: `N4294967296E9`. */
    private fun number(text: String): String {
        val mantissa = text.substringBefore('E').substringBefore('e')
        val exponent = if (mantissa.length < text.length) text.substring(mantissa.length + 1).toIntOrNull() ?: return text else 0
        val whole = mantissa.substringBefore('.')
        val digits = (whole + mantissa.substringAfter('.', "")).trimStart('0')
        if (digits.isEmpty()) return "N0"
        val leadingZeros = (whole + mantissa.substringAfter('.', "")).length - digits.length
        val power = whole.length - 1 - leadingZeros + exponent
        return "N${digits.take(15).trimEnd('0')}E$power"
    }

    /** Postgres's casts, `::text`, `::jsonb`, `::character varying`, taken out. */
    private fun withoutCasts(tokens: List<String>): List<String> {
        val out = mutableListOf<String>()
        var i = 0
        while (i < tokens.size) {
            if (tokens[i] == "::") {
                i++
                while (i < tokens.size && tokens[i].first().isLetter()) i++
                continue
            }
            out += tokens[i++]
        }
        return out
    }

    /** A table's alias before a column (`n.props`), taken out. */
    private fun withoutAliases(tokens: List<String>): List<String> {
        val out = mutableListOf<String>()
        var i = 0
        while (i < tokens.size) {
            if (i + 2 < tokens.size && tokens[i + 1] == "." && tokens[i].first().isLetter() && tokens[i + 2].first().isLetter()) { i += 2; continue }
            out += tokens[i++]
        }
        return out
    }

    private fun without(tokens: List<String>, dropped: List<String>): List<String> {
        val out = mutableListOf<String>()
        var i = 0
        while (i < tokens.size) {
            if (i + dropped.size <= tokens.size && tokens.subList(i, i + dropped.size) == dropped) { i += dropped.size; continue }
            out += tokens[i++]
        }
        return out
    }

    /** Where the call opened at [open], a `(`, closes; the last token where it doesn't. */
    private fun closing(tokens: List<String>, open: Int): Int {
        var depth = 0
        for (i in open until tokens.size) {
            if (tokens[i] == "(") depth++
            if (tokens[i] == ")" && --depth == 0) return i
        }
        return tokens.size - 1
    }

    /** `JSON_EXISTS(…) = 1`, as Oracle writes a test, as the module writes it: `JSON_EXISTS(…)`. */
    private fun existsWithoutEqualsOne(tokens: List<String>): List<String> {
        val out = tokens.toMutableList()
        var i = 0
        while (i < out.size) {
            if (out[i] == "JSON_EXISTS" && i + 1 < out.size && out[i + 1] == "(") {
                val end = closing(out, i + 1)
                if (end + 2 < out.size && out[end + 1] == "=" && out[end + 2] == "N1E0") { out.removeAt(end + 1); out.removeAt(end + 1) }
            }
            i++
        }
        return out
    }

    /** `CASE JSON_EXISTS(…) WHEN 1 THEN`, as Oracle writes a test of a label, as the module writes it: `CASE WHEN JSON_EXISTS(…) THEN`. */
    private fun searchedExists(tokens: List<String>): List<String> {
        val out = tokens.toMutableList()
        var i = 0
        while (i + 2 < out.size) {
            if (out[i] == "CASE" && out[i + 1] == "JSON_EXISTS" && out[i + 2] == "(") {
                val end = closing(out, i + 2)
                if (end + 3 < out.size && out[end + 1] == "WHEN" && out[end + 2] == "N1E0" && out[end + 3] == "THEN") {
                    out.removeAt(end + 1); out.removeAt(end + 1)
                    out.add(i + 1, "WHEN")
                }
            }
            i++
        }
        return out
    }

    /** The key a template is written with in place of a property's, and the label in place of a label: words no item has. */
    const val KEY = "zqdrivinekeyzq"
    const val LABEL = "ZqDrivineLabelZq"

    /**
     * A column of what the module makes, written with [KEY] for its property and [LABEL] for its label, made into a pattern
     * of its canonical form: matched by another column's canonical text, it gives the property that column reads and the
     * label it tests, each as the store writes it in the column ([key], [label]); null where it doesn't match.
     */
    class Template(written: String, dialect: Dialect) {
        private var keyGroup = 0
        private var labelGroup = 0
        private val regex: Regex = run {
            val canonical = canonical(written, dialect)
            val pattern = StringBuilder()
            var groups = 0
            var at = 0
            while (true) {
                val k = canonical.indexOf(KEY, at)
                val l = canonical.indexOf(LABEL, at)
                val next = listOf(k, l).filter { it >= 0 }.minOrNull() ?: break
                pattern.append(Regex.escape(canonical.substring(at, next)))
                if (next == k) {
                    if (keyGroup == 0) { keyGroup = ++groups; pattern.append("(.+?)") } else pattern.append("\\$keyGroup")
                    at = next + KEY.length
                } else {
                    if (labelGroup == 0) { labelGroup = ++groups; pattern.append("(.+?)") } else pattern.append("\\$labelGroup")
                    at = next + LABEL.length
                }
            }
            pattern.append(Regex.escape(canonical.substring(at)))
            Regex(pattern.toString())
        }

        /** Whether the template tests a label. */
        val labelled: Boolean
            get() = labelGroup != 0

        /** The key and the label (null where the template tests none) [canonical], a column's canonical text, reads; null where it isn't of this template. */
        fun match(canonical: String): Pair<String, String?>? {
            val m = regex.matchEntire(canonical) ?: return null
            if (keyGroup == 0) return null
            return m.groupValues[keyGroup] to (if (labelGroup != 0) m.groupValues[labelGroup] else null)
        }
    }
}
