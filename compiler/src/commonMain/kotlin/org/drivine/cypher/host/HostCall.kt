package org.drivine.cypher.host

import org.drivine.DrivineInternalApi
import org.drivine.cypher.json.ArrayNode
import org.drivine.cypher.json.CypherJson
import org.drivine.cypher.json.JsonNode
import org.drivine.cypher.json.ObjectNode
import kotlin.io.encoding.Base64
import kotlin.io.encoding.ExperimentalEncodingApi

/**
 * A call of one of the module's SQLite host functions ([HostFunctions.all]) by its name, its arguments and its answer as JSON
 * text, for a host whose SQLite takes functions in another language and has no Kotlin types: Drivine for TypeScript registers
 * each function on its SQLite as a call of this, through the wasm build's `hostCall`, so the module's one copy is the one
 * every library runs (liberation-data/drivine#757), as drivine4j registers the functions themselves and Python loads the
 * module's native extension.
 *
 * Each argument is a value of SQLite's, typed, as SQLite hands it to the function, so an integer, a float and a text of the
 * same digits stay apart, as the functions tell them apart ([SqlType]):
 * - `null`, SQL's null;
 * - `{"i": "<digits>"}`, an integer, its decimal text, so a JavaScript host's number never rounds one past 2^53;
 * - `{"r": <number>}`, a float, or `{"r": "NaN"}`, `{"r": "Infinity"}` or `{"r": "-Infinity"}`;
 * - `{"t": "<text>"}`, a text;
 * - `{"b": "<base64>"}`, a blob.
 *
 * Answered `{"v": <value>}`, the function's value typed as an argument is, or `{"error": "<message>"}` where the function fails
 * its statement, with the message the host fails it with. A call this can't make (no such function, an argument it can't
 * read) is answered `{"error": …}` too, naming why.
 */
@DrivineInternalApi
object HostCall {

    private val byName: Map<String, HostFunction> = HostFunctions.all.associateBy { it.name }

    /** The answer to calling the function [name] with the arguments [args], a JSON array of typed values. */
    fun call(name: String, args: String): String = try {
        val function = byName[name] ?: throw IllegalArgumentException("No host function is $name: ${byName.keys.joinToString(", ")}.")
        val values = (CypherJson.readTree(args) as? ArrayNode ?: throw IllegalArgumentException("A host call gives its arguments as a JSON array."))
            .map(::value)
        require(function.arity < 0 || values.size == function.arity) { "$name takes ${function.arity} argument(s), not ${values.size}." }
        var answer: Any? = null
        var failure: String? = null
        function.body.call(Args(values), values.size, object : HostResult {
            override fun nullValue() { answer = null }
            override fun long(value: Long) { answer = value }
            override fun int(value: Int) { answer = value.toLong() }
            override fun double(value: Double) { answer = value }
            override fun text(value: String) { answer = value }
            override fun text16(value: String) { answer = value }
            override fun blob(value: ByteArray) { answer = value }
            override fun error(message: String) { failure = message }
        })
        failure?.let { "{\"error\":${quoted(it)}}" } ?: "{\"v\":${typed(answer)}}"
    } catch (e: Exception) {
        "{\"error\":${quoted(e.message ?: e.toString())}}"
    }

    /** A typed argument, as [call] takes one. */
    @OptIn(ExperimentalEncodingApi::class)
    private fun value(node: JsonNode): Any? {
        if (node.isNull) return null
        require(node is ObjectNode && node.size() == 1) { "A host call's argument is null or one of {\"i\"}, {\"r\"}, {\"t\"} and {\"b\"}, not ${CypherJson.writeValueAsString(node)}." }
        val (kind, inner) = node.properties().single()
        return when (kind) {
            "i" -> (inner.textValue() ?: throw IllegalArgumentException("{\"i\": …} holds an integer's text.")).toLong()
            "r" -> when {
                inner.isNumber -> inner.doubleValue()
                inner.textValue() == "NaN" -> Double.NaN
                inner.textValue() == "Infinity" -> Double.POSITIVE_INFINITY
                inner.textValue() == "-Infinity" -> Double.NEGATIVE_INFINITY
                else -> throw IllegalArgumentException("{\"r\": …} holds a number, \"NaN\", \"Infinity\" or \"-Infinity\".")
            }
            "t" -> inner.textValue() ?: throw IllegalArgumentException("{\"t\": …} holds a text.")
            "b" -> Base64.decode(inner.textValue() ?: throw IllegalArgumentException("{\"b\": …} holds base64 text."))
            else -> throw IllegalArgumentException("No argument is {\"$kind\"}: one of i, r, t and b.")
        }
    }

    /** [value] typed as [call] answers it. */
    @OptIn(ExperimentalEncodingApi::class)
    private fun typed(value: Any?): String = when (value) {
        null -> "null"
        is Long -> "{\"i\":\"$value\"}"
        is Double -> when {
            value.isNaN() -> "{\"r\":\"NaN\"}"
            value == Double.POSITIVE_INFINITY -> "{\"r\":\"Infinity\"}"
            value == Double.NEGATIVE_INFINITY -> "{\"r\":\"-Infinity\"}"
            else -> "{\"r\":${org.drivine.cypher.json.CypherJson.writeValueAsString(org.drivine.cypher.json.CypherJson.valueToTree(value))}}"
        }
        is String -> "{\"t\":${quoted(value)}}"
        is ByteArray -> "{\"b\":${quoted(Base64.encode(value))}}"
        else -> throw IllegalStateException("A host function gave ${value::class.simpleName}")
    }

    private fun quoted(text: String) = CypherJson.writeValueAsString(CypherJson.valueToTree(text))

    /** The arguments [values] as a host function reads them. */
    private class Args(private val values: List<Any?>) : HostArgs {
        override fun type(i: Int) = when (values[i]) {
            null -> SqlType.NULL
            is Long -> SqlType.INTEGER
            is Double -> SqlType.FLOAT
            is String -> SqlType.TEXT
            else -> SqlType.BLOB
        }
        override fun long(i: Int) = when (val v = values[i]) { is Long -> v; is Double -> v.toLong(); is String -> v.toLongOrNull() ?: 0L; else -> 0L }
        override fun int(i: Int) = long(i).toInt()
        override fun double(i: Int) = when (val v = values[i]) { is Double -> v; is Long -> v.toDouble(); is String -> v.toDoubleOrNull() ?: 0.0; else -> 0.0 }
        override fun text(i: Int) = when (val v = values[i]) { null -> null; is String -> v; is Long -> v.toString(); is Double -> org.drivine.cypher.number.SqliteRealText.of(v); is ByteArray -> v.decodeToString(); else -> null }
        override fun text16(i: Int) = text(i)
        override fun blob(i: Int) = when (val v = values[i]) { null -> null; is ByteArray -> v; else -> text(i)?.encodeToByteArray() }
    }
}
