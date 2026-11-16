package org.drivine.cypher.host

import org.drivine.cypher.json.CypherJson
import org.drivine.cypher.json.JsonNode
import org.drivine.cypher.json.jvmDoubleText
import org.drivine.cypher.json.parseDouble
import org.drivine.cypher.number.DoubleText
import org.drivine.cypher.number.SqliteRealText
import org.drivine.cypher.number.oracleFile
import org.drivine.cypher.number.target
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Each of `host.yaml`'s calls through [HostCall], as Drivine for TypeScript makes them through the wasm build's `hostCall`
 * (liberation-data/drivine#757): its arguments written as [HostCall] reads them, and the answer the function's own, a value
 * or its failure's message, as the call made directly gives it, on every target.
 */
class HostCallTest {

    private val host: JsonNode = CypherJson.readTree(oracleFile("host.json"))

    /** [text] after its kind and length, as [HostCall] reads an argument. */
    private fun item(kind: Char, text: String) = "$kind${text.length}:$text"

    /** [values], SQLite's values a step gives, as [HostCall] takes them, a float written as JavaScript writes it. */
    private fun typed(values: List<Any?>): String = values.joinToString("") { value ->
        when (value) {
            null -> "n"
            is Long -> item('i', value.toString())
            is Double -> item('r', if (value.isNaN() || value.isInfinite()) value.toString() else jsText(value))
            is String -> item('t', value)
            else -> error("no step gives ${value::class.simpleName}")
        }
    }

    /** [value] about as JavaScript's `String(value)` writes it (`2.5`, `1e+21`, `0` for -0): the same value, in its forms. */
    private fun jsText(value: Double): String = when {
        value == 0.0 -> "0"
        value == kotlin.math.floor(value) && kotlin.math.abs(value) < 9e18 -> value.toLong().toString()
        else -> jvmDoubleText(value).lowercase().replace("e", "e+").replace("e+-", "e-").removeSuffix(".0")
    }

    /** The value [HostCall] answers, as the direct call gives it. */
    private fun read(answer: String): Any? = when (answer[0]) {
        'n' -> null
        'i' -> answer.substring(1).toLong()
        'r' -> answer.substring(1).toDouble()
        't' -> answer.substring(1)
        else -> error("no value: $answer")
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
            val answer = HostCall.call(name, typed(values))
            direct.fold(
                { value ->
                    val got = read(answer)
                    if (value is Double && value.isNaN()) assertTrue(got is Double && got.isNaN(), "$where: $got")
                    else assertEquals(if (value is Int) value.toLong() else value, got, where)
                },
                { failure ->
                    assertTrue(answer.startsWith('e') && failure.message!!.endsWith(answer.substring(1)), "$where: ${failure.message} where hostCall gave $answer")
                },
            )
            checked++
        }
        assertTrue(checked > 200, "only $checked steps were compared")
    }

    @Test
    fun aCallThatCantBeMadeIsAnErrorNamingWhy() {
        for ((name, args, message) in listOf(
            Triple("drivine_nothing", "", "No host function is drivine_nothing"),
            Triple("drivine_trunc", "x1:1", "No argument is of the kind x"),
            Triple("drivine_trunc", "i3:1.5", "i holds an integer's digits, not 1.5"),
            Triple("drivine_trunc", "r4:many", "r holds a number, not many"),
            Triple("drivine_trunc", "b3:a!b", "b holds base64, not a!b"),
            Triple("drivine_trunc", "t", "A host call's argument 1 is no kind, length and colon"),
            Triple("drivine_trunc", "t9:abc", "A host call's argument 1 is no kind, length and colon"),
            Triple("drivine_trunc", "t-1:", "A host call's argument 1 is no kind, length and colon"),
            Triple("drivine_trunc", "t2147483647:a", "A host call's argument 1 is no kind, length and colon"),
            Triple("drivine_trunc", "nn", "drivine_trunc takes 1 argument(s), not 2"),
        )) {
            val answer = HostCall.call(name, args)
            assertTrue(answer.startsWith("e$message"), "on $target, $name $args: $answer")
        }
    }

    @Test
    fun aValueIsAnsweredTypedAsTheFunctionGivesIt() {
        // A whole number past 2^53 stays whole and exact, a float keeps its kind, and a text is a text.
        assertEquals("i9007199254740993", HostCall.call("drivine_trunc", "i16:9007199254740993"), "on $target")
        assertEquals("i2", HostCall.call("drivine_trunc", "r3:2.5"), "on $target")
        assertEquals("n", HostCall.call("drivine_trunc", "n"), "on $target")
        // A blob is its base64, read as a number as SQLite reads its bytes as text.
        assertEquals("t2.5", HostCall.call("drivine_float_text", "b4:Mi41"), "on $target")
        // A text holding the colons, digits and kind letters the arguments are written with is read by its length alone.
        assertEquals("t\"t3:a:b\"", HostCall.call("drivine_canonical", "t8:\"t3:a:b\""), "on $target")
        // A float is read in each of the forms JavaScript's String(number) writes.
        for ((js, value) in listOf("1e+21" to 1e21, "-0" to -0.0, "1.5e-7" to 1.5e-7, "-Infinity" to Double.NEGATIVE_INFINITY, "123.456" to 123.456)) {
            assertEquals("r${jvmDoubleText(value)}", HostCall.call("drivine_float_text", item('r', js)).let { "r" + it.substring(1).toDouble().let(::jvmDoubleText) }, "on $target, $js")
        }
        // A float a function answers reads back as the same, Java's text read as JavaScript's Number reads it.
        assertEquals("r0.1", HostCall.call("drivine_float_text", "r3:0.1").let { "r" + it.substring(1).toDouble() }, "on $target")
        assertEquals("NaN", HostCall.call("drivine_float_text", "r3:NaN").substring(1), "on $target")
    }

    @Test
    fun aTextReadAsANumberIsReadAsSqliteReadsItOnEveryTarget() {
        // HostCallSqliteTest pins these texts against the JVM's SQLite; here the same reads hold on every target.
        for ((text, value) in listOf(
            "12abc" to 12L, " 3.5" to 3L, "\t-7e-2xyz" to -7L, "+2" to 2L, ".5" to 0L, "1e5" to 1L, "abc" to 0L, "" to 0L,
            "007" to 7L, "\u000B4" to 4L, "9223372036854775807" to Long.MAX_VALUE, "9223372036854775808" to Long.MAX_VALUE,
            "-9223372036854775808" to Long.MIN_VALUE, "-9223372036854775809" to Long.MIN_VALUE,
            "123456789012345678901234567890" to Long.MAX_VALUE,
        )) assertEquals(value, HostCall.sqliteLong(text), "on $target, the whole number of '$text'")
        for ((text, value) in listOf(
            "12abc" to 12.0, " 3.5" to 3.5, "\t-7e-2xyz" to -0.07, "+2" to 2.0, ".5" to 0.5, "1." to 1.0, "1e" to 1.0,
            "1e+2x" to 100.0, "-" to 0.0, "abc" to 0.0, "" to 0.0, "0x10" to 0.0, "Infinity" to 0.0, "NaN" to 0.0,
            "1e400" to Double.POSITIVE_INFINITY, "-1e400" to Double.NEGATIVE_INFINITY, "2.5 " to 2.5, "1 2" to 1.0,
        )) assertEquals(value, HostCall.sqliteDouble(text), "on $target, the float of '$text'")
    }

    @Test
    fun aFloatIsReadExactlyAndWrittenAsTextThatReadsBackOnEveryTarget() {
        // Kotlin/Wasm's own String.toDouble is an ulp off for about one double in 400 (see parseDoubleOrNull).
        val random = Random(757)
        repeat(if (target == "jvm") 20_000 else 5_000) {
            val value = when (it % 3) {
                0 -> Double.fromBits(random.nextLong()).takeIf { d -> d.isFinite() } ?: 1.5
                1 -> random.nextDouble(-1e6, 1e6)
                else -> random.nextLong(-1_000_000_000_000L, 1_000_000_000_000L) / 1000.0
            }
            assertEquals("t${DoubleText.of(value)}", HostCall.call("drivine_float_text", item('r', jvmDoubleText(value))), "on $target, $value")
            assertEquals(value, parseDouble(SqliteRealText.of(value)), "on $target, $value (${value.toRawBits()}) as ${SqliteRealText.of(value)}")
        }
    }
}
