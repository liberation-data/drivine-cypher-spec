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
 * JSON, answer as Cypher does; a text's size is 0 where both its reads are null.
 */
class OracleEmptyTextTest {

    private fun sql(cypher: String) = OracleCypherCompiler("g").compile(cypher, mapOf("t" to "ab", "e" to "", "p" to mapOf("q" to null))).sql

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
            assertTrue(!compiler.compile("RETURN \$t STARTS WITH \$e", mapOf("t" to "ab", "e" to "")).sql.contains("length()"))
        }
    }
}
