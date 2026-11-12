package org.drivine.cypher.time

import com.ionspin.kotlin.bignum.integer.BigInteger
import kotlinx.datetime.DayOfWeek
import kotlinx.datetime.LocalDate
import kotlinx.datetime.isoDayNumber
import kotlinx.datetime.number

/**
 * SPIKE: the few java.time calls the compiler made, in common code: offsets as `ZoneOffset.of` reads them, and the calendar
 * dates `date({year, week})` and the like give.
 */
object CommonTime {
    /** As java.time's `DateTimeException`: a temporal the statement writes that is no date or time. */
    class TemporalException(message: String) : IllegalArgumentException(message)

    /**
     * The id `ZoneOffset.of` gives [zone] where it is an offset: `Z`, `+h`, `+hh`, `+hh:mm`, `+hhmm`, `+hh:mm:ss`, `+hhmmss`,
     * up to 18 hours; null where it is none. Its colons are both there or neither, as `ZoneOffset.of` reads it by its length.
     */
    fun offsetId(zone: String): String? {
        if (zone == "Z") return "Z"
        if (zone.length == 8) return null
        val m = Regex("([+-])([0-9]{1,2})(?::?([0-9]{2})(?::?([0-9]{2}))?)?").matchEntire(zone) ?: return null
        val (sign, h, mm, ss) = m.destructured
        if (h.length == 1 && mm.isNotEmpty()) return null
        val hours = h.toInt(); val minutes = mm.ifEmpty { "0" }.toInt(); val seconds = ss.ifEmpty { "0" }.toInt()
        if (hours > 18 || minutes > 59 || seconds > 59 || (hours == 18 && (minutes > 0 || seconds > 0))) return null
        if (hours == 0 && minutes == 0 && seconds == 0) return "Z"
        fun two(n: Int) = n.toString().padStart(2, '0')
        return "$sign${two(hours)}:${two(minutes)}" + if (seconds != 0) ":${two(seconds)}" else ""
    }

    /**
     * Whether [zone] is a zone by name, as `ZoneId.of` takes one: a region the pinned JVM's tzdb knows, or `UTC`, `GMT` or
     * `UT`, alone or before an offset (`UTC+1`), which `ZoneId.of` reads as a region of that offset's rules, though no
     * list of zone ids holds it. [known] tells a region from none (the stand-in fuzz gives it the table off the JVM on each target).
     */
    fun isRegion(zone: String, known: (String) -> Boolean = ::knownRegion): Boolean {
        val prefix = listOf("UTC", "GMT", "UT").firstOrNull { zone.startsWith(it) }
        val offset = prefix?.let { zone.substring(it.length) }
        if (offset != null && (offset.isEmpty() || offset[0] == '+' || offset[0] == '-')) return offset.isEmpty() || offsetId(offset) != null
        return Regex("[A-Za-z][A-Za-z0-9~/._+-]+").matches(zone) && known(zone)
    }

    fun date(year: Int, month: Int, day: Int): LocalDate = try {
        LocalDate(year, month, day)
    } catch (e: IllegalArgumentException) {
        throw TemporalException(e.message ?: "Invalid date")
    }

    /** As `ChronoField.YEAR.checkValidIntValue`. */
    fun checkYear(year: Long): Int {
        if (year !in -999_999_999L..999_999_999L) throw TemporalException("Invalid value for Year (valid values -999999999 - 999999999): $year")
        return year.toInt()
    }

    /**
     * As java.time's `plusDays`, and the message it refuses a date past its range with: by the day of the month where that
     * stays in the month or the next (checking the next year), else by the epoch day.
     */
    fun plusDays(date: LocalDate, days: Long): LocalDate {
        if (days == 0L) return date
        val year = date.year; val month = date.month.number
        val dom = date.day + days
        if (dom in 1..59) {
            val length = lengthOfMonth(year, month)
            when {
                dom <= length -> return LocalDate(year, month, dom.toInt())
                month < 12 -> return LocalDate(year, month + 1, (dom - length).toInt())
                else -> return LocalDate(checkYear(year + 1L), 1, (dom - length).toInt())
            }
        }
        // Math.addExact, as Java's: a sum past a long's range fails, rather than wrapping back into the range.
        val epochDay = date.toEpochDays().let { from -> (from + days).also { if (((from xor it) and (days xor it)) < 0) throw ArithmeticException("long overflow") } }
        if (epochDay !in MIN_EPOCH_DAY..MAX_EPOCH_DAY) {
            throw TemporalException("Invalid value for EpochDay (valid values $MIN_EPOCH_DAY - $MAX_EPOCH_DAY): $epochDay")
        }
        return LocalDate.fromEpochDays(epochDay)
    }

    /** `ChronoField.EPOCH_DAY`'s range: -999999999-01-01 to +999999999-12-31. */
    private const val MIN_EPOCH_DAY = -365243219162L
    private const val MAX_EPOCH_DAY = 365241780471L

    /** The days of [month] in [year]. */
    private fun lengthOfMonth(year: Int, month: Int) = when (month) { 2 -> if (lengthOfYear(year) == 366) 29 else 28; 4, 6, 9, 11 -> 30; else -> 31 }

    /** The Monday of ISO week 1 of [year]. */
    fun isoWeekOne(year: Int): LocalDate {
        val jan4 = date(year, 1, 4)
        return plusDays(jan4, -(jan4.dayOfWeek.isoDayNumber - DayOfWeek.MONDAY.isoDayNumber).toLong())
    }

    /**
     * The message java.time's `LocalDate.parse`, `LocalTime.parse` or `LocalDateTime.parse` refuses [local] with, a text
     * that [org.drivine.cypher.sql.Temporal]'s patterns take (a date, a time, or both joined by `T`), and null where it
     * takes it. kotlinx-datetime refuses such a text with messages of its own, which aren't Java's, nor alike on the JVM
     * (where it adds the java.time exception's name) and the other targets.
     */
    fun javaParseError(local: String): String? {
        fun refused(reason: String) = "Text '$local' could not be parsed: $reason"
        // As java.time reads them: the year -0000 is none, and a fraction of more than nine digits is text it can't read,
        // in that order, as a text it stops reading in is refused before one it reads with text left over.
        if (local.startsWith("-0000-")) return "Text '$local' could not be parsed at index 0"
        val point = local.indexOf('.')
        if (point >= 0 && local.length - point - 1 > 9) return "Text '$local' could not be parsed, unparsed text found at index ${point + 10}"
        val date = Regex("^(-?[0-9]{4,})-([0-9]{2})-([0-9]{2})").find(local)
        if (date != null) {
            val (y, m, d) = date.destructured
            val year = y.toInt(); val month = m.toInt(); val day = d.toInt()
            if (month !in 1..12) return refused("Invalid value for MonthOfYear (valid values 1 - 12): $month")
            if (day !in 1..31) return refused("Invalid value for DayOfMonth (valid values 1 - 28/31): $day")
            val last = lengthOfMonth(year, month)
            if (day > last) {
                return refused(
                    if (month == 2 && day == 29) "Invalid date 'February 29' as '$year' is not a leap year"
                    else "Invalid date '${MONTHS[month - 1]} $day'",
                )
            }
        }
        val time = Regex("([0-9]{2}):([0-9]{2})(?::([0-9]{2}))?").find(if (date != null) local.substringAfter('T', "") else local)
        if (time != null) {
            val (h, m, sec) = time.destructured
            // In java.time's order: the minute before the hour, then the second (`25:60` is refused for its minute).
            if (m.toInt() > 59) return refused("Invalid value for MinuteOfHour (valid values 0 - 59): ${m.toInt()}")
            if (h.toInt() > 23) return refused("Invalid value for HourOfDay (valid values 0 - 23): ${h.toInt()}")
            if (sec.isNotEmpty() && sec.toInt() > 59) return refused("Invalid value for SecondOfMinute (valid values 0 - 59): ${sec.toInt()}")
        }
        return null
    }

    private val MONTHS = listOf(
        "JANUARY", "FEBRUARY", "MARCH", "APRIL", "MAY", "JUNE", "JULY", "AUGUST", "SEPTEMBER", "OCTOBER", "NOVEMBER", "DECEMBER",
    )

    fun lengthOfYear(year: Int): Int = if ((year % 4 == 0 && year % 100 != 0) || year % 400 == 0) 366 else 365

    /** The exact whole number a double that is a whole number is, as `BigDecimal(d).toBigInteger()` writes it. */
    fun exactWhole(d: Double): String {
        if (d > -9.0E18 && d < 9.0E18) return d.toLong().toString()
        val bits = d.toRawBits()
        val exponent = ((bits ushr 52) and 0x7ff).toInt() - 1075
        val mantissa = (bits and 0xfffffffffffffL) or 0x10000000000000L
        val magnitude = BigInteger.fromLong(mantissa).shl(exponent)
        return (if (d < 0) "-" else "") + magnitude.toString()
    }
}

/** Whether the pinned JVM's zone database knows the region [zone]. */
expect fun knownRegion(zone: String): Boolean
