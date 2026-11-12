package org.drivine.cypher

import org.drivine.cypher.json.ObjectNode
import org.drivine.cypher.sql.OracleCypherCompiler
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * On Oracle a statement that writes binds its parameters as one map, and reads each of them that holds a float as extended
 * JSON, where a float is written as extended JSON's double, so it is stored as the float it is (#146, #200). A map key
 * the user writes with a `$` is held with one more (`$$k`, [org.drivine.cypher.store.ValueLayout.escape]), which Oracle's
 * extended JSON reads as the key it is, as a captured Oracle 23 answered: so a map keyed so is read as extended JSON as any
 * other value is, and a float in it, or beside it, is a float (drivine-cypher-spec#202). Only a key with one `$`, as a host's
 * encoder could give in a tree of its own, which extended JSON might read as the value it writes, keeps its parameter read
 * as JSON, each other parameter that holds a float read as extended JSON on its own (#200).
 */
class OracleKeyedMapFloatTest {

    private val cypher = "CREATE (n:N {j: \$x, m: \$m.k}) RETURN [toString(n.j), n.m]"

    private val d = "${'$'}numberDouble"

    private fun compiled(parameters: Map<String, Any?>) = OracleCypherCompiler("g").compile(cypher, parameters)

    @Test
    fun `a map keyed with $ is held with $$, and a float beside it or in it is written as extended JSON's double`() {
        val beside = compiled(mapOf("x" to 1e20, "m" to mapOf("\$k" to "a", "k" to "b")))
        assertEquals("""{"x":{"$d":"1.0E20"},"m":{"${'$'}${'$'}k":"a","k":"b"}}""", beside.parameters.values.single() as String)
        val inside = compiled(mapOf("x" to "s", "m" to mapOf("\$k" to 2.0, "k" to 1e20)))
        assertEquals("""{"x":"s","m":{"${'$'}${'$'}k":{"$d":"2.0"},"k":{"$d":"1.0E20"}}}""", inside.parameters.values.single() as String)
    }

    @Test
    fun `without a map keyed with $ the parameters are written as before`() {
        val text = compiled(mapOf("x" to 1e20, "m" to mapOf("k" to "b"))).parameters.values.single() as String
        assertEquals("""{"x":{"$d":"1.0E20"},"m":{"k":"b"}}""", text)
    }

    @Test
    fun `a statement that only reads binds each parameter apart, a float in a map keyed with $ a double`() {
        val it = OracleCypherCompiler("g").compile("RETURN [toString(\$x), \$m.k]", mapOf("x" to 1e20, "m" to mapOf("\$k" to 2.0, "k" to "b")))
        assertTrue(it.parameters.values.any { v -> v == """{"$d":"1.0E20"}""" }, it.parameters.toString())
        assertTrue(it.parameters.values.any { v -> v == """{"${'$'}${'$'}k":{"$d":"2.0"},"k":"b"}""" }, it.parameters.toString())
    }

    @Test
    fun `a float nested in another map, in a list, or not finite, beside a map keyed with $ deeper down, is written as extended JSON's double`() {
        val it = OracleCypherCompiler("g").compile(
            "CREATE (n:N {a: \$m.n.x, l: \$l, f: \$f, k: \$k.b}) RETURN n",
            mapOf("m" to mapOf("n" to mapOf("x" to 2.0)), "l" to listOf(1.0, 1.5), "f" to Double.NaN, "k" to mapOf("a" to mapOf("\$k" to 1.5), "b" to 3)),
        )
        val text = it.parameters.values.single() as String
        assertEquals("""{"m":{"n":{"x":{"$d":"2.0"}}},"l":[{"$d":"1.0"},{"$d":"1.5"}],"f":{"$d":"NaN"},"k":{"a":{"${'$'}${'$'}k":{"$d":"1.5"}},"b":3}}""", text)
    }

    @Test
    fun `a tree a host gives with a key of one $ keeps its parameter read as JSON, and each other parameter's float a double`() {
        val tree = ObjectNode().apply { put("${'$'}k", "a"); put("k", "b") }
        val it = OracleCypherCompiler("g").compile(cypher, mapOf("x" to 1e20, "m" to tree))
        assertEquals("""{"x":{"$d":"1.0E20"},"m":{"${'$'}k":"a","k":"b"}}""", it.parameters.values.single() as String)
        assertTrue(it.sql.contains("JSON(JSON_QUERY(cy_params, '\$.x' RETURNING CLOB) EXTENDED)"), it.sql)
        assertFalse(it.sql.contains("JSON(JSON_QUERY(cy_params, '\$.m' RETURNING CLOB) EXTENDED)"), it.sql)
    }

    @Test
    fun `a float beside a temporal value in one parameter is written as extended JSON's double, the tag being no extended JSON`() {
        // A temporal value is a tag, {"$t", "v", "s"}, whose `$t` is the layout's own key, not one extended JSON reads.
        val rows = listOf(mapOf("d" to java.time.LocalDate.of(2024, 3, 15), "f" to 2.0))
        val it = OracleCypherCompiler("g").compile("UNWIND \$rows AS r CREATE (n:N {d: r.d, f: r.f}) RETURN n", mapOf("rows" to rows))
        assertEquals(
            """{"rows":[{"d":{"${'$'}t":"date","v":"2024-03-15","s":"10000020240315"},"f":{"$d":"2.0"}}]}""",
            it.parameters.values.single() as String,
        )
    }
}
