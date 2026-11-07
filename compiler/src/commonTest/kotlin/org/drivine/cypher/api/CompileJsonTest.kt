package org.drivine.cypher.api

import com.ionspin.kotlin.bignum.integer.BigInteger
import org.drivine.cypher.json.CypherJson
import org.drivine.cypher.json.TemporalValue
import org.drivine.cypher.sql.Temporal
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** The tagged JSON a host without Kotlin's types reads and writes: see [CompileJson]. */
class CompileJsonTest {

    private fun written(value: Any?) = StringBuilder().also { TypedJson.write(value, it) }.toString()

    private fun read(json: String) = TypedJson.value(CypherJson.readTree(json))

    @Test
    fun everyBoundFloatIsTaggedWholeOrNot() {
        assertEquals("{\"\$float\":\"2.0\"}", written(2.0))
        assertEquals("{\"\$float\":\"2.5\"}", written(2.5))
        assertEquals("{\"\$float\":\"1.0E23\"}", written(1e23))
        assertEquals("{\"\$float\":\"-0.0\"}", written(-0.0))
        assertEquals("{\"\$float\":\"NaN\"}", written(Double.NaN))
        assertEquals("{\"\$float\":\"-Infinity\"}", written(Double.NEGATIVE_INFINITY))
        assertEquals(
            "{\"a\":[{\"\$float\":\"1.0\"},2],\"m\":{\"\$map\":{\"\$k\":{\"\$float\":\"3.0\"},\"n\":null}}}",
            written(linkedMapOf("a" to listOf(1.0, 2L), "m" to linkedMapOf("\$k" to 3.0, "n" to null))),
        )
    }

    @Test
    fun wholeNumbersAndOtherValuesAreWrittenPlain() {
        assertEquals("2", written(2L))
        assertEquals("\"2.0\"", written("2.0"))
        assertEquals("true", written(true))
        assertEquals("123456789012345678901234567890", written(BigInteger.parseString("123456789012345678901234567890")))
        assertEquals("{\"\$bytes\":\"AAEC\"}", written(byteArrayOf(0, 1, 2)))
        assertEquals("{\"\$temporal\":\"DATE\",\"text\":\"2026-11-07\"}", written(TemporalValue(Temporal.DATE, "2026-11-07")))
    }

    @Test
    fun aRequestsFloatIsTaggedOrWrittenWithAFractionOrAnExponent() {
        for (json in listOf("{\"\$float\": 2}", "{\"\$float\": \"2.0\"}", "{\"\$float\": \"2\"}", "2.0", "2e0", "2E0")) {
            val value = read(json)
            assertTrue(value is Double && value == 2.0, "$json is the float 2.0, not $value")
        }
        assertTrue((read("{\"\$float\": \"NaN\"}") as Double).isNaN())
        assertEquals(2L, read("2"))
        assertEquals(BigInteger.parseString("9223372036854775808"), read("9223372036854775808"))
        assertEquals(mapOf("\$float" to 1L, "x" to 2L), read("{\"\$map\": {\"\$float\": 1, \"x\": 2}}"))
        assertEquals(mapOf("\$float" to 1L, "x" to 2L), read("{\"\$float\": 1, \"x\": 2}"))
        assertContentEquals(byteArrayOf(0, 1, 2), read("{\"\$bytes\": \"AAEC\"}") as ByteArray)
        assertEquals(TemporalValue(Temporal.TIME, "12:00+01:00"), read("{\"\$temporal\": \"TIME\", \"text\": \"12:00+01:00\"}"))
    }

    @Test
    fun aRefusalIsNoError() {
        val refused = CompileJson.compile("{\"store\": \"postgres\", \"graph\": {\"name\": \"g\"}, \"cypher\": \"MATCH\"}")
        assertTrue(refused.startsWith("{\"refusal\":\"The statement is not Cypher"), refused)
        for (request in listOf("", "[]", "{\"store\": \"neo4j\", \"graph\": {\"name\": \"g\"}, \"cypher\": \"RETURN 1\"}", "{\"store\": \"postgres\", \"cypher\": \"RETURN 1\"}")) {
            val answer = CompileJson.compile(request)
            assertTrue(answer.startsWith("{\"error\":"), "$request: $answer")
        }
    }

    @Test
    fun aWholeFloatGivenTaggedIsCompiledAsAFloat() {
        fun compiled(x: String) = CompileJson.compile("{\"store\": \"sqlite\", \"graph\": {\"name\": \"g\"}, \"cypher\": \"RETURN \$x\", \"parameters\": {\"x\": $x}}")
        assertEquals(compiled("2.0"), compiled("{\"\$float\": 2}"))
        assertTrue(compiled("2.0") != compiled("2"), "the float 2.0 and the integer 2 compile alike")
    }
}
