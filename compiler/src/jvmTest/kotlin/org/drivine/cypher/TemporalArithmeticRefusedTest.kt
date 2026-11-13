package org.drivine.cypher

import org.drivine.cypher.sql.OracleCypherCompiler
import org.drivine.cypher.sql.PostgresCypherCompiler
import org.drivine.cypher.sql.SqliteCypherCompiler
import org.junit.jupiter.api.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * drivine4j#418: `+`, `-`, `*` and `/` of a temporal value whose kind the statement doesn't know, a tag read from the store,
 * fails by name as the statement runs, where the generic arithmetic gave null. What each store gives is the spec's, in
 * `temporal.yaml` ("Arithmetic on temporal values read from the store"): this is what each is given.
 */
class TemporalArithmeticRefusedTest {

    private val compilers = listOf(PostgresCypherCompiler("g"), OracleCypherCompiler("g"), SqliteCypherCompiler("g"))

    private val refusal = "compiled to SQL yet (drivine4j#418)"

    @Test
    fun `each side of unknown kind is tested for a temporal tag, where the arithmetic would give null`() {
        listOf("n.d1 + n.d2", "n.d1 - n.d2", "n.at + n.d", "n.at - n.d", "n.d * 2", "n.d / 2", "2 * n.d").forEach { expression ->
            compilers.forEach { compiler ->
                val sql = compiler.compile("MATCH (n:T) RETURN $expression", emptyMap()).sql
                assertTrue(refusal in sql, "${compiler::class.simpleName} $expression:\n$sql")
            }
        }
    }

    @Test
    fun `a side known to be temporal is worked out, and arithmetic on known numbers or texts is not tested`() {
        listOf(
            "MATCH (n:T) RETURN n.d + duration('PT1H')", "MATCH (n:T) RETURN duration('PT1H') + n.d", "RETURN 1 + 2", "RETURN 'a' + 'b'",
            "RETURN \$a * \$b", "RETURN [1] + [2]",
            // A function that gives a number.
            "MATCH (n:T) RETURN size(n.l) * 2", "MATCH (n:T) RETURN count(*) + 1", "MATCH (n:T) RETURN toInteger(n.a) - 1", "MATCH (n:T) RETURN 2 / abs(n.a)",
        ).forEach { cypher ->
            compilers.forEach { compiler ->
                val sql = compiler.compile(cypher, mapOf("a" to 2, "b" to 3)).sql
                assertFalse(refusal in sql, "${compiler::class.simpleName} $cypher:\n$sql")
            }
        }
    }

    @Test
    fun `a plus tests a tag beside a tag or a number only, as a temporal value beside a text is its text and beside a list an item`() {
        val sql = PostgresCypherCompiler("g").compile("MATCH (n:T) RETURN n.a + n.b", emptyMap()).sql
        assertTrue("jsonb_typeof" in sql && "= 'number'" in sql && refusal in sql, sql)
        // A side known to be a number: only the other is tested.
        val one = PostgresCypherCompiler("g").compile("MATCH (n:T) RETURN n.a + 1", emptyMap()).sql
        assertTrue(refusal in one, one)
    }

    @Test
    fun `a plus beside a text or a list, written or given, is not tested, as no tag beside it is refused`() {
        listOf("n.name + 'x'", "'x' + n.name", "n.a + [1]", "[1] + n.a", "n.a + \$s", "n.a + \$l").forEach { expression ->
            compilers.forEach { compiler ->
                val sql = compiler.compile("MATCH (n:T) RETURN $expression", mapOf("s" to "x", "l" to listOf(1))).sql
                assertFalse(refusal in sql, "${compiler::class.simpleName} $expression:\n$sql")
            }
        }
    }

    @Test
    fun `the refusal names the tag's kind on either side, where the other side may be a number`() {
        compilers.forEach { compiler ->
            val sql = compiler.compile("MATCH (n:T) RETURN n.a * n.d", emptyMap()).sql
            assertTrue(refusal in sql && "COALESCE(" in sql, "${compiler::class.simpleName}:\n$sql")
        }
    }
}
