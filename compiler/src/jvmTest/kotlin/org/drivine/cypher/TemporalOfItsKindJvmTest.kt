package org.drivine.cypher

import org.drivine.cypher.api.CypherCompiler
import org.drivine.cypher.api.GraphOptions
import org.drivine.cypher.api.Refusal
import org.drivine.cypher.api.Store
import org.drivine.cypher.sql.OracleCypherCompiler
import org.drivine.cypher.sql.PostgresCypherCompiler
import org.drivine.cypher.sql.SqliteCypherCompiler
import org.drivine.cypher.store.StoreValues
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import java.time.Duration
import java.time.Instant
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime
import java.time.OffsetDateTime
import java.time.OffsetTime
import java.time.Period
import java.time.ZoneId
import java.time.ZoneOffset
import java.time.ZonedDateTime
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * The JVM's own temporal values, as drivine4j hands them to the module, at an offset with seconds, are refused by name, as
 * the JSON request's `{"$temporal": …}` is (`TemporalOfItsKindTest`), not bound or stored as their text
 * (liberation-data/drivine-cypher-spec#357). A zone's local mean time has such an offset: `Africa/Monrovia` before 1972.
 */
class TemporalOfItsKindJvmTest {

    private val seconds = ZoneOffset.ofHoursMinutesSeconds(0, -44, -30)

    private val refused = listOf(
        OffsetDateTime.of(1971, 1, 1, 0, 0, 0, 0, seconds) to
            "A value given as a DATE_TIME isn't one: 1971-01-01T00:00:00-00:44:30 (the store holds an offset to the minute).",
        OffsetTime.of(12, 0, 0, 0, seconds) to "A value given as a TIME isn't one: 12:00-00:44:30 (the store holds an offset to the minute).",
        ZonedDateTime.of(1971, 1, 1, 0, 0, 0, 0, ZoneId.of("Africa/Monrovia")) to
            "A value given as a DATE_TIME isn't one: 1971-01-01T00:00:00-00:44:30 (the store holds an offset to the minute).",
    )

    @Test
    fun `a JVM temporal value past the years the store holds is refused by name, with no word of an offset`() {
        val message = "A value given as a DATE_TIME isn't one: +1000000000-12-31T23:59:59.999999999Z."
        for (store in Store.entries) {
            assertEquals(Refusal(message), CypherCompiler.compile(store, GraphOptions("g"), "RETURN \$t AS r", mapOf("t" to Instant.MAX)), store.id)
            assertEquals(message, StoreValues.refusal(mapOf("at" to Instant.MAX), store), store.id)
        }
    }

    private val compilers = listOf(PostgresCypherCompiler("g"), OracleCypherCompiler("g"), SqliteCypherCompiler("g"))

    @Test
    fun `a JVM temporal value at an offset with seconds is refused by name by each compiler, in a read and a write`() {
        for (compiler in compilers) for ((value, message) in refused) {
            for (parameters in listOf(mapOf("t" to value), mapOf("t" to listOf(mapOf("at" to value))))) {
                for (cypher in listOf("RETURN toString(\$t) AS r", "UNWIND [\$t] AS x CREATE (n:N {t: x})")) {
                    val refusal = assertThrows<CompilerException>("${compiler::class.simpleName}: $cypher, $parameters") { compiler.compile(cypher, parameters) }
                    assertEquals(message, refusal.message, "${compiler::class.simpleName}: $cypher, $parameters")
                }
            }
        }
    }

    @Test
    fun `CypherCompiler refuses a JVM temporal value at an offset with seconds, and a save does too`() {
        for (store in Store.entries) for ((value, message) in refused) {
            val result = CypherCompiler.compile(store, GraphOptions("g"), "RETURN \$t AS r", mapOf("t" to value))
            assertEquals(Refusal(message), result, "${store.id}: $value")
            assertEquals(message, StoreValues.refusal(mapOf("at" to value), store), "${store.id}: $value")
        }
    }

    @Test
    fun `the JVM's temporal values of each kind at an offset to the minute are still bound as their tags`() {
        for (value in listOf(
            OffsetDateTime.of(1971, 1, 1, 0, 0, 0, 0, ZoneOffset.ofHoursMinutes(0, -45)),
            OffsetTime.of(12, 0, 0, 0, ZoneOffset.ofHoursMinutes(5, 30)),
            ZonedDateTime.of(1972, 6, 1, 0, 0, 0, 0, ZoneId.of("Africa/Monrovia")),
            ZonedDateTime.of(2024, 3, 15, 10, 30, 0, 0, ZoneId.of("Europe/Paris")),
            Instant.parse("2024-03-15T10:30:00Z"),
            LocalDate.of(2024, 1, 1),
            LocalDate.of(10000, 1, 1),
            LocalTime.of(10, 30),
            LocalDateTime.of(2024, 1, 1, 10, 30),
            Duration.ofMinutes(-90),
            Duration.ofSeconds(1, 500),
            Period.of(1, 2, 3),
            Period.of(-1, 0, 0),
        )) {
            for (compiler in compilers) {
                val bound = compiler.compile("RETURN \$t AS r", mapOf("t" to value)).parameters.values.joinToString()
                assertTrue("\"\$t\":" in bound, "${compiler::class.simpleName}: $value is bound as $bound")
            }
        }
    }
}
