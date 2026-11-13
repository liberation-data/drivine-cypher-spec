package org.drivine.cypher.host.time

import kotlin.math.abs
import kotlin.math.round
import kotlin.math.truncate
import kotlin.math.ulp
import kotlinx.datetime.LocalDate
import kotlinx.datetime.LocalDateTime
import kotlinx.datetime.LocalTime
import kotlinx.datetime.isoDayNumber
import kotlinx.datetime.number
import org.drivine.cypher.host.HostArgs
import org.drivine.cypher.host.HostResult
import org.drivine.cypher.host.SqlType
import org.drivine.cypher.sql.Temporal
import org.drivine.cypher.sql.TemporalSql
import org.drivine.cypher.store.SqliteFunctions
import org.drivine.cypher.store.SqliteTimestamps

/**
 * The bodies of SQLite's temporal host functions, `drivine_temporal`, `drivine_trunc` and `drivine_to_char` (see
 * [org.drivine.cypher.host.HostFunctions]), ported from drivine4j's `SqliteTemporalFunction`, `SqliteTruncFunction` and
 * `SqliteToCharFunction` onto [JavaTime], in the forms host.yaml pins (#108). A timestamp is the text of a wall clock,
 * read as java.time's `LocalDateTime.parse` reads one (a space for the `T`), and written by [SqliteTimestamps.write]
 * alone, which keeps the store's microsecond and cuts what is finer.
 */
internal object HostTemporal {

    private val EPOCH_DAY = LocalDate(1970, 1, 1)

    private fun write(clock: LocalDateTime) = SqliteTimestamps.write(clock)

    /** A text that is no temporal value, as `drivine_temporal` fails on one, naming it. */
    private class NoTemporalValue(text: String, reason: String) : IllegalArgumentException("'$text' is no temporal value: $reason")

    /**
     * `drivine_temporal(operation, …)`: null where any argument after the operation is null (the operation unread, as
     * drivine4j checks), else the operation's result:
     *
     * - `local(text, kind)` and `utc(text, kind)`: the wall clock the ISO text of a [Temporal] kind reads, its offset left
     *   out, or moved to UTC. An offset is [Temporal.OFFSET]'s: one with seconds is none, so the text is no temporal value
     *   (drivine4j#424). A failure names the whole text given.
     * - `extract(timestamp, field)`: `YEAR`, `MONTH`, `DAY`, `HOUR` or `MINUTE`, or `NANOSECOND_OF_MINUTE`, whole numbers;
     *   `SECOND`, a float drivine4j gave, is no field: the compiler reads the nanoseconds.
     * - `day_of_week(timestamp)`: 1 for Monday to 7.
     * - `add_months(timestamp, months)`.
     * - `add_time(timestamp, days, seconds, nanos)`: added as `plusDays`, `plusSeconds` and `plusNanos` add them, any long
     *   each, so nanoseconds past a second are carried.
     * - `months_between(from, to)`.
     * - `seconds_between(from, to)` and `nanos_between(from, to)`: `Duration.between`'s seconds, floored, and the
     *   nanoseconds past them, 0 to 999,999,999, exact over the whole range.
     * - `truncate(timestamp, unit)`: to one of [TemporalSql.UNITS].
     *
     * A count is read as drivine4j's `whole` reads it: a whole number as it is, anything else as a decimal text rounded
     * half away from zero, wrapping past a long's range.
     */
    fun temporal(args: HostArgs, count: Int, result: HostResult) {
        val operation = if (count > 0) args.text(0) else null
        if ((1 until count).any { args.isNull(it) }) return result.nullValue()
        fun arg(i: Int): String {
            if (i >= count) throw IllegalArgumentException("${SqliteFunctions.TEMPORAL}('$operation', …) takes ${i} arguments after the operation at least, not ${count - 1}.")
            return args.text(i)!!
        }
        fun clock(i: Int): LocalDateTime {
            val text = arg(i).replace(' ', 'T')
            return try {
                JavaTime.parseDateTime(text)
            } catch (e: JavaParseException) {
                throw NoTemporalValue(e.parsed, e.message ?: "")
            }
        }
        fun whole(i: Int): Long {
            val text = arg(i)
            return if (args.type(i) == SqlType.INTEGER) args.long(i) else JavaDecimal.parse(text).halfUp(0).wrappedLong()
        }
        when (operation) {
            "local" -> result.text(write(named(arg(1)) { local(it, kind(arg(2))) }))
            "utc" -> result.text(write(named(arg(1)) { utc(it, kind(arg(2))) }))
            "extract" -> result.long(extract(clock(1), arg(2)))
            "day_of_week" -> result.long(clock(1).date.dayOfWeek.isoDayNumber.toLong())
            "add_months" -> clock(1).let { c -> result.text(write(LocalDateTime(JavaTime.plusMonths(c.date, whole(2)), c.time))) }
            "add_time" -> {
                // Each amount read as Java reads it, just before it is added: a failure is the first Java meets.
                var moved = clock(1).let { c -> LocalDateTime(JavaTime.plusDays(c.date, whole(2)), c.time) }
                moved = JavaTime.plus(moved, whole(3), 0)
                result.text(write(JavaTime.plus(moved, 0, whole(4))))
            }
            "months_between" -> result.long(JavaTime.monthsBetween(clock(1), clock(2)))
            "seconds_between" -> result.long(JavaTime.between(clock(1), clock(2)).first)
            "nanos_between" -> result.long(JavaTime.between(clock(1), clock(2)).second)
            "truncate" -> result.text(write(truncate(clock(1), arg(2))))
            else -> throw IllegalArgumentException("${SqliteFunctions.TEMPORAL} has no operation $operation.")
        }
    }

    /** [read] of [text], a failure to parse naming [text], the whole text given, rather than what of it java.time was given. */
    private fun named(text: String, read: (String) -> LocalDateTime): LocalDateTime = try {
        read(text)
    } catch (e: JavaParseException) {
        throw NoTemporalValue(text, e.message ?: "")
    }

    /** A kind by its name, as drivine4j's `Temporal.valueOf` takes one; a name of none fails, naming it. */
    private fun kind(name: String): Temporal =
        Temporal.entries.firstOrNull { it.name == name } ?: throw IllegalArgumentException("No enum constant org.drivine.cypher.sql.Temporal.$name")

    internal fun local(text: String, kind: Temporal): LocalDateTime = when (kind) {
        Temporal.DATE -> LocalDateTime(JavaTime.parseDate(text), LocalTime(0, 0))
        Temporal.LOCAL_DATE_TIME -> JavaTime.parseDateTime(text)
        Temporal.DATE_TIME -> JavaTime.parseDateTime(unzoned(text))
        Temporal.LOCAL_TIME -> LocalDateTime(EPOCH_DAY, JavaTime.parseTime(text))
        Temporal.TIME -> LocalDateTime(EPOCH_DAY, JavaTime.parseTime(unzoned(text)))
        Temporal.DURATION -> throw IllegalArgumentException("A duration is no moment.")
    }

    private fun utc(text: String, kind: Temporal): LocalDateTime = when (kind) {
        Temporal.DATE_TIME -> inUtc(JavaTime.parseOffsetDateTime(withColon(text)))
        Temporal.TIME -> inUtc(JavaTime.parseOffsetDateTime("${EPOCH_DAY}T${withColon(text)}"))
        else -> local(text, kind)
    }

    /** `withOffsetSameInstant(ZoneOffset.UTC).toLocalDateTime()`: the wall clock moved by the offset, as `plusSeconds` moves it. */
    private fun inUtc(clock: Pair<LocalDateTime, Int>): LocalDateTime = JavaTime.plus(clock.first, -clock.second.toLong(), 0)

    /**
     * Where [text] ends with an offset as [Temporal.OFFSET] writes one, `Z`, `±hh:mm` or `±hhmm`: its range in [text].
     * Matched by hand, not by a regular expression, whose `$` the JVM matches before a line break that ends the text too.
     */
    private fun offsetAt(text: String): IntRange? {
        if (text.endsWith('Z')) return text.length - 1 until text.length
        fun digits(from: Int, to: Int) = from >= 0 && (from until to).all { text[it] in '0'..'9' }
        val n = text.length
        for (colon in listOf(true, false)) {
            val start = n - if (colon) 6 else 5
            if (start < 0 || (text[start] != '+' && text[start] != '-')) continue
            if (colon && (text[start + 3] != ':' || !digits(start + 1, start + 3) || !digits(start + 4, n))) continue
            if (!colon && !digits(start + 1, n)) continue
            return start until n
        }
        return null
    }

    /** [text] without the offset it ends with: one of seconds is none, so a text with it is no value of a local kind. */
    private fun unzoned(text: String): String = offsetAt(text)?.let { text.substring(0, it.first) } ?: text

    /**
     * [text] with its offset written as java.time reads one, with a colon, or `Z`. A text with no offset of
     * [Temporal.OFFSET]'s, one of seconds among them (`+02:05:59`, which java.time would read), is no temporal value.
     */
    private fun withColon(text: String): String {
        val range = offsetAt(text) ?: throw JavaParseException(text, "Text '$text' has no offset of hours and minutes")
        val o = text.substring(range.first)
        return text.substring(0, range.first) + if (o == "Z" || o.contains(':')) o else "${o.substring(0, 3)}:${o.substring(3)}"
    }

    private fun extract(clock: LocalDateTime, field: String): Long = when (field) {
        "YEAR" -> clock.year.toLong()
        "MONTH" -> clock.month.number.toLong()
        "DAY" -> clock.day.toLong()
        "HOUR" -> clock.hour.toLong()
        "MINUTE" -> clock.minute.toLong()
        "NANOSECOND_OF_MINUTE" -> clock.second * 1_000_000_000L + clock.nanosecond
        else -> throw IllegalArgumentException("A timestamp has no field $field.")
    }

    private fun truncate(clock: LocalDateTime, unit: String): LocalDateTime {
        val date = clock.date
        fun midnight(d: LocalDate) = LocalDateTime(d, LocalTime(0, 0))
        fun time(hour: Int, minute: Int = 0, second: Int = 0, nanos: Int = 0) = LocalDateTime(date, LocalTime(hour, minute, second, nanos))
        val years = TemporalSql.YEARS_OF[unit]
        return when {
            unit == "year" -> midnight(LocalDate(date.year, 1, 1))
            unit == "quarter" -> midnight(LocalDate(date.year, (date.month.number - 1) / 3 * 3 + 1, 1))
            unit == "month" -> midnight(LocalDate(date.year, date.month.number, 1))
            unit == "week" -> midnight(JavaTime.plusDays(date, -(date.dayOfWeek.isoDayNumber - 1L)))
            unit == "day" -> midnight(date)
            unit == "hour" -> time(clock.hour)
            unit == "minute" -> time(clock.hour, clock.minute)
            unit == "second" -> time(clock.hour, clock.minute, clock.second)
            unit == "millisecond" -> time(clock.hour, clock.minute, clock.second, clock.nanosecond / 1_000_000 * 1_000_000)
            unit == "microsecond" -> time(clock.hour, clock.minute, clock.second, clock.nanosecond / 1_000 * 1_000)
            // The year less its years into the span, counted toward zero, as Neo4j counts: -1234's millennium is -1000.
            years != null -> midnight(LocalDate(date.year - date.year % years, 1, 1))
            unit == "weekYear" -> midnight(JavaTime.weekYearStart(date))
            else -> throw IllegalArgumentException("A timestamp is not truncated to $unit.")
        }
    }

    /**
     * `drivine_trunc(number)`: null of null, a whole number itself; a float within two units of its last place of a whole
     * number is that whole number (SQLite's binary misses a fraction of a second times a power of ten by that much), any
     * other is cut toward zero, and either is a whole number where it is in a long's range, else the float.
     */
    fun trunc(args: HostArgs, result: HostResult) {
        when (args.type(0)) {
            SqlType.NULL -> result.nullValue()
            SqlType.INTEGER -> result.long(args.long(0))
            else -> {
                val number = args.double(0)
                val whole = round(number)
                val cut = if (abs(number - whole) <= 2 * number.ulp) whole else truncate(number)
                if (cut >= Long.MIN_VALUE.toDouble() && cut < Long.MAX_VALUE.toDouble()) result.long(cut.toLong()) else result.double(cut)
            }
        }
    }

    /**
     * `drivine_to_char(value, pattern)`: null where the pattern is, then where the value is. A pattern that opens with `FM`
     * writes a number ([number]); any other a timestamp ([clock]).
     */
    fun toChar(args: HostArgs, result: HostResult) {
        val pattern = args.text(1) ?: return result.nullValue()
        if (args.isNull(0)) return result.nullValue()
        if (pattern.startsWith("FM")) {
            val value = if (args.type(0) == SqlType.INTEGER) JavaDecimal.of(args.long(0)) else JavaDecimal.parse(args.text(0)!!)
            result.text(number(value, pattern.removePrefix("FM")))
        } else {
            val text = args.text(0)!!.replace(' ', 'T')
            val clock = try {
                JavaTime.parseDateTime(text)
            } catch (e: JavaParseException) {
                throw IllegalArgumentException("Text '${e.parsed}' could not be parsed: ${e.message}")
            }
            result.text(clock(clock, pattern))
        }
    }

    /**
     * A number in `to_char`'s pattern, `FM` taken off: with no point, rounded half away from zero to a whole number, its
     * digits padded with zeros to as many as the pattern has `0`s, its sign kept; with one, rounded likewise to as many
     * places as the pattern has characters after it, its trailing zeros left out, the point kept where no fraction is
     * left (`2.`), and a zero unsigned.
     */
    private fun number(value: JavaDecimal, pattern: String): String {
        val point = pattern.indexOf('.')
        if (point < 0) {
            val whole = value.halfUp(0)
            val digits = whole.toPlainString().removePrefix("-").padStart(pattern.count { it == '0' }, '0')
            return if (whole.signum < 0) "-$digits" else digits
        }
        val rounded = value.halfUp((pattern.length - point - 1).toLong()).stripTrailingZeros()
        val text = if (rounded.scale <= 0) rounded.halfUp(0).toPlainString() + "." else rounded.toPlainString()
        return if (text.startsWith("-0") && rounded.signum == 0) text.removePrefix("-") else text
    }

    /** A timestamp's pattern's parts, the longer of two that start alike first. */
    private val TOKENS = listOf("HH24", "IYYY", "YYYY", "DDD", "IW", "MM", "DD", "MI", "SS")

    /**
     * A timestamp in `to_char`'s pattern: `YYYY`, `MM`, `DD`, `HH24`, `MI`, `SS`, `IYYY` (the ISO week's year), `IW` (its
     * week) and `DDD` (the day of the year); text in double quotes as it is, an unclosed quote to the end; anything else itself.
     */
    private fun clock(clock: LocalDateTime, pattern: String): String {
        fun pad(n: Int, width: Int) = n.toString().padStart(width, '0')
        val written = StringBuilder()
        var at = 0
        while (at < pattern.length) {
            if (pattern[at] == '"') {
                val end = pattern.indexOf('"', at + 1).takeIf { it > at } ?: pattern.length
                written.append(pattern, at + 1, end)
                at = end + 1
                continue
            }
            val token = TOKENS.firstOrNull { pattern.startsWith(it, at) }
            if (token == null) {
                written.append(pattern[at++])
                continue
            }
            written.append(
                when (token) {
                    "HH24" -> pad(clock.hour, 2)
                    "IYYY" -> year(JavaTime.weekBasedYear(clock.date))
                    "YYYY" -> year(clock.year)
                    "DDD" -> pad(clock.date.dayOfYear, 3)
                    "IW" -> pad(JavaTime.week(clock.date), 2)
                    "MM" -> pad(clock.month.number, 2)
                    "DD" -> pad(clock.day, 2)
                    "MI" -> pad(clock.minute, 2)
                    else -> pad(clock.second, 2)
                },
            )
            at += token.length
        }
        return written.toString()
    }

    /** A year as ISO 8601 writes it, four digits after its sign: `0000` for 1 BC, `-0044` for 45 BC. */
    private fun year(year: Int) = (if (year < 0) "-" else "") + abs(year.toLong()).toString().padStart(4, '0')
}
