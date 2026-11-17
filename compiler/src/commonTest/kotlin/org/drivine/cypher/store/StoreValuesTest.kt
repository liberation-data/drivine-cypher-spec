package org.drivine.cypher.store

import com.ionspin.kotlin.bignum.integer.BigInteger
import org.drivine.cypher.CompilerException
import org.drivine.cypher.api.Compiled
import org.drivine.cypher.api.CypherCompiler
import org.drivine.cypher.api.GraphOptions
import org.drivine.cypher.api.Refusal
import org.drivine.cypher.api.Store
import org.drivine.cypher.json.ArrayNode
import org.drivine.cypher.json.CypherEncoder
import org.drivine.cypher.json.DoubleNode
import org.drivine.cypher.json.JsonNode
import org.drivine.cypher.json.ObjectNode
import org.drivine.cypher.json.TemporalValue
import org.drivine.cypher.number.target
import org.drivine.cypher.sql.Temporal
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/**
 * A value as each store's layout holds it ([StoreValues.text]), which a library's save writes: exactly what a compiled
 * statement binds the value as, so a value saved and one made by `CREATE` can't drift apart (liberation-data/drivine-cypher-spec#329).
 */
class StoreValuesTest {

    private val date = TemporalValue(Temporal.DATE, "2024-01-01")
    private val at = TemporalValue(Temporal.DATE_TIME, "2024-03-15T10:30:00.5+02:00")

    /** Finite and non-finite floats, alone and in lists and maps, beside temporal values, bytes and keys that begin with `$`. */
    private val values: List<Any?> = listOf(
        null, true, "text", "\$t", 1L, -7L, 9007199254740993L, BigInteger.parseString("123456789012345678901234567890"),
        1.5, 2.0, -0.0, 1e7, 1e24, 1e-7, 0.1, Double.MAX_VALUE, Double.MIN_VALUE, 2.5f,
        Double.NaN, Double.POSITIVE_INFINITY, Double.NEGATIVE_INFINITY,
        listOf(1L, 2.0, "x"), listOf(1.5, Double.NaN), listOf(listOf(Double.POSITIVE_INFINITY), 2.0),
        mapOf("a" to 1.5, "b" to listOf(2.0, 3L)), mapOf("x" to Double.NEGATIVE_INFINITY), mapOf("\$numberDouble" to "NaN"),
        mapOf("\$numberDouble" to 2.0, "\$\$k" to listOf(1e7)),
        date, at, TemporalValue(Temporal.DURATION, "P1DT2H"), listOf(date, 2.0), mapOf("d" to date, "x" to Double.POSITIVE_INFINITY),
        mapOf("at" to at, "n" to Double.NaN, "f" to 2.0), mapOf("at" to at, "f" to 2.0),
        byteArrayOf(1, 2, -1), listOf(byteArrayOf(0, 127, -128), 1.5), mapOf("b" to byteArrayOf(1), "d" to date),
    )

    /** What [store]'s compiler binds `$x` as, given [value], where a statement reads it. */
    private fun bound(store: Store, value: Any?): String? {
        val compiled = CypherCompiler.compile(store, GraphOptions("g"), "RETURN \$x AS x", mapOf("x" to value))
        assertTrue(compiled is Compiled, "on $target, ${store.id}, $value: $compiled")
        assertTrue("_cy_x" in compiled.parameters, "on $target, ${store.id}, $value: ${compiled.parameters}")
        return compiled.parameters["_cy_x"] as String?
    }

    /**
     * Why [store]'s compiler refuses a statement that writes [value] for a float in it that the store holds as no number, or
     * null where it doesn't: a map as a property's value is refused for another reason.
     */
    private fun refused(store: Store, value: Any?): String? =
        (CypherCompiler.compile(store, GraphOptions("g"), "CREATE (n:N {x: \$x})", mapOf("x" to value)) as? Refusal)?.message
            ?.takeIf { it.startsWith("A statement that writes is given ") }

    @Test
    fun eachValueIsTheTextTheCompilerBindsOrTheRefusalOfAStatementThatWritesIt() {
        var held = 0
        var refusals = 0
        for (store in Store.entries) for (value in values) {
            val where = "on $target, ${store.id}, ${org.drivine.cypher.json.CypherJson.writeValueAsString(value)}"
            // A statement that writes a value the store holds as no number is refused; one that reads it binds a stand-in.
            val refusal = refused(store, value)
            if (refusal != null) {
                val e = assertFailsWith<CompilerException>(where) { StoreValues.text(value, store) }
                assertEquals(refusal, e.message, where)
                refusals++
            } else {
                // A null parameter is bound as SQL's null; its JSON is null.
                assertEquals(bound(store, value) ?: "null", StoreValues.text(value, store), where)
                held++
            }
        }
        // Postgres refuses each value with NaN or an infinity in it, and SQLite each with NaN; Oracle none.
        assertEquals(11, refusals, "on $target: the values refused")
        assertTrue(held > 100, "on $target: $held values held")
    }

    @Test
    fun eachStoreSpellsTheFloatsItHoldsAsItsCreateDoes() {
        assertEquals("{\"\$numberDouble\":\"NaN\"}", StoreValues.text(Double.NaN, Store.ORACLE))
        assertEquals("[{\"\$numberDouble\":\"Infinity\"},{\"\$numberDouble\":\"-Infinity\"}]", StoreValues.text(listOf(Double.POSITIVE_INFINITY, Double.NEGATIVE_INFINITY), Store.ORACLE))
        assertEquals("[9e999,-9e999]", StoreValues.text(listOf(Double.POSITIVE_INFINITY, Double.NEGATIVE_INFINITY), Store.SQLITE))
        assertEquals("[10000000.0,1.5]", StoreValues.text(listOf(1e7, 1.5), Store.POSTGRES))
        assertEquals("[1,2,-1]", StoreValues.text(byteArrayOf(1, 2, -1), Store.POSTGRES))
        assertEquals(
            "A statement that writes is given NaN or an infinity, which Postgres's `jsonb` holds as no number: it would store another value in its place.",
            assertFailsWith<CompilerException> { StoreValues.text(mapOf("x" to listOf(Double.NEGATIVE_INFINITY)), Store.POSTGRES) }.message,
        )
        assertEquals(
            "A statement that writes is given NaN, which SQLite's JSON holds as no number: it would store another value in its place.",
            assertFailsWith<CompilerException> { StoreValues.text(listOf(Float.NaN), Store.SQLITE) }.message,
        )
    }

    @Test
    fun aFloatAnEncoderGivesIsCheckedOnTheTree() {
        // The host's encoder writes its own type as a float: the store's refusal and spelling see the tree, not the value.
        class Reading(val value: Double)
        val encoder = CypherEncoder { (it as? Reading)?.let { r -> DoubleNode(r.value) } }
        val nan = mapOf("r" to listOf(Reading(Double.NaN)))
        assertFailsWith<CompilerException> { StoreValues.text(nan, Store.POSTGRES, encoder) }
        assertFailsWith<CompilerException> { StoreValues.text(nan, Store.SQLITE, encoder) }
        assertEquals("{\"r\":[{\"\$numberDouble\":\"NaN\"}]}", StoreValues.text(nan, Store.ORACLE, encoder))
        assertEquals("{\"r\":[9e999]}", StoreValues.text(mapOf("r" to listOf(Reading(Double.POSITIVE_INFINITY))), Store.SQLITE, encoder))
    }

    @Test
    fun aTreeGivenIsLeftAsItWasSoWritingItAgainWritesTheSame() {
        // A host's save may write one tree twice, as a retry does, and a compiler reads a parameter more than once.
        fun tree() = ObjectNode().apply { set<JsonNode>("f", DoubleNode(1e7)); set<JsonNode>("l", ArrayNode().add(DoubleNode(1.5))) }
        for (store in Store.entries) {
            val given = tree()
            val first = StoreValues.text(given, store)
            assertEquals(tree(), given, "on $target, ${store.id}: the tree given")
            assertEquals(first, StoreValues.text(given, store), "on $target, ${store.id}: written again")
            val compiled = CypherCompiler.compile(store, GraphOptions("g"), "CREATE (n:N {x: \$x})", mapOf("x" to given))
            assertEquals(compiled, CypherCompiler.compile(store, GraphOptions("g"), "CREATE (n:N {x: \$x})", mapOf("x" to given)), "on $target, ${store.id}: compiled again")
            assertEquals(tree(), given, "on $target, ${store.id}: the tree compiled")
        }
    }

    @Test
    fun aTreesNaNIsRefusedAsADoublesIs() {
        // A tree given as a parameter holds NaN as a Double does: a statement that writes it is refused, not given a stand-in or a text.
        for (store in Store.entries) for (nan in listOf<Any>(DoubleNode(Double.NaN), ObjectNode().apply { set<JsonNode>("n", DoubleNode(Double.NaN)) })) {
            assertEquals(refused(store, Double.NaN), refused(store, nan), "on $target, ${store.id}, $nan")
        }
    }

    @Test
    fun aTreeKeyedAsExtendedJsonIsRefusedOnOracle() {
        // Only a host's encoder, or a tree given as it is, holds a key that begins with one `$`: extended JSON would read it as a number.
        val keyed = ObjectNode().apply { set<JsonNode>("x", ObjectNode().put("\$numberLong", "5")) }
        val e = assertFailsWith<CompilerException> { StoreValues.text(keyed, Store.ORACLE) }
        assertEquals(StoreValues.KEYED_AS_EXTENDED, e.message)
        assertEquals("{\"x\":{\"\$numberLong\":\"5\"}}", StoreValues.text(ObjectNode().apply { set<JsonNode>("x", ObjectNode().put("\$numberLong", "5")) }, Store.POSTGRES))
        // A user's key, escaped, and a temporal value's tag are no such key.
        assertEquals("{\"\$\$numberLong\":\"5\",\"d\":{\"\$t\":\"date\",\"v\":\"2024-01-01\",\"s\":\"10000020240101\"}}", StoreValues.text(mapOf("\$numberLong" to "5", "d" to date), Store.ORACLE))
    }
}
