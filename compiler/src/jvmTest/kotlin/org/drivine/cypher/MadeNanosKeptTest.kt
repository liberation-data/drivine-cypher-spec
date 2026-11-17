package org.drivine.cypher

import org.drivine.cypher.sql.OracleCypherCompiler
import org.drivine.cypher.sql.PostgresCypherCompiler
import org.drivine.cypher.sql.SqliteCypherCompiler
import org.drivine.cypher.sql.Temporals
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * drivine-cypher-spec#302: a value made of a `nanosecond` field keeps its nine digits, and is refused by name (drivine4j#421)
 * only where a store's timestamp is read of it. What the stores give is the spec's, in `temporal.yaml` ("A nanosecond past the
 * microsecond"): this is each path that reads such a value, run on SQLite, and the SQL each SQL store is given.
 */
class MadeNanosKeptTest {

    /** What [cypher] gives on SQLite in memory: its first row's value, or `refused` where it fails by name past the microsecond. */
    private fun sqlite(cypher: String, parameters: Map<String, Any?> = emptyMap()): String = SqliteHost.connection().use { c ->
        runCatching {
            val compiled = SqliteCypherCompiler("g").compile(cypher, parameters)
            val sql = compiled.parameters.entries.sortedByDescending { it.key.length }.fold(compiled.sql) { text, (name, value) ->
                text.replace("\$$name", if (value == null) "NULL" else if (value is Number) value.toString() else "'" + value.toString().replace("'", "''") + "'")
            }
            c.createStatement().use { s -> s.executeQuery(sql).use { r -> r.next(); r.getString(1) ?: "null" } }
        }.getOrElse { e -> if ("past the microsecond" in (e.message ?: "")) "refused" else "fails: ${e.message}" }
    }

    @Test
    fun `a value made over a base with a nanosecond field is refused where it is moved and kept where it is read`() {
        val made = "localtime({time: localtime('12:00'), nanosecond: \$n})"
        assertTrue("12:00:00.645876123" in sqlite("RETURN $made AS r", mapOf("n" to 645876123)))
        // It was marked as the compiler's own text of six digits, so moved untested: 12:00:01.645876, its nanoseconds cut.
        assertEquals("refused", sqlite("RETURN $made + duration('PT1S') AS r", mapOf("n" to 645876123)))
        assertEquals("refused", sqlite("RETURN localdatetime({year: 2024, week: 1, hour: 0, minute: 0, second: 0, nanosecond: 645876123}) + duration('PT1S') AS r"))
    }

    @Test
    fun `a moment made over one with an offset is refused where a nanosecond field moves it to another time zone`() {
        // Moved through the store's timestamp in UTC; over a local value it takes the time zone as it is, and keeps its digits.
        assertEquals("refused", sqlite("RETURN datetime({datetime: datetime('2024-01-01T00:00Z'), nanosecond: 645876123, timezone: '+05:00'}) AS r"))
        assertTrue("00:00:00.645876123+05:00" in sqlite("RETURN datetime({datetime: localdatetime('2024-01-01T00:00'), nanosecond: 645876123, timezone: '+05:00'}) AS r"))
    }

    @Test
    fun `a nanosecond field out of its range fails and writes no text`() {
        // It wrote 01:00:00.000000-001, which is no temporal value's text.
        assertEquals("refused", sqlite("RETURN localtime({hour: 1, minute: 0, second: 0, nanosecond: \$n}) AS r", mapOf("n" to -1)))
        assertEquals("refused", sqlite("RETURN localtime({hour: 1, minute: 0, second: 0, nanosecond: 1000000001}) AS r"))
        // Beside a millisecond its fraction went past the second: it wrote 01:00:00.1004999999.
        assertEquals("refused", sqlite("RETURN localtime({hour: 1, minute: 0, second: 0, millisecond: 5, nanosecond: 999999999}) AS r"))
    }

    @Test
    fun `Postgres and Oracle write the nanoseconds into a made text and cut a stored one to the microsecond where it is truncated or converted`() {
        for (compiler in listOf(PostgresCypherCompiler("g"), OracleCypherCompiler("g"))) {
            val name = compiler::class.simpleName
            val made = compiler.compile("RETURN localtime({hour: 12, minute: 0, second: 0, nanosecond: \$n}) AS r", mapOf("n" to 645876123)).sql
            assertTrue("('\\1.' || " in made && "'FM000')" in made, "$name: $made")
            assertTrue(Temporals.SUB_MICRO.replace("'", "''") in made, "$name, the range: $made")
            val truncated = compiler.compile("MATCH (n) RETURN localtime.truncate('second', n.t) AS r", emptyMap()).sql
            assertTrue("'([.][0-9]{6})[0-9]+', '\\1')" in truncated, "$name: $truncated")
            val converted = compiler.compile("MATCH (n) RETURN localtime(n.at) AS r", emptyMap()).sql
            assertTrue("'([.][0-9]{6})[0-9]+', '\\1')" in converted && "('\\1' || " in converted, "$name: $converted")
        }
    }
}
