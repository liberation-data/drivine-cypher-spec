package org.drivine.cypher.host

import org.drivine.cypher.json.CypherJson
import org.drivine.cypher.json.JsonNode
import org.drivine.cypher.number.oracleFile
import org.drivine.cypher.number.target
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertFails
import kotlin.test.assertTrue

/**
 * The spec's host.yaml (as host.json, `./gradlew jvmOracle`), over the module's own host functions ([HostFunctions]), called
 * as SQLite calls them, on every target: each `function` step of a function the module implements gives its `result`, fails
 * with its `error`, or its values ascend. A function host.yaml calls that the module doesn't implement yet is in [PENDING], and [PENDING] holds
 * nothing else; each function the module implements has a step. A `read` step pins each library's row reader, not the
 * module's, and is passed over. The cases' `pairs` are checked by each library's runner (drivine4j's HostSpecTest), which
 * compiles them. The SQLite extension's check (check/extension-native.py) runs the same steps through SQL, on the
 * native library loaded in SQLite.
 */
class HostSpecTest {

    private companion object {
        /** The keys of each kind of step, beside its optional `note`: a function's result, its failure, its order, and a read. */
        val SHAPES = setOf(setOf("function", "args", "result"), setOf("function", "args", "error"), setOf("function", "ascending"), setOf("read", "value"))

        /** Drivine's functions, and `regexp`, which SQLite calls, as `regexp(pattern, text)`, for `text REGEXP pattern`. */
        val FUNCTION = Regex("drivine_[a-z_]+|regexp")

        /** The functions host.yaml calls that are not yet ported from drivine4j (#108): a port takes its own out. All are ported now. */
        val PENDING = emptySet<String>()

        /**
         * The note of a step held until the store's timestamps keep nine digits (layout 2): it is run, and must fail, so
         * the hold comes off when it passes. Matched within the note, as check/extension-native.py matches it.
         */
        const val HELD = "drivine4j#421 (layout 2)"
    }

    private val cases: List<JsonNode> = CypherJson.readTree(oracleFile("host.json"))["cases"]!!.toList()

    @Test
    fun `each function step of a function the module implements gives what host_yaml says`() {
        var steps = 0
        var reads = 0
        val held = mutableListOf<String>()
        for (case in cases) {
            for ((index, step) in case["steps"]!!.withIndex()) {
                val where = "${case["name"]!!.textValue()}, step ${index + 1}"
                val outcome = check(step, where)
                if (outcome == Outcome.RAN) steps++
                if (outcome == Outcome.READ) reads++
                if (outcome == Outcome.HELD) held += where
            }
        }
        println("HostSpecTest on $target: $steps function steps of ${HostFunctions.all.size} functions ran; $reads reads are each library's.")
        println("HostSpecTest on $target: held for layout 2, and failing as they should: ${held.joinToString("; ").ifEmpty { "none" }}.")
        assertTrue(steps > 0, "No function step ran")
    }

    @Test
    fun `the pending functions are exactly those host_yaml calls that the module does not implement`() {
        val called = cases.flatMap { case -> case["steps"]!!.mapNotNull { it["function"]?.textValue() } }.toSet()
        val implemented = HostFunctions.all.map { it.name }.toSet()
        assertEquals(emptySet(), PENDING - called, "Pending, but host.yaml calls none of them")
        assertEquals(emptySet(), PENDING intersect implemented, "Implemented now: take them out of PENDING")
        assertEquals(emptySet(), called - implemented - PENDING, "Called by host.yaml, but neither implemented nor pending")
        assertEquals(emptySet(), implemented - called, "Implemented, with no step in host.yaml to pin it")
    }

    @Test
    fun `a step of another shape or a result the function does not give or a function it has not fails`() {
        val good = CypherJson.readTree("""{"function": "drivine_float_text", "args": [{"${'$'}float": "Infinity"}], "result": "Infinity"}""")
        assertEquals(Outcome.RAN, check(good, "good"))
        val wrong = CypherJson.readTree("""{"function": "drivine_float_text", "args": [2.0], "result": "2"}""")
        assertContains(assertFails { check(wrong, "wrong") }.message!!, "wrong")
        val typed = CypherJson.readTree("""{"function": "drivine_float_text", "args": [null], "result": "null"}""")
        assertFails { check(typed, "typed") }
        val misspelt = CypherJson.readTree("""{"function": "drivine_float_text", "args": [1.0], "reslt": "1.0"}""")
        assertContains(assertFails { check(misspelt, "misspelt") }.message!!, "a step has the keys")
        val unknown = CypherJson.readTree("""{"function": "drivine_nothing", "args": [1], "result": 1}""")
        assertContains(assertFails { check(unknown, "unknown") }.message!!, "neither implemented nor pending")
        val arity = CypherJson.readTree("""{"function": "drivine_float_text", "args": [1.0, 2.0], "result": "1.0"}""")
        assertContains(assertFails { check(arity, "arity") }.message!!, "takes 1 argument")
        val one = CypherJson.readTree("""{"function": "drivine_float_text", "ascending": [1.0]}""")
        assertContains(assertFails { check(one, "one") }.message!!, "two values or more")
        val nulls = CypherJson.readTree("""{"function": "drivine_float_text", "ascending": [null, 1.0]}""")
        assertContains(assertFails { check(nulls, "nulls") }.message!!, "before")
        val descending = CypherJson.readTree("""{"function": "drivine_float_text", "ascending": [2.0, 1.0]}""")
        assertContains(assertFails { check(descending, "descending") }.message!!, "before")
        val foreign = CypherJson.readTree("""{"function": "abs", "args": [1], "result": 1}""")
        assertContains(assertFails { check(foreign, "foreign") }.message!!, "no function of Drivine's")
        // SQLite reads a text as a float otherwise than Kotlin does ('Infinity' is 0.0 to it), so the step fails, not passes.
        val text = CypherJson.readTree("""{"function": "drivine_float_text", "args": ["Infinity"], "result": "Infinity"}""")
        assertContains(assertFails { check(text, "text") }.message!!, "No step reads the float of the text")
        val nan = CypherJson.readTree("""{"function": "drivine_float_text", "args": [{"${'$'}float": "NaN"}], "result": "NaN"}""")
        assertContains(assertFails { check(nan, "nan") }.message!!, "can't be given NaN")
        val nanAscending = CypherJson.readTree("""{"function": "drivine_float_text", "ascending": [1.0, {"${'$'}float": "NaN"}]}""")
        assertContains(assertFails { check(nanAscending, "nanAscending") }.message!!, "can't be given NaN")
        val truth = CypherJson.readTree("""{"function": "drivine_float_text", "args": [true], "result": "1.0"}""")
        assertContains(assertFails { check(truth, "truth") }.message!!, "No SQL value of")
        val fails = CypherJson.readTree("""{"function": "drivine_round", "args": [2.5, 0, "UNNECESSARY"], "error": "Rounding necessary"}""")
        assertEquals(Outcome.RAN, check(fails, "fails"))
        val gives = CypherJson.readTree("""{"function": "drivine_float_text", "args": [1.0], "error": "x"}""")
        assertContains(assertFails { check(gives, "gives") }.message!!, "gave no error")
        val otherError = CypherJson.readTree("""{"function": "drivine_round", "args": [2.5, 0, "UNNECESSARY"], "error": "no such"}""")
        assertContains(assertFails { check(otherError, "otherError") }.message!!, "Rounding necessary")
        val untexted = CypherJson.readTree("""{"function": "drivine_round", "args": [2.5, 0, "UNNECESSARY"], "error": 1}""")
        assertContains(assertFails { check(untexted, "untexted") }.message!!, "error is a text, not 1")
        val pending = CypherJson.readTree("""{"function": "drivine_pending", "args": ["local", "x"], "result": null}""")
        assertEquals(Outcome.PENDING, check(pending, "pending", setOf("drivine_pending")))
        val read = CypherJson.readTree("""{"read": {"store": "sqlite", "nonFinite": false, "column": "1"}, "value": 1, "note": "n"}""")
        assertEquals(Outcome.READ, check(read, "read"))
        val failing = HostFunction("drivine_failing", 1, deterministic = true) { _, _, result -> result.error("no") }
        assertContains(assertFails { call(listOf(failing), listOf(1L), "failing") }.message!!, "failing: drivine_failing failed: no")
        val nanResult = HostFunction("drivine_nan", 0, deterministic = true) { _, _, result -> result.double(Double.NaN) }
        assertEquals(null, call(listOf(nanResult), emptyList(), "nanResult"), "SQLite gives NULL of a NaN result")
    }

    @Test
    fun `drivine_float_text writes a finite float as Java does where host_yaml pins only the infinities`() {
        for ((arg, text) in listOf("2.0" to "2.0", "1.0E7" to "1.0E7", "1.2E-4" to "1.2E-4", "0.3333333333333333" to "0.3333333333333333", "2" to "2.0")) {
            val step = CypherJson.readTree("""{"function": "drivine_float_text", "args": [$arg], "result": "$text"}""")
            assertEquals(Outcome.RAN, check(step, arg))
        }
    }

    @Test
    fun `the functions of JSON values take an empty text and JSON's null as no value and fail on a text that is no JSON`() {
        // As drivine4j's do: Jackson reads a text of nothing but JSON's whitespace as a missing node, which is no value.
        for ((step, where) in listOf(
            """{"function": "drivine_equal", "args": ["", "1"], "result": null}""" to "equal",
            """{"function": "drivine_add", "args": [" ", "[1]"], "result": "[null,1]"}""" to "add",
            """{"function": "drivine_canonical", "args": ["\n"], "result": "\n"}""" to "canonical",
            """{"function": "drivine_sort_key", "args": ["\t"], "result": null}""" to "sortKey",
            """{"function": "drivine_sort_key", "args": ["null"], "result": null}""" to "sortKeyNull",
            """{"function": "drivine_list_order", "args": ["null", "[1]"], "result": null}""" to "listOrderNull",
            """{"function": "drivine_list_order", "args": ["[1]", "\r"], "result": null}""" to "listOrderEmpty",
        )) assertEquals(Outcome.RAN, check(CypherJson.readTree(step), where))
        for (function in listOf("drivine_equal", "drivine_add", "drivine_canonical", "drivine_sort_key", "drivine_list_order")) {
            val args = if (function == "drivine_canonical" || function == "drivine_sort_key") """["[1"]""" else """["[1", "[1]"]"""
            assertFails { check(CypherJson.readTree("""{"function": "$function", "args": $args, "result": null}"""), function) }
        }
    }

    @Test
    fun `drivine_round rounds a whole number and reads a float precision as SQLite does where host_yaml pins neither`() {
        for ((args, result) in listOf(
            """[15, -1, "HALF_EVEN"]""" to "20.0",
            """[-15, -1, "HALF_DOWN"]""" to "-10.0",
            """[9223372036854775807, -18, "CEILING"]""" to "1.0E19",
            """[2.345, 2.9, "HALF_UP"]""" to "2.35",
            """[2.5, -9223372036854775808, "UP"]""" to """{"${'$'}float": "Infinity"}""",
            """[2.5, 0, "UNNECESSARY"]""" to null,
        )) {
            val step = CypherJson.readTree("""{"function": "drivine_round", "args": $args, "result": ${result ?: "3.0"}}""")
            if (result != null) assertEquals(Outcome.RAN, check(step, args))
            else assertContains(assertFails { check(step, args) }.message!!, "Rounding necessary")
        }
        val unknown = CypherJson.readTree("""{"function": "drivine_round", "args": [2.5, 0, "half_up"], "result": 3.0}""")
        assertContains(assertFails { check(unknown, "unknown") }.message!!, "no rounding mode is named half_up")
    }

    @Test
    fun `values ascend as SQLite orders them`() {
        assertTrue(sqliteOrder(null, 1L) < 0)
        assertTrue(sqliteOrder(1L, 1.5) < 0)
        assertTrue(sqliteOrder(2.0, 2L) == 0)
        assertTrue(sqliteOrder(9L, "1") < 0)
        // SQLite's BINARY collation compares UTF-8 bytes: a supplementary character after U+FFFD, as UTF-16 would not put it.
        assertTrue(sqliteOrder("�", "😀") < 0)
        assertTrue(sqliteOrder("Infinity", "-Infinity") > 0)
    }

    @Test
    fun `a step held for layout 2 must fail and is listed and one that passes fails the run`() {
        val heldFailing = CypherJson.readTree("""{"note": "x $HELD", "function": "drivine_float_text", "args": [1.0], "result": "1"}""")
        assertEquals(Outcome.HELD, check(heldFailing, "heldFailing"))
        val heldErring = CypherJson.readTree("""{"note": "$HELD", "function": "drivine_round", "args": [2.5, 0, "UNNECESSARY"], "result": 3.0}""")
        assertEquals(Outcome.HELD, check(heldErring, "heldErring"))
        val heldPassing = CypherJson.readTree("""{"note": "$HELD", "function": "drivine_float_text", "args": [1.0], "result": "1.0"}""")
        assertContains(assertFails { check(heldPassing, "heldPassing") }.message!!, "and passing: take the hold off")
        val heldWrongError = CypherJson.readTree("""{"note": "$HELD", "function": "drivine_float_text", "args": [1.0], "error": "x"}""")
        assertEquals(Outcome.HELD, check(heldWrongError, "heldWrongError"))
        // A step the runner can't run is no hold.
        val heldUnreadable = CypherJson.readTree("""{"note": "$HELD", "function": "drivine_float_text", "args": [true], "result": "1.0"}""")
        assertContains(assertFails { check(heldUnreadable, "heldUnreadable") }.message!!, "No SQL value of")
        val otherNote = CypherJson.readTree("""{"note": "drivine4j#421", "function": "drivine_float_text", "args": [1.0], "result": "1"}""")
        assertFails { check(otherNote, "otherNote") }
        val heldPending = CypherJson.readTree("""{"note": "$HELD", "function": "drivine_pending", "args": ["local", "x"], "result": null}""")
        assertEquals(Outcome.PENDING, check(heldPending, "heldPending", setOf("drivine_pending")))
    }

    private enum class Outcome { RAN, PENDING, READ, HELD }

    /** Runs [step], of a shape the spec's README gives. */
    private fun check(step: JsonNode, where: String, pending: Set<String> = PENDING): Outcome {
        val keys = step.keys() - "note"
        require(keys in SHAPES) { "$where: a step has the keys of one of $SHAPES, and an optional note, not $keys" }
        if ("read" in keys) return Outcome.READ
        val name = step["function"]!!.textValue()!!
        require(name.matches(FUNCTION)) { "$where: $name is no function of Drivine's" }
        val functions = HostFunctions.all.filter { it.name == name }
        if (functions.isEmpty()) {
            require(name in pending) { "$where: $name is neither implemented nor pending" }
            return Outcome.PENDING
        }
        if (step["note"]?.textValue()?.contains(HELD) != true) {
            run(step, name, functions, where)
            return Outcome.RAN
        }
        // Held: it must fail, by an assertion or as the function fails, but not as the runner can't run it.
        val failed = try {
            run(step, name, functions, where)
            false
        } catch (e: AssertionError) {
            true
        } catch (e: HostFailure) {
            true
        }
        if (!failed) throw AssertionError("$where: held for layout 2 ($HELD), and passing: take the hold off")
        return Outcome.HELD
    }

    /** Runs the function step [step] of [name], one of [functions]: throws where it doesn't give what the step says. */
    private fun run(step: JsonNode, name: String, functions: List<HostFunction>, where: String) {
        val ascending = step["ascending"]
        if (ascending != null) {
            val values = ascending.map(::expanded)
            require(values.size >= 2) { "$where: ascending has two values or more, each before the next" }
            refuseNaN(values, where)
            values.zipWithNext().forEach { (a, b) ->
                val x = call(functions, listOf(a), where)
                val y = call(functions, listOf(b), where)
                // As SQL's `<` reads them (check/extension-native.py): a null on either side is no order.
                assertTrue(x != null && y != null && sqliteOrder(x, y) < 0, "$where: $name($a) before $name($b), not $x and $y")
            }
        } else {
            val args = step["args"]!!.map(::expanded)
            refuseNaN(args, where)
            val error = step["error"]?.let { it.textValue() ?: throw IllegalArgumentException("$where: error is a text, not ${CypherJson.writeValueAsString(it)}") }
            if (error == null) assertEquals(expanded(step["result"]!!), call(functions, args, where), "$where: $name$args")
            else {
                val failure = runCatching { call(functions, args, where) }.exceptionOrNull()
                    ?: throw AssertionError("$where: $name$args fails with \"$error\", but gave no error")
                assertContains(failure.message!!, error, message = "$where: $name$args")
            }
        }
    }

    /** SQLite binds a NaN as NULL (check/extension-native.py refuses it too): the function would be given null. */
    private fun refuseNaN(values: List<Any?>, where: String) =
        require(values.none { it is Double && it.isNaN() }) { "$where: SQLite can't be given NaN as an argument" }

    /** The function of [functions] that takes [args], called as SQLite calls it: its result, or why it failed. */
    private fun call(functions: List<HostFunction>, args: List<Any?>, where: String): Any? = callHost(functions, args, where)

    private fun expanded(node: JsonNode): Any? = sqlValue(node)

    /** SQLite's order of two values: null first, then numbers by value, then texts by their UTF-8 bytes (BINARY). */
    private fun sqliteOrder(a: Any?, b: Any?): Int {
        fun rank(v: Any?) = when (v) {
            null -> 0
            is Long, is Double -> 1
            is String -> 2
            else -> 3
        }
        if (rank(a) != rank(b)) return rank(a).compareTo(rank(b))
        return when (a) {
            null -> 0
            is Long -> if (b is Long) a.compareTo(b) else a.toDouble().compareTo(b as Double)
            is Double -> a.compareTo((b as Number).toDouble())
            is String -> {
                val x = a.encodeToByteArray()
                val y = (b as String).encodeToByteArray()
                (0 until minOf(x.size, y.size)).firstOrNull { x[it] != y[it] }
                    ?.let { (x[it].toInt() and 0xFF).compareTo(y[it].toInt() and 0xFF) } ?: x.size.compareTo(y.size)
            }
            else -> error("No order of $a")
        }
    }
}
