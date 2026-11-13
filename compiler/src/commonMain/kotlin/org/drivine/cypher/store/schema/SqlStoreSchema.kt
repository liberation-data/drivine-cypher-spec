package org.drivine.cypher.store.schema

import org.drivine.DrivineInternalApi
import org.drivine.cypher.store.OracleSql
import org.drivine.cypher.store.PostgresSql
import org.drivine.cypher.store.SqliteFunctions
import org.drivine.cypher.store.SqliteSql
import org.drivine.cypher.store.Stamps
import org.drivine.cypher.store.ddl.CatalogItem
import org.drivine.cypher.store.ddl.DdlStatement
import org.drivine.cypher.store.ddl.OracleDdl
import org.drivine.cypher.store.ddl.PostgresDdl
import org.drivine.cypher.store.ddl.SqlStoreDdl
import org.drivine.cypher.store.ValueLayout
import org.drivine.cypher.store.ddl.SqliteDdl
import org.drivine.schema.ConstraintSpec
import org.drivine.schema.SchemaItemInfo
import org.drivine.schema.SchemaItemKind
import org.drivine.schema.SchemaItemSpec
import org.drivine.schema.SimilarityFunction
import org.drivine.schema.VectorIndexSpec
import org.drivine.schema.made

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
     * its record says. Where the record says a later form, only the structure is compared ([mismatchOf]'s `structureOnly`),
     * and of the side objects only a side table's index ([otherMismatch]'s `structureOnly`). An object that isn't there is no mismatch: a record whose object is gone isn't listed, and one
     * that lacks part ([isIncomplete]) is made whole where it is of this version's form, and kept as it is where it is of another.
     *
     * Then its record's `details.form`: [SqlStoreDdl.CURRENT_FORM] is current, a later one [ItemForm.Newer], and an earlier
     * one [ItemForm.Stale]. An item no record lists, or whose record says no form, is current: since store layout 2 a store
     * holds only items the module made (see the README's The store layout).
     */
    override fun formOf(spec: SchemaItemSpec, rows: List<Any?>): ItemForm {
        val objects = StoreObject.read(rows)
        val record = recordOf(spec, rows)
        val form = formRecorded(record)
        // A newer version's item is made in a form this version may not read: the record is trusted so long as what stands
        // under each name is of its structure, and its expressions aren't compared, so an older version in a rolling deploy
        // never drops a newer one's index, nor reports it drift, for what it can't read.
        val newer = form != null && form > SqlStoreDdl.CURRENT_FORM
        objectMismatches(spec, objects, newer).firstOrNull()?.let { return it }
        otherMismatch(spec, objects, structureOnly = newer)?.let { return it }
        return when (form) {
            SqlStoreDdl.CURRENT_FORM.toLong(), null -> ItemForm.Current
            // A newer version's: what this version would make of it is older.
            in (SqlStoreDdl.CURRENT_FORM.toLong() + 1)..Long.MAX_VALUE -> ItemForm.Newer(form.toInt(), SqlStoreDdl.CURRENT_FORM)
            else -> ItemForm.Stale("form $form", SqlStoreDdl.CURRENT_FORM)
        }
    }

    /**
     * Each index the store has under a name Drivine gives one of [spec]'s objects ([madeAs]) that isn't the object it is
     * made as, in [madeAs]'s order: what [formOf] reads first of all, and gives the first of. A uniqueness constraint made
     * again drops each but its unique index's, which it builds anew before it drops (#135 §4).
     */
    fun mismatchesOf(spec: SchemaItemSpec, rows: List<Any?>): List<ItemForm.Mismatch> {
        val form = formRecorded(recordOf(spec, rows))
        return objectMismatches(spec, StoreObject.read(rows), newer = form != null && form > SqlStoreDdl.CURRENT_FORM)
    }

    private fun objectMismatches(spec: SchemaItemSpec, objects: StoreObjects, newer: Boolean): List<ItemForm.Mismatch> =
        madeAs(spec).mapNotNull { made ->
            val there = objects[made.name] ?: return@mapNotNull null
            // Where indexes are named apart from other objects, a name another object has is no index's.
            if (!there.isIndex && indexesNamedApart) return@mapNotNull null
            mismatchOf(made, there, structureOnly = newer)?.let { ItemForm.Mismatch(there.name, it, droppable = there.isIndex) }
        }

    /** Whether [rows] list an index under [temporary], the name a recreate builds a unique index under first. */
    fun leftover(rows: List<Any?>, temporary: String): Boolean = StoreObject.read(rows)[temporary]?.isIndex == true

    /**
     * Whether [rows] show a recreate of the uniqueness constraint [spec] cut off after its old index was dropped
     * ([org.drivine.cypher.store.ddl.UniqueRecreation.finish]): no index under [name], and under [temporary] a unique index
     * of the constraint's shape, as [name]'s would be; and no index under [recorded], the name the record found names its
     * own index by where it lists the properties in another order than [spec] (whose index then still stands, the
     * recreate cut off before its swap).
     */
    fun cutOff(spec: SchemaItemSpec, rows: List<Any?>, name: String, temporary: String, recorded: String? = null): Boolean {
        val objects = StoreObject.read(rows)
        objects[name]?.let { standing -> if (standing.isIndex || !indexesNamedApart) return false }
        if (recorded != null && objects[recorded]?.isIndex == true) return false
        val there = objects[temporary]?.takeIf { it.isIndex && it.unique == true } ?: return false
        val made = madeAs(spec).firstOrNull { it.name.equals(name, ignoreCase = true) } ?: return false
        return mismatchOf(made.copy(name = temporary), there, structureOnly = false) == null
    }

    /** The record [rows] list of [spec]'s item; null where they list none. */
    private fun recordOf(spec: SchemaItemSpec, rows: List<Any?>): Map<*, *>? = rows.filterIsInstance<Map<*, *>>().firstOrNull { row ->
        OBJECT !in row && parseIndexRows(listOf(row)).singleOrNull()?.let { matchesIdentity(it, spec) } == true
    }

    /** The form [record] says its item was made in; null where it says none. */
    private fun formRecorded(record: Map<*, *>?): Long? = ((record?.get("details") as? Map<*, *>)?.get(SqlStoreDdl.FORM) as? Number)?.toLong()

    override fun dropMismatched(name: String): List<DdlStatement> = listOf(DdlStatement.Text(ddl.droppedIndex(name)))

    /**
     * Whether the store names indexes apart from its other objects, as Oracle does: a table there of an index's name
     * doesn't keep the index from being made, and isn't taken for it.
     */
    protected open val indexesNamedApart: Boolean
        get() = false

    /**
     * What else the store has under a name Drivine gives one of [spec]'s objects that isn't the item, where an item is made
     * as more than indexes (Oracle's tables beside the nodes); null where nothing is.
     */
    protected open fun otherMismatch(spec: SchemaItemSpec, objects: StoreObjects, structureOnly: Boolean): ItemForm.Mismatch? = null

    /** The objects the store makes [spec]'s item as, under the names Drivine gives them, and what each holds. */
    protected abstract fun madeAs(spec: SchemaItemSpec): List<MadeObject>

    /**
     * What [made] would be, said of [there], what the store has under its name; null where it is that. With
     * [structureOnly], only what the store says of it without its expressions being read: that it is an index, on the
     * node table, as unique as [made], by its access method.
     */
    private fun mismatchOf(made: MadeObject, there: StoreObject, structureOnly: Boolean): String? {
        if (!there.isIndex) return "${there.types.joinToString(" and ") { article(it.lowercase()) }}, not an index"
        if (there.table != null && !there.table.equals(ddl.nodes, ignoreCase = true)) return "an index on ${there.table}"
        if (there.unique != null && there.unique != made.unique) return if (there.unique) "a unique index" else "an index that is not unique"
        if (made.method != null && there.method != null && !there.method.equals(made.method, ignoreCase = true)) return "an index by ${there.method}"
        if (structureOnly) return null
        if (made.opclasses != null && there.opclasses.isNotEmpty() && there.opclasses != made.opclasses) return "an index with ${there.opclasses.joinToString(", ")}"
        val shape = shapeOf(there) ?: return null
        val expected = made.expected
        if (expected != null) {
            columnsOf(there)?.let { texts ->
                val reads = texts.mapIndexed { i, text -> read(text, expected, shape.columns.getOrNull(i)) }
                // A column this can't read is taken as the item's: a false mismatch would drop a good index.
                if (reads.none { it == null }) {
                    val keys = expected.columns.map { it.first }
                    if (reads.size != keys.size || reads.zip(keys).any { (read, key) -> read != Read.Property(key) }) {
                        return "an index over ${reads.filterIndexed { i, read -> i == 0 || read != reads[i - 1] }.joinToString(", ", "[", "]")}"
                    }
                }
            }
        } else {
            made.properties?.let { properties ->
                if (shape.columns.size != properties.size || shape.columns.zip(properties).any { (column, property) -> column != null && column != Read.Property(property) }) {
                    return "an index over ${shape.columns.joinToString(", ", "[", "]") { it?.toString() ?: "?" }}"
                }
            }
        }
        made.keys?.let { keys -> if (shape.keys != keys) return "an index over ${shape.keys}" }
        if (made.dimensions != null && shape.dimensions != null && shape.dimensions != made.dimensions) return "an index over vectors of ${shape.dimensions} dimensions"
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
     * The canonical text ([IndexText.canonical]) of each column the module makes each of [spec]'s indexes with, by the
     * index's name: what a column the store gives back under that name is compared with. `store.yaml`'s `columns` pins it.
     */
    fun expectedColumns(spec: SchemaItemSpec): Map<String, List<String>> =
        madeAs(spec).mapNotNull { made -> made.expected?.let { made.name to it.columns.map { column -> column.second } } }.toMap()

    /** The canonical text of each column [rows] give for the index [name], null for one the store gives no text of; null where they give none. */
    fun foundColumns(name: String, rows: List<Any?>): List<String?>? =
        StoreObject.read(rows)[name]?.let { there -> columnsOf(there)?.map { text -> text?.let { IndexText.canonical(it, dialect) } } }

    /** How the store prints an index's columns again ([IndexText.canonical]). */
    protected abstract val dialect: IndexText.Dialect

    /** The text of each of [there]'s columns, as the store gives it, null for one it gives none of; null where it gives none. */
    protected open fun columnsOf(there: StoreObject): List<String?>? = there.columns

    /** The stored key [written], as a template's column writes it where [IndexText.KEY] stands, names; null where it names none this reads. */
    protected abstract fun keyOf(written: String): String?

    /**
     * What the module makes [label]'s index over the stored [keys] as, by [columns], the builder its DDL makes the index's
     * columns with ([org.drivine.cypher.store.ddl.PostgresDdl.indexColumns] and its kin): each column's key and canonical
     * text, and a template of each column, of it and of each of [others], the other indexes a name could hold one of the
     * item's properties in (Oracle's index over every node, beside a labelled or unique index).
     */
    protected fun expected(
        label: String?,
        keys: List<String>,
        columns: (String, List<String>) -> List<String>,
        others: List<(String, List<String>) -> List<String>> = emptyList(),
    ): Expected {
        val written = columns(label ?: IndexText.LABEL, keys)
        val each = written.size / keys.size
        val texts = written.mapIndexed { i, text -> keys[i / each] to IndexText.canonical(text, dialect) }
        val templates = (listOf(columns) + others).flatMap { builder -> builder(IndexText.LABEL, listOf(IndexText.KEY)).map { IndexText.Template(it, dialect) } }
        return Expected(texts, templates)
    }

    /**
     * What one column of an index under the item's name reads, from its [text]: the item's key where it is, canonically,
     * a column the module writes for the item ([expected]); another key where it is of a column's template with another;
     * where it is neither, what the store's own reading of it ([legacy]) says, where that is a property or a column of the
     * table; and null, unknown, otherwise.
     */
    private fun read(text: String?, expected: Expected, legacy: Read?): Read? {
        if (text == null) return null
        val canonical = IndexText.canonical(text, dialect)
        expected.columns.firstOrNull { it.second == canonical }?.let { return Read.Property(it.first) }
        for (template in expected.templates) {
            val (written, _) = template.match(canonical) ?: continue
            keyOf(written)?.let { return Read.Property(it) }
        }
        return when (legacy) {
            is Read.Property -> legacy
            is Read.Other -> legacy.takeIf { PLAIN_COLUMN.matches(it.text) }
            null -> null
        }
    }

    /**
     * What the module writes an index's columns as: each column's stored key and its canonical text ([IndexText.canonical]),
     * in order, and the [templates] a column of another key, or another label, is read by.
     */
    protected class Expected(val columns: List<Pair<String, String>>, val templates: List<IndexText.Template>)

    /**
     * An object the store makes an item as: an index under [name], [unique] or not, a column for each of [properties] in
     * order, or one expression that reads [keys] in order (a vector's embedding, a full-text index's text), of the nodes of
     * [label] alone, or of every node where it is null; by the access [method] with the operator classes [opclasses], and
     * over vectors of [dimensions], where the store says. What is null isn't compared.
     */
    protected data class MadeObject(
        val name: String,
        val unique: Boolean,
        val properties: List<String>?,
        val label: String?,
        val keys: List<String>? = null,
        val method: String? = null,
        val opclasses: List<String>? = null,
        val dimensions: Int? = null,
        val expected: Expected? = null,
    )

    /**
     * What an index reads: each column's [Read], in order, null where its text isn't read here; for each test of a label it
     * makes, the label, null for a column that tests none, or for an index that tests none at all; the [keys] of every
     * property its columns read, in order, each once; and the size of the vectors it is over, where it says one.
     */
    protected data class Shape(val columns: List<Read?>, val labels: List<String?>, val keys: List<String> = emptyList(), val dimensions: Int? = null)

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

        /** A column of the table, read as itself. */
        private val PLAIN_COLUMN = Regex("[A-Za-z_][A-Za-z0-9_]*")
    }
}

/**
 * What a SQL store has under one name, as its listing gives it beside the records, a row a type of object under the name:
 * [types], each type of object the name is taken by (an index, a table, a view; Oracle keeps indexes apart from its other
 * objects, so a name can be taken by an index and a table); and of the index, the [table] it is on, whether it is [unique],
 * the text of each of its [columns] in order, as the store writes them, its [predicate] (a partial index's), its [method]
 * and [opclasses] (Postgres's), its [indexType] (Oracle's), and the [statement] that made it (SQLite's); of a table, its
 * [tableColumns], each its name and type (Oracle's); and of a trigger, the table it is on, as [table], and its [source]
 * (Oracle's).
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
    val opclasses: List<String> = emptyList(),
    val indexType: String? = null,
    val tableColumns: List<String> = emptyList(),
    val source: String? = null,
) {
    /** Whether the name is taken by an index. */
    val isIndex: Boolean
        get() = types.any { it.equals(INDEX, ignoreCase = true) }

    companion object {
        /** A type of object: an index, as each store names it (Postgres's `relkind` `i`, as `index`). */
        const val INDEX = "INDEX"

        /**
         * The objects [rows] give, by their names in upper case, as each store's listing gives them: `object`, the name;
         * `type`, the type; `table`; `unique`, a boolean (SQLite's `0` or `1`), or Oracle's `uniqueness`, `UNIQUE` or `NONUNIQUE`; `columns`;
         * `where`; `method`; `opclasses`; `indexType`; `sql`; `tableColumns`; `source`, a list of lines or one text. The rows
         * of a name are one object.
         */
        fun read(rows: List<Any?>): StoreObjects {
            val objects = linkedMapOf<String, MutableList<StoreObject>>()
            for (row in rows) {
                val map = row as? Map<*, *> ?: continue
                val name = map[SqlStoreSchema.OBJECT] as? String ?: continue
                val type = when (val t = map["type"] as? String) {
                    "i", "I" -> INDEX
                    // Postgres's relkinds, as words.
                    "r" -> "TABLE"
                    "p" -> "PARTITIONED TABLE"
                    "f" -> "FOREIGN TABLE"
                    "t" -> "TOAST TABLE"
                    "v" -> "VIEW"
                    "m" -> "MATERIALIZED VIEW"
                    "S" -> "SEQUENCE"
                    "c" -> "COMPOSITE TYPE"
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
                    opclasses = (map["opclasses"] as? List<*>).orEmpty().map { it.toString() },
                    indexType = map["indexType"] as? String,
                    tableColumns = (map["tableColumns"] as? List<*>).orEmpty().map { it.toString() },
                    source = (map["source"] as? List<*>)?.joinToString("") { it?.toString().orEmpty() } ?: map["source"] as? String,
                )
                objects.getOrPut(name.uppercase()) { mutableListOf() } += read
            }
            return StoreObjects(objects)
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
            SchemaItemKind.RANGE_INDEX -> listOf(MadeObject(ddl.objectName(PostgresDdl.RANGE, parts), false, spec.properties, spec.label, expected = expected(spec)))
            SchemaItemKind.UNIQUENESS_CONSTRAINT -> listOf(MadeObject(ddl.objectName(PostgresDdl.UNIQUE, parts), true, spec.properties, spec.label, expected = expected(spec)))
            SchemaItemKind.VECTOR_INDEX -> {
                val vector = spec as VectorIndexSpec
                listOf(
                    MadeObject(
                        ddl.objectName(PostgresDdl.VECTOR, listOf(spec.label, spec.properties.single())), false, null, spec.label,
                        keys = spec.properties, method = "hnsw", opclasses = listOf(PostgresSql.operators(vector.similarity)), dimensions = vector.dimensions,
                    ),
                )
            }
            SchemaItemKind.FULLTEXT_INDEX ->
                listOf(MadeObject(ddl.objectName(PostgresDdl.FULLTEXT, parts), false, null, spec.label, keys = spec.properties, method = "gin", opclasses = listOf(TRIGRAMS)))
            SchemaItemKind.TEXT_INDEX -> emptyList()
        }
    }

    private fun expected(spec: SchemaItemSpec) = expected(spec.label, SqlStoreDdl.storedKeys(spec.properties), { _, keys -> ddl.indexColumns(keys) })

    override val dialect: IndexText.Dialect
        get() = IndexText.Dialect.POSTGRES

    /** A key as a literal: `props -> 'name'`. */
    override fun keyOf(written: String): String = SqlText.unquoted(written)

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
        val keys = there.columns.filterNotNull().flatMap { column ->
            KEY.findAll(column).map { if (it.value == "stamp") Stamps.PROPERTY else SqlText.unquoted(it.groupValues[1]) }
        }.distinct()
        val dimensions = there.columns.filterNotNull().firstNotNullOfOrNull { DIMENSIONS.find(it)?.groupValues?.get(1)?.toInt() }
        return Shape(columns, listOf(label), keys, dimensions)
    }

    private companion object {
        val PROPERTY = Regex("""props ->> '${SqlText.LITERAL}'::text""")
        /** A property an expression reads: a key of `props`, or the stamp, a column of its own (`COALESCE(stamp, ''::text)`). */
        val KEY = Regex("""props ->>? '${SqlText.LITERAL}'::text|(?<![\w'".])stamp(?![\w'"])""")
        val DIMENSIONS = Regex("""vector\((\d+)\)""")
        val LABEL = Regex("""labels @> '${SqlText.LITERAL}'::jsonb""")

        /** pg_trgm's operator class, which a full-text index's GIN index is made with. */
        const val TRIGRAMS = "gin_trgm_ops"
    }

    /** `23505`: a second row for a unique key, or a unique index made over rows that already repeat one. */
    override fun isViolation(error: StoreError): Boolean = error.causes.any { it.isSqlError && it.sqlState == "23505" }

    /** A node's element id is its `nid`; [limit] of a key's nodes at most are named, the first made first. */
    override fun sampleConflictsQuery(spec: ConstraintSpec, limit: Int): String = sample(spec, SqlStoreDdl.storedKeys(spec.properties).map { PostgresSql.property("n", it) }, limit)

    /** Each key lowercased as the copies are filled in, with `lower`. */
    override fun sampleRefilledConflictsQuery(spec: ConstraintSpec, limit: Int): String =
        sample(spec.made(), SqlStoreDdl.storedKeys(spec.properties).map { "lower(${PostgresSql.property("n", it)})" }, limit)

    private fun sample(spec: ConstraintSpec, keys: List<String>, limit: Int): String {
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
        val keys = SqlStoreDdl.storedKeys(spec.properties)
        val sharedColumns: (String, List<String>) -> List<String> = { _, k -> ddl.sharedColumns(k) }
        val labelledColumns: (String, List<String>) -> List<String> = { label, k -> ddl.labelledColumns(label, k) }
        val uniqueColumns: (String, List<String>) -> List<String> = { label, k -> ddl.uniqueColumns(label, k) }
        val shared = MadeObject(ddl.objectName(OracleDdl.SHARED, spec.properties), false, spec.properties, null, expected = expected(null, keys, sharedColumns))
        return when (spec.kind) {
            SchemaItemKind.RANGE_INDEX -> listOf(
                shared,
                MadeObject(
                    ddl.objectName(OracleDdl.LABELLED, listOf(spec.label) + spec.properties), false, spec.properties, spec.label,
                    expected = expected(spec.label, keys, labelledColumns, listOf(sharedColumns)),
                ),
            )
            SchemaItemKind.UNIQUENESS_CONSTRAINT -> listOf(
                MadeObject(
                    ddl.objectName(OracleDdl.UNIQUE, listOf(spec.label) + spec.properties), true, spec.properties, spec.label,
                    expected = expected(spec.label, keys, uniqueColumns, listOf(sharedColumns, labelledColumns)),
                ),
                shared,
            )
            else -> emptyList()
        }
    }

    override val dialect: IndexText.Dialect
        get() = IndexText.Dialect.ORACLE

    /** A key as a step of a path, bare or a JSON string: `'$.name'`, `'$."first name"'`. */
    override fun keyOf(written: String): String? {
        val step = SqlText.unquoted(written)
        return if (step.startsWith("\"")) SqlText.jsonString(step) else step.takeIf { PLAIN_STEP.matches(it) }
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

    /** The table beside the nodes [spec]'s item is kept in: a vector index's or a full-text index's; null for any other. */
    private fun side(spec: SchemaItemSpec): String? = when (spec.kind) {
        SchemaItemKind.VECTOR_INDEX -> ddl.vectors(spec.label, spec.properties.single())
        SchemaItemKind.FULLTEXT_INDEX -> ddl.texts(spec.label, spec.properties)
        else -> null
    }

    /**
     * A vector or a full-text index is a table beside the nodes, a trigger on the node table that keeps it in step, and an
     * index over it ([OracleSideTable]). What stands under their names isn't the item where the table lacks the column
     * the item keeps (`EMBEDDING`, a `VECTOR`; `DOC`, `JSON`), the trigger is on another table, or tests another label or
     * reads other properties, or the index is of another type (a `VECTOR` index; Oracle Text's `DOMAIN` index) or on another
     * table. The trigger is read as each library wrote it: a label tested by its text (`INSTR(…, ',"Chunk","')`, drivine4j's)
     * or by a path (`JSON_EXISTS(:NEW.labels, '$?(@ == "Chunk")')`, TypeScript's and Python's), and each property by the
     * path it reads (`'$.p'` or `'$."p"'`). Only an index can be dropped by its name.
     */
    override fun otherMismatch(spec: SchemaItemSpec, objects: StoreObjects, structureOnly: Boolean): ItemForm.Mismatch? {
        val side = side(spec) ?: return null
        val keys = spec.properties
        val (column, type) = if (spec.kind == SchemaItemKind.VECTOR_INDEX) "EMBEDDING" to "VECTOR" else "DOC" to "JSON"
        // A newer form's: only the index over the table, whose type and table Oracle says without anything being read of
        // its text; the table's columns and the trigger's text are what a newer form may write otherwise.
        if (structureOnly) return sideIndexMismatch(spec, side, objects)
        objects.of(side, "TABLE")?.let { table ->
            if ("$column $type" !in table.tableColumns) return ItemForm.Mismatch(table.name, "a table without the $type column $column", droppable = false)
        }
        objects.of(side + TRIGGER, "TRIGGER")?.let { trigger ->
            val source = trigger.source.orEmpty()
            val reason = when {
                trigger.table != null && !trigger.table.equals(ddl.nodes, ignoreCase = true) -> "a trigger on ${trigger.table}"
                else -> {
                    val label = sideLabel(source)
                    val read = SIDE_PATH.findAll(source).mapNotNull { pathKey(it.groupValues[1]) }.distinct().toList()
                    when {
                        label != null && label != spec.label -> "a trigger over the nodes of $label"
                        read.isNotEmpty() && read != keys -> "a trigger over $read"
                        else -> null
                    }
                }
            }
            if (reason != null) return ItemForm.Mismatch(trigger.name, reason, droppable = false)
        }
        return sideIndexMismatch(spec, side, objects)
    }

    /** What stands under the name of the index over the table beside the nodes [side], where it isn't that index: one of another type, or on another table. */
    private fun sideIndexMismatch(spec: SchemaItemSpec, side: String, objects: StoreObjects): ItemForm.Mismatch? {
        val index = objects.of(side + INDEX, StoreObject.INDEX) ?: return null
        val made = if (spec.kind == SchemaItemKind.VECTOR_INDEX) "VECTOR" else "DOMAIN"
        if (index.indexType != null && !index.indexType.equals(made, ignoreCase = true)) return ItemForm.Mismatch(index.name, "an index of the type ${index.indexType}", droppable = true)
        if (index.table != null && !index.table.equals(side, ignoreCase = true)) return ItemForm.Mismatch(index.name, "an index on ${index.table}", droppable = true)
        return null
    }

    /** The label a trigger's [source] tests, as either library writes the test; null where it tests none this reads. */
    private fun sideLabel(source: String): String? {
        MODULES_SIDE_LABEL.find(source)?.let { found ->
            val needle = SqlText.unquoted(found.groupValues[1])
            return SqlText.jsonString(needle.removePrefix(",").removeSuffix(",\""))
        }
        return LIBRARIES_SIDE_LABEL.find(source)?.let { labelOf(SqlText.unquoted(it.groupValues[1])) }
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

        val VIOLATIONS = setOf(1, 1452)

        /** `ORA-01408: such column list already indexed`. */
        const val ALREADY_INDEXED = 1408

        /** What follows a table beside the nodes' name in its trigger's, and in its index's. */
        const val TRIGGER = "_t"
        const val INDEX = "_i"

        /** drivine4j's test of a label in a trigger, by its text: the needle is the label as `JSON_SERIALIZE` writes it. */
        val MODULES_SIDE_LABEL = Regex("""JSON_SERIALIZE\(:NEW\.labels\), '\]'\), '\['\) \|\| ',"', '${SqlText.LITERAL}'\) > 0""", RegexOption.IGNORE_CASE)

        /** TypeScript's and Python's test of a label in a trigger, by a path. */
        val LIBRARIES_SIDE_LABEL = Regex("""JSON_EXISTS\(:NEW\.labels, '${SqlText.LITERAL}'\)""", RegexOption.IGNORE_CASE)

        /** Each path into a node's properties a trigger reads. */
        val SIDE_PATH = Regex("""JSON_(?:QUERY|VALUE)\(:NEW\.props, '${SqlText.LITERAL}'""", RegexOption.IGNORE_CASE)
    }

    /**
     * A range index made before there were labelled indexes, or whose labelled index was dropped, with no uniqueness
     * constraint's index to stand for it: the listing gives it [OracleDdl.INCOMPLETE], and an ensure makes the rest.
     */
    override fun isIncomplete(existing: SchemaItemInfo): Boolean = existing.status == OracleDdl.INCOMPLETE

    /** `ORA-00001`, a second row for a unique key; `ORA-01452`, a unique index over rows that already repeat one. */
    override fun isViolation(error: StoreError): Boolean = error.causes.any { it.isSqlError && it.vendorCode in VIOLATIONS }

    /** `ORA-01408`: an index over the same expressions is there already, which Oracle allows only one of. */
    override fun isAlreadyIndexed(error: StoreError): Boolean = error.causes.any { it.isSqlError && it.vendorCode == ALREADY_INDEXED }

    /**
     * Each node of a key that repeats is numbered within its key, and counted with it, so that a row names [limit] of its
     * nodes at most however many hold the key. A node's element id is its `nid`.
     */
    override fun sampleConflictsQuery(spec: ConstraintSpec, limit: Int): String =
        sample(spec, SqlStoreDdl.storedKeys(spec.properties).map(::sampled), limit)

    /**
     * The property [key] of a node as a sample groups and gives it: a temporal value's tag (layout 2) by its canonical text
     * `v`, which `JSON_VALUE` alone reads as null, so a key of dates that repeats would find no repeat, and the violation
     * would be thrown, not reported; any other value as `JSON_VALUE` reads it.
     */
    private fun sampled(key: String) =
        "COALESCE(JSON_VALUE(n.props, ${OracleSql.path(key, ".${ValueLayout.TEXT}")}), JSON_VALUE(n.props, ${OracleSql.path(key)}))"

    /** Each key lowercased as the copies are filled in, with `LOWER`. */
    override fun sampleRefilledConflictsQuery(spec: ConstraintSpec, limit: Int): String =
        sample(spec.made(), SqlStoreDdl.storedKeys(spec.properties).map { "LOWER(JSON_VALUE(n.props, ${OracleSql.path(it)}))" }, limit)

    private fun sample(spec: ConstraintSpec, keys: List<String>, limit: Int): String {
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
            SchemaItemKind.RANGE_INDEX -> listOf(MadeObject(ddl.objectName(SqliteDdl.RANGE, parts), false, spec.properties, spec.label, expected = expected(spec)))
            SchemaItemKind.UNIQUENESS_CONSTRAINT -> listOf(MadeObject(ddl.objectName(SqliteDdl.UNIQUE, parts), true, spec.properties, spec.label, expected = expected(spec)))
            else -> emptyList()
        }
    }

    private fun expected(spec: SchemaItemSpec) = expected(spec.label, SqlStoreDdl.storedKeys(spec.properties), { _, keys -> ddl.indexColumns(keys) })

    override val dialect: IndexText.Dialect
        get() = IndexText.Dialect.SQLITE

    /** A key within a path's JSON string: `'$."name"'`. */
    override fun keyOf(written: String): String? = SqlText.jsonString("\"" + SqlText.unquoted(written) + "\"")

    /** Each column of the statement that made [there], as it was written; null where there is none. */
    override fun columnsOf(there: StoreObject): List<String?>? {
        val statement = there.statement ?: return null
        val on = ON.find(statement) ?: return null
        val close = SqlText.closing(statement, on.range.last) ?: return null
        return SqlText.split(statement.substring(on.range.last + 1, close))
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
    override fun sampleConflictsQuery(spec: ConstraintSpec, limit: Int): String = sample(spec, SqlStoreDdl.storedKeys(spec.properties).map { SqliteSql.property("n", it) }, limit)

    /** Each key lowercased as the copies are filled in, with the library's own [SqliteFunctions.LOWER]. */
    override fun sampleRefilledConflictsQuery(spec: ConstraintSpec, limit: Int): String =
        sample(spec.made(), SqlStoreDdl.storedKeys(spec.properties).map { "${SqliteFunctions.LOWER}(${SqliteSql.property("n", it)})" }, limit)

    private fun sample(spec: ConstraintSpec, keys: List<String>, limit: Int): String {
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

/**
 * What a SQL store has under each name its listing gives, by the name in upper case: an object of each type a name is taken
 * by, each with its own facts (on Oracle a name can be an index's and a table's, which are named apart).
 */
@DrivineInternalApi
class StoreObjects(private val byName: Map<String, List<StoreObject>>) {

    /**
     * What stands under [name], taken whole: every type the name is taken by, and the facts of its index where one is,
     * as an index is what the name is compared as; null where nothing is.
     */
    operator fun get(name: String): StoreObject? {
        val all = byName[name.uppercase()] ?: return null
        val first = all.firstOrNull { it.isIndex } ?: all.first()
        return first.copy(types = all.flatMap { it.types }.distinct())
    }

    /** The object of [type] under [name] (`TABLE`, `TRIGGER`, `INDEX`), with its own facts; null where there is none. */
    fun of(name: String, type: String): StoreObject? = byName[name.uppercase()]?.firstOrNull { it.types.any { t -> t.equals(type, ignoreCase = true) } }
}
