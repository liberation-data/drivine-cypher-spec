package org.drivine.cypher.api

import org.drivine.cypher.json.ArrayNode
import org.drivine.cypher.json.CypherJson
import org.drivine.cypher.json.JsonNode
import org.drivine.cypher.json.ObjectNode
import org.drivine.cypher.number.oracleFile
import org.drivine.cypher.number.target
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.test.fail

/**
 * Full-text search's golden, `search-golden.json` (`src/commonTest/resources`, which `./gradlew jvmOracle` copies beside
 * the JVM's answers for every target): drivine4j's own statements, written by drivine4j's code at the commit the golden
 * names, for every query of drivine4j's `TextQueryTest` and `OracleSelectTest`, the 22 of its ranking test over
 * `docs-corpus.json`, and labels and properties of other shapes, on each store and in each state of its index. Only the
 * parameters' names were changed, drivine4j's `$_pN` to the module's `$_cy_ft<N+1>`.
 *
 * [FullTextSearch.plan] gives each statement byte for byte, and its parameters, and so does [CompileJson.compile] for the
 * same request as JSON. On a SQL store drivine4j's statement goes on past the ranked candidates, to its own projection of
 * each node's row: the module gives the candidates alone, `nid` and `score`, and each library joins them back for its
 * projection. So the golden's statement is the module's with that projection put back ([projected]), and the rest of it
 * byte for byte.
 */
@OptIn(org.drivine.DrivineInternalApi::class)
class FullTextSearchGoldenTest {

    private val golden: JsonNode = CypherJson.readTree(oracleFile("search-golden.json"))

    private val entries: List<ObjectNode> = (golden["entries"] as ArrayNode).map { it as ObjectNode }

    @Test
    fun theGoldenHoldsEveryStoreAndEachStateOfItsIndex() {
        val stores = entries.map { it["request"]!!["store"]!!.textValue()!! }.toSet()
        assertEquals(SearchStore.entries.map { it.id }.toSet(), stores)
        assertTrue(entries.size > 900, "${entries.size} entries")
        assertTrue(entries.any { it["answer"]!!.has("refusal") }, "a refused query")
        // Each state of the index the library may find: the lookup, none made, made, and on Neo4j and Memgraph named.
        val states = entries.map { entry ->
            val request = entry["request"] as ObjectNode
            request["store"]!!.textValue() to (request["searchIndex"]?.let { "lookup" } ?: request["search"]!!["index"]?.takeUnless { it.isNull }?.let { index ->
                when {
                    index.has("trigram") -> "trigram ${index["trigram"]!!.booleanValue()}"
                    index.has("oracleText") -> "oracleText ${if (index["oracleText"]!!.isNull) "none" else "made"}"
                    else -> "named"
                }
            } ?: "none given")
        }.toSet()
        val wanted = setOf(
            "postgres" to "lookup", "postgres" to "trigram false", "postgres" to "trigram true",
            "oracle" to "lookup", "oracle" to "oracleText none", "oracle" to "oracleText made",
            "sqlite" to "none given", "neo4j" to "named", "neo4j" to "none given", "memgraph" to "named", "memgraph" to "none given",
            "falkordb" to "none given",
        )
        assertTrue(states.containsAll(wanted), "the golden lacks ${wanted - states}")
        println("Full-text search golden on $target: ${entries.size} entries")
    }

    @Test
    fun eachPlanIsDrivine4jsStatementAndParameters() {
        val wrong = mutableListOf<String>()
        var checked = 0
        for (entry in entries) {
            val request = entry["request"] as ObjectNode
            val answer = entry["answer"] as ObjectNode
            val store = SearchStore.of(request["store"]!!.textValue()!!)!!
            val graph = GraphOptions(request["graph"]?.get("name")?.textValue() ?: "g")
            val problem: String? = if (request.has("searchIndex")) {
                val index = request["searchIndex"] as ObjectNode
                val lookup = FullTextSearch.lookup(store, graph, index["label"]!!.textValue()!!, texts(index["properties"]!!))
                    ?: fail("no lookup on ${store.id}")
                unlike(answer["sql"]!!.textValue(), lookup.sql) ?: unlikeParameters(answer["parameters"]!!, lookup.parameters)
            } else {
                when (val result = FullTextSearch.plan(store, graph, searchRequest(request["search"] as ObjectNode))) {
                    is Refusal -> if (answer.has("refusal")) unlike(answer["refusal"]!!.textValue(), result.message) else "refused: ${result.message}"
                    is SearchPlan -> when {
                        answer.has("refusal") -> "not refused: ${answer["refusal"]!!.textValue()}"
                        answer.has("cypher") -> unlike(answer["cypher"]!!.textValue(), result.cypher)
                        else -> unlike(answer["drivine4j"]!!.textValue(), result.sql?.let { projected(it, store, request["search"]!!["index"]) })
                    } ?: unlikeParameters(answer["parameters"]!!, result.parameters)
                }
            }
            checked++
            if (problem != null) wrong += "${CypherJson.writeValueAsString(request)}: $problem"
        }
        assertTrue(wrong.isEmpty(), "${wrong.size} of $checked unlike drivine4j's, such as:\n" + wrong.take(5).joinToString("\n\n"))
    }

    @Test
    fun eachJsonRequestIsAnsweredWithDrivine4jsStatementAndParameters() {
        val wrong = mutableListOf<String>()
        for (entry in entries) {
            val request = entry["request"] as ObjectNode
            val answer = entry["answer"] as ObjectNode
            val got = CypherJson.readTree(CompileJson.compile(CypherJson.writeValueAsString(request))) as ObjectNode
            val problem = when {
                answer.has("refusal") -> unlike(answer["refusal"]!!.textValue(), got["refusal"]?.textValue())
                answer.has("cypher") -> unlike(answer["cypher"]!!.textValue(), got["cypher"]?.textValue())
                request.has("searchIndex") -> unlike(answer["sql"]!!.textValue(), got["sql"]?.textValue())
                else -> {
                    val store = SearchStore.of(request["store"]!!.textValue()!!)!!
                    unlike(answer["drivine4j"]!!.textValue(), got["sql"]?.textValue()?.let { projected(it, store, request["search"]!!["index"]) })
                }
            } ?: if (answer.has("refusal")) null else {
                unlikeParameters(answer["parameters"]!!, TypedJson.value(got["parameters"]!!) as Map<*, *>)
                    ?: if (request.has("searchIndex") || got["warnings"] is ArrayNode && got["warnings"]!!.size() == 0) null else "warnings ${got["warnings"]}"
            }
            if (problem != null) wrong += "${CypherJson.writeValueAsString(request)}: $problem"
        }
        assertTrue(wrong.isEmpty(), "${wrong.size} unlike drivine4j's, such as:\n" + wrong.take(5).joinToString("\n\n"))
    }

    /**
     * The module's ranked candidates, [sql], as drivine4j's statement goes on with them, its projection of each node's row:
     * through BM25, the candidates joined back to the node table and each row's columns (drivine4j's `Bm25.ranked`); through
     * Oracle Text, each row's columns read with the candidates' scores (drivine4j's `OracleSelect.matchingThrough`). Each is
     * put back where drivine4j has it, and found there once.
     */
    private fun projected(sql: String, store: SearchStore, index: JsonNode?): String {
        val oracleText = store == SearchStore.ORACLE && index?.get("oracleText")?.textValue() != null
        return if (oracleText) {
            sql.once("SELECT h.nid, h.score / MAX(h.score) OVER () AS score\n", "SELECT h.nid, h.labels, h.props, h.stamp, h.doc, h.score / MAX(h.score) OVER () AS score\n")
                .once("\n    SELECT c.nid, k.score\n", "\n    SELECT c.nid, c.labels, c.props, c.stamp, $ORACLE_DOC AS doc, k.score\n")
        } else {
            val columns = if (store == SearchStore.ORACLE) "c.nid, c.labels, c.props, c.stamp, $ORACLE_DOC AS doc" else "c.nid, c.labels, c.props, c.stamp"
            sql.once("SELECT b.nid, b.score / MAX(b.score) OVER () AS score\n", "SELECT $columns, b.score / MAX(b.score) OVER () AS score\n")
                .once("\n) b\nWHERE b.score > 0", "\n) b\nJOIN g_node c ON c.nid = b.nid\nWHERE b.score > 0")
        }
    }

    /** [this] with [part] in place of [old], which it holds once. */
    private fun String.once(old: String, part: String): String {
        val at = indexOf(old)
        assertTrue(at >= 0 && indexOf(old, at + 1) < 0, "the ranked candidates hold ${CypherJson.writeValueAsString(old)} once:\n$this")
        return replaceRange(at, at + old.length, part)
    }

    private fun unlike(expected: String?, actual: String?): String? =
        if (expected == actual) null else "expected\n$expected\nbut was\n$actual"

    /** The parameters [actual] binds, unlike [expected], drivine4j's: their names, in order, and their values. */
    private fun unlikeParameters(expected: JsonNode, actual: Map<*, *>): String? {
        val wanted = (TypedJson.value(expected) as Map<*, *>).entries.map { (k, v) -> k to whole(v) }
        val given = actual.entries.map { (k, v) -> k to whole(v) }
        return if (wanted == given) null else "parameters expected $wanted but were $given"
    }

    /** A whole number as a Long, as JSON reads it back, so an Int bound and the golden's number compare. */
    private fun whole(value: Any?): Any? = if (value is Int) value.toLong() else value

    private fun texts(node: JsonNode): List<String> = (node as ArrayNode).map { it.textValue()!! }

    private fun searchRequest(search: ObjectNode): SearchRequest {
        val index = search["index"] as? ObjectNode
        return SearchRequest(
            labels = texts(search["labels"]!!),
            properties = texts(search["properties"]!!),
            query = search["query"]!!.textValue()!!,
            index = SearchIndex(
                trigram = index?.get("trigram")?.booleanValue() ?: false,
                oracleText = index?.get("oracleText")?.textValue(),
                name = index?.get("name")?.textValue(),
            ),
            topK = search["topK"]?.longValue()?.toInt(),
            filtered = search["filtered"]?.booleanValue() ?: false,
            alias = search["alias"]?.textValue() ?: "n",
            scoreAlias = search["scoreAlias"]?.textValue() ?: "_score",
        )
    }

    private companion object {
        /** The document Oracle's graph makes of a node's row, as drivine4j's projection reads it (`OracleSql.transform`). */
        const val ORACLE_DOC = "JSON_TRANSFORM(c.props, SET '\$.labels' = c.labels, SET '\$.\"__drivine.stamp\"' = c.stamp)"
    }
}
