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
 * `writevalues.yaml`'s table (liberation-data/drivine4j#814, #817): each position a `CREATE` or a `MERGE` takes a value
 * in, by each form of value, is one cell, whose statement the position and the form give, and each SQL store's compiler,
 * asked through the JSON entry as TypeScript and Python ask it, gives Neo4j's verdict on it, on every target: the message
 * the cell pins where Neo4j refuses it, and SQL where it stores it, or the refusal of what is not compiled yet where the cell
 * says so. A cell missing from the table, one more, or one out of order fails, so no cell goes unjudged.
 */
class WriteValuesTableTest {

    private val spec: JsonNode = CypherJson.readTree(oracleFile("writevalues.json"))

    private fun text(node: JsonNode, key: String) = node[key]?.textValue() ?: fail("writevalues.yaml: no $key in $node")

    /** The request for [cypher] on [store], as a library writes it. */
    private fun request(cypher: JsonNode, store: String) = "{\"store\": \"$store\", \"graph\": {\"name\": \"g\"}, \"cypher\": ${CypherJson.writeValueAsString(cypher)}}"

    @Test
    fun theTableHoldsOneCellForEachPositionAndFormInOrder() {
        val expected = spec["positions"]!!.flatMap { position ->
            spec["forms"]!!.map { form ->
                Triple(text(position, "name"), text(form, "name"), text(position, "cypher").replace("{value}", text(form, "value").replace("{made}", text(position, "made"))))
            }
        }
        val cells = spec["cells"]!!.map { Triple(text(it, "position"), text(it, "form"), text(it, "cypher")) }
        assertEquals(expected, cells)
    }

    /** Each store's answer to [cypher]: its refusal, or null where it compiles it. */
    private fun verdict(cypher: JsonNode, store: String): String? {
        val got = CypherJson.readTree(CompileJson.compile(request(cypher, store)))
        return got["refusal"]?.textValue() ?: if (got["sql"] != null) null else fail("on $target, $store gives neither SQL nor a refusal for $cypher: $got")
    }

    /**
     * Each cell's verdict on each store is the cell's own, and that is Neo4j's: the message the cell pins where Neo4j refuses
     * it, SQL where it stores it, or, where it stores it and the cell says `ours: "not compiled yet"`, that refusal. Never
     * compiled where Neo4j refuses it, so never rows Neo4j does not give.
     */
    @Test
    fun eachCellIsRefusedWhereNeo4jRefusesItAndCompiledOrNotCompiledYetWhereItStoresIt() {
        val wrong = mutableListOf<String>()
        var checked = 0
        for (cell in spec["cells"]!!) {
            val stored = text(cell, "neo4j") == "stored"
            val notYet = cell["ours"]?.textValue() == "not compiled yet"
            if (notYet && (!stored || text(cell, "error") !in NOT_YET)) wrong += "${text(cell, "position")}, ${text(cell, "form")}: not compiled yet where Neo4j gives ${text(cell, "neo4j")}, or with another message"
            val pinned = if (stored && !notYet) null else text(cell, "error")
            for (store in STORES) {
                val refused = verdict(cell["cypher"]!!, store)
                if (refused != pinned) wrong += "$store, ${text(cell, "position")}, ${text(cell, "form")}: ${refused ?: "compiled"}, not ${pinned ?: "compiled"}"
                checked++
            }
        }
        if (wrong.isNotEmpty()) fail("on $target, ${wrong.size} cells differ from the table:\n" + wrong.joinToString("\n"))
        assertEquals(spec["cells"]!!.size() * STORES.size, checked)
    }

    /**
     * The depth-3 canaries: each is refused by each store's compiler with the message it pins, or compiled where Neo4j
     * stores it (its rows are held to Neo4j's by each library's runner of writes.yaml), Oracle refusing it where it pins
     * an `oracle` refusal. None is compiled where Neo4j refuses it.
     */
    @Test
    fun eachCanaryIsRefusedOrCompiledWhereNeo4jStoresIt() {
        val canaries = spec["canaries"]!!
        val entries = canaries["entries"]!!
        assertEquals(40, entries.size())
        val wrong = mutableListOf<String>()
        for (entry in entries) {
            val stored = text(entry, "neo4j") == "stored"
            val error = entry["error"]?.textValue()
            if (error == null && !stored) wrong += "${text(entry, "cypher")}: compiled where Neo4j gives ${text(entry, "neo4j")}"
            for (store in STORES) {
                val pinned = error ?: if (store == "oracle") entry["oracle"]?.textValue() else null
                val refused = verdict(entry["cypher"]!!, store)
                if (refused != pinned) wrong += "$store, ${text(entry, "nesting")}: ${refused ?: "compiled"}, not ${pinned ?: "compiled"}"
            }
        }
        if (wrong.isNotEmpty()) fail("on $target, ${wrong.size} canaries differ:\n" + wrong.joinToString("\n"))
    }

    private companion object {
        val STORES = listOf("postgres", "oracle", "sqlite")
        /** What is not compiled yet: a variable the clause makes, read within (checked first), and any read of the graph where Neo4j reads it at another moment. */
        val NOT_YET = setOf(
            "A variable a CREATE or a MERGE makes, read inside a pattern or a comprehension in its own properties, is Cypher, and is not compiled to SQL yet.",
            "A pattern or a subquery in the properties of a CREATE, or of a MERGE's relationship, is Cypher, and is not compiled to SQL yet. Work it out in a WITH before the clause.",
        )
    }
}
