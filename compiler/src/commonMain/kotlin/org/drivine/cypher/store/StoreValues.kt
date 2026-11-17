package org.drivine.cypher.store

import com.ionspin.kotlin.bignum.decimal.BigDecimal
import org.drivine.DrivineInternalApi
import org.drivine.cypher.CompilerException
import org.drivine.cypher.api.Store
import org.drivine.cypher.json.ArrayNode
import org.drivine.cypher.json.CypherEncoder
import org.drivine.cypher.json.CypherJson
import org.drivine.cypher.json.DecimalNode
import org.drivine.cypher.json.DoubleNode
import org.drivine.cypher.json.FloatNode
import org.drivine.cypher.json.JsonNode
import org.drivine.cypher.json.JsonNodeFactory
import org.drivine.cypher.json.ObjectNode
import org.drivine.cypher.json.jvmDoubleText
import org.drivine.cypher.sql.PostgresCypherCompiler
import org.drivine.cypher.sql.formatted

/**
 * A value as each SQL store's layout holds it, the JSON text a write binds (liberation-data/drivine-cypher-spec#329): what a
 * compiled statement that writes binds a parameter as, each compiler's `parameterText`, which calls this, and so what a
 * library's save writes, so a value saved and a value made by `CREATE` are held alike and read back alike.
 *
 * Every value is first [CypherJson.valueToTree]'s: each temporal value its tag, each map key that begins with `$` escaped,
 * bytes the list of their signed values (`[1, 2, -1]`), as a graph engine holds them. Then, by store:
 *
 * - **Postgres**: each whole float written out with a fraction, `10000000.0`, which `jsonb` keeps a float. `jsonb` holds
 *   no NaN and no infinity: a value that holds one is refused by name, as a statement that writes is.
 * - **Oracle**: each float as extended JSON's double, `{"$numberDouble": "1.5"}`, NaN and the infinities too (`"NaN"`,
 *   `"Infinity"`, `"-Infinity"`), which the store reads as a binary double where it parses the text `EXTENDED`. A value
 *   that holds a map with a key that begins with one `$`, which only a host's encoder gives, is refused: extended JSON
 *   would read it as another value.
 * - **SQLite**: an infinity as the number SQLite's JSON writes for one, `9e999` or `-9e999`. SQLite's JSON holds no NaN: a
 *   value that holds one is refused by name, as a statement that writes is.
 */
@DrivineInternalApi
object StoreValues {

    /**
     * [value] as [store]'s layout holds it, its JSON text: exactly what a compiled statement that writes binds it as. A value
     * the store can't hold is refused, with a [CompilerException] whose message is the one a statement that writes is
     * refused with. [encoder] is asked first of each value, as a compiler's is (see [CypherEncoder]).
     */
    fun text(value: Any?, store: Store, encoder: CypherEncoder? = null): String {
        val tree = CypherJson.valueToTree(value, encoder)
        nonFiniteRefusal(store, tree)?.let { throw CompilerException(it) }
        return when (store) {
            Store.POSTGRES -> postgres(tree, encoder)
            Store.SQLITE -> sqlite(tree, encoder)
            Store.ORACLE -> {
                if (keyedAsExtended(tree)) throw CompilerException(KEYED_AS_EXTENDED)
                CypherJson.writeValueAsString(oracleDoubles(tree) {}, encoder = encoder)
            }
        }
    }

    /**
     * Why [store]'s layout can't hold [value], the message [text] refuses it with; null where it holds it. A migration
     * keeps such a value as it was, naming it (see [org.drivine.cypher.store.migrate.LayoutMigration]).
     */
    fun refusal(value: Any?, store: Store): String? = try {
        text(value, store)
        null
    } catch (e: CompilerException) {
        e.message
    }

    /** Why a statement that writes is refused a float, the first named, that the store's JSON, the second, holds as no number. */
    internal const val NON_FINITE_WRITE = "A statement that writes is given %s, which %s holds as no number: it would store another value in its place."

    /** Why Oracle refuses a value that holds a map with a key as extended JSON's are: see [text]. */
    internal const val KEYED_AS_EXTENDED =
        "A value written on Oracle holds a map with a key that begins with one \$, which Oracle's extended JSON would read as another value: a map's key that begins with \$ is held with one more."

    /** Extended JSON's key of a double, as Oracle reads one: `{"$numberDouble": "1.5"}`. */
    internal const val DOUBLE = "\$numberDouble"

    /**
     * Why [store] refuses to write [value], a value or its tree, for a float in it, at any depth, that its JSON holds as no
     * number: Postgres's NaN and the infinities, SQLite's NaN. Null where it holds them all, as Oracle does.
     */
    internal fun nonFiniteRefusal(store: Store, value: Any?): String? = when (store) {
        Store.POSTGRES -> if (holdsNonFinite(value)) NON_FINITE_WRITE.formatted("NaN or an infinity", "Postgres's `jsonb`") else null
        Store.SQLITE -> if (holdsNonFinite(value, Double::isNaN)) NON_FINITE_WRITE.formatted("NaN", "SQLite's JSON") else null
        Store.ORACLE -> null
    }

    /**
     * Whether [value], a value as the caller gave it or its tree, is or holds a float that is [nonFinite], at any depth: by
     * default NaN or an infinity.
     */
    internal fun holdsNonFinite(value: Any?, nonFinite: (Double) -> Boolean = { !it.isFinite() }): Boolean = when (value) {
        is Double -> nonFinite(value)
        is Float -> nonFinite(value.toDouble())
        is DoubleNode -> nonFinite(value.value)
        is FloatNode -> nonFinite(value.value.toDouble())
        is Map<*, *> -> value.values.any { holdsNonFinite(it, nonFinite) }
        // A tree's array and object too, each an Iterable of its items.
        is Iterable<*> -> value.any { holdsNonFinite(it, nonFinite) }
        is Array<*> -> value.any { holdsNonFinite(it, nonFinite) }
        is DoubleArray -> value.any(nonFinite)
        is FloatArray -> value.any { nonFinite(it.toDouble()) }
        else -> false
    }

    /**
     * [value] as Postgres's JSON text, each float in it that is a whole number written out in full with a fraction, `10000000.0`:
     * as Java writes it, `1.0E7`, `jsonb` reads it as the whole number 10000000, which is then read back as an integer. NaN and
     * the infinities are the numbers a statement given one reads them as (see [PostgresCypherCompiler]): a statement that
     * writes refuses them first.
     */
    internal fun postgres(value: Any?, encoder: CypherEncoder?): String =
        CypherJson.writeValueAsString(wholeFloats(CypherJson.valueToTree(value, encoder)), plainDecimals = true, encoder = encoder)

    /**
     * [node] with each float in it that is a whole number a decimal of one place, `10000000.0`, from its shortest decimal: the
     * double's exact value, `999999999999999983222784.0` for `1e24`, is no number Postgres works out or reads as that float. NaN
     * and the infinities are the numbers that stand for them: see [PostgresCypherCompiler]. A new tree: [node], which may be a
     * host's own, given again as a retry gives it, is left as it was.
     */
    private fun wholeFloats(node: JsonNode): JsonNode = when {
        (node.isDouble || node.isFloat) && !node.doubleValue().isFinite() ->
            DecimalNode(PostgresCypherCompiler.standIn(node.doubleValue()), PostgresCypherCompiler.standInText(node.doubleValue()))
        (node.isDouble || node.isFloat) && node.doubleValue().let { it.isFinite() && it == kotlin.math.round(it) } ->
            PostgresCypherCompiler.wholePlain(jvmDoubleText(node.doubleValue())).let { DecimalNode(BigDecimal.parseString(it), it) }
        node is ObjectNode -> ObjectNode().apply { node.properties().forEach { (key, value) -> set<JsonNode>(key, wholeFloats(value)) } }
        node is ArrayNode -> ArrayNode().apply { node.forEach { add(wholeFloats(it)) } }
        else -> node
    }

    /**
     * [value] as SQLite's JSON text: JSON, an infinity as the number SQLite writes for one and NaN as
     * [SqliteCypherValues.NAN], as a statement given one reads it. A statement that writes refuses NaN first.
     */
    internal fun sqlite(value: Any?, encoder: CypherEncoder?): String =
        if (!holdsNonFinite(value)) CypherJson.writeValueAsString(value, encoder = encoder)
        else SqliteCypherValues.write(CypherJson.valueToTree(value, encoder))

    /**
     * Whether [node] holds a map with a key that starts with one `$`, as extended JSON's keys do. A user's key that starts
     * with `$` is held with one more ([ValueLayout.escape]), which Oracle's extended JSON reads as the key it is, and a
     * temporal value's tag's `$t` is no key extended JSON reads either, as Oracle reads tags back through it, so in layout 2
     * no value holds one (drivine-cypher-spec#202): this is for a host's encoder that gives a tree with one of its own.
     */
    internal fun keyedAsExtended(node: JsonNode): Boolean =
        (node is ObjectNode && !ValueLayout.isTag(node) && node.fieldNames().asSequence().any { it.startsWith("$") && !it.startsWith("$$") }) ||
            node.any(::keyedAsExtended)

    /**
     * [node] with each float in it written as extended JSON's double, `{"$numberDouble": "1.5"}`, its text as Java writes
     * it (`"NaN"`, `"Infinity"` and `"-Infinity"` among them), calling [doubled] for each. Every float, as the store layout
     * holds one (#146): a binary double, which `JSON(… EXTENDED)` reads of extended JSON's double, and not of JSON's `1.5`,
     * which it reads as a `NUMBER`. A `BigDecimal` is no float: it stays the number it is, every digit kept. A new tree, as
     * [wholeFloats] makes: a tree of the host's, read again, would otherwise hold extended JSON's keys.
     */
    internal fun oracleDoubles(node: JsonNode, doubled: () -> Unit): JsonNode = when {
        node.isDouble || node.isFloat -> {
            doubled()
            JsonNodeFactory.instance.objectNode().put(DOUBLE, jvmDoubleText(node.doubleValue()))
        }
        node is ObjectNode -> ObjectNode().apply { node.properties().forEach { (key, value) -> set<JsonNode>(key, oracleDoubles(value, doubled)) } }
        node is ArrayNode -> ArrayNode().apply { node.forEach { add(oracleDoubles(it, doubled)) } }
        else -> node
    }
}
