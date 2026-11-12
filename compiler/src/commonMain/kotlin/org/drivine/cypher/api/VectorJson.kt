package org.drivine.cypher.api

import org.drivine.cypher.api.SearchJson.graph
import org.drivine.cypher.api.SearchJson.text
import org.drivine.cypher.api.SearchJson.texts
import org.drivine.cypher.json.ObjectNode
import org.drivine.schema.SimilarityFunction

/**
 * A vector search asked of [CompileJson.compile], the one entry the wasm build exports as `compile` and the shared library as
 * `drivine_compile`: a request with `vector` in place of `cypher` is planned by [VectorSearch.plan].
 *
 * ```json
 * {"store": "postgres", "graph": {"name": "g"},
 *  "vector": {"labels": ["Doc"], "property": "embedding", "similarity": "COSINE", "size": 1536, "topK": 10, "index": {"dimensions": 1536}}}
 * ```
 *
 * `store` is `postgres`, `oracle`, `sqlite`, `neo4j`, `memgraph` or `falkordb`; `graph`, with its `name`, is the SQL stores'.
 * `vector` is [VectorRequest]: `labels` (or one `label`, not both), `property`, `similarity` (`COSINE` or `EUCLIDEAN`), `topK`,
 * and where they apply `searchK`, `size` (the query vector's, on a nearest search on a SQL store), `partitionLabel`, `index`
 * (`{"dimensions": …}` on Postgres and Oracle, `{"name": …}` on a graph engine, left out where there's none), `among`
 * (`{"ids": […], "idProperty": …, "threshold": …}`), and on a graph engine `alias` and `scoreAlias` where they aren't `n` and
 * `_score`. The answer is `{"sql": …, "parameters": {…}, "vector": …, "labelCheck": …, "indexK": …, "warnings": […]}`, on a
 * graph engine `cypher` in place of `sql`, or `{"refusal": …}`, or `{"error": …}` where the request is no request. `vector`
 * is the parameter the library binds its query vector as, null among ids; `labelCheck` is null but on a graph engine's
 * nearest search of a root the index's label alone doesn't make.
 */
@OptIn(org.drivine.DrivineInternalApi::class)
internal object VectorJson {

    fun answer(tree: ObjectNode): String {
        val written = try {
            val storeId = tree["store"]?.textValue() ?: throw IllegalArgumentException("A request names its store: $STORES.")
            val store = SearchStore.of(storeId) ?: throw IllegalArgumentException("No store $storeId: $STORES.")
            val vector = tree["vector"] as? ObjectNode ?: throw IllegalArgumentException("A request's vector is a JSON object.")
            plan(store, graph(tree, store), request(vector))
        } catch (e: Exception) {
            return CompileJson.error(e.message ?: e.toString())
        }
        return written
    }

    private fun plan(store: SearchStore, graph: GraphOptions, request: VectorRequest): String =
        when (val result = VectorSearch.plan(store, graph, request)) {
            is Refusal -> CompileJson.refusal(result.message)
            is VectorPlan -> StringBuilder().apply {
                if (result.sql != null) { append("{\"sql\":"); TypedJson.write(result.sql, this) } else { append("{\"cypher\":"); TypedJson.write(result.cypher, this) }
                append(",\"parameters\":"); TypedJson.write(result.parameters, this, 2)
                append(",\"vector\":"); TypedJson.write(result.vector, this)
                append(",\"labelCheck\":"); TypedJson.write(result.labelCheck, this)
                append(",\"indexK\":").append(result.indexK)
                append(",\"warnings\":"); TypedJson.write(result.warnings, this)
                append('}')
            }.toString()
        }

    private fun request(vector: ObjectNode): VectorRequest {
        require(!(vector.has("labels") && vector.has("label"))) { "A request's vector names its labels or its label, not both." }
        val labels = when {
            vector.has("labels") -> texts(vector, "labels", "vector")
            else -> listOf(text(vector, "label") ?: throw IllegalArgumentException("A request's vector names its labels: \"labels\": […]."))
        }
        val similarity = text(vector, "similarity")?.let { given ->
            SimilarityFunction.entries.firstOrNull { it.name == given } ?: throw IllegalArgumentException("A request's vector gives its similarity as COSINE or EUCLIDEAN.")
        } ?: throw IllegalArgumentException("A request's vector gives its similarity as COSINE or EUCLIDEAN.")
        val index = objectAt(vector, "index")
        val among = objectAt(vector, "among")?.let { among ->
            VectorAmong(
                ids = texts(among, "ids", "among"),
                idProperty = text(among, "idProperty") ?: throw IllegalArgumentException("A request's among names its idProperty."),
                threshold = among["threshold"]?.takeUnless { it.isNull }?.let {
                    (TypedJson.value(it) as? Number)?.toDouble() ?: throw IllegalArgumentException("A request's among gives its threshold as a number.")
                },
            )
        }
        return VectorRequest(
            labels = labels,
            property = text(vector, "property") ?: throw IllegalArgumentException("A request's vector names its property."),
            similarity = similarity,
            topK = count(vector, "topK") ?: throw IllegalArgumentException("A request's vector gives its topK."),
            searchK = count(vector, "searchK"),
            size = count(vector, "size"),
            partitionLabel = text(vector, "partitionLabel"),
            index = VectorIndex(dimensions = index?.let { count(it, "dimensions") }, name = index?.let { text(it, "name") }),
            among = among,
            alias = text(vector, "alias") ?: "n",
            scoreAlias = text(vector, "scoreAlias") ?: "_score",
        )
    }

    /** The JSON object at [key], null where it is left out or null; refused where it is no object. */
    private fun objectAt(node: ObjectNode, key: String): ObjectNode? =
        node[key]?.takeUnless { it.isNull }?.let { it as? ObjectNode ?: throw IllegalArgumentException("A request's vector gives $key as a JSON object.") }

    /** The whole number at [key], null where it is left out or null; refused where it is none, or below 0. */
    private fun count(node: ObjectNode, key: String): Int? = node[key]?.takeUnless { it.isNull }?.let {
        require(it.isIntegralNumber && it.longValue() in 0..Int.MAX_VALUE.toLong()) { "A request gives $key as a whole number." }
        it.longValue().toInt()
    }

    private const val STORES = "postgres, oracle, sqlite, neo4j, memgraph or falkordb"
}
