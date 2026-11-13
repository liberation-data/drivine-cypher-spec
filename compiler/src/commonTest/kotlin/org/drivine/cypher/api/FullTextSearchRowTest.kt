package org.drivine.cypher.api

import kotlin.test.Test
import kotlin.test.assertTrue

/**
 * A full-text search's candidates on a SQL store carry each node's row beside its `nid`, so a library reads its projection
 * from them and joins its node table no second time (#223): on Oracle 23.26 that join, to a view of the node table on `nid`,
 * is eliminated with a filter on it where an index over a `JSON_VALUE` of its properties is made.
 */
@OptIn(org.drivine.DrivineInternalApi::class)
class FullTextSearchRowTest {

    private fun sql(store: SearchStore, index: SearchIndex = SearchIndex()): String =
        (FullTextSearch.plan(store, GraphOptions("g"), SearchRequest(listOf("Doc"), listOf("title", "body"), "graph +data -noise", index)) as SearchPlan).sql!!

    @Test
    fun eachStoresCandidatesCarryTheNodesRowThroughBm25() {
        for (store in listOf(SearchStore.POSTGRES, SearchStore.SQLITE, SearchStore.ORACLE)) for (trigram in listOf(false, true)) {
            if (trigram && store != SearchStore.POSTGRES) continue
            val sql = sql(store, SearchIndex(trigram = trigram))
            assertTrue(sql.startsWith("SELECT b.nid, b.labels, b.props, b.stamp, b.score / MAX(b.score) OVER () AS score"), "$store:\n$sql")
            assertTrue("w.nid, w.labels, w.props, w.stamp, " in sql && "s.nid, s.labels, s.props, s.stamp, s.len" in sql, "$store:\n$sql")
            assertTrue("SELECT c.nid, c.labels, c.props, c.stamp, " in sql, "$store's candidates select the row:\n$sql")
            if (store == SearchStore.ORACLE) assertTrue("COLUMNS (c.nid, c.labels, c.props, c.stamp)) c" in sql, "the graph's row is read:\n$sql")
            assertTrue("JOIN g_node" !in sql, "$store reads the node table once, in the candidates:\n$sql")
        }
    }

    @Test
    fun oracleTextsCandidatesCarryTheNodesRowFromTheOneJoinOfTheNodeTable() {
        val sql = sql(SearchStore.ORACLE, SearchIndex(oracleText = "g_t_doc"))
        assertTrue(sql.startsWith("SELECT h.nid, h.labels, h.props, h.stamp, h.score / MAX(h.score) OVER () AS score"), sql)
        assertTrue("SELECT c.nid, c.labels, c.props, c.stamp, k.score" in sql, sql)
        // The one join of the node table is to the side table's view, which reads no node table.
        assertTrue(Regex("JOIN g_node").findAll(sql).count() == 1 && "FROM g_t_doc s" in sql, sql)
    }
}
