package org.drivine.cypher.oracle

import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import org.drivine.cypher.sql.Temporal
import java.io.File
import java.io.PrintWriter
import java.math.BigDecimal
import java.math.BigInteger
import java.security.MessageDigest
import java.time.DateTimeException
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.ZoneId
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import java.time.temporal.ChronoField
import kotlin.random.Random

/**
 * What Java gives where the module stands in for it: the cases the stand-in fuzz (JavaStandInFuzzTest) replays through the
 * module's own code on every target, the JVM included. Java's own API works out each answer here: java.time, java.math,
 * `String`'s, `Integer.parseInt`'s and `StringBuilder.appendCodePoint`'s, `MessageDigest`'s, Jackson's, and where the
 * port replaced a few lines of drivine4j, those lines as drivine4j has them, run on the JVM.
 *
 * Each file, `standins-<name>.txt`, holds a case a line: its fields tab-separated, each written by [esc]. An answer is
 * `=` and the value, or `!` and the message of what Java throws.
 */
object StandIns {

    /** Writes every stand-in's cases, from [seed], into [dir]: how many cases of each. */
    fun write(dir: File, seed: Long): Map<String, Int> {
        val counts = linkedMapOf<String, Int>()
        fun file(name: String, cases: (Random, (List<String>) -> Unit) -> Unit) {
            var count = 0
            File(dir, "standins-$name.txt").printWriter().use { w ->
                cases(Random(seed + name.hashCode())) { fields -> w.line(fields); count++ }
            }
            counts[name] = count
        }
        file("zones", ::zones)
        file("calendar", ::calendar)
        file("temporal", ::temporal)
        file("escapes", ::escapes)
        file("utf8", ::utf8)
        file("sha256", ::sha256)
        file("chars", ::chars)
        file("format", ::format)
        file("numbers", ::numbers)
        file("json", ::json)
        file("timestamps", ::timestamps)
        file("search", ::search)
        return counts
    }

    private fun PrintWriter.line(fields: List<String>) = println(fields.joinToString("\t", transform = ::esc))

    /** [s] with `\` doubled and every character outside printable ASCII as `\uXXXX`, a lone surrogate among them. */
    fun esc(s: String): String = buildString {
        for (c in s) when {
            c == '\\' -> append("\\\\")
            c in ' '..'~' -> append(c)
            else -> append("\\u").append(c.code.toString(16).uppercase().padStart(4, '0'))
        }
    }

    private inline fun answer(work: () -> String): String = try {
        "=" + work()
    } catch (e: Exception) {
        "!" + (e.message ?: e.toString())
    }

    // ----- ZoneId.of: CommonTime.offsetId, isRegion, knownRegion and JVM_ZONE_IDS -----

    /** A zone as `ZoneId.of` takes it: `O` and its id where it is an offset, `R` where a region, `-` where none. */
    private fun zoneOf(zone: String): String = try {
        when (val id = ZoneId.of(zone)) {
            is ZoneOffset -> "O" + id.id
            else -> "R"
        }
    } catch (e: DateTimeException) {
        "-"
    }

    private fun zones(random: Random, emit: (List<String>) -> Unit) {
        val ids = ZoneId.getAvailableZoneIds().sorted()
        val zones = LinkedHashSet<String>()
        for (id in ids) {
            zones += listOf(id, id.lowercase(), id.uppercase(), id + "x", "x$id", id.dropLast(1), id.drop(1), "$id/", " $id", "$id+1", "$id-01:00")
        }
        val prefixes = listOf("", "UTC", "GMT", "UT", "U", "Z", "Etc/GMT", "UTCx", "utc")
        val signs = listOf("+", "-", "", "Z")
        val hours = listOf("", "0", "1", "00", "01", "10", "18", "19", "99", "001", "1a", "+1")
        val minutes = listOf("", "0", "00", "01", "30", "59", "60", "000")
        val seconds = listOf("", "0", "00", "01", "59", "60")
        for (p in prefixes) for (s in signs) for (h in hours) for (c1 in listOf("", ":")) for (m in minutes) for (c2 in listOf("", ":")) for (sec in seconds) {
            zones += p + s + h + c1 + m + c2 + sec
        }
        val alphabet = "+-:0123456789ZzUTCGMXx/_.~ abEtc\u00e9"
        repeat(8_000) { zones += String(CharArray(random.nextInt(0, 11)) { alphabet[random.nextInt(alphabet.length)] }) }
        repeat(4_000) {
            val id = ids[random.nextInt(ids.size)].toCharArray()
            id[random.nextInt(id.size)] = alphabet[random.nextInt(alphabet.length)]
            zones += String(id)
        }
        zones += listOf("", "Z", "z", "+", "-", "UTC", "GMT", "UT", "GMT0", "UTC0", "UT0", "Etc/GMT+5", "Etc/GMT-14", "EST", "EST5EDT", "SystemV/AST4")
        for (zone in zones) emit(listOf(zone, zoneOf(zone)))
    }

    // ----- LocalDate.of, plusDays, with(DAY_OF_WEEK), YEAR.checkValidIntValue, lengthOfYear: CommonTime's calendar -----

    /**
     * The date drivine4j's `calendarDate` works out, by java.time: `W` the ISO week [a] and day of the week [b] of [year],
     * `O` its ordinal day [a], `Q` its quarter [a] and day of the quarter [b]. `L` [year]'s length.
     */
    private fun calendarOf(kind: String, year: Long, a: Long, b: Long): String = if (kind == "L") "=" + (if (java.time.Year.isLeap(year)) 366 else 365) else try {
        val y = ChronoField.YEAR.checkValidIntValue(year)
        val date = when (kind) {
            "W" -> LocalDate.of(y, 1, 4).with(ChronoField.DAY_OF_WEEK, 1).plusDays((a - 1) * 7 + b - 1)
            "O" -> LocalDate.of(y, 1, 1).plusDays(a - 1)
            else -> LocalDate.of(y, ((a - 1) * 3 + 1).toInt(), 1).plusDays(b - 1)
        }
        "=${date.year}-${date.monthValue}-${date.dayOfMonth}"
    } catch (e: DateTimeException) {
        "!" + e.message
    }

    private fun calendar(random: Random, emit: (List<String>) -> Unit) {
        val edges = listOf(
            -999_999_999L, -999_999_998L, -999_999_997L, -999_999_996L, -100_000L, -10_000L, -9999L, -401L, -400L, -101L, -100L,
            -5L, -4L, -1L, 0L, 1L, 4L, 100L, 400L, 1582L, 1900L, 1970L, 2000L, 2004L, 2024L, 2025L, 2026L, 9999L, 10_000L,
            999_999_996L, 999_999_997L, 999_999_998L, 999_999_999L,
        )
        val outOfRange = listOf(1_000_000_000L, -1_000_000_000L, Int.MAX_VALUE.toLong(), Int.MIN_VALUE.toLong(), Long.MAX_VALUE, Long.MIN_VALUE, 2_147_483_648L)
        val years = edges + List(300) { random.nextLong(-999_999_999L, 1_000_000_000L) } + List(200) { random.nextLong(-3000L, 3000L) }
        fun case(kind: String, year: Long, a: Long, b: Long) = emit(listOf(kind, year.toString(), a.toString(), b.toString(), calendarOf(kind, year, a, b)))
        for (year in edges) {
            case("L", year, 0, 0)
            for (week in 1L..53L) for (day in 1L..7L) case("W", year, week, day)
            val length = if (java.time.Year.isLeap(year)) 366L else 365L
            for (day in 1L..length) case("O", year, day, 0)
            for (quarter in 1L..4L) for (day in 1L..92L) case("Q", year, quarter, day)
        }
        for (year in years.drop(edges.size)) {
            case("L", year, 0, 0)
            repeat(4) { case("W", year, random.nextLong(1, 54), random.nextLong(1, 8)) }
            case("W", year, 53, 7)
            case("W", year, 1, 1)
            val length = if (java.time.Year.isLeap(year)) 366L else 365L
            listOf(1L, 59L, 60L, 61L, length - 1, length, random.nextLong(1, length + 1)).forEach { case("O", year, it, 0) }
            repeat(3) { case("Q", year, random.nextLong(1, 5), random.nextLong(1, 93)) }
            case("Q", year, 4, 92)
        }
        for (year in outOfRange) {
            case("W", year, 1, 1)
            case("O", year, 1, 0)
            case("Q", year, 1, 1)
        }
    }

    // ----- java.time's parse and text of a temporal value, java.math's of a duration: Temporals.canonical -----

    /**
     * drivine4j's `Temporals.canonical` (12847912), by java.time and java.math: the lines the port replaced, with the store
     * layout's fraction (#161): nanoseconds kept, in the fewest of three, six or nine digits, as `Instant.toString` writes them.
     */
    private fun canonical(text: String, kind: Temporal): String? {
        if (kind == Temporal.DURATION) return if (Regex(Temporal.DURATION.pattern).matches(text)) canonicalDuration(text) else null
        val from = Temporal.instants.firstOrNull { Regex(it.pattern).matches(text) } ?: return null
        val written = if (from.zoned) Regex(Temporal.OFFSET).find(text)!!.value else null
        val local = if (written != null) text.removeSuffix(written) else text
        val offset = written?.replace(Regex("^[+-]00:?00$"), "Z")?.replace(Regex("^([+-][0-9]{2})([0-9]{2})$"), "$1:$2")
        val clock = when (from) {
            Temporal.DATE -> java.time.LocalDate.parse(local).atStartOfDay()
            Temporal.LOCAL_DATE_TIME, Temporal.DATE_TIME -> java.time.LocalDateTime.parse(local)
            else -> java.time.LocalTime.parse(local).atDate(java.time.LocalDate.of(1970, 1, 1))
        }
        val nanos = clock.nano
        val fraction = when {
            nanos == 0 -> ""
            nanos % 1_000_000 == 0 -> ".%03d".format(java.util.Locale.ROOT, nanos / 1_000_000)
            nanos % 1000 == 0 -> ".%06d".format(java.util.Locale.ROOT, nanos / 1000)
            else -> ".%09d".format(java.util.Locale.ROOT, nanos)
        }
        val time = "%02d:%02d:%02d".format(java.util.Locale.ROOT, clock.hour, clock.minute, clock.second) + fraction
        val zone = if (kind.zoned) offset ?: "Z" else ""
        return when (kind) {
            Temporal.DATE -> clock.toLocalDate().toString()
            Temporal.LOCAL_DATE_TIME, Temporal.DATE_TIME -> "${clock.toLocalDate()}T$time$zone"
            else -> "$time$zone"
        }
    }

    private fun canonicalDuration(text: String): String {
        val groups = Regex(Temporal.DURATION.pattern).matchEntire(text)!!.groupValues
        fun part(group: Int) = groups[group].takeIf { it.isNotEmpty() }?.toBigDecimal() ?: BigDecimal.ZERO
        val sign = if (groups[1] == "-") BigDecimal.ONE.negate() else BigDecimal.ONE
        val m0 = sign * (part(3) * 12.toBigDecimal() + part(5))
        val d0 = sign * (part(7) * 7.toBigDecimal() + part(9))
        val s0 = sign * (part(12) * 3600.toBigDecimal() + part(14) * 60.toBigDecimal() + part(16))
        val monthDays = "30.436875".toBigDecimal()
        val months = m0.setScale(0, java.math.RoundingMode.DOWN)
        val daysExact = d0 + (m0 - months) * monthDays
        val days = daysExact.setScale(0, java.math.RoundingMode.DOWN)
        val seconds = s0 + (daysExact - days) * 86400.toBigDecimal()
        if (months.signum() == 0 && days.signum() == 0 && seconds.signum() == 0) return "PT0S"
        val years = months.divide(12.toBigDecimal(), 0, java.math.RoundingMode.DOWN)
        val hours = seconds.divide(3600.toBigDecimal(), 0, java.math.RoundingMode.DOWN)
        val minutes = (seconds - hours * 3600.toBigDecimal()).divide(60.toBigDecimal(), 0, java.math.RoundingMode.DOWN)
        val secs = seconds - hours * 3600.toBigDecimal() - minutes * 60.toBigDecimal()
        fun unit(value: BigDecimal, letter: String) = if (value.signum() != 0) value.stripTrailingZeros().toPlainString() + letter else ""
        val time = if (seconds.signum() != 0) "T" + unit(hours, "H") + unit(minutes, "M") + unit(secs, "S") else ""
        return "P" + unit(years, "Y") + unit(months - years * 12.toBigDecimal(), "M") + unit(days, "D") + time
    }

    /** What drivine4j's compiler makes of `<function>('<text>')`: the canonical text, or its refusal (12847912's catch). */
    private fun temporalOf(function: String, text: String): String {
        val kind = Temporal.ofFunction(function)!!
        return try {
            canonical(text, kind)?.let { "=$it" } ?: "!'$text' is no $function."
        } catch (e: DateTimeException) {
            "!'$text' is no $function: ${e.message}"
        } catch (e: NumberFormatException) {
            "!'$text' is no $function."
        } catch (e: Exception) {
            "!" + (e.message ?: e.toString())
        }
    }

    private fun temporal(random: Random, emit: (List<String>) -> Unit) {
        val functions = listOf("date", "localtime", "time", "localdatetime", "datetime")
        val years = listOf("0000", "0001", "0999", "1900", "1999", "2000", "2024", "2025", "9999", "-0000", "-0001", "-0004", "-0044", "-0100", "-0400", "-9999")
        val months = listOf("00", "01", "02", "04", "06", "09", "11", "12", "13", "99")
        val days = listOf("00", "01", "28", "29", "30", "31", "32", "99")
        val hours = listOf("00", "01", "12", "23", "24", "25", "99")
        val mins = listOf("00", "30", "59", "60", "99")
        val secs = listOf("", ":00", ":59", ":60", ":99")
        val fractions = listOf("", ".0", ".5", ".123", ".1234", ".123456", ".1234567", ".123456789", ".1234567890", ".000000000001", ".999999999", ".000001", ".001")
        val offsets = listOf("Z", "+00:00", "-00:00", "+0000", "-0000", "+01:00", "-0130", "+18:00", "-18:00", "+19:00", "+99:99", "+0530")
        val dates = years.flatMap { y -> months.flatMap { m -> days.map { d -> "$y-$m-$d" } } }
        val times = hours.flatMap { h -> mins.flatMap { m -> secs.flatMap { s -> if (s.isEmpty()) listOf("$h:$m") else fractions.map { f -> "$h:$m$s$f" } } } }
        fun case(function: String, text: String) = emit(listOf(function, text, temporalOf(function, text)))
        for (date in dates) for (f in functions) case(f, date)
        for (time in times) {
            for (f in functions) case(f, time)
            case(listOf("time", "localtime", "datetime").random(random), time + offsets.random(random))
        }
        repeat(6_000) {
            val text = dates.random(random) + "T" + times.random(random) + if (random.nextBoolean()) offsets.random(random) else ""
            case(functions.random(random), text)
        }
        // Texts of no kind, and kinds a text of another is made into.
        for (text in listOf("", "2024", "2024-1-1", "24:00:00:00", "12", "T12:00", "2024-01-01T", "2024-01-01 12:00", "12:00+1", "P1D", "12:00:00.", "+2024-01-01")) for (f in functions) case(f, text)
        // Durations.
        val parts = listOf(
            "0", "1", "-1", "2", "12", "13", "59", "60", "1.5", "-1.5", ".5", "5.", ".", "1.2.3", "..", "-", "0.000000001", "0.0000000001", "30.436875",
            "1000000", "00", "-0", "-.5", "0.5", "86400", "3600.5", "1234567890123456789012345",
            "1234567890123456789012345678901234567890123456789012345678901234567890",
            "123456789012345678901234567890.12345678901234567890", "0.333333333333333333333333333333333333333333333333333333333333333333333333",
            "99999999999999999999999999999999999999999999999999999999999999.999999999",
        )
        fun part() = if (random.nextInt(4) == 0) random.nextInt(0, 10_000).toString() + (if (random.nextBoolean()) "." + random.nextInt(0, 1000) else "") else parts.random(random)
        repeat(5_000) {
            val sign = if (random.nextInt(4) == 0) "-" else ""
            val date = listOf("Y", "M", "W", "D").joinToString("") { if (random.nextInt(5) < 2) part() + it else "" }
            val time = listOf("H", "M", "S").joinToString("") { if (random.nextInt(5) < 2) part() + it else "" }
            case("duration", "${sign}P$date" + if (time.isNotEmpty() || random.nextInt(8) == 0) "T$time" else "")
        }
        for (p in parts) for (unit in listOf("Y", "M", "W", "D", "TH", "TM", "TS")) {
            case("duration", if (unit.startsWith("T")) "PT$p${unit[1]}" else "P$p$unit")
            case("duration", if (unit.startsWith("T")) "-PT$p${unit[1]}" else "-P$p$unit")
        }
        for (text in listOf("P", "PT", "-P", "PT0S", "P0D", "P1Y2M3W4DT5H6M7.5S", "p1d", "P1DT", "1D")) case("duration", text)
    }

    // ----- String.substring, Integer.parseInt(s, 16), StringBuilder.appendCodePoint: a string literal's \u and \U -----

    /** drivine4j's reading of the escape `\u` (4 digits) or `\U` (8) that [body] opens with, by Java. */
    private fun escapeOf(body: String): String = answer {
        val digits = if (body[1] == 'u') 4 else 8
        StringBuilder().appendCodePoint(body.substring(2, 2 + digits).toInt(16)).toString()
    }

    private fun escapes(random: Random, emit: (List<String>) -> Unit) {
        val hex = "0123456789abcdefABCDEF"
        val other = "gGxzZ +-'\"\\.\u0663\u07c0\uff10\uff19\uff21\uff26\uff27\uff41\uff46\u0966\u00b2\u2460\ud835\udfce"
        val bodies = LinkedHashSet<String>()
        val eight = listOf("00000000", "00000041", "0000D7FF", "0000D800", "0000DBFF", "0000DC00", "0000DFFF", "0000FFFF", "00010000", "0001F600", "0010FFFF", "00110000",
            "0FFFFFFF", "10FFFF00", "7FFFFFFF", "80000000", "FFFFFFFF", "ffffffff", "00000000", "7fffffff")
        val four = listOf("0000", "0041", "D7FF", "D800", "DBFF", "DC00", "DFFF", "FFFF", "ffff", "00e9")
        for (e in eight) { bodies += "\\U$e"; bodies += "\\U${e}x"; bodies += "\\u$e" }
        for (f in four) for (tail in listOf("", "x", "0", "00", "000", "0000", "0000x", "zzzz", "+000", "-000", " 000", "\uff10\uff10\uff10\uff10", "\u0663\u0663\u0663\u0663", "\ud835\udfce\ud835\udfce")) {
            bodies += "\\U$f$tail"
            bodies += "\\u$f$tail"
        }
        repeat(20_000) {
            val letter = if (random.nextInt(4) == 0) 'u' else 'U'
            val head = String(CharArray(4) { hex[random.nextInt(hex.length)] })
            val tail = String(CharArray(random.nextInt(0, 7)) { if (random.nextInt(3) == 0) other[random.nextInt(other.length)] else hex[random.nextInt(hex.length)] })
            bodies += "\\$letter$head$tail"
        }
        for (body in bodies) emit(listOf(body, escapeOf(body)))
    }

    // ----- String.toByteArray(UTF_8): the bytes of a text, a lone surrogate among them -----

    private fun randomText(random: Random, length: Int): String = buildString {
        repeat(length) {
            when (random.nextInt(10)) {
                0 -> append((0xD800 + random.nextInt(0x400)).toChar())
                1 -> append((0xDC00 + random.nextInt(0x400)).toChar())
                2 -> appendCodePoint(0x10000 + random.nextInt(0x100000))
                3 -> append(random.nextInt(0x80, 0x800).toChar())
                4 -> append(random.nextInt(0x800, 0xD800).toChar())
                5 -> append(random.nextInt(0xE000, 0x10000).toChar())
                6 -> append(random.nextInt(0, 0x20).toChar())
                7 -> append("\"\\/\u007f\u2028\u2029\u0085\ufeff\ufffd".random(random))
                else -> append(random.nextInt(0x20, 0x7F).toChar())
            }
        }
    }

    private fun hex(bytes: ByteArray) = bytes.joinToString("") { "%02x".format(it) }

    private fun utf8(random: Random, emit: (List<String>) -> Unit) {
        val texts = listOf("", "a", "\ud800", "\udc00", "\ud800\udc00", "\udc00\ud800", "\ud800\ud800\udc00", "a\ud800b", "\ud800\u0041", "\udbff\udfff", "\u00e9\u20ac\ud83d\ude00")
        for (text in texts + List(5000) { randomText(random, random.nextInt(0, 13)) }) emit(listOf(text, hex(text.toByteArray(Charsets.UTF_8))))
    }

    // ----- MessageDigest's SHA-256: Postgres's procedure names -----

    private fun sha256(random: Random, emit: (List<String>) -> Unit) {
        val lengths = (0..200).toList() + listOf(255, 256, 257, 511, 512, 513, 1000, 4096, 10_000) + List(200) { random.nextInt(0, 3000) }
        for (length in lengths) {
            val bytes = random.nextBytes(length)
            emit(listOf(hex(bytes), hex(MessageDigest.getInstance("SHA-256").digest(bytes))))
        }
    }

    // ----- Kotlin/JVM's case mapping and character classes, which are Java's: String.lowercase and the like -----

    private fun ranges(test: (Char) -> Boolean): String {
        val out = mutableListOf<String>()
        var c = 0
        while (c <= 0xFFFF) {
            if (!test(c.toChar())) { c++; continue }
            val start = c
            while (c <= 0xFFFF && test(c.toChar())) c++
            out += "${start.toString(16)}-${(c - 1).toString(16)}"
        }
        return out.joinToString(",")
    }

    private fun chars(random: Random, emit: (List<String>) -> Unit) {
        // Each code point whose text Java maps to another, in lower case and in upper case; any other maps to itself.
        for (cp in 0..0x10FFFF) {
            val s = String(Character.toChars(cp))
            val lower = s.lowercase()
            val upper = s.uppercase()
            if (lower != s) emit(listOf("L", cp.toString(16), lower))
            if (upper != s) emit(listOf("U", cp.toString(16), upper))
        }
        // Texts whose mapping hangs on the characters around one: a final sigma, a dotted I, and the like.
        val special = "\u03a3\u03c3\u03c2\u0130\u0131Ii\u00df\ufb01\u0149\u01f0\u0390\u1e9e\u0307\u0301\u0345 A.'\u05d0\ud801\udc00\ud800"
        val texts = listOf("\u03a3", "A\u03a3", "A\u03a3 ", "A\u03a3A", "A\u03a3.", "\u0391\u03a3\u0391\u03a3", "\u0130", "I\u0307", "\u0130X", "\ufb01ND", "\u00df", "A\u03a3\u0301") +
            List(3000) { String(CharArray(random.nextInt(1, 7)) { special[random.nextInt(special.length)] }) }
        for (text in texts) emit(listOf("S", text, text.lowercase(), text.uppercase()))
        emit(listOf("P", "isLetter", ranges { it.isLetter() }))
        emit(listOf("P", "isDigit", ranges { it.isDigit() }))
        emit(listOf("P", "isLetterOrDigit", ranges { it.isLetterOrDigit() }))
        emit(listOf("P", "isWhitespace", ranges { it.isWhitespace() }))
        emit(listOf("P", "isHighSurrogate", ranges { it.isHighSurrogate() }))
        emit(listOf("P", "isLowSurrogate", ranges { it.isLowSurrogate() }))
        for (radix in listOf(8, 10, 16)) {
            emit(listOf("D", radix.toString(), (0..0xFFFF).mapNotNull { c -> Character.digit(c.toChar(), radix).takeIf { it >= 0 }?.let { "${c.toString(16)}:$it" } }.joinToString(",")))
        }
    }

    // ----- String.format with %s alone: Parameters.formatted -----

    private fun format(random: Random, emit: (List<String>) -> Unit) {
        val templates = listOf(
            "A statement that writes is given %s, which %s holds as no number: it would store another value in its place.",
            "%s%s", "a %s b %s c", "%s", "no placeholder %s at all, and one more %s",
        )
        val args = listOf("NaN", "NaN or an infinity", "Postgres's `jsonb`", "SQLite's JSON", "%s", "%%", "$1", "\\", "", "\u00e9\ud83d\ude00")
        for (t in templates) repeat(40) {
            val a = args.random(random)
            val b = if (random.nextInt(5) == 0) randomText(random, 4) else args.random(random)
            emit(listOf(t, a, b, String.format(t, a, b)))
        }
    }

    // ----- Numbers: parseDouble's refusals, BigInteger's reading and doubleValue, BigDecimal's text of a double, rint -----

    private fun numbers(random: Random, emit: (List<String>) -> Unit) {
        // P: Double.parseDouble's reading of a text, or the NumberFormatException it throws.
        val alphabet = "0123456789.eE+-xXpP NaIfinityty\t\u0000fFdD_,"
        val texts = listOf("", " ", "\t", "\u0000", " 1 ", " x ", "  1x  ", "1e", "e1", ".", "-", "+", "NaN", " NaN ", "nan", "Infinity", "-Infinity", "+Infinity", "inf",
            "0x1p3", "0x1.8p1", "0x", "1d", "1f", "1D", " 1f ", "1_000", "1,5", "--1", "1e999", "\u00a01", "1\u00a0", "\u20001", "\u0661") +
            List(3000) { String(CharArray(random.nextInt(0, 9)) { alphabet[random.nextInt(alphabet.length)] }) }
        for (text in texts) {
            emit(listOf("P", text, answer { java.lang.Double.doubleToRawLongBits(java.lang.Double.parseDouble(text)).toULong().toString(16) }))
        }
        // I: an integer literal's magnitude, as BigInteger(text, radix) reads it.
        for (radix in listOf(10, 16, 8)) {
            val digits = "0123456789abcdef".take(radix)
            val edges = listOf(Long.MAX_VALUE, Long.MIN_VALUE).flatMap { listOf(BigInteger.valueOf(it).abs(), BigInteger.valueOf(it).abs() + BigInteger.ONE, BigInteger.valueOf(it).abs() - BigInteger.ONE) }
            for (e in edges) emit(listOf("I", radix.toString(), e.toString(radix), BigInteger(e.toString(radix), radix).toString()))
            repeat(2000) {
                val text = String(CharArray(random.nextInt(1, 46)) { digits[random.nextInt(digits.length)] })
                emit(listOf("I", radix.toString(), text, BigInteger(text, radix).toString()))
            }
        }
        // B: BigInteger.doubleValue, of whole numbers past a long.
        repeat(600) {
            val n = BigInteger(random.nextInt(1, 1100), java.util.Random(random.nextLong())).let { if (random.nextBoolean()) it.negate() else it }
            emit(listOf("B", n.toString(), n.toDouble().toRawBits().toULong().toString(16)))
        }
        for (e in listOf(53, 54, 63, 64, 1023, 1024, 1025)) for (d in -2..2) {
            val n = BigInteger.ONE.shiftLeft(e) + BigInteger.valueOf(d.toLong())
            emit(listOf("B", n.toString(), n.toDouble().toRawBits().toULong().toString(16)))
        }
        // X: BigDecimal(d).toBigInteger(), the exact whole number a double is cut to. W: BigDecimal.valueOf(d).setScale(1).toPlainString() of a whole double.
        val doubles = List(1500) { Double.fromBits(random.nextLong()) }.filter { it.isFinite() } +
            List(1500) { random.nextLong(-(1L shl 62), 1L shl 62).toDouble() } +
            (-1074..1023).map { Math.scalb(1.0, it) } + (0..308).map { "1e$it".toDouble() } +
            listOf(0.0, -0.0, 9.0E18, -9.0E18, Math.nextDown(9.0E18), Math.nextUp(-9.0E18), 9.223372036854775807E18, Double.MAX_VALUE, -Double.MAX_VALUE, 0.5, -0.5, 1.5, 2.5)
        for (d in doubles) {
            val bits = d.toRawBits().toULong().toString(16)
            emit(listOf("X", bits, BigDecimal(d).toBigInteger().toString()))
            if (d == Math.rint(d)) emit(listOf("W", bits, BigDecimal.valueOf(d).setScale(1).toPlainString()))
            emit(listOf("R", bits, Math.rint(d).toRawBits().toULong().toString(16)))
        }
        // C: the constants the port took from kotlin.math and kotlin's enums for Java's.
        emit(listOf("C", "E", Math.E.toRawBits().toULong().toString(16)))
        emit(listOf("C", "PI", Math.PI.toRawBits().toULong().toString(16)))
        emit(listOf("C", "RoundingMode", java.math.RoundingMode.values().joinToString(",") { it.name }))
    }

    // ----- Jackson's JSON text of a value: CypherJson.writeValueAsString -----

    private fun json(random: Random, emit: (List<String>) -> Unit) {
        val mapper = jacksonObjectMapper()
        fun case(kind: String, payload: List<String>, value: Any?) = emit(listOf(kind) + payload + listOf(mapper.writeValueAsString(value)))
        val strings = listOf("", "a", "\"", "\\", "/", "\u0000", "\u001f", "\u007f", "\u0080", "\u2028", "\u2029", "\ud800", "\udc00", "\ud800\udc00", "\b\t\n\u000c\r", "\u00e9") +
            List(5000) { randomText(random, random.nextInt(0, 13)) }
        for (s in strings) case("S", listOf(s), s)
        for (n in listOf(0L, 1L, -1L, Long.MAX_VALUE, Long.MIN_VALUE, Int.MAX_VALUE.toLong(), Int.MIN_VALUE.toLong()) + List(500) { random.nextLong() }) case("N", listOf(n.toString()), n)
        val doubles = listOf(0.0, -0.0, Double.NaN, Double.POSITIVE_INFINITY, Double.NEGATIVE_INFINITY, 1.0, 1e7, 1e-3, 1e23, Double.MIN_VALUE, Double.MAX_VALUE) + List(2000) { Double.fromBits(random.nextLong()) }
        for (d in doubles) case("D", listOf(d.toRawBits().toULong().toString(16)), d)
        repeat(300) {
            val n = BigInteger(random.nextInt(1, 300), java.util.Random(random.nextLong())).let { if (random.nextBoolean()) it.negate() else it }
            case("I", listOf(n.toString()), n)
        }
        repeat(300) {
            val bytes = random.nextBytes(random.nextInt(0, 40))
            case("Y", listOf(hex(bytes)), bytes)
        }
        repeat(500) {
            val a = randomText(random, 4); val b = randomText(random, 4)
            case("A", listOf(a, b), listOf(a, b, null, true, 1L, 2.5))
            // A key that begins with `$` is the user's with one more `$` before it, as the store layout holds it (#161).
            case("M", listOf(a, b), linkedMapOf((if (a.startsWith('$')) "$$a" else a) to b, "k" to listOf(b)))
        }
    }

    // ----- DateTimeFormatter "uuuu-MM-dd'T'HH:mm:ss.SSSSSS": SqliteTimestamps.write -----

    private fun timestamps(random: Random, emit: (List<String>) -> Unit) {
        val format = DateTimeFormatter.ofPattern("uuuu-MM-dd'T'HH:mm:ss.SSSSSS")
        val years = listOf(-999_999_999, -10_000, -9999, -1000, -1, 0, 1, 999, 1000, 2026, 9999, 10_000, 99_999, 999_999_999) + List(500) { random.nextInt(-20_000, 20_000) }
        for (year in years) {
            val date = LocalDate.of(year, random.nextInt(1, 13), 1).let { it.withDayOfMonth(random.nextInt(1, it.lengthOfMonth() + 1)) }
            val nanos = listOf(0, 999, 1000, 999_999_999, random.nextInt(0, 1_000_000_000)).random(random)
            val clock = LocalDateTime.of(date, java.time.LocalTime.of(random.nextInt(24), random.nextInt(60), random.nextInt(60), nanos))
            emit(listOf(clock.year.toString(), clock.monthValue.toString(), clock.dayOfMonth.toString(), clock.hour.toString(), clock.minute.toString(), clock.second.toString(), clock.nano.toString(), format.format(clock)))
        }
    }

    // ----- A full-text query's reading: SearchText's lower case, and its classes of a word's and Oracle's characters -----

    /**
     * The characters a full-text query's lower case and its words turn on, each several times over: a capital sigma, which
     * is final or not by the word around it as `BreakIterator` reads it, and what that reads a word by: letters cased and
     * uncased, digits, what stands between them, before and after a number, a danda, marks, a format character, the kana and
     * ideographs, the gaps, surrogates alone and in pairs, and Unicode 14's new letters.
     */
    private val SEARCH_CHARACTERS = intArrayOf(
        0x3A3, 0x3A3, 0x3A3, 'a'.code, 'A'.code, '1'.code, '-'.code, '.'.code, '\''.code, '"'.code, ','.code, '_'.code, ' '.code,
        '$'.code, '%'.code, '#'.code, 0xA2, 0x964, 0x301, 0x345, 0x200D, 0x2160, 0x24B6, 0x2B0, 0x1DAC, 0x4E00, 0x30A2, 0x3042,
        0x30FC, 0x3095, '\r'.code, '\n'.code, 0xD800, 0xDC00, 0x1D400, 0x66B, 0x2027, 0xAD, 0x903, 0x37A, 0x1D2C, 0x2C2F, 0xA7C0,
        0x10570, 0x130, 0x1C5, 0xDF, 0xFB01, 0x2AFAA,
    )

    /** A text of up to 15 code points, among them a capital sigma, drawn from [SEARCH_CHARACTERS] and from any code point. */
    fun searchText(random: Random): String = buildString {
        val length = 1 + random.nextInt(if (random.nextBoolean()) 7 else 15)
        repeat(length) {
            val cp = if (random.nextInt(3) == 0) random.nextInt(0, 0x110000) else SEARCH_CHARACTERS[random.nextInt(SEARCH_CHARACTERS.size)]
            if (cp in 0xD800..0xDFFF) append(cp.toChar()) else appendCodePoint(cp)
        }
        if ('\u03a3' !in this) insert(random.nextInt(length + 1).coerceAtMost(this.length), '\u03a3')
    }

    /** [test]'s code points, of every plane, as `start-end` ranges in hexadecimal. */
    private fun codePointRanges(test: (Int) -> Boolean): String {
        val out = mutableListOf<String>()
        var cp = 0
        while (cp <= 0x10FFFF) {
            if (!test(cp)) { cp++; continue }
            val start = cp
            while (cp <= 0x10FFFF && test(cp)) cp++
            out += "${start.toString(16)}-${(cp - 1).toString(16)}"
        }
        return out.joinToString(",")
    }

    private fun search(random: Random, emit: (List<String>) -> Unit) {
        // Java's own classes, as drivine4j's TextQuery reads them: `[^\p{IsAlphabetic}\p{Nd}_]` and its Oracle's [[:alnum:]].
        emit(listOf("W", codePointRanges { Character.isAlphabetic(it) || Character.getType(it) == Character.DECIMAL_DIGIT_NUMBER.toInt() || it == '_'.code }))
        val oracle = setOf(Character.LETTER_NUMBER, Character.NON_SPACING_MARK, Character.COMBINING_SPACING_MARK)
        emit(listOf("O", codePointRanges { Character.isLetter(it) || Character.isDigit(it) || Character.getType(it).toByte() in oracle }))
        // Texts in lower case, as Kotlin's lowercase() on the JVM, Java's toLowerCase(Locale.ROOT), writes them.
        for (i in 0 until 50_000) {
            val text = searchText(random)
            emit(listOf("L", text, text.lowercase()))
        }
    }
}
