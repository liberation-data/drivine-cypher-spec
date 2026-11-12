package org.drivine.cypher

import org.drivine.cypher.sql.OracleCypherCompiler
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * On Oracle a statement that writes binds its parameters as one map. Where one of them holds a map with a key as extended
 * JSON's are (`$k`), each other parameter that holds a float that is a whole number, or past a long's range, is still
 * written as extended JSON's double and read so on its own, so it is written as a float (#200). In layout 2 a key the
 * user writes with a `$` is held with one more (`$$k`, [org.drivine.cypher.store.ValueLayout.escape]), and every float is
 * written as a double, a fraction among them; the map keyed so is still read as JSON, not extended JSON.
 */
class OracleKeyedMapFloatTest {

    private val cypher = "CREATE (n:N {j: \$x, m: \$m.k}) RETURN [toString(n.j), n.m]"

    private fun compiled(parameters: Map<String, Any?>) = OracleCypherCompiler("g").compile(cypher, parameters)

    @Test
    fun `a float beside a map keyed with $ is written as extended JSON's double, and read as one on its own`() {
        val it = compiled(mapOf("x" to 1e20, "m" to mapOf("\$k" to "a", "k" to "b")))
        val text = it.parameters.values.single() as String
        assertEquals("""{"x":{"${'$'}numberDouble":"1.0E20"},"m":{"${'$'}${'$'}k":"a","k":"b"}}""", text)
        assertTrue(it.sql.contains("JSON(JSON_QUERY(cy_params, '\$.x' RETURNING CLOB) EXTENDED)"), it.sql)
        // The map keyed with $ is read as JSON, not as extended JSON, so its key stays a key.
        assertFalse(it.sql.contains("JSON(JSON_QUERY(cy_params, '\$.m' RETURNING CLOB) EXTENDED)"), it.sql)
    }

    @Test
    fun `a float inside the map keyed with $ is written as JSON is, as before`() {
        val text = compiled(mapOf("x" to "s", "m" to mapOf("\$k" to "a", "k" to 2.0))).parameters.values.single() as String
        assertEquals("""{"x":"s","m":{"${'$'}${'$'}k":"a","k":2.0}}""", text)
    }

    @Test
    fun `without a map keyed with $ the parameters are written as before`() {
        val text = compiled(mapOf("x" to 1e20, "m" to mapOf("k" to "b"))).parameters.values.single() as String
        assertEquals("""{"x":{"${'$'}numberDouble":"1.0E20"},"m":{"k":"b"}}""", text)
    }

    @Test
    fun `a statement that only reads binds each parameter apart, as before`() {
        val it = OracleCypherCompiler("g").compile("RETURN [toString(\$x), \$m.k]", mapOf("x" to 1e20, "m" to mapOf("\$k" to "a", "k" to "b")))
        assertTrue(it.parameters.values.any { v -> v == """{"${'$'}numberDouble":"1.0E20"}""" }, it.parameters.toString())
        assertTrue(it.parameters.values.any { v -> v == """{"${'$'}${'$'}k":"a","k":"b"}""" }, it.parameters.toString())
    }

    @Test
    fun `a float nested in another map, in a list, or not finite, beside a map keyed with $ deeper down, is written as extended JSON's double`() {
        val it = OracleCypherCompiler("g").compile(
            "CREATE (n:N {a: \$m.n.x, l: \$l, f: \$f, k: \$k.b}) RETURN n",
            mapOf("m" to mapOf("n" to mapOf("x" to 2.0)), "l" to listOf(1.0, 1.5), "f" to Double.NaN, "k" to mapOf("a" to mapOf("\$k" to 1), "b" to 3)),
        )
        val text = it.parameters.values.single() as String
        val d = "${'$'}numberDouble"
        assertEquals("""{"m":{"n":{"x":{"$d":"2.0"}}},"l":[{"$d":"1.0"},{"$d":"1.5"}],"f":{"$d":"NaN"},"k":{"a":{"${'$'}${'$'}k":1},"b":3}}""", text)
        for (path in listOf("\$.m.n.x", "\$.l", "\$.f")) assertTrue(it.sql.contains("JSON(JSON_QUERY(cy_params, '$path' RETURNING CLOB) EXTENDED)"), "$path: ${it.sql}")
        assertFalse(it.sql.contains("JSON(JSON_QUERY(cy_params, '\$.k"), it.sql)
    }
}
