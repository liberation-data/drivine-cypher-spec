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
 * statement parameters of names no other fragment of it uses.
 */
@DrivineInternalApi
data class SqlFragment(val terms: List<String>, val parameters: Map<String, Any?>) {
    /** The fragment's one term: a condition's or a value's. */
    val sql: String get() = terms.single()
}

/** A variable a fragment's expression names, bound to a row of the library's own SQL: see [SqlFragment]. */
@DrivineInternalApi
sealed interface FragmentVariable {
    /** The SQL row the variable is: an alias whose columns are those of the graph's table of nodes or of relationships. */
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
