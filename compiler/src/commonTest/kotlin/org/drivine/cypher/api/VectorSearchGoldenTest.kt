package org.drivine.cypher.api

import org.drivine.cypher.json.ArrayNode
import org.drivine.cypher.json.CypherJson
import org.drivine.cypher.json.JsonNode
import org.drivine.cypher.json.ObjectNode
import org.drivine.cypher.number.oracleFile
import org.drivine.cypher.number.target
import org.drivine.schema.SimilarityFunction
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Vector search's golden, `vector-golden.json` (`src/commonTest/resources`, which `./gradlew jvmOracle` copies beside the
 * JVM's answers for every target): drivine4j's own statements, written by drivine4j's code at the commit the golden names
 * (its selects' `nearestTo`, `nearestThrough` and `nearestAmong`, its grammars' `vectorSearchHead` and its
 * `indexedNodeLabelCheck`, and its planner's `planAmong`), on each store, exact and through its index, by cosine and
 * Euclidean, with and without a partition and an over-fetch, for roots of one label and of two, and of names with a quote, a
 * backslash and a backtick in them. Only the parameters' names were changed, to the module's `_cy_vec<n>`, and the query
 * vector's binding left out, as the library binds it.
 *
 * [VectorSearch.plan] gives each statement byte for byte, and its parameters, and so does [CompileJson.compile] for the
 * same request as JSON. On a SQL store drivine4j's nearest statement selects each candidate's columns beside its score: the
 * module gives `nid` and `score` alone, and each library joins them back for its projection. So the golden's statement is
 * the module's with those columns put back ([projected]), and the rest of it byte for byte.
 */
@OptIn(org.drivine.DrivineInternalApi::class)
class VectorSearchGoldenTest {

    private val golden: JsonNode = CypherJson.readTree(oracleFile("vector-golden.json"))

    private val entries: List<ObjectNode> = (golden["entries"] as ArrayNode).map { it as ObjectNode }

    @Test
    fun theGoldenHoldsEveryStoreAndEachWayASearchGoes() {
        val shapes = entries.map { entry ->
            val request = entry["request"] as ObjectNode
            val vector = request["vector"] as ObjectNode
            val store = request["store"]!!.textValue()!!
            val way = when {
                vector.has("among") -> "among " + (if (vector.has("index")) "indexed" else "exact")
                store in setOf("neo4j", "memgraph", "falkordb") -> "nearest"
                vector["index"]?.get("dimensions")?.longValue() == vector["size"]!!.longValue() -> "nearest indexed"
                else -> "nearest exact"
            }
            listOf(store, way, vector["similarity"]!!.textValue(), vector.has("partitionLabel").toString(), vector["labels"]!!.size().toString())
        }.toSet()
        for (store in listOf("postgres", "oracle")) for (way in listOf("nearest exact", "nearest indexed", "among exact", "among indexed")) {
            for (similarity in SimilarityFunction.entries) for (labels in listOf("1", "2")) {
                val partitions = if (way.startsWith("nearest")) listOf("false", "true") else listOf("false")
                for (partition in partitions) assertTrue(listOf(store, way, similarity.name, partition, labels) in shapes, "the golden lacks $store $way ${similarity.name} $partition $labels")
            }
        }
        for (store in listOf("sqlite", "neo4j", "memgraph", "falkordb")) for (similarity in SimilarityFunction.entries) for (labels in listOf("1", "2")) {
            val nearest = if (store == "sqlite") "nearest exact" else "nearest"
            for (partition in listOf("false", "true")) assertTrue(listOf(store, nearest, similarity.name, partition, labels) in shapes, "the golden lacks $store $nearest ${similarity.name} $partition $labels")
            assertTrue(listOf(store, "among exact", similarity.name, "false", labels) in shapes, "the golden lacks $store among ${similarity.name} $labels")
        }
        // Names that must be written as what they are: a label, a property and an id with a quote, a backslash and a backtick.
        assertTrue(entries.any { it["request"]!!["vector"]!!["labels"]!!.any { label -> label.textValue()!!.contains('\'') && label.textValue()!!.contains('\\') } })
        assertTrue(entries.any { it["request"]!!["vector"]!!["property"]!!.textValue()!!.contains('"') })
        println("Vector search golden on $target: ${entries.size} entries")
    }

    @Test
    fun eachPlanIsDrivine4jsStatementAndParameters() {
        val wrong = mutableListOf<String>()
        for (entry in entries) {
            val request = entry["request"] as ObjectNode
            val answer = entry["answer"] as ObjectNode
            val store = SearchStore.of(request["store"]!!.textValue()!!)!!
            val graph = GraphOptions(request["graph"]?.get("name")?.textValue() ?: "")
            val vector = vectorRequest(request["vector"] as ObjectNode)
            val problem = when (val result = VectorSearch.plan(store, graph, vector)) {
                is Refusal -> "refused: ${result.message}"
                is VectorPlan -> unlikeAnswer(answer, store, vector, result.sql, result.cypher, result.labelCheck, result.vector)
                    ?: unlikeParameters(answer["parameters"]!!, result.parameters)
            }
            if (problem != null) wrong += "${CypherJson.writeValueAsString(request)}: $problem"
        }
        assertTrue(wrong.isEmpty(), "${wrong.size} of ${entries.size} unlike drivine4j's, such as:\n" + wrong.take(5).joinToString("\n\n"))
    }

    @Test
    fun eachJsonRequestIsAnsweredWithDrivine4jsStatementAndParameters() {
        val wrong = mutableListOf<String>()
        for (entry in entries) {
            val request = entry["request"] as ObjectNode
            val answer = entry["answer"] as ObjectNode
            val store = SearchStore.of(request["store"]!!.textValue()!!)!!
            val got = CypherJson.readTree(CompileJson.compile(CypherJson.writeValueAsString(request))) as ObjectNode
            val problem = if (got.has("error") || got.has("refusal")) "answered $got" else {
                unlikeAnswer(
                    answer, store, vectorRequest(request["vector"] as ObjectNode), got["sql"]?.textValue(), got["cypher"]?.textValue(),
                    got["labelCheck"]?.textValue(), got["vector"]?.textValue(),
                ) ?: unlikeParameters(answer["parameters"]!!, TypedJson.value(got["parameters"]!!) as Map<*, *>)
                    ?: if (got["warnings"] is ArrayNode && got["warnings"]!!.size() == 0) null else "warnings ${got["warnings"]}"
            }
            if (problem != null) wrong += "${CypherJson.writeValueAsString(request)}: $problem"
        }
        assertTrue(wrong.isEmpty(), "${wrong.size} unlike drivine4j's, such as:\n" + wrong.take(5).joinToString("\n\n"))
    }

    /** What of the module's answer is unlike drivine4j's [answer], its parameters aside; null where none is. */
    private fun unlikeAnswer(answer: ObjectNode, store: SearchStore, request: VectorRequest, sql: String?, cypher: String?, labelCheck: String?, vector: String?): String? = when {
        answer.has("cypher") -> unlike(answer["cypher"]!!.textValue(), cypher)
            ?: unlike(answer["labelCheck"]?.textValue(), labelCheck)
            ?: unlike(answer["vector"]?.textValue(), vector)
        answer.has("sql") -> unlike(answer["sql"]!!.textValue(), sql) ?: unlike(null, vector)
        else -> unlike(answer["drivine4j"]!!.textValue(), sql?.let { projected(it, store, request) }) ?: unlike(answer["vector"]!!.textValue(), vector)
    }

    /**
     * The module's ranked candidates, [sql], as drivine4j selects them: each candidate's columns beside its `nid` and score
     * (drivine4j's `PostgresSql.columns`, `OracleSql.columns`, `SqliteSql.columns`), and on Oracle without its index the
     * property graph's columns they read. Each is put back where drivine4j has it, and found there once.
     */
    private fun projected(sql: String, store: SearchStore, request: VectorRequest): String = when (store) {
        SearchStore.ORACLE -> if (request.index.dimensions == request.size) {
            sql.once("SELECT c.nid, ", "SELECT c.nid, c.labels, c.props, c.stamp, $ORACLE_DOC AS doc, ")
        } else {
            sql.once("SELECT c.nid, ", "SELECT c.nid, c.labels, c.props, c.stamp, c.doc, ")
                .once("COLUMNS (c.nid, c.props)) c", "COLUMNS (c.nid, c.labels, c.props, c.stamp, c.doc)) c")
        }
        else -> sql.once("SELECT c.nid, ", "SELECT c.nid, c.labels, c.props, c.stamp, ")
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

    private fun vectorRequest(vector: ObjectNode): VectorRequest {
        val index = vector["index"] as? ObjectNode
        val among = vector["among"] as? ObjectNode
        return VectorRequest(
            labels = texts(vector["labels"]!!),
            property = vector["property"]!!.textValue()!!,
            similarity = SimilarityFunction.valueOf(vector["similarity"]!!.textValue()!!),
            topK = vector["topK"]!!.longValue().toInt(),
            searchK = vector["searchK"]?.longValue()?.toInt(),
            size = vector["size"]?.longValue()?.toInt(),
            partitionLabel = vector["partitionLabel"]?.textValue(),
            index = VectorIndex(dimensions = index?.get("dimensions")?.longValue()?.toInt(), name = index?.get("name")?.textValue()),
            among = among?.let { VectorAmong(texts(it["ids"]!!), it["idProperty"]!!.textValue()!!, it["threshold"]?.doubleValue()) },
            alias = vector["alias"]?.textValue() ?: "n",
        )
    }

    private companion object {
        /** The document Oracle's graph makes of a node's row, as drivine4j's projection reads it (`OracleSql.transform`). */
        const val ORACLE_DOC = "JSON_TRANSFORM(c.props, SET '\$.labels' = c.labels, SET '\$.\"__drivine.stamp\"' = c.stamp)"
    }
}
