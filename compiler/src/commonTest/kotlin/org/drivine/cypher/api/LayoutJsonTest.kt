package org.drivine.cypher.api

import org.drivine.cypher.json.ArrayNode
import org.drivine.cypher.json.CypherJson
import org.drivine.cypher.json.JsonNode
import org.drivine.cypher.json.ObjectNode
import org.drivine.cypher.number.oracleFile
import org.drivine.cypher.number.target
import org.drivine.cypher.store.marker.StoreStamp
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * The store layout's questions through the JSON entry ([LayoutJson]), as TypeScript and Python ask them: each of the spec's
 * pinned answers, `marker.yaml`'s `storeLayout` verdicts and `host.yaml`'s sort keys and reads, given back on every target
 * (liberation-data/drivine-cypher-spec#161).
 */
class LayoutJsonTest {

    private val marker: JsonNode = CypherJson.readTree(oracleFile("marker.json"))
    private val host: JsonNode = CypherJson.readTree(oracleFile("host.json"))

    private fun json(value: Any?) = CypherJson.writeValueAsString(CypherJson.valueToTree(value))

    private fun answer(request: String): JsonNode = CypherJson.readTree(CompileJson.compile(request))

    private val engines = mapOf(
        "postgres" to "POSTGRES", "oracle" to "ORACLE", "sqlite" to "SQLITE", "neo4j" to "NEO4J", "memgraph" to "MEMGRAPH", "falkordb" to "FALKORDB",
    )

    @Test
    fun eachGraphsLayoutIsJudgedAsMarkerYamlSays() {
        val graph = marker["graph"]!!.textValue()!!
        var checked = 0
        for (case in marker["storeLayout"]!!["cases"]!!) {
            val name = case["name"]!!.textValue()!!
            val engine = engines.getValue(case["store"]!!.textValue()!!)
            val request = "{\"layout\": {\"engine\": ${json(engine)}, \"graph\": ${json(graph)}, \"stamps\": ${CypherJson.writeValueAsString(case["stamps"]!!)}, " +
                "\"foreignNodes\": ${case["foreignNodes"]!!.booleanValue()}${case["objects"]?.let { ", \"objects\": ${CypherJson.writeValueAsString(it)}" }.orEmpty()}}}"
            val got = answer(request)
            val expected = case["verdict"]!!
            when {
                expected.isTextual -> assertEquals("current", got["verdict"]?.textValue(), "on $target, $name: $got")
                expected["stamp"] != null -> {
                    assertEquals("stamp", got["verdict"]?.textValue(), "on $target, $name: $got")
                    assertEquals(texts(expected["stamp"]!!["drops"]!!), texts(got["drops"]!!), "on $target, $name: the drops")
                    assertEquals(
                        StoreStamp.restampParameters(engine).mapValues { (_, v) -> v?.toString() },
                        (got["parameters"] as ObjectNode).properties().associate { (k, v) -> k to (if (v.isNull) null else v.asText()) },
                        "on $target, $name: the re-stamp's parameters",
                    )
                }
                else -> {
                    assertEquals("refuse", got["verdict"]?.textValue(), "on $target, $name: $got")
                    assertEquals(expected["refuse"]!!.textValue(), got["message"]?.textValue(), "on $target, $name")
                }
            }
            checked++
        }
        assertTrue(checked > 0, "marker.yaml has no storeLayout cases")
    }

    @Test
    fun eachSortKeyIsTheOneHostYamlPins() {
        var checked = 0
        for (case in host["cases"]!!) for (step in case["steps"]!!) {
            val key = step["key"] ?: continue
            val got = answer("{\"temporalKey\": ${CypherJson.writeValueAsString(key)}}")
            assertEquals(step["result"]?.textValue(), got["key"]?.textValue(), "on $target: the sort key of $key")
            checked++
        }
        assertTrue(checked > 10, "host.yaml has $checked sort keys")
    }

    @Test
    fun aColumnThatHoldsTagsAndEscapedKeysIsReadAsHostYamlSays() {
        val case = host["cases"]!!.single { it["name"]!!.textValue() == "Rows that hold temporal values and keys that begin with \$" }
        for (step in case["steps"]!!) {
            val column = step["read"]!!["column"]!!.textValue()!!
            val got = answer("{\"read\": ${json(column)}}")
            assertEquals(CypherJson.writeValueAsString(step["value"]!!), CypherJson.writeValueAsString(got["value"]!!), "on $target: $column")
        }
        // Typed, a tag keeps its kind, without its key, and a map with a `$t` key of its own is told from a tag.
        val typed = answer("{\"read\": ${json("{\"d\":{\"\$t\":\"date\",\"v\":\"2024-01-01\",\"s\":\"10000020240101\"},\"m\":{\"\$\$t\":1}}")}, \"typed\": true}")
        assertEquals("{\"d\":{\"\$t\":\"date\",\"v\":\"2024-01-01\"},\"m\":{\"\$t\":\"map\",\"v\":{\"\$t\":1}}}", CypherJson.writeValueAsString(typed["value"]!!))
        // The column is given as its text so a whole number past 2^53 comes back as the store gave it, not rounded.
        assertEquals("{\"value\":[9007199254740993]}", CompileJson.compile("{\"read\": \"[9007199254740993]\"}"))
        // typed given as null is typed left out: a tag is read as its text.
        assertEquals("{\"value\":\"2024-01-01\"}", CompileJson.compile("{\"read\": ${json("{\"\$t\":\"date\",\"v\":\"2024-01-01\",\"s\":\"10000020240101\"}")}, \"typed\": null}"))
    }

    @Test
    fun aRequestTheLayoutCantAnswerIsAnError() {
        for ((request, message) in listOf(
            "{\"temporalKey\": {\"kind\": \"week\", \"v\": \"2024-W01\"}}" to "No temporal kind week: datetime, localdatetime, date, time, localtime, duration.",
            "{\"read\": 1}" to "A read request gives the column as its JSON text.",
            "{\"read\": \"{\", \"typed\": \"yes\"}" to "A request gives typed as true or false.",
            "{\"layout\": {\"engine\": \"POSTGRES\", \"graph\": \"g\", \"stamps\": [], \"foreignNodes\": \"no\"}}" to "A layout request gives foreignNodes as true or false.",
            "{\"temporalKey\": {\"kind\": \"localtime\", \"v\": \"10:00\"}}" to "A temporalKey request gives v as a localtime's canonical text: 10:00:00, not 10:00.",
            "{\"temporalKey\": {\"kind\": \"date\", \"v\": \"20240101\"}}" to "A temporalKey request gives v as a date's canonical text: no date, not 20240101.",
            "{\"temporalKey\": {\"kind\": \"datetime\", \"v\": \"2024-01-01\"}}" to "A temporalKey request gives v as a datetime's canonical text: 2024-01-01T00:00:00Z, not 2024-01-01.",
            "{\"temporalKey\": {\"kind\": \"date\"}}" to "A temporalKey request gives v, the canonical text, as a text.",
            "{\"temporalKey\": {\"v\": \"2024-01-01\"}}" to "A temporalKey request gives the kind as a text.",
            "{\"temporalKey\": 3}" to "A temporalKey request gives {\"kind\": …, \"v\": …}.",
            "{\"layout\": 3}" to "A layout request gives {\"engine\": …, \"graph\": …, \"stamps\": […], \"foreignNodes\": …}.",
            "{\"layout\": {\"graph\": \"g\", \"stamps\": [], \"foreignNodes\": false}}" to "A layout request gives the engine as a text.",
            "{\"layout\": {\"engine\": \"postgres\", \"graph\": \"g\", \"stamps\": [], \"foreignNodes\": false}}" to "A layout request names the engine as its stamp does: POSTGRES, not postgres.",
            "{\"layout\": {\"engine\": \"neo4j\", \"graph\": \"g\", \"stamps\": [{\"layout\": 2}], \"foreignNodes\": false}}" to "A layout request names the engine as its stamp does: NEO4J, not neo4j.",
            "{\"layout\": {\"engine\": \"POSTGRES\", \"stamps\": [], \"foreignNodes\": false}}" to "A layout request gives the graph as a text.",
            "{\"layout\": {\"engine\": \"POSTGRES\", \"graph\": \"g\", \"stamps\": {}, \"foreignNodes\": false}}" to "A layout request gives the stamps as a list.",
            "{\"layout\": {\"engine\": \"POSTGRES\", \"graph\": \"g\", \"stamps\": [2], \"foreignNodes\": false}}" to "A layout request's stamp is a map.",
            "{\"layout\": {\"engine\": \"POSTGRES\", \"graph\": \"g\", \"stamps\": [{\"layout\": 2.5}], \"foreignNodes\": false}}" to "A layout request's stamp gives its layout as a whole number: 2.5.",
            "{\"layout\": {\"engine\": \"POSTGRES\", \"graph\": \"g\", \"stamps\": [{\"layout\": 4294967298}], \"foreignNodes\": false}}" to "A layout request's stamp gives its layout as a whole number: 4294967298.",
            "{\"layout\": {\"engine\": \"POSTGRES\", \"graph\": \"g\", \"stamps\": [], \"foreignNodes\": false, \"objects\": {}}}" to "A layout request gives the objects as a list.",
            "{\"layout\": {\"engine\": \"POSTGRES\", \"graph\": \"g\", \"stamps\": [], \"foreignNodes\": false, \"objects\": [\"x\"]}}" to "A layout request's object is a map.",
            "{\"read\": \"1\", \"cypher\": \"RETURN 1\"}" to "A request gives one of cypher, search, searchIndex, settings, read, temporalKey and layout.",
        )) {
            assertEquals(message, answer(request)["error"]?.textValue(), request)
        }
        assertTrue(answer("{\"read\": \"{\"}")["error"]!!.textValue()!!.startsWith("A read request's column is no JSON"))
        // A column is read as a request is, so one that nests past MAX_DEPTH is an error, not a stack overflown on native.
        assertEquals(
            "A read request's column is no JSON: A request nests its arrays and objects at most 64 deep.",
            answer("{\"read\": ${json("[".repeat(65) + "]".repeat(65))}}")["error"]?.textValue(),
        )
        assertEquals("null", CypherJson.writeValueAsString(answer("{\"temporalKey\": {\"kind\": \"duration\", \"v\": \"P1D\"}}")["key"]!!), "a duration has no key")
        // A stamp's layout as a whole double or as its text is that layout, and objects given as null are left out.
        for (layout in listOf("2", "2.0", "\"2\"")) {
            val got = answer("{\"layout\": {\"engine\": \"POSTGRES\", \"graph\": \"g\", \"stamps\": [{\"layout\": $layout}], \"foreignNodes\": true, \"objects\": null}}")
            assertEquals("current", got["verdict"]?.textValue(), "layout $layout: $got")
        }
    }

    private fun texts(node: JsonNode): List<String> = (node as ArrayNode).map { it.textValue()!! }
}
