package org.drivine.cypher.store

import org.drivine.DrivineInternalApi
import org.drivine.cypher.json.ArrayNode
import org.drivine.cypher.json.JsonNode
import org.drivine.cypher.json.ObjectNode
import org.drivine.cypher.json.TextNode
import org.drivine.cypher.sql.Temporal
import org.drivine.cypher.sql.Temporals

/**
 * How a value is held in a SQL store's JSON, at rest in `props` and in flight in compiled SQL, in the store layout
 * [LAYOUT] (liberation-data/drivine-cypher-spec#161). A number, a text, a truth value and a list are JSON's own. A temporal
 * value is a tag, `{"$t": kind, "v": text, "s": key}`:
 * - `$t` ([KIND]) is its kind's word, [word]: `date`, `localtime`, `time`, `localdatetime`, `datetime` or `duration`;
 * - `v` ([TEXT]) its canonical ISO 8601 text, [Temporals.canonical];
 * - `s` ([KEY]) its sort key, [Temporals.key], worked out of `v` alone; a duration has none, as it has no order.
 *
 * A map's key that begins with `$` is the user's only with another `$` before it ([escape]), so an object whose `$t` is
 * written with one `$` is always a tag, and is read back without it ([unescape]). A map is never a property's value, so a
 * tag at rest is never mistaken for one.
 */
@DrivineInternalApi
object ValueLayout {

    /** The store layout this module writes and reads on a SQL store: see [StoreLayout]. A graph engine's is 1. */
    const val LAYOUT = 2

    /** The tag's key that names a temporal value's kind. */
    const val KIND = "\$t"

    /** The tag's key that holds a temporal value's canonical text. */
    const val TEXT = "v"

    /** The tag's key that holds a temporal value's sort key. */
    const val KEY = "s"

    /** The word [KIND] names [kind] by, as typed output does: its function's name. */
    fun word(kind: Temporal): String = kind.function

    /** The kind [word] names, or null. */
    fun kindOf(word: String): Temporal? = Temporal.entries.firstOrNull { it.function == word }

    /** The tag of the canonical text [v] of [kind]: see the class. */
    fun tag(kind: Temporal, v: String): ObjectNode = ObjectNode().apply {
        put(KIND, word(kind))
        put(TEXT, v)
        Temporals.key(kind, v)?.let { put(KEY, it) }
    }

    /** [tag] as a Kotlin map, in its keys' order. */
    fun tagMap(kind: Temporal, v: String): Map<String, String> = buildMap {
        put(KIND, word(kind))
        put(TEXT, v)
        Temporals.key(kind, v)?.let { put(KEY, it) }
    }

    /** The user's map key [key] as it is held: with one more `$` before it where it begins with one. */
    fun escape(key: String): String = if (key.startsWith('$')) "$$key" else key

    /** A key as it is held, [escape]d, read back as the user's: one `$` fewer where it begins with two. */
    fun unescape(key: String): String = if (key.startsWith("$$")) key.substring(1) else key

    /** Whether [node] is a tag: an object whose [KIND] names a temporal kind and whose [TEXT] is a text. */
    fun isTag(node: JsonNode): Boolean = node is ObjectNode && node[KIND]?.textValue()?.let(::kindOf) != null && node[TEXT]?.isTextual == true

    /**
     * A JSON value from a compiled statement's rows as a caller reads it: each tag its text `v`, or with [typed] `{"$t", "v"}`
     * without its key, and each map's keys [unescape]d, at any depth. A map that has a `$t` key of its own after its keys are
     * unescaped is, with [typed], `{"$t": "map", "v": map}`, so it is not taken for a tag.
     */
    fun read(node: JsonNode, typed: Boolean = false): JsonNode = when {
        isTag(node) -> if (!typed) TextNode(node[TEXT]!!.textValue()!!)
        else ObjectNode().apply { set<JsonNode>(KIND, node[KIND]!!); set<JsonNode>(TEXT, node[TEXT]!!) }
        node is ObjectNode -> {
            val map = ObjectNode().apply { node.properties().forEach { (key, value) -> set<JsonNode>(unescape(key), read(value, typed)) } }
            if (typed && map.has(KIND)) ObjectNode().apply { put(KIND, "map"); set<JsonNode>(TEXT, map) } else map
        }
        node is ArrayNode -> ArrayNode().apply { node.forEach { add(read(it, typed)) } }
        else -> node
    }

    /** [read] of a value a host has read from the row's JSON into its own maps and lists, with no typed output. */
    fun readValue(value: Any?): Any? = when (value) {
        is Map<*, *> -> {
            val kind = (value[KIND] as? String)?.let(::kindOf)
            val text = value[TEXT] as? String
            if (kind != null && text != null) text
            else value.entries.associate { (key, item) -> (key as? String)?.let(::unescape) to readValue(item) }
        }
        is List<*> -> value.map(::readValue)
        else -> value
    }
}
