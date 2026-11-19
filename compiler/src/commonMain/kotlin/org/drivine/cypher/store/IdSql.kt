package org.drivine.cypher.store

import org.drivine.DrivineInternalApi
import org.drivine.cypher.api.Store
import org.drivine.cypher.json.CypherJson

/**
 * The SQL a library's object manager writes to find a node by its id, and to read it, on a store of layout 2
 * (liberation-data/drivine-cypher-spec#107): each comparison of an id written over the expressions the store's range index and
 * uniqueness constraint are made over ([IndexKey.column]), so the index answers it, and a temporal id's tag is equal only to
 * the same value's. drivine4j wrote these itself, and a copy that drifted from the index's DDL read the whole label, and saved a
 * date id twice as two nodes; TypeScript and Python ask for them through the JSON entry (`idSql`).
 *
 * Each takes [key] as the node holds it, [ValueLayout.escape]d where it begins with `$`. A [Stamps.PROPERTY] key is the stamp's
 * column, not a property, which [keyText], [property] and [hasProperty] read; [hasId], [hasIdIn] and [planKey] refuse it,
 * as no index of an id is made over the stamp.
 *
 * The SQL of a bound value ([hasId]'s `json`, [hasIdIn]'s `parameter`) is written in as given, in the host's own placeholder.
 * On Oracle and SQLite [hasId] writes `json` more than once, so a host binds it by name (`:id`, not `?`).
 */
@DrivineInternalApi
object IdSql {

    /**
     * Whether the property [key] of the node [alias] is the id whose JSON, as the node holds it, is [json], an expression of
     * the store: the property's JSON compared with it on Postgres, and its class and key with the id's on Oracle and SQLite.
     * A null [json] is equal to no id.
     */
    fun hasId(store: Store, alias: String, key: String, json: String): String {
        requireId(key)
        val column = IndexKey.column(store, alias, key)
        return when (store) {
            Store.POSTGRES -> "${column.single()} = CAST($json AS jsonb)"
            Store.ORACLE -> "${column[0]} = ${OracleSql.valueClass(json)} AND ${column[1]} = ${OracleSql.valueKey(json)}"
            Store.SQLITE -> "${column[0]} = ${SqliteSql.valueClass(json)} AND ${column[1]} = ${SqliteSql.valueKey(json)}"
        }
    }

    /**
     * Whether the property [key] of the node [alias] is one of the ids bound as [parameter], a JSON array of each id's JSON
     * text, as [hasId] compares one, so the index answers each. On SQLite, which reads no index for a pair of expressions `IN`
     * a list, each id finds its node of the table [nodes] of [labels] through the index before the node is joined.
     */
    fun hasIdIn(store: Store, alias: String, key: String, parameter: String, nodes: String? = null, labels: Collection<String> = emptyList()): String {
        requireId(key)
        val column = IndexKey.column(store, alias, key)
        return when (store) {
            Store.POSTGRES ->
                "${column.single()} = ANY(ARRAY(SELECT CAST(v.value #>> '{}' AS jsonb) FROM jsonb_array_elements(CAST($parameter AS jsonb)) v))"
            Store.ORACLE -> "(${column[0]}, ${column[1]}) IN " +
                "(SELECT ${OracleSql.valueClass("JSON(i.v)")}, ${OracleSql.valueKey("JSON(i.v)")} FROM " +
                "(SELECT v FROM JSON_TABLE($parameter, '$[*]' COLUMNS (v VARCHAR2(4000) PATH '$'))) i)"
            Store.SQLITE -> {
                requireNotNull(nodes) { "An id in a list on SQLite is found through the node table, which it names." }
                "$alias.nid IN (SELECT f.nid FROM json_each($parameter) v CROSS JOIN $nodes f WHERE " +
                    (listOf(hasId(store, "f", key, "v.value")) + labels.map { SqliteSql.hasLabel("f", it) }).joinToString(" AND ") + ")"
            }
        }
    }

    /**
     * Whether the node [alias] carries every one of [labels], as a lookup by its id tests them beside [hasId]: on Postgres and
     * SQLite each label's test as its partial index is made with ([PostgresSql.hasLabels], [SqliteSql.hasLabels]), so the
     * labelled index answers the lookup; on Oracle each by its text ([OracleSql.carriesLabel]), as the index over the id is
     * the store's own and not a label's, and Oracle, given a label test its index over labels answers, reads every node of the
     * label. Each label is given as the node holds it and written into the SQL escaped, a quote in it as the store's JSON and
     * literal write one. No labels at all is a true condition, `1 = 1`, which each store accepts.
     */
    fun hasLabels(store: Store, alias: String, labels: List<String>): String = if (labels.isEmpty()) "1 = 1" else when (store) {
        Store.POSTGRES -> PostgresSql.hasLabels(alias, labels)
        Store.ORACLE -> OracleSql.hasLabels(alias, labels)
        Store.SQLITE -> SqliteSql.hasLabels(alias, labels)
    }

    /** Refuses the stamp as an id: [IndexKey.column] is made over a property, and Postgres's stamp column is no `jsonb`. */
    private fun requireId(key: String) =
        require(key != Stamps.PROPERTY) { "An id is a property of the node, not its stamp (${Stamps.PROPERTY})." }

    /**
     * The id held under [key] of the node [alias] as text: a temporal value's tag read as its text `v`, anything else as the
     * store reads the property as text. A write plan keys an id by [planKey], which reads an id of any kind but text as this.
     */
    fun keyText(store: Store, alias: String, key: String): String = when (store) {
        Store.POSTGRES -> PostgresSql.searchedProperty(alias, key)
        Store.SQLITE -> SqliteSql.searchedProperty(alias, key)
        Store.ORACLE -> if (key == Stamps.PROPERTY) OracleSql.property(alias, key) else "COALESCE(JSON_VALUE($alias.props, ${OracleSql.path(key, ".${ValueLayout.TEXT}")}), JSON_VALUE($alias.props, ${OracleSql.path(key)}))"
    }

    /**
     * The id held under [key] of the node [alias] as the text a write plan keys it by, for each kind apart
     * (drivine-cypher-spec#391): a text id as [TEXT_MARK] and its text, so the text `'2026-01-05'` keys apart from the date
     * `2026-01-05` and the text `'1'` from the number `1`; any other id as [keyText] reads it, a temporal value's tag as its
     * text `v` and a number or a truth value as the store reads it as text. No two ids of other kinds read alike: a temporal
     * kind's text has its own shape, and a number's is no date's or truth value's. The value side is [planKeyOf]'s.
     * [keyText] is left as it is: the select shorthand and the full-text index expression read it. The stamp is refused, as
     * by [hasId]: it is no id, and [planKeyOf] would key its text marked.
     */
    fun planKey(store: Store, alias: String, key: String): String {
        requireId(key)
        return when (store) {
            Store.POSTGRES -> "CASE WHEN jsonb_typeof(${PostgresSql.value(alias, key)}) = 'string' THEN '$TEXT_MARK' || ${PostgresSql.property(alias, key)} ELSE ${keyText(store, alias, key)} END"
            Store.SQLITE -> "CASE WHEN ${SqliteSql.type(alias, key)} = 'text' THEN '$TEXT_MARK' || ${SqliteSql.property(alias, key)} ELSE ${keyText(store, alias, key)} END"
            Store.ORACLE -> "CASE WHEN JSON_VALUE($alias.props, ${OracleSql.path(key, ".type()")}) = 'string' THEN '$TEXT_MARK' || JSON_VALUE($alias.props, ${OracleSql.path(key)}) ELSE ${keyText(store, alias, key)} END"
        }
    }

    /**
     * The key a write plan keys an id by on [store], as [planKey] reads it back from the node that holds it: [json] is the
     * id's JSON as the store's layout holds it ([StoreValues.text]). A text is [TEXT_MARK] and its text; a temporal value's
     * tag its text `v`; a number on Postgres the text `jsonb` gives back for it, `numeric`'s, with no exponent ([numericText]),
     * on Oracle the plain digits `JSON_VALUE` reads it as, a float held as extended JSON's double too (`3.0` as `3`, `1.0E-7`
     * as `0.0000001`), and on SQLite its JSON as written; a truth value its word. So each library keys its ids as the store
     * reads them, with no rule of its own.
     */
    fun planKeyOf(store: Store, json: String): String {
        val node = CypherJson.readTree(json)
        val double = node.takeIf { store == Store.ORACLE && it.isObject && it.size() == 1 }?.get(StoreValues.DOUBLE)?.textValue()
        return when {
            node.isTextual -> TEXT_MARK + node.textValue()
            ValueLayout.isTag(node) -> node[ValueLayout.TEXT]!!.textValue()!!
            node.isNumber && store == Store.POSTGRES -> numericText(json.trim())
            node.isNumber && store == Store.ORACLE -> plain(numericText(json.trim()))
            double != null -> plain(numericText(double))
            else -> json.trim()
        }
    }

    /** The number [text], as [numericText] writes one, with no zeros past its point, nor the point where none are left. */
    private fun plain(text: String): String = if ('.' !in text) text else text.trimEnd('0').trimEnd('.').let { if (it == "-0") "0" else it }

    /**
     * The JSON number [json] as Postgres's `numeric` writes it (`1.0E-7` as `0.00000010`): its digits with no exponent, as many
     * after the point as were written after it less the exponent, and zero with no sign. Any other JSON is as it is.
     */
    fun numericText(json: String): String {
        val number = Regex("""^(-?)(\d+)(?:\.(\d+))?(?:[eE]([+-]?\d+))?$""").matchEntire(json) ?: return json
        val (sign, whole, fraction, exponent) = number.destructured
        val digits = whole + fraction
        val point = whole.length + exponent.ifEmpty { "0" }.toInt()
        val before = if (point <= 0) "0" else digits.take(point).padEnd(point, '0').replace(Regex("""^0+(?=\d)"""), "")
        val after = if (point <= 0) "0".repeat(-point) + digits else digits.drop(point)
        return (if (digits.any { it in '1'..'9' }) sign else "") + before + (if (after.isNotEmpty()) ".$after" else "")
    }

    /** What [planKey] and [planKeyOf] mark a text id with, so it keys apart from an id of another kind that reads alike. */
    const val TEXT_MARK = "t:"

    /** The property [key] of the node or relationship [alias], read as text: the expression a range index is made over. */
    fun property(store: Store, alias: String, key: String): String = when (store) {
        Store.POSTGRES -> PostgresSql.property(alias, key)
        Store.ORACLE -> OracleSql.property(alias, key)
        Store.SQLITE -> SqliteSql.property(alias, key)
    }

    /** Whether the node or relationship [alias] has the property [key] at all, null or not. */
    fun hasProperty(store: Store, alias: String, key: String): String = when {
        key == Stamps.PROPERTY -> "$alias.stamp IS NOT NULL"
        store == Store.POSTGRES -> "${PostgresSql.value(alias, key)} IS NOT NULL"
        store == Store.ORACLE -> OracleSql.hasProperty(alias, key)
        else -> "${SqliteSql.type(alias, key)} IS NOT NULL"
    }
}
