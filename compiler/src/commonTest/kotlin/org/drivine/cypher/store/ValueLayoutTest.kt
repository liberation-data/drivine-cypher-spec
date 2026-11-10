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
