package org.drivine.cypher

import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.dataformat.yaml.YAMLFactory
import org.drivine.cypher.api.CompileJson
import org.drivine.cypher.oracle.Oracle
import org.drivine.cypher.sql.OracleCypherCompiler
import org.drivine.cypher.sql.PostgresCypherCompiler
import org.drivine.cypher.sql.SqliteCypherCompiler
import org.drivine.cypher.store.OracleSql
import org.junit.jupiter.api.Test
import java.io.File
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Every subquery that a condition reads in a statement compiled for Oracle is hinted against subquery coalescing,
 * `SELECT /*+ NO_COALESCE_SQ */`, in its own first hint comment: the query of each `EXISTS (…)` and `NOT EXISTS (…)`, of each
 * `x IN (SELECT …)`, and the count a `COUNT { … }` or a quantifier compares.
 *
 * Oracle 23.26 merged an `OR` of two correlated subqueries wrongly and kept only the rows the first one matched:
 * `MATCH (g:GuideUser) WHERE EXISTS { (g)-[:IS_WEB_USER]->(:Anonymous) } OR EXISTS { (g)-[:IS_WEB_USER]->(:Registered) }
 * RETURN g.uuid` gave one guide where Neo4j gives two (drivine-cypher-spec#252). The hint helps only where it is written in
 * the subquery itself. The check reads each statement of the spec's corpus and each fragment `fragments.yaml` pins for
 * Oracle, so a form of subquery the compiler comes to write is held to it too.
 */
class OraclePredicateSubqueryTest {

    private val json = ObjectMapper()

    /** The spec's files, as the test's resources hold them. */
    private val spec = File(requireNotNull(javaClass.getResource("/cypher/queries.yaml")) { "No spec file cypher/queries.yaml" }.toURI()).parentFile

    @Test
    fun `each subquery a condition reads in a statement compiled for Oracle is hinted against coalescing`() {
        val oracle = Oracle.corpusRequests(spec).mapNotNull { request ->
            val read = runCatching { json.readTree(request) }.getOrNull()?.takeIf { it.isObject } ?: return@mapNotNull null
            if (read["store"]?.textValue() != "oracle") return@mapNotNull null
            val answer = json.readTree(CompileJson.compile(request))
            val sql = listOfNotNull(answer["sql"]?.textValue(), answer["labelCheck"]?.textValue()).ifEmpty { return@mapNotNull null }
            (read["cypher"]?.textValue() ?: read["vector"]?.toString() ?: read.toString()) to sql.joinToString("\n")
        }
        val read = oracle.sumOf { (_, sql) -> PredicateSubqueries.of(sql).size }
        assertTrue(oracle.size > 1_000 && read > 1_000, "${oracle.size} Oracle statements, $read subqueries a condition reads among them")
        val found = oracle.mapNotNull { (cypher, sql) -> PredicateSubqueries.unhinted(sql).takeIf { it.isNotEmpty() }?.let { "$cypher\n    $it" } }
        assertEquals(emptyList(), found.take(20), "${found.size} statements read a subquery in a condition that Oracle may coalesce")
    }

    @Test
    fun `each subquery a fragment's condition reads on Oracle is hinted against coalescing`() {
        val fragments = ObjectMapper(YAMLFactory()).readTree(File(spec, "fragments.yaml"))
        val terms = fragments["cases"].flatMap { case -> case["sql"]?.get("oracle")?.map { it.textValue() }.orEmpty() }
        assertTrue(terms.count { PredicateSubqueries.of(it).isNotEmpty() } >= 3, "${terms.size} Oracle terms")
        assertEquals(emptyList(), terms.filter { PredicateSubqueries.unhinted(it).isNotEmpty() })
    }

    @Test
    fun `the statement of the report has each EXISTS hinted on Oracle, and on Postgres and SQLite none`() {
        val cypher = "MATCH (g:GuideUser) WHERE EXISTS { (g)-[:IS_WEB_USER]->(:Anonymous) } OR EXISTS { (g)-[:IS_WEB_USER]->(:Registered) } RETURN g.uuid"
        val oracle = OracleCypherCompiler("g").compile(cypher).sql
        assertEquals(2, PredicateSubqueries.of(oracle).size, oracle)
        assertEquals(emptyList(), PredicateSubqueries.unhinted(oracle), oracle)
        for (sql in listOf(PostgresCypherCompiler("g"), SqliteCypherCompiler("g")).map { it.compile(cypher).sql }) {
            assertTrue(OracleSql.NO_COALESCE_SQ !in sql, sql)
        }
    }

    @Test
    fun `the hint is written in a query's first hint comment, once, and a query that opens otherwise is read from one that holds it`() {
        assertEquals("SELECT /*+ NO_COALESCE_SQ */ 1 FROM DUAL", OracleSql.uncoalesced("SELECT 1 FROM DUAL"))
        val hinted = "SELECT /*+ CARDINALITY(i1 1) NO_COALESCE_SQ */ i1.v FROM t i1"
        assertEquals(hinted, OracleSql.uncoalesced("SELECT /*+ CARDINALITY(i1 1) */ i1.v FROM t i1"))
        assertEquals(hinted, OracleSql.uncoalesced(hinted))
        // A hint comment of a query within it is not the query's own.
        assertEquals(
            "SELECT /*+ NO_COALESCE_SQ */ 1 FROM (SELECT /*+ NO_COALESCE_SQ */ 1 FROM DUAL) q",
            OracleSql.uncoalesced("SELECT 1 FROM (SELECT /*+ NO_COALESCE_SQ */ 1 FROM DUAL) q"),
        )
        assertEquals("SELECT /*+ NO_COALESCE_SQ */ * FROM (\n    (SELECT 1 FROM DUAL)\n)", OracleSql.uncoalesced("(SELECT 1 FROM DUAL)"))
        val with = OracleSql.uncoalesced("WITH w AS (SELECT 1 AS v FROM DUAL) SELECT v FROM w")
        assertEquals(emptyList(), PredicateSubqueries.unhinted("SELECT 1 FROM DUAL WHERE EXISTS ($with) OR 1 IN ($with)"))
    }

    @Test
    fun `the check finds a subquery a condition reads without the hint, and not one read otherwise`() {
        val hinted = """
            SELECT n1.nid FROM g_node n1
            WHERE EXISTS (SELECT /*+ NO_COALESCE_SQ */ 1 FROM g_edge e2 WHERE e2.src = n1.nid)
              OR NOT EXISTS (
                SELECT /*+ CARDINALITY(i3 1) NO_COALESCE_SQ */ 1 FROM g_edge e4 WHERE e4.dst = n1.nid
              )
              OR n1.nid IN (SELECT /*+ NO_COALESCE_SQ */ e5.src FROM g_edge e5)
              OR (SELECT /*+ NO_COALESCE_SQ */ count(*) FROM (
                SELECT 1 FROM g_edge e6 WHERE e6.src = n1.nid
              ) q7) > 0
              OR (SELECT /*+ NO_COALESCE_SQ */ CASE WHEN count(CASE WHEN q8.v THEN 1 END) > 0 THEN 1 ELSE 0 END FROM (SELECT TRUE AS v FROM DUAL) q8) = 1
        """.trimIndent()
        assertEquals(5, PredicateSubqueries.of(hinted).size)
        assertEquals(emptyList(), PredicateSubqueries.unhinted(hinted))
        // Each form without its hint, or with it in a hint comment Oracle doesn't read, as a second one, or given to the statement.
        assertEquals(1, PredicateSubqueries.unhinted(hinted.replace("SELECT /*+ NO_COALESCE_SQ */ 1 FROM g_edge e2", "SELECT 1 FROM g_edge e2")).size)
        assertEquals(1, PredicateSubqueries.unhinted(hinted.replace("CARDINALITY(i3 1) NO_COALESCE_SQ", "CARDINALITY(i3 1)")).size)
        assertEquals(1, PredicateSubqueries.unhinted(hinted.replace("SELECT /*+ NO_COALESCE_SQ */ e5.src", "SELECT e5.src")).size)
        assertEquals(1, PredicateSubqueries.unhinted(hinted.replace("SELECT /*+ NO_COALESCE_SQ */ count(*)", "SELECT count(*)")).size)
        assertEquals(1, PredicateSubqueries.unhinted(hinted.replace("SELECT /*+ NO_COALESCE_SQ */ CASE", "SELECT CASE")).size)
        assertEquals(1, PredicateSubqueries.unhinted(hinted.replace("SELECT /*+ NO_COALESCE_SQ */ e5.src", "SELECT /*+ CARDINALITY(e5 1) */ /*+ NO_COALESCE_SQ */ e5.src")).size)
        val statementHinted = "SELECT /*+ NO_COALESCE_SQ */ n1.nid FROM g_node n1 WHERE EXISTS (SELECT 1 FROM g_edge e2 WHERE e2.src = n1.nid)"
        assertEquals(1, PredicateSubqueries.unhinted(statementHinted).size)
        // An EXISTS whose query opens otherwise than with its SELECT.
        assertEquals(1, PredicateSubqueries.unhinted("SELECT 1 FROM DUAL WHERE EXISTS ((SELECT /*+ NO_COALESCE_SQ */ 1 FROM DUAL))").size)
        // A derived table, a cursor's loop, a JSON_EXISTS, an IN of values and a text that holds the words are no such subquery.
        val others = """
            SELECT n1.nid FROM (SELECT n.nid FROM g_node n) n1
            WHERE JSON_EXISTS(n1.labels, '$[*]?(@ == "A")') AND n1.nid IN (1, 2) AND n1.t = 'EXISTS (SELECT 1 FROM x) '' IN (SELECT y'
        """.trimIndent()
        assertEquals(emptyList(), PredicateSubqueries.of(others))
        assertEquals(emptyList(), PredicateSubqueries.of("BEGIN FOR cy_r IN (SELECT s.item FROM t s) LOOP NULL; END LOOP; END;"))
    }
}

/**
 * Finds, in a statement compiled for Oracle, each subquery that a condition reads, outside its quoted texts: the query of each
 * `EXISTS (…)`, of each `IN (SELECT …)` but a cursor's `FOR <record> IN (…)`, and the count of a `COUNT { … }` or of a
 * quantifier, `(SELECT count(*) FROM (` and `(SELECT CASE WHEN count(CASE WHEN`, each as the compiler writes it.
 */
internal object PredicateSubqueries {

    /**
     * The start of each such subquery: what opens it, then its `SELECT` and the hints of its first hint comment where it has
     * them. An `EXISTS (` that a `SELECT` doesn't follow is one too, and unhinted.
     */
    private val OPENED = Regex(
        """(?:\bEXISTS\s*\(\s*|(?<!\bFOR \w{1,128} )\bIN\s*\(\s*(?=SELECT\b)|\((?=SELECT (?:/\*\+ [^*]*\*/ )?(?:count\(\*\) FROM \(|CASE WHEN count\(CASE WHEN)))""" +
            """(SELECT\b(?: /\*\+ ([^*]*) \*/)?)?""",
    )

    /** Each subquery a condition reads in [sql], by the text that opens it. */
    fun of(sql: String): List<MatchResult> = OPENED.findAll(unquoted(sql)).toList()

    /** The opening text of each subquery of [sql] whose first hint comment does not hold `NO_COALESCE_SQ`, and what follows it. */
    fun unhinted(sql: String): List<String> {
        val text = unquoted(sql)
        return of(sql).filter { it.groupValues[1].isEmpty() || OracleSql.NO_COALESCE_SQ !in it.groupValues[2].split(' ') }
            .map { text.substring(it.range.first, minOf(text.length, it.range.last + 32)).replace(Regex("\\s+"), " ").trim() }
    }

    /** [sql] with each quoted text, `'…'`, emptied, so the words a text holds aren't read as SQL. */
    private fun unquoted(sql: String) = sql.replace(Regex("'(?:[^']|'')*'"), "''")
}
