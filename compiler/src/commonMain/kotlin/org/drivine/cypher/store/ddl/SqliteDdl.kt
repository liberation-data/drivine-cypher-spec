package org.drivine.cypher.store.ddl

import org.drivine.DrivineInternalApi
import org.drivine.cypher.store.SqliteSql
import org.drivine.cypher.store.SqliteSql.hasLabel
import org.drivine.schema.ConstraintSpec
import org.drivine.schema.FullTextIndexSpec
import org.drivine.schema.IndexSpec
import org.drivine.schema.RangeIndexSpec
import org.drivine.schema.SchemaItemKind
import org.drivine.schema.SchemaItemSpec
import org.drivine.schema.TextIndexSpec
import org.drivine.schema.UniquenessConstraintSpec
import org.drivine.schema.VectorIndexSpec

/**
 * A graph as SQLite keeps it: the tables of a [SqlStoreDdl], with `labels`, `props` and a relationship's `props` as JSON
 * text, so that each library reads the same file as it is; and its indexes and constraints.
 *
 * There is no index over the labels: SQLite has none that answers whether a JSON array holds a value. A label is tested on
 * the text of the array ([SqliteSql.hasLabel]), which a partial index answers for the nodes of a label:
 *
 * - a range index is `((props read as text)) WHERE instr(labels, '"L"') > 0`, which a statement reads a property and
 *   tests a label as ([SqliteSql.propertyText]), so the planner sees the index holds the nodes it asks for;
 * - a uniqueness constraint is that index made `UNIQUE`. A node without the property reads it as null, which a unique index
 *   does not hold, so it is not constrained, as on the graph engines; and it finds a node as a range index does, so no range
 *   index is made beside it.
 *
 * A vector index and a full-text index are recorded, so that a schema that declares one is applied, and nothing is made for
 * them: a search measures every node it may find.
 *
 * Every statement here is safe to run again, so the store is made by running them all where [made] finds it is not all
 * there. A node's `nid` and a relationship's `eid` are `AUTOINCREMENT` keys, so an id is never given again once its row is
 * deleted, as Oracle's and Postgres's identity keys are never given again (drivine4j#713).
 */
@DrivineInternalApi
class SqliteDdl(name: String) : SqlStoreDdl(name, ENGINE, MAX_OBJECT_NAME) {

    /** What makes the store, when it is not there. */
    val ddl: List<String> = listOf(
        """
        CREATE TABLE IF NOT EXISTS $nodes (
            nid    INTEGER PRIMARY KEY AUTOINCREMENT,
            labels TEXT NOT NULL,
            props  TEXT NOT NULL,
            stamp  TEXT,
            words  TEXT
        )
        """,
        """
        CREATE TABLE IF NOT EXISTS $edges (
            eid   INTEGER PRIMARY KEY AUTOINCREMENT,
            src   INTEGER NOT NULL REFERENCES $nodes (nid) ON DELETE CASCADE,
            dst   INTEGER NOT NULL REFERENCES $nodes (nid) ON DELETE CASCADE,
            type  TEXT NOT NULL,
            props TEXT NOT NULL DEFAULT '{}'
        )
        """,
        """
        CREATE TABLE IF NOT EXISTS $catalog (
            kind        TEXT NOT NULL,
            label       TEXT NOT NULL,
            properties  TEXT NOT NULL,
            name        TEXT,
            object_name TEXT,
            details     TEXT NOT NULL,
            PRIMARY KEY (kind, label, properties)
        )
        """,
        "CREATE INDEX IF NOT EXISTS ${name}_edge_src ON $edges (src, type)",
        "CREATE INDEX IF NOT EXISTS ${name}_edge_dst ON $edges (dst, type)",
        """
        CREATE TABLE IF NOT EXISTS $counts (
            kind TEXT NOT NULL,
            name TEXT NOT NULL,
            n    INTEGER NOT NULL,
            PRIMARY KEY (kind, name)
        )
        """,
        """
        CREATE TABLE IF NOT EXISTS $countDeltas (
            id   INTEGER PRIMARY KEY AUTOINCREMENT,
            kind TEXT NOT NULL,
            name TEXT NOT NULL,
            d    INTEGER NOT NULL
        )
        """,
        "CREATE INDEX IF NOT EXISTS $countDeltasIndex ON $countDeltas (kind, name)",
        lockTable,
        lockSeed,
    ).map { it.trimIndent() }

    override val lockTable: String get() = "CREATE TABLE IF NOT EXISTS $lock (name TEXT PRIMARY KEY)"

    override val lockSeed: String get() = "INSERT OR IGNORE INTO $lock (name) VALUES ('$lockRow')"

    /**
     * Counts what of the [ddl] makes is there: each table and index, by its name in any case. A user who may only read the
     * file, or a connection with `query_only` set, is refused a `CREATE ... IF NOT EXISTS` even over a table that is there,
     * so the store is looked for first: where the count is the [ddl]'s size, nothing is made.
     *
     * The count is [REUSES_IDS] where the table of nodes or of relationships is there and was made without `AUTOINCREMENT`,
     * as SQLite keeps the statement that made it: one look finds both. It looks for `KEY AUTOINCREMENT`, so that a graph
     * whose name holds the word is not taken for one made with it. Such a store is refused, naming its file ([FILE]).
     */
    val made: String = run {
        // A statement that makes nothing, the lock table's row, is there where the rest are: the [ddl] made them together.
        val making = ddl.mapNotNull { Regex("IF NOT EXISTS (\\w+)").find(it)?.groupValues?.get(1)?.lowercase() }
        val names = making.joinToString(", ") { "'$it'" }
        val rows = ddl.size - making.size
        val keyed = listOf(nodes, edges).joinToString(", ") { "'${it.lowercase()}'" }
        "SELECT CASE WHEN EXISTS (SELECT 1 FROM sqlite_master WHERE type = 'table' AND lower(name) IN ($keyed) AND sql NOT LIKE '%KEY AUTOINCREMENT%')" +
            " THEN $REUSES_IDS ELSE COUNT(*)${if (rows > 0) " + $rows" else ""} END FROM sqlite_master WHERE lower(name) IN ($names)"
    }

    /** [text] as a schema statement, as drivine4j's grammar gave it. */
    private fun statement(text: String): String = text.trimIndent()

    /** [text] as schema statements, after those that make the store when it is not there. */
    private fun statements(vararg text: String): List<DdlStatement> = (ddl + text).map { DdlStatement.Text(statement(it)) }

    /**
     * Records [spec] as made, as [objectName] (null for one nothing was made for), unless it is recorded already. Two
     * processes recording it at once record it once.
     */
    private fun recorded(spec: SchemaItemSpec, objectName: String?, details: String = "{}"): String = """
        INSERT INTO $catalog (kind, label, properties, name, object_name, details)
        VALUES (${literal(spec.kind.name)}, ${literal(spec.label)}, ${literal(json(spec.properties))}, ${literal(spec.effectiveName)}, ${objectName?.let(::literal) ?: "NULL"}, ${literal(details)})
        ON CONFLICT (kind, label, properties) DO NOTHING
    """

    /**
     * Forgets [item], and drops the index it was made as. SQLite has no block to read the record's object in, so the index
     * is named again from the item's kind, label and properties, as this version names it. Nothing was made for a vector or
     * a full-text index, so nothing is dropped for one.
     */
    private fun forgotten(item: CatalogItem): List<DdlStatement> {
        val kind = when (item.kind) {
            SchemaItemKind.RANGE_INDEX -> RANGE
            SchemaItemKind.UNIQUENESS_CONSTRAINT -> UNIQUE
            else -> null
        }
        return listOfNotNull(
            kind?.let { statement("DROP INDEX IF EXISTS ${objectName(it, listOf(item.label) + item.properties)}") },
            statement("DELETE FROM $catalog WHERE ${recordOf(item)}"),
        ).map { DdlStatement.Text(it) }
    }

    override fun aliased(item: CatalogItem, aliases: List<String>): String {
        val details = if (aliases.isEmpty()) "json_remove(details, '$.$ALIASES')" else "json_set(details, '$.$ALIASES', json(${literal(aliasesJson(aliases))}))"
        return statement("UPDATE $catalog SET details = $details WHERE ${recordOf(item)}")
    }

    /**
     * The partial index over [properties] of the nodes of [label]: each property as a statement reads it, and the test of
     * the label a statement makes, both of the table's own columns.
     */
    private fun over(label: String, properties: List<String>): String =
        "$nodes (${properties.joinToString(", ") { "${SqliteSql.indexClass("props", SqliteSql.path(it))}, ${SqliteSql.indexKey("props", SqliteSql.path(it))}" }}) WHERE ${hasLabel("", label)}"

    override fun createIndex(spec: IndexSpec, existing: CatalogItem?): List<DdlStatement> = when (spec) {
        is RangeIndexSpec -> {
            val name = objectName(RANGE, listOf(spec.label) + spec.properties)
            statements("CREATE INDEX IF NOT EXISTS $name ON ${over(spec.label, spec.properties)}", recorded(spec, name))
        }
        // Recorded and not made: SQLite has no vector index of its own.
        is VectorIndexSpec -> statements(recorded(spec, null, vectorDetails(spec)))
        // Recorded and not made yet: a search reads every node of the label.
        is FullTextIndexSpec -> statements(recorded(spec, null, analyzerDetails(spec.analyzer)))
        is TextIndexSpec -> throw unsupportedTextIndex(spec, engine)
    }

    override fun dropIndex(item: CatalogItem): List<DdlStatement> = forgotten(item)

    override fun createConstraint(spec: ConstraintSpec): List<DdlStatement> = when (spec) {
        is UniquenessConstraintSpec -> {
            val name = objectName(UNIQUE, listOf(spec.label) + spec.properties)
            statements("CREATE UNIQUE INDEX IF NOT EXISTS $name ON ${over(spec.label, spec.properties)}", recorded(spec, name))
        }
    }

    override fun dropConstraint(item: CatalogItem): List<DdlStatement> = forgotten(item)

    /** What is recorded of [kinds], and still there: an index dropped by hand is not listed, so that it is made again when asked for. */
    private fun listed(kinds: String): String = """
        $JSON_ROWS
        SELECT json_object('kind', kind, 'label', label, 'properties', json(properties), 'name', name, 'details', json(details))
        FROM $catalog c
        WHERE kind IN ($kinds)
          AND (object_name IS NULL OR EXISTS (SELECT 1 FROM sqlite_master WHERE type = 'index' AND name = c.object_name))
        ORDER BY label, properties
    """.trimIndent()

    override fun listIndexesQuery(kind: SchemaItemKind): String = listed(literal(kind.name))

    override fun listConstraintsQuery(): String = listed(literal(SchemaItemKind.UNIQUENESS_CONSTRAINT.name))

    companion object {
        /** The store's name, for what is said of a graph name it cannot take. */
        const val ENGINE = "SQLite"

        /** SQLite takes a name of any length; this keeps one as long as the other stores' do. */
        const val MAX_OBJECT_NAME = 63

        /** What [made] gives for a store whose tables give a deleted row's id again. */
        const val REUSES_IDS = -1

        /** The file of the database a connection is on: an empty text for an in-memory or temporary one. */
        const val FILE = "SELECT file FROM pragma_database_list WHERE name = 'main'"

        /**
         * What a statement whose text values are JSON opens with: SQLite has no JSON type for a column to say so, so a
         * library reads a column of such a statement as JSON by it.
         */
        const val JSON_ROWS = "/* drivine:json */"

        /** What the name of a range index, and of a uniqueness constraint's index, says it is. */
        private const val RANGE = "ix"
        private const val UNIQUE = "uq"

        /** Why [name] cannot name a graph here; null where it can (see [SqlStoreDdl.refusal]). */
        fun refusal(name: String): String? = SqlStoreDdl.refusal(name, ENGINE, MAX_OBJECT_NAME)
    }
}
