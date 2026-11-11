package org.drivine.cypher.store

import org.drivine.cypher.json.ArrayNode
import org.drivine.cypher.json.CypherJson
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFails
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** What [SqliteCypherValues] does that host.yaml's cases don't reach: exact numbers, code point order and sort keys. */
class SqliteCypherValuesTest {

    private fun json(text: String) = SqliteCypherValues.read(text)!!

    private fun order(a: String, b: String) = SqliteCypherValues.order(json(a), json(b))

    @Test
    fun `a whole number beside a float is ordered by the float's exact value`() {
        // 2^53 + 1 is no double: as a double it would be 2^53, and equal.
        assertEquals(1, order("9007199254740993", "9007199254740992.0"))
        assertEquals(-1, order("9007199254740992.0", "9007199254740993"))
        assertEquals(0, order("9007199254740992", "9007199254740992.0"))
        assertEquals(-1, order("2", "2.5"))
        assertEquals(1, order("-2", "-2.5"))
        assertEquals(1, order("3", "2.5"))
        // 1e23 is 99999999999999991611392 exactly, below the whole number its shortest text reads as.
        assertEquals(1, order("100000000000000000000000", "1e23"))
        assertEquals(-1, order("99999999999999991611391", "1e23"))
        assertEquals(0, order("99999999999999991611392", "1e23"))
        assertEquals(1, order("9e999", "${"9".repeat(400)}"))
        assertEquals(-1, order("-9e999", "-${"9".repeat(400)}"))
        assertEquals(0, order("0", "-0.0"))
    }

    @Test
    fun `negative zero is equal to zero`() {
        assertTrue(SqliteCypherValues.equal(json("-0.0"), json("0.0")))
        assertTrue(SqliteCypherValues.equal(json("[-0.0]"), json("[0]")))
        assertEquals(0, order("[-0.0]", "[0.0]"))
    }

    @Test
    fun `texts are ordered by their code points and not their UTF-16 units`() {
        assertEquals(-1, order("\"\\uffff\"", "\"\\ud83d\\ude00\""))
        assertEquals(-1, order("\"a\"", "\"ab\""))
        assertEquals(1, order("[\"b\"]", "[\"ab\"]"))
    }

    @Test
    fun `maps are ordered only where equal and values of two kinds not at all`() {
        assertEquals(0, order("{\"a\":1,\"b\":[2]}", "{\"b\":[2.0],\"a\":1.0}"))
        assertNull(order("{\"a\":1}", "{\"a\":2}"))
        assertNull(order("{\"a\":null}", "{\"a\":null}"))
        assertNull(order("1", "\"1\""))
        assertNull(order("[1]", "[\"a\"]"))
        assertNull(order("[true]", "[1]"))
        assertEquals(1, order("true", "false"))
        assertEquals(-1, order("[false,1]", "[true]"))
    }

    @Test
    fun `a null decides an equality only where no pair is false`() {
        fun compare(a: String, b: String) = SqliteCypherValues.compare(json(a), json(b))
        assertEquals(false, compare("{\"a\":null}", "{\"b\":null}"))
        assertEquals(false, compare("[null,1]", "[null]"))
        assertNull(compare("{\"a\":null,\"b\":1}", "{\"b\":1,\"a\":2}"))
        assertEquals(false, compare("{\"a\":null,\"b\":1}", "{\"b\":2,\"a\":2}"))
        assertEquals(true, compare("\"a\"", "\"a\""))
        assertEquals(false, compare("1", "\"1\""))
        assertEquals(false, compare("true", "1"))
    }

    @Test
    fun `two whole numbers sum past a long's range to the whole number they make`() {
        assertEquals("9223372036854775808", SqliteCypherValues.write(SqliteCypherValues.add(json("9223372036854775807"), json("1"))!!))
        assertEquals("-9223372036854775809", SqliteCypherValues.write(SqliteCypherValues.add(json("-9223372036854775808"), json("-1"))!!))
        assertEquals("-9223372036854775808", SqliteCypherValues.write(SqliteCypherValues.add(json("-9223372036854775807"), json("-1"))!!))
        assertEquals("[null,1]", SqliteCypherValues.write(SqliteCypherValues.add(SqliteCypherValues.NULL, json("[1]"))!!))
        assertNull(SqliteCypherValues.add(json("true"), json("1")))
        assertNull(SqliteCypherValues.add(json("{\"a\":1}"), json("\"a\"")))
    }

    @Test
    fun `a sort key orders texts by code point and nested lists by their items and escapes its own marks`() {
        fun key(text: String) = SqliteCypherValues.sortKey(json(text) as ArrayNode)
        fun ascending(vararg lists: String) = lists.toList().zipWithNext().forEach { (a, b) ->
            // As SQLite orders text: by its UTF-8 bytes, which order as code points do.
            assertTrue(key(a).encodeToByteArray().asList().compareTo(key(b).encodeToByteArray().asList()) < 0, "$a before $b")
        }
        ascending("[\"\\uffff\"]", "[\"\\ud83d\\ude00\"]")
        ascending("[\"a\"]", "[\"a\\u0001\"]", "[\"a\\u0002\"]", "[\"a\\u0003\"]", "[\"ab\"]")
        ascending("[[1],2]", "[[1,2]]", "[[2]]")
        ascending("[false]", "[true]")
        ascending("[-1e308]", "[-1]", "[-0.0,1]", "[0,2]", "[1]")
        assertEquals(key("[-0.0]"), key("[0]"))
        // A map by its canonical text: the same entries in any order are the same key.
        assertEquals(key("[{\"b\":1,\"a\":[2]}]"), key("[{\"a\":[2],\"b\":1}]"))
    }

    @Test
    fun `a canonical map's keys are in UTF-16 order on every target as drivine4j's are`() {
        // U+1F600 is a surrogate pair from U+D83D, which comes before U+FFFF in UTF-16 though after it as a code point.
        val map = json("{\"\\uffff\":1,\"\\ud83d\\ude00\":2,\"b\":3}")
        assertEquals("{\"b\":3,\"\ud83d\ude00\":2,\"\uffff\":1}", SqliteCypherValues.write(SqliteCypherValues.canonical(map)))
    }

    @Test
    fun `a text that is no JSON fails and JSON's null is no value`() {
        assertFails { SqliteCypherValues.read("{") }
        assertNull(SqliteCypherValues.read("null"))
        assertNull(SqliteCypherValues.read(null))
        assertEquals(CypherJson.readTree("[1]"), SqliteCypherValues.read(" [1] "))
    }

    private fun List<Byte>.compareTo(other: List<Byte>): Int {
        for (i in 0 until minOf(size, other.size)) {
            val c = (this[i].toInt() and 0xFF).compareTo(other[i].toInt() and 0xFF)
            if (c != 0) return c
        }
        return size.compareTo(other.size)
    }
}
