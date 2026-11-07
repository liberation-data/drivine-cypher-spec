package org.drivine.cypher.time

import com.ionspin.kotlin.bignum.integer.BigInteger
import kotlinx.datetime.DatePeriod
import kotlinx.datetime.DayOfWeek
import kotlinx.datetime.LocalDate
import kotlinx.datetime.isoDayNumber
import kotlinx.datetime.plus

/**
 * SPIKE: the few java.time calls the compiler made, in common code: offsets as `ZoneOffset.of` reads them, and the calendar
 * dates `date({year, week})` and the like give.
 */
object CommonTime {
    /** As java.time's `DateTimeException`: a temporal the statement writes that is no date or time. */
    class TemporalException(message: String) : IllegalArgumentException(message)

    /**
     * The id `ZoneOffset.of` gives [zone] where it is an offset: `Z`, `+h`, `+hh`, `+hh:mm`, `+hhmm`, `+hh:mm:ss`, `+hhmmss`,
     * up to 18 hours; null where it is none.
     */
    fun offsetId(zone: String): String? {
        if (zone == "Z") return "Z"
        val m = Regex("([+-])([0-9]{1,2})(?::?([0-9]{2})(?::?([0-9]{2}))?)?").matchEntire(zone) ?: return null
        val (sign, h, mm, ss) = m.destructured
        if (h.length == 1 && mm.isNotEmpty()) return null
        val hours = h.toInt(); val minutes = mm.ifEmpty { "0" }.toInt(); val seconds = ss.ifEmpty { "0" }.toInt()
        if (hours > 18 || minutes > 59 || seconds > 59 || (hours == 18 && (minutes > 0 || seconds > 0))) return null
        if (hours == 0 && minutes == 0 && seconds == 0) return "Z"
        fun two(n: Int) = n.toString().padStart(2, '0')
        return "$sign${two(hours)}:${two(minutes)}" + if (seconds != 0) ":${two(seconds)}" else ""
    }

    /** Whether [zone] is a zone by name, as `ZoneId.of` takes one: where the platform has no zone database, any name of that shape. */
    fun isRegion(zone: String): Boolean = Regex("[A-Za-z][A-Za-z0-9~/._+-]+").matches(zone) && knownRegion(zone)

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

    fun plusDays(date: LocalDate, days: Long): LocalDate = try {
        date.plus(DatePeriod(days = days.toInt()))
    } catch (e: RuntimeException) {
        throw TemporalException(e.message ?: "Date out of range")
    }

    /** The Monday of ISO week 1 of [year]. */
    fun isoWeekOne(year: Int): LocalDate {
        val jan4 = date(year, 1, 4)
        return plusDays(jan4, -(jan4.dayOfWeek.isoDayNumber - DayOfWeek.MONDAY.isoDayNumber).toLong())
    }

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

/** Whether the zone database knows the region [zone]: true where the platform has none to ask. */
expect fun knownRegion(zone: String): Boolean
