package org.drivine.cypher.store.migrate

import org.drivine.DrivineInternalApi
import org.drivine.cypher.api.Store
import org.drivine.cypher.store.ddl.OracleDdl
import org.drivine.cypher.store.ddl.PostgresDdl
import org.drivine.cypher.store.ddl.SqlStoreDdl
import org.drivine.cypher.store.ddl.SqliteDdl
import org.drivine.cypher.store.OracleSql
import org.drivine.cypher.store.PostgresSql
import org.drivine.cypher.store.SqliteSql
import org.drivine.cypher.store.ValueLayout
import org.drivine.cypher.store.marker.StoreLayout
import org.drivine.cypher.store.marker.StoreStamp

/**
 * The statements a migration of a graph's layout runs on its SQL store ([LayoutMigrator]), each the module's own, as
 * `migrate.yaml`'s `statements` pin them for the graph `drivine`. A statement binds its parameters by name, `$after`, as
 * the store's other statements of the module do. A statement that gives rows gives each as one JSON object (on SQLite,
 * after [SqliteDdl.JSON_ROWS], so its text is read as JSON), as `readStamps` does.
 */
@DrivineInternalApi
sealed class MigrationStatements(val ddl: SqlStoreDdl) {

    /** The store, as a rewrite is told it. */
    abstract val store: Store

    /**
     * Reads the rows of [table] after the id `$after`, at most `$limit` of them, in the order of their ids: each
     * `{"id": <its id>, "labels": [<a node's labels, or a relationship's type>], "props": "<its props' JSON text>"}`, the text
     * as [LayoutMigration.rewrite] reads it.
     */
    abstract fun readBatch(table: Table): String

    /** Writes the row of [table] whose id is `$id` its `props`, `$props`, the text [LayoutMigration.rewrite] gave. */
    abstract fun update(table: Table): String

    /**
     * Reads Drivine's own nodes, the store's stamp and the schema marker, each `{"id", "props"}` in the order of their ids:
     * with [sizes], what a migration records as it begins, and reads again as it resumes, so a graph changed under it is
     * refused ([LayoutMigrator]). A rewrite changes none of them.
     */
    abstract val own: String

    /** The size of the graph: one row, `{"nodes": <count>, "lastNode": <its last id>, "edges": …, "lastEdge": …}`. */
    abstract val sizes: String

    /** Makes the checkpoint table, [SqlStoreDdl.migrate], safe to run again: a `name`, its primary key, and a `state`. */
    abstract val createCheckpoint: String

    /** Reads every row of the checkpoint table, each `{"name", "state"}`, in the order of their names. */
    abstract val readCheckpoint: String

    /**
     * Writes the row of the checkpoint table named `$name` its `state`, `$state`, in its place where there is one. A state
     * holds the report so far, which a graph of many values not of their kind makes long: on Oracle a library binds it as a
     * CLOB, as a text past 4,000 bytes bound as a `VARCHAR2` is refused.
     */
    abstract val writeCheckpoint: String

    /** Drops the checkpoint table, safe to run again: the migration's last statement. */
    open val dropCheckpoint: String get() = "DROP TABLE IF EXISTS ${ddl.migrate}"

    /**
     * Reads each uniqueness constraint the catalog records, `{"label", "properties"}`, the properties their JSON text, as
     * an earlier layout's records hold them: what a migration checks for keys that collide in this layout before its
     * indexes are dropped.
     */
    abstract val uniques: String

    /** Gives every stamp of the graph this layout, whatever it holds besides: the migration's finish, once every value is rewritten. */
    abstract val stampLayout: String

    /**
     * Takes the row lock a schema change takes on the lock table ([SqlStoreDdl.lock]), from a connection of the library's
     * own, held until the migration is done, so two migrations of one graph run one after the other. Null on SQLite, whose
     * library holds the database's write lock as it runs one.
     */
    abstract val lock: String?

    /** A table a migration rewrites the rows of. */
    enum class Table(val word: String) {
        NODES("nodes"),
        EDGES("edges"),
    }

    protected fun literal(text: String) = "'${text.replace("'", "''")}'"

    protected fun idOf(table: Table) = if (table == Table.NODES) "nid" else "eid"

    protected fun tableOf(table: Table) = if (table == Table.NODES) ddl.nodes else ddl.edges

    companion object {
        /** The statements of the store [ddl] makes. */
        fun of(ddl: SqlStoreDdl): MigrationStatements = when (ddl) {
            is PostgresDdl -> Postgres(ddl)
            is OracleDdl -> Oracle(ddl)
            is SqliteDdl -> Sqlite(ddl)
            else -> throw IllegalArgumentException("No migration on ${ddl.engine}.")
        }
    }

    /** Postgres's. */
    class Postgres(ddl: PostgresDdl) : MigrationStatements(ddl) {
        override val store = Store.POSTGRES

        override fun readBatch(table: Table): String {
            val labels = if (table == Table.NODES) "labels" else "jsonb_build_array(type)"
            return "SELECT jsonb_build_object('id', ${idOf(table)}, 'labels', $labels, 'props', props::text) FROM ${tableOf(table)}\n" +
                "WHERE ${idOf(table)} > CAST(\$after AS bigint) ORDER BY ${idOf(table)} LIMIT CAST(\$limit AS integer)"
        }

        override fun update(table: Table) =
            "UPDATE ${tableOf(table)} SET props = CAST(\$props AS jsonb) WHERE ${idOf(table)} = CAST(\$id AS bigint)"

        override val own =
            "SELECT jsonb_build_object('id', n.nid, 'props', n.props::text) FROM ${ddl.nodes} n WHERE ${StoreLayout.postgresOwn("n")} ORDER BY n.nid"

        override val sizes =
            "SELECT jsonb_build_object('nodes', (SELECT count(*) FROM ${ddl.nodes}), 'lastNode', (SELECT max(nid) FROM ${ddl.nodes}), " +
                "'edges', (SELECT count(*) FROM ${ddl.edges}), 'lastEdge', (SELECT max(eid) FROM ${ddl.edges}))"

        override val createCheckpoint = "CREATE TABLE IF NOT EXISTS ${ddl.migrate} (name text PRIMARY KEY, state text NOT NULL)"

        override val readCheckpoint = "SELECT jsonb_build_object('name', name, 'state', state) FROM ${ddl.migrate} ORDER BY name"

        override val writeCheckpoint =
            "INSERT INTO ${ddl.migrate} (name, state) VALUES (CAST(\$name AS text), CAST(\$state AS text))\n" +
                "ON CONFLICT (name) DO UPDATE SET state = EXCLUDED.state"

        override val lock = "SELECT name FROM ${ddl.lock} WHERE name = ${literal(ddl.lockRow)} FOR UPDATE"

        override val uniques =
            "SELECT jsonb_build_object('label', label, 'properties', properties) FROM ${ddl.catalog} WHERE kind = 'UNIQUENESS_CONSTRAINT' ORDER BY label, properties"

        override val stampLayout =
            "UPDATE ${ddl.nodes} n SET props = n.props || jsonb_build_object('layout', ${ValueLayout.LAYOUT}) WHERE ${PostgresSql.hasLabel("n", StoreStamp.LABEL)}"
    }

    /** Oracle's: a row's props read as extended JSON, so a binary double is told from a `NUMBER`, and written back so. */
    class Oracle(ddl: OracleDdl) : MigrationStatements(ddl) {
        override val store = Store.ORACLE

        override fun readBatch(table: Table): String {
            val labels = if (table == Table.NODES) "labels" else "JSON_ARRAY(type)"
            return "SELECT JSON_OBJECT('id' VALUE ${idOf(table)}, 'labels' VALUE $labels, 'props' VALUE JSON_SERIALIZE(props RETURNING CLOB EXTENDED) RETURNING JSON) FROM ${tableOf(table)}\n" +
                "WHERE ${idOf(table)} > \$after ORDER BY ${idOf(table)} FETCH FIRST \$limit ROWS ONLY"
        }

        override fun update(table: Table) = "UPDATE ${tableOf(table)} SET props = JSON(\$props EXTENDED) WHERE ${idOf(table)} = \$id"

        override val own =
            "SELECT JSON_OBJECT('id' VALUE n.nid, 'props' VALUE JSON_SERIALIZE(n.props RETURNING CLOB EXTENDED) RETURNING JSON) FROM ${ddl.nodes} n " +
                "WHERE ${StoreLayout.oracleOwn("n")} ORDER BY n.nid"

        override val sizes =
            "SELECT JSON_OBJECT('nodes' VALUE (SELECT COUNT(*) FROM ${ddl.nodes}), 'lastNode' VALUE (SELECT MAX(nid) FROM ${ddl.nodes}), " +
                "'edges' VALUE (SELECT COUNT(*) FROM ${ddl.edges}), 'lastEdge' VALUE (SELECT MAX(eid) FROM ${ddl.edges}) NULL ON NULL RETURNING JSON) FROM dual"

        override val createCheckpoint = "CREATE TABLE IF NOT EXISTS ${ddl.migrate} (name VARCHAR2(400) PRIMARY KEY, state CLOB NOT NULL)"

        override val readCheckpoint =
            "SELECT JSON_OBJECT('name' VALUE name, 'state' VALUE state RETURNING JSON) FROM ${ddl.migrate} ORDER BY name"

        override val writeCheckpoint =
            "MERGE INTO ${ddl.migrate} c USING (SELECT \$name AS name, TO_CLOB(\$state) AS state FROM dual) s ON (c.name = s.name)\n" +
                "WHEN MATCHED THEN UPDATE SET c.state = s.state WHEN NOT MATCHED THEN INSERT (name, state) VALUES (s.name, s.state)"

        override val lock = "SELECT name FROM ${ddl.lock} WHERE name = ${literal(ddl.lockRow)} FOR UPDATE WAIT 10"

        // PURGE, as the layout's own drops drop a table: dropped to the recycle bin, it would keep its name in use.
        override val dropCheckpoint = "DROP TABLE IF EXISTS ${ddl.migrate} PURGE"

        override val uniques =
            "SELECT JSON_OBJECT('label' VALUE label, 'properties' VALUE properties RETURNING JSON) FROM ${ddl.catalog} WHERE kind = 'UNIQUENESS_CONSTRAINT' ORDER BY label, properties"

        override val stampLayout =
            "UPDATE ${ddl.nodes} n SET n.props = JSON_TRANSFORM(n.props, SET '\$.layout' = ${ValueLayout.LAYOUT}) WHERE ${OracleSql.hasLabel("n", StoreStamp.LABEL)}"
    }

    /** SQLite's: each row given as JSON text, read as JSON ([SqliteDdl.JSON_ROWS]). */
    class Sqlite(ddl: SqliteDdl) : MigrationStatements(ddl) {
        override val store = Store.SQLITE

        override fun readBatch(table: Table): String {
            val labels = if (table == Table.NODES) "json(labels)" else "json_array(type)"
            return "${SqliteDdl.JSON_ROWS}\nSELECT json_object('id', ${idOf(table)}, 'labels', $labels, 'props', props) FROM ${tableOf(table)}\n" +
                "WHERE ${idOf(table)} > \$after ORDER BY ${idOf(table)} LIMIT \$limit"
        }

        override fun update(table: Table) = "UPDATE ${tableOf(table)} SET props = \$props WHERE ${idOf(table)} = \$id"

        override val own =
            "${SqliteDdl.JSON_ROWS}\nSELECT json_object('id', n.nid, 'props', n.props) FROM ${ddl.nodes} n WHERE ${StoreLayout.sqliteOwn("n")} ORDER BY n.nid"

        override val sizes =
            "${SqliteDdl.JSON_ROWS}\nSELECT json_object('nodes', (SELECT count(*) FROM ${ddl.nodes}), 'lastNode', (SELECT max(nid) FROM ${ddl.nodes}), " +
                "'edges', (SELECT count(*) FROM ${ddl.edges}), 'lastEdge', (SELECT max(eid) FROM ${ddl.edges}))"

        override val createCheckpoint = "CREATE TABLE IF NOT EXISTS ${ddl.migrate} (name TEXT PRIMARY KEY, state TEXT NOT NULL)"

        override val readCheckpoint = "${SqliteDdl.JSON_ROWS}\nSELECT json_object('name', name, 'state', state) FROM ${ddl.migrate} ORDER BY name"

        override val writeCheckpoint =
            "INSERT INTO ${ddl.migrate} (name, state) VALUES (\$name, \$state)\nON CONFLICT (name) DO UPDATE SET state = excluded.state"

        override val lock: String? = null

        override val uniques =
            "${SqliteDdl.JSON_ROWS}\nSELECT json_object('label', label, 'properties', properties) FROM ${ddl.catalog} WHERE kind = 'UNIQUENESS_CONSTRAINT' ORDER BY label, properties"

        override val stampLayout =
            "UPDATE ${ddl.nodes} SET props = json_set(props, '\$.layout', ${ValueLayout.LAYOUT}) WHERE nid IN (SELECT n.nid FROM ${ddl.nodes} n WHERE ${SqliteSql.hasLabel("n", StoreStamp.LABEL)})"
    }
}
