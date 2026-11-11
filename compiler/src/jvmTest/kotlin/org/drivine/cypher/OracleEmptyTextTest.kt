package org.drivine.cypher

import org.drivine.cypher.sql.OracleCypherCompiler
import org.drivine.cypher.sql.PostgresCypherCompiler
import org.drivine.cypher.sql.SqliteCypherCompiler
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * On Oracle, an empty text is null as a `VARCHAR2` and as a CLOB, so `STARTS WITH`, `ENDS WITH` and `CONTAINS` gave null
 * where either text was empty, and `size('')` null (drivine4j#416). Where both reads give null, the texts' lengths, read as
 * JSON, answer as Cypher does; a text's size is 0 where both its reads are null. A function that makes a text, and `+`,
 * gave null where it made the empty text: where both its reads are null, it gives the empty text where each text's length
 * is read and each number is one.
 */
class OracleEmptyTextTest {

    private fun sql(cypher: String) = OracleCypherCompiler("g").compile(cypher, mapOf("t" to "ab", "e" to "", "n" to 1, "p" to mapOf("q" to null))).sql

    private fun length(json: String) = "CASE WHEN JSON_VALUE($json, '\$.type()') = 'string' THEN JSON_VALUE($json, '\$.length()' RETURNING NUMBER) END"

    private fun answered(a: String, b: String) = "CASE WHEN $b = 0 AND $a IS NOT NULL THEN TRUE WHEN $a = 0 AND $b IS NOT NULL THEN FALSE END)"

    @Test
    fun `STARTS WITH, ENDS WITH and CONTAINS read the texts' lengths last, where the VARCHAR2s and the CLOBs give null`() {
        for (operator in listOf("STARTS WITH", "ENDS WITH", "CONTAINS")) {
            val sql = sql("RETURN \$t $operator \$e")
            assertTrue(sql.contains("SELECT COALESCE(("), sql)
            // After the VARCHAR2s' answer and the CLOBs', the third value of the COALESCE.
            assertTrue(sql.contains(", ${answered(length("JSON(\$_cy_t)"), length("JSON(\$_cy_e)"))} AS c1"), sql)
            assertEquals(1, Regex("COALESCE\\(").findAll(sql).count(), sql)
        }
    }

    @Test
    fun `a stored text's length is read by its own path`() {
        val sql = sql("MATCH (a:N) WHERE a.name STARTS WITH '' RETURN a.name")
        val stored = "CASE WHEN JSON_VALUE(n1.props, '\$.name.type()') = 'string' THEN JSON_VALUE(n1.props, '\$.name.length()' RETURNING NUMBER) END"
        assertTrue(sql.contains(answered(stored, length("JSON('\"\"')"))), sql)
        assertTrue(!sql.contains("JSON_QUERY(n1.props, '\$.name' RETURNING JSON)"), sql)
    }

    @Test
    fun `beside a long value, a stored text's length is bound beside its VARCHAR2 and its CLOB, read by its own path`() {
        val sql = sql("MATCH (a:N) RETURN toUpper(trim(coalesce(\$p.q, 'ab'))) CONTAINS a.name")
        // Bound in the subquery, as #370 has a property beside a long value read, and read by its column.
        val bound = Regex("CASE WHEN JSON_VALUE\\(n1.props, '\\\$.name.type\\(\\)'\\) = 'string' THEN JSON_VALUE\\(n1.props, '\\\$.name.length\\(\\)' RETURNING NUMBER\\) END AS (v\\d+)")
        val column = bound.find(sql)?.groupValues?.get(1)
        assertTrue(column != null, sql)
        assertTrue(Regex("CASE WHEN sh\\d+\\.$column = 0 AND ").containsMatchIn(sql), sql)
        assertTrue(Regex(" = 0 AND sh\\d+\\.$column IS NOT NULL THEN FALSE END\\)").containsMatchIn(sql), sql)
    }

    @Test
    fun `beside a long value, a function that makes a text reads a stored text's bound length for the empty text`() {
        val sql = sql("MATCH (a:N) RETURN replace(toUpper(trim(coalesce(\$p.q, 'ab'))), a.name, '')")
        val bound = Regex("CASE WHEN JSON_VALUE\\(n1.props, '\\\$.name.type\\(\\)'\\) = 'string' THEN JSON_VALUE\\(n1.props, '\\\$.name.length\\(\\)' RETURNING NUMBER\\) END AS (v\\d+)")
        val column = bound.find(sql)?.groupValues?.get(1)
        assertTrue(column != null, sql)
        assertTrue(Regex("AND sh\\d+\\.$column IS NOT NULL AND .{0,200}? IS NOT NULL THEN JSON\\('\"\"'\\) END\\)").containsMatchIn(sql), sql)
    }

    @Test
    fun `a text's size is 0 where its VARCHAR2 and its CLOB are both null`() {
        val sql = sql("MATCH (a:N) RETURN size(a.name)")
        assertTrue(sql.contains("WHEN 'string' THEN JSON_SCALAR(COALESCE(LENGTH(JSON_VALUE(n1.props, '\$.name')), LENGTH(JSON_VALUE(n1.props, '\$.name' RETURNING CLOB)), 0)) END"), sql)
    }

    @Test
    fun `=~ and Postgres and SQLite's searches read no length, and a text function reads its texts' lengths last`() {
        assertTrue(!sql("RETURN \$e =~ '.*'").contains(".length()' RETURNING NUMBER"))
        // A function that makes a text gives the empty text where its texts are all texts and its SQL gives null (stage 2).
        for (cypher in listOf("RETURN toUpper(\$e)", "RETURN left(\$e, 1)", "RETURN split(\$e, \$t)")) {
            val sql = sql(cypher)
            assertTrue(sql.contains(".length()' RETURNING NUMBER") && sql.contains("THEN JSON('"), "$cypher: $sql")
        }
        for (compiler in listOf(PostgresCypherCompiler("g"), SqliteCypherCompiler("g"))) {
            for (cypher in listOf("RETURN \$t STARTS WITH \$e", "RETURN toUpper(\$e)", "RETURN left(\$e, \$n)", "RETURN split(\$e, \$t)", "RETURN \$e + \$e")) {
                assertTrue(!compiler.compile(cypher, mapOf("t" to "ab", "e" to "", "n" to 1)).sql.contains("length()"), cypher)
            }
        }
    }

    @Test
    fun `a function that makes a text gives the empty text last, where each text's length and each count is read`() {
        val e = length("JSON(\$_cy_e)")
        val t = length("JSON(\$_cy_t)")
        // A count is read as the function reads it, so left('', null) is still null.
        val n = "CAST(1 AS INTEGER) IS NOT NULL"
        for ((cypher, last) in listOf(
            "RETURN toUpper(\$e)" to "CASE WHEN $e IS NOT NULL THEN JSON('\"\"') END",
            "RETURN left(\$e, \$n)" to "CASE WHEN $e IS NOT NULL AND $n THEN JSON('\"\"') END",
            "RETURN substring(\$e, \$n, \$n)" to "CASE WHEN $e IS NOT NULL AND $n AND $n THEN JSON('\"\"') END",
            "RETURN replace(\$e, \$t, \$e)" to "CASE WHEN $e IS NOT NULL AND $t IS NOT NULL AND $e IS NOT NULL THEN JSON('\"\"') END",
            // split() makes a list of the empty text, and only of an empty text.
            "RETURN split(\$e, \$t)" to "CASE WHEN $e = 0 AND $e IS NOT NULL AND $t IS NOT NULL THEN JSON('[\"\"]') END",
            // toString() of the empty text, and of no other kind.
            "RETURN toString(\$e)" to "CASE WHEN JSON_VALUE(JSON(\$_cy_e), '\$.type()') = 'string' THEN JSON('\"\"') END",
        )) {
            val sql = sql(cypher)
            assertTrue(sql.contains(", $last) AS c1"), "$cypher: $sql")
        }
    }

    @Test
    fun `a count out of range beside the empty text still fails`() {
        for (cypher in listOf("UNWIND [''] AS x RETURN left(x, -1)", "UNWIND [''] AS x UNWIND [-1] AS n RETURN substring(x, 0, n)")) {
            val sql = sql(cypher)
            // The empty text's fallback, read last and alone reading a length, reads the count beside it and fails as the function does.
            assertTrue(sql.substringAfter("'\$.length()' RETURNING NUMBER)", "").contains("takes a length from 0 to 2147483647"), "$cypher: $sql")
        }
    }

    @Test
    fun `two empty texts added are the empty text, tested first, and a list reversed reads no length`() {
        assertTrue(sql("RETURN \$e + \$e").contains(
            "CASE WHEN JSON_VALUE(JSON(\$_cy_e), '\$.length()') = 0 AND JSON_VALUE(JSON(\$_cy_e), '\$.length()') = 0 THEN JSON('\"\"') WHEN "))
        val reversed = sql("WITH [\$t] AS l RETURN reverse(l)")
        assertTrue(reversed.substringAfter("WHEN 'array' THEN").let { it != reversed && !it.contains("length()") }, reversed)
    }
}
