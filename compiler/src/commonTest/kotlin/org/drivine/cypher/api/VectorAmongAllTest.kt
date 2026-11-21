package org.drivine.cypher.api

import org.drivine.schema.SimilarityFunction
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * A search among ids for every neighbour ([AmongNeighbours.All], the default): without `searchK` every pair of the roots is
 * measured, through no index, whatever index is made, so the answer is exact (liberation-data/drivine-cypher-spec#408); with
 * it each root asks the index, where one is made, and the plan warns that a root's neighbours can be incomplete.
 * `among.yaml` pins what each store gives.
 */
@OptIn(org.drivine.DrivineInternalApi::class)
class VectorAmongAllTest {

    private val graph = GraphOptions("g")

    private fun request(searchK: Int? = null, dimensions: Int? = null, similarity: SimilarityFunction = SimilarityFunction.COSINE) = VectorRequest(
        labels = listOf("Doc"), property = "embedding", similarity = similarity, topK = 5, searchK = searchK, index = VectorIndex(dimensions = dimensions),
        among = VectorAmong(listOf("a", "b", "c"), "id", 0.5),
    )

    private fun plan(store: SearchStore, request: VectorRequest): VectorPlan = VectorSearch.plan(store, graph, request) as VectorPlan

    @Test
    fun withoutSearchKASqlStoreMeasuresEveryPairAsWhereNoIndexIsMade() {
        // The statement is the one a store with no index made gives, which the golden pins; an index made changes nothing.
        for (store in listOf(SearchStore.POSTGRES, SearchStore.ORACLE, SearchStore.SQLITE)) for (similarity in SimilarityFunction.entries) {
            val measured = plan(store, request(searchK = 40, similarity = similarity))
            for (dimensions in listOf(null, 3)) {
                val planned = plan(store, request(dimensions = dimensions, similarity = similarity))
                assertEquals(measured.sql, planned.sql, "${store.id} ${similarity.name} $dimensions")
                assertEquals(0, planned.indexK, "${store.id}: no index is asked")
                assertEquals(emptyList(), planned.warnings, store.id)
            }
        }
    }

    @Test
    fun withoutSearchKAGraphEngineMeasuresEachRootAgainstEveryOtherPlace() {
        val neo4j = """
            |WITH ${'$'}_cy_vec1 AS _ids
            |UNWIND range(0, size(_ids) - 1) AS _pos
            |WITH _ids, _pos, _ids[_pos] AS _seedId
            |MATCH (seed:Doc)
            |WHERE seed.id = _seedId AND seed.embedding IS NOT NULL AND any(_x IN seed.embedding WHERE _x <> 0)
            |CALL (seed, _pos, _ids) {
            |  UNWIND [_at IN range(0, size(_ids) - 1) WHERE _at <> _pos] AS _next
            |  WITH seed, _next, _ids[_next] AS _nextId
            |  MATCH (n:Doc)
            |  WHERE n.id = _nextId AND n.embedding IS NOT NULL AND any(_x IN n.embedding WHERE _x <> 0) AND size(n.embedding) = size(seed.embedding)
            |  WITH n, _next, vector.similarity.cosine(seed.embedding, n.embedding) AS _score
            |  WHERE _score >= ${'$'}_cy_vec2
            |  WITH n, _next, _score
            |  ORDER BY _score DESC, _next
            |  LIMIT ${'$'}_cy_vec3
            |  RETURN collect({id: n.id, score: _score}) AS near
            |}
            |RETURN {seed: _seedId, near: near} AS row
        """.trimMargin()
        val planned = plan(SearchStore.NEO4J, request())
        assertEquals(neo4j, planned.cypher)
        assertEquals(mapOf("_cy_vec1" to listOf("a", "b", "c"), "_cy_vec2" to 0.5, "_cy_vec3" to 5), planned.parameters)
        for (store in listOf(SearchStore.NEO4J, SearchStore.MEMGRAPH, SearchStore.FALKORDB)) {
            val every = plan(store, request())
            // The statement for each pair once, its neighbours every other place in place of the places after the root's.
            val after = plan(store, request().let { it.copy(among = it.among!!.copy(neighbours = AmongNeighbours.After)) })
            val others = "[_at IN range(0, size(_ids) - 1) WHERE _at <> _pos]"
            assertEquals(after.cypher!!.replace("range(_pos + 1, size(_ids) - 1)", others), every.cypher, store.id)
            assertTrue("CALL db.index" !in every.cypher!! && "vector_search.search" !in every.cypher!! && "db.idx" !in every.cypher!!, "${store.id}: no index is asked")
            assertEquals(0, every.indexK, store.id)
            assertEquals(emptyList(), every.warnings, store.id)
            assertEquals(if (store == SearchStore.FALKORDB) VectorSearch.FALKORDB_AMONG_TIMEOUT_MILLIS else null, every.timeoutMillis, store.id)
        }
    }

    @Test
    fun withSearchKEachRootAsksTheIndexKeepsEveryOtherRootAndThePlanWarns() {
        for (store in SearchStore.entries) for (dimensions in listOf(null, 3)) {
            val planned = plan(store, request(searchK = 20, dimensions = dimensions))
            val text = planned.sql ?: planned.cypher!!
            // As it went before #408: no place is read, and the golden pins its statements.
            assertTrue("_pos" !in text && "pos >" !in text && "ORDINALITY" !in text, "${store.id}:\n$text")
            val throughIndex = store.graphEngine || (store != SearchStore.SQLITE && dimensions != null)
            if (throughIndex) {
                assertEquals(1, planned.warnings.size, "${store.id} $dimensions")
                val warning = planned.warnings.single()
                assertTrue("nearest 20" in warning && "can be incomplete" in warning && "Leave searchK out" in warning, warning)
            } else {
                assertEquals(emptyList(), planned.warnings, "${store.id} $dimensions: every pair is measured")
            }
            assertEquals(null, planned.timeoutMillis, store.id)
        }
    }
}
