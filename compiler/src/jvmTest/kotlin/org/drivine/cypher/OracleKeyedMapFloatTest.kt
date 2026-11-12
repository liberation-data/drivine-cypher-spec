package org.drivine.cypher

import org.drivine.cypher.sql.OracleCypherCompiler
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * On Oracle a statement that writes binds its parameters as one map. Where one of them holds a map with a key as extended
 * JSON's are (`$k`), each other parameter that holds a float that is a whole number, or past a long's range, is still
 * written as extended JSON's double and read so on its own, so it is written as a float (#200).
 */
class OracleKeyedMapFloatTest {

    private val cypher = "CREATE (n:N {j: \$x, m: \$m.k}) RETURN [toString(n.j), n.m]"

    private fun compiled(parameters: Map<String, Any?>) = OracleCypherCompiler("g").compile(cypher, parameters)

    @Test
    fun `a float beside a map keyed with $ is written as extended JSON's double, and read as one on its own`() {
        val it = compiled(mapOf("x" to 1e20, "m" to mapOf("\$k" to "a", "k" to "b")))
        val text = it.parameters.values.single() as String
        assertEquals("""{"x":{"${'$'}numberDouble":"1.0E20"},"m":{"${'$'}k":"a","k":"b"}}""", text)
        assertTrue(it.sql.contains("JSON(JSON_QUERY(cy_params, '\$.x' RETURNING CLOB) EXTENDED)"), it.sql)
        // The map keyed with $ is read as JSON, not as extended JSON, so its key stays a key.
        assertFalse(it.sql.contains("JSON(JSON_QUERY(cy_params, '\$.m' RETURNING CLOB) EXTENDED)"), it.sql)
    }

    @Test
    fun `a float inside the map keyed with $ is written as JSON is, as before`() {
        val text = compiled(mapOf("x" to "s", "m" to mapOf("\$k" to "a", "k" to 2.0))).parameters.values.single() as String
        assertEquals("""{"x":"s","m":{"${'$'}k":"a","k":2.0}}""", text)
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
        assertTrue(it.parameters.values.any { v -> v == """{"${'$'}k":"a","k":"b"}""" }, it.parameters.toString())
    }
}
