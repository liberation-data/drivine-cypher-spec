package org.drivine.cypher

import org.drivine.cypher.sql.OracleCypherCompiler
import org.drivine.cypher.sql.PostgresCypherCompiler
import org.drivine.cypher.sql.SqlCypherCompiler
import org.drivine.cypher.sql.SqliteCypherCompiler
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals

/**
 * Neo4j names a function in any case, `DATE.STATEMENT()` too: it compiles as `date.statement()` does, with a time zone,
 * null or nothing (drivine4j#425). The spec's `temporal.yaml` ("The current moment in a time zone, or of null") holds
 * what the stores give; this pins only that the case is no matter.
 */
class TemporalNowCaseTest {

    @Test
    fun `a now function written in another case compiles as written in lower case`() {
        // A compiler of its own for each statement, as each numbers its aliases on from the last; SQLite's writes the clock in.
        val clock = Regex("'[0-9]{4}-[0-9]{2}-[0-9]{2}T[0-9:.]+'")
        fun sql(compiler: () -> SqlCypherCompiler, cypher: String) = compiler().compile(cypher, emptyMap()).sql.replace(clock, "'now'")
        listOf<() -> SqlCypherCompiler>({ PostgresCypherCompiler("g") }, { OracleCypherCompiler("g") }, { SqliteCypherCompiler("g") }).forEach { compiler ->
            listOf("()", "('+01:00')", "(null)").forEach { arguments ->
                listOf("DATE.STATEMENT", "LocalTime.Realtime", "DateTime.Transaction").forEach { name ->
                    assertEquals(
                        sql(compiler, "RETURN ${name.lowercase()}$arguments"),
                        sql(compiler, "RETURN $name$arguments"),
                        "${compiler()::class.simpleName}: $name$arguments",
                    )
                }
            }
        }
    }
}
