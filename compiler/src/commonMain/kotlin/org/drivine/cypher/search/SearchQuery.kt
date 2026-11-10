package org.drivine.cypher.search

import org.drivine.cypher.text.JavaText

/**
 * drivine4j's `Query` and the row sources it reads (`org.drivine.backend.sql.Query`, and Oracle's `GraphTable`), as far as a
 * full-text search's statement is written with them: one `SELECT`, a clause to a line, a query nested in another under the
 * line that opens it, so the statement is drivine4j's byte for byte.
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

/** One `SELECT`, laid out a clause to a line. */
internal class Query(
    private val select: String,
    private val from: RowSource,
    private val where: List<String> = emptyList(),
) {
    fun text(): String = buildString {
        append("SELECT ").append(select)
        append("\nFROM ").append(from.reading(listOf(select) + where))
        if (where.isNotEmpty()) append("\nWHERE ").append(where.joinToString("\n  AND "))
    }

    /** The query as a value or a row source of another: in parentheses, under the line that opens them. */
    fun nested(): String = "(\n    ${text().under()})"
}

/** [this], text of several lines, with its later lines moved in under the line its first is written on. */
internal fun String.under(): String = replace("\n", "\n    ")

/** The values a statement's parameters take, each named with the compiler's reserved prefix: `_cy_ft1`, `_cy_ft2`, … */
internal class SearchParameters {
    private val bindings = linkedMapOf<String, Any?>()

    /** Binds [value] and gives the parameter that stands for it. */
    fun bound(value: Any?): String {
        val name = "$PREFIX${bindings.size + 1}"
        bindings[name] = value
        return "\$$name"
    }

    fun bindings(): Map<String, Any?> = bindings

    companion object {
        /** What every parameter of a search is named with: the compiler's reserved `_cy_`, which no library binds. */
        const val PREFIX = "_cy_ft"
    }
}
