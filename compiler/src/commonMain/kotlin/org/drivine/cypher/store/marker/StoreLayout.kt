package org.drivine.cypher.store.marker

import org.drivine.DrivineInternalApi
import org.drivine.cypher.store.OracleSql
import org.drivine.cypher.store.PostgresSql
import org.drivine.cypher.store.SqliteSql
import org.drivine.cypher.store.ValueLayout
import org.drivine.cypher.store.ddl.OracleDdl
import org.drivine.cypher.store.ddl.PostgresDdl
import org.drivine.cypher.store.ddl.SqlStoreDdl
import org.drivine.cypher.store.ddl.SqliteDdl
import org.drivine.cypher.store.ddl.StoreDdl

/**
 * The store layout a graph is held in, and what a library does about it on its first connection, before any statement runs:
 * the module decides, and the library runs the reads and the writes it names and throws its refusal (liberation-data/drivine-cypher-spec#161).
 *
 * The layout is the store stamp's `layout` ([StoreStamp]): how values are held in it ([ValueLayout]), and so how its indexes
 * are made. A SQL store holds [ValueLayout.LAYOUT]; a graph engine holds 1, its own values, and a stamp with no `layout` is 1.
 * A library reads the stamps ([StoreStampStatements.readStamps]) and whether the graph holds a node other than Drivine's own,
 * the stamp and the schema marker ([StoreStampStatements.foreignNodes]), and asks [verdict]:
 *
 * | The stamps' layout | Nodes other than Drivine's | Verdict |
 * |---|---|---|
 * | the library's own | any | [Verdict.Current] |
 * | absent or older | none | [Verdict.Stamp]: the old layout's indexes dropped, then [StoreStampStatements.restampLayout]. Nothing is migrated |
 * | absent or older | some | [Verdict.Refuse]: names the graph, its layout and this one's, and the reload |
 * | above the library's own | any, none among them | [Verdict.Refuse]: written by a later Drivine, to be upgraded |
 * | several, of different layouts | any | [Verdict.Refuse] |
 * | none | any, and a later layout's generated names (`g_ix3_…`) | [Verdict.Refuse]: made by a later Drivine, to be upgraded |
 * | none | none, and no earlier layout's object | [Verdict.Current]: a new graph, or one cleared; the stamp is assigned as ever |
 * | none | none, and an earlier layout's objects | [Verdict.Stamp]: those dropped, whatever of this layout's stands beside them, then asked again |
 * | none | some, and the store's objects show this layout made it and no earlier one did | [Verdict.Stamp] with [Verdict.Stamp.assign]: a graph of this layout whose stamp a clear deleted (`MATCH (n) DETACH DELETE n` deletes Drivine's own nodes too), stamped again |
 * | none | some, and objects of this layout and an earlier one | [Verdict.Refuse], naming both |
 *
 * This layout's objects are its lock table ([SqlStoreDdl.lock]), which only this layout makes, and the names it generates
 * ([SqlStoreDdl.objectName], `g_ix2_…`); an earlier layout's, the names it generated (`g_ix_…`, [oldObjects]).
 *
 * After a [Verdict.Stamp]'s statements the library reads the stamps back, lists the objects again (a graph with no stamp
 * is judged by them, and the objects asked of first are those it dropped), and asks again, and refuses anything but
 * [Verdict.Current]: two processes may stamp an empty graph at once, one of them a library from before layouts that stamps
 * with none, and the store is never used on a guess. A drop that fails leaves the graph refused, never used half re-stamped.
 */
@DrivineInternalApi
object StoreLayout {

    /** What a library does with a graph: see [StoreLayout]. */
    sealed interface Verdict {
        /** The graph is in this library's layout, or new, with no stamp yet and nothing of an earlier layout's: use it. */
        data object Current : Verdict

        /**
         * The graph holds no value of its own, and is in an older layout or none, or, where [assign], holds values of this layout
         * and no stamp: run [drops], each a statement of the store's,
         * then [StoreStampStatements.restampLayout] with [parameters], then read the stamps back, list the objects again, and ask [verdict] again. Where
         * [assign], the graph has no stamp to give the layout, a graph of this layout whose stamp a clear deleted: run
         * [StoreStampStatements.assignIfAbsent] with [StoreStamp.assignParameters] (the library's own id and moment) in place of
         * the re-stamp, then ask again.
         */
        data class Stamp(val drops: List<String>, val parameters: Map<String, Any?>, val assign: Boolean = false) : Verdict

        /** The graph is not to be used: throw [message]. */
        data class Refuse(val message: String) : Verdict
    }

    /** The layout this module holds values in on the store whose stamp names [engine] (`POSTGRES`, `NEO4J` …): see [StoreStamp.engine]. */
    fun current(engine: String): Int = if (engine in SQL_ENGINES) ValueLayout.LAYOUT else GRAPH_ENGINE_LAYOUT

    /**
     * The verdict on the graph [graph] of the store [engine] names, whose stamps [stampRows] read (each a map with `layout`, as
     * [StoreStampStatements.readStamps] gives it), and which holds a node other than Drivine's own where [foreignNodes]. [objects]
     * are the names the store's [StoreStampStatements.layoutObjects] lists, each `{name, type}`, whose old layout's a [Verdict.Stamp]
     * drops, and which, where no stamp was read, tell a graph of this layout whose stamp a clear deleted from an earlier one's
     * ([ownObjects], [oldObjects]): on a store of [ddl], the one the library made the graph with, where it has one; without it,
     * no object is known as either layout's.
     */
    fun verdict(
        engine: String,
        graph: String,
        stampRows: List<Map<String, Any?>>,
        foreignNodes: Boolean,
        objects: List<Map<String, Any?>> = emptyList(),
        ddl: StoreDdl? = null,
    ): Verdict {
        val own = current(engine)
        val layouts = stampRows.map { layoutOf(it["layout"]) }.distinct().sorted()
        val store = ENGINE_NAMES[engine] ?: engine
        if (layouts.size > 1) {
            return Verdict.Refuse(
                "The graph '$graph' on $store has store stamps of layouts ${layouts.joinToString(" and ")}: two versions of Drivine stamped it at once. " +
                    "This version of Drivine reads layout $own only. Reload it: export it with the Drivine version that wrote it and import it with this one; see the upgrade notes.",
            )
        }
        val sql = ddl as? SqlStoreDdl
        // No stamp at all: a new graph, or one whose stamp a clear deleted, told by what the store holds. Without [ddl] no
        // object is known as either layout's: an empty graph is still new, not re-stamped by a restamp that finds no stamp.
        if (stampRows.isEmpty() && engine in SQL_ENGINES) {
            val old = sql?.let { oldObjects(it, objects) }.orEmpty()
            val ours = sql?.let { ownObjects(it, objects) }.orEmpty()
            // A later layout's names: refused whatever the graph holds, as a later layout's stamp is, not taken for new or for layout 1.
            val later = sql?.let { s -> objects.mapNotNull { row -> (row["name"] as? String)?.let { generatedLayout(generated(s), it) } } }.orEmpty().maxOrNull()
            if (later != null && later > own) {
                return Verdict.Refuse(
                    "The graph '$graph' on $store was written in store layout $later by a later version of Drivine, and this version reads layout $own only: upgrade Drivine to use it.",
                )
            }
            // Values beside both layouts' objects can't be told apart. An empty graph has nothing to lose: an earlier layout's
            // are dropped as below, whatever of this layout's stands beside them.
            if (foreignNodes && old.isNotEmpty() && ours.isNotEmpty()) {
                return Verdict.Refuse(
                    "The graph '$graph' on $store has no store stamp, and holds objects of store layout $own (${ours.joinToString()}) and of an earlier layout " +
                        "(${old.joinToString { it.first }}): two versions of Drivine made it. This version of Drivine reads layout $own only. " +
                        "Reload it: export it with the Drivine version that wrote it and import it with this one; see the upgrade notes.",
                )
            }
            if (!foreignNodes && old.isEmpty()) return Verdict.Current
            if (foreignNodes && ours.isNotEmpty()) return Verdict.Stamp(emptyList(), StoreStamp.restampParameters(engine), assign = true)
        }
        val layout = layouts.singleOrNull() ?: GRAPH_ENGINE_LAYOUT
        return when {
            layout == own -> Verdict.Current
            layout > own -> Verdict.Refuse(
                "The graph '$graph' on $store was written in store layout $layout by a later version of Drivine, and this version reads layout $own only: upgrade Drivine to use it.",
            )
            foreignNodes -> Verdict.Refuse(
                "The graph '$graph' on $store was written in store layout $layout, and this version of Drivine reads layout $own only, which holds values otherwise. " +
                    "Reload it: export it with the Drivine version that wrote it and import it with this one; see the upgrade notes.",
            )
            else -> Verdict.Stamp((ddl as? SqlStoreDdl)?.let { drops(it, objects) }.orEmpty(), StoreStamp.restampParameters(engine))
        }
    }

    /** The layout a stamp's `layout` names: a number, or none, which is 1. */
    private fun layoutOf(value: Any?): Int = when (value) {
        null -> GRAPH_ENGINE_LAYOUT
        is Number -> value.toInt()
        is String -> value.toIntOrNull() ?: GRAPH_ENGINE_LAYOUT
        else -> GRAPH_ENGINE_LAYOUT
    }

    /**
     * Of [objects], as the store [ddl] lists them, those an earlier layout made: each generated name whose kind carries no
     * layout or an older one (`g_ix_…`, where this layout makes `g_ix2_…`), its index, table or trigger. The whole name is
     * matched, its readable part and its hash and what is made beside it ([SqlStoreDdl.objectName]): Oracle lists every object
     * whose name begins with the graph's, and another graph's, `g_t`'s table `g_t_node` among them, is never dropped.
     */
    fun oldObjects(ddl: SqlStoreDdl, objects: List<Map<String, Any?>>): List<Pair<String, String>> {
        val generated = generated(ddl)
        return objects.mapNotNull { row ->
            val name = row["name"] as? String ?: return@mapNotNull null
            val type = (row["type"] as? String)?.uppercase() ?: "INDEX"
            val layout = generatedLayout(generated, name) ?: return@mapNotNull null
            if (layout < ValueLayout.LAYOUT) name to type else null
        }
    }

    /**
     * Of [objects], as the store [ddl] lists them, those only this layout makes: its lock table ([SqlStoreDdl.lock]), and each
     * generated name whose kind carries this layout (`g_ix2_…`): the evidence that this layout made the graph, where its stamp
     * is gone.
     */
    fun ownObjects(ddl: SqlStoreDdl, objects: List<Map<String, Any?>>): List<String> {
        val generated = generated(ddl)
        return objects.mapNotNull { row ->
            val name = row["name"] as? String ?: return@mapNotNull null
            if (name.equals(ddl.lock, ignoreCase = true) || generatedLayout(generated, name) == ValueLayout.LAYOUT) name else null
        }
    }

    /**
     * A whole name the store [ddl] generates for the graph ([SqlStoreDdl.objectName]), its readable part, its hash and what is
     * made beside it, so that another graph's, whose name begins with this one's, is never taken for it.
     */
    private fun generated(ddl: SqlStoreDdl) =
        Regex("^${Regex.escape(ddl.name)}_(ix|lx|uq|t|v)([0-9]*)_[a-z0-9]*_[0-9a-f]{1,8}(_t|_i)?$", RegexOption.IGNORE_CASE)

    /** The layout the [generated] name [name] carries: none is 1; null where the name is no generated one. */
    private fun generatedLayout(generated: Regex, name: String): Int? =
        generated.find(name)?.groupValues?.get(2)?.let { it.toIntOrNull() ?: GRAPH_ENGINE_LAYOUT }

    /**
     * The statements that drop the [oldObjects] of [objects] on the store [ddl], and the records of the catalog that name
     * them, so the items they were made for are made again in this layout. Run on a graph that holds no value but Drivine's
     * own, where each costs nothing and loses nothing.
     */
    fun drops(ddl: SqlStoreDdl, objects: List<Map<String, Any?>>): List<String> {
        val old = oldObjects(ddl, objects)
        if (old.isEmpty()) return emptyList()
        fun literal(text: String) = "'${text.replace("'", "''")}'"
        val names = old.joinToString(", ") { literal(it.first.lowercase()) }
        return when (ddl) {
            is OracleDdl -> old.sortedBy { listOf("TRIGGER", "INDEX", "TABLE").indexOf(it.second) }.map { (name, type) ->
                when (type) {
                    "TRIGGER" -> "DROP TRIGGER IF EXISTS $name"
                    "TABLE" -> "DROP TABLE IF EXISTS $name PURGE"
                    else -> "DROP INDEX IF EXISTS $name"
                }
            } + "DELETE FROM ${ddl.catalog} WHERE LOWER(object_name) IN ($names) OR LOWER(JSON_VALUE(details, '$.labelled')) IN ($names)"
            else -> old.map { (name, _) -> "DROP INDEX IF EXISTS $name" } + "DELETE FROM ${ddl.catalog} WHERE LOWER(object_name) IN ($names)"
        }
    }

    /** The layout of a graph engine's store, which holds its own values: a stamp with no `layout` holds it. */
    const val GRAPH_ENGINE_LAYOUT = 1

    /** The engines, as a stamp names them, whose store layout this module defines ([ValueLayout.LAYOUT]). */
    val SQL_ENGINES = setOf("POSTGRES", "ORACLE", "SQLITE")

    /** How a message names each engine, by the name its stamp gives it. */
    internal val ENGINE_NAMES = mapOf(
        "POSTGRES" to PostgresDdl.ENGINE, "ORACLE" to "Oracle", "SQLITE" to "SQLite",
        "NEO4J" to "Neo4j", "MEMGRAPH" to "Memgraph", "FALKORDB" to "FalkorDB",
    )

    /** Drivine's own nodes, which a graph that holds no value of its own holds alone: the store's stamp and the schema marker. */
    internal val OWN_LABELS = listOf(StoreStamp.LABEL, SchemaMarker.LABEL)

    /** On Postgres, whether the node [alias] is one of Drivine's own ([OWN_LABELS]). */
    internal fun postgresOwn(alias: String) = OWN_LABELS.joinToString(" OR ", "(", ")") { PostgresSql.hasLabel(alias, it) }

    /** On Oracle, whether the node [alias] is one of Drivine's own ([OWN_LABELS]). */
    internal fun oracleOwn(alias: String) = OWN_LABELS.joinToString(" OR ", "(", ")") { OracleSql.carriesLabel(alias, it) }

    /** On SQLite, whether the node [alias] is one of Drivine's own ([OWN_LABELS]). */
    internal fun sqliteOwn(alias: String) = OWN_LABELS.joinToString(" OR ", "(", ")") { SqliteSql.hasLabel(alias, it) }
}
