package org.drivine.cypher.store.marker

import org.drivine.DrivineInternalApi
import org.drivine.cypher.store.OracleSql
import org.drivine.cypher.store.PostgresSql
import org.drivine.cypher.store.SqliteSql
import org.drivine.cypher.store.ddl.OracleDdl
import org.drivine.cypher.store.ddl.PostgresDdl
import org.drivine.cypher.store.ddl.SqliteDdl
import org.drivine.cypher.store.ddl.StoreDdl
import org.drivine.cypher.store.marker.SchemaMarker.APPLIED_AT
import org.drivine.cypher.store.marker.SchemaMarker.LABEL
import org.drivine.cypher.store.marker.SchemaMarker.SCOPE_PROPERTY

/**
 * The statements that keep the schema marker ([SchemaMarker]), in a store's own language, as drivine4j's
 * `VersionMarkerStatements` of each store writes them: Cypher on the graph engines, and SQL over the node table on
 * Postgres, Oracle and SQLite, where the marker is a node of the graph as on the engines. Every statement binds the owner's
 * scope as `$scope`. Running them, reading their rows and the transactions they run in stay each library's.
 *
 * Of several markers of a scope, which a race before [SchemaMarker.CONSTRAINT] was made can leave, the one read, the one
 * written and the one [deleteAllButNewest] keeps are the same on a SQL store: the one applied last, and of two applied in
 * one moment, the one made last. The graph engines' Cypher, as drivine4j writes it, has no such tie-break, and its `MERGE`
 * writes every marker of the scope.
 */
@DrivineInternalApi
interface MarkerStatements {

    /**
     * What makes what the other statements need, run once on a schema connection before any of them: the graph's tables
     * on a SQL store, which a marker read before anything else made them would not find. Null where nothing is needed.
     */
    val prepare: String?

    /** Reads [property] of the scope's newest marker, as text: one row, or none where there is no marker. */
    fun read(property: String): String

    /** Writes the scope's newest marker, and makes one where there is none. It binds what [mergeParameters] gives. */
    val merge: String

    /**
     * What [merge] binds, for the owner [scope], its [version] (null for none, which takes away the one the marker had) and
     * its inventory's [items] ([MarkerInventory.items]), kept as the store keeps them ([storedItems]). [appliedAt] is the
     * moment, in milliseconds since the epoch, by the caller's clock: a SQL store binds it as `$now`. The graph engines
     * take the moment of their own clock, `timestamp()`, as drivine4j's Cypher always has, and bind no `$now`. The split
     * is kept on purpose, so a store keeps reading the markers it already has as it did: one clock for every store would
     * be a change of its own, made in every library at once, and not a fix here.
     */
    fun mergeParameters(scope: String, version: String?, items: String, appliedAt: Long): Map<String, Any?> =
        mapOf("scope" to scope, "version" to version, "items" to storedItems(items))

    /** Removes every marker of the scope but its newest: what a library runs, with the constraint, before it reads one. */
    val deleteAllButNewest: String

    /** Removes the scope's marker, so that the next enforce takes its schema as never versioned. */
    val delete: String

    /**
     * The scope of every marker, a row each, in the store's own SQL; null where they are read as Cypher, [SCOPES_CYPHER],
     * which the graph engines run and the compiler compiles for Postgres and Oracle. SQLite has them in SQL, as an older
     * SQLite refuses compiled Cypher.
     */
    val scopes: String?
        get() = null

    /** [items] as the store keeps them, which [items] reads back. */
    fun storedItems(items: String): String = items

    /** The inventory's items as [read] gives them back from the store, undoing [storedItems]. */
    fun items(stored: String): String = stored

    /**
     * The inventory of a marker whose items [read] gave as [stored]: null where no row or no value came back, which is a
     * marker written before inventories were kept, or none at all, and so an unknown inventory, not an empty one.
     */
    fun inventory(stored: String?): MarkerInventory? = stored?.let { MarkerInventory.parse(items(it)) }

    companion object {

        /** The Cypher that reads the scope of every marker, where [scopes] is null. */
        const val SCOPES_CYPHER = "MATCH (m:`$LABEL`) RETURN DISTINCT m.$SCOPE_PROPERTY"

        /** The marker's statements on the store [ddl] makes: in SQL on Postgres, Oracle and SQLite, and in Cypher on a graph engine. */
        fun of(ddl: StoreDdl): MarkerStatements = when (ddl) {
            is PostgresDdl -> PostgresMarkerStatements(ddl)
            is OracleDdl -> OracleMarkerStatements(ddl)
            is SqliteDdl -> SqliteMarkerStatements(ddl)
            else -> CypherMarkerStatements
        }
    }
}

/**
 * The marker's statements in Cypher, which Neo4j, Memgraph and FalkorDB share, and which a store with no DDL here, as
 * Neptune, is given, as drivine4j's grammar gives it by default. The moment is the engine's `timestamp()`, in
 * milliseconds, as drivine4j writes it, so a caller's moment is not bound. That is on purpose, not an oversight: the SQL
 * stores bind the caller's clock because they have no `timestamp()`, and one clock for every store would be a change of
 * its own, made in every library at once (see [MarkerStatements.mergeParameters]).
 */
@DrivineInternalApi
object CypherMarkerStatements : MarkerStatements {
    private const val MARKER = "MATCH (m:`$LABEL` {$SCOPE_PROPERTY: \$scope})"

    override val prepare: String? = null

    override fun read(property: String): String = "$MARKER RETURN m.$property ORDER BY m.$APPLIED_AT DESC LIMIT 1"

    override val merge: String =
        "MERGE (m:`$LABEL` {$SCOPE_PROPERTY: \$scope}) SET m.version = \$version, m.items = \$items, m.$APPLIED_AT = timestamp()"

    override val deleteAllButNewest: String = "$MARKER WITH m ORDER BY m.$APPLIED_AT DESC SKIP 1 DELETE m"

    override val delete: String = "$MARKER DELETE m"
}

/**
 * A SQL store's marker statements: the moment is bound as `$now`, the caller's, as the SQL stores have no `timestamp()` of
 * the graph engines' shape.
 */
@DrivineInternalApi
abstract class SqlMarkerStatements : MarkerStatements {
    override fun mergeParameters(scope: String, version: String?, items: String, appliedAt: Long): Map<String, Any?> =
        super.mergeParameters(scope, version, items, appliedAt) + ("now" to appliedAt)
}

/**
 * The marker on Postgres. Postgres keeps an empty text apart from null, so an empty inventory is kept as it is. A value is
 * bound with no type, so each is cast where a function would otherwise have to guess it.
 */
@DrivineInternalApi
class PostgresMarkerStatements(private val ddl: PostgresDdl) : SqlMarkerStatements() {
    private val nodes = ddl.nodes

    /** The test that finds the marker of the scope bound as `$scope`, among the nodes named [alias]. */
    private fun marker(alias: String = "n") = "${PostgresSql.hasLabel(alias, LABEL)} AND ${PostgresSql.property(alias, SCOPE_PROPERTY)} = CAST(\$scope AS text)"

    /** The order that puts the marker applied last first, one never applied after every other, and of two applied at once, the one made later. */
    private fun newestFirst(alias: String = "n") = "${PostgresSql.number(alias, APPLIED_AT)} DESC NULLS LAST, $alias.nid DESC"

    override val prepare: String get() = ddl.ensuring

    override fun read(property: String): String =
        "SELECT ${PostgresSql.property("n", property)} FROM $nodes n WHERE ${marker()} ORDER BY ${newestFirst()} LIMIT 1"

    /** The scope's newest marker is written, and one is made where there is none. A null removes what the marker had. */
    override val merge: String = """
        WITH found AS (SELECT m.nid FROM $nodes m WHERE ${marker("m")} ORDER BY ${newestFirst("m")} LIMIT 1),
        written AS (
            UPDATE $nodes n
            SET props = jsonb_strip_nulls(n.props || jsonb_build_object('version', CAST(${'$'}version AS text), 'items', CAST(${'$'}items AS text), '$APPLIED_AT', CAST(${'$'}now AS bigint)))
            FROM found WHERE n.nid = found.nid
            RETURNING n.nid)
        INSERT INTO $nodes (labels, props)
        SELECT ${PostgresSql.labelArray(LABEL)},
               jsonb_strip_nulls(jsonb_build_object(${PostgresSql.literal(SCOPE_PROPERTY)}, CAST(${'$'}scope AS text), 'version', CAST(${'$'}version AS text), 'items', CAST(${'$'}items AS text), '$APPLIED_AT', CAST(${'$'}now AS bigint)))
        WHERE NOT EXISTS (SELECT 1 FROM found)
    """.trimIndent()

    override val deleteAllButNewest: String = """
        DELETE FROM $nodes WHERE nid IN (
            SELECT nid FROM (SELECT n.nid, row_number() OVER (ORDER BY ${newestFirst()}) AS position FROM $nodes n WHERE ${marker()}) ranked
            WHERE position > 1)
    """.trimIndent()

    override val delete: String = "DELETE FROM $nodes n WHERE ${marker()}"
}

/**
 * The marker on Oracle. A property is read as a CLOB, since an inventory can run past the 4,000 characters a text is read
 * as by default. The inventory is kept behind [ITEMS_MARK], since Oracle keeps an empty text as null, and an empty
 * inventory is not an unknown one.
 */
@DrivineInternalApi
class OracleMarkerStatements(private val ddl: OracleDdl) : SqlMarkerStatements() {
    private val nodes = ddl.nodes

    /** The test that finds the marker of the scope bound as `$scope`, among the nodes named [alias]. */
    private fun marker(alias: String = "n") = "${OracleSql.hasLabel(alias, LABEL)} AND ${OracleSql.property(alias, SCOPE_PROPERTY)} = \$scope"

    /** The order that puts the marker applied last first, one never applied after every other, and of two applied at once, the one made later. */
    private fun newestFirst(alias: String = "n") = "${OracleSql.number(alias, APPLIED_AT)} DESC NULLS LAST, $alias.nid DESC"

    override val prepare: String get() = ddl.ensuring

    override fun read(property: String): String =
        "SELECT JSON_VALUE(n.props, ${OracleSql.path(property)} RETURNING CLOB) FROM $nodes n WHERE ${marker()} ORDER BY ${newestFirst()} FETCH FIRST 1 ROWS ONLY"

    /**
     * The marker is found by a query and matched by its id. Matched by its scope, the statement would update the column it
     * matches on, which Oracle refuses (`ORA-38104`) unless an index over the scope stands in for the column, as the one
     * beneath [SchemaMarker.CONSTRAINT] does: an owner with no version has no such constraint made for it.
     */
    override val merge: String = """
        MERGE INTO $nodes n
        USING (SELECT (SELECT m.nid FROM $nodes m WHERE ${marker("m")} ORDER BY ${newestFirst("m")} FETCH FIRST 1 ROWS ONLY) AS nid FROM dual) found
        ON (n.nid = found.nid)
        WHEN MATCHED THEN UPDATE SET n.props = JSON_MERGEPATCH(n.props, JSON_OBJECT('version' VALUE ${'$'}version, 'items' VALUE ${'$'}items, '$APPLIED_AT' VALUE ${'$'}now RETURNING JSON))
        WHEN NOT MATCHED THEN INSERT (labels, props)
            VALUES (${OracleSql.literal("[\"$LABEL\"]")}, JSON_OBJECT(${OracleSql.literal(SCOPE_PROPERTY)} VALUE ${'$'}scope, 'version' VALUE ${'$'}version, 'items' VALUE ${'$'}items, '$APPLIED_AT' VALUE ${'$'}now ABSENT ON NULL RETURNING JSON))
    """.trimIndent()

    override val deleteAllButNewest: String = """
        DELETE FROM $nodes WHERE nid IN (
            SELECT nid FROM (SELECT n.nid, ROW_NUMBER() OVER (ORDER BY ${newestFirst()}) AS position FROM $nodes n WHERE ${marker()})
            WHERE position > 1)
    """.trimIndent()

    override val delete: String = "DELETE FROM $nodes n WHERE ${marker()}"

    override fun storedItems(items: String): String = ITEMS_MARK + items

    /** The items without their mark; a value written without one, by hand, is read as it is. */
    override fun items(stored: String): String = stored.removePrefix(ITEMS_MARK)

    companion object {
        /** What the inventory is kept behind, so that an empty one is read back as empty and not as absent. */
        const val ITEMS_MARK = "items:"
    }
}

/**
 * The marker on SQLite. SQLite keeps an empty text apart from null, so an empty inventory is kept as it is. The scopes are
 * read in SQL ([scopes]), as an older SQLite refuses compiled Cypher.
 */
@DrivineInternalApi
class SqliteMarkerStatements(private val ddl: SqliteDdl) : SqlMarkerStatements() {
    private val nodes = ddl.nodes

    /** The test that finds the marker of the scope bound as `$scope`, among the nodes named [alias]. */
    private fun marker(alias: String = "n") = "${SqliteSql.hasLabel(alias, LABEL)} AND ${SqliteSql.property(alias, SCOPE_PROPERTY)} = CAST(\$scope AS TEXT)"

    /** The order that puts the marker applied last first, one never applied after every other, and of two applied at once, the one made later. */
    private fun newestFirst(alias: String = "n") = "${SqliteSql.number(alias, APPLIED_AT)} DESC NULLS LAST, $alias.nid DESC"

    /** The table of nodes, which is all the marker needs made. */
    override val prepare: String get() = ddl.ddl.first()

    override fun read(property: String): String =
        "SELECT ${SqliteSql.property("n", property)} FROM $nodes n WHERE ${marker()} ORDER BY ${newestFirst()} LIMIT 1"

    /**
     * The row is inserted with the `nid` of the marker found, or none, and a marker that is there is written instead. A
     * merge patch leaves out what is null, so a version that is null removes the one the marker had.
     */
    override val merge: String = """
        INSERT INTO $nodes (nid, labels, props)
        SELECT (SELECT m.nid FROM $nodes m WHERE ${marker("m")} ORDER BY ${newestFirst("m")} LIMIT 1),
               ${SqliteSql.labelArray(LABEL)},
               json_patch('{}', json_object(${SqliteSql.literal(SCOPE_PROPERTY)}, CAST(${'$'}scope AS TEXT), 'version', ${'$'}version, 'items', ${'$'}items, '$APPLIED_AT', ${'$'}now))
        WHERE 1
        ON CONFLICT (nid) DO UPDATE SET props = json_patch(props, json_object('version', ${'$'}version, 'items', ${'$'}items, '$APPLIED_AT', ${'$'}now))
    """.trimIndent()

    override val deleteAllButNewest: String = """
        DELETE FROM $nodes WHERE nid IN (
            SELECT nid FROM (SELECT n.nid, row_number() OVER (ORDER BY ${newestFirst()}) AS position FROM $nodes n WHERE ${marker()}) ranked
            WHERE position > 1)
    """.trimIndent()

    override val delete: String = "DELETE FROM $nodes WHERE nid IN (SELECT n.nid FROM $nodes n WHERE ${marker()})"

    override val scopes: String = "SELECT DISTINCT ${SqliteSql.property("n", SCOPE_PROPERTY)} FROM $nodes n WHERE ${SqliteSql.hasLabel("n", LABEL)}"
}
