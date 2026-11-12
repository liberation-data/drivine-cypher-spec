package org.drivine.cypher.store

import com.ionspin.kotlin.bignum.integer.BigInteger
import org.drivine.DrivineInternalApi
import org.drivine.cypher.json.ArrayNode
import org.drivine.cypher.json.CypherJson
import org.drivine.cypher.json.JsonNode
import org.drivine.cypher.json.JsonNodeFactory
import org.drivine.cypher.json.NullNode
import org.drivine.cypher.json.ObjectNode
import org.drivine.cypher.text.CommonUnicode
import kotlin.math.floor
import kotlin.math.sign

/**
 * Cypher's values as a compiled statement holds them on SQLite, the JSON text of each, and what Cypher does with them that
 * SQLite's JSON functions do not: compare them, add them and order them. The compiler writes them ([write]); the host
 * functions SQL calls ([org.drivine.cypher.host.HostFunctions]) read them. A text a function is given that is SQL's null, or
 * JSON's, is no value.
 *
 * An infinity is the number SQLite's JSON writes for one, `9.0e+999`, or [INFINITY], each of which reads as the double it
 * is. NaN, which SQLite's JSON holds as no number, is [NAN], as a statement given NaN writes it: a number equal to nothing
 * and ordered with nothing.
 */
@DrivineInternalApi
object SqliteCypherValues {
    /**
     * The JSON text [text] read, or null where it is null, JSON's null, or empty: nothing but JSON's whitespace, which
     * Jackson, and so drivine4j, reads as a missing node.
     */
    fun read(text: String?): JsonNode? =
        text?.takeUnless { it.all { c -> c == ' ' || c == '\t' || c == '\n' || c == '\r' } }?.let(CypherJson::readTree)?.takeUnless { it.isNull }

    /** [node] as JSON text: an infinity as SQLite writes one, and NaN as [NAN]. */
    fun write(node: JsonNode): String = CypherJson.writeValueAsString(nonFinite(node))

    /** NaN, as a statement given one writes it, where SQLite's JSON holds no NaN: see [org.drivine.cypher.sql.SqliteCypherCompiler]. */
    val NAN: JsonNode = JsonNodeFactory.instance.objectNode().put("\$numberDouble", "NaN")

    /** JSON's null, which a list holds where an item is null. */
    val NULL: JsonNode = NullNode

    /** Whether [node] is NaN, as [NAN] writes it: a map of that one entry, and no other. */
    fun isNaN(node: JsonNode): Boolean = node == NAN

    /** [node] with each NaN in it, at any depth, [NAN], and each infinity the number SQLite writes for one. */
    fun nonFinite(node: JsonNode): JsonNode = when {
        node.isFloatingPointNumber && node.doubleValue().isNaN() -> NAN
        node.isFloatingPointNumber && node.doubleValue().isInfinite() ->
            JsonNodeFactory.instance.rawValueNode(if (node.doubleValue() > 0) INFINITY else "-$INFINITY")
        node is ObjectNode -> JsonNodeFactory.instance.objectNode().apply { node.properties().forEach { (key, value) -> set<JsonNode>(key, nonFinite(value)) } }
        node is ArrayNode -> JsonNodeFactory.instance.arrayNode().apply { node.forEach { add(nonFinite(it)) } }
        else -> node
    }

    /**
     * Whether [a] and [b] are equal as Cypher compares them: a number equal to a number of the same value whatever its kind
     * (`1` and `1.0`), a list to a list of equal items in the same order, a map to a map of the same keys with equal values
     * in any order, and nothing to a value of another kind.
     */
    fun equal(a: JsonNode, b: JsonNode): Boolean = when {
        isNaN(a) || isNaN(b) -> false
        a.isNumber && b.isNumber -> numberOrder(a, b) == 0
        a.isArray && b.isArray -> a.size() == b.size() && (0 until a.size()).all { equal(a[it]!!, b[it]!!) }
        a is ObjectNode && b is ObjectNode -> a.size() == b.size() && a.properties().all { (key, value) -> b.has(key) && equal(value, b[key]!!) }
        else -> a == b
    }

    /**
     * Whether [a] and [b] are equal as Cypher compares values that may be or hold a null: null where either is null; a list
     * to a list, and a map to a map, item by item and key by key, false where a pair is false (lists of different lengths
     * and maps of different keys among them), else null where a pair is null, else true; any other two as [equal] compares them.
     */
    fun compare(a: JsonNode, b: JsonNode): Boolean? {
        fun all(pairs: List<Pair<JsonNode, JsonNode>>): Boolean? {
            var unknown = false
            for ((x, y) in pairs) when (compare(x, y)) {
                false -> return false
                null -> unknown = true
                true -> {}
            }
            return if (unknown) null else true
        }
        return when {
            a.isNull || b.isNull -> null
            isNaN(a) || isNaN(b) -> false
            a.isArray && b.isArray -> if (a.size() != b.size()) false else all((0 until a.size()).map { a[it]!! to b[it]!! })
            a is ObjectNode && b is ObjectNode ->
                if (a.size() != b.size() || a.properties().any { !b.has(it.key) }) false else all(a.properties().map { (key, value) -> value to b[key]!! })
            else -> equal(a, b)
        }
    }

    /**
     * `a + b`, as Cypher adds two values: numbers summed, a whole number where both are; lists joined, or an item put at a
     * list's end or start; a text and a text or a number written one after the other. Null for any other two.
     */
    fun add(a: JsonNode, b: JsonNode): JsonNode? = when {
        isNumeric(a) && isNumeric(b) ->
            if (a.isIntegralNumber && b.isIntegralNumber) {
                val x = a.bigIntegerValue() + b.bigIntegerValue()
                if (x >= LONG_MIN && x <= LONG_MAX) JsonNodeFactory.instance.numberNode(x.longValue()) else JsonNodeFactory.instance.numberNode(x)
            } else {
                nonFinite(JsonNodeFactory.instance.numberNode(double(a) + double(b)))
            }
        a.isArray -> JsonNodeFactory.instance.arrayNode().addAll(a.toList()).apply { if (b.isArray) addAll(b.toList()) else add(b) }
        b.isArray -> JsonNodeFactory.instance.arrayNode().add(a).addAll(b.toList())
        (a.isTextual || isNumeric(a)) && (b.isTextual || isNumeric(b)) -> JsonNodeFactory.instance.textNode(text(a) + text(b))
        else -> null
    }

    /** [node] with each map in it, at any depth, with its keys in order. */
    fun canonical(node: JsonNode): JsonNode = when (node) {
        is ObjectNode -> JsonNodeFactory.instance.objectNode().apply { node.properties().sortedBy { it.key }.forEach { (key, value) -> set<JsonNode>(key, canonical(value)) } }
        is ArrayNode -> JsonNodeFactory.instance.arrayNode().apply { node.forEach { add(canonical(it)) } }
        else -> node
    }

    /**
     * Where [a] comes beside [b] as Cypher orders two values in a comparison (`<`, `>=` …): -1 before, 0 equal, 1 after. A list
     * beside a list item by item, the first pair that is not equal deciding, and a list that the other starts with first; a
     * number beside a number, a text beside a text by its code points, and a truth value beside a truth value. Null where they
     * are not ordered: a null, values of two kinds, or two maps that differ, which Cypher orders and the compiler does not yet.
     */
    fun order(a: JsonNode, b: JsonNode): Int? = when {
        a.isNull || b.isNull || isNaN(a) || isNaN(b) -> null
        a.isArray && b.isArray -> listOrder(a, b)
        a.isObject && b.isObject -> 0.takeIf { compare(a, b) == true }
        a.isNumber && b.isNumber -> numberOrder(a, b)
        // By code points, as Cypher orders texts: Kotlin's `compareTo` orders UTF-16's units.
        a.isTextual && b.isTextual -> codePointOrder(a.textValue()!!, b.textValue()!!)
        a.isBoolean && b.isBoolean -> a.booleanValue().compareTo(b.booleanValue()).sign
        else -> null
    }

    /**
     * A text that orders, as SQLite orders text, as Cypher orders the list [node]: item by item, a list that begins another
     * first, and items of different kinds as Cypher orders kinds (a map, a list, a text, a truth value, a number, then null
     * last). Each item is its kind's rank, then itself: a number by the bits of its double, so their text orders as the
     * numbers do, NaN after them all; a text up to a mark below any character; a list its own items, then its end, which
     * comes before any item.
     */
    fun sortKey(node: ArrayNode): String = StringBuilder().also { list(node, it) }.toString()

    /** An infinity as SQLite reads it, as it reads `9.0e+999`, which its JSON writes for one. */
    const val INFINITY = "9e999"

    /** Whether [node] is a number, NaN among them. */
    private fun isNumeric(node: JsonNode) = node.isNumber || isNaN(node)

    /** [node], a number, as a double: NaN for [NAN]. */
    private fun double(node: JsonNode) = if (isNaN(node)) Double.NaN else node.doubleValue()

    /** The text [node], a text or a number, is put together with another as: NaN and an infinity as Java writes them. */
    private fun text(node: JsonNode) = if (isNaN(node)) "NaN" else node.asText()

    /** [order] of the lists [a] and [b]: by their first pair that is not equal, which is null where it is not ordered; else by their sizes. */
    private fun listOrder(a: JsonNode, b: JsonNode): Int? {
        for (i in 0 until minOf(a.size(), b.size())) {
            val pair = order(a[i]!!, b[i]!!)
            if (pair != 0) return pair
        }
        return a.size().compareTo(b.size()).sign
    }

    /**
     * Where the number [a] comes beside the number [b], by their exact values: two whole numbers as such, however large; a
     * whole number beside a float by the float's exact binary value, so `9007199254740993` comes after `9007199254740992.0`;
     * two floats, or an infinity beside anything, as doubles, `-0.0` equal to `0.0`.
     */
    private fun numberOrder(a: JsonNode, b: JsonNode): Int = when {
        a.isIntegralNumber && b.isIntegralNumber -> a.bigIntegerValue().compareTo(b.bigIntegerValue()).sign
        a.isIntegralNumber -> -wholeBesideFloat(b.doubleValue(), a.bigIntegerValue())
        b.isIntegralNumber -> wholeBesideFloat(a.doubleValue(), b.bigIntegerValue())
        else -> doubleOrder(a.doubleValue(), b.doubleValue())
    }

    /** [x] beside [y], neither NaN, as numbers: `-0.0` equal to `0.0`, where `compareTo` puts it first. */
    private fun doubleOrder(x: Double, y: Double): Int = if (x < y) -1 else if (x > y) 1 else 0

    /** Where the float [x] comes beside the whole number [n]: by its whole part's exact value, then by any fraction it has. */
    private fun wholeBesideFloat(x: Double, n: BigInteger): Int {
        if (!x.isFinite()) return doubleOrder(x, 0.0)
        val whole = floor(x)
        val c = exactWhole(whole).compareTo(n).sign
        return if (c != 0) c else if (x == whole) 0 else 1
    }

    /** The whole double [x], exactly: its significand shifted by its exponent. */
    private fun exactWhole(x: Double): BigInteger {
        if (x == 0.0) return BigInteger.ZERO
        val bits = x.toRawBits()
        val exponent = ((bits ushr 52) and 0x7FF).toInt()
        val significand = (bits and 0xFFFFFFFFFFFFFL) or (if (exponent == 0) 0L else 1L shl 52)
        // A whole double is at least 1 in size, so its exponent is never a subnormal's.
        val shift = exponent - 1075
        val magnitude = if (shift >= 0) BigInteger.fromLong(significand).shl(shift) else BigInteger.fromLong(significand shr -shift)
        return if (x < 0) magnitude.negate() else magnitude
    }

    /** [a] beside [b] by their code points, as Cypher orders texts, a lone surrogate as itself. */
    private fun codePointOrder(a: String, b: String): Int {
        val x = CommonUnicode.codePoints(a)
        val y = CommonUnicode.codePoints(b)
        for (i in 0 until minOf(x.size, y.size)) if (x[i] != y[i]) return x[i].compareTo(y[i]).sign
        return x.size.compareTo(y.size).sign
    }

    private fun list(node: JsonNode, out: StringBuilder) {
        node.forEach { item(it, out) }
        out.append(END)
    }

    private fun item(node: JsonNode, out: StringBuilder) {
        when {
            // NaN, as a statement given it writes it, is a number, whose bits come after every other's, an infinity's too.
            isNaN(node) -> out.append('5').append(numberKey(Double.NaN))
            node.isObject -> out.append('1').also { textKey(write(canonical(node)), out) }
            node.isArray -> out.append('2').also { list(node, out) }
            node.isTextual -> out.append('3').also { textKey(node.textValue()!!, out) }
            node.isBoolean -> out.append('4').append(if (node.booleanValue()) '1' else '0')
            node.isNumber -> out.append('5').append(numberKey(node.doubleValue()))
            else -> out.append('6')
        }
    }

    /** The text, its two lowest characters escaped, then a mark below any character it holds. */
    private fun textKey(text: String, out: StringBuilder) {
        text.forEach { c ->
            when (c) {
                '\u0001' -> out.append("\u0002\u0002")
                '\u0002' -> out.append("\u0002\u0003")
                else -> out.append(c)
            }
        }
        out.append('\u0001')
    }

    /** The double's bits as 16 hexadecimal digits that order as the doubles do: a positive one's sign set, a negative one's bits flipped. */
    private fun numberKey(value: Double): String {
        val bits = (if (value == 0.0) 0.0 else value).toBits()
        val ordered = if (bits < 0) bits.inv() else bits or Long.MIN_VALUE
        return ordered.toULong().toString(16).uppercase().padStart(16, '0')
    }

    /** The end of a list, which comes before any item's rank. */
    private const val END = '0'

    private val LONG_MIN = BigInteger.fromLong(Long.MIN_VALUE)
    private val LONG_MAX = BigInteger.fromLong(Long.MAX_VALUE)
}
