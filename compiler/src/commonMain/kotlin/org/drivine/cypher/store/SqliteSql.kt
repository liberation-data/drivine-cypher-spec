package org.drivine.cypher.store

import org.drivine.DrivineInternalApi

/**
 * The SQLite SQL the compiler writes that drivine4j's `org.drivine.backend.sqlite.SqliteSql` writes too, and the
 * expressions the store's DDL (`store.ddl.SqliteDdl`) makes its indexes over: drivine4j's calls these, so each is written
 * once.
 */
@DrivineInternalApi
object SqliteSql {

    /** [text] as a SQL string literal, its quotes doubled. SQLite reads a backslash in one as itself. */
    fun literal(text: String): String = "'${text.replace("'", "''")}'"

    /** [text] as JSON writes a string. */
    fun quoted(text: String): String = "\"${text.replace("\\", "\\\\").replace("\"", "\\\"")}\""

    /** [alias]'s column [column]; the column of the table itself, as an index names it, for an empty alias. */
    fun column(alias: String, column: String): String = if (alias.isEmpty()) column else "$alias.$column"

    /**
     * Whether the node [alias] carries [label]: the test a partial index over the label's nodes is made with, so a
     * statement that makes it is answered by the index.
     */
    fun hasLabel(alias: String, label: String): String = holdsLabel(column(alias, "labels"), label)

    /** Whether the JSON array of labels [labels] holds [label]: the test [hasLabel] makes of a node's column, which compiled Cypher makes too. */
    fun holdsLabel(labels: String, label: String): String = "instr($labels, ${literal(quoted(label))}) > 0"

    /**
     * The value at [path], a literal, of the JSON [props] as text: a string as itself, anything else as its JSON, and
     * null as SQL's null. A range index or a uniqueness constraint is made over it.
     */
    fun propertyText(props: String, path: String): String =
        "(CASE json_type($props, $path) WHEN 'text' THEN $props ->> $path WHEN 'null' THEN NULL ELSE $props -> $path END)"

    /**
     * The path of the property [key] of a JSON object, as a literal: the key quoted, so a key with a dot in it is one
     * property and not a path into another. Refused when it is empty, which names no property.
     */
    fun path(key: String): String {
        require(key.isNotEmpty()) { "A property name is empty: an empty name is no property a statement can read." }
        return literal("$.${quoted(key)}")
    }

    /**
     * The property [key] of the node or relationship [alias], read as text ([propertyText]). The stamp ([Stamps.PROPERTY])
     * is a column of its own. For an empty alias, the table's own column, as an index over the property names it.
     */
    fun property(alias: String, key: String): String =
        if (key == Stamps.PROPERTY) column(alias, "stamp") else propertyText(column(alias, "props"), path(key))

    /**
     * The property [key] of [alias] as a number where one is stored, and null for anything else: what the schema marker is
     * ordered by (`store.marker`), so a marker whose moment is not a number is read as never applied.
     */
    fun number(alias: String, key: String): String =
        "(CASE WHEN json_type(${column(alias, "props")}, ${path(key)}) IN ('integer', 'real') THEN ${column(alias, "props")} ->> ${path(key)} END)"

    /** The labels of a node that carries [label] alone, as a literal of the JSON array they are stored as. */
    fun labelArray(label: String): String = literal("[${quoted(label)}]")
}
