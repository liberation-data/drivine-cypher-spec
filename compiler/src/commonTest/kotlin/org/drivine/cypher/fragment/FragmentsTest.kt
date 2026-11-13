package org.drivine.cypher.fragment

import org.drivine.cypher.api.CompileJson
import org.drivine.cypher.json.ArrayNode
import org.drivine.cypher.json.CypherJson
import org.drivine.cypher.json.JsonNode
import org.drivine.cypher.json.ObjectNode
import org.drivine.cypher.number.oracleFile
import org.drivine.cypher.number.target
import org.drivine.cypher.CompilerException
import org.drivine.cypher.sql.FragmentVariable
import org.drivine.cypher.sql.OracleCypherCompiler
import org.drivine.cypher.sql.PostgresCypherCompiler
import org.drivine.cypher.sql.SqliteCypherCompiler
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
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
        val node = "\"variables\": {\"n\": {\"node\": \"r_\"}}"
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
            "{\"condition\": \"n.k = 1 RETURN 2\", $node}" to "A fragment is one Cypher expression: n.k = 1 RETURN 2",
            "{\"value\": \"DISTINCT n.k\", $node}" to "A fragment is one Cypher expression: DISTINCT n.k",
            "{\"condition\": \"count(n) > 1\", $node}" to "count() aggregates, and a WHERE holds no aggregate",
            "{\"order\": \"sum(n.k)\", $node}" to "sum() aggregates, and a WHERE holds no aggregate",
            "{\"condition\": \"m.k = 1\", $node}" to "m is not defined",
            "{\"condition\": \"n.k = \$missing\", $node}" to "missing",
        )) {
            val got = CypherJson.readTree(CompileJson.compile("{\"store\": \"sqlite\", $graph, \"fragment\": $fragment}"))
            assertTrue(got["refusal"]?.textValue()?.contains(refusal) == true, "$fragment: $got")
        }
    }

    /** A row of people, as `fragments.yaml`'s variables give it. */
    private val people = mapOf("n" to FragmentVariable.Node("people_", listOf("Person")))

    @Test
    fun aFragmentThatCallsAFunctionAStatementDeclaredBeforeIsRefusedStillAndTheStatementsDeclareItAgain() {
        // Libraries keep one compiler: a function declared for a statement compiled before is still one the library's SQL lacks.
        val oracle = OracleCypherCompiler("g")
        val statement = "UNWIND [[1]] AS x RETURN x ORDER BY x"
        assertTrue(oracle.compile(statement, emptyMap()).sql.startsWith("WITH\nFUNCTION cy_list_key("))
        val refused = assertFailsWith<CompilerException> { oracle.order("[n.a]", false, people) }
        assertTrue(refused.message!!.startsWith("A fragment calls a function the store declares"), refused.message)
        // And none the fragment declared, nor one taken off before, changes the next statement.
        assertEquals(OracleCypherCompiler("g").also { it.compile(statement, emptyMap()) }.compile(statement, emptyMap()).sql, oracle.compile(statement, emptyMap()).sql)
    }

    @Test
    fun aFragmentIsWrittenAsTheWhereOfAStatementThatReadsWouldBe() {
        // A float divided by zero works out an infinity, which Postgres's and SQLite's statements that read compile for.
        for (compiler in listOf(PostgresCypherCompiler("g"), SqliteCypherCompiler("g"))) {
            val term = compiler.condition("n.a / 0.0 > 1", people).sql
            val fresh = if (compiler is PostgresCypherCompiler) PostgresCypherCompiler("g") else SqliteCypherCompiler("g")
            val statement = fresh.compile("MATCH (people_) WHERE people_.a / 0.0 > 1 RETURN 1", emptyMap()).sql
            assertTrue(statement.contains(Regex("2e309|numberDouble")) && term.contains(Regex("2e309|numberDouble")), "$term\n$statement")
        }
        // Oracle routes a read of a long text the CLOB way, as it routes a statement's (drivine4j#670).
        assertTrue(OracleCypherCompiler("g").value("[n.name]", people).sql.contains("@.name.length() <= 4000"))
        // And holds a walk's ids as its own bound needs, not as the statement compiled before needed them.
        for (walk in listOf("EXISTS { (n)-[*]->() }", "EXISTS { (n)-[*1..2]->() }")) {
            val oracle = OracleCypherCompiler("g")
            val alone = OracleCypherCompiler("g").condition(walk, people)
            // A walk with no bound holds its ids as JSON, and one with a bound as text (see OracleIdListFormTest).
            assertEquals("[*]" in walk, "CAST(',' AS VARCHAR2(4000))" !in alone.sql, alone.sql)
            for (before in listOf("MATCH (a)-[*]->(b) RETURN 1", "MATCH (a)-[*1..2]->(b) RETURN 1")) {
                oracle.compile(before, emptyMap())
                assertEquals(alone, oracle.condition(walk, people), "$walk after $before")
            }
        }
    }

    @Test
    fun aRowNamedAsTheCompilerNamesItsOwnIsRefusedAsASubqueryWouldTakeItForOne() {
        // `n1` is the compiler's name for the node a subquery matches: the walk would be read from it to itself.
        val refused = assertFailsWith<CompilerException> { PostgresCypherCompiler("g").condition("EXISTS { (n)-->(m) }", mapOf("n" to FragmentVariable.Node("n1"))) }
        assertEquals("A fragment's variable n is a row whose name ends with _, as no row the compiler names does: n1", refused.message)
        for (row in listOf("x", "knows")) {
            assertFailsWith<CompilerException> { SqliteCypherCompiler("g").condition("r.w > 1", mapOf("r" to FragmentVariable.Relationship(row))) }
        }
        assertTrue(PostgresCypherCompiler("g").condition("EXISTS { (n)-->(m) }", people).sql.contains("e2.src = people_.nid AND e2.dst = n1.nid"))
    }

    @Test
    fun anExpressionThatEndsWithALineCommentIsOneExpression() {
        for (compiler in listOf(PostgresCypherCompiler("g"), OracleCypherCompiler("g"), SqliteCypherCompiler("g"))) {
            assertEquals(compiler.condition("n.name = \$p", people, mapOf("p" to "Ada")), compiler.condition("n.name = \$p // by name", people, mapOf("p" to "Ada")))
        }
    }

    @Test
    fun aRelationshipIsReadFromItsRowAndANodeOfNoLabelsByNoLabelsIndex() {
        val knows = mapOf("r" to FragmentVariable.Relationship("knows_"))
        for (compiler in listOf(PostgresCypherCompiler("g"), OracleCypherCompiler("g"), SqliteCypherCompiler("g"))) {
            assertTrue(compiler.condition("type(r) = 'KNOWS'", knows).sql.contains("knows_.type"), "$compiler")
            assertTrue(compiler.order("r.since", false, knows).terms.all { "knows_.props" in it }, "$compiler")
        }
        // Oracle tests a node's labels beside an indexed equality only where the library gives them.
        val oracle = OracleCypherCompiler("g", false, false, setOf("Person" to "name"))
        assertTrue("JSON_EXISTS(people_.labels" in oracle.condition("n.name = 'Ada'", people).sql)
        assertTrue("people_.labels" !in oracle.condition("n.name = 'Ada'", mapOf("n" to FragmentVariable.Node("people_"))).sql)
    }

    private companion object {
        val STORES = listOf("postgres", "oracle", "sqlite")
    }
}
