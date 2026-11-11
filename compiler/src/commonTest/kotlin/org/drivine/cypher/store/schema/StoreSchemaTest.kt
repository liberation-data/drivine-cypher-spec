package org.drivine.cypher.store.schema

import org.drivine.cypher.CompilerException
import org.drivine.cypher.json.CypherJson
import org.drivine.cypher.json.JsonNode
import org.drivine.cypher.json.ObjectNode
import org.drivine.cypher.number.oracleFile
import org.drivine.cypher.store.ddl.FalkorDbDdl
import org.drivine.cypher.store.ddl.MemgraphDdl
import org.drivine.cypher.store.ddl.Neo4jDdl
import org.drivine.cypher.store.ddl.OracleDdl
import org.drivine.cypher.store.ddl.PostgresDdl
import org.drivine.cypher.store.ddl.SqliteDdl
import org.drivine.cypher.store.ddl.StoreDdl
import org.drivine.schema.ConstraintSpec
import org.drivine.schema.FalkorDbVectorOptions
import org.drivine.schema.FullTextIndexSpec
import org.drivine.schema.Neo4jVectorOptions
import org.drivine.schema.RangeIndexSpec
import org.drivine.schema.SchemaDeclarations
import org.drivine.schema.SchemaItemInfo
import org.drivine.schema.SchemaItemKind
import org.drivine.schema.SchemaItemSpec
import org.drivine.schema.SimilarityFunction
import org.drivine.schema.TextIndexSpec
import org.drivine.schema.UniquenessConstraintSpec
import org.drivine.schema.VectorIndexSpec
import org.drivine.schema.made
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertSame
import kotlin.test.assertTrue
import kotlin.test.fail

/**
 * The spec's `store.yaml`, its sections on the store's schema, against the module's `store.schema` and the catalog merge,
 * on each target: the JVM writes it as JSON with its answers (`./gradlew jvmOracle`, `store.json`), as the other targets
 * read no YAML.
 *
 * - each store says what it can do, and gives the await, state and sample statements, as drivine4j's grammar did;
 * - each listing's rows read back to the items drivine4j read, with the aliases each record keeps;
 * - each pair of an item and a spec is the same item, and in its shape, where drivine4j said so, on every store;
 * - each error is a violation, an index in the way, or a build that ran out, where drivine4j said so, on every store;
 * - a catalog's declarations merge to drivine4j's items, or are refused with its message.
 */
class StoreSchemaTest {

    private val store: JsonNode = CypherJson.readTree(oracleFile("store.json"))

    private val graph = store["graph"]!!.textValue()!!

    private val schemas: Map<String, StoreSchema> = linkedMapOf(
        "neo4j" to Neo4jSchema(),
        "memgraph" to MemgraphSchema(),
        "falkordb" to FalkorDbSchema,
        "postgres" to PostgresSchema(PostgresDdl(graph)),
        "oracle" to OracleSchema(OracleDdl(graph)),
        "sqlite" to SqliteSchema(SqliteDdl(graph)),
    )

    /** Every store a section answers for: each must answer for all of them, so a store left out fails rather than passes. */
    private fun stores(node: JsonNode): Map<String, JsonNode> {
        val answers = (node as ObjectNode).properties().associate { it.key to it.value }
        assertEquals(schemas.keys, answers.keys - "neo4j4", "the stores answered for")
        return answers
    }

    @Test
    fun eachStoreSaysWhatItCanDoAsDrivine4jsGrammarDid() {
        val pinned = store["schema"]!!
        for ((id, expected) in stores(pinned["stores"]!!)) {
            val schema = schemas.getValue(id)
            assertEquals(expected["engine"]!!.textValue(), schema.engine, "$id: engine")
            assertEquals(expected["supportsIfNotExists"]!!.booleanValue(), schema.supportsIfNotExists, "$id: supportsIfNotExists")
            assertEquals(expected["supportsNamedItems"]!!.booleanValue(), schema.supportsNamedItems, "$id: supportsNamedItems")
            assertEquals(expected["supportsTextIndex"]!!.booleanValue(), schema.supportsTextIndex, "$id: supportsTextIndex")
            assertEquals(expected["constraintsRequireBackingIndex"]!!.booleanValue(), schema.constraintsRequireBackingIndex, "$id: constraintsRequireBackingIndex")
            assertEquals(expected["constraintCreationIsAsync"]!!.booleanValue(), schema.constraintCreationIsAsync, "$id: constraintCreationIsAsync")
            assertEquals(expected["indexOperationsAreAsync"]!!.booleanValue(), schema.indexOperationsAreAsync, "$id: indexOperationsAreAsync")
            assertEquals(expected["awaitIndex"]!!.textValue(), schema.awaitIndexStatement, "$id: awaitIndex")
            assertEquals(expected["indexState"]!!.textValue(), schema.indexStateStatement, "$id: indexState")
        }
        for (case in pinned["tuning"]!!) {
            val spec = spec(case["spec"]!!) as VectorIndexSpec
            for ((id, expected) in stores(case["stores"]!!)) {
                assertEquals(texts(expected), schemas.getValue(id).unsupportedVectorTuning(spec), "$id: what $spec pins and the store can't make")
            }
        }
    }

    @Test
    fun eachStoreSamplesAConstraintsConflictsAsDrivine4jDid() {
        for (case in store["schema"]!!["samples"]!!) {
            val spec = spec(case["spec"]!!) as ConstraintSpec
            val limit = case["limit"]!!.longValue().toInt()
            for ((id, expected) in stores(case["stores"]!!)) {
                val schema = if (id == "neo4j4") Neo4jSchema("id") else schemas.getValue(id)
                assertEquals(expected.textValue(), schema.sampleConflictsQuery(spec, limit), "$id: the sample of $spec")
            }
        }
    }

    @Test
    fun eachListingIsReadBackAsDrivine4jReadIt() {
        for (case in store["listings"]!!) {
            val name = case["name"]!!.textValue()
            val listing = case["listing"]!!.textValue()
            val rows = case["rows"]!!.map(::plain)
            val answers = (case["stores"] as ObjectNode).properties()
            assertTrue(answers.isNotEmpty(), "$name: answered for no store")
            for ((id, expected) in answers) {
                val schema = schemas[id] ?: fail("$name: no store $id")
                fun parse(of: List<Any?>) = if (listing == "indexes") schema.parseIndexRows(of) else schema.parseConstraintRows(of)
                if (expected["invalid"]?.booleanValue() == true) {
                    assertFails("$id, $name: a record this module doesn't know is refused") { parse(rows) }
                    continue
                }
                assertEquals(expected["items"]!!.map(::item), parse(rows), "$id, $name")
                // The aliases of each item, each from the row it was read from.
                val aliases = rows.flatMap { row -> parse(listOf(row)).map { schema.aliasesOf(row) } }
                assertEquals(expected["aliases"]!!.map(::texts), aliases, "$id, $name: aliases")
            }
        }
    }

    @Test
    fun eachItemIsTheSpecsAndInItsShapeWhereDrivine4jSaidSo() {
        val matching = store["matching"]!!
        for (case in matching["pairs"]!!) {
            val name = case["name"]!!.textValue()
            val existing = item(case["existing"]!!)
            val spec = spec(case["spec"]!!)
            for ((id, expected) in stores(case["stores"]!!)) {
                val schema = schemas.getValue(id)
                assertEquals(expected["identity"]!!.booleanValue(), schema.matchesIdentity(existing, spec), "$id, $name: identity")
                assertEquals(expected["shape"]!!.booleanValue(), schema.matchesShape(existing, spec), "$id, $name: shape")
            }
        }
        for (case in matching["incomplete"]!!) {
            val item = item(case["item"]!!)
            for ((id, expected) in stores(case["stores"]!!)) {
                assertEquals(expected.booleanValue(), schemas.getValue(id).isIncomplete(item), "$id: whether $item is incomplete")
            }
        }
        for (case in matching["builds"]!!) {
            val item = SchemaItemInfo(SchemaItemKind.RANGE_INDEX, "Person", listOf("name"), status = case["status"]!!.textValue())
            assertEquals(case["buildFailed"]!!.booleanValue(), item.buildFailed, "${item.status}: failed")
            assertEquals(case["buildInProgress"]!!.booleanValue(), item.buildInProgress, "${item.status}: in progress")
        }
    }

    @Test
    fun eachErrorIsClassifiedAsDrivine4jClassifiedIt() {
        for (case in store["errors"]!!) {
            val name = case["name"]!!.textValue()
            val error = StoreError(case["causes"]!!.map(::cause))
            for ((id, expected) in stores(case["stores"]!!)) {
                val schema = schemas.getValue(id)
                assertEquals(expected["violation"]!!.booleanValue(), schema.isViolation(error), "$id, $name: a violation")
                assertEquals(expected["blockedByIndex"]!!.booleanValue(), schema.isBlockedByIndex(error), "$id, $name: an index in the way")
                assertEquals(expected["buildTimeout"]!!.booleanValue(), schema.isBuildTimeout(error), "$id, $name: a build that ran out")
            }
        }
    }

    @Test
    fun aNeo4jErrorWithNoStatusIsReadAsDrivine4jsDriverGivesIt() {
        // drivine4j's driver gives 50N42 where a server sent no status; a driver that gives none is read alike.
        val refusal = Cause(code = "Neo.DatabaseError.Schema.ConstraintCreationFailed", message = "Unable to create Constraint( name='x' ):")
        assertTrue(Neo4jSchema().isViolation(StoreError(refusal)))
        assertEquals(Neo4jSchema().isViolation(StoreError(refusal.copy(gqlStatus = "50N42"))), Neo4jSchema().isViolation(StoreError(refusal)))
        assertFalse(Neo4jSchema().isViolation(StoreError(refusal, Cause(code = "N/A", gqlStatus = "51N27"))))
    }

    @Test
    fun declarationsMergeAsDrivine4jMergedThem() {
        for (case in store["declarations"]!!) {
            val name = case["name"]!!.textValue()
            val specs = case["specs"]!!.map(::spec)
            val conflict = case["conflict"]?.textValue()
            if (conflict != null) {
                val refused = assertFailsWith<CompilerException>(name) { SchemaDeclarations.deduplicate(specs) }
                assertEquals(conflict, refused.message, name)
            } else {
                assertEquals(case["items"]!!.map(::spec), SchemaDeclarations.deduplicate(specs), name)
            }
        }
    }

    @Test
    fun eachSchemaReadsTheListingsItsStoresDdlWrites() {
        val ddls: List<StoreDdl> = listOf(Neo4jDdl, MemgraphDdl(), FalkorDbDdl, PostgresDdl(graph), OracleDdl(graph), SqliteDdl(graph))
        for (ddl in ddls) {
            val schema = StoreSchema.of(ddl)
            assertSame(ddl, schema.ddl, "${ddl.engine}: the schema reads its own DDL's listings")
            assertEquals(ddl.engine, schema.engine)
        }
        assertEquals(schemas.values.map { it::class }, ddls.map { StoreSchema.of(it)::class })
        val item = SchemaItemInfo(SchemaItemKind.RANGE_INDEX, "Person", listOf("name"), name = "n", status = "OPERATIONAL")
        assertEquals(listOf("n"), listOf(item.catalogItem.name))
        assertNotNull(schemas.getValue("postgres").ddl.drop(item.catalogItem).singleOrNull())
    }

    /**
     * Each SQL store's listing, its rows captured from the store, says the form of each item it lists, and tells another
     * item under a name Drivine gives from the item: [StoreSchema.formOf], over the spec as it is made.
     */
    @Test
    fun eachListingSaysTheFormOfEachItemAsTheStoreGaveIt() {
        var checked = 0
        for (case in store["forms"]!!) {
            val name = case["name"]!!.textValue()
            val id = case["store"]!!.textValue()!!
            val schema = schemas.getValue(id)
            val rows = case["rows"]!!.map(::plain)
            for (entry in case["items"]!!) {
                val spec = spec(entry["spec"]!!).made()
                assertEquals(form(entry["form"]!!), schema.formOf(spec, rows), "$id, $name: the form of $spec")
                checked++
            }
            // The records read back as items, the store's objects beside them left out.
            case["listed"]?.let { listed ->
                val parsed = if (case["listing"]!!.textValue() == "constraints") schema.parseConstraintRows(rows) else schema.parseIndexRows(rows)
                assertEquals(listed.map(::item), parsed, "$id, $name: the items listed")
            }
        }
        assertTrue(checked > 0, "store.yaml pins no form")
    }

    private fun form(node: JsonNode): ItemForm = when {
        node.isTextual && node.textValue() == "current" -> ItemForm.Current
        node["stale"] != null -> ItemForm.Stale(node["stale"]!!.textValue()!!, org.drivine.cypher.store.ddl.SqlStoreDdl.CURRENT_FORM)
        node["newer"] != null -> ItemForm.Newer(node["newer"]!!.longValue().toInt(), org.drivine.cypher.store.ddl.SqlStoreDdl.CURRENT_FORM)
        node["mismatch"] != null -> node["mismatch"]!!.let {
            ItemForm.Mismatch(it["name"]!!.textValue()!!, it["reason"]!!.textValue()!!, it["droppable"]!!.booleanValue())
        }
        else -> fail("no form $node")
    }

    /** The value [node] holds, as a driver gives a row's: a map, a list, text, a whole number as a Long, a boolean or null. */
    private fun plain(node: JsonNode): Any? = when {
        node.isNull -> null
        node.isTextual -> node.textValue()
        node.isBoolean -> node.booleanValue()
        node.isIntegralNumber -> node.longValue()
        node.isNumber -> node.doubleValue()
        node.isArray -> node.map(::plain)
        node is ObjectNode -> node.properties().associate { it.key to plain(it.value) }
        else -> fail("not a value: $node")
    }

    /** An item as `store.yaml` writes it, with what it doesn't give left null. */
    private fun item(node: JsonNode): SchemaItemInfo = SchemaItemInfo(
        kind = SchemaItemKind.valueOf(node["kind"]!!.textValue()!!),
        label = node["label"]!!.textValue()!!,
        properties = texts(node["properties"]!!),
        name = node["name"]?.textValue(),
        dimensions = node["dimensions"]?.longValue()?.toInt(),
        similarity = node["similarity"]?.textValue()?.let(SimilarityFunction::valueOf),
        analyzer = node["analyzer"]?.textValue(),
        status = node["status"]?.textValue(),
        quantizationEnabled = node["quantizationEnabled"]?.booleanValue(),
        hnswM = node["hnswM"]?.longValue()?.toInt(),
        hnswEfConstruction = node["hnswEfConstruction"]?.longValue()?.toInt(),
    )

    /** One error of a chain as `store.yaml` writes it. */
    private fun cause(node: JsonNode): Cause = Cause(
        sqlState = node["sqlState"]?.textValue(),
        vendorCode = node["vendorCode"]?.longValue()?.toInt(),
        code = node["code"]?.textValue(),
        gqlStatus = node["gqlStatus"]?.textValue(),
        message = node["message"]?.textValue(),
    )

    /** A spec as `store.yaml`'s cases write one, with a constraint's `nodeIdProperty` beside. */
    private fun spec(node: JsonNode): SchemaItemSpec {
        val label = node["label"]!!.textValue()!!
        val properties = texts(node["properties"]!!)
        val name = node["name"]?.textValue()
        val ignoreCase = node["ignoreCase"]?.booleanValue() ?: false
        val options = node["options"]
        fun option(key: String) = options?.get(key)?.takeUnless { it.isNull }
        return when (SchemaItemKind.valueOf(node["kind"]!!.textValue()!!)) {
            SchemaItemKind.RANGE_INDEX -> RangeIndexSpec(label, properties, name, ignoreCase)
            SchemaItemKind.UNIQUENESS_CONSTRAINT -> UniquenessConstraintSpec(label, properties, name, node["nodeIdProperty"]?.textValue(), ignoreCase)
            SchemaItemKind.FULLTEXT_INDEX -> FullTextIndexSpec(label, properties, name, option("analyzer")?.textValue())
            SchemaItemKind.TEXT_INDEX -> TextIndexSpec(label, properties.single(), name, ignoreCase)
            SchemaItemKind.VECTOR_INDEX -> VectorIndexSpec(
                label, properties.single(), option("dimensions")!!.longValue().toInt(),
                SimilarityFunction.valueOf(option("similarity")!!.textValue()!!), name,
                option("hnswM")?.longValue()?.toInt(), option("hnswEfConstruction")?.longValue()?.toInt(),
                listOfNotNull(
                    option("quantizationEnabled")?.let { Neo4jVectorOptions(quantizationEnabled = it.booleanValue()) },
                    option("efRuntime")?.let { FalkorDbVectorOptions(efRuntime = it.longValue().toInt()) },
                ),
            )
        }
    }

    private fun texts(node: JsonNode): List<String> = node.map { it.textValue()!! }

    private fun assertFails(message: String, block: () -> Unit) {
        val failed = try {
            block()
            false
        } catch (_: Throwable) {
            true
        }
        assertTrue(failed, message)
    }
}
