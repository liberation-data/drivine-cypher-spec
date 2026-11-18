package org.drivine.cypher.spec

import com.fasterxml.jackson.databind.DeserializationFeature
import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.dataformat.yaml.YAMLFactory
import com.fasterxml.jackson.module.kotlin.readValue
import com.fasterxml.jackson.module.kotlin.registerKotlinModule
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * `nesting.yaml`'s cells as the libraries run them (liberation-data/drivine4j#681): each cell the SQL stores refuse is a
 * step of `refusals.yaml` with the message the cell pins, and each Neo4j gives rows for a step of `queries.yaml` with its
 * rows, run on the SQL stores too where they compile it, over that file's graph, which the table's statements were run
 * over. A cell whose Neo4j answer differs by environment says `neo4j: "not pinned"` (liberation-data/drivine-cypher-spec#366):
 * the SQL stores' refusal of it stays a step of `refusals.yaml`, and it is no step of `queries.yaml`. `NestingTableTest`
 * holds the compiler to the table on every target.
 */
class NestingSpecTest {

    private data class Cell(
        val context: String, val nesting: List<String>, val reference: String, val cypher: String, val neo4j: String,
        val rows: List<Any?>? = null, val error: String? = null, val note: String? = null,
    )

    private data class Canary(val seed: Long, val count: Int, val references: List<String>, val cells: List<Cell>)

    private data class Table(val contexts: List<Map<String, String>>, val nesting: List<Map<String, String>>, val holes: Map<String, Any?>, val references: List<Map<String, String>>, val cells: List<Cell>, val canary: Canary)

    private val table: Table = ObjectMapper(YAMLFactory()).registerKotlinModule().enable(DeserializationFeature.USE_LONG_FOR_INTS)
        .readValue(requireNotNull(NestingSpecTest::class.java.getResource("/cypher/nesting.yaml")))

    private val cells = table.cells + table.canary.cells

    @Test
    fun `each cell the SQL stores refuse is a refusal of refusals yaml with its message`() {
        val steps = CypherSpec.load("refusals.yaml").cases.flatMap { it.steps }
        cells.filter { it.error != null }.forEach { cell ->
            assertEquals(listOf(cell.error), steps.filter { it.cypher == cell.cypher }.map { it.error }.distinct(), cell.cypher)
        }
    }

    @Test
    fun `each cell Neo4j gives rows for is a step of queries yaml with its rows`() {
        val steps = CypherSpec.load("queries.yaml").cases.flatMap { it.steps }
        cells.filter { it.neo4j == "rows" }.forEach { cell ->
            // One of the table's own, or one written before it.
            val found = steps.filter { it.cypher == cell.cypher }
            assertTrue(found.isNotEmpty(), "${cell.cypher} is no step of queries.yaml")
            found.forEach {
                assertEquals(cell.rows, it.rows, cell.cypher)
                // On the SQL stores too, but where they refuse it.
                assertEquals(if (cell.error == null) listOf("neo4j", "sql") else listOf("neo4j"), it.only, cell.cypher)
            }
        }
    }

    @Test
    fun `each cell whose Neo4j answer is not pinned is refused on the SQL stores, says why, and is no step of queries yaml`() {
        val steps = CypherSpec.load("queries.yaml").cases.flatMap { it.steps }.map { it.cypher }.toSet()
        cells.filter { it.neo4j == "not pinned" }.forEach { cell ->
            assertTrue(cell.error != null && cell.note != null && cell.rows == null, "${cell.cypher} pins no refusal, no note, or rows")
            assertTrue(cell.cypher !in steps, "${cell.cypher} is a step of queries.yaml")
        }
    }

    @Test
    fun `each cell Neo4j refuses is refused on the SQL stores`() {
        assertEquals(emptyList(), cells.filter { it.neo4j != "rows" && it.error == null }.map { it.cypher })
    }
}
