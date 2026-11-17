package org.drivine.cypher

import org.drivine.cypher.sql.OracleCypherCompiler
import org.drivine.cypher.sql.PostgresCypherCompiler
import org.drivine.cypher.sql.SqliteCypherCompiler
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * drivine-cypher-spec#316: a `DISTINCT` of rows that come in an order keeps each row where the first of its kind came, as Neo4j
 * keeps it, where that order can be seen: its rows are numbered in their order and grouped, each ordered by its least number.
 * A `SELECT DISTINCT` keeps no order: Oracle hashes or sorts its rows, and Postgres may. What the stores give is the spec's, in
 * `queries.yaml` ("DISTINCT keeps the order its rows come in"): this runs those steps on SQLite in memory, and pins the SQL.
 */
class DistinctKeepsOrderTest {

    private val compilers = listOf(PostgresCypherCompiler("g"), OracleCypherCompiler("g"), SqliteCypherCompiler("g"))

    /** Each row's result [cypher] gives on SQLite in memory, as its JSON text. */
    private fun sqlite(cypher: String): List<String> = SqliteHost.connection().use { c ->
        val compiled = SqliteCypherCompiler("g").compile(cypher)
        val sql = compiled.parameters.entries.sortedByDescending { it.key.length }.fold(compiled.sql) { text, (name, value) ->
            text.replace("\$$name", if (value == null) "NULL" else if (value is Number) value.toString() else "'" + value.toString().replace("'", "''") + "'")
        }
        c.createStatement().use { s -> s.executeQuery(sql).use { r -> buildList { while (r.next()) add(r.getString(1)) } } }
    }

    /** Whether [sql] numbers its rows to keep them distinct in their order. */
    private fun numbered(sql: String) = "ROW_NUMBER() OVER (ORDER BY" in sql && Regex("""MIN\(q\d+\.rn\) AS o1""").containsMatchIn(sql)

    /** Scrambled: 2,000 rows in an order no key sorts, so a store that hashes or sorts the distinct rows gives others. */
    private val ordered = "UNWIND range(1, 2000) AS i WITH i ORDER BY (i * 7919) % 2000"
    private val unwound = "UNWIND [i IN range(1, 2000) | (i * 7919) % 2000] AS r"

    @Test
    fun `on SQLite each step gives the rows Neo4j 2026_10_1 gives`() {
        val first37 = "2, 14, 26, 1, 13, 25, 0, 12, 24, 36, 11, 23, 35, 10, 22, 34, 9, 21, 33, 8, 20, 32, 7, 19, 31, 6, 18, 30, 5, 17, 29, 4, 16, 28, 3, 15, 27"
        mapOf(
            "$ordered WITH DISTINCT i % 37 AS k LIMIT 5 RETURN k" to "2, 14, 26, 1, 13",
            "$ordered WITH DISTINCT i % 37 AS k SKIP 32 RETURN k" to "16, 28, 3, 15, 27",
            "$ordered WITH DISTINCT i % 37 AS k RETURN collect(k)" to "[$first37]",
            "$unwound WITH DISTINCT r % 37 AS k LIMIT 5 RETURN k" to "32, 25, 18, 11, 4",
            "$ordered RETURN DISTINCT i % 37 AS k" to first37,
            "$ordered RETURN DISTINCT i % 37 AS k LIMIT 5" to "2, 14, 26, 1, 13",
            "UNWIND range(1, 2000) AS i WITH i ORDER BY (i * 7919) % 2000 DESC WITH DISTINCT i % 37 AS k, i % 2 AS p LIMIT 5 RETURN k, p" to
                "[25, 1], [13, 0], [1, 1], [26, 0], [14, 1]",
            "CALL { $unwound RETURN DISTINCT r % 37 AS k } RETURN k LIMIT 5" to "32, 25, 18, 11, 4",
            "UNWIND [1, 2] AS j CALL (j) { $unwound WITH DISTINCT (r + j) % 37 AS k LIMIT 3 RETURN k } RETURN j, k" to
                "[1, 33], [1, 26], [1, 19], [2, 34], [2, 27], [2, 20]",
            "$ordered WITH DISTINCT i % 37 AS k RETURN count(k), sum(k)" to "[37, 666]",
            "$ordered WITH DISTINCT i % 37 AS k WITH k LIMIT 5 RETURN collect(k)" to "[2, 14, 26, 1, 13]",
        ).forEach { (cypher, rows) ->
            assertEquals(rows, sqlite(cypher).joinToString(", ") { it.replace(",", ", ") }, cypher)
        }
    }

    @Test
    fun `a DISTINCT whose order is seen numbers its rows on every store`() {
        listOf(
            "$ordered WITH DISTINCT i % 37 AS k LIMIT 5 RETURN k",
            "$ordered WITH DISTINCT i % 37 AS k SKIP 32 RETURN k",
            "$ordered WITH DISTINCT i % 37 AS k RETURN collect(k)",
            "$ordered WITH DISTINCT i % 37 AS k RETURN size(collect(k)) AS n",
            "$unwound WITH DISTINCT r % 37 AS k LIMIT 5 RETURN k",
            "$ordered RETURN DISTINCT i % 37 AS k",
            "$ordered WITH DISTINCT i % 37 AS k WITH k LIMIT 5 RETURN collect(k)",
            "$ordered WITH DISTINCT i % 37 AS k WITH k ORDER BY k % 2 RETURN k",
            "$ordered WITH DISTINCT i % 37 AS k MATCH (n) WHERE n.k = k RETURN n.k",
            "$ordered WITH DISTINCT i % 37 AS k CALL (k) { RETURN k * 2 AS d } RETURN count(d)",
            "CALL { $unwound RETURN DISTINCT r % 37 AS k } RETURN count(k)",
            "UNWIND [1, 2] AS j CALL (j) { $unwound WITH DISTINCT (r + j) % 37 AS k LIMIT 3 RETURN k } RETURN j, k",
            // Its own LIMIT sees the order, even where nothing after it does.
            "RETURN COUNT { $unwound RETURN DISTINCT r % 37 AS k LIMIT 3 } AS n",
            // A write reads the rows in their order.
            "UNWIND [3, 1, 3] AS x WITH DISTINCT x CREATE (:N {x: x})",
        ).forEach { cypher ->
            compilers.forEach { compiler ->
                val sql = compiler.compile(cypher).sql
                assertTrue(numbered(sql), "${compiler::class.simpleName}: $cypher\n$sql")
                assertFalse("SELECT DISTINCT" in sql, "${compiler::class.simpleName}: $cypher\n$sql")
            }
        }
    }

    @Test
    fun `a DISTINCT of rows in no order is a SELECT DISTINCT as before`() {
        listOf(
            "MATCH (p:Person) RETURN DISTINCT p.name",
            "MATCH (p:Person) WITH DISTINCT p.born AS b RETURN b LIMIT 2",
            "MATCH (p:Person) WITH DISTINCT p.born AS b RETURN collect(b)",
            // One that orders its rows its own way sorts them after.
            "$ordered RETURN DISTINCT i % 37 AS k ORDER BY k DESC",
        ).forEach { cypher ->
            compilers.forEach { compiler ->
                val sql = compiler.compile(cypher).sql
                assertFalse(numbered(sql), "${compiler::class.simpleName}: $cypher\n$sql")
                assertTrue("SELECT DISTINCT" in sql, "${compiler::class.simpleName}: $cypher\n$sql")
            }
        }
    }

    @Test
    fun `a DISTINCT whose order only an order-blind aggregate or a subquery's test reads is a SELECT DISTINCT`() {
        listOf(
            "$ordered WITH DISTINCT i % 37 AS k RETURN count(k), sum(k)",
            "$ordered WITH DISTINCT i % 37 AS k RETURN count(*), avg(k), min(k), max(k)",
            "$ordered WITH DISTINCT i % 37 AS k RETURN k % 2 AS p, count(*) AS n",
            "$ordered WITH DISTINCT i % 37 AS k WITH k WHERE k > 3 UNWIND [k, k] AS x RETURN count(DISTINCT x)",
            "$unwound WITH DISTINCT r % 37 AS k RETURN sum(k)",
            "UNWIND [1, 2, 3] AS o RETURN o, EXISTS { UNWIND [1, 1, 2] AS j WITH DISTINCT j WHERE j = o RETURN j }",
            "UNWIND [1, 2, 3] AS o RETURN o, COUNT { UNWIND [1, 1, 2] AS j RETURN DISTINCT j + o }",
            "UNWIND [1, 2, 3] AS o RETURN o, COUNT { UNWIND [1, 1, 2] AS j WITH DISTINCT j RETURN j }",
            "UNWIND [1, 2] AS j CALL (j) { $unwound WITH DISTINCT (r + j) % 37 AS k RETURN count(k) AS n } RETURN j, n",
            // A procedure's rows come in no order Neo4j gives.
            "CALL db.labels()",
            "CALL db.labels() YIELD label RETURN label",
            "CALL db.relationshipTypes() YIELD relationshipType AS t RETURN t LIMIT 1",
        ).forEach { cypher ->
            compilers.forEach { compiler ->
                val sql = compiler.compile(cypher).sql
                assertFalse(numbered(sql), "${compiler::class.simpleName}: $cypher\n$sql")
                assertTrue("SELECT DISTINCT" in sql, "${compiler::class.simpleName}: $cypher\n$sql")
            }
        }
    }

    /** The lines of [sql] a numbered DISTINCT reads its rows from: from its ROW_NUMBER() to the GROUP BY that keeps them distinct. */
    private fun numberedRows(sql: String): String {
        val lines = sql.lines()
        val from = lines.indexOfFirst { "ROW_NUMBER() OVER (ORDER BY" in it }
        val to = lines.indexOfFirst { Regex("""^\s*GROUP BY q\d+\.""").containsMatchIn(it) }
        return lines.subList(from, to).joinToString("\n")
    }

    @Test
    fun `the ORDER BY of a WITH a numbered DISTINCT reads is left out where it picks no rows`() {
        // ROW_NUMBER() sorts the rows by the same terms: a second sort of them is all the ORDER BY would add.
        listOf(
            "$ordered WITH DISTINCT i % 37 AS k LIMIT 5 RETURN k",
            "$ordered MATCH (n) WITH DISTINCT n.k AS k LIMIT 5 RETURN k",
            "$ordered WITH i, i % 2 AS p WITH DISTINCT i % 37 AS k LIMIT 5 RETURN k",
        ).forEach { cypher ->
            compilers.forEach { compiler ->
                val sql = compiler.compile(cypher).sql
                assertFalse(Regex("""^\s*ORDER BY""", RegexOption.MULTILINE).containsMatchIn(numberedRows(sql)), "${compiler::class.simpleName}: $cypher\n$sql")
            }
        }
        // A SKIP or a LIMIT picks the rows by it: it is kept.
        listOf(
            "UNWIND range(1, 2000) AS i WITH i ORDER BY (i * 7919) % 2000 LIMIT 100 WITH DISTINCT i % 37 AS k LIMIT 5 RETURN k",
            "UNWIND range(1, 2000) AS i WITH i ORDER BY (i * 7919) % 2000 SKIP 100 WITH DISTINCT i % 37 AS k LIMIT 5 RETURN k",
        ).forEach { cypher ->
            compilers.forEach { compiler ->
                val sql = compiler.compile(cypher).sql
                assertTrue(Regex("""^\s*ORDER BY""", RegexOption.MULTILINE).containsMatchIn(numberedRows(sql)), "${compiler::class.simpleName}: $cypher\n$sql")
            }
        }
    }

    @Test
    fun `a NaN is still kept apart in the order it came`() {
        // Its d1, a number no other row has, is grouped by too (drivine4j#685).
        val cypher = "UNWIND [0.0, 1.0, 0.0, 1.0] AS z RETURN DISTINCT z / z AS v"
        compilers.forEach { compiler ->
            val sql = compiler.compile(cypher).sql
            assertTrue(numbered(sql) && Regex("""GROUP BY q\d+\.c1, q\d+\.d1""").containsMatchIn(sql), "${compiler::class.simpleName}\n$sql")
        }
        // SQLite gives NaN as a tagged JSON number.
        assertEquals(listOf("NaN", "1.0", "NaN"), sqlite(cypher).map { if ("NaN" in it) "NaN" else it.toDouble().toString() })
    }
}
