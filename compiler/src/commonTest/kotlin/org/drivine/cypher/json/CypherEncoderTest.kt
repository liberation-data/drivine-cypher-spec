package org.drivine.cypher.json

import org.drivine.cypher.sql.OracleCypherCompiler
import org.drivine.cypher.sql.PostgresCypherCompiler
import org.drivine.cypher.sql.SqliteCypherCompiler
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertSame
import kotlin.test.assertTrue

/** A host's [CypherEncoder]: asked before the compiler's own writing of a value, and only by the compiler it is given to. */
class CypherEncoderTest {

    /** An application's type, which the compiler has no JSON for. */
    private class Point(val x: Long, val y: Long)

    private val asked = mutableListOf<Any?>()

    /** A host's tree of a [Point], as `{"x": …, "y": …}`; null for anything else. */
    private val trees = CypherEncoder { value ->
        asked += value
        if (value is Point) ObjectNode().apply { set<JsonNode>("x", LongNode(value.x)); set<JsonNode>("y", LongNode(value.y)) } else null
    }

    /** As [trees], and a host's text of a [Point], `"x,y"`, written apart from its tree. */
    private val texts = object : CypherEncoder by trees {
        override fun text(value: Any?): String? = if (value is Point) "\"${value.x},${value.y}\"" else null
    }

    @Test
    fun theEncodersTreeIsTakenAtTheTopAndInsideAContainer() {
        assertEquals("{\"x\":1,\"y\":2}", CypherJson.writeValueAsString(CypherJson.valueToTree(Point(1, 2), trees)))
        val nested = CypherJson.valueToTree(mapOf("a" to listOf(Point(3, 4), "b")), trees)
        assertEquals("{\"a\":[{\"x\":3,\"y\":4},\"b\"]}", CypherJson.writeValueAsString(nested))
    }

    @Test
    fun aValueTheEncoderLeavesIsTheCompilersOwn() {
        assertEquals("[1,\"b\",2.5]", CypherJson.writeValueAsString(listOf(1, "b", 2.5), encoder = trees))
        assertEquals(listOf<Any?>(listOf(1, "b", 2.5), 1, "b", 2.5), asked)
    }

    @Test
    fun aTreeIsTakenAsItIsAndTheEncoderIsNotAsked() {
        val tree = ObjectNode().put("k", "v")
        assertSame(tree, CypherJson.valueToTree(tree, trees))
        assertEquals("{\"k\":\"v\"}", CypherJson.writeValueAsString(tree, encoder = texts))
        assertEquals(emptyList(), asked)
    }

    @Test
    fun withNoEncoderAnApplicationsTypeIsRefused() {
        assertFailsWith<IllegalArgumentException> { CypherJson.valueToTree(Point(1, 2)) }
    }

    @Test
    fun theEncodersTextIsTakenForTheWholeValueAndItsTreeWhereItGivesNone() {
        assertEquals("\"1,2\"", CypherJson.writeValueAsString(Point(1, 2), encoder = texts))
        // The text is asked of the value written alone: inside a container, the tree.
        assertEquals("[{\"x\":1,\"y\":2}]", CypherJson.writeValueAsString(listOf(Point(1, 2)), encoder = texts))
    }

    @Test
    fun decimalsWrittenPlainTakeTheEncodersTreeAndNotItsText() {
        assertEquals("{\"x\":1,\"y\":2}", CypherJson.writeValueAsString(Point(1, 2), plainDecimals = true, encoder = texts))
    }

    @Test
    fun eachStoresCompilerWritesAParameterAsItsEncoderDoes() {
        val p = mapOf("p" to Point(1, 2))
        assertEquals("{\"x\":1,\"y\":2}", PostgresCypherCompiler("g", trees).compile("RETURN \$p", p).parameters["_cy_p"])
        assertEquals("{\"x\":1,\"y\":2}", OracleCypherCompiler("g", true, true, emptySet(), trees).compile("RETURN \$p", p).parameters["_cy_p"])
        assertEquals("\"1,2\"", SqliteCypherCompiler("g", texts).compile("RETURN \$p", p).parameters["_cy_p"])
    }

    @Test
    fun aCompilerMadeWithoutAnEncoderAsksNone() {
        val p = mapOf("p" to Point(1, 2))
        assertFailsWith<IllegalArgumentException> { PostgresCypherCompiler("g").compile("RETURN \$p", p) }
        assertFailsWith<IllegalArgumentException> { OracleCypherCompiler("g").compile("RETURN \$p", p) }
        assertFailsWith<IllegalArgumentException> { SqliteCypherCompiler("g").compile("RETURN \$p", p) }
        // Another compiler's encoder is its own: one made after it, without one, still refuses the value.
        PostgresCypherCompiler("g", trees).compile("RETURN \$p", p)
        assertFailsWith<IllegalArgumentException> { PostgresCypherCompiler("g").compile("RETURN \$p", p) }
    }

    @Test
    fun theOldConstructorsCompileAsTheNewOnesWithNoEncoder() {
        val cypher = "MATCH (n:Person {name: \$name}) RETURN n.age"
        val p = mapOf("name" to "Ada")
        assertEquals(PostgresCypherCompiler("g", null).compile(cypher, p), PostgresCypherCompiler("g").compile(cypher, p))
        assertEquals(SqliteCypherCompiler("g", null).compile(cypher, p), SqliteCypherCompiler("g").compile(cypher, p))
        assertEquals(
            OracleCypherCompiler("g", false, false, setOf("Person" to "name"), null).compile(cypher, p),
            OracleCypherCompiler("g", nullsEqualStored = false, eraStored = false, indexedProperties = setOf("Person" to "name")).compile(cypher, p),
        )
    }

    /** An encoder that writes [target] as the text `"MARK"`, asking nothing of a tree, and keeps what it was asked of. */
    private fun marking(target: Any?) = object : CypherEncoder {
        val textsAsked = mutableListOf<Any?>()
        override fun tree(value: Any?): JsonNode? = null
        override fun text(value: Any?): String? = value.also { textsAsked += it }.takeIf { it == target }?.let { "\"MARK\"" }
    }

    @Test
    fun oraclesLiteralJsonIsWrittenAsTheEncodersText() {
        // A literal the statement writes, a text here, compared as JSON.
        val encoder = marking("x")
        val sql = OracleCypherCompiler("g", true, true, emptySet(), encoder).compile("MATCH (n:N) WHERE n.a = 'x' RETURN n").sql
        assertTrue("x" in encoder.textsAsked, "Not asked of 'x': ${encoder.textsAsked}")
        assertTrue("JSON('\"MARK\"')" in sql, sql)
    }

    @Test
    fun postgresLabelTestIsWrittenAsTheEncodersText() {
        val encoder = marking(listOf("Person"))
        val sql = PostgresCypherCompiler("g", encoder).compile("MATCH (n:Person) RETURN n").sql
        assertTrue(listOf("Person") in encoder.textsAsked, "Not asked of [\"Person\"]: ${encoder.textsAsked}")
        assertTrue("@> '\"MARK\"'" in sql, sql)
    }

    @Test
    fun sqlitesProcedureStepsAreWrittenAsTheEncodersText() {
        val encoder = object : CypherEncoder {
            val textsAsked = mutableListOf<Any?>()
            override fun tree(value: Any?): JsonNode? = null
            override fun text(value: Any?): String? {
                textsAsked += value
                return if (value is Map<*, *> && value.keys == setOf("steps")) "\"MARK\"" else null
            }
        }
        val sql = SqliteCypherCompiler("g", encoder).compile("CREATE (n:N {a: 1})").sql
        assertTrue(encoder.textsAsked.any { it is Map<*, *> && it.keys == setOf("steps") }, "Not asked of the steps: ${encoder.textsAsked}")
        assertTrue("MARK" in sql, sql)
    }

    @Test
    fun oraclesParameterKeyedAsExtendedJsonIsWrittenAsTheEncodersTreeAndNotReadAsExtended() {
        // A host's tree with a key as extended JSON's: written as JSON is, and not read as extended JSON, or Oracle would
        // read {"$numberLong": "5"} as the number 5.
        val encoder = CypherEncoder { value -> if (value is Point) ObjectNode().put("\$numberLong", value.x.toString()) else null }
        val compiled = OracleCypherCompiler("g", true, true, emptySet(), encoder).compile("RETURN \$p", mapOf("p" to Point(5, 0)))
        assertEquals("{\"\$numberLong\":\"5\"}", compiled.parameters["_cy_p"])
        assertTrue("EXTENDED" !in compiled.sql, compiled.sql)
    }
}
