package org.drivine.cypher

import org.drivine.cypher.json.TemporalValue
import org.drivine.cypher.sql.OracleCypherCompiler
import org.drivine.cypher.sql.PostgresCypherCompiler
import org.drivine.cypher.sql.SqliteCypherCompiler
import org.drivine.cypher.sql.Temporal
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * drivine-cypher-spec#440: `duration.between` of two values whose kinds the statement doesn't know, as two read from the store,
 * reads each as the kind its own tag names, beside the other's: both were read as the first's, so a date and a time of day
 * failed, gave null, or a wrong duration. What the stores give is the spec's, in `temporal.yaml` ("The duration between two
 * values stored, of different kinds"): this runs every pair of kinds on SQLite, each side read off a list so its kind is open,
 * against the same pair given as parameters, whose kinds the statement knows, and holds the statement within a ceiling.
 */
class DurationBetweenStoredTest {

    /** What [cypher] gives on SQLite in memory: its first row's value, or `fails: <message>`. */
    private fun sqlite(cypher: String, parameters: Map<String, Any?>): String = SqliteHost.connection().use { c ->
        runCatching {
            val compiled = SqliteCypherCompiler("g").compile(cypher, parameters)
            val sql = compiled.parameters.entries.sortedByDescending { it.key.length }.fold(compiled.sql) { text, (name, value) ->
                text.replace("\$$name", if (value == null) "NULL" else if (value is Number) value.toString() else "'" + value.toString().replace("'", "''") + "'")
            }
            c.createStatement().use { s -> s.executeQuery(sql).use { r -> r.next(); r.getString(1) ?: "null" } }
        }.getOrElse { e -> "fails: ${e.message}" }
    }

    private val values = listOf(
        TemporalValue(Temporal.DATE, "2024-01-01"), TemporalValue(Temporal.LOCAL_TIME, "10:00:00"), TemporalValue(Temporal.TIME, "10:00:00+01:00"),
        TemporalValue(Temporal.LOCAL_DATE_TIME, "2024-03-05T23:30:00.5"), TemporalValue(Temporal.DATE_TIME, "2024-01-02T10:00:00-05:00"),
    )

    @Test
    fun `each pair of kinds read off a list gives what the pair gives where the statement knows both kinds`() {
        val differ = mutableListOf<String>()
        listOf("between", "inMonths", "inDays", "inSeconds").forEach { function ->
            values.forEach { a -> values.forEach { b ->
                val given = mapOf("a" to a, "b" to b)
                val known = sqlite("RETURN toString(duration.$function(\$a, \$b)) AS r", given)
                val open = sqlite("UNWIND [\$a, \$b] AS x WITH collect(x) AS l RETURN toString(duration.$function(l[0], l[1])) AS r", given)
                assertTrue("fails" !in known, "$function ${a.kind}, ${b.kind}: $known")
                if (open != known) differ += "$function(${a.kind} ${a.text}, ${b.kind} ${b.text}): open $open, known $known"
            } }
        }
        assertTrue(differ.isEmpty(), differ.joinToString("\n"))
    }

    @Test
    fun `a null side gives null`() {
        val date = TemporalValue(Temporal.DATE, "2024-01-01")
        // l[1] is past the list's end: null of an open kind.
        listOf("l[0], l[1]", "l[1], l[0]").forEach { sides ->
            assertEquals("null", sqlite("UNWIND [\$a] AS x WITH collect(x) AS l RETURN duration.between($sides) AS r", mapOf("a" to date)), sides)
        }
    }

    /** Each side is read in a `CASE` of its own kind and the other's, and the duration worked out once: 48,000 characters on Postgres, 34,000 on SQLite. */
    @Test
    fun `two values of open kinds compile to one duration's SQL, not one for each pair of kinds`() {
        listOf(PostgresCypherCompiler("g"), OracleCypherCompiler("g"), SqliteCypherCompiler("g")).forEach { compiler ->
            val one = compiler.compile("MATCH (n:T) RETURN duration.between(n.a, n.b) AS r", emptyMap()).sql.length
            val two = compiler.compile("MATCH (n:T) RETURN [duration.between(n.a, n.b), duration.between(n.b, n.a)] AS r", emptyMap()).sql.length
            assertTrue(one < 60_000, "${compiler::class.simpleName}: $one characters")
            assertTrue(two < 3 * one, "${compiler::class.simpleName}: $one, then $two characters")
        }
    }
}
