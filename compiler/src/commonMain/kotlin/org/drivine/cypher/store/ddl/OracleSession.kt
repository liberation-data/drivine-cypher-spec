package org.drivine.cypher.store.ddl

import org.drivine.DrivineInternalApi

/**
 * What a library's Oracle session is set to before it runs a statement the compiler makes (drivine-cypher-spec#372): text
 * compared and sorted by its code points, as Cypher compares it, whatever the database's or the session's NLS settings.
 * Oracle compares a `VARCHAR2` as the session's `NLS_COMP` and `NLS_SORT` say, and under `NLS_COMP=LINGUISTIC` with a sort
 * such as `BINARY_CI`, `'B' < 'a'` turns over and `=` ignores case: in a comparison, an order, a `GROUP BY`, a `DISTINCT`, an
 * `IN`, a `MERGE`'s find and a lookup by an index, with no error. The compiler writes no collation of its own into each, as
 * Postgres's `COLLATE "C"`: a grouping and a distinct of `JSON` take none. So the guarantee is the session's, as Postgres's
 * functions are the store's ([PostgresDdl]): each library runs [STATEMENTS] once on each connection as it opens it, and again
 * where its pool resets a session's settings as it takes a connection back. Oracle's default, `NLS_COMP=BINARY`, is
 * already so, and to most sessions they change nothing. drivine4j runs them through the Kotlin types; TypeScript and Python
 * read them through the JSON entry (`storeStatements`'s `session`).
 */
@DrivineInternalApi
object OracleSession {

    /** The statements each connection runs once as it opens, in order: see the object. */
    val STATEMENTS: List<String> = listOf("ALTER SESSION SET NLS_COMP = BINARY NLS_SORT = BINARY")
}
