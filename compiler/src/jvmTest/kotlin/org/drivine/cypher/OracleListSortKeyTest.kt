package org.drivine.cypher

import org.drivine.cypher.sql.OracleCypherCompiler
import org.drivine.cypher.sql.PostgresCypherCompiler
import org.drivine.cypher.sql.SqliteCypherCompiler
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * On Oracle, a sort by a list is by a key the query's own function, `cy_list_key`, gives, then by the list as `JSON` sorts it
 * (drivine4j#205): declared once with `WITH FUNCTION`, before the statement, and not in a statement that writes, whose block
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
    fun `a value of a kind left open is sorted by the key between its kind and itself, where it is a list`() {
        val sql = oracle("MATCH (n:A) RETURN n.l AS l ORDER BY l")
        assertEquals(1, declarations(sql), sql)
        assertTrue(
            Regex("""ORDER BY DECODE\(JSON_VALUE\((\S+), '\$\.type\(\)'\), [^)]*\), CASE WHEN JSON_VALUE\(\1, '\$\.type\(\)'\) = 'array' THEN cy_list_key\(\1\) END, \1$""")
                .containsMatchIn(sql),
            sql,
        )
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
        assertTrue(sql.contains("EXCEPTION WHEN too_long THEN") && sql.contains("SUBSTR(s, 1, 2000)"), sql)
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
