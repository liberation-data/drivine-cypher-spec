package org.drivine.cypher.store.migrate

import com.ionspin.kotlin.bignum.decimal.BigDecimal
import com.ionspin.kotlin.bignum.integer.BigInteger
import org.drivine.DrivineInternalApi
import org.drivine.cypher.CompilerException
import org.drivine.cypher.api.Store
import org.drivine.cypher.json.ArrayNode
import org.drivine.cypher.json.BigIntegerNode
import org.drivine.cypher.json.BooleanNode
import org.drivine.cypher.json.NullNode
import org.drivine.cypher.json.TextNode
import org.drivine.cypher.json.CypherJson
import org.drivine.cypher.json.DecimalNode
import org.drivine.cypher.json.DoubleNode
import org.drivine.cypher.json.JsonNode
import org.drivine.cypher.json.LongNode
import org.drivine.cypher.json.ObjectNode
import org.drivine.cypher.json.TemporalValue
import org.drivine.cypher.json.parseDoubleOrNull
import org.drivine.cypher.sql.Temporal
import org.drivine.cypher.sql.Temporals
import org.drivine.cypher.store.StoreValues
import org.drivine.cypher.store.ValueLayout
import kotlin.io.encoding.Base64

/**
 * The rewrite of a node's or a relationship's `props` from store layout 1 to layout 2 (liberation-data/drivine-cypher-spec#256):
 * one row at a time, the JSON text the store gives of it in, the text layout 2 holds out, so every library migrates a row
 * alike, as `migrate.yaml`'s `rewrites` pin it on every target.
 *
 * Layout 1 held a value as the store's JSON alone: a temporal value as its ISO text, bytes as base64 text where a library
 * wrote them so, and on Oracle every float that a save wrote as a `NUMBER`. So which texts were temporal values, and which
 * whole numbers floats, is lost, and a migration is told it, property by property ([Declaration]), as a library knows it
 * from its model; it is never guessed (#161 refused a guess: Neo4j orders a text shaped like a date as a text). What no
 * declaration is needed for is rewritten for every property:
 * - a property whose name begins with `$` is held with one more ([ValueLayout.escape]);
 * - on Oracle, a `NUMBER` with a fraction, which no integer has, is a float, a binary double in layout 2.
 *
 * The row is read into the values it holds and written as [StoreValues.text] writes them for the store, the text a
 * layout-2 save or `CREATE` writes, so a row migrated is held exactly as the same values written anew.
 *
 * What each store gives as a row's text, and reads back: Postgres its `jsonb`'s text (`props::text`), SQLite its text,
 * Oracle `JSON_SERIALIZE(props EXTENDED)`, whose binary doubles are extended JSON's `{"$numberDouble": …}`, and whose
 * rewrite is read back with `JSON(… EXTENDED)`.
 */
@DrivineInternalApi
object LayoutMigration {

    /** The layout this rewrites from. */
    const val FROM = 1

    /** The layout this rewrites to: [ValueLayout.LAYOUT]. */
    const val TO = 2

    /** What a property is declared to hold, which layout 1 can't tell: a temporal kind, a float, or bytes. */
    enum class Kind(val word: String, val temporal: Temporal? = null) {
        DATE("date", Temporal.DATE),
        LOCAL_TIME("localtime", Temporal.LOCAL_TIME),
        TIME("time", Temporal.TIME),
        LOCAL_DATE_TIME("localdatetime", Temporal.LOCAL_DATE_TIME),
        DATE_TIME("datetime", Temporal.DATE_TIME),
        DURATION("duration", Temporal.DURATION),
        FLOAT("float"),
        BYTES("bytes");

        companion object {
            /** The kind [word] names, or null. */
            fun of(word: String): Kind? = entries.firstOrNull { it.word == word }
        }
    }

    /**
     * That the property [property] of the nodes of the label [label], or of the relationships of that type, holds values of
     * [kind], or lists of them. [property] is the user's name, as a model names it: `$x`, not `$$x`.
     */
    data class Declaration(val label: String, val property: String, val kind: Kind)

    /** What a rewrite changed of a property, as a report counts them. */
    enum class Change(val word: String) {
        /** Its name began with `$`, and is held with one more. */
        ESCAPED_KEY("escapedKey"),

        /** An Oracle `NUMBER` with a fraction, a float, made a binary double. */
        DOUBLE("double"),

        /** A text declared temporal made its tag. */
        TEMPORAL("temporal"),

        /** A whole number declared a float made one. */
        FLOAT("float"),

        /** Bytes declared, as base64 text or a list of unsigned bytes, made the list of their signed values. */
        BYTES("bytes"),
    }

    /** That [change] was made to the property [property], as the user names it. */
    data class Changed(val property: String, val change: Change)

    /**
     * A value of the property [property], [value] as the row's JSON holds it, that is not of the kind it is declared
     * ([kind]), and why ([message]). It is kept as it was in the rewrite, which a migration refuses to write unless it is
     * told to keep such values (`--keep-invalid-as-text`), naming each.
     */
    data class Invalid(val property: String, val kind: Kind, val value: String, val message: String)

    /**
     * A row rewritten: [props], the JSON text layout 2 holds it as; [changes], what was changed, property by property, in
     * the row's order, empty where nothing was, so the row needs no write; and [invalid], each value that is not of its
     * declared kind, kept as it was.
     */
    data class Rewrite(val props: String, val changes: List<Changed>, val invalid: List<Invalid>) {
        /** Whether the row is to be written: whether anything was changed. */
        val changed: Boolean get() = changes.isNotEmpty()
    }

    /**
     * Why [declarations] can't be used: two that declare one label's property two kinds. Null where they can. A
     * declaration of a property under two labels may give two kinds; a row of both labels then can't be migrated (see
     * [rewrite]).
     */
    fun refusal(declarations: List<Declaration>): String? {
        val kinds = declarations.groupBy { it.label to it.property }.filterValues { list -> list.map { it.kind }.distinct().size > 1 }
        val (key, list) = kinds.entries.firstOrNull() ?: return null
        return "The property ${key.second} of ${key.first} is declared ${list.map { it.kind.word }.distinct().joinToString(" and ")}: declare one kind."
    }

    /**
     * The row [props], the JSON text [store] gives of a layout-1 node's or relationship's properties, as layout 2 holds it,
     * for a node of [labels], or a relationship whose type is the one of them, under [declarations].
     *
     * A value of a property declared of a kind is made that kind's, or listed [Rewrite.invalid] and kept as it was where it
     * is not one; a list is made a list of the kind's values, item by item, and is invalid whole where any item is. A
     * temporal kind's value is its text, which must be a text of that kind as ISO 8601 writes it (`2024-03-15`,
     * `10:30`, `2024-03-15T10:30:00.123+02:00`, `P1DT2H`), a space in place of the `T` between a date and its time too, and
     * a named zone after an offset (`+01:00[Europe/Paris]`) dropped, as layout 2 holds none; it is held canonical, its tag
     * worked out of it. A float's is a number. Bytes are base64 text, or a list of whole numbers from -128 to 255, those
     * from 128 read as unsigned. A value a store can't hold in layout 2, NaN on Postgres say, is invalid too, with the
     * message a write is refused with. A row of two labels that declare one property two kinds has it invalid.
     */
    fun rewrite(props: String, store: Store, labels: List<String>, declarations: List<Declaration>): Rewrite {
        val tree = CypherJson.readTree(props) as? ObjectNode
            ?: throw IllegalArgumentException("A row's properties are a JSON object, not $props.")
        val changes = mutableListOf<Changed>()
        val invalid = mutableListOf<Invalid>()
        val values = LinkedHashMap<String, Any?>()
        for ((name, node) in tree.properties()) {
            if (name.startsWith('$')) changes += Changed(name, Change.ESCAPED_KEY)
            val declared = declarations.filter { it.property == name && it.label in labels }.map { it.kind }.distinct()
            val kind = declared.singleOrNull()
            if (declared.size > 1) {
                invalid += Invalid(name, declared.first(), CypherJson.writeValueAsString(node),
                    "A node of the labels ${labels.joinToString()} has ${name} declared ${declared.joinToString(" and ") { it.word }}: it is kept as it was.")
                values[name] = plain(node, store, name, changes)
                continue
            }
            if (kind == null) {
                values[name] = plain(node, store, name, changes)
                continue
            }
            val made = try {
                declaredValue(node, kind, store)
            } catch (e: Kept) {
                invalid += Invalid(name, kind, CypherJson.writeValueAsString(node), e.message!!)
                values[name] = plain(node, store, name, changes)
                continue
            }
            // A value of its kind already, as a list of signed bytes is, or a float a binary double, is changed in nothing; an
            // Oracle NUMBER with a fraction declared a float is the change any such NUMBER is (Change.DOUBLE).
            val was = plain(node, store, name, changes)
            if (!same(was, made.value)) changes += Changed(name, made.change)
            values[name] = made.value
            // A value the store can't hold in layout 2 is kept as it was, as invalid.
            StoreValues.refusal(made.value, store)?.let { message ->
                changes.removeAll { it.property == name && it.change != Change.ESCAPED_KEY }
                invalid += Invalid(name, kind, CypherJson.writeValueAsString(node), message)
                values[name] = was
            }
        }
        return Rewrite(StoreValues.text(values, store), changes, invalid)
    }

    /** A declared value made, and what it changed. */
    private class Made(val value: Any?, val change: Change)

    /** Why a declared value is kept as it was: see [Invalid]. */
    private class Kept(message: String) : Exception(message)

    /** [node], the value of a property declared [kind], as a value of that kind, or a list of them. */
    private fun declaredValue(node: JsonNode, kind: Kind, store: Store): Made = when {
        kind == Kind.BYTES -> Made(bytes(node), Change.BYTES)
        node is ArrayNode -> Made(node.map { item -> one(item, kind, store) }, change(kind))
        else -> Made(one(node, kind, store), change(kind))
    }

    private fun change(kind: Kind) = if (kind.temporal != null) Change.TEMPORAL else Change.FLOAT

    /** One item, [node], of a property declared [kind], a temporal kind or a float. */
    private fun one(node: JsonNode, kind: Kind, store: Store): Any? {
        val temporal = kind.temporal
        if (temporal != null) {
            val text = node.takeIf { it.isTextual }?.textValue() ?: throw Kept("${CypherJson.writeValueAsString(node)} is no text, so no ${kind.word}.")
            return TemporalValue(temporal, canonical(text, temporal, kind))
        }
        // A float: a number of any form, or Oracle's double; NaN and the infinities as the texts a save of layout 1 wrote.
        when (val extended = oracleExtended(node, store)) {
            null -> {}
            is Double -> return extended
            is Long -> return extended.toDouble()
            is BigInteger -> return extended.doubleValue(exactRequired = false)
            is BigDecimal -> return extended.doubleValue(exactRequired = false)
        }
        return when {
            node is LongNode -> node.value.toDouble()
            node is BigIntegerNode -> node.value.doubleValue(exactRequired = false)
            node is DoubleNode -> node.value
            node is DecimalNode -> node.value.doubleValue(exactRequired = false)
            node.isTextual && node.textValue() in NON_FINITE -> NON_FINITE[node.textValue()]
            else -> throw Kept("${CypherJson.writeValueAsString(node)} is no number, so no float.")
        }
    }

    /** The texts a layout-1 save wrote NaN and the infinities as. */
    private val NON_FINITE = mapOf("NaN" to Double.NaN, "Infinity" to Double.POSITIVE_INFINITY, "-Infinity" to Double.NEGATIVE_INFINITY)

    /** A named zone after an offset (`…+01:00[Europe/Paris]`), the text before it in the group; or after none. */
    private val NAMED_ZONE = Regex("""^(.*?)\[[A-Za-z0-9_+\-/]+]$""")

    /** A date and its time apart by a space, as Python's `str` writes a moment: the two in the groups. */
    private val SPACED = Regex("""^(-?[0-9]{4}-[0-9]{2}-[0-9]{2}) ([0-9]{2}:.*)$""")

    /** The canonical text of [kind] of [text], a text of that kind: see [rewrite]. */
    private fun canonical(text: String, kind: Temporal, declared: Kind): String {
        var given = text
        NAMED_ZONE.find(given)?.let { found ->
            val before = found.groupValues[1]
            if (!Temporal.OFFSET_REGEX.containsMatchIn(before)) {
                throw Kept("'$text' names a zone without its offset, which layout 2 can't hold: give the offset, as in 2024-03-15T10:30+01:00.")
            }
            given = before
        }
        SPACED.find(given)?.let { given = "${it.groupValues[1]}T${it.groupValues[2]}" }
        val found = if (kind == Temporal.DURATION) Temporal.DURATION.takeIf { it.regex.matches(given) }
        else Temporal.instants.firstOrNull { it.regex.matches(given) }
        if (found == null) throw Kept("'$text' is no ${declared.word}.")
        if (found != kind) throw Kept("'$text' is a ${found.function}, not a ${declared.word}.")
        val canonical = try {
            Temporals.canonical(given, kind)
        } catch (e: Exception) {
            throw Kept("'$text' is no ${declared.word}: ${e.message}")
        }
        return canonical ?: throw Kept("'$text' is no ${declared.word}.")
    }

    /** Bytes declared, [node]: see [rewrite]. */
    private fun bytes(node: JsonNode): ByteArray = when {
        node.isTextual -> try {
            Base64.decode(node.textValue()!!)
        } catch (e: IllegalArgumentException) {
            throw Kept("'${node.textValue()}' is no base64 text, so no bytes.")
        }
        node is ArrayNode -> ByteArray(node.size()) { i ->
            val item = node[i]
            val n = (item as? LongNode)?.value ?: throw Kept("${CypherJson.writeValueAsString(node)} holds ${CypherJson.writeValueAsString(item)}, which is no byte.")
            if (n !in -128L..255L) throw Kept("${CypherJson.writeValueAsString(node)} holds $n, which is no byte.")
            n.toInt().toByte()
        }
        else -> throw Kept("${CypherJson.writeValueAsString(node)} is no base64 text or list of bytes.")
    }

    /**
     * [node], a value of a property [name] declared nothing, as a value, rewritten as needs no declaration: on Oracle, a
     * `NUMBER` with a fraction a float ([Change.DOUBLE]), and an extended JSON double a float.
     */
    private fun plain(node: JsonNode, store: Store, name: String, changes: MutableList<Changed>): Any? {
        oracleExtended(node, store)?.let { return it }
        return when {
            node is ArrayNode -> node.map { plain(it, store, name, changes) }
            node is ObjectNode -> LinkedHashMap<String, Any?>().apply { node.properties().forEach { (k, v) -> put(k, plain(v, store, name, changes)) } }
            node is DoubleNode && store == Store.ORACLE -> {
                // Oracle's JSON_SERIALIZE writes a NUMBER as JSON's number: one with a fraction is a float, as no integer has one.
                if (node.value != kotlin.math.floor(node.value) && changes.none { it.property == name && it.change == Change.DOUBLE }) changes += Changed(name, Change.DOUBLE)
                if (node.value == kotlin.math.floor(node.value)) BigInteger.parseString(org.drivine.cypher.time.CommonTime.exactWhole(node.value)).let { if (it.bitLength() < 64) it.longValue(exactRequired = true) else it } else node.value
            }
            node is DoubleNode -> node.value
            node is LongNode -> node.value
            node is BigIntegerNode -> node.value
            node is DecimalNode -> node.value
            node is BooleanNode -> node.value
            node is TextNode -> node.value
            node is NullNode -> null
            else -> throw IllegalArgumentException("A row holds ${CypherJson.writeValueAsString(node)}, which is no value.")
        }
    }

    /**
     * On Oracle, the value of [node] where it is one of extended JSON's numbers, `{"$numberDouble": …}` (a binary double,
     * a [Double]), `{"$numberFloat": …}`, `{"$numberLong": …}` or `{"$numberDecimal": …}`; null for any other node. An
     * object of one other key of Oracle's extended JSON (`$oracleDate` …), which layout 1 never wrote, is refused.
     */
    private fun oracleExtended(node: JsonNode, store: Store): Any? {
        if (store != Store.ORACLE || node !is ObjectNode || node.size() != 1) return null
        val (key, value) = node.properties().single()
        val text = if (value.isTextual) value.textValue()!! else CypherJson.writeValueAsString(value)
        return when (key) {
            "\$numberDouble", "\$numberFloat" -> when (text) {
                "NaN", "Nan" -> Double.NaN
                "Infinity", "Inf" -> Double.POSITIVE_INFINITY
                "-Infinity", "-Inf" -> Double.NEGATIVE_INFINITY
                else -> parseDoubleOrNull(text) ?: throw IllegalArgumentException("Oracle's $key of '$text' is no number.")
            }
            "\$numberLong" -> text.toLongOrNull() ?: BigInteger.parseString(text)
            "\$numberDecimal" -> BigDecimal.parseString(text)
            else -> if (key.startsWith("\$oracle")) throw IllegalArgumentException("Oracle's $key in a row of layout 1, which never wrote one.") else null
        }
    }

    /** Whether two values, a row's before and after, are one: of one kind and equal, a float to a float, at any depth. */
    private fun same(a: Any?, b: Any?): Boolean = when {
        a is Double && b is Double -> a == b || (a.isNaN() && b.isNaN())
        a is List<*> && b is List<*> -> a.size == b.size && a.indices.all { same(a[it], b[it]) }
        a is List<*> && b is ByteArray -> a.size == b.size && a.indices.all { (a[it] as? Long) == b[it].toLong() }
        else -> a == b && a != null && b != null && a::class == b::class
    }
}
