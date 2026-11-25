package org.drivine.cypher.store.bulk

import org.drivine.DrivineInternalApi
import org.drivine.cypher.store.PostgresSql
import org.drivine.cypher.store.ValueLayout
import org.drivine.cypher.store.ddl.PostgresDdl
import org.drivine.cypher.store.ddl.SqlStoreDdl

/**
 * The statements a bulk import of nodes and relationships runs on a SQL store, each the module's own, as `bulk.yaml` pins them
 * for the graph `drivine` (liberation-data/drivine4j#684). A library runs them, in the order each statement's KDoc gives, in
 * one transaction, and writes the rows into the staging tables through the store's own bulk path (`COPY` on Postgres).
 *
 * The import is all or nothing. It refuses a node whose key the graph or the import holds already, and a relationship
 * whose end is in neither. Its rows are those a save writes:
 * - a node's labels, props and stamp, as the library writes them: each value as `StoreValues.text` gives it, nulls left out,
 *   keys [ValueLayout.escape]d, and a stamp of its own;
 * - the lowercased copies of the properties the library keeps them of, written by the graph's own `<graph>_lowered`;
 * - a relationship's type and props, made as `CREATE` makes one, never merged with another between the same nodes.
 *
 * A node already in the graph that gains a relationship gets a new second token in its stamp, as a save gives it.
 *
 * The import holds the graph alone while it runs. It takes the lock row a schema change takes ([lock]), and locks the node and
 * edge tables against other writers ([lockTables]): reads go on, writes wait for it. On Postgres it then drops the edge table's
 * two foreign keys and its two indexes ([defer]), and makes them again once the relationships are in ([rebuild]), as
 * [PostgresDdl] made them. That keeps the cost of each relationship flat however large the graph: checked one by one, the
 * foreign keys cost a write to the node table's pages for each relationship once that table outgrows the cache. Made again at
 * the end, they check every relationship, so a dangling one fails the import there.
 */
@DrivineInternalApi
sealed class BulkImportStatements(val ddl: SqlStoreDdl) {

    /** A node's label and the property its relationships find it by, as the import names an end. */
    data class End(val label: String, val key: String)

    companion object {
        /** The statements of the store [ddl] makes. */
        fun of(ddl: SqlStoreDdl): BulkImportStatements = when (ddl) {
            is PostgresDdl -> Postgres(ddl)
            else -> throw IllegalArgumentException("No bulk import on ${ddl.engine} yet: it is made for Postgres first.")
        }
    }

    /**
     * Postgres's. The staging tables are temporary, dropped at the commit, so an import leaves none behind whatever ends it.
     * A statement binds its parameters by name, `$mark`, as the store's other statements of the module do.
     */
    class Postgres(private val pg: PostgresDdl) : BulkImportStatements(pg) {

        /** The staging table of the nodes of one call, written through [copyNodes]: each node's key, labels, props and stamp. */
        val nodeStage = "${pg.name}_bulk_node"

        /** The staging table of the relationships of one call, written through [copyEdges]: each one's ends' keys and props. */
        val edgeStage = "${pg.name}_bulk_edge"

        /** Each node's id by its label and key: the nodes the import made, and those of the graph its relationships named. */
        val keys = "${pg.name}_bulk_key"

        /** First: the row lock a schema change takes, so none runs while the edge table is without its foreign keys. */
        val lock: String = "SELECT name FROM ${pg.lock} WHERE name = ${PostgresSql.literal(pg.lockRow)} FOR UPDATE"

        /** Second: other writers wait for the import's commit; reads go on, until [defer] drops the edge table's objects. */
        val lockTables: String = "LOCK TABLE ${pg.nodes}, ${pg.edges} IN SHARE ROW EXCLUSIVE MODE"

        /** Third: the staging tables, dropped at the commit. */
        val createStages: List<String> = listOf(
            "CREATE TEMP TABLE $nodeStage (key jsonb NOT NULL, labels jsonb NOT NULL, props jsonb NOT NULL, stamp varchar(33) NOT NULL) ON COMMIT DROP",
            "CREATE TEMP TABLE $edgeStage (src jsonb NOT NULL, dst jsonb NOT NULL, props jsonb NOT NULL) ON COMMIT DROP",
            "CREATE TEMP TABLE $keys (label text NOT NULL, key jsonb NOT NULL, nid bigint NOT NULL, made boolean NOT NULL, PRIMARY KEY (label, key)) ON COMMIT DROP",
        )

        /**
         * Before the first relationship: the edge table's foreign keys and its two indexes dropped. The drop takes `ACCESS
         * EXCLUSIVE` on the edge table, so reads of relationships wait for the commit too. A store that hasn't them all, as
         * the layout makes them, refuses the import here.
         */
        val defer: List<String> = pg.edgeForeignKeys.values.map { "ALTER TABLE ${pg.edges} DROP CONSTRAINT $it" } +
            listOf("src", "dst").map { "DROP INDEX ${pg.name}_edge_$it" }

        /**
         * The nodes of one call written into [nodeStage]: CSV of the key's JSON, the labels' JSON array, the props' JSON
         * object and the stamp. [nodeStage] is emptied after each call ([clearNodes]).
         */
        val copyNodes: String = "COPY $nodeStage (key, labels, props, stamp) FROM STDIN WITH (FORMAT csv)"

        /**
         * The keys among the staged nodes of [label] under [key] that the graph or the import holds already, or that the call
         * gives twice, at most ten, each `{"key": <its JSON>}`: none, or the import is refused.
         */
        fun takenKeys(label: String, key: String): String {
            val value = PostgresSql.value("n", ValueLayout.escape(key))
            return "SELECT jsonb_build_object('key', k.key) FROM (\n" +
                "SELECT s.key FROM $nodeStage s GROUP BY s.key HAVING count(*) > 1\n" +
                "UNION SELECT s.key FROM $nodeStage s JOIN ${pg.nodes} n ON $value = s.key AND ${PostgresSql.hasLabel("n", label)}\n" +
                "UNION SELECT s.key FROM $nodeStage s JOIN $keys m ON m.label = ${PostgresSql.literal(label)} AND m.key = s.key\n" +
                ") k LIMIT 10"
        }

        /**
         * The staged nodes made, with the lowercased copy of each property `$lower` names (a JSON array of keys, as the node
         * holds them), and each kept in [keys] under [label] and its [key], as its props hold it.
         */
        fun makeNodes(label: String, key: String): String =
            "WITH made AS (\n" +
                "INSERT INTO ${pg.nodes} (labels, props, stamp)\n" +
                "SELECT s.labels, ${pg.name}_lowered(s.props, CAST(\$lower AS jsonb)), s.stamp FROM $nodeStage s\n" +
                "RETURNING nid, ${PostgresSql.value("", ValueLayout.escape(key))} AS key\n" +
                ")\n" +
                "INSERT INTO $keys (label, key, nid, made) SELECT ${PostgresSql.literal(label)}, made.key, made.nid, true FROM made"

        /** After each call of nodes: [nodeStage] emptied. */
        val clearNodes: String = "TRUNCATE $nodeStage"

        /**
         * The relationships of one call written into [edgeStage]: CSV of the start's key's JSON, the end's and the props' JSON
         * object. [edgeStage] is emptied after each call ([clearEdges]).
         */
        val copyEdges: String = "COPY $edgeStage (src, dst, props) FROM STDIN WITH (FORMAT csv)"

        /**
         * The nodes of the graph the staged relationships name at their [column] end, `src` or `dst`, that the import didn't
         * make, kept in [keys]. First, [ambiguousKeys]: two nodes of a label with one key are no end.
         */
        fun findEnds(end: End, column: String): String {
            val value = PostgresSql.value("n", ValueLayout.escape(end.key))
            return "INSERT INTO $keys (label, key, nid, made)\n" +
                "SELECT ${PostgresSql.literal(end.label)}, $value, n.nid, false FROM ${pg.nodes} n\n" +
                "WHERE ${PostgresSql.hasLabel("n", end.label)} AND $value IN (SELECT s.${endColumn(column)} FROM $edgeStage s)\n" +
                "ON CONFLICT (label, key) DO NOTHING"
        }

        /**
         * The keys the staged relationships name at their [column] end that two nodes of the graph's [End.label] hold, at
         * most ten, each `{"key": <its JSON>}`: none, or the import is refused, as such a key names no one node.
         */
        fun ambiguousKeys(end: End, column: String): String {
            val value = PostgresSql.value("n", ValueLayout.escape(end.key))
            return "SELECT jsonb_build_object('key', $value) FROM ${pg.nodes} n\n" +
                "WHERE ${PostgresSql.hasLabel("n", end.label)} AND $value IN (SELECT s.${endColumn(column)} FROM $edgeStage s)\n" +
                "GROUP BY $value HAVING count(*) > 1 LIMIT 10"
        }

        /**
         * The staged relationships whose start or end is no node, at most ten, each `{"src": <its key>, "dst": <its key>}`:
         * none, or the import is refused.
         */
        fun missingEnds(from: End, to: End): String =
            "SELECT jsonb_build_object('src', s.src, 'dst', s.dst) FROM $edgeStage s\n" +
                "LEFT JOIN $keys a ON a.label = ${PostgresSql.literal(from.label)} AND a.key = s.src\n" +
                "LEFT JOIN $keys b ON b.label = ${PostgresSql.literal(to.label)} AND b.key = s.dst\n" +
                "WHERE a.nid IS NULL OR b.nid IS NULL LIMIT 10"

        /** The staged relationships made, of the type `$type`, from [from]'s node to [to]'s. */
        fun makeEdges(from: End, to: End): String =
            "INSERT INTO ${pg.edges} (src, dst, type, props)\n" +
                "SELECT a.nid, b.nid, CAST(\$type AS text), s.props FROM $edgeStage s\n" +
                "JOIN $keys a ON a.label = ${PostgresSql.literal(from.label)} AND a.key = s.src\n" +
                "JOIN $keys b ON b.label = ${PostgresSql.literal(to.label)} AND b.key = s.dst"

        /**
         * Each node the import didn't make that a staged relationship starts or ends at, its stamp's second token `$mark`'s,
         * as a save gives a node whose relationships it changed: a stamp that is not two tokens is replaced whole.
         */
        fun relink(from: End, to: End): String {
            val twoTokens = "CASE WHEN length(n.stamp) = 33 AND substr(n.stamp, 17, 1) = ':' THEN n.stamp END"
            return "UPDATE ${pg.nodes} n SET stamp = COALESCE(substr($twoTokens, 1, 17), substr(CAST(\$mark AS text), 1, 17)) || substr(CAST(\$mark AS text), 18)\n" +
                "FROM (\n" +
                "SELECT a.nid FROM $edgeStage s JOIN $keys a ON a.label = ${PostgresSql.literal(from.label)} AND a.key = s.src AND NOT a.made\n" +
                "UNION SELECT b.nid FROM $edgeStage s JOIN $keys b ON b.label = ${PostgresSql.literal(to.label)} AND b.key = s.dst AND NOT b.made\n" +
                ") r WHERE n.nid = r.nid"
        }

        /** After each call of relationships: [edgeStage] emptied. */
        val clearEdges: String = "TRUNCATE $edgeStage"

        /**
         * Last, where [defer] ran: the edge table's indexes and foreign keys made again, as [PostgresDdl] makes them, with
         * [maintenanceMegabytes] for their sorts, and both tables analysed. Adding a foreign key checks every relationship.
         */
        fun rebuild(maintenanceMegabytes: Int): List<String> {
            require(maintenanceMegabytes > 0) { "maintenance_work_mem is $maintenanceMegabytes MB: more than 0." }
            return listOf("SET LOCAL maintenance_work_mem = '${maintenanceMegabytes}MB'") + pg.edgeIndexes +
                pg.edgeForeignKeys.map { (column, name) ->
                    "ALTER TABLE ${pg.edges} ADD CONSTRAINT $name FOREIGN KEY ($column) REFERENCES ${pg.nodes} (nid) ON DELETE CASCADE"
                } + analyze
        }

        /** Last, where [defer] didn't run, as an import of nodes alone: both tables analysed. */
        val analyze: List<String> = listOf("ANALYZE ${pg.nodes}", "ANALYZE ${pg.edges}")

        private fun endColumn(column: String): String {
            require(column == "src" || column == "dst") { "An end is src or dst, not $column." }
            return column
        }
    }
}
