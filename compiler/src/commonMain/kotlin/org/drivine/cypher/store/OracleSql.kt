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
     * The class of the property [key] of the JSON [props], as a range index or a uniqueness constraint holds it beside its
     * [indexKey], written with Oracle's built-ins alone, as an index over a function of the store's would be disabled where
     * the function is made again (ORA-30554): a temporal value's tag's kind (`$t`), else the JSON's kind by its `type()`, a
     * binary double and a number as one, `number`. A `VARCHAR2(16)`, so the two fit an index's key. Null where the key is,
     * so a value the index holds no key of (a list, an empty text, a text past 4,000 bytes) is constrained by none, as before.
     * Two values are equal, as Neo4j has them, where their classes and their keys are.
     */
    fun indexClass(props: String, key: String): String = classAt(props, "$.${step(key)}")

    /** [indexClass] of the JSON value [json] itself, as a statement compares a value with a property its index holds. */
    fun valueClass(json: String): String = classAt(json, "$")

    /** [indexClass] of the value at the JSON path [at], written bare, of [json]. */
    private fun classAt(json: String, at: String): String =
        "CASE WHEN ${keyAt(json, at)} IS NOT NULL THEN CASE ${type(json, at)} WHEN 'object' THEN JSON_VALUE($json, ${literal("$at.\"${ValueLayout.KIND}\"")} RETURNING VARCHAR2(16)) " +
            "WHEN 'double' THEN 'number' ELSE ${type(json, at)} END END"

    /**
     * The key of the property [key] of the JSON [props], beside its [indexClass]: a temporal value's tag's sort key `s`, or its
     * text `v` where it has none, a duration's; a number's exact digits, `TO_CHAR(…, 'TM9')`, so that a `NUMBER` and a binary
     * double of the same whole value are one key, and integers past 2^53 stay apart; any other double as `d` and its 17
     * significant digits, which tell any two doubles apart; any other value as `JSON_VALUE` reads it. A tag's key and text are
     * read only where the value is an object: Oracle's path is lax, and reads `s` of a list's one item, so a list that holds
     * one temporal value would be keyed as that value, and a unique index refuse the two side by side.
     *
     * A binary double is read whole to its digits by halves at 2^32: Oracle makes no `NUMBER` of a double exactly, `CAST` and
     * `TO_NUMBER` rounding to 17 digits, and `JSON_SERIALIZE` and `RETURNING NUMBER` write the double 2^53 as
     * `9007199254740993`; each half, worked out in binary double, is exact, and so is `NUMBER`'s arithmetic of the two. Kept to
     * the range of a long, so no `CAST` overflows: `CASE` takes its branches in order.
     */
    fun indexKey(props: String, key: String): String = keyAt(props, "$.${step(key)}")

    /** [indexKey] of the JSON value [json] itself, as a statement compares a value with a property its index holds. */
    fun valueKey(json: String): String = keyAt(json, "$")

    /** [indexKey] of the value at the JSON path [at], written bare, of [json]. */
    private fun keyAt(json: String, at: String): String {
        val d = "JSON_VALUE($json, ${literal(at)} RETURNING BINARY_DOUBLE)"
        val whole = "TO_CHAR(CAST(FLOOR($d / 4294967296) AS NUMBER) * 4294967296 + CAST($d - FLOOR($d / 4294967296) * 4294967296 AS NUMBER), 'TM9')"
        // A tag's key and text are read of an object alone: Oracle's path is lax, and `$.k.s` of a list of one tag is its item's.
        return "CASE ${type(json, at)} WHEN 'object' THEN COALESCE(JSON_VALUE($json, ${literal("$at.${ValueLayout.KEY}")}), JSON_VALUE($json, ${literal("$at.${ValueLayout.TEXT}")})) " +
            "WHEN 'number' THEN TO_CHAR(JSON_VALUE($json, ${literal(at)} RETURNING NUMBER), 'TM9') " +
            "WHEN 'double' THEN CASE WHEN $d = FLOOR($d) AND $d >= -9223372036854775808 AND $d < 9223372036854775808 THEN $whole ELSE 'd' || TO_CHAR($d) END " +
            "ELSE JSON_VALUE($json, ${literal(at)}) END"
    }

    /** The JSON kind of the value at [at] of [json], by the `type()` item method: `string`, `number`, `double`, `boolean` … */
    private fun type(json: String, at: String) = "JSON_VALUE($json, ${literal("$at.type()")} RETURNING VARCHAR2(16))"

    /**
     * [indexClass] and [indexKey] of the property [key] of the node [alias], where the node carries [label], and null where
     * it does not: the two expressions of the index a range index makes over the nodes of its label (see `OracleDdl`). A
     * comparison with them is met only by a node of the label, so the one index answers both the label and the property.
     */
    fun labelledIndex(alias: String, label: String, key: String): List<String> =
        listOf(indexClass(props(alias), key), indexKey(props(alias), key)).map { "CASE WHEN ${hasLabel(alias, label)} THEN $it END" }

    /**
     * The property [key] of the node [alias] read as text where it carries [label], and null where it does not: what a labelled
     * index held in the store layout 1, which no index of layout 2 holds ([labelledIndex]). drivine4j's own reads of a node by a
     * property call it until they compare the property's class and key, as the compiler does.
     */
    fun labelled(alias: String, label: String, key: String): String =
        "CASE WHEN ${hasLabel(alias, label)} THEN JSON_VALUE($alias.props, ${path(key)}) END"

    /**
     * [indexClass] and [indexKey] of the property [key] of the node [alias], where the node carries [label] and has each of
     * [keys], and null where it does not: the expressions of a uniqueness constraint's index over [keys], two for each. A
     * unique index skips a row only where every column is null, so a node without the label or one of the properties is not
     * constrained. Of a constraint over the one property [key], the same values as [labelled] on every node.
     */
    fun uniqueIndex(alias: String, label: String, keys: List<String>, key: String): List<String> {
        val constrained = (listOf(hasLabel(alias, label)) + keys.map { "JSON_EXISTS(${props(alias)}, ${path(it)})" }).joinToString(" AND ")
        return listOf(indexClass(props(alias), key), indexKey(props(alias), key)).map { "CASE WHEN $constrained THEN $it END" }
    }

    /**
     * The text a full-text search reads of the property [key] of [alias], a CLOB: a temporal value's tag's text `v` (see
     * [ValueLayout]), any other value's as `JSON_VALUE` reads it. Oracle Text's document, a side table's payload, reads it so too.
     */
    fun searchedText(alias: String, key: String): String =
        "COALESCE(JSON_VALUE(${props(alias)}, ${path(key, ".${ValueLayout.TEXT}")} RETURNING CLOB), JSON_VALUE(${props(alias)}, ${path(key)} RETURNING CLOB))"

    /** The `props` column of [alias], or of the table itself for the empty alias. */
    private fun props(alias: String) = if (alias.isEmpty()) "props" else "$alias.props"

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
