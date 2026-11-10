package org.drivine.cypher.store.plan

import org.drivine.cypher.CompilerException
import org.drivine.schema.ConstraintSpec
import org.drivine.schema.IndexSpec
import org.drivine.schema.SchemaDeclarations
import org.drivine.schema.SchemaItemKind
import org.drivine.schema.SchemaItemSpec
import org.drivine.schema.UniquenessConstraintSpec
import org.drivine.schema.made

/** A request refused before anything is applied, with drivine4j's message. */
internal class PlanRefusal(override val message: String) : Exception(message)

/**
 * What one database is given, as drivine4j's `SchemaManager.plan` merges it: the catalogs that contribute, each owner's
 * items merged ([ownerSpecs], in the order owners first contribute, each owner's indexes before its constraints), the keys
 * more than one owner declares ([coOwnedKeys]), and for each owner the keys only its runtime items declare
 * ([runtimeOnlyKeys]). Merged over every owner on the database, even where one is applied, so that a co-owned item is
 * known as one, and a conflict with another owner is found.
 */
internal class Declarations private constructor(
    val contributing: List<DeclaredCatalog>,
    val ownerSpecs: Map<String?, List<SchemaItemSpec>>,
    val coOwnedKeys: Set<String>,
    val runtimeOnlyKeys: Map<String?, Set<String>>,
) {
    companion object {

        /**
         * The declarations of [request]; null where no catalog contributes on its database. A conflict within an owner,
         * or across owners where one of them is among [owners], is a [PlanRefusal].
         */
        fun of(request: PlanRequest, owners: Owners): Declarations? {
            val declared = request.catalogs.filter { it.items.isNotEmpty() }
            val ensured = request.runtime.filter { it.items.isNotEmpty() }
            val contributing = declared + ensured
            if (contributing.isEmpty()) return null
            val ownerSpecs = linkedMapOf<String?, List<SchemaItemSpec>>()
            for ((owner, catalogs) in contributing.groupBy { it.owner }) ownerSpecs[owner] = merged(catalogs.flatMap { it.items })
            conflictsAcrossOwners(request.database, ownerSpecs, owners)
            val runtimeOnlyKeys = ensured.groupBy { it.owner }.mapValues { (owner, catalogs) ->
                val atStartup = declared.filter { it.owner == owner }.flatMap { it.items }.map { it.inventoryKey }.toSet()
                catalogs.flatMap { it.items }.map { it.inventoryKey }.toSet() - atStartup
            }
            return Declarations(contributing, ownerSpecs, coOwnedKeys(ownerSpecs), runtimeOnlyKeys)
        }

        /** [specs] merged as drivine4j's `SchemaCatalog.of(specs).items` merges them: the indexes first, then the constraints. */
        fun merged(specs: List<SchemaItemSpec>): List<SchemaItemSpec> {
            val deduplicated = try {
                SchemaDeclarations.deduplicate(specs)
            } catch (e: CompilerException) {
                throw PlanRefusal(e.message ?: e.toString())
            }
            return deduplicated.filterIsInstance<IndexSpec>() + deduplicated.filterIsInstance<ConstraintSpec>()
        }

        /**
         * Refuses two owners' declarations of one kind, label and set of properties that are not the same, where one of
         * the owners is among [owners], so that a selective enforce isn't stopped by two owners it doesn't apply.
         */
        private fun conflictsAcrossOwners(database: String, ownerSpecs: Map<String?, List<SchemaItemSpec>>, owners: Owners) {
            class Owned(val owner: String?, val spec: SchemaItemSpec)
            val byIdentity = linkedMapOf<Triple<SchemaItemKind, String, Set<String>>, MutableList<Owned>>()
            ownerSpecs.forEach { (owner, specs) ->
                specs.forEach { spec ->
                    byIdentity.getOrPut(Triple(spec.kind, spec.label, spec.made().properties.toSet())) { mutableListOf() } += Owned(owner, spec)
                }
            }
            byIdentity.forEach { (identity, owned) ->
                // The property a constraint's violation sample names nodes by is no part of the item.
                val distinct = owned.map { (it.spec as? UniquenessConstraintSpec)?.copy(nodeIdProperty = null) ?: it.spec }.distinct()
                if (distinct.size > 1 && owned.any { owners.includes(it.owner) }) {
                    throw PlanRefusal(
                        "Conflicting schema declarations for ${identity.first} on " +
                            "${identity.second}${identity.third.toList()} on database '$database', across owners: " +
                            owned.joinToString("; ") { "'${it.owner ?: "schema"}' declares ${it.spec}" } +
                            ". Reconcile the declaration, or have only one owner declare this item."
                    )
                }
            }
        }

        /** The inventory keys more than one owner declares. */
        private fun coOwnedKeys(ownerSpecs: Map<String?, List<SchemaItemSpec>>): Set<String> =
            ownerSpecs.values
                .flatMap { specs -> specs.map { it.inventoryKey }.distinct() }
                .groupingBy { it }
                .eachCount()
                .filterValues { it > 1 }
                .keys
    }
}
