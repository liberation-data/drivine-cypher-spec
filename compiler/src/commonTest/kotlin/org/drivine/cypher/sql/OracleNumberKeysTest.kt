package org.drivine.cypher.sql

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * A number compared, ordered and counted on Oracle by its keys, its double and what tells an integer past 2^53 apart beyond
 * it, where Oracle's JSON compares and sorts one through a decimal `NUMBER`, which holds no double nearer 0 than 1e-130 or
 * past 1e126 and rounds an integer beside a float near 2^53 (liberation-data/drivine-cypher-spec#309). A group and a
 * `DISTINCT` compare a number as JSON, as before (drivine-cypher-spec#315). Each key is read from the stored properties by
 * the property's path, never from the property read through its text, which each read would work out again; but a value a
 * group is keyed by has its keys read of it, after the `GROUP BY`, as no other read of it is a group's key (ORA-00979).
 */
class OracleNumberKeysTest {

    private val compiler = OracleCypherCompiler("g", true, true, emptySet())

    private fun double(props: String, path: String) = "JSON_VALUE($props, '$path' RETURNING BINARY_DOUBLE NULL ON ERROR TYPE(STRICT))"

    /** The value at [path] read as a `NUMBER` where it is one, its kind asked apart (see OracleNumberKeyPathsTest). */
    private fun exactOf(n: String, d: String) =
        "CASE WHEN ABS($n) > 9007199254740992 THEN $n - (CAST(FLOOR($d / 4294967296) AS NUMBER) * 4294967296 + CAST($d - FLOOR($d / 4294967296) * 4294967296 AS NUMBER)) ELSE 0 END"

    private fun big(props: String, path: String) =
        "CASE WHEN JSON_VALUE($props, '$path.type()') = 'number' THEN JSON_VALUE($props, '$path' RETURNING NUMBER NULL ON ERROR TYPE(STRICT)) END"

    /** No key is read from a value read through its text, nor from a column that carries one. */
    private fun assertNoKeyOfARoutedValue(sql: String) {
        assertTrue(!Regex("JSON_VALUE\\(JSON\\(JSON_QUERY\\(JSON_SERIALIZE[^)]*\\)[^']*'[^']*' RETURNING (BINARY_DOUBLE|NUMBER)").containsMatchIn(sql), sql)
        assertTrue(!Regex("JSON_VALUE\\([pw]\\d+\\.[sc]\\d+, '\\$[^']*' RETURNING (BINARY_DOUBLE|NUMBER)").containsMatchIn(sql), sql)
    }

    @Test
    fun anOrderByAPropertyCarriesItsKeysReadOnceFromTheProperty() {
        val sql = compiler.compile("MATCH (p:Person) RETURN p.karma ORDER BY p.karma DESC LIMIT 100", emptyMap()).sql
        assertTrue("${double("n1.props", "$.karma")} AS s1k1" in sql, sql)
        // The double and the NUMBER, each read once, and the exact key worked out of their columns in the order.
        assertTrue("${big("n1.props", "$.karma")} AS s1k2" in sql, sql)
        // The order reads the keys' columns, after the kind and before the value.
        assertTrue(Regex("ORDER BY DECODE\\(.*\\) DESC, p2\\.s1k1 DESC, ${Regex.escape(exactOf("p2.s1k2", "p2.s1k1"))} DESC, p2\\.s1 DESC").containsMatchIn(sql), sql)
        // Each key once in the select list.
        assertEquals(1, Regex(Regex.escape("${double("n1.props", "$.karma")} AS")).findAll(sql).count(), sql)
        assertNoKeyOfARoutedValue(sql)
    }

    @Test
    fun minAndMaxOrderByTheKeysOfThePropertyAfterItsKind() {
        val sql = compiler.compile("MATCH (p:Person) RETURN [min(p.karma), max(p.karma)]", emptyMap()).sql
        // The kind, then the double alone; of one double, MIN or MAX of the JSON, as before (an integer past 2^53 and a float of its
        // double are not told apart: drivine-cypher-spec#315).
        assertTrue("ASC NULLS LAST, ${double("n1.props", "$.karma")} ASC NULLS LAST)" in sql, sql)
        assertTrue("DESC NULLS LAST, ${double("n1.props", "$.karma")} DESC NULLS LAST)" in sql, sql)
        assertTrue(Regex("KEEP \\([^)]*RETURNING NUMBER").find(sql) == null && "THEN ${big("n1.props", "$.karma")} END" !in sql, sql)
        // After its kind, each KEEP's order names the property's double once.
        Regex("KEEP \\(DENSE_RANK FIRST ORDER BY (DECODE\\(.*?'float', '5'\\)) (ASC|DESC) NULLS LAST, (.*?)\\)(, MAX| NULL ON NULL)").findAll(sql).toList().also { assertEquals(2, it.size, sql) }.forEach { keep ->
            val keys = keep.groupValues[3]
            assertEquals(1, Regex("JSON_VALUE\\(n1\\.props, '\\$\\.karma(\\.type\\(\\))?'").findAll(keys).count(), keys)
        }
        assertNoKeyOfARoutedValue(sql)
    }

    @Test
    fun twoPropertiesAreComparedAndOrderedByTheirKeys() {
        val equal = compiler.compile("MATCH (n:K), (m:K) RETURN n.k = m.k", emptyMap()).sql
        // Null where either is no number, and then compared as JSON.
        assertTrue("COALESCE(${double("n1.props", "$.k")} = ${double("n2.props", "$.k")} AND CASE WHEN ABS(${big("n1.props", "$.k")}" in equal, equal)
        assertNoKeyOfARoutedValue(equal)
        val less = compiler.compile("MATCH (n:K), (m:K) RETURN n.k < m.k", emptyMap()).sql
        // NaN is ordered against nothing; the doubles decide, and where they are equal, what an integer past 2^53 is beyond its double.
        assertTrue("WHEN ${double("n1.props", "$.k")} <> ${double("n2.props", "$.k")} THEN (${double("n1.props", "$.k")} < ${double("n2.props", "$.k")} AND ${double("n1.props", "$.k")} IS NOT NAN AND ${double("n2.props", "$.k")} IS NOT NAN) ELSE (CASE WHEN ABS(${big("n1.props", "$.k")}" in less, less)
        assertNoKeyOfARoutedValue(less)
    }

    @Test
    fun aCaseSubjectAndARelationshipsPropertyAreComparedByTheirKeys() {
        // A simple CASE compares its subject with each alternative as = does: by the keys, read from the properties.
        val case = compiler.compile("MATCH (n:K), (m:K) RETURN CASE n.k WHEN m.k THEN 1 END", emptyMap()).sql
        assertTrue("COALESCE(${double("n1.props", "$.k")} = ${double("n2.props", "$.k")} AND CASE WHEN ABS(${big("n1.props", "$.k")}" in case, case)
        assertNoKeyOfARoutedValue(case)
        // A relationship's property is keyed as a node's is, from its own properties.
        val rel = compiler.compile("MATCH ()-[r:R]->() RETURN r.k ORDER BY r.k", emptyMap()).sql
        assertTrue(Regex("JSON_VALUE\\(e\\d+\\.props, '\\$\\.k' RETURNING BINARY_DOUBLE NULL ON ERROR TYPE\\(STRICT\\)\\) AS s1k1").containsMatchIn(rel), rel)
        assertNoKeyOfARoutedValue(rel)
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
        assertTrue("CASE WHEN ABS(${big("n1.props", "$.k")}" in within, within)
        val ordered = compiler.compile("MATCH (n:K) RETURN n.k < \$g", mapOf("g" to 1.0e-140)).sql
        assertTrue("CASE WHEN ${double("n1.props", "$.k")} IS NOT NULL AND ${double("JSON(\$_cy_g EXTENDED)", "$")} IS NOT NULL THEN (${double("n1.props", "$.k")} < " in ordered, ordered)
        assertTrue(big("n1.props", "$.k") !in ordered, ordered)
        // A text given is compared as JSON, which compares texts as Cypher does: no number key is read.
        val text = compiler.compile("MATCH (n:K) WHERE n.k IN \$l RETURN n.i", mapOf("l" to listOf("a", "b"))).sql
        assertTrue("BINARY_DOUBLE NULL ON ERROR TYPE(STRICT)" !in text, text)
    }

    @Test
    fun aGroupAndADistinctReadNoNumberKey() {
        // A group or a DISTINCT compares a number as JSON, as before: keyed too, an aggregate over a few walks took a third as long
        // again and more (drivine-cypher-spec#315 keeps 1e200 and -1e200 one group).
        for (cypher in listOf(
            "MATCH (n:K) RETURN n.k AS k, count(*) AS c", "MATCH (n:K) RETURN DISTINCT n.k", "MATCH (n:K) WITH DISTINCT n.k AS k RETURN k",
            "MATCH (n:K) WITH n.k AS k, count(*) AS c RETURN k, c",
        )) {
            val sql = compiler.compile(cypher, emptyMap()).sql
            assertTrue("RETURNING BINARY_DOUBLE NULL ON ERROR TYPE(STRICT)" !in sql && "RETURNING NUMBER NULL ON ERROR TYPE(STRICT)" !in sql, "$cypher: $sql")
        }
        val grouped = compiler.compile("MATCH (n:K) RETURN n.k AS k, count(*) AS c", emptyMap()).sql
        assertTrue(Regex("GROUP BY JSON\\(JSON_QUERY\\(JSON_SERIALIZE\\(JSON_TRANSFORM\\(n1\\.props, KEEP '\\$\\.k'\\)[^\\n]*\\n\\) p2").containsMatchIn(grouped), grouped)
    }

    @Test
    fun countDistinctCountsANumberByItsKeysAndEachNaNApart() {
        val sql = compiler.compile("MATCH (n:K) RETURN count(DISTINCT n.k)", emptyMap()).sql
        assertTrue("count(DISTINCT CASE WHEN ${double("n1.props", "$.k")} IS NULL THEN CASE WHEN" in sql, sql)
        assertTrue("count(DISTINCT CASE WHEN ${double("n1.props", "$.k")} IS NAN OR ABS(${big("n1.props", "$.k")}) > 9007199254740992 THEN NULL ELSE ${double("n1.props", "$.k")} END)" in sql, sql)
        assertTrue("count(DISTINCT CASE WHEN ABS(${big("n1.props", "$.k")}) > 9007199254740992 THEN ${big("n1.props", "$.k")} END)" in sql, sql)
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

    @Test
    fun anIdIsKeyedByItsColumnAndTwoIdsAreComparedAsJson() {
        // Read with JSON_VALUE of JSON_SCALAR of a key column, the statement gave no rows on Oracle (writes.yaml, "An id is not
        // used again by a later statement"): an id's keys are its column's.
        for (cypher in listOf(
            "MATCH (k:CyKeep), (:CyWrite {k: 'a'})-[t:T]->(c:CyWrite {k: 'c'}) RETURN k.node = elementId(c), k.rel = elementId(t)",
            "MATCH (n:CyWrite {id: 'a'}), (b:CyWrite {id: 'b'}) SET n.p = elementId(b) RETURN n.p = elementId(b)",
        )) {
            val sql = compiler.compile(cypher, emptyMap()).sql
            assertTrue(!Regex("JSON_VALUE\\(JSON_SCALAR\\(\\w+\\.(nid|eid)\\), '\\$(\\?[^']*)?' RETURNING (BINARY_DOUBLE|NUMBER)").containsMatchIn(sql), sql)
            assertTrue(Regex("= TO_BINARY_DOUBLE\\(\\w+\\.(nid|eid)\\)").containsMatchIn(sql), sql)
        }
        // An id carried by a WITH is an integer still: two ids are compared as JSON, with no key read.
        val carried = compiler.compile(
            "MATCH (a:CyWrite) WITH a, elementId(a) AS old DELETE a FOREACH (x IN [1] | MERGE (:CyWrite {num: 5})) WITH old MATCH (b:CyWrite) RETURN b.num, old = elementId(b)",
            emptyMap(),
        ).sql
        assertTrue(Regex("\\w+\\.c1 = JSON_SCALAR\\(n\\d+\\.nid\\)").containsMatchIn(carried) && "TO_BINARY_DOUBLE(n" !in carried, carried)
        // An id one query of a UNION in a CALL returns, beside a property another returns, is no integer the statement makes:
        // compared as JSON, the property 1e-200 would be equal to 0.
        for (cypher in listOf(
            "MATCH (m:K) CALL { MATCH (n:K) RETURN id(n) AS a UNION ALL MATCH (n:K) RETURN n.k AS a } RETURN a = 0",
            "MATCH (m:K) CALL { MATCH (n:K) RETURN n.k AS a UNION ALL MATCH (n:K) RETURN id(n) AS a } RETURN a = 0",
        )) {
            val sql = compiler.compile(cypher, emptyMap()).sql
            assertTrue(Regex("JSON_VALUE\\(c\\d+\\.c1, '\\$' RETURNING BINARY_DOUBLE NULL ON ERROR TYPE\\(STRICT\\)\\) = ").containsMatchIn(sql), "$cypher: $sql")
        }
    }

    @Test
    fun countDistinctOfAListItemCountsAStoredNaNOnce() {
        // Keyed as any value, but a NaN an item of a stored list holds is counted once where the statement works out none, as
        // collect(DISTINCT …) keeps it (writes.yaml, "MERGE of a null or NaN property").
        val sql = compiler.compile("MATCH (n:CyWrite) WHERE n.l IS NOT NULL RETURN count(DISTINCT n.l[0]), size(collect(DISTINCT n.l[0]))", emptyMap()).sql
        assertTrue("IS NAN THEN 1 END" !in sql && "BINARY_DOUBLE NULL ON ERROR TYPE(STRICT)" in sql, sql)
        // Given NaN, each is counted apart, as before.
        val given = compiler.compile("MATCH (n:CyWrite) WHERE n.l IS NOT NULL AND n.x <> \$nan RETURN count(DISTINCT n.l[0])", mapOf("nan" to Double.NaN)).sql
        assertTrue("IS NAN THEN 1 END" in given, given)
    }

    @Test
    fun aValueAWithCarriesCarriesItsKeysAsColumnsOfTheirOwn() {
        // Read once by the property's path in the WITH's query, and read of those columns after it: never of the WITH's column,
        // a property read through its text, which each read would work out again.
        for (cypher in listOf(
            "MATCH (n:K) WITH n.k AS k RETURN k ORDER BY k", "MATCH (n:K) WITH n.k AS k RETURN min(k)",
            "MATCH (n:K) WITH n.k AS k RETURN k = 0", "MATCH (n:K) WITH n.k AS k, n.j AS j RETURN k < j",
        )) {
            val sql = compiler.compile(cypher, emptyMap()).sql
            assertTrue("${double("n1.props", "$.k")} AS c1k1" in sql && Regex("w\\d+\\.c1k1").containsMatchIn(sql), "$cypher: $sql")
            // The property's keys read in the WITH's own query alone, the one that reads the node.
            val within = sql.substring(sql.lastIndexOf("SELECT ", sql.indexOf("FROM g_node n1")), sql.indexOf("FROM g_node n1"))
            assertEquals(Regex(Regex.escape(double("n1.props", "$.k"))).findAll(sql).count(), Regex(Regex.escape(double("n1.props", "$.k"))).findAll(within).count(), "$cypher: $sql")
            assertNoKeyOfARoutedValue(sql)
        }
        // Grouped after the WITH as JSON, by the value alone; the carried keys are the double and the NUMBER, of which the exact key
        // is worked out where it is compared.
        val grouped = compiler.compile("MATCH (n:K) WITH n.k AS k RETURN k, count(*)", emptyMap()).sql
        assertTrue(Regex("GROUP BY w\\d+\\.c1\\n").containsMatchIn(grouped), grouped)
        val compared = compiler.compile("MATCH (n:K) WITH n.k AS k, n.j AS j RETURN k < j", emptyMap()).sql
        assertTrue("${big("n1.props", "$.k")} AS c1k2" in compared && exactOf("w3.c1k2", "w3.c1k1") in compared, compared)
    }

    @Test
    fun countDistinctAndMinAndMaxOfAnyValueReadItsKeysWhereItIs() {
        val unwound = compiler.compile("UNWIND [1e-200, 0] AS x RETURN count(DISTINCT x)", emptyMap()).sql
        assertTrue("count(DISTINCT CASE WHEN ABS(" in unwound && double("u1.item", "$") in unwound, unwound)
        // No NaN given or worked out: a NaN an unwound item holds is counted once, as collect(DISTINCT …) keeps it.
        assertTrue("IS NAN THEN 1 END" !in unwound, unwound)
        val worked = compiler.compile("MATCH (n:K) RETURN max(n.k + 0)", emptyMap()).sql
        assertTrue(Regex("KEEP \\(DENSE_RANK FIRST ORDER BY .* DESC NULLS LAST, JSON_VALUE\\(.* RETURNING BINARY_DOUBLE NULL ON ERROR TYPE\\(STRICT\\)\\) DESC NULLS LAST\\)").containsMatchIn(worked), worked)
        // Under the aggregate, nothing is bound in a subquery.
        assertTrue(!Regex("KEEP \\(DENSE_RANK FIRST ORDER BY [^)]*\\(SELECT").containsMatchIn(worked), worked)
    }

    @Test
    fun aQueryWhoseColumnsNothingReadsCarriesNoKeys() {
        // A final RETURN of a value a WITH carries with its keys gives the value alone.
        val returned = compiler.compile("MATCH (n:K) WITH n.k AS k RETURN k", emptyMap()).sql
        assertTrue(!Regex("w\\d+\\.c1k1").containsMatchIn(returned), returned)
        // A COUNT { … }'s RETURN is counted, its columns read by nothing.
        val counted = compiler.compile("MATCH (n:K) RETURN COUNT { MATCH (m:K) RETURN m.k }", emptyMap()).sql
        assertTrue("BINARY_DOUBLE NULL ON ERROR TYPE(STRICT)" !in counted, counted)
    }

    @Test
    fun eachQueryOfAUnionInACallGivesTheSameColumns() {
        // A value a WITH carries with its keys, returned by one query of the UNION beside a value with none in the other: each
        // query gives its value alone, where Oracle refuses queries of a UNION with different numbers of columns (ORA-01789).
        for (cypher in listOf(
            "MATCH (n:K) WITH n.k AS k CALL { WITH k RETURN k AS a UNION WITH k RETURN 1.5 AS a } RETURN a",
            "MATCH (n:K) WITH n.k AS k CALL (k) { RETURN k AS a UNION ALL RETURN 1.5 AS a } RETURN a ORDER BY a",
        )) {
            val sql = compiler.compile(cypher, emptyMap()).sql
            val parts = Regex("SELECT (q\\d+\\.c1.*) FROM \\(").findAll(sql).map { it.groupValues[1].replace(Regex("q\\d+\\."), "") }.toList()
            assertEquals(listOf("c1", "c1"), parts, "$cypher: $sql")
        }
    }

    @Test
    fun aGroupingKeyOrderedByIsReadOfItsGroupedValueNotItsPath() {
        // Read by the property's path beside a GROUP BY of its JSON, a key was no group's (ORA-00979): the order's keys are read of
        // the column the grouped value is carried in, after the GROUP BY, or of the grouped value itself.
        for (cypher in listOf(
            "MATCH (p:Person)-[:ACTED_IN]->(m) RETURN p.name, count(*) ORDER BY p.name",
            "MATCH (me:CyKey)--(you:CyKey) RETURN me.age AS age, count(you.age) AS cnt ORDER BY me.age + count(you.age)",
            "MATCH (me:CyKey)--(you:CyKey) RETURN me.age AS age, count(you.age) AS cnt ORDER BY CASE WHEN me.age > 1 THEN count(*) END",
        )) {
            val sql = compiler.compile(cypher, emptyMap()).sql
            val grouped = sql.substring(sql.lastIndexOf("SELECT ", sql.indexOf("GROUP BY")), sql.indexOf("GROUP BY"))
            assertTrue(!Regex("JSON_VALUE\\(n\\d+\\.props, '[^']*' RETURNING (BINARY_DOUBLE|NUMBER) NULL ON ERROR").containsMatchIn(grouped) ||
                Regex("GROUP BY .*JSON_VALUE\\(n\\d+\\.props").containsMatchIn(sql), "$cypher: $sql")
        }
        val ordered = compiler.compile("MATCH (p:Person)-[:ACTED_IN]->(m) RETURN p.name, count(*) ORDER BY p.name", emptyMap()).sql
        assertTrue(Regex("ORDER BY .*JSON_VALUE\\(p\\d+\\.s1, '\\$' RETURNING BINARY_DOUBLE").containsMatchIn(ordered) && " AS s1k1" !in ordered, ordered)
    }

    @Test
    fun anIntegerAndAnEqualFloatTieInAnOrderReadInPlace() {
        // As a fragment orders by a property: 36 and 36.0 tie, and only an integer past 2^53 is told apart by its NUMBER.
        val order = compiler.order("n.age", false, mapOf("n" to FragmentVariable.Node("people_"))).terms
        val n = big("people_.props", "$.age")
        assertTrue(order.any { it == "CASE WHEN ABS($n) > 9007199254740992 THEN $n END" }, "$order")
        assertTrue(order.none { it == n }, "$order")
    }
}
