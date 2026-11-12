package org.drivine.cypher.host

import org.drivine.DrivineInternalApi
import org.drivine.cypher.json.ArrayNode
import org.drivine.cypher.number.DoubleText
import org.drivine.cypher.store.SqliteCypherValues
import org.drivine.cypher.store.SqliteFunctions

/**
 * The host functions of [SqliteFunctions] that the module implements, which each host registers on SQLite as they are:
 * the native library as a SQLite extension, a host on the JVM from its own registrations. They are ported from
 * drivine4j's (#108); the spec's host.yaml pins what each gives, and HostSpecTest checks each one here against its cases,
 * and lists those not yet ported. Those of JSON values read each argument as JSON text ([SqliteCypherValues.read]), and fail
 * the statement on a text that is no JSON: an empty one is no value.
 */
@DrivineInternalApi
object HostFunctions {

    /**
     * `drivine_float_text(real)`: the float as Java writes a double, as Cypher's `toString` gives it on a graph engine
     * (`2.0`, `1.0E7`, `0.3333333333333333`, an infinity as `Infinity`), where SQLite's own text of a real has 17
     * digits. Null of null; any other argument is read as SQLite reads it as a float.
     */
    val floatText = HostFunction(SqliteFunctions.FLOAT_TEXT, 1, deterministic = true) { args, _, result ->
        if (args.isNull(0)) result.nullValue() else result.text(DoubleText.of(args.double(0)))
    }

    /**
     * `drivine_equal(a, b)`: whether the JSON values are equal as Cypher compares them ([SqliteCypherValues.compare]), 1 or 0;
     * null where either is no value, as a comparison with null is, or where a null they hold makes the comparison null.
     */
    val equal = HostFunction(SqliteFunctions.EQUAL, 2, deterministic = true) { args, _, result ->
        val a = SqliteCypherValues.read(args.text(0))
        val b = SqliteCypherValues.read(args.text(1))
        when (if (a == null || b == null) null else SqliteCypherValues.compare(a, b)) {
            true -> result.int(1)
            false -> result.int(0)
            null -> result.nullValue()
        }
    }

    /**
     * `drivine_add(a, b)`: Cypher's `a + b` of the JSON values, as JSON text ([SqliteCypherValues.add]). A list takes a null
     * as an item; any other value added to null is null, as are two values Cypher does not add.
     */
    val add = HostFunction(SqliteFunctions.ADD, 2, deterministic = true) { args, _, result ->
        val a = SqliteCypherValues.read(args.text(0)) ?: SqliteCypherValues.NULL
        val b = SqliteCypherValues.read(args.text(1)) ?: SqliteCypherValues.NULL
        val sum = SqliteCypherValues.add(a, b)
        if (sum == null) result.nullValue() else result.text(SqliteCypherValues.write(sum))
    }

    /**
     * `drivine_canonical(json)`: the JSON text of the value, each map in it with its keys in order, so two maps of the same
     * entries are the same text, and group, and are kept once, as Postgres's `jsonb` keeps them. Null of null; JSON's null
     * as it is given.
     */
    val canonical = HostFunction(SqliteFunctions.CANONICAL, 1, deterministic = true) { args, _, result ->
        val text = args.text(0)
        val node = SqliteCypherValues.read(text)
        when {
            text == null -> result.nullValue()
            node == null -> result.text(text)
            else -> result.text(SqliteCypherValues.write(SqliteCypherValues.canonical(node)))
        }
    }

    /**
     * `drivine_sort_key(json)`: of a JSON list, a text that orders, as SQLite orders text, as Cypher orders lists
     * ([SqliteCypherValues.sortKey]). Null where the value is null; of a value that is no list, its JSON text as given.
     */
    val sortKey = HostFunction(SqliteFunctions.SORT_KEY, 1, deterministic = true) { args, _, result ->
        val text = args.text(0)
        val node = SqliteCypherValues.read(text)
        when {
            text == null || node == null -> result.nullValue()
            node is ArrayNode -> result.text(SqliteCypherValues.sortKey(node))
            else -> result.text(text)
        }
    }

    /**
     * `drivine_list_order(a, b)`: where the JSON list `a` comes beside the JSON list `b` as Cypher orders them in a
     * comparison ([SqliteCypherValues.order]), -1, 0 or 1; null where either is no list, or where the pair that decides is
     * not ordered.
     */
    val listOrder = HostFunction(SqliteFunctions.LIST_ORDER, 2, deterministic = true) { args, _, result ->
        val a = SqliteCypherValues.read(args.text(0))
        val b = SqliteCypherValues.read(args.text(1))
        val order = if (a is ArrayNode && b is ArrayNode) SqliteCypherValues.order(a, b) else null
        if (order == null) result.nullValue() else result.int(order)
    }

    /** Every host function the module implements, in the order each host registers them. */
    val all: List<HostFunction> = listOf(floatText, equal, add, canonical, sortKey, listOrder)
}
