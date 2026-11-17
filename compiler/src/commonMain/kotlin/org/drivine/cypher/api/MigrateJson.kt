package org.drivine.cypher.api

import org.drivine.DrivineInternalApi
import org.drivine.cypher.json.ArrayNode
import org.drivine.cypher.json.CypherJson
import org.drivine.cypher.json.JsonNode
import org.drivine.cypher.json.ObjectNode
import org.drivine.cypher.store.ddl.SqlStoreDdl
import org.drivine.cypher.store.migrate.LayoutMigrator
import org.drivine.cypher.store.migrate.MigrationAction
import org.drivine.cypher.store.migrate.MigrationOutcome
import org.drivine.cypher.store.migrate.MigrationRequest
import org.drivine.cypher.store.migrate.MigrationState
import org.drivine.cypher.store.migrate.MigrationStep
import org.drivine.cypher.store.migrate.declarationsOf
import org.drivine.cypher.store.plan.PlanJson

/**
 * A store's layout migrated ([LayoutMigrator], liberation-data/drivine-cypher-spec#256) for a host with no Kotlin types, asked
 * of the JSON entry ([CompileJson]) a round at a time, as the schema plan is ([SchemaPlanJson]): the module decides, the host
 * runs each round's statements and gives back what came of each.
 *
 * A request names its SQL store, `{"store": "postgres", "graph": "g"}`, and one of:
 * - `"start": {"declarations": [{"label", "property", "kind"}, …], "batchSize": 1000, "dryRun": false, "keepInvalid": false}`:
 *   the first round (each may be left out: no declarations, and these defaults; a key it doesn't know is refused);
 * - `"resume": <state>, "outcomes": [<outcome>, …]`: the next round, after the last round of the state gave `outcomes`, one
 *   for each of its actions that ran, in order: all of them, or those up to and with the first that failed.
 *
 * Answered `{"run": [<action>, …], "state": <state>}`, or `{"done": <report>}` ([org.drivine.cypher.store.migrate.MigrationReport]).
 * An action is one of `{"type": "query", "statement", "parameters", "purpose", "compiled"}` (answered `{"type": "rows",
 * "rows": […]}`, each row the JSON object it gives, read as a map; where `compiled`, the statement is Cypher, compiled for
 * the store as any statement is), `{"type": "execute", …}` (run in auto-commit), `{"type": "transaction", "statements":
 * [<execute>, …]}` (all of them in one transaction, or none), `{"type": "prepare"}` (the store's tables made as at a first
 * connection, without the verdict), `{"type": "lock", "statement"}` (run on a connection of the host's own, in a transaction
 * held open until the migration is done) and `{"type": "assignStamp"}` (the stamp's `assignIfAbsent` with the host's own id
 * and moment, where the graph has no stamp); each but a query is answered `{"type": "ran"}`, and
 * any `{"type": "failed", "message": …}`.
 */
@DrivineInternalApi
internal object MigrateJson {

    const val KIND = "migrate"

    private val QUESTIONS = listOf("start", "resume")

    private val START_KEYS = listOf("declarations", "batchSize", "dryRun", "keepInvalid")

    fun answer(tree: ObjectNode): String = try {
        val given = tree[KIND] as? ObjectNode ?: throw IllegalArgumentException("A $KIND request gives {\"store\": …, \"graph\": …, …}.")
        if (QUESTIONS.count { given.has(it) } != 1) throw IllegalArgumentException("A $KIND request gives one of start and resume.")
        val ddl = StoreJson.ddl(given, KIND) as? SqlStoreDdl ?: throw IllegalArgumentException("A $KIND request names a SQL store, postgres, oracle or sqlite: a graph engine holds its own layout.")
        val migrator = LayoutMigrator(ddl)
        val step = if (given.has("start")) {
            val request = requestOf(given["start"]!!)
            migrator.validate(request)?.let { throw IllegalArgumentException(it) }
            migrator.start(request)
        } else {
            val state = try {
                MigrationState.fromJson(CypherJson.writeValueAsString(given["resume"]!!))
            } catch (e: Exception) {
                throw IllegalArgumentException("A resume gives back the state its last round was answered with, as it was: ${e.message ?: e.toString()}")
            }
            val outcomes = (given["outcomes"] as? ArrayNode ?: throw IllegalArgumentException("A resume gives its outcomes as a list.")).map(::outcomeOf)
            migrator.resume(state, outcomes)
        }
        PlanJson.writePlain(step(step))
    } catch (e: Exception) {
        CompileJson.error(e.message ?: e.toString())
    }

    private fun requestOf(node: JsonNode): MigrationRequest {
        require(node is ObjectNode) { "A start gives {\"declarations\": […], …}." }
        // A key it doesn't know, dryRun mistyped say, is refused, as read past it would have the migration write.
        val unknown = node.properties().map { it.key }.filter { it !in START_KEYS }
        require(unknown.isEmpty()) { "A start gives no ${unknown.joinToString()}: it gives ${START_KEYS.joinToString()}." }
        require(node["declarations"].let { it == null || it.isNull || it is ArrayNode }) { "A start gives its declarations as a list." }
        fun flag(key: String) = node[key]?.takeUnless { it.isNull }?.let { require(it.isBoolean) { "A start gives $key as true or false." }; it.booleanValue() } ?: false
        val batch = node["batchSize"]?.takeUnless { it.isNull }?.let { require(it.isIntegralNumber) { "A start gives batchSize as a whole number." }; it.longValue() } ?: 1000L
        require(batch in 1..Int.MAX_VALUE) { "A migration reads its rows in batches of 1 or more, not $batch." }
        return MigrationRequest(declarationsOf(node["declarations"]), batch.toInt(), flag("dryRun"), flag("keepInvalid"))
    }

    private fun outcomeOf(node: JsonNode): MigrationOutcome = when (val type = node["type"]?.textValue()) {
        "rows" -> MigrationOutcome.Rows(PlanJson.plain(node["rows"] ?: throw IllegalArgumentException("A rows outcome gives its rows.")) as? List<*>
            ?: throw IllegalArgumentException("A rows outcome gives its rows as a list."))
        "ran" -> MigrationOutcome.Ran
        "failed" -> MigrationOutcome.Failed(node["message"]?.textValue() ?: "")
        else -> throw IllegalArgumentException("No outcome is $type: rows, ran or failed.")
    }

    private fun step(step: MigrationStep): Map<String, Any?> = when (step) {
        is MigrationStep.Run -> linkedMapOf("run" to step.actions.map(::action), "state" to PlanJson.plain(CypherJson.readTree(step.state.toJson())))
        is MigrationStep.Done -> mapOf("done" to step.report.toMap())
    }

    private fun action(action: MigrationAction): Map<String, Any?> = when (action) {
        is MigrationAction.Query -> linkedMapOf("type" to "query", "statement" to action.statement, "parameters" to action.parameters, "purpose" to action.purpose, "compiled" to action.compiled)
        is MigrationAction.Execute -> linkedMapOf("type" to "execute", "statement" to action.statement, "parameters" to action.parameters, "purpose" to action.purpose)
        is MigrationAction.Transaction -> linkedMapOf("type" to "transaction", "statements" to action.statements.map(::action))
        MigrationAction.Prepare -> linkedMapOf("type" to "prepare")
        MigrationAction.AssignStamp -> linkedMapOf("type" to "assignStamp")
        is MigrationAction.Lock -> linkedMapOf("type" to "lock", "statement" to action.statement)
    }
}
