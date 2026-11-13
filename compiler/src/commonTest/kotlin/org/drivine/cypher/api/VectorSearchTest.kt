package org.drivine.cypher.api

import org.drivine.cypher.json.CypherJson
import org.drivine.cypher.json.ObjectNode
import org.drivine.cypher.store.ddl.DdlStatement
import org.drivine.cypher.store.ddl.OracleDdl
import org.drivine.cypher.store.ddl.PostgresDdl
import org.drivine.cypher.store.ddl.SqlStoreDdl
import org.drivine.schema.SimilarityFunction
import org.drivine.schema.VectorIndexSpec
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * What [VectorSearch] decides beyond the golden of drivine4j's statements ([VectorSearchGoldenTest]): that a search through
 * an index reads the embedding as the module's DDL makes the index over it, so the index answers it, that a graph engine's
 * names are written escaped (#217), and what it refuses.
 */
@OptIn(org.drivine.DrivineInternalApi::class)
class VectorSearchTest {

    private val graph = GraphOptions("g")

    private fun request(similarity: SimilarityFunction = SimilarityFunction.COSINE, labels: List<String> = listOf("Doc"), property: String = "embedding", dimensions: Int? = 3) =
        VectorRequest(labels = labels, property = property, similarity = similarity, topK = 10, size = 3, index = VectorIndex(dimensions = dimensions))

    private fun plan(store: SearchStore, request: VectorRequest): VectorPlan = VectorSearch.plan(store, graph, request) as VectorPlan

    private fun ddl(store: SqlStoreDdl, spec: VectorIndexSpec): List<String> =
        store.createIndex(spec, null).filterIsInstance<DdlStatement.Text>().map { it.statement }

    @Test
    fun aPostgresSearchThroughTheIndexReadsTheEmbeddingAsTheIndexIsMadeOverIt() {
        for (similarity in SimilarityFunction.entries) for (property in listOf("embedding", "emb'e\\d \"x\"", "\$e")) {
            val spec = VectorIndexSpec("Doc", property, 3, similarity)
            val create = ddl(PostgresDdl("g"), spec).single { it.contains("USING hnsw") }
            val sql = plan(SearchStore.POSTGRES, request(similarity, property = property)).sql!!
            // The index's expression and its predicate, of the table's own columns, are the search's of its row `s`.
            val expression = create.substringAfter("USING hnsw ((").substringBefore(") vector_")
            val predicate = create.substringAfter(" WHERE ")
            assertTrue(sql.contains(expression.replace("(props ->", "(s.props ->")), "the search reads $expression:\n$sql")
            for (conjunct in predicate.split(" AND ")) {
                val ofRow = conjunct.replace("labels @>", "s.labels @>").replace("(props ->", "(s.props ->")
                assertTrue(sql.contains(ofRow), "the search meets the index's $conjunct:\n$sql")
            }
            // The index's operator class measures the distance the search orders by.
            val operator = if (create.contains("vector_cosine_ops")) "<=>" else "<->"
            assertTrue(sql.contains(" $operator CAST(\$"), "the search orders by $operator:\n$sql")
        }
    }

    @Test
    fun aCosineSearchLeavesOutAStoredEmbeddingOfNoLengthAndAEuclideanOneMeasuresIt() {
        // Neo4j's cosine index holds no embedding of no length (vector.yaml, "Only the index's nodes"); a euclidean one does.
        val lengths = mapOf(SearchStore.POSTGRES to "vector_norm(", SearchStore.ORACLE to "VECTOR_NORM(", SearchStore.SQLITE to "e.value <> 0")
        for ((store, length) in lengths) for (dimensions in listOf(3, null)) {
            val cosine = plan(store, request(SimilarityFunction.COSINE, dimensions = dimensions)).sql!!
            val euclidean = plan(store, request(SimilarityFunction.EUCLIDEAN, dimensions = dimensions)).sql!!
            assertTrue(cosine.contains(length), "$store, index of $dimensions, by cosine:\n$cosine")
            assertTrue(!euclidean.contains(length), "$store, index of $dimensions, euclidean:\n$euclidean")
        }
    }

    @Test
    fun aCosineSearchAmongIdsLeavesOutARootWhoseEmbeddingHasNoLengthAndAEuclideanOneKeepsIt() {
        // Its NaN score sorted first; as a seed it has no direction to search by, and Neo4j's cosine index never yields it.
        val lengths = mapOf(SearchStore.POSTGRES to "vector_norm(", SearchStore.ORACLE to "VECTOR_NORM(", SearchStore.SQLITE to "e.value <> 0")
        for ((store, length) in lengths) for (dimensions in listOf(3, null)) {
            val among = VectorAmong(listOf("a", "b"), "id")
            val cosine = plan(store, request(SimilarityFunction.COSINE, dimensions = dimensions).copy(among = among, size = null)).sql!!
            val euclidean = plan(store, request(SimilarityFunction.EUCLIDEAN, dimensions = dimensions).copy(among = among, size = null)).sql!!
            // In the roots searched among: the statement's first query, which each root's search reads.
            assertTrue(cosine.substringAfter("WITH members AS").substringBefore("SELECT s.").contains(length), "$store, index of $dimensions, by cosine:\n$cosine")
            assertTrue(!euclidean.contains(length), "$store, index of $dimensions, euclidean:\n$euclidean")
        }
    }

    @Test
    fun anOracleSearchThroughTheIndexReadsTheSideTableTheDdlMakesWithItsMetric() {
        for (similarity in SimilarityFunction.entries) for (label in listOf("Doc", "Do'c\\x")) {
            val spec = VectorIndexSpec(label, "embedding", 3, similarity)
            val create = ddl(OracleDdl("g"), spec).single { it.startsWith("CREATE VECTOR INDEX") }
            val table = create.substringAfter(" ON ").substringBefore(" (embedding)")
            val metric = create.substringAfter("DISTANCE ")
            val sql = plan(SearchStore.ORACLE, request(similarity, labels = listOf(label))).sql!!
            assertTrue(sql.contains("FROM $table s ") && sql.contains("ORDER BY distance FETCH APPROX FIRST"), "the search reads $table:\n$sql")
            assertTrue(sql.contains("TO_VECTOR(\$_cy_vec1), $metric)"), "the search measures by $metric:\n$sql")
            val among = plan(SearchStore.ORACLE, request(similarity, labels = listOf(label)).copy(among = VectorAmong(listOf("a"), "id"))).sql!!
            assertTrue(among.contains("FROM $table e"), "a search among ids reads $table:\n$among")
        }
    }

    @Test
    fun aGraphEngineSearchNamesTheIndexTheSchemaMakes() {
        val spec = VectorIndexSpec("Doc", "embedding", 3)
        for (store in listOf(SearchStore.NEO4J, SearchStore.MEMGRAPH)) {
            assertTrue(plan(store, request()).cypher!!.contains("('${spec.defaultName()}', "), store.id)
        }
    }

    @Test
    fun aGraphEngineSearchWritesEachNameEscapedWhateverItHolds() {
        // #217: a quote, a backslash and a backtick in each name the head writes into its CALL, and in the roots' labels.
        val odd = "it's`\\x"
        val string = "'it\\'s`\\\\x'"
        val label = "`it's``\\x`"
        val among = VectorAmong(listOf("a"), "id")
        for (store in listOf(SearchStore.NEO4J, SearchStore.MEMGRAPH, SearchStore.FALKORDB)) {
            val byName = request(labels = listOf("Doc")).copy(index = VectorIndex(name = odd))
            val byLabel = request(labels = listOf(odd, odd), property = odd)
            val calls = when (store) {
                SearchStore.NEO4J -> "CALL db.index.vector.queryNodes("
                SearchStore.MEMGRAPH -> "CALL vector_search.search("
                else -> "CALL db.idx.vector.queryNodes("
            }
            val expected = when (store) {
                SearchStore.FALKORDB -> listOf("$string, $string, " to byLabel, "'Doc', 'embedding', " to byName)
                else -> listOf("$string, " to byName, "'it\\'s`\\\\x_it\\'s`\\\\x_vector', " to byLabel)
            }
            for ((arguments, request) in expected) for (asked in listOf(request, request.copy(among = among))) {
                val cypher = plan(store, asked).cypher!!
                assertTrue("$calls$arguments" in cypher, "${store.id}: $cypher")
            }
            val amongCypher = plan(store, byLabel.copy(among = among)).cypher!!
            assertTrue("\nMATCH (seed:$label:$label)\n" in amongCypher, "${store.id}: $amongCypher")
            assertEquals("n:`Doc`:$label", plan(store, request(labels = listOf("Doc", odd))).labelCheck, store.id)
            // With a partition the index's name, or FalkorDB's label, is bound as it is, not written in; FalkorDB's property
            // is still written, escaped.
            val partitioned = plan(store, request(labels = listOf("Doc"), property = odd).copy(partitionLabel = odd))
            val bound = if (store == SearchStore.FALKORDB) odd else "${odd}_${odd}_vector"
            assertEquals(bound, partitioned.parameters["_cy_vec1"], store.id)
            assertTrue(partitioned.cypher!!.startsWith("$calls\$_cy_vec1, " + if (store == SearchStore.FALKORDB) "$string, " else ""), "${store.id}: ${partitioned.cypher}")
            // A plain name is written as it always was: bare in the MATCH, between plain quotes in the CALL.
            val plain = plan(store, request(labels = listOf("Doc", "Chunk")).copy(among = among)).cypher!!
            assertTrue("\nMATCH (seed:Doc:Chunk)\n" in plain, "${store.id}: $plain")
            val plainArguments = if (store == SearchStore.FALKORDB) "'Doc', 'embedding', " else "'Doc_embedding_vector', "
            assertTrue("$calls$plainArguments" in plain, "${store.id}: $plain")
        }
    }

    @Test
    fun aSearchGoesThroughTheIndexOnlyWhereItIsOfTheVectorsSize() {
        for (store in listOf(SearchStore.POSTGRES, SearchStore.ORACLE)) {
            val through = plan(store, request(dimensions = 3)).sql!!
            for (dimensions in listOf(null, 4)) {
                val exact = plan(store, request(dimensions = dimensions)).sql!!
                assertTrue(through != exact && exact.contains("ORDER BY score DESC, c.nid"), "${store.id} $dimensions:\n$exact")
            }
        }
        val sqlite = plan(SearchStore.SQLITE, request(dimensions = 3))
        assertEquals(plan(SearchStore.SQLITE, request(dimensions = null)), sqlite, "SQLite has no index to go through")
    }

    @Test
    fun aSqlStoreReadsAnEmbeddingAndAnAmongIdWhoseNameBeginsWithDollarAtTheEscapedKey() {
        // Store layout 2 holds a property `$e` under `$$e` (ValueLayout.escape): every SQL search reads it there, the
        // embedding and an among search's id alike, and never at the property's own name.
        val unescaped = Regex("(?<!\\$)\\$(e|id)\\b")
        val dollar = request(property = "\$e")
        for (store in listOf(SearchStore.POSTGRES, SearchStore.ORACLE, SearchStore.SQLITE)) {
            for (dimensions in listOf(null, 3)) {
                val among = dollar.copy(index = VectorIndex(dimensions = dimensions), among = VectorAmong(listOf("a"), "\$id"))
                for (searched in listOf(dollar.copy(index = VectorIndex(dimensions = dimensions)), among)) {
                    val sql = plan(store, searched).sql!!
                    // Oracle's search through the index reads its side table's column, and no property.
                    assertTrue("\$\$e" in sql || (store == SearchStore.ORACLE && dimensions != null && searched.among == null), "${store.id} $dimensions:\n$sql")
                    assertNull(unescaped.find(sql), "${store.id} $dimensions:\n$sql")
                }
                assertTrue("\$\$id" in plan(store, among).sql!!, "${store.id} $dimensions")
            }
        }
    }

    @Test
    fun theIndexIsAskedForSearchKWhereItIsGiven() {
        val plan = plan(SearchStore.POSTGRES, request().copy(searchK = 50))
        assertEquals(50, plan.indexK)
        assertEquals(mapOf<String, Any?>("_cy_vec2" to 50), plan.parameters)
        assertEquals("_cy_vec1", plan.vector)
        assertEquals(10, plan(SearchStore.NEO4J, request()).indexK)
    }

    @Test
    fun amongIdsWithoutSearchKTheIndexIsAskedForOneMoreThanTopK() {
        // drivine4j's loadNearestAmong asks for searchK ?: topK + 1: the index finds the root itself first.
        val among = request().copy(among = VectorAmong(listOf("a", "b"), "id"))
        for (store in listOf(SearchStore.POSTGRES, SearchStore.ORACLE, SearchStore.NEO4J, SearchStore.MEMGRAPH, SearchStore.FALKORDB)) {
            val indexed = among.copy(index = VectorIndex(dimensions = 3))
            val plan = plan(store, indexed)
            assertEquals(11, plan.indexK, store.id)
            assertTrue(11 in plan.parameters.values && 10 in plan.parameters.values, "${store.id}: ${plan.parameters}")
            assertEquals(40, plan(store, indexed.copy(searchK = 40)).indexK, store.id)
        }
        assertEquals(11, plan(SearchStore.SQLITE, among).indexK)
    }

    @Test
    fun amongIdsEachIdIsSearchedOnce() {
        // drivine4j's loadNearestAmong searches ids.distinct(): a repeat would give a graph engine's root a row twice.
        val repeated = request().copy(among = VectorAmong(listOf("a", "b", "a"), "id"))
        for (store in SearchStore.entries) {
            val plan = plan(store, repeated)
            assertEquals(plan(store, repeated.copy(among = VectorAmong(listOf("a", "b"), "id"))), plan, store.id)
            assertTrue(listOf("a", "b") in plan.parameters.values, "${store.id}: ${plan.parameters}")
        }
    }

    @Test
    fun aBlankIndexNameIsNoneSoAPartitionMayGiveIt() {
        for (store in listOf(SearchStore.NEO4J, SearchStore.MEMGRAPH)) {
            assertEquals(plan(store, request()), plan(store, request().copy(index = VectorIndex(name = " "))), store.id)
            val partitioned = request().copy(partitionLabel = "P")
            assertEquals(plan(store, partitioned), plan(store, partitioned.copy(index = VectorIndex(name = ""))), store.id)
            assertEquals("P_embedding_vector", plan(store, partitioned).parameters["_cy_vec1"], store.id)
        }
    }

    @Test
    fun whatAVectorSearchRefuses() {
        val partitioned = request().copy(partitionLabel = "Tenant7", index = VectorIndex(name = "doc_vectors"))
        assertFailsWith<IllegalArgumentException> { VectorSearch.plan(SearchStore.NEO4J, graph, partitioned) }
        assertFailsWith<IllegalArgumentException> { VectorSearch.plan(SearchStore.POSTGRES, graph, request().copy(partitionLabel = "P", among = VectorAmong(listOf("a"), "id"))) }
        assertFailsWith<IllegalArgumentException> { VectorSearch.plan(SearchStore.POSTGRES, graph, request().copy(size = null)) }
        assertFailsWith<IllegalArgumentException> { VectorSearch.plan(SearchStore.POSTGRES, graph, request().copy(labels = emptyList())) }
        // A graph name the store can't take is refused, as compiling over it is.
        val refused = VectorSearch.plan(SearchStore.POSTGRES, GraphOptions("9g"), request())
        assertTrue(refused is Refusal, "$refused")
        // A graph engine needs no size: the engine's index measures what it holds.
        assertNull(plan(SearchStore.FALKORDB, request().copy(size = null)).sql)
    }

    @Test
    fun aJsonRequestThatIsNoRequestIsAnError() {
        fun answer(vector: String, store: String = "postgres") =
            CypherJson.readTree(CompileJson.compile("{\"store\": \"$store\", \"graph\": {\"name\": \"g\"}, \"vector\": $vector}")) as ObjectNode
        val base = "\"labels\": [\"A\"], \"property\": \"e\", \"topK\": 1, \"size\": 2"
        for ((vector, error) in listOf(
            "{$base, \"similarity\": \"DOT\"}" to "COSINE or EUCLIDEAN",
            "{$base}" to "COSINE or EUCLIDEAN",
            "{\"labels\": [\"A\"], \"property\": \"e\", \"similarity\": \"COSINE\", \"size\": 2}" to "gives its topK",
            "{$base, \"similarity\": \"COSINE\", \"label\": \"B\"}" to "not both",
            "{$base, \"similarity\": \"COSINE\", \"topK\": -1}" to "topK as a whole number",
            "{$base, \"similarity\": \"COSINE\", \"index\": 3}" to "index as a JSON object",
            "{$base, \"similarity\": \"COSINE\", \"among\": {\"ids\": [1], \"idProperty\": \"id\"}}" to "ids as",
            "{$base, \"similarity\": \"COSINE\", \"among\": {\"ids\": [\"a\"]}}" to "names its idProperty",
            "{$base, \"similarity\": \"COSINE\", \"among\": {\"ids\": [\"a\"], \"idProperty\": \"id\", \"threshold\": \"high\"}}" to "threshold as a number",
            "[1]" to "vector is a JSON object",
            // What plan itself refuses to take, an error over JSON as a full-text search's is.
            "{\"labels\": [], \"property\": \"e\", \"similarity\": \"COSINE\", \"topK\": 1, \"size\": 2}" to "names the root's labels",
            "{\"labels\": [\"A\"], \"similarity\": \"COSINE\", \"topK\": 1, \"size\": 2}" to "names its property",
            "{\"labels\": [\"A\"], \"property\": \"e\", \"similarity\": \"COSINE\", \"topK\": 1}" to "gives its vector's size",
            "{$base, \"similarity\": \"COSINE\", \"partitionLabel\": \"P\", \"among\": {\"ids\": [\"a\"], \"idProperty\": \"id\"}}" to "takes no partitionLabel",
            "{$base, \"similarity\": \"COSINE\", \"partitionLabel\": \"P\", \"index\": {\"name\": \"n\"}}" to "names no index",
            "{$base, \"similarity\": \"COSINE\", \"index\": {\"dimensions\": 2.5}}" to "dimensions as a whole number",
        )) {
            val got = answer(vector)
            assertTrue(got["error"]?.textValue()?.contains(error) == true, "$vector: $got")
        }
        assertTrue(answer("{$base, \"similarity\": \"COSINE\"}", store = "redis")["error"]!!.textValue()!!.startsWith("No store redis"))
        // A request asks one thing: a vector search beside a statement or a full-text search is an error, not either read past.
        val vector = "\"vector\": {$base, \"similarity\": \"COSINE\"}"
        for (other in listOf("\"cypher\": \"RETURN 1\"", "\"search\": {}", "\"searchIndex\": {}", "\"settings\": {}")) {
            val request = "{\"store\": \"postgres\", \"graph\": {\"name\": \"g\"}, $vector, $other}"
            assertEquals("{\"error\":\"A request gives one of cypher, search, searchIndex, vector, settings, read, temporalKey and layout.\"}", CompileJson.compile(request), request)
        }
        // A SQL store's search names its graph; a graph engine's needs none.
        assertTrue(CompileJson.compile("{\"store\": \"oracle\", \"vector\": {$base, \"similarity\": \"COSINE\"}}").contains("gives its graph"))
        val engine = CypherJson.readTree(CompileJson.compile("{\"store\": \"neo4j\", \"vector\": {\"label\": \"A\", \"property\": \"e\", \"similarity\": \"COSINE\", \"topK\": 1}}")) as ObjectNode
        assertTrue(engine["cypher"]!!.textValue()!!.startsWith("CALL db.index.vector.queryNodes('A_e_vector', \$_cy_vec1, \$_cy_vec2)"), "$engine")
        val among = answer("{$base, \"similarity\": \"COSINE\", \"among\": {\"ids\": [\"a\"], \"idProperty\": \"id\", \"threshold\": {\"\$float\": \"0.5\"}}}")
        assertEquals("{\"\$float\":\"0.5\"}", CypherJson.writeValueAsString(among["parameters"]!!["_cy_vec3"]))
        assertTrue(among["vector"]!!.isNull && among["labelCheck"]!!.isNull, "$among")
    }
}
