package org.drivine.cypher.api

import com.ionspin.kotlin.bignum.integer.BigInteger
import org.drivine.cypher.json.ArrayNode
import org.drivine.cypher.json.BigIntegerNode
import org.drivine.cypher.json.CypherJson
import org.drivine.cypher.json.DecimalNode
import org.drivine.cypher.json.MAX_DEPTH
import org.drivine.cypher.json.JsonNode
import org.drivine.cypher.json.LongNode
import org.drivine.cypher.json.ObjectNode
import org.drivine.cypher.json.TemporalValue
import org.drivine.cypher.json.jvmDoubleText
import org.drivine.cypher.json.parseDouble
import org.drivine.cypher.sql.Temporal
import kotlin.io.encoding.Base64
import kotlin.io.encoding.ExperimentalEncodingApi

/**
 * The compiler for a host that has no Kotlin types: a request as JSON text, and back the answer as JSON text. The wasmJs
 * build exports this as `compile`, and the native shared library as `drivine_compile`.
 *
 * The request:
 *
 * ```json
 * {"store": "postgres", "graph": {"name": "g"}, "cypher": "MATCH (p:Person {name: $name}) RETURN p", "parameters": {"name": "Ada"}}
 * ```
 *
 * `store` is `postgres`, `oracle` or `sqlite`. `graph` is [GraphOptions]: `name`, and on Oracle `nullsEqualStored`,
 * `eraStored` (true where left out), `indexedProperties` (`[["Person", "name"], …]`), `oracleRowsAsText` and
 * `oracleReencodeJson` (false where left out). `parameters` may be left out. With `"typed": true` (false where left out),
 * the rows' nodes and relationships are tagged with their labels and type: see [org.drivine.cypher.sql.ColumnKind].
 *
 * The answer is [Compiled] (`sql`, `parameters`, `setup`, `warnings`, `nonFinite`, and on Oracle `storedFunctions`:
 * `{"nullsEqual": …, "era": …}`, and with `typed` `columns`: `[{"name": "p", "kind": "node"}, …]`), `{"refusal": "<message>"}` where the compiler refuses the statement, or
 * `{"error": "<message>"}` where the request is no request.
 *
 * A request with `search` or `searchIndex` in place of `cypher` is a full-text search, answered by [FullTextSearch]: see
 * [SearchJson]. A request that gives more than one of the three is an error.
 *
 * Values, in the parameters given and those bound, are plain JSON, tagged only where JSON, or a host's reading of it, loses
 * what the SQL depends on:
 * - a JSON integer is a whole number, a Long (or past a Long, a big integer); a JSON number with a fraction or an exponent is
 *   a float, a Double;
 * - `{"$float": "2.0" | 2 | "NaN" | "Infinity" | "-Infinity"}` is the float, for a host that can't write a whole float as
 *   one (JavaScript), and for NaN and the infinities, which JSON has no number for;
 * - `{"$temporal": "DATE" | "LOCAL_TIME" | "TIME" | "LOCAL_DATE_TIME" | "DATE_TIME" | "DURATION", "text": "<ISO 8601>"}`
 *   is a temporal value of that kind;
 * - `{"$bytes": "<base64>"}` is a byte array;
 * - `{"$map": {...}}` is the map it holds, its keys taken as they are: how a map with a key that starts with `$` is written;
 * - any other map is the map it is.
 *
 * A bound value is written likewise, except that every float is tagged, whole or not: `{"$float": "2.0"}`,
 * `{"$float": "2.5"}`, `{"$float": "NaN"}`, its text as Java writes it. JavaScript's `JSON.parse` reads `2.0` as the
 * integer 2, and Postgres types a parameter by it. A host that writes a request does likewise for a float its JSON writer
 * can't write with a fraction (JavaScript's `JSON.stringify(2.0)` is `2`): an untagged JSON number with a fraction or an
 * exponent is read as a float, and one without as a whole number. A decimal a Kotlin host binds is `{"$decimal": "2.5"}`,
 * so an untagged number in the answer is always whole.
 *
 * A request nests its arrays and objects at most [org.drivine.cypher.json.MAX_DEPTH] deep; a deeper one is an error.
 */
object CompileJson {

    /**
     * The process's compiler settings, `{"settings": {"parserCacheStates": 20000}}`, each one given set, and all of them
     * answered as they are then, with the parser's caches' renewals and the states they hold:
     * `{"settings": {"parserCacheStates": 20000, "parserCacheRenewals": 3, "parserCacheHeld": 4211}}`. `{"settings": {}}`
     * asks them and sets none. See [CypherCompiler.parserCacheStates].
     */
    private fun settings(given: JsonNode?): String {
        if (given !is ObjectNode) return error("A request's settings are a JSON object.")
        val unknown = given.properties().map { it.key }.filter { it != "parserCacheStates" }
        if (unknown.isNotEmpty()) return error("A request's settings give no ${unknown.joinToString()}: parserCacheStates is the one setting.")
        given["parserCacheStates"]?.let { states ->
            val value = (states as? LongNode)?.value?.takeIf { it in 1..Int.MAX_VALUE }?.toInt()
            if (value == null) return error("A request's settings give parserCacheStates as a whole number, 1 or more.")
            CypherCompiler.parserCacheStates = value
        }
        return "{\"settings\":{\"parserCacheStates\":${CypherCompiler.parserCacheStates}," +
            "\"parserCacheRenewals\":${CypherCompiler.parserCacheRenewals},\"parserCacheHeld\":${CypherCompiler.parserCacheHeld}}}"
    }

    fun compile(request: String): String {
        val tree = try {
            CypherJson.readTree(request) as? ObjectNode ?: throw IllegalArgumentException("A request is a JSON object.")
        } catch (e: Exception) {
            return error(e.message ?: e.toString())
        }
        // A full-text search, asked of the same entry: a request with "search" or "searchIndex" in place of "cypher".
        // A request asks one thing: one that gives two would have the other read past as though it were not there.
        if (listOf("cypher", "search", "searchIndex", "settings").count { tree.has(it) } > 1) {
            return error("A request gives one of cypher, search, searchIndex and settings.")
        }
        if (tree.has("search") || tree.has("searchIndex")) return SearchJson.answer(tree)
        if (tree.has("settings")) return settings(tree["settings"])
        val call = try {
            read(tree)
        } catch (e: Exception) {
            return error(e.message ?: e.toString())
        }
        // A value bound is one given, or text, so it nests no deeper than the request: writing it can't be refused.
        return when (val result = CypherCompiler.compile(call.store, call.graph, call.cypher, call.parameters, call.typed)) {
            is Refusal -> refusal(result.message)
            is Compiled -> StringBuilder().apply {
                append("{\"sql\":"); TypedJson.write(result.sql, this)
                append(",\"parameters\":"); TypedJson.write(result.parameters, this, 2)
                append(",\"setup\":"); TypedJson.write(result.setup, this)
                append(",\"warnings\":"); TypedJson.write(result.warnings, this)
                append(",\"nonFinite\":").append(result.nonFinite)
                result.storedFunctions?.let { append(",\"storedFunctions\":{\"nullsEqual\":").append(it.nullsEqual).append(",\"era\":").append(it.era).append('}') }
                // Only where asked, so an answer to a request without it is byte for byte as before.
                if (call.typed) {
                    append(",\"columns\":[")
                    result.columns.forEachIndexed { i, column ->
                        if (i > 0) append(',')
                        append("{\"name\":"); TypedJson.write(column.name, this)
                        append(",\"kind\":"); TypedJson.write(column.kind.id, this)
                        append('}')
                    }
                    append(']')
                }
                append('}')
            }.toString()
        }
    }

    private class Call(val store: Store, val graph: GraphOptions, val cypher: String, val parameters: Map<String, Any?>, val typed: Boolean)

    private fun read(tree: ObjectNode): Call {
        val storeId = tree["store"]?.textValue() ?: throw IllegalArgumentException("A request names its store: postgres, oracle or sqlite.")
        val store = Store.of(storeId) ?: throw IllegalArgumentException("No store $storeId: postgres, oracle or sqlite.")
        val graph = tree["graph"] as? ObjectNode ?: throw IllegalArgumentException("A request gives its graph: {\"name\": …}.")
        val name = graph["name"]?.textValue() ?: throw IllegalArgumentException("A request's graph has a name.")
        val indexedGiven = graph["indexedProperties"]?.takeUnless { it.isNull }
        require(indexedGiven == null || indexedGiven is ArrayNode) { "A request's indexedProperties are [[label, property], …]." }
        val indexed = (indexedGiven as ArrayNode?)?.map { pair ->
            val label = if (pair.isArray && pair.size() == 2) pair[0]?.textValue() else null
            val property = if (pair.isArray && pair.size() == 2) pair[1]?.textValue() else null
            require(label != null && property != null) { "An indexed property is [label, property]." }
            label to property
        }?.toSet() ?: emptySet()
        val options = GraphOptions(
            name = name,
            nullsEqualStored = flag(graph, "nullsEqualStored"),
            eraStored = flag(graph, "eraStored"),
            indexedProperties = indexed,
            oracleRowsAsText = flag(graph, "oracleRowsAsText", default = false),
            oracleReencodeJson = flag(graph, "oracleReencodeJson", default = false),
        )
        val cypher = tree["cypher"]?.textValue() ?: throw IllegalArgumentException("A request gives its statement as cypher.")
        val parameters = when (val given = tree["parameters"]) {
            null -> emptyMap()
            is ObjectNode -> given.properties().associate { (key, value) -> key to TypedJson.value(value, 3) }
            else -> if (given.isNull) emptyMap() else throw IllegalArgumentException("A request's parameters are a JSON object.")
        }
        val typedGiven = tree["typed"]?.takeUnless { it.isNull }
        require(typedGiven == null || typedGiven.isBoolean) { "A request gives typed as true or false." }
        return Call(store, options, cypher, parameters, typedGiven?.booleanValue() ?: false)
    }

    /** The graph's option [name], [default] where it is left out; refused where it is no boolean, as `"false"` would be read as true. */
    private fun flag(graph: ObjectNode, name: String, default: Boolean = true): Boolean {
        val given = graph[name]?.takeUnless { it.isNull } ?: return default
        require(given.isBoolean) { "A request's graph gives $name as true or false." }
        return given.booleanValue()
    }

    internal fun error(message: String) = StringBuilder().apply { append("{\"error\":"); TypedJson.write(message, this); append('}') }.toString()

    internal fun refusal(message: String) = StringBuilder().apply { append("{\"refusal\":"); TypedJson.write(message, this); append('}') }.toString()
}

/** The tagged JSON of values that [CompileJson] reads and writes. */
object TypedJson {

    /**
     * [node] as the value the compiler takes, refused where its arrays and objects nest past [MAX_DEPTH], counting [node]
     * at [depth]: a request's parameter is at 3, inside the request's object and its `parameters`.
     */
    @OptIn(ExperimentalEncodingApi::class)
    fun value(node: JsonNode, depth: Int = 1): Any? = when {
        node.isNull -> null
        node.isContainerNode && depth > MAX_DEPTH -> throw IllegalArgumentException(VALUE_TOO_DEEP)
        node is ObjectNode && node.size() == 1 && node.has(FLOAT) -> node[FLOAT]!!.let {
            if (it.isNumber) it.doubleValue()
            else parseDouble(it.textValue() ?: throw IllegalArgumentException("{\"$FLOAT\": …} holds a number or its text."))
        }
        node is ObjectNode && node.size() == 2 && node.has(TEMPORAL) && node.has("text") -> {
            val kind = node[TEMPORAL]!!.textValue(); val text = node["text"]!!.textValue()
            require(kind != null && text != null) { "{\"$TEMPORAL\": <kind>, \"text\": …} holds text." }
            TemporalValue(temporal(kind), text)
        }
        node is ObjectNode && node.size() == 1 && node.has(BYTES) ->
            Base64.decode(node[BYTES]!!.textValue() ?: throw IllegalArgumentException("{\"$BYTES\": …} holds base64 text."))
        node is ObjectNode && node.size() == 1 && node.has(MAP) ->
            (node[MAP] as? ObjectNode ?: throw IllegalArgumentException("{\"$MAP\": …} holds a map.")).properties().associate { (k, v) -> k to value(v, depth + 2) }
        node is ObjectNode -> node.properties().associate { (k, v) -> k to value(v, depth + 1) }
        node is ArrayNode -> node.map { value(it, depth + 1) }
        node.isTextual -> node.textValue()
        node.isBoolean -> node.booleanValue()
        node is BigIntegerNode -> node.value
        node.isIntegralNumber -> node.longValue()
        node.isNumber -> node.doubleValue()
        else -> null
    }

    /**
     * [value], a value the compiler binds, as tagged JSON, refused where its lists and maps nest past [MAX_DEPTH], counting
     * [value] at [depth].
     */
    @OptIn(ExperimentalEncodingApi::class)
    fun write(value: Any?, out: StringBuilder, depth: Int = 1) {
        require(depth <= MAX_DEPTH || (value !is Map<*, *> && value !is Iterable<*> && value !is Array<*>)) { VALUE_TOO_DEEP }
        when (value) {
            null -> out.append("null")
            is String -> quote(value, out)
            is Boolean -> out.append(value)
            is Long, is Int, is Short, is Byte -> out.append(value.toString())
            // Every float tagged, a whole one too: JavaScript's JSON.parse reads 2.0 as the integer 2.
            is Double -> { out.append("{\"$FLOAT\":"); quote(jvmDoubleText(value), out); out.append('}') }
            is Float -> write(value.toDouble(), out)
            is BigInteger -> out.append(value.toString())
            is ByteArray -> { out.append("{\"$BYTES\":"); quote(Base64.encode(value), out); out.append('}') }
            is TemporalValue -> { out.append("{\"$TEMPORAL\":"); quote(value.kind.name, out); out.append(",\"text\":"); quote(value.text, out); out.append('}') }
            is Map<*, *> -> {
                val tagged = value.keys.any { it.toString().startsWith("$") }
                if (tagged) out.append("{\"$MAP\":")
                out.append('{')
                var first = true
                for ((k, v) in value) {
                    if (!first) out.append(',')
                    first = false
                    quote(k.toString(), out); out.append(':'); write(v, out, if (tagged) depth + 2 else depth + 1)
                }
                out.append('}')
                if (tagged) out.append('}')
            }
            is Iterable<*> -> {
                out.append('[')
                var first = true
                for (item in value) { if (!first) out.append(','); first = false; write(item, out, depth + 1) }
                out.append(']')
            }
            is Array<*> -> write(value.asList(), out, depth)
            // A decimal (bignum's, or on the JVM java.math's) is tagged, as a host's JSON may read it as a double and lose
            // digits, and an untagged number in the answer is whole: `{"$decimal": "2.5"}`, its plain text
            // (java.math's with its scale, `"2.50"`; bignum's keeps none).
            else -> when (val tree = CypherJson.valueToTree(value)) {
                is DecimalNode -> { out.append("{\"$DECIMAL\":"); quote(tree.plain, out); out.append('}') }
                else -> out.append(CypherJson.writeValueAsString(tree))
            }
        }
    }

    /** The kind of temporal value [name] names, refused with the same message on every target (`valueOf`'s isn't). */
    private fun temporal(name: String): Temporal = Temporal.entries.firstOrNull { it.name == name }
        ?: throw IllegalArgumentException("No temporal kind $name: ${Temporal.entries.joinToString { it.name }}.")

    /**
     * [text] as a JSON string, as Jackson quotes one, except that a lone surrogate is escaped (`\uD800`): written as it
     * is, the native library's UTF-8 would carry U+FFFD in its place, and the answer would differ from the wasm build's.
     */
    private fun quote(text: String, out: StringBuilder) {
        val quoted = CypherJson.writeValueAsString(text)
        if (quoted.none { it.isSurrogate() }) {
            out.append(quoted)
            return
        }
        var i = 0
        while (i < quoted.length) {
            val c = quoted[i]
            if (c.isHighSurrogate() && i + 1 < quoted.length && quoted[i + 1].isLowSurrogate()) {
                out.append(c).append(quoted[i + 1])
                i += 2
                continue
            }
            if (c.isSurrogate()) out.append("\\u").append(c.code.toString(16).uppercase()) else out.append(c)
            i++
        }
    }

    private const val FLOAT = "\$float"
    private const val TEMPORAL = "\$temporal"
    private const val BYTES = "\$bytes"
    private const val MAP = "\$map"
    private const val DECIMAL = "\$decimal"
    private const val VALUE_TOO_DEEP = "A value nests its lists and maps at most $MAX_DEPTH deep."
}
