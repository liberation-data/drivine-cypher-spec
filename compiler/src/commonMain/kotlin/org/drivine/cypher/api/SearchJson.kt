package org.drivine.cypher.api

import org.drivine.cypher.json.ArrayNode
import org.drivine.cypher.json.JsonNode
import org.drivine.cypher.json.ObjectNode

/**
 * A full-text search asked of [CompileJson.compile], the one entry the wasm build exports as `compile` and the shared library
 * as `drivine_compile`: a request with `search` in place of `cypher` is planned by [FullTextSearch.plan], and one with
 * `searchIndex` is given the catalog lookup [FullTextSearch.lookup] writes.
 *
 * ```json
 * {"store": "postgres", "graph": {"name": "g"},
 *  "search": {"labels": ["Chunk"], "properties": ["text"], "query": "how do I define an action", "index": {"trigram": true}}}
 * ```
 *
 * `store` is `postgres`, `oracle`, `sqlite`, `neo4j`, `memgraph` or `falkordb`; `graph`, with its `name`, is the SQL stores'.
 * `search` is [SearchRequest]: `labels` (or one `label`, not both), `properties`, `query`, `index` (`{"trigram": …}` on Postgres,
 * `{"oracleText": "<side table>" | null}` on Oracle, `{"name": …}` on Neo4j and Memgraph), and on a graph engine `topK`, and
 * `filtered`, `alias` and `scoreAlias` where they aren't false, `n` and `_score`. The answer is
 * `{"sql": …, "parameters": {…}, "warnings": […]}`, on a graph engine `cypher` in place of `sql`, or `{"refusal": …}`, or
 * `{"error": …}` where the request is no request.
 *
 * `searchIndex` is `{"label": …, "properties": […]}`, answered `{"sql": …, "parameters": {…}}`: on Postgres a row where the
 * index's trigram index is made, on Oracle the side table of its Oracle Text index; `sql` is null on a store that keeps no
 * record of it.
 */
@OptIn(org.drivine.DrivineInternalApi::class)
internal object SearchJson {

    fun answer(tree: ObjectNode): String {
        val written = try {
            val storeId = tree["store"]?.textValue() ?: throw IllegalArgumentException("A request names its store: $STORES.")
            val store = SearchStore.of(storeId) ?: throw IllegalArgumentException("No store $storeId: $STORES.")
            val graph = graph(tree, store)
            when (val search = tree["search"]) {
                null -> lookup(store, graph, tree["searchIndex"] as? ObjectNode ?: throw IllegalArgumentException("A request's searchIndex is {\"label\": …, \"properties\": […]}."))
                is ObjectNode -> plan(store, graph, request(search, store))
                else -> throw IllegalArgumentException("A request's search is a JSON object.")
            }
        } catch (e: Exception) {
            return CompileJson.error(e.message ?: e.toString())
        }
        return written
    }

    private fun plan(store: SearchStore, graph: GraphOptions, request: SearchRequest): String =
        when (val result = FullTextSearch.plan(store, graph, request)) {
            is Refusal -> CompileJson.refusal(result.message)
            is SearchPlan -> StringBuilder().apply {
                if (result.sql != null) { append("{\"sql\":"); TypedJson.write(result.sql, this) } else { append("{\"cypher\":"); TypedJson.write(result.cypher, this) }
                append(",\"parameters\":"); TypedJson.write(result.parameters, this, 2)
                append(",\"warnings\":"); TypedJson.write(result.warnings, this)
                append('}')
            }.toString()
        }

    private fun lookup(store: SearchStore, graph: GraphOptions, index: ObjectNode): String {
        val label = text(index, "label") ?: throw IllegalArgumentException("A request's searchIndex names its label.")
        val lookup = FullTextSearch.lookup(store, graph, label, texts(index, "properties", "searchIndex"))
        return StringBuilder().apply {
            append("{\"sql\":"); TypedJson.write(lookup?.sql, this)
            append(",\"parameters\":"); TypedJson.write(lookup?.parameters ?: emptyMap<String, Any?>(), this, 2)
            append('}')
        }.toString()
    }

    /** The graph a SQL store's search runs over; a graph engine's needs none. */
    private fun graph(tree: ObjectNode, store: SearchStore): GraphOptions {
        val graph = tree["graph"]?.takeUnless { it.isNull }
        if (graph == null && store.graphEngine) return GraphOptions("")
        require(graph is ObjectNode) { "A request gives its graph: {\"name\": …}." }
        val name = graph["name"]?.textValue() ?: if (store.graphEngine) "" else throw IllegalArgumentException("A request's graph has a name.")
        return GraphOptions(name)
    }

    private fun request(search: ObjectNode, store: SearchStore): SearchRequest {
        require(!(search.has("labels") && search.has("label"))) { "A request's search names its labels or its label, not both." }
        val labels = when {
            search.has("labels") -> texts(search, "labels", "search")
            else -> listOf(text(search, "label") ?: throw IllegalArgumentException("A request's search names its labels: \"labels\": […]."))
        }
        val index = search["index"]?.takeUnless { it.isNull }?.let {
            it as? ObjectNode ?: throw IllegalArgumentException("A request's search index is a JSON object.")
        }
        val topK = search["topK"]?.takeUnless { it.isNull }?.let {
            require(it.isIntegralNumber && it.longValue() in 0..Int.MAX_VALUE.toLong()) { "A request's search gives its topK as a whole number." }
            it.longValue().toInt()
        }
        return SearchRequest(
            labels = labels,
            properties = texts(search, "properties", "search"),
            query = text(search, "query") ?: throw IllegalArgumentException("A request's search gives its query as text."),
            index = SearchIndex(
                trigram = index?.let { flag(it, "trigram") } ?: false,
                oracleText = index?.let { text(it, "oracleText") },
                name = index?.let { text(it, "name") },
            ),
            topK = topK,
            filtered = flag(search, "filtered"),
            alias = text(search, "alias") ?: "n",
            scoreAlias = text(search, "scoreAlias") ?: "_score",
        )
    }

    /** The text at [key], null where it is left out or null; refused where it is no text. */
    private fun text(node: ObjectNode, key: String): String? {
        val value: JsonNode = node[key]?.takeUnless { it.isNull } ?: return null
        return value.textValue() ?: throw IllegalArgumentException("A request gives $key as text.")
    }

    /** The texts at [key], a JSON array of them. */
    private fun texts(node: ObjectNode, key: String, of: String): List<String> {
        val value = node[key] as? ArrayNode ?: throw IllegalArgumentException("A request's $of gives its $key as [\"…\", …].")
        return value.map { it.textValue() ?: throw IllegalArgumentException("A request's $of gives its $key as [\"…\", …].") }
    }

    /** The flag at [key], false where it is left out; refused where it is no boolean. */
    private fun flag(node: ObjectNode, key: String): Boolean {
        val value = node[key]?.takeUnless { it.isNull } ?: return false
        require(value.isBoolean) { "A request gives $key as true or false." }
        return value.booleanValue()
    }

    private const val STORES = "postgres, oracle, sqlite, neo4j, memgraph or falkordb"
}
