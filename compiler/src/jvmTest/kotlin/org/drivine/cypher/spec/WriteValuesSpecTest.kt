package org.drivine.cypher.spec

import com.fasterxml.jackson.databind.DeserializationFeature
import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.dataformat.yaml.YAMLFactory
import com.fasterxml.jackson.module.kotlin.readValue
import com.fasterxml.jackson.module.kotlin.registerKotlinModule
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals

/**
 * `writevalues.yaml`'s cells as the libraries run them (liberation-data/drivine4j#814, #817): each cell Neo4j refuses is a
 * step of `refusals.yaml` with the message the cell pins, and each it stores a step of `writes.yaml` with its rows, after
 * the table's graph alone. `WriteValuesTableTest` holds the compiler to the table on every target.
 */
class WriteValuesSpecTest {

    private data class Cell(val position: String, val form: String, val cypher: String, val neo4j: String, val ours: String? = null, val error: String? = null, val rows: List<Any?>? = null)

    private data class Canary(val site: String, val nesting: String, val cypher: String, val neo4j: String, val rows: List<Any?>? = null, val error: String? = null, val oracle: String? = null)

    private data class Canaries(val seed: Long, val constructs: List<String>, val entries: List<Canary>)

    private data class Table(val graph: String, val positions: List<Map<String, String>>, val forms: List<Map<String, String>>, val cells: List<Cell>, val canaries: Canaries)

    private val table: Table = ObjectMapper(YAMLFactory()).registerKotlinModule().enable(DeserializationFeature.USE_LONG_FOR_INTS)
        .readValue(requireNotNull(WriteValuesSpecTest::class.java.getResource("/cypher/writevalues.yaml")))

    @Test
    fun `each cell Neo4j refuses is a refusal of refusals yaml with its message`() {
        val steps = CypherSpec.load("refusals.yaml").cases.flatMap { it.steps }
        val refused = table.cells.filter { it.neo4j != "stored" || it.ours != null }
        refused.forEach { cell ->
            assertEquals(listOf(cell.error), steps.filter { it.cypher == cell.cypher }.map { it.error }.distinct(), "${cell.position}: ${cell.form}")
        }
    }

    @Test
    fun `each cell Neo4j stores is a step of writes yaml with its rows after the table's graph`() {
        val cases = CypherSpec.load("writes.yaml").cases
        val stored = table.cells.filter { it.neo4j == "stored" && it.ours == null }
        stored.forEach { cell -> written(cases, cell.cypher, cell.rows, "${cell.position}: ${cell.form}") }
        table.cells.filter { it.ours != null }.forEach { cell ->
            assertEquals(emptyList(), cases.flatMap { it.steps }.filter { it.cypher == cell.cypher }, "${cell.position}: ${cell.form} is not compiled, so no step of writes.yaml")
        }
    }

    /** [cypher] is one step of writes.yaml, with [rows], after the table's graph alone. */
    private fun written(cases: List<CypherSpec.Case>, cypher: String, rows: List<Any?>?, what: String) {
        val found = cases.flatMap { case -> case.steps.withIndex().filter { it.value.cypher == cypher }.map { case to it.index } }
        assertEquals(1, found.size, "$what is ${found.size} steps of writes.yaml")
        val (case, i) = found.single()
        assertEquals(rows, case.steps[i].rows, what)
        assertEquals(table.graph, case.steps[i - 1].cypher, "$what follows the table's graph")
        if (i > 1) assertEquals("MATCH (x) DETACH DELETE x", case.steps[i - 2].cypher, "$what follows an empty graph")
    }

    @Test
    fun `each canary refused is a refusal of refusals yaml and each compiled a step of writes yaml with Neo4j's rows`() {
        val refusals = CypherSpec.load("refusals.yaml").cases.flatMap { it.steps }
        val cases = CypherSpec.load("writes.yaml").cases
        table.canaries.entries.forEach { canary ->
            val pinned = refusals.filter { it.cypher == canary.cypher }
            if (canary.error != null) {
                assertEquals(listOf(canary.error), pinned.map { it.error }, canary.nesting)
            } else {
                assertEquals("stored", canary.neo4j, canary.nesting)
                written(cases, canary.cypher, canary.rows, "${canary.site}: ${canary.nesting}")
                assertEquals(listOfNotNull(canary.oracle), pinned.map { it.error }, canary.nesting)
            }
        }
    }
}
