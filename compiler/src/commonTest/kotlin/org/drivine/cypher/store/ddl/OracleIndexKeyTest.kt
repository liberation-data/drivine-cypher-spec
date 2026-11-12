package org.drivine.cypher.store.ddl

import org.drivine.cypher.json.CypherJson
import org.drivine.cypher.json.JsonNode
import org.drivine.cypher.json.ObjectNode
import org.drivine.cypher.number.oracleFile
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Two rules of layout 2's indexes on Oracle (#161), checked over the spec's own files. No index expression calls a function
 * the store makes: Oracle makes each again with `CREATE OR REPLACE` whenever the store is made, and an index over one it
 * replaces is disabled, after which every write to its table fails (ORA-30554). And store.yaml's `doubleKeys`, the key
 * Oracle gives a binary double that is no whole number, are each `d` and Oracle's text of it, and no two alike.
 */
class OracleIndexKeyTest {

    private val store: JsonNode = CypherJson.readTree(oracleFile("store.json"))
    private val marker: JsonNode = CypherJson.readTree(oracleFile("marker.json"))

    /** The functions store.yaml makes on Oracle, by name. */
    private val functions: Set<String> = texts(store)
        .flatMap { Regex("""CREATE OR REPLACE FUNCTION (\w+)""", RegexOption.IGNORE_CASE).findAll(it).map { m -> m.groupValues[1].lowercase() } }
        .toSet()

    private fun texts(node: JsonNode): List<String> = when {
        node.isTextual -> listOf(node.textValue()!!)
        node.isArray || node.isObject -> node.toList().flatMap(::texts)
        else -> emptyList()
    }

    /** The texts within [node] under a key `oracle`, at any depth: what the spec has Oracle run. */
    private fun oracleTexts(node: JsonNode): List<String> = when {
        node is ObjectNode -> node.properties().flatMap { (key, value) -> if (key == "oracle") texts(value) else oracleTexts(value) }
        node.isArray -> node.toList().flatMap(::oracleTexts)
        else -> emptyList()
    }

    /** Each `CREATE [UNIQUE] INDEX` statement in [text], to its end: a PL/SQL block may run several, each as a literal. */
    private fun indexStatements(text: String): List<String> =
        Regex("""\bCREATE\s+(UNIQUE\s+)?INDEX\b[^;]*""", RegexOption.IGNORE_CASE).findAll(text).map { it.value }.toList()

    @Test
    fun `no index Oracle is made with calls a function the store makes`() {
        assertTrue("drivine_temporal_key" in functions && "drivine_era" in functions, "store.yaml's Oracle functions: $functions")
        val indexes = (oracleTexts(store) + oracleTexts(marker)).flatMap(::indexStatements)
        assertTrue(indexes.isNotEmpty(), "No Oracle CREATE INDEX found in store.yaml or marker.yaml")
        for (index in indexes) {
            val called = Regex("""\b(\w+)\s*\(""").findAll(index).map { it.groupValues[1].lowercase() }.filter { it in functions }.toList()
            assertEquals(emptyList(), called, "An index calls a function the store makes again: $index")
        }
    }

    @Test
    fun `each pinned double key is d and Oracle's text of a double and no two are alike`() {
        val keys = store["doubleKeys"]!!["oracle"]!!.toList()
        assertTrue(keys.size >= 4, "doubleKeys pins too few doubles: ${keys.size}")
        val texts = keys.map { it["key"]!!.textValue()!! }
        assertEquals(texts.size, texts.toSet().size, "Two doubles share a key: $texts")
        for (key in keys) {
            val text = key["key"]!!.textValue()!!
            assertTrue(Regex("""d-?[0-9](\.[0-9]{1,16})?E[+-][0-9]{3}""").matches(text), "Not Oracle's text of a double: $text")
            // The key reads back as the double it was made of: it tells it apart from every other.
            assertEquals(key["double"]!!.textValue()!!.toDouble(), text.removePrefix("d").toDouble(), text)
        }
    }
}
