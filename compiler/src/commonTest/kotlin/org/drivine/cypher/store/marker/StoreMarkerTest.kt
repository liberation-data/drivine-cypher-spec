package org.drivine.cypher.store.marker

import org.drivine.cypher.json.CypherJson
import org.drivine.cypher.json.JsonNode
import org.drivine.cypher.number.oracleFile
import org.drivine.cypher.number.target
import org.drivine.cypher.store.Stamps
import org.drivine.cypher.store.ddl.DdlStatement
import org.drivine.cypher.store.ddl.FalkorDbDdl
import org.drivine.cypher.store.ddl.MemgraphDdl
import org.drivine.cypher.store.ddl.Neo4jDdl
import org.drivine.cypher.store.ddl.OracleDdl
import org.drivine.cypher.store.ddl.PostgresDdl
import org.drivine.cypher.store.ddl.SqliteDdl
import org.drivine.cypher.store.ddl.StoreDdl
import org.drivine.schema.FullTextIndexSpec
import org.drivine.schema.RangeIndexSpec
import org.drivine.schema.SchemaItemKind
import org.drivine.schema.SchemaItemSpec
import org.drivine.schema.SimilarityFunction
import org.drivine.schema.TextIndexSpec
import org.drivine.schema.UniquenessConstraintSpec
import org.drivine.schema.VectorIndexSpec
import org.drivine.schema.made
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.test.fail

/**
 * The spec's `marker.yaml`, the schema marker and the stamps, against the module's `store.marker` and [Stamps], on each
 * target: the JVM writes it as JSON with its answers (`./gradlew jvmOracle`, `marker.json`), as the other targets read no
 * YAML.
 *
 * - each store's marker and store stamp statements, and what they bind, are drivine4j's, with the constraint each is kept
 *   single by made as `store.ddl` makes it;
 * - each inventory is written as drivine4j wrote it, kept behind Oracle's mark there, and read back to the keys, runtime
 *   items and stale items drivine4j read, as are the stored texts of `reads`;
 * - the inventory keys, the version tokens and the node stamps are drivine4j's.
 */
class StoreMarkerTest {

    private val pinned: JsonNode = CypherJson.readTree(oracleFile("marker.json"))

    private val graph = pinned["graph"]!!.textValue()!!

    private val postgres = PostgresDdl(graph)
    private val oracle = OracleDdl(graph)
    private val sqlite = SqliteDdl(graph)

    private val stores: Map<String, StoreDdl> = linkedMapOf(
        "neo4j" to Neo4jDdl,
        "memgraph" to MemgraphDdl(),
        "falkordb" to FalkorDbDdl,
        "postgres" to postgres,
        "oracle" to oracle,
        "sqlite" to sqlite,
    )

    /** What `prepare` names in `marker.yaml`: the statements of `store.yaml`'s `tables` that make the store. */
    private fun prepared(id: String, name: JsonNode): String? = when (val text = name.textValue()) {
        null -> null
        "ensuring" -> when (id) {
            "postgres" -> postgres.ensuring
            "oracle" -> oracle.ensuring
            else -> fail("$id has no ensuring")
        }
        "ddl[0]" -> sqlite.ddl.first()
        else -> fail("$id: no prepare $text")
    }

    /** What makes [spec] on [ddl], without the statements that make the store first, which `marker.yaml` leaves out as `store.yaml` does. */
    private fun constraint(id: String, ddl: StoreDdl, spec: UniquenessConstraintSpec): List<Any> {
        val made = ddl.createConstraint(spec.made()).map {
            when (it) {
                is DdlStatement.Text -> it.statement
                is DdlStatement.Native -> mapOf("native" to it.args)
            }
        }
        val prefix = when (id) {
            "postgres" -> listOf(postgres.ensuring)
            "oracle" -> listOf(oracle.ensuring)
            "sqlite" -> sqlite.ddl
            else -> emptyList()
        }
        assertEquals(prefix, made.take(prefix.size), "$id: the constraint's create makes the store first")
        return made.drop(prefix.size)
    }

    @Test
    fun theMarkersStatementsAreDrivine4js() {
        val marker = pinned["marker"]!!
        assertEquals(marker["label"]!!.textValue(), SchemaMarker.LABEL)
        assertEquals(marker["scopeProperty"]!!.textValue(), SchemaMarker.SCOPE_PROPERTY)
        assertEquals(marker["defaultScope"]!!.textValue(), SchemaMarker.DEFAULT_SCOPE)
        for (case in pinned["scopeFor"]!!) {
            val owner = case["owner"]!!.textValue()
            assertEquals(case["scope"]!!.textValue(), SchemaMarker.scopeFor(owner), "the scope of the owner $owner")
        }
        assertEquals(marker["separator"]!!.textValue(), SchemaMarker.SEPARATOR)
        assertEquals(marker["runtimePrefix"]!!.textValue(), SchemaMarker.RUNTIME_PREFIX)
        assertEquals(marker["staleRuntimePrefix"]!!.textValue(), SchemaMarker.STALE_RUNTIME_PREFIX)
        assertEquals(constraintSpec(marker["constraint"]!!), SchemaMarker.CONSTRAINT)
        assertEquals(marker["scopesCypher"]!!.textValue(), MarkerStatements.SCOPES_CYPHER)
        for ((id, ddl) in stores) {
            val expected = marker["stores"]!![id] ?: fail("marker.yaml has no marker statements for $id")
            val statements = MarkerStatements.of(ddl)
            assertEquals(prepared(id, expected["prepare"]!!), statements.prepare, "$id: prepare")
            assertEquals(expected["read"]!!["version"]!!.textValue(), statements.read(SchemaMarker.VERSION), "$id: read version")
            assertEquals(expected["read"]!!["items"]!!.textValue(), statements.read(SchemaMarker.ITEMS), "$id: read items")
            assertEquals(listOf("scope"), texts(expected["readParameters"]!!), "$id: a read binds the scope alone")
            assertEquals(expected["merge"]!!.textValue(), statements.merge, "$id: merge")
            assertEquals(expected["deleteAllButNewest"]!!.textValue(), statements.deleteAllButNewest, "$id: deleteAllButNewest")
            assertEquals(expected["delete"]!!.textValue(), statements.delete, "$id: delete")
            assertEquals(expected["scopes"]!!.textValue(), statements.scopes, "$id: scopes")
            assertEquals(constraint(id, ddl, SchemaMarker.CONSTRAINT), statements(expected["constraint"]!!), "$id: the marker's constraint")

            val mark = expected["itemsMark"]!!.textValue()!!
            val bound = statements.mergeParameters("owner", "v1", "a\nb", 1_762_000_000_123L)
            assertEquals(texts(expected["mergeParameters"]!!), bound.keys.toList(), "$id: what merge binds")
            assertEquals("owner", bound["scope"], "$id: the scope bound")
            assertEquals("v1", bound["version"], "$id: the version bound")
            assertEquals(mark + "a\nb", bound["items"], "$id: the items bound, behind the store's mark")
            if ("now" in bound) assertEquals(1_762_000_000_123L, bound["now"], "$id: the caller's moment bound as now")
            assertNull(statements.mergeParameters("owner", null, "", 0L)["version"], "$id: no version bound as null")
        }
        // Memgraph of another vector capacity keeps the marker as any Memgraph does; a store with no DDL here, as Neptune,
        // is given the graph engines' Cypher, as drivine4j's grammar gives it by default.
        assertEquals(CypherMarkerStatements, MarkerStatements.of(MemgraphDdl(1024)))
        assertEquals(CypherMarkerStatements, MarkerStatements.of(neptune))
    }

    /** A store this module has no DDL of, as Neptune: Neo4j's, under another name. */
    private val neptune: StoreDdl = object : StoreDdl by Neo4jDdl {
        override val engine = "Neptune"
    }

    @Test
    fun inventoryKeysAreDrivine4js() {
        for (entry in pinned["keys"]!!) {
            val spec = spec(entry["spec"]!!)
            assertEquals(entry["key"]!!.textValue(), spec.inventoryKey, "the key of $spec")
            assertEquals(listOf(entry["key"]!!.textValue()), SchemaMarker.keys(listOf(spec)))
        }
        assertTrue(pinned["keys"]!!.size() > 0, "marker.yaml has no keys")
    }

    @Test
    fun eachInventoryIsWrittenAndReadBackAsDrivine4jDid() {
        for (case in pinned["inventory"]!!) {
            val name = case["name"]!!.textValue()!!
            val record = case["record"]!!
            val inventory = MarkerInventory(texts(record["keys"]!!), texts(record["runtime"]!!).toSet(), texts(record["stale"]!!).toSet())
            val items = case["items"]!!.textValue()!!
            assertEquals(items, inventory.items, "$name: the items written")
            assertEquals(read(case["read"]!!), MarkerInventory.parse(items), "$name: the items read back")
            for ((id, ddl) in stores) {
                val statements = MarkerStatements.of(ddl)
                val stored = statements.storedItems(items)
                assertEquals(pinned["marker"]!!["stores"]!![id]!!["itemsMark"]!!.textValue() + items, stored, "$name: the items kept on $id")
                assertEquals(read(case["read"]!!), statements.inventory(stored), "$name: the items read back on $id")
            }
        }
        assertTrue(pinned["inventory"]!!.size() > 0, "marker.yaml has no inventories")
    }

    @Test
    fun storedTextsAreReadAsDrivine4jReadThem() {
        for (case in pinned["reads"]!!) {
            val stored = case["stored"]!!.textValue()
            for ((id, ddl) in stores) {
                val expected = (if (id == "oracle") case["oracle"] else null) ?: case["read"]!!
                assertEquals(expected.takeUnless { it.isNull }?.let(::read), MarkerStatements.of(ddl).inventory(stored), "$id reads ${stored?.let { "\"$it\"" }}")
            }
        }
    }

    @Test
    fun versionTokensAreDrivine4js() {
        for (case in pinned["versions"]!!) {
            val versions = case["versions"]!!.map { it.textValue() }
            assertEquals(case["token"]!!.textValue(), SchemaMarker.versionToken(versions), "on $target: the token of $versions")
        }
    }

    @Test
    fun theStoreStampsStatementsAreDrivine4js() {
        val stamp = pinned["storeStamp"]!!
        assertEquals(stamp["label"]!!.textValue(), StoreStamp.LABEL)
        assertEquals(stamp["keyProperty"]!!.textValue(), StoreStamp.KEY_PROPERTY)
        assertEquals(stamp["key"]!!.textValue(), StoreStamp.KEY)
        assertEquals(constraintSpec(stamp["constraint"]!!), StoreStamp.CONSTRAINT)
        val bound = StoreStamp.assignParameters("2b9f0c1e-0000-4000-8000-000000000000", "2026-11-10T05:00:00.123Z", "NEO4J")
        assertEquals(texts(stamp["parameters"]!!), bound.keys.toList())
        assertEquals(StoreStamp.KEY, bound["key"])
        for ((id, ddl) in stores) {
            val expected = stamp["stores"]!![id] ?: fail("marker.yaml has no stamp statements for $id")
            val statements = StoreStampStatements.of(ddl)
            assertEquals(expected["engine"]!!.textValue(), StoreStamp.engine(ddl), "$id: engine")
            assertEquals(prepared(id, expected["prepare"]!!), statements.prepare, "$id: prepare")
            assertEquals(expected["assignIfAbsent"]!!.textValue(), statements.assignIfAbsent, "$id: assignIfAbsent")
            assertEquals(expected["readStamps"]!!.textValue(), statements.readStamps, "$id: readStamps")
            assertEquals(constraint(id, ddl, StoreStamp.CONSTRAINT), statements(expected["constraint"]!!), "$id: the stamp's constraint")
        }
        assertEquals(stamp["stores"]!!["memgraph"]!!["engine"]!!.textValue(), StoreStamp.engine(MemgraphDdl(1024)))
        assertEquals(CypherStoreStampStatements, StoreStampStatements.of(MemgraphDdl(1024)))
        // A store with no DDL here gets the graph engines' Cypher, but no engine name: its library names it.
        assertEquals(CypherStoreStampStatements, StoreStampStatements.of(neptune))
        assertFailsWith<IllegalArgumentException> { StoreStamp.engine(neptune) }
    }

    @Test
    fun nodeStampsAreWrittenAndReadAsDrivine4jDoes() {
        val stamps = pinned["nodeStamps"]!!
        assertEquals(stamps["property"]!!.textValue(), Stamps.PROPERTY)
        assertEquals(stamps["token"]!!.longValue().toInt(), Stamps.TOKEN)
        assertEquals(stamps["separator"]!!.textValue(), Stamps.SEPARATOR.toString())
        assertEquals(stamps["neverStamped"]!!.textValue(), Stamps.NEVER_STAMPED)
        for (case in stamps["tokens"]!!) {
            val bits = case["bits"]!!.textValue()!!.toLong()
            assertEquals(case["token"]!!.textValue(), Stamps.token(bits), "on $target: the token of $bits")
        }
        for (case in stamps["stamps"]!!) {
            val stamp = case["stamp"]!!.textValue()!!
            assertEquals(case["nodeToken"]!!.textValue(), Stamps.nodeToken(stamp), "the node token of \"$stamp\"")
            assertEquals(case["linksToken"]!!.textValue(), Stamps.linksToken(stamp), "the links token of \"$stamp\"")
            assertEquals(case["twoTokens"]!!.booleanValue(), Stamps.isTwoTokens(stamp), "whether \"$stamp\" is two tokens")
            assertEquals(case["hasNodeToken"]!!.booleanValue(), Stamps.hasNodeToken(stamp), "whether \"$stamp\" has a node token")
            assertEquals(case["withoutLinks"]!!.textValue(), Stamps.withoutLinks(stamp), "\"$stamp\" without its links")
            // A stamp with a colon is its two tokens written again; one without has a node token alone, which gains one.
            if (Stamps.SEPARATOR in stamp) assertEquals(stamp, Stamps.of(Stamps.nodeToken(stamp), Stamps.linksToken(stamp)), "\"$stamp\" written again")
        }
        assertTrue(stamps["stamps"]!!.size() > 0, "marker.yaml has no node stamps")
        // A fresh stamp is two tokens, each from the random source given.
        val random = Random(646)
        val fresh = Stamps.fresh(Random(646))
        assertEquals(Stamps.of(Stamps.token(random.nextLong()), Stamps.token(random.nextLong())), fresh)
        assertTrue(Regex("[0-9a-f]{16}:[0-9a-f]{16}").matches(fresh) && Stamps.isTwoTokens(fresh), "a fresh stamp: $fresh")
    }

    private fun read(node: JsonNode): MarkerInventory =
        MarkerInventory(texts(node["keys"]!!), texts(node["runtime"]!!).toSet(), texts(node["stale"]!!).toSet())

    private fun constraintSpec(node: JsonNode): UniquenessConstraintSpec {
        assertEquals(SchemaItemKind.UNIQUENESS_CONSTRAINT.name, node["kind"]!!.textValue())
        return UniquenessConstraintSpec(node["label"]!!.textValue()!!, texts(node["properties"]!!))
    }

    /** A spec of `marker.yaml`'s `keys`: a vector index's size and similarity don't reach its key, so any do. */
    private fun spec(node: JsonNode): SchemaItemSpec {
        val label = node["label"]!!.textValue()!!
        val properties = texts(node["properties"]!!)
        val name = node["name"]?.textValue()
        val ignoreCase = node["ignoreCase"]?.booleanValue() ?: false
        return when (SchemaItemKind.valueOf(node["kind"]!!.textValue()!!)) {
            SchemaItemKind.RANGE_INDEX -> RangeIndexSpec(label, properties, name, ignoreCase)
            SchemaItemKind.UNIQUENESS_CONSTRAINT -> UniquenessConstraintSpec(label, properties, name, ignoreCase = ignoreCase)
            SchemaItemKind.FULLTEXT_INDEX -> FullTextIndexSpec(label, properties, name)
            SchemaItemKind.TEXT_INDEX -> TextIndexSpec(label, properties.single(), name, ignoreCase)
            SchemaItemKind.VECTOR_INDEX -> VectorIndexSpec(label, properties.single(), 384, SimilarityFunction.COSINE, name)
        }
    }

    private fun texts(node: JsonNode): List<String> = node.map { it.textValue()!! }

    /** Each statement as its text, or `{native}`, as [constraint] gives them. */
    private fun statements(node: JsonNode): List<Any> = node.map {
        if (it.isTextual) it.textValue()!! else mapOf("native" to texts(it["native"]!!))
    }
}
