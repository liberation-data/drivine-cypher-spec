package org.drivine.cypher

import com.fasterxml.jackson.databind.ObjectMapper
import org.drivine.cypher.api.CompileJson
import org.drivine.cypher.oracle.Mutants
import org.drivine.cypher.oracle.Oracle
import org.drivine.cypher.oracle.OracleReads
import org.junit.jupiter.api.Test
import java.io.File
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Over every Oracle statement of the corpus and of the mutants, as `./gradlew jvmOracle` makes them (the same requests, the
 * same sample of mutants), compiled here: each property read that may give a text past 4,000 bytes takes the CLOB route. No
 * read of a stored property, an unwound or carried item, a write's row or a `reduce()` step's context, or of a value worked
 * out of one, by `JSON_QUERY … RETURNING JSON`, but where [OracleReads.SAFE] proves it gives no such text.
 */
class OracleStoredReadRouteTest {

    private val json = ObjectMapper()

    /** The spec's files, as the test's resources hold them. */
    private val spec = File(requireNotNull(javaClass.getResource("/cypher/queries.yaml")) { "No spec file cypher/queries.yaml" }.toURI()).parentFile

    /** The Oracle statements' SQL, each with its Cypher, of the requests [requests]. */
    private fun oracleSql(requests: Sequence<String>) = requests.mapNotNull { request ->
        val read = runCatching { json.readTree(request) }.getOrNull()?.takeIf { it.isObject } ?: return@mapNotNull null
        // The compiler's statements: a full-text search's, which holds no Cypher, is FullTextSearchGoldenTest's.
        if (read["store"]?.textValue() != "oracle" || !read.has("cypher")) return@mapNotNull null
        val sql = json.readTree(CompileJson.compile(request))["sql"]?.textValue() ?: return@mapNotNull null
        read["cypher"].textValue() to sql
    }

    @Test
    fun `every long-text-capable property read on Oracle takes the CLOB route, over the corpus and the mutants`() {
        val corpus = oracleSql(Oracle.corpusRequests(spec).asSequence()).toList()
        val sample = File.createTempFile("mutants", ".jsonl").apply { deleteOnExit() }
        // As jvmOracle samples them: see Oracle.main.
        Mutants.write(Oracle.statements(spec), Oracle.STORES, sample, 10_000, 646, Oracle::answerUnlessClocked)
        val mutants = oracleSql(sample.readLines().asSequence().map { json.readTree(it)["request"].textValue() }).toList()
        assertTrue(corpus.size > 1_000 && mutants.size > 1_000, "${corpus.size} corpus and ${mutants.size} mutant statements on Oracle")
        val found = (corpus.map { "corpus" to it } + mutants.map { "mutants" to it }).mapNotNull { (from, statement) ->
            val unsafe = OracleReads.unsafe(statement.second)
            if (unsafe.isEmpty()) null else Triple(from, statement.first, unsafe.map { "${it.source} ${it.path}" })
        }
        val reads = found.sumOf { it.third.size }
        println("Oracle property reads off the CLOB route: $reads, in ${found.size} of ${corpus.size + mutants.size} statements.")
        assertEquals(
            emptyList(), found.take(40).map { (from, cypher, unsafe) -> "$from: $cypher\n    ${unsafe.distinct().joinToString("; ")}" },
            "$reads reads off the CLOB route in ${found.size} of ${corpus.size + mutants.size} statements",
        )
    }
}
