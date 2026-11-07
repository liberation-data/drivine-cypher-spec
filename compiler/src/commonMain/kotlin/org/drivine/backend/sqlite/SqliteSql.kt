package org.drivine.backend.sqlite

/** SPIKE: the pieces of drivine4j's `SqliteSql` the compiler calls, copied as they are. */
internal object SqliteSql {
    fun literal(text: String): String = "'${text.replace("'", "''")}'"
    fun quoted(text: String): String = "\"${text.replace("\\", "\\\\").replace("\"", "\\\"")}\""
    private fun column(alias: String, column: String): String = if (alias.isEmpty()) column else "$alias.$column"
    fun hasLabel(alias: String, label: String): String = holdsLabel(column(alias, "labels"), label)
    fun holdsLabel(labels: String, label: String): String = "instr($labels, ${literal(quoted(label))}) > 0"
    fun propertyText(props: String, path: String): String =
        "(CASE json_type($props, $path) WHEN 'text' THEN $props ->> $path WHEN 'null' THEN NULL ELSE $props -> $path END)"
}
