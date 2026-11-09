package org.drivine.cypher

import com.fasterxml.jackson.databind.ObjectMapper
import org.drivine.cypher.api.Compiled
import org.drivine.cypher.api.CypherCompiler
import org.drivine.cypher.api.GraphOptions
import org.drivine.cypher.api.Store
import org.drivine.cypher.json.CypherEncoder
import org.drivine.cypher.oracle.Oracle
import org.drivine.cypher.sql.OracleCypherCompiler
import org.junit.jupiter.api.Test
import java.io.File
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * On Oracle, with `rowsAsText` (`GraphOptions.oracleRowsAsText`), a statement that writes returns its rows as one row of
 * extended JSON text, a list of them in their order, in the column `rows:json`, for a host whose driver gives only the
 * prefetched rows of an implicit result with a `JSON` or a `CLOB` column (oracle/node-oracledb#1795). The rows are those
 * of the same query, and every other statement compiles as it does without the option.
 */
class OracleRowsAsTextTest {

    private val parameters = mapOf("long" to "x".repeat(40000))

    private fun sql(cypher: String, rowsAsText: Boolean) =
        OracleCypherCompiler("g", true, true, emptySet(), null, rowsAsText).compile(cypher, parameters).sql

    private val writes = listOf(
        "UNWIND [3, 1, 2] AS i CREATE (n:N {i: i}) RETURN n.i ORDER BY n.i DESC",
        "MERGE (n:N {id: 1}) ON CREATE SET n.made = true RETURN n",
        "MATCH (n:N) SET n.seen = true RETURN n.i, n.seen ORDER BY n.i",
        "MATCH (n:N) WITH n, n.i AS i DETACH DELETE n RETURN i ORDER BY i",
        "UNWIND [1, 2] AS i CALL { WITH i CREATE (n:N {i: i}) RETURN n } RETURN n.i",
        "UNWIND [1, 2] AS i CREATE (n:N {i: i, long: \$long}) RETURN n.long",
        "CREATE (n:N {d: date('2026-11-09'), x: 2.0}) RETURN n.d, n.x, 0.0 / 0.0 AS nan",
    )

    @Test
    fun `a write returns its rows as one row of text, the same query's rows in their order`() {
        for (cypher in writes) {
            val plain = sql(cypher, rowsAsText = false)
            val text = sql(cypher, rowsAsText = true)
            // The query, each of its lines indented as deep again as the block it now stands in.
            val query = plain.substringAfter("\n    OPEN cy_cursor FOR\n").substringBefore(";\n    DBMS_SQL.RETURN_RESULT(cy_cursor);\n")
            val block = "\n    OPEN cy_cursor FOR\n" +
                "        SELECT JSON_SERIALIZE(COALESCE(JSON_ARRAYAGG(cy_r.result ORDER BY cy_r.cy_n NULL ON NULL RETURNING JSON), JSON('[]')) RETURNING CLOB EXTENDED) AS \"rows:json\" FROM (\n" +
                "            SELECT cy_q.result, ROWNUM AS cy_n FROM (\n" +
                query.prependIndent("        ") + "\n" +
                "            ) cy_q\n" +
                "        ) cy_r;\n" +
                "    DBMS_SQL.RETURN_RESULT(cy_cursor);\n"
            val expected = plain.replace("\n    OPEN cy_cursor FOR\n$query;\n    DBMS_SQL.RETURN_RESULT(cy_cursor);\n", block)
            assertTrue(expected != plain, cypher)
            assertEquals(expected, text, cypher)
        }
    }

    @Test
    fun `without the option a write's rows are a row of JSON each, as before`() {
        val sql = OracleCypherCompiler("g").compile("UNWIND [1, 2] AS i CREATE (n:N {i: i}) RETURN n.i").sql
        assertTrue("\n    OPEN cy_cursor FOR\n        SELECT " in sql && "\n    DBMS_SQL.RETURN_RESULT(cy_cursor);\n" in sql, sql)
        assertTrue("rows:json" !in sql && "JSON_SERIALIZE(COALESCE(JSON_ARRAYAGG(cy_r.result" !in sql, sql)
        for (cypher in writes) {
            assertEquals(sql(cypher, rowsAsText = false), OracleCypherCompiler("g").compile(cypher, parameters).sql, cypher)
            assertEquals(compiled(cypher, GraphOptions("g")), compiled(cypher, GraphOptions("g", oracleRowsAsText = false)), cypher)
        }
    }

    @Test
    fun `a read, a write that returns nothing, and the other stores compile alike either way`() {
        for (cypher in listOf(
            "MATCH (p:Person) RETURN p.name ORDER BY p.name",
            "UNWIND [1, 2] AS i RETURN i",
            "CREATE (n:N {i: 1})",
            "MATCH (n:N) DETACH DELETE n",
            "UNWIND [1, 2] AS i CALL { WITH i CREATE (:N {i: i}) }",
        )) assertEquals(sql(cypher, rowsAsText = false), sql(cypher, rowsAsText = true), cypher)
        for (store in listOf(Store.POSTGRES, Store.SQLITE)) for (cypher in writes.take(3)) {
            assertEquals(compiled(cypher, GraphOptions("g"), store), compiled(cypher, GraphOptions("g", oracleRowsAsText = true), store), "$store $cypher")
        }
    }

    @Test
    fun `the option reaches the compiler through GraphOptions`() {
        val cypher = writes.first()
        assertEquals(sql(cypher, rowsAsText = true), (compiled(cypher, GraphOptions("g", oracleRowsAsText = true)) as Compiled).sql)
        assertEquals("rows:json", OracleCypherCompiler.ROWS_AS_TEXT)
    }

    @Test
    fun `the constructors drivine4j was compiled against still link, without the option`() {
        val encoder = OracleCypherCompiler::class.java.getConstructor(
            String::class.java, Boolean::class.javaPrimitiveType, Boolean::class.javaPrimitiveType, Set::class.java, CypherEncoder::class.java,
        )
        val plain = OracleCypherCompiler::class.java.getConstructor(
            String::class.java, Boolean::class.javaPrimitiveType, Boolean::class.javaPrimitiveType, Set::class.java,
        )
        val cypher = writes.first()
        for (compiler in listOf(encoder.newInstance("g", true, true, emptySet<Any>(), null), plain.newInstance("g", true, true, emptySet<Any>()))) {
            assertEquals(sql(cypher, rowsAsText = false), compiler.compile(cypher, parameters).sql)
        }
    }

    @Test
    fun `the corpus checks the option on every target, over writes of each kind that return rows`() {
        val spec = File(requireNotNull(javaClass.getResource("/cypher/queries.yaml")) { "No spec file cypher/queries.yaml" }.toURI()).parentFile
        val json = ObjectMapper()
        val text = Oracle.corpusRequests(spec).map { json.readTree(it) }.filter { it["graph"]?.get("oracleRowsAsText")?.booleanValue() == true }
        val statements = text.map { it["cypher"].textValue() }
        assertTrue(text.all { it["store"].textValue() == "oracle" } && statements.size > 100, "${statements.size} requests with oracleRowsAsText")
        for (kind in listOf("CREATE", "MERGE", "SET", "DELETE", "CALL {")) {
            assertTrue(statements.any { kind in it && "RETURN" in it }, "no write with $kind that returns rows")
        }
        assertTrue(text.any { request -> request["parameters"].any { it.isTextual && it.textValue().length > 32_767 } }, "no write of a long text")
    }

    private fun compiled(cypher: String, graph: GraphOptions, store: Store = Store.ORACLE) =
        CypherCompiler.compile(store, graph, cypher, parameters)
}
