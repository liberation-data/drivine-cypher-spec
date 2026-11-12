package org.drivine.cypher.oracle

/**
 * The regular expressions a SQLite statement's SQL gives SQLite's `REGEXP` and the regular expression functions Drivine
 * registers, at each depth its SQL nests statements: as a text of SQL, its quotes doubled, and as JSON's, its backslashes
 * doubled too. The pieces `=~` puts around a user's pattern are among them, and the user's pattern not. Read by
 * `SqliteRegexpPatternsTest` and by [Regexes], which writes Java's answers for each.
 */
internal object SqliteSqlPatterns {

    fun of(sql: String): Set<String> {
        val texts = generateSequence(listOf(sql)) { level -> level.flatMap { listOf(it.replace("''", "'"), it.replace("\\\\", "\\").replace("\\\"", "\"")) }.distinct() }
            .take(4).flatten().distinct()
        return texts.flatMap { text ->
            OPERAND.findAll(text).map { it.groupValues[1] } + MATCHES.findAll(text).flatMap { listOf(it.groupValues[1], it.groupValues[2]) } +
                FUNCTION.findAll(text).mapNotNull { secondArgument(text, it.range.last + 1) }
        }.map { it.replace("''", "'") }.filter { it.isNotEmpty() }.toSet()
    }

    /** The SQL text that is the second argument of the call whose arguments open at [from] in [sql]: null where it is no SQL text. */
    private fun secondArgument(sql: String, from: Int): String? {
        var depth = 0
        var at = from
        while (at < sql.length) {
            when (sql[at]) {
                '\'' -> at = sql.indexOf('\'', at + 1).takeIf { it >= 0 } ?: return null
                '(' -> depth++
                ')' -> if (depth-- == 0) return null
                ',' -> if (depth == 0) return LITERAL.matchAt(sql, at + 2)?.groupValues?.get(1)
            }
            at++
        }
        return null
    }

    /** A pattern given `REGEXP` as a SQL text. */
    private val OPERAND = Regex("""REGEXP '((?:[^']|'')*)'""")

    /** The pieces `=~` puts around a user's pattern: see `SqliteCypherCompiler.matches`. */
    private val MATCHES = Regex("""REGEXP \('((?:[^']|'')*)' \|\| .*? \|\| '(\)(?:[^']|'')*)'\)""")

    /** A call of a regular expression function Drivine registers, whose second argument, a SQL text, is a pattern. */
    private val FUNCTION = Regex("""(drivine_regex_group|drivine_regexp_replace|drivine_regexp_count)\(""")

    /** A SQL text. */
    private val LITERAL = Regex("""'((?:[^']|'')*)'""")
}
