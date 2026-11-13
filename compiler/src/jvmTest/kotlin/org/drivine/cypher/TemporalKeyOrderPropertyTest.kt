package org.drivine.cypher

import org.drivine.cypher.sql.Temporal
import org.drivine.cypher.sql.Temporals
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime
import java.time.OffsetDateTime
import java.time.OffsetTime
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import java.time.temporal.ChronoField
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * Random temporal values of every kind that has a sort key (liberation-data/drivine-cypher-spec#161): each one's canonical
 * text `v` gives the key `s` the stored tag carries, the same each time it is worked out of `v`, of the width its kind's is;
 * and two of a kind, their keys compared as text, are in the order Neo4j puts them in, as the spec's probed rules have it,
 * which java.time gives here: a date, a local time and a local date-time by their fields; a zoned moment or time by its
 * instant, then its offset, the larger later. Two values are one key only where they are one value.
 */
class TemporalKeyOrderPropertyTest {

    private val random = Random(161)

    /** A year from anywhere Neo4j holds one, most of them near the present, where values are mostly written. */
    private fun year(): Int = when (random.nextInt(10)) {
        0 -> random.nextInt(-999_999_999, 1_000_000_000)
        1 -> random.nextInt(-9999, 10_000)
        else -> random.nextInt(1900, 2100)
    }

    private fun date(): LocalDate {
        val y = year()
        val month = random.nextInt(1, 13)
        return LocalDate.of(y, month, random.nextInt(1, LocalDate.of(y, month, 1).lengthOfMonth() + 1))
    }

    private fun time(): LocalTime {
        val nanos = when (random.nextInt(4)) {
            0 -> 0
            1 -> random.nextInt(1000) * 1_000_000
            2 -> random.nextInt(1_000_000) * 1000
            else -> random.nextInt(1_000_000_000)
        }
        // The edges of the day, where a zoned value's UTC day is another.
        val second = if (random.nextInt(5) == 0) listOf(0, 86_399).random(random) else random.nextInt(86_400)
        return LocalTime.ofSecondOfDay(second.toLong()).withNano(nanos)
    }

    /** An offset, in minutes, to its limits of ±18:00; often UTC, and the limits themselves. */
    private fun offset(): ZoneOffset = when (random.nextInt(6)) {
        0 -> ZoneOffset.UTC
        1 -> ZoneOffset.ofTotalSeconds(listOf(-64_800, 64_800).random(random))
        else -> ZoneOffset.ofTotalSeconds(random.nextInt(-1080, 1081) * 60)
    }

    private val isoTime = DateTimeFormatter.ofPattern("HH:mm:ss")

    /** ISO 8601's text of [time], its fraction to the nanosecond where it has one. */
    private fun text(time: LocalTime) = isoTime.format(time) + if (time.nano == 0) "" else "." + time.nano.toString().padStart(9, '0')

    /** ISO 8601's text of [date], as java.time writes it: a year past 9999 signed. */
    private fun text(date: LocalDate) = date.toString()

    /** A value of [kind], as the text a statement would write and a comparable java.time value, ordered as Neo4j orders them. */
    private fun value(kind: Temporal): Pair<String, Comparable<*>> = when (kind) {
        Temporal.DATE -> date().let { text(it) to it }
        Temporal.LOCAL_TIME -> time().let { text(it) to it }
        Temporal.TIME -> time().let { t -> offset().let { o -> text(t) + o.id to OffsetTime.of(t, o) } }
        Temporal.LOCAL_DATE_TIME -> LocalDateTime.of(date(), time()).let { "${text(it.toLocalDate())}T${text(it.toLocalTime())}" to it }
        Temporal.DATE_TIME -> OffsetDateTime.of(date(), time(), offset()).let { "${text(it.toLocalDate())}T${text(it.toLocalTime())}${it.offset.id}" to it }
        Temporal.DURATION -> error("a duration has no order")
    }

    /** Neo4j's order of two values of a kind: by instant, then offset, the larger later, where it is zoned. */
    @Suppress("UNCHECKED_CAST")
    private fun neo4jOrder(a: Comparable<*>, b: Comparable<*>): Int = when (a) {
        is OffsetDateTime -> compareValuesBy(a, b as OffsetDateTime, { it.toInstant() }, { it.offset.totalSeconds })
        is OffsetTime -> compareValuesBy(a, b as OffsetTime, { it.toLocalTime().toNanoOfDay() - it.offset.totalSeconds * 1_000_000_000L }, { it.offset.totalSeconds })
        else -> (a as Comparable<Any>).compareTo(b)
    }

    /** The width of each kind's key, as README.md's table has it. */
    private val widths = mapOf(Temporal.DATE to 14, Temporal.LOCAL_TIME to 14, Temporal.TIME to 21, Temporal.LOCAL_DATE_TIME to 29, Temporal.DATE_TIME to 35)

    @Test
    fun eachKeyIsWorkedOutOfItsTextAloneAndOrdersAsNeo4jDoes() {
        var pairs = 0
        for (kind in Temporal.instants) {
            val values = List(400) { value(kind) }.map { (written, value) ->
                val v = assertNotNull(Temporals.canonical(written, kind), "$written is a $kind")
                val s = assertNotNull(Temporals.key(kind, v), "$v has a key")
                assertEquals(s, Temporals.key(kind, v), "$v: the key is a function of v alone")
                assertEquals(widths.getValue(kind), s.length, "$v: its key is as wide as its kind's: $s")
                assertTrue(s.all { it in '0'..'9' }, "$v: its key is digits: $s")
                Triple(v, s, value)
            }
            for (a in values) for (b in values) {
                val expected = Integer.signum(neo4jOrder(a.third, b.third))
                assertEquals(expected, Integer.signum(a.second.compareTo(b.second)), "${a.first} and ${b.first}, keys ${a.second} and ${b.second}")
                pairs++
            }
        }
        assertEquals(5 * 400 * 400, pairs)
    }

    @Test
    fun aMomentsInstantAndOffsetAreReadBackFromItsKey() {
        // The key's parts are the moment's UTC clock and its offset: read back, they are the value's own, so no two moments share one.
        repeat(2000) {
            val (written, value) = value(Temporal.DATE_TIME)
            val moment = value as OffsetDateTime
            val s = Temporals.key(Temporal.DATE_TIME, Temporals.canonical(written, Temporal.DATE_TIME)!!)!!
            // Past the last year java.time's clock holds in UTC, at the offsets' limits, its key is checked by its order alone.
            val utc = runCatching { moment.withOffsetSameInstant(ZoneOffset.UTC) }.getOrNull() ?: return@repeat
            assertEquals(utc.year.toLong() + Temporals.YEAR_BIAS, s.substring(0, 10).toLong(), written)
            assertEquals(utc.getLong(ChronoField.NANO_OF_DAY), LocalTime.parse(s.substring(14, 20).chunked(2).joinToString(":")).toNanoOfDay() + s.substring(20, 29).toLong(), written)
            assertEquals(moment.offset.totalSeconds.toLong() + Temporals.OFFSET_BIAS, s.substring(29).toLong(), written)
        }
    }
}
