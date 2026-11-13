package org.drivine.cypher

import org.drivine.cypher.oracle.OracleReads
import org.drivine.cypher.sql.OracleCypherCompiler
import org.drivine.cypher.sql.PostgresCypherCompiler
import org.drivine.cypher.sql.SqliteCypherCompiler
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * drivine4j#698: a map, a node or a relationship read by a number key, or a list read by a text key, fails as Neo4j fails
 * it: when the statement is compiled where both kinds are known, a parameter's by its value, and as it runs otherwise,
 * where Oracle gave the whole map and Postgres and SQLite null. What each store gives is the spec's, in `queries.yaml`
 * ("A map, a node or a list read by a key of another kind"): this is what each store is given.
 */
class IndexKeyKindTest {

    /** SqlCypherCompiler.NUMBER_KEY_OF_MAP and TEXT_INDEX_OF_LIST, which its protected companion holds. */
    private val numberKeyOfMap = "A map, a node or a relationship is read by a text key, as in Neo4j, not a"
    private val textIndexOfList = "A list is indexed by an integer, as in Neo4j, not a"

    private val compilers = listOf(PostgresCypherCompiler("g"), OracleCypherCompiler("g"), SqliteCypherCompiler("g"))

    private fun each(cypher: String, parameters: Map<String, Any?> = emptyMap(), check: (String) -> Unit) = compilers.forEach { compiler ->
        val sql = compiler.compile(cypher, parameters).sql
        runCatching { check(sql) }.onFailure { throw AssertionError("${compiler::class.simpleName}, $cypher: $sql", it) }
    }

    @Test
    fun `a node, a relationship or a map given read by a number, and a list given by a text, are refused`() {
        listOf(
            "MATCH (p:Person) RETURN p[\$k]" to mapOf("k" to 0),
            "MATCH (p:Person) WITH p, 0 AS k RETURN p[k]" to emptyMap(),
            "MATCH ()-[r]->() WITH r, 0 AS k RETURN r[k]" to emptyMap(),
            "RETURN \$m[0]" to mapOf("m" to mapOf("a" to 1)),
            "RETURN \$l[\$k]" to mapOf("l" to listOf(1), "k" to "a"),
        ).forEach { (cypher, parameters) ->
            compilers.forEach { compiler ->
                val refused = assertThrows<CompilerException>(cypher) { compiler.compile(cypher, parameters) }.message!!
                assertTrue(refused.contains(" is indexed by "), "$cypher: $refused")
            }
        }
    }

    @Test
    fun `a value of a kind left open is tested as it runs, for either key`() {
        each("MATCH (n) UNWIND [0, 'a'] AS k RETURN n.x[k]") { sql ->
            assertTrue(numberKeyOfMap in sql && textIndexOfList in sql)
        }
    }

    @Test
    fun `a value of a known kind is not tested, and fails only a key of the other kind, where it isn't null`() {
        // A node, a relationship and a map given are read as a map; a list split from a text as a list.
        listOf("MATCH (n) UNWIND [0, 'a'] AS k RETURN n[k]", "MATCH ()-[n]->() UNWIND [0, 'a'] AS k RETURN n[k]", "UNWIND [0, 'a'] AS k RETURN \$m[k]").forEach { cypher ->
            each(cypher, mapOf("m" to mapOf("a" to 1))) { sql ->
                assertFalse(textIndexOfList in sql)
                assertTrue(sql.indexOf("CASE WHEN NOT ") in 0 until sql.indexOf(numberKeyOfMap))
            }
        }
        listOf("WITH split(\$s, ',') AS l UNWIND [0, 'a'] AS k RETURN l[k]", "UNWIND [0, 'a'] AS k RETURN [10, 20][k]").forEach { cypher ->
            each(cypher, mapOf("s" to "a,b")) { sql ->
                assertFalse(numberKeyOfMap in sql)
                assertTrue(sql.indexOf("CASE WHEN NOT ") in 0 until sql.indexOf(textIndexOfList))
            }
        }
    }

    @Test
    fun `a list of nodes or relationships read by a key that may be no integer fails it, and by an integer is not tested`() {
        // spec#232: SQLite read the text as 0, and gave the list's first node; a float or a truth value it read as an integer.
        listOf(
            "MATCH (n:Person) WITH collect(n) AS l, 'a' AS k RETURN l[k]" to emptyMap(),
            "MATCH ()-[r]->() WITH collect(r) AS l, 'a' AS k RETURN l[k]" to emptyMap(),
            "MATCH (n:Person) WITH collect(n) AS l UNWIND ['a', 0] AS k RETURN l[k]" to emptyMap(),
            "MATCH (n:Person) WITH collect(n) AS l UNWIND [1.5, true] AS k RETURN l[k]" to emptyMap(),
            "MATCH (n:Person) WITH collect(n) AS l RETURN l[\$k]" to mapOf("k" to 1.5),
        ).forEach { (cypher, parameters) ->
            each(cypher, parameters) { sql ->
                assertTrue(textIndexOfList in sql)
                // The index is read where it is an integer or null, and fails otherwise, whatever its kind.
                assertTrue(sql.indexOf(" OR ") in 0 until sql.indexOf(textIndexOfList))
            }
        }
        listOf(
            "MATCH (n:Person) WITH collect(n) AS l RETURN l[0]",
            "MATCH (n:Person) WITH collect(n) AS l, 1 AS k RETURN l[k]",
            "MATCH (n:Person) WITH collect(n) AS l RETURN l[\$k]" to mapOf("k" to 1),
        ).forEach { case ->
            val (cypher, parameters) = case as? Pair<*, *> ?: (case to emptyMap<String, Any?>())
            @Suppress("UNCHECKED_CAST")
            each(cypher as String, parameters as Map<String, Any?>) { assertFalse(textIndexOfList in it) }
        }
    }

    @Test
    fun `an item taken out of another value is read untested`() {
        each("MATCH (n) UNWIND [0, 'a'] AS k RETURN n.x[0..1][k]") { sql -> assertFalse(numberKeyOfMap in sql || textIndexOfList in sql) }
        // Only the item n.x[0] is tested, by its number.
        each("MATCH (n) UNWIND [0, 'a'] AS k RETURN n.x[0][k]") { sql ->
            assertFalse(textIndexOfList in sql)
            assertEquals(1, sql.split(numberKeyOfMap).size - 1)
        }
    }

    @Test
    fun `Oracle names the key's kind as the text it is, and reads every entry on the CLOB route`() {
        val oracle = OracleCypherCompiler("g")
        listOf("MATCH (n:Doc) UNWIND ['long', 0] AS k RETURN n.x[k]", "MATCH (n:Doc) WITH n {.*} AS m UNWIND ['long', 0] AS k RETURN m[k]").forEach { cypher ->
            val sql = oracle.compile(cypher).sql
            assertEquals(emptyList(), OracleReads.unsafe(sql).map { "${it.source} ${it.path}" }, sql)
            assertTrue(sql.contains("TO_NUMBER('$numberKeyOfMap ' || DECODE("), sql)
            assertFalse(sql.contains("'$numberKeyOfMap ' || TO_CHAR("), sql)
        }
    }
}
