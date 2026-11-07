package org.drivine.cypher.api

import com.ionspin.kotlin.bignum.decimal.BigDecimal
import com.ionspin.kotlin.bignum.integer.BigInteger
import org.drivine.cypher.json.ArrayNode
import org.drivine.cypher.json.BigIntegerNode
import org.drivine.cypher.json.CypherJson
import org.drivine.cypher.json.JsonNode
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
 * `eraStored` (true where left out) and `indexedProperties` (`[["Person", "name"], …]`). `parameters` may be left out.
 *
 * The answer is [Compiled] (`sql`, `parameters`, `setup`, `warnings`, `nonFinite`, and on Oracle `storedFunctions`:
 * `{"nullsEqual": …, "era": …}`), `{"refusal": "<message>"}` where the compiler refuses the statement, or
 * `{"error": "<message>"}` where the request is no request.
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
 * A bound value is written likewise: a float as Java writes it (`2.0`, `1.0E23`), so a host that reads a number's text can
 * tell it from a whole number, and NaN and the infinities as `{"$float": …}`.
 */
object CompileJson {

    fun compile(request: String): String {
        val call = try {
            read(request)
        } catch (e: Exception) {
            return error(e.message ?: e.toString())
        }
        return when (val result = CypherCompiler.compile(call.store, call.graph, call.cypher, call.parameters)) {
            is Refusal -> StringBuilder().apply { append("{\"refusal\":"); TypedJson.write(result.message, this); append('}') }.toString()
            is Compiled -> StringBuilder().apply {
                append("{\"sql\":"); TypedJson.write(result.sql, this)
                append(",\"parameters\":"); TypedJson.write(result.parameters, this)
                append(",\"setup\":"); TypedJson.write(result.setup, this)
                append(",\"warnings\":"); TypedJson.write(result.warnings, this)
                append(",\"nonFinite\":").append(result.nonFinite)
                result.storedFunctions?.let { append(",\"storedFunctions\":{\"nullsEqual\":").append(it.nullsEqual).append(",\"era\":").append(it.era).append('}') }
                append('}')
            }.toString()
        }
    }

    private class Call(val store: Store, val graph: GraphOptions, val cypher: String, val parameters: Map<String, Any?>)

    private fun read(request: String): Call {
        val tree = CypherJson.readTree(request) as? ObjectNode ?: throw IllegalArgumentException("A request is a JSON object.")
        val storeId = tree["store"]?.textValue() ?: throw IllegalArgumentException("A request names its store: postgres, oracle or sqlite.")
        val store = Store.of(storeId) ?: throw IllegalArgumentException("No store $storeId: postgres, oracle or sqlite.")
        val graph = tree["graph"] as? ObjectNode ?: throw IllegalArgumentException("A request gives its graph: {\"name\": …}.")
        val name = graph["name"]?.textValue() ?: throw IllegalArgumentException("A request's graph has a name.")
        val indexed = (graph["indexedProperties"] as? ArrayNode)?.map { pair ->
            val label = if (pair.isArray && pair.size() == 2) pair[0]?.textValue() else null
            val property = if (pair.isArray && pair.size() == 2) pair[1]?.textValue() else null
            require(label != null && property != null) { "An indexed property is [label, property]." }
            label to property
        }?.toSet() ?: emptySet()
        val options = GraphOptions(
            name = name,
            nullsEqualStored = graph["nullsEqualStored"]?.takeIf { it.isBoolean }?.booleanValue() ?: true,
            eraStored = graph["eraStored"]?.takeIf { it.isBoolean }?.booleanValue() ?: true,
            indexedProperties = indexed,
        )
        val cypher = tree["cypher"]?.textValue() ?: throw IllegalArgumentException("A request gives its statement as cypher.")
        val parameters = when (val given = tree["parameters"]) {
            null -> emptyMap()
            is ObjectNode -> given.properties().associate { (key, value) -> key to TypedJson.value(value) }
            else -> if (given.isNull) emptyMap() else throw IllegalArgumentException("A request's parameters are a JSON object.")
        }
        return Call(store, options, cypher, parameters)
    }

    private fun error(message: String) = StringBuilder().apply { append("{\"error\":"); TypedJson.write(message, this); append('}') }.toString()
}

/** The tagged JSON of values that [CompileJson] reads and writes. */
object TypedJson {

    /** [node] as the value the compiler takes. */
    @OptIn(ExperimentalEncodingApi::class)
    fun value(node: JsonNode): Any? = when {
        node.isNull -> null
        node is ObjectNode && node.size() == 1 && node.has(FLOAT) -> node[FLOAT]!!.let { if (it.isNumber) it.doubleValue() else parseDouble(it.asText()) }
        node is ObjectNode && node.size() == 2 && node.has(TEMPORAL) && node.has("text") ->
            TemporalValue(Temporal.valueOf(node[TEMPORAL]!!.asText()), node["text"]!!.asText())
        node is ObjectNode && node.size() == 1 && node.has(BYTES) -> Base64.decode(node[BYTES]!!.asText())
        node is ObjectNode && node.size() == 1 && node.has(MAP) ->
            (node[MAP] as? ObjectNode ?: throw IllegalArgumentException("{\"$MAP\": …} holds a map.")).properties().associate { (k, v) -> k to value(v) }
        node is ObjectNode -> node.properties().associate { (k, v) -> k to value(v) }
        node is ArrayNode -> node.map(::value)
        node.isTextual -> node.textValue()
        node.isBoolean -> node.booleanValue()
        node is BigIntegerNode -> node.value
        node.isIntegralNumber -> node.longValue()
        node.isNumber -> node.doubleValue()
        else -> null
    }

    /** [value], a value the compiler binds, as tagged JSON. */
    @OptIn(ExperimentalEncodingApi::class)
    fun write(value: Any?, out: StringBuilder) {
        when (value) {
            null -> out.append("null")
            is String -> quote(value, out)
            is Boolean -> out.append(value)
            is Long, is Int, is Short, is Byte -> out.append(value.toString())
            is Double -> if (value.isFinite()) out.append(jvmDoubleText(value)) else { out.append("{\"$FLOAT\":"); quote(jvmDoubleText(value), out); out.append('}') }
            is Float -> write(value.toDouble(), out)
            is BigInteger -> out.append(value.toString())
            is BigDecimal -> out.append(value.toStringExpanded())
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
                    quote(k.toString(), out); out.append(':'); write(v, out)
                }
                out.append('}')
                if (tagged) out.append('}')
            }
            is Iterable<*> -> {
                out.append('[')
                var first = true
                for (item in value) { if (!first) out.append(','); first = false; write(item, out) }
                out.append(']')
            }
            is Array<*> -> write(value.asList(), out)
            else -> out.append(CypherJson.writeValueAsString(value))
        }
    }

    /** [text] as a JSON string, as Jackson quotes one. */
    private fun quote(text: String, out: StringBuilder) {
        out.append(CypherJson.writeValueAsString(text))
    }

    private const val FLOAT = "\$float"
    private const val TEMPORAL = "\$temporal"
    private const val BYTES = "\$bytes"
    private const val MAP = "\$map"
}
