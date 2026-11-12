package org.drivine.cypher

import org.drivine.cypher.api.CompileJson
import org.drivine.cypher.api.Compiled
import org.drivine.cypher.api.CypherCompiler
import org.drivine.cypher.api.GraphOptions
import org.drivine.cypher.api.Store
import org.drivine.cypher.sql.OracleCypherCompiler
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * On Oracle, with `readsAsText` (`GraphOptions.oracleReadsAsText`), a statement that only reads gives each row's value as
 * its extended JSON text, a `CLOB`, in place of `JSON`, so a host whose driver reads a `JSON` value's numbers through a
 * double keeps a whole number's digits (#198). Its rows are numbered in the statement's order and given in it; a hint the
 * statement starts with starts it still. A write, a typed compile and the other stores compile as before.
 */
class OracleReadsAsTextTest {

    private fun sql(cypher: String, readsAsText: Boolean, parameters: Map<String, Any?> = emptyMap()) =
        OracleCypherCompiler("g", true, true, emptySet(), null, false, false, readsAsText).compile(cypher, parameters).sql

    private val reads = listOf(
        "RETURN 9007199254740993 AS big",
        "MATCH (n:N) RETURN n",
        "MATCH ()-[r]->() RETURN r",
        "MATCH (n:N) RETURN n.x AS x, n.y AS y ORDER BY x DESC SKIP 1 LIMIT 2",
        "MATCH (n:N) RETURN DISTINCT n.x, count(*) AS c ORDER BY c",
        "MATCH (n:N) RETURN n UNION MATCH (n:M) RETURN n",
        "MATCH (n:N) RETURN n.x AS x UNION ALL MATCH (n:M) RETURN n.x AS x",
        "MATCH (a:Big) MATCH (b:Big) WHERE a = b RETURN a.n",
        "CALL db.labels() YIELD label RETURN label",
        "UNWIND \$xs AS x RETURN x",
    )

    @Test
    fun `a read gives each row's value as its extended JSON text, numbered in the read's order`() {
        for (cypher in reads) {
            val plain = sql(cypher, false, mapOf("xs" to listOf(1, 2)))
            val text = sql(cypher, true, mapOf("xs" to listOf(1, 2)))
            val hint = Regex("""^SELECT (/\*\+.*?\*/) """).find(plain)
            val head = if (hint == null) "SELECT" else "SELECT ${hint.groupValues[1]}"
            val inner = if (hint == null) plain else "SELECT " + plain.substring(hint.range.last + 1)
            val numbered = "SELECT cy_q.result, ROWNUM AS cy_n FROM (\n${inner.prependIndent("    ")}\n) cy_q"
            assertEquals(
                "$head JSON_SERIALIZE(cy_r.result RETURNING CLOB EXTENDED) AS result FROM (\n${numbered.prependIndent("    ")}\n) cy_r\nORDER BY cy_r.cy_n",
                text,
                cypher,
            )
        }
    }

    @Test
    fun `a hint the read starts with starts the statement still, and is in it once`() {
        val text = sql("MATCH (a:Big) MATCH (b:Big) WHERE a = b RETURN a.n", true)
        assertTrue(text.startsWith("SELECT /*+ NO_JSON_TABLE_TRANSFORM"), text)
        assertEquals(1, Regex("""/\*\+""").findAll(text).count(), text)
    }

    @Test
    fun `a write, with rows or without, compiles alike either way`() {
        for (cypher in listOf(
            "CREATE (n:N {i: 9007199254740993}) RETURN n.i",
            "MERGE (t:N {id: 1}) SET t.x = 2 RETURN t",
            "MATCH (n:N) DETACH DELETE n",
            "UNWIND [1, 2] AS i CREATE (:N {i: i})",
        )) assertEquals(sql(cypher, false), sql(cypher, true), cypher)
    }

    @Test
    fun `a typed compile, and every other store, compile alike either way`() {
        val typed = { readsAsText: Boolean ->
            (CypherCompiler.compile(Store.ORACLE, GraphOptions("g", oracleReadsAsText = readsAsText), "MATCH (n:N) RETURN n", emptyMap(), typed = true) as Compiled).sql
        }
        assertEquals(typed(false), typed(true))
        for (store in listOf(Store.POSTGRES, Store.SQLITE)) {
            val compiled = { readsAsText: Boolean ->
                (CypherCompiler.compile(store, GraphOptions("g", oracleReadsAsText = readsAsText), "RETURN 9007199254740993 AS big") as Compiled).sql
            }
            assertEquals(compiled(false), compiled(true), store.name)
        }
    }

    @Test
    fun `the option reaches the compiler through GraphOptions and a JSON request`() {
        val cypher = "RETURN 9007199254740993 AS big"
        val viaOptions = (CypherCompiler.compile(Store.ORACLE, GraphOptions("g", oracleReadsAsText = true), cypher) as Compiled).sql
        assertEquals(sql(cypher, true), viaOptions)
        val answer = CompileJson.compile("{\"store\": \"oracle\", \"graph\": {\"name\": \"g\", \"oracleReadsAsText\": true}, \"cypher\": \"$cypher\"}")
        assertTrue(answer.contains("JSON_SERIALIZE(cy_r.result RETURNING CLOB EXTENDED)"), answer)
        val refused = CompileJson.compile("{\"store\": \"oracle\", \"graph\": {\"name\": \"g\", \"oracleReadsAsText\": \"yes\"}, \"cypher\": \"$cypher\"}")
        assertEquals("{\"error\":\"A request's graph gives oracleReadsAsText as true or false.\"}", refused)
    }

    @Test
    fun `the constructor drivine4j was compiled against, with reencodeJson last, still links without the option`() {
        val cypher = "RETURN 9007199254740993 AS big"
        assertEquals(sql(cypher, false), OracleCypherCompiler("g", true, true, emptySet(), null, false, false).compile(cypher).sql)
    }
}
