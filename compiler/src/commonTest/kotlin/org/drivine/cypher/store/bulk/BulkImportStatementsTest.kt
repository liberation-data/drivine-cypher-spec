package org.drivine.cypher.store.bulk

import org.drivine.cypher.json.CypherJson
import org.drivine.cypher.json.JsonNode
import org.drivine.cypher.json.ObjectNode
import org.drivine.cypher.number.oracleFile
import org.drivine.cypher.number.target
import org.drivine.cypher.store.bulk.BulkImportStatements.End
import org.drivine.cypher.store.ddl.OracleDdl
import org.drivine.cypher.store.ddl.PostgresDdl
import org.drivine.cypher.store.ddl.PostgresWriteFunctions
import org.drivine.cypher.store.ddl.SqliteDdl
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/**
 * `bulk.yaml`'s statements of a bulk import (liberation-data/drivine4j#684), on each target: each is what
 * [BulkImportStatements] gives the graph `drivine`. The tests also hold that what an import drops it makes again, as the
 * layout made it.
 */
class BulkImportStatementsTest {

    private val spec: JsonNode = CypherJson.readTree(oracleFile("bulk.json"))

    private val postgres = BulkImportStatements.of(PostgresDdl("drivine")) as BulkImportStatements.Postgres

    private val person = End("Person", "id")
    private val repo = End("Repo", "\$slug")

    private fun texts(node: JsonNode) = node.toList().map { it.textValue() }

    @Test
    fun postgresStatementsAreTheOnesBulkYamlPins() {
        val stores = spec["statements"] as ObjectNode
        assertEquals(setOf("postgres"), stores.fieldNames().asSequence().toSet())
        val pinned = stores["postgres"]!!
        val given = mapOf(
            "lock" to postgres.lock,
            "lockTables" to postgres.lockTables,
            "copyNodes" to postgres.copyNodes,
            "takenKeys" to postgres.takenKeys("Person", "id"),
            "makeNodes" to postgres.makeNodes("Person", "id"),
            "clearNodes" to postgres.clearNodes,
            "copyEdges" to postgres.copyEdges,
            "missingEnds" to postgres.missingEnds(person, repo),
            "makeEdges" to postgres.makeEdges(person, repo),
            "relink" to postgres.relink(person, repo),
            "clearEdges" to postgres.clearEdges,
        )
        for ((name, statement) in given) assertEquals(pinned[name]!!.textValue(), statement, "on $target: $name")
        assertEquals(texts(pinned["createStages"]!!), postgres.createStages, "on $target: createStages")
        assertEquals(texts(pinned["defer"]!!), postgres.defer, "on $target: defer")
        assertEquals(texts(pinned["rebuild"]!!), postgres.rebuild(1024), "on $target: rebuild")
        assertEquals(texts(pinned["analyze"]!!), postgres.analyze, "on $target: analyze")
        assertEquals(pinned["ambiguousKeys"]!!["src"]!!.textValue(), postgres.ambiguousKeys(person, "src"), "on $target: ambiguousKeys src")
        assertEquals(pinned["ambiguousKeys"]!!["dst"]!!.textValue(), postgres.ambiguousKeys(repo, "dst"), "on $target: ambiguousKeys dst")
        assertEquals(pinned["findEnds"]!!["src"]!!.textValue(), postgres.findEnds(person, "src"), "on $target: findEnds src")
        assertEquals(pinned["findEnds"]!!["dst"]!!.textValue(), postgres.findEnds(repo, "dst"), "on $target: findEnds dst")
        // Every statement bulk.yaml pins is checked here.
        val checked = given.keys + setOf("createStages", "defer", "rebuild", "analyze", "ambiguousKeys", "findEnds")
        assertEquals(checked, (pinned as ObjectNode).fieldNames().asSequence().toSet(), "on $target: bulk.yaml's statements")
    }

    @Test
    fun whatAnImportDropsItMakesAgainAsTheLayoutMadeIt() {
        val ddl = PostgresDdl("drivine")
        // The indexes made again are the layout's own statements, which the store's DDL runs.
        assertTrue(ddl.ddl.containsAll(ddl.edgeIndexes), "on $target: the layout makes the edge indexes the import makes again")
        val rebuild = postgres.rebuild(256)
        assertTrue(rebuild.containsAll(ddl.edgeIndexes), "on $target: $rebuild")
        // Each foreign key dropped is added again under its name, as the layout's REFERENCES makes it.
        val edgeTable = ddl.ddl.single { it.startsWith("CREATE TABLE IF NOT EXISTS ${ddl.edges} ") }
        for ((column, name) in ddl.edgeForeignKeys) {
            assertTrue(postgres.defer.contains("ALTER TABLE ${ddl.edges} DROP CONSTRAINT $name"), "on $target: $name dropped")
            assertTrue(rebuild.any { it.contains("ADD CONSTRAINT $name FOREIGN KEY ($column) REFERENCES ${ddl.nodes} (nid) ON DELETE CASCADE") }, "on $target: $name made again")
            assertTrue(Regex("""\b$column\s+bigint NOT NULL REFERENCES ${ddl.nodes} \(nid\) ON DELETE CASCADE""").containsMatchIn(edgeTable), "on $target: the layout's $column")
        }
        // Each index dropped is one of the layout's two.
        for (drop in postgres.defer.filter { it.startsWith("DROP INDEX ") }) {
            val name = drop.removePrefix("DROP INDEX ")
            assertTrue(ddl.edgeIndexes.any { it.startsWith("CREATE INDEX IF NOT EXISTS $name ON ") }, "on $target: $drop")
        }
        assertEquals("SET LOCAL maintenance_work_mem = '256MB'", rebuild.first(), "on $target")
        assertEquals(postgres.analyze, rebuild.takeLast(2), "on $target")
        assertFailsWith<IllegalArgumentException> { postgres.rebuild(0) }
    }

    @Test
    fun theForeignKeysNamesAreTheOnesPostgresGivesThemForTheLongestGraphName() {
        // Postgres names an inline REFERENCES `<table>_<column>_fkey`, truncated past 63 characters: a graph's name is short
        // enough that it never is, so the names the import drops are those the store holds.
        val longest = (1..63).map { "g" + "a".repeat(it - 1) }.last { SqlNames.refused(it) == null }
        val ddl = PostgresDdl(longest)
        for (name in ddl.edgeForeignKeys.values) assertTrue(name.length <= 63, "on $target: $name is ${name.length} long")
    }

    @Test
    fun aKeyAndALabelAreWrittenAsTheNodeHoldsThemAndQuoted() {
        val odd = End("It's", "\$code")
        // A key beginning with $ is held with one more; a quote in a label or a key is doubled.
        assertTrue(postgres.findEnds(odd, "src").contains("(n.props -> '\$\$code')"), "on $target")
        assertTrue(postgres.findEnds(odd, "src").contains("n.labels @> '[\"It''s\"]'"), "on $target")
        assertTrue(postgres.missingEnds(odd, odd).contains("a.label = 'It''s'"), "on $target")
        assertTrue(postgres.makeNodes("It's", "\$code").contains("RETURNING nid, (props -> '\$\$code') AS key"), "on $target")
        assertFailsWith<IllegalArgumentException> { postgres.findEnds(person, "both") }
    }

    @Test
    fun anEndIsFoundByItsLabelAndItsPropertyNotByItsLabelAlone() {
        // Nodes of :Person made keyed by `id`, then relationships from :Person found by `email`: a value one node holds under
        // `id` and another under `email` names two nodes, so each is kept, and found, under its own property.
        val byEmail = End("Person", "email")
        assertTrue(postgres.createStages.last().contains("PRIMARY KEY (label, property, key)"), "on $target")
        assertTrue(postgres.makeNodes("Person", "id").contains("SELECT 'Person', 'id', made.key"), "on $target")
        assertTrue(postgres.takenKeys("Person", "id").contains("m.label = 'Person' AND m.property = 'id' AND m.key = s.key"), "on $target")
        val found = postgres.findEnds(byEmail, "src")
        assertTrue(found.contains("SELECT 'Person', 'email', (n.props -> 'email')"), "on $target: $found")
        assertTrue(found.endsWith("ON CONFLICT (label, property, key) DO NOTHING"), "on $target: $found")
        for (statement in listOf(postgres.missingEnds(byEmail, person), postgres.makeEdges(byEmail, person), postgres.relink(byEmail, person))) {
            assertTrue(statement.contains("a.label = 'Person' AND a.property = 'email' AND a.key = s.src"), "on $target: $statement")
            assertTrue(statement.contains("b.label = 'Person' AND b.property = 'id' AND b.key = s.dst"), "on $target: $statement")
        }
        // The property is held escaped, as the node holds its key.
        assertTrue(postgres.makeEdges(person, repo).contains("b.property = '\$\$slug'"), "on $target")
    }

    @Test
    fun aStagedNodeWhosePropsOrLabelsDisagreeWithItsRowIsTaken() {
        // A node whose props don't hold its key, or hold another value, would be made with no key; one without the call's
        // label would be kept as an end of a label it hasn't. takenKeys gives both, so the import is refused there. A
        // missing property is SQL null, which IS DISTINCT FROM any key.
        val taken = postgres.takenKeys("It's", "\$code")
        val disagrees = "SELECT s.key FROM ${postgres.nodeStage} s WHERE (s.props -> '\$\$code') IS DISTINCT FROM s.key OR NOT s.labels @> '[\"It''s\"]'"
        assertTrue(taken.lines().contains(disagrees), "on $target: $taken")
    }

    @Test
    fun anEndIsSrcOrDstInEachStatementThatNamesOne() {
        assertFailsWith<IllegalArgumentException> { postgres.ambiguousKeys(person, "both") }
        assertFailsWith<IllegalArgumentException> { postgres.findEnds(person, "src; DROP TABLE drivine_node") }
    }

    @Test
    fun aRelinkKeepsTheFirstTokenAsASaveDoes() {
        // The stamp's first token is kept as `<graph>_write`'s relinks keep it, through the same test of two tokens.
        val save = PostgresDdl("drivine").writes.ddl.last()
        val kept = "COALESCE(substr(${PostgresWriteFunctions.twoTokens("stamp")}, 1, 17), substr(p_mark, 1, 17)) || substr(p_mark, 18)"
        assertTrue(save.contains(kept), "on $target: the save's relink")
        val relink = postgres.relink(person, repo)
        assertTrue(relink.contains(kept.replace("stamp", "n.stamp").replace("p_mark", "CAST(\$mark AS text)")), "on $target: $relink")
    }

    @Test
    fun aNodesLowercasedCopiesAreWrittenThroughTheSavesFunction() {
        // makeNodes calls the function the layout makes and a save writes its copies through, under the graph's name.
        val ddl = PostgresDdl("other")
        val lowered = ddl.writes.lowered
        assertTrue(ddl.writes.ddl.any { it.startsWith("CREATE OR REPLACE FUNCTION $lowered(p_props jsonb, p_keys jsonb) ") }, "on $target: $lowered")
        val made = (BulkImportStatements.of(ddl) as BulkImportStatements.Postgres).makeNodes("Person", "id")
        assertTrue(made.contains("SELECT s.labels, $lowered(s.props, CAST(\$lower AS jsonb)), s.stamp FROM other_bulk_node s"), "on $target: $made")
    }

    @Test
    fun onlyPostgresHasABulkImportYet() {
        assertFailsWith<IllegalArgumentException> { BulkImportStatements.of(OracleDdl("drivine")) }
        assertFailsWith<IllegalArgumentException> { BulkImportStatements.of(SqliteDdl("drivine")) }
    }

    /** What [org.drivine.cypher.store.ddl.SqlStoreDdl.refusal] says of a graph's name on Postgres. */
    private object SqlNames {
        fun refused(name: String): String? = org.drivine.cypher.store.ddl.SqlStoreDdl.refusal(name, "Postgres", PostgresDdl.MAX_OBJECT_NAME)
    }
}
