package org.drivine.cypher.api

import org.drivine.cypher.store.IdSql
import org.drivine.cypher.store.IndexKey
import org.drivine.schema.SimilarityFunction
import kotlin.test.Test
import kotlin.test.assertTrue

/**
 * On a SQL store a search among ids finds each root as a lookup by its id finds it (drivine4j#868): the ids are each id's
 * JSON, the roots are found through the index over the id, joined to their places by the index's own expressions, and each
 * id is read back as [IdSql.keyText] reads it, so a temporal id is found by its tag and an integer as an integer.
 * `among.yaml`'s case "Ids of another type" pins it on every store.
 */
@OptIn(org.drivine.DrivineInternalApi::class)
class VectorAmongTypedIdsTest {

    private fun request(neighbours: AmongNeighbours, searchK: Int?, dimensions: Int?, idProperty: String) = VectorRequest(
        labels = listOf("Doc"), property = "embedding", similarity = SimilarityFunction.COSINE, topK = 5, searchK = searchK,
        index = VectorIndex(dimensions = dimensions), among = VectorAmong(listOf("\"a\"", "7"), idProperty, null, neighbours),
    )

    @Test
    fun eachRootIsFoundThroughTheIndexOverItsIdAndItsIdReadAsAKeyText() {
        val stores = mapOf(SearchStore.POSTGRES to Store.POSTGRES, SearchStore.ORACLE to Store.ORACLE, SearchStore.SQLITE to Store.SQLITE)
        for ((search, store) in stores) for (idProperty in listOf("id", "\$i'd")) for (neighbours in listOf(AmongNeighbours.All, AmongNeighbours.After)) {
            for ((searchK, dimensions) in listOf(null to null, 20 to 3)) {
                val sql = (VectorSearch.plan(search, GraphOptions("g"), request(neighbours, searchK, dimensions, idProperty)) as VectorPlan).sql!!
                val key = org.drivine.cypher.store.ValueLayout.escape(idProperty)
                val what = "${search.id} $idProperty $neighbours $searchK:\n$sql"
                assertTrue("${IdSql.keyText(store, "c", key)} AS id" in sql, what)
                val nodes = if (store == Store.SQLITE) "g_node" else null
                assertTrue(IdSql.hasIdIn(store, "c", key, "\$_cy_vec1", nodes, listOf("Doc")) in sql, what)
                val columns = IndexKey.column(store, "c", key)
                val on = if (store == Store.POSTGRES) "${columns.single()} = g.json" else "${columns[0]} = g.id_class AND ${columns[1]} = g.id_key"
                assertTrue(") g ON $on\n" in sql, what)
                // No id is compared as text any more: a temporal id's tag, or an integer, would not be equal to it.
                assertTrue("g.id =" !in sql, what)
            }
        }
    }
}
