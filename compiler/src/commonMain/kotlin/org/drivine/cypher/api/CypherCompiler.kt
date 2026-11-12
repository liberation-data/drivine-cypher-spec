package org.drivine.cypher.api

import org.drivine.cypher.ParserCaches

import org.drivine.cypher.sql.ColumnKind
import org.drivine.cypher.sql.OracleCypherCompiler
import org.drivine.cypher.sql.ResultColumn
import org.drivine.cypher.sql.PostgresCypherCompiler
import org.drivine.cypher.sql.SqlCypherCompiler
import org.drivine.cypher.sql.SqliteCypherCompiler

/** A store Drivine compiles Cypher to SQL for. */
enum class Store(val id: String) {
    POSTGRES("postgres"),
    ORACLE("oracle"),
    SQLITE("sqlite");

    companion object {
        /** The store named [id], as the JSON request names it: `postgres`, `oracle` or `sqlite`. */
        fun of(id: String): Store? = entries.firstOrNull { it.id == id }
    }
}

/**
 * What the compiler is told of the graph a statement runs over.
 *
 * @param name the graph's name: its tables are `<name>_node` and `<name>_edge`.
 * @param nullsEqualStored on Oracle, whether the store keeps the graph's function that compares two values that may hold
 *   a null. A store made by an earlier version, or by a user who may not make a function, has none: compile again with
 *   false where [Compiled.storedFunctions] says the statement calls it and the store hasn't it.
 * @param eraStored on Oracle, whether the store keeps the graph's functions that renumber a year BC, likewise.
 * @param indexedProperties on Oracle, the labels and properties a range index or a one-property uniqueness constraint is
 *   over, each `label to property`: a node a pattern finds by one is read through that index.
 * @param oracleRowsAsText on Oracle, whether a statement that writes and returns rows returns them all as one row of JSON
 *   text, a list in their order, in a column named `rows:json` ([OracleCypherCompiler.ROWS_AS_TEXT]), in place of a row
 *   of `JSON` each. The text is Oracle's extended JSON (`JSON_SERIALIZE(… EXTENDED)`), so a float stays a float: an
 *   object of the one key `$numberDouble` is one, `{"$numberDouble": 2}`, `{"$numberDouble": 2.5}`, and NaN and the
 *   infinities `{"$numberDouble": "Nan"}`, `"Inf"` and `"-Inf"`. Every other value is plain JSON, as the `JSON` rows
 *   hold it: a whole number in full, a text, a temporal value as its ISO 8601 text, a list, a map. A map the statement
 *   returns whose one key is `$numberDouble` is written as a float is, and read as one. One row, as a host reads it while
 *   the driver's prefetch is at least one row (node-oracledb's default is 2).
 *
 *   It is for a host whose Oracle driver can't read an implicit result's `JSON` column: in node-oracledb's thin mode such
 *   a result gives only its prefetched rows, 2 by default and none with `prefetchRows: 0`, with no error
 *   (oracle/node-oracledb#1795), and some values fail with `NJS-126: OSON version 2 is not supported`. A `CLOB` column
 *   is cut short alike, a row each, and a `VARCHAR2` holds too little of a row. It is temporary: Drivine for TypeScript
 *   drops it once a node-oracledb release with the fix ships, and drivine-python once python-oracledb's thin mode, another
 *   driver, is known to read every row (liberation-data/drivine-python#468). A statement that returns no rows, or
 *   reads only, and every statement on the other stores, is compiled alike either way.
 * @param oracleReencodeJson on Oracle, whether each value a statement returns is encoded again, as
 *   `JSON_TRANSFORM(value, KEEP '$')`. A document updated in place comes back as OSON version 2 where the statement doesn't
 *   make it anew, as a node with no stamp is returned, and the thin drivers read only versions 1 and 3: python-oracledb's
 *   thin mode gives `DPY-3021: OSON version 2 is not supported` (oracle/python-oracledb#622), node-oracledb's
 *   `NJS-126` (oracle/node-oracledb#1796). For a host on a thin driver, reading or writing; JDBC reads version 2, so
 *   drivine4j leaves it false. With [oracleRowsAsText] a write's rows are text already, and its reads still need this.
 *   A statement that returns nothing, and every statement on the other stores, is compiled alike either way.
 * @param oracleReadsAsText on Oracle, whether a statement that only reads gives each row's value as its text in Oracle's
 *   extended JSON, a `CLOB` in the column `result`, in place of `JSON`: `JSON_SERIALIZE(… RETURNING CLOB EXTENDED)`, as
 *   [oracleRowsAsText] gives a write's rows. A whole number keeps its digits and a float is marked as one, for a host whose
 *   driver reads a `JSON` value's numbers through a double: node-oracledb's thin mode does, so 9007199254740993 came back
 *   9007199254740992 (#198). The rows keep their order. drivine4j leaves it false, as JDBC keeps a number's digits. A
 *   statement that writes, one compiled typed, and every statement on the other stores, is compiled alike either way.
 */
data class GraphOptions(
    val name: String,
    val nullsEqualStored: Boolean = true,
    val eraStored: Boolean = true,
    val indexedProperties: Set<Pair<String, String>> = emptySet(),
    val oracleRowsAsText: Boolean = false,
    val oracleReencodeJson: Boolean = false,
    val oracleReadsAsText: Boolean = false,
)

/** What compiling a statement gives: the SQL, or the compiler's refusal of the statement. */
sealed interface CompileResult

/**
 * A statement as SQL.
 *
 * @param sql the statement to run, binding [parameters].
 * @param parameters the values the statement binds, by name.
 * @param setup statements to run first, in the same session, binding nothing: on Postgres, a statement that writes is a
 *   function of the session's own (`pg_temp`), which [sql] calls.
 * @param warnings what the compiler warns of, for the host to log.
 * @param nonFinite whether the statement is given NaN or an infinity, or works one out: a store whose JSON holds no such
 *   float then gives it in the rows as a value of its own (`{"$numberDouble": "NaN"}`), which the host reads back.
 * @param storedFunctions on Oracle, which of the graph's stored functions [sql] calls; null on the other stores.
 * @param columns the columns of the statement's rows, each named as a graph engine names it, and of the kind the compiler
 *   knows it holds: see [ColumnKind]. None for a statement that returns nothing.
 */
data class Compiled(
    val sql: String,
    val parameters: Map<String, Any?>,
    val setup: List<String>,
    val warnings: List<String>,
    val nonFinite: Boolean,
    val storedFunctions: StoredFunctions?,
    val columns: List<ResultColumn> = emptyList(),
) : CompileResult

/** Which of the graph's stored functions an Oracle statement calls: see [GraphOptions]. */
data class StoredFunctions(val nullsEqual: Boolean, val era: Boolean)

/**
 * The compiler refuses the statement, with [message]: what each library throws, as the spec's refusals pin it. Or a
 * full-text search's query ([FullTextSearch]), or a vector search ([VectorSearch]).
 */
data class Refusal(val message: String) : CompileResult, SearchResult, VectorResult

/**
 * Drivine's Cypher compiler: a statement in Drivine's flavour of Cypher, as SQL for Postgres, Oracle or SQLite.
 *
 * The parameters are the values a JVM caller binds: `Long` (or `Int`), `Double`, `String`, `Boolean`, null, lists, maps,
 * bignum's `BigInteger` and `BigDecimal`, a `ByteArray`, and [org.drivine.cypher.json.TemporalValue] for a temporal value,
 * and on the JVM java.time's types too. A host that has no such types gives its parameters as JSON: see [CompileJson].
 */
object CypherCompiler {

    /**
     * The most DFA states the parser's prediction caches hold, for the whole process, before they are made anew: 5,000 where
     * it is not set, about 15 MB at their most (#179). A server that compiles many more distinct statements than about 300
     * may raise it, trading memory for the few tens of microseconds a statement pays after the caches are made anew; see
     * [parserCacheRenewals] for how often they are.
     */
    var parserCacheStates: Int
        get() = ParserCaches.cap
        set(value) {
            require(value >= 1) { "The parser's caches hold at least one DFA state: $value." }
            ParserCaches.cap = value
        }

    /**
     * How many times the parser's caches have been made anew since the process began: a library logs it, at debug, where it
     * has grown since it last looked, so a statement set too large for [parserCacheStates] shows in the log.
     */
    val parserCacheRenewals: Int get() = ParserCaches.renewals

    /** The DFA states the parser's caches hold now. */
    val parserCacheHeld: Int get() = ParserCaches.states()

    fun compile(store: Store, graph: GraphOptions, cypher: String, parameters: Map<String, Any?> = emptyMap()): CompileResult =
        compile(store, graph, cypher, parameters, typed = false)

    /**
     * [cypher] compiled as the [compile] above compiles it, and with [typed] its rows' nodes and relationships tagged with their
     * labels and type, in the columns [ColumnKind.tagged] names. Untyped, the SQL is byte for byte the other's.
     */
    fun compile(store: Store, graph: GraphOptions, cypher: String, parameters: Map<String, Any?>, typed: Boolean): CompileResult {
        val compiler: SqlCypherCompiler = when (store) {
            Store.POSTGRES -> PostgresCypherCompiler(graph.name)
            Store.ORACLE -> OracleCypherCompiler(graph.name, graph.nullsEqualStored, graph.eraStored, graph.indexedProperties, encoder = null, rowsAsText = graph.oracleRowsAsText, reencodeJson = graph.oracleReencodeJson, readsAsText = graph.oracleReadsAsText)
            Store.SQLITE -> SqliteCypherCompiler(graph.name)
        }
        val compiled = try {
            compiler.compile(cypher, parameters, typed)
        } catch (e: Exception) {
            return Refusal(e.message ?: e.toString())
        }
        val (setup, sql) = if (store == Store.POSTGRES) postgresSetup(compiled.sql) else emptyList<String>() to compiled.sql
        return Compiled(
            sql = sql,
            parameters = compiled.parameters,
            setup = setup,
            warnings = compiler.warnings.toList(),
            nonFinite = compiled.nonFinite,
            storedFunctions = (compiler as? OracleCypherCompiler)?.let { StoredFunctions(it.callsStoredNullsEqual, it.callsStoredEra) },
            columns = compiled.columns,
        )
    }

    /**
     * A Postgres statement that writes, split into the function it makes and the call of it. The compiler writes them as one
     * text, which drivine4j's JDBC driver runs as two statements; a driver that binds parameters runs one statement at a
     * time, so the function is made first.
     */
    private fun postgresSetup(sql: String): Pair<List<String>, String> {
        if (!sql.startsWith(PG_FUNCTION)) return emptyList<String>() to sql
        // The function's body is quoted by a tag no text in it holds; the call follows it.
        val at = sql.lastIndexOf(PG_CALL)
        return listOf(sql.substring(0, at)) to sql.substring(at + 1)
    }

    private const val PG_FUNCTION = "CREATE OR REPLACE FUNCTION pg_temp."
    private const val PG_CALL = "\nSELECT cy.result FROM pg_temp."
}
