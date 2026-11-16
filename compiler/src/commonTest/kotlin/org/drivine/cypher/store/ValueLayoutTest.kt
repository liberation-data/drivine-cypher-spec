package org.drivine.cypher.store

import org.drivine.cypher.json.ArrayNode
import org.drivine.cypher.json.CypherJson
import org.drivine.cypher.json.DoubleNode
import org.drivine.cypher.json.JsonNode
import org.drivine.cypher.json.LongNode
import org.drivine.cypher.json.ObjectNode
import org.drivine.cypher.json.TemporalValue
import org.drivine.cypher.number.oracleFile
import org.drivine.cypher.number.target
import org.drivine.cypher.sql.Temporal
import org.drivine.cypher.sql.Temporals
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * The store layout's values (liberation-data/drivine-cypher-spec#161), on each target: `host.yaml`'s sort keys, which
 * [Temporals.key] gives, and its reads of a column that holds a temporal value's tag or a key that begins with `$`, which
 * [ValueLayout.read] gives; and the encoder's tags and escapes, which [ValueLayout.read] reads back as they were given.
 */
class ValueLayoutTest {

    private val host: JsonNode = CypherJson.readTree(oracleFile("host.json"))

    @Test
    fun eachSortKeyIsTheOneHostYamlPins() {
        var checked = 0
        for (case in host["cases"]!!) for (step in case["steps"]!!) {
            val key = step["key"] ?: continue
            val kind = ValueLayout.kindOf(key["kind"]!!.textValue()!!)!!
            val v = key["v"]!!.textValue()!!
            assertEquals(step["result"]?.textValue(), Temporals.key(kind, v), "on $target: the sort key of $kind $v")
            // The text is canonical: the one the module writes of it.
            assertEquals(v, Temporals.canonical(v, kind), "on $target: $v is canonical")
            checked++
        }
        assertTrue(checked > 10, "host.yaml has $checked sort keys")
    }

    @Test
    fun aColumnThatHoldsTagsAndEscapedKeysIsReadAsHostYamlSays() {
        val case = host["cases"]!!.single { it["name"]!!.textValue() == "Rows that hold temporal values and keys that begin with \$" }
        for (step in case["steps"]!!) {
            val read = step["read"]!!
            val column = read["column"]!!.textValue()!!
            assertEquals(plain(step["value"]!!), plain(ValueLayout.read(CypherJson.readTree(column))), "on $target: $column")
        }
    }

    @Test
    fun theEncodersTagsAndEscapesReadBackAsTheyWereGiven() {
        val given = linkedMapOf(
            "\$t" to 1, "\$\$t" to 2, "a\$" to 3,
            "d" to TemporalValue(Temporal.DATE, "2024-01-01"),
            "l" to listOf(TemporalValue(Temporal.DATE_TIME, "2024-03-15T10:30:00.5+0200"), TemporalValue(Temporal.DURATION, "PT36H")),
        )
        val written = CypherJson.valueToTree(given)
        assertEquals(
            """{"${'$'}${'$'}t":1,"${'$'}${'$'}${'$'}t":2,"a${'$'}":3,"d":{"${'$'}t":"date","v":"2024-01-01","s":"10000020240101"},""" +
                """"l":[{"${'$'}t":"datetime","v":"2024-03-15T10:30:00.500+02:00","s":"10000020240315083000500000000072000"},{"${'$'}t":"duration","v":"PT36H"}]}""",
            CypherJson.writeValueAsString(written),
        )
        assertEquals(
            """{"${'$'}t":1,"${'$'}${'$'}t":2,"a${'$'}":3,"d":"2024-01-01","l":["2024-03-15T10:30:00.500+02:00","PT36H"]}""",
            CypherJson.writeValueAsString(ValueLayout.read(written)),
        )
        assertEquals(
            """{"d":{"${'$'}t":"date","v":"2024-01-01"},"m":{"${'$'}t":"map","v":{"${'$'}t":1}}}""",
            CypherJson.writeValueAsString(ValueLayout.read(CypherJson.valueToTree(mapOf("d" to TemporalValue(Temporal.DATE, "2024-01-01"), "m" to mapOf("\$t" to 1))), typed = true)),
        )
    }

    @Test
    fun aHostsOwnMapsAndListsReadBackAsTheTreeDoes() {
        // tagMap is tag as a host's map, and readValue reads a host's maps of the row's JSON as read reads its tree.
        val tag = ValueLayout.tag(Temporal.DATE_TIME, "2024-03-15T10:30:00.500+02:00")
        assertEquals(tag.properties().map { (key, value) -> key to value.textValue() }, ValueLayout.tagMap(Temporal.DATE_TIME, "2024-03-15T10:30:00.500+02:00").toList())
        assertEquals(setOf("\$t", "v"), ValueLayout.tagMap(Temporal.DURATION, "PT36H").keys, "a duration has no key")
        val held = mapOf(
            "\$\$t" to 1, "\$\$\$x" to 2, "a" to listOf(ValueLayout.tagMap(Temporal.DATE, "2024-01-01"), mapOf("\$\$t" to "date", "v" to "x")),
            "d" to ValueLayout.tagMap(Temporal.DURATION, "PT36H"),
        )
        assertEquals(
            mapOf("\$t" to 1, "\$\$x" to 2, "a" to listOf("2024-01-01", mapOf("\$t" to "date", "v" to "x")), "d" to "PT36H"),
            ValueLayout.readValue(held),
        )
    }

    /**
     * What a library may read without asking [ValueLayout.read]: Drivine for TypeScript reads a column whose JSON text holds
     * neither `"$` nor `\u0024` as it is, and asks the module only of the others, as a call of it costs some twenty times a
     * parse. So every tag and every escaped key the module writes holds `"$` in its text, and [ValueLayout.read] gives back
     * unchanged every value whose text holds neither. A change that tags a value otherwise fails here.
     */
    @Test
    fun everyTagAndEscapedKeyHoldsADollarAfterAQuoteAndReadChangesNoTextWithout() {
        val texts = mapOf(
            Temporal.DATE to "2024-01-01", Temporal.LOCAL_TIME to "10:00:00", Temporal.TIME to "10:00:00+02:00",
            Temporal.LOCAL_DATE_TIME to "2024-01-01T10:00:00", Temporal.DATE_TIME to "2024-03-15T10:30:00.500+02:00",
            Temporal.DURATION to "PT36H",
        )
        assertEquals(Temporal.entries.toSet(), texts.keys, "a text of each temporal kind")
        for ((kind, v) in texts) {
            val written = CypherJson.writeValueAsString(CypherJson.valueToTree(mapOf("x" to TemporalValue(kind, v))))
            assertTrue(written.contains("\"\$"), "on $target: the tag of $kind, $written")
        }
        for (key in listOf("\$", "\$t", "\$\$x", "\$1")) {
            val written = CypherJson.writeValueAsString(CypherJson.valueToTree(mapOf(key to 1)))
            assertTrue(written.contains("\"\$"), "on $target: the key $key, $written")
        }
        // A text with neither reads back as it is, whatever else it holds: no tag and no escaped key can be in it.
        val random = kotlin.random.Random(161)
        val pieces = listOf("{", "}", "[", "]", ",", ":", "\"t\"", "\"v\"", "\"s\"", "\"date\"", "\"a\$\"", "\"\\u0041\"", "1", "-2.5", "true", "null", "\"2024-01-01\"")
        var checked = 0
        repeat(if (target == "jvm") 50_000 else 5_000) {
            val text = (0 until random.nextInt(1, 14)).joinToString("") { pieces.random(random) }
            if (text.contains("\"\$") || text.contains("\\u0024")) return@repeat
            val tree = runCatching { CypherJson.readTree(text) }.getOrNull() ?: return@repeat
            assertEquals(CypherJson.writeValueAsString(tree), CypherJson.writeValueAsString(ValueLayout.read(tree)), "on $target: $text")
            assertEquals(CypherJson.writeValueAsString(tree), CypherJson.writeValueAsString(ValueLayout.read(tree, typed = true)), "on $target, typed: $text")
            checked++
        }
        assertTrue(checked > 500, "on $target: $checked texts read")
        // An object shaped as a tag with no dollar, `{"t": "date", "v": …}`, is not one.
        val shaped = CypherJson.readTree("""{"t":"date","v":"2024-01-01","s":"10000020240101"}""")
        assertEquals(shaped, ValueLayout.read(shaped))
    }

    /** [node] with each whole number a long, each other number a double, for the YAML's and the JSON's to compare. */
    private fun plain(node: JsonNode): Any? = when {
        node is ObjectNode -> node.properties().associate { (k, v) -> k to plain(v) }
        node is ArrayNode -> node.map(::plain)
        node.isNull -> null
        node.isTextual -> node.textValue()
        node.isBoolean -> node.booleanValue()
        node.isIntegralNumber -> LongNode(node.longValue()).value
        node.isNumber -> DoubleNode(node.doubleValue()).value
        else -> node.asText()
    }
}
