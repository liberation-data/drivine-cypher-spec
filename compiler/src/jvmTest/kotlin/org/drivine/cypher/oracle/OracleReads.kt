package org.drivine.cypher.oracle

/**
 * The property reads in a statement Oracle's compiler wrote, found in its SQL: each `JSON_QUERY(source, '$.k' RETURNING
 * JSON)` by a key path whose source reads stored JSON, a node's or a relationship's properties (`n1.props`, or `w3.c1_props` where a node is carried), an unwound
 * item or a carried row (`u1.item`, `r1.item`), a write's row (`cy_row`), a row a `CALL { … }` carries (`cy_caller1`), a `reduce()` step's context (`cy_context`), or a value worked out of one of them (a map
 * read from the properties, a map built of a property). A read is [Read.safe] where it can't take a text past 4,000 bytes
 * out as JSON: see [SAFE].
 *
 * Reads of other sources, the statement's parameters among them, aren't property reads and aren't listed; nor is a read by
 * a path that filters what it reads (`'$?(@.a.length() <= 4000).a'`), or one through a text (`RETURNING CLOB`).
 */
object OracleReads {

    /** A read at [at] in the SQL of [path] from [source], and the context of [SAFE] that keeps it safe, if one does. */
    data class Read(val at: Int, val source: String, val path: String, val safeBy: String?) {
        val safe get() = safeBy != null
    }

    /**
     * The one allowlist: what is proved to give no text past 4,000 bytes, each with why. The first three are never read by
     * `JSON_QUERY … RETURNING JSON` at all, so no read is let through for them; the last two are the contexts a read so is
     * safe in.
     */
    val SAFE: List<Pair<String, String>> = listOf(
        "number" to "A number the compiler reads to compute with is read by JSON_VALUE(… RETURNING NUMBER or BINARY_DOUBLE), which gives no text.",
        "boolean" to "A truth value is read by JSON_VALUE(… RETURNING BOOLEAN) or tested by JSON_EXISTS, which give no text.",
        "key-column" to "A node's or a relationship's id, labels, type, ends and stamp are columns of their own (nid, labels, eid, src, dst, type, stamp), not read from the properties.",
        "kind-case" to "The read is in a branch of a CASE that only a value of another kind than a text reaches: an ELSE or a later branch after " +
            "`WHEN JSON_VALUE(source, '\$.k.type()') = 'string'`, or a condition that has it as one of its OR-ed terms (reached where that is " +
            "false, another kind, or null, no value), the branch of a " +
            "simple CASE on that kind for another kind, or a `THEN` whose condition is that kind equal to, or in, other kinds.",
        "short-or-not-text" to "The read is in the THEN of `JSON_VALUE(source, '\$.k') IS NOT NULL OR NVL(JSON_VALUE(source, '\$.k.type()'), 'null') <> " +
            "'string'`, or of a condition that has `JSON_VALUE(source, '\$.k') IS NOT NULL` as one of its AND-ed terms and has no OR-ed ones: a value of another kind, " +
            "or a text JSON_VALUE reads whole as a VARCHAR2(4000), at most 4,000 bytes (a longer one is null).",
    )

    /**
     * The read of [path] from the stored properties [props] as Oracle's compiler routes one the CLOB way: as JSON by a path that
     * leaves out a text past 4,000 characters, and such a text through its text.
     */
    fun routedRead(props: String, path: String) =
        "COALESCE(JSON_QUERY($props, '$?(@${path.drop(2).dropLast(1)}.type() != \"string\" || @${path.drop(2).dropLast(1)}.length() <= 4000)${path.drop(2)} RETURNING JSON), " +
            "CASE WHEN JSON_EXISTS($props, $path) THEN " +
            "JSON(JSON_QUERY(JSON_SERIALIZE(JSON_TRANSFORM($props, KEEP $path) RETURNING CLOB EXTENDED), $path RETURNING CLOB) EXTENDED) END)"

    /** The read of [path] from the item or row [item] as Oracle's compiler routes one the CLOB way: a text through the item's text. */
    fun routedItem(item: String, path: String) =
        "CASE WHEN JSON_VALUE($item, ${path.dropLast(1)}.type()') = 'string' THEN JSON(JSON_QUERY(JSON_SERIALIZE($item RETURNING CLOB), $path RETURNING CLOB)) " +
            "ELSE JSON_QUERY($item, $path RETURNING JSON) END"

    /** Every property read in [sql]. */
    fun reads(sql: String): List<Read> = cases(sql).mapNotNull { (at, frames) ->
        val open = at + "JSON_QUERY".length
        val close = closing(sql, open).takeIf { it >= 0 } ?: return@mapNotNull null
        val arguments = arguments(sql.substring(open + 1, close))
        if (arguments.size != 2) return@mapNotNull null
        val path = READ_PATH.matchEntire(arguments[1])?.groupValues?.get(1)?.takeIf { KEY_PATH.matches(it) } ?: return@mapNotNull null
        val source = normal(arguments[0])
        if (!STORED.containsMatchIn(source)) return@mapNotNull null
        Read(at, source, path, safeBy(frames, source, path))
    }

    /** The reads in [sql] that aren't safe. */
    fun unsafe(sql: String) = reads(sql).filterNot { it.safe }

    private val STORED = Regex("""\b[A-Za-z_]\w*\.(?:\w+_)?props\b|\b[A-Za-z_]\w*\.item\b|\bcy_(?:row|context|caller\d+|saved\d+|gathered\d+)\b""")
    private val READ_PATH = Regex("""('\$(?:[^']|'')*') RETURNING JSON""")
    private val KEY_PATH = Regex("""'\$(?:\.(?:[A-Za-z_]\w*|"(?:[^"'\\]|\\.|'')*"))+'""")
    private val WORD = Regex("""[A-Za-z_][\w$]*""")

    /** [sql] with each run of white space outside its literals one space: a key's own spaces are kept. */
    private fun normal(sql: String) = sql.split('\'').mapIndexed { i, part -> if (i % 2 == 0) part.replace(Regex("\\s+"), " ") else part }.joinToString("'").trim()

    /** The CASE an expression is in: its subject, if it is a simple CASE, the conditions of the branches before, and where in it the expression is. */
    private data class Frame(val subject: String?, val before: List<String>, val condition: String?, val state: String)

    private class Open(val depth: Int) {
        var state = "subject"
        var mark = 0
        var subject: String? = null
        val before = mutableListOf<String>()
        var condition: String? = null
    }

    /** Each `JSON_QUERY(` in [sql], with the CASEs it is in, innermost last. */
    private fun cases(sql: String): List<Pair<Int, List<Frame>>> {
        val found = mutableListOf<Pair<Int, List<Frame>>>()
        val stack = ArrayDeque<Open>()
        var depth = 0
        var i = 0
        while (i < sql.length) {
            val c = sql[i]
            when {
                c == '\'' -> {
                    var j = i + 1
                    while (j < sql.length) {
                        if (sql[j] == '\'') { if (j + 1 < sql.length && sql[j + 1] == '\'') { j += 2; continue }; break }
                        j++
                    }
                    i = j + 1
                    continue
                }
                c == '(' -> { depth++; i++; continue }
                c == ')' -> { depth--; while (stack.isNotEmpty() && stack.last().depth > depth) stack.removeLast(); i++; continue }
            }
            val word = if (i == 0 || !(sql[i - 1].isLetterOrDigit() || sql[i - 1] in "_$.")) WORD.matchAt(sql, i) else null
            if (word == null) { i++; continue }
            val top = stack.lastOrNull()?.takeIf { it.depth == depth }
            when (word.value.uppercase()) {
                "CASE" -> stack.addLast(Open(depth).apply { mark = word.range.last + 1 })
                "WHEN" -> if (top != null) {
                    if (top.state == "subject") top.subject = normal(sql.substring(top.mark, i)).ifEmpty { null }
                    top.condition?.let { top.before += it }
                    top.condition = null
                    top.state = "when"
                    top.mark = word.range.last + 1
                }
                "THEN" -> if (top != null && top.state == "when") { top.condition = normal(sql.substring(top.mark, i)); top.state = "then" }
                "ELSE" -> if (top != null) { top.condition?.let { top.before += it }; top.condition = null; top.state = "else" }
                "END" -> if (top != null) stack.removeLast()
                "JSON_QUERY" -> if (sql.startsWith("(", word.range.last + 1))
                    found += i to stack.map { Frame(it.subject, it.before.toList(), it.condition, it.state) }
            }
            i = word.range.last + 1
        }
        return found
    }

    /** Which of [SAFE] keeps the read of [path] from [source] in [frames] safe, if one does. */
    private fun safeBy(frames: List<Frame>, source: String, path: String): String? {
        val typed = "JSON_VALUE($source, ${path.dropLast(1)}.type()')"
        val short = "JSON_VALUE($source, $path)"
        val otherKind = Regex(Regex.escape(typed) + """ (?:= '(?!string')\w+'|IN \('(?!string')\w+'(?:, '(?!string')\w+')*\))""")
        for (frame in frames) {
            if (frame.subject == typed) {
                if (frame.state == "then" && frame.condition != "'string'" && frame.condition?.matches(Regex("""'\w+'""")) == true) return "kind-case"
                if (frame.state != "then" && "'string'" in frame.before) return "kind-case"
            }
            // The rest are a searched CASE's: a simple one's branch is reached by a value equal to its subject, not by a condition.
            if (frame.subject != null) continue
            if (frame.before.any { "$typed = 'string'" in parts(it, " OR ") }) return "kind-case"
            if (frame.state == "then") {
                val condition = frame.condition ?: continue
                if (otherKind.matches(condition)) return "kind-case"
                if (condition == "$short IS NOT NULL OR NVL($typed, 'null') <> 'string'") return "short-or-not-text"
                if ("$short IS NOT NULL" in (parts(condition, " OR ").singleOrNull()?.let { parts(it, " AND ") } ?: emptyList())) return "short-or-not-text"
            }
        }
        return null
    }

    /** [condition] split at [operator] (` AND `, ` OR `) where it is outside parentheses, `CASE … END` and literals. */
    private fun parts(condition: String, operator: String): List<String> {
        val out = mutableListOf<String>()
        var depth = 0
        var quoted = false
        var start = 0
        var j = 0
        while (j < condition.length) {
            val c = condition[j]
            when {
                c == '\'' -> quoted = !quoted
                quoted -> {}
                c == '(' || word(condition, j, "CASE") -> depth++
                c == ')' || word(condition, j, "END") -> depth--
                depth == 0 && condition.startsWith(operator, j) -> { out += condition.substring(start, j); start = j + operator.length; j = start; continue }
            }
            j++
        }
        out += condition.substring(start)
        return out
    }

    private fun word(sql: String, at: Int, word: String) =
        sql.regionMatches(at, word, 0, word.length, ignoreCase = true) && (at == 0 || !(sql[at - 1].isLetterOrDigit() || sql[at - 1] in "_$.")) &&
            (at + word.length == sql.length || !(sql[at + word.length].isLetterOrDigit() || sql[at + word.length] in "_$"))

    /** The index of the `)` that closes the `(` at [open] in [sql], or -1. */
    fun closing(sql: String, open: Int): Int {
        var depth = 0
        var quoted = false
        for (j in open until sql.length) {
            val c = sql[j]
            when {
                c == '\'' -> quoted = !quoted
                quoted -> {}
                c == '(' -> depth++
                c == ')' -> if (--depth == 0) return j
            }
        }
        return -1
    }

    /** The arguments of a call, the text between its parentheses, split at its top level. */
    private fun arguments(sql: String): List<String> {
        val out = mutableListOf<String>()
        var depth = 0
        var quoted = false
        var start = 0
        sql.forEachIndexed { j, c ->
            when {
                c == '\'' -> quoted = !quoted
                quoted -> {}
                c == '(' -> depth++
                c == ')' -> depth--
                c == ',' && depth == 0 -> { out += sql.substring(start, j).trim(); start = j + 1 }
            }
        }
        out += sql.substring(start).trim()
        return out
    }
}
