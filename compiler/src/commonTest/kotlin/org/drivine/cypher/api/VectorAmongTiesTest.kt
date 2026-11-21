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
            val indexed = plan(store, request(neighbours, searchK = 20))
            val cypher = indexed.cypher!!
            assertTrue("\nORDER BY _score DESC, _place\n" in cypher, "${store.id} $neighbours:\n$cypher")
            // The places are read through a variable bound once: Memgraph copies a parameter at each row that reads it.
            assertTrue(cypher.startsWith("WITH \$_cy_vec2 AS _places\n") && cypher.split("\$_cy_vec2").size == 2, cypher)
            assertEquals(mapOf("a" to 0, "b" to 1, "c" to 2), indexed.parameters["_cy_vec2"], store.id)
        }
    }

    @Test
    fun aRepeatedIdIsPlacedOnceAtItsFirstPlace() {
        for (store in listOf(SearchStore.NEO4J, SearchStore.MEMGRAPH, SearchStore.FALKORDB)) for (neighbours in listOf(AmongNeighbours.All, AmongNeighbours.After)) {
            val repeated = plan(store, request(neighbours, searchK = 20, ids = listOf("a", "b", "a", "c", "b")))
            assertEquals(plan(store, request(neighbours, searchK = 20)), repeated, store.id)
        }
    }
}
