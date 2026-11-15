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
 * `nesting.yaml`'s table (liberation-data/drivine4j#681, #680): a reference to a variable a `WITH` drops, or a control,
 * in each outer context, under each nesting of subqueries, comprehensions and `CALL`s up to two deep, is one cell, whose
 * statement the lists give, and each SQL store's compiler, asked through the JSON entry as TypeScript and Python ask it,
 * gives Neo4j's verdict on it, on every target: the message the cell pins where it is refused, and SQL where Neo4j gives
 * rows. A cell missing from the table, one more, or one out of order fails, so no cell goes unjudged; the depth-3 canary,
 * a sample of three-deep nestings, is held to Neo4j's verdicts alike.
 */
class NestingTableTest {

    private val spec: JsonNode = CypherJson.readTree(oracleFile("nesting.json"))

    private fun text(node: JsonNode, key: String) = node[key]?.textValue() ?: fail("nesting.yaml: no $key in $node")

    private fun named(list: String, name: String) = spec[list]!!.firstOrNull { text(it, "name") == name } ?: fail("nesting.yaml: no $name in $list")

    private fun sub(template: String, v: String, d: Int, col: String) = template.replace("{v}", v).replace("{d}", "$d").replace("{col}", col)

    /** The text of a hole of [kind], held by the nesting at depth [d], whose anchor is [v] and which returns [col] where it is a query. */
    private fun fill(kind: String, v: String, col: String, d: Int, chain: List<String>, reference: String): String {
        val hole = spec["holes"]!![kind] ?: fail("nesting.yaml: no hole $kind")
        if (chain.isEmpty()) {
            val ref = named("references", reference)
            return sub(text(hole, "reference"), v, d, col).replace("{pattern}", text(ref, "pattern").replace("{v}", v)).replace("{expr}", text(ref, "expr").replace("{v}", v))
        }
        val nesting = named("nesting", chain.first())
        val adaptor = hole[text(nesting, "kind")] ?: fail("nesting.yaml: ${chain.first()} is in no $kind hole")
        val anchor = sub(adaptor["anchor"]?.textValue() ?: "{v}", v, d, col)
        return sub(text(adaptor, "template"), v, d, col).replace("{inner}", nested(nesting, anchor, col, d + 1, chain.drop(1), reference))
    }

    private fun nested(nesting: JsonNode, v: String, col: String, d: Int, rest: List<String>, reference: String): String {
        val anchor = sub(nesting["anchor"]?.textValue() ?: "{v}", v, d, col)
        val column = sub(nesting["column"]?.textValue() ?: "x{d}", v, d, col)
        return sub(text(nesting, "cypher"), v, d, col).replace("{hole}", fill(text(nesting, "hole"), anchor, column, d, rest, reference))
    }

    private fun statement(context: String, chain: List<String>, reference: String): String {
        val outer = named("contexts", context)
        return text(outer, "cypher").replace("{hole}", fill("expr", text(outer, "anchor"), "", 0, chain, reference))
    }

    /** Each nesting [depth] deep that is Cypher: each in a hole of its kind, in the lists' order. */
    private fun chains(depth: Int): List<List<String>> {
        if (depth == 0) return listOf(emptyList())
        return chains(depth - 1).flatMap { chain ->
            val kind = chain.lastOrNull()?.let { text(named("nesting", it), "hole") } ?: "expr"
            spec["nesting"]!!.filter { spec["holes"]!![kind]!!.has(text(it, "kind")) }.map { chain + text(it, "name") }
        }
    }

    private data class Cell(val context: String, val nesting: List<String>, val reference: String, val cypher: String)

    private fun cell(node: JsonNode) = Cell(text(node, "context"), node["nesting"]!!.map { it.textValue()!! }, text(node, "reference"), text(node, "cypher"))

    @Test
    fun theTableHoldsOneCellForEachContextNestingAndReferenceInOrder() {
        val expected = spec["contexts"]!!.flatMap { context ->
            (0..2).flatMap { depth ->
                chains(depth).flatMap { chain ->
                    spec["references"]!!.map { ref -> Cell(text(context, "name"), chain, text(ref, "name"), statement(text(context, "name"), chain, text(ref, "name"))) }
                }
            }
        }
        assertEquals(expected, spec["cells"]!!.map(::cell))
    }

    @Test
    fun eachCanaryIsThreeDeepAndItsStatementIsTheListsOwn() {
        val three = chains(3).toSet()
        val canary = spec["canary"]!!["cells"]!!.map(::cell)
        assertEquals(spec["canary"]!!["count"]!!.longValue().toInt(), canary.size)
        assertEquals(canary.size, canary.map { it.cypher }.toSet().size, "a canary's statement is no other's")
        canary.forEach {
            if (it.nesting !in three) fail("the canary's ${it.nesting} is no three-deep nesting of the lists")
            assertEquals(statement(it.context, it.nesting, it.reference), it.cypher)
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
            if (!answered && pinned == null) fail("nesting.yaml: ${text(cell, "cypher")} is refused by Neo4j, and pins no refusal")
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
