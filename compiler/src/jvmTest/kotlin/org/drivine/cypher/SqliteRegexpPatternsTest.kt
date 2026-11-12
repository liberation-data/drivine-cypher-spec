package org.drivine.cypher

import com.fasterxml.jackson.databind.ObjectMapper
import org.drivine.cypher.api.CompileJson
import org.drivine.cypher.oracle.Mutants
import org.drivine.cypher.oracle.Oracle
import org.drivine.cypher.oracle.SqliteSqlPatterns
import org.drivine.cypher.sql.PostgresTemporalSql
import org.drivine.cypher.sql.SqliteTemporalSql
import org.junit.jupiter.api.Test
import java.io.File
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Over every SQLite statement of the corpus and of the mutants, as `./gradlew jvmOracle` makes them: each regular expression
 * the compiler writes into SQLite's `REGEXP`, `drivine_regex_group` or `drivine_regexp_replace` means the same to every
 * library's host, which reads it with its own language's regular expressions (see the spec's `host.yaml`). So it is written
 * with no construct the hosts read otherwise: no `\s`, `\d`, `\w`, `\b` or any other class escape (drivine4j's are Unicode's,
 * Drivine for TypeScript's `\d` and `\w` ASCII's), no `$` (Java's matches before a line break that ends the text), no `.`
 * (Java's matches no U+0085, JavaScript's does), no class within a class or `&&` in one (Java's union and intersection), and
 * no flag group: only `\z` for the text's end, and classes written out, with `\t`, `\n`, `\f`, `\r`, `\xNN` and `\uNNNN`
 * escapes. A user's own pattern, given to `=~`, is the user's: only the compiler's text around it is checked.
 */
class SqliteRegexpPatternsTest {

    private val json = ObjectMapper()

    /** The spec's files, as the test's resources hold them. */
    private val spec = File(requireNotNull(javaClass.getResource("/cypher/queries.yaml")) { "No spec file cypher/queries.yaml" }.toURI()).parentFile

    /** The SQL of each SQLite statement of the requests [requests], its setup too, with its Cypher. */
    private fun sqliteSql(requests: Sequence<String>) = requests.mapNotNull { request ->
        val read = runCatching { json.readTree(request) }.getOrNull()?.takeIf { it.isObject } ?: return@mapNotNull null
        // The compiler's statements: a full-text search's holds no Cypher, and its expressions are drivine4j's, as its library's
        // drivine_regexp_count reads them (FullTextSearchGoldenTest, TextQueryCountTest).
        if (read["store"]?.textValue() != "sqlite" || !read.has("cypher")) return@mapNotNull null
        val answer = json.readTree(CompileJson.compile(request))
        val sql = answer["sql"]?.textValue() ?: return@mapNotNull null
        read["cypher"].textValue() to (listOf(sql) + (answer["setup"]?.map { it.textValue() } ?: emptyList())).joinToString("\n")
    }

    @Test
    fun `no pattern the compiler writes on SQLite leans on a construct the hosts read otherwise, over the corpus and the mutants`() {
        val corpus = sqliteSql(Oracle.corpusRequests(spec).asSequence()).toList()
        val sample = File.createTempFile("mutants", ".jsonl").apply { deleteOnExit() }
        // As jvmOracle samples them: see Oracle.main.
        Mutants.write(Oracle.statements(spec), Oracle.STORES, sample, 10_000, 646, Oracle::answerUnlessClocked)
        val mutants = sqliteSql(sample.readLines().asSequence().map { json.readTree(it)["request"].textValue() }).toList()
        assertTrue(corpus.size > 1_000 && mutants.size > 1_000, "${corpus.size} corpus and ${mutants.size} mutant statements on SQLite")
        val patterns = mutableMapOf<String, String>()
        for ((cypher, sql) in corpus + mutants) for (pattern in patterns(sql)) patterns.putIfAbsent(pattern, cypher)
        println("Patterns the compiler writes on SQLite, as each depth of SQL reads them: ${patterns.size}.")
        assertEquals(emptyMap(), patterns.mapValues { (pattern, _) -> unsafe(pattern) }.filterValues { it.isNotEmpty() }
            .mapKeys { (pattern, _) -> "$pattern (of ${patterns.getValue(pattern)})" })
        // The kinds of temporal values, the parts of a duration, an offset, toFloat's and toInteger's texts, and =~'s ends.
        for (known in listOf("^-?[0-9]{4}-[0-9]{2}-[0-9]{2}\\z", "T((-?[0-9.]+)H)?((-?[0-9.]+)M)?((-?[0-9.]+)S)?\\z", "(Z|[+-][0-9]{2}:?[0-9]{2})\\z",
            "^[+-]00:?00\\z", "^[\\x00-\\x20]*[+-]?NaN[\\x00-\\x20]*\\z", "^(?:", ")\\z")) {
            assertTrue(known in patterns, "the corpus has the pattern $known: ${patterns.keys}")
        }
    }

    @Test
    fun `a construct the hosts read otherwise is found, and one they read alike is not`() {
        assertEquals(listOf("\\s", "$"), unsafe("^\\s*a$"))
        assertEquals(listOf("\\d", "\\w", "\\b", "\\S", "\\p", "\\Z"), unsafe("\\d\\w\\b\\S\\p{L}\\Z"))
        assertEquals(listOf("(?i", "."), unsafe("(?i)a.b"))
        assertEquals(listOf("[:"), unsafe("[[:alnum:]]"))
        // Java reads a class within a class as their union and && as their intersection, JavaScript and Python as characters.
        assertEquals(listOf("["), unsafe("[a[b]]"))
        assertEquals(listOf("&&"), unsafe("[a&&b]"))
        assertEquals(listOf("\\-"), unsafe("a\\-b"))
        assertEquals(emptyList(), unsafe("^[\\t\\n\\x0B\\f\\r \\u3000-]*[+-]?([0-9]+\\.?[0-9]*|[.$][0-9]+){0,17}\\$\\z"))
        assertEquals(emptyList(), unsafe("^(?:"))
    }

    @Test
    fun `SQLite's regex ends a pattern at the text's end in place of its trailing anchor alone, and Postgres's leaves it`() {
        val sqlite = SqliteTemporalSql { _, body -> body(emptyList()) }
        assertEquals("^a\\z", sqlite.regex("^a$"))
        assertEquals("^a\\\\\\z", sqlite.regex("^a\\\\$"), "an escaped backslash before an anchor")
        for (kept in listOf("^a\\$", "^a\\\\\\$", "[$]", "^a\\z", "(a$|b)c", "")) assertEquals(kept, sqlite.regex(kept))
        assertEquals("^a$", PostgresTemporalSql { _, body -> body(emptyList()) }.regex("^a$"))
    }

    @Test
    fun `the patterns of a statement are found at every depth its SQL nests them, and a user's own pattern is left out`() {
        assertEquals(setOf("^a\\z"), patterns("x REGEXP '^a\\z'"))
        assertTrue("^a\\z" in patterns("drivine_cypher('{\"sql\": \"x REGEXP ''^a\\\\z''\"}')"))
        assertEquals(setOf("(Z)\\z", "^b\\z"), patterns("drivine_regexp_replace(drivine_regex_group(f(t, (g(1))), '(Z)\\z', 1), '^b\\z', 'Z')"))
        assertEquals(setOf("^(?:", ")\\z"), patterns("(x REGEXP ('^(?:' || 'a\\s$' || ')\\z'))"))
    }

    private fun patterns(sql: String): Set<String> = SqliteSqlPatterns.of(sql)

    /** The constructs in [pattern] that the hosts read otherwise. */
    private fun unsafe(pattern: String): List<String> {
        val found = mutableListOf<String>()
        var inClass = false
        var at = 0
        while (at < pattern.length) {
            val c = pattern[at]
            when {
                c == '\\' -> {
                    val escaped = pattern.getOrNull(at + 1)
                    if (escaped == null || !(escaped in ESCAPES || escaped in SYNTAX || (inClass && escaped == '-'))) found += "\\${escaped ?: ""}"
                    at++
                }
                inClass -> when {
                    c == ']' -> inClass = false
                    c == '[' -> found += if (pattern.getOrNull(at + 1) == ':') "[:" else "["
                    c == '&' && pattern.getOrNull(at + 1) == '&' -> found += "&&"
                }
                c == '[' -> {
                    inClass = true
                    if (pattern.getOrNull(at + 1) == '^') at++
                    if (pattern.getOrNull(at + 1) == ']') at++
                }
                c == '$' || c == '.' -> found += "$c"
                c == '(' && pattern.getOrNull(at + 1) == '?' && pattern.getOrNull(at + 2) != ':' -> found += pattern.substring(at, minOf(at + 3, pattern.length))
            }
            at++
        }
        return found
    }

    private companion object {
        /** The escapes every host reads alike: of a tab, a line feed, a form feed, a CR, `\xNN`, `\uNNNN`, and `\z`, the text's end. */
        const val ESCAPES = "tnfrxuz"

        /** The characters of a pattern's syntax, which a backslash makes themselves on every host: JavaScript's `u` reads no other. */
        const val SYNTAX = "^$\\.*+?()[]{}|/"
    }
}
