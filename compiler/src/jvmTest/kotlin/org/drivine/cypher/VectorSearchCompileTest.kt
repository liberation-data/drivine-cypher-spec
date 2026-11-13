package org.drivine.cypher

import org.drivine.cypher.sql.PostgresCypherCompiler
import org.drivine.schema.SimilarityFunction
import org.drivine.schema.VectorIndexSpec
import kotlin.test.Test

/** `db.index.vector.queryNodes` compiled to SQL (liberation-data/drivine-cypher-spec#160). */
class VectorSearchCompileTest {

    private val indexes = listOf(
        VectorIndexSpec("Chunk", "embedding", 3, SimilarityFunction.COSINE, name = "chunk_cos"),
        VectorIndexSpec("Place", "location", 3, SimilarityFunction.EUCLIDEAN, name = "place_l2"),
    )

    private fun postgres(cypher: String, parameters: Map<String, Any?> = emptyMap()) =
        PostgresCypherCompiler("g").also { it.vectorIndexes = indexes }.compile(cypher, parameters)

    @Test
    fun probe() {
        println(postgres("CALL db.index.vector.queryNodes('chunk_cos', 3, [1.0, 0.0, 0.0]) YIELD node, score MATCH (node)-[:PART_OF]->(d:Doc)<-[:WROTE]-(a:Author) WHERE d.public RETURN node.id, score, d.id, a.name").sql)
        println("----")
        println(postgres("CALL db.index.vector.queryNodes('chunk_cos', \$k, \$v) YIELD node, score RETURN node.id, score", mapOf("k" to 2L, "v" to listOf(1.0, 0.0, 0.0))).sql)
    }
}
