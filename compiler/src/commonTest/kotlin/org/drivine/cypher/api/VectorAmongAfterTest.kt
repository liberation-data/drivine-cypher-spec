package org.drivine.cypher.api

import org.drivine.cypher.json.ArrayNode
import org.drivine.cypher.json.CypherJson
import org.drivine.cypher.json.ObjectNode
import org.drivine.schema.SimilarityFunction
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * A search among ids for each pair once ([AmongNeighbours.After], liberation-data/drivine-cypher-spec#406): each store's
 * statement, which measures every pair of the roots without `searchK`, keeps each root's neighbours by their places among
 * the ids, before its `topK` are cut, and compares no two ids; what it warns of through an index; and its JSON. Every
 * statement here was run on each store, and gave the exact answer. `among.yaml` pins what each store gives.
 */
@OptIn(org.drivine.DrivineInternalApi::class)
class VectorAmongAfterTest {

    private val graph = GraphOptions("g")

    private fun request(
        similarity: SimilarityFunction = SimilarityFunction.COSINE, labels: List<String> = listOf("Doc"), searchK: Int? = null, dimensions: Int? = null,
        threshold: Double? = 0.5, neighbours: AmongNeighbours = AmongNeighbours.After,
    ) = VectorRequest(
        labels = labels, property = "embedding", similarity = similarity, topK = 5, searchK = searchK, index = VectorIndex(dimensions = dimensions),
        among = VectorAmong(listOf("a", "b", "c"), "id", threshold, neighbours),
    )

    private fun plan(store: SearchStore, request: VectorRequest): VectorPlan = VectorSearch.plan(store, graph, request) as VectorPlan

    private fun statement(plan: VectorPlan): String = plan.sql ?: plan.cypher!!

    /** Each store's statement for each pair once, without searchK and with a threshold, as it was run on the store. */
    private val everyPair: Map<SearchStore, String> = mapOf(
        SearchStore.POSTGRES to """
            |WITH members AS (
            |    SELECT c.nid, (c.props ->> 'id') AS id, c.props, g.pos
            |    FROM g_node c
            |    JOIN (
            |        SELECT v.value #>> '{}' AS id, v.pos FROM jsonb_array_elements(CAST(${'$'}_cy_vec1 AS jsonb)) WITH ORDINALITY v(value, pos)
            |    ) g ON g.id = (c.props ->> 'id')
            |    WHERE c.labels @> '["Doc"]'
            |      AND jsonb_typeof((c.props -> 'embedding')) = 'array'
            |      AND CASE WHEN jsonb_typeof((c.props -> 'embedding')) = 'array' THEN vector_norm(CAST(CAST((c.props -> 'embedding') AS text) AS vector)) > 0 END)
            |SELECT jsonb_build_object(
            |    'seed', s.id,
            |    'near', COALESCE((
            |        SELECT jsonb_agg(jsonb_build_object(
            |            'id', r.id,
            |            'score', r.score) ORDER BY r.score DESC, r.nid)
            |        FROM (
            |            SELECT o.nid, o.id, (2 - (CAST(CAST((o.props -> 'embedding') AS text) AS vector) <=> CAST(CAST((s.props -> 'embedding') AS text) AS vector))) / 2 AS score
            |            FROM members o
            |            WHERE o.pos > s.pos
            |              AND jsonb_array_length((o.props -> 'embedding')) = jsonb_array_length((s.props -> 'embedding'))
            |            ORDER BY score DESC, o.nid
            |            LIMIT ${'$'}_cy_vec2
            |        ) r
            |        WHERE r.score >= ${'$'}_cy_vec3), '[]'::jsonb)) AS result
            |FROM members s
        """.trimMargin(),
        SearchStore.ORACLE to """
            |WITH members AS (
            |    SELECT c.nid, JSON_VALUE(c.props, '${'$'}.id') AS id, TO_VECTOR(JSON_QUERY(c.props, '${'$'}.embedding' RETURNING CLOB)) AS embedding, g.pos
            |    FROM g_node c
            |    JOIN (
            |        SELECT v.id, v.pos FROM JSON_TABLE(${'$'}_cy_vec1, '${'$'}[*]' COLUMNS (pos FOR ORDINALITY, id VARCHAR2(4000) PATH '${'$'}')) v
            |    ) g ON g.id = JSON_VALUE(c.props, '${'$'}.id')
            |    WHERE INSTR(',' || LTRIM(RTRIM(JSON_SERIALIZE(c.labels), ']'), '[') || ',"', ',"Doc","') > 0
            |      AND JSON_EXISTS(c.props, '${'$'}.embedding')
            |      AND VECTOR_NORM(TO_VECTOR(JSON_QUERY(c.props, '${'$'}.embedding' RETURNING CLOB))) > 0),
            |ranked AS (
            |    SELECT p.seed, p.nid, p.id, p.score, ROW_NUMBER() OVER (PARTITION BY p.seed ORDER BY p.score DESC, p.nid) AS place
            |    FROM (
            |        SELECT s.nid AS seed, o.nid, o.id, (2 - VECTOR_DISTANCE(o.embedding, s.embedding, COSINE)) / 2 AS score
            |        FROM members s
            |        JOIN members o ON o.pos > s.pos AND VECTOR_DIMENSION_COUNT(o.embedding) = VECTOR_DIMENSION_COUNT(s.embedding)
            |    ) p)
            |SELECT JSON_OBJECT(
            |    'seed' VALUE s.id,
            |    'near' VALUE COALESCE((
            |        SELECT JSON_ARRAYAGG(JSON_OBJECT(
            |            'id' VALUE r.id,
            |            'score' VALUE r.score
            |            RETURNING JSON) ORDER BY r.score DESC, r.nid RETURNING JSON)
            |        FROM ranked r
            |        WHERE r.seed = s.nid
            |          AND r.place <= ${'$'}_cy_vec2
            |          AND r.score >= ${'$'}_cy_vec3), JSON('[]'))
            |    RETURNING JSON) AS result
            |FROM members s
        """.trimMargin(),
        SearchStore.SQLITE to """
            |/* drivine:json */
            |WITH members AS (
            |    SELECT c.nid, (CASE json_type(c.props, '${'$'}."id"') WHEN 'text' THEN c.props ->> '${'$'}."id"' WHEN 'null' THEN NULL ELSE c.props -> '${'$'}."id"' END) AS id, c.props, g.pos
            |    FROM g_node c
            |    JOIN (
            |        SELECT (CASE v.type WHEN 'text' THEN v.value WHEN 'null' THEN NULL WHEN 'true' THEN 'true' WHEN 'false' THEN 'false' WHEN 'integer' THEN json_quote(v.value) WHEN 'real' THEN json_quote(v.value) ELSE v.value END) AS id, v.key AS pos FROM json_each(${'$'}_cy_vec1) v
            |    ) g ON g.id = (CASE json_type(c.props, '${'$'}."id"') WHEN 'text' THEN c.props ->> '${'$'}."id"' WHEN 'null' THEN NULL ELSE c.props -> '${'$'}."id"' END)
            |    WHERE instr(c.labels, '"Doc"') > 0
            |      AND json_type(c.props, '${'$'}."embedding"') = 'array'
            |      AND EXISTS (SELECT 1 FROM json_each(c.props, '${'$'}."embedding"') e WHERE e.value <> 0)),
            |ranked AS (
            |    SELECT p.seed, p.nid, p.id, p.score, ROW_NUMBER() OVER (PARTITION BY p.seed ORDER BY p.score DESC, p.nid) AS place
            |    FROM (
            |        SELECT s.nid AS seed, o.nid, o.id, drivine_similarity((o.props -> '${'$'}."embedding"'), (s.props -> '${'$'}."embedding"'), 'COSINE') AS score
            |        FROM members s
            |        JOIN members o ON o.pos > s.pos
            |    ) p
            |    WHERE p.score IS NOT NULL)
            |SELECT json_object(
            |    'seed', s.id,
            |    'near', json((
            |        SELECT json_group_array(json_object(
            |            'id', r.id,
            |            'score', r.score) ORDER BY r.score DESC, r.nid)
            |        FROM ranked r
            |        WHERE r.seed = s.nid
            |          AND r.place <= ${'$'}_cy_vec2
            |          AND r.score >= ${'$'}_cy_vec3))) AS result
            |FROM members s
        """.trimMargin(),
        SearchStore.NEO4J to """
            |UNWIND range(0, size(${'$'}_cy_vec1) - 1) AS _pos
            |WITH _pos, ${'$'}_cy_vec1[_pos] AS _seedId
            |MATCH (seed:Doc)
            |WHERE seed.id = _seedId AND seed.embedding IS NOT NULL AND any(_x IN seed.embedding WHERE _x <> 0)
            |CALL (seed, _pos) {
            |  UNWIND range(_pos + 1, size(${'$'}_cy_vec1) - 1) AS _next
            |  WITH seed, _next, ${'$'}_cy_vec1[_next] AS _nextId
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
        """.trimMargin(),
        SearchStore.MEMGRAPH to """
            |UNWIND range(0, size(${'$'}_cy_vec1) - 1) AS _pos
            |WITH _pos, ${'$'}_cy_vec1[_pos] AS _seedId
            |MATCH (seed:Doc)
            |WHERE seed.id = _seedId AND seed.embedding IS NOT NULL AND any(_x IN seed.embedding WHERE _x <> 0)
            |CALL {
            |  WITH seed, _pos
            |  UNWIND range(_pos + 1, size(${'$'}_cy_vec1) - 1) AS _next
            |  WITH seed, _next, ${'$'}_cy_vec1[_next] AS _nextId
            |  MATCH (n:Doc)
            |  WHERE n.id = _nextId AND n.embedding IS NOT NULL AND any(_x IN n.embedding WHERE _x <> 0) AND size(n.embedding) = size(seed.embedding)
            |  WITH n, _next, (1.0 + vector_search.cosine_similarity(seed.embedding, n.embedding)) / 2.0 AS _score
            |  WHERE _score >= ${'$'}_cy_vec2
            |  WITH n, _next, _score
            |  ORDER BY _score DESC, _next
            |  LIMIT ${'$'}_cy_vec3
            |  RETURN collect({id: n.id, score: _score}) AS near
            |}
            |RETURN {seed: _seedId, near: near} AS row
        """.trimMargin(),
        SearchStore.FALKORDB to """
            |UNWIND range(0, size(${'$'}_cy_vec1) - 1) AS _pos
            |WITH _pos, ${'$'}_cy_vec1[_pos] AS _seedId
            |MATCH (seed:Doc)
            |WHERE seed.id = _seedId AND seed.embedding IS NOT NULL
            |CALL {
            |  WITH seed, _pos
            |  UNWIND range(_pos + 1, size(${'$'}_cy_vec1) - 1) AS _next
            |  WITH seed, _next, ${'$'}_cy_vec1[_next] AS _nextId
            |  MATCH (n:Doc)
            |  WHERE n.id = _nextId AND n.embedding IS NOT NULL
            |  WITH n, _next, 1.0 - vec.cosineDistance(seed.embedding, n.embedding) / 2.0 AS _score
            |  WHERE _score >= ${'$'}_cy_vec2
            |  WITH n, _next, _score
            |  ORDER BY _score DESC, _next
            |  LIMIT ${'$'}_cy_vec3
            |  RETURN collect({id: n.id, score: _score}) AS near
            |}
            |RETURN {seed: _seedId, near: near} AS row
        """.trimMargin(),
    )

    @Test
    fun withoutSearchKEachStoreMeasuresEveryPairOfTheRootsAfterEachRoot() {
        for ((store, expected) in everyPair) {
            val planned = plan(store, request())
            assertEquals(expected, statement(planned), store.id)
            assertEquals(mapOf("_cy_vec1" to listOf("a", "b", "c")), planned.parameters.filterKeys { it == "_cy_vec1" }, store.id)
            assertEquals(0, planned.indexK, "${store.id}: no index is asked")
            assertEquals(emptyList(), planned.warnings, store.id)
        }
    }

    @Test
    fun withoutSearchKAMadeIndexIsNotAsked() {
        // An index made on Postgres or Oracle changes nothing: the roots are measured, as where none is made.
        for (store in listOf(SearchStore.POSTGRES, SearchStore.ORACLE)) {
            assertEquals(everyPair.getValue(store), statement(plan(store, request(dimensions = 3))), store.id)
        }
    }

    @Test
    fun aGraphEngineMeasuresEachPairByItsOwnFunctionOfTwoEmbeddings() {
        val functions = mapOf(
            SearchStore.NEO4J to listOf("vector.similarity.cosine(seed.embedding, n.embedding)", "vector.similarity.euclidean(seed.embedding, n.embedding)"),
            SearchStore.MEMGRAPH to listOf(
                "(1.0 + vector_search.cosine_similarity(seed.embedding, n.embedding)) / 2.0",
                "1.0 / (1.0 + reduce(_d = 0.0, _i IN range(0, size(seed.embedding) - 1) | _d + (seed.embedding[_i] - n.embedding[_i]) ^ 2))",
            ),
            SearchStore.FALKORDB to listOf("1.0 - vec.cosineDistance(seed.embedding, n.embedding) / 2.0", "1.0 / (1.0 + vec.euclideanDistance(seed.embedding, n.embedding) ^ 2)"),
        )
        for ((store, scores) in functions) for ((similarity, score) in SimilarityFunction.entries.zip(scores)) {
            val cypher = plan(store, request(similarity)).cypher!!
            assertTrue("WITH n, _next, $score AS _score" in cypher, "${store.id} ${similarity.name}:\n$cypher")
            assertTrue("CALL db.index" !in cypher && "vector_search.search" !in cypher && "db.idx" !in cypher, "${store.id}: no index is asked")
            // An embedding of no length has no cosine, and Neo4j's and Memgraph's functions refuse one; Euclidean measures it.
            val length = "any(_x IN n.embedding WHERE _x <> 0)"
            assertEquals(similarity == SimilarityFunction.COSINE && store != SearchStore.FALKORDB, length in cypher, "${store.id} ${similarity.name}")
            // Each engine's function refuses two sizes; FalkorDB can't tell a stored vector's size.
            assertEquals(store != SearchStore.FALKORDB, "size(n.embedding) = size(seed.embedding)" in cypher, store.id)
        }
    }

    @Test
    fun aGraphEngineFindsARootOfEveryLabelAndItsNeighboursToo() {
        for (store in listOf(SearchStore.NEO4J, SearchStore.MEMGRAPH, SearchStore.FALKORDB)) {
            val cypher = plan(store, request(labels = listOf("Doc", "Chunk"))).cypher!!
            assertTrue("MATCH (seed:Doc:Chunk)\n" in cypher && "  MATCH (n:Doc:Chunk)\n" in cypher, "${store.id}:\n$cypher")
        }
    }

    @Test
    fun theDirectionIsKeptBeforeEachRootsTopKIsCut() {
        // The later roots alone are measured, so a root whose nearest all come before it still keeps its topK after it.
        for (store in listOf(SearchStore.NEO4J, SearchStore.MEMGRAPH, SearchStore.FALKORDB)) {
            val cypher = plan(store, request()).cypher!!
            assertTrue(cypher.indexOf("UNWIND range(_pos + 1, size(\$_cy_vec1) - 1) AS _next") in 0 until cypher.indexOf("LIMIT \$_cy_vec3"), cypher)
        }
        // On Postgres each root's own LIMIT cuts it; on Oracle and SQLite each pair is numbered within its root, and cut by place.
        for (store in listOf(SearchStore.POSTGRES, SearchStore.ORACLE, SearchStore.SQLITE)) {
            val sql = plan(store, request()).sql!!
            val cut = if (store == SearchStore.POSTGRES) "LIMIT \$_cy_vec2" else "r.place <= \$_cy_vec2"
            assertTrue(sql.indexOf("o.pos > s.pos") in 0 until sql.indexOf(cut), "${store.id}:\n$sql")
        }
    }

    @Test
    fun onlyFalkorDbIsGivenATimeoutAndOnlyToMeasureEveryPair() {
        for (store in SearchStore.entries) for (searchK in listOf(null, 20)) {
            val expected = if (store == SearchStore.FALKORDB && searchK == null) VectorSearch.FALKORDB_AMONG_TIMEOUT_MILLIS else null
            assertEquals(expected, plan(store, request(searchK = searchK)).timeoutMillis, "${store.id} $searchK")
            assertNull(plan(store, request(searchK = searchK, neighbours = AmongNeighbours.All)).timeoutMillis, "${store.id} all")
        }
    }

    @Test
    fun withSearchKEachRootAsksTheIndexAndKeepsWhatItFindsAfterItAndThePlanWarns() {
        for (store in listOf(SearchStore.NEO4J, SearchStore.MEMGRAPH, SearchStore.FALKORDB)) {
            val planned = plan(store, request(searchK = 20))
            val cypher = planned.cypher!!
            assertTrue(cypher.startsWith("UNWIND range(0, size(\$_cy_vec1) - 1) AS _pos\nWITH _pos, \$_cy_vec1[_pos] AS _seedId\n"), cypher)
            assertTrue("[m IN _found WHERE m.id IN \$_cy_vec1[(_pos + 1)..] AND m.score >= \$_cy_vec3][0..\$_cy_vec4]" in cypher, cypher)
            assertEquals(20, planned.indexK, store.id)
            assertEquals(1, planned.warnings.size, store.id)
            assertTrue("pairs can be missed" in planned.warnings.single() && "nearest 20" in planned.warnings.single(), planned.warnings.single())
        }
        for (store in listOf(SearchStore.POSTGRES, SearchStore.ORACLE)) {
            val through = plan(store, request(searchK = 20, dimensions = 3))
            assertTrue("o.pos > s.pos" in through.sql!!, through.sql)
            assertEquals(1, through.warnings.size, "${store.id} through its index")
            // Without an index made, searchK changes nothing: every pair is measured, and nothing is warned.
            val measured = plan(store, request(searchK = 20))
            assertEquals(everyPair.getValue(store), measured.sql, store.id)
            assertEquals(emptyList(), measured.warnings, "${store.id} with no index made")
        }
        assertEquals(emptyList(), plan(SearchStore.SQLITE, request(searchK = 20)).warnings, "SQLite has no index")
        assertEquals(emptyList(), plan(SearchStore.SQLITE, request(searchK = 20, dimensions = 3)).warnings, "SQLite has no index, whatever dimensions are given")
    }

    @Test
    fun withoutAThresholdAGraphEngineKeepsEachRootsBestTopK() {
        for (store in listOf(SearchStore.NEO4J, SearchStore.MEMGRAPH, SearchStore.FALKORDB)) {
            val planned = plan(store, request(threshold = null))
            val cypher = planned.cypher!!
            assertTrue("_score >=" !in cypher, "${store.id}:\n$cypher")
            assertTrue(" AS _score\n  ORDER BY _score DESC, _next\n  LIMIT \$_cy_vec2\n" in cypher, "${store.id}:\n$cypher")
            assertEquals(setOf("_cy_vec1", "_cy_vec2"), planned.parameters.keys, store.id)
        }
    }

    @Test
    fun theSearchForEveryNeighbourTakesNoPlaces() {
        // All is as it was before After: the golden pins its statements, and none reads a place.
        for (store in SearchStore.entries) for (searchK in listOf(null, 20)) for (dimensions in listOf(null, 3)) {
            val planned = plan(store, request(searchK = searchK, dimensions = dimensions, neighbours = AmongNeighbours.All))
            val text = statement(planned)
            assertTrue("_pos" !in text && "pos >" !in text && "ORDINALITY" !in text, "${store.id}:\n$text")
            assertEquals(emptyList(), planned.warnings, store.id)
        }
    }

    @Test
    fun theJsonRequestGivesTheNeighboursAndTheAnswerItsTimeout() {
        val base = "\"labels\": [\"Doc\"], \"property\": \"embedding\", \"similarity\": \"COSINE\", \"topK\": 5"
        fun raw(store: SearchStore, among: String) = CompileJson.compile("{\"store\": \"${store.id}\", \"graph\": {\"name\": \"g\"}, \"vector\": {$base, \"among\": $among}}")
        fun answer(store: SearchStore, among: String) = CypherJson.readTree(raw(store, among)) as ObjectNode
        for (store in SearchStore.entries) {
            val after = answer(store, "{\"ids\": [\"a\", \"b\", \"c\"], \"idProperty\": \"id\", \"threshold\": 0.5, \"neighbours\": \"after\"}")
            assertEquals(everyPair.getValue(store), (after["sql"] ?: after["cypher"])!!.textValue(), store.id)
            assertEquals(if (store == SearchStore.FALKORDB) VectorSearch.FALKORDB_AMONG_TIMEOUT_MILLIS else null, after["timeoutMillis"]?.longValue(), store.id)
            assertEquals(raw(store, "{\"ids\": [\"a\"], \"idProperty\": \"id\"}"), raw(store, "{\"ids\": [\"a\"], \"idProperty\": \"id\", \"neighbours\": \"all\"}"), "${store.id}: all is the default")
            val all = answer(store, "{\"ids\": [\"a\"], \"idProperty\": \"id\", \"neighbours\": \"all\"}")
            assertTrue(!all.has("timeoutMillis") && (all["warnings"] as ArrayNode).size() == 0, store.id)
        }
        for (given in listOf("\"sideways\"", "1", "true")) {
            val refused = answer(SearchStore.POSTGRES, "{\"ids\": [\"a\"], \"idProperty\": \"id\", \"neighbours\": $given}")
            assertTrue(refused["error"]!!.textValue()!!.startsWith("A request's among gives its neighbours as \"all\" or \"after\""), refused.toString())
        }
    }
}
