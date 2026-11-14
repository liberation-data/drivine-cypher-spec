package org.drivine.cypher.host

import com.ionspin.kotlin.bignum.decimal.BigDecimal
import com.ionspin.kotlin.bignum.decimal.DecimalMode
import com.ionspin.kotlin.bignum.decimal.RoundingMode
import kotlinx.datetime.LocalDate
import kotlinx.datetime.LocalDateTime
import kotlinx.datetime.LocalTime
import org.drivine.cypher.host.time.HostTemporal
import org.drivine.cypher.host.time.JavaDecimal
import org.drivine.cypher.json.CypherJson
import org.drivine.cypher.json.ObjectNode
import org.drivine.cypher.sql.Temporal
import org.drivine.cypher.sql.TemporalArithmeticFunction
import org.drivine.cypher.store.SqliteTimestamps
import org.drivine.cypher.store.ValueLayout
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * The shortcuts `drivine_temporal_arithmetic` takes give what the longer way gives, on every target: a duration of whole
 * parts read as longs, its seconds rounded by bignum, and a clock cut to the microsecond in place of written and read
 * again. TemporalArithmeticHostTest checks the function against the inline SQL; this checks the shortcuts' edges.
 */
class HostTemporalArithmeticTest {

    private val arithmetic = HostFunctions.all.filter { it.name == TemporalArithmeticFunction.HOST }

    private fun tag(kind: Temporal, v: String) =
        CypherJson.writeValueAsString(ObjectNode().apply { put(ValueLayout.KIND, ValueLayout.word(kind)); put(ValueLayout.TEXT, v) })

    /** [moment] [operator] [duration]: its value, or its failure's message without the call it names. */
    private fun moved(operator: String, moment: Pair<Temporal, String>, duration: String): String =
        runCatching { callHost(arithmetic, listOf(operator, tag(moment.first, moment.second), tag(Temporal.DURATION, duration)), "") }
            .fold({ "$it" }, { "fails: ${it.message?.substringAfter("failed: ")}" })

    /** The duration of these parts, each null where absent. */
    private fun duration(sign: String, y: String?, mo: String?, w: String?, d: String?, h: String?, mi: String?, s: String?): String {
        fun part(value: String?, letter: String) = value?.let { it + letter } ?: ""
        val time = part(h, "H") + part(mi, "M") + part(s, "S")
        return sign + "P" + part(y, "Y") + part(mo, "M") + part(w, "W") + part(d, "D") + (if (time.isEmpty()) "" else "T$time")
    }

    @Test
    fun `a duration of whole parts moves a moment as its exact parts do`() {
        val moments = listOf(
            Temporal.DATE to "2024-02-29", Temporal.LOCAL_DATE_TIME to "2024-01-31T23:59:59.5",
            Temporal.DATE_TIME to "1999-12-31T23:59:59.123456+05:30", Temporal.LOCAL_TIME to "00:00:00.5",
        )
        val wholes = listOf(null, "0", "-0", "007", "-5", "999999999999", "-999999999999")
        val seconds = listOf(
            null, "0", "-0", "00", "007", "1.", ".5", "-.5", "-0.", "-1.5", "0.000000001", "1.1234567891", "59.999999999",
            "9223372036854775807", "9223372036854775808", ".", "-.", "1.2.3", "..",
        )
        val durations = mutableListOf<List<String?>>()
        for (sign in listOf("", "-")) {
            for (s in seconds) durations += listOf(sign, "1", "2", null, "3", "4", "5", s)
            for (i in 0 until 6) for (whole in wholes) durations += listOf(sign, null, null, null, null, null, null, "1.5").toMutableList().also { it[i + 1] = whole }
        }
        val differences = mutableListOf<String>()
        for ((sign, y, mo, w, d, h, mi, s) in durations.map { it.toTypedArray() }) {
            val short = duration(sign!!, y, mo, w, d, h, mi, s)
            // The same weeks, with thirteen digits: too long for the long path, so the exact one reads them.
            val exact = duration(sign, y, mo, (w ?: "0").let { if (it.startsWith('-')) "-" + it.drop(1).padStart(13, '0') else it.padStart(13, '0') }, d, h, mi, s)
            for (operator in listOf("+", "-")) for (moment in moments) {
                val (got, expected) = moved(operator, moment, short) to moved(operator, moment, exact)
                if (got != expected) differences += "$moment $operator $short: $got, where $exact gives $expected"
            }
        }
        assertEquals(emptyList(), differences)
    }

    @Test
    fun `a duration's seconds are rounded by bignum as java_math's HALF_UP rounds them`() {
        val quotient = DecimalMode(40, RoundingMode.ROUND_HALF_AWAY_FROM_ZERO)
        val values = listOf(
            "0", "-0", "0.0000000004", "0.0000000005", "-0.0000000005", "0.00000000049", "5e-1000", "1.0000000005", "-1.0000000005",
            "2.5", "9.9999999995", "-9.9999999995", "0.9999999995", "86399.9999999995", "-0.0000000015", "1e40", "-1e100", "1234567890123",
            "123456789012345678901234567890.1234567895",
        ).map(BigDecimal::parseString) + listOf("1e45" to "3", "-7.5" to "3", "1" to "3", "1e-12" to "3", "2" to "-3", "86400" to "7")
            .map { (a, b) -> BigDecimal.parseString(a).divide(BigDecimal.parseString(b), quotient) }
        for (value in values) {
            val halfUp = JavaDecimal.parse(value.toPlainString()).halfUp(9).toPlainString()
            val rounded = value.roundToDigitPositionAfterDecimalPoint(9, RoundingMode.ROUND_HALF_AWAY_FROM_ZERO)
            assertEquals(BigDecimal.parseString(halfUp).toPlainString(), rounded.toPlainString(), "${value.toPlainString()} to 9 places")
        }
    }

    @Test
    fun `a clock written as SQLite's timestamp and read again is cut to the microsecond, and nothing else`() {
        for (year in listOf(-999999999, -10000, -9999, -44, -1, 0, 1, 9999, 10000, 999999999)) for (nanos in listOf(0, 1, 999, 1000, 500000, 999999999)) {
            val clock = LocalDateTime(LocalDate(year, 12, 31), LocalTime(23, 59, 59, nanos))
            val cut = LocalDateTime(clock.date, LocalTime(clock.hour, clock.minute, clock.second, nanos / 1000 * 1000))
            assertEquals(cut, HostTemporal.local(SqliteTimestamps.write(clock), Temporal.LOCAL_DATE_TIME), "$clock")
        }
    }

    private operator fun <T> Array<T>.component6() = this[5]
    private operator fun <T> Array<T>.component7() = this[6]
    private operator fun <T> Array<T>.component8() = this[7]
}
