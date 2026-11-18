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
                    assertEquals(expected["stamp"]!!["assign"]?.booleanValue() ?: false, got["assign"]!!.booleanValue(), "on $target, $name: whether it is stamped anew")
                    assertEquals(
                        StoreStamp.restampParameters(engine).mapValues { (_, v) -> v?.toString() },
                        (got["parameters"] as ObjectNode).properties().associate { (k, v) -> k to (if (v.isNull) null else v.asText()) },
                        "on $target, $name: the re-stamp's parameters",
                    )
                }
                else -> {
                    assertEquals("refuse", got["verdict"]?.textValue(), "on $target, $name: $got")
                    assertEquals(expected["refuse"]!!.textValue(), got["message"]?.textValue(), "on $target, $name")
                    assertEquals(expected["from"]?.longValue(), got["from"]?.longValue(), "on $target, $name: the layout a migration moves it from")
                    assertEquals(expected["inProgress"]?.booleanValue() ?: false, got["inProgress"]?.booleanValue() ?: false, "on $target, $name: part way through a migration")
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
    fun anInfinityIsReadBackAsANumberAsSqliteWritesOne() {
        // SQLite writes an infinity as 9e999: read beside a tag or an escaped key, it stays a number, not the text "Infinity".
        val got = CompileJson.compile("{\"read\": ${json("{\"\$\$k\":[9e999,-9e999,\"Infinity\"],\"d\":{\"\$t\":\"date\",\"v\":\"2024-01-01\"}}")}}")
        assertEquals("{\"value\":{\"\$k\":[9e999,-9e999,\"Infinity\"],\"d\":\"2024-01-01\"}}", got, "on $target")
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
            "{\"rewrite\": {}, \"store\": \"sqlite\", \"labels\": []}" to "A rewrite request gives the row as its JSON text.",
            "{\"rewrite\": \"{}\", \"labels\": []}" to "A rewrite request gives the store: postgres, oracle or sqlite.",
            "{\"rewrite\": \"{}\", \"store\": \"neo4j\", \"labels\": []}" to "A rewrite request gives the store as postgres, oracle or sqlite, not neo4j.",
            "{\"rewrite\": \"{}\", \"store\": \"sqlite\"}" to "A rewrite request gives the labels as a list.",
            "{\"rewrite\": \"{}\", \"store\": \"sqlite\", \"labels\": [1]}" to "A rewrite request's label is a text.",
            "{\"rewrite\": \"{}\", \"store\": \"sqlite\", \"labels\": [], \"declarations\": {}}" to "A rewrite request gives the declarations as a list.",
            "{\"rewrite\": \"{}\", \"store\": \"sqlite\", \"labels\": [], \"declarations\": [{\"label\": \"A\", \"kind\": \"date\"}]}" to "A rewrite request's declaration gives its property as a text.",
            "{\"rewrite\": \"{}\", \"store\": \"sqlite\", \"labels\": [], \"declarations\": [{\"label\": \"A\", \"property\": \"p\", \"kind\": \"week\"}]}" to "No kind week to declare: date, localtime, time, localdatetime, datetime, duration, float, bytes.",
            "{\"rewrite\": \"{}\", \"store\": \"sqlite\", \"labels\": [], \"declarations\": [{\"label\": \"A\", \"property\": \"p\", \"kind\": \"date\"}, {\"label\": \"A\", \"property\": \"p\", \"kind\": \"float\"}]}" to "The property p of A is declared date and float: declare one kind.",
            "{\"rewrite\": \"[1]\", \"store\": \"sqlite\", \"labels\": []}" to "A row's properties are a JSON object, not [1].",
            "{\"read\": \"1\", \"cypher\": \"RETURN 1\"}" to "A request gives one of cypher, search, searchIndex, vector, settings, fragment, read, temporalKey, layout, expand, write, rewrite, storeStatements, schemaPlan, idSql and migrate.",
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

    @Test
    fun aStatementOfStoreYamlHasEachGeneratedFunctionExpandedAsTheModuleMakesIt() {
        val store = CypherJson.readTree(oracleFile("store.json"))
        val tables = store["tables"]!!
        val held = listOf(tables["postgres"]!!["ensuring"]!!, tables["oracle"]!!["ensuring"]!!) + (tables["oracle"]!!["functions"] as ArrayNode).toList()
        val graph = store["graph"]!!.textValue()!!
        val made = listOf(org.drivine.cypher.store.ddl.PostgresDdl(graph).ensuring, org.drivine.cypher.store.ddl.OracleDdl(graph).ensuring) +
            org.drivine.cypher.store.ddl.OracleDdl(graph).functions
        held.zip(made).forEach { (statement, expected) ->
            assertEquals(expected, answer("{\"expand\": ${json(statement.textValue())}}")["text"]!!.textValue(), "on $target")
        }
        assertTrue(answer("{\"expand\": 1}")["error"] != null)
    }

    @Test
    fun eachValueASaveWritesIsAsHostYamlSays() {
        var checked = 0
        for (case in host["cases"]!!) for ((index, step) in case["steps"]!!.withIndex()) {
            val write = step["write"] ?: continue
            val where = "on $target, ${case["name"]!!.textValue()}, step ${index + 1}"
            val request = "{\"write\": ${CypherJson.writeValueAsString(write["value"]!!)}" +
                write["store"]?.let { ", \"store\": ${CypherJson.writeValueAsString(it)}" }.orEmpty() + "}"
            val got = CompileJson.compile(request)
            // The text compared as it is: an infinity's 9e999 and a whole float's fraction would not survive a read of it.
            when (val error = step["error"]) {
                null -> assertEquals("{\"value\":${step["result"]!!.textValue()}}", got, "$where: $request")
                else -> assertEquals(error.textValue(), answer(request)["error"]?.textValue(), "$where: $request gave $got")
            }
            checked++
        }
        assertTrue(checked >= 30, "host.yaml has $checked write steps")
    }

    @Test
    fun aValueWrittenIsAsTheStoreHoldsItThroughStoreValues() {
        // Each request's value beside the Kotlin value a library gives StoreValues.text: the same text, on every target.
        val date = org.drivine.cypher.json.TemporalValue(org.drivine.cypher.sql.Temporal.DATE, "2024-01-01")
        val at = org.drivine.cypher.json.TemporalValue(org.drivine.cypher.sql.Temporal.DATE_TIME, "2024-01-01T10:00:00+02:00")
        val cases = listOf(
            "{\"\$temporal\": \"DATE\", \"text\": \"2024-01-01\"}" to date,
            "{\"a\": {\"\$temporal\": \"DATE_TIME\", \"text\": \"2024-01-01T10:00:00+02:00\"}, \"\$k\": [1, {\"\$\$x\": true}]}" to
                mapOf("a" to at, "\$k" to listOf(1L, mapOf("\$\$x" to true))),
            "[{\"\$temporal\": \"DURATION\", \"text\": \"PT1S\"}, \"x\", null]" to
                listOf(org.drivine.cypher.json.TemporalValue(org.drivine.cypher.sql.Temporal.DURATION, "PT1S"), "x", null),
            "9007199254740993" to 9007199254740993L,
            "1.5" to 1.5,
            "[2.0, {\"\$float\": \"1e24\"}]" to listOf(2.0, 1e24),
            "\"\$t\"" to "\$t",
            "null" to null,
        )
        for (store in Store.entries) for ((given, value) in cases) {
            val got = CompileJson.compile("{\"write\": $given, \"store\": \"${store.id}\"}")
            assertEquals("{\"value\":${org.drivine.cypher.store.StoreValues.text(value, store)}}", got, "on $target, ${store.id}, $given")
        }
        // A temporal value's tag holds its sort key, and a key that begins with `$` is escaped, however deep.
        val written = answer("{\"write\": {\"m\": {\"\$x\": {\"\$temporal\": \"DATE\", \"text\": \"2024-01-01\"}}}, \"store\": \"postgres\"}")["value"]!!
        assertEquals("date", written["m"]!!["\$\$x"]!!["\$t"]!!.textValue(), "on $target: $written")
        assertTrue(written["m"]!!["s"] == null && written["m"]!!["\$\$x"]!!["s"]!!.isTextual, "on $target: $written")
        // Postgres writes a whole float with its fraction, which jsonb keeps; Oracle every float as extended JSON's double.
        assertEquals("{\"value\":[10000000.0,1.5]}", CompileJson.compile("{\"write\": [1e7, 1.5], \"store\": \"postgres\"}"))
        assertEquals("{\"value\":[{\"\$numberDouble\":\"1.0E7\"},2]}", CompileJson.compile("{\"write\": [1e7, 2], \"store\": \"oracle\"}"))
        assertEquals("{\"value\":[1.0E7,9e999]}", CompileJson.compile("{\"write\": [1e7, {\"\$float\": \"Infinity\"}], \"store\": \"sqlite\"}"))
        assertTrue(answer("{\"write\": ${"[".repeat(65) + "]".repeat(65)}, \"store\": \"sqlite\"}")["error"] != null, "a value nested past MAX_DEPTH is refused")
    }

    @Test
    fun aValueWrittenReadsEachTaggedForm() {
        // A float, bytes and a map of `$` keys, each as a request's parameter gives one, beside the Kotlin value it stands for.
        for ((given, value) in listOf(
            "{\"\$float\": \"2.0\"}" to 2.0,
            "{\"\$float\": 3}" to 3.0,
            "{\"\$bytes\": \"AQI=\"}" to byteArrayOf(1, 2),
            "{\"\$map\": {\"\$t\": 1, \"\$\$y\": {\"\$temporal\": \"DATE\", \"text\": \"2024-01-01\"}}}" to
                mapOf("\$t" to 1L, "\$\$y" to org.drivine.cypher.json.TemporalValue(org.drivine.cypher.sql.Temporal.DATE, "2024-01-01")),
            // Valid text of another form is written as its kind's canonical text, as drivine4j writes a java.time value.
            "{\"\$temporal\": \"DATE_TIME\", \"text\": \"2024-01-01T10:00+0200\"}" to
                org.drivine.cypher.json.TemporalValue(org.drivine.cypher.sql.Temporal.DATE_TIME, "2024-01-01T10:00:00+02:00"),
        )) for (store in Store.entries) {
            val got = CompileJson.compile("{\"write\": $given, \"store\": \"${store.id}\"}")
            assertEquals("{\"value\":${org.drivine.cypher.store.StoreValues.text(value, store)}}", got, "on $target, ${store.id}, $given")
        }
        // Bytes are the list of their signed values on every store, not base64 text, which a store reads back as a text.
        for (store in Store.entries) {
            assertEquals("{\"value\":[1,2,-1]}", CompileJson.compile("{\"write\": {\"\$bytes\": \"AQL/\"}, \"store\": \"${store.id}\"}"), "on $target, ${store.id}")
        }
    }

    @Test
    fun aWriteOfNoValueOfItsKindOrForNoSqlStoreIsAnError() {
        // A temporal value whose text is no value of its kind is refused, not written as a text the layout reads as one.
        for ((request, message) in listOf(
            "{\"write\": {\"\$temporal\": \"DATE\", \"text\": \"2024-1-1\"}, \"store\": \"postgres\"}" to "A value given as a DATE isn't one: 2024-1-1.",
            "{\"write\": [{\"a\": {\"\$temporal\": \"DURATION\", \"text\": \"1 day\"}}], \"store\": \"oracle\"}" to "A value given as a DURATION isn't one: 1 day.",
            "{\"write\": {\"\$temporal\": \"DAY\", \"text\": \"2024-01-01\"}, \"store\": \"sqlite\"}" to "No temporal kind DAY",
            "{\"write\": {\"\$bytes\": 1}, \"store\": \"sqlite\"}" to "{\"\$bytes\": …} holds base64 text.",
            "{\"write\": {\"\$map\": 1}, \"store\": \"sqlite\"}" to "{\"\$map\": …} holds a map.",
            "{\"write\": 1}" to "A write request gives the store: postgres, oracle or sqlite.",
            "{\"write\": 1, \"store\": null}" to "A write request gives the store: postgres, oracle or sqlite.",
            "{\"write\": 1, \"store\": 1}" to "A write request gives the store: postgres, oracle or sqlite.",
            "{\"write\": 1, \"store\": \"POSTGRES\"}" to "A write request gives the store as postgres, oracle or sqlite, not POSTGRES.",
            "{\"write\": 1, \"store\": \"memgraph\"}" to "A write request gives the store as postgres, oracle or sqlite, not memgraph.",
            "{\"write\": null, \"store\": \"sqlite\"}" to "",
        )) {
            val answer = answer(request)
            if (message.isEmpty()) {
                assertEquals("null", CypherJson.writeValueAsString(answer["value"]!!), "on $target, $request: $answer")
                continue
            }
            val error = answer["error"]?.textValue()
            assertTrue(error != null && error.startsWith(message), "on $target, $request: $error")
        }
    }

    @Test
    fun aRewriteRequestOfNoDeclarationsDeclaresNothing() {
        // Declarations left out, or null, are none: a property is rewritten only as needs no declaration.
        for (declarations in listOf("", ", \"declarations\": null")) {
            val request = "{\"rewrite\": ${json("{\"born\":\"2024-03-15\",\"\$t\":1}")}, \"store\": \"sqlite\", \"labels\": [\"Person\"]$declarations}"
            assertEquals(
                "{\"value\":{\"born\":\"2024-03-15\",\"\$\$t\":1},\"changed\":true,\"changes\":[{\"property\":\"\$t\",\"change\":\"escapedKey\"}],\"invalid\":[]}",
                CompileJson.compile(request), "on $target, $request",
            )
        }
    }

    private fun texts(node: JsonNode): List<String> = (node as ArrayNode).map { it.textValue()!! }
}
