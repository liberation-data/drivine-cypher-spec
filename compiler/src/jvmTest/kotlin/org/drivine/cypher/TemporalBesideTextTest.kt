package org.drivine.cypher

import org.drivine.cypher.json.TemporalValue
import org.drivine.cypher.sql.OracleCypherCompiler
import org.drivine.cypher.sql.PostgresCypherCompiler
import org.drivine.cypher.sql.SqliteCypherCompiler
import org.drivine.cypher.sql.Temporal
import org.junit.jupiter.api.Test
import java.time.Duration
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime
import java.time.OffsetDateTime
import java.time.OffsetTime
import java.time.ZoneOffset
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * A temporal value the statement makes is equal to no text, though its ISO text is (drivine4j#379): the comparison is
 * false, or an `IN` reads the value as a map of its kind (`{"date": …}`). A parameter of a temporal kind is no text, and
 * is compared as the moment it is. What the stores give is the spec's, in `temporal.yaml` ("A temporal value beside a
 * text that spells it", and "A text parameter in a list or a map beside a temporal value"); its parameters can't be of a
 * temporal kind, so that is pinned here.
 */
class TemporalBesideTextTest {

    private val compilers = listOf(PostgresCypherCompiler("g"), OracleCypherCompiler("g"), SqliteCypherCompiler("g"))

    private fun each(check: (name: String, sql: (String, Map<String, Any?>) -> String) -> Unit) =
        compilers.forEach { compiler -> check(compiler::class.simpleName!!) { cypher, parameters -> compiler.compile(cypher, parameters).sql } }

    private val date = TemporalValue(Temporal.DATE, "2024-01-01")

    /** The kind's pattern, which a comparison of two dates tests each side's text by. */
    private val pattern = "[0-9]{2}-[0-9]{2}"

    @Test
    fun `a date beside a parameter of a temporal kind is compared as a date, and beside a text is not`() {
        each { store, sql ->
            val temporal = sql("RETURN date('2024-01-01') = \$d", mapOf("d" to date))
            assertTrue(pattern in temporal && "THEN FALSE END" !in temporal, "$store: $temporal")
            val text = sql("RETURN date('2024-01-01') = \$d", mapOf("d" to "2024-01-01"))
            assertTrue(pattern !in text && "THEN FALSE END" in text, "$store: $text")
        }
    }

    @Test
    fun `a date in a list given of temporal values, or of lists of them, or beside a map, is not read as a map of its kind`() {
        each { store, sql ->
            listOf(
                "RETURN date('2024-01-01') IN \$l" to mapOf("l" to listOf(date)),
                "RETURN [[date('2024-01-01')]] = \$l" to mapOf("l" to listOf(listOf(date))),
                "WITH date('2024-01-01') AS d RETURN d IN \$l" to mapOf("l" to listOf(mapOf("date" to "2024-01-01"))),
                "WITH [date('2024-01-01')] AS ds RETURN \$m IN ds" to mapOf("m" to mapOf("date" to "2024-01-01")),
            ).forEach { (cypher, parameters) -> sql(cypher, parameters).let { assertFalse("'date'" in it, "$store, $cypher: $it") } }
            val kinded = sql("RETURN date('2024-01-01') IN \$l", mapOf("l" to listOf("2024-01-01")))
            assertTrue("'date'" in kinded, "$store: $kinded")
        }
    }

    /** A parameter of each temporal kind, tagged ([TemporalValue]) and as java.time gives it, and the ISO text that spells it. */
    private val kinds = listOf(
        Triple(TemporalValue(Temporal.DATE, "2024-01-01"), LocalDate.of(2024, 1, 1), "2024-01-01"),
        Triple(TemporalValue(Temporal.LOCAL_DATE_TIME, "2024-01-01T10:00:00"), LocalDateTime.of(2024, 1, 1, 10, 0), "2024-01-01T10:00:00"),
        Triple(TemporalValue(Temporal.DATE_TIME, "2024-01-01T10:00:00Z"), OffsetDateTime.of(2024, 1, 1, 10, 0, 0, 0, ZoneOffset.UTC), "2024-01-01T10:00:00Z"),
        Triple(TemporalValue(Temporal.LOCAL_TIME, "10:00:00"), LocalTime.of(10, 0), "10:00:00"),
        Triple(TemporalValue(Temporal.TIME, "10:00:00Z"), OffsetTime.of(10, 0, 0, 0, ZoneOffset.UTC), "10:00:00Z"),
        Triple(TemporalValue(Temporal.DURATION, "P1D"), Duration.ofDays(1), "P1D"),
    )

    @Test
    fun `a temporal parameter in a list or a map beside a text parameter that spells it is not equal to it`() {
        // drivine4j#779: in a list, as a map of its kind; in a map, entry by entry, so the comparison is false.
        val listed = listOf("RETURN \$v IN [\$s]", "RETURN \$s IN [\$v]", "RETURN [\$v] = [\$s]", "RETURN [\$s] <> [\$v]", "RETURN [[\$v]] = [[\$s]]", "RETURN [\$v] IN [[\$s]]")
        val mapped = listOf("RETURN {k: \$v} = {k: \$s}", "RETURN {k: \$s} <> {k: \$v}", "RETURN {k: \$v, n: 1} = {k: \$s, n: 1}", "RETURN {n: 1, k: \$v} = {k: \$s, n: 1}")
        each { store, sql ->
            kinds.forEach { (tagged, platform, text) ->
                listOf(tagged, platform).forEach { v ->
                    val kind = (if (v is TemporalValue) v.kind else Temporal.ofValue(v)!!).name.lowercase()
                    val parameters = mapOf("v" to v, "s" to text)
                    listed.forEach { cypher -> sql(cypher, parameters).let { assertTrue("'$kind'" in it, "$store, $cypher, $v: $it") } }
                    mapped.forEach { cypher -> sql(cypher, parameters).let { assertTrue("THEN FALSE END" in it, "$store, $cypher, $v: $it") } }
                }
            }
        }
    }

    @Test
    fun `a temporal parameter in a list or a map beside a map that holds its text, or a temporal parameter, is compared whole`() {
        each { store, sql ->
            val map = mapOf("date" to "2024-01-01")
            listOf(
                "RETURN \$v IN [\$m]" to mapOf("v" to date, "m" to map),
                "RETURN [\$v] = [\$m]" to mapOf("v" to date, "m" to map),
                "RETURN [\$v] = [\$w]" to mapOf("v" to date, "w" to date),
            ).forEach { (cypher, parameters) -> sql(cypher, parameters).let { assertFalse("'date'" in it, "$store, $cypher: $it") } }
            listOf(
                "RETURN {k: \$v} = {k: \$m}" to mapOf("v" to date, "m" to map),
                "RETURN {k: \$v} = {k: \$w}" to mapOf("v" to date, "w" to date),
                "RETURN {k: \$v} = {k: \$s, n: 1}" to mapOf("v" to date, "s" to "2024-01-01"),
            ).forEach { (cypher, parameters) -> sql(cypher, parameters).let { assertFalse("THEN FALSE END" in it, "$store, $cypher: $it") } }
        }
    }
}
