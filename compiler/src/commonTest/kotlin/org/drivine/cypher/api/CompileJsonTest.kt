package org.drivine.cypher.api

import com.ionspin.kotlin.bignum.decimal.BigDecimal
import com.ionspin.kotlin.bignum.integer.BigInteger
import org.drivine.cypher.json.CypherJson
import org.drivine.cypher.json.MAX_DEPTH
import org.drivine.cypher.json.TemporalValue
import org.drivine.cypher.sql.Temporal
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
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
    fun aRequestThatIsNoRequestSaysWhatItLacks() {
        val cases = mapOf(
            "{\"store\": 1, \"graph\": {\"name\": \"g\"}, \"cypher\": \"RETURN 1\"}" to "A request names its store: postgres, oracle or sqlite.",
            "{\"store\": \"sqlite\", \"graph\": {}, \"cypher\": \"RETURN 1\"}" to "A request's graph has a name.",
            "{\"store\": \"sqlite\", \"graph\": {\"name\": \"g\"}}" to "A request gives its statement as cypher.",
            "{\"store\": \"sqlite\", \"graph\": {\"name\": \"g\"}, \"cypher\": \"RETURN 1\", \"parameters\": [1]}" to "A request's parameters are a JSON object.",
        )
        for ((request, message) in cases) assertEquals("{\"error\":\"$message\"}", CompileJson.compile(request), request)
        val none = CompileJson.compile("{\"store\": \"sqlite\", \"graph\": {\"name\": \"g\"}, \"cypher\": \"RETURN 1\"}")
        assertEquals(none, CompileJson.compile("{\"store\": \"sqlite\", \"graph\": {\"name\": \"g\"}, \"cypher\": \"RETURN 1\", \"parameters\": null}"))
    }

    @Test
    fun aWholeFloatGivenTaggedIsCompiledAsAFloat() {
        fun compiled(x: String) = CompileJson.compile("{\"store\": \"sqlite\", \"graph\": {\"name\": \"g\"}, \"cypher\": \"RETURN \$x\", \"parameters\": {\"x\": $x}}")
        assertEquals(compiled("2.0"), compiled("{\"\$float\": 2}"))
        assertTrue(compiled("2.0") != compiled("2"), "the float 2.0 and the integer 2 compile alike")
    }

    private fun request(parameters: String, graph: String = "{\"name\": \"g\"}", store: String = "sqlite") =
        CompileJson.compile("{\"store\": \"$store\", \"graph\": $graph, \"cypher\": \"RETURN \$x\", \"parameters\": {\"x\": $parameters}}")

    @Test
    fun aRequestsNumberIsJsonsAlone() {
        // kotlinx.serialization takes these unquoted; a JSON number is none of them.
        for (number in listOf("1.5f", "0x1.8p1", ".5", "5.", "01", "-", "NaN", "Infinity", "1d", "tru")) {
            assertEquals("{\"error\":\"$number is no JSON value.\"}", request(number), number)
        }
        for (number in listOf("0", "-0", "1.5", "-1.5e-7", "1E+2", "123456789012345678901234567890")) {
            assertTrue(request(number).startsWith("{\"sql\":"), number)
        }
    }

    @Test
    fun aMalformedTagIsAnErrorAlikeOnEveryTarget() {
        assertEquals(
            "{\"error\":\"No temporal kind date: DATE, LOCAL_TIME, TIME, LOCAL_DATE_TIME, DATE_TIME, DURATION.\"}",
            request("{\"\$temporal\": \"date\", \"text\": \"2026-11-07\"}"),
        )
        assertEquals("{\"error\":\"For input string: \\\"two\\\"\"}", request("{\"\$float\": \"two\"}"))
        assertTrue(request("{\"\$bytes\": \"!!\"}").startsWith("{\"error\":"))
        assertTrue(request("{\"\$map\": 1}").startsWith("{\"error\":"))
        // A tag's text is a JSON string: a number or a null is not read as its text.
        assertEquals("{\"error\":\"{\\\"\$bytes\\\": …} holds base64 text.\"}", request("{\"\$bytes\": 1234}"))
        // A float is a number or its text: a boolean, a null or a container is not read as the text "true", "null" or "".
        for (float in listOf("{\"\$float\": true}", "{\"\$float\": null}", "{\"\$float\": {}}", "{\"\$float\": [2]}")) {
            assertEquals("{\"error\":\"{\\\"\$float\\\": …} holds a number or its text.\"}", request(float), float)
        }
        for (temporal in listOf("{\"\$temporal\": \"DATE\", \"text\": 20261107}", "{\"\$temporal\": \"DATE\", \"text\": null}", "{\"\$temporal\": 1, \"text\": \"2026-11-07\"}")) {
            assertEquals("{\"error\":\"{\\\"\$temporal\\\": <kind>, \\\"text\\\": …} holds text.\"}", request(temporal), temporal)
        }
    }

    @Test
    fun aGraphsOptionIsABooleanOrLeftOut() {
        assertEquals(
            "{\"error\":\"A request's graph gives nullsEqualStored as true or false.\"}",
            request("1", "{\"name\": \"g\", \"nullsEqualStored\": \"false\"}", "oracle"),
        )
        assertEquals(
            "{\"error\":\"A request's indexedProperties are [[label, property], …].\"}",
            request("1", "{\"name\": \"g\", \"indexedProperties\": {\"Person\": \"name\"}}", "oracle"),
        )
        for (pairs in listOf("[[\"Person\"]]", "[[\"Person\", 1]]", "[\"Person\"]", "[[\"Person\", \"name\", \"x\"]]")) {
            assertEquals(
                "{\"error\":\"An indexed property is [label, property].\"}",
                request("1", "{\"name\": \"g\", \"indexedProperties\": $pairs}", "oracle"),
                pairs,
            )
        }
        assertEquals(
            request("1", "{\"name\": \"g\"}", "oracle"),
            request("1", "{\"name\": \"g\", \"nullsEqualStored\": null, \"eraStored\": true, \"indexedProperties\": null}", "oracle"),
        )
    }

    @Test
    fun oraclesRowsAsTextIsABooleanFalseWhereLeftOut() {
        fun write(graph: String, store: String = "oracle") =
            CompileJson.compile("{\"store\": \"$store\", \"graph\": $graph, \"cypher\": \"UNWIND [1, 2, 3] AS i CREATE (n:N {i: i}) RETURN n.i\"}")
        assertEquals(
            "{\"error\":\"A request's graph gives oracleRowsAsText as true or false.\"}",
            write("{\"name\": \"g\", \"oracleRowsAsText\": \"true\"}"),
        )
        val json = write("{\"name\": \"g\"}")
        assertEquals(json, write("{\"name\": \"g\", \"oracleRowsAsText\": false}"))
        assertEquals(json, write("{\"name\": \"g\", \"oracleRowsAsText\": null}"))
        assertTrue("DBMS_SQL.RETURN_RESULT(cy_cursor)" in json && "rows:json" !in json, json)
        val text = write("{\"name\": \"g\", \"oracleRowsAsText\": true}")
        assertTrue("RETURNING CLOB EXTENDED) AS \\\"rows:json\\\" FROM (" in text, text)
        // Meaningful on Oracle only: the other stores compile alike either way.
        for (store in listOf("postgres", "sqlite")) {
            assertEquals(write("{\"name\": \"g\"}", store), write("{\"name\": \"g\", \"oracleRowsAsText\": true}", store), store)
        }
    }

    @Test
    fun anAnswersTextEscapesNulAndALoneSurrogate() {
        assertEquals("\"a\\u0000\\u001Fb\\\"\\\\\u2028\uD83D\uDE00\"", written("a\u0000\u001Fb\"\\\u2028\uD83D\uDE00"))
        assertEquals("\"a\\uD800b\\uDFFF\"", written("a\uD800b\uDFFF"))
        // A lone surrogate given comes back escaped, and so ASCII, in the text bound.
        val answer = request("\"a\\ud800b\"")
        assertTrue("\"_cy_x\":\"\\\"a\\uD800b\\\"\"" in answer, answer)
    }

    @Test
    fun aRequestNestedPastTheLimitIsAnErrorNotAnOverflow() {
        // The request's object and its parameters are two levels, so a value may nest 62 more.
        fun list(levels: Int) = "[".repeat(levels) + "1" + "]".repeat(levels)
        fun map(levels: Int) = "{\"a\":".repeat(levels) + "1" + "}".repeat(levels)
        for (store in listOf("postgres", "oracle", "sqlite")) {
            for (deepest in listOf(list(62), map(62))) {
                val answer = request(deepest, store = store)
                assertTrue(answer.startsWith("{\"sql\":"), "$store: ${answer.take(200)}")
            }
        }
        val refused = "{\"error\":\"A request nests its arrays and objects at most 64 deep.\"}"
        assertEquals(refused, request(list(63)))
        assertEquals(refused, request(map(63)))
        assertEquals(refused, request(list(100_000)))
        assertEquals(refused, CompileJson.compile("[".repeat(100_000) + "]".repeat(100_000)))
        // Brackets in a string, an escaped quote's too, are no nesting.
        val text = "\"\\\"" + "[{".repeat(1000) + "\""
        assertTrue(request(text).startsWith("{\"sql\":"), request(text).take(200))
        // An escaped backslash ends no string early or late: the brackets after it count, and those in a key don't.
        assertEquals(refused, request("[\"a\\\\\", " + list(62) + "]"))
        assertTrue(request("{\"" + "[{".repeat(1000) + "\": 1}").startsWith("{\"sql\":"))
    }

    @Test
    fun aValueNestedPastTheLimitIsRefusedReadOrWritten() {
        var deep: Any? = 1L
        repeat(MAX_DEPTH + 1) { deep = listOf(deep) }
        assertEquals("A value nests its lists and maps at most 64 deep.", assertFailsWith<IllegalArgumentException> { written(deep) }.message)
        val tree = CypherJson.valueToTree(deep)
        assertEquals("A value nests its lists and maps at most 64 deep.", assertFailsWith<IllegalArgumentException> { TypedJson.value(tree) }.message)
        assertEquals("[".repeat(MAX_DEPTH) + "1" + "]".repeat(MAX_DEPTH), written((deep as List<*>).single()))
    }

    @Test
    fun aDecimalIsTaggedAsNoNumberInTheAnswerIsUntaggedAndNotWhole() {
        assertEquals("{\"\$decimal\":\"2.5\"}", written(BigDecimal.parseString("2.5")))
        assertEquals("{\"\$decimal\":\"2\"}", written(BigDecimal.parseString("2")))
        // bignum's decimal keeps no scale, on any target: java.math's, on the JVM, does (TypedJsonJvmTest).
        assertEquals("{\"\$decimal\":\"2.5\"}", written(BigDecimal.parseString("2.50")))
        assertEquals("{\"\$decimal\":\"12345678901234567890.123456789\"}", written(BigDecimal.parseString("12345678901234567890.123456789")))
        assertEquals("[{\"\$decimal\":\"0.1\"}]", written(listOf(BigDecimal.parseString("0.1"))))
    }
}
