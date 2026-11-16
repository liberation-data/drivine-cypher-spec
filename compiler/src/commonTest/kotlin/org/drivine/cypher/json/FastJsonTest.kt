package org.drivine.cypher.json

import org.drivine.cypher.number.target
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.test.fail

/**
 * [FastJson] decides nothing of its own: wherever it reads a text, the full read ([CypherJson.readTreeFull], as
 * kotlinx.serialization reads it) reads the same tree, of the same node types, and wherever it doesn't, [CypherJson.readTree]
 * falls back to the full read. Over hand-picked texts at each edge of JSON's grammar, and texts made at random from JSON's
 * pieces, valid and broken, on every target.
 */
class FastJsonTest {

    /** The texts at each edge, and whether [FastJson] reads each (true) or leaves it to the full read (false). */
    private val edges = listOf(
        "1" to true, "-0" to true, "0" to true, "-1.5e-3" to true, "1E5" to true, "1e+5" to true, "2.50" to true,
        "9223372036854775807" to true, "9223372036854775808" to true, "-9223372036854775809" to true, "123456789012345678901234567890" to true,
        "true" to true, "false" to true, "null" to true, "\"\"" to true, "\"a\\\"b\\\\c\\/d\\b\\f\\n\\r\\t\\u00e9\\uD83D\\uDE00\"" to true,
        "[]" to true, "{}" to true, " [ 1 , [ ] , { } ] " to true, "{\"a\":{\"b\":[null,true]}}" to true, "\t\n\r[1]\n" to true,
        "\"\\u0000\"" to true, "[1,2.0,\"3\"]" to true,
        // Each of these the fast read leaves to the full read, which reads or refuses it as before.
        "" to false, "   " to false, "01" to false, "1." to false, ".5" to false, "-" to false, "+1" to false, "1e" to false,
        "NaN" to false, "Infinity" to false, "abc" to false, "True" to false, "nul" to false, "truex" to false, "1abc" to false,
        "[1,]" to false, "[,1]" to false, "{\"a\":1,}" to false, "{a:1}" to false, "{\"a\" 1}" to false, "{\"a\":1,\"a\":2}" to false,
        "[1 2]" to false, "1 2" to false, "\"abc" to false, "\"a\u0001b\"" to false, "\"\\x\"" to false, "\"\\u12\"" to false,
        "\"\\u12G4\"" to false, "[" to false, "{" to false, "]" to false, "'a'" to false, "[1]x" to false,
        "[".repeat(65) + "]".repeat(65) to false,
    )

    @Test
    fun eachEdgeIsReadAsTheFullReadReadsItOrLeftToIt() {
        for ((text, fast) in edges) {
            val node = FastJson.read(text)
            if (fast) assertNotNull(node, "on $target, the fast read leaves ${show(text)} to the full read") else assertNull(node, "on $target, the fast read reads ${show(text)}")
            same(text)
        }
        // The deepest nesting the full read takes, the fast read takes too.
        assertNotNull(FastJson.read("[".repeat(MAX_DEPTH) + "]".repeat(MAX_DEPTH)), "on $target")
    }

    @Test
    fun randomTextsAreReadAsTheFullReadReadsThem() {
        val random = Random(757)
        val pieces = listOf(
            "[", "]", "{", "}", ",", ":", " ", "\n", "\"a\"", "\"k\"", "\"\\n\"", "\"\\u0041\"", "\"é\"", "0", "-1", "1.5", "2e3",
            "-0.0", "123456789012345678901", "true", "false", "null", "x", "01", "1.", "\\", "\"",
        )
        var read = 0
        repeat(if (target == "jvm") 200_000 else 20_000) {
            val text = if (random.nextBoolean()) valid(random, 0) else (0 until random.nextInt(1, 12)).joinToString("") { pieces.random(random) }
            if (same(text)) read++
        }
        assertTrue(read > 5_000, "on $target, the fast read read only $read texts")
    }

    @Test
    fun readTreeGivesTheFullReadsFailureForATextTheFastReadLeaves() {
        for (text in listOf("abc", "[1,]", "{\"a\":1,\"a\":2}", "[".repeat(65) + "]".repeat(65))) {
            val full = runCatching { CypherJson.readTreeFull(text) }
            val tree = runCatching { CypherJson.readTree(text) }
            assertEquals(full.exceptionOrNull()?.message, tree.exceptionOrNull()?.message, "on $target, ${show(text)}")
            assertEquals(full.getOrNull(), tree.getOrNull(), "on $target, ${show(text)}")
        }
    }

    /** A valid JSON text, made at random. */
    private fun valid(random: Random, depth: Int): String = when (if (depth > 4) random.nextInt(4) else random.nextInt(6)) {
        0 -> listOf("0", "-7", "3.25", "1e-7", "-2.5E+10", "9007199254740993", "99999999999999999999", "0.1").random(random)
        1 -> listOf("true", "false", "null").random(random)
        2 -> listOf("\"\"", "\"abc\"", "\"a\\\"b\"", "\"\\u00e9\"", "\"\\\\\"", "\"$\"").random(random)
        3 -> "\"s${random.nextInt(100)}\""
        4 -> (0 until random.nextInt(4)).joinToString(",", "[", "]") { valid(random, depth + 1) }
        else -> (0 until random.nextInt(4)).joinToString(",", "{", "}") { "\"k$it\": ${valid(random, depth + 1)}" }
    }

    /** Whether the fast read reads [text]; where it does, the full read reads the same tree, of the same types. */
    private fun same(text: String): Boolean {
        val fast = FastJson.read(text) ?: return false
        val full = try {
            CypherJson.readTreeFull(text)
        } catch (e: Exception) {
            fail("on $target, the fast read reads ${show(text)}, which the full read refuses: ${e.message}")
        }
        assertEquals(kinds(full), kinds(fast), "on $target, ${show(text)}")
        assertEquals(full, fast, "on $target, ${show(text)}")
        return true
    }

    /** [node]'s node types, all through it, as equality of the trees alone might not tell a 1 from a 1.0. */
    private fun kinds(node: JsonNode): String = when (node) {
        is ArrayNode -> node.joinToString(",", "[", "]") { kinds(it) }
        is ObjectNode -> node.properties().joinToString(",", "{", "}") { (k, v) -> "$k:${kinds(v)}" }
        else -> "${node::class.simpleName}(${CypherJson.writeValueAsString(node)})"
    }

    private fun show(text: String) = if (text.length > 40) text.take(40) + "…" else text
}
