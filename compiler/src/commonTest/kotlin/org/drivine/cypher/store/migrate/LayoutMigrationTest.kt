package org.drivine.cypher.store.migrate

import org.drivine.cypher.api.CompileJson
import org.drivine.cypher.api.Store
import org.drivine.cypher.json.CypherJson
import org.drivine.cypher.json.JsonNode
import org.drivine.cypher.json.ObjectNode
import org.drivine.cypher.number.oracleFile
import org.drivine.cypher.number.target
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
    fun declarationsThatGiveOnePropertyTwoKindsAreRefused() {
        for (case in spec["declarationRefusals"]!!) {
            assertEquals(case["refusal"]!!.textValue(), LayoutMigration.refusal(declarations(case)), "on $target: ${case["name"]!!.textValue()}")
        }
        assertEquals(null, LayoutMigration.refusal(listOf(LayoutMigration.Declaration("Person", "born", LayoutMigration.Kind.DATE), LayoutMigration.Declaration("Robot", "born", LayoutMigration.Kind.DATE_TIME))))
    }
}
