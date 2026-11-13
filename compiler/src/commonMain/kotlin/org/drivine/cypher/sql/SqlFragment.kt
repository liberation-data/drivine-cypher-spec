package org.drivine.cypher.sql

import org.drivine.DrivineInternalApi

/**
 * A part of a statement a library writes itself, as a store compiles it ([SqlCypherCompiler.condition],
 * [SqlCypherCompiler.value], [SqlCypherCompiler.order]): its object manager's filters, orders and seeks, which compare,
 * sort and bind a value as a compiled statement does, so each library writes them alike on each store and over each layout
 * the module holds values in (liberation-data/drivine-cypher-spec#161).
 *
 * [terms] is the SQL: one term for a condition or a value, and an order's terms in their order, each with its direction.
 * [parameters] is what the SQL binds, by name: each Cypher parameter `$p` the expression names, as `$_cy_p`, bound to its
 * JSON text as the store reads it. A library binds them under those names beside its own, so it gives each fragment of one
 * statement parameters of names no other fragment of it uses. [nonFinite] is a statement's ([CompiledCypher.nonFinite]): a
 * value the fragment gives may then be NaN or an infinity, held as the store holds one, which the library reads back so.
 * [hints] are the optimizer hints the library's query that holds the terms carries in its outermost `SELECT`
 * (`SELECT /*+ … */`), as a statement that compares so is planned with them: on Oracle, `NO_JSON_TABLE_TRANSFORM` where a
 * term reads a property it compares by its own path or a list item by its place, without which Oracle 23 gives a comparison
 * over such a read the wrong truth; none on Postgres and SQLite. A value is for the library's SQL to compare, group or bind
 * within it: a column read back as a statement's rows are is a statement's, which Oracle re-encodes as it returns it.
 */
@DrivineInternalApi
data class SqlFragment(
    val terms: List<String>,
    val parameters: Map<String, Any?>,
    val nonFinite: Boolean = false,
    val hints: List<String> = emptyList(),
) {
    /** The fragment's one term: a condition's or a value's. */
    val sql: String get() = terms.single()
}

/** A variable a fragment's expression names, bound to a row of the library's own SQL: see [SqlFragment]. */
@DrivineInternalApi
sealed interface FragmentVariable {
    /**
     * The SQL row the variable is: an alias whose columns are those of the graph's table of nodes or of relationships. Its name
     * ends with `_`, as no name the compiler gives its own rows does, so a subquery the fragment writes doesn't take it for one
     * of them (`n1`, `x`); another is refused.
     */
    val row: String

    /**
     * A node, [row] a row of the graph's table of nodes. [labels] are labels the library knows it has, as its query matches
     * it by them: an equality of one of its properties with a value is then written so the store's index of that label and
     * property answers it, where the store's index is of a label (Oracle's).
     */
    data class Node(override val row: String, val labels: List<String> = emptyList()) : FragmentVariable

    /** A relationship, [row] a row of the graph's table of relationships. */
    data class Relationship(override val row: String) : FragmentVariable
}
