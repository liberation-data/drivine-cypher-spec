package org.drivine.cypher.host.regex

import org.drivine.cypher.json.CypherJson
import org.drivine.cypher.number.oracleFile
import org.drivine.cypher.search.TextQuery
import org.drivine.cypher.sql.Patterns
import org.drivine.cypher.sql.SqliteCypherCompiler
import org.drivine.cypher.sql.SqliteTemporalSql
import org.drivine.cypher.sql.Temporal
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Every pattern Drivine writes for SQLite's regular expression functions is inside the grammar HarmonyRewrite takes on
 * Kotlin/Native and Kotlin/Wasm, built from the compiler's own constants and functions, on every target: the JVM runs the
 * rewrite too. That each matches as Java's does is RegexOracleTest's, which has each of these among its patterns
 * (`org.drivine.cypher.oracle.Regexes`), with every other the compiler writes over the corpus, none of them refused.
 */
class DrivinePatternsTest {

    /** host.yaml's patterns: regexp's first argument, drivine_regex_group's and drivine_regexp_replace's second. */
    private val host: List<String> = CypherJson.readTree(oracleFile("host.json"))["cases"]!!.flatMap { case -> case["steps"]!!.toList() }
        .mapNotNull { step ->
            when (step["function"]?.textValue()) {
                "regexp" -> step["args"]!![0]
                "drivine_regex_group", "drivine_regexp_replace" -> step["args"]!![1]
                else -> null
            }?.textValue()
        }.distinct()

    @Test
    fun `each pattern the compiler and a full-text search and host_yaml give is inside the grammar the rewrite takes`() {
        val all = DrivinePatterns.compiler.toList() + DrivinePatterns.search.toList() + host.map { "host.yaml" to it }
        assertTrue(host.size > 10, "host.yaml's regular expression steps: ${host.size}")
        assertTrue(all.any { it.second.endsWith("(?=\$|[^[:alnum:]_])") }, "a term looking ahead")
        val refused = all.mapNotNull { (source, pattern) ->
            runCatching { HarmonyPattern(JavaPatterns.java(pattern)); JavaPatterns.compile(pattern) }.exceptionOrNull()?.let { "$source $pattern: ${it.message}" }
        }
        assertEquals(emptyList(), refused)
    }

    @Test
    fun `a full-text search's term looking ahead counts each occurrence`() {
        val term = TextQuery.lookingAhead(TextQuery.parse("kiwi").scored.single())
        assertEquals(3, JavaPatterns.of(term).count("kiwi kiwi,kiwi kiwis akiwi"))
    }
}

/**
 * The patterns the compiler and a full-text search write for SQLite's regular expression functions, from their own constants
 * and functions: for [DrivinePatternsTest], and for the oracle (`org.drivine.cypher.oracle.Regexes`), which writes Java's
 * answers for each.
 */
internal object DrivinePatterns {

    private val sqliteRegex = SqliteTemporalSql { _, body -> body(emptyList()) }

    /** [pattern] as `=~` gives it SQLite's REGEXP ([SqliteCypherCompiler.matches]). */
    fun matched(pattern: String) = SqliteCypherCompiler.MATCHES_BEFORE + pattern + SqliteCypherCompiler.MATCHES_AFTER

    /** The compiler's patterns: toFloat's and toInteger's texts, the temporal kinds and parts, and =~ around some. */
    val compiler: Map<String, String> = buildMap {
        put("DECIMAL_TEXT", SqliteCypherCompiler.DECIMAL_TEXT)
        put("WHOLE_TEXT", SqliteCypherCompiler.WHOLE_TEXT)
        put("NAN_TEXT", SqliteCypherCompiler.NAN_TEXT)
        put("INFINITY_TEXT", SqliteCypherCompiler.INFINITY_TEXT)
        put("NEGATIVE_INFINITY_TEXT", SqliteCypherCompiler.NEGATIVE_INFINITY_TEXT)
        put("CYPHER_NUMBER_TEXT", SqliteCypherCompiler.CYPHER_NUMBER_TEXT)
        for (kind in Temporal.entries) {
            put("Temporal.$kind", sqliteRegex.regex(kind.pattern))
            put("=~ Temporal.$kind", matched(sqliteRegex.regex(kind.pattern)))
            kind.adPattern?.let { put("=~ Temporal.$kind AD", matched(sqliteRegex.regex(it))) }
        }
        put("DURATION_DAYS", sqliteRegex.regex(Temporal.DURATION_DAYS))
        put("DURATION_TIME", sqliteRegex.regex(Temporal.DURATION_TIME))
        put("OFFSET", sqliteRegex.regex(Temporal.OFFSET))
        // =~ around a user's pattern, case-insensitive too, as host.yaml's steps write it.
        put("=~ a|bc", matched("a|bc"))
        put("=~ (?i)[ck].*", matched("(?i)[ck].*"))
        // =~ around the pattern that matches nothing, in place of one with a backreference Java matches nothing with.
        put("=~ NO_MATCH", matched(Patterns.NO_MATCH))
    }

    /** A full-text search's patterns, as SQLite's drivine_regexp_count is given them: each term, and each looking ahead. */
    val search: Map<String, String> = buildMap {
        for (query in listOf("kiwi", "kiw*", "\"of soup\"", "+graph -python", "C++", "domain\\user", "a\\.b", "naïve résumé", "😀 emoji", "x1 y22")) {
            val parsed = TextQuery.parse(query)
            for (term in parsed.scored + parsed.required + parsed.excluded) {
                put("$query: $term", term)
                put("$query: lookingAhead", TextQuery.lookingAhead(term))
            }
        }
    }
}
