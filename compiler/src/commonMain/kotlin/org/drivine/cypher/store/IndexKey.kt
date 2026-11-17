package org.drivine.cypher.store

import org.drivine.DrivineInternalApi
import org.drivine.cypher.api.Store
import org.drivine.cypher.json.JsonNode
import org.drivine.cypher.json.LongNode
import org.drivine.cypher.sql.Temporals
import org.drivine.cypher.text.JavaText

/**
 * What a range index or a uniqueness constraint of the store layout 2 is made over a property by, and what a lookup by
 * that property must compare with it, in one place: the compiler's keyed MATCH reads it here, and [column] gives the
 * expressions the index's DDL is made over (liberation-data/drivine-cypher-spec#286).
 *
 * On Oracle and SQLite the index holds a property's class and key ([column]: `OracleSql.indexClass` and `indexKey`,
 * `SqliteSql.indexClass` and `indexKey`). A lookup compares them with the value's own class and key, save that SQLite's
 * lets a list past its key, its JSON text, by its class (`SqliteCypherCompiler.indexedEqualityOfAny`, #354). Where the value is given
 * as the statement compiles, [of] works them out here, so the statement binds the two texts once, as the index's columns
 * hold them: written in the SQL over the bound value, as `OracleSql.valueClass` and `valueKey` write them, Oracle's key of
 * a value of any kind is a `CASE` that reads the value at each branch, and a lookup by id bound it 59 times (#286).
 * [of] gives null for a value it doesn't work out: the SQL over the value is then compared, as before.
 *
 * On Postgres the index is over the property's JSON itself (`PostgresSql.indexed`), and a value compares as its JSON text.
 *
 * The keys [of] gives are pinned, value by value, in `store.yaml`'s `indexKeys`, which the libraries check against what
 * each store's own [column] gives a stored value (drift guard).
 */
@DrivineInternalApi
object IndexKey {

    /** A value's class and key, as an index of [IndexKey] holds a property of that value. */
    data class Key(val kind: String, val key: String)

    /**
     * The class and key of the JSON [value] on [store], as its index holds a property of that value, or null where this
     * doesn't work them out and the store's SQL over the value is compared instead:
     * - a temporal value's tag: its kind's word and its sort key `s`, or its text `v` where it has none (a duration);
     * - a text of 1 to [SHORT_TEXT] bytes: `string` on Oracle, as `type()` names it, `text` on SQLite, and the text;
     * - a truth value: `boolean`, and `true` or `false` on Oracle, `1` or `0` on SQLite, as `->>` reads one;
     * - a whole number held as a long: `number`, and its decimal digits, as `TO_CHAR(…, 'TM9')` writes an integer.
     *
     * SQLite's index holds the key of a number or a truth value as an integer, as `->>` reads it: its digits here.
     *
     * A float, a decimal, a list, a map, null, an empty text and one past [SHORT_TEXT] bytes give null: Oracle's index holds no
     * key of such a text, and SQLite's is left to its SQL alike. Postgres compares a value's JSON, so it gives null too.
     */
    fun of(store: Store, value: JsonNode): Key? {
        if (store == Store.POSTGRES) return null
        if (ValueLayout.isTag(value)) {
            val kind = value[ValueLayout.KIND]!!.textValue()!!
            val text = value[ValueLayout.TEXT]!!.textValue()!!
            // As the tag is written at rest (ValueLayout.tag): its key worked out of its text where it doesn't carry one, and
            // its text where it has none, a duration's. A text the key can't be worked out of is left to the SQL.
            val temporal = ValueLayout.kindOf(kind) ?: return null
            val key = value[ValueLayout.KEY]?.textValue()
                ?: (if (temporal == org.drivine.cypher.sql.Temporal.DURATION) text else runCatching { Temporals.key(temporal, text) }.getOrNull())
                ?: return null
            return Key(kind, key)
        }
        return when {
            value.isTextual -> value.textValue()!!.takeIf { it.isNotEmpty() && JavaText.utf8(it).size <= SHORT_TEXT }
                ?.let { Key(if (store == Store.ORACLE) "string" else "text", it) }
            value.isBoolean -> Key("boolean", if (store == Store.ORACLE) value.booleanValue().toString() else if (value.booleanValue()) "1" else "0")
            value is LongNode -> Key("number", value.value.toString())
            else -> null
        }
    }

    /**
     * What the index of [store] holds of the property [key] of the node [alias]: its class and its key on Oracle and SQLite,
     * its JSON on Postgres. A key that begins with `$` is the stored one, as [ValueLayout.escape] writes it.
     */
    fun column(store: Store, alias: String, key: String): List<String> = when (store) {
        Store.ORACLE -> (if (alias.isEmpty()) "props" else "$alias.props").let { props -> listOf(OracleSql.indexClass(props, key), OracleSql.indexKey(props, key)) }
        Store.SQLITE -> SqliteSql.column(alias, "props").let { props -> listOf(SqliteSql.indexClass(props, SqliteSql.path(key)), SqliteSql.indexKey(props, SqliteSql.path(key))) }
        Store.POSTGRES -> listOf(PostgresSql.indexed(alias, key))
    }

    /** The longest text, in UTF-8 bytes, a key is worked out of: Oracle's `JSON_VALUE` reads a longer one as null. */
    const val SHORT_TEXT = 4000
}
