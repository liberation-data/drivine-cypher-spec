package org.drivine.cypher.host.regex

import org.drivine.cypher.json.CypherJson
import org.drivine.cypher.json.JsonNode
import org.drivine.cypher.number.oracleFile
import org.drivine.cypher.number.target
import kotlin.test.Test
import kotlin.test.assertTrue

/**
 * Every target's regular expressions against Java's answers (`regex.json`, written by the JVM: see
 * `org.drivine.cypher.oracle.Regexes`), as SQLite's host functions use them: whether each pattern is found in each text,
 * the groups of its first match, how many times it matches, and every match replaced; and what case-insensitive matching
 * takes. Each pattern must give Java's answers, or be refused by one of [REFUSED]'s names off the JVM.
 *
 * Two ways: [JavaPatterns], as the host functions compile a pattern on this target (java.util.regex on the JVM), and
 * [HarmonyPattern], the rewrite for kotlin.text.Regex that Kotlin/Native and Kotlin/Wasm run, which on the JVM runs on
 * java.util.regex without UNICODE_CHARACTER_CLASS, so the JVM checks the rewrite's tables too.
 */
class RegexOracleTest {

    private val oracle: JsonNode = CypherJson.readTree(oracleFile("regex.json"))
    private val texts: List<String> = oracle["texts"]!!.map { it.textValue()!! }

    @Test
    fun `each pattern matches as Java's does through JavaPatterns`() = check("JavaPatterns") { JavaPatterns.compile(it) }

    @Test
    fun `each pattern matches as Java's does through the rewrite for kotlin_text_Regex`() = check("HarmonyPattern") { HarmonyPattern(JavaPatterns.java(it)) }

    @Test
    fun `case-insensitive matching takes the code points Java's takes through JavaPatterns`() = caseFold("JavaPatterns") { JavaPatterns.compile(it) }

    @Test
    fun `case-insensitive matching takes the code points Java's takes through the rewrite`() = caseFold("HarmonyPattern") { HarmonyPattern(JavaPatterns.java(it)) }

    private fun check(way: String, compile: (String) -> JavaPattern) {
        val differences = mutableListOf<String>()
        val refusals = mutableMapOf<String, Int>()
        var agreed = 0
        for (case in oracle["patterns"]!!) {
            val pattern = case["pattern"]!!.textValue()!!
            val javaError = case["error"]?.textValue()
            val compiled = try {
                compile(pattern)
            } catch (e: UnsupportedPatternException) {
                if (javaError == null && e.construct in REFUSED) refusals[e.construct] = (refusals[e.construct] ?: 0) + 1 else if (javaError == null) differences += "${show(pattern)}: refused, ${e.message}"
                continue
            } catch (e: Throwable) {
                if (javaError == null) differences += "${show(pattern)}: refused (${e.message?.lineSequence()?.first()}), where Java compiles it" else agreed++
                continue
            }
            if (javaError != null) {
                differences += "${show(pattern)}: compiled, where Java refuses it ($javaError)"
                continue
            }
            val replacement = JavaPatterns.javaReplacement(case["replacement"]!!.textValue()!!)
            val before = differences.size
            for ((index, expected) in case["results"]!!.withIndex()) {
                val text = texts[index]
                fun differ(op: String, java: Any?, got: Any?) {
                    differences += "${show(pattern)}: $op of ${show(text)}: Java ${show(java)}, $target ${show(got)}"
                }
                val javaGroups = expected[1]!!.takeUnless { it.isNull }?.map { if (it.isNull) null else it.textValue() }
                val groups = attempt { compiled.find(text) }
                if (groups.getOrNull() != javaGroups || groups.isFailure) differ("find", javaGroups, groups.getOrNull() ?: groups.exceptionOrNull())
                val count = attempt { compiled.count(text) }
                if (count.getOrNull() != expected[2]!!.longValue().toInt()) differ("count", expected[2]!!.longValue(), count.getOrNull() ?: count.exceptionOrNull())
                val replaced = attempt { compiled.replaceAll(text, replacement) }
                if (replaced.getOrNull() != expected[3]!!.textValue()) differ("replace", expected[3]!!.textValue(), replaced.getOrNull() ?: replaced.exceptionOrNull())
            }
            if (differences.size == before) agreed++
        }
        println("RegexOracleTest on $target through $way: ${oracle["patterns"]!!.size()} patterns over ${texts.size} texts, $agreed as Java's, refused $refusals.")
        assertTrue(differences.isEmpty(), "$way on $target: ${differences.size} answers differ from Java's:\n" + differences.take(30).joinToString("\n"))
    }

    private fun caseFold(way: String, compile: (String) -> JavaPattern) {
        val differences = mutableListOf<String>()
        var checked = 0
        for (entry in oracle["caseFold"]!!) {
            val cp = entry["cp"]!!.longValue().toInt()
            val candidates = entry["candidates"]!!.map { it.longValue().toInt() }
            for ((key, pattern) in listOf("alone" to "(?i)" + text(cp), "inClass" to "(?i)[" + text(cp) + "]")) {
                val compiled = compile(pattern)
                for ((i, candidate) in candidates.withIndex()) {
                    val java = entry[key]!![i]!!.booleanValue()
                    val got = compiled.find(text(candidate))?.get(0) == text(candidate)
                    checked++
                    if (got != java) differences += "${show(pattern)} of ${show(text(candidate))}: Java $java, $target $got"
                }
            }
        }
        println("RegexOracleTest on $target through $way: $checked case-insensitive matches.")
        assertTrue(differences.isEmpty(), "$way on $target: ${differences.size} case-insensitive matches differ from Java's:\n" + differences.take(30).joinToString("\n"))
    }

    private fun text(cp: Int): String =
        if (cp <= 0xFFFF) cp.toChar().toString() else charArrayOf((0xD800 + ((cp - 0x10000) shr 10)).toChar(), (0xDC00 + ((cp - 0x10000) and 0x3FF)).toChar()).concatToString()

    private inline fun <T> attempt(block: () -> T): Result<T> = try {
        Result.success(block())
    } catch (e: Throwable) {
        Result.failure(e)
    }

    private companion object {
        /** What a target whose regular expressions are not Java's refuses by name: see HarmonyRewrite. */
        val REFUSED = setOf("\\G", "\\X", "\\N{…}", "\\b{g}", "(?-U)", "(?-u)", "a back reference under (?i)", "a back reference to a later group",
            "a lazy quantifier on a group but *? and ??", "a possessive quantifier on a group")

        /** [value], short, its characters outside printable ASCII escaped. */
        fun show(value: Any?): String {
            val text = when (value) {
                null -> return "null"
                is String -> "\"$value\""
                else -> value.toString()
            }
            val shown = buildString {
                for (c in text) if (c.code in 0x20..0x7E) append(c) else append("\\u").append(c.code.toString(16).padStart(4, '0'))
            }
            return if (shown.length > 200) shown.take(200) + "…" else shown
        }
    }
}
