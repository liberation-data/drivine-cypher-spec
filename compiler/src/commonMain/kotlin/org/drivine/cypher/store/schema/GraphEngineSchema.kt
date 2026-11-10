package org.drivine.cypher.store.schema

import org.drivine.DrivineInternalApi
import org.drivine.cypher.store.ddl.FalkorDbDdl
import org.drivine.cypher.store.ddl.MemgraphDdl
import org.drivine.cypher.store.ddl.Neo4jDdl
import org.drivine.cypher.store.ddl.StoreDdl
import org.drivine.schema.FullTextIndexSpec
import org.drivine.schema.Neo4jVectorOptions
import org.drivine.schema.RangeIndexSpec
import org.drivine.schema.SchemaItemInfo
import org.drivine.schema.SchemaItemKind
import org.drivine.schema.SchemaItemSpec
import org.drivine.schema.SimilarityFunction
import org.drivine.schema.VectorIndexSpec

/**
 * Neo4j's schema, 4.x to 2026: `SHOW INDEXES` and `SHOW CONSTRAINTS` read as maps, its own TEXT index, the indexes it
 * builds in the background and is waited on for, and errors told by the driver's codes and GQL statuses.
 *
 * @param elementIdFunction the function that gives a node's own id: `elementId` from Neo4j 5, `id` on Neo4j 4, which has
 *   no `elementId`
 */
@DrivineInternalApi
class Neo4jSchema(private val elementIdFunction: String = "elementId") : StoreSchema {

    override val ddl: StoreDdl = Neo4jDdl
    override val supportsIfNotExists = true
    override val supportsNamedItems = true
    override val supportsTextIndex = true

    /**
     * `db.awaitIndex`: an index is made POPULATING and built in the background, and a write of a value too large for it
     * that commits just as it comes online can stop the database (Neo4j skips the key-size check while it populates). It
     * fails, with Neo4j's reason, for an index whose build failed.
     */
    override val awaitIndexStatement = "CALL db.awaitIndex(\$name, \$timeout)"

    override val indexStateStatement = "SHOW INDEXES YIELD name, state WHERE name = \$name RETURN {state: state}"

    /** Neo4j makes the portable HNSW parameters and its own options, so nothing is left unmade. */
    override fun unsupportedVectorTuning(spec: VectorIndexSpec): List<String> = emptyList()

    /** Quantization, by the rule of the portable parameters: unpinned or unreported is never drift. */
    override fun matchesEngineVectorOptions(existing: SchemaItemInfo, spec: VectorIndexSpec): Boolean =
        pinnedMatches((spec.optionsFor(Neo4jVectorOptions.ENGINE) as? Neo4jVectorOptions)?.quantizationEnabled, existing.quantizationEnabled)

    override fun parseIndexRows(rows: List<Any?>): List<SchemaItemInfo> = rows.mapNotNull { parseIndexRow(it) }

    private fun parseIndexRow(row: Any?): SchemaItemInfo? {
        val map = row as? Map<*, *> ?: return null
        if ((map["entityType"] as? String)?.uppercase() == "RELATIONSHIP") return null
        val kind = when ((map["type"] as? String)?.uppercase()) {
            "VECTOR" -> SchemaItemKind.VECTOR_INDEX
            "RANGE", "BTREE" -> SchemaItemKind.RANGE_INDEX
            "FULLTEXT" -> SchemaItemKind.FULLTEXT_INDEX
            "TEXT" -> SchemaItemKind.TEXT_INDEX
            else -> return null // LOOKUP, POINT: not Drivine's
        }
        val label = (map["labelsOrTypes"] as? List<*>)?.firstOrNull() as? String ?: return null
        val properties = (map["properties"] as? List<*>)?.filterIsInstance<String>() ?: return null
        val name = map["name"] as? String
        val indexConfig = ((map["options"] as? Map<*, *>)?.get("indexConfig")) as? Map<*, *>
        return when (kind) {
            SchemaItemKind.VECTOR_INDEX -> SchemaItemInfo(
                kind = kind,
                label = label,
                properties = properties,
                name = name,
                dimensions = (indexConfig?.get("vector.dimensions") as? Number)?.toInt(),
                similarity = (indexConfig?.get("vector.similarity_function") as? String)?.let(::similarityFromName),
                // What the server chose, pinned or not, so that drift from a pinned value is seen and the rest logged.
                quantizationEnabled = quantizationEnabled(indexConfig),
                hnswM = (indexConfig?.get("vector.hnsw.m") as? Number)?.toInt(),
                hnswEfConstruction = (indexConfig?.get("vector.hnsw.ef_construction") as? Number)?.toInt(),
            )
            SchemaItemKind.FULLTEXT_INDEX -> SchemaItemInfo(
                kind = kind,
                label = label,
                properties = properties,
                name = name,
                analyzer = indexConfig?.get("fulltext.analyzer") as? String,
            )
            else -> SchemaItemInfo(kind = kind, label = label, properties = properties, name = name)
        }
    }

    /**
     * Whether a vector index quantizes, as its `indexConfig` reports it: `vector.quantization.enabled` up to Neo4j 2026.05,
     * and from 2026.06 `vector.quantization.type` in its place, `NONE` or the kind of quantization. Null where it reports
     * neither.
     */
    private fun quantizationEnabled(indexConfig: Map<*, *>?): Boolean? {
        val type = indexConfig?.get("vector.quantization.type") as? String
        if (type != null) return !type.equals("NONE", ignoreCase = true)
        return indexConfig?.get("vector.quantization.enabled") as? Boolean
    }

    override fun parseConstraintRows(rows: List<Any?>): List<SchemaItemInfo> = rows.mapNotNull { parseConstraintRow(it) }

    private fun parseConstraintRow(row: Any?): SchemaItemInfo? {
        val map = row as? Map<*, *> ?: return null
        if ((map["entityType"] as? String)?.uppercase() == "RELATIONSHIP") return null
        // Neo4j 5 reports UNIQUENESS; some versions NODE_PROPERTY_UNIQUENESS.
        val type = (map["type"] as? String)?.uppercase() ?: return null
        if (!type.contains("UNIQUENESS")) return null
        val label = (map["labelsOrTypes"] as? List<*>)?.firstOrNull() as? String ?: return null
        val properties = (map["properties"] as? List<*>)?.filterIsInstance<String>() ?: return null
        return SchemaItemInfo(SchemaItemKind.UNIQUENESS_CONSTRAINT, label, properties, name = map["name"] as? String)
    }

    /**
     * By the driver's codes. A write the constraint refuses (`ConstraintValidationFailed`) is always one. A constraint the
     * server would not make is `ConstraintCreationFailed` on every version, for a violation and other refusals alike, so
     * its cause decides:
     *
     * - Neo4j 2026 names a violation in the cause, `22N79` or `22N80`, and another refusal by its own status, as `51N27`
     *   for a key constraint on Community Edition: not a violation.
     * - Neo4j 5.26 gives the refusal `50N11` and a violation beneath it only `50N42` (an unexpected error), the status the
     *   driver also gives an error a server sent none for. An older server gives no status at all.
     *
     * So a refusal whose chain holds no status but these general ones is suspected, and the data is read to confirm it.
     * A null status is read as `50N42`, as drivine4j's driver gives it. Only the driver's errors count: words alone say nothing.
     */
    override fun isViolation(error: StoreError): Boolean {
        val errors = error.causes.filter { it.isNeo4jError }
        if (errors.any { it.code == VALIDATION_FAILED || it.gqlStatus in VIOLATED }) return true
        return errors.all { (it.gqlStatus ?: UNEXPECTED) in GENERAL_STATUSES } && errors.any { it.code == CREATION_FAILED }
    }

    /**
     * "There already exists an index (:Label {prop}). A constraint cannot be created until the index has been dropped."
     * By its code, which Neo4j 4.4 to 2026 all give.
     */
    override fun isBlockedByIndex(error: StoreError): Boolean = error.causes.any { it.isNeo4jError && it.code == INDEX_ALREADY_EXISTS }

    /** `db.awaitIndex` gives `ProcedureTimedOut` for an index still populating when the wait ran out, and another code for one that failed. */
    override fun isBuildTimeout(error: StoreError): Boolean = error.causes.any { it.isNeo4jError && it.code == PROCEDURE_TIMED_OUT }

    override fun elementId(variable: String): String = "$elementIdFunction($variable)"

    private fun similarityFromName(name: String): SimilarityFunction? = when (name.lowercase()) {
        "cosine" -> SimilarityFunction.COSINE
        "euclidean" -> SimilarityFunction.EUCLIDEAN
        else -> null
    }

    private companion object {
        /** A uniqueness constraint violated, and the index entry conflict beneath it. */
        val VIOLATED = setOf("22N79", "22N80")

        /** An unexpected error, which the driver also gives an error from a server that sends no status. */
        const val UNEXPECTED = "50N42"

        /** Statuses that name no cause: a constraint not made (`50N11`), and an unexpected error. */
        val GENERAL_STATUSES = setOf("50N11", UNEXPECTED)

        /** A write a constraint refuses. */
        const val VALIDATION_FAILED = "Neo.ClientError.Schema.ConstraintValidationFailed"

        /** A constraint not made, for whatever reason. */
        const val CREATION_FAILED = "Neo.DatabaseError.Schema.ConstraintCreationFailed"

        /** An index over the schema a constraint would make its own index over. */
        const val INDEX_ALREADY_EXISTS = "Neo.ClientError.Schema.IndexAlreadyExists"

        /** A procedure whose wait ran out: `db.awaitIndex`'s, for an index still populating. */
        const val PROCEDURE_TIMED_OUT = "Neo.ClientError.Procedure.ProcedureTimedOut"
    }
}

/**
 * Whether the messages of [error]'s chain say a constraint is violated: Memgraph's and FalkorDB's errors carry no code
 * that is specific, so the words decide, in any error of the chain, across them.
 */
private fun saysViolated(error: StoreError): Boolean {
    val messages = error.messages
    return messages.contains("constraint", ignoreCase = true) && messages.contains("violat", ignoreCase = true)
}

/**
 * Memgraph's schema: `SHOW INDEX INFO` and `SHOW CONSTRAINT INFO` read as rows of columns, and its vector indexes from
 * `vector_search.show_index_info()` as maps; a label read with or without the colon Memgraph 3.11 puts before it.
 */
@DrivineInternalApi
class MemgraphSchema(override val ddl: MemgraphDdl = MemgraphDdl()) : StoreSchema {

    override val supportsIfNotExists = false
    override val supportsNamedItems = true

    /**
     * A vector row is a map, of `vector_search.show_index_info()`'s `RETURN {…}`; a range or text row is a list of
     * `SHOW INDEX INFO`'s columns (index type, label, property, count), its type telling them apart: a text index is
     * `label_text (name: <name>)`.
     */
    override fun parseIndexRows(rows: List<Any?>): List<SchemaItemInfo> = rows.mapNotNull { row ->
        when (row) {
            is Map<*, *> -> parseVectorIndexRow(row)
            is List<*> -> parseTextIndexRow(row) ?: parseLabelPropertyIndexRow(row)
            else -> null
        }
    }

    /** A text index, a full-text one: `["label_text (name: chunk_ft)", "Chunk", ["title", "body"], 0]`. Null for another row, which the range parser reads. */
    private fun parseTextIndexRow(row: List<*>): SchemaItemInfo? {
        if (row.size < 3) return null
        val indexType = (row[0] as? String)?.lowercase() ?: return null
        if (!indexType.startsWith("label_text")) return null
        val label = (row[1] as? String)?.let(::normalizeLabel) ?: return null
        val properties = propertiesOf(row[2]) ?: return null
        if (properties.isEmpty()) return null
        return SchemaItemInfo(
            kind = SchemaItemKind.FULLTEXT_INDEX,
            label = label,
            properties = properties,
            name = TEXT_INDEX_NAME.find(row[0] as String)?.groupValues?.get(1),
        )
    }

    private fun parseVectorIndexRow(map: Map<*, *>): SchemaItemInfo? {
        val label = (map["label"] as? String)?.let(::normalizeLabel) ?: return null
        val property = map["property"] as? String ?: return null
        return SchemaItemInfo(
            kind = SchemaItemKind.VECTOR_INDEX,
            label = label,
            properties = listOf(property),
            name = map["index_name"] as? String,
            dimensions = (map["dimension"] as? Number)?.toInt(),
            similarity = (map["metric"] as? String)?.let(::metricFromName),
        )
    }

    /**
     * A range index: `label+property`. A label-only index is not Drivine's, and `label+property_vector`, a vector index's
     * backing that Memgraph 3.11 lists here, is read from the vector listing, not as a range index.
     */
    private fun parseLabelPropertyIndexRow(row: List<*>): SchemaItemInfo? {
        if (row.size < 3) return null
        val indexType = (row[0] as? String)?.lowercase() ?: return null
        if (!indexType.startsWith("label+prop") || indexType.contains("vector")) return null
        val label = (row[1] as? String)?.let(::normalizeLabel) ?: return null
        val properties = propertiesOf(row[2]) ?: return null
        if (properties.isEmpty()) return null
        return SchemaItemInfo(SchemaItemKind.RANGE_INDEX, label, properties, name = null) // unnamed on Memgraph
    }

    /** `SHOW CONSTRAINT INFO`'s columns: constraint type, label, properties. */
    override fun parseConstraintRows(rows: List<Any?>): List<SchemaItemInfo> = rows.mapNotNull { row ->
        val cols = row as? List<*> ?: return@mapNotNull null
        if (cols.size < 3) return@mapNotNull null
        if ((cols[0] as? String)?.lowercase() != "unique") return@mapNotNull null
        val label = (cols[1] as? String)?.let(::normalizeLabel) ?: return@mapNotNull null
        val properties = propertiesOf(cols[2]) ?: return@mapNotNull null
        if (properties.isEmpty()) return@mapNotNull null
        SchemaItemInfo(SchemaItemKind.UNIQUENESS_CONSTRAINT, label, properties, name = null) // unnamed on Memgraph
    }

    /**
     * By the message: Memgraph gives every error one code, `Memgraph.ClientError.MemgraphError.MemgraphError`. "Unable to
     * create unique constraint :Label(prop), because an existing node violates it." Its other refusals ("Constraints are
     * not supported in analytical storage mode") say nothing of a violation.
     */
    override fun isViolation(error: StoreError): Boolean = saysViolated(error)

    /** A column of one property, or of a list of them. */
    private fun propertiesOf(column: Any?): List<String>? = when (column) {
        is String -> listOf(column)
        is List<*> -> column.filterIsInstance<String>()
        else -> null
    }

    /** A label without the colon Memgraph 3.11 puts before it, which earlier versions don't, so that it matches a spec's. */
    private fun normalizeLabel(raw: String): String = raw.removePrefix(":")

    private fun metricFromName(metric: String): SimilarityFunction? = when (metric.lowercase()) {
        "cos" -> SimilarityFunction.COSINE
        "l2sq" -> SimilarityFunction.EUCLIDEAN
        else -> null
    }

    private companion object {
        /** `<name>` in a text index's type, `label_text (name: <name>)`. */
        val TEXT_INDEX_NAME = Regex("""name:\s*([^)]+)\)""")
    }
}

/**
 * FalkorDB's schema: `db.indexes()` gives one row a label, whose `types` say which kinds index each property, so a range or
 * full-text index is found by coverage; items have no names; and a constraint needs its index first, and is built, as an
 * index is, after its statement returns.
 */
@DrivineInternalApi
object FalkorDbSchema : StoreSchema {

    override val ddl: StoreDdl = FalkorDbDdl
    override val supportsIfNotExists = false
    override val supportsNamedItems = false
    override val constraintsRequireBackingIndex = true
    override val constraintCreationIsAsync = true
    override val indexOperationsAreAsync = true

    /** FalkorDB makes the portable HNSW parameters (as `M` and `efConstruction`) and its own `efRuntime`. */
    override fun unsupportedVectorTuning(spec: VectorIndexSpec): List<String> = emptyList()

    /**
     * One row is every index of a label. Its range properties are one item, its full-text properties another, as
     * FalkorDB keeps one index of each kind a label (a spec over two, made one property at a time, reads back as one), and
     * each vector property an item of its own. Each has the label's status, `UNDER CONSTRUCTION` while any is built.
     */
    override fun parseIndexRows(rows: List<Any?>): List<SchemaItemInfo> = rows.flatMap { row ->
        val map = row as? Map<*, *> ?: return@flatMap emptyList()
        val entityType = (map["entitytype"] as? String)?.uppercase()
        if (entityType != null && entityType != "NODE") return@flatMap emptyList()
        val label = map["label"] as? String ?: return@flatMap emptyList()
        val types = map["types"] as? Map<*, *> ?: return@flatMap emptyList()
        val options = map["options"] as? Map<*, *>
        val status = map["status"] as? String

        fun propertiesOf(type: String) = types.entries.filter { (_, propTypes) -> hasType(propTypes, type) }.mapNotNull { it.key as? String }

        val items = mutableListOf<SchemaItemInfo>()
        val rangeProperties = propertiesOf("RANGE")
        if (rangeProperties.isNotEmpty()) {
            items += SchemaItemInfo(SchemaItemKind.RANGE_INDEX, label, rangeProperties, name = null, status = status)
        }
        val fullTextProperties = propertiesOf("FULLTEXT")
        if (fullTextProperties.isNotEmpty()) {
            items += SchemaItemInfo(SchemaItemKind.FULLTEXT_INDEX, label, fullTextProperties, name = null, status = status)
        }
        for (property in propertiesOf("VECTOR")) {
            val vectorOptions = vectorOptionsFor(options, property)
            items += SchemaItemInfo(
                kind = SchemaItemKind.VECTOR_INDEX,
                label = label,
                properties = listOf(property),
                name = null,
                dimensions = (vectorOptions?.get("dimension") as? Number)?.toInt(),
                similarity = (vectorOptions?.get("similarityFunction") as? String)?.let(::similarityFromName),
                // FalkorDB reports its HNSW parameters, its defaults where none was pinned (M 16, efConstruction 200).
                hnswM = (vectorOptions?.get("M") as? Number)?.toInt(),
                hnswEfConstruction = (vectorOptions?.get("efConstruction") as? Number)?.toInt(),
                status = status,
            )
        }
        items
    }

    /** The options of a vector property: FalkorDB 4 keys them by property, and older versions give one flat map. */
    private fun vectorOptionsFor(options: Map<*, *>?, property: String): Map<*, *>? {
        val perProperty = options?.get(property) as? Map<*, *>
        if (perProperty != null && perProperty.containsKey("dimension")) return perProperty
        if (options != null && options.containsKey("dimension")) return options
        return perProperty
    }

    private fun hasType(propTypes: Any?, type: String): Boolean = when (propTypes) {
        is List<*> -> propTypes.any { (it as? String)?.equals(type, ignoreCase = true) == true }
        is String -> propTypes.equals(type, ignoreCase = true)
        else -> false
    }

    override fun parseConstraintRows(rows: List<Any?>): List<SchemaItemInfo> = rows.mapNotNull { row ->
        val map = row as? Map<*, *> ?: return@mapNotNull null
        if ((map["type"] as? String)?.lowercase() != "unique") return@mapNotNull null
        val entityType = (map["entitytype"] as? String)?.uppercase()
        if (entityType != null && entityType != "NODE") return@mapNotNull null
        val label = map["label"] as? String ?: return@mapNotNull null
        val properties = (map["properties"] as? List<*>)?.filterIsInstance<String>() ?: return@mapNotNull null
        if (properties.isEmpty()) return@mapNotNull null
        SchemaItemInfo(SchemaItemKind.UNIQUENESS_CONSTRAINT, label, properties, name = null, status = map["status"] as? String)
    }

    /**
     * A range or full-text index is one a label over many properties, so it is the spec's where it covers all the spec's
     * properties, not only where they are the same. A vector index and a constraint are matched as on the other stores.
     */
    override fun matchesIdentity(existing: SchemaItemInfo, spec: SchemaItemSpec): Boolean {
        if (existing.kind != spec.kind || existing.label != spec.label) return false
        return when (spec) {
            is RangeIndexSpec, is FullTextIndexSpec -> existing.properties.containsAll(spec.properties)
            else -> existing.properties.toSet() == spec.properties.toSet()
        }
    }

    /**
     * By the message, as on Memgraph. A violation is mostly a constraint listed `FAILED` once it is built
     * ([constraintCreationIsAsync]); this covers what the driver refuses when the command is sent.
     */
    override fun isViolation(error: StoreError): Boolean = saysViolated(error)

    private fun similarityFromName(name: String): SimilarityFunction? = when (name.lowercase()) {
        "cosine" -> SimilarityFunction.COSINE
        "euclidean" -> SimilarityFunction.EUCLIDEAN
        else -> null
    }
}
