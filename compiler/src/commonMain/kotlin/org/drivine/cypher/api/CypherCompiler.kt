package org.drivine.cypher.api

import org.drivine.cypher.sql.OracleCypherCompiler
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
 */
data class GraphOptions(
    val name: String,
    val nullsEqualStored: Boolean = true,
    val eraStored: Boolean = true,
    val indexedProperties: Set<Pair<String, String>> = emptySet(),
    val oracleRowsAsText: Boolean = false,
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
 */
data class Compiled(
    val sql: String,
    val parameters: Map<String, Any?>,
    val setup: List<String>,
    val warnings: List<String>,
    val nonFinite: Boolean,
    val storedFunctions: StoredFunctions?,
) : CompileResult

/** Which of the graph's stored functions an Oracle statement calls: see [GraphOptions]. */
data class StoredFunctions(val nullsEqual: Boolean, val era: Boolean)

/** The compiler refuses the statement, with [message]: what each library throws, as the spec's refusals pin it. */
data class Refusal(val message: String) : CompileResult

/**
 * Drivine's Cypher compiler: a statement in Drivine's flavour of Cypher, as SQL for Postgres, Oracle or SQLite.
 *
 * The parameters are the values a JVM caller binds: `Long` (or `Int`), `Double`, `String`, `Boolean`, null, lists, maps,
 * bignum's `BigInteger` and `BigDecimal`, a `ByteArray`, and [org.drivine.cypher.json.TemporalValue] for a temporal value,
 * and on the JVM java.time's types too. A host that has no such types gives its parameters as JSON: see [CompileJson].
 */
object CypherCompiler {

    fun compile(store: Store, graph: GraphOptions, cypher: String, parameters: Map<String, Any?> = emptyMap()): CompileResult {
        val compiler: SqlCypherCompiler = when (store) {
            Store.POSTGRES -> PostgresCypherCompiler(graph.name)
            Store.ORACLE -> OracleCypherCompiler(graph.name, graph.nullsEqualStored, graph.eraStored, graph.indexedProperties, encoder = null, rowsAsText = graph.oracleRowsAsText)
            Store.SQLITE -> SqliteCypherCompiler(graph.name)
        }
        val compiled = try {
            compiler.compile(cypher, parameters)
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
