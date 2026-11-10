package org.drivine.cypher.store.plan

import org.drivine.DrivineInternalApi

/**
 * Where a plan has got to: the [request], and each round run so far, with what the library gave back for it. The last
 * round's [PlanRound.outcomes] are empty until [SchemaPlanner.resume] is given them.
 *
 * The planner keeps nothing else: [SchemaPlanner.resume] decides again from the request, round by round, over the
 * outcomes given, and so reaches where it left off. A state is a plain value, and [toJson] writes it as text that
 * [fromJson] reads back, so a library may hold it where it can't hold an object of the module's, as Python's wheel holds
 * it across the C ABI.
 */
@DrivineInternalApi
data class PlannerState(val request: PlanRequest, val rounds: List<PlanRound>) {

    /** The state as JSON, which [fromJson] reads back to an equal state. */
    fun toJson(): String = PlanJson.write(this)

    companion object {
        /** The state [text], written by [toJson]. */
        fun fromJson(text: String): PlannerState = PlanJson.readState(text)
    }
}

/**
 * One round: the moment [now] it was planned at, by the library's clock, the [actions] planned, and the [outcomes] the
 * library gave back, one for each action it ran, in order, the last a failure where it stopped at one.
 */
@DrivineInternalApi
data class PlanRound(val now: Long, val actions: List<PlanAction>, val outcomes: List<PlanOutcome> = emptyList())
