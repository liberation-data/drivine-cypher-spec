package org.drivine.cypher.host

import org.drivine.DrivineInternalApi
import org.drivine.cypher.host.regex.JavaPatterns
import org.drivine.cypher.host.time.HostTemporal
import org.drivine.cypher.json.ArrayNode
import org.drivine.cypher.number.DecimalRound
import org.drivine.cypher.number.DoubleText
import org.drivine.cypher.sql.RoundingMode
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

    /**
     * `drivine_round(number, precision, mode)`: the number rounded to as many decimal places by the mode, in decimal, as
     * Neo4j's `round(value, precision, mode)` does ([DecimalRound]): 2.345 is halfway between 2.34 and 2.35, where SQLite's
     * binary arithmetic finds it just below. A float, as a REAL. Null where the number or the precision is. An infinity,
     * which SQLite stores and a decimal holds no value for, is itself. The mode is one of [RoundingMode]'s names, as the
     * compiler writes it; any other fails the statement.
     */
    val round = HostFunction(SqliteFunctions.ROUND, 3, deterministic = true) { args, _, result ->
        if (args.isNull(0) || args.isNull(1)) return@HostFunction result.nullValue()
        val text = args.text(2)
        val mode = RoundingMode.entries.firstOrNull { it.name == text }
            ?: return@HostFunction result.error("${SqliteFunctions.ROUND}: no rounding mode is named $text.")
        val places = args.long(1)
        if (args.type(0) == SqlType.INTEGER) return@HostFunction result.double(DecimalRound.of(args.long(0), places, mode))
        val number = args.double(0)
        result.double(if (number.isInfinite()) number else DecimalRound.of(number, places, mode))
    }

    /**
     * `regexp(pattern, text)`, which SQLite calls for its operator `text REGEXP pattern` (the pattern first): 1 where the
     * regular expression matches somewhere in the text, 0 where it matches nowhere; null where either is null, as a
     * comparison with null is. The pattern is Java's, as drivine4j reads it ([JavaPatterns]); it fails the statement where
     * Java refuses it, or where this platform can't match it as Java does (a construct named in the message).
     */
    val regexp = HostFunction(SqliteFunctions.REGEXP, 2, deterministic = true) { args, _, result ->
        val pattern = args.text(0)
        val text = args.text(1)
        if (pattern == null || text == null) result.nullValue() else result.int(if (JavaPatterns.of(pattern).find(text) != null) 1 else 0)
    }

    /**
     * `drivine_regex_group(text, pattern, group)`: the text the group of that number matches where the pattern first matches
     * in the text, as Postgres's `regexp_match` gives it (0 the whole match); null where it matches nowhere, or the group
     * takes no part or is past the pattern's groups, or the text or the pattern is null. A null group is 0, as SQLite reads
     * it as a whole number; a negative one fails the statement, as Java's `Matcher.group` refuses it.
     */
    val regexGroup = HostFunction(SqliteFunctions.REGEX_GROUP, 3, deterministic = true) { args, _, result ->
        val text = args.text(0)
        val pattern = args.text(1)
        if (text == null || pattern == null) return@HostFunction result.nullValue()
        val groups = JavaPatterns.of(pattern).find(text)
        val group = args.int(2)
        when {
            groups == null || group >= groups.size -> result.nullValue()
            group < 0 -> throw IndexOutOfBoundsException("No group $group")
            else -> groups[group]?.let { result.text(it) } ?: result.nullValue()
        }
    }

    /**
     * `drivine_regexp_replace(text, pattern, replacement)`: the text with each match of the pattern replaced, the
     * replacement naming a group as Postgres's and Oracle's do, a backslash and a digit (`\1`), and anything else itself;
     * null where any is null. A match whose replacement names a group the pattern hasn't fails the statement, as Java's does.
     */
    val regexpReplace = HostFunction(SqliteFunctions.REGEX_REPLACE, 3, deterministic = true) { args, _, result ->
        val text = args.text(0)
        val pattern = args.text(1)
        val replacement = args.text(2)
        if (text == null || pattern == null || replacement == null) {
            result.nullValue()
        } else {
            result.text(JavaPatterns.of(pattern).replaceAll(text, JavaPatterns.javaReplacement(replacement)))
        }
    }

    /**
     * `drivine_temporal(operation, …)`: what compiled SQL works out of a timestamp, the text of a wall clock to the
     * microsecond, by its operation (`local`, `utc`, `extract`, `day_of_week`, `add_months`, `add_time`, `months_between`,
     * `seconds_between`, `nanos_between`, `truncate`), as java.time works it out: see [HostTemporal.temporal]. Null where
     * an argument after the operation is null. Any number of arguments, the operation's.
     */
    val temporal = HostFunction(SqliteFunctions.TEMPORAL, -1, deterministic = true) { args, count, result -> HostTemporal.temporal(args, count, result) }

    /**
     * `drivine_trunc(number)`: the number's fraction cut off, a whole number where one holds it, as Postgres's and Oracle's
     * `TRUNC` give it, where SQLite's own `trunc` gives a float: see [HostTemporal.trunc].
     */
    val trunc = HostFunction(SqliteFunctions.TRUNC, 1, deterministic = true) { args, _, result -> HostTemporal.trunc(args, result) }

    /**
     * `drivine_to_char(value, pattern)`: a timestamp or a number in a pattern of Postgres's and Oracle's `to_char`, those
     * compiled SQL writes temporal values with: see [HostTemporal.toChar].
     */
    val toChar = HostFunction(SqliteFunctions.TO_CHAR, 2, deterministic = true) { args, _, result -> HostTemporal.toChar(args, result) }

    /**
     * `drivine_host_version()`: [SqliteFunctions.HOST_VERSION], the version of the forms of these functions' arguments and
     * results, which a host checks once on each connection it registers them on: see [HostCheck].
     */
    val hostVersion = HostFunction(SqliteFunctions.HOST_VERSION_FUNCTION, 0, deterministic = true) { _, _, result ->
        result.long(SqliteFunctions.HOST_VERSION.toLong())
    }

    /** Every host function the module implements, in the order each host registers them. */
    val all: List<HostFunction> = listOf(
        floatText, equal, add, canonical, sortKey, listOrder, round, regexp, regexGroup, regexpReplace, temporal, trunc, toChar, hostVersion,
    )
}
