package org.drivine.cypher.store.plan

import org.drivine.cypher.api.CompileJson
import org.drivine.cypher.json.CypherJson
import org.drivine.cypher.json.JsonNode
import org.drivine.cypher.json.ObjectNode
import org.drivine.cypher.number.oracleFile
import org.drivine.cypher.number.target
import org.drivine.cypher.store.ddl.CatalogItem
import org.drivine.cypher.store.ddl.FalkorDbDdl
import org.drivine.cypher.store.ddl.MemgraphDdl
import org.drivine.cypher.store.ddl.Neo4jDdl
import org.drivine.cypher.store.ddl.OracleDdl
import org.drivine.cypher.store.ddl.PostgresDdl
import org.drivine.cypher.store.ddl.SqlStoreDdl
import org.drivine.cypher.store.ddl.SqliteDdl
import org.drivine.cypher.store.ddl.StoreDdl
import org.drivine.cypher.store.schema.StoreSchema
import org.drivine.schema.RangeIndexSpec
import org.drivine.schema.SchemaItemInfo
import org.drivine.schema.SchemaItemKind
import org.drivine.schema.SimilarityFunction
import org.drivine.schema.TextIndexSpec
import org.drivine.schema.UniquenessConstraintSpec
import org.drivine.schema.VectorIndexSpec
import org.drivine.schema.made
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * The schema planner's listing questions through the JSON entry (`listings`, `items` and `aliased`, [org.drivine.cypher.api.SchemaPlanJson]):
 * what a host with no Kotlin types lists, finds and aliases by, with no plan, as drivine4j's `IndexManager` and
 * `ConstraintManager` do through the store schema's own calls. On each store, each answer is what those calls give: the
 * listing statements `store.yaml` pins, and the items the store's rows hold ([ListingRows]), parsed, found by a spec's
 * identity, with a catalog record's aliases and an item's build state.
 */
class SchemaListingsJsonTest {

    private val store: JsonNode = CypherJson.readTree(oracleFile("store.json"))

    private val graph = store["graph"]!!.textValue()!!

    private val ddls: Map<String, StoreDdl> = linkedMapOf(
        "neo4j" to Neo4jDdl,
        "memgraph" to MemgraphDdl(),
        "falkordb" to FalkorDbDdl,
        "postgres" to PostgresDdl(graph),
        "oracle" to OracleDdl(graph),
        "sqlite" to SqliteDdl(graph),
    )

    private fun ask(id: String, rest: String): JsonNode {
        val named = if (ddls[id] is SqlStoreDdl) ", \"graph\": \"$graph\"" else ""
        return CypherJson.readTree(CompileJson.compile("{\"schemaPlan\": {\"store\": \"$id\"$named, $rest}}"))
    }

    private fun json(value: Any?): String = PlanJson.writePlain(value)

    /** An index of each kind, and a constraint, as a store holds them. */
    private val indexes = listOf(
        SchemaItemInfo(SchemaItemKind.RANGE_INDEX, "Person", listOf("name"), "Person_name_range"),
        SchemaItemInfo(SchemaItemKind.RANGE_INDEX, "Person", listOf("born", "name"), "Person_born_name_range"),
        SchemaItemInfo(SchemaItemKind.VECTOR_INDEX, "Chunk", listOf("embedding"), "Chunk_embedding_vector", dimensions = 384, similarity = SimilarityFunction.COSINE),
    )

    private val constraints = listOf(
        SchemaItemInfo(SchemaItemKind.UNIQUENESS_CONSTRAINT, "Person", listOf("email"), "Person_email_unique"),
    )

    @Test
    fun theListingsAreThoseStoreYamlPins() {
        for ((id, ddl) in ddls) {
            val schema = StoreSchema.of(ddl)
            val got = ask(id, "\"listings\": {}")
            val pinned = store["catalog"]!!["stores"]!![id]!!
            assertEquals(pinned["constraints"]!!.textValue(), got["constraints"]!!.textValue(), "on $target, $id: the constraint listing")
            // The online builds under way on the node table, which a plan reads before it drops an index left invalid (#382).
            assertEquals(pinned["onlineBuilds"]?.textValue(), ddl.onlineBuildsQuery, "on $target, $id: the online builds listing")
            val kinds = (got["indexes"] as ObjectNode).properties().map { (kind, _) -> kind }
            val expected = listOfNotNull("VECTOR_INDEX", "RANGE_INDEX", "FULLTEXT_INDEX", "TEXT_INDEX".takeIf { schema.supportsTextIndex })
            assertEquals(expected, kinds, "on $target, $id: a listing for each kind of index the store has")
            for (kind in kinds) {
                assertEquals(ddl.listIndexesQuery(SchemaItemKind.valueOf(kind)), got["indexes"]!![kind]!!.textValue(), "on $target, $id, $kind")
                pinned["indexes"]?.get(kind)?.let { assertEquals(it.textValue(), got["indexes"]!![kind]!!.textValue(), "on $target, $id, $kind: store.yaml's") }
            }
        }
    }

    @Test
    fun theItemsOfAListingAreThoseTheStoreSchemaParses() {
        for ((id, ddl) in ddls) {
            val schema = StoreSchema.of(ddl)
            for ((of, items) in listOf("indexes" to indexes, "constraints" to constraints)) {
                val rows = ListingRows.rows(id, items, of == "constraints")
                val parsed = if (of == "constraints") schema.parseConstraintRows(rows) else schema.parseIndexRows(rows)
                val got = ask(id, "\"items\": {\"of\": \"$of\", \"rows\": ${json(rows)}}")
                assertEquals(
                    json(parsed.distinct().map { PlanJson.item(it)!! + linkedMapOf("aliases" to emptyList<String>(), "buildInProgress" to it.buildInProgress, "buildFailed" to it.buildFailed) }),
                    CypherJson.writeValueAsString(got["items"]!!),
                    "on $target, $id, $of: $got",
                )
                assertTrue(got["items"]!!.size() > 0, "on $target, $id, $of: items listed")
                // A spec given as null is no spec: every item is listed.
                val unfiltered = ask(id, "\"items\": {\"of\": \"$of\", \"rows\": ${json(rows)}, \"spec\": null}")
                assertEquals(CypherJson.writeValueAsString(got["items"]!!), CypherJson.writeValueAsString(unfiltered["items"]!!), "on $target, $id, $of: $unfiltered")
            }
        }
    }

    @Test
    fun aSpecFindsTheItemOfItsIdentityAsTheStoreSchemaMatchesIt() {
        for ((id, ddl) in ddls) {
            val schema = StoreSchema.of(ddl)
            val rows = ListingRows.rows(id, indexes, false)
            // The properties in another order are the same index; another label's is not.
            for (spec in listOf(RangeIndexSpec("Person", listOf("name", "born")), RangeIndexSpec("Person", listOf("name")), RangeIndexSpec("Movie", listOf("name")), VectorIndexSpec("Chunk", "embedding", 384))) {
                val expected = schema.parseIndexRows(rows).distinct().filter { schema.matchesIdentity(it, spec.made()) }.map { it.name }
                val got = ask(id, "\"items\": {\"of\": \"indexes\", \"rows\": ${json(rows)}, \"spec\": ${json(PlanJson.spec(spec))}}")
                assertEquals(expected, got["items"]!!.map { it["name"]?.takeUnless { n -> n.isNull }?.textValue() }, "on $target, $id, $spec: $got")
            }
            val unique = ListingRows.rows(id, constraints, true)
            val email = UniquenessConstraintSpec("Person", listOf("email"))
            val found = ask(id, "\"items\": {\"of\": \"constraints\", \"rows\": ${json(unique)}, \"spec\": ${json(PlanJson.spec(email))}}")
            val expected = schema.parseConstraintRows(unique).filter { schema.matchesIdentity(it, email.made()) }.map { json(PlanJson.item(it)) }
            assertEquals(1, expected.size, "on $target, $id: the constraint is found by its spec")
            assertEquals(expected, found["items"]!!.map { node -> json(PlanJson.item(SchemaItemInfo(SchemaItemKind.valueOf(node["kind"]!!.textValue()!!), node["label"]!!.textValue()!!, node["properties"]!!.map { it.textValue()!! }, node["name"]?.takeUnless { it.isNull }?.textValue()))) }, "on $target, $id: $found")
        }
    }

    @Test
    fun aSpecThatIgnoresCaseFindsTheIndexMadeOverTheLowercasedCopies() {
        val spec = RangeIndexSpec("Person", listOf("name"), ignoreCase = true)
        val made = spec.made()
        val lowered = SchemaItemInfo(SchemaItemKind.RANGE_INDEX, "Person", made.properties, made.name)
        for ((id, ddl) in ddls) {
            val schema = StoreSchema.of(ddl)
            val rows = ListingRows.rows(id, listOf(indexes[0], lowered), false)
            val expected = schema.parseIndexRows(rows).distinct().filter { schema.matchesIdentity(it, made) }.map { json(PlanJson.item(it)) }
            assertEquals(1, expected.size, "on $target, $id: the index over the copies, not the one over name")
            val got = ask(id, "\"items\": {\"of\": \"indexes\", \"rows\": ${json(rows)}, \"spec\": ${json(PlanJson.spec(spec))}}")
            assertEquals(expected, got["items"]!!.map { json(PlanJson.item(SchemaItemInfo(SchemaItemKind.valueOf(it["kind"]!!.textValue()!!), it["label"]!!.textValue()!!, it["properties"]!!.map { p -> p.textValue()!! }, it["name"]?.takeUnless { n -> n.isNull }?.textValue()))) }, "on $target, $id: $got")
        }
    }

    @Test
    fun aCatalogRecordsAliasesAndAnItemsBuildStateAreGiven() {
        val record = linkedMapOf<String, Any?>(
            "kind" to "RANGE_INDEX", "label" to "Person", "properties" to listOf("name"), "name" to "Person_name_range",
            "details" to linkedMapOf("aliases" to listOf("by_name", "people_name")),
        )
        val got = ask("postgres", "\"items\": {\"of\": \"indexes\", \"rows\": ${json(listOf(record))}}")
        assertEquals(listOf("by_name", "people_name"), got["items"]!![0]!!["aliases"]!!.map { it.textValue() }, "on $target: $got")
        // FalkorDB lists an index still being built by its status.
        val building = ListingRows.rows("falkordb", listOf(indexes[0].copy(status = "UNDER CONSTRUCTION")), false)
        val falkor = ask("falkordb", "\"items\": {\"of\": \"indexes\", \"rows\": ${json(building)}}")
        assertEquals(true, falkor["items"]!![0]!!["buildInProgress"]!!.booleanValue(), "on $target: $falkor")
        assertEquals(false, falkor["items"]!![0]!!["buildFailed"]!!.booleanValue(), "on $target: $falkor")
        // And a constraint whose build failed, which it keeps listing though it enforces nothing, by its status too.
        val failed = ListingRows.rows("falkordb", listOf(constraints[0].copy(status = "FAILED")), true)
        val broken = ask("falkordb", "\"items\": {\"of\": \"constraints\", \"rows\": ${json(failed)}}")
        assertEquals(true, broken["items"]!![0]!!["buildFailed"]!!.booleanValue(), "on $target: $broken")
        assertEquals(false, broken["items"]!![0]!!["buildInProgress"]!!.booleanValue(), "on $target: $broken")
    }

    @Test
    fun aTextIndexIsFoundOnlyOnAStoreThatHasThemAsDrivine4jsFindRefusesIt() {
        val text = TextIndexSpec("Person", "bio")
        for ((id, ddl) in ddls) {
            val got = ask(id, "\"items\": {\"of\": \"indexes\", \"rows\": [], \"spec\": ${json(PlanJson.spec(text))}}")
            if (StoreSchema.of(ddl).supportsTextIndex) {
                assertEquals(0, got["items"]!!.size(), "on $target, $id: $got")
            } else {
                assertTrue(got["error"]?.textValue()?.contains("a TEXT index is Neo4j's") == true, "on $target, $id: $got")
            }
        }
    }

    @Test
    fun anAliasIsRecordedByTheStoresOwnStatementAndNoneWhereTheStoreKeepsNoRecord() {
        val item = CatalogItem(SchemaItemKind.RANGE_INDEX, "Person", listOf("name"), "Person_name_range")
        val asked = "\"aliased\": {\"item\": {\"kind\": \"RANGE_INDEX\", \"label\": \"Person\", \"properties\": [\"name\"], \"name\": \"Person_name_range\"}, \"aliases\": [\"by_name\"]}"
        for ((id, ddl) in ddls) {
            val got = ask(id, asked)
            val expected = (ddl as? SqlStoreDdl)?.aliased(item, listOf("by_name"))
            assertEquals(expected, got["statement"]?.takeUnless { it.isNull }?.textValue(), "on $target, $id: $got")
            if (ddl is SqlStoreDdl) assertTrue(expected!!.contains("by_name"), "on $target, $id: $expected")
        }
        // An item the store lists with no name, and no aliases left, which takes the record's away.
        val unnamed = CatalogItem(SchemaItemKind.UNIQUENESS_CONSTRAINT, "Person", listOf("email"))
        for ((id, ddl) in ddls) {
            val got = ask(id, "\"aliased\": {\"item\": {\"kind\": \"UNIQUENESS_CONSTRAINT\", \"label\": \"Person\", \"properties\": [\"email\"], \"name\": null}, \"aliases\": []}")
            assertEquals((ddl as? SqlStoreDdl)?.aliased(unnamed, emptyList()), got["statement"]?.takeUnless { it.isNull }?.textValue(), "on $target, $id: $got")
        }
    }

    @Test
    fun aQuestionTheModuleCantAnswerIsAnErrorNamingWhy() {
        val wrong = mapOf(
            "\"items\": {\"rows\": []}" to "items gives of",
            "\"items\": {\"of\": \"indexes\"}" to "items gives its rows as a list",
            "\"aliased\": {\"item\": {\"kind\": \"NO_KIND\", \"label\": \"A\", \"properties\": []}, \"aliases\": []}" to "aliased gives its item's kind",
            "\"items\": []" to "items gives {",
            "\"aliased\": []" to "aliased gives {",
            "\"aliased\": {\"aliases\": []}" to "aliased gives its item.",
            "\"aliased\": {\"item\": {\"kind\": \"RANGE_INDEX\", \"properties\": []}, \"aliases\": []}" to "aliased gives its item's label",
            "\"aliased\": {\"item\": {\"kind\": \"RANGE_INDEX\", \"label\": \"A\", \"properties\": \"a\"}, \"aliases\": []}" to "aliased gives its item's properties as a list",
            "\"aliased\": {\"item\": {\"kind\": \"RANGE_INDEX\", \"label\": \"A\", \"properties\": [1]}, \"aliases\": []}" to "aliased gives its item's properties as names",
            "\"aliased\": {\"item\": {\"kind\": \"RANGE_INDEX\", \"label\": \"A\", \"properties\": []}}" to "aliased gives its aliases as a list",
            "\"aliased\": {\"item\": {\"kind\": \"RANGE_INDEX\", \"label\": \"A\", \"properties\": []}, \"aliases\": [1]}" to "aliased gives its aliases as names",
            "\"listings\": {}, \"validate\": {}" to "A schemaPlan request gives one of",
        )
        for ((rest, message) in wrong) {
            val got = ask("postgres", rest)
            assertTrue(got["error"]?.textValue()?.contains(message) == true, "on $target, $rest: $got")
        }
    }
}
