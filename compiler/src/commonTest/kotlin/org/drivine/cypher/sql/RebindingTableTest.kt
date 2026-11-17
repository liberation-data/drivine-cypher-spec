package org.drivine.cypher.sql

import org.drivine.cypher.api.CompileJson
import org.drivine.cypher.json.CypherJson
import org.drivine.cypher.json.JsonNode
import org.drivine.cypher.number.oracleFile
import org.drivine.cypher.number.target
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.fail

/**
 * `rebinding.yaml`'s table (liberation-data/drivine4j#819): a name a subquery has from outside it, bound again within it
 * by each binder, in each context, and the two controls, a name not bound outside and one only read, is one cell, whose
 * statement the lists give, and each SQL store's compiler, asked through the JSON entry as TypeScript and Python ask it,
 * gives Neo4j's verdict on it, on every target: the message the cell pins where it is refused, and SQL where Neo4j gives
 * rows. A cell missing from the table, one more, or one out of order fails, so no cell goes unjudged; the depth-2 canary,
 * a sample of two contexts one within the other, is held to Neo4j's verdicts alike.
 */
class RebindingTableTest {

    private val spec: JsonNode = CypherJson.readTree(oracleFile("rebinding.json"))

    private fun text(node: JsonNode, key: String) = node[key]?.textValue() ?: fail("rebinding.yaml: no $key in $node")

    private fun named(list: String, name: String) = spec[list]!!.firstOrNull { text(it, "name") == name } ?: fail("rebinding.yaml: no $name in $list")

    /** The statement of [binder] as [variant] has it, within each of [contexts], the first outermost. */
    private fun statement(contexts: List<String>, binder: String, variant: String): String {
        val form = named("variants", variant)
        val outer = text(form, "outer")
        var body = text(named("binders", binder), text(form, "body")).replace("{v}", text(spec, "name"))
        contexts.drop(1).reversed().forEach { body = text(named("contexts", it), "inner").replace("{o}", outer).replace("{body}", body) }
        return text(named("contexts", contexts.first()), "outer").replace("{o}", outer).replace("{body}", body)
    }

    private data class Cell(val contexts: List<String>, val binder: String, val variant: String, val cypher: String)

    private fun cell(node: JsonNode) = Cell(node["contexts"]!!.map { it.textValue()!! }, text(node, "binder"), text(node, "variant"), text(node, "cypher"))

    private fun names(list: String) = spec[list]!!.map { text(it, "name") }

    @Test
    fun theTableHoldsOneCellForEachContextBinderAndVariantInOrder() {
        val expected = names("contexts").flatMap { context ->
            names("binders").flatMap { binder -> names("variants").map { variant -> Cell(listOf(context), binder, variant, statement(listOf(context), binder, variant)) } }
        }
        assertEquals(expected, spec["cells"]!!.map(::cell))
    }

    @Test
    fun eachCanaryIsTwoContextsDeepAndItsStatementIsTheListsOwn() {
        val table = spec["cells"]!!.map { text(it, "cypher") }.toSet()
        val canary = spec["canary"]!!["cells"]!!.map(::cell)
        assertEquals(spec["canary"]!!["count"]!!.longValue().toInt(), canary.size)
        assertEquals(canary.size, canary.map { it.cypher }.toSet().size, "a canary's statement is no other's")
        canary.forEach {
            assertEquals(2, it.contexts.size, "the canary's ${it.contexts} is two contexts deep")
            if (it.cypher in table) fail("the canary's ${it.cypher} is a cell of the table")
            assertEquals(statement(it.contexts, it.binder, it.variant), it.cypher)
        }
    }

    @Test
    fun eachCellIsRefusedOrCompiledAsNeo4jHasIt() = verdicts(spec["cells"]!!)

    @Test
    fun eachCanaryIsRefusedOrCompiledAsNeo4jHasIt() = verdicts(spec["canary"]!!["cells"]!!)

    private fun verdicts(cells: JsonNode) {
        val wrong = mutableListOf<String>()
        var checked = 0
        for (cell in cells) {
            val answered = text(cell, "neo4j") == "rows"
            val pinned = cell["error"]?.textValue()
            if (!answered && pinned == null) fail("rebinding.yaml: ${text(cell, "cypher")} is refused by Neo4j, and pins no refusal")
            for (store in STORES) {
                val got = CypherJson.readTree(CompileJson.compile(request(text(cell, "cypher"), store)))
                val refused = got["refusal"]?.textValue()
                if (refused != pinned || (pinned == null && got["sql"] == null)) {
                    wrong += "$store, ${text(cell, "cypher")}: ${refused ?: got["sql"]?.let { "compiled" } ?: "$got"}, not ${pinned ?: "compiled"}"
                }
                checked++
            }
        }
        if (wrong.isNotEmpty()) fail("on $target, ${wrong.size} cells differ from the table:\n" + wrong.joinToString("\n"))
        assertEquals(cells.size() * STORES.size, checked)
    }

    /** The request for [cypher] on [store], as a library writes it. */
    private fun request(cypher: String, store: String) = "{\"store\": \"$store\", \"graph\": {\"name\": \"g\"}, \"cypher\": ${CypherJson.writeValueAsString(cypher)}}"

    private companion object {
        val STORES = listOf("postgres", "oracle", "sqlite")
    }
}
