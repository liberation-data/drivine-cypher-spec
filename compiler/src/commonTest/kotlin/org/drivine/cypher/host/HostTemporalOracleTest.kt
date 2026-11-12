package org.drivine.cypher.host

import org.drivine.cypher.json.CypherJson
import org.drivine.cypher.json.JsonNode
import org.drivine.cypher.number.oracleFile
import org.drivine.cypher.number.target
import kotlin.test.Test
import kotlin.test.assertTrue

/**
 * The module's SQLite temporal host functions, `drivine_temporal`, `drivine_trunc` and `drivine_to_char`, against Java's
 * answers (`host-temporal.json`, written by the JVM: see `org.drivine.cypher.oracle.HostTemporals`), on every target,
 * called as SQLite calls them ([callHost]): each case gives Java's result exactly, of its type (a whole number, a float,
 * a text or null), or fails with a message that holds its `errorKey`. A function the module doesn't implement yet fails
 * this test, naming how many of its cases didn't run (#108).
 */
class HostTemporalOracleTest {

    private val oracle: JsonNode = CypherJson.readTree(oracleFile("host-temporal.json"))

    private fun check(function: String) {
        val cases = oracle["cases"]!!.filter { it["function"]!!.textValue() == function }
        assertTrue(cases.isNotEmpty(), "host-temporal.json has no case of $function")
        val functions = HostFunctions.all.filter { it.name == function }
        assertTrue(functions.isNotEmpty(), "$function is not ported yet (#108): none of its ${cases.size} cases ran on $target")
        val differences = mutableListOf<String>()
        val groups = mutableMapOf<String, Int>()
        for (case in cases) {
            val args = case["args"]!!.map(::sqlValue)
            val where = "${case["group"]!!.textValue()}: $function${args.map(::show)}"
            val error = case["error"]?.textValue()
            val got = runCatching { callHost(functions, args, where, floatText = true) }
            val wrong = if (error == null) {
                val expected = sqlValue(case["result"]!!)
                when {
                    got.isFailure -> "Java ${show(expected)}, $target failed: ${got.exceptionOrNull()!!.message}"
                    !same(expected, got.getOrNull()) -> "Java ${show(expected)}, $target ${show(got.getOrNull())}"
                    else -> null
                }
            } else {
                val key = case["errorKey"]!!.textValue()!!
                val message = got.exceptionOrNull()?.message
                when {
                    message == null -> "Java fails ($error), $target gives ${show(got.getOrNull())}"
                    !message.contains(key) -> "Java fails with a message holding ${show(key)} ($error), $target with $message"
                    else -> null
                }
            }
            if (wrong != null) {
                differences += "$where: $wrong"
                groups[case["group"]!!.textValue()!!] = (groups[case["group"]!!.textValue()!!] ?: 0) + 1
            }
        }
        println("HostTemporalOracleTest on $target: $function, ${cases.size} cases, ${differences.size} unlike Java's")
        assertTrue(differences.isEmpty(), "$function: ${differences.size} of ${cases.size} cases unlike Java's ($groups), such as:\n" + differences.take(40).joinToString("\n"))
    }

    /** Equal, and of the same type: a whole number is not a float, and a float is compared by its bits (-0.0 is not 0.0). */
    private fun same(expected: Any?, got: Any?): Boolean = when (expected) {
        is Double -> got is Double && got.toRawBits() == expected.toRawBits()
        else -> expected == got
    }

    private fun show(value: Any?): String = when (value) {
        null -> "null"
        is String -> "'$value'"
        is Double -> "${value}f"
        else -> value.toString()
    }

    @Test
    fun `drivine_temporal gives what java_time gives`() = check("drivine_temporal")

    @Test
    fun `drivine_trunc gives what drivine4j gives`() = check("drivine_trunc")

    @Test
    fun `drivine_to_char gives what java_time and java_math give`() = check("drivine_to_char")
}
