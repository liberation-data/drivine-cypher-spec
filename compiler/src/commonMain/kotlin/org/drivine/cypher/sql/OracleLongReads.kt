package org.drivine.cypher.sql

import org.drivine.cypher.store.schema.SqlText

/**
 * The last pass over a statement Oracle's compiler wrote: each read of a stored value by a key path as JSON,
 * `JSON_QUERY(source, '$.k' RETURNING JSON)`, that may give a text past 4,000 bytes, is routed the CLOB way. Its source is
 * a node's or a relationship's properties (`n1.props`, or `w3.c1_props` where a `WITH`, a `CALL` or an `OPTIONAL MATCH` carries
 * the node), an unwound item or a carried row (`u1.item`, `cy_caller1`), a
 * write's row (`cy_row`), a `reduce()` step's context (`cy_context`), or a value worked out of one of them.
 *
 * **A workaround, to be removed with [OracleCypherCompiler.itemProperty]'s.** A long text property read as a direct member
 * of a list or a map, or as a nested property, is routed the CLOB way on Oracle, to avoid an Oracle server defect
 * (drivine4j#670). The compiler's other reads of such a value go their own CLOB way already; this pass routes whatever
 * read is left, so no shape of statement is missed.
 *
 * A read is left as it is where it can't give such a text: in a branch of a `CASE` that only a value of another kind than a
 * text reaches (after `WHEN JSON_VALUE(source, '$.k.type()') = 'string'`), and in the branch that only a text `JSON_VALUE`
 * reads whole reaches, at most 4,000 bytes (`JSON_VALUE(source, '$.k') IS NOT NULL OR NVL(JSON_VALUE(source,
 * '$.k.type()'), 'null') <> 'string'`).
 *
 * A property is read as JSON by a path that leaves out a text past 4,000 characters, and such a text, where there is one,
 * through its text, as [OracleCypherCompiler.itemProperty] reads one: `COALESCE(JSON_QUERY(p, '$?(@.k.type() != "string"
 * || @.k.length() <= 4000).k' RETURNING JSON), CASE WHEN JSON_EXISTS(p, '$.k') THEN … END)`. A list or a map, even one that holds
 * a long text (`['<40 KB>', 'y']`, `{t: '<40 KB>'}`), is read whole as JSON by the first, as [OracleCypherCompiler.rowValue] reads
 * one: over two rows and repeated runs, returned, collected, carried and compared, it needs no other route. A value of an item or a row is read
 * as [OracleCypherCompiler.itemValue] reads one, a text from the item written out as text. A key of a map read from a
 * property (`n.m.t`) is read by the whole path from the properties, `'$.m.t'`; and a read `JSON_VALUE` reads by a path of its
 * own (`JSON_VALUE(JSON_QUERY(n1.props, '$.a' RETURNING JSON), '$.type()')`) is read by the whole path from the stored
 * value, `JSON_VALUE(n1.props, '$.a.type()')`, as the compiler's own reads by a property's path are; but not by a path into a
 * list's item (`'$[$i].type()'`), which reads the routed value as it reads any list.
 *
 * Its cost, on 10,000 nodes of short texts: at most about 1 µs a row more, 0.6 to 0.9 µs where a property is read as JSON
 * (an `ORDER BY` key, `n.u = n.v`, `[x.i, x.name]`), and less than before where a temporal value is read, as its kind and
 * its text are read by their own paths. The `JSON_EXISTS` keeps a missing property from being written out: without it, a
 * sort by one took 5.6 µs a row more, with it 1.1 µs.
 */
internal object OracleLongReads {

    /** [sql] with each read off the CLOB route routed it. */
    fun routed(sql: String): String {
        val reads = unrouted(sql)
        if (reads.isEmpty()) return sql
        // The outermost only, in the order they're found: a read in another's source goes with it.
        val out = StringBuilder(sql.length * 2)
        var done = 0
        for (read in reads) {
            if (read.from < done) continue
            val scalar = if (read.guarded) null else scalarRead(sql, read)
            val (from, until, text) = scalar ?: Triple(read.from, read.until, route(read))
            out.appendRange(sql, done, from).append(text)
            done = until
        }
        return out.appendRange(sql, done, sql.length).toString()
    }

    /**
     * Where [read] is what `JSON_VALUE` reads by a path of its own, `JSON_VALUE(JSON_QUERY(n1.props, '$.a' RETURNING JSON),
     * '$.type()'`: that start of the call, from where to where it is in [sql], read from the stored value by the whole path,
     * `JSON_VALUE(n1.props, '$.a.type()'`, which takes no value out as JSON.
     */
    private fun scalarRead(sql: String, read: Read): Triple<Int, Int, String>? {
        val (base, path) = chain(read.source) ?: return null
        var start = read.from
        while (start > 0 && sql[start - 1] == ' ') start--
        if (!sql.startsWith("JSON_VALUE(", start - "JSON_VALUE(".length)) return null
        var at = read.until
        while (at < sql.length && sql[at] == ' ') at++
        if (at >= sql.length || sql[at] != ',') return null
        at++
        while (at < sql.length && sql[at] == ' ') at++
        if (!sql.startsWith("'$", at)) return null
        var end = at + 1
        while (end < sql.length) {
            if (sql[end] == '\'') { if (end + 1 < sql.length && sql[end + 1] == '\'') { end += 2; continue }; break }
            end++
        }
        if (end >= sql.length) return null
        // Not into a list's item, `'$[$i]'`: the routed value is read as any list is, to avoid an Oracle server defect (drivine4j#670).
        if (sql.substring(at, end).replace(QUOTED_KEY, "").contains('[')) return null
        val whole = path + read.path.drop(2).dropLast(1) + sql.substring(at + 2, end + 1)
        return Triple(start - "JSON_VALUE(".length, end + 1, "JSON_VALUE($base, $whole")
    }

    /**
     * A read at [from] until [until], `JSON_QUERY(source, path RETURNING JSON)`, its source as written. A [guarded] one is
     * left off the CLOB route, as a `CASE` keeps it to a value that is no long text, but read by its whole path where its
     * source is a stored value's read, as that `CASE`'s test of it is (see [scalarRead]).
     */
    private class Read(val from: Int, val until: Int, val source: String, val path: String, val guarded: Boolean = false)

    private fun route(read: Read): String {
        if (read.guarded) {
            val (base, path) = chain(read.source)!!
            return "JSON_QUERY($base, $path${read.path.drop(2)} RETURNING JSON)"
        }
        chain(read.source)?.let { (base, path) -> return routed(base, path + read.path.drop(2).dropLast(1)) }
        // A value worked out of a stored one: its reads routed first.
        val source = routed(read.source)
        return clobRead(source, read.path)
    }

    /** The base and the path, all but its last quote, of a read of a stored value by a key path, `JSON_QUERY(n1.props, '$.m' …)`, or of such a read's. */
    private fun chain(source: String): Pair<String, String>? {
        if (BASE.matches(source)) return source to "'$"
        if (!source.startsWith("JSON_QUERY(") || closing(source, "JSON_QUERY".length) != source.length - 1) return null
        val arguments = arguments(source.substring("JSON_QUERY(".length, source.length - 1))
        if (arguments.size != 2) return null
        val path = READ_PATH.matchEntire(arguments[1])?.groupValues?.get(1)?.takeIf { KEY_PATH.matches(it) } ?: return null
        val (base, before) = chain(arguments[0]) ?: return null
        return base to before + path.drop(2).dropLast(1)
    }

    /** The read of [path], all but its last quote, from the stored value [base]. */
    private fun routed(base: String, path: String): String {
        val whole = "$path'"
        return if (ITEM.matches(base)) OracleCypherCompiler.throughText(base, whole, "$path.type()'") else clobRead(base, whole)
    }

    /** The read of [path] from [json] as JSON, a text past [OracleCypherCompiler.SHORT_TEXT] characters through its text. */
    private fun clobRead(json: String, path: String): String {
        // The filter is the map's, so a list is kept whole: one of the value's own would test each of a list's items.
        val tail = path.drop(2).dropLast(1)
        val short = "'$?(@$tail.type() != \"string\" || @$tail.length() <= ${OracleCypherCompiler.SHORT_TEXT})$tail'"
        return "COALESCE(JSON_QUERY($json, $short RETURNING JSON), CASE WHEN JSON_EXISTS($json, $path) THEN ${OracleCypherCompiler.keptText(json, path)} END)"
    }

    /** A stored value a read may have as its source, or in it. */
    private val STORED = Regex("""\b[A-Za-z_]\w*\.(?:\w+_)?props\b|\b[A-Za-z_]\w*\.item\b|\bcy_(?:row|context|caller\d+|saved\d+|gathered\d+)\b""")
    private val BASE = Regex("""[A-Za-z_]\w*\.(?:(?:\w+_)?props|item)|cy_(?:row|context|caller\d+|saved\d+|gathered\d+)""")

    /** A base read as an item is: one that may hold a map with a key as extended JSON's are, as a parameter a `reduce()` step's context holds may. */
    private val ITEM = Regex("""[A-Za-z_]\w*\.item|cy_(?:row|context|caller\d+|saved\d+|gathered\d+)""")
    private val READ_PATH = Regex("""('\$${SqlText.BODY}') RETURNING JSON""")
    /** A key of a path in its quotes, `"a[b"`, as a key path has one: unrolled as [SqlText.BODY] is. */
    private val QUOTED_KEY = Regex(""""[^"'\\]*(?:(?:\\.|'')[^"'\\]*)*"""")
    private val KEY_PATH = Regex("""'\$(?:\.(?:[A-Za-z_]\w*|${QUOTED_KEY.pattern}))+'""")

    /** [sql] with each run of white space outside its literals one space: a key's own spaces are kept. */
    private fun normal(sql: String) = sql.split('\'').mapIndexed { i, part -> if (i % 2 == 0) part.replace(SPACE, " ") else part }.joinToString("'").trim()

    private val SPACE = Regex("\\s+")

    /**
     * A CASE open at [depth]: its subject, if it is a simple CASE, the conditions of the branches before and their OR-ed terms,
     * the condition of the branch the scan is in and its AND-ed terms, and where in it the scan is. Each is split once, so a
     * CASE of many branches is read in one pass.
     */
    private class Open(val depth: Int, var mark: Int) {
        var state = "subject"
        var subject: String? = null
        val before = HashSet<String>()
        val ored = HashSet<String>()
        var condition: String? = null
        var anded: Set<String> = emptySet()

        /** The branch the scan was in is passed. */
        fun passed() {
            condition?.let { before += it; ored += parts(it, " OR ") }
            condition = null
            anded = emptySet()
        }
    }

    /** The reads in [sql] off the CLOB route. */
    private fun unrouted(sql: String): List<Read> {
        if (!sql.contains(" RETURNING JSON)") || !STORED.containsMatchIn(sql)) return emptyList()
        val found = mutableListOf<Read>()
        val stack = ArrayList<Open>()
        var depth = 0
        var i = 0
        while (i < sql.length) {
            val c = sql[i]
            if (c == '\'') {
                var j = i + 1
                while (j < sql.length) {
                    if (sql[j] == '\'') { if (j + 1 < sql.length && sql[j + 1] == '\'') { j += 2; continue }; break }
                    j++
                }
                i = j + 1
                continue
            }
            if (c == '(') { depth++; i++; continue }
            if (c == ')') { depth--; while (stack.isNotEmpty() && stack.last().depth > depth) stack.removeAt(stack.size - 1); i++; continue }
            if (!(c.isLetter() || c == '_') || (i > 0 && (sql[i - 1].isLetterOrDigit() || sql[i - 1] in "_$."))) { i++; continue }
            var end = i + 1
            while (end < sql.length && (sql[end].isLetterOrDigit() || sql[end] == '_' || sql[end] == '$')) end++
            val top = stack.lastOrNull()?.takeIf { it.depth == depth }
            when (sql.substring(i, end).uppercase()) {
                "CASE" -> stack += Open(depth, end)
                "WHEN" -> if (top != null) {
                    if (top.state == "subject") top.subject = normal(sql.substring(top.mark, i)).ifEmpty { null }
                    top.passed()
                    top.state = "when"
                    top.mark = end
                }
                "THEN" -> if (top != null && top.state == "when") {
                    // AND-ed terms only of a condition with no OR-ed ones: `a AND b OR c` holds where `a` doesn't.
                    top.condition = normal(sql.substring(top.mark, i)).also { top.anded = parts(it, " OR ").singleOrNull()?.let { one -> parts(one, " AND ").toHashSet() } ?: emptySet() }
                    top.state = "then"
                }
                "ELSE" -> if (top != null) { top.passed(); top.state = "else" }
                "END" -> if (top != null) stack.removeAt(stack.size - 1)
                "JSON_QUERY" -> if (end < sql.length && sql[end] == '(') read(sql, i, end, stack)?.let { found += it }
            }
            i = end
        }
        return found
    }

    /** The read at [at], whose `(` is at [open], where it is off the CLOB route in the CASEs [stack]. */
    private fun read(sql: String, at: Int, open: Int, stack: List<Open>): Read? {
        val close = closing(sql, open).takeIf { it >= 0 } ?: return null
        val arguments = arguments(sql.substring(open + 1, close))
        if (arguments.size != 2) return null
        val path = READ_PATH.matchEntire(arguments[1])?.groupValues?.get(1)?.takeIf { KEY_PATH.matches(it) } ?: return null
        if (!STORED.containsMatchIn(arguments[0])) return null
        val source = normal(arguments[0])
        if (guarded(stack, source, path)) {
            return if (arguments[0].startsWith("JSON_QUERY(") && chain(arguments[0]) != null) Read(at, close + 1, arguments[0], path, guarded = true) else null
        }
        return Read(at, close + 1, arguments[0], path)
    }

    /** Whether the CASEs [stack] keep the read of [path] from [source] to a value that is no text past 4,000 bytes. */
    private fun guarded(stack: List<Open>, source: String, path: String): Boolean {
        val typed = "JSON_VALUE($source, ${path.dropLast(1)}.type()')"
        val short = "JSON_VALUE($source, $path)"
        val stringCase = "$typed = 'string'"
        for (frame in stack) {
            if (frame.subject == typed) {
                if (frame.state == "then" && frame.condition != "'string'" && KIND.matches(frame.condition ?: "")) return true
                if (frame.state != "then" && "'string'" in frame.before) return true
            }
            // The rest are a searched CASE's: a simple one's branch is reached by a value equal to its subject, not by a condition.
            if (frame.subject != null) continue
            if (stringCase in frame.ored) return true
            if (frame.state == "then") {
                val condition = frame.condition ?: continue
                if (otherKind(condition, typed)) return true
                if (condition == "$short IS NOT NULL OR NVL($typed, 'null') <> 'string'") return true
                if ("$short IS NOT NULL" in frame.anded) return true
            }
        }
        return false
    }

    /** Whether [condition] is [typed] equal to, or in, kinds that aren't a text. */
    private fun otherKind(condition: String, typed: String): Boolean {
        if (!condition.startsWith("$typed ")) return false
        val rest = condition.substring(typed.length + 1)
        val kinds = when {
            rest.startsWith("= ") -> listOf(rest.substring(2))
            rest.startsWith("IN (") && rest.endsWith(")") -> rest.substring(4, rest.length - 1).split(", ")
            else -> return false
        }
        return kinds.all { KIND.matches(it) && it != "'string'" }
    }

    private val KIND = Regex("""'\w+'""")

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

    /** Whether the word [word] is at [at] in [sql]. */
    private fun word(sql: String, at: Int, word: String) =
        sql.regionMatches(at, word, 0, word.length, ignoreCase = true) && (at == 0 || !(sql[at - 1].isLetterOrDigit() || sql[at - 1] in "_$.")) &&
            (at + word.length == sql.length || !(sql[at + word.length].isLetterOrDigit() || sql[at + word.length] in "_$"))

    /** The index of the `)` that closes the `(` at [open] in [sql], or -1. */
    private fun closing(sql: String, open: Int): Int {
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
