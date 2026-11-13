package org.drivine.cypher.fragment

import org.drivine.cypher.api.CompileJson
import org.drivine.cypher.json.ArrayNode
import org.drivine.cypher.json.CypherJson
import org.drivine.cypher.json.JsonNode
import org.drivine.cypher.json.ObjectNode
import org.drivine.cypher.number.oracleFile
import org.drivine.cypher.number.target
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Each of `fragments.yaml`'s cases asked of the JSON entry ([org.drivine.cypher.api.FragmentJson]) for each SQL store, as
 * TypeScript and Python ask it and as drivine4j asks the compiler: the terms each store gives are those the case pins, on
 * every target (liberation-data/drivine-cypher-spec#161). Each library's runner holds the store's answer to Neo4j's rows.
 */
class FragmentsTest {

    private val spec: JsonNode = CypherJson.readTree(oracleFile("fragments.json"))

    private fun json(node: JsonNode?) = CypherJson.writeValueAsString(node)

    /** The request for [case] on [store], as a library writes it. */
    fun request(case: JsonNode, store: String): String {
        val part = listOf("condition", "value", "order").single { case[it] != null }
        val descending = case["descending"]?.let { ", \"descending\": ${json(it)}" }.orEmpty()
        return "{\"store\": \"$store\", \"graph\": {\"name\": ${json(spec["graph"])}, \"indexedProperties\": ${json(spec["indexed"])}}, " +
            "\"fragment\": {\"$part\": ${json(case[part])}$descending, \"variables\": ${json(spec["variables"])}}, " +
            "\"parameters\": ${case["parameters"]?.let(::json) ?: "{}"}}"
    }

    @Test
    fun eachCaseGivesTheTermsItPinsOnEachStore() {
        var checked = 0
        for (case in spec["cases"]!!) {
            val name = case["name"]!!.textValue()!!
            for (store in STORES) {
                val got = CypherJson.readTree(CompileJson.compile(request(case, store)))
                val pinned = case["sql"]?.get(store) as? ArrayNode ?: throw AssertionError("on $target, $name pins no terms on $store: $got")
                assertEquals(pinned.map { it.textValue() }, (got["terms"] as? ArrayNode)?.map { it.textValue() }, "on $target, $name on $store: $got")
                checked++
            }
        }
        assertEquals(spec["cases"]!!.size() * STORES.size, checked)
    }

    @Test
    fun aFragmentBindsItsParametersAsAStatementDoes() {
        val case = spec["cases"]!!.first { it["name"]!!.textValue() == "A date before a date: a value of another kind is no date, and neither before nor after it" }
        for (store in STORES) {
            val got = CypherJson.readTree(CompileJson.compile(request(case, store))) as ObjectNode
            val bound = (got["parameters"] as ObjectNode).properties().associate { (k, v) -> k to v }
            assertEquals(setOf("_cy_d"), bound.keys, "on $target, $store: $got")
            // The date bound as its tag, with its sort key, as a statement binds it.
            assertTrue(bound.getValue("_cy_d").textValue()!!.contains("\"s\":\"10000019000101\""), "on $target, $store: $got")
            assertEquals(store == "oracle", got.has("storedFunctions"), "on $target, $store: $got")
        }
    }

    @Test
    fun aRequestThatIsNoFragmentIsAnErrorAndOneTheCompilerRefusesARefusal() {
        val graph = "\"graph\": {\"name\": \"g\"}"
        val node = "\"variables\": {\"n\": {\"node\": \"r\"}}"
        for ((request, message) in listOf(
            "{\"store\": \"postgres\", $graph, \"fragment\": 3}" to "A request's fragment is {\"condition\" | \"value\" | \"order\": …, \"variables\": {…}}.",
            "{\"store\": \"postgres\", $graph, \"fragment\": {$node}}" to "A fragment gives one of condition, value and order.",
            "{\"store\": \"postgres\", $graph, \"fragment\": {\"condition\": \"true\", \"value\": \"1\"}}" to "A fragment gives one of condition, value and order.",
            "{\"store\": \"postgres\", $graph, \"fragment\": {\"condition\": 1}}" to "A fragment gives its condition as a Cypher expression's text.",
            "{\"store\": \"postgres\", $graph, \"fragment\": {\"condition\": \"true\", \"descending\": true}}" to "Only an order is descending.",
            "{\"store\": \"postgres\", $graph, \"fragment\": {\"order\": \"1\", \"descending\": \"yes\"}}" to "A fragment gives descending as true or false.",
            "{\"store\": \"postgres\", $graph, \"fragment\": {\"condition\": \"true\", \"variables\": []}}" to "A fragment gives its variables as {\"<name>\": {…}, …}.",
            "{\"store\": \"postgres\", $graph, \"fragment\": {\"condition\": \"true\", \"variables\": {\"n\": {}}}}" to "A fragment's variable n is {\"node\": <row>, \"labels\": […]} or {\"relationship\": <row>}.",
            "{\"store\": \"postgres\", $graph, \"fragment\": {\"condition\": \"true\", \"variables\": {\"n\": {\"node\": \"r\", \"relationship\": \"r\"}}}}" to "A fragment's variable n is {\"node\": <row>, \"labels\": […]} or {\"relationship\": <row>}.",
            "{\"store\": \"postgres\", $graph, \"fragment\": {\"condition\": \"true\", \"variables\": {\"n\": {\"relationship\": \"r\", \"labels\": []}}}}" to "A fragment's variable n is a relationship, which has no labels.",
            "{\"store\": \"postgres\", $graph, \"fragment\": {\"condition\": \"true\", \"variables\": {\"n\": {\"node\": \"r\", \"labels\": \"L\"}}}}" to "A fragment's variable n gives its labels as a list.",
            "{\"store\": \"postgres\", $graph, \"fragment\": {\"condition\": \"true\", \"variables\": {\"n\": {\"node\": \"r\", \"labels\": [1]}}}}" to "A fragment's variable n gives its labels as texts.",
            "{\"store\": \"postgres\", $graph, \"cypher\": \"RETURN 1\", \"fragment\": {\"condition\": \"true\"}}" to "A request gives one of cypher, search, searchIndex, vector, settings, fragment, read, temporalKey and layout.",
        )) {
            assertEquals(message, CypherJson.readTree(CompileJson.compile(request))["error"]?.textValue(), request)
        }
        for ((fragment, refusal) in listOf(
            "{\"condition\": \"n.k = 1 RETURN 2\", $node}" to null,
            "{\"condition\": \"m.k = 1\", $node}" to "m is not defined",
            "{\"condition\": \"n.k = \$missing\", $node}" to "missing",
        )) {
            val got = CypherJson.readTree(CompileJson.compile("{\"store\": \"sqlite\", $graph, \"fragment\": $fragment}"))
            assertTrue(got["refusal"]?.textValue()?.contains(refusal ?: "") == true, "$fragment: $got")
        }
    }

    private companion object {
        val STORES = listOf("postgres", "oracle", "sqlite")
    }
}
