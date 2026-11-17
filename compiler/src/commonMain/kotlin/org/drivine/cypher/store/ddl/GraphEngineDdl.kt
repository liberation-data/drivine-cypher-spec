package org.drivine.cypher.store.ddl

import org.drivine.DrivineInternalApi
import org.drivine.cypher.CompilerException
import org.drivine.schema.ConstraintSpec
import org.drivine.schema.FalkorDbVectorOptions
import org.drivine.schema.FullTextIndexSpec
import org.drivine.schema.IndexSpec
import org.drivine.schema.Neo4jVectorOptions
import org.drivine.schema.RangeIndexSpec
import org.drivine.schema.SchemaItemKind
import org.drivine.schema.SimilarityFunction
import org.drivine.schema.TextIndexSpec
import org.drivine.schema.UniquenessConstraintSpec
import org.drivine.schema.VectorIndexSpec

/** How the graph engines' schema DDL writes a name: as the three libraries' grammars wrote it. */
@DrivineInternalApi
object CypherNames {

    private val PLAIN_IDENTIFIER = Regex("[A-Za-z_][A-Za-z0-9_]*")

    /** The Unicode escape of a backtick, with as many `u` as an escape may have. */
    private val ESCAPED_BACKTICK = Regex("\\\\u+0060")

    /** [name] bare where Cypher reads it as one identifier as it is, and [quoted] where it does not. */
    fun identifier(name: String): String = if (PLAIN_IDENTIFIER.matches(name)) name else quoted(name)

    /**
     * [name] between backticks, as one identifier whatever it holds: a backtick in it is doubled, as Cypher escapes them.
     * Neo4j decodes a `\uXXXX` escape before it reads the statement, inside backticks as outside, so the escape of a
     * backtick ends the quotes as a backtick does. It is doubled as one, and a name can end its own quotes in neither
     * spelling.
     */
    fun quoted(name: String): String = "`${name.replace(ESCAPED_BACKTICK, "`").replace("`", "``")}`"

    /** The Unicode escape of a single quote, with as many `u` as an escape may have. */
    private val ESCAPED_QUOTE = Regex("\\\\u+0027")

    /**
     * [text] as a Cypher string literal, between single quotes, whatever it holds: a backslash and a quote in it escaped with
     * a backslash, as Cypher reads them, so it is one string on Neo4j, Memgraph and FalkorDB (liberation-data/drivine4j#767).
     * The `\uXXXX` escape of a quote is taken for the quote, as [quoted] takes that of a backtick: Neo4j decodes it inside the
     * backticks of the DDL that names the index, so the name searched is the name Neo4j made. A doubled backslash already
     * keeps an escape in [text] from closing the quotes.
     */
    fun string(text: String): String = "'${text.replace(ESCAPED_QUOTE, "'").replace("\\", "\\\\").replace("'", "\\'")}'"
}

/** The name the graph engines give [similarity]. */
private fun similarityName(similarity: SimilarityFunction): String = similarity.name.lowercase()

/**
 * Neo4j's schema DDL, 4.x to 2026: every name backtick-quoted, `IF NOT EXISTS` and `IF EXISTS` guards, items named and
 * dropped by name, a TEXT index of its own beside a FULLTEXT one, and a vector index configured by backtick-quoted dotted
 * keys. Listed by `SHOW INDEXES` and `SHOW CONSTRAINTS`.
 */
@DrivineInternalApi
object Neo4jDdl : StoreDdl {

    override val engine = "Neo4j"

    private fun id(name: String): String = CypherNames.quoted(name)

    private fun text(statement: String): List<DdlStatement> = listOf(DdlStatement.Text(statement))

    override fun createIndex(spec: IndexSpec, existing: CatalogItem?): List<DdlStatement> = when (spec) {
        is VectorIndexSpec -> text(
            """
            CREATE VECTOR INDEX ${id(spec.effectiveName)} IF NOT EXISTS
            FOR (n:${id(spec.label)}) ON (n.${id(spec.property)})
            OPTIONS { indexConfig: { ${vectorIndexConfig(spec)} }}
            """.trimIndent()
        )
        is RangeIndexSpec -> text("CREATE INDEX ${id(spec.effectiveName)} IF NOT EXISTS FOR (n:${id(spec.label)}) ON ${propertyList(spec.properties)}")
        is FullTextIndexSpec -> text(
            "CREATE FULLTEXT INDEX ${id(spec.effectiveName)} IF NOT EXISTS " +
                "FOR (n:${id(spec.label)}) ON EACH ${eachPropertyList(spec.properties)} " +
                "OPTIONS {indexConfig: {${analyzerConfig(spec.analyzer)}}}"
        )
        is TextIndexSpec -> text("CREATE TEXT INDEX ${id(spec.effectiveName)} IF NOT EXISTS FOR (n:${id(spec.label)}) ON (n.${id(spec.property)})")
    }

    override fun dropIndex(item: CatalogItem): List<DdlStatement> {
        val name = item.name ?: throw CompilerException(
            "Cannot drop index on ${item.label}${item.properties}: Neo4j drops indexes by name, but no name is known for this index"
        )
        return text("DROP INDEX ${id(name)} IF EXISTS")
    }

    override fun createConstraint(spec: ConstraintSpec): List<DdlStatement> = when (spec) {
        is UniquenessConstraintSpec -> text(
            "CREATE CONSTRAINT ${id(spec.effectiveName)} IF NOT EXISTS FOR (n:${id(spec.label)}) REQUIRE ${propertyList(spec.properties)} IS UNIQUE"
        )
    }

    override fun dropConstraint(item: CatalogItem): List<DdlStatement> {
        val name = item.name ?: throw CompilerException(
            "Cannot drop constraint on ${item.label}${item.properties}: Neo4j drops constraints by name, but no name is known for this constraint"
        )
        return text("DROP CONSTRAINT ${id(name)} IF EXISTS")
    }

    override fun listIndexesQuery(kind: SchemaItemKind): String = """
        SHOW INDEXES
        YIELD name, type, entityType, labelsOrTypes, properties, options
        RETURN {name: name, type: type, entityType: entityType, labelsOrTypes: labelsOrTypes, properties: properties, options: options}
    """.trimIndent()

    override fun listConstraintsQuery(): String = """
        SHOW CONSTRAINTS
        YIELD name, type, entityType, labelsOrTypes, properties
        RETURN {name: name, type: type, entityType: entityType, labelsOrTypes: labelsOrTypes, properties: properties}
    """.trimIndent()

    /** Always parenthesised, one property or several. */
    private fun propertyList(properties: List<String>): String = properties.joinToString(", ", "(", ")") { "n.${id(it)}" }

    /** Full-text's `ON EACH` takes a bracketed list, not the parenthesised one the other kinds use. */
    private fun eachPropertyList(properties: List<String>): String = properties.joinToString(", ", "[", "]") { "n.${id(it)}" }

    /** Empty when no analyzer is declared, so Neo4j applies its own default rather than one that could drift from it. */
    private fun analyzerConfig(analyzer: String?): String = analyzer?.let { "`fulltext.analyzer`: ${CypherNames.string(it)}" } ?: ""

    /**
     * The `indexConfig` body for a vector index, on one line. Dimensions and similarity are always written; the physical
     * parameters only when the spec pins them, so an unpinned schema keeps taking the engine's default.
     */
    private fun vectorIndexConfig(spec: VectorIndexSpec): String {
        val tuning = spec.tuningFor(engine)
        return listOfNotNull(
            "`vector.dimensions`: ${spec.dimensions}",
            "`vector.similarity_function`: '${similarityName(spec.similarity)}'",
            // `enabled`, not `type`: every Neo4j from 5.x to 2026.10 accepts `enabled`, and `vector.quantization.type` is refused before 2026.06.
            (spec.optionsFor(Neo4jVectorOptions.ENGINE) as? Neo4jVectorOptions)?.quantizationEnabled?.let { "`vector.quantization.enabled`: $it" },
            tuning.hnswM?.let { "`vector.hnsw.m`: $it" },
            tuning.hnswEfConstruction?.let { "`vector.hnsw.ef_construction`: $it" },
        ).joinToString(", ")
    }
}

/**
 * Memgraph's schema DDL: no `IF NOT EXISTS` guards, so a library looks before it makes or drops; unnamed range indexes and
 * constraints, dropped by their label and properties; a named vector index configured `WITH CONFIG`, with uSearch's
 * metric names; and its TEXT index, which is full-text.
 *
 * @param vectorIndexCapacity the capacity a vector index is made with, which Memgraph grows from
 */
@DrivineInternalApi
class MemgraphDdl(private val vectorIndexCapacity: Int = DEFAULT_VECTOR_CAPACITY) : StoreDdl {

    override val engine = "Memgraph"

    private fun id(name: String): String = CypherNames.identifier(name)

    private fun ids(names: List<String>): String = names.joinToString(", ") { id(it) }

    private fun text(statement: String): List<DdlStatement> = listOf(DdlStatement.Text(statement))

    override fun createIndex(spec: IndexSpec, existing: CatalogItem?): List<DdlStatement> = when (spec) {
        is VectorIndexSpec -> text(
            """
            CREATE VECTOR INDEX ${id(spec.effectiveName)} ON :${id(spec.label)}(${id(spec.property)})
            WITH CONFIG {"dimension": ${spec.dimensions}, "metric": "${metricName(spec.similarity)}", "capacity": $vectorIndexCapacity}
            """.trimIndent()
        )
        is RangeIndexSpec -> text("CREATE INDEX ON :${id(spec.label)}(${ids(spec.properties)})")
        // Memgraph calls it a TEXT index, and it is named and over several properties as Neo4j's full-text index is.
        is FullTextIndexSpec -> text("CREATE TEXT INDEX ${id(spec.effectiveName)} ON :${id(spec.label)}(${ids(spec.properties)})")
        // Memgraph's TEXT INDEX is full-text, made above from a FullTextIndexSpec; Neo4j's has no equivalent here.
        is TextIndexSpec -> throw unsupportedTextIndex(spec, engine)
    }

    override fun dropIndex(item: CatalogItem): List<DdlStatement> = when (item.kind) {
        SchemaItemKind.VECTOR_INDEX -> text("DROP VECTOR INDEX ${id(item.name ?: "${item.label}_${item.properties.first()}_vector")}")
        SchemaItemKind.FULLTEXT_INDEX -> text("DROP TEXT INDEX ${id(item.name ?: "${item.label}_${item.properties.joinToString("_")}_fulltext")}")
        else -> text("DROP INDEX ON :${id(item.label)}(${ids(item.properties)})")
    }

    override fun createConstraint(spec: ConstraintSpec): List<DdlStatement> = when (spec) {
        is UniquenessConstraintSpec -> text(
            "CREATE CONSTRAINT ON (n:${id(spec.label)}) ASSERT ${spec.properties.joinToString(", ") { "n.${id(it)}" }} IS UNIQUE"
        )
    }

    override fun dropConstraint(item: CatalogItem): List<DdlStatement> = text(
        "DROP CONSTRAINT ON (n:${id(item.label)}) ASSERT ${item.properties.joinToString(", ") { "n.${id(it)}" }} IS UNIQUE"
    )

    override fun listIndexesQuery(kind: SchemaItemKind): String = when (kind) {
        SchemaItemKind.VECTOR_INDEX -> """
            CALL vector_search.show_index_info()
            YIELD index_name, label, property, dimension, metric
            RETURN {index_name: index_name, label: label, property: property, dimension: dimension, metric: metric}
        """.trimIndent()
        // Range and text (full-text) indexes both are listed by SHOW INDEX INFO.
        else -> "SHOW INDEX INFO"
    }

    override fun listConstraintsQuery(): String = "SHOW CONSTRAINT INFO"

    /** uSearch's name of [similarity], which Memgraph's vector index takes. */
    private fun metricName(similarity: SimilarityFunction): String = when (similarity) {
        SimilarityFunction.COSINE -> "cos"
        SimilarityFunction.EUCLIDEAN -> "l2sq"
    }

    companion object {
        const val DEFAULT_VECTOR_CAPACITY = 10_000
    }
}

/**
 * FalkorDB's schema DDL: no guards and no names, so an item is its label and properties; one index per label over many
 * properties, which refuses a property indexed already, so a range or full-text index adds only the properties the label's
 * index lacks ([createIndex]'s `existing`); and uniqueness constraints made by the Redis command `GRAPH.CONSTRAINT`, a
 * [DdlStatement.Native], over an exact-match index made first.
 */
@DrivineInternalApi
object FalkorDbDdl : StoreDdl {

    override val engine = "FalkorDB"

    /** A name `GRAPH.CONSTRAINT` takes, as FalkorDB checks it: it has no way to quote one (FalkorDB/FalkorDB#551). */
    val CONSTRAINABLE = Regex("[A-Za-z_][A-Za-z0-9_]*")

    private fun id(name: String): String = CypherNames.identifier(name)

    override fun createIndex(spec: IndexSpec, existing: CatalogItem?): List<DdlStatement> = when (spec) {
        is VectorIndexSpec -> listOf(
            DdlStatement.Text(
                """
                CREATE VECTOR INDEX FOR (n:${id(spec.label)}) ON (n.${id(spec.property)})
                OPTIONS {${vectorIndexOptions(spec)}}
                """.trimIndent()
            )
        )
        is RangeIndexSpec -> {
            val alreadyIndexed = existing?.properties?.toSet() ?: emptySet()
            val missing = spec.properties.filterNot { it in alreadyIndexed }
            if (missing.isEmpty()) emptyList()
            else listOf(DdlStatement.Text("CREATE INDEX FOR (n:${id(spec.label)}) ON " + missing.joinToString(", ", "(", ")") { "n.${id(it)}" }))
        }
        // One statement per property, the form FalkorDB 4.x and 6.x both take, for those the label's full-text index lacks.
        is FullTextIndexSpec -> {
            val alreadyIndexed = existing?.properties?.toSet() ?: emptySet()
            spec.properties.filterNot { it in alreadyIndexed }.map { property ->
                DdlStatement.Text("CREATE FULLTEXT INDEX FOR (n:${id(spec.label)}) ON (n.${id(property)})")
            }
        }
        is TextIndexSpec -> throw unsupportedTextIndex(spec, engine)
    }

    /** One drop per property, so that dropping a narrowed item never removes more than was asked for. */
    override fun dropIndex(item: CatalogItem): List<DdlStatement> {
        val kind = when (item.kind) {
            SchemaItemKind.VECTOR_INDEX -> "VECTOR INDEX"
            SchemaItemKind.FULLTEXT_INDEX -> "FULLTEXT INDEX"
            else -> "INDEX"
        }
        return item.properties.map { DdlStatement.Text("DROP $kind FOR (n:${id(item.label)}) ON (n.${id(it)})") }
    }

    /**
     * Refuses [spec] where `GRAPH.CONSTRAINT` can't make it: it takes a label and properties as plain identifiers, so a
     * property whose name holds a dot, a bag's key or a lowercased copy, can be indexed and not constrained.
     */
    fun requireConstrainable(spec: ConstraintSpec) {
        val refused = spec.properties.filterNot { CONSTRAINABLE.matches(it) }.ifEmpty { return }
        throw CompilerException(
            "FalkorDB cannot make the ${spec.kind} ${spec.effectiveName} on ${spec.label}${spec.properties}: GRAPH.CONSTRAINT takes only " +
                "a plain name for a property, and ${refused.joinToString { "'$it'" }} is not one. A uniqueness constraint that ignores " +
                "case is over the lowercased copy, named so. See https://github.com/FalkorDB/FalkorDB/issues/551"
        )
    }

    /** What makes [spec], once [requireConstrainable] has let it through. */
    override fun createConstraint(spec: ConstraintSpec): List<DdlStatement> = when (spec) {
        is UniquenessConstraintSpec -> listOf(constraint("CREATE", spec.label, spec.properties))
    }

    override fun dropConstraint(item: CatalogItem): List<DdlStatement> = listOf(constraint("DROP", item.label, item.properties))

    private fun constraint(command: String, label: String, properties: List<String>) = DdlStatement.Native(
        listOf("GRAPH.CONSTRAINT", command, DdlStatement.Native.GRAPH_NAME, "UNIQUE", "NODE", label, "PROPERTIES", properties.size.toString()) + properties
    )

    override fun listIndexesQuery(kind: SchemaItemKind): String = """
        CALL db.indexes()
        YIELD label, properties, types, options, entitytype, status
        RETURN {label: label, properties: properties, types: types, options: options, entitytype: entitytype, status: status}
    """.trimIndent()

    override fun listConstraintsQuery(): String = """
        CALL db.constraints()
        YIELD type, label, properties, entitytype, status
        RETURN {type: type, label: label, properties: properties, entitytype: entitytype, status: status}
    """.trimIndent()

    /** The `OPTIONS` of a vector index: FalkorDB names the HNSW parameters `M` and `efConstruction`, and adds `efRuntime`. */
    private fun vectorIndexOptions(spec: VectorIndexSpec): String {
        val tuning = spec.tuningFor(engine)
        val options = spec.optionsFor(engine) as? FalkorDbVectorOptions
        return listOfNotNull(
            "dimension: ${spec.dimensions}",
            "similarityFunction: '${similarityName(spec.similarity)}'",
            tuning.hnswM?.let { "M: $it" },
            tuning.hnswEfConstruction?.let { "efConstruction: $it" },
            options?.efRuntime?.let { "efRuntime: $it" },
        ).joinToString(", ")
    }
}
