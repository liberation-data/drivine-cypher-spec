package org.drivine.cypher.json

import com.ionspin.kotlin.bignum.decimal.BigDecimal
import com.ionspin.kotlin.bignum.integer.BigInteger
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

/**
 * SPIKE: a JSON tree shaped as Jackson's `JsonNode`, so the compiler's code that walks parameter trees moves to common code
 * with few edits. kotlinx.serialization's `JsonElement` keeps a number as its text only, and the compiler tells a double from
 * a whole number and a `BigDecimal` (Postgres's whole floats, Oracle's extended doubles), so a tree of its own types is kept;
 * kotlinx.serialization parses text into it ([CypherJson.readTree]).
 */
abstract class JsonNode : Iterable<JsonNode> {
    open val isNull: Boolean get() = false
    val isMissingNode: Boolean get() = false
    open val isArray: Boolean get() = false
    open val isObject: Boolean get() = false
    val isContainerNode: Boolean get() = isArray || isObject
    open val isTextual: Boolean get() = false
    open val isBoolean: Boolean get() = false
    open val isNumber: Boolean get() = false
    open val isIntegralNumber: Boolean get() = false
    open val isDouble: Boolean get() = false
    open val isFloat: Boolean get() = false
    val isFloatingPointNumber: Boolean get() = isNumber && !isIntegralNumber
    open fun textValue(): String? = null
    open fun booleanValue(): Boolean = false
    open fun doubleValue(): Double = 0.0
    open fun longValue(): Long = 0L
    open fun bigIntegerValue(): BigInteger = BigInteger.fromLong(longValue())
    open fun asText(): String = ""
    open fun size(): Int = 0
    open operator fun get(key: String): JsonNode? = null
    open operator fun get(index: Int): JsonNode? = null
    open fun has(key: String): Boolean = false
    override fun iterator(): Iterator<JsonNode> = emptyList<JsonNode>().iterator()
}

object NullNode : JsonNode() {
    val instance = this
    override val isNull get() = true
    override fun asText() = "null"
    override fun equals(other: Any?) = other === this
    override fun hashCode() = 0
}

class BooleanNode(val value: Boolean) : JsonNode() {
    override val isBoolean get() = true
    override fun booleanValue() = value
    override fun asText() = value.toString()
    override fun equals(other: Any?) = other is BooleanNode && other.value == value
    override fun hashCode() = value.hashCode()
}

class TextNode(val value: String) : JsonNode() {
    override val isTextual get() = true
    override fun textValue() = value
    override fun asText() = value
    override fun equals(other: Any?) = other is TextNode && other.value == value
    override fun hashCode() = value.hashCode()
}

class LongNode(val value: Long) : JsonNode() {
    override val isNumber get() = true
    override val isIntegralNumber get() = true
    override fun longValue() = value
    override fun doubleValue() = value.toDouble()
    override fun asText() = value.toString()
    override fun equals(other: Any?) = other is LongNode && other.value == value
    override fun hashCode() = value.hashCode()
}

class BigIntegerNode(val value: BigInteger) : JsonNode() {
    override val isNumber get() = true
    override val isIntegralNumber get() = true
    override fun longValue() = value.longValue(exactRequired = false)
    /** As Java's `BigInteger.doubleValue`, the nearest double. */
    override fun doubleValue() = parseDouble(value.toString())
    override fun bigIntegerValue() = value
    override fun asText() = value.toString()
    override fun equals(other: Any?) = other is BigIntegerNode && other.value == value
    override fun hashCode() = value.hashCode()
}

class DoubleNode(val value: Double) : JsonNode() {
    override val isNumber get() = true
    override val isDouble get() = true
    override fun doubleValue() = value
    override fun longValue() = value.toLong()
    override fun asText() = jvmDoubleText(value)
    override fun equals(other: Any?) = other is DoubleNode && other.value == value
    override fun hashCode() = value.hashCode()
}

class FloatNode(val value: Float) : JsonNode() {
    override val isNumber get() = true
    override val isFloat get() = true
    override fun doubleValue() = value.toDouble()
    override fun longValue() = value.toLong()
    override fun asText() = jvmFloatText(value)
    override fun equals(other: Any?) = other is FloatNode && other.value == value
    override fun hashCode() = value.hashCode()
}

/** A decimal number, kept as the text Jackson's `BigDecimal` writes, [plain] or as `toString` writes it. */
class DecimalNode(val value: BigDecimal, val plain: String, val scientific: String = plain) : JsonNode() {
    override val isNumber get() = true
    /** As Java's `BigDecimal.doubleValue`, the nearest double: bignum's own reads the platform's text of it. */
    override fun doubleValue() = parseDouble(plain)
    override fun longValue() = value.longValue(exactRequired = false)
    override fun asText() = scientific
    override fun equals(other: Any?) = other is DecimalNode && other.value == value
    override fun hashCode() = value.hashCode()
}

/** Text written into the JSON as it is: Jackson's `RawValue`. */
class RawNode(val raw: String) : JsonNode() {
    override fun asText() = raw
}

class ArrayNode(private val items: MutableList<JsonNode> = mutableListOf()) : JsonNode() {
    override val isArray get() = true
    override fun size() = items.size
    override fun get(index: Int): JsonNode = items[index]
    operator fun set(index: Int, value: JsonNode) { items[index] = value }
    fun add(value: JsonNode): ArrayNode = apply { items.add(value) }
    fun addAll(values: Collection<JsonNode>): ArrayNode = apply { items.addAll(values) }
    override fun iterator() = items.iterator()
    override fun equals(other: Any?) = other is ArrayNode && other.items == items
    override fun hashCode() = items.hashCode()
}

class ObjectNode(private val fields: LinkedHashMap<String, JsonNode> = linkedMapOf()) : JsonNode() {
    override val isObject get() = true
    override fun size() = fields.size
    override fun get(key: String) = fields[key]
    override fun has(key: String) = fields.containsKey(key)
    fun <T : JsonNode> set(key: String, value: JsonNode): T { fields[key] = value; @Suppress("UNCHECKED_CAST") return this as T }
    fun put(key: String, value: String): ObjectNode = apply { fields[key] = TextNode(value) }
    fun properties(): Set<Map.Entry<String, JsonNode>> = fields.entries
    fun fieldNames(): Iterator<String> = fields.keys.iterator()
    override fun iterator() = fields.values.iterator()
    override fun equals(other: Any?) = other is ObjectNode && other.fields == fields
    override fun hashCode() = fields.hashCode()
}

object JsonNodeFactory {
    val instance = this
    fun objectNode() = ObjectNode()
    fun arrayNode() = ArrayNode()
    fun textNode(text: String) = TextNode(text)
    fun numberNode(value: Long): JsonNode = LongNode(value)
    fun numberNode(value: BigInteger): JsonNode = BigIntegerNode(value)
    fun numberNode(value: Double): JsonNode = DoubleNode(value)
    fun rawValueNode(raw: String): JsonNode = RawNode(raw)
}

/** A temporal value a host that has no temporal types of its own gives as a parameter: its kind and ISO 8601 text. */
data class TemporalValue(val kind: org.drivine.cypher.sql.Temporal, val text: String)

/**
 * [text] as the JVM's `Double.parseDouble` reads it, null where that throws: the JVM's own, and elsewhere
 * [org.drivine.cypher.number.DoubleParse], as Kotlin/Wasm's `String.toDouble` is an ulp off for about one double in 400.
 */
expect fun parseDoubleOrNull(text: String): Double?

/**
 * [text] as the JVM's `Double.parseDouble` reads it, and the `NumberFormatException` it throws: of the text trimmed, or of
 * a second point among the digits that open a decimal (after its sign), where Java stops reading.
 */
fun parseDouble(text: String): Double = parseDoubleOrNull(text) ?: throw NumberFormatException(
    text.trim { it <= ' ' }.let { trimmed ->
        val unsigned = trimmed.removePrefix("+").let { if (it == trimmed) trimmed.removePrefix("-") else it }
        when {
            trimmed.isEmpty() -> "empty String"
            !unsigned.startsWith("0x", ignoreCase = true) && unsigned.takeWhile { it in '0'..'9' || it == '.' }.count { it == '.' } > 1 -> "multiple points"
            else -> "For input string: \"$trimmed\""
        }
    },
)

/** The platform's tree of a value of one of its own types (java.time, `BigDecimal`, an enum…); null for any other. */
expect fun platformTree(value: Any): JsonNode?

/** The kind of temporal value [value] is, where it is one of the platform's own temporal types. */
expect fun platformTemporal(value: Any): org.drivine.cypher.sql.Temporal?

/**
 * [value] as the JVM's `Double.toString` writes it, as Jackson writes a double: the JVM's own, and elsewhere
 * [org.drivine.cypher.number.DoubleText].
 */
expect fun jvmDoubleText(value: Double): String

/**
 * [value] as the JVM's `Float.toString` writes it. Only a JVM host binds a `Float`: a host that gives its parameters as
 * JSON gives doubles, so elsewhere this is the platform's own text, unchecked against the JVM's.
 */
expect fun jvmFloatText(value: Float): String

/**
 * The most levels a request's arrays and objects, or a value's lists and maps, nest, the outermost the first. Far past any
 * real request, and short of a small thread's stack: read and written recursively, a deeper one would overflow it, which
 * on native ends the host's process. Each level costs the native library about 1 KiB of stack, and its debug build some
 * 4 KiB, so 64 levels fit a Kotlin/Native Worker's 512 KiB on macOS, and musl's 128 KiB, where 256 don't.
 * A request's own object and its `parameters` are two of the levels.
 */
const val MAX_DEPTH = 64

/** The SHA-256 digest of [bytes]. */
fun sha256(bytes: ByteArray): ByteArray = Sha256.digest(bytes)

/**
 * SPIKE: what drivine4j's `CypherJson` did for the compiler: a value as a JSON tree, and a tree or a value as
 * compact JSON text, as Jackson writes it.
 */
object CypherJson {
    /** A host's own conversion, tried before the built-in one: drivine4j would hand its Jackson mapper's here. */
    var encoder: ((Any?) -> JsonNode?)? = null

    @OptIn(kotlin.io.encoding.ExperimentalEncodingApi::class)
    fun valueToTree(value: Any?): JsonNode {
        encoder?.invoke(value)?.let { return it }
        return when (value) {
            null -> NullNode
            is JsonNode -> value
            is String -> TextNode(value)
            is Boolean -> BooleanNode(value)
            is Int -> LongNode(value.toLong())
            is Long -> LongNode(value)
            is Short -> LongNode(value.toLong())
            is Byte -> LongNode(value.toLong())
            is Double -> DoubleNode(value)
            is Float -> FloatNode(value)
            is BigInteger -> BigIntegerNode(value)
            is BigDecimal -> DecimalNode(value, value.toPlainString(), value.toStringExpanded())
            is Char -> TextNode(value.toString())
            is TemporalValue -> TextNode(value.text)
            is Map<*, *> -> ObjectNode().apply { value.forEach { (k, v) -> set<JsonNode>(k.toString(), valueToTree(v)) } }
            is Iterable<*> -> ArrayNode().apply { value.forEach { add(valueToTree(it)) } }
            is Array<*> -> ArrayNode().apply { value.forEach { add(valueToTree(it)) } }
            is IntArray -> ArrayNode().apply { value.forEach { add(LongNode(it.toLong())) } }
            is LongArray -> ArrayNode().apply { value.forEach { add(LongNode(it)) } }
            is DoubleArray -> ArrayNode().apply { value.forEach { add(DoubleNode(it)) } }
            is FloatArray -> ArrayNode().apply { value.forEach { add(FloatNode(it)) } }
            is BooleanArray -> ArrayNode().apply { value.forEach { add(BooleanNode(it)) } }
            // As Jackson writes a byte[]: its base64 text.
            is ByteArray -> TextNode(kotlin.io.encoding.Base64.encode(value))
            is Enum<*> -> TextNode(value.name)
            else -> platformTree(value) ?: throw IllegalArgumentException("SPIKE: no JSON for a ${value::class.simpleName}")
        }
    }

    fun writeValueAsString(value: Any?, plainDecimals: Boolean = false): String =
        StringBuilder().also { write(valueToTree(value), it, plainDecimals) }.toString()

    /**
     * [text] as a tree, refused where its arrays and objects nest past [MAX_DEPTH]: before it is parsed, as
     * kotlinx.serialization's parse recurses as deep as the text nests, and overflows the stack.
     */
    fun readTree(text: String): JsonNode {
        nesting(text)
        return fromElement(Json.parseToJsonElement(text))
    }

    /** Refuses [text] where its arrays and objects, outside its strings, nest past [MAX_DEPTH]. The parse finds any other fault. */
    private fun nesting(text: String) {
        var depth = 0
        var inString = false
        var i = 0
        while (i < text.length) {
            val c = text[i++]
            when {
                inString -> if (c == '\\') i++ else if (c == '"') inString = false
                c == '"' -> inString = true
                c == '[' || c == '{' -> require(++depth <= MAX_DEPTH) { "A request nests its arrays and objects at most $MAX_DEPTH deep." }
                c == ']' || c == '}' -> depth--
            }
        }
    }

    private fun fromElement(element: JsonElement): JsonNode = when (element) {
        is JsonNull -> NullNode
        is JsonObject -> ObjectNode().apply { element.forEach { (k, v) -> set<JsonNode>(k, fromElement(v)) } }
        is JsonArray -> ArrayNode().apply { element.forEach { add(fromElement(it)) } }
        is JsonPrimitive -> when {
            element.isString -> TextNode(element.content)
            element.content == "true" -> BooleanNode(true)
            element.content == "false" -> BooleanNode(false)
            // kotlinx.serialization takes any word unquoted; Jackson reads only JSON's numbers (no `1.5f`, `.5`, `01`, `NaN`).
            !JSON_NUMBER.matches(element.content) -> throw IllegalArgumentException("${element.content} is no JSON value.")
            element.content.any { it == '.' || it == 'e' || it == 'E' } -> DoubleNode(parseDouble(element.content))
            else -> element.content.toLongOrNull()?.let(::LongNode) ?: BigIntegerNode(BigInteger.parseString(element.content))
        }
    }

    private val JSON_NUMBER = Regex("-?(0|[1-9][0-9]*)([.][0-9]+)?([eE][+-]?[0-9]+)?")

    private fun write(node: JsonNode, out: StringBuilder, plain: Boolean) {
        when (node) {
            is NullNode -> out.append("null")
            is TextNode -> quote(node.value, out)
            is DoubleNode -> out.append(if (node.value.isFinite()) jvmDoubleText(node.value) else "\"${jvmDoubleText(node.value)}\"")
            is FloatNode -> out.append(if (node.value.isFinite()) jvmFloatText(node.value) else "\"${jvmFloatText(node.value)}\"")
            is DecimalNode -> out.append(if (plain) node.plain else node.scientific)
            is RawNode -> out.append(node.raw)
            is ArrayNode -> {
                out.append('[')
                node.forEachIndexed { i, item -> if (i > 0) out.append(','); write(item, out, plain) }
                out.append(']')
            }
            is ObjectNode -> {
                out.append('{')
                node.properties().forEachIndexed { i, (k, v) -> if (i > 0) out.append(','); quote(k, out); out.append(':'); write(v, out, plain) }
                out.append('}')
            }
            else -> out.append(node.asText())
        }
    }

    /** [text] quoted as Jackson quotes it: `"` and `\` escaped, control characters as `\n` and the like or `\u00XX`. */
    private fun quote(text: String, out: StringBuilder) {
        out.append('"')
        for (c in text) when (c) {
            '"' -> out.append("\\\"")
            '\\' -> out.append("\\\\")
            '\b' -> out.append("\\b")
            '\t' -> out.append("\\t")
            '\n' -> out.append("\\n")
            '\u000C' -> out.append("\\f")
            '\r' -> out.append("\\r")
            else -> if (c < ' ') out.append("\\u00").append(c.code.toString(16).uppercase().padStart(2, '0')) else out.append(c)
        }
        out.append('"')
    }
}

