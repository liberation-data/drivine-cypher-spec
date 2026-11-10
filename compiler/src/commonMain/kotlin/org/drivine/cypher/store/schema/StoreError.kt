package org.drivine.cypher.store.schema

import org.drivine.DrivineInternalApi

/**
 * One error of a failure's chain, as plain values: what a store's error rules read of it ([StoreSchema.isViolation],
 * [StoreSchema.isBlockedByIndex], [StoreSchema.isBuildTimeout]). The rules are pure, so the module can say for every
 * library what drivine4j decides of an error, and a library only has to say what its driver gave.
 *
 * A library makes one of each error in the chain, outermost first:
 *
 * - from a SQL driver's error (a JDBC `SQLException`, a Postgres, Oracle or SQLite driver's error): its [sqlState], and its
 *   [vendorCode], the driver's own number (JDBC's `getErrorCode`, `0` where the driver gives none);
 * - from a Neo4j driver's error, which Memgraph's errors are too: its [code] (`Neo.ClientError…`, or `N/A` where the
 *   driver gives none, as drivine4j's Java driver does, since a null code would read as no driver's error), and its
 *   [gqlStatus], or null where the driver has none to give;
 * - from any other error: its [message] alone.
 *
 * Every error gives its [message], null where it has none.
 *
 * @param sqlState the SQL state of a SQL driver's error
 * @param vendorCode the vendor code of a SQL driver's error, never null for one: what marks it a SQL driver's
 * @param code the code of a Neo4j driver's error, never null for one: what marks it a Neo4j driver's
 * @param gqlStatus the GQL status of a Neo4j driver's error. drivine4j's driver gives `50N42` for an error a server sent
 *   none for, and the rules read a null as that
 */
@DrivineInternalApi
data class Cause(
    val sqlState: String? = null,
    val vendorCode: Int? = null,
    val code: String? = null,
    val gqlStatus: String? = null,
    val message: String? = null,
) {

    /** Whether this is a SQL driver's error, as drivine4j's `is SQLException`. */
    val isSqlError: Boolean
        get() = vendorCode != null || sqlState != null

    /** Whether this is a Neo4j driver's error, as drivine4j's `is Neo4jException`. */
    val isNeo4jError: Boolean
        get() = code != null
}

/**
 * A failure as a store's error rules read it: its [causes], the chain of errors from the outermost in, as a JVM library
 * walks `Throwable.cause`. A failure to make or read a schema item comes back to the module's schema manager as this,
 * which decides what drivine4j decides of it.
 */
@DrivineInternalApi
data class StoreError(val causes: List<Cause>) {

    constructor(vararg causes: Cause) : this(causes.toList())

    /** Every message of the chain, joined by ` | `, as drivine4j's `SchemaGrammar.messagesOf` reads them. */
    val messages: String
        get() = causes.mapNotNull { it.message }.joinToString(" | ")
}
