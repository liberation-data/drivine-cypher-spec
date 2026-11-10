package org.drivine.cypher.api

import org.drivine.DrivineInternalApi
import org.drivine.cypher.search.Bm25
import org.drivine.cypher.search.GraphTable
import org.drivine.cypher.search.Query
import org.drivine.cypher.search.SearchParameters
import org.drivine.cypher.search.Table
import org.drivine.cypher.search.TextQuery
import org.drivine.cypher.store.OracleSql
import org.drivine.cypher.store.PostgresSql
import org.drivine.cypher.store.SqliteFunctions
import org.drivine.cypher.store.SqliteSql
import org.drivine.cypher.store.ddl.CypherNames
import org.drivine.cypher.store.ddl.OracleDdl
import org.drivine.cypher.store.ddl.PostgresDdl
import org.drivine.cypher.store.ddl.SqliteDdl
import org.drivine.schema.SchemaItemKind

/** A store a full-text search runs on: the SQL stores, and the graph engines, whose full-text index answers it. */
@DrivineInternalApi
enum class SearchStore(val id: String) {
    POSTGRES("postgres"),
    ORACLE("oracle"),
    SQLITE("sqlite"),
    NEO4J("neo4j"),
    MEMGRAPH("memgraph"),
    FALKORDB("falkordb");

    /** Whether the store is a graph engine, whose search is a Cypher head and not SQL. */
    val graphEngine: Boolean get() = this == NEO4J || this == MEMGRAPH || this == FALKORDB

    companion object {
        /** The store named [id], as a JSON request names it. */
        fun of(id: String): SearchStore? = entries.firstOrNull { it.id == id }
    }
}

/**
 * What the library found of a full-text index in its catalog, by the statement [FullTextSearch.lookup] gives it.
 *
 * @param trigram on Postgres, whether the trigram index of the label's full-text index is made (a row answered the lookup):
 *   the candidates are then narrowed to the nodes that hold every word of some scoring term, which it answers.
 * @param oracleText on Oracle, the side table an Oracle Text index is over (the `object_name` the lookup answered), or null
 *   where none is made: the search then scores by BM25 over every node of the label, as the other SQL stores do.
 * @param name on Neo4j and Memgraph, the index's name, where it isn't `<label>_<properties joined by _>_fulltext`.
 */
@DrivineInternalApi
data class SearchIndex(val trigram: Boolean = false, val oracleText: String? = null, val name: String? = null)

/**
 * A full-text search, as a library asks it.
 *
 * @param labels the root's labels; the index is on the first.
 * @param properties the properties the index covers, by the names they are stored under.
 * @param query the user's query, in the plain core of the Lucene syntax (see `org.drivine.cypher.search.TextQuery`).
 * @param index what the library found of the index.
 * @param topK on a graph engine, how many rows the search wants: the index is asked for that many hits, or with [filtered]
 *   ten times as many ([indexLimit]).
 * @param filtered on a graph engine, whether the hits are pruned after the index: by a caller's filter, by a required
 *   relationship, or by a root label beyond the first.
 * @param alias on a graph engine, what the head names a hit's node: drivine4j's fragment search's `n`, or a view's root.
 * @param scoreAlias on a graph engine, what the head names a hit's score.
 */
@DrivineInternalApi
data class SearchRequest(
    val labels: List<String>,
    val properties: List<String>,
    val query: String,
    val index: SearchIndex = SearchIndex(),
    val topK: Int? = null,
    val filtered: Boolean = false,
    val alias: String = "n",
    val scoreAlias: String = "_score",
)

/**
 * What planning a full-text search ([FullTextSearch.plan]) gives: a [SearchPlan], or the [Refusal] of the query, as compiling
 * a statement gives [Compiled] or the refusal of it.
 */
@DrivineInternalApi
sealed interface SearchResult

/**
 * A full-text search as a statement.
 *
 * @param sql on a SQL store, one `SELECT` of the ranked candidates, `nid` and `score`: the nodes whose text meets the query,
 *   each scored against the best of them, so the best scores 1, and only those that score above 0. The library joins them
 *   back to its node table by `nid` for its projection, and adds its threshold, its predicates, `ORDER BY score DESC, nid`
 *   and its `LIMIT`.
 * @param cypher on a graph engine, the search's head: the index's `CALL`, the hits that have every root label, the best
 *   [SearchRequest.topK] of them (or the over-fetch), and each node and its score against the best as
 *   [SearchRequest.alias] and [SearchRequest.scoreAlias], to which the library adds its filters, projection and `LIMIT`.
 * @param parameters the values the statement binds, each named `_cy_ft<n>`.
 * @param warnings what the module warns of, for the library to log.
 */
@DrivineInternalApi
data class SearchPlan(
    val sql: String?,
    val cypher: String?,
    val parameters: Map<String, Any?>,
    val warnings: List<String> = emptyList(),
) : SearchResult

/**
 * The statement that finds what a library's catalog records of a full-text index: on Postgres a row where its trigram index is
 * made ([SearchIndex.trigram]), on Oracle the side table of its Oracle Text index, the row's `object_name`
 * ([SearchIndex.oracleText]), and no row where none is made.
 */
@DrivineInternalApi
data class CatalogLookup(val sql: String, val parameters: Map<String, Any?>)

/**
 * Full-text search, as drivine4j writes it, for every library: the user's query read (`org.drivine.cypher.search.TextQuery`),
 * and the statement each store answers it with. Pure: the library runs [lookup], then the statement [plan] gives, and keeps
 * its own projection.
 *
 * On the SQL stores, each node is scored by BM25 (`org.drivine.cypher.search.Bm25`) as Lucene, and so Neo4j, scores it,
 * unless Oracle has an Oracle Text index, which scores by its own measure. On the graph engines, the query is given to the
 * engine's index as it is.
 */
@DrivineInternalApi
object FullTextSearch {

    /** The search [request] asks of [store], over [graph]: a [SearchPlan], or a [Refusal] of its query or its graph. */
    fun plan(store: SearchStore, graph: GraphOptions, request: SearchRequest): SearchResult {
        require(request.labels.isNotEmpty()) { "A full-text search names the root's labels." }
        require(request.properties.isNotEmpty()) { "A full-text search names the properties its index covers." }
        if (store.graphEngine) return head(store, request)
        return try {
            val query = TextQuery.parse(request.query)
            val scope = SearchParameters()
            val sql = when (store) {
                SearchStore.POSTGRES -> postgres(PostgresDdl(graph.name), request, query, scope)
                SearchStore.ORACLE -> {
                    val ddl = OracleDdl(graph.name)
                    request.index.oracleText?.let { oracleText(ddl, it, request, query, scope) } ?: oracle(ddl, request, query, scope)
                }
                else -> sqlite(SqliteDdl(graph.name), request, query, scope)
            }
            SearchPlan(sql = sql, cypher = null, parameters = scope.bindings())
        } catch (e: IllegalArgumentException) {
            Refusal(e.message ?: e.toString())
        }
    }

    /**
     * The statement that finds what [graph]'s catalog records of the full-text index on [label]'s [properties], on Postgres
     * and Oracle; null on a store that keeps no such record. drivine4j's backends' `made`.
     */
    fun lookup(store: SearchStore, graph: GraphOptions, label: String, properties: List<String>): CatalogLookup? {
        if (store != SearchStore.POSTGRES && store != SearchStore.ORACLE) return null
        val scope = SearchParameters()
        val key = "kind = ${scope.bound(SchemaItemKind.FULLTEXT_INDEX.name)} AND label = ${scope.bound(label)} AND properties = ${scope.bound(json(properties))}"
        val sql = if (store == SearchStore.POSTGRES) {
            "SELECT details FROM ${PostgresDdl(graph.name).catalog} WHERE $key AND object_name IS NOT NULL AND to_regclass(object_name) IS NOT NULL"
        } else {
            "SELECT object_name FROM ${OracleDdl(graph.name).catalog} WHERE $key AND object_name IS NOT NULL"
        }
        return CatalogLookup(sql, scope.bindings())
    }

    /** The index limit a graph engine's search asks its index for: [topK], or where its hits are [filtered], ten times as many. */
    fun indexLimit(topK: Int, filtered: Boolean): Int =
        if (filtered) (topK.toLong() * FILTERED_OVER_FETCH).coerceAtMost(Int.MAX_VALUE.toLong()).toInt() else topK

    /** How many times as many hits a search whose hits are filtered after the index asks it for. */
    const val FILTERED_OVER_FETCH: Int = 10

    /** The properties of an item as the catalog's key holds them: drivine4j's `SqlGraph.json`. */
    private fun json(values: List<String>): String = values.joinToString(",", "[", "]") { "\"${it.replace("\\", "\\\\").replace("\"", "\\\"")}\"" }

    // ----- The SQL stores: drivine4j's PostgresSelect, OracleSelect and SqliteSelect `matching` -----

    /**
     * Each term is a regular expression that matches it as a whole word. With [SearchIndex.trigram], the nodes counted are
     * first narrowed to those whose text holds every word of some scoring term, which the trigram index of a full-text index
     * answers: a node that does not cannot match the term, so every node that holds a term is still counted, and the scores
     * are the same.
     */
    private fun postgres(ddl: PostgresDdl, request: SearchRequest, query: TextQuery, scope: SearchParameters): String {
        val keys = request.properties
        val text = PostgresSql.searchedText("c", keys)
        val len = "length(${PostgresSql.joinedText("c", keys)})"
        val labelled = listOf(request.labels.joinToString(" AND ") { PostgresSql.hasLabel("c", it) })
        // A node that matches a term holds each of its words: what the trigram index answers is whether it holds them.
        val narrowed = if (request.index.trigram) {
            listOf(query.scoredWords.joinToString(" OR ", "(", ")") { words -> words.joinToString(" AND ", "(", ")") { "$text LIKE ${postgresText(scope.bound("%${likeText(it)}%"))}" } })
        } else {
            emptyList()
        }
        return Bm25.ranked(
            Query("c.nid, $text AS text, $len AS len", Table(ddl.nodes, "c"), labelled + narrowed).text(),
            Query("$len AS len", Table(ddl.nodes, "c"), labelled).text(),
            query,
            Bm25.Dialect(
                count = { text, term -> "(SELECT count(*) FROM regexp_matches($text, ${postgresText(scope.bound(TextQuery.lookingAhead(term)))}, 'g'))" },
                holds = { text, term -> "$text ~ ${postgresText(scope.bound(term))}" },
                ln = { "ln($it)" },
                real = { "CAST($it AS double precision)" },
            ),
        )
    }

    private fun postgresText(parameter: String) = "CAST($parameter AS text)"

    /** [word] as text a `LIKE` pattern matches as it is: its own `%`, `_` and backslash taken as themselves. */
    private fun likeText(word: String): String = word.replace("\\", "\\\\").replace("%", "\\%").replace("_", "\\_")

    /**
     * Every node of the label is read, and each term counted on its own. Counting them as the rows of one bound array, in a
     * subquery the statement names twice, gave wrong counts on Oracle 23.26: every position of the text.
     */
    private fun oracle(ddl: OracleDdl, request: SearchRequest, query: TextQuery, scope: SearchParameters): String {
        val keys = request.properties
        val values = keys.map { "JSON_VALUE(c.props, ${OracleSql.path(it)} RETURNING CLOB)" }
        val text = values.joinToString(" || ' ' || ") { "LOWER($it)" }
        val len = "NVL(LENGTH(${values.joinToString(" || ' ' || ")}), 0)"
        fun scanned() = GraphTable(ddl.name, "(c IS node WHERE ${oracleLabels("c", request.labels, scanned = true)})", ORACLE_NODE_COLUMNS, "c")
        return Bm25.ranked(
            Query("c.nid, $text AS text, $len AS len", scanned()).text(),
            Query("$len AS len", scanned()).text(),
            query,
            Bm25.Dialect(
                // Oracle's expressions don't look ahead: the term ending where its last word does, less those a word goes on
                // from, or a prefix term or one with a character not measured as [[:alnum:]] by its one expression
                // (TextQuery.counted); a phrase that repeats a word or whose last word starts its first, in the text with each
                // character between words written twice (TextQuery.SEPARATOR).
                count = { text, term ->
                    TextQuery.counted(term)?.let { (ended, continued) ->
                        "REGEXP_COUNT($text, ${scope.bound(ended)})" + (continued?.let { " - REGEXP_COUNT($text, ${scope.bound(it)})" } ?: "")
                    } ?: "REGEXP_COUNT(REGEXP_REPLACE($text, '${TextQuery.SEPARATOR}', '\\1\\1'), ${scope.bound(term)})"
                },
                holds = { text, term -> "REGEXP_LIKE($text, ${scope.bound(term)})" },
                ln = { "LN($it)" },
                real = { it },
            ),
        )
    }

    /**
     * Through an Oracle Text index: [texts] is the table the index is over, which holds that text for each node of the
     * index's label. Scored by Oracle Text, each property's score added, and then against the best of them. The index holds
     * what has been committed, so a search does not see what its own transaction has written.
     */
    private fun oracleText(ddl: OracleDdl, texts: String, request: SearchRequest, query: TextQuery, scope: SearchParameters): String {
        val keys = request.properties
        val text = scope.bound(query.oracleText)
        val contains = keys.mapIndexed { i, key -> "JSON_TEXTCONTAINS(s.doc, ${OracleSql.path(key)}, $text, ${i + 1})" }
        return """
            SELECT h.nid, h.score / MAX(h.score) OVER () AS score
            FROM (
                SELECT c.nid, k.score
                FROM (SELECT s.nid, ${keys.indices.joinToString(" + ") { "SCORE(${it + 1})" }} AS score FROM $texts s WHERE ${contains.joinToString(" OR ")}) k
                JOIN ${ddl.nodes} c ON c.nid = k.nid
                WHERE ${oracleLabels("c", request.labels)}
            ) h
            WHERE h.score > 0
        """.trimIndent()
    }

    /**
     * Whether the node [alias] carries each of [labels]: with [scanned], the first as the index over labels answers it, for
     * a statement that reads every node of it, and the rest by the labels' text. drivine4j's `OracleSql.hasLabels`.
     */
    private fun oracleLabels(alias: String, labels: List<String>, scanned: Boolean = false): String =
        labels.withIndex().joinToString(" AND ") { (i, label) -> if (scanned && i == 0) OracleSql.hasLabel(alias, label) else OracleSql.carriesLabel(alias, label) }

    /** The columns a row of a node has on Oracle: those of the table, and the map the graph makes of them. */
    private val ORACLE_NODE_COLUMNS = listOf("nid", "labels", "props", "stamp", "doc")

    /** Each term is counted by the function [SqliteFunctions.REGEXP_COUNT] the library registers, as Postgres counts it. */
    private fun sqlite(ddl: SqliteDdl, request: SearchRequest, query: TextQuery, scope: SearchParameters): String {
        val keys = request.properties
        val text = SqliteSql.searchedText("c", keys)
        val len = "length(${SqliteSql.joinedText("c", keys)})"
        val labelled = listOf(request.labels.joinToString(" AND ") { SqliteSql.hasLabel("c", it) })
        return Bm25.ranked(
            Query("c.nid, $text AS text, $len AS len", Table(ddl.nodes, "c"), labelled).text(),
            Query("$len AS len", Table(ddl.nodes, "c"), labelled).text(),
            query,
            Bm25.Dialect(
                count = { text, term -> "${SqliteFunctions.REGEXP_COUNT}($text, ${sqliteText(scope.bound(TextQuery.lookingAhead(term)))})" },
                holds = { text, term -> "${SqliteFunctions.REGEXP_COUNT}($text, ${sqliteText(scope.bound(term))}) > 0" },
                ln = { "${SqliteFunctions.MATH}('log', $it)" },
                real = { "CAST($it AS REAL)" },
            ),
        )
    }

    private fun sqliteText(parameter: String) = "CAST($parameter AS TEXT)"

    // ----- The graph engines: drivine4j's grammars' fullTextSearchHead -----

    /**
     * The search's head on a graph engine: the query as it is, to the engine's full-text index. Neo4j's
     * `db.index.fulltext.queryNodes` and Memgraph's `text_search.search_all` name the index; FalkorDB's
     * `db.idx.fulltext.queryNodes` names its label, as FalkorDB has no index names.
     */
    private fun head(store: SearchStore, request: SearchRequest): SearchResult {
        val topK = requireNotNull(request.topK) { "A full-text search on ${store.id} gives its topK." }
        val label = request.labels.first()
        val indexName = request.index.name ?: "${label}_${request.properties.joinToString("_")}_fulltext"
        val scope = SearchParameters()
        val query = scope.bound(request.query)
        val limit = scope.bound(indexLimit(topK, request.filtered))
        val call = when (store) {
            SearchStore.NEO4J -> "CALL db.index.fulltext.queryNodes('$indexName', $query, {limit: $limit})"
            // Without a limit, text_search yields its best 1,000 hits and no more; one near Int.MAX_VALUE takes the server down.
            SearchStore.MEMGRAPH -> "CALL text_search.search_all('$indexName', $query, {limit: CASE WHEN $limit < $TEXT_SEARCH_LIMIT_CAP THEN $limit ELSE $TEXT_SEARCH_LIMIT_CAP END})"
            else -> "CALL db.idx.fulltext.queryNodes('$label', $query)"
        }
        return SearchPlan(sql = null, cypher = normalizedHead(call, request.alias, request.scoreAlias, limit, request.labels, label), parameters = scope.bindings())
    }

    /** The most hits a full-text `CALL` asks Memgraph's `text_search` for: Memgraph 3.14 crashes at 500,000,000. */
    private const val TEXT_SEARCH_LIMIT_CAP: Int = 1_000_000

    /**
     * drivine4j's `CypherGrammar.normalizedFullTextHead`: [call]'s hits, those with every one of [labels] (the index is on
     * [indexedLabel] alone), the best [limit] of them, each score against the best, and 1 where the best is 0 (FalkorDB's).
     */
    private fun normalizedHead(call: String, alias: String, scoreAlias: String, limit: String, labels: List<String>, indexedLabel: String): String {
        val check = if (labels.isEmpty() || labels == listOf(indexedLabel)) null else "node" + labels.joinToString("") { ":${CypherNames.quoted(it)}" }
        return """
            |$call
            |YIELD node, score
            |${check?.let { "WITH node, score WHERE $it\n|" } ?: ""}WITH node, score
            |ORDER BY score DESC
            |LIMIT $limit
            |WITH collect({node: node, score: score}) AS _ftResults, max(score) AS _ftMax
            |UNWIND _ftResults AS _ftRow
            |WITH _ftRow.node AS $alias, (CASE WHEN _ftMax > 0 THEN _ftRow.score / _ftMax ELSE 1.0 END) AS $scoreAlias
        """.trimMargin()
    }
}
