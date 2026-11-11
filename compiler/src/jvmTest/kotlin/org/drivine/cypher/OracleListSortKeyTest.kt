package org.drivine.cypher

import org.drivine.cypher.sql.OracleCypherCompiler
import org.drivine.cypher.sql.PostgresCypherCompiler
import org.drivine.cypher.sql.SqliteCypherCompiler
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * On Oracle, a sort by a list is by a key the query's own function, `cy_list_key`, gives, then by the list's text, or as `JSON`
 * sorts it where its kind is left open (drivine4j#205): the function declared once with `WITH FUNCTION`, before the statement, and not in a statement that writes, whose block
 * calls no function it declares. Postgres and SQLite sort as before.
 */
class OracleListSortKeyTest {

    private fun oracle(cypher: String) = OracleCypherCompiler("g").compile(cypher, emptyMap()).sql

    private fun declarations(sql: String) = Regex(Regex.escape("FUNCTION cy_list_key(")).findAll(sql).count()

    @Test
    fun `a list is sorted by its key and then by its text, the key's function declared once before the statement`() {
        val sql = oracle("UNWIND [[1], [2]] AS xs UNWIND [[3]] AS ys RETURN xs, ys ORDER BY xs, ys DESC")
        assertTrue(sql.startsWith("WITH\nFUNCTION cy_list_key(cy_x JSON) RETURN RAW IS\n"), sql)
        assertEquals(1, declarations(sql), sql)
        // Then, where the key is cut short, the list's text: JSON sorts no value past 32 KB.
        assertTrue(Regex("""ORDER BY cy_list_key\((\S+)\), JSON_SERIALIZE\(\1 RETURNING VARCHAR2\(4000\) TRUNCATE\), cy_list_key\((\S+)\) DESC, JSON_SERIALIZE\(\2 RETURNING VARCHAR2\(4000\) TRUNCATE\) DESC$""").containsMatchIn(sql), sql)
    }

    @Test
    fun `a list kept distinct or grouped by is sorted by its key and then by its text`() {
        for (cypher in listOf(
            "UNWIND [[1], [2], [1]] AS xs RETURN DISTINCT xs ORDER BY xs",
            "UNWIND [[1], [2], [1]] AS xs RETURN xs, count(*) AS n ORDER BY xs",
        )) {
            val sql = oracle(cypher)
            assertEquals(1, declarations(sql), sql)
            assertTrue(Regex("""ORDER BY cy_list_key\((\S+)\), JSON_SERIALIZE\(\1 RETURNING VARCHAR2\(4000\) TRUNCATE\)$""").containsMatchIn(sql), sql)
        }
    }

    @Test
    fun `a value of a kind left open, as a property, sorts as before, by its kind and then as JSON, with no function declared`() {
        // A list such a value holds still sorts as JSON sorts it on Oracle: the key isn't read for every sort of a value left open.
        val sql = oracle("MATCH (n:A) RETURN n.l AS l ORDER BY l")
        assertEquals(0, declarations(sql), sql)
        assertTrue(Regex("""ORDER BY DECODE\(JSON_VALUE\((\S+), '\$\.type\(\)'\), [^)]*\), \1$""").containsMatchIn(sql), sql)
        val nullable = OracleCypherCompiler("g").compile("UNWIND \$ms AS m RETURN m.l AS l ORDER BY l", mapOf("ms" to listOf(mapOf("l" to listOf(1)), mapOf("l" to null)))).sql
        assertEquals(0, declarations(nullable), nullable)
        assertTrue(Regex("""ORDER BY DECODE\(JSON_VALUE\((\S+), '\$\.type\(\)'\), [^)]*\), NVL\(\1, JSON\('null'\)\)$""").containsMatchIn(nullable), nullable)
    }

    @Test
    fun `the key's function is declared once before a statement whose subquery and UNION sort by lists`() {
        val sql = oracle(
            "CALL { UNWIND [[1], [2]] AS xs RETURN xs ORDER BY xs LIMIT 1 } RETURN xs UNION CALL { UNWIND [[3], [4]] AS xs RETURN xs ORDER BY xs DESC LIMIT 1 } RETURN xs"
        )
        assertTrue(sql.startsWith("WITH\nFUNCTION cy_list_key("), sql)
        assertEquals(1, declarations(sql), sql)
        assertEquals(4, Regex("""cy_list_key\((?!cy_x)""").findAll(sql).count(), sql)
    }

    @Test
    fun `a statement that writes declares no function and sorts a list as JSON sorts it`() {
        for (cypher in listOf(
            "CREATE (n:A) WITH n UNWIND [[1], [2]] AS xs RETURN xs ORDER BY xs",
            "CREATE (n:A) WITH n MATCH (m:A) RETURN m.l AS l ORDER BY l",
        )) {
            val sql = oracle(cypher)
            assertTrue(!sql.contains("cy_list_key") && !sql.contains("WITH\nFUNCTION"), sql)
        }
    }

    @Test
    fun `the key reads a double, NaN and the infinities as extended JSON writes them, and a text too long for a VARCHAR2`() {
        val sql = oracle("UNWIND [[1], [2]] AS xs RETURN xs ORDER BY xs")
        assertTrue(sql.contains("JSON_ELEMENT_T.parse(JSON_SERIALIZE(cy_x RETURNING CLOB EXTENDED))"), sql)
        assertTrue(sql.contains("o.has('\$numberDouble')") && sql.contains("WHEN f = -BINARY_DOUBLE_INFINITY THEN HEXTORAW('050000')"), sql)
        // A text is read from its list as itself, and one past a VARCHAR2 from its CLOB.
        assertTrue(sql.contains("a.get_String(i)") && sql.contains("EXCEPTION WHEN too_long OR VALUE_ERROR THEN") && sql.contains("a.get_Clob(i)") && sql.contains("SUBSTR(s, 1, 2000)"), sql)
        assertTrue(!sql.contains("to_String"), sql)
    }

    @Test
    fun `Postgres and SQLite sort a list as before, with no function of the query's`() {
        for (cypher in listOf("UNWIND [[1], [2]] AS xs RETURN xs ORDER BY xs DESC", "MATCH (n:A) RETURN n.l AS l ORDER BY l")) {
            for (sql in listOf(PostgresCypherCompiler("g").compile(cypher, emptyMap()).sql, SqliteCypherCompiler("g").compile(cypher, emptyMap()).sql)) {
                assertTrue(!sql.contains("cy_list_key") && !sql.startsWith("WITH\nFUNCTION"), sql)
            }
        }
    }
}
