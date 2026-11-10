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
 * On Oracle, with `reencodeJson` (`GraphOptions.oracleReencodeJson`), each value a statement returns, its rows' `result`, is
 * encoded again, `JSON_TRANSFORM(value, KEEP '$')`, for a host whose thin driver can't read the OSON version 2 a document
 * updated in place comes back as (#165). It is the statement's own result that is wrapped, once, on every path a host gets
 * rows from: not a subquery's, an order term or an identity column. Without the option every statement compiles as before.
 */
class OracleReencodeJsonTest {

    private fun sql(cypher: String, reencodeJson: Boolean, rowsAsText: Boolean = false) =
        OracleCypherCompiler("g", true, true, emptySet(), null, rowsAsText, reencodeJson).compile(cypher, parameters).sql

    /** Each statement, and how many results it is wrapped at: one, but a statement's UNION, whose each query gives its rows' result. */
    private val returning = listOf(
        "MATCH (n:N) RETURN n" to 1,
        "MATCH ()-[r]->() RETURN r" to 1,
        "RETURN 1" to 1,
        "RETURN [1, 2]" to 1,
        "RETURN {a: 1}" to 1,
        "RETURN null" to 1,
        "MATCH (n:N) RETURN n.x AS x, n.y AS y ORDER BY x DESC SKIP 1 LIMIT 2" to 1,
        "MATCH (n:N) RETURN DISTINCT n.x, count(*) AS c ORDER BY c" to 1,
        "MATCH (n:N) WITH n ORDER BY n.x RETURN collect(n)" to 1,
        "CALL db.labels() YIELD label RETURN label" to 1,
        "MATCH (n:N) CALL { WITH n MATCH (n)-->(m) RETURN m } RETURN m" to 1,
        "MATCH (n:N) CALL { WITH n MATCH (n)-->(m) RETURN m UNION MATCH (n)<--(m) RETURN m } RETURN m" to 1,
        "MATCH (n:N) WHERE EXISTS { MATCH (n)-->(m) RETURN m UNION MATCH (n)<--(m) RETURN m } RETURN n" to 1,
        "MATCH (n:N) RETURN COUNT { MATCH (n)-->(m) RETURN m UNION ALL MATCH (n)<--(m) RETURN m } AS c" to 1,
        "MATCH (n:N) RETURN n UNION MATCH (n:M) RETURN n" to 2,
        "MATCH (n:N) RETURN n.x AS x UNION ALL MATCH (n:M) RETURN n.x AS x" to 2,
        "MERGE (t:N {id: 1}) SET t += \$task RETURN properties(t)" to 1,
        "MERGE (t:N {id: 1}) SET t.x = 2 RETURN t" to 1,
        "UNWIND [1, 2] AS i CALL { WITH i CREATE (n:N {i: i}) RETURN n } RETURN n.i" to 1,
        "MATCH (n:N) WITH n, n.i AS i DETACH DELETE n RETURN i ORDER BY i" to 1,
    )

    private val parameters = mapOf("task" to mapOf("id" to 1, "title" to "t"))

    /** [sql] with each `JSON_TRANSFORM(value, KEEP '$') AS result` given back as `value AS result`, read by its parentheses and quotes. */
    private fun unwrapped(sql: String): String {
        val open = "JSON_TRANSFORM("
        val close = ", KEEP '\$')"
        val opened = ArrayDeque<Int>()
        val cut = mutableListOf<Pair<Int, Int>>()
        var i = 0
        while (i < sql.length) {
            when {
                sql[i] == '\'' -> i = sql.indexOf('\'', i + 1)
                sql.startsWith(open, i) -> { opened.addLast(i); i += open.length - 1 }
                sql[i] == '(' -> opened.addLast(-1)
                sql[i] == ')' -> {
                    val start = opened.removeLast()
                    if (start >= 0 && sql.startsWith(close, i + 1 - close.length) && sql.startsWith(" AS result", i + 1)) cut += start to i + 1
                }
            }
            i++
        }
        val out = StringBuilder()
        var done = 0
        for ((start, end) in cut.sortedBy { it.first }) {
            out.append(sql, done, start).append(sql, start + open.length, end - close.length)
            done = end
        }
        return out.append(sql, done, sql.length).toString()
    }

    @Test
    fun `each statement's result is encoded again, once, and nothing else is`() {
        for (rowsAsText in listOf(false, true)) for ((cypher, results) in returning) {
            val plain = sql(cypher, reencodeJson = false, rowsAsText)
            val again = sql(cypher, reencodeJson = true, rowsAsText)
            assertTrue("KEEP '\$')" !in plain, cypher)
            assertEquals(results, Regex("KEEP '\\\$'\\) AS result").findAll(again).count(), "$cypher\n$again")
            assertEquals(results, Regex("KEEP '\\\$'\\)").findAll(again).count(), "$cypher\n$again")
            // Taken off again, the SQL is the same query's: nothing but the result is changed, no order term, identity or subquery.
            assertEquals(plain, unwrapped(again), cypher)
        }
    }

    @Test
    fun `without the option every statement compiles as before`() {
        for ((cypher, _) in returning) {
            assertEquals(OracleCypherCompiler("g").compile(cypher, parameters).sql, sql(cypher, reencodeJson = false), cypher)
            assertEquals(compiled(cypher, GraphOptions("g")), compiled(cypher, GraphOptions("g", oracleReencodeJson = false)), cypher)
        }
        for (store in listOf(Store.POSTGRES, Store.SQLITE)) for ((cypher, _) in returning.take(3)) {
            assertEquals(compiled(cypher, GraphOptions("g"), store), compiled(cypher, GraphOptions("g", oracleReencodeJson = true), store), "$store $cypher")
        }
    }

    @Test
    fun `a write that returns nothing compiles alike either way`() {
        for (rowsAsText in listOf(false, true)) for (cypher in listOf(
            "CREATE (n:N {i: 1})",
            "MATCH (n:N) SET n.x = 1",
            "MATCH (n:N) DETACH DELETE n",
            "UNWIND [1, 2] AS i CALL { WITH i CREATE (:N {i: i}) }",
        )) assertEquals(sql(cypher, reencodeJson = false, rowsAsText), sql(cypher, reencodeJson = true, rowsAsText), cypher)
    }

    @Test
    fun `the option reaches the compiler through GraphOptions, beside oracleRowsAsText`() {
        val cypher = returning.first().first
        assertEquals(sql(cypher, reencodeJson = true), (compiled(cypher, GraphOptions("g", oracleReencodeJson = true)) as Compiled).sql)
        val write = "MERGE (t:N {id: 1}) SET t.x = 2 RETURN t"
        assertEquals(
            sql(write, reencodeJson = true, rowsAsText = true),
            (compiled(write, GraphOptions("g", oracleRowsAsText = true, oracleReencodeJson = true)) as Compiled).sql,
        )
    }

    @Test
    fun `the constructors drivine4j was compiled against still link, without the option`() {
        val types = listOf(String::class.java, Boolean::class.javaPrimitiveType, Boolean::class.javaPrimitiveType, Set::class.java)
        val rowsAsText = OracleCypherCompiler::class.java.getConstructor(*(types + CypherEncoder::class.java + Boolean::class.javaPrimitiveType).toTypedArray())
        val encoder = OracleCypherCompiler::class.java.getConstructor(*(types + CypherEncoder::class.java).toTypedArray())
        val plain = OracleCypherCompiler::class.java.getConstructor(*types.toTypedArray())
        val cypher = returning.first().first
        for (compiler in listOf(
            rowsAsText.newInstance("g", true, true, emptySet<Any>(), null, false),
            encoder.newInstance("g", true, true, emptySet<Any>(), null),
            plain.newInstance("g", true, true, emptySet<Any>()),
        )) assertEquals(sql(cypher, reencodeJson = false), compiler.compile(cypher, parameters).sql)
    }

    @Test
    fun `the corpus checks the option on every target, over reads, unions and writes`() {
        val spec = File(requireNotNull(javaClass.getResource("/cypher/queries.yaml")) { "No spec file cypher/queries.yaml" }.toURI()).parentFile
        val json = ObjectMapper()
        val again = Oracle.corpusRequests(spec).map { json.readTree(it) }.filter { it["graph"]?.get("oracleReencodeJson")?.booleanValue() == true }
        val statements = again.map { it["cypher"].textValue() }
        assertTrue(again.all { it["store"].textValue() == "oracle" } && statements.size > 1000, "${statements.size} requests with oracleReencodeJson")
        for (kind in listOf("MATCH", "UNION", "UNION ALL", "CALL {", "CREATE", "MERGE", "SET")) {
            assertTrue(statements.any { kind in it && "RETURN" in it }, "no statement with $kind that returns rows")
        }
    }

    private fun compiled(cypher: String, graph: GraphOptions, store: Store = Store.ORACLE) =
        CypherCompiler.compile(store, graph, cypher, parameters)
}
