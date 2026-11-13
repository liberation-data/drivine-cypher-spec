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
            "{\"condition\": \"count(n) > 1\", $node}" to "The fragment API doesn't take count()",
            "{\"order\": \"sum(n.k)\", $node}" to "The fragment API doesn't take sum()",
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
    fun aTemporalKeyOfASumIsRefusedWhereTheStatementDeclaresItAndCalledWhereTheStoreKeepsIt() {
        // Oracle works out a key of a sum of a stored kind with a function a statement declares where the store keeps none
        // (eraStored false), written in only once the statement is whole: the fragment's SQL would call a function no one declared.
        fun ask(era: Boolean, fragment: String) = CypherJson.readTree(CompileJson.compile(
            "{\"store\": \"oracle\", \"graph\": {\"name\": \"g\", \"eraStored\": $era}, \"fragment\": {$fragment, " +
                "\"variables\": {\"n\": {\"node\": \"people_\"}}}, \"parameters\": {\"d\": {\"\$temporal\": \"DURATION\", \"text\": \"P1D\"}, " +
                "\"t\": {\"\$temporal\": \"DATE\", \"text\": \"2000-01-01\"}}}",
        ))
        for (fragment in listOf("\"order\": \"n.born + \$d\"", "\"condition\": \"n.born + \$d < \$t\"")) {
            val refused = ask(false, fragment)["refusal"]?.textValue()
            assertTrue(refused?.startsWith("A fragment calls a function the store declares") == true, "$fragment: $refused")
        }
        // Where the graph keeps the era's functions, the fragment calls the store's, and says so as a statement does.
        val answer = ask(true, "\"order\": \"n.born + \$d\"")
        assertTrue((answer["terms"] as ArrayNode).any { "g_temporal_key(" in it.textValue()!! }, "$answer")
        assertEquals(true, answer["storedFunctions"]?.get("era")?.booleanValue(), "$answer")
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
    fun aValueThatMayBeNaNOrAnInfinitySaysSoAsAStatementDoes() {
        // Postgres's JSON holds no infinity: the value comes back as the statement's would, which the library reads by nonFinite.
        for ((value, parameters) in listOf("n.a / 0.0" to emptyMap(), "n.a + \$f" to mapOf("f" to Double.NaN))) {
            val statement = PostgresCypherCompiler("g").compile("MATCH (people_) RETURN ${value.replace("n.", "people_.")}", parameters)
            assertTrue(statement.nonFinite, value)
            assertEquals(statement.nonFinite, PostgresCypherCompiler("g").value(value, people, parameters).nonFinite, value)
        }
        assertEquals(false, PostgresCypherCompiler("g").value("n.a / 2.0", people).nonFinite)
        val answer = CypherJson.readTree(CompileJson.compile("{\"store\": \"postgres\", \"graph\": {\"name\": \"g\"}, \"fragment\": {\"value\": \"n.a / 0.0\", \"variables\": {\"n\": {\"node\": \"people_\"}}}}"))
        assertEquals(true, answer["nonFinite"]?.booleanValue(), "$answer")
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
        // Through the JSON entry, a relationship's row is read as one.
        val answer = CypherJson.readTree(CompileJson.compile("{\"store\": \"sqlite\", \"graph\": {\"name\": \"g\"}, \"fragment\": {\"condition\": \"type(r) = 'KNOWS'\", \"variables\": {\"r\": {\"relationship\": \"knows_\"}}}}"))
        assertEquals(SqliteCypherCompiler("g").condition("type(r) = 'KNOWS'", knows).terms, (answer["terms"] as? ArrayNode)?.map { it.textValue() }, "$answer")
        // Oracle tests a node's labels beside an indexed equality only where the library gives them.
        val oracle = OracleCypherCompiler("g", false, false, setOf("Person" to "name"))
        assertTrue("JSON_EXISTS(people_.labels" in oracle.condition("n.name = 'Ada'", people).sql)
        assertTrue("people_.labels" !in oracle.condition("n.name = 'Ada'", mapOf("n" to FragmentVariable.Node("people_"))).sql)
    }

    @Test
    fun aFormNoLibraryWritesIsRefusedByNameAndEachTheyWriteIsTaken() {
        // randomUUID() a statement makes first for each row (#412), which the library's SQL has no column for; an aggregate,
        // which it has no group for; and the forms no library's object manager writes.
        val refused = mapOf(
            "toUpper(randomUUID()) + '-x'" to "randomUUID()", "randomUUID()" to "randomUUID()", "count(n) > 1" to "count()",
            "[x IN [1] | x] = [1]" to "a list comprehension", "reduce(s = 0, x IN [1] | s + x) = 1" to "reduce()",
            "size([(n)-->(m) | m]) > 0" to "a pattern comprehension", "(n)-->()" to "a pattern as a predicate, which EXISTS { … } writes",
            "n.list[0] = 1" to "an item read by its place or key, x[i]", "n.list[0..1] = [1]" to "a slice, x[i..j]",
            "{a: n.k} = {a: 1}" to "a map", "n {.k} = {k: 1}" to "a map projection", "COUNT { (n)-->() } > 1" to "a subquery, COUNT { … }",
            "single(x IN n.list WHERE x = 1)" to "single()", "n.k % 2 = 0" to "modulo", "n.k ^ 2 = 4" to "power",
            "EXISTS { p = (n)-->() }" to "a path variable", "date() > n.k" to "date()", "count(DISTINCT n.k) > 1" to "count()",
        )
        for (compiler in listOf(PostgresCypherCompiler("g"), OracleCypherCompiler("g"), SqliteCypherCompiler("g"))) {
            for ((cypher, form) in refused) {
                val message = assertFailsWith<CompilerException>("$compiler: $cypher") { compiler.condition(cypher, people) }.message
                assertEquals("The fragment API doesn't take $form: a fragment is a condition, a value or an order of the forms compiler/README.md lists. $cypher", message)
            }
            // Each form the libraries write, taken.
            for (cypher in listOf(
                "n.k = \$p", "n.k <> \$p", "n.k < \$p", "n.k IN \$l", "\$p IN n.list", "n.k IS NULL", "n.k IS NOT NULL",
                "n.k STARTS WITH \$p", "n.k ENDS WITH \$p", "n.k CONTAINS \$p", "n.k =~ \$p", "toLower(n.k) = toLower(\$p)",
                "NOT (n.k = \$p) OR (n.a > 1 XOR n.b < 2)", "n:Person:Admin", "any(x IN labels(n) WHERE x IN \$l)",
                "EXISTS { (n)-[:KNOWS*1..3]->(m:Person {name: \$p}) WHERE m.age > 1 }", "all(x IN n.list WHERE x > 1)",
                "none(x IN n.list WHERE x > 1)", "coalesce(n.a, 0) * 2 - floor(exp(n.b)) / toFloat(abs(n.c)) > -1",
                "CASE WHEN n.a IS NULL THEN 0 ELSE n.a END > toInteger(toString(size(n.list)))", "n.born.epochMillis / 1000.0 > 1",
                "toUpper(n.k) = 'A'",
            )) compiler.condition(cypher, people, mapOf("p" to "a", "l" to listOf("a")))
        }
    }

    @Test
    fun aVariableBoundAgainWithinIsNotTheLibrarysRowAndHasNoneOfItsLabels() {
        // `n` within the ANY is each node the person knows, which may be no Person: its equality isn't tested by Person's index.
        for (within in listOf("EXISTS { (n)-->(m) WHERE ANY(n IN [m] WHERE n.name = 'Ada') }", "any(n IN [n.friend] WHERE n.name = 'Ada')")) {
            val labelled = OracleCypherCompiler("g", false, false, setOf("Person" to "name")).condition(within, people).sql
            val unlabelled = OracleCypherCompiler("g", false, false, setOf("Person" to "name")).condition(within, mapOf("n" to FragmentVariable.Node("people_"))).sql
            assertEquals(unlabelled, labelled, within)
        }
    }

    /**
     * A fragment says the hints its library's query carries in its outermost `SELECT` as the statement that compares so,
     * `MATCH (p) WHERE <the condition> RETURN p`, is planned with them: on Oracle `NO_JSON_TABLE_TRANSFORM` where a comparison
     * reads a property by its own path or an item of a list by its place, without which Oracle 23 gives such a comparison the
     * wrong truth (`all(x IN [1] WHERE p.born > 1962) = true` was null for each person); none on Postgres and SQLite. A
     * fragment's own reads decide it, not those of a statement compiled before on the compiler, and a statement compiled after
     * is planned as on a compiler that compiled no fragment.
     */
    @Test
    fun aFragmentSaysTheHintsItsLibrarysQueryCarriesAsTheStatementThatComparesSoIsPlannedWithThem() {
        val variables = mapOf("p" to org.drivine.cypher.sql.FragmentVariable.Node("people_"))
        val conditions = listOf(
            "all(x IN [1] WHERE p.born > 1962) = true", "p.name = 'Ada'", "p.name IS NULL",
            "EXISTS { (p)-->() }", "true",
        )
        val oracle = org.drivine.cypher.sql.OracleCypherCompiler("g")
        // On Oracle every fragment is hinted against the JSON table transformation, a statement that compares so being hinted
        // too, and the library's query reading the graph's tables in a FROM the module doesn't see: each hint a statement
        // compiled of the condition carries, the fragment carries.
        for (condition in conditions.reversed() + conditions) {
            val statement = org.drivine.cypher.sql.OracleCypherCompiler("g").compile("MATCH (p) WHERE $condition RETURN p").sql
            val hints = oracle.condition(condition, variables).hints
            assertTrue("NO_JSON_TABLE_TRANSFORM" in hints, "$condition: $hints")
            if (statement.contains(OracleCypherCompiler.NO_FEEDBACK)) assertTrue(OracleCypherCompiler.NO_FEEDBACK in hints, "$condition: $hints, as\n$statement")
        }
        // A walk between two labelled nodes tests the later's labels as a node read by its key, which a statement is planned
        // without statistics feedback for: the fragment's query is too, and one whose walk starts at the library's row isn't.
        for ((walk, keyed) in listOf("EXISTS { (a:A)-[:R]->(b:B) }" to true, "EXISTS { (p)-[:R]->(b:B) }" to false)) {
            val statement = OracleCypherCompiler("g").compile("MATCH (p) WHERE $walk RETURN p").sql
            val hints = oracle.condition(walk, variables).hints
            assertEquals(keyed, statement.contains(OracleCypherCompiler.NO_FEEDBACK), "$walk: $statement")
            assertEquals(keyed, OracleCypherCompiler.NO_FEEDBACK in hints, "$walk: $hints")
        }
        // A fragment over two of the library's rows, as one over one, whose statement isn't hinted.
        val two = mapOf("p" to FragmentVariable.Node("people_"), "q" to FragmentVariable.Node("others_"))
        for (rows in listOf(variables, two)) assertTrue("NO_JSON_TABLE_TRANSFORM" in oracle.condition("p.id = 'a'", rows).hints, "$rows")
        // A statement compiled after the fragments is planned as on a compiler that compiled none.
        assertEquals(org.drivine.cypher.sql.OracleCypherCompiler("g").compile("MATCH (p) RETURN p.name").sql, oracle.compile("MATCH (p) RETURN p.name").sql)
        for (other in listOf(org.drivine.cypher.sql.PostgresCypherCompiler("g"), org.drivine.cypher.sql.SqliteCypherCompiler("g"))) {
            for (condition in conditions) assertEquals(emptyList(), other.condition(condition, variables).hints, "${other::class.simpleName}: $condition")
        }
        // Through the JSON entry too, and typed beside a fragment is an error.
        val json = CypherJson.readTree(CompileJson.compile("{\"store\": \"oracle\", \"graph\": {\"name\": \"g\"}, \"fragment\": {\"condition\": \"p.name = 'Ada'\", \"variables\": {\"p\": {\"node\": \"people_\"}}}}"))
        assertEquals("[\"NO_JSON_TABLE_TRANSFORM\"]", CypherJson.writeValueAsString(json["hints"]))
        assertEquals(
            "A fragment request gives no typed: a fragment's values are read back by the library's own SQL.",
            CypherJson.readTree(CompileJson.compile("{\"store\": \"oracle\", \"graph\": {\"name\": \"g\"}, \"typed\": true, \"fragment\": {\"condition\": \"true\"}}"))["error"]?.textValue(),
        )
    }

    /**
     * Oracle's labelled index answers an equality the row must pass to be kept, as a pattern's properties do, and no other: its
     * test is null, not false, for a value it doesn't hold, a stored list or a long text, so under a `NOT`, beside an `OR` or
     * in a value it would drop rows Cypher keeps. `NOT (n.name = 'Ada')` keeps a node whose name is a list in Cypher.
     */
    @Test
    fun theLabelledIndexAnswersOnlyAnEqualityTheRowMustPass() {
        val labelled = mapOf("n" to FragmentVariable.Node("people_", listOf("Person")))
        val plain = mapOf("n" to FragmentVariable.Node("people_"))
        // A compiler each, as the names a compiler gives its shared values count on across what it compiles.
        fun oracle() = OracleCypherCompiler("g", indexedProperties = setOf("Person" to "name"))
        fun uses(term: String) = term.contains("JSON_EXISTS(people_.labels")
        assertTrue(uses(oracle().condition("n.name = 'Ada'", labelled).sql), "a conjunct of its own")
        assertTrue(uses(oracle().condition("n.name = 'Ada' AND n.age > 1", labelled).sql), "a conjunct among others")
        for (unkept in listOf("NOT (n.name = 'Ada')", "n.name = 'Ada' OR n.age > 1", "n.name <> 'Ada'", "(n.name = 'Ada') = false")) {
            assertEquals(oracle().condition(unkept, plain).sql, oracle().condition(unkept, labelled).sql, unkept)
        }
        assertEquals(oracle().value("n.name = 'Ada'", plain).sql, oracle().value("n.name = 'Ada'", labelled).sql, "a value")
    }

    /**
     * `fragments.yaml`'s table: each form a fragment takes, each condition in each of its positions and each value as an order
     * both ways, compiled on each store to the terms whose digest the table pins, on every target. Each library's runner holds
     * each to Neo4j's rows on its store.
     */
    @Test
    fun eachFormOfTheTableIsCompiledToTheTermsItsDigestPins() {
        val table = spec["table"]!!
        val positions = (table["positions"] as ArrayNode).map { it.textValue()!! }
        var checked = 0
        for (store in STORES) {
            for (entry in table["conditions"]!!) {
                val form = entry["form"]!!.textValue()!!
                positions.forEachIndexed { i, position ->
                    val case = tableCase("condition", position.replace("{f}", form), entry["parameters"])
                    assertEquals(entry["digests"]?.get(store)?.get(i)?.textValue(), digest(case, store), "on $target, $store, $form in ${position}")
                    checked++
                }
            }
            for (entry in table["orders"]!!) {
                val value = entry["value"]!!.textValue()!!
                listOf(false, true).forEachIndexed { i, descending ->
                    assertEquals(entry["digests"]?.get(store)?.get(i)?.textValue(), digest(tableCase("order", value, null, descending), store), "on $target, $store, order by $value")
                    checked++
                }
            }
        }
        assertEquals(STORES.size * (table["conditions"]!!.size() * positions.size + table["orders"]!!.size() * 2), checked)
    }

    /** A table entry as a case of the file's: the [part] and its [cypher], its parameters, descending where an order is. */
    fun tableCase(part: String, cypher: String, parameters: JsonNode?, descending: Boolean = false): JsonNode = CypherJson.readTree(
        "{\"$part\": ${CypherJson.writeValueAsString(CypherJson.valueToTree(cypher))}" +
            (if (part == "order") ", \"descending\": $descending" else "") +
            (parameters?.let { ", \"parameters\": ${CypherJson.writeValueAsString(it)}" } ?: "") + "}",
    )

    /** The first 16 hexadecimal digits of the SHA-256 of [case]'s terms on [store], joined by a line feed. */
    fun digest(case: JsonNode, store: String): String {
        val got = CypherJson.readTree(CompileJson.compile(request(case, store)))
        val terms = (got["terms"] as? ArrayNode)?.map { it.textValue()!! } ?: throw AssertionError("on $target, $store: $got")
        return org.drivine.cypher.json.sha256(terms.joinToString("\n").encodeToByteArray()).joinToString("") { (it.toInt() and 0xff).toString(16).padStart(2, '0') }.take(16)
    }

    private companion object {
        val STORES = listOf("postgres", "oracle", "sqlite")
    }
}
