package org.drivine.cypher.oracle

import com.fasterxml.jackson.core.json.JsonWriteFeature
import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.databind.json.JsonMapper
import java.io.File
import kotlin.random.Random

/**
 * The spec's statements, mutated: what the corpus, all valid statements, never sends down the compiler's other paths, where
 * an exception thrown by the standard library (a list's index, `!!`, `toInt`, `substring`, a regex) would give a target's
 * own message as the refusal. Each statement is truncated (at a token, or anywhere), has a character or a token deleted,
 * junk inserted, two tokens swapped, a clause written twice, a literal changed to an edge value, or a function's name
 * changed to a near miss, and the mutant is compiled for each store.
 *
 * Only a mutant whose answer is of interest is kept: a compile that succeeds, or a refusal other than the parser's own
 * (`The statement is not Cypher, …`), which the syntax fuzz covers. Of those, [write] keeps a sample of `count`, from
 * `seed`, each line `{"request": …, "answer": …}` as in `corpus.jsonl`. Each statement is mutated 12 times, or as many as
 * the system property `mutants.perStatement` says, for a wider run by hand (`-Poracle.jvmArgs=-Dmutants.perStatement=150`).
 */
object Mutants {
    private val json = ObjectMapper()

    /** A request with a lone surrogate is written in ASCII, `\uD800`: no UTF-8 holds one, and the C ABI takes UTF-8. */
    private val ascii = JsonMapper.builder().enable(JsonWriteFeature.ESCAPE_NON_ASCII).build()

    /** How many mutants were made, how many of interest, and how many written. */
    data class Counts(val made: Int, val kept: Int, val written: Int)

    /** A spec statement and its parameters. */
    data class Statement(val cypher: String, val parameters: Map<String, Any?>)

    private val PER_STATEMENT = System.getProperty("mutants.perStatement")?.toInt() ?: 12
    private const val SYNTAX = "{\"refusal\":\"The statement is not Cypher, "

    /**
     * Writes the sample into [into]. [answer] compiles a request on the JVM: null for one whose answer embeds the clock, as the
     * corpus leaves those out.
     */
    fun write(statements: List<Statement>, stores: List<String>, into: File, count: Int, seed: Long, answer: (String) -> String?): Counts {
        val random = Random(seed)
        val seen = HashSet<String>()
        statements.forEach { seen += it.cypher }
        var made = 0
        val kept = ArrayList<String>()
        for (statement in statements.distinctBy { it.cypher to it.parameters }) {
            repeat(PER_STATEMENT) {
                // One mutation, or now and then two.
                var cypher = mutate(statement.cypher, random) ?: return@repeat
                if (random.nextInt(3) == 0) cypher = mutate(cypher, random) ?: cypher
                if (!seen.add(cypher)) return@repeat
                for (store in stores) {
                    made++
                    val request = (if (cypher.hasLoneSurrogate()) ascii else json).writeValueAsString(
                        linkedMapOf("store" to store, "graph" to mapOf("name" to "g"), "cypher" to cypher, "parameters" to statement.parameters),
                    )
                    // A mutant the JVM can't answer at all (a stack overflow) is no case.
                    val got = try {
                        answer(request)
                    } catch (e: Throwable) {
                        null
                    }
                    if (got == null || got.startsWith(SYNTAX)) continue
                    // In ASCII, so a lone surrogate in an answer outlives the file's UTF-8.
                    kept += ascii.writeValueAsString(linkedMapOf("request" to request, "answer" to got))
                }
            }
        }
        val sample = if (kept.size <= count) kept else kept.shuffled(Random(seed)).take(count)
        into.printWriter().use { w -> sample.forEach(w::println) }
        return Counts(made, kept.size, sample.size)
    }

    private fun String.hasLoneSurrogate(): Boolean = indices.any { i ->
        val c = this[i]
        (c.isHighSurrogate() && getOrNull(i + 1)?.isLowSurrogate() != true) || (c.isLowSurrogate() && getOrNull(i - 1)?.isHighSurrogate() != true)
    }

    private class Token(val start: Int, val end: Int, val kind: Char)

    /** A quoted text's run of plain characters is matched at once, as `OracleCypherCompiler`'s `QUOTED` is, so a long one doesn't overflow the stack. */
    private val TOKEN = Regex(
        """('[^'\\]*(?:\\.[^'\\]*)*'?|"[^"\\]*(?:\\.[^"\\]*)*"?)|(`[^`]*(?:``[^`]*)*`?)|(\$\w+)|(0x[0-9a-fA-F]+|\d+(?:\.\d+)?(?:[eE][+-]?\d+)?)|([\p{L}_][\p{L}\p{N}_]*)|(<>|<=|>=|=~|->|<-|\.\.|\+=|\S)""",
    )

    private fun tokens(cypher: String): List<Token> = TOKEN.findAll(cypher).map { m ->
        val kind = (1..6).first { m.groups[it] != null }.let { "sbpnio"[it - 1] }
        Token(m.range.first, m.range.last + 1, kind)
    }.toList()

    private val JUNK = listOf(
        "#", ")", "'", "\"", "`", "(", "[", "|", "{", "}", ",", "-", ".", "$", "\\", " RETURN ", " AS ", " WITH ", "0x", "1e",
        "😀", "\uD800",
    )
    /** Edge values, and two floats whose text Java writes otherwise than JavaScript (`1.0E7`, `1.0E-4`). */
    private val EDGES = listOf("9223372036854775808", "-0.0", "1e309", "''", "'\\U0041'", "[]", "{}", "null", "1e7", "1e-4")
    private val CLAUSES = setOf(
        "MATCH", "OPTIONAL", "WHERE", "WITH", "RETURN", "CREATE", "MERGE", "SET", "DELETE", "DETACH", "REMOVE", "UNWIND", "ORDER",
        "SKIP", "LIMIT", "CALL", "UNION", "FOREACH", "ON",
    )

    /** One mutation of [cypher], or null where the mutation picked has nothing to work on. */
    private fun mutate(cypher: String, random: Random): String? {
        val tokens = tokens(cypher)
        if (tokens.isEmpty()) return null
        fun pick() = tokens[random.nextInt(tokens.size)]
        fun boundary() = if (random.nextBoolean()) pick().start else pick().end
        fun replace(t: Token, with: String) = cypher.substring(0, t.start) + with + cypher.substring(t.end)
        return when (random.nextInt(10)) {
            0 -> cypher.substring(0, boundary())
            1 -> cypher.substring(0, random.nextInt(cypher.length))
            2 -> random.nextInt(cypher.length).let { cypher.removeRange(it, it + 1) }
            3 -> replace(pick(), "")
            4 -> {
                val at = if (random.nextBoolean()) boundary() else random.nextInt(cypher.length + 1)
                cypher.substring(0, at) + JUNK[random.nextInt(JUNK.size)] + cypher.substring(at)
            }
            5 -> {
                if (tokens.size < 2) return null
                val i = random.nextInt(tokens.size)
                val j = if (random.nextBoolean()) (i + 1).coerceAtMost(tokens.size - 1) else random.nextInt(tokens.size)
                if (i == j) return null
                val (a, b) = if (i < j) tokens[i] to tokens[j] else tokens[j] to tokens[i]
                cypher.substring(0, a.start) + cypher.substring(b.start, b.end) + cypher.substring(a.end, b.start) +
                    cypher.substring(a.start, a.end) + cypher.substring(b.end)
            }
            6 -> {
                val starts = tokens.filter { it.kind == 'i' && cypher.substring(it.start, it.end).uppercase() in CLAUSES }
                if (starts.isEmpty()) return null
                val i = random.nextInt(starts.size)
                val from = starts[i].start
                val to = starts.getOrNull(i + 1)?.start ?: cypher.length
                val clause = cypher.substring(from, to).trimEnd()
                cypher.substring(0, to).trimEnd() + " " + clause + (if (to < cypher.length) " " + cypher.substring(to) else "")
            }
            7, 8 -> {
                val literals = tokens.filter { it.kind == 'n' || it.kind == 's' || (it.kind == 'i' && cypher.substring(it.start, it.end).lowercase() in setOf("true", "false", "null")) }
                if (literals.isEmpty()) return null
                replace(literals[random.nextInt(literals.size)], EDGES[random.nextInt(EDGES.size)])
            }
            else -> {
                val calls = tokens.indices.filter { tokens[it].kind == 'i' && tokens.getOrNull(it + 1)?.let { n -> cypher[n.start] == '(' } == true }
                if (calls.isEmpty()) return null
                val t = tokens[calls[random.nextInt(calls.size)]]
                replace(t, nearMiss(cypher.substring(t.start, t.end), random))
            }
        }
    }

    /** [name] a letter off: one dropped, doubled, changed or swapped with the next, its case flipped, or `s` added. */
    private fun nearMiss(name: String, random: Random): String {
        val i = random.nextInt(name.length)
        return when (random.nextInt(6)) {
            0 -> if (name.length > 1) name.removeRange(i, i + 1) else name + "x"
            1 -> name.substring(0, i + 1) + name[i] + name.substring(i + 1)
            2 -> name.substring(0, i) + ('a' + random.nextInt(26)) + name.substring(i + 1)
            3 -> if (i + 1 < name.length) name.substring(0, i) + name[i + 1] + name[i] + name.substring(i + 2) else name + "s"
            4 -> name.map { if (it.isUpperCase()) it.lowercaseChar() else it.uppercaseChar() }.joinToString("")
            else -> name + "s"
        }
    }
}
