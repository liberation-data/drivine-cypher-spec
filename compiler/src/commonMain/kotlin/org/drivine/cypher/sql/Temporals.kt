package org.drivine.cypher.sql

import org.drivine.cypher.host.time.JavaDecimal
import com.ionspin.kotlin.bignum.decimal.BigDecimal
import kotlinx.datetime.atTime
import kotlinx.datetime.atDate
import kotlinx.datetime.number

/**
 * openCypher's temporal values on a SQL store, worked out of a store's [TemporalSql]. A value is its ISO 8601
 * text, as a store keeps a moment: `2024-03-01`, `10:30:00`, `10:30:00+02:00`, `2024-03-01T10:30:00`,
 * `2024-03-01T10:30:00.500Z`, `P1Y2M3DT4H`. A zoned value keeps the offset it was written with, its fields
 * read on its wall clock and compared as the moment it is. A duration is months, days and seconds, as
 * Cypher's is, and as a Postgres interval is. A year is numbered as Neo4j writes it, astronomically: `0000` is
 * 1 BC and `-0044` is 45 BC, and each store's [TemporalSql] reads and writes a year BC as the store keeps one.
 *
 * Every argument and result is SQL: a text, a number, or with [shared] a value written once where it is read
 * more than once.
 */
class Temporals(
    private val sql: TemporalSql,
    private val shared: (List<String>, (List<String>) -> String) -> String,
    /** The statement failing by name, with a message and a SQL text it names, as it runs: see [format]. */
    private val refuse: ((message: String, text: String) -> String)? = null,
    /** Whether [shared] binds a long value in a subquery where the SQL is being written, rather than writing it out. */
    private val binding: () -> Boolean = { true },
) {

    /** A duration's parts, SQL numbers each: whole months, whole days, and seconds with their fraction. */
    class Parts(val months: String, val days: String, val seconds: String)

    // ----- Kinds -----

    /** Whether the text [text] is of [kind]. */
    fun isKind(text: String, kind: Temporal): String = sql.matches(text, literal(sql.regex(kind.pattern)))

    /**
     * [body] of the kind among [kinds] that the text [text] is, as its shape tells, and of the text, written once:
     * null where it is none.
     */
    fun byKind(text: String, kinds: List<Temporal> = Temporal.instants, body: (Temporal, String) -> String): String =
        shared(listOf(text)) { (t) -> ofKinds(listOf(t), kinds) { kind -> body(kind, t) } }

    /**
     * [body] of the kind among [kinds] that a temporal value's tag names, its word [kind], a SQL text, and of the tag's text
     * [text], written once: null where it names none of them. A tag names its kind, so no text's shape is read.
     */
    fun byTag(kind: String, text: String, kinds: List<Temporal> = Temporal.instants, body: (Temporal, String) -> String): String =
        shared(listOf(kind, text)) { (k, t) -> ofKinds(listOf(t), kinds, { of -> "$k = '${of.function}'" }) { of -> body(of, t) } }

    /**
     * A `CASE` that gives [body] of the kind among [kinds] that each of [texts] is: null where they are of none. On a store
     * that reads a year BC otherwise than ISO 8601 writes it, a kind with a date has a `WHEN` first, where each is a text
     * of a year from 1000 on ([Temporal.adPattern]), and [body] there reads them as they are ([TemporalSql.knownAd]); the
     * kinds' own `WHEN`s follow those. So an ordinary date costs the tests of the kinds before its own, as before years BC
     * were read, and only a text the first `WHEN`s leave pays to be read as one. A [body] that reads none of them as a
     * store reads a year BC, as a year read off the text, has its kind's `WHEN` alone, as has a long one where the compiler
     * binds nothing ([binding]), as in an aggregate's argument: a value made of another is written out wherever it is read
     * there, and a long [body] written twice would double it again at each (see [TemporalSql.bcOnly]).
     */
    private fun ofKinds(texts: List<String>, kinds: List<Temporal>, tagged: ((Temporal) -> String)? = null, body: (Temporal) -> String): String {
        fun whens(test: (String) -> String, sql: String) = "WHEN ${texts.joinToString(" AND ", transform = test)} THEN $sql"
        val first = mutableListOf<String>()
        val own = mutableListOf<String>()
        for (kind in kinds) {
            val ad = kind.adPattern?.takeIf { sql.numbersBcFromOne }
            val (plain, read) = if (ad == null) body(kind) to false else sql.knownAd(texts) { body(kind) }
            // A tag's text is of its kind: the year's test alone tells it is from 1000 on.
            val tag = tagged?.let { "${it(kind)} AND " }.orEmpty()
            // A tag's canonical text is of its year from 1000 on where it opens with four digits, the first not 0: no pattern is read.
            val isAd: (String) -> String = if (tagged != null) { t -> "SUBSTR($t, 1, 1) IN ('1', '2', '3', '4', '5', '6', '7', '8', '9') AND SUBSTR($t, 5, 1) = '-'" }
                else { t -> sql.matches(t, literal(sql.regex(ad ?: ""))) }
            if (read && ad != null && (binding() || plain.length <= TemporalSql.GUARDED_UP_TO)) first += "WHEN " + tag + whens(isAd, plain).removePrefix("WHEN ")
            own += if (tagged != null) "WHEN ${tagged(kind)} THEN ${if (read) body(kind) else plain}" else whens({ isKind(it, kind) }, if (read) body(kind) else plain)
        }
        return "CASE ${(first + own).joinToString(" ")} END"
    }

    // ----- The stored tag's sort key -----

    /**
     * The sort key `s` of the canonical text [text] of [kind], a SQL text, as [key] works it out in Kotlin, by text and integer
     * arithmetic on the text alone, never from a store's timestamp, which holds neither every year nor nanoseconds: null where
     * [text] is null, and for a duration, which has no order.
     */
    fun sortKey(text: String, kind: Temporal): String? = if (kind == Temporal.DURATION) null else sql.storedKey(text, kind) ?: shared(listOf(text)) { (t) ->
        fun sub(of: String, from: String, length: String? = null) = "SUBSTR($of, $from${length?.let { ", $it" }.orEmpty()})"
        fun int(of: String) = sql.integer(of)
        val len = "LENGTH($t)"
        // The nine digits of a time's fraction, written from its tenth character where it has one: of a zoned time, [local] long.
        fun fraction(time: String, local: String? = null) =
            sub("${if (local == null) sub(time, "10") else "CASE WHEN $local > 9 THEN ${sub(time, "10", "$local - 9")} ELSE '' END"} || '000000000'", "1", "9")
        fun date(day: String, hhmmss: String = "") = shared(listOf(day)) { (d) ->
            "${sql.digits("${int(sub(d, "1", "LENGTH($d) - 6"))} + $YEAR_BIAS", 10)} || ${sub(d, "LENGTH($d) - 4", "2")} || ${sub(d, "LENGTH($d) - 1", "2")}$hhmmss"
        }
        // A zoned text's offset, in seconds, and how long its text is: `Z`, or `+hh:mm`.
        val zulu = "${sub(t, len, "1")} = 'Z'"
        val offset = "CASE WHEN $zulu THEN 0 ELSE CASE WHEN ${sub(t, "$len - 5", "1")} = '-' THEN -1 ELSE 1 END * " +
            "(${int(sub(t, "$len - 4", "2"))} * 3600 + ${int(sub(t, "$len - 1", "2"))} * 60) END"
        fun seconds(time: String) = "(${int(sub(time, "1", "2"))} * 3600 + ${int(sub(time, "4", "2"))} * 60 + ${int(sub(time, "7", "2"))})"
        when (kind) {
            Temporal.DATE -> date(t)
            Temporal.LOCAL_TIME -> sql.digits("${seconds(t)} * $NANOS + ${int(fraction(t))}", 14)
            Temporal.TIME -> shared(listOf(offset)) { (o) ->
                val local = "CASE WHEN $zulu THEN $len - 1 ELSE $len - 6 END"
                "${sql.digits("${seconds(t)} * $NANOS + ${int(fraction(t, local))} - $o * $NANOS + $TIME_BIAS", 15)} || ${sql.digits("$o + $OFFSET_BIAS", 6)}"
            }
            Temporal.LOCAL_DATE_TIME -> shared(listOf(sql.position(t, "'T'"))) { (p) ->
                val time = sub(t, "$p + 1")
                date(sub(t, "1", "$p - 1"), " || ${sub(time, "1", "2")} || ${sub(time, "4", "2")} || ${sub(time, "7", "2")} || ${fraction(time)}")
            }
            Temporal.DATE_TIME -> shared(listOf(sql.position(t, "'T'"), offset)) { (p, o) ->
                val day = sub(t, "1", "$p - 1")
                val time = sub(t, "$p + 1")
                val local = "CASE WHEN $zulu THEN $len - $p - 1 ELSE $len - $p - 6 END"
                shared(listOf(int(sub(day, "1", "$p - 7")), int(sub(day, "$p - 5", "2")), int(sub(day, "$p - 2", "2")), "(${seconds(time)} - $o)")) { (y, m, d, u) ->
                    val carry = "CASE WHEN $u < 0 THEN -1 WHEN $u >= 86400 THEN 1 ELSE 0 END"
                    shared(listOf(carry)) { (c) ->
                        fun daysIn(year: String, month: String) = "CASE WHEN $month IN (4, 6, 9, 11) THEN 30 WHEN $month = 2 THEN CASE WHEN ${sql.mod(year, "4")} = 0 AND " +
                            "(${sql.mod(year, "100")} <> 0 OR ${sql.mod(year, "400")} = 0) THEN 29 ELSE 28 END ELSE 31 END"
                        val year = "CASE WHEN $c < 0 AND $m = 1 AND $d = 1 THEN $y - 1 WHEN $c > 0 AND $m = 12 AND $d = 31 THEN $y + 1 ELSE $y END"
                        val month = "CASE WHEN $c < 0 AND $d = 1 THEN CASE WHEN $m = 1 THEN 12 ELSE $m - 1 END " +
                            "WHEN $c > 0 AND $d = ${daysIn(y, m)} THEN CASE WHEN $m = 12 THEN 1 ELSE $m + 1 END ELSE $m END"
                        val dayOf = "CASE WHEN $c < 0 AND $d = 1 THEN CASE WHEN $m = 1 THEN 31 ELSE ${daysIn(y, "$m - 1")} END " +
                            "WHEN $c > 0 AND $d = ${daysIn(y, m)} THEN 1 ELSE $d + $c END"
                        val clock = "($u - $c * 86400)"
                        "${sql.digits("$year + $YEAR_BIAS", 10)} || ${sql.digits(month, 2)} || ${sql.digits(dayOf, 2)} || " +
                            "${sql.digits(sql.div(clock, "3600"), 2)} || ${sql.digits(sql.mod(sql.div(clock, "60"), "60"), 2)} || ${sql.digits(sql.mod(clock, "60"), 2)} || " +
                            "${fraction(time, local)} || ${sql.digits("$o + $OFFSET_BIAS", 6)}"
                    }
                }
            }
            Temporal.DURATION -> error("a duration has no sort key")
        }
    }

    /**
     * [sortKey] of the canonical text [text] of the kind whose word the SQL [kind] gives, as the statement runs: by the store's
     * function where it keeps one, else by a `CASE` of each kind's.
     */
    fun sortKeyOf(text: String, kind: String, compared: Boolean = false): String = (if (compared) sql.comparedKeyOf(text, kind) else sql.storedKeyOf(text, kind)) ?: shared(listOf(text, kind)) { (t, k) ->
        "CASE $k ${Temporal.instants.joinToString(" ") { "WHEN '${it.function}' THEN ${sortKey(t, it)}" }} END"
    }

    // ----- Moments and times of day -----

    /**
     * The text of [kind] the wall clock [timestamp] reads, with [offset], a text, where the kind is zoned: null where
     * [timestamp] is null, which Oracle would otherwise write out as the text's punctuation alone (`.`, `.Z`), for its
     * null joins a text as the empty text.
     */
    fun format(timestamp: String, kind: Temporal, offset: String? = null): String = shared(listOf(timestamp)) { (t) -> sql.asAd(timestamp, listOf(t)) {
        val time = "${sql.toChar}($t, 'HH24:MI:SS') || ${fraction(t)}"
        val text = when (kind) {
            Temporal.DATE -> sql.date(t)
            Temporal.LOCAL_DATE_TIME -> "${sql.date(t, "\"T\"")} || $time"
            Temporal.DATE_TIME -> "${sql.date(t, "\"T\"")} || $time || ${offset ?: "'Z'"}"
            Temporal.LOCAL_TIME -> time
            Temporal.TIME -> "$time || ${offset ?: "'Z'"}"
            Temporal.DURATION -> error("a duration is no moment")
        }
        val written = if (kind == Temporal.DATE) text else "CASE WHEN $t IS NOT NULL THEN $text END"
        // A year past four digits, which a store whose timestamp holds it writes wrong (Postgres's `####`, SQLite's year
        // unsigned), fails by name, as no SQL store works such a year out yet (#263, #264). Oracle's timestamp holds none, so
        // it fails there as the year is worked out, before its text is written.
        val refusing = refuse
        val long = sql.longYear(t)
        if (refusing == null || long == null || kind == Temporal.LOCAL_TIME || kind == Temporal.TIME) written
        else "CASE WHEN $long THEN ${refusing(LONG_YEAR, "CAST($t AS ${sql.textType})")} ELSE $written END"
    } }

    /** A second's fraction as Java writes one: none for a whole second, else three digits or six. */
    private fun fraction(timestamp: String): String = shared(listOf(sql.micros(timestamp))) { (f) ->
        "CASE WHEN $f = '000000' THEN '' WHEN SUBSTR($f, 4, 3) = '000' THEN '.' || SUBSTR($f, 1, 3) ELSE '.' || $f END"
    }

    /**
     * The offset the zoned text [text] ends with, as Cypher gives it: with a colon where it is written without one,
     * and `Z` where it is none, however written (`+00:00`, `-0000`), so a value at UTC is equal to another however
     * each writes it.
     */
    fun offset(text: String): String {
        val utc = sql.replace(sql.group(text, literal(sql.regex(Temporal.OFFSET)), 1), literal(sql.regex("^[+-]00:?00$")), "'Z'")
        return sql.replace(utc, literal(sql.regex("^([+-][0-9]{2})([0-9]{2})$")), "'\\1:\\2'")
    }

    /** The offset a value of [to] made of the text [text] of [from] has: its own, or UTC's where it has none; none where [to] is local. */
    private fun offset(text: String, from: Temporal, to: Temporal): String? = if (to.zoned) (if (from.zoned) offset(text) else "'Z'") else null

    /** The text of [to] the text [text] of [from] is: a moment's date, a date's midnight, a local value's in UTC. */
    fun convert(text: String, from: Temporal, to: Temporal): String = shared(listOf(text)) { (t) ->
        // The wall clock kept is of the text's year, so is written as one from 1000 on where the text's is.
        sql.local(t, from).let { clock -> sql.asAd(t, listOf(clock)) { format(clock, to, offset(t, from, to)) } }
    }

    /** The text of [to] the text [text] is, whatever kind its shape tells: null where it is none. */
    fun convert(text: String, to: Temporal): String = byKind(text) { from, t -> convert(t, from, to) }

    /** The text of [kind] the statement runs at, in UTC. */
    fun now(kind: Temporal): String = format(sql.now, kind)

    /**
     * The text of [kind] of the fields [fields], SQL numbers each by Cypher's names (`year`, `month`, `day`,
     * `hour`, `minute`, `second`, `millisecond`, `microsecond`, `nanosecond`), and [timezone], a text. [givenYear] where the
     * statement gives the year, not where it is read off a value the store holds: see [yearHeld].
     */
    fun of(fields: Map<String, String>, timezone: String?, kind: Temporal, givenYear: Boolean = true): String {
        val year = fields["year"]
        // A year the statement gives is tested, bound once and the text made of the bound name, so the test writes no second
        // copy (#263). One read off a value the store holds, as a truncation or a date's own reads it, is held already.
        if (refuse != null && year != null && givenYear) return yearHeld(year) { y -> made(fields + ("year" to y), timezone, kind) }
        return made(fields, timezone, kind)
    }

    /** [of], its fields as they are. */
    private fun made(fields: Map<String, String>, timezone: String?, kind: Temporal): String {
        fun field(name: String, default: String, digits: Int) = "${sql.toChar}(${fields[name] ?: default}, 'FM${"0".repeat(digits)}')"
        val micros = "${sql.trunc}(${fields["millisecond"] ?: "0"} * 1000 + ${fields["microsecond"] ?: "0"} + ${fields["nanosecond"] ?: "0"} / 1000)"
        val date = "${field("year", "1970", 4)} || '-' || ${field("month", "1", 2)} || '-' || ${field("day", "1", 2)}"
        val time = "${field("hour", "0", 2)} || ':' || ${field("minute", "0", 2)} || ':' || ${field("second", "0", 2)} || '.' || ${sql.toChar}($micros, 'FM000000')"
        val zone = timezone ?: "'Z'"
        val text = when (kind) {
            Temporal.DATE -> date
            Temporal.LOCAL_DATE_TIME -> "$date || 'T' || $time"
            Temporal.DATE_TIME -> "$date || 'T' || $time || $zone"
            Temporal.LOCAL_TIME -> time
            Temporal.TIME -> "$time || $zone"
            Temporal.DURATION -> error("a duration is made of its own fields")
        }
        return convert(text, kind, kind)
    }

    /**
     * [made] of [year], bound once, a temporal value's text made of fields whose year it is, where the store holds that year:
     * else the statement fails by name (#263). A year it doesn't hold reads back as no text, so it gave null, where Neo4j makes
     * the value.
     */
    private fun yearHeld(year: String, made: (String) -> String): String {
        val refusing = refuse ?: return made(year)
        return shared(listOf(year)) { (y) ->
            "CASE WHEN $y > 9999 OR $y < ${sql.firstYear} THEN ${refusing(LONG_YEAR, "CAST($y AS ${sql.textType})")} ELSE ${made(y)} END"
        }
    }

    /**
     * [of] for [fields] that give the date by the calendar's other fields beside the `year`, SQL numbers each: a `week`
     * of the ISO week-based year and its `dayOfWeek`, an `ordinalDay`, or a `quarter` and its `dayOfQuarter`. The date
     * is so many days after the first of the year, of the quarter, or of the year's first ISO week, the week of January
     * 4th. As Neo4j has them, a field that is null is given as its least (the year 0, the first week, day or quarter),
     * and a week runs to 53 and a day of the quarter to 92 in any year, carried into the next where it has fewer. A
     * field that is not whole or is past its range gives the day 0, which the store fails on as it fails on `{day: 0}`.
     * [givenYear] as [of] takes it.
     */
    fun ofCalendar(fields: Map<String, String>, timezone: String?, kind: Temporal, givenYear: Boolean = true): String {
        fun given(name: String, least: Int) = fields[name]?.let { "COALESCE($it, $least)" } ?: "$least"
        if (refuse != null && "year" in fields && givenYear) return yearHeld(given("year", 0)) { y -> calendar(fields + ("year" to y), timezone, kind) }
        return calendar(fields, timezone, kind)
    }

    /** [ofCalendar], its fields as they are. */
    private fun calendar(fields: Map<String, String>, timezone: String?, kind: Temporal): String {
        val time = fields - CALENDAR_FIELDS - "year"
        fun given(name: String, least: Int) = fields[name]?.let { "COALESCE($it, $least)" } ?: "$least"
        fun january(y: String, day: Int) = sql.local("${sql.toChar}($y, 'FM0000') || '-01-0$day'", Temporal.DATE)
        fun whole(vararg values: String) = values.joinToString(" AND ") { "$it = ${sql.trunc}($it)" }
        val year = given("year", 0)
        return when {
            "week" in fields -> shared(listOf(year, given("week", 1), given("dayOfWeek", 1))) { (y, w, d) ->
                shared(listOf(january(y, 4))) { (january4) ->
                    calendarDate(sql.addTime(january4, "($w - 1) * 7 + $d - ${sql.dayOfWeek(january4)}", "0"), time, timezone, kind) {
                        "${whole(y, w, d)} AND $w BETWEEN 1 AND 53 AND $d BETWEEN 1 AND 7"
                    }
                }
            }
            "ordinalDay" in fields -> shared(listOf(year, given("ordinalDay", 1))) { (y, n) ->
                calendarDate(sql.addTime(january(y, 1), "$n - 1", "0"), time, timezone, kind) { day -> "${whole(y, n)} AND $n >= 1 AND ${sql.year(day)} = $y" }
            }
            else -> shared(listOf(year, given("quarter", 1), given("dayOfQuarter", 1))) { (y, q, n) ->
                calendarDate(sql.addTime(sql.addMonths(january(y, 1), "($q - 1) * 3"), "$n - 1", "0"), time, timezone, kind) {
                    "${whole(y, q, n)} AND $q BETWEEN 1 AND 4 AND $n BETWEEN 1 AND 92"
                }
            }
        }
    }

    /**
     * [of] the date the timestamp [date] reads where [valid] holds of it, else of the day 0, and the fields of the time
     * [time]. A moment's date is read as a date first: every store fails on a date's day 0, where Oracle reads a moment's
     * text as a JSON value's, null where it is no moment.
     */
    private fun calendarDate(date: String, time: Map<String, String>, timezone: String?, kind: Temporal, valid: (String) -> String): String =
        shared(listOf(date)) { (day) ->
            val dayOfMonth = "CASE WHEN NOT (${valid(day)}) THEN 0 ELSE ${sql.extract("DAY", day)} END"
            dated(mapOf("year" to sql.year(day), "month" to sql.extract("MONTH", day), "day" to dayOfMonth), time, timezone, kind)
        }

    /**
     * [of] the date of the fields [date] (`year`, `month`, `day`) and the fields of the time [time], the date read as a date
     * first where [kind] has a time too, so a store fails on a day its month has none of: see [calendarDate].
     */
    private fun dated(date: Map<String, String>, time: Map<String, String>, timezone: String?, kind: Temporal): String =
        if (kind == Temporal.DATE) of(date, null, kind, givenYear = false)
        else shared(listOf(sql.local(of(date, null, Temporal.DATE, givenYear = false), Temporal.DATE))) { (checked) -> of(time + dateOf(checked), timezone, kind, givenYear = false) }

    /** The year, the month and the day of the month of the timestamp [timestamp], as [of] takes them. */
    private fun dateOf(timestamp: String) = mapOf("year" to sql.year(timestamp), "month" to sql.extract("MONTH", timestamp), "day" to sql.extract("DAY", timestamp))

    // ----- Values made from another -----

    /**
     * A temporal value that a map's `date`, `time` or `datetime` gives [over]: its [text], and the [kinds] it may be, one
     * where the statement tells its kind, else those of the kind that give what it is to give (a date, a time of day, both),
     * its text telling which, as a value read from the store does.
     */
    class Base(val text: String, val kinds: List<Temporal>)

    /**
     * The text of [kind] made of the value [date] gives its date and the value [time] gives its time of day (one value where a
     * map's `datetime` gives both), with the fields [fields] laid over theirs, SQL numbers each by Cypher's names, and the
     * offset's text [timezone], as Neo4j lays them:
     * - A `week` is of the base's week-based year or the `year` given, and keeps its day of the week unless one is given; an
     *   `ordinalDay` is of its year or the one given; a `dayOfQuarter` of its quarter or the one given. Each is worked out as
     *   [ofCalendar] works it out.
     * - Else a `year`, a `month` and a `day` given make a date with the base's others, which fails where it is none (February
     *   29th of another year); then a `quarter` alone moves it so many quarters, to the month's last day where it has fewer,
     *   and a `dayOfWeek` alone to that day of its week. Beside a `dayOfWeek` the `year` moves the date so many years, to the
     *   month's last day where it has fewer, and doesn't fail.
     * - A millisecond, a microsecond or a nanosecond given makes the whole fraction of the second.
     * - A time of day or a moment that gives the time keeps its offset, else UTC's; with a [timezone] it is moved there, the same
     *   moment, where it has an offset, and given that offset where it has none.
     *
     * Without [date], the fields give the date as a map's fields do; without [time], the time. Fails as the statement runs where a value
     * given is null, or of a kind that hasn't what it is to give, or the [timezone] is null or no offset, as Neo4j fails.
     */
    fun over(date: Base?, time: Base?, fields: Map<String, String>, timezone: String?, kind: Temporal): String {
        val bases = listOfNotNull(date, time).distinct()
        return shared(bases.map { it.text }) { texts ->
            fun text(base: Base) = texts[bases.indexOf(base)]
            val clocks = bases.map { clock(text(it), it.kinds) }
            shared(clocks) { bound ->
                val day = date?.let { bound[bases.indexOf(it)] }
                val clock = time?.let { bound[bases.indexOf(it)] }
                // The offset of what gives the time, where it has one; null where it is local, or a moment is made without one.
                val own = time?.takeIf { kind.zoned }?.let { base ->
                    val zoned = base.kinds.filter { it.zoned }
                    when {
                        zoned.isEmpty() -> null
                        zoned.size == base.kinds.size -> offset(text(base))
                        else -> "CASE WHEN ${zoned.joinToString(" OR ") { isKind(text(base), it) }} THEN ${offset(text(base))} END"
                    }
                }
                val open = own != null && time.kinds.any { !it.zoned }
                val made = when {
                    !kind.zoned -> overFields(day, clock, fields, null, kind)
                    timezone == null -> overFields(day, clock, fields, own?.let { if (open) "COALESCE($it, 'Z')" else it }, kind)
                    own == null -> overFields(day, clock, fields, timezone, kind)
                    else -> moved(overFields(day, clock, fields, if (open) "COALESCE($own, $timezone)" else own, kind), kind, timezone)
                }
                // A value given that is null, or of a kind that hasn't what it is to give, or a time zone an expression gives that
                // is null or none, fails as on Neo4j: its date is the day 0, which every store fails on, written of the condition
                // so that a store can't fail on it where it isn't reached.
                shared(listOf(made)) { (value) ->
                    // The time zone too, which Oracle's `||` would drop where it is null, not make the text null.
                    val given = (bound + listOfNotNull(timezone) + value).joinToString(" AND ") { "$it IS NOT NULL" }
                    "CASE WHEN $given THEN $value ELSE ${of(mapOf("day" to "CASE WHEN $given THEN 1 ELSE 0 END"), null, Temporal.DATE)} END"
                }
            }
        }
    }

    /** The wall clock the text [text] reads, of the one of [kinds] it is: null where it is none. */
    private fun clock(text: String, kinds: List<Temporal>): String =
        kinds.singleOrNull()?.let { sql.local(text, it) }
            ?: ofKinds(listOf(text), Temporal.instants.filter { it in kinds }) { kind -> sql.local(text, kind) }

    /** [over]'s value of the wall clocks [day] and [clock] (null where none is given), laid over by [fields], with the offset [offset]. */
    private fun overFields(day: String?, clock: String?, fields: Map<String, String>, offset: String?, kind: Temporal): String {
        val time = buildMap {
            listOf("hour" to "HOUR", "minute" to "MINUTE").forEach { (name, part) -> (fields[name] ?: clock?.let { sql.extract(part, it) })?.let { put(name, it) } }
            (fields["second"] ?: clock?.let { wholeSecond(it) })?.let { put("second", it) }
            if (FRACTION.any { it in fields }) putAll(fields.filterKeys { it in FRACTION })
            else if (clock != null) put("microsecond", sql.number(sql.micros(clock)))
        }
        if (kind == Temporal.LOCAL_TIME || kind == Temporal.TIME) return of(time, offset, kind)
        val calendar = fields.filterKeys { it == "year" || it in CALENDAR_FIELDS }
        if (day == null) return if (calendar.keys.any { it in CALENDAR_FIELDS }) ofCalendar(calendar + time, offset, kind) else of(fields.filterKeys { it in DAY } + time, offset, kind)
        fun own(name: String) = fields[name] ?: clockField(day, name)
        return when {
            "week" in fields -> ofCalendar(mapOf("year" to (fields["year"] ?: clockField(day, "weekYear")), "week" to own("week"), "dayOfWeek" to own("dayOfWeek")) + time, offset, kind, "year" in fields)
            "ordinalDay" in fields -> ofCalendar(mapOf("year" to own("year"), "ordinalDay" to own("ordinalDay")) + time, offset, kind, "year" in fields)
            "dayOfQuarter" in fields -> ofCalendar(mapOf("year" to own("year"), "quarter" to own("quarter"), "dayOfQuarter" to own("dayOfQuarter")) + time, offset, kind, "year" in fields)
            DAY.none { it in fields } -> moveDay(day, fields, time, offset, kind)
            // Beside a day of the week (no month or day can be), a year moves the date so many years, to February 28th from the
            // 29th of a common year, as Neo4j moves it, where alone it fails there.
            "dayOfWeek" in fields -> moveDay(sql.addMonths(day, "(${own("year")} - ${clockField(day, "year")}) * 12"), fields, time, offset, kind)
            else -> shared(listOf(sql.local(of(DAY.associateWith { own(it) }, null, Temporal.DATE, givenYear = "year" in fields), Temporal.DATE))) { (checked) -> moveDay(checked, fields, time, offset, kind) }
        }
    }

    /** [over]'s value of the date [day], moved by a `quarter` or a `dayOfWeek` among [fields] where one is. */
    private fun moveDay(day: String, fields: Map<String, String>, time: Map<String, String>, offset: String?, kind: Temporal): String {
        fun whole(value: String, last: Int) = "$value = ${sql.trunc}($value) AND $value BETWEEN 1 AND $last"
        val quarter = fields["quarter"]
        val dayOfWeek = fields["dayOfWeek"]
        return when {
            quarter != null -> shared(listOf(quarter)) { (q) -> calendarDate(sql.addMonths(day, "($q - ${clockField(day, "quarter")}) * 3"), time, offset, kind) { whole(q, 4) } }
            dayOfWeek != null -> shared(listOf(dayOfWeek)) { (d) -> calendarDate(sql.addTime(day, "$d - ${sql.dayOfWeek(day)}", "0"), time, offset, kind) { whole(d, 7) } }
            else -> of(dateOf(day) + time, offset, kind, givenYear = false)
        }
    }

    /** The text of [kind], zoned, the same moment as the text [text] of it at the offset's text [timezone]. */
    private fun moved(text: String, kind: Temporal, timezone: String): String = shared(listOf(text, timezone)) { (t, z) ->
        format(sql.addTime(sql.utc(t, kind), "0", zoneField(z, "offsetSeconds").sql), kind, offset(z))
    }

    /**
     * A field of the text [text] of [kind], by Cypher's name for it: a number, or for `timezone` and `offset` a
     * text; null for a name the kind has no field of. A zoned value's fields are those of its wall clock.
     */
    fun field(text: String, kind: Temporal, name: String): Field? {
        if (kind == Temporal.DURATION) {
            if (durationField(parts("NULL"), name) == null) return null
            return Field(shared(listOf(text)) { (t) -> durationField(parts(t), name)!! }, text = false)
        }
        if (kind.zoned && name in setOf("timezone", "offset", "offsetMinutes", "offsetSeconds")) return zoneField(text, name)
        val epoch = sql.epoch
        if (sql.exactTime && (name == "epochSeconds" || name == "epochMillis")) return Field(exactEpoch(sql.utc(text, kind), name), text = false)
        when (name) {
            "epochSeconds" -> return Field("${sql.trunc}(${sql.secondsBetween(epoch, sql.utc(text, kind))})", text = false)
            "epochMillis" -> return Field("${sql.trunc}(${sql.secondsBetween(epoch, sql.utc(text, kind))} * 1000)", text = false)
        }
        if (name !in INSTANT_FIELDS) return null
        textField(text, kind, name)?.let { return Field(it, text = false) }
        if (name == "year") sql.textYear(text, kind)?.let { return Field(it, text = false) }
        // A field read from the timestamp once is read where it is: bound, a stored date's year was a subquery for each row.
        val local = sql.local(text, kind)
        return Field(if (name in READ_ONCE) clockField(local, name) else shared(listOf(local)) { (t) -> clockField(t, name) }, text = false)
    }

    /**
     * The field [name] of the canonical text [text] of [kind] read off the text, where it is one of its date's or its time's
     * own (the year, the month and the day, the hour, the minute, the second and its fraction): exactly, whatever the year
     * and to the nanosecond, as no store's timestamp holds either. Null for any other field, which is worked out of the clock.
     */
    private fun textField(text: String, kind: Temporal, name: String): String? {
        val dated = kind == Temporal.DATE || kind == Temporal.LOCAL_DATE_TIME || kind == Temporal.DATE_TIME
        val timed = kind != Temporal.DATE && kind != Temporal.DURATION
        fun sub(of: String, from: String, length: String? = null) = "SUBSTR($of, $from${length?.let { ", $it" }.orEmpty()})"
        fun int(of: String) = sql.integer(of)
        return when (name) {
            "year", "month", "day" -> if (!dated) null else shared(listOf(text)) { (t) ->
                val day = if (kind == Temporal.DATE) t else sub(t, "1", "${sql.position(t, "'T'")} - 1")
                shared(listOf(day)) { (d) ->
                    when (name) {
                        "year" -> int(sub(d, "1", "LENGTH($d) - 6"))
                        "month" -> int(sub(d, "LENGTH($d) - 4", "2"))
                        else -> int(sub(d, "LENGTH($d) - 1", "2"))
                    }
                }
            }
            "hour", "minute", "second", "millisecond", "microsecond", "nanosecond" -> if (!timed) null else shared(listOf(text)) { (t) ->
                val time = if (dated) sub(t, "${sql.position(t, "'T'")} + 1") else t
                shared(listOf(time)) { (c) ->
                    val local = if (kind.zoned) sub(c, "1", "LENGTH($c) - CASE WHEN ${sub(c, "LENGTH($c)", "1")} = 'Z' THEN 1 ELSE 6 END") else c
                    val nanos = int(sub("${sub(local, "10")} || '000000000'", "1", "9"))
                    when (name) {
                        "hour" -> int(sub(c, "1", "2"))
                        "minute" -> int(sub(c, "4", "2"))
                        "second" -> int(sub(c, "7", "2"))
                        "millisecond" -> sql.div(nanos, "1000000")
                        "microsecond" -> sql.div(nanos, "1000")
                        else -> nanos
                    }
                }
            }
            else -> null
        }
    }

    /** The field [name], one of [INSTANT_FIELDS], of the wall clock [t], a number. */
    private fun clockField(t: String, name: String): String {
        // Lazy: a store of exact time reads the nanoseconds into the minute, and has no SECOND to extract.
        val second by lazy { sql.extract("SECOND", t) }
        val nanos by lazy { sql.nanosOfMinute(t) }
        val exact = sql.exactTime
        return when (name) {
            "year" -> sql.year(t)
            "quarter" -> "${sql.trunc}((${sql.extract("MONTH", t)} + 2) / 3)"
            "month" -> sql.extract("MONTH", t)
            "week" -> sql.number("${sql.toChar}($t, 'IW')")
            "weekYear" -> sql.weekYear(t)
            "day" -> sql.extract("DAY", t)
            "ordinalDay" -> sql.number("${sql.toChar}($t, 'DDD')")
            "dayOfQuarter" -> "(${sql.number("${sql.toChar}($t, 'DDD')")} - ${sql.number("${sql.toChar}(${sql.truncate("quarter", t)!!}, 'DDD')")} + 1)"
            "dayOfWeek", "weekDay" -> sql.dayOfWeek(t)
            "hour" -> sql.extract("HOUR", t)
            "minute" -> sql.extract("MINUTE", t)
            "second" -> if (exact) "($nanos / 1000000000)" else "${sql.trunc}($second)"
            "millisecond" -> if (exact) "($nanos / 1000000 % 1000)" else "(${sql.trunc}($second * 1000) - ${sql.trunc}($second) * 1000)"
            "microsecond" -> if (exact) "($nanos / 1000 % 1000000)" else "(${sql.trunc}($second * 1000000) - ${sql.trunc}($second) * 1000000)"
            else -> if (exact) "($nanos % 1000000000)" else "((${sql.trunc}($second * 1000000) - ${sql.trunc}($second) * 1000000) * 1000)"
        }
    }

    /** The whole seconds of the wall clock [t] into its minute: of its nanoseconds, where the store's time is exact. */
    private fun wholeSecond(t: String): String =
        if (sql.exactTime) "(${sql.nanosOfMinute(t)} / 1000000000)" else "${sql.trunc}(${sql.extract("SECOND", t)})"

    /**
     * `epochSeconds` or `epochMillis` of [moment], a timestamp in UTC, on a store whose time is exact
     * ([TemporalSql.exactTime]): of the whole seconds since the epoch, floored, and the nanoseconds past them, in whole
     * numbers alone, counted toward zero as the other stores' `TRUNC` of their seconds counts them: a moment a fraction of
     * a second before the epoch has `epochSeconds` 0.
     */
    private fun exactEpoch(moment: String, name: String): String = shared(listOf(moment)) { (m) ->
        val (seconds, nanos) = sql.exactBetween(sql.epoch, m)
        shared(listOf(seconds, nanos)) { (s, n) ->
            if (name == "epochSeconds") "($s + ($s < 0 AND $n > 0))"
            else "($s * 1000 + $n / 1000000 + ($s < 0 AND $n % 1000000 > 0))"
        }
    }

    /** A field of a value: its SQL, and whether it is a text, where it is not a number. */
    class Field(val sql: String, val text: Boolean)

    private fun zoneField(text: String, name: String): Field = shared(listOf(offset(text))) { (o) ->
        val sign = "CASE WHEN SUBSTR($o, 1, 1) = '-' THEN -1 ELSE 1 END"
        val minutes = "CASE WHEN $o = 'Z' THEN 0 ELSE $sign * (${sql.number("SUBSTR($o, 2, 2)")} * 60 + ${sql.number("SUBSTR(REPLACE($o, ':', ''), 4, 2)")}) END"
        when (name) {
            "timezone", "offset" -> o
            "offsetMinutes" -> minutes
            else -> "($minutes) * 60"
        }
    }.let { Field(it, text = name == "timezone" || name == "offset") }

    /** The names of the fields a temporal value may have, of a moment's or a duration's. */
    val fieldNames = setOf(
        "year", "quarter", "month", "week", "weekYear", "day", "ordinalDay", "dayOfQuarter", "dayOfWeek", "weekDay", "hour", "minute", "second",
        "millisecond", "microsecond", "nanosecond", "epochSeconds", "epochMillis", "timezone", "offset", "offsetMinutes", "offsetSeconds",
        "years", "quarters", "months", "weeks", "days", "hours", "minutes", "seconds", "milliseconds", "microseconds", "nanoseconds",
        "quartersOfYear", "monthsOfQuarter", "monthsOfYear", "daysOfWeek", "minutesOfHour", "secondsOfMinute", "millisecondsOfSecond",
        "microsecondsOfSecond", "nanosecondsOfSecond",
    )

    /** The text of [kind] the duration text [duration] after the text [text] of it, or with [sign] -1 before: a zoned value keeps its offset. */
    fun add(text: String, kind: Temporal, duration: String, sign: Int): String = shared(listOf(text, duration)) { (t, d) ->
        format(moved(sql.local(t, kind), d, sign, if (kind == Temporal.DATE) "1 = 1" else null), kind, offset(t, kind, kind))
    }

    /**
     * [add] for a text of whatever kind its shape tells: read as a wall clock once, whatever its kind, the duration
     * added once, and written as the kind it was.
     */
    fun addToAny(text: String, duration: String, sign: Int): String = shared(listOf(text, duration)) { (t, d) ->
        shared(listOf(moved(clock(t, Temporal.instants), d, sign, isKind(t, Temporal.DATE)))) { (moved) ->
            "CASE ${Temporal.instants.joinToString(" ") { "WHEN ${isKind(t, it)} THEN ${format(moved, it, offset(t, it, it))}" }} END"
        }
    }

    /**
     * The timestamp [timestamp] the duration text [duration] later, or with [sign] -1 earlier. Where [date], a SQL
     * condition, holds, the duration's time is taken in whole days alone, as Cypher adds one to a date: a date less
     * `PT1H` is the same date, less `PT25H` the day before.
     */
    private fun moved(timestamp: String, duration: String, sign: Int, date: String? = null): String {
        val s = if (sign < 0) "-" else ""
        if (sql.exactTime) return exactParts(duration) { months, days, wholeSeconds, nanos ->
            val months = "$s($months)"
            if (date == null) {
                sql.addExactTime(sql.addMonths(timestamp, months), "$s($days)", "$s($wholeSeconds)", "$s($nanos)")
            } else {
                // A date takes the whole days of the duration's time alone, counted toward zero, as below.
                shared(listOf(wholeSeconds, nanos)) { (sec, n) ->
                    val toward = "CASE WHEN $sec % 86400 = 0 AND $sec > 0 AND $n < 0 THEN 1 WHEN $sec % 86400 = 0 AND $sec < 0 AND $n > 0 THEN -1 ELSE 0 END"
                    sql.addExactTime(
                        sql.addMonths(timestamp, months), "$s($days)",
                        "$s(CASE WHEN $date THEN ($sec / 86400 - $toward) * 86400 ELSE $sec END)", "$s(CASE WHEN $date THEN 0 ELSE $n END)",
                    )
                }
            }
        }
        val parts = parts(duration)
        val seconds = if (date == null) parts.seconds else shared(listOf(parts.seconds)) { (sec) -> "CASE WHEN $date THEN ${sql.trunc}($sec / 86400) * 86400 ELSE $sec END" }
        return sql.addTime(sql.addMonths(timestamp, "$s(${parts.months})"), "$s(${parts.days})", "$s($seconds)")
    }

    /**
     * The fields the third argument of a truncation gives the value cut short, in place of its own: SQL numbers by
     * Cypher's names (`year`, `month`, `day`, `hour`, `minute`, `second`, `millisecond`, `microsecond`), `dayOfWeek`,
     * and [timezone], an offset's text.
     */
    class Overrides(val fields: Map<String, String>, val timezone: String?)

    /**
     * The text of [to] the text [text] of [from] is, cut short to [unit], with [overrides] in place of its fields where
     * it has any: null where the store truncates to no such unit.
     */
    fun truncate(unit: String, text: String, from: Temporal, to: Temporal, overrides: Overrides? = null): String? {
        if (sql.truncate(unit, "NULL") == null) return null
        if (overrides == null || (overrides.fields.isEmpty() && overrides.timezone == null)) {
            // A truncation of a year from 1000 on is of one too, so is written as such.
            return shared(listOf(text)) { (t) -> sql.truncate(unit, sql.local(t, from))!!.let { cut -> sql.asAd(t, listOf(cut)) { format(cut, to, offset(t, from, to)) } } }
        }
        return shared(listOf(text)) { (t) ->
            shared(listOf(sql.truncate(unit, sql.local(t, from))!!)) { (cut) -> overridden(cut, unit, to, overrides, overrides.timezone ?: offset(t, from, to)) }
        }
    }

    /**
     * The text of [kind] of the timestamp [cut], cut short to [unit], with the fields [overrides] gives in place of its own,
     * and the offset [offset], as Neo4j lays a truncation's fields over the value: the year, month and day, then the time,
     * then the day of the week, which moves the value within its ISO week. A millisecond or a microsecond given makes the
     * whole fraction of the second, but a microsecond given a value cut to the millisecond is added to the millisecond kept.
     */
    private fun overridden(cut: String, unit: String, kind: Temporal, overrides: Overrides, offset: String?): String {
        val given = overrides.fields
        val own = mapOf(
            "year" to sql.year(cut), "month" to sql.extract("MONTH", cut), "day" to sql.extract("DAY", cut),
            "hour" to sql.extract("HOUR", cut), "minute" to sql.extract("MINUTE", cut), "second" to wholeSecond(cut),
        )
        val micros = sql.number(sql.micros(cut))
        val carried = unit == "millisecond" && "microsecond" in given
        val kept = if (carried) "($micros + ${given["microsecond"]})" else micros
        val fraction = when {
            "millisecond" in given -> "${given["millisecond"]} * 1000" + if (carried || "microsecond" !in given) "" else " + ${given["microsecond"]}"
            "microsecond" in given && !carried -> given.getValue("microsecond")
            else -> kept
        }
        val made = of(own.mapValues { (name, value) -> given[name] ?: value } + ("microsecond" to fraction), offset, kind, givenYear = "year" in given)
        val day = given["dayOfWeek"] ?: return made
        return shared(listOf(made)) { (m) ->
            shared(listOf(sql.local(m, kind))) { (clock) ->
                format(sql.addTime(clock, "$day - ${sql.dayOfWeek(clock)}", "0"), kind, if (kind.zoned) offset(m) else null)
            }
        }
    }

    /** What the text [text] of [kind] sorts by: the moment it is, whatever its offset, as a timestamp. */
    fun moment(text: String, kind: Temporal): String = sql.utc(text, kind)

    /** Whether the text [a] stands to the text [b], both of [kind], as [operator] says: null where either is no such text. */
    fun compare(a: String, b: String, kind: Temporal, operator: String): String = shared(listOf(a, b)) { (x, y) ->
        fun test() = if (kind == Temporal.DURATION) {
            // A duration is equal to another of the same parts, and is in no order.
            if (operator != "=" && operator != "<>") "NULL" else {
                val (p, q) = parts(x) to parts(y)
                val equal = "(${p.months} = ${q.months} AND ${p.days} = ${q.days} AND ${p.seconds} = ${q.seconds})"
                if (operator == "=") equal else "NOT $equal"
            }
        } else if (kind.zoned && (operator == "=" || operator == "<>")) {
            // Two zoned values are equal where they are the same moment at the same offset, as Neo4j has them.
            val equal = "(${sql.utc(x, kind)} = ${sql.utc(y, kind)} AND ${offset(x)} = ${offset(y)})"
            if (operator == "=") equal else "NOT $equal"
        } else {
            "${sql.utc(x, kind)} $operator ${sql.utc(y, kind)}"
        }
        ofKinds(listOf(x, y), listOf(kind)) { test() }
    }

    // ----- Durations -----

    /**
     * The parts of the duration text [text], which each read it more than once: [text] is a name, written once.
     * A part it leaves out is 0, and every part of null is null, so a duration of null is added as null. A duration
     * negated whole opens with its sign.
     */
    private fun parts(text: String): Parts {
        literalParts(text)?.let { return it }
        fun group(name: String) = Temporal.DURATION_PARTS.getValue(name).let { (pattern, group) -> sql.group(text, literal(sql.regex(pattern)), group) }
        fun part(name: String) = "COALESCE(${sql.number(group(name))}, 0)"
        val sign = "CASE WHEN SUBSTR($text, 1, 1) = '-' THEN -1 WHEN $text IS NOT NULL THEN 1 END"
        return Parts(
            "($sign * (${part("years")} * 12 + ${part("months")}))",
            "($sign * (${part("weeks")} * 7 + ${part("days")}))",
            "($sign * (${part("hours")} * 3600 + ${part("minutes")} * 60 + ${part("seconds")}))",
        )
    }

    /**
     * The parts of the duration text [text] where it is a SQL literal, as a duration the statement writes is: worked out here, as
     * the store would work them out, so the SQL reads no regular expression of a text it was given.
     */
    private fun literalParts(text: String): Parts? {
        if (text.length < 3 || !text.startsWith("'") || !text.endsWith("'") || text.drop(1).dropLast(1).contains('\'')) return null
        val groups = Temporal.DURATION.regex.matchEntire(text.drop(1).dropLast(1))?.groupValues ?: return null
        fun part(group: Int) = groups[group].ifEmpty { "0" }.takeIf { Regex("-?[0-9]+([.][0-9]+)?").matches(it) }?.let(BigDecimal::parseString)
        val (y, mo, w, d, h, mi, sec) = listOf(3, 5, 7, 9, 12, 14, 16).map { part(it) ?: return null }
        val sign = if (groups[1] == "-") BigDecimal.ONE.negate() else BigDecimal.ONE
        // As the store reads a part it works out, a number of the type it reads a text's number as.
        fun of(value: BigDecimal) = sql.number(literal((sign * value).toPlainString()))
        return Parts(of(y * BigDecimal.fromInt(12) + mo), of(w * BigDecimal.fromInt(7) + d), of(h * BigDecimal.fromInt(3600) + mi * BigDecimal.fromInt(60) + sec))
    }

    private operator fun <T> List<T>.component6() = this[5]
    private operator fun <T> List<T>.component7() = this[6]

    /**
     * [body] of the parts of the duration text [text], as [parts] reads them, but its time as whole seconds and nanoseconds,
     * whole numbers each, for a store whose time is exact ([TemporalSql.exactTime]): the seconds part's digits before its
     * point and the nine after it are read as text, never through a float. Its hours and minutes are whole, as a duration's
     * text writes them; a fraction of one is cut to the whole second.
     */
    private fun exactParts(text: String, body: (months: String, days: String, wholeSeconds: String, nanos: String) -> String): String {
        val parts = parts(text)
        fun group(name: String) = Temporal.DURATION_PARTS.getValue(name).let { (pattern, group) -> sql.group(text, literal(sql.regex(pattern)), group) }
        fun part(name: String) = "COALESCE(${sql.number(group(name))}, 0)"
        val sign = "CASE WHEN SUBSTR($text, 1, 1) = '-' THEN -1 WHEN $text IS NOT NULL THEN 1 END"
        return shared(listOf(group("seconds"))) { (g) ->
            val point = "INSTR($g, '.')"
            val fraction = "CASE WHEN $point > 0 THEN CAST(SUBSTR(SUBSTR($g, $point + 1) || '000000000', 1, 9) AS INTEGER) ELSE 0 END"
            val whole = "${sql.trunc}($sign * (${part("hours")} * 3600 + ${part("minutes")} * 60 + COALESCE(CAST($g AS INTEGER), 0)))"
            val nanos = "($sign * CASE WHEN SUBSTR($g, 1, 1) = '-' THEN -1 ELSE 1 END * $fraction)"
            body(parts.months, parts.days, whole, nanos)
        }
    }

    /** The duration text of the fields [fields], SQL numbers each by Cypher's names (`years`, `months`, `weeks`, `days`, `hours`…). */
    fun duration(fields: Map<String, String>): String {
        fun f(name: String) = fields[name] ?: "0"
        return text(Parts(
            "(${f("years")} * 12 + ${f("quarters")} * 3 + ${f("months")})",
            "(${f("weeks")} * 7 + ${f("days")})",
            "(${f("hours")} * 3600 + ${f("minutes")} * 60 + ${f("seconds")} + ${sql.quotient(f("milliseconds"), "1000")} + ${sql.quotient(f("microseconds"), "1000000")} + ${sql.quotient(f("nanoseconds"), "1000000000")})",
        ))
    }

    /**
     * The duration text of the parts [parts], as Neo4j writes one: whole years and months, days, then hours,
     * minutes and seconds, each signed, and `PT0S` for none. A fraction of a month or a day goes to the parts
     * below it, as Cypher's does: a month as 30.436875 days. Null where a part is null.
     */
    private fun text(parts: Parts): String = shared(listOf(parts.months, parts.days, parts.seconds)) { (m0, d0, s0) ->
        val m = "${sql.trunc}($m0)"
        val d = "${sql.trunc}($d0 + ($m0 - ${sql.trunc}($m0)) * 30.436875)"
        // The seconds rounded to the nine places [number] writes before they are split, so a hair under a minute is no `59M60S`,
        // nor a hair over a whole time a `0S` (#261).
        val s = sql.round("($s0 + ($d0 + ($m0 - ${sql.trunc}($m0)) * 30.436875 - $d) * 86400)", 9)
        shared(listOf(m, d, s)) { (months, days, seconds) ->
            val years = "${sql.trunc}($months / 12)"
            val hours = "${sql.trunc}($seconds / 3600)"
            val minutes = "(${sql.trunc}($seconds / 60) - $hours * 60)"
            val secs = "($seconds - ${sql.trunc}($seconds / 60) * 60)"
            fun unit(value: String, letter: String) = "CASE WHEN $value <> 0 THEN ${number(value)} || '$letter' ELSE '' END"
            "CASE WHEN $months = 0 AND $days = 0 AND $seconds = 0 THEN 'PT0S' WHEN $seconds IS NOT NULL THEN 'P' || ${unit(years, "Y")} || ${unit("($months - $years * 12)", "M")} || " +
                "${unit(days, "D")} || CASE WHEN $seconds <> 0 THEN 'T' || ${unit(hours, "H")} || ${unit(minutes, "M")} || ${unit(secs, "S")} ELSE '' END END"
        }
    }

    /**
     * A field of a duration of the parts [parts], by Cypher's name for it: null for a name a duration has no field of. As
     * Cypher holds a duration's seconds, they are whole seconds, floored, and a fraction of a second from 0 up to 1, so a
     * negative duration of -86399.9 seconds has `seconds` -86400 and `nanosecondsOfSecond` 100000000 (drivine4j#418);
     * the larger units are of the whole seconds, counted toward zero.
     */
    private fun durationField(parts: Parts, name: String): String? {
        val (m, d, s) = Triple(parts.months, parts.days, parts.seconds)
        val floor = sql::floor
        // Lazy: on SQLite a floor binds its number, and a binding made for a field that reads no seconds is left unread.
        val whole by lazy { floor(s) }
        return when (name) {
            "years" -> "${sql.trunc}($m / 12)"
            "quarters" -> "${sql.trunc}($m / 3)"
            "months" -> m
            "weeks" -> "${sql.trunc}($d / 7)"
            "days" -> d
            "hours" -> "${sql.trunc}($whole / 3600)"
            "minutes" -> "${sql.trunc}($whole / 60)"
            "seconds" -> whole
            "milliseconds" -> floor("$s * 1000")
            "microseconds" -> floor("$s * 1000000")
            "nanoseconds" -> floor("$s * 1000000000")
            "quartersOfYear" -> "(${sql.trunc}($m / 3) - ${sql.trunc}($m / 12) * 4)"
            "monthsOfQuarter" -> "($m - ${sql.trunc}($m / 3) * 3)"
            "monthsOfYear" -> "($m - ${sql.trunc}($m / 12) * 12)"
            "daysOfWeek" -> "($d - ${sql.trunc}($d / 7) * 7)"
            "minutesOfHour" -> "(${sql.trunc}($whole / 60) - ${sql.trunc}($whole / 3600) * 60)"
            "secondsOfMinute" -> "($whole - ${sql.trunc}($whole / 60) * 60)"
            "millisecondsOfSecond" -> "(${floor("$s * 1000")} - $whole * 1000)"
            "microsecondsOfSecond" -> "(${floor("$s * 1000000")} - $whole * 1000000)"
            "nanosecondsOfSecond" -> "(${floor("$s * 1000000000")} - $whole * 1000000000)"
            else -> null
        }
    }

    /** The duration text of the sum of the duration texts [a] and [b], or with [sign] -1 their difference. */
    fun sum(a: String, b: String, sign: Int): String = shared(listOf(a, b)) { (x, y) ->
        val (p, q) = parts(x) to parts(y)
        val op = if (sign < 0) "-" else "+"
        text(Parts("(${p.months} $op ${q.months})", "(${p.days} $op ${q.days})", "(${p.seconds} $op ${q.seconds})"))
    }

    /** The duration text of the duration text [duration] times [factor], a SQL number. */
    fun times(duration: String, factor: String): String = shared(listOf(duration, factor)) { (d, f) ->
        val p = parts(d)
        text(Parts("(${p.months} * $f)", "(${p.days} * $f)", "(${p.seconds} * $f)"))
    }

    /**
     * The duration text of the duration text [duration] divided by [divisor], a SQL number: each part divided, as Cypher divides
     * a duration, not times its reciprocal, whose rounding lost a whole month on Postgres (`P1Y / 3`, #260).
     */
    fun divided(duration: String, divisor: String): String = shared(listOf(duration, divisor)) { (d, k) ->
        val p = parts(d)
        text(Parts(sql.quotient(p.months, k), sql.quotient(p.days, k), sql.quotient(p.seconds, k)))
    }

    /** The canonical duration text of the duration text [text]: null where it is none. */
    fun duration(text: String): String = shared(listOf(text)) { (t) -> "CASE WHEN ${isKind(t, Temporal.DURATION)} THEN ${text(parts(t))} END" }

    /**
     * `duration.between`: the duration from the text [from] of [kind] to the text [to] of [toKind], in whole months
     * as a calendar counts them, then days, then seconds; with [unit] `months`, `days` or `seconds`, in that alone.
     * Two zoned values are read as the moments they are; where either is local, both are read on their wall clocks.
     * Where one is a time of day and the other has a date, only their times of day are compared, as in Cypher: the one
     * with a date is read as its time of day, of the other's kind, so a date is its midnight (drivine4j#418).
     */
    fun between(from: String, to: String, kind: Temporal, unit: String? = null, toKind: Temporal = kind): String {
        val zoned = kind.zoned && toKind.zoned
        val times = kind in TIMES || toKind in TIMES
        val (a, aKind) = if (times && kind !in TIMES) convert(from, kind, toKind) to toKind else from to kind
        val (b, bKind) = if (times && toKind !in TIMES) convert(to, toKind, kind) to kind else to to toKind
        fun read(text: String, of: Temporal) = if (zoned) sql.utc(text, of) else sql.local(text, of)
        return between(read(a, aKind), read(b, bKind), times, unit)
    }

    private fun between(from: String, to: String, times: Boolean, unit: String?): String = shared(listOf(from, to)) { (a, b) ->
        val months = if (times) "0" else sql.monthsBetween(a, b)
        if (sql.exactTime) exactBetween(a, b, months, unit) else when (unit) {
            "months" -> text(Parts(months, "0", "0"))
            "days" -> text(Parts("0", "${sql.trunc}(${sql.secondsBetween(a, b)} / 86400)", "0"))
            "seconds" -> text(Parts("0", "0", sql.secondsBetween(a, b)))
            else -> shared(listOf(months)) { (m) ->
                shared(listOf(sql.secondsBetween(sql.addMonths(a, m), b))) { (rest) -> text(Parts(m, "${sql.trunc}($rest / 86400)", "($rest - ${sql.trunc}($rest / 86400) * 86400)")) }
            }
        }
    }

    /**
     * [between] on a store whose time is exact ([TemporalSql.exactTime]): of the whole seconds from [a] to [b], floored, and
     * the nanoseconds past them, in whole numbers alone, so the duration's seconds are exact (drivine4j#418) over the whole
     * range of years, where a count of nanoseconds would overflow past ±292 years.
     */
    private fun exactBetween(a: String, b: String, months: String, unit: String?): String = when (unit) {
        "months" -> text(Parts(months, "0", "0"))
        "days" -> sql.exactBetween(a, b).let { (s, n) -> shared(listOf(s, n)) { (sec, ns) -> text(Parts("0", wholeDays(sec, ns), "0")) } }
        "seconds" -> sql.exactBetween(a, b).let { (s, n) -> exactText("0", "0", s, n) }
        else -> shared(listOf(months)) { (m) ->
            sql.exactBetween(sql.addMonths(a, m), b).let { (s, n) ->
                shared(listOf(s, n)) { (sec, ns) ->
                    shared(listOf(wholeDays(sec, ns))) { (d) -> exactText(m, d, "($sec - $d * 86400)", ns) }
                }
            }
        }
    }

    /**
     * The whole days of [seconds], floored, and [nanos] past them (0 up to a second), counted toward zero, as `TRUNC` of
     * their sum over 86400 counts them: SQLite's `/` of whole numbers counts toward zero already, but for a negative whole
     * number of days with nanoseconds past it, which is a day nearer zero.
     */
    private fun wholeDays(seconds: String, nanos: String) = "($seconds / 86400 + ($seconds < 0 AND $seconds % 86400 = 0 AND $nanos > 0))"

    /**
     * The duration text of whole [months] and [days], and [seconds], floored, and [nanos] past them, 0 up to a second, all
     * whole numbers, as [text] writes one, but with no float: its seconds and their fraction counted toward zero, the
     * fraction's digits written from the nanoseconds, its trailing zeros left out (`PT-22.142S`). Null where a part is null.
     */
    private fun exactText(months: String, days: String, seconds: String, nanos: String): String = shared(listOf(months, days, seconds, nanos)) { (m, d, s0, n0) ->
        // Toward zero: -22.142 seconds are -23 and 858000000 nanoseconds floored, -22 and -142000000 so.
        val back = "($s0 < 0 AND $n0 > 0)"
        shared(listOf("($s0 + $back)", "($n0 - $back * 1000000000)")) { (s, n) ->
            val hours = "($s / 3600)"
            val minutes = "($s / 60 - $hours * 60)"
            val secs = "($s - $s / 60 * 60)"
            val fraction = "CASE WHEN $n = 0 THEN '' ELSE '.' || RTRIM(SUBSTR('000000000' || ABS($n), -9), '0') END"
            val second = "CASE WHEN $secs < 0 OR $n < 0 THEN '-' ELSE '' END || ABS($secs) || $fraction || 'S'"
            fun unit(value: String, letter: String) = "CASE WHEN $value <> 0 THEN ${number(value)} || '$letter' ELSE '' END"
            val years = "($m / 12)"
            "CASE WHEN $m = 0 AND $d = 0 AND $s = 0 AND $n = 0 THEN 'PT0S' WHEN $s IS NOT NULL AND $n IS NOT NULL THEN 'P' || ${unit(years, "Y")} || " +
                "${unit("($m - $years * 12)", "M")} || ${unit(d, "D")} || CASE WHEN $s <> 0 OR $n <> 0 THEN 'T' || ${unit(hours, "H")} || " +
                "${unit(minutes, "M")} || CASE WHEN $secs <> 0 OR $n <> 0 THEN $second ELSE '' END ELSE '' END END"
        }
    }

    /** A number as Neo4j writes one in a duration: no zeros after the point, nor the point where there is no fraction. */
    private fun number(value: String) = "RTRIM(${sql.toChar}($value, 'FM9999999999990.999999999'), '.')"

    private fun literal(text: String) = "'${text.replace("'", "''")}'"

    companion object {
        /**
         * What a statement that works out a date or a moment whose year the store can't hold fails with, the year or the moment
         * after it: past 9999, or before the store's first, -4712 on Postgres, -4711 on Oracle and -9999 on SQLite (README.md,
         * Values). Short, as a statement that makes many such values writes it for each.
         */
        const val LONG_YEAR = "A year the store doesn't hold, past 9999 or before its first, isn't worked out yet (drivine-cypher-spec#263, #264):"

        /**
         * The canonical text of [kind] of the text [text], worked out as the store's SQL would work it out, for a
         * value the statement writes: null where the text is of no temporal kind.
         */
        fun canonical(text: String, kind: Temporal): String? {
            if (kind == Temporal.DURATION) return if (Temporal.DURATION.regex.matches(text)) canonicalDuration(text) else null
            // A year past 9999, or before -9999, as ISO 8601 and Neo4j write one, signed (`+999999999-12-31`): read as the year of the
            // same place in the Gregorian calendar's 400-year cycle, which has the same days, and written back with its own.
            EXTENDED_YEAR.find(text)?.let { found ->
                val year = found.groupValues[1].toLong()
                if (year !in -999_999_999L..999_999_999L) return null
                if (year in -9999L..9999L) return null
                val stand = 2000 + ((year % 400) + 400) % 400
                val read = canonical(stand.toString() + text.substring(found.groupValues[1].length), kind) ?: return null
                return if (kind.function.contains("time") && !kind.function.contains("date")) read else isoYear(year) + read.substring(4)
            }
            val from = Temporal.instants.firstOrNull { it.regex.matches(text) } ?: return null
            val written = if (from.zoned) Temporal.OFFSET_REGEX.find(text)!!.value else null
            val local = if (written != null) text.removeSuffix(written) else text
            val offset = written?.replace(Temporal.ZERO_OFFSET, "Z")?.replace(Temporal.OFFSET_WITHOUT_COLON, "$1:$2")
            // SPIKE: java.time's message where it refuses the text; kotlinx-datetime's are its own.
            org.drivine.cypher.time.CommonTime.javaParseError(local)?.let { throw org.drivine.cypher.time.CommonTime.TemporalException(it) }
            val clock = when (from) {
                Temporal.DATE -> kotlinx.datetime.LocalDate.parse(local).atTime(0, 0)
                Temporal.LOCAL_DATE_TIME, Temporal.DATE_TIME -> kotlinx.datetime.LocalDateTime.parse(local)
                else -> kotlinx.datetime.LocalTime.parse(local).atDate(kotlinx.datetime.LocalDate(1970, 1, 1))
            }
            val fraction = fraction(clock.nanosecond)
            val time = listOf(clock.hour, clock.minute, clock.second).joinToString(":") { it.toString().padStart(2, '0') } + fraction
            val zone = if (kind.zoned) offset ?: "Z" else ""
            return when (kind) {
                Temporal.DATE -> isoDate(clock.date)
                Temporal.LOCAL_DATE_TIME, Temporal.DATE_TIME -> "${isoDate(clock.date)}T$time$zone"
                else -> "$time$zone"
            }
        }

        /**
         * A second's fraction of [nanos] nanoseconds as the canonical text writes it, as `Instant.toString` does: none for a
         * whole second, else the fewest of three, six or nine digits that hold it.
         */
        fun fraction(nanos: Int): String = when {
            nanos == 0 -> ""
            nanos % 1_000_000 == 0 -> "." + (nanos / 1_000_000).toString().padStart(3, '0')
            nanos % 1000 == 0 -> "." + (nanos / 1000).toString().padStart(6, '0')
            else -> "." + nanos.toString().padStart(9, '0')
        }

        /**
         * The sort key `s` of the canonical text [v] of [kind], as the stored tag `{"$t", "v", "s"}` holds it (see
         * [org.drivine.cypher.store.ValueLayout]): digits all, of one width for each kind, so that two values of one kind
         * order as their keys compare, under any collation, as Neo4j orders them. Null for a duration, which has no order.
         * Worked out of [v] alone, by the text and integer arithmetic each store's SQL does too ([sortKey]), so that every
         * writer's `s` of a value is the same:
         * - a date: its year plus 10^9 ([YEAR_BIAS]), in 10 digits, then `MMDD`;
         * - a local moment: the same, then `hhmmss` and nine digits of nanoseconds;
         * - a zoned moment: the same of its clock in UTC, then its offset in seconds plus 64,800 ([OFFSET_BIAS]), in 6 digits;
         * - a local time: its nanoseconds of the day, in 14 digits;
         * - a zoned time: its nanoseconds of the day in UTC plus 18 hours ([TIME_BIAS]), in 15 digits, then its offset as a
         *   zoned moment's. Neo4j compares a zoned time so, with no wrap at midnight: `time('01:00+02:00')` is before `time('22:00Z')`.
         */
        fun key(kind: Temporal, v: String): String? {
            fun digits(n: Long, width: Int) = n.toString().padStart(width, '0')
            fun nanos(time: String): Long {
                val h = time.substring(0, 2).toLong()
                val m = time.substring(3, 5).toLong()
                val s = time.substring(6, 8).toLong()
                val f = (time.substring(8).removePrefix(".") + "000000000").substring(0, 9).toLong()
                return ((h * 60 + m) * 60 + s) * NANOS + f
            }
            fun offset(text: String): Pair<String, Long> {
                if (text.endsWith("Z")) return text.dropLast(1) to 0L
                val sign = if (text[text.length - 6] == '-') -1 else 1
                return text.dropLast(6) to sign * (text.substring(text.length - 5, text.length - 3).toLong() * 3600 + text.takeLast(2).toLong() * 60)
            }
            fun date(year: Long, month: Long, day: Long) = digits(year + YEAR_BIAS, 10) + digits(month, 2) + digits(day, 2)
            fun clock(n: Long) = digits(n / 3600, 2) + digits(n / 60 % 60, 2) + digits(n % 60, 2)
            fun ymd(date: String) = Triple(date.dropLast(6).toLong(), date.substring(date.length - 5, date.length - 3).toLong(), date.takeLast(2).toLong())
            return when (kind) {
                Temporal.DURATION -> null
                Temporal.DATE -> ymd(v).let { (y, m, d) -> date(y, m, d) }
                Temporal.LOCAL_TIME -> digits(nanos(v), 14)
                Temporal.TIME -> offset(v).let { (local, seconds) -> digits(nanos(local) - seconds * NANOS + TIME_BIAS, 15) + digits(seconds + OFFSET_BIAS, 6) }
                Temporal.LOCAL_DATE_TIME -> {
                    val t = v.indexOf('T')
                    val (y, m, d) = ymd(v.substring(0, t))
                    val n = nanos(v.substring(t + 1))
                    date(y, m, d) + clock(n / NANOS) + digits(n % NANOS, 9)
                }
                Temporal.DATE_TIME -> {
                    val t = v.indexOf('T')
                    val (local, seconds) = offset(v.substring(t + 1))
                    var (y, m, d) = ymd(v.substring(0, t))
                    val n = nanos(local)
                    val u = n / NANOS - seconds
                    val carry = if (u < 0) -1 else if (u >= 86400) 1 else 0
                    if (carry < 0) when {
                        d > 1 -> d--
                        m > 1 -> { m--; d = daysIn(y, m) }
                        else -> { y--; m = 12; d = 31 }
                    } else if (carry > 0) when {
                        d < daysIn(y, m) -> d++
                        m < 12 -> { m++; d = 1 }
                        else -> { y++; m = 1; d = 1 }
                    }
                    date(y, m, d) + clock(u - carry * 86400L) + digits(n % NANOS, 9) + digits(seconds + OFFSET_BIAS, 6)
                }
            }
        }

        /** The days of the month [month] of the year [year], numbered astronomically, as the proleptic Gregorian calendar has them. */
        private fun daysIn(year: Long, month: Long): Long = when (month) {
            4L, 6L, 9L, 11L -> 30
            2L -> if (year % 4 == 0L && (year % 100 != 0L || year % 400 == 0L)) 29 else 28
            else -> 31
        }

        /** Nanoseconds in a second. */
        const val NANOS = 1_000_000_000L

        /** What `s` adds to a year, so that every year Neo4j holds, and UTC's of them at the offsets' limits, is ten digits: see [key]. */
        const val YEAR_BIAS = 1_000_000_000L

        /** What `s` adds to a zoned time's nanoseconds of the day in UTC, 18 hours', so that each is at least 0: see [key]. */
        const val TIME_BIAS = 64_800L * NANOS

        /** What `s` adds to an offset's seconds, 18 hours', so that each is at least 0: see [key]. */
        const val OFFSET_BIAS = 64_800L

        /** A year as ISO 8601 writes it, as java.time does: past 9999 signed, before 0 with its sign, four digits at least. */
        private fun isoYear(y: Long): String = when {
            y > 9999 -> "+$y"
            y < 0 -> "-" + (-y).toString().padStart(4, '0')
            else -> y.toString().padStart(4, '0')
        }

        /** The year of a text that opens with one past four digits, signed: see [canonical]. */
        private val EXTENDED_YEAR = Regex("^([+-][0-9]{5,10})-[0-9]{2}-[0-9]{2}")

        /** SPIKE: ISO 8601's date, as java.time writes it: a year past 9999 signed, one before 0 with its sign, four digits at least. */
        private fun isoDate(date: kotlinx.datetime.LocalDate): String {
            val year = isoYear(date.year.toLong())
            return "$year-${date.month.number.toString().padStart(2, '0')}-${date.day.toString().padStart(2, '0')}"
        }

        /** The canonical text of the duration text [text], as [text] of its parts writes it. */
        private fun canonicalDuration(text: String): String = durationParts(text).let { (m0, d0, s0) -> durationText(m0, d0, s0) }

        /**
         * The parts of the duration text [text], exactly: its months (years as twelve), days (weeks as seven) and seconds (hours
         * and minutes with them), each signed by the text's own sign, as [parts] reads them.
         */
        internal fun durationParts(text: String): Triple<BigDecimal, BigDecimal, BigDecimal> {
            val groups = Temporal.DURATION.regex.matchEntire(text)?.groupValues ?: throw IllegalArgumentException("'$text' is no duration.")
            // As java.math's `BigDecimal(text)`, which refuses a part of no digits or of two points with NumberFormatException.
            fun dec(text: String) = if (JAVA_DECIMAL.matches(text)) BigDecimal.parseString(text) else throw NumberFormatException("No decimal: $text")
            fun dec(n: Int) = BigDecimal.fromInt(n)
            fun part(group: Int) = groups[group].takeIf { it.isNotEmpty() }?.let(::dec) ?: BigDecimal.ZERO
            val sign = if (groups[1] == "-") BigDecimal.ONE.negate() else BigDecimal.ONE
            return Triple(sign * (part(3) * dec(12) + part(5)), sign * (part(7) * dec(7) + part(9)), sign * (part(12) * dec(3600) + part(14) * dec(60) + part(16)))
        }

        /**
         * The duration text of the parts [m0] months, [d0] days and [s0] seconds, exactly, as [text] of parts writes one: a
         * fraction of a month goes to the days as 30.436875 of them, and of a day to the seconds. With [places], the seconds and
         * each part are rounded half away from zero to that many places, as the builder's `number` writes them.
         */
        internal fun durationText(m0: BigDecimal, d0: BigDecimal, s0: BigDecimal, places: Long? = null): String {
            fun dec(text: String) = BigDecimal.parseString(text)
            fun dec(n: Int) = BigDecimal.fromInt(n)
            fun down(value: BigDecimal) = value.roundToDigitPositionAfterDecimalPoint(0, com.ionspin.kotlin.bignum.decimal.RoundingMode.TOWARDS_ZERO)
            // As `divide(b, 0, DOWN)`, exactly, however many digits: [a] cut toward zero, divided as whole numbers.
            fun quotient(a: BigDecimal, b: BigDecimal) = BigDecimal.fromBigInteger(down(a).toBigInteger().divide(b.toBigInteger()))
            val monthDays = dec("30.436875")
            val months = down(m0)
            val daysExact = d0 + (m0 - months) * monthDays
            val days = down(daysExact)
            val exactSeconds = s0 + (daysExact - days) * dec(86400)
            // With [places], the seconds rounded first, so a whole minute is no `59M60S`, and no time a `0S`.
            val seconds = if (places == null) exactSeconds
                else exactSeconds.roundToDigitPositionAfterDecimalPoint(places, com.ionspin.kotlin.bignum.decimal.RoundingMode.ROUND_HALF_AWAY_FROM_ZERO)
            if (months.signum() == 0 && days.signum() == 0 && seconds.signum() == 0) return "PT0S"
            val years = quotient(months, dec(12))
            val hours = quotient(seconds, dec(3600))
            val minutes = quotient(seconds - hours * dec(3600), dec(60))
            val secs = seconds - hours * dec(3600) - minutes * dec(60)
            // With [places], each part as the builder's `number` writes it: rounded half away from zero to that many places.
            fun written(value: BigDecimal) = if (places == null) value.toPlainString()
                else JavaDecimal.parse(value.toPlainString()).halfUp(places).stripTrailingZeros().toPlainString()
            fun unit(value: BigDecimal, letter: String) = if (value.signum() != 0) written(value) + letter else ""
            val time = if (seconds.signum() != 0) "T" + unit(hours, "H") + unit(minutes, "M") + unit(secs, "S") else ""
            return "P" + unit(years, "Y") + unit(months - years * dec(12), "M") + unit(days, "D") + time
        }

        /** A part of a duration as java.math reads a decimal: a sign, and digits with one point at most. */
        private val JAVA_DECIMAL = Regex("-?([0-9]+[.]?[0-9]*|[.][0-9]+)")

        /** The kinds of a time of day, which no months are between. */
        private val TIMES = setOf(Temporal.TIME, Temporal.LOCAL_TIME)

        /** The [INSTANT_FIELDS] that read the timestamp once, on every store: see [TemporalSql.year]. */
        private val READ_ONCE = setOf("year", "quarter", "month", "week", "day", "ordinalDay", "hour", "minute")

        /** The fields of a moment or a time of day read off its wall clock. */
        val INSTANT_FIELDS = setOf(
            "year", "quarter", "month", "week", "weekYear", "day", "ordinalDay", "dayOfQuarter", "dayOfWeek", "weekDay",
            "hour", "minute", "second", "millisecond", "microsecond", "nanosecond",
        )

        /** The fields that give a date by the calendar's other fields, beside the year, in place of a month and a day: see [ofCalendar]. */
        val CALENDAR_FIELDS = setOf("week", "dayOfWeek", "ordinalDay", "quarter", "dayOfQuarter")

        /** The fields of a map that give another temporal value to make one of: see [over]. */
        val SELECTORS = setOf("date", "time", "datetime")

        /** The fields of a date by its month: see [over]. */
        private val DAY = listOf("year", "month", "day")

        /** The fields of a fraction of a second, which make the whole fraction where any is given: see [over]. */
        private val FRACTION = setOf("millisecond", "microsecond", "nanosecond")
    }
}
