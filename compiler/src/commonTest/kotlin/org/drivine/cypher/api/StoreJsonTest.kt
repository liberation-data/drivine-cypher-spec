package org.drivine.cypher.api

import org.drivine.cypher.json.ArrayNode
import org.drivine.cypher.json.CypherJson
import org.drivine.cypher.json.JsonNode
import org.drivine.cypher.json.ObjectNode
import org.drivine.cypher.number.oracleFile
import org.drivine.cypher.number.target
import org.drivine.cypher.store.ddl.FalkorDbDdl
import org.drivine.cypher.store.ddl.MemgraphDdl
import org.drivine.cypher.store.ddl.Neo4jDdl
import org.drivine.cypher.store.ddl.OracleDdl
import org.drivine.cypher.store.ddl.PostgresDdl
import org.drivine.cypher.store.ddl.SqliteDdl
import org.drivine.cypher.store.ddl.StoreDdl
import org.drivine.cypher.store.marker.MarkerStatements
import org.drivine.cypher.store.marker.StoreLayout
import org.drivine.cypher.store.marker.StoreStamp
import org.drivine.cypher.store.marker.StoreStampStatements
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * A store's own statements through the JSON entry ([StoreJson]), as TypeScript and Python ask for them: each the Kotlin value
 * drivine4j reads, on every target, and the DDL `store.yaml` pins (liberation-data/drivine-cypher-spec#107).
 */
class StoreJsonTest {

    private fun answer(request: String): JsonNode = CypherJson.readTree(CompileJson.compile(request))

    private fun json(value: Any?) = CypherJson.writeValueAsString(CypherJson.valueToTree(value))

    private fun texts(node: JsonNode?): List<String> = (node as ArrayNode).map { it.textValue()!! }

    private fun text(node: JsonNode?): String? = node?.takeUnless { it.isNull }?.textValue()

    private val stores: Map<String, (String) -> StoreDdl> = mapOf(
        "postgres" to ::PostgresDdl, "oracle" to ::OracleDdl, "sqlite" to ::SqliteDdl,
        "neo4j" to { _ -> Neo4jDdl }, "memgraph" to { _ -> MemgraphDdl() }, "falkordb" to { _ -> FalkorDbDdl },
    )

    @Test
    fun eachStoresStatementsAreTheModulesOwn() {
        for ((store, make) in stores) {
            val ddl = make("g")
            val got = answer("{\"storeStatements\": {\"store\": ${json(store)}, \"graph\": \"g\"}}")
            val engine = StoreStamp.engine(ddl)
            assertEquals(engine, text(got["engine"]), "on $target, $store: $got")
            assertEquals(StoreLayout.current(engine).toLong(), got["layout"]!!.longValue(), "on $target, $store")
            when (ddl) {
                is PostgresDdl -> { assertEquals(ddl.ddl, texts(got["ddl"])); assertEquals(ddl.ensuring, text(got["ensuring"])) }
                is OracleDdl -> {
                    assertEquals(ddl.ddl, texts(got["ddl"])); assertEquals(ddl.ensuring, text(got["ensuring"])); assertEquals(ddl.functions, texts(got["functions"]))
                    assertEquals(ddl.versions, (got["versions"] as ObjectNode).properties().associate { (k, v) -> k to v.textValue()!! })
                }
                is SqliteDdl -> { assertEquals(ddl.ddl, texts(got["ddl"])); assertTrue(got["ensuring"]!!.isNull) }
                else -> assertTrue(got["ddl"] == null && got["ensuring"] == null, "on $target, $store: a graph engine has no DDL of its own: $got")
            }
            val stamp = StoreStampStatements.of(ddl)
            val stamps = got["stamp"]!!
            assertEquals(
                listOf(stamp.prepare, stamp.assignIfAbsent, stamp.readStamps, stamp.foreignNodes, stamp.restampLayout, stamp.layoutObjects),
                listOf("prepare", "assignIfAbsent", "readStamps", "foreignNodes", "restampLayout", "layoutObjects").map { text(stamps[it]) },
                "on $target, $store: the stamp's statements",
            )
            val marker = MarkerStatements.of(ddl)
            val markers = got["marker"]!!
            assertEquals(
                listOf(marker.prepare, marker.merge, marker.deleteAllButNewest, marker.delete, marker.scopes, marker.storedItems("")),
                listOf("prepare", "merge", "deleteAllButNewest", "delete", "scopes", "itemsMark").map { text(markers[it]) },
                "on $target, $store: the marker's statements",
            )
            for (property in listOf("version", "items")) {
                assertEquals(marker.read(property), text(markers["read"]!![property]), "on $target, $store: the marker's read of $property")
            }
        }
    }

    @Test
    fun oraclesProbesAreDrivine4jsOneForEachCompilerFlag() {
        val got = answer("{\"storeStatements\": {\"store\": \"oracle\", \"graph\": \"g\"}}")["storedFunctions"]!!
        assertEquals(
            mapOf(
                "nullsEqualStored" to "SELECT g_nulls_equal(JSON('[1]'), JSON('[1]')) FROM DUAL",
                "eraStored" to "SELECT g_era('0000-01-01'), g_era_date('-0001-01-01'), g_temporal_key('date', '2024-01-01') FROM DUAL",
                "temporalArithmeticStored" to "SELECT JSON_SERIALIZE(g_temporal_arith('+', JSON('{\"\$t\":\"date\",\"v\":\"2024-01-01\"}'), JSON('{\"\$t\":\"duration\",\"v\":\"P1D\"}'))) FROM DUAL",
            ),
            (got["probes"] as ObjectNode).properties().associate { (k, v) -> k to v.textValue()!! },
            "on $target",
        )
        assertEquals(listOf(904L, 942L, 1031L, 6575L), (got["noFunction"] as ArrayNode).map { it.longValue() }, "on $target")
        for (store in listOf("postgres", "sqlite", "neo4j")) {
            assertTrue(answer("{\"storeStatements\": {\"store\": \"$store\", \"graph\": \"g\"}}")["storedFunctions"] == null, "on $target, $store has no probes")
        }
    }

    @Test
    fun oraclesLabelledIndexesQueryIsTheOneALibraryTellsTheCompilerOf() {
        val got = answer("{\"storeStatements\": {\"store\": \"oracle\", \"graph\": \"g\"}}")["labelled"]!!
        assertEquals(OracleDdl("g").labelledIndexesQuery(), got.textValue(), "on $target")
        for (store in listOf("postgres", "sqlite", "neo4j")) {
            assertTrue(answer("{\"storeStatements\": {\"store\": \"$store\", \"graph\": \"g\"}}")["labelled"] == null, "on $target, $store lists none")
        }
    }

    @Test
    fun oraclesSessionComparesTextByCodePointWhateverItsNlsSettings() {
        val got = answer("{\"storeStatements\": {\"store\": \"oracle\", \"graph\": \"g\"}}")["session"] as ArrayNode
        assertEquals(listOf("ALTER SESSION SET NLS_COMP = BINARY NLS_SORT = BINARY"), got.map { it.textValue() }, "on $target")
        for (store in listOf("postgres", "sqlite", "neo4j")) {
            assertTrue(answer("{\"storeStatements\": {\"store\": \"$store\", \"graph\": \"g\"}}")["session"] == null, "on $target, $store sets no session")
        }
    }

    @Test
    fun eachSqlStoresDdlIsWhatStoreYamlPins() {
        // store.yaml's tables hold each statement with its generated functions marked: expanded, they are the store's own.
        val store = CypherJson.readTree(oracleFile("store.json"))
        val graph = store["graph"]!!.textValue()!!
        for (engine in listOf("postgres", "oracle", "sqlite")) {
            val pinned = store["tables"]!![engine]!!
            val got = answer("{\"storeStatements\": {\"store\": \"$engine\", \"graph\": ${json(graph)}}}")
            fun expanded(node: JsonNode) = texts(node).map { org.drivine.cypher.store.ddl.GeneratedFunctions.expand(it) }
            assertEquals(expanded(pinned["ddl"]!!), texts(got["ddl"]), "on $target, $engine: the DDL")
            pinned["ensuring"]?.let { assertEquals(org.drivine.cypher.store.ddl.GeneratedFunctions.expand(it.textValue()!!), text(got["ensuring"]), "on $target, $engine: the ensuring block") }
            pinned["functions"]?.let { assertEquals(expanded(it), texts(got["functions"]), "on $target, $engine: the functions") }
        }
    }

    @Test
    fun eachStoresMarkerAndStampStatementsAreWhatMarkerYamlPins() {
        // marker.yaml's statements, beside the answer's: each but `prepare`, which it names (ensuring, the DDL's first) and
        // eachStoresStatementsAreTheModulesOwn checks against the Kotlin value.
        val marker = CypherJson.readTree(oracleFile("marker.json"))
        val graph = marker["graph"]!!.textValue()!!
        fun pinned(node: JsonNode?): String? = node?.takeUnless { it.isNull }?.let {
            if (it.isTextual) org.drivine.cypher.store.ddl.GeneratedFunctions.expand(it.textValue()!!) else CypherJson.writeValueAsString(it)
        }
        fun given(node: JsonNode?): String? = node?.takeUnless { it.isNull }?.let { if (it.isTextual) it.textValue() else CypherJson.writeValueAsString(it) }
        var checked = 0
        for (store in stores.keys) {
            val got = answer("{\"storeStatements\": {\"store\": ${json(store)}, \"graph\": ${json(graph)}}}")
            val markers = marker["marker"]!!["stores"]!![store]!!
            for (field in listOf("read", "merge", "deleteAllButNewest", "delete", "scopes", "itemsMark", "constraint")) {
                assertEquals(pinned(markers[field]), given(got["marker"]!![field]), "on $target, $store: the marker's $field")
                checked++
            }
            val stamps = marker["storeStamp"]!!["stores"]!![store]!!
            for (field in listOf("assignIfAbsent", "readStamps", "foreignNodes", "restampLayout", "layoutObjects", "constraint")) {
                assertEquals(pinned(stamps[field]), given(got["stamp"]!![field]), "on $target, $store: the stamp's $field")
                checked++
            }
            assertEquals(stamps["engine"]!!.textValue(), got["engine"]!!.textValue(), "on $target, $store")
            assertEquals(stamps["layout"]!!.longValue(), got["layout"]!!.longValue(), "on $target, $store")
        }
        assertTrue(checked == 6 * 13, "every store's statements were checked: $checked")
    }

    @Test
    fun eachParameterSetIsWhatTheStatementsBind() {
        for ((store, make) in stores) {
            val ddl = make("g")
            val engine = StoreStamp.engine(ddl)
            fun parameters(extra: String) = answer("{\"storeStatements\": {\"store\": ${json(store)}, \"graph\": \"g\", $extra}}")["parameters"]!!
            assertEquals(json(StoreStamp.assignParameters("s1", "2026-01-01T00:00:00Z", engine)), CypherJson.writeValueAsString(parameters("\"stampParameters\": {\"storeId\": \"s1\", \"assignedAt\": \"2026-01-01T00:00:00Z\"}")), "on $target, $store")
            assertEquals(json(StoreStamp.restampParameters(engine)), CypherJson.writeValueAsString(parameters("\"restamp\": true")), "on $target, $store")
            assertEquals(
                json(MarkerStatements.of(ddl).mergeParameters("schema", null, "a\nb", 1700000000000L)),
                CypherJson.writeValueAsString(parameters("\"markerParameters\": {\"scope\": \"schema\", \"version\": null, \"items\": \"a\\nb\", \"appliedAt\": 1700000000000}")),
                "on $target, $store",
            )
        }
    }

    @Test
    fun aRequestTheStoreCantAnswerIsAnError() {
        for ((request, message) in listOf(
            "{\"storeStatements\": 1}" to "A storeStatements request gives",
            "{\"storeStatements\": {\"graph\": \"g\"}}" to "A storeStatements request names its store",
            "{\"storeStatements\": {\"store\": \"neptune\"}}" to "No store neptune",
            "{\"storeStatements\": {\"store\": \"postgres\"}}" to "A storeStatements request on postgres names its graph",
            "{\"storeStatements\": {\"store\": \"sqlite\", \"graph\": \"g\", \"stampParameters\": {\"storeId\": 1}}}" to "stampParameters gives storeId as text",
            "{\"storeStatements\": {\"store\": \"sqlite\", \"graph\": \"g\", \"markerParameters\": {\"scope\": \"s\", \"items\": \"\", \"appliedAt\": 1.5}}}" to "markerParameters gives appliedAt",
            "{\"storeStatements\": {\"store\": \"sqlite\", \"graph\": \"g\"}, \"cypher\": \"RETURN 1\"}" to "A request gives one of",
            "{\"storeStatements\": {\"store\": \"oracle\", \"graph\": \"1g\"}}" to OracleDdl.refusal("1g")!!,
            "{\"storeStatements\": {\"store\": \"postgres\", \"graph\": \"g\", \"stampParameters\": {\"storeId\": \"s\"}}}" to "stampParameters gives assignedAt as text",
            "{\"storeStatements\": {\"store\": \"postgres\", \"graph\": \"g\", \"stampParameters\": 1}}" to "stampParameters gives storeId as text",
            "{\"storeStatements\": {\"store\": \"postgres\", \"graph\": \"g\", \"restamp\": false}}" to "restamp is true",
            "{\"storeStatements\": {\"store\": \"postgres\", \"graph\": \"g\", \"restamp\": true, \"markerParameters\": {}}}" to "A storeStatements request gives one of stampParameters, restamp and markerParameters",
            "{\"storeStatements\": {\"store\": \"sqlite\", \"graph\": \"g\", \"markerParameters\": 1}}" to "markerParameters gives {",
            "{\"storeStatements\": {\"store\": \"sqlite\", \"graph\": \"g\", \"markerParameters\": {\"items\": \"\", \"appliedAt\": 1}}}" to "markerParameters gives scope as text",
            "{\"storeStatements\": {\"store\": \"sqlite\", \"graph\": \"g\", \"markerParameters\": {\"scope\": \"s\", \"version\": 1, \"items\": \"\", \"appliedAt\": 1}}}" to "markerParameters gives version as text or null",
            "{\"storeStatements\": {\"store\": \"sqlite\", \"graph\": \"g\", \"markerParameters\": {\"scope\": \"s\", \"appliedAt\": 1}}}" to "markerParameters gives items as text",
        )) {
            val error = answer(request)["error"]?.textValue()
            assertTrue(error != null && error.startsWith(message), "on $target, $request: $error")
        }
    }
}
