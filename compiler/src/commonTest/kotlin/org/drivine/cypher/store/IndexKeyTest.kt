package org.drivine.cypher.store

import org.drivine.cypher.api.Store
import org.drivine.cypher.json.ArrayNode
import org.drivine.cypher.json.CypherJson
import org.drivine.cypher.json.JsonNode
import org.drivine.cypher.json.ObjectNode
import org.drivine.cypher.json.TextNode
import org.drivine.cypher.number.oracleFile
import org.drivine.cypher.sql.OracleCypherCompiler
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * A value's class and key as an index of layout 2 holds a property of it, worked out as the statement compiles ([IndexKey.of]),
 * against `store.yaml`'s `indexKeys`; and a lookup on Oracle compares them, bound once, with the index's columns, where the key
 * written over the bound value read it 59 times (#286).
 */
class IndexKeyTest {

    private val table: JsonNode = CypherJson.readTree(oracleFile("store.json"))["indexKeys"]!!

    /** [node] with each `{$repeat: text, times: n}` in it written out, as the spec's runners read it. */
    private fun expanded(node: JsonNode): JsonNode = when {
        node is ObjectNode && node["\$repeat"] != null -> TextNode(node["\$repeat"]!!.textValue()!!.repeat(node["times"]!!.longValue().toInt()))
        node is ObjectNode -> ObjectNode().also { out -> node.properties().forEach { (key, value) -> out.set<ObjectNode>(key, expanded(value)) } }
        node is ArrayNode -> ArrayNode().also { out -> node.toList().forEach { out.add(expanded(it)) } }
        else -> node
    }

    private fun pinned(row: JsonNode, store: String): IndexKey.Key? =
        row[store]?.takeUnless { it.isNull }?.let { expanded(it).toList() }?.let { (kind, key) -> IndexKey.Key(kind.textValue()!!, key.textValue()!!) }

    @Test
    fun eachValueIsKeyedAsStoreYamlPinsOnEachStore() {
        val rows = table["values"]!!.toList()
        assertTrue(rows.size >= 20, "indexKeys pins too few values: ${rows.size}")
        for (row in rows) {
            val value = expanded(row["value"]!!)
            val shown = CypherJson.writeValueAsString(value).take(80)
            assertEquals(pinned(row, "oracle"), IndexKey.of(Store.ORACLE, value), "oracle: $shown")
            assertEquals(pinned(row, "sqlite"), IndexKey.of(Store.SQLITE, value), "sqlite: $shown")
            assertNull(IndexKey.of(Store.POSTGRES, value), "postgres compares the value's JSON: $shown")
        }
    }

    @Test
    fun aLookupOnOracleBindsTheValuesKeyOnceAndComparesItWithEachIndexColumn() {
        val compiler = OracleCypherCompiler("g", true, true, setOf("Person" to "id"))
        val statement = "MATCH (p:Person {id: \$id}) RETURN p.name"
        for ((given, key) in listOf<Pair<Any, String>>("p-123" to "p-123", 5L to "5", true to "true")) {
            val compiled = compiler.compile(statement, mapOf("id" to given))
            assertEquals(key, compiled.parameters["_cyk_id"], "the key of $given")
            assertEquals(1, Regex("JSON\\(\\\$_cy_id\\)").findAll(compiled.sql).count(), compiled.sql)
            // The labelled range index's and the uniqueness constraint's key columns, each compared with the one bound key.
            assertEquals(2, Regex("= \\\$_cyk_id\\b").findAll(compiled.sql).count(), compiled.sql)
        }
        // Named after its parameter, as `_cy_id` is: the fragments of one statement, given parameters of their own, bind apart.
        val two = compiler.compile("MATCH (p:Person {id: \$a}), (q:Person {id: \$b}) RETURN p.name, q.name", mapOf("a" to "x", "b" to "y"))
        assertEquals("x", two.parameters["_cyk_a"])
        assertEquals("y", two.parameters["_cyk_b"])
        // A literal's key is written as the literal is, binding nothing of the compiler's.
        val literal = compiler.compile("MATCH (p:Person {id: 'p-1'}) RETURN p.name", emptyMap())
        assertTrue(literal.parameters.keys.none { it.startsWith("_cyk_") } && "= 'p-1'" in literal.sql, literal.sql)
        // A float is left to the SQL over the value: its key is Oracle's text of a binary double (store.yaml's doubleKeys).
        assertTrue(compiler.compile(statement, mapOf("id" to 2.5)).parameters.keys.none { it.startsWith("_cyk_") })
    }

    @Test
    fun aWriteWhoseParametersAreOneMapComparesTheKeyWrittenOverTheValue() {
        // A procedure binds its parameters as one map (Parameters.inObject), which holds no key of the compiler's.
        val compiled = OracleCypherCompiler("g", true, true, setOf("Person" to "id"))
            .compile("MATCH (p:Person {id: \$id}) SET p.seen = true RETURN p.name", mapOf("id" to "p-123"))
        assertTrue(compiled.parameters.keys.none { it.startsWith("_cyk_") }, compiled.parameters.keys.toString())
    }

    @Test
    fun thePropertiesTheTableIsReadUnderAreItsStoredKeys() {
        for (row in table["keys"]!!.toList()) {
            val key = row["key"]!!.textValue()!!
            assertEquals(row["stored"]?.textValue() ?: key, ValueLayout.escape(key))
        }
    }
}
