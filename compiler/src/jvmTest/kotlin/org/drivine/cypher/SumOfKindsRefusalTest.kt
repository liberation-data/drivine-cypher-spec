package org.drivine.cypher

import org.drivine.cypher.sql.OracleCypherCompiler
import org.drivine.cypher.sql.PostgresCypherCompiler
import org.drivine.cypher.sql.SqliteCypherCompiler
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * drivine-cypher-spec#281: a `sum` or an `avg` of anything but integers, floats and nulls fails by name on the SQL stores, where a
 * store's sum adds the numbers alone and gave 0, and the average null: a duration as not yet worked out (#282), any other kind as
 * Neo4j fails it. What the stores give is the spec's, in `temporal.yaml` ("A sum or an average of durations, or of other kinds"):
 * this is what each is given.
 */
class SumOfKindsRefusalTest {

    private val compilers = listOf(PostgresCypherCompiler("g"), OracleCypherCompiler("g"), SqliteCypherCompiler("g"))

    private val duration = "A sum or an average of durations isn't worked out yet"

    private val other = "sum() and avg() take integers, floats and durations alone, as in Neo4j"

    /** [message] as a SQL text literal writes it, each `'` twice. */
    private fun quoted(message: String) = message.replace("'", "''")

    @Test
    fun `a kind the statement knows is refused as it compiles`() {
        val lists = listOf(
            "[duration('PT0.1S'), duration('PT0.2S')]" to duration, "['a']" to other, "[true]" to other, "[[1]]" to other,
            "[{a: 1}]" to other, "[date('2024-01-01')]" to other, "[localdatetime('2024-01-01T00:00')]" to other,
        )
        for (compiler in compilers) for ((list, message) in lists) for (call in listOf("sum(x)", "avg(x)", "sum(DISTINCT x)")) {
            val refused = assertThrows<CompilerException>("${compiler::class.simpleName} $list $call") {
                compiler.compile("UNWIND $list AS x RETURN $call", emptyMap())
            }
            assertTrue(message in refused.message!!, "${compiler::class.simpleName} $list: ${refused.message}")
        }
        for (compiler in compilers) for (form in listOf("n.a > 1", "n")) {
            val refused = assertThrows<CompilerException>("${compiler::class.simpleName} $form") { compiler.compile("MATCH (n) RETURN sum($form)", emptyMap()) }
            assertTrue(other in refused.message!!, "${compiler::class.simpleName} $form: ${refused.message}")
        }
    }

    @Test
    fun `a value of open kind is tested as the statement runs and a known number is not`() {
        val forms = listOf(
            "n.d" to true, "DISTINCT n.d" to true, "n.a * 2" to true, "coalesce(n.d, 1)" to true, "head(n.l)" to true,
            "CASE WHEN n.a THEN n.d ELSE 1 END" to true, "datetime(n.at).timezone" to true,
            "labels(n)" to true, "toString(n.a)" to true, "toInteger(n.a)" to false, "size(n.l)" to false, "\$x" to false, "count(*)" to false,
        )
        for (compiler in compilers) for (aggregate in listOf("sum", "avg")) for ((form, tested) in forms) {
            if (form == "count(*)") continue
            val sql = compiler.compile("MATCH (n) RETURN $aggregate($form)", mapOf("x" to 1)).sql
            val written = quoted(duration) in sql && quoted(other) in sql
            assertTrue(written == tested, "${compiler::class.simpleName} $aggregate($form): ${if (tested) "tested" else "not tested"} expected:\n$sql")
        }
    }

    @Test
    fun `on SQLite a group given as a parameter fails by name where an item is no number and is summed where none is`() {
        SqliteHost.connection().use { c ->
            fun run(aggregate: String, items: List<Any?>, list: String = "\$l"): String = runCatching {
                val compiled = SqliteCypherCompiler("g").compile("UNWIND $list AS x RETURN $aggregate(x) AS r", mapOf("l" to items))
                // Each parameter written in as a literal, the longest name first, as TemporalArithmeticHostTest's inline SQL is.
                val sql = compiled.parameters.entries.sortedByDescending { it.key.length }.fold(compiled.sql) { text, (name, value) ->
                    text.replace("\$$name", when (value) {
                        null -> "NULL"
                        is Number -> value.toString()
                        else -> "'" + (if (value is String) value else org.drivine.cypher.json.CypherJson.writeValueAsString(value)).replace("'", "''") + "'"
                    })
                }
                c.createStatement().use { s -> s.executeQuery(sql).use { r -> r.next(); r.getString(1) ?: "null" } }
            }.getOrElse { e -> if (other in (e.message ?: "")) "other" else if (duration in (e.message ?: "")) "duration" else "fails: ${e.message}" }
            assertEquals("other", run("sum", listOf(1, "a")))
            assertEquals("other", run("avg", listOf(true)))
            assertEquals("other", run("sum", listOf(listOf(1))))
            assertEquals("other", run("sum", listOf(mapOf("a" to 1))))
            // A map given in a tag's shape is a map, as Neo4j has it; a duration the statement makes, among items of open kind, is one.
            assertEquals("other", run("sum", listOf(mapOf("\$t" to "duration", "v" to "PT1S"))))
            assertEquals("duration", run("sum", listOf(1), "\$l + [duration('PT1S')]"))
            assertEquals("duration", run("avg", listOf(null), "\$l + [duration('PT1S')]"))
            assertEquals("3.5", run("sum", listOf(1, null, 2.5)))
            assertEquals("1.75", run("avg", listOf(1, null, 2.5)))
            assertEquals("0", run("sum", listOf(null)))
            assertEquals("1", run("sum", listOf(1), "[x IN \$l | CASE WHEN x = 1 THEN 1 ELSE 'a' END]"))
            assertEquals("null", run("avg", emptyList<Any?>()))
        }
    }

    @Test
    fun `an item of a list with none is no text nor list to refuse`() {
        // Neo4j sums one as 0 and averages it as null: the statement compiles, and is tested as it runs.
        for (compiler in compilers) compiler.compile("UNWIND [] AS x RETURN [sum(x), avg(x), toString(sum(x))]", emptyMap())
    }

    @Test
    fun `a value that may be a number among other kinds is tested as it runs`() {
        // Neo4j plans each, and sums the numbers a row gives: none is refused as the statement compiles.
        val statements = listOf(
            "UNWIND [1] AS c RETURN sum(CASE WHEN c = 1 THEN 1 ELSE 'a' END)", "UNWIND [1] AS c RETURN sum(coalesce(null, 1, 'a'))",
            "UNWIND [1, 'a'] AS x RETURN sum(x)", "UNWIND [[1, 'a']] AS l RETURN sum(l[0])", "WITH {k: 1} AS m RETURN avg(m.k)",
            "UNWIND [null] AS x RETURN sum(x)", "UNWIND [[]] AS l UNWIND l AS x RETURN sum(x)",
            "UNWIND [1] AS c RETURN sum(CASE WHEN c = 2 THEN date('2024-01-01') ELSE 1 END)",
        )
        for (compiler in compilers) for (statement in statements) {
            val sql = compiler.compile(statement, emptyMap()).sql
            assertTrue(quoted(other) in sql, "${compiler::class.simpleName} $statement:\n$sql")
        }
    }

    @Test
    fun `numbers the statement knows are summed with no test`() {
        for (compiler in compilers) {
            val sql = compiler.compile("UNWIND [1, 2] AS x RETURN [sum(x), avg(x)]", emptyMap()).sql
            assertTrue(quoted(duration) !in sql && quoted(other) !in sql, "${compiler::class.simpleName}:\n$sql")
        }
    }
}
