package org.drivine.cypher

import org.drivine.cypher.json.ArrayNode
import org.drivine.cypher.json.CypherJson
import org.drivine.cypher.sql.SqliteCypherCompiler
import org.drivine.cypher.sql.Temporal
import org.drivine.cypher.store.ValueLayout
import org.drivine.cypher.store.ddl.DdlStatement
import org.drivine.cypher.store.ddl.SqliteDdl
import org.drivine.schema.RangeIndexSpec
import org.junit.jupiter.api.Test
import java.sql.Connection
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * liberation-data/drivine-cypher-spec#354: on SQLite a list is equal to a list of the same items, `[1.0,2.5]` to `[1,2.5]`,
 * where the index's test beside the comparison held a list's JSON text as its key and found none. A value of any kind is now
 * let past that test by its class where it is a list, and the key tested as a range, which SQLite reads the index by: so
 * every lookup is still a search of the index, and one by a value known to be no list is the equality it was. writes.yaml's
 * "A list found by an equal list, whole numbers for its floats" runs these steps against Neo4j and each store.
 */
class SqliteListLookupTest {

    private val given = mapOf(
        "rows" to listOf(mapOf("s" to listOf(2.0, 3.0)), mapOf("s" to 4L), mapOf("s" to listOf(1L, 2.5)), mapOf("s" to listOf(9L))),
        "lists" to listOf(mapOf("s" to listOf(1L, 2.5))),
        "ids" to listOf(mapOf("s" to 4L), mapOf("s" to 12L)),
    )

    /** A store in memory with a range index over `:CyList(id)`, the steps' nodes, and 2,000 more, so the plan is a real one. */
    private fun store(): Connection = SqliteHost.connection().also { c ->
        val ddl = SqliteDdl("g")
        c.createStatement().use { s ->
            ddl.ddl.forEach { s.execute(it) }
            ddl.createIndex(RangeIndexSpec("CyList", "id")).forEach { s.execute((it as DdlStatement.Text).statement) }
            // As drivine4j writes a list of floats, and as SQLite's json_array writes integers.
            listOf("""{"id":[1.0,2.50],"k":5}""", """{"id":4.0,"k":6}""", """{"id":[2,3],"k":7}""").forEach { props ->
                s.execute("INSERT INTO g_node (labels, props) VALUES ('[\"CyList\"]', '$props')")
            }
            s.execute("WITH RECURSIVE r(i) AS (SELECT 10 UNION ALL SELECT i + 1 FROM r WHERE i < 2000) " +
                "INSERT INTO g_node (labels, props) SELECT '[\"CyList\"]', json_object('id', i, 'k', 1000 + i) FROM r")
        }
    }

    private fun sql(cypher: String): String {
        val compiled = SqliteCypherCompiler("g").compile(cypher, given)
        return compiled.parameters.entries.sortedByDescending { it.key.length }.fold(compiled.sql) { text, (name, value) ->
            text.replace("\$$name", if (value == null) "NULL" else if (value is Number) value.toString() else "'" + value.toString().replace("'", "''") + "'")
        }
    }

    private fun Connection.rows(sql: String): List<String> =
        createStatement().use { s -> s.executeQuery(sql).use { r -> buildList { while (r.next()) add(r.getString(1)) } } }

    /** The detail of each line of the statement's query plan. */
    private fun Connection.plan(sql: String): List<String> =
        createStatement().use { s -> s.executeQuery("EXPLAIN QUERY PLAN $sql").use { r -> buildList { while (r.next()) add(r.getString(4)) } } }

    @Test
    fun `a list is found by an equal list, and the index is searched for it`() = store().use { c ->
        listOf(
            "MATCH (a:CyList {k: 5}) RETURN [a.id = [1, 2.5], a.id = [1.0, 2.5], a.id = [1, 2.5, 3]]" to listOf("[true,true,false]"),
            "MATCH (a:CyList {id: [1, 2.5]}) RETURN a.k" to listOf("5"),
            "WITH [1, 2.5] AS v MATCH (a:CyList {id: v}) RETURN a.k" to listOf("5"),
            "WITH [2.0, 3.0] AS v MATCH (a:CyList {id: v}) RETURN a.k" to listOf("7"),
            "UNWIND \$rows AS row MATCH (a:CyList {id: row.s}) RETURN a.k ORDER BY a.k" to listOf("5", "6", "7"),
            "UNWIND \$lists AS row MATCH (a:CyList) WHERE a.id = row.s RETURN a.k" to listOf("5"),
            "MATCH (x:CyList) WHERE x.k < 10 MATCH (a:CyList {id: x.id}) RETURN [x.k, a.k] ORDER BY x.k" to listOf("[5,5]", "[6,6]", "[7,7]"),
        ).forEach { (cypher, expected) ->
            val sql = sql(cypher)
            assertEquals(expected, c.rows(sql), "$cypher\n$sql")
        }
        // Each lookup by a value of any kind is a search of the index, by its class and its key's range, not a scan of the label.
        listOf(
            "MATCH (a:CyList {id: [1, 2.5]}) RETURN a.k",
            "WITH [1, 2.5] AS v MATCH (a:CyList {id: v}) RETURN a.k",
            "UNWIND \$rows AS row MATCH (a:CyList {id: row.s}) RETURN a.k",
            "MATCH (x:CyList) WHERE x.k < 10 MATCH (a:CyList {id: x.id}) RETURN a.k",
        ).forEach { cypher ->
            val plan = c.plan(sql(cypher))
            assertTrue(plan.any { Regex("""SEARCH n\d USING INDEX g_ix2_CyListid_\w+ \(<expr>=\? AND <expr>>\? AND <expr><\?\)""").matches(it) }, "$cypher\n$plan")
        }
    }

    @Test
    fun `a scalar is found as before, and a value known to be no list is compared by the key's equality`() = store().use { c ->
        assertEquals(listOf("6", "1012"), c.rows(sql("UNWIND \$ids AS row MATCH (a:CyList {id: row.s}) RETURN a.k ORDER BY a.k")))
        assertEquals(listOf("6"), c.rows(sql("MATCH (x:CyList {k: 6}) MATCH (a:CyList {id: x.id}) RETURN a.k")))
        assertEquals(listOf("1012"), c.rows(sql("WITH 12 AS v MATCH (a:CyList {id: v}) RETURN a.k")))
        // Scalar rows the statement is given, and a value given as it compiles, keep the key's equality, unchanged.
        listOf("UNWIND \$ids AS row MATCH (a:CyList {id: row.s}) RETURN a.k", "MATCH (a:CyList {id: 12}) RETURN a.k").forEach { cypher ->
            val sql = sql(cypher)
            assertTrue("X''" !in sql, "$cypher\n$sql")
            val plan = c.plan(sql)
            assertTrue(plan.any { Regex("""SEARCH n\d USING INDEX g_ix2_CyListid_\w+ \(<expr>=\? AND <expr>=\?\)""").matches(it) }, "$cypher\n$plan")
        }
    }

    @Test
    fun `a list of temporal values, a list of lists and the empty list are found by an equal list`() = store().use { c ->
        c.createStatement().use { s ->
            // A date as the store holds one, its tag with its sort key.
            val date = CypherJson.writeValueAsString(ArrayNode().apply { add(ValueLayout.tag(Temporal.DATE, "2024-01-02")) })
            s.execute("INSERT INTO g_node (labels, props) VALUES ('[\"CyList\"]', json_object('id', json('$date'), 'k', 8))")
            s.execute("""INSERT INTO g_node (labels, props) VALUES ('["CyList"]', '{"id":[[1.0],[2,3.50]],"k":9}')""")
            s.execute("""INSERT INTO g_node (labels, props) VALUES ('["CyList"]', '{"id":[],"k":40}')""")
        }
        listOf(
            "WITH [date('2024-01-02')] AS v MATCH (a:CyList {id: v}) RETURN a.k" to listOf("8"),
            "WITH [date('2024-01-03')] AS v MATCH (a:CyList {id: v}) RETURN a.k" to emptyList(),
            "MATCH (x:CyList {k: 8}) MATCH (a:CyList {id: x.id}) RETURN a.k" to listOf("8"),
            "WITH [[1], [2.0, 3.5]] AS v MATCH (a:CyList {id: v}) RETURN a.k" to listOf("9"),
            "WITH [] AS v MATCH (a:CyList {id: v}) RETURN a.k" to listOf("40"),
            "WITH [null] AS v MATCH (a:CyList {id: v}) RETURN a.k" to emptyList(),
        ).forEach { (cypher, expected) ->
            val sql = sql(cypher)
            assertEquals(expected, c.rows(sql), "$cypher\n$sql")
        }
    }

    @Test
    fun `a text and a truth value of any kind are still found by their key alone`() = store().use { c ->
        c.createStatement().use { s ->
            s.execute("""INSERT INTO g_node (labels, props) VALUES ('["CyList"]', '{"id":"[1,2.5]","k":30}')""")
            s.execute("""INSERT INTO g_node (labels, props) VALUES ('["CyList"]', '{"id":true,"k":31}')""")
        }
        // A text that reads as a list is no list: the range is the key itself, and the list beside it isn't found by it.
        assertEquals(listOf("30"), c.rows(sql("WITH '[1,2.5]' AS v MATCH (a:CyList {id: v}) RETURN a.k")))
        assertEquals(listOf("5"), c.rows(sql("WITH [1, 2.5] AS v MATCH (a:CyList {id: v}) RETURN a.k")))
        assertEquals(listOf("31"), c.rows(sql("WITH true AS v MATCH (a:CyList {id: v}) RETURN a.k")))
    }
}
