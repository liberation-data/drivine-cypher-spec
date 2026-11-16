package org.drivine.cypher.sql

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * A number compared, ordered, grouped and counted on Oracle by its keys, its double and what tells an integer past 2^53 apart
 * beyond it, where Oracle's JSON compares and sorts one through a decimal `NUMBER`, which holds no double nearer 0 than
 * 1e-130 or past 1e126 and rounds an integer beside a float near 2^53 (liberation-data/drivine-cypher-spec#309). Each key is
 * read from the stored properties by the property's path, never from the property read through its text, which each read
 * would work out again.
 */
class OracleNumberKeysTest {

    private val compiler = OracleCypherCompiler("g", true, true, emptySet())

    private fun double(props: String, path: String) = "JSON_VALUE($props, '$path' RETURNING BINARY_DOUBLE NULL ON ERROR TYPE(STRICT))"

    private fun big(props: String, path: String) =
        "JSON_VALUE($props, '$path?(@.type() == \"number\" && @.abs() > 9007199254740992)' RETURNING NUMBER NULL ON ERROR)"

    /** No key is read from a value read through its text, nor from a column that carries one. */
    private fun assertNoKeyOfARoutedValue(sql: String) {
        assertTrue(!Regex("JSON_VALUE\\(JSON\\(JSON_QUERY\\(JSON_SERIALIZE[^)]*\\)[^']*'[^']*' RETURNING (BINARY_DOUBLE|NUMBER)").containsMatchIn(sql), sql)
        assertTrue(!Regex("JSON_VALUE\\(p\\d+\\.[sc]\\d+, '\\$[^']*' RETURNING (BINARY_DOUBLE|NUMBER)").containsMatchIn(sql), sql)
    }

    @Test
    fun anOrderByAPropertyCarriesItsKeysReadOnceFromTheProperty() {
        val sql = compiler.compile("MATCH (p:Person) RETURN p.karma ORDER BY p.karma DESC LIMIT 100", emptyMap()).sql
        assertTrue("${double("n1.props", "$.karma")} AS s1k1" in sql, sql)
        assertTrue(Regex("NVL\\(${Regex.escape(big("n1.props", "$.karma"))} - \\(.*\\), 0\\) AS s1k2").containsMatchIn(sql), sql)
        // The order reads the keys' columns, after the kind and before the value.
        assertTrue(Regex("ORDER BY DECODE\\(.*\\) DESC, p2\\.s1k1 DESC, p2\\.s1k2 DESC, p2\\.s1 DESC").containsMatchIn(sql), sql)
        // Each key once in the select list.
        assertEquals(1, Regex(Regex.escape("${double("n1.props", "$.karma")} AS")).findAll(sql).count(), sql)
        assertNoKeyOfARoutedValue(sql)
    }

    @Test
    fun minAndMaxOrderByTheKeysOfThePropertyAfterItsKind() {
        val sql = compiler.compile("MATCH (p:Person) RETURN [min(p.karma), max(p.karma)]", emptyMap()).sql
        assertTrue("ASC NULLS LAST, ${double("n1.props", "$.karma")} ASC NULLS LAST, NVL(${big("n1.props", "$.karma")}" in sql, sql)
        assertTrue("DESC NULLS LAST, ${double("n1.props", "$.karma")} DESC NULLS LAST, NVL(${big("n1.props", "$.karma")}" in sql, sql)
        assertNoKeyOfARoutedValue(sql)
    }

    @Test
    fun twoPropertiesAreComparedAndOrderedByTheirKeys() {
        val equal = compiler.compile("MATCH (n:K), (m:K) RETURN n.k = m.k", emptyMap()).sql
        // Null where either is no number, and then compared as JSON.
        assertTrue("COALESCE(${double("n1.props", "$.k")} = ${double("n2.props", "$.k")} AND NVL(${big("n1.props", "$.k")}" in equal, equal)
        assertNoKeyOfARoutedValue(equal)
        val less = compiler.compile("MATCH (n:K), (m:K) RETURN n.k < m.k", emptyMap()).sql
        // NaN is ordered against nothing; the doubles decide, and where they are equal, what an integer past 2^53 is beyond its double.
        assertTrue("WHEN ${double("n1.props", "$.k")} <> ${double("n2.props", "$.k")} THEN (${double("n1.props", "$.k")} < ${double("n2.props", "$.k")} AND ${double("n1.props", "$.k")} IS NOT NAN AND ${double("n2.props", "$.k")} IS NOT NAN) ELSE (NVL(${big("n1.props", "$.k")}" in less, less)
        assertNoKeyOfARoutedValue(less)
    }

    @Test
    fun aGivenFloatAndAListOfThemAreComparedByTheirKeys() {
        val equal = compiler.compile("MATCH (n:K) WHERE n.k = \$g RETURN n.i", mapOf("g" to 1.0e-140)).sql
        assertTrue("${double("n1.props", "$.k")} = ${double("JSON(\$_cy_g EXTENDED)", "$")}" in equal, equal)
        val within = compiler.compile("MATCH (n:K) WHERE n.k IN \$l RETURN n.i", mapOf("l" to listOf(1.0e-140, 1.0e200))).sql
        assertTrue("${double("i2.item", "$")} = ${double("n1.props", "$.k")}" in within, within)
        // Beside a number from -2^53 to 2^53 the doubles alone decide; beside 1e200, a whole number past 2^53, an integer past
        // 2^53 is told apart from the double nearest it too.
        assertTrue(big("n1.props", "$.k") !in equal, equal)
        assertTrue("NVL(${big("n1.props", "$.k")}" in within, within)
        val ordered = compiler.compile("MATCH (n:K) RETURN n.k < \$g", mapOf("g" to 1.0e-140)).sql
        assertTrue("CASE WHEN ${double("n1.props", "$.k")} IS NOT NULL AND ${double("JSON(\$_cy_g EXTENDED)", "$")} IS NOT NULL THEN (${double("n1.props", "$.k")} < " in ordered, ordered)
        assertTrue(big("n1.props", "$.k") !in ordered, ordered)
        // A text given is compared as JSON, which compares texts as Cypher does: no number key is read.
        val text = compiler.compile("MATCH (n:K) WHERE n.k IN \$l RETURN n.i", mapOf("l" to listOf("a", "b"))).sql
        assertTrue("BINARY_DOUBLE NULL ON ERROR TYPE(STRICT)" !in text, text)
    }

    @Test
    fun aGroupIsKeyedByTheKeysAndAnOrderOfItReadsThemFromTheirColumns() {
        val sql = compiler.compile("MATCH (n:K) RETURN n.k AS k, count(*) AS c ORDER BY k", emptyMap()).sql
        assertTrue(Regex("GROUP BY JSON\\(.*\\), ${Regex.escape(double("n1.props", "$.k"))}, NVL\\(").containsMatchIn(sql), sql)
        assertTrue("${double("n1.props", "$.k")} AS c1k1" in sql, sql)
        assertTrue(Regex("ORDER BY DECODE\\(.*\\), p\\d+\\.c1k1, p\\d+\\.c1k2, p\\d+\\.c1$").containsMatchIn(sql), sql)
        assertNoKeyOfARoutedValue(sql)
    }

    @Test
    fun countDistinctCountsANumberByItsKeysAndEachNaNApart() {
        val sql = compiler.compile("MATCH (n:K) RETURN count(DISTINCT n.k)", emptyMap()).sql
        assertTrue("count(DISTINCT CASE WHEN ${double("n1.props", "$.k")} IS NULL THEN CASE WHEN" in sql, sql)
        assertTrue("count(DISTINCT CASE WHEN ${double("n1.props", "$.k")} IS NAN THEN NULL WHEN NVL(${big("n1.props", "$.k")}" in sql, sql)
        assertTrue("count(CASE WHEN ${double("n1.props", "$.k")} IS NAN THEN 1 END)" in sql, sql)
        assertNoKeyOfARoutedValue(sql)
    }

    @Test
    fun twoIntegersTheStatementMakesAreComparedAsJsonWithNoKey() {
        for (cypher in listOf(
            "MATCH (n:K) RETURN size(n.tags) < 3",
            "MATCH (n:K) RETURN size(n.tags) = \$g",
            "MATCH (n:K) WITH n, 2 AS two RETURN size(n.tags) >= two",
        )) {
            val sql = compiler.compile(cypher, mapOf("g" to 2L)).sql
            assertTrue("BINARY_DOUBLE NULL ON ERROR TYPE(STRICT)" !in sql, "$cypher: $sql")
        }
        // A property is no integer the statement makes: beside one, the keys are read.
        val property = compiler.compile("MATCH (n:K) RETURN n.k < 3", emptyMap()).sql
        assertTrue("BINARY_DOUBLE NULL ON ERROR TYPE(STRICT)" in property, property)
    }
}
