package org.drivine.cypher

import org.drivine.cypher.sql.OracleCypherCompiler
import org.drivine.cypher.sql.PostgresCypherCompiler
import org.drivine.cypher.sql.SqliteCypherCompiler
import org.drivine.cypher.store.OracleSql
import org.junit.jupiter.api.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * On Oracle, the query of an `EXISTS { … }` is read from a derived table, as a `COUNT { … }`'s is: Oracle refuses an `ORDER BY`
 * that ends the query of an `EXISTS` (ORA-00907), and one whose body unwinds a list or sorts its rows ends in one
 * (drivine4j#449). A body of a `UNION` is read the same way, and so is one in a `CASE`, a list comprehension or a write. The
 * spec's "EXISTS { } over UNWIND" and "SET and MERGE with EXISTS { } over UNWIND" cases run them on each store. Its `SELECT`
 * is hinted against subquery coalescing (`OraclePredicateSubqueryTest`). Postgres and SQLite read the query as it is.
 */
class OracleExistsQueryTest {

    private val statements = listOf(
        "RETURN EXISTS { UNWIND [1, 2, 3] AS j RETURN j }",
        "MATCH (p:Person) WHERE EXISTS { UNWIND keys(p) AS k WITH k WHERE k = 'born' RETURN k } RETURN p.name",
        "RETURN EXISTS { UNWIND [1, 2, 3] AS j RETURN j ORDER BY j }",
        "UNWIND [1, 2, 3] AS o WITH o WHERE NOT EXISTS { UNWIND [1, 2] AS j WITH j WHERE j = o RETURN j } RETURN o",
        "UNWIND [1, 2, 3] AS o RETURN o, EXISTS { UNWIND range(1, o) AS j WITH j WHERE EXISTS { UNWIND [2, 3] AS k WITH k WHERE k = j RETURN k } RETURN j }",
        "UNWIND [1, 2, 3] AS o RETURN o, EXISTS { UNWIND [1] AS j WITH j WHERE j = o RETURN j UNION UNWIND [3] AS j WITH j WHERE j = o RETURN j }",
        "UNWIND [1, 2, 3] AS o CALL (o) { RETURN EXISTS { UNWIND [1, 2] AS j WITH j WHERE j = o RETURN j } AS e } RETURN o, e",
        "UNWIND [1, 2, 3] AS o RETURN o, CASE WHEN EXISTS { UNWIND [1, 2] AS j WITH j WHERE j = o RETURN j } THEN 'y' ELSE 'n' END",
        "UNWIND [1, 2] AS o RETURN o, [x IN [1, 2, 3] WHERE EXISTS { UNWIND [2, 3] AS j WITH j WHERE j = x + o RETURN j } | x]",
    )

    /** Writes, which SQLite compiles to steps in a JSON text, where its `EXISTS (` isn't found as it is in [existsBodies]. */
    private val writes = listOf(
        "MATCH (n:CyWrite) WHERE EXISTS { UNWIND keys(n) AS k WITH k WHERE k = 'a' RETURN k } SET n.hasA = true RETURN n.id",
        "MERGE (n:CyWrite {id: 3}) SET n.e = EXISTS { UNWIND [1, 3] AS j WITH j WHERE j = n.id RETURN j ORDER BY j } RETURN n.e",
    )

    /** Each `EXISTS (` of [sql] that opens a subquery, or Oracle's count of one that reads no table, and the text that follows it. */
    private fun existsBodies(sql: String) = Regex("""(?:EXISTS \(|count\(\*\) FROM \()\n(\s*)(\S[^\n]*)""").findAll(sql).map { it.groupValues[2] }.toList()

    @Test
    fun `each EXISTS query is read from a derived table on Oracle`() {
        for (cypher in statements + writes) {
            val sql = OracleCypherCompiler("g").compile(cypher).sql
            val bodies = existsBodies(sql)
            assertTrue(bodies.isNotEmpty() && bodies.all { it == "SELECT /*+ NO_COALESCE_SQ */ 1 FROM (" }, sql)
        }
    }

    @Test
    fun `an ORDER BY that a LIMIT reads is kept beside it inside the derived table`() {
        val sql = OracleCypherCompiler("g").compile(
            "UNWIND [1, 2, 3] AS o RETURN o, EXISTS { UNWIND [3, 1, 2] AS j WITH j ORDER BY j LIMIT 1 WITH j WHERE j = o RETURN j }"
        ).sql
        val inside = sql.substring(sql.indexOf("SELECT /*+ NO_COALESCE_SQ */ 1 FROM ("))
        assertTrue(Regex("""ORDER BY [^\n]*\n\s*FETCH NEXT 1 ROWS ONLY""").containsMatchIn(inside), sql)
    }

    @Test
    fun `Postgres and SQLite read the EXISTS query as it is`() {
        for (cypher in statements + writes) {
            val compilers = if (cypher in writes) listOf(PostgresCypherCompiler("g")) else listOf(PostgresCypherCompiler("g"), SqliteCypherCompiler("g"))
            for (sql in compilers.map { it.compile(cypher).sql }) {
                val bodies = existsBodies(sql)
                assertTrue(bodies.isNotEmpty() && bodies.none { it.startsWith("SELECT 1 FROM (") }, sql)
            }
        }
    }

    /** Statements whose `EXISTS { … }` reads only the row it is in, as the spec's "EXISTS { } and COUNT { } of the row alone" has them. */
    private val ofTheRow = listOf(
        "MATCH (:Person {name: 'Carrie'})-[:ACTED_IN]->(m1) RETURN m1.title AS title, EXISTS { MATCH (m1:Movie) RETURN m1.title AS y } AS y ORDER BY title",
        "MATCH (n) RETURN EXISTS { MATCH (n:Movie) WITH n.title AS t RETURN t AS y }",
        "MATCH (n) RETURN n.name ORDER BY EXISTS { MATCH (n:Movie) RETURN n.title AS y }",
        "MATCH (n) RETURN EXISTS { (n:Movie) }",
        "MATCH (n) RETURN EXISTS { MATCH (n:Movie) }",
        "MATCH (n) RETURN EXISTS { WITH n WHERE n:Movie RETURN n.title AS y }",
        "MATCH (n) RETURN EXISTS { MATCH (n:Movie) WHERE COUNT { (n)<--() } > 1 RETURN n.title AS y }",
        "MATCH (n) WHERE NOT EXISTS { MATCH (n:Movie) RETURN n.title AS y } RETURN n.name",
        "MATCH (n) RETURN EXISTS { OPTIONAL MATCH (n:Movie) RETURN n.title AS y }",
        "MATCH (n) RETURN EXISTS { MATCH (n:Movie) RETURN n.title AS y UNION MATCH (n:Person) RETURN n.name AS y }",
        "MATCH (n) RETURN EXISTS { MATCH (n:Movie) RETURN n.title AS y ORDER BY y LIMIT 1 }",
        "MATCH (n) RETURN CASE WHEN EXISTS { MATCH (n:Movie) RETURN n.title AS y } THEN 'y' ELSE 'n' END",
        "MATCH (n) RETURN EXISTS { MATCH (n:Movie) WHERE n.released.year = 1999 }",
        "MATCH (n) RETURN EXISTS { MATCH (n:Movie) WHERE datetime(n.released).quarter = 1 }",
        // Its SQL reads a list's rows (`JSON_TABLE`), but its Cypher, an `UNWIND`, no table for certain.
        "MATCH (n) RETURN EXISTS { UNWIND [n] AS z WITH z WHERE z:Movie RETURN z }",
        // A node bound within the query, by a `WITH`, an `UNWIND` or a `CALL` that reads no table, is not matched anew.
        "MATCH (n) RETURN EXISTS { WITH n AS x MATCH (x:Movie) RETURN x.title AS y }",
        "MATCH (n) RETURN EXISTS { UNWIND [n] AS x MATCH (x:Movie) RETURN x.title AS y }",
        "MATCH (n) RETURN EXISTS { CALL (n) { WITH n WHERE n:Movie RETURN n AS x } MATCH (x:Movie) RETURN x.title AS y }",
        // A path that may take no step is not taken to read a table for certain.
        "MATCH (n) RETURN EXISTS { MATCH (n:Movie)-[*0..1]->(n) }",
        // A union reads a table for certain only where each of its queries does.
        "MATCH (n) RETURN EXISTS { MATCH (n:Movie) RETURN n.title AS y UNION MATCH (x:Person) RETURN x.name AS y }",
        // An optional read keeps the row whatever the table holds.
        "MATCH (n) RETURN EXISTS { MATCH (n:Movie) OPTIONAL MATCH (n)-->(x) RETURN x }",
        "MATCH (n) RETURN EXISTS { OPTIONAL CALL (n) { MATCH (n)-->(k) RETURN k } RETURN k }",
    )

    /** Statements whose `EXISTS { … }` reads a table: the graph's, or a list's rows. */
    private val ofATable = listOf(
        "MATCH (n) RETURN EXISTS { MATCH (n:Movie)<-[:ACTED_IN]-(a) RETURN a.name AS y }",
        "MATCH (n) RETURN EXISTS { (n)-->() }",
        "MATCH (n) RETURN EXISTS { MATCH (x:Movie) RETURN x.title AS y }",
        "MATCH (n) RETURN EXISTS { MATCH (n:Movie) CALL (n) { MATCH (n)-->(x) RETURN x } RETURN x }",
        "MATCH (n), (m) RETURN EXISTS { MATCH (n)-[:R]->(m) }",
        "MATCH (n) RETURN EXISTS { WITH n.title AS t MATCH (x {title: t}) RETURN x }",
        "MATCH (n) RETURN EXISTS { WITH n AS x MATCH (x)-->() RETURN x }",
        "MATCH (n) RETURN EXISTS { MATCH (n)-[*1..2]->(:Person) }",
        // A read after an aggregate decides the truth again: a node returned by a `CALL` is bound.
        "MATCH (n) RETURN EXISTS { MATCH (x:Movie) WITH count(x) AS c MATCH (y:Person) RETURN y }",
        "MATCH (n) RETURN EXISTS { CALL (n) { MATCH (n)-->(x) RETURN x } WITH x, count(*) AS c MATCH (x)-->(y) RETURN y }",
        "MATCH (n) WHERE (n)-->() RETURN n",
    )

    @Test
    fun `an EXISTS whose query reads only the row is its rows counted on Oracle`() {
        for (cypher in ofTheRow) {
            val sql = OracleCypherCompiler("g").compile(cypher).sql
            assertTrue(sql.contains(") ex) > 0)") && Regex("""(?<!JSON_)EXISTS \(\n""").findAll(sql).count() == 0, sql)
        }
    }

    @Test
    fun `an EXISTS whose query reads a table is written as it was on Oracle`() {
        for (cypher in ofATable) {
            val sql = OracleCypherCompiler("g").compile(cypher).sql
            assertTrue(!sql.contains(") ex) > 0)") && Regex("""(?<!JSON_)EXISTS \(""").containsMatchIn(sql), sql)
        }
    }

    /**
     * Statements whose `EXISTS { … }` reads a table in its SQL, but whose Cypher may read none: a path that may take no step,
     * a quantified one, a shortest path, or a clause after a `CALL` that reads none. Each is counted, which costs only speed.
     * One that aggregates after its read keeps a row whatever the table holds, as an optional read does, so it is counted too.
     */
    private val mayReadNone = listOf(
        "MATCH (n) RETURN EXISTS { MATCH (n)-[*0..]->(m) }",
        "MATCH (n) RETURN EXISTS { MATCH (n)-[:R]->*(m) }",
        "MATCH (n) RETURN EXISTS { MATCH (n) ((a)-[:R]->(b)){0,2} (m) }",
        "MATCH (n) RETURN EXISTS { MATCH p = shortestPath((n)-[*]->(m)) }",
        "MATCH (n) RETURN EXISTS { MATCH p = allShortestPaths((n)-[*]->(m)) }",
        "MATCH (n) RETURN EXISTS { CALL (n) { WITH n WHERE n:Movie RETURN n AS x } MATCH (y) RETURN y }",
        "MATCH (n) RETURN EXISTS { CALL db.labels() YIELD label MATCH (x) RETURN x }",
        "MATCH (n) RETURN EXISTS { MATCH (x:Movie) RETURN count(x) AS c }",
        "MATCH (n) RETURN EXISTS { MATCH (x:Movie) WITH collect(x) AS xs RETURN xs }",
        "MATCH (n) RETURN EXISTS { MATCH (n)-->(x) WITH count(x) AS c WHERE c >= 0 RETURN c }",
        "MATCH (n) RETURN EXISTS { CALL { MATCH (x:Movie) RETURN count(x) AS c } RETURN c }",
        "MATCH (n) RETURN EXISTS { CALL { MATCH (x:Movie) RETURN x } RETURN count(x) AS c }",
        "MATCH (n) RETURN EXISTS { CALL (n) { MATCH (n)-->(x) RETURN x } WITH x, count(*) AS c MATCH (x:Movie) RETURN x }",
    )

    @Test
    fun `an EXISTS whose Cypher may read no table is counted on Oracle though its SQL reads one`() {
        for (cypher in mayReadNone) {
            val sql = OracleCypherCompiler("g").compile(cypher).sql
            val counted = Regex("""count\(\*\) FROM \(\n([\s\S]*)\n\s*\) ex\) > 0\)""").find(sql)
            assertTrue(counted != null && OracleSql.readsTable(counted.groupValues[1]), sql)
        }
    }

    @Test
    fun `an EXISTS of the row alone is counted while an EXISTS within its WHERE that reads a table is written as it was`() {
        val sql = OracleCypherCompiler("g").compile(
            "MATCH (n) RETURN EXISTS { WITH n WHERE n:Movie AND EXISTS { (n)<-[:ACTED_IN]-(:Person {name: 'Carrie'}) } RETURN 1 AS y }"
        ).sql
        val counted = sql.indexOf(") ex) > 0)")
        val inner = Regex("""(?<!JSON_)EXISTS \(SELECT /\*\+ NO_COALESCE_SQ \*/ 1 FROM g_node""").findAll(sql).toList()
        assertTrue(Regex("""\) ex\) > 0\)""").findAll(sql).count() == 1 && inner.size == 1 && inner[0].range.first < counted, sql)
    }

    @Test
    fun `Postgres and SQLite write the EXISTS of a query that reads only the row as an EXISTS`() {
        for (cypher in ofTheRow) {
            for (sql in listOf(PostgresCypherCompiler("g"), SqliteCypherCompiler("g")).map { it.compile(cypher).sql }) {
                assertTrue(!sql.contains(") ex) > 0") && sql.contains("EXISTS ("), sql)
            }
        }
    }

    @Test
    fun `a query reads a table where a source of it or of a derived table or a lateral one is a table`() {
        for (query in listOf(
            "SELECT 1 FROM g_node n",
            "SELECT 1 FROM (SELECT 1) one CROSS JOIN g_edge e",
            "SELECT 1 FROM (SELECT x FROM (SELECT 1 FROM g_node n) d) d2",
            "SELECT 1 FROM (SELECT 1) one LEFT JOIN LATERAL (SELECT 1 FROM g_node n) o ON 1 = 1",
            "SELECT 1 FROM (SELECT 1) one CROSS APPLY (SELECT 1 FROM g_node n) a",
            "SELECT 1 FROM (SELECT 1) one OUTER APPLY (SELECT 1 FROM g_node n) a",
            "SELECT 1 FROM JSON_TABLE(x, '\$[*]' COLUMNS (v PATH '\$')) j",
            "SELECT 1 FROM DUAL UNION ALL SELECT 1 FROM g_node n",
            "SELECT 1 FROM g_node DUAL",
        )) assertTrue(OracleSql.readsTable(query), query)
    }

    @Test
    fun `a query reads no table where only DUAL or a subquery within an expression or a quoted text names one`() {
        for (query in listOf(
            "SELECT 1 FROM DUAL WHERE JSON_EXISTS(n1.labels, '\$[*]?(@ == \"Movie\")')",
            "SELECT 1 FROM (SELECT 1) one WHERE x = 'a FROM b' AND y = 'it''s JOIN c'",
            "SELECT 1 WHERE EXISTS (SELECT 1 FROM g_node n) AND x IN (SELECT 1 FROM g_edge e)",
            "SELECT (SELECT count(*) FROM g_node n) AS c FROM DUAL",
            "SELECT 1 FROM (SELECT 1 FROM DUAL) d CROSS JOIN LATERAL (SELECT 1 FROM DUAL) l",
            "SELECT 1 FROM (SELECT 1) one WHERE EXTRACT(MONTH FROM JSON_VALUE(n1.props, '\$.a' RETURNING TIMESTAMP)) = 1",
            "SELECT 1 WHERE EXTRACT( TIMEZONE_HOUR  FROM x) = 1",
        )) assertFalse(OracleSql.readsTable(query), query)
    }
}
