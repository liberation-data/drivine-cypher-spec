package org.drivine.cypher

import com.fasterxml.jackson.databind.ObjectMapper
import org.drivine.cypher.sql.OracleCypherCompiler
import org.junit.jupiter.api.Test
import java.io.File
import kotlin.test.assertTrue

/**
 * Oracle's number keys (drivine-cypher-spec#309) read a value's integer by asking its kind apart, as an earlier form caused
 * an Oracle server crash (ORA-00600). A list or a map is compared with no number key at all.
 */
class OracleNumberKeyPathsTest {

    @Test
    fun aListOrAMapComparedWithItselfReadsNoNumberKey() {
        val compiler = OracleCypherCompiler("g")
        for (cypher in listOf(
            "MATCH (:Person {name: 'Keanu'})-[r1:KNOWS]->() OPTIONAL MATCH (:Person {name: 'Hugo'})-[r2:KNOWS]->() WITH r1, r2, [r1, r2] AS a, {x: r2} AS m RETURN a = a",
            "MATCH (:Person {name: 'Keanu'})-[r1:KNOWS]->() OPTIONAL MATCH (:Person {name: 'Hugo'})-[r2:KNOWS]->() WITH r1, r2, [r1, r2] AS a, {x: r2} AS m RETURN m = m",
            "MATCH (n:K) WITH [n.k, 1] AS l, {a: n.k} AS m RETURN l = l, m = m, l = [1, 2]",
        )) {
            val sql = compiler.compile(cypher, emptyMap()).sql
            assertTrue("BINARY_DOUBLE NULL ON ERROR TYPE(STRICT)" !in sql && "RETURNING NUMBER NULL ON ERROR TYPE(STRICT)" !in sql, "$cypher: $sql")
        }
    }

    @Test
    fun noStatementOfTheCorpusFiltersANumberKeyByAPath() {
        // Every statement in the spec's files, as Oracle compiles it (`./gradlew jvmOracle`'s corpus): no `abs()` in a JSON path,
        // which that earlier form of a number key wrote.
        val corpus = File(requireNotNull(System.getenv("DRIVINE_ORACLE")) { "DRIVINE_ORACLE names no directory: run ./gradlew jvmOracle" }, "corpus.jsonl")
        val json = ObjectMapper()
        var oracle = 0
        corpus.useLines { lines ->
            lines.forEach { line ->
                val entry = json.readTree(line)
                if (!entry["request"].asText().contains("\"store\":\"oracle\"")) return@forEach
                oracle++
                val answer = entry["answer"].asText()
                assertTrue("abs()" !in answer, "${entry["request"].asText()}: $answer")
            }
        }
        assertTrue(oracle > 1000, "the corpus holds $oracle statements on Oracle")
    }
}
