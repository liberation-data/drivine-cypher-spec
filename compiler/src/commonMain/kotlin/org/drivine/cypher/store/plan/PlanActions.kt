package org.drivine.cypher.store.plan

import org.drivine.DrivineInternalApi
import org.drivine.cypher.store.ddl.DdlStatement
import org.drivine.cypher.store.schema.StoreError

/**
 * What the library runs for the planner: a round's actions, in order, stopping at the first that fails. Every statement
 * is the module's own, from `store.ddl` and `store.marker`, with what it binds. The library runs each where drivine4j
 * runs it, which [Purpose] says.
 */
@DrivineInternalApi
sealed interface PlanAction {

    /**
     * A statement whose [rows][PlanOutcome.Rows] the planner reads, after waiting [delayMillis] (a poll of a store that
     * builds in the background). [compiled] is a Cypher statement a SQL store runs compiled
     * ([org.drivine.cypher.store.marker.MarkerStatements.SCOPES_CYPHER] on Postgres and Oracle); every other is in the
     * store's own language.
     */
    data class Query(
        val statement: String,
        val parameters: Map<String, Any?> = emptyMap(),
        val purpose: Purpose,
        val delayMillis: Long = 0,
        val compiled: Boolean = false,
    ) : PlanAction

    /**
     * A statement run for what it does: DDL in auto-commit, on a schema connection of its own, as drivine4j's
     * `SchemaExecutor` runs it (a FalkorDB [DdlStatement.Native] as a command of its driver), or a marker's, as its
     * auto-commit manager runs it. A DDL statement, or one that sets a record's aliases, tells the library's own caches
     * that the store's schema changed, whether it ran or failed, as drivine4j's `SchemaGrammar.changed()` in a `finally`.
     */
    data class Execute(
        val statement: DdlStatement,
        val parameters: Map<String, Any?> = emptyMap(),
        val purpose: Purpose,
        val retry: Retry = Retry.ONCE,
    ) : PlanAction

    /**
     * Neo4j's wait on the server for the index named in [parameters] (`name`) to be built, for at most `timeout` whole
     * seconds ([org.drivine.cypher.store.schema.StoreSchema.awaitIndexStatement]). Its rows are not read; it fails when
     * the build failed or did not end in time.
     */
    data class AwaitIndex(val statement: String, val parameters: Map<String, Any?>) : PlanAction

    /**
     * Reads the store's record of which lowercased copies every node of a label has, as drivine4j's
     * `LowerCopyCatalog.stored()` reads it before a fill of [properties] on the nodes of [label]: on a schema connection,
     * after what its statements need made (drivine4j's `prepare`, run once). It is answered [PlanOutcome.Rows], each row a
     * record as the list of its label and its property; the planner reads those of [label] and [properties]. Its
     * statements stay the library's for now. A read that fails is the store's error, which ends the plan.
     */
    data class ReadLowerCopies(val label: String, val properties: List<String>) : PlanAction

    /**
     * Fills in the lowercased copies of [properties] on the nodes of [label], and records that every node has them, as
     * drivine4j's `LowerCopyCatalog.fillIn` does, through the auto-commit manager; the planner leaves out those
     * [ReadLowerCopies] found recorded. Its statements stay the library's for now.
     */
    data class FillLowerCopies(val label: String, val properties: List<String>) : PlanAction

    /**
     * The marker's merge ([org.drivine.cypher.store.marker.MarkerStatements.merge]) of the owner [scope], with what it
     * binds. A failed merge is given again once by the planner, as drivine4j's `SchemaVersionStore.record` gives it: a
     * second process may have made the marker meanwhile, and a second merge finds it.
     */
    data class WriteMarker(val scope: String, val statement: String, val parameters: Map<String, Any?>) : PlanAction
}

/** Why the planner runs a statement, which says where the library runs it. */
@DrivineInternalApi
enum class Purpose {
    /** What the marker's statements need made first ([org.drivine.cypher.store.marker.MarkerStatements.prepare]), on a schema connection. */
    PREPARE,

    /** One of the marker's statements, on the auto-commit manager. */
    MARKER,

    /** The scope of every marker, on the auto-commit manager. */
    SCOPES,

    /** A listing of indexes or constraints, on a schema connection. A listing that fails reads as none. */
    LISTING,

    /** DDL that makes or drops an index or a constraint, on a schema connection. */
    DDL,

    /** What sets the aliases a SQL store's record keeps, on a schema connection. */
    ALIASES,

    /** Neo4j's state of an index, read when its wait fails for another reason than its timeout. A read that fails reads as no state. */
    STATE,

    /** The sample of the keys a uniqueness constraint would refuse. A sample that fails reads as no data. */
    SAMPLE,
}

/** How an [PlanAction.Execute] is given again where the store turns it away. */
@DrivineInternalApi
enum class Retry {
    /** Run once: a failure is the planner's to read. */
    ONCE,

    /** Run again after a pause where the store turns it away as contended, as drivine4j's `DdlContention` runs a store's `ensuring`. */
    CONTENDED,
}

/** What came of an action, as the library gives it back. */
@DrivineInternalApi
sealed interface PlanOutcome {

    /** The rows of a [PlanAction.Query] or a [PlanAction.ReadLowerCopies], as plain values: maps, lists, text, numbers, booleans and null. */
    data class Rows(val rows: List<Any?>) : PlanOutcome

    /** An action that ran, whose rows aren't read. */
    data object Ran : PlanOutcome

    /** An action that failed, with the chain of its errors. The library keeps the error itself, which a [DatabaseReport.rethrown] asks it to throw. */
    data class Failed(val error: StoreError) : PlanOutcome
}

/** What [SchemaPlanner.start] and [SchemaPlanner.resume] give: actions to run, or the plan done. */
@DrivineInternalApi
sealed interface PlanStep {

    /** The [actions] to run, in order, stopping at the first that fails; their outcomes go to [SchemaPlanner.resume] with [state]. */
    data class Run(val actions: List<PlanAction>, val state: PlannerState) : PlanStep

    /** The plan is done, and [report] says what came of it. */
    data class Done(val report: DatabaseReport) : PlanStep
}
