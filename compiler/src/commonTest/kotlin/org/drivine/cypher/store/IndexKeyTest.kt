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
        // Bound only where an index answers it: a property no index holds binds no key, as its SQL names none.
        val unindexed = compiler.compile("MATCH (p:Person {name: \$n}) RETURN p.id", mapOf("n" to "Ada"))
        assertTrue(unindexed.parameters.keys.none { it.startsWith("_cyk_") }, unindexed.parameters.keys.toString())
        // A literal's key is written as the literal is, binding nothing of the compiler's.
        val literal = compiler.compile("MATCH (p:Person {id: 'p-1'}) RETURN p.name", emptyMap())
        assertTrue(literal.parameters.keys.none { it.startsWith("_cyk_") } && "= 'p-1'" in literal.sql, literal.sql)
        // A float is left to the SQL over the value: its key is Oracle's text of a binary double (store.yaml's doubleKeys).
        assertTrue(compiler.compile(statement, mapOf("id" to 2.5)).parameters.keys.none { it.startsWith("_cyk_") })
    }

    @Test
    fun aLiteralsKeyIsWrittenQuotedAndOneOracleTakesNoLiteralOfIsLeftToTheSql() {
        val compiler = OracleCypherCompiler("g", true, true, setOf("Person" to "id"))
        // A quote in the key is doubled, as the literal's own is.
        val quoted = compiler.compile("MATCH (p:Person {id: 'it\\'s'}) RETURN p.name", emptyMap()).sql
        assertTrue("= 'it''s'" in quoted, quoted)
        // 4,000 bytes with a quote among them is a literal of 4,001, which Oracle refuses (ORA-01704, drivine4j#419): the key is
        // left to the SQL over the literal's JSON, which is written in pieces.
        val text = "x".repeat(3_999) + "'"
        val long = compiler.compile("MATCH (p:Person {id: '${text.replace("'", "\\'")}'}) RETURN p.name", emptyMap()).sql
        assertTrue("'${text.replace("'", "''")}'" !in long, long.take(200))
    }

    @Test
    fun aTemporalParameterBindsItsSortKeyAndAMapsEntryBindsNone() {
        val compiler = OracleCypherCompiler("g", true, true, setOf("Person" to "born"))
        val given = org.drivine.cypher.json.TemporalValue(org.drivine.cypher.sql.Temporal.DATE, "2026-01-02")
        val compiled = compiler.compile("MATCH (p:Person {born: \$d}) RETURN p.name", mapOf("d" to given))
        assertEquals("10000020260102", compiled.parameters["_cyk_d"], compiled.parameters.toString())
        assertTrue("'date', \$_cyk_d" in compiled.sql || "= \$_cyk_d" in compiled.sql, compiled.sql)
        // An entry of a parameter that is a map has no marker of its own to be named after: the SQL over it is compared.
        val entry = compiler.compile("MATCH (p:Person {born: \$m.d}) RETURN p.name", mapOf("m" to mapOf("d" to given)))
        assertTrue(entry.parameters.keys.none { it.startsWith("_cyk_") }, entry.parameters.keys.toString())
    }

    @Test
    fun aParameterComparedTwiceBindsOneKeyThatEachComparisonReads() {
        val compiler = OracleCypherCompiler("g", true, true, setOf("Person" to "id", "Person" to "code"))
        val compiled = compiler.compile("MATCH (p:Person {id: \$x}), (q:Person {code: \$x}) RETURN p.name, q.name", mapOf("x" to "k"))
        assertEquals(listOf("_cyk_x"), compiled.parameters.keys.filter { it.startsWith("_cyk_") })
        assertEquals(4, Regex("= \\\$_cyk_x\\b").findAll(compiled.sql).count(), compiled.sql)
    }

    @Test
    fun postgresAndSqliteBindNoKey() {
        val statement = "MATCH (p:Person {id: \$id}) RETURN p.name"
        for (sql in listOf(org.drivine.cypher.sql.PostgresCypherCompiler("g"), org.drivine.cypher.sql.SqliteCypherCompiler("g"))) {
            val compiled = sql.compile(statement, mapOf("id" to "p-1"))
            assertTrue(compiled.parameters.keys.none { it.startsWith("_cyk_") } && "_cyk_" !in compiled.sql, compiled.sql)
        }
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
