package org.drivine.cypher.host.time

import kotlinx.datetime.LocalDate
import kotlinx.datetime.LocalDateTime
import kotlinx.datetime.LocalTime
import kotlinx.datetime.isoDayNumber
import kotlinx.datetime.number
import org.drivine.cypher.time.CommonTime

/**
 * A text java.time's ISO parsers refuse: [parsed] is the text it was given. The message is a reason, not java.time's
 * wording, which a host function doesn't promise (host.yaml pins `'<text>' is no temporal value` and `Text '<text>'
 * could not be parsed` alone).
 */
internal class JavaParseException(val parsed: String, reason: String) : IllegalArgumentException(reason)

/**
 * The java.time that drivine4j's SQLite temporal functions call, in common code, on kotlinx-datetime's values alone: its
 * ISO parsers' acceptance, and its calendar arithmetic with its range checks and their messages, where kotlinx-datetime's
 * own parse and arithmetic check otherwise and word it otherwise. The JVM's `HostTemporals` oracle pins each against
 * java.time, on every target (HostTemporalOracleTest).
 */
internal object JavaTime {

    private const val NANOS_PER_SECOND = 1_000_000_000L
    private const val SECONDS_PER_DAY = 86_400L
    private const val NANOS_PER_DAY = SECONDS_PER_DAY * NANOS_PER_SECOND

    // ----- Parsing, as DateTimeFormatter.ISO_LOCAL_DATE, ISO_LOCAL_TIME, ISO_LOCAL_DATE_TIME and ISO_OFFSET_DATE_TIME -----

    /** A text being read, from [at]: each read gives null where java.time's parser stops. */
    private class Cursor(val text: String) {
        var at = 0

        val done get() = at == text.length

        fun take(c: Char, ignoreCase: Boolean = false): Boolean =
            (at < text.length && text[at].equals(c, ignoreCase)).also { if (it) at++ }

        /** Up to [max] ASCII digits, at least [min]: java.time's standard decimal style reads no other digits. */
        fun digits(min: Int, max: Int): String? {
            val start = at
            while (at < text.length && at - start < max && text[at] in '0'..'9') at++
            return if (at - start >= min) text.substring(start, at) else null.also { at = start }
        }
    }

    private fun refuse(text: String, reason: String? = null): Nothing =
        throw JavaParseException(text, reason ?: CommonTime.javaParseError(text) ?: "Text '$text' could not be parsed")

    /**
     * ISO_LOCAL_DATE's date at [c]: the year `appendValue(YEAR, 4, 10, EXCEEDS_PAD)` reads strictly, four digits unsigned,
     * a `+` before more, a `-` before any but zero, and within YEAR's range; then `-MM-dd`, a date of the year.
     */
    private fun date(c: Cursor): LocalDate? {
        val negative = c.take('-')
        val positive = !negative && c.take('+')
        val digits = c.digits(4, 10) ?: return null
        val year = digits.toLong()
        if (negative && year == 0L) return null
        if (positive && digits.length <= 4) return null
        if (!negative && !positive && digits.length > 4) return null
        if (!c.take('-')) return null
        val month = c.digits(2, 2)?.toInt() ?: return null
        if (!c.take('-')) return null
        val day = c.digits(2, 2)?.toInt() ?: return null
        val signed = if (negative) -year else year
        if (signed !in -999_999_999L..999_999_999L || month !in 1..12 || day !in 1..31) return null
        if (day > lengthOfMonth(signed.toInt(), month)) return null
        return LocalDate(signed.toInt(), month, day)
    }

    /** ISO_LOCAL_TIME's time at [c]: `HH:mm`, then optionally `:ss`, then optionally a point and up to nine digits. */
    private fun time(c: Cursor): LocalTime? {
        val hour = c.digits(2, 2)?.toInt() ?: return null
        if (!c.take(':')) return null
        val minute = c.digits(2, 2)?.toInt() ?: return null
        var second = 0
        var nano = 0
        val beforeSeconds = c.at
        if (c.take(':')) {
            val s = c.digits(2, 2)
            if (s == null) {
                // The optional section is left unread, and what it held is text left over.
                c.at = beforeSeconds
            } else {
                second = s.toInt()
                if (c.take('.')) nano = (c.digits(0, 9) ?: "").padEnd(9, '0').toInt()
            }
        }
        if (hour > 23 || minute > 59 || second > 59) return null
        return LocalTime(hour, minute, second, nano)
    }

    /**
     * `appendOffsetId()`'s offset at [c], in seconds: `Z` (in either case, as ISO_OFFSET_DATE_TIME parses case-insensitively),
     * or `±HH:MM` and optionally `:ss`, within ±18:00.
     */
    private fun offset(c: Cursor): Int? {
        if (c.take('Z', ignoreCase = true)) return 0
        val sign = when {
            c.take('+') -> 1
            c.take('-') -> -1
            else -> return null
        }
        val hours = c.digits(2, 2)?.toInt() ?: return null
        if (!c.take(':')) return null
        val minutes = c.digits(2, 2)?.toInt() ?: return null
        var seconds = 0
        val beforeSeconds = c.at
        if (c.take(':')) seconds = c.digits(2, 2)?.toInt() ?: run { c.at = beforeSeconds; 0 }
        if (hours > 23 || minutes > 59 || seconds > 59) return null
        val total = hours * 3600 + minutes * 60 + seconds
        if (total > 18 * 3600) return null
        return sign * total
    }

    private fun <T> parse(text: String, read: (Cursor) -> T?): T {
        val c = Cursor(text)
        val value = read(c)
        if (value == null || !c.done) refuse(text)
        return value
    }

    /** `LocalDate.parse`. */
    fun parseDate(text: String): LocalDate = parse(text, ::date)

    /** `LocalTime.parse`. */
    fun parseTime(text: String): LocalTime = parse(text, ::time)

    /** `LocalDateTime.parse`: a date, `T` (or `t`), and a time. */
    fun parseDateTime(text: String): LocalDateTime = parse(text) { c ->
        val date = date(c) ?: return@parse null
        if (!c.take('T', ignoreCase = true)) return@parse null
        LocalDateTime(date, time(c) ?: return@parse null)
    }

    /** `OffsetDateTime.parse`: a date-time and its offset, as the wall clock and the offset's seconds. */
    fun parseOffsetDateTime(text: String): Pair<LocalDateTime, Int> = parse(text) { c ->
        val date = date(c) ?: return@parse null
        if (!c.take('T', ignoreCase = true)) return@parse null
        val time = time(c) ?: return@parse null
        LocalDateTime(date, time) to (offset(c) ?: return@parse null)
    }

    // ----- Arithmetic, as LocalDate's and LocalDateTime's -----

    /** `Math.addExact`. */
    fun addExact(a: Long, b: Long): Long {
        val sum = a + b
        if (((a xor sum) and (b xor sum)) < 0) throw ArithmeticException("long overflow")
        return sum
    }

    private fun floorDiv(a: Long, b: Long): Long = a / b - if ((a % b != 0L) && ((a xor b) < 0)) 1 else 0

    private fun floorMod(a: Long, b: Long): Long = a - floorDiv(a, b) * b

    fun lengthOfMonth(year: Int, month: Int): Int = when (month) {
        2 -> if (CommonTime.lengthOfYear(year) == 366) 29 else 28
        4, 6, 9, 11 -> 30
        else -> 31
    }

    /** `LocalDate.plusDays`. */
    fun plusDays(date: LocalDate, days: Long): LocalDate = CommonTime.plusDays(date, days)

    /**
     * `LocalDate.plusMonths`: the proleptic month moved (a long that wraps silently, as Java's does), its year checked, and
     * a day past the month's end its last day.
     */
    fun plusMonths(date: LocalDate, months: Long): LocalDate {
        if (months == 0L) return date
        val count = date.year * 12L + (date.month.number - 1) + months
        val year = CommonTime.checkYear(floorDiv(count, 12))
        val month = floorMod(count, 12).toInt() + 1
        return LocalDate(year, month, minOf(date.day, lengthOfMonth(year, month)))
    }

    /** `LocalDateTime.plusSeconds` and `plusNanos`: `plusWithOverflow`, its days carried into the date. */
    fun plus(clock: LocalDateTime, seconds: Long, nanos: Long): LocalDateTime {
        if (seconds == 0L && nanos == 0L) return clock
        var days = nanos / NANOS_PER_DAY + seconds / SECONDS_PER_DAY
        val current = clock.time.toNanosecondOfDay()
        val total = nanos % NANOS_PER_DAY + (seconds % SECONDS_PER_DAY) * NANOS_PER_SECOND + current
        days += floorDiv(total, NANOS_PER_DAY)
        val nanoOfDay = floorMod(total, NANOS_PER_DAY)
        val time = if (nanoOfDay == current) clock.time else LocalTime.fromNanosecondOfDay(nanoOfDay)
        return LocalDateTime(plusDays(clock.date, days), time)
    }

    /**
     * `ChronoUnit.MONTHS.between(from, to)`, as `LocalDateTime.until` counts: the end date a day nearer where its time of
     * day hasn't reached the start's, then whole months of `(packed end - packed start) / 32`, toward zero.
     */
    fun monthsBetween(from: LocalDateTime, to: LocalDateTime): Long {
        var end = to.date
        if (end > from.date && to.time < from.time) end = plusDays(end, -1)
        else if (end < from.date && to.time > from.time) end = plusDays(end, 1)
        fun packed(d: LocalDate) = (d.year * 12L + d.month.number - 1) * 32L + d.day
        return (packed(end) - packed(from.date)) / 32
    }

    /**
     * `Duration.between(from, to)`: its whole seconds, floored (`getSeconds`), and the nanoseconds past them, from 0 to
     * 999,999,999 (`getNano`), so the difference is exact over the whole range, where nanoseconds alone overflow a long.
     */
    fun between(from: LocalDateTime, to: LocalDateTime): Pair<Long, Long> {
        val days = to.date.toEpochDays() - from.date.toEpochDays()
        val nanos = to.time.toNanosecondOfDay() - from.time.toNanosecondOfDay()
        return days * SECONDS_PER_DAY + floorDiv(nanos, NANOS_PER_SECOND) to floorMod(nanos, NANOS_PER_SECOND)
    }

    // ----- The ISO week-based year, as IsoFields reads it -----

    /** `IsoFields.WEEK_BASED_YEAR` of [date]. */
    fun weekBasedYear(date: LocalDate): Int {
        var year = date.year
        var doy = date.dayOfYear
        val dow = date.dayOfWeek.isoDayNumber - 1
        if (doy <= 3) {
            if (doy - dow < -2) year--
        } else if (doy >= 363) {
            doy = doy - 363 - (if (CommonTime.lengthOfYear(year) == 366) 1 else 0)
            if (doy - dow >= 0) year++
        }
        return year
    }

    /** The weeks of the ISO week-based year [year]: 53 where it starts on a Thursday, or a Wednesday in a leap year. */
    private fun weeksOf(year: Int): Int {
        val dow = LocalDate(year, 1, 1).dayOfWeek.isoDayNumber
        return if (dow == 4 || (dow == 3 && CommonTime.lengthOfYear(year) == 366)) 53 else 52
    }

    /**
     * `IsoFields.WEEK_OF_WEEK_BASED_YEAR` of [date]. A day in the last week of the year before reads that year's weeks
     * from a day of it (`withDayOfYear(180).minusYears(1)`), which is past the range for the first days of the first year.
     */
    fun week(date: LocalDate): Int {
        val dow0 = date.dayOfWeek.isoDayNumber - 1
        val doy0 = date.dayOfYear - 1
        val doyThu0 = doy0 + (3 - dow0)
        val alignedWeek = doyThu0 / 7
        val firstThuDoy0 = doyThu0 - alignedWeek * 7
        var firstMonDoy0 = firstThuDoy0 - 3
        if (firstMonDoy0 < -3) firstMonDoy0 += 7
        if (doy0 < firstMonDoy0) return weeksOf(CommonTime.checkYear(date.year - 1L))
        var week = (doy0 - firstMonDoy0) / 7 + 1
        if (week == 53 && !(firstMonDoy0 == -3 || (firstMonDoy0 == -2 && CommonTime.lengthOfYear(date.year) == 366))) week = 1
        return week
    }

    /** `with(IsoFields.WEEK_OF_WEEK_BASED_YEAR, 1).with(ChronoField.DAY_OF_WEEK, 1)`: the Monday of the first week of [date]'s week-based year. */
    fun weekYearStart(date: LocalDate): LocalDate {
        val first = plusDays(date, (1L - week(date)) * 7)
        return plusDays(first, 1L - first.dayOfWeek.isoDayNumber)
    }
}
