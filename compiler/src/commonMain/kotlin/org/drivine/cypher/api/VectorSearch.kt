package org.drivine.cypher.api

import org.drivine.DrivineInternalApi
import org.drivine.cypher.search.Derived
import org.drivine.cypher.search.GraphTable
import org.drivine.cypher.search.OnceReached
import org.drivine.cypher.search.Paired
import org.drivine.cypher.search.Placed
import org.drivine.cypher.search.Query
import org.drivine.cypher.search.SearchParameters
import org.drivine.cypher.search.Table
import org.drivine.cypher.store.IdSql
import org.drivine.cypher.store.IndexKey
import org.drivine.cypher.store.OracleSql
import org.drivine.cypher.store.PostgresSql
import org.drivine.cypher.store.SqliteSql
import org.drivine.cypher.store.ValueLayout
import org.drivine.cypher.store.ddl.CypherNames
import org.drivine.cypher.store.ddl.OracleDdl
import org.drivine.cypher.store.ddl.PostgresDdl
import org.drivine.cypher.store.ddl.SqliteDdl
import org.drivine.schema.SimilarityFunction

/**
 * What the library found of the vector index a search goes through.
 *
 * @param dimensions on Postgres and Oracle, the size of the embeddings the index on the property was made for, as the catalog
 *   records it for the label searched ([VectorRequest.partitionLabel], or the root's first); null where none is made. A nearest search goes through the index only where it is the query vector's
 *   size, and measures every node otherwise; a search among ids with a `searchK` goes through any index made, and one
 *   without measures every pair.
 * @param name on a graph engine, the index's name where it isn't `<label>_<property>_vector`; a blank one is none.
 */
@DrivineInternalApi
data class VectorIndex(val dimensions: Int? = null, val name: String? = null)

/**
 * A search among ids: for each root among [ids] that holds the embedding, its nearest among the others. By cosine, on a SQL
 * store, a root whose embedding has no length, all zeros, is left out, as a seed and as a neighbour.
 *
 * @param ids the roots' ids, as the text each is found by; a repeat is dropped, as drivine4j's `loadNearestAmong` drops it.
 *   The library gives them in its id type's own order, as their places decide which neighbours a root keeps for each pair
 *   once ([AmongNeighbours.After]), and, of two neighbours that score exactly alike, which comes first, on every store but
 *   FalkorDB through the index, where the index's own order decides (liberation-data/drivine-cypher-spec#412, #416). Neo4j and FalkorDB score in float32, so two pairs that score alike in exact
 *   arithmetic can differ in their last bit on one store and not another: only an exact tie is decided so.
 * @param idProperty the property the root's id is stored under.
 * @param threshold the least score a neighbour is kept with, or null to keep any.
 * @param neighbours which of the other roots each root keeps: every one ([AmongNeighbours.All]), or those after it in [ids]
 *   ([AmongNeighbours.After]), so each pair comes back once.
 */
@DrivineInternalApi
data class VectorAmong(
    val ids: List<String>,
    val idProperty: String,
    val threshold: Double? = null,
    val neighbours: AmongNeighbours = AmongNeighbours.All,
)

/**
 * Which of the other roots a search among ids ([VectorAmong]) keeps as a root's neighbours, before each root's best `topK` are
 * cut. A neighbour predicate of the caller's own would be a case of its own, kept where these are.
 */
@DrivineInternalApi
sealed interface AmongNeighbours {

    /**
     * Every other root: a pair can come back twice, once under each of its roots.
     *
     * With [VectorRequest.searchK] left out, every pair of the roots is measured, through no index: exact, its cost growing
     * with the square of the number of roots. With it, each root asks the vector index for its `searchK` nearest, where one
     * is made, and keeps those among the roots: a root's neighbours the index doesn't give it are missed, and the plan warns
     * so (liberation-data/drivine-cypher-spec#408).
     */
    data object All : AmongNeighbours

    /**
     * Only the roots after the root in the ids as given, a repeat dropped: each pair comes back once, under the root given
     * first, and none is lost to the `topK` cut, as a root's nearest may all come before it. The library gives the ids in its
     * id type's own order (numbers by value, text by code point, temporals by time), as the module compares no two ids: it
     * keeps a root's neighbours by their places among the ids alone, so every store agrees.
     *
     * With [VectorRequest.searchK] left out, every pair of the roots is measured, through no index: exact, its cost growing
     * with the square of the number of roots. With it, each root asks the vector index for its `searchK` nearest, where one is
     * made, and keeps those after it: a pair the index doesn't give the first of its roots is missed, and the plan warns so.
     */
    data object After : AmongNeighbours
}

/**
 * A vector search, as a library asks it: drivine4j's `loadNearest`, or with [among] its `loadNearestAmong`.
 *
 * @param labels the root's labels; the index is on the first, or on [partitionLabel].
 * @param property the property the embedding is stored under, as the index covers it.
 * @param similarity what the index measures, and so the search's score.
 * @param topK how many the search wants: on a nearest search the library's `LIMIT` after its filters, and what the index is
 *   asked for where [searchK] is null; among ids, how many neighbours each root keeps.
 * @param searchK how many the index is asked for, where it is more than [topK]: the nearest `searchK` are measured, and of
 *   what is left of them after the library's filters `topK` come back. On Neo4j a nearest search with it scores what the index
 *   found exactly, as the index's own score doesn't order quite as the exact one does. Among ids, each root asks the index
 *   for its `searchK` nearest, where one is made; where it is null no index is asked, and every pair of the roots is
 *   measured.
 * @param size the query vector's size, on a nearest search on a SQL store: only embeddings of that size are measured.
 * @param partitionLabel the label whose index is searched, in place of the root's first: one index for each partition, of the
 *   same property. The root's own labels are still checked. A search among ids takes none.
 * @param index what the library found of the index.
 * @param among a search among ids, in place of a nearest search.
 * @param alias on a graph engine, what the head names a hit's node.
 * @param scoreAlias on a graph engine, what the head names a hit's score.
 */
@DrivineInternalApi
data class VectorRequest(
    val labels: List<String>,
    val property: String,
    val similarity: SimilarityFunction,
    val topK: Int,
    val searchK: Int? = null,
    val size: Int? = null,
    val partitionLabel: String? = null,
    val index: VectorIndex = VectorIndex(),
    val among: VectorAmong? = null,
    val alias: String = "n",
    val scoreAlias: String = "_score",
)

/** What planning a vector search ([VectorSearch.plan]) gives: a [VectorPlan], or the [Refusal] of the search. */
@DrivineInternalApi
sealed interface VectorResult

/**
 * A vector search as a statement.
 *
 * @param sql on a SQL store, a nearest search's ranked candidates, one `SELECT` of each node's row, `nid`, `labels`, `props`
 *   and `stamp`, and its `score`: the nearest [VectorRequest.searchK] (or `topK`) nodes, each scored as the graph engines
 *   score it, in `[0, 1]`, higher the nearer. The library reads its projection from those columns, with no second join of its
 *   node table (on Oracle that join loses a filter on it: see `ROW`), and adds its threshold (`score >= threshold`), its
 *   predicates, the view's required relationships, `ORDER BY score DESC, nid` and, where `searchK` was given, its `LIMIT topK`.
 *   A search among ids is the whole statement: one row a root, `result`, the document `{seed, near}`, `near` its neighbours
 *   as `{id, score}`, best first, and of two that score alike the one placed first among [VectorAmong.ids].
 * @param cypher on a graph engine, a nearest search's head: the index's `CALL`, and each hit as [VectorRequest.alias] and its
 *   score as [VectorRequest.scoreAlias], to which the library adds [labelCheck], its threshold, filters, projection and
 *   `LIMIT`. A search among ids is the whole statement, one row a root, `row`, `{seed, near}`.
 * @param parameters the values the statement binds, each named `_cy_vec<n>`: all but the query vector.
 *   Through the index on Neo4j and Memgraph, a search among ids binds a map too, each id's text to its place among the ids,
 *   counted from 0 (#412): its keys are the ids, any text, so the library binds it as a map of those keys as given.
 * @param vector the parameter the library binds the query vector as, a list of floats as it binds a list; null among ids,
 *   where each root's own embedding is the vector.
 * @param labelCheck on a graph engine, whether a hit has every one of the root's labels, as a predicate on the node: the index
 *   is on one label, so a hit with that label alone is no root. Null where the root's one label is the index's. The library
 *   tests it before the threshold and its filters.
 * @param indexK how many the index is asked for: [VectorRequest.searchK], or where that is null `topK` on a nearest search;
 *   0 on a search among ids that measures every pair of the roots, asking no index.
 * @param warnings what the module warns of, for the library to log.
 * @param timeoutMillis on FalkorDB, the timeout the library runs the statement under, in milliseconds (`GRAPH.QUERY`'s
 *   `TIMEOUT`); null to run it as it runs any statement.
 */
@DrivineInternalApi
data class VectorPlan(
    val sql: String?,
    val cypher: String?,
    val parameters: Map<String, Any?>,
    val vector: String?,
    val labelCheck: String?,
    val indexK: Int,
    val warnings: List<String> = emptyList(),
    val timeoutMillis: Long? = null,
) : VectorResult

/**
 * Vector search, as drivine4j writes it, for every library: which way a store answers it (through its vector index, or by
 * measuring every node), the statement that does, and the score, the same on every store. Pure: the library finds what its
 * catalog records of the index, gives it as [VectorIndex], runs the statement [plan] gives, and keeps its own projection.
 *
 * A search's score is the graph engines': for cosine `(1 + cos) / 2`, for Euclidean `1 / (1 + d²)`. On Postgres and Oracle the
 * search reads the embedding by the expression the module's vector index is made over (`PostgresDdl`, `OracleDdl`), so the
 * index answers it.
 */
@DrivineInternalApi
object VectorSearch {

    /** What every parameter of a vector search is named with: the compiler's reserved `_cy_`, which no library binds. */
    const val PREFIX: String = "_cy_vec"

    /**
     * The timeout, in milliseconds, FalkorDB runs a search among ids under where it measures every pair of the roots, as
     * without `searchK`. It is a ceiling, not a target: FalkorDB's image sets the legacy `TIMEOUT` to
     * 1 s, which would end a search of a few hundred roots that runs longer. Were it ever to be tuned, it would be given on the
     * request; there is no setting for it.
     */
    const val FALKORDB_AMONG_TIMEOUT_MILLIS: Long = 300_000

    /** The vector search [request] asks of [store], over [graph]: a [VectorPlan], or a [Refusal] of it or of its graph. */
    fun plan(store: SearchStore, graph: GraphOptions, request: VectorRequest): VectorResult {
        require(request.labels.isNotEmpty()) { "A vector search names the root's labels." }
        require(request.property.isNotEmpty()) { "A vector search names the property its embedding is stored under." }
        // Each id once, as drivine4j's loadNearestAmong makes the set: a repeat would give a graph engine's root a row twice.
        val among = request.among?.let { it.copy(ids = it.ids.distinct()) }
        require(among == null || request.partitionLabel == null) { "A vector search among ids takes no partitionLabel." }
        require(request.partitionLabel == null || request.index.name.isNullOrBlank()) {
            "A vector search of partition '${request.partitionLabel}' names no index: each partition's index is named for its label."
        }
        require(among != null || store.graphEngine || request.size != null) { "A nearest search on ${store.id} gives its vector's size." }
        return try {
            val scope = SearchParameters(PREFIX)
            if (store.graphEngine) {
                when {
                    among == null -> graphNearest(store, request, scope)
                    measuresEveryPair(request) -> graphAmongEveryPair(store, request, among, scope)
                    else -> graphAmong(store, request, among, scope)
                }
            } else if (among != null) {
                val sql = when (store) {
                    SearchStore.POSTGRES -> postgresAmong(PostgresDdl(graph.name), request, among, scope)
                    SearchStore.ORACLE -> oracleAmong(OracleDdl(graph.name), request, among, scope)
                    else -> sqliteAmong(SqliteDdl(graph.name), request, among, scope)
                }
                VectorPlan(
                    sql = sql, cypher = null, parameters = scope.bindings(), vector = null, labelCheck = null,
                    indexK = if (measuresEveryPair(request)) 0 else amongK(request), warnings = amongWarnings(store, request, among),
                )
            } else {
                val vector = scope.reserved()
                val nearest = request.searchK ?: request.topK
                val sql = candidates(store, graph.name, request, "\$$vector", scope.bound(nearest))
                VectorPlan(sql = sql, cypher = null, parameters = scope.bindings(), vector = vector, labelCheck = null, indexK = nearest)
            }
        } catch (e: IllegalArgumentException) {
            Refusal(e.message ?: e.toString())
        }
    }

    /**
     * A nearest search's candidates on the SQL store [store], over the graph [graph]: one `SELECT` of each node's row and its `score`, the
     * [k] nodes nearest the query vector [vector], best first, through the index where [VectorRequest.index]'s dimensions are
     * [VectorRequest.size], else measuring every node of that size. [vector] and [k] are SQL: the parameters [plan] binds,
     * or what a compiled statement reads (`db.index.vector.queryNodes`, liberation-data/drivine-cypher-spec#160). On
     * Postgres [vector] is a text pgvector reads as a vector; on Oracle one `TO_VECTOR` reads; on SQLite a JSON array.
     */
    internal fun candidates(store: SearchStore, graph: String, request: VectorRequest, vector: String, k: String): String {
        val size = request.size!!
        val through = request.index.dimensions == size
        return when (store) {
            SearchStore.POSTGRES -> {
                val ddl = PostgresDdl(graph)
                if (through) postgresThrough(ddl, request, size, vector, k) else postgresTo(ddl, request, size, vector, k)
            }
            SearchStore.ORACLE -> {
                val ddl = OracleDdl(graph)
                if (through) oracleThrough(ddl, request, vector, k) else oracleTo(ddl, request, size, vector, k)
            }
            else -> sqliteTo(SqliteDdl(graph), request, size, vector, k)
        }
    }

    /**
     * The key a node's `props` holds [property] at, which the vector index is made over, as the DDL's index (`PostgresDdl`,
     * `OracleDdl`) reads it: store layout 2 (#161) holds a property whose name begins with `$` under an escaped key, `$$e`
     * for `$e` ([ValueLayout.escape]), and any other under its own name. The one place a search names it.
     */
    private fun stored(property: String): String = ValueLayout.escape(property)

    /**
     * The join of a root, the node `c`, to its place among the ids on Oracle or SQLite: the node's id held under [key], as the
     * index over it is made ([IndexKey.column]), equal to the given id's class and key, as `placedIds` reads them once for each
     * id. So a root is found as a library's lookup by its id finds it, a temporal id by its tag, through the index.
     */
    private fun placedOn(store: Store, key: String): String {
        val (idClass, idKey) = IndexKey.column(store, "c", key)
        return "$idClass = g.id_class AND $idKey = g.id_key"
    }

    /** The label the index a search goes through is on: its partition's, or the root's first. */
    private fun indexLabel(request: VectorRequest): String = request.partitionLabel ?: request.labels.first()

    /** How many a search among ids that goes through the index asks it for, for each root: `searchK`, which it is given. */
    private fun amongK(request: VectorRequest): Int = requireNotNull(request.searchK) { "A search among ids without searchK asks no index." }

    /**
     * Whether a search among ids measures every pair of the roots, through no index made: one without `searchK`, whichever
     * neighbours it keeps (liberation-data/drivine-cypher-spec#408).
     */
    private fun measuresEveryPair(request: VectorRequest): Boolean = request.searchK == null

    /**
     * What a search among ids warns of: one that goes through an index, as `searchK` is given, that its answer is the
     * index's, approximate. SQLite makes no vector index, so it measures every pair whatever dimensions a request gives.
     */
    private fun amongWarnings(store: SearchStore, request: VectorRequest, among: VectorAmong): List<String> {
        val throughIndex = store.graphEngine || (store != SearchStore.SQLITE && request.index.dimensions != null)
        val searchK = request.searchK
        return if (searchK == null || !throughIndex) emptyList()
        else listOf(
            when (among.neighbours) {
                AmongNeighbours.After ->
                    "A search among ids for the neighbours after each root goes through the vector index, as searchK is given: a pair " +
                        "is found only where the index gives the later root among the earlier one's nearest $searchK, so pairs " +
                        "can be missed. Leave searchK out to measure every pair of the ids exactly."
                AmongNeighbours.All ->
                    "A search among ids goes through the vector index, as searchK is given: a root's neighbours are only those the " +
                        "index gives among its nearest $searchK, approximately, so they can be incomplete. Leave searchK out to " +
                        "measure every pair of the ids exactly."
            },
        )
    }

    // ----- A stored embedding of no length -----
    //
    // Measured by cosine, an embedding all of zeros has no direction, and its distance is NaN: Neo4j leaves it out of a cosine
    // index, so a search never finds it (vector.yaml, "Only the index's nodes"). Postgres's HNSW index leaves it out too, but
    // a search without one, Oracle's and SQLite's, measured it, and NaN sorted first. Each is left out here, on a cosine
    // search, a nearest search's candidates and a search among ids' roots alike; a euclidean one measures it, as Neo4j does.

    /** Whether the embedding under [key] of [alias], of [dimensions], has a length, on a cosine search; null on another. */
    private fun postgresLength(request: VectorRequest, alias: String, key: String, dimensions: Int): String? =
        if (request.similarity != SimilarityFunction.COSINE) null
        // Read only where it is a list of the size, as the cast fails on any other value.
        else "CASE WHEN ${PostgresSql.holdsEmbedding(alias, key, dimensions)} THEN vector_norm(${PostgresSql.embedding(alias, key, dimensions)}) > 0 END"

    /**
     * Whether the embedding under [key] of [alias], a list of any size, has a length, on a cosine search; null on another: a
     * search among ids leaves such a root out, as a seed and as a neighbour, where no index is made to read it by its size.
     */
    private fun postgresAnyLength(request: VectorRequest, alias: String, key: String): String? =
        if (request.similarity != SimilarityFunction.COSINE) null
        else "CASE WHEN jsonb_typeof(${PostgresSql.value(alias, key)}) = 'array' THEN vector_norm(${PostgresSql.embedding(alias, key)}) > 0 END"

    /** Whether the embedding [embedding], read as a vector, has a length, on a cosine search; null on another. */
    private fun oracleLength(request: VectorRequest, embedding: String): String? =
        if (request.similarity != SimilarityFunction.COSINE) null else "VECTOR_NORM($embedding) > 0"

    /** Whether the embedding under [key] of [alias] holds a number other than 0, on a cosine search; null on another. */
    private fun sqliteLength(request: VectorRequest, alias: String, key: String): String? =
        if (request.similarity != SimilarityFunction.COSINE) null
        else "EXISTS (SELECT 1 FROM json_each(${SqliteSql.column(alias, "props")}, ${SqliteSql.path(key)}) e WHERE e.value <> 0)"

    // ----- Postgres: drivine4j's PostgresSelect nearestTo, nearestThrough and nearestAmong -----

    /**
     * Every node of the root's labels, and the partition's, that holds an embedding of the vector's size is measured, so the
     * answer is exact: pgvector refuses to measure two sizes.
     */
    private fun postgresTo(ddl: PostgresDdl, request: VectorRequest, size: Int, vector: String, k: String): String {
        val key = stored(request.property)
        val distance = "${PostgresSql.embedding("c", key)} ${PostgresSql.distance(request.similarity)} CAST($vector AS vector)"
        val has = listOf(PostgresSql.hasLabels("c", request.labels + listOfNotNull(request.partitionLabel)), PostgresSql.holdsEmbedding("c", key, size)) +
            listOfNotNull(postgresLength(request, "c", key, size))
        return Query(
            "$ROW, ${PostgresSql.score(request.similarity, distance)} AS score",
            Table(ddl.nodes, "c"), has, listOf("ORDER BY score DESC, c.nid", "LIMIT $k"),
        ).text()
    }

    /**
     * Through the HNSW index over the embeddings of the index's label of its size: the index gives its nearest,
     * approximately, and they are then tested for the root's own labels. The search reads the embedding as the index is made
     * over it, and names what the index holds, so the index answers it.
     */
    private fun postgresThrough(ddl: PostgresDdl, request: VectorRequest, dimensions: Int, vector: String, k: String): String {
        val key = stored(request.property)
        val distance = "${PostgresSql.embedding("s", key, dimensions)} ${PostgresSql.distance(request.similarity)} CAST($vector AS vector($dimensions))"
        val indexed = Query(
            "s.nid, $distance AS distance", Table(ddl.nodes, "s"),
            listOf(PostgresSql.hasLabel("s", indexLabel(request)), PostgresSql.holdsEmbedding("s", key, dimensions)) +
                listOfNotNull(postgresLength(request, "s", key, dimensions)),
            listOf("ORDER BY distance", "LIMIT $k"),
        )
        // drivine4j's text, trimmed of its indent as drivine4j trims it: its lines keep what of it the nested query's don't have.
        return """
            SELECT $ROW, ${PostgresSql.score(request.similarity, "k.distance")} AS score
            FROM ${indexed.nested()} k
            JOIN ${ddl.nodes} c ON c.nid = k.nid
            WHERE ${PostgresSql.hasLabels("c", request.labels)}
        """.trimIndent()
    }

    /**
     * Without `searchK`, or without an index made, each root measures only the other roots whose embeddings are of its size:
     * exact. With both, each root asks the index over the embeddings of the root's first label for its `searchK` nearest, and
     * keeps those among the roots: approximate, as the index is. For each pair once ([AmongNeighbours.After]) each root keeps
     * only the roots placed after it in the ids.
     */
    private fun postgresAmong(ddl: PostgresDdl, request: VectorRequest, among: VectorAmong, scope: SearchParameters): String {
        val key = stored(request.property)
        val idKey = stored(among.idProperty)
        val id = IdSql.keyText(Store.POSTGRES, "c", idKey)
        val after = among.neighbours == AmongNeighbours.After
        val dimensions = request.index.dimensions.takeUnless { measuresEveryPair(request) }
        val embedded = dimensions?.let { PostgresSql.holdsEmbedding("c", key, it) } ?: "jsonb_typeof(${PostgresSql.value("c", key)}) = 'array'"
        val ids = scope.bound(among.ids)
        // The roots are found through the index over the id, as a lookup by id finds them, and each joined to its place.
        val has = listOf(PostgresSql.hasLabels("c", request.labels), embedded, IdSql.hasIdIn(Store.POSTGRES, "c", idKey, ids)) +
            listOfNotNull(postgresAnyLength(request, "c", key))
        val members = Query("c.nid, $id AS id, c.props, g.pos", Placed(ddl.nodes, "c", PostgresSql.placedIds(ids), "g", "${IndexKey.column(Store.POSTGRES, "c", idKey).single()} = g.json"), has)
        val other = if (after) "o.pos > s.pos" else "o.nid <> s.nid"
        val nearest = if (dimensions == null) {
            val distance = "${PostgresSql.embedding("o", key)} ${PostgresSql.distance(request.similarity)} ${PostgresSql.embedding("s", key)}"
            Query(
                "o.nid, o.id, o.pos, ${PostgresSql.score(request.similarity, distance)} AS score", Table(MEMBERS, "o"),
                listOf(other, "jsonb_array_length(${PostgresSql.value("o", key)}) = jsonb_array_length(${PostgresSql.value("s", key)})"),
                listOf("ORDER BY score DESC, o.pos, o.nid", "LIMIT ${scope.bound(request.topK)}"),
            )
        } else {
            val distance = "${PostgresSql.embedding("x", key, dimensions)} ${PostgresSql.distance(request.similarity)} ${PostgresSql.embedding("s", key, dimensions)}"
            val indexed = Query(
                "x.nid, $distance AS distance", Table(ddl.nodes, "x"),
                listOf(PostgresSql.hasLabel("x", indexLabel(request)), PostgresSql.holdsEmbedding("x", key, dimensions)),
                listOf("ORDER BY distance", "LIMIT ${scope.bound(amongK(request))}"),
            )
            Query(
                "o.nid, o.id, o.pos, ${PostgresSql.score(request.similarity, "k.distance")} AS score", OnceReached(indexed.text(), "k", MEMBERS, "o"),
                listOf(other), listOf("ORDER BY score DESC, o.pos, o.nid", "LIMIT ${scope.bound(request.topK)}"),
            )
        }
        val near = Query(
            PostgresSql.arrayOf(PostgresSql.jsonObject(listOf("id" to "r.id", "score" to "r.score")), "r.score DESC, r.pos, r.nid"),
            Derived(nearest.text(), "r"), listOfNotNull(among.threshold?.let { "r.score >= ${scope.bound(it)}" }),
        )
        val result = PostgresSql.jsonObject(listOf("seed" to "s.id", "near" to "COALESCE(${near.nested()}, ${PostgresSql.EMPTY_ARRAY})"))
        return "WITH $MEMBERS AS ${members.nested()}\n" + Query("$result AS result", Table(MEMBERS, "s")).text()
    }

    // ----- Oracle: drivine4j's OracleSelect nearestTo, nearestThrough and nearestAmong -----

    /**
     * Every node of the root's labels, and the partition's, that holds an embedding of the vector's size is measured, read
     * through the property graph: exact. A node with another size is left out, as Oracle refuses to measure two sizes.
     */
    private fun oracleTo(ddl: OracleDdl, request: VectorRequest, size: Int, vector: String, k: String): String {
        val key = stored(request.property)
        val score = OracleSql.score(request.similarity, "VECTOR_DISTANCE(${OracleSql.embedding("c", key)}, TO_VECTOR($vector), ${OracleSql.metric(request.similarity)})")
        val has = listOf(
            OracleSql.hasLabels("c", request.labels + listOfNotNull(request.partitionLabel), scanned = true),
            OracleSql.hasProperty("c", key),
            OracleSql.holdsEmbedding("c", key, size),
        ).plus(listOfNotNull(oracleLength(request, OracleSql.embedding("c", key)))).joinToString(" AND ")
        return Query(
            "$ROW, $score AS score", GraphTable(ddl.name, OracleSql.node("c", has), OracleSql.NODE_COLUMNS, "c"),
            tail = listOf("ORDER BY score DESC, c.nid", "FETCH FIRST $k ROWS ONLY"),
        ).text()
    }

    /**
     * Through the vector index over the side table of the index's label's embeddings ([OracleDdl.vectors]), which holds those
     * of its size: the index gives its nearest, approximately, and they are then tested for the root's own labels.
     */
    private fun oracleThrough(ddl: OracleDdl, request: VectorRequest, vector: String, k: String): String {
        val embeddings = ddl.vectors(indexLabel(request), request.property)
        val distance = "VECTOR_DISTANCE(s.embedding, TO_VECTOR($vector), ${OracleSql.metric(request.similarity)})"
        return """
            SELECT $ROW, ${OracleSql.score(request.similarity, "k.distance")} AS score
            FROM (SELECT s.nid, $distance AS distance FROM $embeddings s${oracleLength(request, "s.embedding")?.let { " WHERE $it" } ?: ""} ORDER BY distance FETCH APPROX FIRST $k ROWS ONLY) k
            JOIN ${ddl.nodes} c ON c.nid = k.nid
            WHERE ${OracleSql.hasLabels("c", request.labels)}
        """.trimIndent()
    }

    /**
     * Without `searchK`, or without an index made, every pair of roots whose embeddings are of one size is measured: exact.
     * With both, each root asks the index over the side table for its `searchK` nearest, and keeps those among the roots:
     * approximate. Only the roots the side table holds are searched from, as it holds only embeddings of the index's size.
     * For each pair once ([AmongNeighbours.After]) each root keeps only the roots placed after it in the ids. Either way each
     * root's neighbours are numbered best first, a tie of two scores by place among the ids, and the first `topK` kept.
     */
    private fun oracleAmong(ddl: OracleDdl, request: VectorRequest, among: VectorAmong, scope: SearchParameters): String {
        val key = stored(request.property)
        val idKey = stored(among.idProperty)
        val id = IdSql.keyText(Store.ORACLE, "c", idKey)
        val after = among.neighbours == AmongNeighbours.After
        val embeddings = request.index.dimensions?.takeUnless { measuresEveryPair(request) }?.let { ddl.vectors(request.labels.first(), request.property) }
        val ids = scope.bound(among.ids)
        val selected = "c.nid, $id AS id, ${OracleSql.embedding("c", key)} AS embedding"
        val has = listOfNotNull(
            OracleSql.hasLabels("c", request.labels), OracleSql.hasProperty("c", key), IdSql.hasIdIn(Store.ORACLE, "c", idKey, ids),
            embeddings?.let { "c.nid IN (${OracleSql.uncoalesced("SELECT e.nid FROM $it e")})" },
            oracleLength(request, OracleSql.embedding("c", key)),
        )
        val members = Query("$selected, g.pos", Placed(ddl.nodes, "c", OracleSql.placedIds(ids), "g", placedOn(Store.ORACLE, idKey)), has)
        val other = if (after) "o.pos > s.pos" else "o.nid <> s.nid"
        val metric = OracleSql.metric(request.similarity)
        val pairs = if (embeddings == null) {
            "SELECT s.nid AS seed, o.nid, o.id, o.pos, ${OracleSql.score(request.similarity, "VECTOR_DISTANCE(o.embedding, s.embedding, $metric)")} AS score\n" +
                "FROM $MEMBERS s\nJOIN $MEMBERS o ON $other AND VECTOR_DIMENSION_COUNT(o.embedding) = VECTOR_DIMENSION_COUNT(s.embedding)"
        } else {
            "SELECT s.nid AS seed, o.nid, o.id, o.pos, ${OracleSql.score(request.similarity, "k.distance")} AS score\n" +
                "FROM $MEMBERS s\nCROSS APPLY (\n" +
                "    SELECT e.nid, VECTOR_DISTANCE(e.embedding, s.embedding, $metric) AS distance FROM $embeddings e\n" +
                "    ORDER BY distance FETCH APPROX FIRST ${scope.bound(amongK(request))} ROWS ONLY) k\n" +
                "JOIN $MEMBERS o ON o.nid = k.nid AND $other"
        }
        val ranked = Query("p.seed, p.nid, p.id, p.pos, p.score, ROW_NUMBER() OVER (PARTITION BY p.seed ORDER BY p.score DESC, p.pos, p.nid) AS place", Derived(pairs, "p"))
        val near = Query(
            OracleSql.arrayOf(OracleSql.jsonObject(listOf("id" to "r.id", "score" to "r.score")), "r.score DESC, r.pos, r.nid"),
            Table(RANKED, "r"),
            listOfNotNull("r.seed = s.nid", "r.place <= ${scope.bound(request.topK)}", among.threshold?.let { "r.score >= ${scope.bound(it)}" }),
        )
        val result = OracleSql.jsonObject(listOf("seed" to "s.id", "near" to "COALESCE(${near.nested()}, ${OracleSql.EMPTY_ARRAY})"))
        return "WITH $MEMBERS AS ${members.nested()},\n$RANKED AS ${ranked.nested()}\n" + Query("$result AS result", Table(MEMBERS, "s")).text()
    }

    // ----- SQLite: drivine4j's SqliteSelect nearestTo and nearestAmong -----

    /**
     * SQLite has no vector index: every node of the root's labels, and the partition's, that holds an embedding of the vector's
     * size is measured by the library's [org.drivine.cypher.store.SqliteFunctions.SIMILARITY], so the answer is exact.
     */
    private fun sqliteTo(ddl: SqliteDdl, request: VectorRequest, size: Int, vector: String, k: String): String {
        val key = stored(request.property)
        val has = listOf(SqliteSql.hasLabels("c", request.labels + listOfNotNull(request.partitionLabel)), SqliteSql.holdsEmbedding("c", key, size)) +
            listOfNotNull(sqliteLength(request, "c", key))
        return Query(
            "$ROW, ${SqliteSql.score("c", key, request.similarity, vector)} AS score",
            Table(ddl.nodes, "c"), has, listOf("ORDER BY score DESC, c.nid", "LIMIT $k"),
        ).text()
    }

    /**
     * Only the roots are measured, so the answer is exact; a pair whose embeddings differ in size has no score, and is left
     * out. SQLite has no `LATERAL`, so every pair is scored once, numbered within its root best first, a tie of two scores by place
     * among the ids, and a root keeps the first `topK`.
     */
    private fun sqliteAmong(ddl: SqliteDdl, request: VectorRequest, among: VectorAmong, scope: SearchParameters): String {
        val key = stored(request.property)
        val idKey = stored(among.idProperty)
        val id = IdSql.keyText(Store.SQLITE, "c", idKey)
        val after = among.neighbours == AmongNeighbours.After
        val ids = scope.bound(among.ids)
        val has = listOf(
            SqliteSql.hasLabels("c", request.labels), "${SqliteSql.type("c", key)} = 'array'",
            IdSql.hasIdIn(Store.SQLITE, "c", idKey, ids, ddl.nodes, request.labels),
        ) + listOfNotNull(sqliteLength(request, "c", key))
        val members = Query("c.nid, $id AS id, c.props, g.pos", Placed(ddl.nodes, "c", SqliteSql.placedIds(ids), "g", placedOn(Store.SQLITE, idKey)), has)
        val scored = Query(
            "s.nid AS seed, o.nid, o.id, o.pos, ${SqliteSql.score("o", key, request.similarity, SqliteSql.value("s", key))} AS score",
            Paired(MEMBERS, "s", "o", if (after) "o.pos > s.pos" else "o.nid <> s.nid"),
        )
        val ranked = Query(
            "p.seed, p.nid, p.id, p.pos, p.score, ROW_NUMBER() OVER (PARTITION BY p.seed ORDER BY p.score DESC, p.pos, p.nid) AS place",
            Derived(scored.text(), "p"), listOf("p.score IS NOT NULL"),
        )
        val near = Query(
            SqliteSql.arrayOf(SqliteSql.plainObject(listOf("id" to "r.id", "score" to "r.score")), "r.score DESC, r.pos, r.nid"),
            Table(RANKED, "r"),
            listOfNotNull("r.seed = s.nid", "r.place <= ${scope.bound(request.topK)}", among.threshold?.let { "r.score >= ${scope.bound(it)}" }),
        )
        val result = SqliteSql.plainObject(listOf("seed" to "s.id", "near" to "json(${near.nested()})"))
        return "${SqliteDdl.JSON_ROWS}\nWITH $MEMBERS AS ${members.nested()},\n$RANKED AS ${ranked.nested()}\n" + Query("$result AS result", Table(MEMBERS, "s")).text()
    }

    // ----- The graph engines: drivine4j's grammars' vectorSearchHead, and its NearestAmongBuilder -----

    /**
     * A nearest search's head on a graph engine. With a partition, the index's name (FalkorDB's label) is bound, so one
     * statement serves every partition; without one it is written into the statement as a Cypher string, escaped (#217).
     */
    private fun graphNearest(store: SearchStore, request: VectorRequest, scope: SearchParameters): VectorPlan {
        val label = indexLabel(request)
        val named = if (request.partitionLabel != null) scope.bound(if (store == SearchStore.FALKORDB) label else indexName(request, label)) else null
        val k = request.searchK ?: request.topK
        val head = head(store, request, label, named, scope.bound(k), vector = null, scope = scope, rescored = request.searchK != null, alias = request.alias, scoreAlias = request.scoreAlias)
        return VectorPlan(
            sql = null, cypher = head.text, parameters = scope.bindings(), vector = head.vector,
            labelCheck = labelCheck(request.alias, request.labels, label), indexK = k,
        )
    }

    /**
     * A search among ids with `searchK` on Neo4j and Memgraph (FalkorDB's is [falkorDbAmong]): each id unwound to its root,
     * with its place among the ids, and the index asked for each root's `searchK` nearest by the root's own embedding, so the engine answers every root in one
     * statement. What the index yields is narrowed by each hit's place to the roots, less the root itself (for each pair
     * once, to the roots after it), and what scores under the threshold, and the best `topK` of what is left are kept, a tie
     * of two scores by place. On Neo4j the score is measured afresh, as the narrowing trims what the index found.
     */
    private fun graphAmong(store: SearchStore, request: VectorRequest, among: VectorAmong, scope: SearchParameters): VectorPlan {
        if (store == SearchStore.FALKORDB) return falkorDbAmong(store, request, among, scope)
        val label = request.labels.first()
        val id = CypherNames.identifier(among.idProperty)
        val embedding = "seed.${CypherNames.identifier(request.property)}"
        val ids = scope.bound(among.ids)
        // Each id's place among the ids, bound as a map of its text, so each hit's place is read at once: what is no root has
        // none, and a tie of two scores goes by place (#412). It is read through a variable, bound once: Memgraph copies a
        // parameter each time a row reads it, and a map of 2000 ids read so 80 000 times took 36 s, through a variable 31 ms.
        // A hit whose id is no text, as of a node of the label stored under another id type, is no root, and has no place:
        // a map read by a key that is no text is an error on every graph engine. FalkorDB takes no such map: see falkorDbAmong.
        val places = scope.bound(among.ids.withIndex().associate { (place, text) -> text to place })
        val after = among.neighbours == AmongNeighbours.After
        val carried = "$PLACES, $POS, $SEED_ID"
        val head = head(store, request, label, named = null, k = scope.bound(amongK(request)), vector = embedding, scope = scope, rescored = true, alias = NODE, scoreAlias = SCORE, carried = carried)
        // For each pair once, each root keeps what the index finds of the ids after it; for every neighbour, of every other id.
        val others = if (after) listOf("m.place > $POS") else listOf("m.place IS NOT NULL", "m.place <> $POS")
        val kept = others + listOfNotNull(among.threshold?.let { "m.score >= ${scope.bound(it)}" })
        val labelled = labelCheck(NODE, request.labels, label)?.let { "\nWHERE $it" } ?: ""
        val topK = scope.bound(request.topK)
        val cypher = """
            |WITH $places AS $PLACES
            |UNWIND range(0, size($ids) - 1) AS $POS
            |WITH $PLACES, $POS, $ids[$POS] AS $SEED_ID
            |MATCH ($SEED:${request.labels.joinToString(":") { CypherNames.identifier(it) }})
            |WHERE $SEED.$id = $SEED_ID AND $embedding IS NOT NULL
            |${head.text}$labelled
            |WITH $carried, $NODE, $SCORE, CASE WHEN toString($NODE.$id) = $NODE.$id THEN $PLACES[$NODE.$id] END AS _place
            |ORDER BY $SCORE DESC, _place
            |WITH $SEED_ID, $POS, collect({id: $NODE.$id, score: $SCORE, place: _place}) AS _found
            |RETURN {seed: $SEED_ID, near: [m IN _found WHERE ${kept.joinToString(" AND ")} | {id: m.id, score: m.score}][0..$topK]} AS row
        """.trimMargin()
        return VectorPlan(
            sql = null, cypher = cypher, parameters = scope.bindings(), vector = null, labelCheck = null, indexK = amongK(request),
            warnings = amongWarnings(store, request, among),
        )
    }

    /**
     * A search among ids with `searchK` on FalkorDB: [graphAmong] less the places. A FalkorDB client writes each parameter
     * into the statement as text, and each library refuses some map keys (TypeScript's any that is no plain name), so a map
     * keyed by the ids can't be bound (liberation-data/drivine-cypher-spec#416). What the index yields is narrowed by id to
     * the roots, less the root itself (for each pair once, to the ids after it), and ordered by score alone: a tie of two
     * scores goes by the index's own order, which this path, approximate and warned of, leaves as it is.
     */
    private fun falkorDbAmong(store: SearchStore, request: VectorRequest, among: VectorAmong, scope: SearchParameters): VectorPlan {
        val label = request.labels.first()
        val id = CypherNames.identifier(among.idProperty)
        val embedding = "seed.${CypherNames.identifier(request.property)}"
        val ids = scope.bound(among.ids)
        // For each pair once, each root is unwound with its place among the ids, and keeps what the index finds of the ids
        // after it: the later ids alone, so the root itself among them, and no two ids are compared.
        val after = among.neighbours == AmongNeighbours.After
        val carried = if (after) "$POS, $SEED_ID" else SEED_ID
        val head = head(store, request, label, named = null, k = scope.bound(amongK(request)), vector = embedding, scope = scope, rescored = true, alias = NODE, scoreAlias = SCORE, carried = carried)
        val others = if (after) listOf("m.id IN $ids[($POS + 1)..]") else listOf("m.id IN $ids", "m.id <> $SEED_ID")
        val kept = others + listOfNotNull(among.threshold?.let { "m.score >= ${scope.bound(it)}" })
        val labelled = labelCheck(NODE, request.labels, label)?.let { "\nWHERE $it" } ?: ""
        val topK = scope.bound(request.topK)
        val unwound = if (after) "UNWIND range(0, size($ids) - 1) AS $POS\nWITH $POS, $ids[$POS] AS $SEED_ID" else "UNWIND $ids AS $SEED_ID"
        val cypher = """
            |$unwound
            |MATCH ($SEED:${request.labels.joinToString(":") { CypherNames.identifier(it) }})
            |WHERE $SEED.$id = $SEED_ID AND $embedding IS NOT NULL
            |${head.text}$labelled
            |WITH $carried, $NODE, $SCORE
            |ORDER BY $SCORE DESC
            |WITH $carried, collect({id: $NODE.$id, score: $SCORE}) AS _found
            |RETURN {seed: $SEED_ID, near: [m IN _found WHERE ${kept.joinToString(" AND ")}][0..$topK]} AS row
        """.trimMargin()
        return VectorPlan(
            sql = null, cypher = cypher, parameters = scope.bindings(), vector = null, labelCheck = null, indexK = amongK(request),
            warnings = amongWarnings(store, request, among),
        )
    }

    /**
     * A search among ids without `searchK` on a graph engine: every pair of the roots measured by the engine's own function of
     * two embeddings, through no index, so the answer is exact. Each root, unwound with its place among the ids, measures in a
     * subquery of its own the roots at every other place ([AmongNeighbours.All]), or only those whose places come after its own
     * ([AmongNeighbours.After]), and keeps its best `topK`: no two ids are compared, and a statement holds a root's `topK` at a time, never every pair. A pair whose embeddings
     * differ in size has no score, and an embedding of no length none by cosine, as on the SQL stores; FalkorDB can't tell
     * either of a stored vector, so it refuses a pair of two sizes, as its index does, and measures one of no length.
     */
    private fun graphAmongEveryPair(store: SearchStore, request: VectorRequest, among: VectorAmong, scope: SearchParameters): VectorPlan {
        val labels = request.labels.joinToString(":") { CypherNames.identifier(it) }
        val id = CypherNames.identifier(among.idProperty)
        val property = CypherNames.identifier(request.property)
        val seed = "$SEED.$property"
        val node = "$NODE.$property"
        val ids = scope.bound(among.ids)
        val seedHas = listOfNotNull("$SEED.$id = $SEED_ID", "$seed IS NOT NULL", hasLength(store, request, seed))
        val nodeHas = listOfNotNull("$NODE.$id = $NEXT_ID", "$node IS NOT NULL", hasLength(store, request, node), sameSize(store, seed, node))
        val threshold = among.threshold?.let { "\n  WHERE $SCORE >= ${scope.bound(it)}\n  WITH $NODE, $NEXT, $SCORE" } ?: ""
        val topK = scope.bound(request.topK)
        val open = if (store == SearchStore.NEO4J) "CALL ($SEED, $POS) {" else "CALL {\n  WITH $SEED, $POS"
        val others = when (among.neighbours) {
            AmongNeighbours.After -> "range($POS + 1, size($ids) - 1)"
            AmongNeighbours.All -> "[_at IN range(0, size($ids) - 1) WHERE _at <> $POS]"
        }
        val cypher = """
            |UNWIND range(0, size($ids) - 1) AS $POS
            |WITH $POS, $ids[$POS] AS $SEED_ID
            |MATCH ($SEED:$labels)
            |WHERE ${seedHas.joinToString(" AND ")}
            |$open
            |  UNWIND $others AS $NEXT
            |  WITH $SEED, $NEXT, $ids[$NEXT] AS $NEXT_ID
            |  MATCH ($NODE:$labels)
            |  WHERE ${nodeHas.joinToString(" AND ")}
            |  WITH $NODE, $NEXT, ${pairScore(store, request.similarity, seed, node)} AS $SCORE$threshold
            |  ORDER BY $SCORE DESC, $NEXT
            |  LIMIT $topK
            |  RETURN collect({id: $NODE.$id, score: $SCORE}) AS near
            |}
            |RETURN {seed: $SEED_ID, near: near} AS row
        """.trimMargin()
        return VectorPlan(
            sql = null, cypher = cypher, parameters = scope.bindings(), vector = null, labelCheck = null, indexK = 0,
            timeoutMillis = if (store == SearchStore.FALKORDB) FALKORDB_AMONG_TIMEOUT_MILLIS else null,
        )
    }

    /**
     * The score of the embeddings [a] and [b] on the graph engine [store], measured through no index, as its index's search
     * scores them: for cosine `(1 + cos) / 2`, for Euclidean `1 / (1 + d²)`. Memgraph has no function of two vectors by
     * distance, so its Euclidean one is summed in Cypher.
     */
    private fun pairScore(store: SearchStore, similarity: SimilarityFunction, a: String, b: String): String = when (store) {
        SearchStore.NEO4J -> when (similarity) {
            SimilarityFunction.COSINE -> "vector.similarity.cosine($a, $b)"
            SimilarityFunction.EUCLIDEAN -> "vector.similarity.euclidean($a, $b)"
        }
        SearchStore.MEMGRAPH -> when (similarity) {
            SimilarityFunction.COSINE -> "(1.0 + vector_search.cosine_similarity($a, $b)) / 2.0"
            SimilarityFunction.EUCLIDEAN -> "1.0 / (1.0 + reduce(_d = 0.0, _i IN range(0, size($a) - 1) | _d + ($a[_i] - $b[_i]) ^ 2))"
        }
        else -> when (similarity) {
            SimilarityFunction.COSINE -> "1.0 - vec.cosineDistance($a, $b) / 2.0"
            SimilarityFunction.EUCLIDEAN -> "1.0 / (1.0 + vec.euclideanDistance($a, $b) ^ 2)"
        }
    }

    /**
     * That the embedding [embedding] has a length, on a cosine search on [store]; null on a Euclidean one, and on FalkorDB,
     * whose stored vector can't be read item by item. Neo4j's and Memgraph's cosine refuse an embedding all of zeros.
     */
    private fun hasLength(store: SearchStore, request: VectorRequest, embedding: String): String? =
        if (request.similarity != SimilarityFunction.COSINE || store == SearchStore.FALKORDB) null else "any(_x IN $embedding WHERE _x <> 0)"

    /** That [a] and [b] are of one size, which each engine's function of two embeddings refuses otherwise; null on FalkorDB, which can't tell. */
    private fun sameSize(store: SearchStore, a: String, b: String): String? =
        if (store == SearchStore.FALKORDB) null else "size($b) = size($a)"

    /** A head's text, and the parameter it reads the query vector from, where it reads a bound one. */
    private class Head(val text: String, val vector: String?)

    /**
     * The index's `CALL` on [store] and each hit as [alias], its score as [scoreAlias] (after [carried], where the head
     * carries a variable from before it). [named] is the bound index name, or label on FalkorDB, or null to write it in;
     * [vector] the expression the vector is read from, or null to read one the library binds, reserved here. Each engine's
     * own measure is made the score the SQL stores give: Neo4j's score is it already, and with [rescored] is measured afresh
     * of the stored embedding; Memgraph's and FalkorDB's distance is rescaled to it.
     */
    private fun head(
        store: SearchStore, request: VectorRequest, label: String, named: String?, k: String, vector: String?, scope: SearchParameters,
        rescored: Boolean, alias: String, scoreAlias: String, carried: String? = null,
    ): Head {
        // drivine4j's VectorQuerySpec.indexNameArgument and labelArgument: a Cypher string, its quotes and backslashes escaped
        // whatever the name holds (#217), so a plain name is written as drivine4j writes it.
        val nameArgument = named ?: CypherNames.string(indexName(request, label))
        val labelArgument = named ?: CypherNames.string(label)
        val reserved = if (vector == null) scope.reserved() else null
        val source = vector ?: "\$$reserved"
        val prefix = carried?.let { "$it, " } ?: ""
        val text = when (store) {
            SearchStore.NEO4J -> {
                val call = "CALL db.index.vector.queryNodes($nameArgument, $k, $source)"
                if (!rescored) {
                    "$call\nYIELD node, score\nWITH ${prefix}node AS $alias, score AS $scoreAlias"
                } else {
                    val similarity = when (request.similarity) {
                        SimilarityFunction.COSINE -> "vector.similarity.cosine"
                        SimilarityFunction.EUCLIDEAN -> "vector.similarity.euclidean"
                    }
                    "$call\nYIELD node\nWITH ${prefix}node AS $alias, $similarity(node.${CypherNames.identifier(request.property)}, $source) AS $scoreAlias"
                }
            }
            // From the distance, not the similarity, which Memgraph gives as |1 - distance|: a cosine of -0.6 would score as 0.6.
            SearchStore.MEMGRAPH -> {
                val score = when (request.similarity) {
                    SimilarityFunction.COSINE -> "1.0 - distance / 2.0"
                    SimilarityFunction.EUCLIDEAN -> "1.0 / (1.0 + distance)"
                }
                "CALL vector_search.search($nameArgument, $k, $source)\nYIELD node, distance\nWITH ${prefix}node AS $alias, $score AS $scoreAlias"
            }
            // FalkorDB's distance is 1 - cos for cosine, and d for Euclidean. A stored embedding is its vector type already.
            else -> {
                val score = when (request.similarity) {
                    SimilarityFunction.COSINE -> "1.0 - score / 2.0"
                    SimilarityFunction.EUCLIDEAN -> "1.0 / (1.0 + score * score)"
                }
                "CALL db.idx.vector.queryNodes($labelArgument, ${CypherNames.string(request.property)}, $k, ${vector ?: "vecf32($source)"})\n" +
                    "YIELD node, score\nWITH ${prefix}node AS $alias, $score AS $scoreAlias"
            }
        }
        return Head(text, reserved?.let { "$it" })
    }

    /** The index's name: the one the library found, or `<label>_<property>_vector`, as the schema names it. A blank one is none. */
    private fun indexName(request: VectorRequest, label: String): String =
        request.index.name?.takeUnless { it.isBlank() } ?: "${label}_${request.property}_vector"

    /** drivine4j's `indexedNodeLabelCheck`: that [alias] has every one of [labels], or null where they are the index's [label] alone. */
    private fun labelCheck(alias: String, labels: List<String>, label: String): String? =
        if (labels.isEmpty() || labels == listOf(label)) null else alias + labels.joinToString("") { ":${CypherNames.quoted(it)}" }

    /**
     * What a nearest search's candidates select of each node: its row, beside its score, so the library and a compiled
     * statement read the node from them, and join the node table no second time. On Oracle 23.26 a second join of the node
     * table on `nid`, the first read through an inline view, is eliminated with the filter on it where an index over a
     * `JSON_VALUE` of its properties is made: a bound value crashed the server process (ORA-07445 [kkocfbMarkBindFroCB]),
     * and a literal one was dropped, every candidate kept (liberation-data/drivine-cypher-spec#160).
     */
    private const val ROW = "c.nid, c.labels, c.props, c.stamp"

    private const val MEMBERS = "members"
    private const val RANKED = "ranked"
    private const val SEED = "seed"
    private const val SEED_ID = "_seedId"
    private const val POS = "_pos"
    private const val PLACES = "_places"
    private const val NEXT = "_next"
    private const val NEXT_ID = "_nextId"
    private const val NODE = "n"
    private const val SCORE = "_score"
}
