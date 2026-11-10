package org.drivine.cypher.store.plan

import org.drivine.DrivineInternalApi
import org.drivine.cypher.store.schema.StoreError
import org.drivine.schema.EnsureResult
import org.drivine.schema.SchemaItemSpec

/**
 * What came of a plan on one database, for the library to log and to throw as drivine4j's schema manager would.
 *
 * The library logs [log] as it is, line for line, under each line's logger, so all three libraries log alike. Then:
 *
 * - where [rethrown] is given, it throws the error the store gave for that action, as it got it: an error drivine4j's
 *   schema manager doesn't catch (DDL the store refused for no reason the planner reads), which ends the plan there;
 * - otherwise, where [failure] is given, it throws it as its own error (drivine4j's `DrivineException`): owners that
 *   failed, the operation refused before anything was applied, or an [Operation.Item] or [Operation.Remove] that failed;
 * - otherwise the operation succeeded, and [owners] and [item] say how.
 *
 * @param owners each owner applied, in the order applied
 * @param item what an [Operation.Item] gave
 * @param log every line drivine4j logs at INFO and above, in order
 */
@DrivineInternalApi
data class DatabaseReport(
    val database: String,
    val owners: List<OwnerReport> = emptyList(),
    val item: ItemReport? = null,
    val failure: String? = null,
    val rethrown: Rethrown? = null,
    val log: List<LogLine> = emptyList(),
)

/**
 * One owner's part of a plan: what each of its items gave ([results], in the order applied), the indexes it made or
 * found that aren't built ([unbuilt]), the keys its marker recorded that it no longer declares ([orphans]), the marker it
 * wrote, or null where it wrote none, and why it failed, where it did.
 *
 * For an [Operation.Remove], [removed] says what the removal did, and [results] is empty.
 */
@DrivineInternalApi
data class OwnerReport(
    val owner: String?,
    val results: List<ItemOutcome> = emptyList(),
    val unbuilt: List<UnbuiltIndex> = emptyList(),
    val orphans: List<String> = emptyList(),
    val marker: WrittenMarker? = null,
    val failure: String? = null,
    val removed: Removal? = null,
)

/** What applying [spec] gave. */
@DrivineInternalApi
data class ItemOutcome(val spec: SchemaItemSpec, val result: EnsureResult)

/** An index made or found that isn't built: its build failed, or it is [stillBuilding] when the wait ran out. [message] names it and gives the store's reason. */
@DrivineInternalApi
data class UnbuiltIndex(val spec: SchemaItemSpec, val stillBuilding: Boolean, val message: String)

/** The marker written: the [version] token recorded, null for none, and the inventory's [items] as the marker holds them. */
@DrivineInternalApi
data class WrittenMarker(val version: String?, val items: String)

/** What a removal did on the database: how many of its items it [dropped], and which it [kept], as another declares them. */
@DrivineInternalApi
data class Removal(val dropped: Int, val kept: List<SchemaItemSpec>)

/** What an [Operation.Item] gave: the [result] of an ensure or a recreate, or whether a drop [dropped] anything. */
@DrivineInternalApi
data class ItemReport(val result: EnsureResult? = null, val dropped: Boolean? = null)

/**
 * The action whose failure ends the plan, the error the store gave for it, and the [round] it ran in, counted from 0, which
 * it was the last action of: the library throws the error it got there, as it got it.
 */
@DrivineInternalApi
data class Rethrown(val action: PlanAction, val error: StoreError, val round: Int)

/** A line drivine4j logs: its [level], the class it logs under ([logger], `SchemaManager`, `IndexManager` or `ConstraintManager`), and its [message]. */
@DrivineInternalApi
data class LogLine(val level: LogLevel, val logger: String, val message: String)

/** The levels a [LogLine] is at. */
@DrivineInternalApi
enum class LogLevel { INFO, WARN, ERROR }
