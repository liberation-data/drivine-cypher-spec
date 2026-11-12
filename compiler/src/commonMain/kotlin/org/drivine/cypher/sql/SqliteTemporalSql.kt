package org.drivine.cypher.sql

import org.drivine.cypher.store.SqliteFunctions
import org.drivine.cypher.store.SqliteTimestamps
import kotlinx.datetime.TimeZone
import kotlinx.datetime.toLocalDateTime

/**
 * SQLite's SQL for temporal values: see [TemporalSql]. SQLite has no type for a moment and no arithmetic of the
 * calendar, so a timestamp is the text of a wall clock to the microsecond, `2024-03-15T10:30:00.000000`, which
 * compares and orders as the clock does (see `SqliteTimestamps`), and what is worked out of one is worked out by
 * the functions Drivine registers on each connection, with `java.time`: see `SqliteTemporalFunction`, and the module's
 * port of it, `org.drivine.cypher.host.HostFunctions.temporal`. Its time is [exactTime]: worked out in whole numbers.
 */
class SqliteTemporalSql(shared: (List<String>, (List<String>) -> String) -> String) : TemporalSql(shared) {

    override fun local(text: String, kind: Temporal) = temporal("local", text, "'${kind.name}'")

    override fun utc(text: String, kind: Temporal) = temporal("utc", text, "'${kind.name}'")

    /**
     * The clock, in UTC, as the statement is compiled, just before it runs: the same for each of its rows and each step
     * of a write, as a statement's clock is in Cypher. SQLite's own `'now'` is the same within one step of a query alone.
     */
    override val now = "'${SqliteTimestamps.write(@OptIn(kotlin.time.ExperimentalTime::class) kotlin.time.Clock.System.now().toLocalDateTime(TimeZone.UTC))}'"

    override fun matches(text: String, pattern: String) = "($text REGEXP $pattern)"

    /**
     * [pattern] ending at `\z`, the text's end, in place of the `$` it ends with, where no backslash escapes it: `REGEXP` and the functions Drivine registers read it with
     * each library's own regular expressions, and Java's `$` matches before a line break that ends the text too, where
     * JavaScript's does not. The spec's `host.yaml` pins what each library reads a pattern compiled SQL gives it as.
     */
    override fun regex(pattern: String) =
        if (pattern.endsWith("$") && pattern.dropLast(1).takeLastWhile { it == '\\' }.length % 2 == 0) pattern.dropLast(1) + "\\z" else pattern

    override fun group(text: String, pattern: String, group: Int) = "${SqliteFunctions.REGEX_GROUP}($text, $pattern, $group)"

    override fun number(text: String) = "CAST(NULLIF($text, '') AS NUMERIC)"

    /** A timestamp is such a text already. */
    override fun sortable(timestamp: String) = timestamp

    /** The last six digits, for a timestamp of a year BC is a character longer: `-0044-03-15T10:30:00.000000`. */
    override fun micros(timestamp: String) = "substr($timestamp, -6)"

    /** `java.time`'s, for SQLite's own `strftime` reads no year before 0. */
    override fun dayOfWeek(timestamp: String) = temporal("day_of_week", timestamp)

    override fun addMonths(timestamp: String, months: String) = temporal("add_months", timestamp, months)

    /** Whole [seconds] alone, as each caller of this form gives (`0`, an offset's seconds): see [addExactTime]. */
    override fun addTime(timestamp: String, days: String, seconds: String) = addExactTime(timestamp, days, seconds, "0")

    override val exactTime = true

    override fun addExactTime(timestamp: String, days: String, seconds: String, nanos: String) = temporal("add_time", timestamp, days, seconds, nanos)

    override fun monthsBetween(from: String, to: String) = temporal("months_between", from, to)

    /** None: the seconds with their fraction are a float, so [exactBetween] gives them, in two whole numbers. */
    override fun secondsBetween(from: String, to: String): String =
        throw UnsupportedOperationException("SQLite works out the time between two timestamps exactly: see exactBetween.")

    override fun exactBetween(from: String, to: String) = temporal("seconds_between", from, to) to temporal("nanos_between", from, to)

    override fun nanosOfMinute(timestamp: String) = temporal("extract", timestamp, "'NANOSECOND_OF_MINUTE'")

    override fun truncate(unit: String, timestamp: String) = if (unit in UNITS) temporal("truncate", timestamp, "'$unit'") else null

    override fun replace(text: String, pattern: String, replacement: String) = "${SqliteFunctions.REGEX_REPLACE}($text, $pattern, $replacement)"

    override val toChar = SqliteFunctions.TO_CHAR

    override val trunc = SqliteFunctions.TRUNC

    /**
     * SQLite's numbers are binary, so a fraction of a second times a power of ten misses a whole number by a unit of its
     * last place or two: [trunc] takes such a number to be the whole number, and so does this, going down from it only
     * where a negative number is below it by more than that. It reads [number] four times, so binds it with [shared].
     */
    override fun floor(number: String) = shared(listOf(number)) { (x) -> "($trunc($x) - CASE WHEN $x < 0 AND $x < $trunc($x) - ABS($x) * 1e-15 THEN 1 ELSE 0 END)" }

    override val epoch = "'1970-01-01T00:00:00.000000'"

    /** SQLite divides one whole number by another to a whole number: the dividend is made a real number first. */
    override fun quotient(dividend: String, divisor: String) = "($dividend + 0.0) / $divisor"

    /** A whole number of [field]: `SECOND`, a float, is none, as the host function has none ([nanosOfMinute] gives the seconds). */
    override fun extract(field: String, timestamp: String): String {
        require(field != "SECOND") { "SQLite reads a timestamp's seconds by nanosOfMinute, not extract('SECOND')." }
        return temporal("extract", timestamp, "'$field'")
    }

    /** `java.time` numbers years as Cypher does, and its `to_char` writes one before 0 with its sign. */
    override val numbersBcFromOne = false

    /** The temporal function's [operation] of [arguments]. */
    private fun temporal(operation: String, vararg arguments: String) = "${SqliteFunctions.TEMPORAL}('$operation', ${arguments.joinToString(", ")})"
}
