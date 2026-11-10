package org.drivine.cypher.store.plan

import org.drivine.DrivineInternalApi
import org.drivine.cypher.store.marker.MarkerStatements
import org.drivine.cypher.store.schema.StoreSchema
import org.drivine.schema.SchemaDeclarations
import org.drivine.schema.SchemaItemSpec
import org.drivine.schema.made

/**
 * drivine4j's schema manager (its `SchemaManager`, `IndexManager` and `ConstraintManager`), as a planner with no store:
 * it decides what to run on a store, and what came of it, from what the store gives back, and the library runs what it
 * decides. Every library that runs it decides alike, as drivine4j does, of a store any of them made.
 *
 * A plan runs in rounds. [start] gives the first round's actions, or [PlanStep.Done] where there is nothing to run. The
 * library runs a round's actions in order, stopping at the first that fails, and gives what came of each to [resume],
 * which gives the next round, until it gives [PlanStep.Done]. The first round reads what the plan decides from: the
 * owners' markers and the store's listings; the rounds after it run the plan, owner by owner, item by item, reading again
 * what drivine4j reads after its own DDL runs (an item found after it is made, a build polled until it ends). A store
 * where everything matches takes two rounds.
 *
 * The planner keeps nothing between rounds but [PlannerState], and decides each round again from the request, over the
 * outcomes so far: so the decisions are a pure function of the request and of what the store said, and a state crosses
 * any boundary as JSON.
 *
 * Where it reads differs from drivine4j: a listing is read once and used until the plan's own DDL runs, where drivine4j
 * reads it again for each item, and the markers and the listings are read in the first round, before anything is made.
 * What is decided and written is drivine4j's, statement for statement, and the log lines are its, word for word.
 *
 * @param store what the planner knows of the store: its statements, how it reads their rows, and what its errors say
 * @param markers the store's marker statements
 */
@DrivineInternalApi
class SchemaPlanner(
    private val store: StoreSchema,
    private val markers: MarkerStatements = MarkerStatements.of(store.ddl),
) {

    /**
     * Why [request] is refused before anything is applied: the conflict between two declarations, of one owner or of two,
     * as drivine4j's `SchemaManager` refuses it, with its message, or of two items an [Operation.Ensure] or an
     * [Operation.Remove] is given, as drivine4j's `SchemaCatalog.of` refuses them, first. Null where nothing conflicts, or the operation declares nothing to
     * conflict ([Operation.Item]). A library checks every database before it starts on any of an [Operation.Ensure] or an
     * [Operation.Remove], and before it takes a remove's items from its runtime items, so a conflict leaves them all as
     * they were.
     */
    fun validate(request: PlanRequest): String? = try {
        when (val operation = request.operation) {
            is Operation.Enforce -> Declarations.of(request, operation.owners)
            is Operation.RecreateAll -> Declarations.of(request, operation.owners)
            is Operation.Ensure -> Declarations.merged(operation.items).also { Declarations.of(request, Owners.One(operation.owner)) }
            is Operation.Remove -> Declarations.merged(operation.items)
            is Operation.Item -> null
        }
        null
    } catch (refusal: PlanRefusal) {
        refusal.message
    }

    /** The first round of [request], planned at [now], the library's clock in milliseconds since the epoch. */
    fun start(request: PlanRequest, now: Long): PlanStep = step(PlannerState(request, emptyList()), now)

    /**
     * The next round, after the last round of [state] gave [outcomes], one for each of its actions that ran, in order: all
     * of them, or those up to and with the first that failed. [now] is the library's clock. A [PlanAction.Query] and a
     * [PlanAction.ReadLowerCopies] are answered [PlanOutcome.Rows] and every other action [PlanOutcome.Ran], or either
     * [PlanOutcome.Failed]; an outcome that doesn't fit its action is refused.
     */
    fun resume(state: PlannerState, outcomes: List<PlanOutcome>, now: Long): PlanStep {
        val last = requireNotNull(state.rounds.lastOrNull()) { "A plan that has not started has nothing to resume" }
        require(last.outcomes.isEmpty()) { "The last round of the state was resumed already" }
        require(outcomes.isNotEmpty() && outcomes.size <= last.actions.size) {
            "A round of ${last.actions.size} action(s) gives back between one and ${last.actions.size} outcome(s), not ${outcomes.size}"
        }
        require(outcomes.size == last.actions.size || outcomes.last() is PlanOutcome.Failed) {
            "A round stops early only at an action that failed"
        }
        require(outcomes.dropLast(1).none { it is PlanOutcome.Failed }) { "A round stops at the first action that fails" }
        outcomes.forEachIndexed { i, outcome ->
            val action = last.actions[i]
            val rows = action is PlanAction.Query || action is PlanAction.ReadLowerCopies
            require(outcome is PlanOutcome.Failed || (outcome is PlanOutcome.Rows) == rows) {
                "Action $i of round ${state.rounds.size - 1}, $action, is answered " + (if (rows) "Rows" else "Ran") + " or Failed, not $outcome"
            }
        }
        return step(state.copy(rounds = state.rounds.dropLast(1) + last.copy(outcomes = outcomes)), now)
    }

    private fun step(state: PlannerState, now: Long): PlanStep {
        val replay = Replay(state.rounds, now)
        val report = try {
            SchemaProgram(store, markers, state.request, replay).run()
        } catch (_: Suspend) {
            null
        }
        if (report != null && replay.pending.isEmpty()) return PlanStep.Done(report)
        return PlanStep.Run(replay.pending.toList(), state.copy(rounds = state.rounds + PlanRound(now, replay.pending.toList())))
    }

    companion object {

        /**
         * The library's runtime items after [operation], given to the [databases] its call names (null for every
         * database), as drivine4j's `SchemaManager` changes them; [runtime] where the operation changes none.
         *
         * - An [Operation.Ensure] adds its items, merged as drivine4j's catalog merges them (`SchemaDeclarations.deduplicate`,
         *   which keeps the declaration that pins engine options, or that names a constraint's node id property), to the
         *   owner's items on the same [databases], each replacing the one of its identity where there is one, in that one's
         *   place, and after them where there isn't. The library keeps what this gives only once [validate] has passed
         *   every database planned with it, and plans them with it then.
         * - An [Operation.Remove] takes the items of its items' identities from the owner's items on every target where
         *   [databases] is null, and on the same [databases] where it isn't, leaving out a catalog it empties. The library
         *   keeps what this gives before it plans any database, and plans each with it.
         *
         * An ensure or a remove of no items, or of items that conflict, which [validate] refuses, changes nothing.
         */
        fun runtimeAfter(operation: Operation, runtime: List<RuntimeCatalog>, databases: Set<String>? = null): List<RuntimeCatalog> {
            fun identity(spec: SchemaItemSpec) = SchemaDeclarations.Key(spec.kind, spec.label, spec.made().properties.toSet())
            return when (operation) {
                is Operation.Ensure -> {
                    val given = try {
                        Declarations.merged(operation.items)
                    } catch (_: PlanRefusal) {
                        return runtime
                    }
                    if (given.isEmpty()) return runtime
                    val at = runtime.indexOfFirst { it.owner == operation.owner && it.databases == databases }
                    val items = linkedMapOf<SchemaDeclarations.Key, SchemaItemSpec>()
                    runtime.getOrNull(at)?.items?.forEach { items[identity(it)] = it }
                    given.forEach { items[identity(it)] = it }
                    val ensured = RuntimeCatalog(operation.owner, databases, items.values.toList())
                    if (at < 0) runtime + ensured else runtime.mapIndexed { i, it -> if (i == at) ensured else it }
                }
                is Operation.Remove -> {
                    try {
                        Declarations.merged(operation.items)
                    } catch (_: PlanRefusal) {
                        return runtime
                    }
                    val removed = operation.items.map(::identity).toSet()
                    runtime.map {
                        if (it.owner == operation.owner && (databases == null || it.databases == databases)) {
                            it.copy(items = it.items.filter { spec -> identity(spec) !in removed })
                        } else {
                            it
                        }
                    }.filter { it.items.isNotEmpty() }
                }
                is Operation.Enforce, is Operation.RecreateAll, is Operation.Item -> runtime
            }
        }

        /** The [PlanRequest.runtime] of [database]: the [runtime] items that apply to it, a catalog for each owner and target. */
        fun runtimeOn(database: String, runtime: List<RuntimeCatalog>): List<DeclaredCatalog> =
            runtime.filter { it.includes(database) }.map { DeclaredCatalog(it.owner, null, it.items) }
    }
}

/** What stops the program where it needs an outcome it hasn't been given: the round so far is run. */
internal class Suspend : Throwable()

/**
 * The outcomes of the rounds so far, given to the program again in the order it asks for them. An action past them is
 * planned: one whose outcome the program needs ([ask]) ends the round there, and one whose outcome it doesn't ([run]) is
 * taken to have run, the program going on, as the library stops at the first that fails and the next round then decides
 * again from its failure.
 */
internal class Replay(rounds: List<PlanRound>, private val now: Long) {
    private val actions = mutableListOf<PlanAction>()
    private val outcomes = mutableListOf<PlanOutcome>()
    private val nows = mutableListOf<Long>()
    private val roundOf = mutableListOf<Int>()

    init {
        rounds.forEachIndexed { round, it ->
            it.outcomes.forEachIndexed { i, outcome ->
                actions += it.actions[i]
                outcomes += outcome
                nows += it.now
                roundOf += round
            }
        }
    }

    private var cursor = 0

    /** What this round plans, in order. */
    val pending = mutableListOf<PlanAction>()

    /** The round the last outcome given was of. */
    var lastRound: Int = -1
        private set

    /**
     * The library's clock where the program is: the moment of the round whose outcome it reads next, as that round was
     * planned when the program was first there; past the outcomes, the moment of this round.
     */
    fun clock(): Long = if (pending.isEmpty() && cursor < outcomes.size) nows[cursor] else now

    /** The outcome of [action], given already; or null, with it planned. */
    private fun next(action: PlanAction): PlanOutcome? {
        if (pending.isNotEmpty() || cursor >= outcomes.size) return null
        check(actions[cursor] == action) { "The state does not match its request: it ran ${actions[cursor]} where the plan is at $action" }
        lastRound = roundOf[cursor]
        return outcomes[cursor++]
    }

    /** The outcome of [action], which the program reads: given, or the round ends here. */
    fun ask(action: PlanAction): PlanOutcome = next(action) ?: run {
        pending += action
        throw Suspend()
    }

    /** The outcome of [action], given; or [PlanOutcome.Ran], with it planned, the program going on. */
    fun run(action: PlanAction): PlanOutcome = next(action) ?: PlanOutcome.Ran.also { pending += action }

    /** The outcome of [action], given; or null, with it planned, for the program to end the round once it has planned the rest it reads first. */
    fun prefetch(action: PlanAction): PlanOutcome? = next(action) ?: null.also { pending += action }

    /** Ends the round where any of what [prefetch] planned is still to run. */
    fun endPrefetch() {
        if (pending.isNotEmpty()) throw Suspend()
    }
}
