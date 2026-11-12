package org.drivine.cypher.search

import org.drivine.cypher.text.JavaText

/**
 * drivine4j's `Query` and the row sources it reads (`org.drivine.backend.sql.Query`, and Oracle's `GraphTable`), as far as a
 * full-text or a vector search's statement is written with them: one `SELECT`, a clause to a line, a query nested in another
 * under the line that opens it, so the statement is drivine4j's byte for byte.
 */
internal interface RowSource {
    /** The source as it stands after `FROM`, for a query whose other clauses are [readers]. */
    fun reading(readers: List<String>): String
}

/** A table of the graph, [name], as rows named [alias]. */
internal class Table(private val name: String, private val alias: String) : RowSource {
    override fun reading(readers: List<String>): String = "$name $alias"
}

/** A query written elsewhere, as rows named [alias]. */
internal class Derived(private val query: String, private val alias: String) : RowSource {
    override fun reading(readers: List<String>): String = "(\n    ${query.under()}\n) $alias"
}

/**
 * The rows [grouped] gives, named [once], joined to their rows of [nodes], named [row], by `nid`: how a search among ids joins
 * what a vector index finds to the roots searched. drivine4j's `OnceReached`.
 */
internal class OnceReached(private val grouped: String, private val once: String, private val nodes: String, private val row: String) : RowSource {
    override fun reading(readers: List<String>): String = "(\n    ${grouped.under()}\n) $once\nJOIN $nodes $row ON $row.nid = $once.nid"
}

/** The rows of [name] as [first], each joined to the rows of [name] as [alias] that meet [on]: every pair. drivine4j's `Paired`. */
internal class Paired(private val name: String, private val first: String, private val alias: String, private val on: String) : RowSource {
    override fun reading(readers: List<String>): String = "$name $first\nJOIN $name $alias ON $on"
}

/**
 * Oracle's nodes of [graph] that meet [pattern], as rows named [alias] of the [columns] the query reads (`GRAPH_TABLE`), and
 * the first where it reads none. drivine4j's `GraphTable`.
 */
internal class GraphTable(private val graph: String, private val pattern: String, private val columns: List<String>, private val alias: String) : RowSource {
    override fun reading(readers: List<String>): String {
        val read = columns.filter { column -> readers.any { reads(it, column) } }.ifEmpty { columns.take(1) }
        return "GRAPH_TABLE ($graph\n    MATCH $pattern\n    COLUMNS (${read.joinToString(", ") { "$alias.$it" }})) $alias"
    }

    /** Whether [text] names the column [name] of a row of this source. */
    private fun reads(text: String, name: String): Boolean {
        val reference = "$alias.$name"
        var at = text.indexOf(reference)
        while (at >= 0) {
            val before = text.getOrNull(at - 1)
            val after = text.getOrNull(at + reference.length)
            if ((before == null || !continuesName(before)) && (after == null || !continuesName(after))) return true
            at = text.indexOf(reference, at + 1)
        }
        return false
    }

    private fun continuesName(c: Char) = JavaText.isLetterOrDigit(c) || c == '_' || c == '$' || c == '#'
}

/** One `SELECT`, laid out a clause to a line, and the clauses after its `WHERE`, [tail], each a line: an order, a page. */
internal class Query(
    private val select: String,
    private val from: RowSource,
    private val where: List<String> = emptyList(),
    private val tail: List<String> = emptyList(),
) {
    fun text(): String = buildString {
        append("SELECT ").append(select)
        append("\nFROM ").append(from.reading(listOf(select) + where + tail))
        if (where.isNotEmpty()) append("\nWHERE ").append(where.joinToString("\n  AND "))
        tail.forEach { append("\n").append(it) }
    }

    /** The query as a value or a row source of another: in parentheses, under the line that opens them. */
    fun nested(): String = "(\n    ${text().under()})"
}

/** [this], text of several lines, with its later lines moved in under the line its first is written on. */
internal fun String.under(): String = replace("\n", "\n    ")

/**
 * The values a statement's parameters take, each named with the compiler's reserved prefix and a count: `_cy_ft1`, `_cy_ft2`,
 * … for a full-text search, [prefix] `_cy_vec` for a vector search's `_cy_vec1`, ….
 */
internal class SearchParameters(private val prefix: String = PREFIX) {
    private val bindings = linkedMapOf<String, Any?>()
    private var count = 0

    /** Binds [value] and gives the parameter that stands for it. */
    fun bound(value: Any?): String {
        val name = next()
        bindings[name] = value
        return "\$$name"
    }

    /** A parameter the library binds itself, as a vector search's query vector: named in turn, and not among [bindings]. */
    fun reserved(): String = next()

    private fun next(): String = "$prefix${++count}"

    fun bindings(): Map<String, Any?> = bindings

    companion object {
        /** What every parameter of a full-text search is named with: the compiler's reserved `_cy_`, which no library binds. */
        const val PREFIX = "_cy_ft"
    }
}
