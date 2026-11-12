package org.drivine.cypher.oracle

import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.dataformat.yaml.YAMLFactory
import org.drivine.cypher.sql.Temporal
import org.drivine.cypher.sql.TemporalSql
import java.io.File
import java.math.BigDecimal
import java.math.RoundingMode
import java.time.DateTimeException
import java.time.DayOfWeek
import java.time.Duration
import java.time.Instant
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime
import java.time.OffsetDateTime
import java.time.ZoneId
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import java.time.format.DateTimeParseException
import java.time.temporal.ChronoField
import java.time.temporal.ChronoUnit
import java.time.temporal.IsoFields
import java.time.temporal.TemporalAdjusters
import java.util.Locale
import kotlin.random.Random

/**
 * Java's answers for SQLite's temporal host functions, `drivine_temporal`, `drivine_trunc` and `drivine_to_char`
 * (`host-temporal.json`), which every target's `HostTemporalOracleTest` checks the module's port of them against (#108).
 *
 * The answers are worked out here with java.time and java.math, as drivine4j's `SqliteTemporalFunction`,
 * `SqliteTruncFunction` and `SqliteToCharFunction` work them out, line for line, but in the forms #108 agreed for the port
 * (and host.yaml's `drivine_temporal` case pins):
 *
 * - `add_time(timestamp, days, seconds, nanos)`: whole days, seconds and nanoseconds, each any long, added in that order as
 *   `plusDays`, `plusSeconds` and `plusNanos` add them, not a float of seconds;
 * - `seconds_between(from, to)`: the whole seconds of `Duration.between`, floored (`getSeconds`), not a float of seconds;
 *   and `nanos_between(from, to)`: the nanoseconds past them, 0 to 999,999,999 (`getNano`), so the two are the exact
 *   difference over the whole range, where nanoseconds alone in a long would overflow past ±292 years;
 * - `extract(timestamp, 'NANOSECOND_OF_MINUTE')`: the nanoseconds into the minute, a whole number; `SECOND` is no field;
 * - `local` and `utc` keep a text's nanoseconds, and a zoned text's offset is `Z`, `±hh:mm` or `±hhmm` alone
 *   ([Temporal.OFFSET]): one with seconds (`+02:05:59`) is no temporal value (drivine4j#424).
 *
 * A timestamp is still written to the microsecond, its finer part cut ([write]), as the store's layout 1 keeps it.
 *
 * Each case is `{function, group, args, result}` or `{function, group, args, error, errorKey}`: SQL values each, a text,
 * a whole number, `{$float: …}` a float, or null. `errorKey` is the part of Java's message a port must give: the whole
 * message, but for a text java.time can't parse, where it is `'<text>' is no temporal value` (`drivine_temporal`) or
 * `Text '<text>' could not be parsed` (`drivine_to_char`), as the rest is java.time's wording of why; and empty where
 * Java's message is java.math's (`BigDecimal` of a text that is no number), so any failure will do.
 *
 * A float argument has at most 15 significant digits, so SQLite's text of it (`%!.15g`, which drivine4j's
 * `value_text` reads where it reads a float as a decimal) is the decimal Java's shortest text is: see [float].
 *
 * The host.yaml steps of the three functions are checked against these answers too, where the step is not held for
 * layout 2 ([HELD_NOTE]), which must differ from them: a step and the oracle that disagree fail `jvmOracle`.
 */
object HostTemporals {

    private val json = ObjectMapper()
    private val yaml = ObjectMapper(YAMLFactory())

    const val TEMPORAL = "drivine_temporal"
    const val TRUNC = "drivine_trunc"
    const val TO_CHAR = "drivine_to_char"

    /** The note of a host.yaml step that needs layout 2's nine-digit timestamps. */
    const val HELD_NOTE = "drivine4j#421 (layout 2)"

    // ----- The answers: drivine4j's code, in the agreed forms -----

    /** A call's answer: its value, or the message Java fails with and the part of it a port must give. */
    sealed interface Out {
        data class Value(val value: Any?) : Out
        data class Failed(val message: String, val key: String) : Out
    }

    private class SqlFailure(message: String, val key: String = message) : Exception(message)

    /** Runs [work] as xerial runs a function: what it throws is the statement's error. */
    private fun call(work: () -> Any?): Out = try {
        Out.Value(work())
    } catch (e: SqlFailure) {
        Out.Failed(e.message!!, e.key)
    } catch (e: NumberFormatException) {
        Out.Failed(e.message ?: e.toString(), "")
    } catch (e: Exception) {
        Out.Failed(e.message ?: e.toString(), e.message ?: e.toString())
    }

    fun answer(function: String, args: List<Any?>): Out = when (function) {
        TEMPORAL -> call { temporal(args) }
        TRUNC -> call { trunc(args) }
        TO_CHAR -> call { toChar(args) }
        else -> error("No function $function")
    }

    /** SQLite's text of a SQL value, as `value_text` gives it: a float's as its shortest decimal, which [float] keeps to 15 digits. */
    private fun text(value: Any?): String? = when (value) {
        null -> null
        is String -> value
        is Long -> value.toString()
        is Double -> BigDecimal(value.toString()).toString()
        else -> error("No SQL value $value")
    }

    private val FORMAT: DateTimeFormatter = DateTimeFormatter.ofPattern("uuuu-MM-dd'T'HH:mm:ss.SSSSSS")
    private val EPOCH_DAY: LocalDate = LocalDate.of(1970, 1, 1)
    private val OFFSET = Regex(Temporal.OFFSET)

    /** A timestamp's text, to the microsecond, its finer part cut: `SqliteTimestamps.write`. */
    fun write(clock: LocalDateTime): String = FORMAT.format(clock)

    /** drivine4j's `SqliteTimestamps.read`. */
    private fun read(text: String): LocalDateTime = LocalDateTime.parse(text.replace(' ', 'T'))

    private fun temporal(args: List<Any?>): Any? {
        val operation = text(args[0])
        if ((1 until args.size).any { args[it] == null }) return null
        fun clock(i: Int) = read(text(args[i])!!)
        try {
            return when (operation) {
                "local" -> write(named(text(args[1])!!) { local(it, kind(text(args[2])!!)) })
                "utc" -> write(named(text(args[1])!!) { utc(it, kind(text(args[2])!!)) })
                "extract" -> extract(clock(1), text(args[2])!!)
                "day_of_week" -> clock(1).dayOfWeek.value.toLong()
                "add_months" -> write(clock(1).plusMonths(whole(args[2])))
                "add_time" -> write(clock(1).plusDays(whole(args[2])).plusSeconds(whole(args[3])).plusNanos(whole(args[4])))
                "months_between" -> ChronoUnit.MONTHS.between(clock(1), clock(2))
                "seconds_between" -> Duration.between(clock(1), clock(2)).seconds
                "nanos_between" -> Duration.between(clock(1), clock(2)).nano.toLong()
                "truncate" -> write(truncate(clock(1), text(args[2])!!))
                else -> throw SqlFailure("drivine_temporal has no operation $operation.")
            }
        } catch (e: DateTimeParseException) {
            val message = "'${e.parsedString}' is no temporal value"
            throw SqlFailure("$message: ${e.message}", message)
        } catch (e: DateTimeException) {
            throw SqlFailure(e.message!!)
        }
    }

    /**
     * [read] of [text], refused naming [text] itself, where drivine4j's message names what it gave java.time: the text
     * without its offset, or a time of day after `1970-01-01T`. host.yaml's `'10:00:00+02:05:59' is no temporal value` is so.
     */
    private fun named(text: String, read: (String) -> LocalDateTime): LocalDateTime = try {
        read(text)
    } catch (e: DateTimeParseException) {
        throw DateTimeParseException(e.message, text, 0, e)
    }

    /** A kind by its name, as `Temporal.valueOf`: a port names the kind it doesn't know. */
    private fun kind(name: String): Temporal =
        Temporal.entries.firstOrNull { it.name == name } ?: throw SqlFailure("No enum constant org.drivine.cypher.sql.Temporal.$name", name)

    /** The argument as a whole number, rounded half away from zero, as Postgres casts a number to one. */
    private fun whole(value: Any?): Long = if (value is Long) value else BigDecimal(text(value)).setScale(0, RoundingMode.HALF_UP).toLong()

    private fun local(text: String, kind: Temporal): LocalDateTime = when (kind) {
        Temporal.DATE -> LocalDate.parse(text).atStartOfDay()
        Temporal.LOCAL_DATE_TIME -> LocalDateTime.parse(text)
        Temporal.DATE_TIME -> LocalDateTime.parse(unzoned(text))
        Temporal.LOCAL_TIME -> LocalTime.parse(text).atDate(EPOCH_DAY)
        Temporal.TIME -> LocalTime.parse(unzoned(text)).atDate(EPOCH_DAY)
        Temporal.DURATION -> throw SqlFailure("A duration is no moment.")
    }

    private fun utc(text: String, kind: Temporal): LocalDateTime = when (kind) {
        Temporal.DATE_TIME -> OffsetDateTime.parse(withColon(text)).withOffsetSameInstant(ZoneOffset.UTC).toLocalDateTime()
        Temporal.TIME -> OffsetDateTime.parse("${EPOCH_DAY}T${withColon(text)}").withOffsetSameInstant(ZoneOffset.UTC).toLocalDateTime()
        else -> local(text, kind)
    }

    private fun extract(clock: LocalDateTime, field: String): Long = when (field) {
        "YEAR" -> clock.year.toLong()
        "MONTH" -> clock.monthValue.toLong()
        "DAY" -> clock.dayOfMonth.toLong()
        "HOUR" -> clock.hour.toLong()
        "MINUTE" -> clock.minute.toLong()
        "NANOSECOND_OF_MINUTE" -> clock.second * 1_000_000_000L + clock.nano
        else -> throw SqlFailure("A timestamp has no field $field.")
    }

    private fun truncate(clock: LocalDateTime, unit: String): LocalDateTime = when (unit) {
        "year" -> clock.toLocalDate().withDayOfYear(1).atStartOfDay()
        "quarter" -> clock.toLocalDate().withDayOfMonth(1).withMonth((clock.monthValue - 1) / 3 * 3 + 1).atStartOfDay()
        "month" -> clock.toLocalDate().withDayOfMonth(1).atStartOfDay()
        "week" -> clock.toLocalDate().with(TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY)).atStartOfDay()
        "day" -> clock.truncatedTo(ChronoUnit.DAYS)
        "hour" -> clock.truncatedTo(ChronoUnit.HOURS)
        "minute" -> clock.truncatedTo(ChronoUnit.MINUTES)
        "second" -> clock.truncatedTo(ChronoUnit.SECONDS)
        "millisecond" -> clock.truncatedTo(ChronoUnit.MILLIS)
        "microsecond" -> clock.truncatedTo(ChronoUnit.MICROS)
        in TemporalSql.YEARS_OF -> TemporalSql.YEARS_OF.getValue(unit).let { years -> LocalDate.of(clock.year - clock.year % years, 1, 1).atStartOfDay() }
        "weekYear" -> clock.toLocalDate().with(IsoFields.WEEK_OF_WEEK_BASED_YEAR, 1).with(ChronoField.DAY_OF_WEEK, 1).atStartOfDay()
        else -> throw SqlFailure("A timestamp is not truncated to $unit.")
    }

    /** [text] without the offset it ends with: an offset of seconds is none, so the text is no value of a local kind. */
    private fun unzoned(text: String) = OFFSET.replace(text, "")

    /**
     * [text] with its offset written as java.time reads one, with a colon, or `Z`. An offset that is not [Temporal.OFFSET]'s,
     * one of seconds among them, is no offset, where java.time reads `+02:05:59` (drivine4j#424): the text is no value.
     * What follows the offset (Java's `$` matches before a line break that ends the text) is kept, so java.time refuses it.
     */
    private fun withColon(text: String): String {
        val offset = OFFSET.find(text) ?: throw DateTimeParseException("Text '$text' has no offset of hours and minutes", text, 0)
        val o = offset.value
        return text.substring(0, offset.range.first) + (if (o == "Z" || o.contains(':')) o else "${o.substring(0, 3)}:${o.substring(3)}") +
            text.substring(offset.range.last + 1)
    }

    private fun trunc(args: List<Any?>): Any? = when (val value = args[0]) {
        null -> null
        is Long -> value
        is Double -> {
            val whole = Math.rint(value)
            val cut = if (Math.abs(value - whole) <= 2 * Math.ulp(value)) whole else kotlin.math.truncate(value)
            if (cut >= Long.MIN_VALUE.toDouble() && cut < Long.MAX_VALUE.toDouble()) cut.toLong() else cut
        }
        else -> error("No step gives drivine_trunc a text")
    }

    private fun toChar(args: List<Any?>): Any? {
        val pattern = text(args[1]) ?: return null
        if (args[0] == null) return null
        return if (pattern.startsWith("FM")) number(args[0], pattern.removePrefix("FM")) else clock(args[0], pattern)
    }

    private fun number(value: Any?, pattern: String): String {
        val decimal = if (value is Long) BigDecimal.valueOf(value) else BigDecimal(text(value))
        val point = pattern.indexOf('.')
        if (point < 0) {
            val whole = decimal.setScale(0, RoundingMode.HALF_UP)
            val digits = whole.abs().toPlainString().padStart(pattern.count { it == '0' }, '0')
            return if (whole.signum() < 0) "-$digits" else digits
        }
        val fraction = pattern.length - point - 1
        val rounded = decimal.setScale(fraction, RoundingMode.HALF_UP).stripTrailingZeros()
        val text = if (rounded.scale() <= 0) rounded.setScale(0).toPlainString() + "." else rounded.toPlainString()
        return if (text.startsWith("-0") && rounded.signum() == 0) text.removePrefix("-") else text
    }

    private val TOKENS = listOf("HH24", "IYYY", "YYYY", "DDD", "IW", "MM", "DD", "MI", "SS")

    private fun clock(value: Any?, pattern: String): String {
        val source = text(value)!!
        val clock = try {
            read(source)
        } catch (e: DateTimeParseException) {
            throw SqlFailure(e.message!!, "Text '${e.parsedString}' could not be parsed")
        }
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
                    "HH24" -> "%02d".format(Locale.ROOT, clock.hour)
                    "IYYY" -> year(clock.get(IsoFields.WEEK_BASED_YEAR))
                    "YYYY" -> year(clock.year)
                    "DDD" -> "%03d".format(Locale.ROOT, clock.get(ChronoField.DAY_OF_YEAR))
                    "IW" -> "%02d".format(Locale.ROOT, clock.get(IsoFields.WEEK_OF_WEEK_BASED_YEAR))
                    "MM" -> "%02d".format(Locale.ROOT, clock.monthValue)
                    "DD" -> "%02d".format(Locale.ROOT, clock.dayOfMonth)
                    "MI" -> "%02d".format(Locale.ROOT, clock.minute)
                    else -> "%02d".format(Locale.ROOT, clock.second)
                },
            )
            at += token.length
        }
        return written.toString()
    }

    private fun year(year: Int) = (if (year < 0) "-" else "") + "%04d".format(Locale.ROOT, Math.abs(year))

    // ----- The inputs -----

    /** A float argument, of at most 15 significant digits: see the class. */
    private fun float(d: Double): Double {
        require(d.isFinite() && BigDecimal(d.toString()).precision() <= 15) { "$d has more than 15 significant digits: SQLite's text of it isn't Java's." }
        return d
    }

    private val EDGE_YEARS = listOf(
        -999_999_999, -999_999_998, -100_000, -10_000, -9999, -1001, -1000, -999, -401, -400, -101, -100, -45, -44, -5, -4, -1, 0,
        1, 4, 99, 100, 999, 1000, 1582, 1600, 1700, 1752, 1900, 1969, 1970, 1999, 2000, 2004, 2020, 2021, 2024, 2025, 2026, 2100,
        2262, 2263, 9999, 10_000, 99_999, 999_999_998, 999_999_999,
    )

    /** Dates of [year] that are edges: of the year, its months, its weeks (the ISO week-based year turns near them), and February's. */
    private fun edgeDates(year: Int, random: Random): List<LocalDate> {
        val leap = java.time.Year.isLeap(year.toLong())
        val days = listOf(1 to 1, 1 to 2, 1 to 3, 1 to 4, 1 to 5, 2 to 28, 3 to 1, 3 to 31, 4 to 1, 6 to 30, 7 to 1, 9 to 30, 10 to 1, 12 to 28, 12 to 29, 12 to 30, 12 to 31) +
            (if (leap) listOf(2 to 29) else emptyList())
        val random1 = LocalDate.of(year, random.nextInt(1, 13), 1).let { it.withDayOfMonth(random.nextInt(1, it.lengthOfMonth() + 1)) }
        return days.map { (m, d) -> LocalDate.of(year, m, d) } + random1
    }

    private val TIMES = listOf(LocalTime.MIDNIGHT, LocalTime.of(23, 59, 59, 999_999_000), LocalTime.of(12, 34, 56, 789_012_000), LocalTime.of(0, 0, 0, 1000))

    private fun randomTime(random: Random): LocalTime =
        LocalTime.of(random.nextInt(24), random.nextInt(60), random.nextInt(60), random.nextInt(1_000_000) * 1000)

    /** The timestamps the operations of one or two of them are worked out on: edges of years and of the range, and random ones. */
    private fun clocks(random: Random): List<LocalDateTime> = buildList {
        for (year in EDGE_YEARS) {
            val dates = edgeDates(year, random)
            for (date in dates) add(LocalDateTime.of(date, TIMES[random.nextInt(TIMES.size)]))
            add(LocalDateTime.of(dates.first(), LocalTime.MIDNIGHT))
            add(LocalDateTime.of(dates.last(), LocalTime.of(23, 59, 59, 999_999_000)))
        }
        add(LocalDateTime.MIN)
        add(LocalDateTime.MAX.truncatedTo(ChronoUnit.MICROS))
        repeat(400) {
            val year = if (it % 4 == 0) random.nextInt(-999_999_999, 1_000_000_000) else random.nextInt(-3000, 3000)
            val date = LocalDate.of(year, random.nextInt(1, 13), 1).let { d -> d.withDayOfMonth(random.nextInt(1, d.lengthOfMonth() + 1)) }
            add(LocalDateTime.of(date, randomTime(random)))
        }
    }

    /**
     * The texts of zoned values at the moments zones change their offset (daylight saving, and Pacific/Apia's skipped day),
     * at the offsets on either side: `local` and `utc` read fixed offsets alone, and these are the offsets the world uses,
     * of hours and minutes, and before 1900 of seconds too (a local mean time), which are no temporal value (drivine4j#424).
     */
    private fun zoneTexts(): List<String> = buildList {
        val zones = listOf(
            "Europe/London", "America/New_York", "Australia/Lord_Howe", "Asia/Kathmandu", "America/St_Johns", "Pacific/Apia",
            "Pacific/Chatham", "Europe/Dublin", "Africa/Casablanca", "Asia/Kolkata", "America/Sao_Paulo", "Europe/Amsterdam",
        )
        for (zone in zones) {
            val rules = ZoneId.of(zone).rules
            var at = Instant.parse("1840-01-01T00:00:00Z")
            var count = 0
            while (count < 25) {
                val transition = rules.nextTransition(at) ?: break
                if (transition.instant.isAfter(Instant.parse("2030-01-01T00:00:00Z"))) break
                for (instant in listOf(transition.instant.minusNanos(1000), transition.instant, transition.instant.plusSeconds(1800))) {
                    for (offset in listOf(transition.offsetBefore, transition.offsetAfter)) {
                        add(OffsetDateTime.ofInstant(instant, offset).toString())
                    }
                }
                at = transition.instant.plusSeconds(1)
                // A zone's early transitions are few and far apart; those since 1970 are kept a sample of.
                if (transition.instant.isAfter(Instant.parse("1970-01-01T00:00:00Z"))) at = at.plus(Duration.ofDays(random.nextLong(200, 2000)))
                count++
            }
        }
    }

    private val random = Random(421)

    /** Local and zoned texts of every kind, as `local` and `utc` are given them, read or refused. */
    private fun texts(random: Random): List<Pair<String, String>> = buildList {
        val years = listOf("0000", "0001", "0999", "1582", "1900", "1970", "2000", "2024", "2025", "9999", "-0001", "-0044", "-0400", "-9999", "-0000", "+10000", "+999999999", "-999999999", "10000", "+0000002024", "-0000000044")
        val dates = listOf("01-01", "02-28", "02-29", "02-30", "03-01", "04-31", "12-31", "13-01", "00-10", "06-00")
        val times = listOf("00:00", "23:59", "24:00", "12:60", "10:30:00", "23:59:59", "23:59:60", "10:30:22")
        val fractions = listOf("", ".", ".1", ".12", ".123", ".1234", ".12345", ".123456", ".1234567", ".12345678", ".123456789", ".1234567891", ".999999999", ".9999999", ".000000001", ".0000001")
        val offsets = listOf("Z", "+00:00", "-00:00", "+0000", "+01:00", "-0130", "+05:45", "-03:30", "+14:00", "-12:00", "+18:00", "-18:00", "+19:00", "+02:05:59", "+020559", "+02", "+2:00", "z", "+99:99", "-00:01:15")
        for (y in years) for (d in dates) add("DATE" to "$y-$d")
        for (t in times) for (f in fractions) {
            if (f.isNotEmpty() && t.count { it == ':' } < 2) continue
            add("LOCAL_TIME" to "$t$f")
            for (o in offsets) add("TIME" to "$t$f$o")
        }
        repeat(3000) {
            val text = "${years.random(random)}-${dates.random(random)}T${times.random(random)}" + if (random.nextBoolean()) fractions.random(random) else ""
            add("LOCAL_DATE_TIME" to text)
            add("DATE_TIME" to text + offsets.random(random))
        }
        for (text in listOf("", "2024", "2024-1-1", "12", "T12:00", "2024-01-01T", "2024-01-01 12:00", "12:00+1", "P1D", "12:00:00.", "2024-03-15T10:30:00.1234567", "2024-03-15t10:30:00", "2024-03-15T10:30:00Z\n", "10:30:00+01:00\n", "3000000000-01-01", "3000000000-01-01T00:00", "-9999999999-01-01T00:00Z")) {
            for (kind in Temporal.entries) add(kind.name to text)
        }
        for (text in zoneTexts()) {
            add("DATE_TIME" to text)
            add("DATE_TIME" to text.replace(Regex("([+-][0-9]{2}):([0-9]{2})$"), "$1$2"))
            add("TIME" to text.substringAfter('T'))
        }
    }

    /** Seconds `add_time` adds: edges of a day, of the ±292 years a long of nanoseconds holds, and of the whole range. */
    private val SECONDS = listOf(
        0L, 1L, -1L, 59L, 86_400L, -86_400L, 9_300_000_000L, -9_300_000_000L, 31_556_952_000L, -31_556_952_000L, 1_000_000_000_000L,
    )

    /** Nanoseconds `add_time` adds: around a microsecond, a second and a day, and any long, which it normalises. */
    private val NANOS = listOf(
        0L, 1L, -1L, 999L, 1000L, 1999L, -1999L, 999_999_999L, 1_000_000_000L, -1_000_000_001L, 86_400_000_000_000L, -86_400_000_000_001L,
        Long.MAX_VALUE, Long.MIN_VALUE, 1_234_567_890_123_456_789L,
    )

    private val TRUNCATIONS = TemporalSql.UNITS + listOf("nanosecond", "Year", "", "weekyear")
    private val FIELDS = listOf("YEAR", "MONTH", "DAY", "HOUR", "MINUTE", "NANOSECOND_OF_MINUTE", "SECOND", "year", "WEEK", "")
    private val CLOCK_PATTERNS = listOf(
        "YYYY-MM-DD", "HH24:MI:SS", "YYYY-MM-DD\"T\"HH24:MI:SS", "IYYY", "IW", "DDD", "YYYY", "MM", "DD", "HH24", "MI", "SS",
        "IYYY-\"W\"IW", "YYYY-DDD", "HH", "HH12", "Y", "D", "\"YYYY\"", "\"unclosed", "YYYYY", "DDDD", "IIW", "yyyy", "", "\"\"", "FX", "éYYYY😀",
    )
    private val NUMBER_PATTERNS = listOf(
        "FM0000", "FM00", "FM000000", "FM", "FM9999999999990.999999999", "FM0.999", "FM.99", "FM0.", "FM9", "FM0000000000000000000000", "FMx0y0",
    )

    /**
     * Decimal texts of exponents near an int's ends, and of many digits, which Java answers or fails on at once, but
     * which a power of ten written out would hang on: each is beyond `BigInteger`'s range (or `setScale`'s scale) or a
     * zero, or small enough for Java to write out. Java's wrapping `longValue` is in them too (`1e19`, `3e63`, `1e64`).
     */
    private val HUGE_DECIMALS = listOf(
        "1e2147483647", "-1e2147483647", "10e2147483647", "1e2147483640", "1e715827883", "-1e715827883", "1e-2147483647",
        "1e-2147483648", "1.5e-2147483646", "-1e-715827892", "0e999999999", "0e2147483647", "-0e-2147483647", "0.000e-2147483647",
        "1e19", "123e17", "-9.3e18", "3e63", "-3e63", "1e64", "1e10000", "-1e-10000", "9.5e-1", "-4e-1", "99.5", "0.99999999995",
        "7".repeat(5000), "-" + "3".repeat(5000) + "e-4990", "5" + "0".repeat(4999) + "e-5000",
    )

    /** The cases: each function over its inputs, by group. */
    private fun cases(random: Random): List<Map<String, Any?>> = buildList {
        fun case(function: String, group: String, vararg args: Any?) {
            val entry = linkedMapOf<String, Any?>("function" to function, "group" to group, "args" to args.map(::sql))
            when (val out = answer(function, args.toList())) {
                is Out.Value -> entry["result"] = sql(out.value)
                is Out.Failed -> {
                    entry["error"] = out.message
                    entry["errorKey"] = out.key
                }
            }
            add(entry)
        }
        val clocks = clocks(random)
        val stamps = clocks.map(::write)

        // local and utc, of every kind's texts.
        for ((kind, text) in texts(random)) {
            case(TEMPORAL, "local", "local", text, kind)
            case(TEMPORAL, "utc", "utc", text, kind)
        }
        for (kind in listOf("DURATION", "date", "OTHER")) case(TEMPORAL, "local", "local", "2024-03-15", kind)

        // The operations of one timestamp.
        for (stamp in stamps) {
            for (field in FIELDS) if (field.isNotEmpty() || random.nextInt(20) == 0) case(TEMPORAL, "extract", "extract", stamp, field)
            case(TEMPORAL, "day_of_week", "day_of_week", stamp)
            for (unit in TRUNCATIONS) if (unit in TemporalSql.UNITS || random.nextInt(10) == 0) case(TEMPORAL, "truncate", "truncate", stamp, unit)
            for (months in listOf(1L, -1L, 12L, -12L, 13L, random.nextLong(-30_000, 30_000))) case(TEMPORAL, "add_months", "add_months", stamp, months)
            for (n in NANOS.shuffled(random).take(4)) {
                case(TEMPORAL, "add_time", "add_time", stamp, random.nextLong(-400, 400), SECONDS[random.nextInt(SECONDS.size)], n)
            }
            case(TEMPORAL, "add_time", "add_time", stamp, 0L, SECONDS.random(random), 0L)
            case(TEMPORAL, "add_time", "add_time", stamp, 0L, random.nextLong(-20_000_000_000, 20_000_000_000), random.nextLong(0, 1_000_000_000))
            case(TEMPORAL, "add_time", "add_time", stamp, 0L, 0L, 0L)
            for (pattern in CLOCK_PATTERNS.shuffled(random).take(4)) case(TO_CHAR, "to_char clock", stamp, pattern)
            case(TO_CHAR, "to_char clock", stamp, "YYYY-MM-DD\"T\"HH24:MI:SS IYYY-IW DDD")
        }
        // Each pattern of a clock on the edges of each year.
        for (stamp in stamps.take(EDGE_YEARS.size * 20)) for (pattern in listOf("IYYY-IW", "DDD", "YYYY-MM-DD")) case(TO_CHAR, "to_char clock", stamp, pattern)
        for (pattern in CLOCK_PATTERNS) case(TO_CHAR, "to_char clock", "2024-03-15T10:30:05.000000", pattern)

        // Two timestamps: near each other, across eras, and far apart (past the nanoseconds a long holds: ±292 years).
        repeat(4000) {
            val a = clocks.random(random)
            // Near the range's ends, a moment past it is none: the other is then any.
            val b = try {
                when (random.nextInt(4)) {
                    0 -> clocks.random(random)
                    1 -> a.plusNanos(random.nextLong(-100_000_000_000_000, 100_000_000_000_000)).truncatedTo(ChronoUnit.MICROS)
                    2 -> a.plusMonths(random.nextLong(-40, 40)).plusSeconds(random.nextLong(-100_000, 100_000))
                    else -> a.plusYears(random.nextLong(-300, 300))
                }
            } catch (e: DateTimeException) {
                clocks.random(random)
            }
            case(TEMPORAL, "months_between", "months_between", write(a), write(b))
            case(TEMPORAL, "seconds_between", "seconds_between", write(a), write(b))
            case(TEMPORAL, "nanos_between", "nanos_between", write(a), write(b))
        }
        // A day's end and a leap year's day, each way.
        for ((a, b) in listOf(
            "2024-02-28T23:59:59.999999" to "2024-03-01T00:00:00.000000", "2023-01-31T00:00:00.000000" to "2023-02-28T00:00:00.000000",
            "2024-01-31T00:00:00.000000" to "2024-02-29T00:00:00.000000", "1677-09-21T00:12:43.145224" to "1970-01-01T00:00:00.000000",
            "1970-01-01T00:00:00.000000" to "2262-04-11T23:47:16.854775", "1970-01-01T00:00:00.000000" to "2262-04-11T23:47:16.854776",
            "-0044-03-15T00:00:00.000000" to "0000-01-01T00:00:00.000000", "0001-01-01T00:00:00.000000" to "9999-12-31T23:59:59.999999",
            "-999999999-01-01T00:00:00.000000" to "+999999999-12-31T23:59:59.999999", "2024-03-15T10:30:00.000001" to "2024-03-15T10:30:00.000000",
        )) {
            for ((x, y) in listOf(a to b, b to a)) {
                case(TEMPORAL, "months_between", "months_between", x, y)
                case(TEMPORAL, "seconds_between", "seconds_between", x, y)
                case(TEMPORAL, "nanos_between", "nanos_between", x, y)
            }
        }

        // Arguments past the calendar's range, of other types, null, and malformed.
        for (stamp in listOf("+999999999-12-31T23:59:59.999999", "-999999999-01-01T00:00:00.000000", "2024-03-15T10:30:00.000000")) {
            for (months in listOf(Long.MAX_VALUE, Long.MIN_VALUE, 12_000_000_000L, -12_000_000_000L, 1L, -1L)) case(TEMPORAL, "add_months", "add_months", stamp, months)
            for (days in listOf(Long.MAX_VALUE, Long.MIN_VALUE, 365_243_219_162L, -365_243_219_162L, 1L, -1L)) case(TEMPORAL, "add_time", "add_time", stamp, days, 0L, 0L)
            for (seconds in listOf(Long.MAX_VALUE, Long.MIN_VALUE, 31_556_889_864_403_199L, -31_556_889_864_403_199L, 1L, -1L)) case(TEMPORAL, "add_time", "add_time", stamp, 0L, seconds, 0L)
            for (nanos in listOf(Long.MAX_VALUE, Long.MIN_VALUE, 1L, -1L)) case(TEMPORAL, "add_time", "add_time", stamp, 0L, 0L, nanos)
            case(TEMPORAL, "add_time", "add_time", stamp, 1L, Long.MAX_VALUE, Long.MAX_VALUE)
            case(TEMPORAL, "add_time", "add_time", stamp, -1L, Long.MIN_VALUE, Long.MIN_VALUE)
            for (unit in listOf("weekYear", "millennium", "week")) case(TEMPORAL, "truncate", "truncate", stamp, unit)
        }
        for (amount in listOf("2.5", "-2.5", "2.4", "1e3", "0.5", "-0.5", "x", "", " 1", "\u0661\u0662.\u0665") + HUGE_DECIMALS) {
            case(TEMPORAL, "add_months", "add_months", "2024-01-31T00:00:00.000000", amount)
            case(TEMPORAL, "add_time", "add_time", "2024-01-31T00:00:00.000000", amount, 0L, 0L)
            case(TEMPORAL, "add_time", "add_time", "2024-01-31T00:00:00.000000", 0L, amount, 0L)
            case(TEMPORAL, "add_time", "add_time", "2024-01-31T00:00:00.000000", 0L, 0L, amount)
        }
        for (amount in listOf(2.5, -2.5, 1.5, 0.5, -0.5, 1e3, 1.25e9, 123456789.123456)) {
            case(TEMPORAL, "add_months", "add_months", "2024-01-31T00:00:00.000000", float(amount))
            case(TEMPORAL, "add_time", "add_time", "2024-01-31T00:00:00.000000", float(amount), 0L, 0L)
            case(TEMPORAL, "add_time", "add_time", "2024-01-31T00:00:00.000000", 0L, float(amount), 0L)
            case(TEMPORAL, "add_time", "add_time", "2024-01-31T00:00:00.000000", 0L, 0L, float(amount))
        }
        for (op in listOf("extract", "day_of_week", "add_months", "add_time", "months_between", "seconds_between", "nanos_between", "truncate", "local", "utc")) {
            val args: List<Any?> = when (op) {
                "extract" -> listOf("2024-03-15T10:30:00.000000", "YEAR")
                "day_of_week" -> listOf("2024-03-15T10:30:00.000000")
                "add_months" -> listOf("2024-03-15T10:30:00.000000", 1L)
                "add_time" -> listOf("2024-03-15T10:30:00.000000", 1L, 1L, 1L)
                "months_between", "seconds_between", "nanos_between" -> listOf("2024-03-15T10:30:00.000000", "2025-03-15T10:30:00.000000")
                "truncate" -> listOf("2024-03-15T10:30:00.000000", "day")
                else -> listOf("2024-03-15T10:30:00", "LOCAL_DATE_TIME")
            }
            for (i in args.indices) case(TEMPORAL, "null", op, *args.toMutableList().also { it[i] = null }.toTypedArray())
            if (op != "local" && op != "utc") {
                for (bad in listOf("2024-02-30T00:00:00.000000", "2024-03-15", "x", "2024-03-15 10:30:00.000000", "2024-03-15T10:30", "2024-03-15T10:30:00.1234567891", "+10000-01-01T00:00:00.000000", "10000-01-01T00:00:00.000000", "2024-03-15t10:30:00.000000", "2024-03-15T10:30:59.999999999", "+0000002024-03-15T10:30:00.000000", "3000000000-03-15T10:30:00.000000")) {
                    case(TEMPORAL, "timestamps", op, bad, *args.drop(1).toTypedArray())
                }
            }
        }
        case(TEMPORAL, "operations", "nothing", "2024-03-15T10:30:00.000000")
        case(TEMPORAL, "operations", null, "2024-03-15T10:30:00.000000")
        case(TEMPORAL, "operations", "LOCAL", "2024-03-15", "DATE")

        // drivine_trunc: whole numbers, and floats near a whole number, of every size.
        for (n in listOf(0L, 1L, -1L, Long.MAX_VALUE, Long.MIN_VALUE)) case(TRUNC, "trunc", n)
        case(TRUNC, "trunc", null)
        val floats = mutableListOf(
            0.0, -0.0, 0.5, -0.5, 1.5, -1.5, 2.7, -2.7, 1e15, 1e16, 1e19, -1e19, 9.223372036854776E18, -9.223372036854776E18, 9.2233720368547748E18,
            Double.MAX_VALUE, -Double.MAX_VALUE, Double.MIN_VALUE, Double.POSITIVE_INFINITY, Double.NEGATIVE_INFINITY,
            33.001 * 1_000_000, 0.1 * 3 * 10, 22.142 * 1000, -22.142 * 1000, 0.29 * 100, 1.1 * 1_000_000_000, 4.35 * 100, -4.35 * 100,
        )
        repeat(3000) {
            floats += when (random.nextInt(5)) {
                0 -> Double.fromBits(random.nextLong()).takeIf { it.isFinite() } ?: 1.5
                1 -> random.nextInt(-1_000_000, 1_000_000) / 1000.0 * listOf(1.0, 1000.0, 1_000_000.0, 1e9).random(random)
                2 -> Math.nextUp(random.nextLong(-1_000_000_000, 1_000_000_000).toDouble()) * if (random.nextBoolean()) 1 else -1
                3 -> Math.nextDown(random.nextLong(-1_000_000_000, 1_000_000_000).toDouble())
                else -> random.nextDouble(-1e6, 1e6)
            }
        }
        for (x in floats) for (y in listOf(x, Math.nextUp(x), Math.nextDown(x))) if (!y.isNaN()) case(TRUNC, "trunc", y)

        // drivine_to_char of numbers.
        val numbers: List<Any> = listOf(
            0L, 1L, -1L, 12L, -5L, 123456L, Long.MAX_VALUE, Long.MIN_VALUE, float(2.5), float(-2.5), float(2.0), float(0.5), float(-0.5),
            float(0.0005), float(-0.0004), float(1e-10), float(-1e-10), float(123.456), float(1e20), float(-1e15), float(99.9995),
            "2.5", "-2.5", "0.4999999999", "1.0000000005", "-0.0000000004", "1e3", "12345678901234567890.123456789123", "x", "", "2024-03-15T10:30:00.000000",
            // Any Unicode decimal digits, as BigDecimal reads them: Arabic-Indic and fullwidth.
            "\u0661\u0662.\u0665", "-\uff11\uff12e\uff11",
        ) + HUGE_DECIMALS + List(1500) {
            when (random.nextInt(3)) {
                0 -> random.nextLong(-1_000_000, 1_000_000)
                1 -> float(BigDecimal(random.nextLong(-1_000_000_000_000, 1_000_000_000_000)).movePointLeft(random.nextInt(0, 13)).toDouble())
                else -> BigDecimal(random.nextLong()).movePointLeft(random.nextInt(0, 25)).toPlainString()
            }
        }
        for (n in numbers) for (pattern in NUMBER_PATTERNS) if (n !is Long || pattern != "FMx0y0" || random.nextBoolean()) case(TO_CHAR, "to_char number", n, pattern)
        case(TO_CHAR, "to_char null", null, "FM00")
        case(TO_CHAR, "to_char null", "2024-03-15T10:30:00.000000", null)
        case(TO_CHAR, "to_char null", null, null)
        case(TO_CHAR, "to_char clock", 20240315L, "YYYY")
        case(TO_CHAR, "to_char clock", float(2.5), "YYYY")
        case(TO_CHAR, "to_char clock", "3000000000-03-15T10:30:00.000000", "YYYY")
    }

    /** A SQL value as the file writes it: a float as `{$float: …}`, so a whole one is not read as a whole number. */
    private fun sql(value: Any?): Any? = when (value) {
        is Double -> mapOf("\$float" to value.toString())
        null, is String, is Long -> value
        is Int -> value.toLong()
        else -> error("No SQL value $value")
    }

    // ----- host.yaml -----

    /** The value of a host.yaml step's argument or result, as [answer] takes it. */
    private fun stepValue(node: JsonNode): Any? = when {
        node.isNull -> null
        node.isTextual -> node.textValue()
        node.isIntegralNumber -> node.longValue()
        node.isNumber -> node.doubleValue()
        node.isObject && node.has("\$float") -> node["\$float"].textValue().toDouble()
        else -> error("No SQL value of $node")
    }

    /**
     * host.yaml's steps of the three functions, against [answer]: each gives the step's result or fails with its error,
     * but a step held for layout 2, which must not. Returns how many steps were checked; throws naming each that disagrees.
     */
    fun checkHostYaml(spec: File): Int {
        val wrong = mutableListOf<String>()
        var checked = 0
        for (case in yaml.readTree(File(spec, "host.yaml"))["cases"]) for ((index, step) in case["steps"].withIndex()) {
            val function = step["function"]?.textValue() ?: continue
            if (function !in setOf(TEMPORAL, TRUNC, TO_CHAR) || !step.has("args")) continue
            checked++
            val where = "host.yaml ${case["name"].textValue()}, step ${index + 1}: $function${step["args"]}"
            val out = answer(function, step["args"].map(::stepValue))
            val held = step["note"]?.textValue()?.contains(HELD_NOTE) == true
            val agrees = when (out) {
                is Out.Value -> step.has("result") && stepValue(step["result"]).let { it == out.value || (it is Double && out.value is Double && it.equals(out.value)) }
                is Out.Failed -> step.has("error") && out.message.contains(step["error"].textValue())
            }
            if (held && agrees) wrong += "$where is held for layout 2, but Java's answer in layout 1 is the step's: $out"
            if (!held && !agrees) wrong += "$where: Java gives $out"
        }
        check(wrong.isEmpty()) { "host.yaml's temporal steps disagree with java.time:\n" + wrong.joinToString("\n") }
        return checked
    }

    /** Writes `host-temporal.json` into [out], and checks host.yaml's steps: how many cases of each group. */
    fun write(spec: File, out: File): Map<String, Int> {
        val steps = checkHostYaml(spec)
        val cases = cases(Random(108))
        val groups = cases.groupingBy { "${it["function"]} ${it["group"]}" }.eachCount().toSortedMap()
        val written = linkedMapOf(
            "jdk" to System.getProperty("java.runtime.version"),
            "groups" to groups,
            "cases" to cases,
        )
        // Every character outside ASCII escaped, so a lone surrogate is itself.
        val ascii = ObjectMapper().also { it.factory.enable(com.fasterxml.jackson.core.JsonGenerator.Feature.ESCAPE_NON_ASCII) }
        File(out, "host-temporal.json").writeText(ascii.writeValueAsString(written))
        println("host-temporal.json: ${cases.size} cases ($groups); $steps host.yaml steps agree with java.time.")
        return groups
    }

    /** The spec's directory, and the directory to write into. */
    @JvmStatic
    fun main(args: Array<String>) {
        write(File(args[0]), File(args[1]).apply { mkdirs() })
    }
}
