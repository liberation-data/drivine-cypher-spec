package org.drivine.cypher.api

import org.drivine.DrivineInternalApi
import org.drivine.cypher.json.ArrayNode
import org.drivine.cypher.json.CypherJson
import org.drivine.cypher.json.JsonNode
import org.drivine.cypher.json.ObjectNode
import org.drivine.cypher.store.plan.PlanJson
import org.drivine.cypher.store.plan.PlanStep
import org.drivine.cypher.store.plan.PlannerState
import org.drivine.cypher.store.plan.SchemaPlanner
import org.drivine.cypher.store.schema.StoreSchema
import org.drivine.cypher.store.ddl.CatalogItem
import org.drivine.cypher.store.ddl.SqlStoreDdl
import org.drivine.schema.SchemaItemKind
import org.drivine.schema.made

/**
 * The schema planner for a host with no Kotlin types, asked of the JSON entry ([CompileJson]) in place of a statement
 * (liberation-data/drivine-cypher-spec#107, #135): which indexes and constraints to make or drop, and what to record in the
 * schema marker, decided by the module, a round at a time, while the host runs each round's statements on its own
 * connections and gives back what each did, as drivine4j's `SchemaPlanDriver` runs [SchemaPlanner]. A request, an action, an
 * outcome and the planner's state are written as [PlanJson] writes them, so a state a host keeps between rounds is the state
 * the module wrote.
 *
 * Each request names its store, as `storeStatements` does (`{"store": "postgres", "graph": "g"}`, a graph engine with no
 * graph), and one of:
 * - `"validate": <request>`: why the request is refused before anything is applied ([SchemaPlanner.validate]). Answered
 *   `{"refusal": "…"}`, null where nothing conflicts.
 * - `"start": <request>, "now": <ms>`: the first round, planned at `now`, the host's clock in milliseconds since the epoch.
 * - `"resume": <state>, "outcomes": [<outcome>, …], "now": <ms>`: the next round, after the last round of the state gave
 *   `outcomes`, one for each of its actions that ran, in order: all of them, or those up to and with the first that failed.
 *   A start and a resume are answered `{"run": [<action>, …], "state": <state>}`, the actions to run in order and the state
 *   to resume from, or `{"done": <report>}` ([PlanJson.report]).
 * - `"runtimeAfter": {"operation": <operation>, "runtime": [<runtime catalog>, …], "databases": [<name>, …] | null}`: the
 *   runtime catalogs after the operation, as drivine4j keeps the items it ensures as it runs ([SchemaPlanner.runtimeAfter]).
 *   Answered `{"runtime": [<runtime catalog>, …]}`.
 * - `"runtimeOn": {"database": <name>, "runtime": [<runtime catalog>, …]}`: the runtime catalogs that hold on the database,
 *   as a request's `runtime` takes them ([SchemaPlanner.runtimeOn]). Answered `{"catalogs": [<catalog>, …]}`.
 * - `"listings": {}`: the statements that list what the store holds, read with no plan, as drivine4j's `IndexManager` and
 *   `ConstraintManager` list, find and look for a constraint in force: `{"indexes": {<kind>: <statement>, …}, "constraints":
 *   <statement>}`, an index listing for each kind the store has (`TEXT_INDEX` only where it has text indexes), each kind's
 *   [org.drivine.cypher.store.ddl.StoreDdl.listIndexesQuery], one statement listing more than one kind on some stores.
 * - `"items": {"of": "indexes" | "constraints", "rows": [<row>, …], "spec": <spec>}`: the items the rows of one of those
 *   listings hold, as the store schema parses them ([StoreSchema.parseIndexRows], [StoreSchema.parseConstraintRows]):
 *   `{"items": [<item>, …]}`, each written as [PlanJson.item] writes it, with its `aliases`, the names a catalog record keeps
 *   beside its own (none on a graph engine), and whether its build is in progress or failed. With `spec`, only those of the
 *   spec's identity as it is made ([StoreSchema.matchesIdentity], `spec.made()`), as a find takes them.
 * - `"aliased": {"item": {"kind", "label", "properties", "name"}, "aliases": [<name>, …]}`: the statement that records the
 *   aliases as all the item's aliases, in its catalog record, `{"statement": …}`, null on a store that keeps no record.
 *
 * A request that gives more than one of them is an error.
 */
@DrivineInternalApi
internal object SchemaPlanJson {

    const val KIND = "schemaPlan"

    private val QUESTIONS = listOf("validate", "start", "resume", "runtimeAfter", "runtimeOn", "listings", "items", "aliased")

    /** The answer to [tree], a request that gives [KIND]. */
    fun answer(tree: ObjectNode): String = try {
        val given = tree[KIND] as? ObjectNode ?: throw IllegalArgumentException("A $KIND request gives {\"store\": …, …}.")
        // A request asks one thing: one that gives two would have the other read past.
        if (QUESTIONS.count { given.has(it) } != 1) throw IllegalArgumentException("A $KIND request gives one of ${QUESTIONS.dropLast(1).joinToString()} and ${QUESTIONS.last()}.")
        val result: Map<String, Any?> = when {
            given.has("runtimeAfter") -> runtimeAfter(given["runtimeAfter"])
            given.has("runtimeOn") -> runtimeOn(given["runtimeOn"])
            else -> {
                val store = StoreSchema.of(StoreJson.ddl(given, KIND))
                val planner = SchemaPlanner(store)
                when {
                    given.has("listings") -> listings(store)
                    given.has("items") -> items(store, given["items"])
                    given.has("aliased") -> aliased(store, given["aliased"])
                    given.has("validate") -> mapOf("refusal" to planner.validate(PlanJson.requestOf(given["validate"]!!)))
                    given.has("start") -> step(planner.start(PlanJson.requestOf(given["start"]!!), now(given)))
                    given.has("resume") -> {
                        val state = PlannerState.fromJson(CypherJson.writeValueAsString(given["resume"]!!))
                        val outcomes = (given["outcomes"] as? ArrayNode ?: throw IllegalArgumentException("A resume gives its outcomes as a list.")).map(PlanJson::outcomeOf)
                        step(planner.resume(state, outcomes, now(given)))
                    }
                    else -> throw IllegalStateException("unreachable: ${QUESTIONS.joinToString()}")
                }
            }
        }
        // As the planner holds each value: a sample's or a row's `$t` is not the layout's to escape (PlanJson.writePlain).
        PlanJson.writePlain(result)
    } catch (e: Exception) {
        // As every other request answers a failure: a host is answered, never thrown at.
        CompileJson.error(e.message ?: e.toString())
    }

    private fun now(given: ObjectNode): Long =
        given["now"]?.takeIf { it.isIntegralNumber }?.longValue() ?: throw IllegalArgumentException("A start or a resume gives now, the host's clock in milliseconds.")

    private fun step(step: PlanStep): Map<String, Any?> = when (step) {
        is PlanStep.Run -> linkedMapOf("run" to step.actions.map(PlanJson::action), "state" to CypherJson.readTree(step.state.toJson()))
        is PlanStep.Done -> mapOf("done" to PlanJson.report(step.report))
    }

    private fun runtimeAfter(given: JsonNode?): Map<String, Any?> {
        require(given is ObjectNode) { "runtimeAfter gives {\"operation\": …, \"runtime\": […], \"databases\": […]}." }
        val operation = PlanJson.operationOf(given["operation"] ?: throw IllegalArgumentException("runtimeAfter gives its operation."))
        val runtime = (given["runtime"] as? ArrayNode ?: throw IllegalArgumentException("runtimeAfter gives runtime as a list.")).map(PlanJson::runtimeOf)
        val databases = given["databases"]?.takeUnless { it.isNull }?.map { it.textValue() ?: throw IllegalArgumentException("runtimeAfter's databases are names.") }?.toSet()
        return mapOf("runtime" to SchemaPlanner.runtimeAfter(operation, runtime, databases).map(PlanJson::runtimeTree))
    }

    private fun runtimeOn(given: JsonNode?): Map<String, Any?> {
        require(given is ObjectNode) { "runtimeOn gives {\"database\": …, \"runtime\": […]}." }
        val database = given["database"]?.takeIf { it.isTextual }?.textValue() ?: throw IllegalArgumentException("runtimeOn gives its database as text.")
        val runtime = (given["runtime"] as? ArrayNode ?: throw IllegalArgumentException("runtimeOn gives runtime as a list.")).map(PlanJson::runtimeOf)
        return mapOf("catalogs" to SchemaPlanner.runtimeOn(database, runtime).map(PlanJson::catalogTree))
    }

    /** The index listing of each kind the store has, and its constraint listing. */
    private fun listings(store: StoreSchema): Map<String, Any?> = linkedMapOf(
        "indexes" to listOfNotNull(
            SchemaItemKind.VECTOR_INDEX,
            SchemaItemKind.RANGE_INDEX,
            SchemaItemKind.FULLTEXT_INDEX,
            SchemaItemKind.TEXT_INDEX.takeIf { store.supportsTextIndex },
        ).associate { it.name to store.ddl.listIndexesQuery(it) },
        "constraints" to store.ddl.listConstraintsQuery(),
    )

    /** The items the rows of a listing hold, each with its aliases and build state; with a spec, those of its identity. */
    private fun items(store: StoreSchema, given: JsonNode?): Map<String, Any?> {
        require(given is ObjectNode) { "items gives {\"of\": \"indexes\" | \"constraints\", \"rows\": […]}." }
        val constraints = when (given["of"]?.takeIf { it.isTextual }?.textValue()) {
            "indexes" -> false
            "constraints" -> true
            else -> throw IllegalArgumentException("items gives of, \"indexes\" or \"constraints\": the listing its rows are of.")
        }
        val rows = (given["rows"] as? ArrayNode ?: throw IllegalArgumentException("items gives its rows as a list.")).map(PlanJson::plain)
        val made = given["spec"]?.takeUnless { it.isNull }?.let { PlanJson.spec(it).made() }
        // A row at a time, as drivine4j reads a catalog record's aliases beside the item parsed from it.
        val items = rows.flatMap { row ->
            val parsed = if (constraints) store.parseConstraintRows(listOf(row)) else store.parseIndexRows(listOf(row))
            parsed.map { it to aliasesIn(row) }
        }.distinct().filter { (info, _) -> made == null || store.matchesIdentity(info, made) }
        return mapOf(
            "items" to items.map { (info, aliases) ->
                PlanJson.item(info)!! + linkedMapOf(
                    "aliases" to aliases,
                    "buildInProgress" to info.buildInProgress,
                    "buildFailed" to info.buildFailed,
                )
            },
        )
    }

    /** The aliases a catalog row's `details` keep: none, where there are none, or the store keeps none. */
    private fun aliasesIn(row: Any?): List<String> =
        (((row as? Map<*, *>)?.get("details") as? Map<*, *>)?.get(SqlStoreDdl.ALIASES) as? List<*>).orEmpty().map { it.toString() }

    /** The statement that records the aliases of an item in its catalog record; null on a store that keeps none. */
    private fun aliased(store: StoreSchema, given: JsonNode?): Map<String, Any?> {
        require(given is ObjectNode) { "aliased gives {\"item\": {…}, \"aliases\": […]}." }
        val item = given["item"] as? ObjectNode ?: throw IllegalArgumentException("aliased gives its item.")
        val kind = item["kind"]?.textValue()?.let { name -> SchemaItemKind.entries.firstOrNull { it.name == name } }
            ?: throw IllegalArgumentException("aliased gives its item's kind, one of ${SchemaItemKind.entries.joinToString()}.")
        val label = item["label"]?.takeIf { it.isTextual }?.textValue() ?: throw IllegalArgumentException("aliased gives its item's label.")
        val properties = (item["properties"] as? ArrayNode ?: throw IllegalArgumentException("aliased gives its item's properties as a list.")).map {
            it.textValue() ?: throw IllegalArgumentException("aliased gives its item's properties as names.")
        }
        val name = item["name"]?.takeUnless { it.isNull }?.textValue()
        val aliases = (given["aliases"] as? ArrayNode ?: throw IllegalArgumentException("aliased gives its aliases as a list.")).map {
            it.textValue() ?: throw IllegalArgumentException("aliased gives its aliases as names.")
        }
        return mapOf("statement" to (store.ddl as? SqlStoreDdl)?.aliased(CatalogItem(kind, label, properties, name), aliases))
    }
}
