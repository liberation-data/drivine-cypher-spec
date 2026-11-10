package org.drivine.cypher.store.marker

import org.drivine.DrivineInternalApi
import org.drivine.schema.SchemaItemSpec
import org.drivine.schema.UniquenessConstraintSpec

/**
 * The schema marker: one node of the graph for each owner of a schema catalog, `(:_DrivineSchema {scope, version, items,
 * appliedAt})`, holding the version token the owner last applied, the inventory of the items it applied, and when. Every
 * library reads and writes it, so a store one library made is ensured alike by another; what the marker holds is written
 * once, here, as drivine4j's `SchemaVersionStore` writes it, and pinned by the spec's `marker.yaml`.
 *
 * The version token is how a library detects a change that the store's own catalog can't show, such as an embedding model
 * swapped for another of the same size. The inventory is what ties the items to their owner: a recreate acts on what it
 * recorded, and an item no longer declared is found as an orphan rather than left unattributed.
 *
 * The marker is a node, and not a table, so that it is the same on the graph engines and the SQL stores, and travels with
 * a dump. Its inventory is one text property, the keys joined by newlines ([MarkerInventory.items]), which every store
 * keeps as it keeps any text.
 */
@DrivineInternalApi
object SchemaMarker {

    /** The marker's label, reserved to Drivine. */
    const val LABEL = "_DrivineSchema"

    /** The property that keys a marker to its owner, which [CONSTRAINT] makes unique, so a `MERGE` on it is safe from races. */
    const val SCOPE_PROPERTY = "scope"

    /** The scope of the owner with no name: the marker's only scope before owners were named, so an older store reads alike. */
    const val DEFAULT_SCOPE = "schema"

    /** The property the version token is kept under: absent where the owner sets none. */
    const val VERSION = "version"

    /** The property the inventory is kept under, as [MarkerInventory.items] writes it. */
    const val ITEMS = "items"

    /** The property the moment the marker was last written is kept under, which the newest marker of a scope is found by. */
    const val APPLIED_AT = "appliedAt"

    /** What separates the inventory's entries. */
    const val SEPARATOR = "\n"

    /** What an entry for an item ensured at runtime begins with. An inventory key begins with its kind, never with this. */
    const val RUNTIME_PREFIX = "runtime:"

    /** What an entry for an item ensured at runtime, and to be recreated when next ensured, begins with. */
    const val STALE_RUNTIME_PREFIX = "runtime-stale:"

    /** The separator of the version tokens of several catalogs of one owner, as [versionToken] joins them. */
    const val VERSION_SEPARATOR = "|"

    /**
     * What keeps one marker for each scope: `MERGE` is atomic across transactions only on a key a uniqueness constraint
     * backs. It spans every scope, so it is made once and shared by every owner.
     */
    val CONSTRAINT: UniquenessConstraintSpec = UniquenessConstraintSpec(LABEL, SCOPE_PROPERTY)

    /** The scope of the owner [owner]: its name, or [DEFAULT_SCOPE] for the owner with none. */
    fun scopeFor(owner: String?): String = owner ?: DEFAULT_SCOPE

    /**
     * The version token of an owner whose catalogs set [versions], null for a catalog that sets none: each token once,
     * sorted, joined by [VERSION_SEPARATOR]; null where none sets one. Sorted as Java sorts strings, by their UTF-16 code
     * units, which Kotlin's `compareTo` is on every target, so the token does not change with the order catalogs are given.
     */
    fun versionToken(versions: List<String?>): String? {
        val distinct = versions.filterNotNull().distinct().sorted()
        return if (distinct.isEmpty()) null else distinct.joinToString(VERSION_SEPARATOR)
    }

    /** The inventory key of each of [specs], in order, as the marker records them: see [SchemaItemSpec.inventoryKey]. */
    fun keys(specs: List<SchemaItemSpec>): List<String> = specs.map { it.inventoryKey }
}

/**
 * What a marker records: the inventory [keys] of every item applied, in the order they were recorded, the keys of those
 * that were ensured at runtime rather than declared by a catalog ([runtime]), and the keys of those to be recreated when
 * next ensured, because their owner was recreated while their specs were not known ([stale]).
 *
 * A key is written once for each time it is in [keys], marked with [SchemaMarker.STALE_RUNTIME_PREFIX] where it is in
 * [stale], with [SchemaMarker.RUNTIME_PREFIX] where it is in [runtime] only, and as it is otherwise. A key in [stale] is
 * read back in [runtime] too, as a stale item is always a runtime one; a key of [runtime] or [stale] not in [keys] is not
 * written at all.
 */
@DrivineInternalApi
data class MarkerInventory(
    val keys: List<String>,
    val runtime: Set<String> = emptySet(),
    val stale: Set<String> = emptySet(),
) {

    /** The inventory as the marker's `items` property holds it, before a store's mark ([MarkerStatements.storedItems]). */
    val items: String
        get() = keys.joinToString(SchemaMarker.SEPARATOR) { key ->
            when (key) {
                in stale -> SchemaMarker.STALE_RUNTIME_PREFIX + key
                in runtime -> SchemaMarker.RUNTIME_PREFIX + key
                else -> key
            }
        }

    companion object {

        /** An inventory with nothing recorded, which is not the same as a marker with no inventory. */
        val EMPTY = MarkerInventory(emptyList())

        /**
         * The inventory [items] holds, as [MarkerInventory.items] writes it. An empty text is an empty inventory, and not
         * one empty key: an inventory of nothing is written as an empty text. A marker with no `items` at all, written
         * before inventories were kept, has no inventory, and is not read here: a library reads it as unknown, so that
         * nothing is taken for an orphan.
         */
        fun parse(items: String): MarkerInventory {
            if (items.isEmpty()) return EMPTY
            val keys = mutableListOf<String>()
            val runtime = mutableSetOf<String>()
            val stale = mutableSetOf<String>()
            for (entry in items.split(SchemaMarker.SEPARATOR)) {
                when {
                    entry.startsWith(SchemaMarker.STALE_RUNTIME_PREFIX) -> entry.removePrefix(SchemaMarker.STALE_RUNTIME_PREFIX).also {
                        keys += it
                        runtime += it
                        stale += it
                    }
                    entry.startsWith(SchemaMarker.RUNTIME_PREFIX) -> entry.removePrefix(SchemaMarker.RUNTIME_PREFIX).also {
                        keys += it
                        runtime += it
                    }
                    else -> keys += entry
                }
            }
            return MarkerInventory(keys, runtime, stale)
        }
    }
}
