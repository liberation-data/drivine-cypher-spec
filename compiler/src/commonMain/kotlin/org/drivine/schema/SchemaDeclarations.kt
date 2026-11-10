package org.drivine.schema

import org.drivine.DrivineInternalApi
import org.drivine.cypher.CompilerException

/**
 * How the declarations of one owner's catalogs become the items it ensures: drivine4j's `SchemaCatalog` merge
 * (`deduplicate` and `resolveDeclarations`), in common Kotlin, so that a catalog declared alike in any library makes the
 * same items, and a conflict is refused with the same message. Pinned by the spec's `store.yaml` (`declarations`).
 *
 * The version token the merge keeps, drivine4j's `combineVersions`, is [SchemaMarker.versionToken]
 * [org.drivine.cypher.store.marker.SchemaMarker.versionToken], which the marker records.
 */
@DrivineInternalApi
object SchemaDeclarations {

    /** What identifies a declared item: its kind, its label, and the set of the properties it is made over. */
    data class Key(val kind: SchemaItemKind, val label: String, val properties: Set<String>)

    /**
     * Collapses duplicate declarations, in the order each [Key] is first declared. The key is the kind, the label and the
     * set of the properties the item is made over ([made]), so one that ignores case is another item than one that doesn't.
     *
     * Declarations for one key must agree on everything portable — dimensions, similarity, name, common HNSW parameters
     * and the order of the properties — and disagreement there is a conflict, as it always has been in drivine4j (e.g.
     * two fragments declaring different vector dimensions for the same property).
     *
     * The one permitted difference is **engine-specific options**. A vector index that is both queried and tuned is
     * declared twice: `@VectorIndex` stays on the fragment, as a library resolves `loadNearest`'s index from it, while
     * engine options can only be written in a hand-made catalog spec. The spec carrying engine options wins whole, with no
     * merging field by field; two that both pin engine options and disagree are a conflict.
     *
     * A constraint's [ConstraintSpec.nodeIdProperty] may differ too, being no part of the item: a constraint read from a
     * fragment and the same one declared by hand are one, and the fragment's, which knows the property its nodes are
     * identified by, wins.
     *
     * @throws CompilerException for declarations of one key that conflict, naming the key and each of them, with
     *   drivine4j's message (drivine4j throws its `DrivineException`)
     */
    fun deduplicate(specs: List<SchemaItemSpec>): List<SchemaItemSpec> =
        specs.groupBy { Key(it.kind, it.label, it.made().properties.toSet()) }
            .map { (key, group) -> resolveDeclarations(key, group) }

    /** The one item [group], the declarations of [key], make, as [deduplicate] says. */
    fun resolveDeclarations(key: Key, group: List<SchemaItemSpec>): SchemaItemSpec {
        fun conflict(reason: String, offenders: List<SchemaItemSpec>): Nothing = throw CompilerException(
            "Conflicting schema declarations for ${key.kind} on ${key.label}${key.properties.toList()} " +
                "($reason): " + offenders.joinToString("; ")
        )

        // Compared with engine options stripped: every declaration must agree on the portable one first.
        val portable = group.map(::declared).distinct()
        if (portable.size > 1) {
            conflict("they disagree on portable parameters", portable)
        }

        val pinning = group.filterIsInstance<VectorIndexSpec>()
            .filter { it.engineOptions.isNotEmpty() }
            .distinct()
        if (pinning.size > 1) {
            conflict("more than one declaration pins engine options", pinning)
        }
        return pinning.firstOrNull()
            // A constraint read from a fragment knows the property its nodes are identified by.
            ?: group.firstOrNull { it is UniquenessConstraintSpec && it.nodeIdProperty != null }
            ?: group.first()
    }

    /**
     * What [spec] declares of the item itself: without engine options, which only one declaration may pin, and without
     * the property a violation's sample names nodes by, which a hand-written declaration does not know.
     */
    private fun declared(spec: SchemaItemSpec): SchemaItemSpec = when (spec) {
        is VectorIndexSpec -> spec.withoutEngineOptions()
        is UniquenessConstraintSpec -> spec.copy(nodeIdProperty = null)
        else -> spec
    }
}
