package org.drivine.cypher.store

import org.drivine.DrivineInternalApi
import org.drivine.schema.SimilarityFunction

/**
 * The Oracle SQL the compiler writes that drivine4j's `org.drivine.backend.oracle.OracleSql` writes too, and the
 * expressions the store's DDL (`store.ddl.OracleDdl`) makes its indexes, side tables and property graph over: drivine4j's
 * calls these, so each is written once.
 */
@DrivineInternalApi
object OracleSql {
    fun literal(text: String): String = "'${text.replace("'", "''")}'"

    /** One step of a JSON path: a key, quoted when it must be, so a key with a dot in it is one key. */
    fun step(key: String): String =
        if (PLAIN_KEY.matches(key) && key !in JSON_WORDS) key else "\"${key.replace("\\", "\\\\").replace("\"", "\\\"")}\""

    /** A key a JSON path writes bare: what SQL would take for an identifier. */
    private val PLAIN_KEY = Regex("[A-Za-z_][A-Za-z0-9_]*")

    /** The words a JSON path reads as values, not keys. */
    private val JSON_WORDS = setOf("true", "false", "null")

    /** The path of the property [key], as a SQL literal. */
    fun path(key: String, suffix: String = ""): String {
        require(key.isNotEmpty()) { "A property name is empty: an empty name is no property a statement can read." }
        return literal("$.${step(key)}$suffix")
    }

    /**
     * The path of the property the keys [keys] lead to, each of a map in the one before, as a SQL literal: `'$.p.text'`, or
     * with [suffix] after it, `'$.p.text.type()'`.
     */
    fun path(keys: List<String>, suffix: String = ""): String = path(keys.first(), keys.drop(1).joinToString("") { key ->
        require(key.isNotEmpty()) { "A property name is empty: an empty name is no property a statement can read." }
        ".${step(key)}"
    } + suffix)

    /** The property [key] of the node or relationship [alias], read as text. The stamp ([Stamps.PROPERTY]) is a column of its own. */
    fun property(alias: String, key: String): String =
        if (key == Stamps.PROPERTY) "$alias.stamp" else "JSON_VALUE($alias.props, ${path(key)})"

    /**
     * The property [key] of [alias] read as a number, and null where it is none: what the schema marker is ordered by
     * (`store.marker`).
     */
    fun number(alias: String, key: String): String = "JSON_VALUE($alias.props, ${path(key)} RETURNING NUMBER)"

    /**
     * Whether the node [alias] carries [label], written so the index over labels can answer it: the
     * form for a statement that reads every node of a label. A label is data, so it is written into
     * the path, quoted.
     */
    fun hasLabel(alias: String, label: String): String =
        "JSON_EXISTS($alias.labels, ${literal("$[*]?(@ == ${quoted(label)})")})"

    /**
     * Whether the node [alias] carries [label], written so the index over labels cannot answer it:
     * the form for a node that is found some other way, by its id or at the end of a relationship.
     * Given the choice, Oracle reads every node of the label and tests each for the id, where the
     * index over the id gives the one node. The label is looked for by its text: see [holdsLabel].
     */

    /**
     * Whether the JSON array of labels [labels] holds [label], told by its text. `JSON_SERIALIZE` writes the array
     * compactly, `["A","B"]`; with its brackets made commas and `,"` put after it, `,"A","B","`, each label is
     * `,"label","`, and the label is looked for as that. The trims take one bracket at each end, since a label's quote
     * stands beside each, and no labels at all leave `,,"`, which holds no label. Matched there, the needle is a whole
     * label. Its first quote follows a comma, so isn't escaped: it opens a label or closes one. If it opens one, the
     * label's text runs to the first unescaped quote, which is the one that closes the needle's label. If it closes one, a comma and an opening quote
     * follow, which the needle has only when the label is `,`, and then the quote after that closes a label `,`.
     * The label is written as `JSON_SERIALIZE` writes it ([serialized]), so one holding a control character is
     * found, and one holding `","` is not taken for two.
     */
    fun holdsLabel(labels: String, label: String): String =
        "INSTR(',' || LTRIM(RTRIM(JSON_SERIALIZE($labels), ']'), '[') || ',\"', ${literal(",${serialized(label)},\"")}) > 0"

    /**
     * The property [key] of the node [alias], read as text, where the node carries [label], and null
     * where it does not: the expression of the index a range index makes over the nodes of its label
     * (see [OracleSchemaGrammar]). A comparison with it is met only by a node of the label, so the one
     * index answers both the label and the property.
     */
    fun labelled(alias: String, label: String, key: String): String =
        "CASE WHEN ${hasLabel(alias, label)} THEN JSON_VALUE($alias.props, ${path(key)}) END"

    /**
     * The property [key] of the node [alias], read as text, where the node carries [label] and has
     * each of [keys], and null where it does not: the expression of each column of a uniqueness
     * constraint's index over [keys] (see [OracleSchemaGrammar]). A unique index skips a row only
     * when every column is null, so a node without the label or without one of the properties is
     * not constrained.
     *
     * For a constraint over the one property [key] it is the same value as [labelled] on every
     * node: a node without the property, or with it stored as JSON null, `""`, an array or an
     * object, reads as null either way. So a filter on the property may be read through it.
     */
    fun unique(alias: String, label: String, keys: List<String>, key: String): String {
        val constrained = (listOf(hasLabel(alias, label)) + keys.map { "JSON_EXISTS($alias.props, ${path(it)})" }).joinToString(" AND ")
        return "CASE WHEN $constrained THEN JSON_VALUE($alias.props, ${path(key)}) END"
    }

    /**
     * Whether the node [alias] carries [label], written so the index over labels cannot answer it: the form for a node
     * that is found some other way, by its id or at the end of a relationship, and the test a side table's trigger makes.
     * The label is looked for by its text: see [holdsLabel].
     */
    fun carriesLabel(alias: String, label: String): String = holdsLabel("$alias.labels", label)

    /** The embedding stored under [key] of the node [alias], as a vector. */
    fun embedding(alias: String, key: String): String = "TO_VECTOR(JSON_QUERY($alias.props, ${path(key)} RETURNING CLOB))"

    /**
     * Whether the embedding stored under [key] of [alias] is of [dimensions]: a search measures only those, as Oracle
     * refuses to measure vectors of two sizes against each other, and a vector index holds only those, so a node written
     * with another is left out of it and not refused.
     */
    fun holdsEmbedding(alias: String, key: String, dimensions: Int): String = "VECTOR_DIMENSION_COUNT(${embedding(alias, key)}) = $dimensions"

    /** The distance a vector index is built with for [similarity], and a search by it must name. */
    fun metric(similarity: SimilarityFunction): String = when (similarity) {
        SimilarityFunction.COSINE -> "COSINE"
        SimilarityFunction.EUCLIDEAN -> "EUCLIDEAN_SQUARED"
    }

    /**
     * The score the graph engines give for a [distance] by [similarity], measured by [metric]: in `[0, 1]`, higher the nearer.
     * Oracle's cosine distance is `1 - cos`, so the score is `(1 + cos) / 2`; its squared Euclidean distance is `d²`, so the
     * score is `1 / (1 + d²)`.
     */
    fun score(similarity: SimilarityFunction, distance: String): String = when (similarity) {
        SimilarityFunction.COSINE -> "(2 - $distance) / 2"
        SimilarityFunction.EUCLIDEAN -> "1 / (1 + $distance)"
    }

    /** Whether the node or relationship [alias] has the property [key] at all. The stamp ([Stamps.PROPERTY]) is a column of its own. */
    fun hasProperty(alias: String, key: String): String =
        if (key == Stamps.PROPERTY) "$alias.stamp IS NOT NULL" else "JSON_EXISTS($alias.props, ${path(key)})"

    /**
     * Whether the node [alias] carries every one of [labels]: with [scanned], the first as the index over labels answers it
     * ([hasLabel]), for a statement that reads every node of it, and the rest, or every one without it, by their text
     * ([carriesLabel]).
     */
    fun hasLabels(alias: String, labels: List<String>, scanned: Boolean = false): String {
        require(labels.isNotEmpty()) { "A node is tested for one label or more." }
        return labels.withIndex().joinToString(" AND ") { (i, label) -> if (scanned && i == 0) hasLabel(alias, label) else carriesLabel(alias, label) }
    }

    /** One node in a `GRAPH_TABLE`'s `MATCH`, named [variable], that meets [where]. */
    fun node(variable: String, where: String): String = "($variable IS node WHERE $where)"

    /** The columns a row of a node has in the property graph: those of the table, and the map the graph makes of them ([transform]). */
    val NODE_COLUMNS: List<String> = listOf("nid", "labels", "props", "stamp", "doc")

    /** The texts of the JSON array bound as [parameter], as rows of one column, `v`. */
    fun textValues(parameter: String): String = "SELECT v FROM JSON_TABLE($parameter, '$[*]' COLUMNS (v VARCHAR2(4000) PATH '$'))"

    /** A JSON object of [entries], each a key and the expression for its value, laid out under the key. */
    fun jsonObject(entries: List<Pair<String, String>>): String =
        entries.joinToString(",\n    ", "JSON_OBJECT(\n    ", "\n    RETURNING JSON)") { (key, value) -> "${literal(key)} VALUE ${value.replace("\n", "\n    ")}" }

    /** The JSON array of [value] over the rows a query reads, in [order]. */
    fun arrayOf(value: String, order: String): String = "JSON_ARRAYAGG($value ORDER BY $order RETURNING JSON)"

    /** An empty JSON array. */
    const val EMPTY_ARRAY: String = "JSON('[]')"

    /**
     * The map a load returns for a node, put together from the columns of the row [alias] (`""` for the columns
     * themselves): its properties, with its labels under `labels` and its stamp under the property the graph engines store
     * it as. What the property graph's `doc` property is.
     */
    fun transform(alias: String): String {
        val of = if (alias.isEmpty()) "" else "$alias."
        return "JSON_TRANSFORM(${of}props, SET '$.labels' = ${of}labels, SET ${path(Stamps.PROPERTY)} = ${of}stamp)"
    }

    /** [text] as JSON writes a string. */
    fun quoted(text: String): String = "\"${text.replace("\\", "\\\\").replace("\"", "\\\"")}\""

    /**
     * [text] as Oracle's `JSON_SERIALIZE` writes a string, checked on Oracle Free 23 for every character: `"` and `\`
     * escaped; backspace, tab, newline, form feed and carriage return as `\b`, `\t`, `\n`, `\f` and `\r`; every other
     * character below U+0020, and U+007F, as `\u00XX` in upper case; U+2028 and U+2029 as `\u2028` and `\u2029`; and
     * every other character as it is.
     */
    fun serialized(text: String): String = buildString {
        append('"')
        for (c in text) {
            when (c) {
                '"' -> append("\\\"")
                '\\' -> append("\\\\")
                '\b' -> append("\\b")
                '\t' -> append("\\t")
                '\n' -> append("\\n")
                '\u000C' -> append("\\f")
                '\r' -> append("\\r")
                else -> if (c < ' ' || c == '\u007F' || c == '\u2028' || c == '\u2029') append("\\u").append(c.code.toString(16).uppercase().padStart(4, '0')) else append(c)
            }
        }
        append('"')
    }
}
