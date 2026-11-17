package org.drivine.cypher.api

import org.drivine.cypher.json.CypherJson
import org.drivine.cypher.json.ObjectNode
import org.drivine.cypher.json.TemporalValue
import org.drivine.cypher.sql.Temporal
import org.drivine.cypher.store.StoreValues
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * A parameter or a value written that is given as a temporal value whose text is no value of its kind, an offset with
 * seconds among them, is refused by name, on every store, not bound or stored as its text, which is equal to no temporal
 * value (liberation-data/drivine-cypher-spec#357). The JVM's own temporal values are held to it in `TemporalOfItsKindJvmTest`.
 */
class TemporalOfItsKindTest {

    private val stores = listOf("postgres", "oracle", "sqlite")

    private fun tag(kind: String, text: String) = "{\"\$temporal\": \"$kind\", \"text\": \"$text\"}"

    private fun compiled(store: String, cypher: String, parameter: String) =
        CypherJson.readTree(CompileJson.compile("{\"cypher\": \"$cypher\", \"store\": \"$store\", \"graph\": {\"name\": \"g\"}, \"parameters\": {\"t\": $parameter}}")) as ObjectNode

    /** The values of kind and text not of it, each with the message it is refused with. */
    private val refused = listOf(
        Triple("DATE_TIME", "1971-01-01T00:00:00-00:44:30", "A value given as a DATE_TIME isn't one: 1971-01-01T00:00:00-00:44:30 (the store holds an offset to the minute)."),
        Triple("DATE_TIME", "1971-01-01T00:00:00+004430", "A value given as a DATE_TIME isn't one: 1971-01-01T00:00:00+004430 (the store holds an offset to the minute)."),
        Triple("TIME", "12:00:00-00:44:30", "A value given as a TIME isn't one: 12:00:00-00:44:30 (the store holds an offset to the minute)."),
        Triple("DATE", "2024-13-01", "A value given as a DATE isn't one: 2024-13-01."),
        Triple("DURATION", "x", "A value given as a DURATION isn't one: x."),
    )

    @Test
    fun aParameterGivenAsATemporalValueOfNoneOfItsKindIsRefusedByName() {
        for (store in stores) for ((kind, text, message) in refused) {
            for (cypher in listOf("RETURN toString(\$t) AS r", "CREATE (n:N {t: \$t})")) {
                val answer = compiled(store, cypher, tag(kind, text))
                assertEquals(message, answer["refusal"]?.textValue(), "$store, $cypher, $kind $text: $answer")
            }
            // In a list and in a map too, as a write's UNWIND takes them.
            val answer = compiled(store, "UNWIND \$t AS row CREATE (n:N) SET n = row", "[{\"at\": ${tag(kind, text)}}]")
            assertEquals(message, answer["refusal"]?.textValue(), "$store, a list, $kind $text: $answer")
        }
    }

    @Test
    fun aParameterAtAnOffsetToTheMinuteIsStillBoundAsItsTag() {
        for (store in stores) {
            val answer = compiled(store, "RETURN toString(\$t) AS r", tag("DATE_TIME", "1971-01-01T00:00:00-00:45"))
            val bound = CypherJson.writeValueAsString(answer["parameters"])
            assertTrue("\\\"\$t\\\":\\\"datetime\\\"" in bound && "1971-01-01T00:00:00-00:45" in bound, "$store: $answer")
            val time = compiled(store, "RETURN toString(\$t) AS r", tag("TIME", "12:00:00-00:45"))
            assertTrue("\\\"\$t\\\":\\\"time\\\"" in CypherJson.writeValueAsString(time["parameters"]), "$store: $time")
        }
    }

    @Test
    fun aWriteRequestOfATemporalValueOfNoneOfItsKindIsAnErrorWithTheSameMessage() {
        for (store in stores) for ((kind, text, message) in refused) {
            val answer = CypherJson.readTree(CompileJson.compile("{\"write\": {\"at\": ${tag(kind, text)}}, \"store\": \"$store\"}"))
            assertEquals(message, answer["error"]?.textValue(), "$store, $kind $text: $answer")
        }
        val held = CypherJson.readTree(CompileJson.compile("{\"write\": ${tag("DATE_TIME", "1971-01-01T00:00:00-00:45")}, \"store\": \"sqlite\"}"))
        assertEquals("datetime", held["value"]?.get("\$t")?.textValue(), "$held")
    }

    @Test
    fun aSaveOfATemporalValueOfNoneOfItsKindIsRefusedByName() {
        for (store in Store.entries) {
            val refusal = StoreValues.refusal(mapOf("at" to TemporalValue(Temporal.DATE_TIME, "1971-01-01T00:00:00-00:44:30")), store)
            assertEquals("A value given as a DATE_TIME isn't one: 1971-01-01T00:00:00-00:44:30 (the store holds an offset to the minute).", refusal, store.id)
        }
    }
}
