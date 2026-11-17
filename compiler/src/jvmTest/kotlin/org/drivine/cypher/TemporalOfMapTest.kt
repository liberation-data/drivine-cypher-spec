package org.drivine.cypher

import org.drivine.cypher.json.TemporalValue
import org.drivine.cypher.sql.OracleCypherCompiler
import org.drivine.cypher.sql.PostgresCypherCompiler
import org.drivine.cypher.sql.SqliteCypherCompiler
import org.drivine.cypher.sql.Temporal
import org.junit.jupiter.api.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

/**
 * drivine-cypher-spec#328: a temporal value made from a map the compiler doesn't see written, which Neo4j makes as the
 * statement runs, gave null. A map a parameter gives or an expression is known to make is refused (refusals.yaml); one only
 * read as the statement runs, as an item of a list, fails it by name. This runs the latter on SQLite, and checks the SQL each
 * SQL store is given. drivine-cypher-spec#331: so is a list, a number or a truth value, which Neo4j fails, refused or
 * failed by name as the statement compiles or runs, where it gave null too.
 */
class TemporalOfMapTest {

    /** What [cypher] gives on SQLite in memory: its first row's value, or `fails: <message>`. */
    private fun sqlite(cypher: String, parameters: Map<String, Any?> = emptyMap()): String = SqliteHost.connection().use { c ->
        runCatching {
            val compiled = SqliteCypherCompiler("g").compile(cypher, parameters)
            // SQLite numbers each name it binds by where it first comes: these statements bind one at most.
            c.prepareStatement(compiled.sql).use { s ->
                compiled.parameters.values.forEachIndexed { i, v -> s.setObject(i + 1, v) }
                s.executeQuery().use { r -> r.next(); r.getString(1) ?: "null" }
            }
        }.getOrElse { e -> "fails: ${e.message}" }
    }

    @Test
    fun `a map read as the statement runs fails it by name and gives no null`() {
        val failed = sqlite("WITH collect({hour: 1}) AS l RETURN localtime(l[0]) AS r")
        assertContains(failed, "A temporal value of a map read as the statement runs is Cypher, and is not compiled to SQL yet: localtime()")
        assertContains(sqlite("WITH collect({days: 1}) AS l RETURN duration(l[0]) AS r"), "duration()")
    }

    @Test
    fun `a text or a temporal value read as the statement runs is made as before`() {
        assertContains(sqlite("WITH collect('12:30') AS l RETURN localtime(l[0]) AS r"), "12:30")
        assertContains(sqlite("WITH collect(localtime('12:30')) AS l RETURN localtime(l[0]) AS r"), "12:30")
        assertContains(sqlite("WITH collect(duration('P1D')) AS l RETURN duration(l[0]) AS r"), "P1D")
        assertEquals("null", sqlite("WITH collect(null) + [null] AS l RETURN localtime(l[0]) AS r"))
        assertEquals("null", sqlite("UNWIND \$l AS m RETURN date(m) AS r", mapOf("l" to listOf(null))))
        assertEquals("null", sqlite("RETURN date(toFloat(\$x)) AS r", mapOf("x" to null)))
        assertEquals("null", sqlite("RETURN date(CASE WHEN 1 = 0 THEN 5 END) AS r"))
    }

    @Test
    fun `a map a list parameter gives as the statement runs fails it by name`() {
        val failed = sqlite("UNWIND \$l AS m RETURN localtime(m) AS r", mapOf("l" to listOf(mapOf("hour" to 1L))))
        assertContains(failed, "A temporal value of a map read as the statement runs is Cypher, and is not compiled to SQL yet: localtime()")
    }

    @Test
    fun `a temporal value or a text a parameter or its key gives is no map and is made as before`() {
        val date = TemporalValue(Temporal.DATE, "2024-03-15")
        assertContains(sqlite("RETURN date(\$d) AS r", mapOf("d" to date)), "2024-03-15")
        assertContains(sqlite("RETURN date(\$p.d) AS r", mapOf("p" to mapOf("d" to date))), "2024-03-15")
        assertContains(sqlite("RETURN date(\$d) AS r", mapOf("d" to java.time.LocalDate.of(2024, 3, 15))), "2024-03-15")
        assertContains(sqlite("RETURN date(\$s) AS r", mapOf("s" to "2024-03-15")), "2024-03-15")
        assertEquals("null", sqlite("RETURN date(\$d) AS r", mapOf("d" to null)))
    }

    @Test
    fun `Postgres and Oracle fail by name where the item is a map`() {
        listOf(PostgresCypherCompiler("g"), OracleCypherCompiler("g")).forEach { compiler ->
            assertContains(compiler.compile("WITH collect({hour: 1}) AS l RETURN localtime(l[0]) AS r").sql, "A temporal value of a map read as the statement runs")
        }
    }

    @Test
    fun `a list a number or a truth value read as the statement runs fails it by name and gives no null`() {
        val prefix = "A temporal value is made of a text or a map of its fields, as in Neo4j:"
        assertContains(sqlite("WITH collect([1]) AS l RETURN localtime(l[0]) AS r"), "$prefix localtime() of a list")
        assertContains(sqlite("WITH [[1]] AS ls RETURN duration(ls[0]) AS r"), "$prefix duration() of a list")
        assertContains(sqlite("UNWIND [[1], 2, true] AS l RETURN localtime(l) AS r"), "$prefix localtime() of a list")
        assertContains(sqlite("RETURN date(toFloat(2.5)) AS r"), "$prefix date() of a number")
        assertContains(sqlite("RETURN date(CASE WHEN 1 = 1 THEN true END) AS r"), "$prefix date() of a truth value")
        assertContains(sqlite("WITH collect(5) AS l RETURN date(l[0]) AS r"), "$prefix date() of a number")
        assertContains(sqlite("WITH collect(2.5) AS l RETURN datetime(l[0]) AS r"), "$prefix datetime() of a number")
        assertContains(sqlite("WITH collect(true) AS l RETURN date(l[0]) AS r"), "$prefix date() of a truth value")
        assertContains(sqlite("WITH collect(false) AS l RETURN localdatetime(l[0]) AS r"), "$prefix localdatetime() of a truth value")
        assertContains(sqlite("WITH collect(1) AS l RETURN time(l[0]) AS r"), "$prefix time() of a number")
        assertContains(sqlite("UNWIND \$l AS m RETURN date(m) AS r", mapOf("l" to listOf(listOf(2024L)))), "$prefix date() of a list")
        assertContains(sqlite("UNWIND \$l AS m RETURN date(m) AS r", mapOf("l" to listOf(5L))), "$prefix date() of a number")
    }

    @Test
    fun `a list a number or a truth value the compiler knows is refused by name as Neo4j refuses it`() {
        listOf(PostgresCypherCompiler("g"), OracleCypherCompiler("g"), SqliteCypherCompiler("g")).forEach { compiler ->
            fun refused(cypher: String, parameters: Map<String, Any?> = emptyMap()) =
                assertFailsWith<CompilerException>(cypher) { compiler.compile(cypher, parameters) }.message
            assertEquals("localtime() takes a text or a map of its fields, not a list, as in Neo4j.", refused("RETURN localtime([1])"))
            assertEquals("datetime() takes a text or a map of its fields, not a list, as in Neo4j.", refused("RETURN datetime([])"))
            assertEquals("date() takes a text or a map of its fields, not an integer, as in Neo4j.", refused("RETURN date(5)"))
            assertEquals("date() takes a text or a map of its fields, not a float, as in Neo4j.", refused("RETURN date(-2.5)"))
            assertEquals("date() takes a text or a map of its fields, not a truth value, as in Neo4j.", refused("RETURN date(true)"))
            assertEquals("date() takes a text or a map of its fields, not a list, as in Neo4j.", refused("RETURN date(\$l)", mapOf("l" to listOf(2024L))))
            assertEquals("duration() takes a text or a map of its fields, not an integer, as in Neo4j.", refused("RETURN duration(\$p.d)", mapOf("p" to mapOf("d" to 1L))))
            assertEquals("time() takes a text or a map of its fields, not a truth value, as in Neo4j.", refused("RETURN time(\$b)", mapOf("b" to false)))
            assertEquals("localtime() takes a text or a map of its fields, not a list, as in Neo4j.", refused("UNWIND [[1], [2]] AS l RETURN localtime(l)"))
            assertEquals("date() takes a text or a map of its fields, not a truth value, as in Neo4j.", refused("MATCH (n) RETURN date(n.x > 1)"))
        }
    }

    @Test
    fun `Postgres and Oracle fail by name where the item is a list a number or a truth value`() {
        listOf(PostgresCypherCompiler("g"), OracleCypherCompiler("g")).forEach { compiler ->
            val sql = compiler.compile("WITH collect([1]) AS l RETURN localtime(l[0]) AS r").sql
            listOf("localtime() of a list", "localtime() of a number", "localtime() of a truth value").forEach { assertContains(sql, it) }
        }
    }
}
