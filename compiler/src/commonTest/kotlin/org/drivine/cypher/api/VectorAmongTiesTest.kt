package org.drivine.cypher.api

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Of two neighbours that score alike in a search among ids, the one placed first among the ids comes first, on every store
 * and every way the search goes (liberation-data/drivine-cypher-spec#412): the library gives the ids in its id type's own
 * order, so the tie goes by that order, not by which node a store wrote first. `among.yaml`'s case "A tie" pins it.
 */
@OptIn(org.drivine.DrivineInternalApi::class)
class VectorAmongTiesTest {

    private val graph = GraphOptions("g")

    private fun request(neighbours: AmongNeighbours, searchK: Int? = null, dimensions: Int? = null, ids: List<String> = listOf("a", "b", "c")) = VectorRequest(
        labels = listOf("Doc"), property = "embedding", similarity = org.drivine.schema.SimilarityFunction.COSINE, topK = 5, searchK = searchK,
        index = VectorIndex(dimensions = dimensions), among = VectorAmong(ids, "id", null, neighbours),
    )

    private fun plan(store: SearchStore, request: VectorRequest): VectorPlan = VectorSearch.plan(store, graph, request) as VectorPlan

    private val ways = listOf(null to null, 20 to null, 20 to 3)

    @Test
    fun aSqlStoreBreaksATieByPlaceThenNodeIdBothWhereItCutsAndWhereItAggregates() {
        for (store in listOf(SearchStore.POSTGRES, SearchStore.ORACLE, SearchStore.SQLITE)) for (neighbours in listOf(AmongNeighbours.All, AmongNeighbours.After)) {
            for ((searchK, dimensions) in ways) {
                val sql = plan(store, request(neighbours, searchK, dimensions)).sql!!
                val cut = if (store == SearchStore.POSTGRES) "ORDER BY score DESC, o.pos, o.nid" else "ORDER BY p.score DESC, p.pos, p.nid"
                assertTrue(cut in sql, "${store.id} $neighbours $searchK $dimensions:\n$sql")
                assertTrue("ORDER BY r.score DESC, r.pos, r.nid" in sql, "${store.id} $neighbours $searchK $dimensions:\n$sql")
                assertTrue("o.nid\"" !in sql && ", o.nid)" !in sql, sql)
                assertTrue(", g.pos\n    FROM g_node c\n    JOIN (" in sql, "${store.id}: every search reads places:\n$sql")
            }
        }
    }

    @Test
    fun aGraphEngineBreaksATieByPlaceWhicheverWayTheSearchGoes() {
        for (store in listOf(SearchStore.NEO4J, SearchStore.MEMGRAPH, SearchStore.FALKORDB)) for (neighbours in listOf(AmongNeighbours.All, AmongNeighbours.After)) {
            val exact = plan(store, request(neighbours)).cypher!!
            assertTrue("  ORDER BY _score DESC, _next\n" in exact, "${store.id} $neighbours:\n$exact")
            if (store == SearchStore.FALKORDB) continue
            val indexed = plan(store, request(neighbours, searchK = 20))
            val cypher = indexed.cypher!!
            assertTrue("\nORDER BY _score DESC, _place\n" in cypher, "${store.id} $neighbours:\n$cypher")
            // The places are read through a variable bound once: Memgraph copies a parameter at each row that reads it.
            assertTrue(cypher.startsWith("WITH \$_cy_vec2 AS _places\n") && cypher.split("\$_cy_vec2").size == 2, cypher)
            assertEquals(mapOf("a" to 0, "b" to 1, "c" to 2), indexed.parameters["_cy_vec2"], store.id)
        }
    }

    @Test
    fun throughTheIndexAHitWhoseIdIsNoTextHasNoPlaceAndIsNoRoot() {
        for (store in listOf(SearchStore.NEO4J, SearchStore.MEMGRAPH)) for (neighbours in listOf(AmongNeighbours.All, AmongNeighbours.After)) {
            val given = request(neighbours, searchK = 20)
            val cypher = plan(store, given.copy(among = given.among!!.copy(idProperty = "the id"))).cypher!!
            // A map read by a key that is no text is an error on every engine, so a hit whose id is a number has no place.
            assertTrue(", CASE WHEN toString(n.`the id`) = n.`the id` THEN _places[n.`the id`] END AS _place\n" in cypher, "${store.id}:\n$cypher")
            assertTrue("collect({id: n.`the id`, score: _score, place: _place}) AS _found" in cypher, cypher)
            val kept = if (neighbours == AmongNeighbours.After) "m.place > _pos" else "m.place IS NOT NULL AND m.place <> _pos"
            assertTrue("[m IN _found WHERE $kept | {id: m.id, score: m.score}][0..\$_cy_vec4]" in cypher, "${store.id} $neighbours:\n$cypher")
        }
    }

    @Test
    fun throughTheIndexFalkorDbBindsNoPlacesAndLeavesATieToTheIndex() {
        // A FalkorDB client writes a map parameter's keys into the statement, and each library refuses some, so a map keyed by
        // the ids can't be bound there (liberation-data/drivine-cypher-spec#416): the plan is as it was, ordered by score alone.
        for (neighbours in listOf(AmongNeighbours.All, AmongNeighbours.After)) {
            val indexed = plan(SearchStore.FALKORDB, request(neighbours, searchK = 20))
            val cypher = indexed.cypher!!
            assertTrue("_places" !in cypher && "\nORDER BY _score DESC\n" in cypher, "$neighbours:\n$cypher")
            assertTrue(indexed.parameters.values.none { it is Map<*, *> }, "${indexed.parameters}")
            val kept = if (neighbours == AmongNeighbours.After) "m.id IN \$_cy_vec1[(_pos + 1)..]" else "m.id IN \$_cy_vec1 AND m.id <> _seedId"
            assertTrue("[m IN _found WHERE $kept][0..\$_cy_vec3]" in cypher, "$neighbours:\n$cypher")
        }
    }

    @Test
    fun aRepeatedIdIsPlacedOnceAtItsFirstPlace() {
        for (store in listOf(SearchStore.NEO4J, SearchStore.MEMGRAPH, SearchStore.FALKORDB)) for (neighbours in listOf(AmongNeighbours.All, AmongNeighbours.After)) {
            val repeated = plan(store, request(neighbours, searchK = 20, ids = listOf("a", "b", "a", "c", "b")))
            assertEquals(plan(store, request(neighbours, searchK = 20)), repeated, store.id)
        }
    }

    @Test
    fun overJsonThePlacesComeBackAsAMapTaggedWhereAnIdStartsWithADollar() {
        for (store in listOf(SearchStore.NEO4J, SearchStore.MEMGRAPH)) {
            val raw = CompileJson.compile(
                "{\"store\": \"${store.id}\", \"vector\": {\"labels\": [\"Doc\"], \"property\": \"embedding\", \"similarity\": \"COSINE\", " +
                    "\"topK\": 5, \"searchK\": 20, \"among\": {\"ids\": [\"a\", \"${'$'}b\"], \"idProperty\": \"id\"}}}",
            )
            assertTrue("\"_cy_vec2\":{\"${'$'}map\":{\"a\":0,\"${'$'}b\":1}}" in raw, "${store.id}:\n$raw")
        }
    }
}
