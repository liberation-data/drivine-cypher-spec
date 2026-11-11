package org.drivine.cypher

import org.drivine.cypher.json.TemporalValue
import org.drivine.cypher.sql.OracleCypherCompiler
import org.drivine.cypher.sql.PostgresCypherCompiler
import org.drivine.cypher.sql.SqliteCypherCompiler
import org.drivine.cypher.sql.Temporal
import org.junit.jupiter.api.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * A temporal value the statement makes is equal to no text, though its ISO text is (drivine4j#379): the comparison is
 * false, or an `IN` reads the value as a map of its kind (`{"date": …}`). A parameter of a temporal kind is no text, and
 * is compared as the moment it is. What the stores give is the spec's, in `temporal.yaml` ("A temporal value beside a
 * text that spells it"); its parameters can't be of a temporal kind, so that is pinned here.
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
}
