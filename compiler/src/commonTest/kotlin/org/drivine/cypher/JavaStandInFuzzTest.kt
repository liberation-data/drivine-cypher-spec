package org.drivine.cypher

import com.ionspin.kotlin.bignum.integer.BigInteger
import kotlinx.datetime.LocalDateTime
import kotlinx.datetime.number
import org.drivine.cypher.store.SqliteTimestamps
import org.drivine.cypher.api.CypherCompiler
import org.drivine.cypher.api.GraphOptions
import org.drivine.cypher.api.Refusal
import org.drivine.cypher.api.Store
import org.drivine.cypher.json.BigIntegerNode
import org.drivine.cypher.json.CypherJson
import org.drivine.cypher.json.jvmDoubleText
import org.drivine.cypher.json.parseDouble
import org.drivine.cypher.json.sha256
import org.drivine.cypher.number.oracleFile
import org.drivine.cypher.number.target
import org.drivine.cypher.sql.PostgresCypherCompiler
import org.drivine.cypher.sql.RoundingMode
import org.drivine.cypher.sql.Temporal
import org.drivine.cypher.sql.Temporals
import org.drivine.cypher.sql.formatted
import org.drivine.cypher.text.CommonUnicode
import org.drivine.cypher.text.JavaText
import org.drivine.cypher.text.SearchText
import org.drivine.cypher.time.CommonTime
import org.drivine.cypher.time.JVM_ZONE_IDS
import org.drivine.cypher.time.knownRegion
import kotlin.test.Test
import kotlin.test.assertTrue

/**
 * The stand-in fuzz: where the module stands in for Java (java.time, java.math, `String`'s and `StringBuilder`'s methods,
 * `MessageDigest`, Jackson), it gives what Java gives, on every target, the JVM included: checked against what Java
 * gave on the pinned JDK (`standins-<name>.txt`, see the JVM's StandIns). Each test is one stand-in's cases.
 */
class JavaStandInFuzzTest {

    /** Each case of [name]: its fields, and what the module gives for them where it differs from Java's last field. */
    private fun check(name: String, case: (List<String>) -> String?) {
        var cases = 0
        val wrong = mutableListOf<String>()
        for (line in oracleFile("standins-$name.txt").lineSequence()) {
            if (line.isEmpty()) continue
            cases++
            val fields = line.split('\t').map(::unesc)
            val ours = try {
                case(fields)
            } catch (e: Throwable) {
                "threw ${e::class.simpleName}: ${e.message}"
            }
            if (ours != null) wrong += "${fields.dropLast(1).joinToString(" | ") { show(it) }}: Java ${show(fields.last())}, ours ${show(ours)}"
        }
        println("Stand-in fuzz on $target: $name, $cases cases, ${wrong.size} unlike Java's")
        assertTrue(cases > 0, "standins-$name.txt holds no cases")
        assertTrue(wrong.isEmpty(), "$name: ${wrong.size} of $cases unlike Java's, such as:\n" + wrong.take(30).joinToString("\n"))
    }

    /** [ours], where it is not [java]: null where they agree. */
    private fun differs(java: String, ours: String): String? = ours.takeIf { it != java }

    private inline fun answer(work: () -> String): String = try {
        "=" + work()
    } catch (e: Exception) {
        "!" + (e.message ?: e.toString())
    }

    @Test
    fun zonesAreReadAsZoneIdOfReadsThem() = check("zones") { (zone, java) ->
        fun of(known: (String) -> Boolean) = CommonTime.offsetId(zone)?.let { "O$it" } ?: if (CommonTime.isRegion(zone, known)) "R" else "-"
        // The table of the pinned JDK's ids the targets other than the JVM read, on every target; and the platform's own off
        // the JVM, which is that table. The JVM's own is java.time's, whose tzdb is the running JDK's, the pinned one in CI.
        differs(java, of { it in JVM_ZONE_IDS })?.let { "$it (by JVM_ZONE_IDS)" } ?: if (target == "jvm") null else differs(java, of(::knownRegion))
    }

    @Test
    fun calendarDatesAreWorkedOutAsJavaTimeWorksThemOut() = check("calendar") { (kind, yearText, aText, bText, java) ->
        val year = yearText.toLong(); val a = aText.toLong(); val b = bText.toLong()
        val ours = try {
            if (kind == "L") {
                "=" + CommonTime.lengthOfYear(year.toInt())
            } else {
                val y = CommonTime.checkYear(year)
                val date = when (kind) {
                    "W" -> CommonTime.plusDays(CommonTime.isoWeekOne(y), (a - 1) * 7 + b - 1)
                    "O" -> CommonTime.plusDays(CommonTime.date(y, 1, 1), a - 1)
                    else -> CommonTime.plusDays(CommonTime.date(y, ((a - 1) * 3 + 1).toInt(), 1), b - 1)
                }
                "=${date.year}-${date.month.number}-${date.day}"
            }
        } catch (e: CommonTime.TemporalException) {
            "!" + e.message
        }
        differs(java, ours)
    }

    private var compiled = 0

    @Test
    fun temporalTextsAreReadAndWrittenAsJavaTimeReadsAndWritesThem() = check("temporal") { (function, text, java) ->
        val kind = Temporal.ofFunction(function)!!
        // As SqlCypherCompiler's temporal() catches what canonical throws.
        val ours = try {
            Temporals.canonical(text, kind)?.let { "=$it" } ?: "!'$text' is no $function."
        } catch (e: NumberFormatException) {
            "!'$text' is no $function."
        } catch (e: IllegalArgumentException) {
            "!'$text' is no $function: ${e.message}"
        } catch (e: Exception) {
            "!" + (e.message ?: e.toString())
        }
        // And through the compiler itself, for one case in 40: its refusal, or no refusal.
        val whole = if (compiled++ % 40 != 0) null else {
            val result = CypherCompiler.compile(Store.SQLITE, GraphOptions("g"), "RETURN $function('$text') AS v")
            val refused = (result as? Refusal)?.message
            when {
                java.startsWith("!") && refused != java.drop(1) -> "compiled: ${refused ?: "no refusal"}"
                java.startsWith("=") && refused != null -> "compiled: $refused"
                else -> null
            }
        }
        differs(java, ours) ?: whole
    }

    @Test
    fun aStringLiteralsEscapesAreReadAsJavaReadsThem() = check("escapes") { (body, java) ->
        differs(java, answer {
            // As CypherReader's string() reads an escape.
            val digits = if (body[1] == 'u') 4 else 8
            JavaText.run { appendCodePoint(StringBuilder(), parseInt(substring(body, 2, 2 + digits), 16)) }.toString()
        })
    }

    @Test
    fun textsAreEncodedAsJavaEncodesThemInUtf8() = check("utf8") { (text, java) ->
        differs(java, hex(JavaText.utf8(text)))
    }

    @Test
    fun sha256IsMessageDigests() = check("sha256") { (bytes, java) ->
        differs(java, hex(sha256(unhex(bytes))))
    }

    /**
     * The code points Unicode 14 gave a case pair, which JDK 21 maps and Kotlin's own tables off the JVM don't. The compiler
     * maps the case of names only to compare them with ASCII ones (function names, DDL keywords), which no such letter's
     * either case is, so these are let be: any other code point mapped unlike Java's fails.
     */
    private val NEWER_CASES: Set<Int> = (listOf(0x2C2F, 0x2C5F, 0xA7C0, 0xA7C1, 0xA7D0, 0xA7D1, 0xA7D6, 0xA7D7, 0xA7D8, 0xA7D9) +
        (0x10570..0x105BC)).toSet().takeUnless { target == "jvm" } ?: emptySet()

    private val lower = HashMap<Int, String>()
    private val upper = HashMap<Int, String>()

    @Test
    fun charactersAreMappedAndClassedAsJavaMapsAndClassesThem() {
        val listed = runCatching { check("chars") { fields ->
            when (fields[0]) {
                "L" -> { lower[fields[1].toInt(16)] = fields[2]; null }
                "U" -> { upper[fields[1].toInt(16)] = fields[2]; null }
                "S" -> {
                    val (_, text, javaLower, javaUpper) = fields
                    // Off the JVM a final sigma is told by Unicode's rule, by the letters around it: Java's is by word breaks
                    // (ConditionalSpecialCasing), which is costly to copy. The compiler lowercases only names it compares with
                    // ASCII ones (function names), where σ and ς both differ alike, so it is let be.
                    fun sigma(s: String) = if (target == "jvm") s else s.replace('\u03c2', '\u03c3')
                    differs("${sigma(javaLower)} / $javaUpper", "${sigma(text.lowercase())} / ${text.uppercase()}")
                }
                "P" -> {
                    val (_, name, java) = fields
                    val test: (Char) -> Boolean = when (name) {
                        "isLetter" -> JavaText::isLetter
                        "isDigit" -> Char::isDigit
                        "isLetterOrDigit" -> JavaText::isLetterOrDigit
                        "isWhitespace" -> Char::isWhitespace
                        "isHighSurrogate" -> Char::isHighSurrogate
                        else -> Char::isLowSurrogate
                    }
                    unlike(expand(java), (0..0xFFFF).filter { test(it.toChar()) }.map { it.toString(16) }.toSet())
                }
                else -> {
                    val (_, radix, java) = fields
                    unlike(java.split(',').toSet(), (0..0xFFFF).mapNotNull { c -> c.toChar().digitToIntOrNull(radix.toInt())?.let { "${c.toString(16)}:$it" } }.toSet())
                }
            }
        } }
        // Every code point, in lower and in upper case: those not listed map to themselves.
        val wrong = mutableListOf<String>()
        for (cp in 0..0x10FFFF) {
            val s = if (cp < 0x10000) cp.toChar().toString() else charArrayOf((0xD800 + ((cp - 0x10000) shr 10)).toChar(), (0xDC00 + ((cp - 0x10000) and 0x3FF)).toChar()).concatToString()
            val l = s.lowercase(); val u = s.uppercase()
            if (cp in NEWER_CASES) continue
            if (l != (lower[cp] ?: s)) wrong += "lower case of U+${cp.toString(16)}: Java ${show(lower[cp] ?: s)}, ours ${show(l)}"
            if (u != (upper[cp] ?: s)) wrong += "upper case of U+${cp.toString(16)}: Java ${show(upper[cp] ?: s)}, ours ${show(u)}"
        }
        println("Stand-in fuzz on $target: case mapping of every code point, ${wrong.size} unlike Java's")
        assertTrue(lower.isNotEmpty() && wrong.isEmpty(), "${wrong.size} code points unlike Java's, such as:\n" + wrong.take(30).joinToString("\n"))
        listed.getOrThrow()
    }

    @Test
    fun formattedIsStringFormatOfPercentS() = check("format") { (template, a, b, java) ->
        differs(java, template.formatted(a, b))
    }

    @Test
    fun numbersAreReadAndWrittenAsJavaMathReadsAndWritesThem() = check("numbers") { fields ->
        val java = fields.last()
        fun bits(d: Double) = d.toRawBits().toULong().toString(16)
        fun double(bits: String) = Double.fromBits(bits.toULong(16).toLong())
        when (fields[0]) {
            "P" -> differs(java, answer { bits(parseDouble(fields[1])) })
            "I" -> differs(java, BigInteger.parseString(fields[2], fields[1].toInt()).toString())
            "B" -> differs(java, bits(BigIntegerNode(BigInteger.parseString(fields[1])).doubleValue()))
            "X" -> differs(java, CommonTime.exactWhole(double(fields[1])))
            "W" -> differs(java, PostgresCypherCompiler.wholePlain(jvmDoubleText(double(fields[1]))))
            "R" -> differs(java, bits(kotlin.math.round(double(fields[1]))))
            else -> when (fields[1]) {
                "E" -> differs(java, bits(kotlin.math.E))
                "PI" -> differs(java, bits(kotlin.math.PI))
                else -> differs(java, RoundingMode.entries.joinToString(",") { it.name })
            }
        }
    }

    @Test
    fun valuesAreWrittenAsJacksonWritesThem() = check("json") { fields ->
        val java = fields.last()
        val value: Any? = when (fields[0]) {
            "S" -> fields[1]
            "N" -> fields[1].toLong()
            "D" -> Double.fromBits(fields[1].toULong(16).toLong())
            "I" -> BigInteger.parseString(fields[1])
            "Y" -> unhex(fields[1])
            "A" -> listOf(fields[1], fields[2], null, true, 1L, 2.5)
            else -> linkedMapOf(fields[1] to fields[2], "k" to listOf(fields[2]))
        }
        differs(java, CypherJson.writeValueAsString(value))
    }

    /**
     * A full-text query's reading ([SearchText]): Java's classes of a word's characters and of Oracle's `[[:alnum:]]`, over
     * every code point, and Java's lower case of texts a final sigma's word turns on. The tables the targets but the JVM
     * read ([CommonUnicode]) are checked on every target, the JVM's among them, as well as what each target reads.
     */
    @Test
    fun searchTextIsReadAsJavaReadsIt() = check("search") { fields ->
        when (fields[0]) {
            "W" -> unlikeCodePoints(fields[1], SearchText::isWordChar) ?: unlikeCodePoints(fields[1], CommonUnicode::isWordChar)?.let { "tables: $it" }
            "O" -> unlikeCodePoints(fields[1], SearchText::isOracleAlnum) ?: unlikeCodePoints(fields[1], CommonUnicode::isOracleAlnum)?.let { "tables: $it" }
            else -> {
                val (_, text, java) = fields
                differs(java, SearchText.lowercase(text)) ?: differs(java, CommonUnicode.lowercase(text))?.let { "tables: $it" }
            }
        }
    }

    /** The code points [test] and Java's ranges [java] differ by, of every plane: null where none. */
    private fun unlikeCodePoints(java: String, test: (Int) -> Boolean): String? {
        val ranges = java.split(',').map { r -> r.substringBefore('-').toInt(16)..r.substringAfter('-').toInt(16) }
        var r = 0
        val wrong = mutableListOf<Int>()
        for (cp in 0..0x10FFFF) {
            while (r < ranges.size && cp > ranges[r].last) r++
            val javas = r < ranges.size && cp in ranges[r]
            if (test(cp) != javas) wrong += cp
        }
        return if (wrong.isEmpty()) null else "${wrong.size} code points unlike Java's, such as ${wrong.take(20).joinToString(",") { it.toString(16) }}"
    }

    @Test
    fun timestampsAreWrittenAsTheirDateTimeFormatterWritesThem() = check("timestamps") { f ->
        val clock = LocalDateTime(f[0].toInt(), f[1].toInt(), f[2].toInt(), f[3].toInt(), f[4].toInt(), f[5].toInt(), f[6].toInt())
        differs(f[7], SqliteTimestamps.write(clock))
    }

    /** The characters of Java's ranges `start-end,…`, in hexadecimal. */
    private fun expand(ranges: String): Set<String> = ranges.split(',').flatMap { r ->
        val (start, end) = r.split('-').map { it.toInt(16) }
        (start..end).map { it.toString(16) }
    }.toSet()

    /** The characters Java's set and ours differ by: null where none. */
    private fun unlike(java: Set<String>, ours: Set<String>): String? =
        if (java == ours) null else "Java's alone ${spans(java - ours)}, ours alone ${spans(ours - java)}"

    /** The characters [chars] (hexadecimal, or `hex:value`) as spans of consecutive ones: `870-887`. */
    private fun spans(chars: Set<String>): String {
        val codes = chars.map { it.substringBefore(':').toInt(16) }.sorted()
        val out = mutableListOf<String>()
        var i = 0
        while (i < codes.size) {
            var j = i
            while (j + 1 < codes.size && codes[j + 1] == codes[j] + 1) j++
            out += if (i == j) codes[i].toString(16) else "${codes[i].toString(16)}-${codes[j].toString(16)}"
            i = j + 1
        }
        return "${codes.size} [" + out.take(60).joinToString(",") + "]"
    }

    private fun hex(bytes: ByteArray) = bytes.joinToString("") { (it.toInt() and 0xff).toString(16).padStart(2, '0') }

    private fun unhex(text: String) = ByteArray(text.length / 2) { text.substring(it * 2, it * 2 + 2).toInt(16).toByte() }

    /** A field as StandIns.esc wrote it. */
    private fun unesc(s: String): String {
        if ('\\' !in s) return s
        val out = StringBuilder()
        var i = 0
        while (i < s.length) {
            if (s[i] == '\\' && s[i + 1] == 'u') {
                out.append(s.substring(i + 2, i + 6).toInt(16).toChar())
                i += 6
            } else if (s[i] == '\\') {
                out.append(s[i + 1])
                i += 2
            } else {
                out.append(s[i++])
            }
        }
        return out.toString()
    }

    /** [s] as a report shows it: every character outside printable ASCII as `\uXXXX`. */
    private fun show(s: String): String = buildString {
        append('"')
        for (c in s) if (c in ' '..'~') append(c) else append("\\u").append(c.code.toString(16).padStart(4, '0'))
        append('"')
    }
}
