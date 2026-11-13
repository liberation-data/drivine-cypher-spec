package org.drivine.cypher

import org.drivine.cypher.sql.OracleCypherCompiler
import org.drivine.cypher.sql.PostgresCypherCompiler
import org.drivine.cypher.sql.SqlCypherCompiler
import org.drivine.cypher.sql.SqliteCypherCompiler
import org.drivine.schema.SimilarityFunction
import org.drivine.schema.VectorIndexSpec
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * `db.index.vector.queryNodes` compiled to SQL (liberation-data/drivine-cypher-spec#160): what each SQL store's compiler
 * reads, binds and refuses. What the statements give on each store is the spec's `vector.yaml`, run by the libraries.
 */
class VectorSearchCompileTest {

    private val indexes = listOf(
        VectorIndexSpec("Chunk", "embedding", 3, SimilarityFunction.COSINE, name = "chunk_cos"),
        VectorIndexSpec("Place", "location", 3, SimilarityFunction.EUCLIDEAN, name = "place_l2"),
    )

    private fun compilers(given: List<VectorIndexSpec> = indexes): List<SqlCypherCompiler> =
        listOf(PostgresCypherCompiler("g"), OracleCypherCompiler("g"), SqliteCypherCompiler("g")).onEach { it.vectorIndexes = given }

    /** [cypher]'s refusal, the same on every store. */
    private fun refused(cypher: String, parameters: Map<String, Any?> = emptyMap()): String =
        compilers().map { compiler ->
            assertThrows<CompilerException>("${compiler::class.simpleName}: $cypher") { compiler.compile(cypher, parameters) }.message!!
        }.distinct().single()

    private val search = "CALL db.index.vector.queryNodes"

    @Test
    fun `a search with no YIELD as the whole statement gives its node and its score`() {
        compilers().forEach { compiler ->
            assertEquals(
                compiler.compile("$search('chunk_cos', 2, [1.0, 0.0, 0.0]) YIELD node, score RETURN node, score").sql,
                compiler.compile("$search('chunk_cos', 2, [1.0, 0.0, 0.0])").sql,
                compiler::class.simpleName,
            )
        }
    }

    @Test
    fun `a hit is read from the candidates' own row, with no second join of the node table, and is filtered after them`() {
        // On Oracle 23.26 a second join of the node table to the candidates on nid is eliminated with the filter on it, where a
        // JSON_VALUE index of the property is made: a bound value crashed the server, a written one was dropped (#160).
        val filtered = "$search('chunk_cos', 3, [1.0, 0.0, 0.0]) YIELD node, score WHERE node.id = \$id RETURN node.id, score"
        for (compiler in compilers()) {
            val sql = compiler.compile(filtered, mapOf("id" to "a")).sql
            assertEquals(1, Regex("""\bg_node c\b""").findAll(sql).count(), "${compiler::class.simpleName}: the node table is read once, in the candidates:\n$sql")
            assertTrue(!Regex("""JOIN g_node \w+ ON \w+\.nid = v\d+\.nid""").containsMatchIn(sql), "${compiler::class.simpleName}: no join back:\n$sql")
        }
    }

    @Test
    fun `the first clause reads the candidates as a source, and one after other rows joins them for each row`() {
        val first = "$search('chunk_cos', 2, [1.0, 0.0, 0.0]) YIELD node, score RETURN node.id, score"
        val after = "MATCH (a:Author) $search('chunk_cos', 2, [1.0, 0.0, 0.0]) YIELD node, score RETURN a.name, node.id, score"
        val joined = mapOf(PostgresCypherCompiler::class to "CROSS JOIN LATERAL (", OracleCypherCompiler::class to "CROSS JOIN LATERAL (", SqliteCypherCompiler::class to "JOIN json_each(")
        compilers().forEach { compiler ->
            val join = joined.getValue(compiler::class)
            assertFalse(compiler.compile(first).sql.contains(join), "${compiler::class.simpleName}: the first clause is no join")
            assertTrue(compiler.compile(after).sql.contains(join), "${compiler::class.simpleName}: after a MATCH, a join for each row")
        }
    }

    @Test
    fun `a k given as a parameter is bound, and one written is written`() {
        val written = PostgresCypherCompiler("g").also { it.vectorIndexes = indexes }.compile("$search('chunk_cos', 2, [1.0, 0.0, 0.0]) YIELD node RETURN node")
        assertTrue(written.sql.contains("LIMIT 2)"), written.sql)
        compilers().forEach { compiler ->
            val given = compiler.compile("$search('chunk_cos', \$k, [1.0, 0.0, 0.0]) YIELD node RETURN node", mapOf("k" to 2L))
            assertEquals(mapOf("_cyn_k" to 2L), given.parameters.filterKeys { it.startsWith("_cyn_") }, compiler::class.simpleName)
            assertTrue(given.sql.contains("_cyn_k"), given.sql)
        }
    }

    @Test
    fun `k is refused where it is no whole number from 1 to 2147483647, as Neo4j fails it`() {
        val range = "db.index.vector.queryNodes() takes a number of neighbours from 1 to 2147483647"
        assertEquals("$range, not 0, as in Neo4j.", refused("$search('chunk_cos', 0, [1.0, 0.0, 0.0]) YIELD node RETURN node"))
        assertEquals("$range, not -1, as in Neo4j.", refused("$search('chunk_cos', -1, [1.0, 0.0, 0.0]) YIELD node RETURN node"))
        assertEquals("$range, not -2, as in Neo4j.", refused("$search('chunk_cos', -\$k, [1.0, 0.0, 0.0]) YIELD node RETURN node", mapOf("k" to 2L)))
        assertEquals("$range, not 2147483648, as in Neo4j.", refused("$search('chunk_cos', 2147483648, [1.0, 0.0, 0.0]) YIELD node RETURN node"))
        assertEquals("$range, not 2147483648, as in Neo4j.", refused("$search('chunk_cos', \$k, [1.0, 0.0, 0.0]) YIELD node RETURN node", mapOf("k" to 2147483648L)))
        val whole = "db.index.vector.queryNodes() takes a whole number of neighbours"
        assertEquals("$whole, not null, as in Neo4j.", refused("$search('chunk_cos', null, [1.0, 0.0, 0.0]) YIELD node RETURN node"))
        assertEquals("$whole, not \$k, which is null, as in Neo4j.", refused("$search('chunk_cos', \$k, [1.0, 0.0, 0.0]) YIELD node RETURN node", mapOf("k" to null)))
        assertEquals("$whole, not 2.0, as in Neo4j.", refused("$search('chunk_cos', \$k, [1.0, 0.0, 0.0]) YIELD node RETURN node", mapOf("k" to 2.0)))
        assertEquals(
            "A vector search whose number of neighbours is an expression is Cypher, and is not compiled to SQL yet.",
            refused("$search('chunk_cos', 1 + 1, [1.0, 0.0, 0.0]) YIELD node RETURN node"),
        )
    }

    @Test
    fun `a vector written or given is refused where Neo4j fails it, before the statement runs`() {
        val list = "db.index.vector.queryNodes() takes its vector as a list of numbers"
        assertEquals("$list, not null, as in Neo4j.", refused("$search('chunk_cos', 2, null) YIELD node RETURN node"))
        assertEquals("$list, not null, as in Neo4j.", refused("$search('chunk_cos', 2, \$v) YIELD node RETURN node", mapOf("v" to null)))
        assertEquals("$list, not that, as in Neo4j.", refused("$search('chunk_cos', 2, \$v) YIELD node RETURN node", mapOf("v" to "[1.0, 0.0, 0.0]")))
        assertEquals("$list, as in Neo4j: its coordinates must be finite.", refused("$search('chunk_cos', 2, ['a', 0.0, 0.0]) YIELD node RETURN node"))
        assertEquals(
            "db.index.vector.queryNodes() takes a vector whose coordinates are finite, as in Neo4j.",
            refused("$search('chunk_cos', 2, \$v) YIELD node RETURN node", mapOf("v" to listOf(Double.NaN, 0.0, 0.0))),
        )
        assertEquals(
            "The index 'chunk_cos' is of vectors of 3 dimensions, and the query vector has 2, as Neo4j fails it.",
            refused("$search('chunk_cos', 2, \$v) YIELD node RETURN node", mapOf("v" to listOf(1.0, 0.0))),
        )
        assertEquals(
            "The index 'chunk_cos' measures by cosine, which a vector of no length has none of, as Neo4j fails it.",
            refused("$search('chunk_cos', 2, [0, -0.0, 0.0]) YIELD node RETURN node"),
        )
        // A euclidean index measures from the origin as from any point.
        compilers().forEach { it.compile("$search('place_l2', 2, [0.0, 0.0, 0.0]) YIELD node RETURN node") }
    }

    @Test
    fun `a vector given as an array is a list, as a JVM caller gives an embedding`() {
        // A float[] from an embedding model, as the driver and the store's binding take any list.
        val given = listOf(floatArrayOf(1f, 0f, 0f), doubleArrayOf(1.0, 0.0, 0.0), intArrayOf(1, 0, 0), longArrayOf(1, 0, 0), arrayOf(1.0, 0.0, 0.0), setOf(1.0, 0.0, -1.0))
        compilers().forEach { compiler ->
            given.forEach { v -> compiler.compile("$search('chunk_cos', 2, \$v) YIELD node RETURN node", mapOf("v" to v)) }
        }
        assertEquals(
            "The index 'chunk_cos' is of vectors of 3 dimensions, and the query vector has 2, as Neo4j fails it.",
            refused("$search('chunk_cos', 2, \$v) YIELD node RETURN node", mapOf("v" to floatArrayOf(1f, 0f))),
        )
        assertEquals(
            "db.index.vector.queryNodes() takes a vector whose coordinates are finite, as in Neo4j.",
            refused("$search('chunk_cos', 2, \$v) YIELD node RETURN node", mapOf("v" to floatArrayOf(Float.NaN, 0f, 0f))),
        )
    }

    @Test
    fun `a vector the statement works out is checked as it runs, and one written is written as the store reads it`() {
        val guards = mapOf(
            PostgresCypherCompiler::class to "vector_dims(",
            OracleCypherCompiler::class to "size()' RETURNING NUMBER)",
            SqliteCypherCompiler::class to "json_array_length(",
        )
        val noLength = "measures by cosine, which a vector of no length has none of:"
        compilers().forEach { compiler ->
            val store = compiler::class.simpleName
            val guard = guards.getValue(compiler::class)
            val read = "MATCH (q:Chunk {id: 'c'}) $search('%s', 2, q.embedding) YIELD node RETURN node"
            val cosine = compiler.compile(read.format("chunk_cos")).sql
            assertTrue(cosine.contains(guard) && cosine.contains("of 3 dimensions, and the query vector has"), "$store:\n$cosine")
            assertTrue(cosine.contains("takes its vector as a list of numbers, not") && cosine.contains(noLength), "$store:\n$cosine")
            // A euclidean index has a point of no length like any other.
            assertFalse(compiler.compile(read.format("place_l2")).sql.contains("of no length"), store)
            // A parameter's value is bound as it is, and checked again as the statement runs.
            val given = compiler.compile("$search('chunk_cos', 2, \$v) YIELD node RETURN node", mapOf("v" to listOf(1.0, 0.0, 0.0)))
            assertTrue(given.sql.contains(guard), "$store:\n${given.sql}")
            val written = compiler.compile("$search('chunk_cos', 2, [1, 0.5, 0.0]) YIELD node RETURN node").sql
            assertTrue(written.contains("'[1.0, 0.5, 0.0]'"), "$store:\n$written")
            assertFalse(written.contains("and the query vector has"), "$store: a vector written is checked before it runs:\n$written")
        }
        // On Oracle the vector is read as a CLOB: JSON_SERIALIZE gives a VARCHAR2(4000) by default, which an embedding of a few
        // hundred dimensions outgrows (ORA-40478); and so is each failure, so the CASE is of one type.
        val oracle = OracleCypherCompiler("g").also { it.vectorIndexes = indexes }
            .compile("$search('chunk_cos', 2, \$v) YIELD node RETURN node", mapOf("v" to listOf(1.0, 0.0, 0.0))).sql
        assertTrue(oracle.contains(" RETURNING CLOB) END), COSINE)"), oracle)
        assertFalse(oracle.contains("THEN TO_CHAR("), oracle)
    }

    @Test
    fun `an index is found by the name the schema API gives it, and one not given is refused`() {
        val unnamed = listOf(VectorIndexSpec("Chunk", "embedding", 3), VectorIndexSpec("Place", "location", 2, name = ""))
        compilers(unnamed).forEach { compiler ->
            compiler.compile("$search('Chunk_embedding_vector', 2, [1.0, 0.0, 0.0]) YIELD node RETURN node")
            val wrongSize = assertThrows<CompilerException> { compiler.compile("$search('Place_location_vector', 2, [1.0, 0.0, 0.0]) YIELD node RETURN node") }
            assertEquals("The index 'Place_location_vector' is of vectors of 2 dimensions, and the query vector has 3, as Neo4j fails it.", wrongSize.message)
        }
        assertEquals(
            "The index 'nope' does not exist, as Neo4j says of a vector index the graph has not made.",
            refused("$search('nope', 2, [1.0, 0.0, 0.0]) YIELD node RETURN node"),
        )
        compilers(emptyList()).forEach { compiler ->
            assertThrows<CompilerException> { compiler.compile("$search('chunk_cos', 2, [1.0, 0.0, 0.0]) YIELD node RETURN node") }
        }
    }

    @Test
    fun `a call the compiler can't read as a search is refused, saying how one is written`() {
        val shape = "db.index.vector.queryNodes() takes its index's name, its number of neighbours and its vector, as in Cypher: CALL db.index.vector.queryNodes('chunks', 10, \$vector)."
        assertEquals(shape, refused("$search('chunk_cos', 2) YIELD node RETURN node"))
        assertEquals(shape, refused("$search('chunk_cos', 2, [1.0, 0.0, 0.0], 4) YIELD node RETURN node"))
        assertEquals(
            "db.index.vector.queryNodes() is called with its index, its number of neighbours and its vector, as in Cypher: CALL db.index.vector.queryNodes('chunks', 10, \$vector).",
            refused(search),
        )
        assertEquals(
            "db.index.vector.queryNodes() is compiled to SQL with its index named by a string, as in CALL db.index.vector.queryNodes('chunks', 10, \$vector): a SQL store reads the index's table when the statement is compiled.",
            refused("$search(\$i, 2, [1.0, 0.0, 0.0]) YIELD node RETURN node", mapOf("i" to "chunk_cos")),
        )
        assertEquals("db.index.vector.queryNodes() yields node and score, not distance.", refused("$search('chunk_cos', 2, [1.0, 0.0, 0.0]) YIELD node, distance RETURN node"))
        assertEquals(
            "A CALL that is the whole statement takes no WHERE, as in Cypher: follow its YIELD with WITH or RETURN, as in CALL db.index.vector.queryNodes('chunks', 10, \$vector) YIELD node, score WHERE … RETURN node, score.",
            refused("$search('chunk_cos', 2, [1.0, 0.0, 0.0]) YIELD node WHERE node.lang = 'en'"),
        )
        assertEquals(
            "A CALL of a procedure within a query names what it yields, as in Cypher: CALL db.index.vector.queryNodes('chunks', 10, \$vector) YIELD node, score.",
            refused("MATCH (a:Author) $search('chunk_cos', 2, [1.0, 0.0, 0.0]) RETURN a"),
        )
    }

    @Test
    fun `a variable yielded is refused where it is bound already, or yielded twice`() {
        assertEquals(
            "The variable node is bound already, so a CALL does not return or yield it, as in Cypher: name it with AS.",
            refused("MATCH (node) $search('chunk_cos', 2, [1.0, 0.0, 0.0]) YIELD node RETURN node"),
        )
        assertEquals(
            "db.index.vector.queryNodes() yields x twice, as Neo4j refuses it: name each field it yields with a variable of its own.",
            refused("$search('chunk_cos', 2, [1.0, 0.0, 0.0]) YIELD node AS x, score AS x RETURN x"),
        )
        compilers().forEach { it.compile("MATCH (node) $search('chunk_cos', 2, [1.0, 0.0, 0.0]) YIELD node AS hit RETURN node, hit") }
    }
}
