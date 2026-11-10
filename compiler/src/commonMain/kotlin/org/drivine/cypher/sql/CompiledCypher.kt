package org.drivine.cypher.sql

/**
 * A Cypher statement as SQL: the statement, and the values its parameters are bound to. [nonFinite] says it is given NaN or an
 * infinity, which a store whose JSON holds no such float as a number writes as a value of its own, and gives back in the rows
 * that way: see [org.drivine.mapper.JdbcResultMapper]. [columns] are the columns its rows give, as a graph engine names them:
 * none for a statement that returns nothing.
 */
data class CompiledCypher(
    val sql: String,
    val parameters: Map<String, Any?>,
    val nonFinite: Boolean = false,
    val columns: List<ResultColumn> = emptyList(),
)

/**
 * A column of a statement's rows: its [name], as a graph engine names it (an item's alias, or its text as written, and
 * `RETURN *`'s variables in the order of their names), and the [kind] of value the compiler knows it holds.
 *
 * On a SQL store the rows hold one value each, a list of the columns' values where there are several, so a host reads the
 * names here, a result with no rows among them.
 */
data class ResultColumn(val name: String, val kind: ColumnKind)

/**
 * The kind of value a [ResultColumn] holds, by [id], as the JSON answer names it. Compiled typed (`typed`), a column of
 * [NODE], [RELATIONSHIP], [NODES] or [RELATIONSHIPS] holds each node and relationship tagged:
 *
 * - a node as `{"$t": "node", "labels": […], "properties": {…}}`;
 * - a relationship as `{"$t": "relationship", "type": "…", "properties": {…}}`;
 * - [NODES] and [RELATIONSHIPS], a list of them, each tagged so, with null where the list is.
 *
 * A host reads tags only in those columns ([tagged]). Every other column holds its value as compiling untyped gives it,
 * and a map there with a `$t` key of its own is a map. A temporal kind ([DATE] to [DURATION]) is one the compiler knows
 * the value has, given as its ISO text, as compiling untyped gives it. [VALUE] is any other, or one the compiler can't
 * tell from the statement, as a property's.
 *
 * Without `typed`, a column of a node or a relationship holds its properties, as `PersistenceManager.query` gives it.
 */
enum class ColumnKind(val id: String) {
    NODE("node"),
    RELATIONSHIP("relationship"),
    NODES("nodes"),
    RELATIONSHIPS("relationships"),
    DATE("date"),
    LOCAL_TIME("localtime"),
    TIME("time"),
    LOCAL_DATE_TIME("localdatetime"),
    DATE_TIME("datetime"),
    DURATION("duration"),
    VALUE("value");

    /** Whether a host reads tagged nodes and relationships in a column of this kind, compiled typed. */
    val tagged: Boolean get() = this == NODE || this == RELATIONSHIP || this == NODES || this == RELATIONSHIPS

    companion object {
        /** The kind of a value the compiler knows is of [temporal]. */
        fun of(temporal: Temporal): ColumnKind = entries.first { it.id == temporal.function }
    }
}
