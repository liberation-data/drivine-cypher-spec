package org.drivine.cypher.api

import org.drivine.cypher.json.CypherJson
import org.drivine.cypher.json.JsonNode
import org.drivine.cypher.json.ObjectNode
import org.drivine.cypher.store.migrate.MigrationReport
import org.drivine.cypher.store.migrate.Status
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * A migration's requests through the JSON entry ([MigrateJson]) that `LayoutMigratorTest`'s run through it doesn't ask: a
 * request refused, and what a host gives back read as a host with no Kotlin types gives it (liberation-data/drivine-cypher-spec#256).
 */
class MigrateJsonTest {

    private fun ask(rest: String, store: String = "postgres"): JsonNode =
        CypherJson.readTree(CompileJson.compile("{\"migrate\": {\"store\": \"$store\", \"graph\": \"g\"${if (rest.isEmpty()) "" else ", $rest"}}}"))

    private fun error(rest: String, store: String = "postgres"): String =
        ask(rest, store)["error"]?.textValue() ?: throw AssertionError("not refused: $rest")

    @Test
    fun aRequestThatCantBeRunIsRefusedWithWhy() {
        for ((rest, message) in listOf(
            "" to "one of start and resume",
            "\"start\": {}, \"resume\": {}, \"outcomes\": []" to "one of start and resume",
            // A key mistyped would have a dry run write the graph.
            "\"start\": {\"dry_run\": true}" to "A start gives no dry_run",
            "\"start\": {\"declarations\": {\"label\": \"Person\", \"property\": \"born\", \"kind\": \"date\"}}" to "declarations as a list",
            "\"start\": {\"declarations\": [{\"label\": \"Person\", \"property\": \"born\", \"kind\": \"day\"}]}" to "No kind day",
            "\"start\": {\"declarations\": [{\"label\": \"Person\", \"kind\": \"date\"}]}" to "its property as a text",
            "\"start\": {\"declarations\": [{\"label\": \"DrivineStore\", \"property\": \"at\", \"kind\": \"date\"}]}" to "Drivine's own label",
            "\"start\": {\"batchSize\": 0}" to "batches of 1 or more",
            "\"start\": {\"batchSize\": 1.5}" to "batchSize as a whole number",
            "\"start\": {\"dryRun\": \"yes\"}" to "dryRun as true or false",
            "\"resume\": {\"phase\": \"judge\"}, \"outcomes\": []" to "A resume gives back the state",
        )) {
            val given = error(rest)
            assertTrue(given.contains(message), "$rest: $given")
        }
        assertTrue(error("\"start\": {}", store = "neo4j").contains("names a SQL store"))
        val state = ask("\"start\": {}")["state"]!!
        val json = CypherJson.writeValueAsString(state)
        assertTrue(error("\"resume\": $json").contains("outcomes as a list"))
        assertTrue(error("\"resume\": $json, \"outcomes\": [{\"type\": \"done\"}]").contains("No outcome is done"))
    }

    @Test
    fun aFailedActionEndsTheMigrationWithTheStoresMessage() {
        val state = CypherJson.writeValueAsString(ask("\"start\": {}")["state"]!!)
        val done = ask("\"resume\": $state, \"outcomes\": [{\"type\": \"rows\", \"rows\": []}, {\"type\": \"failed\", \"message\": \"relation does not exist\"}]")["done"]!!
        val report = MigrationReport.of(done as ObjectNode)
        assertEquals(Status.FAILED, report.status)
        assertEquals("relation does not exist", report.message)
    }

    @Test
    fun aGraphOfThisLayoutIsCurrentWithItsRowsAsAHostWithNoKotlinTypesGivesThem() {
        val state = CypherJson.writeValueAsString(ask("\"start\": {\"dryRun\": true}")["state"]!!)
        // Whole numbers as a host whose JSON writes each number as a double, JavaScript's or a JSON column's, may give them.
        val outcomes = """[
            {"type": "rows", "rows": [{"id": "s", "assignedAt": "2026-11-17T00:00:00Z", "layout": 2.0}]},
            {"type": "rows", "rows": [1.0]},
            {"type": "rows", "rows": [{"name": "g_lock2", "type": "TABLE"}]},
            {"type": "rows", "rows": [{"id": 1.0, "props": "{}"}]},
            {"type": "rows", "rows": [{"nodes": 2.0, "lastNode": 2.0, "edges": 0, "lastEdge": null}]}
        ]"""
        val done = ask("\"resume\": $state, \"outcomes\": $outcomes")["done"]
        assertEquals(Status.CURRENT, MigrationReport.of(done as ObjectNode).status, CypherJson.writeValueAsString(done))
    }
}
