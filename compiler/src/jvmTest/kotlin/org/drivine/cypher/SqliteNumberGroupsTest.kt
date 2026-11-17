package org.drivine.cypher

import org.drivine.cypher.sql.SqliteCypherCompiler
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * On SQLite, a number is counted and grouped by its value, not by its JSON text: `1` and `1.0`, and `0`, `0.0` and `-0.0`, are
 * one, as in Neo4j and on the other stores, where SQLite's text kept them apart (writes.yaml, "A zero and a whole float
 * compared, counted and grouped with the integer they equal"; drivine-cypher-spec#309). `DISTINCT` keeps its rows in the order
 * they come in, and still keeps a number by its text (drivine-cypher-spec#315). Run on SQLite in memory.
 */
class SqliteNumberGroupsTest {

    /** The single value [cypher] gives over nodes of the label CyWrite whose `k` is each of [ks], JSON text as SQLite stores it. */
    private fun run(cypher: String, ks: List<String>): String = SqliteHost.connection().use { c ->
        c.createStatement().use { s ->
            s.execute("CREATE TABLE g_node (nid INTEGER PRIMARY KEY, labels TEXT, props TEXT, stamp TEXT)")
            ks.forEach { k -> s.execute("INSERT INTO g_node (labels, props) VALUES ('[\"CyWrite\"]', '{\"k\":$k}')") }
            s.executeQuery(SqliteCypherCompiler("g").compile(cypher).sql).use { r -> r.next(); r.getString(1) }
        }
    }

    private val zeros = listOf("-0.0", "0.0", "0", "1", "1.0")

    @Test
    fun countDistinctAndGroupsKeyANumberByItsValue() {
        assertEquals("2", run("MATCH (n:CyWrite) RETURN count(DISTINCT n.k)", zeros))
        assertEquals("[2,3]", run("MATCH (n:CyWrite) WITH n.k AS k, count(*) AS c WITH c ORDER BY c RETURN collect(c)", zeros))
    }

    @Test
    fun anIntegerPast2To53StaysApartFromTheDoubleNearestIt() {
        // SQLite compares an INTEGER with a REAL exactly: 2^53 + 1 is not the float 2^53, which 2^53 is.
        val near = listOf("9007199254740993", "9007199254740992.0", "9007199254740992")
        assertEquals("2", run("MATCH (n:CyWrite) RETURN count(DISTINCT n.k)", near))
        assertEquals("[1,2]", run("MATCH (n:CyWrite) WITH n.k AS k, count(*) AS c WITH c ORDER BY c RETURN collect(c)", near))
    }

    @Test
    fun aTextOrAListIsKeptApartFromTheNumberItSpells() {
        val kinds = listOf("1", "\"1\"", "[1]", "1.0", "true")
        assertEquals("4", run("MATCH (n:CyWrite) RETURN count(DISTINCT n.k)", kinds))
    }

    @Test
    fun distinctKeepsTheOrderItsRowsComeInAsBefore() {
        // writes.yaml, "DISTINCT keeps the order its rows come in": a DISTINCT, not a GROUP BY, which SQLite gives sorted by its keys.
        val ordered = "UNWIND [{k: 3}, {k: 1}, {k: 2}, {k: 1}] AS m WITH m ORDER BY m.k DESC WITH DISTINCT m.k AS k"
        assertEquals("[3,2,1]", run("$ordered RETURN collect(k)", emptyList()))
        assertEquals("[3,2]", run("$ordered LIMIT 2 RETURN collect(k)", emptyList()))
        val sql = SqliteCypherCompiler("g").compile("MATCH (n:CyWrite) RETURN DISTINCT n.k AS k").sql
        assertTrue("SELECT DISTINCT" in sql && "GROUP BY" !in sql, sql)
    }
}
