package org.drivine.cypher

import org.drivine.cypher.host.HostCall
import org.drivine.cypher.json.jvmDoubleText
import org.junit.jupiter.api.Test
import java.sql.SQLException
import java.util.Base64
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * [HostCall] against the same functions registered on the JVM's SQLite ([SqliteHost], as drivine4j registers them), for
 * arguments a function reads as another type than they are: a text read as a number, a float read as text, a blob read
 * as either. SQLite converts each its own way (a text's leading number, `12abc` as 12; a REAL's text, `Inf` for an
 * infinity), and a call through [HostCall] gives what the call through SQLite gives (liberation-data/drivine#757).
 */
class HostCallSqliteTest {

    private val texts = listOf(
        "12abc", " 3.5", "\t-7e-2xyz", "+2", ".5", "1.", "1e", "1e5", "1e+2x", "-0", "-", "abc", "", "0x10", "Infinity",
        "-Infinity", "NaN", "inf", "1.9", "007", "9223372036854775807", "9223372036854775808", "-9223372036854775808",
        "-9223372036854775809", "123456789012345678901234567890", "1e400", "-1e400", "2.5 ", "\u000B4", "1 2",
    )
    private val floats = listOf(0.1, 2.5, -1.0, 1e20, 1e-7, 1.0 / 3, Double.POSITIVE_INFINITY, Double.NEGATIVE_INFINITY, -0.0, 1e17, 123456789.125)

    /** Each value as SQLite holds it: a text, a whole number, a float, a blob, or null. */
    private val values: List<Any?> = texts + floats + listOf(7L, -3L, Long.MAX_VALUE, null) + texts.take(6).map { it.encodeToByteArray() }

    /** The calls compared: a function, and its arguments, one of them each of [values] in turn. */
    private val calls: List<Pair<String, (Any?) -> List<Any?>>> = listOf(
        "drivine_float_text" to { v -> listOf(v) },
        "drivine_trunc" to { v -> listOf(v) },
        "drivine_regex_group" to { v -> listOf("abcd", "(a)(b)(c)", v) },
        "drivine_round" to { v -> listOf(12345L, v, "HALF_UP") },
        "drivine_round" to { v -> listOf(v, 1L, "HALF_UP") },
        "drivine_canonical" to { v -> listOf(v) },
        "drivine_to_char" to { v -> listOf(v, "FM9990.00") },
    )

    @Test
    fun eachArgumentReadAsAnotherTypeIsReadAsSqliteReadsIt() {
        var compared = 0
        SqliteHost.connection().use { c ->
            for ((name, args) in calls) for (value in values) {
                val given = args(value)
                val sqlite = try {
                    c.prepareStatement("SELECT $name(${given.joinToString(", ") { "?" }})").use { s ->
                        given.forEachIndexed { i, v ->
                            when (v) {
                                null -> s.setNull(i + 1, java.sql.Types.NULL)
                                is Long -> s.setLong(i + 1, v)
                                is Double -> s.setDouble(i + 1, v)
                                is String -> s.setString(i + 1, v)
                                is ByteArray -> s.setBytes(i + 1, v)
                            }
                        }
                        s.executeQuery().use { r -> r.next(); Result.success(r.getObject(1)) }
                    }
                } catch (e: SQLException) {
                    Result.failure(e)
                }
                val answer = HostCall.call(name, given.joinToString("") { written(it) })
                val where = "$name(${given.joinToString { show(it) }}): SQLite gave ${sqlite.getOrNull()?.let(::show) ?: sqlite.exceptionOrNull()?.message}, hostCall $answer"
                sqlite.fold(
                    { got ->
                        when (answer[0]) {
                            'n' -> assertEquals(null, got, where)
                            'i' -> assertEquals((got as Number).toLong(), answer.substring(1).toLong(), where)
                            'r' -> assertEquals((got as Number).toDouble(), answer.substring(1).toDouble(), where)
                            't' -> assertEquals(got, answer.substring(1), where)
                            else -> throw AssertionError(where)
                        }
                    },
                    { failure -> assertTrue(answer.startsWith('e') && failure.message!!.contains(answer.substring(1)), where) },
                )
                compared++
            }
        }
        assertTrue(compared > 300, "only $compared calls were compared")
    }

    private fun written(value: Any?): String = when (value) {
        null -> "n"
        is Long -> item('i', value.toString())
        is Double -> item('r', jvmDoubleText(value))
        is String -> item('t', value)
        is ByteArray -> item('b', Base64.getEncoder().encodeToString(value))
        else -> error("no SQL value: $value")
    }

    private fun item(kind: Char, text: String) = "$kind${text.length}:$text"

    private fun show(value: Any?): String = when (value) {
        is String -> "'$value'"
        is ByteArray -> "x'${value.decodeToString()}'"
        else -> value.toString()
    }
}
