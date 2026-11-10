package org.drivine.cypher.store

import org.drivine.DrivineInternalApi
import org.drivine.cypher.json.CypherEncoder
import org.drivine.cypher.json.CypherJson
import org.drivine.schema.SimilarityFunction

/**
 * The Postgres SQL an index is made over and a statement reads the index by: the expressions the store's DDL
 * (`store.ddl.PostgresDdl`) and the compiler both write, each written once here, so that Postgres sees an index holds what
 * a statement asks for. drivine4j's `org.drivine.backend.postgres.PostgresSql` calls these too.
 *
 * Each takes an alias, a table's alias in a statement, or the empty alias for the table's own columns, as an index names
 * them. Postgres matches an index's expression with a statement's whatever the alias.
 */
@DrivineInternalApi
object PostgresSql {

    /** [text] as a SQL string literal, its quotes doubled. Postgres reads a backslash in one as itself. */
    fun literal(text: String): String = "'${text.replace("'", "''")}'"

    /** [alias]'s column [column]; the column of the table itself, as an index names it, for an empty alias. */
    fun column(alias: String, column: String): String = if (alias.isEmpty()) column else "$alias.$column"

    /** The key [key] of a JSON object, as a literal; refused when it is empty, which names no property. */
    private fun keyOf(key: String): String {
        require(key.isNotEmpty()) { "A property name is empty: an empty name is no property a statement can read." }
        return literal(key)
    }

    /**
     * Whether the node [alias] carries [label]: the form the index over labels answers, and the predicate of a partial
     * index over the label's nodes. A label is data, so it is written into the literal.
     */
    fun hasLabel(alias: String, label: String): String = holdsLabel(column(alias, "labels"), label)

    /**
     * Whether the JSON array of labels [labels] holds [label]: the test [hasLabel] makes of a node's column, which compiled
     * Cypher makes of a node's labels too, so a partial index over the label's nodes answers either. [encoder] is the
     * compiler's, which writes the array where a host gives one.
     */
    fun holdsLabel(labels: String, label: String, encoder: CypherEncoder? = null): String = "$labels @> ${labelArray(label, encoder)}"

    /**
     * The labels of a node that carries [label] alone, as a literal of the JSON array they are stored as. Written as the
     * labels are written, as Jackson writes JSON, so a label with a control character in it is valid JSON here too.
     */
    fun labelArray(label: String, encoder: CypherEncoder? = null): String = literal(CypherJson.writeValueAsString(listOf(label), encoder = encoder))

    /** The JSON value of the property [key] of the node or relationship [alias]. */
    fun value(alias: String, key: String): String = "(${column(alias, "props")} -> ${keyOf(key)})"

    /**
     * The text of the JSON object [props]'s key [key], a literal, as `->>` reads it: the expression a range index or a
     * uniqueness constraint is made over, and a statement that compares a property with a value's text reads.
     */
    fun propertyText(props: String, key: String): String = "($props ->> $key)"

    /**
     * The property [key] of the node or relationship [alias], read as text. The stamp ([Stamps.PROPERTY]) is a column of
     * its own and not a property. For an empty alias, the table's own column, as an index over the property names it.
     */
    fun property(alias: String, key: String): String {
        if (key == Stamps.PROPERTY) return column(alias, "stamp")
        return propertyText(column(alias, "props"), keyOf(key))
    }

    /** [property], a temporal value's tag read as its text `v` (see [ValueLayout]): what a full-text search reads. */
    fun searchedProperty(alias: String, key: String): String {
        if (key == Stamps.PROPERTY) return property(alias, key)
        return "COALESCE(${value(alias, key)} ->> ${literal(ValueLayout.TEXT)}, ${property(alias, key)})"
    }

    /**
     * The property [key] of the node or relationship [alias] as a range index or a uniqueness constraint holds it: its JSON,
     * [value], whose equality is `jsonb`'s, Neo4j's on every value a property holds: a text is equal only to a text, a number
     * to a number of the same value (`1` to `1.0`, exactly past 2^53), and a temporal value's tag only to the same value's.
     * A statement that compares the property with a value as JSON, `(props -> 'k') = $p`, is answered by it. The stamp is
     * its column.
     */
    fun indexed(alias: String, key: String): String = if (key == Stamps.PROPERTY) column(alias, "stamp") else value(alias, key)

    /**
     * The property [key] of [alias] as a number where one is stored, and null for anything else: what the schema marker is
     * ordered by (`store.marker`), so a marker whose moment is not a number is read as never applied, not as text.
     */
    fun number(alias: String, key: String): String =
        "CASE WHEN jsonb_typeof(${value(alias, key)}) = 'number' THEN (${column(alias, "props")} ->> ${keyOf(key)})::numeric END"

    /**
     * The embedding stored under [key] of [alias] as a pgvector `vector`: the JSON array's text, which is the text pgvector
     * reads a vector from. Of [dimensions] when given, as an index over it is made, so a statement that reads it so is
     * answered by the index.
     */
    fun embedding(alias: String, key: String, dimensions: Int? = null): String =
        "CAST(CAST(${value(alias, key)} AS text) AS vector${dimensions?.let { "($it)" }.orEmpty()})"

    /**
     * Whether [alias] holds an embedding of [dimensions] under [key]: a search measures only those, as pgvector refuses to
     * measure vectors of two sizes, and a vector index holds only those, so a node written with another is left out of it
     * and not refused.
     */
    fun holdsEmbedding(alias: String, key: String, dimensions: Int): String =
        "jsonb_typeof(${value(alias, key)}) = 'array' AND jsonb_array_length(${value(alias, key)}) = $dimensions"

    /** The operator class a vector index is made with to answer a search by [similarity]. */
    fun operators(similarity: SimilarityFunction): String = when (similarity) {
        SimilarityFunction.COSINE -> "vector_cosine_ops"
        SimilarityFunction.EUCLIDEAN -> "vector_l2_ops"
    }

    /**
     * The text a full-text search reads of [alias]: its properties [keys], each as text, a space between them, in lower
     * case. A property it does not have is no text. The expression of a full-text index too (with an empty alias), so a
     * statement that reads it so is answered by the index.
     */
    fun searchedText(alias: String, keys: List<String>): String = "lower(${joinedText(alias, keys)})"

    /** [searchedText] before it is lowered: what a full-text search measures a text's length by (drivine4j#604). */
    fun joinedText(alias: String, keys: List<String>): String =
        keys.joinToString(" || ' ' || ") { "COALESCE(${searchedProperty(alias, it)}, '')" }
}
