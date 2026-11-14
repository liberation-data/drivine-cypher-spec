package org.drivine.cypher.sql

/** Postgres's SQL for temporal values: see [TemporalSql]. Its intervals keep months, days and time apart, as Cypher's durations do. */
class PostgresTemporalSql(
    shared: (List<String>, (List<String>) -> String) -> String,
    /** The graph whose store keeps the function [storedKey] calls (see `PostgresDdl.temporalKey`); null where the SQL is written inline. */
    private val graph: String? = null,
) : TemporalSql(shared) {

    /**
     * Postgres's timestamp holds years past 9999, which `to_char` writes `####`, and none before 4713 BC: see
     * [TemporalSql.longYear].
     */
    override fun longYear(timestamp: String) = "$timestamp >= TIMESTAMP '10000-01-01 00:00:00'"

    override val textType = "text"

    /** Postgres holds no whole year before 4713 BC: of 4714 BC, Cypher's -4713, only the days from November 24th. */
    override val firstYear = -4712

    /** The store's `PostgresDdl.temporalKey`: written inline, the key of a zoned moment is some 2,500 characters. */
    override fun storedKey(text: String, kind: Temporal): String? = storedKeyOf(text, "'${kind.function}'")

    override fun storedKeyOf(text: String, kind: String): String? = graph?.let { "${org.drivine.cypher.store.ddl.PostgresDdl.temporalKeyName(it)}($kind, $text)" }

    /**
     * [storedKeyOf], or where the value is a date, a local date-time or a moment at UTC of a year from 1000 to 9999, as a value
     * worked out as the statement runs mostly is, its key cut from its text as it stands: the year, the month and day, the
     * clock and the fraction are the key's own digits there, and no UTC day is worked out. Called for each row a compared sum is
     * made for, the function took that sum's time a tenth as long again (#161). Only where a comparison or an order reads the
     * key: written into every tag a statement makes whole, it made a sum of a stored duration some 28,000 characters, which
     * Postgres's backend died compiling.
     */
    override fun comparedKeyOf(text: String, kind: String): String? = graph?.let { graph ->
        val function = "${org.drivine.cypher.store.ddl.PostgresDdl.temporalKeyName(graph)}($kind, $text)"
        if (text.length > CUT_UP_TO || kind.length > CUT_UP_TO) return@let function
        shared(listOf(text, kind)) { (t, k) ->
            fun cut(from: Int, length: Int) = "SUBSTR($t, $from, $length)"
            val day = "'100000' || ${cut(1, 4)} || ${cut(6, 2)} || ${cut(9, 2)}"
            val clock = "${cut(12, 2)} || ${cut(15, 2)} || ${cut(18, 2)}"
            fun fraction(zone: Int) = "CASE WHEN SUBSTR($t, 20, 1) = '.' THEN SUBSTR(SUBSTR($t, 21, LENGTH($t) - 20 - $zone) || '000000000', 1, 9) ELSE '000000000' END"
            val ad = "SUBSTR($t, 1, 1) IN ('1', '2', '3', '4', '5', '6', '7', '8', '9') AND SUBSTR($t, 5, 1) = '-'"
            "CASE WHEN $ad AND $k = 'date' THEN $day " +
                "WHEN $ad AND $k = 'localdatetime' THEN $day || $clock || ${fraction(0)} " +
                "WHEN $ad AND $k = 'datetime' AND RIGHT($t, 1) = 'Z' THEN $day || $clock || ${fraction(1)} || '$OFFSET_ZERO' " +
                "ELSE ${org.drivine.cypher.store.ddl.PostgresDdl.temporalKeyName(graph)}($k, $t) END"
        }
    }

    override fun local(text: String, kind: Temporal) = when (kind) {
        Temporal.DATE -> "CAST(CAST(${eraOf(text)} AS date) AS timestamp)"
        Temporal.LOCAL_DATE_TIME -> "CAST(${eraOf(text)} AS timestamp)"
        Temporal.DATE_TIME -> "CAST(${eraOf(text, zoned = true)} AS timestamp)"
        Temporal.LOCAL_TIME -> "CAST('1970-01-01 ' || $text AS timestamp)"
        Temporal.TIME -> "CAST('1970-01-01 ' || ${unzoned(text)} AS timestamp)"
        Temporal.DURATION -> error("a duration is no moment")
    }

    override fun utc(text: String, kind: Temporal) = when (kind) {
        Temporal.DATE_TIME -> "(CAST(${eraOf(text)} AS timestamptz) AT TIME ZONE 'UTC')"
        Temporal.TIME -> "(CAST('1970-01-01 ' || $text AS timestamptz) AT TIME ZONE 'UTC')"
        else -> local(text, kind)
    }

    /**
     * Postgres reads no year before 1 in ISO text, but reads one BC after it: so 1 BC, the year `0000`, is `0001 … BC`,
     * and 45 BC, the year `-0044`, is `0045 … BC`. See [bcOnly].
     */
    override fun era(text: String) = shared(listOf(text)) { (t) ->
        bcOnly(t, "ascii($t) < $BC_BELOW", "regexp_replace($t, '^(-[0-9]{4}|0000)(.*)$', to_char(1 - CAST(substring($t from '^-?[0-9]{4}') AS int), 'FM0000') || '\\2 BC')")
    }

    /** Where it may be BC ([bcOnly]), the year as [year] numbers it, written with its sign as `to_char` writes a negative number, then the rest of the date. */
    override fun date(timestamp: String, then: String) = bcOnly(
        timestamp, "$timestamp < $FIRST_AD", "to_char(${year(timestamp)}, 'FM0000') || to_char($timestamp, '-MM-DD$then')", super.date(timestamp, then),
    )

    /**
     * The year of [timestamp] [LIFT] years later, less [LIFT]: [LIFT] is twelve cycles of the Gregorian calendar's 400
     * years, after which its days, leap days and ISO weeks fall again as they did, and lifts Postgres's first year, 4713 BC,
     * past 0. So a year BC is numbered as Cypher numbers it, the timestamp read once, where Postgres numbers it from -1.
     */
    override fun year(timestamp: String) = "(EXTRACT(YEAR FROM ($timestamp) + $LIFT) - $LIFT_YEARS)"

    /** The date's text, its time of day cut off, less its month and day. */
    override fun textYear(text: String, kind: Temporal) = when (kind) {
        Temporal.DATE -> "CAST(left($text, -6) AS numeric)"
        Temporal.LOCAL_DATE_TIME, Temporal.DATE_TIME -> "CAST(left(split_part($text, 'T', 1), -6) AS numeric)"
        else -> null
    }

    /** The ISO week's year, lifted as [year] lifts it: `to_char` writes a week's year BC unsigned. */
    override fun weekYear(timestamp: String) = "(${number("to_char(($timestamp) + $LIFT, 'IYYY')")} - $LIFT_YEARS)"

    override val now = "(statement_timestamp() AT TIME ZONE 'UTC')"

    override fun integer(text: String) = "CAST($text AS bigint)"

    override fun position(text: String, char: String) = "strpos($text, $char)"

    override fun digits(n: String, width: Int) = "lpad(CAST($n AS text), $width, '0')"

    override fun div(a: String, b: String) = "div($a, $b)"

    /**
     * By `div`, exactly: numeric's `/` keeps 16 significant digits or so, so `315575999999.999999999 / 3600` is
     * `87660000.000000000000`, and `PT87660000H - PT0.000000001S` was written `PT87660000H-0.000000001S`
     * (drivine-cypher-spec#275).
     */
    override fun truncatedQuotient(a: String, b: String) = "div($a, $b)"

    override fun matches(text: String, pattern: String) = "($text ~ $pattern)"

    override fun group(text: String, pattern: String, group: Int) = "(regexp_match($text, $pattern))[$group]"

    override fun number(text: String) = "CAST(NULLIF($text, '') AS numeric)"

    /**
     * `numeric`'s division keeps some 16 significant digits, so the quotient of a large dividend had fewer than the nine places a
     * duration's seconds are written to: `3600000000 / 7` is `514285714.28571429` (`duration('PT1000000H') / 7`, #260). The
     * dividend is given 20 places, which the quotient keeps.
     */
    override fun quotient(dividend: String, divisor: String) = "ROUND($dividend, 20) / $divisor"

    override fun sortable(timestamp: String) = "to_char($timestamp, 'YYYY-MM-DD\"T\"HH24:MI:SS.US')"

    override fun micros(timestamp: String) = "to_char($timestamp, 'US')"

    override fun dayOfWeek(timestamp: String) = "CAST(EXTRACT(ISODOW FROM $timestamp) AS numeric)"

    override fun addMonths(timestamp: String, months: String) = "($timestamp + make_interval(months => CAST($months AS int)))"

    override fun addTime(timestamp: String, days: String, seconds: String) =
        "($timestamp + make_interval(days => CAST($days AS int), secs => CAST($seconds AS double precision)))"

    /** `age` counts in calendar months, then days, then time, as Cypher's `duration.between` does. */
    override fun monthsBetween(from: String, to: String) =
        shared(listOf("age($to, $from)")) { (age) -> "(EXTRACT(YEAR FROM $age) * 12 + EXTRACT(MONTH FROM $age))" }

    override fun secondsBetween(from: String, to: String) = "EXTRACT(EPOCH FROM ($to - $from))"

    /**
     * Postgres rounds a `numeric` to places, and no `double precision`: a duration's part may be either, as a statement reads
     * it (#261), so it is rounded as the `numeric` it is or converts to.
     */
    override fun round(number: String, places: Int) = "ROUND(CAST($number AS numeric), $places)"

    /**
     * `date_trunc`, but for the units it counts otherwise than Neo4j: its millennium starts in 2001 and its century in
     * 1901, so those and the decade are the year less its years into the span (see [YEARS_OF]). The week-based year is
     * so many weeks before the timestamp's week, its ISO week less one.
     */
    override fun truncate(unit: String, timestamp: String) = when (unit) {
        in setOf("year", "quarter", "month", "week", "day", "hour", "minute", "second", "millisecond") -> "date_trunc('$unit', $timestamp)"
        "microsecond" -> "date_trunc('microseconds', $timestamp)"
        in YEARS_OF -> shared(listOf(timestamp)) { (t) ->
            "(date_trunc('year', $t) - make_interval(years => MOD(CAST(${year(t)} AS int), ${YEARS_OF.getValue(unit)})))"
        }
        "weekYear" -> shared(listOf(timestamp)) { (t) -> "(date_trunc('week', $t) - make_interval(weeks => CAST(EXTRACT(WEEK FROM $t) AS int) - 1))" }
        else -> null
    }

    private companion object {
        /** How long a text and its kind may be for [storedKeyOf] to cut the key from the text: a column, or a key read from one. */
        const val CUT_UP_TO = 200

        /** The key's offset of UTC, 0 seconds plus [Temporals.OFFSET_BIAS], in its six digits. */
        val OFFSET_ZERO = Temporals.OFFSET_BIAS.toString().padStart(6, '0')

        /** How many years [year] lifts a timestamp by: see it. */
        const val LIFT_YEARS = 4800

        /** [LIFT_YEARS] as an interval. */
        const val LIFT = "interval '4800 years'"

        /** The first moment of year 1: a timestamp before it has a year BC. */
        const val FIRST_AD = "timestamp '0001-01-01'"
    }
}
