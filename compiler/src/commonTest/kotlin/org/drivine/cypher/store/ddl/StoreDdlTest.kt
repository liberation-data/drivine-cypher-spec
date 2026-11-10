package org.drivine.cypher.store.ddl

import org.drivine.cypher.CompilerException
import org.drivine.cypher.api.Compiled
import org.drivine.cypher.api.CypherCompiler
import org.drivine.cypher.api.GraphOptions
import org.drivine.cypher.api.Refusal
import org.drivine.cypher.api.Store
import org.drivine.cypher.json.CypherJson
import org.drivine.cypher.json.JsonNode
import org.drivine.cypher.json.ObjectNode
import org.drivine.cypher.number.oracleFile
import org.drivine.cypher.number.target
import org.drivine.schema.ConstraintSpec
import org.drivine.schema.FalkorDbVectorOptions
import org.drivine.schema.FullTextIndexSpec
import org.drivine.schema.Neo4jVectorOptions
import org.drivine.schema.RangeIndexSpec
import org.drivine.schema.SchemaItemKind
import org.drivine.schema.SchemaItemSpec
import org.drivine.schema.SimilarityFunction
import org.drivine.schema.TextIndexSpec
import org.drivine.schema.UniquenessConstraintSpec
import org.drivine.schema.VectorIndexSpec
import org.drivine.schema.made
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.test.fail

/**
 * The spec's `store.yaml`, the store layout's DDL, against the module's generators (`store.ddl`) and the compiler, on each
 * target: the JVM writes it as JSON with its answers (`./gradlew jvmOracle`, `store.json`), as the other targets read no YAML.
 *
 * - each generator gives the statements `store.yaml` holds, as drivine4j gave them: the tables and the probe, each case's
 *   index or constraint made and dropped, the catalog's statements, and the graph-name refusals;
 * - [SqlStoreDdl.objectName] gives each name `store.yaml` holds: Java's `String.hashCode`, written out, on every target;
 * - each case's read, compiled on each SQL store, holds the expressions the case's index is made over, so the store's
 *   planner can read the index.
 */
class StoreDdlTest {

    private val store: JsonNode = CypherJson.readTree(oracleFile("store.json"))

    private val graph = store["graph"]!!.textValue()!!

    private val postgres = PostgresDdl(graph)
    private val oracle = OracleDdl(graph)
    private val sqlite = SqliteDdl(graph)

    private val stores: Map<String, StoreDdl> = linkedMapOf(
        "postgres" to postgres,
        "oracle" to oracle,
        "sqlite" to sqlite,
        "neo4j" to Neo4jDdl,
        "memgraph" to MemgraphDdl(),
        "falkordb" to FalkorDbDdl,
    )

    /** What a SQL store's create begins with, which `store.yaml` leaves out of each case: the statements that make the store. */
    private val tables: Map<String, List<String>> = mapOf(
        "postgres" to listOf(postgres.ensuring),
        "oracle" to listOf(oracle.ensuring),
        "sqlite" to sqlite.ddl,
    )

    @Test
    fun tablesAndProbeAreDrivine4js() {
        val tables = store["tables"]!!
        assertEquals(texts(tables["postgres"]!!["ddl"]!!), postgres.ddl, "postgres ddl")
        assertEquals(tables["postgres"]!!["ensuring"]!!.textValue(), postgres.ensuring, "postgres ensuring")
        assertEquals(texts(tables["oracle"]!!["ddl"]!!), oracle.ddl, "oracle ddl")
        assertEquals(texts(tables["oracle"]!!["functions"]!!), oracle.functions, "oracle functions")
        assertEquals(tables["oracle"]!!["ensuring"]!!.textValue(), oracle.ensuring, "oracle ensuring")
        assertEquals(texts(tables["sqlite"]!!["ddl"]!!), sqlite.ddl, "sqlite ddl")
        assertEquals(tables["sqlite"]!!["made"]!!.textValue(), sqlite.made, "sqlite made")
        assertEquals(tables["sqlite"]!!["file"]!!.textValue(), SqliteDdl.FILE, "sqlite file")
    }

    @Test
    fun eachIndexAndConstraintIsMadeAndDroppedAsDrivine4jDid() {
        var checked = 0
        for (case in store["cases"]!!) {
            val name = case["name"]!!.textValue()!!
            val made = spec(case["spec"]!!).made()
            val existing = case["existing"]?.let { CatalogItem(made.kind, made.label, texts(it)) }
            val named = CatalogItem(made.kind, made.label, made.properties, made.effectiveName)
            val unnamed = CatalogItem(made.kind, made.label, made.properties)
            for ((id, ddl) in stores) {
                val expected = case["stores"]!![id] ?: fail("$name: no statements for $id")
                val prefix = tables[id].orEmpty().map { mapOf("text" to it) }
                val create = expected["create"]!!
                assertEquals(
                    if (create.isArray) prefix + statements(create) else refusal(create),
                    attempt { ddl.create(made, existing) },
                    "$name: create on $id",
                )
                assertEquals(expectedOf(expected["drop"]!!), attempt { ddl.drop(named) }, "$name: drop on $id")
                assertEquals(expectedOf(expected["dropUnnamed"] ?: expected["drop"]!!), attempt { ddl.drop(unnamed) }, "$name: drop unnamed on $id")
                checked++
            }
            // FalkorDB's refusal of a constraint GRAPH.CONSTRAINT can't make, which a library asks before its create.
            if (made is ConstraintSpec) {
                val refused = case["stores"]!!["falkordb"]!!["constrainable"]?.let(::refusal) ?: emptyList<Any>()
                assertEquals(refused, attempt { FalkorDbDdl.requireConstrainable(made); emptyList() }, "$name: constrainable on falkordb")
            }
        }
        assertTrue(checked > 0, "store.yaml has no cases")
    }

    @Test
    fun catalogIsListedAndAliasedAsDrivine4jDid() {
        val catalog = store["catalog"]!!
        val item = catalog["item"]!!.let { CatalogItem(SchemaItemKind.valueOf(it["kind"]!!.textValue()!!), it["label"]!!.textValue()!!, texts(it["properties"]!!), it["name"]?.textValue()) }
        val aliases = texts(catalog["aliases"]!!)
        for ((id, ddl) in stores) {
            val expected = catalog["stores"]!![id]!!
            for (kind in SchemaItemKind.entries) {
                assertEquals(expected["indexes"]!![kind.name]!!.textValue(), ddl.listIndexesQuery(kind), "$id: list of $kind")
            }
            assertEquals(expected["constraints"]!!.textValue(), ddl.listConstraintsQuery(), "$id: list of constraints")
            if (ddl is SqlStoreDdl) {
                assertEquals(expected["aliased"]!!.textValue(), ddl.aliased(item, aliases), "$id: aliased")
                assertEquals(expected["unaliased"]!!.textValue(), ddl.aliased(item, emptyList()), "$id: unaliased")
            }
        }
        assertEquals(catalog["stores"]!!["oracle"]!!["labelled"]!!.textValue(), oracle.labelledIndexesQuery(), "oracle: labelled indexes")
    }

    @Test
    fun objectsAreNamedByJavasHashOnEveryTarget() {
        val names = store["names"]!!
        for (entry in names) {
            val graph = entry["graph"]!!.textValue()!!
            val ddl = when (val id = entry["store"]!!.textValue()) {
                "postgres" -> PostgresDdl(graph)
                "oracle" -> OracleDdl(graph)
                "sqlite" -> SqliteDdl(graph)
                else -> fail("No SQL store $id")
            }
            val parts = texts(entry["parts"]!!)
            assertEquals(entry["name"]!!.textValue(), ddl.objectName(entry["kind"]!!.textValue()!!, parts), "on $target: ${entry["store"]!!.textValue()} $graph $parts")
        }
        assertTrue(names.size() > 0)
    }

    @Test
    fun graphNamesAreRefusedAsDrivine4jRefusedThem() {
        for (entry in store["refusals"]!!) {
            val name = entry["graph"]!!.textValue()!!
            val refusal = when (val id = entry["store"]!!.textValue()) {
                "postgres" -> PostgresDdl.refusal(name)
                "oracle" -> OracleDdl.refusal(name)
                "sqlite" -> SqliteDdl.refusal(name)
                else -> fail("No SQL store $id")
            }
            assertEquals(entry["refusal"]?.takeUnless { it.isNull }?.textValue(), refusal, "${entry["store"]!!.textValue()}: $name")
        }
    }

    @Test
    fun eachReadCompilesToSqlThatHoldsItsIndexsExpressions() {
        var checked = 0
        for (case in store["cases"]!!) {
            val read = case["read"] ?: continue
            val name = case["name"]!!.textValue()!!
            val cypher = read["cypher"]!!.textValue()!!
            val parameters = (read["parameters"] as? ObjectNode)?.properties()?.associate { (k, v) -> k to v.textValue() }.orEmpty()
            val indexed = read["indexedProperties"]!!.map { it[0]!!.textValue()!! to it[1]!!.textValue()!! }.toSet()
            for (id in listOf("postgres", "oracle", "sqlite")) {
                val options = GraphOptions(graph, indexedProperties = if (id == "oracle") indexed else emptySet())
                val sql = when (val compiled = CypherCompiler.compile(Store.of(id)!!, options, cypher, parameters)) {
                    is Compiled -> compiled.sql
                    is Refusal -> fail("$name: $cypher is refused on $id: ${compiled.message}")
                }
                val alias = Regex("FROM ${graph}_node (\\w+)").find(sql)?.groupValues?.get(1) ?: fail("$name: no node table read on $id in\n$sql")
                val made = statements(case["stores"]!![id]!!["create"]!!).joinToString("\n") { it.values.single().toString() }
                for (expression in texts(read["holds"]!![id]!!)) {
                    assertTrue(expression in made || expression.replace("'", "''") in made, "$name: the index on $id is not made over\n$expression")
                    val qualified = qualified(expression, alias)
                    assertTrue(qualified in sql, "$name: $cypher on $id does not hold its index's\n$qualified\nin\n$sql")
                    checked++
                }
            }
        }
        assertTrue(checked > 0, "store.yaml has no reads")
    }

    /**
     * [expression], over a table's own columns as a Postgres or SQLite index names them, or over Oracle's `n`, as a
     * statement reads them over [alias]: the columns `props`, `labels` and `stamp`, outside a literal, are [alias]'s.
     */
    private fun qualified(expression: String, alias: String): String = expression.split('\'').mapIndexed { i, part ->
        if (i % 2 == 1) part
        else Regex("\\b(n\\.)?(props|labels|stamp)\\b").replace(part) { m ->
            val before = part.getOrNull(m.range.first - 1)
            if (before != null && (before == '.' || before == '_' || before.isLetterOrDigit())) m.value else "$alias.${m.groupValues[2]}"
        }
    }.joinToString("'")

    /** The spec `store.yaml` writes, as `schema.yaml` writes one, with `ignoreCase` and each engine's vector options beside. */
    private fun spec(node: JsonNode): SchemaItemSpec {
        val label = node["label"]!!.textValue()!!
        val properties = texts(node["properties"]!!)
        val name = node["name"]?.textValue()
        val ignoreCase = node["ignoreCase"]?.booleanValue() ?: false
        val options = node["options"]
        fun option(key: String) = options?.get(key)?.takeUnless { it.isNull }
        return when (SchemaItemKind.valueOf(node["kind"]!!.textValue()!!)) {
            SchemaItemKind.RANGE_INDEX -> RangeIndexSpec(label, properties, name, ignoreCase)
            SchemaItemKind.UNIQUENESS_CONSTRAINT -> UniquenessConstraintSpec(label, properties, name, ignoreCase = ignoreCase)
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

    /** Each statement as `{text}` or `{native}`, as [attempt] gives a generator's. */
    private fun statements(node: JsonNode): List<Map<String, Any>> = node.map {
        if (it.isTextual) mapOf("text" to it.textValue()!!) else mapOf("native" to texts(it["native"]!!))
    }

    private fun refusal(node: JsonNode): Map<String, Any> = mapOf("error" to node["error"]!!.textValue()!!)

    private fun expectedOf(node: JsonNode): Any = if (node.isArray) statements(node) else refusal(node)

    private fun attempt(block: () -> List<DdlStatement>): Any = try {
        block().map {
            when (it) {
                is DdlStatement.Text -> mapOf("text" to it.statement)
                is DdlStatement.Native -> mapOf("native" to it.args)
            }
        }
    } catch (e: CompilerException) {
        mapOf("error" to (e.message ?: ""))
    }
}
