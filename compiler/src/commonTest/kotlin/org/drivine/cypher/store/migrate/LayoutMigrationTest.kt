package org.drivine.cypher.store.migrate

import org.drivine.cypher.api.CompileJson
import org.drivine.cypher.api.Store
import org.drivine.cypher.json.CypherJson
import org.drivine.cypher.json.JsonNode
import org.drivine.cypher.json.ObjectNode
import org.drivine.cypher.number.oracleFile
import org.drivine.cypher.number.target
import org.drivine.cypher.store.ValueLayout
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * `migrate.yaml`'s rewrites of a layout-1 row to layout 2 (liberation-data/drivine-cypher-spec#256), on each target: each
 * row, store by store, through [LayoutMigration.rewrite] and through the JSON request `{"rewrite": …}`, which every library
 * that isn't on the JVM asks, gives the text, the changes and the invalid values the case pins, exactly.
 */
class LayoutMigrationTest {

    private val spec: JsonNode = CypherJson.readTree(oracleFile("migrate.json"))

    private fun declarations(case: JsonNode) = case["declarations"]!!.map {
        LayoutMigration.Declaration(it["label"]!!.textValue()!!, it["property"]!!.textValue()!!, LayoutMigration.Kind.of(it["kind"]!!.textValue()!!)!!)
    }

    @Test
    fun eachRowIsRewrittenAsMigrateYamlPins() {
        var checked = 0
        for (case in spec["rewrites"]!!) {
            val name = case["name"]!!.textValue()!!
            val labels = case["labels"]!!.map { it.textValue()!! }
            val declarations = declarations(case)
            for ((store, pinned) in (case["stores"] as ObjectNode).properties()) {
                val at = "on $target, $store: $name"
                val done = LayoutMigration.rewrite(pinned["props"]!!.textValue()!!, Store.of(store)!!, labels, declarations)
                assertEquals(pinned["result"]!!.textValue(), done.props, at)
                val changes = pinned["changes"]?.map { it["property"]!!.textValue() to it["change"]!!.textValue() }.orEmpty()
                assertEquals(changes, done.changes.map { it.property to it.change.word }, "$at: the changes")
                val invalid = pinned["invalid"]?.map { listOf(it["property"], it["kind"], it["value"], it["message"]).map { v -> v!!.textValue() } }.orEmpty()
                assertEquals(invalid, done.invalid.map { listOf(it.property, it.kind.word, it.value, it.message) }, "$at: the invalid values")
                assertEquals(changes.isNotEmpty(), done.changed, "$at: whether it is to be written")
                checked++
            }
        }
        assertTrue(checked >= 20, "migrate.yaml has $checked rewrites")
    }

    @Test
    fun theJsonRequestAnswersEachRowAlike() {
        for (case in spec["rewrites"]!!) {
            val name = case["name"]!!.textValue()!!
            for ((store, pinned) in (case["stores"] as ObjectNode).properties()) {
                val request = ObjectNode().apply {
                    set<JsonNode>("rewrite", pinned["props"]!!)
                    put("store", store)
                    set<JsonNode>("labels", case["labels"]!!)
                    set<JsonNode>("declarations", case["declarations"]!!)
                }
                val answer = CypherJson.readTree(CompileJson.compile(CypherJson.writeValueAsString(request)))
                val at = "on $target, $store: $name"
                assertEquals(CypherJson.readTree(pinned["result"]!!.textValue()!!).let(CypherJson::writeValueAsString), CypherJson.writeValueAsString(answer["value"]!!), at)
                fun rows(node: JsonNode?, vararg keys: String) = node?.map { row -> keys.map { row[it]?.textValue() } }.orEmpty()
                assertEquals(rows(pinned["changes"], "property", "change"), rows(answer["changes"], "property", "change"), "$at: the changes")
                assertEquals((pinned["changes"]?.size() ?: 0) > 0, answer["changed"]!!.booleanValue(), "$at: changed")
                assertEquals(rows(pinned["invalid"], "property", "kind", "value", "message"), rows(answer["invalid"], "property", "kind", "value", "message"), "$at: the invalid values")
            }
        }
    }

    @Test
    fun migrateYamlPinsTheLayoutsTheRewriteIsBetween() {
        assertEquals(LayoutMigration.FROM.toLong(), spec["from"]!!.longValue(), "on $target: from")
        assertEquals(LayoutMigration.TO.toLong(), spec["to"]!!.longValue(), "on $target: to")
        assertEquals(ValueLayout.LAYOUT, LayoutMigration.TO, "on $target: the rewrite is to the layout values are written in")
    }

    @Test
    fun eachStoresStatementsAreTheOnesMigrateYamlPins() {
        val stores = spec["statements"] as ObjectNode
        for ((name, ddl) in listOf(
            "postgres" to org.drivine.cypher.store.ddl.PostgresDdl("drivine"),
            "oracle" to org.drivine.cypher.store.ddl.OracleDdl("drivine"),
            "sqlite" to org.drivine.cypher.store.ddl.SqliteDdl("drivine"),
        )) {
            val pinned = stores[name]!!
            val statements = MigrationStatements.of(ddl)
            for (table in MigrationStatements.Table.entries) {
                assertEquals(pinned["readBatch"]!![table.word]!!.textValue(), statements.readBatch(table), "on $target, $name: readBatch ${table.word}")
                assertEquals(pinned["update"]!![table.word]!!.textValue(), statements.update(table), "on $target, $name: update ${table.word}")
            }
            assertEquals(pinned["own"]!!.textValue(), statements.own, "on $target, $name: own")
            assertEquals(pinned["sizes"]!!.textValue(), statements.sizes, "on $target, $name: sizes")
            assertEquals(pinned["createCheckpoint"]!!.textValue(), statements.createCheckpoint, "on $target, $name: createCheckpoint")
            assertEquals(pinned["readCheckpoint"]!!.textValue(), statements.readCheckpoint, "on $target, $name: readCheckpoint")
            assertEquals(pinned["writeCheckpoint"]!!.textValue(), statements.writeCheckpoint, "on $target, $name: writeCheckpoint")
            assertEquals(pinned["dropCheckpoint"]!!.textValue(), statements.dropCheckpoint, "on $target, $name: dropCheckpoint")
            assertEquals(pinned["lock"]?.textValue(), statements.lock, "on $target, $name: lock")
            assertEquals(pinned["uniques"]!!.textValue(), statements.uniques, "on $target, $name: uniques")
            assertEquals(pinned["stampLayout"]!!.textValue(), statements.stampLayout, "on $target, $name: stampLayout")
        }
        assertEquals(setOf("postgres", "oracle", "sqlite"), stores.fieldNames().asSequence().toSet())
    }

    @Test
    fun aFinishedGraphIsJudgedCurrentAsMigrateYamlPins() {
        for (case in spec["finished"]!!) {
            val store = case["store"]!!.textValue()!!
            val ddl = when (store) {
                "postgres" -> org.drivine.cypher.store.ddl.PostgresDdl("drivine")
                "oracle" -> org.drivine.cypher.store.ddl.OracleDdl("drivine")
                else -> org.drivine.cypher.store.ddl.SqliteDdl("drivine")
            }
            @Suppress("UNCHECKED_CAST")
            fun maps(key: String) = org.drivine.cypher.store.plan.PlanJson.plain(case[key]!!) as List<Map<String, Any?>>
            val verdict = org.drivine.cypher.store.marker.StoreLayout.verdict(
                org.drivine.cypher.store.marker.StoreStamp.engine(ddl), "drivine", maps("stamps"), case["foreignNodes"]!!.booleanValue(), maps("objects"), ddl,
            )
            assertEquals(org.drivine.cypher.store.marker.StoreLayout.Verdict.Current, verdict, "on $target, $store: a finished graph")
        }
        assertEquals(3, spec["finished"]!!.size())
    }

    @Test
    fun aListOfWholeAndFractionalNumbersIsKeptAsItWasStored() {
        // #377: a save widens [1, 2.5] to [1.0, 2.5]; a migration writes a row only for a change declared, so it keeps the list
        // as it was, or it would be widened in a row it writes for another change, and not in one it leaves.
        val declarations = listOf(LayoutMigration.Declaration("Person", "born", LayoutMigration.Kind.DATE))
        for (store in Store.entries) {
            val done = LayoutMigration.rewrite("{\"born\":\"2024-03-15\",\"l\":[1,2.5]}", store, listOf("Person"), declarations)
            // Its whole number kept whole: on Oracle its NUMBER with a fraction is made a double, as any is.
            assertTrue(done.changed && "\"l\":[1," in done.props, "on $target, ${store.id}: ${done.props}")
        }
    }

    @Test
    fun declarationsThatGiveOnePropertyTwoKindsAreRefused() {
        for (case in spec["declarationRefusals"]!!) {
            assertEquals(case["refusal"]!!.textValue(), LayoutMigration.refusal(declarations(case)), "on $target: ${case["name"]!!.textValue()}")
        }
        assertEquals(null, LayoutMigration.refusal(listOf(LayoutMigration.Declaration("Person", "born", LayoutMigration.Kind.DATE), LayoutMigration.Declaration("Robot", "born", LayoutMigration.Kind.DATE_TIME))))
    }
}
