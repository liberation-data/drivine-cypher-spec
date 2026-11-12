package org.drivine.cypher

import org.drivine.cypher.sql.OracleCypherCompiler
import org.drivine.cypher.sql.PostgresCypherCompiler
import org.drivine.cypher.sql.SqliteCypherCompiler
import org.junit.jupiter.api.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * drivine4j#424: a time zone an expression gives a temporal map, by its fields or beside another value, fails the statement
 * where it is no offset the store's text reads, rather than give null; one written or given by a parameter is read where the
 * statement compiles, as its offset's id, with no such test. What each gives on a store is the spec's, in `temporal.yaml`
 * ("Temporal values from another that an expression gives as none") and `refusals.yaml`: this is what each store is given.
 */
class TemporalZoneGuardTest {

    /** SqlCypherCompiler.ZONE_NOT_COMPILED, which its protected companion holds. */
    private val zoneNotCompiled = "A time zone that is not Z or an offset of hours and minutes (+hh:mm)"

    private val compilers = listOf(PostgresCypherCompiler("g"), OracleCypherCompiler("g"), SqliteCypherCompiler("g"))

    @Test
    fun `a zone an expression gives is tested on every store, by its fields or beside another value`() {
        compilers.forEach { compiler ->
            listOf(
                "UNWIND ['+01:00'] AS z RETURN datetime({year: 2024, month: 1, day: 1, timezone: z})",
                "UNWIND ['+01:00'] AS z RETURN time({hour: 12, timezone: z})",
                "UNWIND ['+01:00'] AS z RETURN datetime({datetime: datetime('2024-03-15T10:00Z'), timezone: z})",
                "UNWIND ['+01:00'] AS z RETURN time({time: localtime('10:00'), timezone: z})",
            ).forEach { cypher ->
                val sql = compiler.compile(cypher).sql
                assertTrue(zoneNotCompiled in sql, "${compiler::class.simpleName}: $cypher: $sql")
            }
        }
    }

    @Test
    fun `a zone written or a parameter gives is the offset's id, with no test`() {
        compilers.forEach { compiler ->
            listOf(
                "RETURN datetime({year: 2024, month: 1, day: 1, timezone: '+02:00:00'})" to emptyMap(),
                "RETURN datetime({year: 2024, month: 1, day: 1, timezone: \$z})" to mapOf("z" to "+02:00:00"),
                "RETURN datetime({datetime: datetime('2024-03-15T10:00Z'), timezone: \$z})" to mapOf("z" to "+02:00"),
                "RETURN datetime({datetime: datetime('2024-03-15T10:00Z'), timezone: '+02:00'})" to emptyMap(),
            ).forEach { (cypher, parameters) ->
                val sql = compiler.compile(cypher, parameters).sql
                assertFalse(zoneNotCompiled in sql, "${compiler::class.simpleName}: $cypher: $sql")
                assertTrue("'+02:00'" in sql, "${compiler::class.simpleName}: $cypher: $sql")
            }
        }
    }
}
