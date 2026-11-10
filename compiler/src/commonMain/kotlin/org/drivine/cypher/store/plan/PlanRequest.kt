package org.drivine.cypher.store.plan

import org.drivine.DrivineInternalApi
import org.drivine.schema.SchemaItemSpec
import org.drivine.schema.SchemaPolicy

/**
 * What a schema manager is asked to do on one database: the [operation], over the catalogs that apply there, under a
 * [policy]. The library resolves which databases a catalog applies to, and asks once for each, in its own order; the
 * planner decides everything that happens on one.
 *
 * @param database the database's name, as the log lines and failures name it
 * @param operation what is asked
 * @param catalogs the startup catalogs that apply to [database], each as one catalog of the library declares it, in the
 *   order the library was given them
 * @param runtime the items ensured at runtime that apply to [database], a catalog for each owner and target, as drivine4j's
 *   `SchemaManager` keeps them: for an [Operation.Ensure], with the items being ensured already among them, and for an
 *   [Operation.Remove], with the items being removed already taken out
 * @param policy how drift, violations and the rest are treated
 * @param buildTimeoutMillis how long an index built in the background (Neo4j, FalkorDB) is waited for, each time it is
 *   applied, before it is taken as still being built: drivine4j's `indexBuildTimeout`, 60 seconds unless given
 */
@DrivineInternalApi
data class PlanRequest(
    val database: String,
    val operation: Operation,
    val catalogs: List<DeclaredCatalog> = emptyList(),
    val runtime: List<DeclaredCatalog> = emptyList(),
    val policy: SchemaPolicy = SchemaPolicy(),
    val buildTimeoutMillis: Long = DEFAULT_BUILD_TIMEOUT_MILLIS,
) {
    init {
        require(buildTimeoutMillis >= 0) { "buildTimeoutMillis must not be negative: $buildTimeoutMillis" }
    }

    companion object {
        /** drivine4j's `IndexManager.DEFAULT_BUILD_TIMEOUT`: what an index made outside a schema manager's own wait is waited for, too. */
        const val DEFAULT_BUILD_TIMEOUT_MILLIS: Long = 60_000
    }
}

/**
 * One catalog as declared: its [owner] (null for the owner with no name, whose marker's scope is `schema`), the [version]
 * token it sets (null for none; never one for a catalog of items ensured at runtime), and its [items], in the order
 * declared. A catalog with no items applies nothing, and is passed over as drivine4j passes over an empty one.
 */
@DrivineInternalApi
data class DeclaredCatalog(
    val owner: String?,
    val version: String? = null,
    val items: List<SchemaItemSpec>,
)

/** Which owners an [Operation.Enforce] or an [Operation.RecreateAll] applies: all, or one, by name (null for the unnamed one). */
@DrivineInternalApi
sealed interface Owners {

    /** Whether the owner [name] is among these. */
    fun includes(name: String?): Boolean

    /** Every owner. */
    data object All : Owners {
        override fun includes(name: String?): Boolean = true
    }

    /** The owner [name] alone, null for the unnamed one. */
    data class One(val name: String?) : Owners {
        override fun includes(name: String?): Boolean = name == this.name
    }
}

/**
 * What a schema manager is asked, as drivine4j's `SchemaManager`, `IndexManager` and `ConstraintManager` are: each maps to
 * one of their calls.
 */
@DrivineInternalApi
sealed interface Operation {

    /** `SchemaManager.enforce()` and `enforce(name)`: every item of the owners, made where missing, recreated where their version changed. */
    data class Enforce(val owners: Owners = Owners.All) : Operation

    /** `SchemaManager.recreateAll()` and `recreateAll(name)`: every item of the owners dropped and made again, co-owned ones too. */
    data class RecreateAll(val owners: Owners = Owners.All) : Operation

    /**
     * `SchemaManager.ensure(catalog)`: the [items] given to [owner] at runtime, applied as its others are, its version as
     * the marker records it. The library has added them to the [PlanRequest.runtime] items, and checked every database
     * with [SchemaPlanner.validate] first.
     */
    data class Ensure(val owner: String?, val items: List<SchemaItemSpec>) : Operation

    /** `SchemaManager.remove(catalog)`: the [items] taken from what [owner] declares, dropped where no one else declares them. */
    data class Remove(val owner: String?, val items: List<SchemaItemSpec>) : Operation

    /** One of the public `IndexManager` and `ConstraintManager` calls, on [spec] alone, with no marker. */
    data class Item(val call: ItemCall, val spec: SchemaItemSpec) : Operation
}

/** The public calls of drivine4j's `IndexManager` and `ConstraintManager` an [Operation.Item] is. */
@DrivineInternalApi
enum class ItemCall {
    /** `ensure`: made where missing, reported where it drifts or is refused; an index not built is a failure. */
    ENSURE,

    /** `recreate`: dropped where it is there, and made again. */
    RECREATE,

    /** `drop`: dropped where it is there. */
    DROP,
}
