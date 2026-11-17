package org.drivine.cypher

import org.drivine.cypher.sql.OracleCypherCompiler
import org.drivine.cypher.sql.PostgresCypherCompiler
import org.drivine.cypher.sql.SqliteCypherCompiler
import org.junit.jupiter.api.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals

/**
 * drivine-cypher-spec#328: a temporal value made from a map the compiler doesn't see written, which Neo4j makes as the
 * statement runs, gave null. A map a parameter gives or an expression is known to make is refused (refusals.yaml); one only
 * read as the statement runs, as an item of a list, fails it by name. This runs the latter on SQLite, and checks the SQL each
 * SQL store is given.
 */
class TemporalOfMapTest {

    /** What [cypher] gives on SQLite in memory: its first row's value, or `fails: <message>`. */
    private fun sqlite(cypher: String): String = SqliteHost.connection().use { c ->
        runCatching {
            val compiled = SqliteCypherCompiler("g").compile(cypher, emptyMap())
            c.createStatement().use { s -> s.executeQuery(compiled.sql).use { r -> r.next(); r.getString(1) ?: "null" } }
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
    }

    @Test
    fun `Postgres and Oracle fail by name where the item is a map`() {
        listOf(PostgresCypherCompiler("g"), OracleCypherCompiler("g")).forEach { compiler ->
            assertContains(compiler.compile("WITH collect({hour: 1}) AS l RETURN localtime(l[0]) AS r").sql, "A temporal value of a map read as the statement runs")
        }
    }
}
