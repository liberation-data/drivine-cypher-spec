package org.drivine.cypher.store.migrate

import org.drivine.DrivineInternalApi
import org.drivine.cypher.json.JsonNode
import org.drivine.cypher.json.ObjectNode
import org.drivine.cypher.store.marker.StoreLayout
import org.drivine.cypher.store.migrate.MigrationStatements.Table

/**
 * What a migration, or its dry run, found and did ([LayoutMigrator]): the report a library and the command line show, and a
 * dry run's whole answer, so a user sees, before anything is written, what would change and what to declare.
 *
 * - [status] and [message]: how it ended.
 * - [tables]: for `nodes` and `edges`, the rows read and the rows changed: by the scan, what would change; once written, what was.
 * - [properties]: for each label (a relationship's type, for one) and property that a rewrite changes or finds a value of
 *   not of its declared kind, the count of each change ([LayoutMigration.Change]'s words) and each such value. A property
 *   of a node of two labels is counted under both.
 * - [observed]: for each label and property, the count of its values of each class ([LayoutMigration.observe]), of the
 *   rows other than Drivine's own whose labels declare the property nothing.
 * - [candidates]: each label's property no declaration names whose values [observed] all read as one kind a declaration
 *   would change them by (a temporal kind, where each is a text of it; a float, where they are whole numbers and floats
 *   both), with the count of its values: what to declare, never applied unless declared. A value of a node of two labels,
 *   one of which declares its property, is that declaration's, and no candidate of the other.
 * - [kept]: once written, each value kept as it was, not of its declared kind, where the migration was told to keep them.
 */
@DrivineInternalApi
data class MigrationReport(
    val status: Status? = null,
    val message: String? = null,
    val tables: Map<String, Counted> = emptyMap(),
    val properties: Map<String, PropertyReport> = emptyMap(),
    val observed: Map<String, Map<String, Long>> = emptyMap(),
    val candidates: List<Candidate> = emptyList(),
    val kept: List<Kept> = emptyList(),
) {
    /** The rows of a table read and changed. */
    data class Counted(val rows: Long = 0, val changed: Long = 0)

    /** What a property's values were changed by, and its values not of their declared kind. */
    data class PropertyReport(val changes: Map<String, Long> = emptyMap(), val invalid: List<InvalidValue> = emptyList())

    /** A value of the row [id] of [table] not of its declared [kind]: [value], its JSON text, and why ([message]). */
    data class InvalidValue(val table: String, val id: Long, val kind: String, val value: String, val message: String)

    /** A property to declare: [kind] would change its [values]. */
    data class Candidate(val label: String, val property: String, val kind: String, val values: Long)

    /** How many values are not of their declared kind. */
    val invalidCount: Int get() = properties.values.sumOf { it.invalid.size }

    /** This report, ended with [status] and [message]. */
    fun ended(status: Status, message: String) = copy(status = status, message = message)

    /** This report, after the scan read [row] of [table], which [rewrite] rewrote and [observe] observed. */
    internal fun scanned(
        table: Table,
        row: Row,
        rewrite: LayoutMigration.Rewrite,
        observe: List<Pair<String, String>>,
        declarations: List<LayoutMigration.Declaration>,
    ): MigrationReport {
        val props = properties.toMutableMap()
        for (label in row.labels) {
            for ((property, changes) in rewrite.changes.groupBy { it.property }) {
                val key = key(label, property)
                val was = props[key] ?: PropertyReport()
                props[key] = was.copy(changes = was.changes.toMutableMap().apply { changes.forEach { count(it.change.word) } })
            }
        }
        // A value not of its kind is listed under each of the row's labels that declares its property.
        for (invalid in rewrite.invalid) {
            for (label in declarations.filter { it.property == invalid.property && it.label in row.labels }.map { it.label }.distinct()) {
                val key = key(label, invalid.property)
                val was = props[key] ?: PropertyReport()
                props[key] = was.copy(invalid = was.invalid + InvalidValue(table.word, row.id, invalid.kind.word, invalid.value, invalid.message))
            }
        }
        val seen = observed.toMutableMap()
        // Drivine's own nodes are no candidates, nor a property a declaration of one of the row's labels rewrites already.
        if (row.labels.none { it in StoreLayout.OWN_LABELS }) {
            val declared = declarations.filter { it.label in row.labels }.map { it.property }.toSet()
            for (label in row.labels) for ((property, kind) in observe) {
                if (property in declared) continue
                val key = key(label, property)
                seen[key] = (seen[key] ?: emptyMap()).toMutableMap().apply { count(kind) }
            }
        }
        return copy(tables = counted(table, rewrite.changed), properties = props, observed = seen)
    }

    /** This report, after the write read a row of [table], [changed] or not. */
    internal fun written(table: Table, changed: Boolean) = copy(tables = counted(table, changed))

    private fun counted(table: Table, changed: Boolean): Map<String, Counted> {
        val was = tables[table.word] ?: Counted()
        return tables + (table.word to Counted(was.rows + 1, was.changed + if (changed) 1 else 0))
    }

    /** This report with its [candidates], from what the scan observed of the properties no declaration among [declarations] names. */
    internal fun withCandidates(declarations: List<LayoutMigration.Declaration>): MigrationReport = copy(candidates = observed.mapNotNull { (key, classes) ->
        val (label, property) = split(key)
        if (declarations.any { it.label == label && it.property == property }) return@mapNotNull null
        val total = classes.values.sum()
        val temporal = classes.keys.singleOrNull()?.takeIf { word -> LayoutMigration.Kind.of(word)?.temporal != null }
        when {
            temporal != null -> Candidate(label, property, temporal, total)
            (classes["whole"] ?: 0) > 0 && (classes["float"] ?: 0) > 0 && classes.keys == setOf("whole", "float") ->
                Candidate(label, property, LayoutMigration.Kind.FLOAT.word, total)
            else -> null
        }
    }.sortedWith(compareBy({ it.label }, { it.property })))

    /** As JSON: see the class. */
    fun toMap(): Map<String, Any?> = linkedMapOf(
        "status" to status?.word,
        "message" to message,
        "tables" to tables.mapValues { (_, c) -> linkedMapOf("rows" to c.rows, "changed" to c.changed) },
        "properties" to properties.entries.sortedBy { it.key }.map { (key, p) ->
            val (label, property) = split(key)
            linkedMapOf(
                "label" to label, "property" to property,
                "changes" to p.changes.sorted(),
                "invalid" to p.invalid.map { linkedMapOf("table" to it.table, "id" to it.id, "kind" to it.kind, "value" to it.value, "message" to it.message) },
            )
        },
        "observed" to observed.entries.sortedBy { it.key }.map { (key, classes) ->
            val (label, property) = split(key)
            linkedMapOf("label" to label, "property" to property, "classes" to classes.sorted())
        },
        "candidates" to candidates.map { linkedMapOf("label" to it.label, "property" to it.property, "kind" to it.kind, "values" to it.values) },
        "kept" to kept.map { it.toMap() },
    )

    companion object {
        private const val SEPARATOR = "\u0000"

        private fun key(label: String, property: String) = label + SEPARATOR + property

        private fun split(key: String) = key.substringBefore(SEPARATOR) to key.substringAfter(SEPARATOR)

        /** The report [tree] is, as [toMap] writes one. */
        fun of(tree: ObjectNode): MigrationReport = MigrationReport(
            status = tree["status"]?.textValue()?.let { word -> Status.entries.first { it.word == word } },
            message = tree["message"]?.textValue(),
            tables = (tree["tables"] as? ObjectNode)?.properties()?.associate { (name, c) -> name to Counted(c["rows"]!!.longValue(), c["changed"]!!.longValue()) }.orEmpty(),
            properties = tree["properties"]?.associate { p ->
                key(p["label"]!!.textValue()!!, p["property"]!!.textValue()!!) to PropertyReport(
                    (p["changes"] as ObjectNode).properties().associate { (word, n) -> word to n.longValue() },
                    p["invalid"]!!.map { InvalidValue(it["table"]!!.textValue()!!, it["id"]!!.longValue(), it["kind"]!!.textValue()!!, it["value"]!!.textValue()!!, it["message"]!!.textValue()!!) },
                )
            }.orEmpty(),
            observed = tree["observed"]?.associate { o ->
                key(o["label"]!!.textValue()!!, o["property"]!!.textValue()!!) to (o["classes"] as ObjectNode).properties().associate { (word, n) -> word to n.longValue() }
            }.orEmpty(),
            candidates = tree["candidates"]?.map { Candidate(it["label"]!!.textValue()!!, it["property"]!!.textValue()!!, it["kind"]!!.textValue()!!, it["values"]!!.longValue()) }.orEmpty(),
            kept = tree["kept"]?.map { Kept.of(it) }.orEmpty(),
        )
    }
}

/** One more of [word]: `merge` and `toSortedMap` are the JVM's alone, and this module is built for every target. */
private fun MutableMap<String, Long>.count(word: String) {
    this[word] = (this[word] ?: 0L) + 1L
}

private fun Map<String, Long>.sorted(): Map<String, Long> = entries.sortedBy { it.key }.associate { it.key to it.value }

private fun <T> JsonNode.associate(transform: (JsonNode) -> Pair<String, T>): Map<String, T> = map(transform).toMap()
