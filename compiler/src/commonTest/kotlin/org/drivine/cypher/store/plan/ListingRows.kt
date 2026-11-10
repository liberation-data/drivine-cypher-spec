package org.drivine.cypher.store.plan

import org.drivine.schema.SchemaItemInfo
import org.drivine.schema.SchemaItemKind
import org.drivine.schema.SimilarityFunction

/**
 * The rows a store lists items as, the way its listing gives them, for `plans.yaml`'s answers, which give a listing as the
 * items it reads as: so a case says what a store has, and each store's rows are written once, here. Every store's rows
 * read back to the items they were written from ([org.drivine.cypher.store.schema.StoreSchema.parseIndexRows] and
 * [org.drivine.cypher.store.schema.StoreSchema.parseConstraintRows]), as `StorePlanTest` checks over `store.yaml`'s
 * listings.
 *
 * - Neo4j: a map a row, an index's `indexConfig` among its `options`.
 * - Memgraph: a range or text index as `SHOW INDEX INFO`'s columns, a vector index as a map, a constraint as
 *   `SHOW CONSTRAINT INFO`'s columns.
 * - FalkorDB: a row a label, its properties by type, an index's status the label's.
 * - Postgres, Oracle and SQLite: a record a row, with its `details`; Oracle's with its `status`.
 */
internal object ListingRows {

    fun rows(store: String, items: List<SchemaItemInfo>, constraints: Boolean): List<Any?> = when (store) {
        "neo4j" -> items.map { if (constraints) neo4jConstraint(it) else neo4jIndex(it) }
        "memgraph" -> items.map { if (constraints) listOf("unique", it.label, it.properties) else memgraphIndex(it) }
        "falkordb" -> if (constraints) items.map(::falkorDbConstraint) else falkorDbIndexes(items)
        else -> items.map { sqlRecord(store, it) }
    }

    private fun neo4jConstraint(item: SchemaItemInfo) = linkedMapOf(
        "name" to item.name, "type" to "UNIQUENESS", "entityType" to "NODE", "labelsOrTypes" to listOf(item.label), "properties" to item.properties,
    )

    private fun neo4jIndex(item: SchemaItemInfo): Map<String, Any?> {
        val config = linkedMapOf<String, Any?>()
        if (item.kind == SchemaItemKind.VECTOR_INDEX) {
            item.dimensions?.let { config["vector.dimensions"] = it.toLong() }
            item.similarity?.let { config["vector.similarity_function"] = it.name }
            item.quantizationEnabled?.let { config["vector.quantization.enabled"] = it }
            item.hnswM?.let { config["vector.hnsw.m"] = it.toLong() }
            item.hnswEfConstruction?.let { config["vector.hnsw.ef_construction"] = it.toLong() }
        }
        if (item.kind == SchemaItemKind.FULLTEXT_INDEX) item.analyzer?.let { config["fulltext.analyzer"] = it }
        return linkedMapOf(
            "name" to item.name, "type" to item.kind.name.removeSuffix("_INDEX"), "entityType" to "NODE", "labelsOrTypes" to listOf(item.label),
            "properties" to item.properties, "options" to linkedMapOf("indexConfig" to config),
        )
    }

    private fun memgraphIndex(item: SchemaItemInfo): Any = when (item.kind) {
        SchemaItemKind.VECTOR_INDEX -> linkedMapOf(
            "index_name" to item.name, "label" to item.label, "property" to item.properties.single(), "dimension" to item.dimensions?.toLong(),
            "metric" to when (item.similarity) {
                SimilarityFunction.COSINE -> "cos"
                SimilarityFunction.EUCLIDEAN -> "l2sq"
                null -> null
            },
        )
        SchemaItemKind.FULLTEXT_INDEX -> listOf("label_text (name: ${item.name})", item.label, item.properties, 0L)
        else -> listOf("label+property", item.label, item.properties.singleOrNull() ?: item.properties, 0L)
    }

    private fun falkorDbConstraint(item: SchemaItemInfo) = linkedMapOf<String, Any?>(
        "type" to "unique", "label" to item.label, "properties" to item.properties, "entitytype" to "NODE",
    ).also { row -> item.status?.let { row["status"] = it } }

    private fun falkorDbIndexes(items: List<SchemaItemInfo>): List<Any?> = items.groupBy { it.label }.map { (label, of) ->
        val types = linkedMapOf<String, MutableList<String>>()
        val options = linkedMapOf<String, Any?>()
        for (item in of) for (property in item.properties) {
            types.getOrPut(property) { mutableListOf() } += item.kind.name.removeSuffix("_INDEX")
            if (item.kind == SchemaItemKind.VECTOR_INDEX) {
                options[property] = linkedMapOf<String, Any?>("dimension" to item.dimensions?.toLong(), "similarityFunction" to item.similarity?.name?.lowercase()).also {
                    item.hnswM?.let { m -> it["M"] = m.toLong() }
                    item.hnswEfConstruction?.let { ef -> it["efConstruction"] = ef.toLong() }
                }
            }
        }
        // The label's status is its items': one being built, where any is, else the one they share.
        val status = of.firstNotNullOfOrNull { it.status?.takeIf { status -> status != OPERATIONAL } } ?: of.firstNotNullOfOrNull { it.status }
        linkedMapOf<String, Any?>(
            "label" to label, "properties" to types.keys.toList(), "types" to types, "options" to options, "entitytype" to "NODE",
        ).also { row -> status?.let { row["status"] = it } }
    }

    private fun sqlRecord(store: String, item: SchemaItemInfo): Map<String, Any?> {
        val details = linkedMapOf<String, Any?>()
        item.dimensions?.let { details["dimensions"] = it.toLong() }
        item.similarity?.let { details["similarity"] = it.name }
        item.analyzer?.let { details["analyzer"] = it }
        return linkedMapOf<String, Any?>("kind" to item.kind.name, "label" to item.label, "properties" to item.properties, "name" to item.name, "details" to details).also {
            if (store == "oracle" && item.status != null) it["status"] = item.status
        }
    }

    private const val OPERATIONAL = "OPERATIONAL"
}
