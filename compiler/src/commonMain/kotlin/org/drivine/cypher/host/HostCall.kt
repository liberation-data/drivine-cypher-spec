package org.drivine.cypher.host

import org.drivine.DrivineInternalApi
import org.drivine.cypher.json.jvmDoubleText
import org.drivine.cypher.json.parseDoubleOrNull
import org.drivine.cypher.number.SqliteRealText
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
            require(length != null && length >= 0 && length <= args.length - colon - 1) {
                "A host call's argument ${values.size + 1} is no kind, length and colon (${args.substring(at - 1).take(20)})."
            }
            val text = args.substring(colon + 1, colon + 1 + length)
            at = colon + 1 + length
            values.add(
                when (kind) {
                    'i' -> text.toLongOrNull() ?: throw IllegalArgumentException("i holds an integer's digits, not $text.")
                    'r' -> parseDoubleOrNull(text) ?: throw IllegalArgumentException("r holds a number, not $text.")
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

    /**
     * The arguments [values] as a host function reads them: one read as another type converted as SQLite converts it, a
     * text's (or a blob's) leading number read as a number ([sqliteLong], [sqliteDouble]), a float's text as SQLite writes
     * it ([SqliteRealText]).
     */
    private class Args(private val values: List<Any?>) : HostArgs {
        override fun type(i: Int) = when (values[i]) {
            null -> SqlType.NULL
            is Long -> SqlType.INTEGER
            is Double -> SqlType.FLOAT
            is String -> SqlType.TEXT
            else -> SqlType.BLOB
        }
        override fun long(i: Int) = when (val v = values[i]) { is Long -> v; is Double -> v.toLong(); null -> 0L; else -> sqliteLong(text(i)!!) }
        override fun int(i: Int) = long(i).toInt()
        override fun double(i: Int) = when (val v = values[i]) { is Double -> v; is Long -> v.toDouble(); null -> 0.0; else -> sqliteDouble(text(i)!!) }
        override fun text(i: Int) = when (val v = values[i]) { null -> null; is String -> v; is Long -> v.toString(); is Double -> SqliteRealText.of(v); is ByteArray -> v.decodeToString(); else -> null }
        override fun text16(i: Int) = text(i)
        override fun blob(i: Int) = when (val v = values[i]) { null -> null; is ByteArray -> v; else -> text(i)?.encodeToByteArray() }
    }

    /** Whether [c] is a space as SQLite's `sqlite3Isspace` takes one, which it skips before a number. */
    private fun space(c: Char) = c == ' ' || c in '\t'..'\r'

    /**
     * [text] as SQLite's `sqlite3_value_int64` reads a text: after any spaces and a sign, its leading digits (`12abc` is 12,
     * `1.9` and `1e3` are 1, `abc` is 0), a whole number past a long's range the nearest end of it.
     */
    internal fun sqliteLong(text: String): Long {
        var at = 0
        while (at < text.length && space(text[at])) at++
        val negative = at < text.length && text[at] == '-'
        if (at < text.length && (text[at] == '-' || text[at] == '+')) at++
        while (at < text.length && text[at] == '0') at++
        val start = at
        while (at < text.length && text[at] in '0'..'9') at++
        val digits = text.substring(start, at)
        val magnitude = if (digits.length > 19) null else digits.ifEmpty { "0" }.toULong()
        return when {
            magnitude == null || magnitude > Long.MAX_VALUE.toULong() -> if (negative) Long.MIN_VALUE else Long.MAX_VALUE
            negative -> -magnitude.toLong()
            else -> magnitude.toLong()
        }
    }

    /**
     * [text] as SQLite's `sqlite3_value_double` reads a text: after any spaces, its leading decimal number, a sign, digits, a
     * point and digits, and an exponent where digits follow its `e` (`12abc` is 12.0, `1e` is 1.0, `.5` is 0.5); 0.0 where
     * it opens with none (`abc`, `Infinity`), and `0x10` is 0.0, its `0`. SQLite reads one of more than 19 significant
     * digits in its own arithmetic, which can be an ulp from the nearest double this gives.
     */
    internal fun sqliteDouble(text: String): Double {
        var at = 0
        while (at < text.length && space(text[at])) at++
        val start = at
        if (at < text.length && (text[at] == '-' || text[at] == '+')) at++
        val whole = at
        while (at < text.length && text[at] in '0'..'9') at++
        var digits = at > whole
        if (at < text.length && text[at] == '.') {
            val point = ++at
            while (at < text.length && text[at] in '0'..'9') at++
            digits = digits || at > point
        }
        if (!digits) return 0.0
        var end = at
        if (at < text.length && (text[at] == 'e' || text[at] == 'E')) {
            at++
            if (at < text.length && (text[at] == '-' || text[at] == '+')) at++
            val exponent = at
            while (at < text.length && text[at] in '0'..'9') at++
            if (at > exponent) end = at
        }
        // Java reads a number with a point at either end (`1.`, `.5`), and in big exponents an infinity or zero, as SQLite.
        return parseDoubleOrNull(text.substring(start, end)) ?: 0.0
    }
}
