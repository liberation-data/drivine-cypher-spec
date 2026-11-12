package org.drivine.cypher.store

import org.drivine.DrivineInternalApi
import org.drivine.schema.SimilarityFunction

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
     * The text a full-text search reads of [alias]: its properties [keys], each as text, a space between them, in lower case
     * by the library's [SqliteFunctions.LOWER]. A property it does not have is no text.
     */
    fun searchedText(alias: String, keys: List<String>): String = "${SqliteFunctions.LOWER}(${joinedText(alias, keys)})"

    /** [searchedText] before it is lowered: what a full-text search measures a text's length by (drivine4j#604). */
    fun joinedText(alias: String, keys: List<String>): String =
        keys.joinToString(" || ' ' || ") { "COALESCE(${property(alias, it)}, '')" }

    /**
     * The property [key] of [alias] as a number where one is stored, and null for anything else: what the schema marker is
     * ordered by (`store.marker`), so a marker whose moment is not a number is read as never applied.
     */
    fun number(alias: String, key: String): String =
        "(CASE WHEN json_type(${column(alias, "props")}, ${path(key)}) IN ('integer', 'real') THEN ${column(alias, "props")} ->> ${path(key)} END)"

    /** The labels of a node that carries [label] alone, as a literal of the JSON array they are stored as. */
    fun labelArray(label: String): String = literal("[${quoted(label)}]")

    /** Whether the node [alias] carries every one of [labels], each a test of its own. */
    fun hasLabels(alias: String, labels: List<String>): String {
        require(labels.isNotEmpty()) { "A node is tested for one label or more." }
        return labels.joinToString(" AND ") { hasLabel(alias, it) }
    }

    /** The JSON of the property [key] of the node or relationship [alias], as text: `'"Ada"'` for a string. */
    fun value(alias: String, key: String): String = "(${column(alias, "props")} -> ${path(key)})"

    /** The JSON type of the property [key] of [alias], as SQLite names it (`'text'`, `'array'`…); null where there is none. */
    fun type(alias: String, key: String): String = "json_type(${column(alias, "props")}, ${path(key)})"

    /** Whether [alias] holds an embedding of [dimensions] under [key]: a search measures only those. */
    fun holdsEmbedding(alias: String, key: String, dimensions: Int): String =
        "${type(alias, key)} = 'array' AND json_array_length(${column(alias, "props")}, ${path(key)}) = $dimensions"

    /**
     * The score the graph engines give the embedding under [key] of [alias] against the vector [vector], by [similarity]: in
     * `[0, 1]`, higher the nearer, as `PostgresSql.score` gives it, measured by the library's [SqliteFunctions.SIMILARITY].
     */
    fun score(alias: String, key: String, similarity: SimilarityFunction, vector: String): String =
        "${SqliteFunctions.SIMILARITY}(${value(alias, key)}, $vector, ${literal(similarity.name)})"

    /** The texts of the JSON array bound as [parameter], as rows of one column, each as a property is read as text ([propertyText]). */
    fun textValues(parameter: String): String =
        "SELECT (CASE v.type WHEN 'text' THEN v.value WHEN 'null' THEN NULL WHEN 'true' THEN 'true' WHEN 'false' THEN 'false' " +
            "WHEN 'integer' THEN json_quote(v.value) WHEN 'real' THEN json_quote(v.value) ELSE v.value END) FROM json_each($parameter) v"

    /** A JSON object of [entries] whose values are put in as the SQL values they are, a text as a string, each laid out under its key. */
    fun plainObject(entries: List<Pair<String, String>>): String =
        entries.joinToString(",\n    ", "json_object(\n    ", ")") { (key, value) -> "${literal(key)}, ${value.replace("\n", "\n    ")}" }

    /** The JSON array of [value] over the rows a query reads, in [order]. */
    fun arrayOf(value: String, order: String): String = "json_group_array($value ORDER BY $order)"
}
