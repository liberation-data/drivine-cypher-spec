package org.drivine.cypher.host

import org.drivine.cypher.json.CypherJson
import org.drivine.cypher.json.JsonNode
import org.drivine.cypher.json.ObjectNode
import org.drivine.cypher.number.oracleFile
import org.drivine.cypher.number.target
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Each of `host.yaml`'s calls through [HostCall], as Drivine for TypeScript makes them through the wasm build's `hostCall`
 * (liberation-data/drivine#757): its arguments as typed JSON, and the answer the function's own, a value or its failure's
 * message, as the call made directly gives it, on every target.
 */
class HostCallTest {

    private val host: JsonNode = CypherJson.readTree(oracleFile("host.json"))

    private fun quoted(text: String) = CypherJson.writeValueAsString(CypherJson.valueToTree(text))

    /** [values], SQLite's values a step gives, as [HostCall] takes them. */
    private fun typed(values: List<Any?>): String = values.joinToString(",", "[", "]") { value ->
        when (value) {
            null -> "null"
            is Long -> "{\"i\":\"$value\"}"
            is Double -> when {
                value.isNaN() -> "{\"r\":\"NaN\"}"
                value.isInfinite() -> "{\"r\":\"${if (value > 0) "Infinity" else "-Infinity"}\"}"
                else -> "{\"r\":${CypherJson.writeValueAsString(CypherJson.valueToTree(value))}}"
            }
            is String -> "{\"t\":${quoted(value)}}"
            else -> error("no step gives ${value::class.simpleName}")
        }
    }

    /** The value [HostCall] answers, as the direct call gives it. */
    private fun read(answer: JsonNode): Any? {
        val v = answer["v"] ?: error("no value: ${CypherJson.writeValueAsString(answer)}")
        if (v.isNull) return null
        val (kind, inner) = (v as ObjectNode).properties().single()
        return when (kind) {
            "i" -> inner.textValue()!!.toLong()
            "r" -> if (inner.isNumber) inner.doubleValue() else when (inner.textValue()) { "NaN" -> Double.NaN; "Infinity" -> Double.POSITIVE_INFINITY; else -> Double.NEGATIVE_INFINITY }
            "t" -> inner.textValue()
            else -> error("no step answers $kind")
        }
    }

    @Test
    fun eachStepThroughTheJsonCallGivesWhatTheDirectCallGives() {
        val byName = HostFunctions.all.groupBy { it.name }
        var checked = 0
        for (case in host["cases"]!!) for ((i, step) in case["steps"]!!.withIndex()) {
            val name = step["function"]?.textValue() ?: continue
            val args = step["args"] ?: continue
            val functions = byName[name] ?: continue
            val values = args.map(::sqlValue)
            val where = "on $target, $name step $i"
            val direct = runCatching { callHost(functions, values, where) }
            // A step the direct call can't make, as a SQLite read the harness doesn't convert, isn't one to compare.
            if (direct.exceptionOrNull() is Unreadable) continue
            val answer = CypherJson.readTree(HostCall.call(name, typed(values)))
            direct.fold(
                { value ->
                    val got = read(answer)
                    if (value is Double && value.isNaN()) assertTrue(got is Double && got.isNaN(), "$where: $got")
                    else assertEquals(if (value is Int) value.toLong() else value, got, where)
                },
                { failure ->
                    val error = answer["error"]?.textValue()
                    assertTrue(error != null && failure.message!!.endsWith(error), "$where: ${failure.message} where the JSON call gave ${CypherJson.writeValueAsString(answer)}")
                },
            )
            checked++
        }
        assertTrue(checked > 200, "only $checked steps were compared")
    }

    @Test
    fun aCallThatCantBeMadeIsAnErrorNamingWhy() {
        for ((name, args, message) in listOf(
            Triple("drivine_nothing", "[]", "No host function is drivine_nothing"),
            Triple("drivine_trunc", "{}", "A host call gives its arguments as a JSON array"),
            Triple("drivine_trunc", "[{\"x\": 1}]", "No argument is {\"x\"}"),
            Triple("drivine_trunc", "[{\"i\": 1}]", "{\"i\": …} holds an integer's text"),
            Triple("drivine_trunc", "[{\"r\": \"many\"}]", "{\"r\": …} holds a number"),
            Triple("drivine_trunc", "[1]", "A host call's argument is null or one of"),
            Triple("drivine_trunc", "[null, null]", "drivine_trunc takes 1 argument(s), not 2"),
        )) {
            val error = CypherJson.readTree(HostCall.call(name, args))["error"]?.textValue()
            assertTrue(error != null && error.startsWith(message), "on $target, $name $args: $error")
        }
    }

    @Test
    fun aValueIsAnsweredTypedAsTheFunctionGivesIt() {
        // A whole number past 2^53 stays whole and exact, a float keeps its kind, and a text is a text.
        assertEquals("{\"v\":{\"i\":\"9007199254740993\"}}", HostCall.call("drivine_trunc", "[{\"i\":\"9007199254740993\"}]"), "on $target")
        val float = CypherJson.readTree(HostCall.call("drivine_trunc", "[{\"r\":2.5}]"))
        assertTrue(float["v"]!!["r"] != null || float["v"]!!["i"] != null, "on $target: $float")
        assertEquals("{\"v\":null}", HostCall.call("drivine_trunc", "[null]"), "on $target")
    }
}
