package org.drivine.cypher.host

import org.drivine.DrivineInternalApi
import org.drivine.cypher.json.jvmDoubleText
import kotlin.io.encoding.Base64
import kotlin.io.encoding.ExperimentalEncodingApi

/**
 * A call of one of the module's SQLite host functions ([HostFunctions.all]) by its name, with its arguments and its answer as
 * text, for a host whose SQLite takes functions in another language and has no Kotlin types: Drivine for TypeScript registers
 * each function on its SQLite as a call of this, through the wasm build's `hostCall`, so the module's one copy is the one
 * every library runs (liberation-data/drivine#757), as drivine4j registers the functions themselves and Python loads the
 * module's native extension.
 *
 * The arguments are SQLite's values, each typed as SQLite hands it to the function, so an integer, a float and a text of the
 * same digits stay apart, as the functions tell them apart ([SqlType]), one after another with nothing between:
 * - `n`, SQL's null;
 * - `i<length>:<digits>`, an integer, as its decimal text, so a JavaScript host's number never rounds one past 2^53;
 * - `r<length>:<number>`, a float, as JavaScript writes one (`2.5`, `1e+21`, `-0`, `NaN`, `Infinity`, `-Infinity`);
 * - `t<length>:<text>`, a text, as it stands;
 * - `b<length>:<base64>`, a blob.
 *
 * `<length>` counts the UTF-16 units of what follows the colon, as JavaScript's `length` does, so no text is escaped: a
 * function's argument is most often JSON text, and escaping it inside JSON, to be read twice, took much of a call's time.
 *
 * Answered by a value written the same way without its length (`n`, `i<digits>`, `r<number>`, `t<text>`, `b<base64>`), a
 * float as Java writes it (`2.5`, `1.0E21`, `NaN`), which JavaScript's `Number` reads back as the same; or `e<message>` where
 * the function fails its statement, with the message the host fails it with. A call this can't make (no such function, the
 * wrong number of arguments, arguments it can't read) is answered `e…` too, naming why.
 */
@DrivineInternalApi
object HostCall {

    private val byName: Map<String, HostFunction> = HostFunctions.all.associateBy { it.name }

    /** The answer to calling the function [name] with the arguments [args], written as the type's doc says. */
    fun call(name: String, args: String): String = try {
        val function = byName[name] ?: throw IllegalArgumentException("No host function is $name: ${byName.keys.joinToString(", ")}.")
        val values = values(args)
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
        failure?.let { "e$it" } ?: written(answer)
    } catch (e: Exception) {
        "e${e.message ?: e.toString()}"
    }

    /** The arguments [args] holds, each read as its kind. */
    @OptIn(ExperimentalEncodingApi::class)
    private fun values(args: String): List<Any?> {
        val values = ArrayList<Any?>(4)
        var at = 0
        while (at < args.length) {
            val kind = args[at++]
            if (kind == 'n') { values.add(null); continue }
            val colon = args.indexOf(':', at)
            val length = if (colon < 0) null else args.substring(at, colon).toIntOrNull()
            require(length != null && length >= 0 && colon + 1 + length <= args.length) {
                "A host call's argument ${values.size + 1} is no kind, length and colon (${args.substring(at - 1).take(20)})."
            }
            val text = args.substring(colon + 1, colon + 1 + length)
            at = colon + 1 + length
            values.add(
                when (kind) {
                    'i' -> text.toLongOrNull() ?: throw IllegalArgumentException("i holds an integer's digits, not $text.")
                    'r' -> when (text) {
                        "NaN" -> Double.NaN
                        "Infinity" -> Double.POSITIVE_INFINITY
                        "-Infinity" -> Double.NEGATIVE_INFINITY
                        else -> text.toDoubleOrNull() ?: throw IllegalArgumentException("r holds a number, not $text.")
                    }
                    't' -> text
                    'b' -> Base64.decode(text)
                    else -> throw IllegalArgumentException("No argument is of the kind $kind: one of n, i, r, t and b.")
                },
            )
        }
        return values
    }

    /** [value] as [call] answers it. */
    @OptIn(ExperimentalEncodingApi::class)
    private fun written(value: Any?): String = when (value) {
        null -> "n"
        is Long -> "i$value"
        is Double -> "r${jvmDoubleText(value)}"
        is String -> "t$value"
        is ByteArray -> "b${Base64.encode(value)}"
        else -> throw IllegalStateException("A host function gave ${value::class.simpleName}")
    }

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
