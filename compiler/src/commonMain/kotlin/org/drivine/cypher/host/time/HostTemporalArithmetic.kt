package org.drivine.cypher.host.time

import com.ionspin.kotlin.bignum.decimal.BigDecimal
import com.ionspin.kotlin.bignum.decimal.DecimalMode
import com.ionspin.kotlin.bignum.decimal.RoundingMode
import kotlinx.datetime.LocalDateTime
import kotlinx.datetime.LocalTime
import org.drivine.cypher.host.HostArgs
import org.drivine.cypher.host.HostResult
import org.drivine.cypher.host.SqlType
import org.drivine.cypher.json.CypherJson
import org.drivine.cypher.json.ObjectNode
import org.drivine.cypher.sql.Temporal
import org.drivine.cypher.sql.TemporalArithmeticFunction
import org.drivine.cypher.sql.Temporals
import org.drivine.cypher.store.SqliteCypherValues
import org.drivine.cypher.store.SqliteTimestamps
import org.drivine.cypher.store.ValueLayout

/**
 * `drivine_temporal_arithmetic(operator, x, y)`: SQLite's function of temporal arithmetic (liberation-data/drivine4j#418), the
 * `+`, `-`, `*` or `/` of x and y where either is a tag of a temporal value read from the store, as Postgres and Oracle work it
 * out by the function they keep ([TemporalArithmeticFunction]). Their bodies are the compiler's inline arithmetic; SQLite keeps
 * none, so this works out the same steps the inline SQLite SQL takes, in the same order, by the same functions it calls
 * ([HostTemporal]): a moment moved by `add_months`, then `add_time` of the days, whole seconds and nanoseconds, each step
 * written as SQLite's timestamp is, to the microsecond, and the moment written as `Temporals.format` writes it; a duration's
 * parts summed or scaled, and written as `Temporals.durationText` writes them, exactly, to nine places. A test runs it beside the inline SQL
 * over generated values, so the inline builder stays what it is checked against.
 *
 * x and y are JSON texts, or SQL numbers. Either null, SQL's or JSON's, or an empty text, gives null. A pair of kinds Cypher has
 * no arithmetic of fails by name ([TemporalArithmeticFunction.UNDEFINED]), as does a date or a moment with a date whose year is past
 * 9999 or before -9999 ([Temporals.LONG_YEAR]), which the inline SQL refuses as it writes the moment. The answer is the tag the arithmetic makes, as
 * JSON text.
 */
internal object HostTemporalArithmetic {

    /** A side: a temporal value's kind and text, or a number, as its exact decimal. */
    private class Side(val kind: Temporal?, val v: String?, val number: BigDecimal?, val word: String)

    fun arithmetic(args: HostArgs, count: Int, result: HostResult) {
        require(count == 3) { "${TemporalArithmeticFunction.HOST}(operator, x, y) takes 3 arguments, not $count." }
        val operator = args.text(0)
        val x = side(args, 1) ?: return result.nullValue()
        val y = side(args, 2) ?: return result.nullValue()
        val (kind, v) = when {
            operator == "+" && x.kind.moment && y.kind == Temporal.DURATION -> x.kind!! to moved(x, y, 1)
            operator == "+" && x.kind == Temporal.DURATION && y.kind.moment -> y.kind!! to moved(y, x, 1)
            operator == "-" && x.kind.moment && y.kind == Temporal.DURATION -> x.kind!! to moved(x, y, -1)
            (operator == "+" || operator == "-") && x.kind == Temporal.DURATION && y.kind == Temporal.DURATION -> Temporal.DURATION to sum(x, y, if (operator == "+") 1 else -1)
            operator == "*" && x.kind == Temporal.DURATION && y.number != null -> Temporal.DURATION to times(x, y.number)
            operator == "*" && x.number != null && y.kind == Temporal.DURATION -> Temporal.DURATION to times(y, x.number)
            operator == "/" && x.kind == Temporal.DURATION && y.number != null -> Temporal.DURATION to divided(x, y.number)
            else -> return result.error("${TemporalArithmeticFunction.UNDEFINED} $operator, ${x.word} and ${y.word}")
        }
        result.text(CypherJson.writeValueAsString(ValueLayout.tag(kind, v)))
    }

    private val Temporal?.moment get() = this != null && this != Temporal.DURATION

    /** Division's quotient, `1 / k`, to more places than a store's number holds. */
    private val QUOTIENT = DecimalMode(40, RoundingMode.ROUND_HALF_AWAY_FROM_ZERO)

    /** The side at [i]: null where it is null, SQL's or JSON's, or an empty text. */
    private fun side(args: HostArgs, i: Int): Side? = when (args.type(i)) {
        SqlType.NULL -> null
        SqlType.INTEGER -> args.long(i).let { Side(null, null, BigDecimal.fromLong(it), "number") }
        SqlType.FLOAT -> args.double(i).let { Side(null, null, BigDecimal.parseString(it.toString()), "number") }
        else -> {
            // As the module's other functions of JSON values read one: an empty text is no value.
            val node = SqliteCypherValues.read(args.text(i)) ?: return null
            when {
                node.isNumber -> Side(null, null, BigDecimal.parseString(node.asText()), "number")
                node is ObjectNode && node[ValueLayout.KIND]?.isTextual == true -> {
                    val word = node[ValueLayout.KIND]!!.textValue()!!
                    Side(ValueLayout.kindOf(word), node[ValueLayout.TEXT]?.textValue(), null, word)
                }
                else -> Side(null, null, null, if (node.isTextual) "string" else if (node is ObjectNode) "map" else if (node.isBoolean) "boolean" else "list")
            }
        }
    }

    /**
     * The text of [moment]'s kind [duration] later, or with [sign] -1 earlier, as `Temporals.add` works it out on SQLite: the
     * months, then the days, the whole seconds and the nanoseconds; a date takes the whole days of the duration's time alone.
     */
    private fun moved(moment: Side, duration: Side, sign: Int): String {
        val kind = moment.kind!!
        val text = moment.v!!
        val (months, days, wholeSeconds, nanos) = exactParts(duration.v!!, sign)
        var clock = HostTemporal.local(text, kind)
        clock = timestamp(LocalDateTime(JavaTime.plusMonths(clock.date, months), clock.time))
        val (seconds, fraction) = if (kind == Temporal.DATE) {
            // A date takes the whole days of the duration's time alone, counted toward zero.
            val toward = when {
                wholeSeconds % 86400 == 0L && wholeSeconds > 0 && nanos < 0 -> 1
                wholeSeconds % 86400 == 0L && wholeSeconds < 0 && nanos > 0 -> -1
                else -> 0
            }
            (wholeSeconds / 86400 - toward) * 86400 to 0L
        } else wholeSeconds to nanos
        clock = LocalDateTime(JavaTime.plusDays(clock.date, days), clock.time)
        clock = JavaTime.plus(clock, seconds, 0)
        clock = timestamp(JavaTime.plus(clock, 0, fraction))
        // A year SQLite's text can't write fails by name, as the inline SQL's does where it writes the moment (#263): a time
        // of day writes no year, so it takes any.
        val dated = kind != Temporal.LOCAL_TIME && kind != Temporal.TIME
        if (dated && (clock.year > 9999 || clock.year < -9999)) throw IllegalArgumentException("${Temporals.LONG_YEAR} ${SqliteTimestamps.write(clock)}")
        return format(SqliteTimestamps.write(clock), kind, if (kind.zoned) offset(text) else null)
    }

    /**
     * [clock] as SQLite's timestamp holds it, to the microsecond: each primitive step writes one. [SqliteTimestamps.write]
     * cuts the nanoseconds to microseconds and changes nothing else, so the clock is cut here, not written and read again.
     */
    private fun timestamp(clock: LocalDateTime): LocalDateTime =
        LocalDateTime(clock.date, LocalTime(clock.hour, clock.minute, clock.second, clock.nanosecond / 1000 * 1000))

    /**
     * The duration text [text]'s months and days, whole as `add_months` and `add_time` read a number (half away from zero), and
     * its time as whole seconds and nanoseconds, as `Temporals.exactParts` reads them, each times [sign].
     */
    private fun exactParts(text: String, sign: Int): List<Long> {
        val groups = Temporal.DURATION.regex.matchEntire(text)!!.groupValues
        val textSign = if (groups[1] == "-") -1L else 1L
        // Whole years, months, weeks, days, hours and minutes, as a stored duration's are, add up as longs: the exact parts
        // below give the same, slower. A fraction of any, or a part too long for a long, takes them.
        val small = WHOLE_GROUPS.map { groups[it].let { g -> if (g.isEmpty()) 0L else if (isSmallWhole(g)) g.toLong() else null } }
        if (null !in small) {
            val (y, mo, w, d, h, mi) = small.map { it!! }
            val g = groups[16]
            val point = g.indexOf('.')
            val fraction = if (point >= 0) (g.substring(point + 1) + "000000000").take(9).toLong() else 0L
            val whole = (if (point >= 0) g.substring(0, point) else g).let { if (it == "-" || it.isEmpty()) 0L else it.toLongOrNull() }
            if (whole != null) {
                val wholeSeconds = textSign * (h * 3600 + mi * 60 + whole)
                val nanos = textSign * (if (g.startsWith("-")) -1 else 1) * fraction
                return listOf(sign * textSign * (y * 12 + mo), sign * textSign * (w * 7 + d), sign * wholeSeconds, sign * nanos)
            }
        }
        val (m0, d0, _) = Temporals.durationParts(text)
        fun whole(value: BigDecimal) = JavaDecimal.parse(value.toPlainString()).halfUp(0).wrappedLong()
        fun number(group: Int) = groups[group].ifEmpty { "0" }.let { BigDecimal.parseString(it) }
        val g = groups[16]
        val point = g.indexOf('.')
        val fraction = if (point >= 0) (g.substring(point + 1) + "000000000").take(9).toLong() else 0L
        val integral = if (g.isEmpty()) 0L else (if (point >= 0) g.substring(0, point) else g).let { if (it == "-" || it.isEmpty()) 0L else it.toLong() }
        val time = (number(12) * BigDecimal.fromInt(3600) + number(14) * BigDecimal.fromInt(60)).roundToDigitPositionAfterDecimalPoint(0, RoundingMode.TOWARDS_ZERO)
        val wholeSeconds = textSign * (time.longValue(exactRequired = false) + integral)
        val nanos = textSign * (if (g.startsWith("-")) -1 else 1) * fraction
        return listOf(sign * whole(m0), sign * whole(d0), sign * wholeSeconds, sign * nanos)
    }

    /** The offset the zoned text [text] ends with, as `Temporals.offset` gives it. */
    private fun offset(text: String): String {
        val written = Temporal.OFFSET_REGEX.find(text)?.value ?: "Z"
        return written.replace(Temporal.ZERO_OFFSET, "Z").replace(Temporal.OFFSET_WITHOUT_COLON, "$1:$2")
    }

    /** The text of [kind] of the SQLite timestamp [timestamp], with [offset] where zoned, as `Temporals.format` writes it. */
    private fun format(timestamp: String, kind: Temporal, offset: String?): String {
        val date = timestamp.substringBefore('T')
        val clock = timestamp.substringAfter('T')
        val micros = clock.substringAfter('.', "000000").padEnd(6, '0').take(6)
        val fraction = when {
            micros == "000000" -> ""
            micros.substring(3) == "000" -> "." + micros.substring(0, 3)
            else -> ".$micros"
        }
        val time = clock.substringBefore('.') + fraction
        return when (kind) {
            Temporal.DATE -> date
            Temporal.LOCAL_DATE_TIME -> "${date}T$time"
            Temporal.DATE_TIME -> "${date}T$time${offset ?: "Z"}"
            Temporal.LOCAL_TIME -> time
            Temporal.TIME -> "$time${offset ?: "Z"}"
            Temporal.DURATION -> error("a duration is no moment")
        }
    }

    /** The duration text of [x] and [y] added, or with [sign] -1 [y] taken from [x], as `Temporals.sum` writes it: to nine places. */
    private fun sum(x: Side, y: Side, sign: Int): String {
        val (m1, d1, s1) = Temporals.durationParts(x.v!!)
        val (m2, d2, s2) = Temporals.durationParts(y.v!!)
        val k = BigDecimal.fromInt(sign)
        return Temporals.durationText(m1 + m2 * k, d1 + d2 * k, s1 + s2 * k, PLACES)
    }

    /** The duration text of [duration] times [factor], as `Temporals.times` writes it: each part to nine places, as its `number` does. */
    private fun times(duration: Side, factor: BigDecimal): String {
        val (m, d, s) = Temporals.durationParts(duration.v!!)
        return Temporals.durationText(m * factor, d * factor, s * factor, PLACES)
    }

    /** The duration text of [duration] divided by [divisor], each part of it divided, as Cypher divides a duration. */
    private fun divided(duration: Side, divisor: BigDecimal): String {
        val (m, d, s) = Temporals.durationParts(duration.v!!)
        return Temporals.durationText(m.divide(divisor, QUOTIENT), d.divide(divisor, QUOTIENT), s.divide(divisor, QUOTIENT), PLACES)
    }

    /** The groups of [Temporal.DURATION] of its years, months, weeks, days, hours and minutes. */
    private val WHOLE_GROUPS = listOf(3, 5, 7, 9, 12, 14)

    /** Whether [group] is a whole number of at most 12 digits, signed or not: one that adds up as a long. */
    private fun isSmallWhole(group: String): Boolean {
        val digits = if (group.startsWith('-')) group.substring(1) else group
        return digits.length in 1..12 && digits.all { it in '0'..'9' }
    }

    private operator fun <T> List<T>.component6() = this[5]

    /** The places the builder's `number` writes a part of a duration to: nanoseconds. */
    private const val PLACES = 9L
}
