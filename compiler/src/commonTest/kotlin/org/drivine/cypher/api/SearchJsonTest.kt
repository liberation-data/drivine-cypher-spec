package org.drivine.cypher.api

import org.drivine.cypher.json.CypherJson
import org.drivine.cypher.json.ObjectNode
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * A full-text search asked of [CompileJson.compile] ([SearchJson]) where the golden doesn't reach: a request that is no
 * request, the routing beside a `cypher` request, a label given alone, a blank index name, and [FullTextSearch.indexLimit].
 */
@OptIn(org.drivine.DrivineInternalApi::class)
class SearchJsonTest {

    private fun search(store: String, search: String, graph: String? = "{\"name\": \"g\"}") =
        CompileJson.compile("{\"store\": \"$store\"${graph?.let { ", \"graph\": $it" } ?: ""}, \"search\": $search}")

    private fun error(message: String) = "{\"error\":${CypherJson.writeValueAsString(message)}}"

    @Test
    fun aSearchRequestThatIsNoRequestSaysWhatItLacks() {
        val cases = listOf(
            search("redis", "{\"labels\": [\"A\"], \"properties\": [\"t\"], \"query\": \"x\"}") to
                "No store redis: postgres, oracle, sqlite, neo4j, memgraph or falkordb.",
            search("postgres", "{\"labels\": [\"A\"], \"properties\": [\"t\"], \"query\": \"x\"}", graph = null) to
                "A request gives its graph: {\"name\": …}.",
            search("postgres", "{\"labels\": [\"A\"], \"properties\": [\"t\"]}") to "A request's search gives its query as text.",
            search("postgres", "{\"labels\": [\"A\"], \"properties\": [\"t\"], \"query\": 1}") to "A request gives query as text.",
            search("postgres", "{\"properties\": [\"t\"], \"query\": \"x\"}") to "A request's search names its labels: \"labels\": […].",
            search("postgres", "{\"labels\": \"A\", \"properties\": [\"t\"], \"query\": \"x\"}") to "A request's search gives its labels as [\"…\", …].",
            search("postgres", "{\"labels\": [\"A\"], \"label\": \"A\", \"properties\": [\"t\"], \"query\": \"x\"}") to
                "A request's search names its labels or its label, not both.",
            search("postgres", "{\"labels\": [], \"properties\": [\"t\"], \"query\": \"x\"}") to "A full-text search names the root's labels.",
            search("postgres", "{\"labels\": [\"A\"], \"properties\": [], \"query\": \"x\"}") to "A full-text search names the properties its index covers.",
            search("postgres", "{\"labels\": [\"A\"], \"properties\": [\"t\"], \"query\": \"x\", \"index\": {\"trigram\": \"yes\"}}") to
                "A request gives trigram as true or false.",
            search("postgres", "{\"labels\": [\"A\"], \"properties\": [\"t\"], \"query\": \"x\", \"index\": []}") to
                "A request's search index is a JSON object.",
            search("oracle", "[1]") to "A request's search is a JSON object.",
            search("neo4j", "{\"labels\": [\"A\"], \"properties\": [\"t\"], \"query\": \"x\"}", graph = null) to
                "A full-text search on neo4j gives its topK.",
            search("neo4j", "{\"labels\": [\"A\"], \"properties\": [\"t\"], \"query\": \"x\", \"topK\": -1}", graph = null) to
                "A request's search gives its topK as a whole number.",
            search("neo4j", "{\"labels\": [\"A\"], \"properties\": [\"t\"], \"query\": \"x\", \"topK\": 1.5}", graph = null) to
                "A request's search gives its topK as a whole number.",
            CompileJson.compile("{\"store\": \"postgres\", \"graph\": {\"name\": \"g\"}, \"searchIndex\": {\"properties\": [\"t\"]}}") to
                "A request's searchIndex names its label.",
        )
        for ((answer, message) in cases) assertEquals(error(message), answer)
    }

    @Test
    fun aRequestAsksForOneOfCypherSearchAndSearchIndex() {
        val search = "{\"labels\": [\"A\"], \"properties\": [\"t\"], \"query\": \"x\"}"
        val both = listOf(
            "{\"store\": \"postgres\", \"graph\": {\"name\": \"g\"}, \"cypher\": \"RETURN 1\", \"search\": $search}",
            "{\"store\": \"postgres\", \"graph\": {\"name\": \"g\"}, \"search\": $search, \"searchIndex\": {\"label\": \"A\", \"properties\": [\"t\"]}}",
        )
        for (request in both) assertEquals(error("A request gives one of cypher, search and searchIndex."), CompileJson.compile(request), request)
        // Neither is a Cypher request that lacks its statement; a Cypher request alone is compiled.
        assertEquals(error("A request gives its statement as cypher."), CompileJson.compile("{\"store\": \"postgres\", \"graph\": {\"name\": \"g\"}}"))
        assertTrue(CompileJson.compile("{\"store\": \"postgres\", \"graph\": {\"name\": \"g\"}, \"cypher\": \"RETURN 1\"}").startsWith("{\"sql\":"))
    }

    @Test
    fun oneLabelIsTheLabelsOfOne() {
        for (store in listOf("postgres", "oracle", "sqlite", "neo4j", "memgraph", "falkordb")) {
            val one = search(store, "{\"label\": \"Chunk\", \"properties\": [\"text\"], \"query\": \"graph\", \"topK\": 5}")
            assertEquals(search(store, "{\"labels\": [\"Chunk\"], \"properties\": [\"text\"], \"query\": \"graph\", \"topK\": 5}"), one, store)
            assertTrue(one.startsWith(if (store in listOf("neo4j", "memgraph", "falkordb")) "{\"cypher\":" else "{\"sql\":"), "$store: $one")
        }
    }

    @Test
    fun aGraphNameTheStoreCantTakeIsRefusedAndAGraphEngineNeedsNone() {
        val refused = CypherJson.readTree(search("postgres", "{\"labels\": [\"A\"], \"properties\": [\"t\"], \"query\": \"x\"}", "{\"name\": \"9g\"}")) as ObjectNode
        assertTrue(refused.has("refusal") && refused["refusal"]!!.textValue()!!.contains("9g"), "$refused")
        val head = CypherJson.readTree(search("falkordb", "{\"labels\": [\"A\"], \"properties\": [\"t\"], \"query\": \"x\", \"topK\": 3}", graph = null)) as ObjectNode
        assertTrue(head["cypher"]!!.textValue()!!.startsWith("CALL db.idx.fulltext.queryNodes('A', \$_cy_ft1)"), "$head")
        assertEquals(mapOf("_cy_ft1" to "x", "_cy_ft2" to 3L), TypedJson.value(head["parameters"]!!))
        // A query with no word to score is the SQL stores' refusal; a graph engine is given it as it is.
        assertTrue(search("neo4j", "{\"labels\": [\"A\"], \"properties\": [\"t\"], \"query\": \"-only\", \"topK\": 3}", graph = null).startsWith("{\"cypher\":"))
    }

    @Test
    fun aBlankIndexNameIsNoneAsDrivine4jReadsIt() {
        for (store in listOf("neo4j", "memgraph")) {
            val named = { name: String -> search(store, "{\"labels\": [\"A\"], \"properties\": [\"t\", \"u\"], \"query\": \"x\", \"topK\": 3, \"index\": {\"name\": $name}}", graph = null) }
            assertEquals(named("null"), named("\"\""), store)
            assertEquals(named("\"A_t_u_fulltext\""), named("\" \""), store)
            assertTrue(named("\"\"").contains("'A_t_u_fulltext'"), store)
        }
    }

    @Test
    fun theLookupIsNullWhereTheStoreKeepsNoRecordOfTheIndex() {
        for (store in listOf("sqlite", "neo4j", "memgraph", "falkordb")) {
            assertEquals(
                "{\"sql\":null,\"parameters\":{}}",
                CompileJson.compile("{\"store\": \"$store\", \"graph\": {\"name\": \"g\"}, \"searchIndex\": {\"label\": \"A\", \"properties\": [\"t\"]}}"),
                store,
            )
            assertEquals(null, FullTextSearch.lookup(SearchStore.of(store)!!, GraphOptions("g"), "A", listOf("t")))
        }
        val oracle = CypherJson.readTree(CompileJson.compile("{\"store\": \"oracle\", \"graph\": {\"name\": \"g\"}, \"searchIndex\": {\"label\": \"A\", \"properties\": [\"t\", \"u\\\"v\"]}}")) as ObjectNode
        assertEquals(mapOf("_cy_ft1" to "FULLTEXT_INDEX", "_cy_ft2" to "A", "_cy_ft3" to "[\"t\",\"u\\\"v\"]"), TypedJson.value(oracle["parameters"]!!))
    }

    @Test
    fun aFilteredSearchAsksItsIndexForTenTimesAsManyHitsAndNoMoreThanAnInt() {
        assertEquals(25, FullTextSearch.indexLimit(25, filtered = false))
        assertEquals(250, FullTextSearch.indexLimit(25, filtered = true))
        assertEquals(Int.MAX_VALUE, FullTextSearch.indexLimit(Int.MAX_VALUE, filtered = true))
        assertEquals(Int.MAX_VALUE, FullTextSearch.indexLimit(Int.MAX_VALUE, filtered = false))
    }
}
