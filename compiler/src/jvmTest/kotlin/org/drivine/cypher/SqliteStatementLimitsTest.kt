package org.drivine.cypher

import org.drivine.cypher.sql.Parameters
import org.drivine.cypher.sql.SqliteCypherCompiler
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * What SQLite refuses of a statement, on a connection xerial's sqlite-jdbc opens: a function of more than 100 arguments
 * (`SQLITE_LIMIT_FUNCTION_ARG`), and a statement of more than 1,000,000 bytes (`SQLITE_LIMIT_SQL_LENGTH`). A CALL's rows
 * are gathered as a JSON map each, of a key and a value for each column, so one of more than 50 columns builds its map in
 * parts (drivine4j#375). A statement that writes is a procedure whose steps are written into the statement, as a text the
 * function that carries them out reads, so one whose steps make it too long is given them bound (drivine4j#427, the openCypher
 * TCK's Create4 [2]). What the stores give is the spec's, in `queries.yaml` ("A CALL that gives more than 50 columns") and
 * `writes.yaml` ("A write that compiles to a long statement").
 */
class SqliteStatementLimitsTest {

    private fun compiled(cypher: String) = SqliteCypherCompiler("g").compile(cypher)

    /**
     * The number of arguments of each call of a JSON function or one of Drivine's in [sql], read past its literals and quoted
     * names: the text a procedure's steps are written in is a literal, read by a statement of its own.
     */
    private fun argumentCounts(sql: String): List<Pair<String, Int>> {
        data class Call(val name: String?, var commas: Int = 0, var empty: Boolean = true)
        val calls = ArrayDeque<Call>()
        val counts = mutableListOf<Pair<String, Int>>()
        var i = 0
        while (i < sql.length) {
            val c = sql[i]
            when {
                c == '\'' || c == '"' -> {
                    var end = i + 1
                    while (end < sql.length) {
                        if (sql[end] != c) end++ else if (end + 1 < sql.length && sql[end + 1] == c) end += 2 else break
                    }
                    calls.lastOrNull()?.empty = false
                    i = end + 1
                    continue
                }
                c == '(' -> {
                    calls.lastOrNull()?.empty = false
                    val name = Regex("""([A-Za-z_][A-Za-z0-9_]*)$""").find(sql.substring(maxOf(0, i - 64), i))?.groupValues?.get(1)
                    calls.addLast(Call(name?.takeIf { it.startsWith("json") || it.startsWith("drivine_") }))
                }
                c == ')' -> {
                    val call = calls.removeLast()
                    if (call.name != null) counts += call.name to if (call.empty) 0 else call.commas + 1
                }
                c == ',' -> calls.lastOrNull()?.commas = (calls.lastOrNull()?.commas ?: 0) + 1
                !c.isWhitespace() -> calls.lastOrNull()?.empty = false
            }
            i++
        }
        return counts
    }

    private fun assertArgumentsWithinLimit(sql: String) {
        val over = argumentCounts(sql).filter { it.second > 100 }
        assertTrue(over.isEmpty(), "calls of more than 100 arguments: $over")
    }

    @Test
    fun `a CALL that gives 60 values gathers its rows in maps of no more than 100 arguments a call, each column read back`() {
        val values = (1..60).joinToString(", ") { "x + $it AS c$it" }
        val sql = compiled("UNWIND [1, 2] AS x CALL (x) { RETURN $values } RETURN x, c1, c30, c51, c60 ORDER BY x").sql
        assertArgumentsWithinLimit(sql)
        assertTrue(sql.contains("json_group_array(json_set(json_object('c1', "), sql)
        listOf("c1", "c30", "c51", "c60").forEach { assertTrue(sql.contains("value -> '$.$it'"), "$it: $sql") }
    }

    @Test
    fun `a CALL that gives no more than 50 columns gathers each row in one map, as before`() {
        val values = (1..50).joinToString(", ") { "x + $it AS c$it" }
        val sql = compiled("UNWIND [1, 2] AS x CALL (x) { RETURN $values } RETURN x, c1, c50 ORDER BY x").sql
        assertArgumentsWithinLimit(sql)
        assertTrue(sql.contains("json_group_array(json_object('c1', "), sql)
        assertFalse(sql.contains("json_group_array(json_set("), sql)
    }

    @Test
    fun `an uncorrelated CALL of 60 values and one of 13 nodes, four columns each, keep within the limit`() {
        val values = (1..60).joinToString(", ") { "$it AS c$it" }
        assertArgumentsWithinLimit(compiled("CALL { RETURN $values } RETURN c1, c60").sql)
        val nodes = (1..13).joinToString(", ") { "f AS f$it" }
        val sql = compiled("MATCH (p:Person {name: 'Carrie'}) CALL (p) { MATCH (p)-[:KNOWS]-(f) RETURN $nodes } RETURN f1.name, f13").sql
        assertArgumentsWithinLimit(sql)
        assertTrue(sql.contains("json_group_array(json_set(json_object("), sql)
    }

    /** As the openCypher TCK's Create4 [2], whose 731 nodes compiled to a statement of 1.5 MB. */
    private val manyCreates = "CREATE (s:School {name: 'Hilly Fields'})\n" +
        (1..730).joinToString("\n") { "CREATE (s)-[:STUDENT]->(:Student {name: 'Student $it'})" }

    @Test
    fun `a write whose steps would make a statement past 1,000,000 bytes is given its steps bound, in a short statement`() {
        val compiled = compiled(manyCreates)
        assertTrue(compiled.sql.length < 1_000, "${compiled.sql.length} characters")
        assertTrue(compiled.sql.contains("drivine_cypher(\$_cy_program, \$${Parameters.OBJECT})"), compiled.sql)
        val program = compiled.parameters["_cy_program"]
        assertTrue(program is String && program.length > 1_000_000 && program.startsWith("{\"steps\":["), "${(program as? String)?.length}")
        assertEquals(setOf(Parameters.OBJECT, "_cy_program"), compiled.parameters.keys)
    }

    @Test
    fun `a write given its steps bound is the statement that writes them in, with the parameter in their place`() {
        // 561 MERGEs, as the spec's case, compile to 1.2 MB with the steps written in.
        val bound = compiled("MERGE (:CyWrite) ".repeat(560) + "MERGE (n:CyWrite) RETURN count(n)")
        assertTrue((bound.parameters["_cy_program"] as String).contains("\"op\":\"loop\""))
        val written = compiled("MERGE (:CyWrite) ".repeat(20) + "MERGE (n:CyWrite) RETURN count(n)").sql
        val steps = Regex("""drivine_cypher\(('(?:[^']++|'')*+'), """).find(written)!!.groupValues[1]
        assertEquals(written.replace(steps, "\$_cy_program"), bound.sql)
    }

    /**
     * drivine4j runs the statement with `/* drivine:json */` and a line break before it, and `?` for `$_cy_params`: 9 bytes
     * longer than the compiler wrote it. A statement written within those few bytes of the limit is refused there.
     */
    @Test
    fun `a write whose statement would be within a host's few bytes of the limit is given its steps bound`() {
        fun cypher(length: Int) = "CREATE (s:School {name: 'Hilly Fields'})\n" +
            (1..300).joinToString("\n") { "CREATE (s)-[:STUDENT]->(:Student {name: 'Student $it'})" } + "\nCREATE (:Note {t: '${"x".repeat(length)}'})"
        fun writtenBytes(length: Int): Int {
            val compiled = compiled(cypher(length))
            val steps = compiled.parameters["_cy_program"] as String? ?: return compiled.sql.toByteArray().size
            return compiled.sql.replace("\$_cy_program", "'${steps.replace("'", "''")}'").toByteArray().size
        }
        // The longest text whose statement, written, is within the limit.
        var (low, high) = 0 to 1_000_000
        while (low < high) {
            val mid = (low + high + 1) / 2
            if (writtenBytes(mid) <= 1_000_000) low = mid else high = mid - 1
        }
        assertTrue(writtenBytes(low) > 1_000_000 - 9, "${writtenBytes(low)} bytes")
        assertTrue(compiled(cypher(low)).parameters.containsKey("_cy_program"))
    }

    /** SQLite's limit is of bytes: a text of 'é', two bytes a character, makes a statement past it in fewer characters. */
    @Test
    fun `a write whose statement is within the limit in characters but past it in bytes is given its steps bound`() {
        val compiled = compiled("CREATE (:Note {t: '${"é".repeat(600_000)}'})")
        val steps = compiled.parameters["_cy_program"] as String?
        assertTrue(steps != null, "steps written in: ${compiled.sql.length} characters, ${compiled.sql.toByteArray().size} bytes")
        val written = compiled.sql.replace("\$_cy_program", "'${steps.replace("'", "''")}'")
        assertTrue(written.length < 1_000_000 && written.toByteArray().size > 1_000_000, "${written.length} characters, ${written.toByteArray().size} bytes")
    }

    @Test
    fun `a write whose statement is within the limit has its steps written into it, as before`() {
        val compiled = compiled("CREATE (s:School {name: 'Hilly Fields'})\n" + (1..100).joinToString("\n") { "CREATE (s)-[:STUDENT]->(:Student {name: 'Student $it'})" })
        assertTrue(compiled.sql.contains("drivine_cypher('{\"steps\":["), compiled.sql.take(200))
        assertEquals(setOf(Parameters.OBJECT), compiled.parameters.keys)
    }
}
