package org.drivine.cypher.api

import org.drivine.DrivineInternalApi
import org.drivine.cypher.search.Derived
import org.drivine.cypher.search.GraphTable
import org.drivine.cypher.search.OnceReached
import org.drivine.cypher.search.Paired
import org.drivine.cypher.search.Query
import org.drivine.cypher.search.SearchParameters
import org.drivine.cypher.search.Table
import org.drivine.cypher.store.OracleSql
import org.drivine.cypher.store.PostgresSql
import org.drivine.cypher.store.SqliteSql
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
 *   size, and measures every node otherwise; a search among ids goes through any index made.
 * @param name on a graph engine, the index's name where it isn't `<label>_<property>_vector`; a blank one is none.
 */
@DrivineInternalApi
data class VectorIndex(val dimensions: Int? = null, val name: String? = null)

/**
 * A search among ids: for each root among [ids] that holds the embedding, its nearest among the others.
 *
 * @param ids the roots' ids, as the text each is found by; a repeat is dropped, as drivine4j's `loadNearestAmong` drops it.
 * @param idProperty the property the root's id is stored under.
 * @param threshold the least score a neighbour is kept with, or null to keep any.
 */
@DrivineInternalApi
data class VectorAmong(val ids: List<String>, val idProperty: String, val threshold: Double? = null)

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
 *   found exactly, as the index's own score doesn't order quite as the exact one does. Among ids, where it is null, the index
 *   is asked for `topK + 1` for each root, as drivine4j's `loadNearestAmong` asks: it finds the root itself first.
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
 * @param sql on a SQL store, a nearest search's ranked candidates, one `SELECT` of `nid` and `score`: the nearest
 *   [VectorRequest.searchK] (or `topK`) nodes, each scored as the graph engines score it, in `[0, 1]`, higher the nearer. The
 *   library joins them back to its node table by `nid` for its projection, and adds its threshold (`score >= threshold`), its
 *   predicates, the view's required relationships, `ORDER BY score DESC, nid` and, where `searchK` was given, its `LIMIT topK`.
 *   A search among ids is the whole statement: one row a root, `result`, the document `{seed, near}`, `near` its neighbours
 *   as `{id, score}`, best first.
 * @param cypher on a graph engine, a nearest search's head: the index's `CALL`, and each hit as [VectorRequest.alias] and its
 *   score as [VectorRequest.scoreAlias], to which the library adds [labelCheck], its threshold, filters, projection and
 *   `LIMIT`. A search among ids is the whole statement, one row a root, `row`, `{seed, near}`.
 * @param parameters the values the statement binds, each named `_cy_vec<n>`: all but the query vector.
 * @param vector the parameter the library binds the query vector as, a list of floats as it binds a list; null among ids,
 *   where each root's own embedding is the vector.
 * @param labelCheck on a graph engine, whether a hit has every one of the root's labels, as a predicate on the node: the index
 *   is on one label, so a hit with that label alone is no root. Null where the root's one label is the index's. The library
 *   tests it before the threshold and its filters.
 * @param indexK how many the index is asked for: [VectorRequest.searchK], or where that is null `topK` on a nearest search and
 *   `topK + 1` among ids.
 * @param warnings what the module warns of, for the library to log.
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
                if (among != null) graphAmong(store, request, among, scope) else graphNearest(store, request, scope)
            } else if (among != null) {
                val sql = when (store) {
                    SearchStore.POSTGRES -> postgresAmong(PostgresDdl(graph.name), request, among, scope)
                    SearchStore.ORACLE -> oracleAmong(OracleDdl(graph.name), request, among, scope)
                    else -> sqliteAmong(SqliteDdl(graph.name), request, among, scope)
                }
                VectorPlan(sql = sql, cypher = null, parameters = scope.bindings(), vector = null, labelCheck = null, indexK = amongK(request))
            } else {
                val size = request.size!!
                val vector = scope.reserved()
                val nearest = request.searchK ?: request.topK
                val through = request.index.dimensions == size
                val sql = when (store) {
                    SearchStore.POSTGRES -> {
                        val ddl = PostgresDdl(graph.name)
                        if (through) postgresThrough(ddl, request, size, vector, nearest, scope) else postgresTo(ddl, request, size, vector, nearest, scope)
                    }
                    SearchStore.ORACLE -> {
                        val ddl = OracleDdl(graph.name)
                        if (through) oracleThrough(ddl, request, vector, nearest, scope) else oracleTo(ddl, request, size, vector, nearest, scope)
                    }
                    else -> sqliteTo(SqliteDdl(graph.name), request, size, vector, nearest, scope)
                }
                VectorPlan(sql = sql, cypher = null, parameters = scope.bindings(), vector = vector, labelCheck = null, indexK = nearest)
            }
        } catch (e: IllegalArgumentException) {
            Refusal(e.message ?: e.toString())
        }
    }

    /**
     * The key a node's `props` holds [property] at, which the vector index is made over: the property's own name, as the
     * DDL's index (`PostgresDdl`, `OracleDdl`) reads it. The one place a search names it: store layout 2 (#161) holds a
     * property whose name begins with `$` under an escaped key, and the search switches to that key here, as the DDL does.
     */
    private fun stored(property: String): String = property

    /** The label the index a search goes through is on: its partition's, or the root's first. */
    private fun indexLabel(request: VectorRequest): String = request.partitionLabel ?: request.labels.first()

    /**
     * How many a search among ids asks the index for, for each root: `searchK`, or one more than it keeps, as drivine4j's
     * `GraphObjectManager.loadNearestAmong` asks, since the index finds the root itself first.
     */
    private fun amongK(request: VectorRequest): Int = request.searchK ?: (request.topK + 1)

    // ----- Postgres: drivine4j's PostgresSelect nearestTo, nearestThrough and nearestAmong -----

    /**
     * Every node of the root's labels, and the partition's, that holds an embedding of the vector's size is measured, so the
     * answer is exact: pgvector refuses to measure two sizes.
     */
    private fun postgresTo(ddl: PostgresDdl, request: VectorRequest, size: Int, vector: String, nearest: Int, scope: SearchParameters): String {
        val key = stored(request.property)
        val distance = "${PostgresSql.embedding("c", key)} ${PostgresSql.distance(request.similarity)} CAST(\$$vector AS vector)"
        val has = listOf(PostgresSql.hasLabels("c", request.labels + listOfNotNull(request.partitionLabel)), PostgresSql.holdsEmbedding("c", key, size))
        return Query(
            "c.nid, ${PostgresSql.score(request.similarity, distance)} AS score",
            Table(ddl.nodes, "c"), has, listOf("ORDER BY score DESC, c.nid", "LIMIT ${scope.bound(nearest)}"),
        ).text()
    }

    /**
     * Through the HNSW index over the embeddings of the index's label of its size: the index gives its nearest,
     * approximately, and they are then tested for the root's own labels. The search reads the embedding as the index is made
     * over it, and names what the index holds, so the index answers it.
     */
    private fun postgresThrough(ddl: PostgresDdl, request: VectorRequest, dimensions: Int, vector: String, nearest: Int, scope: SearchParameters): String {
        val key = stored(request.property)
        val distance = "${PostgresSql.embedding("s", key, dimensions)} ${PostgresSql.distance(request.similarity)} CAST(\$$vector AS vector($dimensions))"
        val indexed = Query(
            "s.nid, $distance AS distance", Table(ddl.nodes, "s"),
            listOf(PostgresSql.hasLabel("s", indexLabel(request)), PostgresSql.holdsEmbedding("s", key, dimensions)),
            listOf("ORDER BY distance", "LIMIT ${scope.bound(nearest)}"),
        )
        // drivine4j's text, trimmed of its indent as drivine4j trims it: its lines keep what of it the nested query's don't have.
        return """
            SELECT c.nid, ${PostgresSql.score(request.similarity, "k.distance")} AS score
            FROM ${indexed.nested()} k
            JOIN ${ddl.nodes} c ON c.nid = k.nid
            WHERE ${PostgresSql.hasLabels("c", request.labels)}
        """.trimIndent()
    }

    /**
     * Without an index made, each root measures only the other roots whose embeddings are of its size: exact. With one, each
     * root asks the index over the embeddings of the root's first label for its `searchK` nearest, and keeps those among the
     * roots: approximate, as the index is.
     */
    private fun postgresAmong(ddl: PostgresDdl, request: VectorRequest, among: VectorAmong, scope: SearchParameters): String {
        val key = stored(request.property)
        val id = PostgresSql.property("c", stored(among.idProperty))
        val dimensions = request.index.dimensions
        val embedded = dimensions?.let { PostgresSql.holdsEmbedding("c", key, it) } ?: "jsonb_typeof(${PostgresSql.value("c", key)}) = 'array'"
        val members = Query(
            "c.nid, $id AS id, c.props", Table(ddl.nodes, "c"),
            listOf(PostgresSql.hasLabels("c", request.labels), embedded, "$id IN (${PostgresSql.textValues(scope.bound(among.ids))})"),
        )
        val nearest = if (dimensions == null) {
            val distance = "${PostgresSql.embedding("o", key)} ${PostgresSql.distance(request.similarity)} ${PostgresSql.embedding("s", key)}"
            Query(
                "o.nid, o.id, ${PostgresSql.score(request.similarity, distance)} AS score", Table(MEMBERS, "o"),
                listOf("o.nid <> s.nid", "jsonb_array_length(${PostgresSql.value("o", key)}) = jsonb_array_length(${PostgresSql.value("s", key)})"),
                listOf("ORDER BY score DESC, o.nid", "LIMIT ${scope.bound(request.topK)}"),
            )
        } else {
            val distance = "${PostgresSql.embedding("x", key, dimensions)} ${PostgresSql.distance(request.similarity)} ${PostgresSql.embedding("s", key, dimensions)}"
            val indexed = Query(
                "x.nid, $distance AS distance", Table(ddl.nodes, "x"),
                listOf(PostgresSql.hasLabel("x", indexLabel(request)), PostgresSql.holdsEmbedding("x", key, dimensions)),
                listOf("ORDER BY distance", "LIMIT ${scope.bound(amongK(request))}"),
            )
            Query(
                "o.nid, o.id, ${PostgresSql.score(request.similarity, "k.distance")} AS score", OnceReached(indexed.text(), "k", MEMBERS, "o"),
                listOf("o.nid <> s.nid"), listOf("ORDER BY score DESC, o.nid", "LIMIT ${scope.bound(request.topK)}"),
            )
        }
        val near = Query(
            PostgresSql.arrayOf(PostgresSql.jsonObject(listOf("id" to "r.id", "score" to "r.score")), "r.score DESC, r.nid"),
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
    private fun oracleTo(ddl: OracleDdl, request: VectorRequest, size: Int, vector: String, nearest: Int, scope: SearchParameters): String {
        val key = stored(request.property)
        val score = OracleSql.score(request.similarity, "VECTOR_DISTANCE(${OracleSql.embedding("c", key)}, TO_VECTOR(\$$vector), ${OracleSql.metric(request.similarity)})")
        val has = listOf(
            OracleSql.hasLabels("c", request.labels + listOfNotNull(request.partitionLabel), scanned = true),
            OracleSql.hasProperty("c", key),
            OracleSql.holdsEmbedding("c", key, size),
        ).joinToString(" AND ")
        return Query(
            "c.nid, $score AS score", GraphTable(ddl.name, OracleSql.node("c", has), OracleSql.NODE_COLUMNS, "c"),
            tail = listOf("ORDER BY score DESC, c.nid", "FETCH FIRST ${scope.bound(nearest)} ROWS ONLY"),
        ).text()
    }

    /**
     * Through the vector index over the side table of the index's label's embeddings ([OracleDdl.vectors]), which holds those
     * of its size: the index gives its nearest, approximately, and they are then tested for the root's own labels.
     */
    private fun oracleThrough(ddl: OracleDdl, request: VectorRequest, vector: String, nearest: Int, scope: SearchParameters): String {
        val embeddings = ddl.vectors(indexLabel(request), request.property)
        val distance = "VECTOR_DISTANCE(s.embedding, TO_VECTOR(\$$vector), ${OracleSql.metric(request.similarity)})"
        return """
            SELECT c.nid, ${OracleSql.score(request.similarity, "k.distance")} AS score
            FROM (SELECT s.nid, $distance AS distance FROM $embeddings s ORDER BY distance FETCH APPROX FIRST ${scope.bound(nearest)} ROWS ONLY) k
            JOIN ${ddl.nodes} c ON c.nid = k.nid
            WHERE ${OracleSql.hasLabels("c", request.labels)}
        """.trimIndent()
    }

    /**
     * Without an index made, every pair of roots whose embeddings are of one size is measured: exact. With one, each root asks
     * the index over the side table for its `searchK` nearest, and keeps those among the roots: approximate. Only the roots
     * the side table holds are searched from, as it holds only embeddings of the index's size. Either way each root's
     * neighbours are numbered best first, and the first `topK` kept.
     */
    private fun oracleAmong(ddl: OracleDdl, request: VectorRequest, among: VectorAmong, scope: SearchParameters): String {
        val key = stored(request.property)
        val id = OracleSql.property("c", stored(among.idProperty))
        val embeddings = request.index.dimensions?.let { ddl.vectors(request.labels.first(), request.property) }
        val members = Query(
            "c.nid, $id AS id, ${OracleSql.embedding("c", key)} AS embedding", Table(ddl.nodes, "c"),
            listOfNotNull(
                OracleSql.hasLabels("c", request.labels), OracleSql.hasProperty("c", key), "$id IN (${OracleSql.textValues(scope.bound(among.ids))})",
                embeddings?.let { "c.nid IN (SELECT e.nid FROM $it e)" },
            ),
        )
        val metric = OracleSql.metric(request.similarity)
        val pairs = if (embeddings == null) {
            "SELECT s.nid AS seed, o.nid, o.id, ${OracleSql.score(request.similarity, "VECTOR_DISTANCE(o.embedding, s.embedding, $metric)")} AS score\n" +
                "FROM $MEMBERS s\nJOIN $MEMBERS o ON o.nid <> s.nid AND VECTOR_DIMENSION_COUNT(o.embedding) = VECTOR_DIMENSION_COUNT(s.embedding)"
        } else {
            "SELECT s.nid AS seed, o.nid, o.id, ${OracleSql.score(request.similarity, "k.distance")} AS score\n" +
                "FROM $MEMBERS s\nCROSS APPLY (\n" +
                "    SELECT e.nid, VECTOR_DISTANCE(e.embedding, s.embedding, $metric) AS distance FROM $embeddings e\n" +
                "    ORDER BY distance FETCH APPROX FIRST ${scope.bound(amongK(request))} ROWS ONLY) k\n" +
                "JOIN $MEMBERS o ON o.nid = k.nid AND o.nid <> s.nid"
        }
        val ranked = Query("p.seed, p.nid, p.id, p.score, ROW_NUMBER() OVER (PARTITION BY p.seed ORDER BY p.score DESC, p.nid) AS place", Derived(pairs, "p"))
        val near = Query(
            OracleSql.arrayOf(OracleSql.jsonObject(listOf("id" to "r.id", "score" to "r.score")), "r.score DESC, r.nid"),
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
    private fun sqliteTo(ddl: SqliteDdl, request: VectorRequest, size: Int, vector: String, nearest: Int, scope: SearchParameters): String {
        val key = stored(request.property)
        val has = listOf(SqliteSql.hasLabels("c", request.labels + listOfNotNull(request.partitionLabel)), SqliteSql.holdsEmbedding("c", key, size))
        return Query(
            "c.nid, ${SqliteSql.score("c", key, request.similarity, "\$$vector")} AS score",
            Table(ddl.nodes, "c"), has, listOf("ORDER BY score DESC, c.nid", "LIMIT ${scope.bound(nearest)}"),
        ).text()
    }

    /**
     * Only the roots are measured, so the answer is exact; a pair whose embeddings differ in size has no score, and is left
     * out. SQLite has no `LATERAL`, so every pair is scored once, numbered within its root best first, and a root keeps the
     * first `topK`.
     */
    private fun sqliteAmong(ddl: SqliteDdl, request: VectorRequest, among: VectorAmong, scope: SearchParameters): String {
        val key = stored(request.property)
        val id = SqliteSql.property("c", stored(among.idProperty))
        val members = Query(
            "c.nid, $id AS id, c.props", Table(ddl.nodes, "c"),
            listOf(SqliteSql.hasLabels("c", request.labels), "${SqliteSql.type("c", key)} = 'array'", "$id IN (${SqliteSql.textValues(scope.bound(among.ids))})"),
        )
        val scored = Query(
            "s.nid AS seed, o.nid, o.id, ${SqliteSql.score("o", key, request.similarity, SqliteSql.value("s", key))} AS score",
            Paired(MEMBERS, "s", "o", "o.nid <> s.nid"),
        )
        val ranked = Query(
            "p.seed, p.nid, p.id, p.score, ROW_NUMBER() OVER (PARTITION BY p.seed ORDER BY p.score DESC, p.nid) AS place",
            Derived(scored.text(), "p"), listOf("p.score IS NOT NULL"),
        )
        val near = Query(
            SqliteSql.arrayOf(SqliteSql.plainObject(listOf("id" to "r.id", "score" to "r.score")), "r.score DESC, r.nid"),
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
     * A search among ids on a graph engine: each id unwound to its root, and the index asked for each root's nearest by the
     * root's own embedding, so the engine answers every root in one statement. What the index yields is narrowed to the
     * roots, less the root itself and what scores under the threshold, and the best `topK` of what is left are kept. On Neo4j
     * the score is measured afresh, as the narrowing trims what the index found.
     */
    private fun graphAmong(store: SearchStore, request: VectorRequest, among: VectorAmong, scope: SearchParameters): VectorPlan {
        val label = request.labels.first()
        val id = CypherNames.identifier(among.idProperty)
        val embedding = "seed.${CypherNames.identifier(request.property)}"
        val ids = scope.bound(among.ids)
        val head = head(store, request, label, named = null, k = scope.bound(amongK(request)), vector = embedding, scope = scope, rescored = true, alias = NODE, scoreAlias = SCORE, carried = SEED_ID)
        val kept = listOfNotNull("m.id IN $ids", "m.id <> $SEED_ID", among.threshold?.let { "m.score >= ${scope.bound(it)}" })
        val labelled = labelCheck(NODE, request.labels, label)?.let { "\nWHERE $it" } ?: ""
        val topK = scope.bound(request.topK)
        val cypher = """
            |UNWIND $ids AS $SEED_ID
            |MATCH ($SEED:${request.labels.joinToString(":") { CypherNames.identifier(it) }})
            |WHERE $SEED.$id = $SEED_ID AND $embedding IS NOT NULL
            |${head.text}$labelled
            |WITH $SEED_ID, $NODE, $SCORE
            |ORDER BY $SCORE DESC
            |WITH $SEED_ID, collect({id: $NODE.$id, score: $SCORE}) AS _found
            |RETURN {seed: $SEED_ID, near: [m IN _found WHERE ${kept.joinToString(" AND ")}][0..$topK]} AS row
        """.trimMargin()
        return VectorPlan(sql = null, cypher = cypher, parameters = scope.bindings(), vector = null, labelCheck = null, indexK = amongK(request))
    }

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

    private const val MEMBERS = "members"
    private const val RANKED = "ranked"
    private const val SEED = "seed"
    private const val SEED_ID = "_seedId"
    private const val NODE = "n"
    private const val SCORE = "_score"
}
