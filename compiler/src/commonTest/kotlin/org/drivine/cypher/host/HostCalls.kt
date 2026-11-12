package org.drivine.cypher.host

import org.drivine.cypher.json.JsonNode
import org.drivine.cypher.json.ObjectNode
import org.drivine.cypher.json.parseDouble
import org.drivine.cypher.number.DoubleText

/*
 * Host functions called as SQLite calls them, with no SQLite: HostSpecTest's steps and HostTemporalOracleTest's cases.
 */

/** A value of the spec's files, and the oracle's, as a SQL value: a text, a whole number (a Long), a float (a Double), or null. */
internal fun sqlValue(node: JsonNode): Any? = when {
    node.isNull -> null
    node.isTextual -> node.textValue()
    node.isIntegralNumber -> node.longValue()
    node.isNumber -> node.doubleValue()
    node.isObject && node.keys() == setOf("\$float") -> parseDouble(node["\$float"]!!.textValue() ?: error("No float $node"))
    else -> error("No SQL value of $node")
}

private fun unreadable(message: String): Nothing = throw Unreadable(message)

internal fun JsonNode.keys(): Set<String> = (this as? ObjectNode)?.fieldNames()?.asSequence()?.toSet() ?: emptySet()

/** A host function's failure, as SQLite reports one: what it wrote with `error`, or what it threw. */
internal class HostFailure(message: String, cause: Throwable? = null) : IllegalStateException(message, cause)

/** An argument [Given] can't hand over as SQLite would: the step's or the case's, not the function's. */
internal class Unreadable(message: String) : IllegalStateException(message)

/** The function of [functions] that takes [args], called as SQLite calls it: its result, or why it failed (a [HostFailure]). */
internal fun callHost(functions: List<HostFunction>, args: List<Any?>, where: String, floatText: Boolean = false): Any? {
    val function = functions.firstOrNull { it.arity == args.size || it.arity == -1 }
        ?: error("$where: ${functions.first().name} takes ${functions.joinToString(" or ") { "${it.arity} argument${if (it.arity == 1) "" else "s"}" }}, not ${args.size}")
    val result = Captured()
    try {
        function.body.call(Given(args, floatText), args.size, result)
    } catch (e: Unreadable) {
        throw e
    } catch (e: Throwable) {
        // The native extension catches every Throwable, and fails the statement with its message.
        throw HostFailure("$where: ${function.name} failed: ${e.message ?: e::class.simpleName}", e)
    }
    result.failed?.let { throw HostFailure("$where: ${function.name} failed: $it") }
    return result.value
}

/**
 * A call's arguments, as SQLite would hand them over: each read of another type converted, as SQLite converts it. With
 * [floatText], a float's text is Java's (`2.5`, `1.0E20`), the decimal SQLite's `%!.15g` text is of a float of at most 15
 * significant digits, as the oracle's floats are (see the JVM's HostTemporals); without it, no text of a float is read.
 */
internal class Given(private val values: List<Any?>, private val floatText: Boolean = false) : HostArgs {
    override fun type(i: Int) = when (values[i]) {
        null -> SqlType.NULL
        is Long -> SqlType.INTEGER
        is Double -> SqlType.FLOAT
        is String -> SqlType.TEXT
        else -> SqlType.BLOB
    }

    override fun long(i: Int): Long = when (val v = values[i]) {
        null -> 0L
        is Long -> v
        is Double -> v.toLong()
        // SQLite reads a text's leading number, as Kotlin does not ('12abc' is 12): no step reads one so.
        is String -> unreadable("No step reads the whole number of the text $v")
        else -> unreadable("No whole number of $v")
    }

    override fun int(i: Int): Int = long(i).toInt()

    override fun double(i: Int): Double = when (val v = values[i]) {
        null -> 0.0
        is Long -> v.toDouble()
        is Double -> v
        // SQLite reads a text's leading number, and no infinity ('Infinity' is 0.0), as Kotlin does not: no step reads one so.
        is String -> unreadable("No step reads the float of the text $v")
        else -> unreadable("No float of $v")
    }

    override fun text(i: Int): String? = when (val v = values[i]) {
        null -> null
        is String -> v
        is Long -> v.toString()
        // SQLite writes a float's text its own way (1.0/3 as 0.33333333333333332): no step reads one so, and a guess here would pass a wrong one.
        is Double -> if (floatText) DoubleText.of(v) else unreadable("No step reads the text of the float $v")
        else -> unreadable("No text of $v")
    }

    override fun text16(i: Int): String? = text(i)

    override fun blob(i: Int): ByteArray? = text(i)?.encodeToByteArray()
}

/** A call's result: its value, as the spec writes one, or why it failed. */
internal class Captured : HostResult {
    var value: Any? = null
    var failed: String? = null

    override fun nullValue() { value = null }
    override fun long(value: Long) { this.value = value }
    override fun int(value: Int) { this.value = value.toLong() }
    // SQLite's sqlite3_result_double writes NaN as NULL, so the function gives null.
    override fun double(value: Double) { this.value = if (value.isNaN()) null else value }
    override fun text(value: String) { this.value = value }
    override fun text16(value: String) { this.value = value }
    override fun blob(value: ByteArray) { this.value = value }
    override fun error(message: String) { failed = message }
}
