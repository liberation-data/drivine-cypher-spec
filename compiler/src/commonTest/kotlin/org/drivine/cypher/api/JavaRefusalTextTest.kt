package org.drivine.cypher.api

import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * Where the port replaced a JVM call whose exception's message is the refusal, the refusal is drivine4j's, word for word,
 * on every target: java.time's refusals of a temporal text, and `appendCodePoint`'s of a code point past U+10FFFF. And a
 * float a refusal writes is as Java writes it.
 */
class JavaRefusalTextTest {

    private fun refusal(cypher: String) = (CypherCompiler.compile(Store.SQLITE, GraphOptions("g"), cypher) as? Refusal)?.message

    @Test
    fun aTemporalTextJavaTimeRefusesIsRefusedWithItsMessage() {
        val cases = mapOf(
            "date('2025-02-30')" to "'2025-02-30' is no date: Text '2025-02-30' could not be parsed: Invalid date 'FEBRUARY 30'",
            "date('2024-04-31')" to "'2024-04-31' is no date: Text '2024-04-31' could not be parsed: Invalid date 'APRIL 31'",
            "date('2025-02-29')" to "'2025-02-29' is no date: Text '2025-02-29' could not be parsed: Invalid date 'February 29' as '2025' is not a leap year",
            "date('2025-13-01')" to "'2025-13-01' is no date: Text '2025-13-01' could not be parsed: Invalid value for MonthOfYear (valid values 1 - 12): 13",
            "date('2025-00-00')" to "'2025-00-00' is no date: Text '2025-00-00' could not be parsed: Invalid value for MonthOfYear (valid values 1 - 12): 0",
            "date('2025-01-32')" to "'2025-01-32' is no date: Text '2025-01-32' could not be parsed: Invalid value for DayOfMonth (valid values 1 - 28/31): 32",
            "date('-0000-01-01')" to "'-0000-01-01' is no date: Text '-0000-01-01' could not be parsed at index 0",
            "localtime('25:00')" to "'25:00' is no localtime: Text '25:00' could not be parsed: Invalid value for HourOfDay (valid values 0 - 23): 25",
            "localtime('12:60')" to "'12:60' is no localtime: Text '12:60' could not be parsed: Invalid value for MinuteOfHour (valid values 0 - 59): 60",
            "localtime('12:00:60')" to "'12:00:60' is no localtime: Text '12:00:60' could not be parsed: Invalid value for SecondOfMinute (valid values 0 - 59): 60",
            "localtime('25:60')" to "'25:60' is no localtime: Text '25:60' could not be parsed: Invalid value for MinuteOfHour (valid values 0 - 59): 60",
            "localtime('25:00:60')" to "'25:00:60' is no localtime: Text '25:00:60' could not be parsed: Invalid value for HourOfDay (valid values 0 - 23): 25",
            "localdatetime('2025-02-30T25:00')" to "'2025-02-30T25:00' is no localdatetime: Text '2025-02-30T25:00' could not be parsed: Invalid date 'FEBRUARY 30'",
            "date('-0001-02-29')" to "'-0001-02-29' is no date: Text '-0001-02-29' could not be parsed: Invalid date 'February 29' as '-1' is not a leap year",
            "localtime('12:00:00.1234567890')" to "'12:00:00.1234567890' is no localtime: Text '12:00:00.1234567890' could not be parsed, unparsed text found at index 18",
            "time('24:00+01:00')" to "'24:00+01:00' is no time: Text '24:00' could not be parsed: Invalid value for HourOfDay (valid values 0 - 23): 24",
            "localdatetime('2025-13-01T25:00')" to "'2025-13-01T25:00' is no localdatetime: Text '2025-13-01T25:00' could not be parsed: Invalid value for MonthOfYear (valid values 1 - 12): 13",
            "localdatetime('-0000-01-01T00:00:00.1234567890')" to "'-0000-01-01T00:00:00.1234567890' is no localdatetime: Text '-0000-01-01T00:00:00.1234567890' could not be parsed at index 0",
            "localdatetime('2025-13-01T00:00:00.1234567890')" to "'2025-13-01T00:00:00.1234567890' is no localdatetime: Text '2025-13-01T00:00:00.1234567890' could not be parsed, unparsed text found at index 29",
            "datetime('2025-01-00T00:00Z')" to "'2025-01-00T00:00Z' is no datetime: Text '2025-01-00T00:00' could not be parsed: Invalid value for DayOfMonth (valid values 1 - 28/31): 0",
        )
        for ((call, message) in cases) assertEquals(message, refusal("RETURN $call"), call)
        // Years BC as java.time counts their leap years: -0044 is 45 BC, a leap year.
        assertEquals(null, refusal("RETURN date('-0044-02-29')"))
        assertEquals(null, refusal("RETURN localtime('12:00:00.123456789')"))
    }

    @Test
    fun aZoneIdOfTakesByAPrefixIsAZoneByName() {
        // ZoneId.of reads UTC, GMT or UT, alone or before an offset, as a region of that offset's rules, which no target's
        // list of zone ids holds; and an offset only as ZoneOffset.of writes one, its colons both there or neither.
        for (zone in listOf("UT", "UTC+1", "UTC+01:00", "GMT-5", "UT+01:00", "UTC+0")) {
            val unsupported = "The time zone '$zone' is Cypher, and is not compiled to SQL yet."
            assertEquals(unsupported, refusal("RETURN time({hour: 1, timezone: '$zone'})"), zone)
            assertEquals(unsupported, refusal("RETURN datetime.truncate('day', date('2024-03-15'), {timezone: '$zone'})"), zone)
        }
        for (zone in listOf("UTCx", "UTC+19", "UT-", "UTC+01:0000", "+01:0000", "+0100:00")) {
            assertEquals("'$zone' is no time zone.", refusal("RETURN datetime.truncate('day', date('2024-03-15'), {timezone: '$zone'})"), zone)
        }
    }

    @Test
    fun aCodePointPastUnicodesIsRefusedAsAppendCodePointRefusesIt() {
        assertEquals("Not a valid Unicode code point: 0x110000", refusal("RETURN '\\U00110000'"))
        assertEquals(null, refusal("RETURN '\\U0010FFFF'"))
    }

    @Test
    fun aFloatARefusalWritesIsAsJavaWritesIt() {
        val cases = mapOf(
            "MATCH (n) RETURN n LIMIT 1e7" to "SKIP and LIMIT take a whole number, not 1.0E7.",
            "MATCH (n) RETURN n SKIP 1e-4" to "SKIP and LIMIT take a whole number, not 1.0E-4.",
            "RETURN date({year: 1e7, week: 1})" to "date() takes a whole number for year, not 1.0E7.",
            "RETURN date({year: 201e24, quarter: 4, dayOfQuarter: 93})" to "date() takes a whole number for year, not 2.01E26.",
            "RETURN date({date: 1e-4})" to "date() takes a date, a localdatetime or a datetime for date, not 1.0E-4.",
            "RETURN localtime({time: localtime('10:00'), second: 1e7})" to "localtime() takes a whole number for second, not 1.0E7.",
            "RETURN time({time: time('23:00+01:00'), timezone: 1e-4})" to "time() takes a time zone, not 1.0E-4.",
            "RETURN date.truncate('month', date('2024-03-15'), {day: 1e7})" to "date.truncate() takes a whole number for day, not 1.0E7.",
            "RETURN round(5, 1, 1e7)" to "round() rounds by CEILING, FLOOR, UP, DOWN, HALF_EVEN, HALF_UP, HALF_DOWN or UNNECESSARY, not 1.0E7.",
            "CREATE VECTOR INDEX v FOR (c:Chunk) ON c.embedding OPTIONS {indexConfig: {`vector.dimensions`: 9223372036854775808}}"
                to "A vector index's `vector.dimensions` is a positive whole number, not 9.223372036854776E18.",
            "CREATE VECTOR INDEX v FOR (c:Chunk) ON c.embedding OPTIONS {indexConfig: {`vector.dimensions`: [1e7, {a: 1e-4}]}}"
                to "A vector index's `vector.dimensions` is a positive whole number, not [1.0E7, {a=1.0E-4}].",
            "CREATE VECTOR INDEX v FOR (c:Chunk) ON c.embedding OPTIONS {indexConfig: {`vector.dimensions`: 3, `vector.similarity_function`: 1e7}}"
                to "A vector index's `vector.similarity_function` is 'cosine' or 'euclidean', not 1.0E7.",
            "CREATE VECTOR INDEX v FOR (c:Chunk) ON c.embedding OPTIONS {indexConfig: {`vector.dimensions`: 3, `vector.quantization.enabled`: [1e-4]}}"
                to "A vector index's `vector.quantization.enabled` is true or false, not [1.0E-4].",
            "CREATE FULLTEXT INDEX t FOR (n:Book) ON EACH [n.title] OPTIONS {indexConfig: {`fulltext.analyzer`: 1e7}}"
                to "A full-text index's `fulltext.analyzer` is the name of an analyzer, not 1.0E7.",
        )
        for ((cypher, message) in cases) assertEquals(message, refusal(cypher), cypher)
    }
}
