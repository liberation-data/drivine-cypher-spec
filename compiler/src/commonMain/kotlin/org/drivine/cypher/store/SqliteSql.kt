package org.drivine.cypher.store

import org.drivine.DrivineInternalApi

/**
 * The SQLite SQL the compiler writes that drivine4j's `org.drivine.backend.sqlite.SqliteSql` writes too: drivine4j's
 * calls these, so each is written once.
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
}
