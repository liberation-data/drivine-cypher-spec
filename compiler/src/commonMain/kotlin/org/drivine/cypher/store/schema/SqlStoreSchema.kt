package org.drivine.cypher.store.schema

import org.drivine.DrivineInternalApi
import org.drivine.cypher.store.OracleSql
import org.drivine.cypher.store.PostgresSql
import org.drivine.cypher.store.SqliteSql
import org.drivine.cypher.store.Stamps
import org.drivine.cypher.store.ddl.CatalogItem
import org.drivine.cypher.store.ddl.DdlStatement
import org.drivine.cypher.store.ddl.OracleDdl
import org.drivine.cypher.store.ddl.PostgresDdl
import org.drivine.cypher.store.ddl.SqlStoreDdl
import org.drivine.cypher.store.ddl.SqliteDdl
import org.drivine.schema.ConstraintSpec
import org.drivine.schema.SchemaItemInfo
import org.drivine.schema.SchemaItemKind
import org.drivine.schema.SchemaItemSpec
import org.drivine.schema.SimilarityFunction

/**
 * The schema of a SQL store: what Drivine made is read back from the graph's catalog, a record a row, as a map of its
 * `kind`, `label`, `properties`, `name` and `details` (and on Oracle its `status`), since an index's expression doesn't say
 * what it was made for. An index and a constraint are listed alike, and read alike.
 *
 * A record of a kind or a similarity this module doesn't know is refused, as drivine4j refuses it: the catalog is
 * Drivine's own, and such a record isn't one it wrote.
 */
@DrivineInternalApi
abstract class SqlStoreSchema internal constructor() : StoreSchema {

    abstract override val ddl: SqlStoreDdl

    override val supportsIfNotExists = true
    override val supportsNamedItems = true

    /** Whether a record's `status` is read: Oracle's, which lists a range index `INCOMPLETE`; the others list none. */
    protected open val readsStatus: Boolean
        get() = false

    override fun parseIndexRows(rows: List<Any?>): List<SchemaItemInfo> = rows.mapNotNull { row ->
        val map = row as? Map<*, *> ?: return@mapNotNull null
        // What the store has under a name Drivine gives an object, given beside the records ([formOf]).
        if (OBJECT in map) return@mapNotNull null
        val details = map["details"] as? Map<*, *>
        SchemaItemInfo(
            kind = SchemaItemKind.valueOf(map["kind"] as String),
            label = map["label"] as String,
            properties = (map["properties"] as List<*>).map { it.toString() },
            name = map["name"] as? String,
            dimensions = (details?.get("dimensions") as? Number)?.toInt(),
            similarity = (details?.get("similarity") as? String)?.let(SimilarityFunction::valueOf),
            analyzer = details?.get("analyzer") as? String,
            status = if (readsStatus) map["status"] as? String else null,
        )
    }

    override fun parseConstraintRows(rows: List<Any?>): List<SchemaItemInfo> = parseIndexRows(rows)

    // ----- Forms -----

    /**
     * What [rows] say of [spec]'s form. First, each object the store has under a name Drivine gives one of the item's
     * ([madeAs]) must be the object it would make: one of its kind, on the node table, as unique as it, over the item's
     * properties in order, and of its label's nodes alone where it is; otherwise the item is a [ItemForm.Mismatch], whatever
     * its record says. An object that isn't there is no mismatch: a record whose object is gone isn't listed, and one
     * that lacks part is made whole ([isIncomplete]).
     *
     * Then its record's `details.form`: [SqlStoreDdl.CURRENT_FORM] is current, and another form is [ItemForm.Stale]. A
     * record that says none is as [unrecordedForm] reads its objects.
     */
    override fun formOf(spec: SchemaItemSpec, rows: List<Any?>): ItemForm {
        val objects = StoreObject.read(rows)
        for (made in madeAs(spec)) {
            val there = objects[made.name.uppercase()] ?: continue
            // Where indexes are named apart from other objects, a name another object has is no index's.
            if (!there.isIndex && indexesNamedApart) continue
            mismatchOf(made, there)?.let { return ItemForm.Mismatch(there.name, it, droppable = there.isIndex) }
        }
        val record = rows.filterIsInstance<Map<*, *>>().firstOrNull { row ->
            OBJECT !in row && parseIndexRows(listOf(row)).singleOrNull()?.let { matchesIdentity(it, spec) } == true
        } ?: return ItemForm.Current
        return when (val form = ((record["details"] as? Map<*, *>)?.get(SqlStoreDdl.FORM) as? Number)?.toLong()) {
            SqlStoreDdl.CURRENT_FORM.toLong() -> ItemForm.Current
            null -> unrecordedForm(spec, record, objects)
            else -> ItemForm.Stale("form $form", SqlStoreDdl.CURRENT_FORM)
        }
    }

    /**
     * The form of [spec]'s item whose [record] says none, from [objects]: the module's, here, as on Postgres and SQLite the
     * three libraries made every item byte for byte alike before records said their form.
     */
    protected open fun unrecordedForm(spec: SchemaItemSpec, record: Map<*, *>, objects: Map<String, StoreObject>): ItemForm = ItemForm.Current

    override fun dropMismatched(name: String): List<DdlStatement> = listOf(DdlStatement.Text(ddl.droppedIndex(name)))

    /**
     * Whether the store names indexes apart from its other objects, as Oracle does: a table there of an index's name
     * doesn't keep the index from being made, and isn't taken for it.
     */
    protected open val indexesNamedApart: Boolean
        get() = false

    /** The objects the store makes [spec]'s item as, under the names Drivine gives them, and what each holds. */
    protected abstract fun madeAs(spec: SchemaItemSpec): List<MadeObject>

    /** What [made] would be, said of [there], what the store has under its name; null where it is that. */
    private fun mismatchOf(made: MadeObject, there: StoreObject): String? {
        if (!there.isIndex) return "${there.types.joinToString(" and ") { article(it.lowercase()) }}, not an index"
        if (there.table != null && !there.table.equals(ddl.nodes, ignoreCase = true)) return "an index on ${there.table}"
        if (there.unique != null && there.unique != made.unique) return if (there.unique) "a unique index" else "an index that is not unique"
        val properties = made.properties ?: return null
        val shape = shapeOf(there) ?: return null
        if (shape.columns.size != properties.size || shape.columns.zip(properties).any { (column, property) -> column != null && column != Read.Property(property) }) {
            return "an index over ${shape.columns.joinToString(", ", "[", "]") { it?.toString() ?: "?" }}"
        }
        // Each label it tests (none, for an index over every node) is the one it is made for.
        if (shape.labels.all { it == made.label }) return null
        return when (val other = shape.labels.first { it != made.label }) {
            null -> "an index over every node"
            else -> "an index over the nodes of $other"
        }
    }

    /** What [there]'s columns read, and the labels it is over; null where the store's text of it isn't read here. */
    protected abstract fun shapeOf(there: StoreObject): Shape?

    /**
     * An object the store makes an item as: an index under [name], [unique] or not, over [properties] in order (null where
     * its expressions aren't compared), of the nodes of [label] alone, or of every node where it is null.
     */
    protected data class MadeObject(val name: String, val unique: Boolean, val properties: List<String>?, val label: String?)

    /**
     * What an index reads: each column's [Read], in order, null where its text isn't read here; and for each test of a
     * label it makes, the label, null for a column that tests none, or for an index that tests none at all.
     */
    protected data class Shape(val columns: List<Read?>, val labels: List<String?>)

    /** What one column of an index reads. */
    protected sealed interface Read {
        /** The property [key] of a node. */
        data class Property(val key: String) : Read {
            override fun toString() = key
        }

        /** Something else: a column of the table, or another expression, as the store writes it. */
        data class Other(val text: String) : Read {
            override fun toString() = text
        }
    }

    companion object {
        /** The key of a listing's row that holds what the store has under a name Drivine gives, not a record. */
        const val OBJECT = "object"

        private fun article(type: String) = if (type.first() in "aeiou") "an $type" else "a $type"
    }
}

/**
 * What a SQL store has under one name, as its listing gives it beside the records, a row a type of object under the name:
 * [types], each type of object the name is taken by (an index, a table, a view; Oracle keeps indexes apart from its other
 * objects, so a name can be taken by an index and a table); and of the index, the [table] it is on, whether it is [unique],
 * the text of each of its [columns] in order, as the store writes them, its [predicate] (a partial index's), its [method]
 * (Postgres's), and the [statement] that made it (SQLite's).
 */
@DrivineInternalApi
data class StoreObject(
    val name: String,
    val types: List<String>,
    val table: String? = null,
    val unique: Boolean? = null,
    val columns: List<String?> = emptyList(),
    val predicate: String? = null,
    val method: String? = null,
    val statement: String? = null,
) {
    /** Whether the name is taken by an index. */
    val isIndex: Boolean
        get() = types.any { it.equals(INDEX, ignoreCase = true) }

    companion object {
        /** A type of object: an index, as each store names it (Postgres's `relkind` `i`, as `index`). */
        const val INDEX = "INDEX"

        /**
         * The objects [rows] give, by their names in upper case, as each store's listing gives them: `object`, the name;
         * `type`, the type; `table`; `unique`, a boolean, or Oracle's `uniqueness`, `UNIQUE` or `NONUNIQUE`; `columns`;
         * `where`; `method`; `sql`. The rows of a name are one object.
         */
        fun read(rows: List<Any?>): Map<String, StoreObject> {
            val objects = linkedMapOf<String, StoreObject>()
            for (row in rows) {
                val map = row as? Map<*, *> ?: continue
                val name = map[SqlStoreSchema.OBJECT] as? String ?: continue
                val type = when (val t = map["type"] as? String) {
                    "i", "I" -> INDEX
                    "r" -> "TABLE"
                    "v" -> "VIEW"
                    "m" -> "MATERIALIZED VIEW"
                    "S" -> "SEQUENCE"
                    else -> t?.uppercase() ?: "OBJECT"
                }
                val unique = (map["unique"] as? Boolean) ?: (map["unique"] as? Number)?.let { it.toLong() != 0L }
                    ?: (map["uniqueness"] as? String)?.let { it.equals("UNIQUE", ignoreCase = true) }
                val read = StoreObject(
                    name = name,
                    types = listOf(type),
                    table = map["table"] as? String,
                    unique = unique,
                    columns = (map["columns"] as? List<*>).orEmpty().map { it?.toString() },
                    predicate = map["where"] as? String,
                    method = map["method"] as? String,
                    statement = map["sql"] as? String,
                )
                val key = name.uppercase()
                val before = objects[key]
                objects[key] = when {
                    before == null -> read
                    // The index's facts, whichever row gives them; every type the name is taken by.
                    read.isIndex -> read.copy(types = (before.types + read.types).distinct())
                    else -> before.copy(types = (before.types + read.types).distinct())
                }
            }
            return objects
        }
    }
}

/** Postgres's schema: errors told by their SQL state. */
@DrivineInternalApi
class PostgresSchema(override val ddl: PostgresDdl) : SqlStoreSchema() {

    /** Each item is one index, of the label's nodes. A vector's and a full-text index's expressions aren't compared. */
    override fun madeAs(spec: SchemaItemSpec): List<MadeObject> {
        val parts = listOf(spec.label) + spec.properties
        return when (spec.kind) {
            SchemaItemKind.RANGE_INDEX -> listOf(MadeObject(ddl.objectName(PostgresDdl.RANGE, parts), false, spec.properties, spec.label))
            SchemaItemKind.UNIQUENESS_CONSTRAINT -> listOf(MadeObject(ddl.objectName(PostgresDdl.UNIQUE, parts), true, spec.properties, spec.label))
            SchemaItemKind.VECTOR_INDEX -> listOf(MadeObject(ddl.objectName(PostgresDdl.VECTOR, listOf(spec.label, spec.properties.single())), false, null, spec.label))
            SchemaItemKind.FULLTEXT_INDEX -> listOf(MadeObject(ddl.objectName(PostgresDdl.FULLTEXT, parts), false, null, spec.label))
            SchemaItemKind.TEXT_INDEX -> emptyList()
        }
    }

    /**
     * Postgres writes each column as `pg_get_indexdef` gives it, `(props ->> 'name'::text)`, or a column of the table,
     * `stamp`; and the predicate as `pg_get_expr` gives it, `(labels @> '["Person"]'::jsonb)`.
     */
    override fun shapeOf(there: StoreObject): Shape {
        val columns = there.columns.map { column ->
            val text = column?.let(SqlText::unwrapped)
            when {
                text == null -> null
                text == "stamp" -> Read.Property(Stamps.PROPERTY)
                else -> PROPERTY.matchEntire(text)?.let { Read.Property(SqlText.unquoted(it.groupValues[1])) } ?: Read.Other(text)
            }
        }
        val label = there.predicate?.let { LABEL.find(it) }?.let { SqlText.jsonLabel(SqlText.unquoted(it.groupValues[1])) }
        return Shape(columns, listOf(label))
    }

    private companion object {
        val PROPERTY = Regex("""props ->> '${SqlText.LITERAL}'::text""")
        val LABEL = Regex("""labels @> '${SqlText.LITERAL}'::jsonb""")
    }

    /** `23505`: a second row for a unique key, or a unique index made over rows that already repeat one. */
    override fun isViolation(error: StoreError): Boolean = error.causes.any { it.isSqlError && it.sqlState == "23505" }

    /** A node's element id is its `nid`; [limit] of a key's nodes at most are named, the first made first. */
    override fun sampleConflictsQuery(spec: ConstraintSpec, limit: Int): String {
        val keys = spec.properties.map { PostgresSql.property("n", it) }
        val entries = spec.properties.zip(keys).joinToString(", ") { (property, key) -> "${PostgresSql.literal(property)}, $key" }
        val node = listOfNotNull(
            "'elementId', n.nid",
            StoreSchema.sampledNodeId(spec)?.let { "${PostgresSql.literal(it)}, ${PostgresSql.property("n", it)}" },
        ).joinToString(", ")
        return """
            SELECT jsonb_build_object('properties', jsonb_build_object($entries), 'count', COUNT(*),
                                      'nodes', to_jsonb((array_agg(jsonb_build_object($node) ORDER BY n.nid))[1:$limit]))
            FROM ${ddl.nodes} n
            WHERE ${PostgresSql.hasLabel("n", spec.label)} AND ${keys.joinToString(" AND ") { "$it IS NOT NULL" }}
            GROUP BY ${keys.joinToString(", ")}
            HAVING COUNT(*) > 1
            LIMIT $limit
        """.trimIndent()
    }
}

/** Oracle's schema: errors told by their vendor code, and a range index listed `INCOMPLETE` made whole. */
@DrivineInternalApi
class OracleSchema(override val ddl: OracleDdl) : SqlStoreSchema() {

    override val readsStatus: Boolean
        get() = true

    override val indexesNamedApart: Boolean
        get() = true

    /**
     * A range index is the index over the property of every node (`ix`, of no label, which labels with a property of the
     * name share) and its labelled index (`lx`); a uniqueness constraint is its unique index (`uq`) and the range index's
     * shared one, which it makes too. A vector and a full-text index are tables beside the nodes, whose expressions aren't
     * compared.
     */
    override fun madeAs(spec: SchemaItemSpec): List<MadeObject> {
        val shared = MadeObject(ddl.objectName(OracleDdl.SHARED, spec.properties), false, spec.properties, null)
        return when (spec.kind) {
            SchemaItemKind.RANGE_INDEX -> listOf(shared, MadeObject(ddl.objectName(OracleDdl.LABELLED, listOf(spec.label) + spec.properties), false, spec.properties, spec.label))
            SchemaItemKind.UNIQUENESS_CONSTRAINT -> listOf(MadeObject(ddl.objectName(OracleDdl.UNIQUE, listOf(spec.label) + spec.properties), true, spec.properties, spec.label), shared)
            else -> emptyList()
        }
    }

    /**
     * Oracle gives each column of an index over an expression as the text of the hidden column it makes for it, as it
     * writes it again (`JSON_VALUE("PROPS" FORMAT OSON , '$.name' RETURNING VARCHAR2(4000) NULL ON ERROR TYPE(LAX) )`):
     * the property is the path of the `JSON_VALUE` of `PROPS` in it, `'$.name'` or `'$."name"'`, and the label the one the
     * `JSON_EXISTS2` of `LABELS` in it tests, `'$[*]?(@ == "Person")'` or `'$?(@ == "Person")'`. A column of the table is
     * its name, `STAMP`. Oracle keeps at most 4,000 characters of a column's text, and none of a longer one: a column it
     * gives none of, or whose property isn't read here, isn't compared.
     */
    override fun shapeOf(there: StoreObject): Shape {
        val columns = there.columns.map { column ->
            when {
                column == null -> null
                PLAIN_STEP.matches(column) -> if (column.equals("stamp", ignoreCase = true)) Read.Property(Stamps.PROPERTY) else Read.Other(column)
                else -> VALUE.find(column)?.let { pathKey(SqlText.unquoted(it.groupValues[1])) }?.let { Read.Property(it) }
            }
        }
        val labels = there.columns.filterNotNull().map { column -> LABEL_TEST.find(column)?.let { labelOf(SqlText.unquoted(it.groupValues[1])) } }
        return Shape(columns, labels)
    }

    /**
     * The form of an item whose record says none, from its labelled or unique index's expressions: drivine4j's, which
     * tests a label as `'$[*]?(@ == …)'`, is this version's, and its form is recorded; Drivine for TypeScript's and
     * drivine-python's, which test it as `'$?(@ == …)'` and read a property as `'$."p"'`, is another, kept as it is. What
     * neither is, or an item with neither index, is taken as this version's, and left as it is.
     */
    override fun unrecordedForm(spec: SchemaItemSpec, record: Map<*, *>, objects: Map<String, StoreObject>): ItemForm {
        val kind = if (spec.kind == SchemaItemKind.UNIQUENESS_CONSTRAINT) OracleDdl.UNIQUE else OracleDdl.LABELLED
        val there = objects[ddl.objectName(kind, listOf(spec.label) + spec.properties).uppercase()]?.takeIf { it.isIndex } ?: return ItemForm.Current
        val paths = there.columns.filterNotNull().mapNotNull { column -> LABEL_TEST.find(column)?.let { SqlText.unquoted(it.groupValues[1]) } }
        if (paths.isEmpty()) return ItemForm.Current
        return when {
            paths.all { it.startsWith(LIBRARIES_LABEL_TEST) } -> ItemForm.Stale(LIBRARIES_FORM, SqlStoreDdl.CURRENT_FORM)
            paths.all { it.startsWith(MODULES_LABEL_TEST) } -> {
                val properties = (record["properties"] as List<*>).map { it.toString() }
                ItemForm.Unrecorded(ddl.formRecorded(CatalogItem(spec.kind, spec.label, properties)))
            }
            else -> ItemForm.Current
        }
    }

    private fun pathKey(path: String): String? = when {
        path.startsWith("$.\"") -> SqlText.jsonString(path.removePrefix("$."))
        path.startsWith("$.") -> path.removePrefix("$.").takeIf { PLAIN_STEP.matches(it) }
        else -> null
    }

    private fun labelOf(path: String): String? = LABEL_PATH.matchEntire(path)?.let { SqlText.jsonString(it.groupValues[1]) }

    private companion object {
        val VALUE = Regex("""JSON_VALUE\(\s*"?PROPS"?[^']*'${SqlText.LITERAL}'""", RegexOption.IGNORE_CASE)
        val LABEL_TEST = Regex("""JSON_EXISTS2?\(\s*"?LABELS"?[^']*'${SqlText.LITERAL}'""", RegexOption.IGNORE_CASE)
        val LABEL_PATH = Regex("""\$(?:\[\*])?\?\(@ == ("(?:[^"\\]|\\.)*")\)""")
        val PLAIN_STEP = Regex("[A-Za-z_][A-Za-z0-9_]*")

        /** How drivine4j, and the module, test a label in an index: every item of the labels. */
        const val MODULES_LABEL_TEST = "$[*]?("

        /** How Drivine for TypeScript and drivine-python test a label in an index: the labels' array itself. */
        const val LIBRARIES_LABEL_TEST = "$?("

        /** What a stale form's line names the libraries' Oracle form as. */
        const val LIBRARIES_FORM = "TS's or Python's Oracle form"

        val VIOLATIONS = setOf(1, 1452)
    }

    /**
     * A range index made before there were labelled indexes, or whose labelled index was dropped, with no uniqueness
     * constraint's index to stand for it: the listing gives it [OracleDdl.INCOMPLETE], and an ensure makes the rest.
     */
    override fun isIncomplete(existing: SchemaItemInfo): Boolean = existing.status == OracleDdl.INCOMPLETE

    /** `ORA-00001`, a second row for a unique key; `ORA-01452`, a unique index over rows that already repeat one. */
    override fun isViolation(error: StoreError): Boolean = error.causes.any { it.isSqlError && it.vendorCode in VIOLATIONS }

    /**
     * Each node of a key that repeats is numbered within its key, and counted with it, so that a row names [limit] of its
     * nodes at most however many hold the key. A node's element id is its `nid`.
     */
    override fun sampleConflictsQuery(spec: ConstraintSpec, limit: Int): String {
        val keys = spec.properties.map { "JSON_VALUE(n.props, ${OracleSql.path(it)})" }
        val aliases = keys.indices.map { "k$it" }
        val entries = spec.properties.zip(aliases).joinToString(", ") { (property, key) -> "${OracleSql.literal(property)} VALUE $key" }
        val nodeId = StoreSchema.sampledNodeId(spec)
        val node = listOfNotNull("'elementId' VALUE nid", nodeId?.let { "${OracleSql.literal(it)} VALUE i" }).joinToString(", ")
        val selected = (keys.zip(aliases).map { (key, alias) -> "$key AS $alias" } + listOfNotNull(nodeId?.let { "JSON_VALUE(n.props, ${OracleSql.path(it)}) AS i" }))
            .joinToString(", ")
        val partition = keys.joinToString(", ")
        return """
            SELECT JSON_OBJECT('properties' VALUE JSON_OBJECT($entries), 'count' VALUE MAX(c),
                               'nodes' VALUE JSON_ARRAYAGG(JSON_OBJECT($node) ORDER BY nid RETURNING JSON) RETURNING JSON)
            FROM (SELECT n.nid, $selected, COUNT(*) OVER (PARTITION BY $partition) AS c, ROW_NUMBER() OVER (PARTITION BY $partition ORDER BY n.nid) AS r
                  FROM ${ddl.nodes} n
                  WHERE ${OracleSql.hasLabel("n", spec.label)} AND ${keys.joinToString(" AND ") { "$it IS NOT NULL" }})
            WHERE c > 1 AND r <= $limit
            GROUP BY ${aliases.joinToString(", ")}
            FETCH FIRST $limit ROWS ONLY
        """.trimIndent()
    }

}

/** SQLite's schema: errors told by their message, as its driver gives one code for every constraint. */
@DrivineInternalApi
class SqliteSchema(override val ddl: SqliteDdl) : SqlStoreSchema() {

    /** A range index and a uniqueness constraint are one index each, of the label's nodes; a vector and a full-text index are recorded and not made. */
    override fun madeAs(spec: SchemaItemSpec): List<MadeObject> {
        val parts = listOf(spec.label) + spec.properties
        return when (spec.kind) {
            SchemaItemKind.RANGE_INDEX -> listOf(MadeObject(ddl.objectName(SqliteDdl.RANGE, parts), false, spec.properties, spec.label))
            SchemaItemKind.UNIQUENESS_CONSTRAINT -> listOf(MadeObject(ddl.objectName(SqliteDdl.UNIQUE, parts), true, spec.properties, spec.label))
            else -> emptyList()
        }
    }

    /**
     * SQLite keeps the statement that made an index as it was written: each column is the property's text
     * (`SqliteSql.propertyText`), read by its path from the first `json_type(props, '$."name"')` in it, or a column of the
     * table, `stamp`; the predicate tests the label as `instr(labels, '"Person"') > 0`.
     */
    override fun shapeOf(there: StoreObject): Shape? {
        val statement = there.statement ?: return null
        val on = ON.find(statement) ?: return null
        val open = on.range.last
        val close = SqlText.closing(statement, open) ?: return null
        val columns = SqlText.split(statement.substring(open + 1, close)).map { column ->
            val text = SqlText.unwrapped(column)
            when {
                text == "stamp" -> Read.Property(Stamps.PROPERTY)
                else -> PATH.find(text)?.let { path -> SqlText.jsonString(SqlText.unquoted(path.groupValues[1]).removePrefix("$."))?.let { Read.Property(it) } }
                    ?: Read.Other(column.trim())
            }
        }
        val label = LABEL.find(statement.substring(close + 1))?.let { SqlText.jsonString(SqlText.unquoted(it.groupValues[1])) }
        return Shape(columns, listOf(label))
    }

    private companion object {
        val ON = Regex("""\sON\s+\S+\s*\(""", RegexOption.IGNORE_CASE)
        val PATH = Regex("""json_type\(props, '${SqlText.LITERAL}'\)""")
        val LABEL = Regex("""instr\(labels, '${SqlText.LITERAL}'\) > 0""")
    }

    /**
     * A second row for a unique key, or a unique index made over rows that already repeat one: `SQLITE_CONSTRAINT_UNIQUE`,
     * said in a driver's error's own message.
     */
    override fun isViolation(error: StoreError): Boolean =
        error.causes.any { it.isSqlError && it.message.orEmpty().contains("UNIQUE constraint failed") }

    /** A node's element id is its `nid`; [limit] of a key's nodes at most are named, the first made first. Its rows are JSON ([SqliteDdl.JSON_ROWS]). */
    override fun sampleConflictsQuery(spec: ConstraintSpec, limit: Int): String {
        val keys = spec.properties.map { SqliteSql.property("n", it) }
        val columns = keys.mapIndexed { i, key -> "$key AS k$i" }.joinToString(", ")
        val grouping = keys.indices.joinToString(", ") { "k$it" }
        val entries = spec.properties.mapIndexed { i, property -> "${SqliteSql.literal(property)}, k.k$i" }.joinToString(", ")
        val sampled = StoreSchema.sampledNodeId(spec)
        val node = listOfNotNull("'elementId', k.nid", sampled?.let { "${SqliteSql.literal(it)}, k.sampled" }).joinToString(", ")
        return """
            ${SqliteDdl.JSON_ROWS}
            SELECT json_object('properties', json_object($entries), 'count', MAX(k.total),
                               'nodes', json_group_array(json_object($node) ORDER BY k.nid) FILTER (WHERE k.position <= $limit))
            FROM (
                SELECT n.nid, $columns${sampled?.let { ", ${SqliteSql.property("n", it)} AS sampled" }.orEmpty()},
                       row_number() OVER (PARTITION BY ${keys.joinToString(", ")} ORDER BY n.nid) AS position,
                       COUNT(*) OVER (PARTITION BY ${keys.joinToString(", ")}) AS total
                FROM ${ddl.nodes} n
                WHERE ${SqliteSql.hasLabel("n", spec.label)} AND ${keys.joinToString(" AND ") { "$it IS NOT NULL" }}
            ) k
            WHERE k.total > 1
            GROUP BY $grouping
            LIMIT $limit
        """.trimIndent()
    }
}
