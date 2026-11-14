package org.drivine.cypher

import org.drivine.cypher.host.HostArgs
import org.drivine.cypher.host.HostFunction
import org.drivine.cypher.host.HostFunctions
import org.drivine.cypher.host.HostResult
import org.drivine.cypher.host.SqlType
import org.drivine.cypher.json.CypherJson
import org.drivine.cypher.sql.SqliteCypherCompiler
import org.drivine.cypher.sql.Temporal
import org.drivine.cypher.sql.TemporalArithmeticFunction
import org.drivine.cypher.sql.Temporals
import org.drivine.cypher.store.ValueLayout
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestInstance
import org.sqlite.Function
import org.sqlite.core.Codes
import java.sql.Connection
import java.sql.DriverManager
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * drivine4j#418: SQLite's host function of temporal arithmetic, `drivine_temporal_arithmetic`, gives what the compiler's inline
 * SQLite SQL gives for the same operator and values, byte for byte, the inline SQL run on SQLite with the module's host functions
 * registered as drivine4j registers them. The inline builder is what it is checked against: each case is a pair of kinds and
 * values, generated over the edges a calendar has (a month's end, a leap day, a year BC, an offset, a fraction of a second, a
 * negative duration, a fraction of a month).
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class TemporalArithmeticHostTest {

    private val connection: Connection = DriverManager.getConnection("jdbc:sqlite::memory:").also { c ->
        HostFunctions.all.forEach { register(c, it) }
        // drivine_fail, which each library registers itself, as a refusal the SQL writes calls it (#263's among them).
        Function.create(c, org.drivine.cypher.store.SqliteFunctions.FAIL, object : Function() {
            override fun xFunc() = error(value_text(0) ?: "failed")
        }, 1, 0)
    }

    @AfterAll
    fun close() = connection.close()

    private val moments = mapOf(
        Temporal.DATE to listOf("2024-01-31", "2024-02-29", "2023-03-31", "-0044-03-15", "0001-01-01", "9999-12-01", "2024-12-31"),
        Temporal.LOCAL_TIME to listOf("23:30:00", "00:15:00.25", "12:00:00.000001", "00:00:00", "23:59:59.999999"),
        Temporal.TIME to listOf("10:00:00+02:00", "23:30:00Z", "00:00:00.5-05:30", "12:00:00+14:00"),
        Temporal.LOCAL_DATE_TIME to listOf("2024-01-31T23:59:59.5", "2024-02-29T12:00:00", "-0044-03-15T10:30:00", "1999-12-31T23:59:59.999999"),
        Temporal.DATE_TIME to listOf("2024-01-31T10:00:00Z", "2024-03-30T12:00:00+01:00", "1999-12-31T23:59:59.123456+05:30", "2024-02-29T00:00:00-12:00"),
    )

    private val durations = listOf(
        "P1M", "P1Y", "-P1M2DT3H", "P1M2DT3H", "-P2M1DT30M", "PT45M", "PT14H", "PT25H", "PT1S", "PT0.5S", "-PT0.000001S", "P1W", "P1DT2H",
        "PT30M0.5S", "P1Y2M3DT4H5M6.789S", "-P1Y2M3W4DT5H6M7.5S", "PT0S", "P0.5M", "P1.5D", "PT24H", "-PT24H",
        "PT-1.5S", "-PT1M0.5S", "-PT25H0.5S", "P1.5M", "-P0.5M", "PT1.5H", "PT0.9999999999S", "PT0.0000000001S",
    )

    private val numbers = listOf("2", "3", "-1", "0.5", "1.5", "2.5", "-7", "10", "1e3", "2.5E-1")

    /**
     * The cases where the inline SQLite SQL is wrong, and the host function gives Neo4j's answer: each fails this test where it
     * starts to match, so the list shrinks as the builder is fixed, and the test is back to all alike.
     * - A duration scaled by a number the inline SQL works out in SQLite's doubles, which writes a tail of a nanosecond
     *   (`6.262999999S`) where the answer is exact (liberation-data/drivine-cypher-spec#262).
     */
    private val known = setOf(
        "duration('P1Y2M3DT4H5M6.789S') / 3",
        "duration('P1Y2M3DT4H5M6.789S') / 1.5",
        "duration('P1Y2M3DT4H5M6.789S') / 2.5",
        "duration('-P1Y2M3W4DT5H6M7.5S') / 3",
        "duration('-P1Y2M3W4DT5H6M7.5S') / 1.5",
        "duration('-P1Y2M3W4DT5H6M7.5S') / 2.5",
    )

    @Test
    fun `the host function gives what the inline SQL gives, for each operator and pair of kinds`() {
        val cases = mutableListOf<Triple<String, String, String>>()
        moments.forEach { (kind, texts) ->
            texts.forEach { text -> durations.forEach { d ->
                cases += Triple("+", literal(kind, text), literal(Temporal.DURATION, d))
                cases += Triple("+", literal(Temporal.DURATION, d), literal(kind, text))
                cases += Triple("-", literal(kind, text), literal(Temporal.DURATION, d))
            } }
        }
        durations.forEach { a -> durations.forEach { b ->
            cases += Triple("+", literal(Temporal.DURATION, a), literal(Temporal.DURATION, b))
            cases += Triple("-", literal(Temporal.DURATION, a), literal(Temporal.DURATION, b))
        } }
        durations.forEach { d -> numbers.forEach { k ->
            cases += Triple("*", literal(Temporal.DURATION, d), k)
            cases += Triple("*", k, literal(Temporal.DURATION, d))
            cases += Triple("/", literal(Temporal.DURATION, d), k)
        } }
        val differ = cases.mapNotNull { (op, x, y) ->
            val inline = inline("$x $op $y")
            val host = host(op, x, y)
            val alike = inline == host
            val expected = "$x $op $y" !in known
            if (alike == expected) null else if (alike) "$x $op $y: alike, but listed as known" else "$x $op $y: inline $inline, host $host"
        }
        assertTrue(differ.isEmpty(), "${differ.size} of ${cases.size} unlike the list, such as:\n${differ.take(25).joinToString("\n")}")
        assertTrue(known.all { k -> cases.any { (op, x, y) -> "$x $op $y" == k } }, "a known case is no longer generated")
        assertTrue(cases.size > 2000, "${cases.size}")
    }

    @Test
    fun `null gives null, and a pair Cypher has no arithmetic of fails by name`() {
        assertEquals(null, call("+", tag(Temporal.DURATION, "P1D"), null))
        assertEquals(null, call("-", "null", tag(Temporal.DURATION, "P1D")))
        listOf(
            Triple("+", tag(Temporal.DATE, "2024-01-01"), tag(Temporal.DATE, "2024-01-01")),
            Triple("-", tag(Temporal.DURATION, "P1D"), tag(Temporal.DATE, "2024-01-01")),
            Triple("/", "2", tag(Temporal.DURATION, "P1D")),
            Triple("*", tag(Temporal.DURATION, "P1D"), tag(Temporal.DURATION, "P1D")),
        ).forEach { (op, x, y) ->
            val failure = runCatching { call(op, x, y) }.exceptionOrNull()
            assertTrue(failure?.message?.contains(TemporalArithmeticFunction.UNDEFINED) == true, "$op: $failure")
        }
    }

    @Test
    fun `a number given as a SQL integer or float is the number its JSON text is, and an empty text is no value`() {
        val month = tag(Temporal.DURATION, "P1M")
        listOf<Pair<Any, String>>(3L to "3", 1.5 to "1.5", 1e-7 to "1e-7", 1e20 to "1e20", -7L to "-7").forEach { (number, json) ->
            listOf("*", "/").forEach { op ->
                assertEquals(call(op, month, json), call(op, month, number), "$op $number")
            }
            assertEquals(call("*", json, month), call("*", number, month), "$number *")
        }
        assertEquals("{\"\$t\":\"duration\",\"v\":\"P3M\"}", call("*", month, 3L))
        assertEquals(null, call("+", month, ""))
        assertEquals(null, call("*", " ", month))
    }

    /** The Cypher literal of [text] of [kind]. */
    private fun literal(kind: Temporal, text: String) = "${kind.function}('$text')"

    /** The tag, as JSON text, of [text] of [kind], as the store holds one. */
    private fun tag(kind: Temporal, text: String) = CypherJson.writeValueAsString(ValueLayout.tag(kind, Temporals.canonical(text, kind)!!))

    /** A side, written as a Cypher literal or a number, as the store holds it: a tag, or the number's JSON. */
    private fun stored(side: String): String {
        val kind = Temporal.entries.firstOrNull { side.startsWith(it.function + "('") } ?: return side
        return tag(kind, side.removePrefix(kind.function + "('").removeSuffix("')"))
    }

    /** What the inline SQL the compiler writes for [expression] gives on SQLite: its value as JSON text, or its failure. */
    private fun inline(expression: String): String = runCatching {
        val compiled = SqliteCypherCompiler("g").compile("RETURN $expression AS r", emptyMap())
        val sql = compiled.parameters.entries.sortedByDescending { it.key.length }.fold(compiled.sql) { text, (name, value) ->
            text.replace("\$$name", if (value is Number) value.toString() else "'" + value.toString().replace("'", "''") + "'")
        }
        connection.createStatement().use { s -> s.executeQuery(sql).use { r -> r.next(); r.getString(1) } }
    }.fold({ value -> normal(value) }, { "fails" })

    /** What the host function gives for [op] of the sides [x] and [y]: its tag as JSON text, or its failure. */
    private fun host(op: String, x: String, y: String): String = runCatching { call(op, stored(x), stored(y)) }.fold({ normal(it) }, { "fails" })

    /** The host function of [op], [x] and [y], each a text, a SQL number ([Long] or [Double]), or null. */
    private fun call(op: String, x: Any?, y: Any?): String? =
        connection.prepareStatement("SELECT ${TemporalArithmeticFunction.HOST}(?, ?, ?)").use { s ->
            s.setString(1, op); s.setObject(2, x); s.setObject(3, y)
            s.executeQuery().use { r -> r.next(); r.getString(1) }
        }

    /** [json], a value or a tag, written alike whichever wrote it: a map's keys in order, as the two write them in their own. */
    private fun normal(json: String?): String = if (json == null) "null" else sorted(CypherJson.readTree(json)).toString()

    private fun sorted(node: org.drivine.cypher.json.JsonNode): Any? = when (node) {
        is org.drivine.cypher.json.ObjectNode -> node.fieldNames().asSequence().toList().sorted().associateWith { sorted(node[it]!!) }.toSortedMap()
        is org.drivine.cypher.json.ArrayNode -> node.map { sorted(it) }
        else -> CypherJson.writeValueAsString(node)
    }

    /** [function] registered on [connection], as drivine4j's `SqliteHostFunction` registers each. */
    private fun register(connection: Connection, function: HostFunction) =
        Function.create(connection, function.name, Host(function), function.arity, if (function.deterministic) Function.FLAG_DETERMINISTIC else 0)

    /** A host function as xerial's driver calls one: drivine4j's `SqliteHostFunction`, for the test. */
    private class Host(private val function: HostFunction) : Function() {
        override fun xFunc() {
            try {
                function.body.call(args, args(), result)
            } catch (e: Exception) {
                error(e.message ?: e.javaClass.simpleName)
            }
        }

        private val args = object : HostArgs {
            override fun type(i: Int) = when (value_type(i)) {
                Codes.SQLITE_INTEGER -> SqlType.INTEGER
                Codes.SQLITE_FLOAT -> SqlType.FLOAT
                Codes.SQLITE_TEXT -> SqlType.TEXT
                Codes.SQLITE_BLOB -> SqlType.BLOB
                else -> SqlType.NULL
            }
            override fun long(i: Int) = value_long(i)
            override fun int(i: Int) = value_int(i)
            override fun double(i: Int) = value_double(i)
            override fun text(i: Int): String? = value_text(i)
            override fun text16(i: Int): String? = value_text(i)
            override fun blob(i: Int): ByteArray? = value_blob(i)
        }

        private val result = object : HostResult {
            override fun nullValue() = result()
            override fun long(value: Long) = result(value)
            override fun int(value: Int) = result(value)
            override fun double(value: Double) = result(value)
            override fun text(value: String) = result(value)
            override fun text16(value: String) = result(value)
            override fun blob(value: ByteArray) = result(value)
            override fun error(message: String) = this@Host.error(message)
        }
    }
}
