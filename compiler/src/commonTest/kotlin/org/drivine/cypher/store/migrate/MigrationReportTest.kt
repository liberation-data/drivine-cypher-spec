package org.drivine.cypher.store.migrate

import org.drivine.cypher.api.Store
import org.drivine.cypher.json.CypherJson
import org.drivine.cypher.json.ObjectNode
import org.drivine.cypher.store.migrate.LayoutMigration.Declaration
import org.drivine.cypher.store.migrate.LayoutMigration.Kind
import org.drivine.cypher.store.migrate.MigrationStatements.Table
import org.drivine.cypher.store.plan.PlanJson
import kotlin.test.Test
import kotlin.test.assertEquals

/** What a scan's report names to declare ([MigrationReport.candidates]), and the report as JSON (liberation-data/drivine-cypher-spec#256). */
class MigrationReportTest {

    /** The candidates of a scan of [nodes], each a row's labels and props, on [store] under [declarations]. */
    private fun candidates(nodes: List<Pair<List<String>, String>>, declarations: List<Declaration> = emptyList(), store: Store = Store.POSTGRES): List<MigrationReport.Candidate> =
        scan(nodes, declarations, store).withCandidates(declarations).candidates

    private fun scan(nodes: List<Pair<List<String>, String>>, declarations: List<Declaration>, store: Store): MigrationReport =
        nodes.foldIndexed(MigrationReport()) { i, report, (labels, props) ->
            report.scanned(Table.NODES, Row(i + 1L, labels, props), LayoutMigration.rewrite(props, store, labels, declarations),
                LayoutMigration.observe(props, store), declarations)
        }

    @Test
    fun aPropertyWhoseValuesAreAllTextsOfOneTemporalKindOrListsOfThemIsACandidate() {
        assertEquals(listOf(MigrationReport.Candidate("Event", "on", "date", 3)), candidates(listOf(
            listOf("Event") to """{"on": "2024-03-15"}""",
            listOf("Event") to """{"on": ["2024-03-16", "2024-03-17"]}""",
            listOf("Event") to """{"on": "2024-03-18"}""",
        )))
        assertEquals(listOf(MigrationReport.Candidate("Event", "at", "datetime", 1), MigrationReport.Candidate("Event", "for", "duration", 1)),
            candidates(listOf(listOf("Event") to """{"for": "P1DT2H", "at": "2024-03-15T10:30:00+02:00"}""")))
    }

    @Test
    fun aPropertyOfTwoClassesIsNoCandidateBarWholeNumbersAndFloats() {
        // A date beside a text, a date beside a datetime, or beside a list holding both: no one kind changes them all.
        assertEquals(emptyList(), candidates(listOf(
            listOf("Event") to """{"on": "2024-03-15", "at": "2024-03-15", "of": ["2024-03-15"]}""",
            listOf("Event") to """{"on": "soon", "at": "2024-03-15T10:30:00Z", "of": ["2024-03-15", "2024-03-15T10:30:00Z"]}""",
        )))
        // Whole numbers and floats both: a float; floats alone, or whole numbers alone, need nothing declared.
        assertEquals(listOf(MigrationReport.Candidate("Reading", "value", "float", 2)), candidates(listOf(
            listOf("Reading") to """{"value": 1, "count": 1, "ratio": 0.5}""",
            listOf("Reading") to """{"value": 1.5, "count": 2, "ratio": 0.25}""",
        )))
        // On Oracle, a whole NUMBER read as a double is a whole number, and an extended double a float.
        assertEquals(listOf(MigrationReport.Candidate("Reading", "value", "float", 2)), candidates(listOf(
            listOf("Reading") to """{"value": 1.0}""",
            listOf("Reading") to """{"value": {"${'$'}numberDouble": "1.5"}}""",
        ), store = Store.ORACLE))
    }

    @Test
    fun drivinesOwnNodesAndDeclaredPropertiesAreNoCandidates() {
        val declarations = listOf(Declaration("Person", "born", Kind.DATE))
        assertEquals(emptyList(), candidates(listOf(
            listOf("DrivineStore") to """{"assignedAt": "2026-11-17T00:00:00Z"}""",
            listOf("_DrivineSchema") to """{"at": "2026-11-17T00:00:00Z"}""",
            listOf("Person") to """{"born": "2024-03-15"}""",
        ), declarations))
        // A node of two labels whose property one of them declares is rewritten by that declaration: no candidate under the other.
        assertEquals(emptyList(), candidates(listOf(listOf("Person", "Employee") to """{"born": "2024-03-15"}"""), declarations))
        // Where a node of the other label alone holds it, that node's values are the other label's candidate.
        assertEquals(listOf(MigrationReport.Candidate("Employee", "born", "date", 1)), candidates(listOf(
            listOf("Person", "Employee") to """{"born": "2024-03-15"}""",
            listOf("Employee") to """{"born": "2024-03-16"}""",
        ), declarations))
    }

    @Test
    fun aReportReadsBackAsItWasWrittenThroughJson() {
        val declarations = listOf(Declaration("Person", "born", Kind.DATE))
        val report = scan(listOf(
            listOf("Person", "Robot") to """{"born": "soon", "${'$'}x": {"${'$'}y": 1}, "met": "2024-03-15"}""",
            listOf("Person") to """{"born": "2024-03-15"}""",
        ), declarations, Store.POSTGRES).withCandidates(declarations)
            .copy(kept = listOf(Kept("nodes", 1, listOf("Person", "Robot"), "born", "date", "\"soon\"", "'soon' is no date.")))
            .ended(Status.REWRITTEN, "done")
        assertEquals(1, report.invalidCount)
        val text = PlanJson.writePlain(report.toMap())
        assertEquals(report, MigrationReport.of(CypherJson.readTree(text) as ObjectNode), text)
        assertEquals(MigrationReport(), MigrationReport.of(CypherJson.readTree(PlanJson.writePlain(MigrationReport().toMap())) as ObjectNode))
    }
}
