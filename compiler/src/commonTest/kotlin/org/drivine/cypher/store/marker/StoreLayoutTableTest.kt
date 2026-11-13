package org.drivine.cypher.store.marker

import org.drivine.cypher.store.ddl.Neo4jDdl
import org.drivine.cypher.store.ddl.OracleDdl
import org.drivine.cypher.store.ddl.PostgresDdl
import org.drivine.cypher.store.ddl.SqlStoreDdl
import org.drivine.cypher.store.ddl.SqliteDdl
import org.drivine.cypher.store.ddl.StoreDdl
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue
import kotlin.test.fail

/**
 * [StoreLayout.verdict] over every graph it can be asked of, against its table (liberation-data/drivine-cypher-spec#161): each
 * combination of an engine, the stamps read, whether the graph holds values, the objects the store lists and whether the
 * library gives the store's DDL meets the table's rows, written here apart from the verdict's code, and the verdict is the
 * first row's. Each [StoreLayout.Verdict.Stamp] is then run as a library runs it, its drops taken from the objects, its stamp
 * written or not, the objects listed again, and asked again: the graph is then [StoreLayout.Verdict.Current].
 */
class StoreLayoutTableTest {

    private val graph = "g"

    /** The stamps a graph may hold, in terms of the engine's own layout. */
    private enum class Stamps { NONE, CURRENT, EARLIER, NO_LAYOUT, NOT_A_NUMBER, LATER, MIXED }

    /** The objects a store may list, each a name a layout generates, or another graph's, or entries of no name a library can read. */
    private enum class Object { OLD, LOCK, OWN, LATER, LATER_LOCK, NEIGHBOUR, UNKNOWN }

    private sealed interface Expected {
        data object Current : Expected
        data class Stamp(val drops: Boolean, val assign: Boolean) : Expected
        data class Refuse(val says: String) : Expected
    }

    /** A graph the verdict is asked of. */
    private data class Graph(val engine: String, val stamps: Stamps, val values: Boolean, val objects: Set<Object>, val ddl: Boolean) {
        val sql get() = engine in setOf("POSTGRES", "ORACLE", "SQLITE")
        /** The objects the verdict can know: by their names, only with the DDL. */
        fun known(o: Object) = ddl && sql && o in objects
        val laterStamp get() = stamps == Stamps.LATER
        val stamped get() = stamps != Stamps.NONE
        val earlierStamp get() = stamps == Stamps.EARLIER || ((stamps == Stamps.NO_LAYOUT || stamps == Stamps.NOT_A_NUMBER) && sql)
    }

    /** The table, as the README and [StoreLayout]'s KDoc write it: the first row a graph meets is its verdict. */
    private val rows: List<Pair<String, (Graph) -> Expected?>> = listOf(
        "1 stamps of two layouts" to { g -> if (g.stamps == Stamps.MIXED) Expected.Refuse("two versions of Drivine stamped it") else null },
        "2 a later layout's stamp" to { g -> if (g.laterStamp) Expected.Refuse("by a later version of Drivine") else null },
        "3 a later layout's objects" to { g -> if (g.known(Object.LATER) || g.known(Object.LATER_LOCK)) Expected.Refuse("by a later version of Drivine") else null },
        "4 this layout's stamp" to { g -> if (g.stamped && !g.earlierStamp) Expected.Current else null },
        "5 an earlier stamp, no values" to { g -> if (g.earlierStamp && g.sql && !g.values) Expected.Stamp(drops = g.known(Object.OLD), assign = false) else null },
        "6 an earlier stamp, values" to { g -> if (g.earlierStamp && g.sql) Expected.Refuse("Reload it") else null },
        "7 no stamp or an earlier one, a graph engine" to { g -> if (!g.sql) Expected.Current else null },
        "8 no stamp, no values, nothing earlier" to { g -> if (!g.values && !g.known(Object.OLD)) Expected.Current else null },
        "9 no stamp, no values, something earlier" to { g -> if (!g.values) Expected.Stamp(drops = true, assign = false) else null },
        "10 no stamp, values, both layouts' objects" to { g ->
            if ((g.known(Object.LOCK) || g.known(Object.OWN)) && g.known(Object.OLD)) Expected.Refuse("has no store stamp, and holds objects of store layout") else null
        },
        "11 no stamp, values, this layout's only" to { g -> if (g.known(Object.LOCK) || g.known(Object.OWN)) Expected.Stamp(drops = false, assign = true) else null },
        "12 no stamp, values, nothing of this layout's" to { _ -> Expected.Refuse("written in store layout 1, and this version") },
    )

    private fun ddl(engine: String): StoreDdl = when (engine) {
        "POSTGRES" -> PostgresDdl(graph)
        "ORACLE" -> OracleDdl(graph)
        "SQLITE" -> SqliteDdl(graph)
        else -> Neo4jDdl
    }

    private fun stampRows(engine: String, stamps: Stamps): List<Map<String, Any?>> {
        val own = StoreLayout.current(engine)
        fun of(layout: Int?) = mapOf("layout" to layout)
        return when (stamps) {
            Stamps.NONE -> emptyList()
            Stamps.CURRENT -> listOf(of(own))
            Stamps.EARLIER -> listOf(of(own - 1))
            Stamps.NO_LAYOUT -> listOf(of(null))
            Stamps.NOT_A_NUMBER -> listOf(mapOf("layout" to "two"))
            Stamps.LATER -> listOf(of(own + 1))
            Stamps.MIXED -> listOf(of(own), of(own + 1))
        }
    }

    private fun objectRows(ddl: StoreDdl, objects: Set<Object>): List<Map<String, Any?>> {
        val lock = (ddl as? SqlStoreDdl)?.lock ?: "${graph}_lock2"
        return objects.flatMap { o ->
            when (o) {
                Object.OLD -> listOf(mapOf("name" to "${graph}_ix_personname_1a2b3c4d", "type" to "INDEX"))
                Object.LOCK -> listOf(mapOf("name" to lock.lowercase(), "type" to "TABLE"))
                Object.OWN -> listOf(mapOf("name" to "${graph}_uq2_personname_1a2b3c4d", "type" to "INDEX"))
                Object.LATER -> listOf(mapOf("name" to "${graph}_ix3_personname_1a2b3c4d", "type" to "INDEX"))
                // As Oracle lists it, every object whose name begins with the graph's.
                Object.LATER_LOCK -> listOf(mapOf("name" to "${graph}_lock3", "type" to "TABLE"))
                // Another graph's, whose name begins with this one's: never this graph's.
                Object.NEIGHBOUR -> listOf(mapOf("name" to "${graph}_t_uq2_personname_1a2b3c4d", "type" to "INDEX"))
                // Entries a library can't read a name from: no layout's.
                Object.UNKNOWN -> listOf(mapOf("type" to "INDEX"), mapOf("name" to null, "type" to null), mapOf("name" to 42, "type" to "TABLE"))
            }
        }
    }

    private fun ask(g: Graph, stamps: List<Map<String, Any?>>, objects: List<Map<String, Any?>>) =
        StoreLayout.verdict(g.engine, graph, stamps, g.values, objects, if (g.ddl) ddl(g.engine) else null)

    private fun graphs(): List<Graph> {
        val objectSets = (0 until (1 shl Object.entries.size)).map { bits -> Object.entries.filterIndexed { i, _ -> bits and (1 shl i) != 0 }.toSet() }
        // A graph engine lists no objects, but is asked of every set, with its DDL and without, as a SQL store is.
        return listOf("POSTGRES", "ORACLE", "SQLITE", "NEO4J").flatMap { engine ->
            Stamps.entries.flatMap { stamps ->
                listOf(false, true).flatMap { values ->
                    objectSets.flatMap { objects -> listOf(true, false).map { Graph(engine, stamps, values, objects, it) } }
                }
            }
        }
    }

    @Test
    fun everyGraphMeetsARowAndTheVerdictIsTheFirstRowItMeets() {
        val graphs = graphs()
        val wrong = mutableListOf<String>()
        for (g in graphs) {
            val (row, expected) = rows.firstNotNullOfOrNull { (name, row) -> row(g)?.let { name to it } } ?: fail("$g meets no row")
            val got = ask(g, stampRows(g.engine, g.stamps), objectRows(ddl(g.engine), g.objects))
            val ok = when (expected) {
                Expected.Current -> got == StoreLayout.Verdict.Current
                is Expected.Stamp -> got is StoreLayout.Verdict.Stamp && got.assign == expected.assign && got.drops.isNotEmpty() == expected.drops
                is Expected.Refuse -> got is StoreLayout.Verdict.Refuse && got.message.contains(expected.says)
            }
            if (!ok) wrong += "$g, row $row: expected $expected, got $got"
        }
        assertTrue(wrong.isEmpty(), "${wrong.size} of ${graphs.size} graphs:\n" + wrong.take(20).joinToString("\n"))
        // Each store: every stamp, both of values, each set of objects, with its DDL and without.
        assertEquals(4 * Stamps.entries.size * 2 * (1 shl Object.entries.size) * 2, graphs.size, "every combination")
    }

    @Test
    fun aStampRunAsALibraryRunsItLeavesTheGraphCurrent() {
        var replayed = 0
        for (g in graphs()) {
            val ddl = ddl(g.engine)
            val objects = objectRows(ddl, g.objects)
            val first = ask(g, stampRows(g.engine, g.stamps), objects) as? StoreLayout.Verdict.Stamp ?: continue
            // The drops run: the earlier layout's objects are gone. The stamp: a restamp updates one there is, an assign makes one.
            val left = if (first.drops.isEmpty()) objects else objects.filterNot { o -> (o["name"] as? String)?.let { name -> first.drops.any { it.contains(name) } } ?: false }
            val stamps = when {
                first.assign -> stampRows(g.engine, Stamps.CURRENT)
                g.stamps == Stamps.NONE -> emptyList()
                else -> stampRows(g.engine, Stamps.CURRENT)
            }
            // Asked again, the objects listed again: a library that asked with the first listing would be told to stamp again.
            assertEquals(StoreLayout.Verdict.Current, ask(g, stamps, left), "$g after $first")
            replayed++
        }
        assertTrue(replayed > 100, "$replayed stamps replayed")
    }

    /** DDL of another store than the graph's is the library's error, thrown, never a verdict on objects it misreads. */
    @Test
    fun theDdlOfAnotherStoreIsThrown() {
        val engines = listOf("POSTGRES", "ORACLE", "SQLITE", "NEO4J")
        for (engine in engines) for (other in engines) {
            if (other == engine) continue
            assertFailsWith<IllegalArgumentException>("$engine given ${ddl(other)}") { StoreLayout.verdict(engine, graph, emptyList(), false, emptyList(), ddl(other)) }
        }
    }
}
