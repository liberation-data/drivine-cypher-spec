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
 * the cell pins where Neo4j refuses it, and SQL where it stores it. A cell missing from the table, one more, or one out of order fails, so no cell goes unjudged.
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

    @Test
    fun eachCellIsRefusedWhereNeo4jRefusesItAndCompiledWhereItStoresIt() {
        val wrong = mutableListOf<String>()
        var checked = 0
        for (cell in spec["cells"]!!) {
            val stored = text(cell, "neo4j") == "stored"
            for (store in STORES) {
                val got = CypherJson.readTree(CompileJson.compile(request(cell["cypher"]!!, store)))
                val refused = got["refusal"]?.textValue()
                val pinned = if (stored) null else text(cell, "error")
                if (refused != pinned || (stored && got["sql"] == null)) {
                    wrong += "$store, ${text(cell, "position")}, ${text(cell, "form")}: ${refused ?: got["sql"]?.let { "compiled" } ?: "$got"}, not ${pinned ?: "compiled"}"
                }
                checked++
            }
        }
        if (wrong.isNotEmpty()) fail("on $target, ${wrong.size} cells differ from the table:\n" + wrong.joinToString("\n"))
        assertEquals(spec["cells"]!!.size() * STORES.size, checked)
    }

    private companion object {
        val STORES = listOf("postgres", "oracle", "sqlite")
    }
}
