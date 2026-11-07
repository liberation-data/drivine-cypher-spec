package org.drivine.cypher.json

import org.drivine.cypher.sql.OracleCypherCompiler
import org.drivine.cypher.sql.PostgresCypherCompiler
import org.drivine.cypher.sql.SqliteCypherCompiler
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertSame

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
}
