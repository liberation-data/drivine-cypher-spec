package org.drivine.cypher.store.plan

import org.drivine.cypher.json.ArrayNode
import org.drivine.cypher.json.CypherJson
import org.drivine.cypher.json.JsonNode
import org.drivine.cypher.json.ObjectNode
import org.drivine.cypher.store.ddl.DdlStatement
import org.drivine.cypher.store.schema.Cause
import org.drivine.cypher.store.schema.StoreError
import org.drivine.schema.EngineVectorOptions
import org.drivine.schema.FalkorDbVectorOptions
import org.drivine.schema.FullTextIndexSpec
import org.drivine.schema.Neo4jVectorOptions
import org.drivine.schema.RangeIndexSpec
import org.drivine.schema.SchemaItemKind
import org.drivine.schema.SchemaItemSpec
import org.drivine.schema.SchemaMode
import org.drivine.schema.SchemaPolicy
import org.drivine.schema.SimilarityFunction
import org.drivine.schema.TextIndexSpec
import org.drivine.schema.UniquenessConstraintSpec
import org.drivine.schema.VectorIndexSpec

/**
 * [PlannerState] as JSON, in the module's own JSON ([CypherJson]): every field written, so a state read back is the state
 * written. A value of a row or a parameter is a plain value (a map, a list, text, a whole number, a double, a boolean or
 * null), read back as one; a whole number as a `Long`, whatever it was written from.
 */
internal object PlanJson {

    fun write(state: PlannerState): String =
        writePlain(mapOf("request" to request(state.request), "rounds" to state.rounds.map(::round)))

    /**
     * [value], a plain value, as JSON text with every key and value as it is: not as the store layout writes a value
     * ([CypherJson.valueToTree] doubles a `$` that begins a key and turns a temporal value into its tag). A row a host gives
     * back, an action's parameters and a violation's sample are values a host binds or reads as they are, as drivine4j's
     * planner holds them: a sampled date is a tag with `$t`, which the layout's writing would make `$$t`.
     */
    fun writePlain(value: Any?): String = CypherJson.writeValueAsString(plainTree(value))

    private fun plainTree(value: Any?): JsonNode = when (value) {
        // A tree is written as it is: a JsonNode is Iterable, over its values.
        is JsonNode -> value
        is Map<*, *> -> ObjectNode().apply { value.forEach { (k, v) -> set<JsonNode>(k.toString(), plainTree(v)) } }
        is Iterable<*> -> ArrayNode().apply { value.forEach { add(plainTree(it)) } }
        else -> CypherJson.valueToTree(value)
    }

    fun readState(text: String): PlannerState {
        val node = CypherJson.readTree(text)
        return PlannerState(request(node.at("request")), node.at("rounds").map(::round))
    }

    // ----- For a host, through the JSON entry (liberation-data/drivine-cypher-spec#107) -----

    /** A request, its operation and catalogs, as a host gives one and as [write] writes one in a state. */
    fun requestOf(node: JsonNode): PlanRequest = request(node)

    /** [request] as [requestOf] reads it back. */
    fun requestTree(request: PlanRequest): Map<String, Any?> = request(request)

    /** An operation, as a request writes one. */
    fun operationOf(node: JsonNode): Operation = operation(node)

    /** [operation] as [operationOf] reads it back. */
    fun operationTree(operation: Operation): Map<String, Any?> = operation(operation)

    /** An action's outcome, as a host gives it back on resuming: `{"type": "rows", "rows": […]}`, `{"type": "ran"}` or `{"type": "failed", "causes": […]}`. */
    fun outcomeOf(node: JsonNode): PlanOutcome = outcome(node)

    /** [outcome] as [outcomeOf] reads it back. */
    fun outcomeTree(outcome: PlanOutcome): Map<String, Any?> = outcome(outcome)

    /** A declared catalog, as a request writes one. */
    fun catalogOf(node: JsonNode): DeclaredCatalog = catalog(node)

    /** [catalog] as [catalogOf] reads it back. */
    fun catalogTree(catalog: DeclaredCatalog): Map<String, Any?> = catalog(catalog)

    /** A runtime catalog: its owner, the databases it is on (null for all) and its items. */
    fun runtimeOf(node: JsonNode): RuntimeCatalog = RuntimeCatalog(
        node.text("owner"), node["databases"]?.takeUnless { it.isNull }?.map { it.textValue()!! }?.toSet(), node.at("items").map(::spec),
    )

    /** [catalog] as [runtimeOf] reads it back, its databases in order. */
    fun runtimeTree(catalog: RuntimeCatalog): Map<String, Any?> =
        linkedMapOf("owner" to catalog.owner, "databases" to catalog.databases?.sorted(), "items" to catalog.items.map(::spec))

    /**
     * [report], every field written, null where it has none: each spec written out as [spec] writes it, each item as the store
     * lists it ([item]), each error by its causes, and the action whose failure ended the plan as [action] writes it.
     */
    fun report(report: DatabaseReport): Map<String, Any?> = linkedMapOf(
        "database" to report.database,
        "owners" to report.owners.map { owner ->
            linkedMapOf(
                "owner" to owner.owner,
                "results" to owner.results.map { linkedMapOf("spec" to spec(it.spec), "result" to result(it.result)) },
                "unbuilt" to owner.unbuilt.map { linkedMapOf("spec" to spec(it.spec), "stillBuilding" to it.stillBuilding, "message" to it.message) },
                "orphans" to owner.orphans,
                "marker" to owner.marker?.let { linkedMapOf("version" to it.version, "items" to it.items) },
                "failure" to owner.failure,
                "removed" to owner.removed?.let { linkedMapOf("dropped" to it.dropped, "kept" to it.kept.map(::spec)) },
            )
        },
        "item" to report.item?.let { linkedMapOf("result" to it.result?.let(::result), "dropped" to it.dropped) },
        "failure" to report.failure,
        "rethrown" to report.rethrown?.let {
            linkedMapOf("action" to action(it.action), "causes" to it.error.causes.map(::cause), "round" to it.round)
        },
        "log" to report.log.map { linkedMapOf("level" to it.level.name, "logger" to it.logger, "message" to it.message) },
    )

    /** What ensuring an item gave, keyed by its kind: `created`, `alreadyMatching`, `drift`, `recreated`, `violation` or `blockedByIndex`. */
    private fun result(result: org.drivine.schema.EnsureResult): Map<String, Any?> = when (result) {
        is org.drivine.schema.EnsureResult.Created -> mapOf("created" to item(result.info))
        is org.drivine.schema.EnsureResult.AlreadyMatching -> mapOf("alreadyMatching" to item(result.info))
        is org.drivine.schema.EnsureResult.Drift -> mapOf("drift" to linkedMapOf("existing" to item(result.existing), "requested" to spec(result.requested)))
        is org.drivine.schema.EnsureResult.Recreated -> mapOf("recreated" to linkedMapOf("previous" to item(result.previous), "current" to item(result.current)))
        is org.drivine.schema.EnsureResult.Violation -> mapOf("violation" to linkedMapOf("requested" to spec(result.requested), "sample" to result.conflictingSample))
        is org.drivine.schema.EnsureResult.BlockedByIndex -> mapOf("blockedByIndex" to linkedMapOf("requested" to spec(result.requested), "index" to item(result.index)))
    }

    /** An item as the store lists it, every field written. */
    fun item(info: org.drivine.schema.SchemaItemInfo?): Map<String, Any?>? = info?.let {
        linkedMapOf(
            "kind" to it.kind.name, "label" to it.label, "properties" to it.properties, "name" to it.name, "dimensions" to it.dimensions,
            "similarity" to it.similarity?.name, "analyzer" to it.analyzer, "status" to it.status, "quantizationEnabled" to it.quantizationEnabled,
            "hnswM" to it.hnswM, "hnswEfConstruction" to it.hnswEfConstruction,
        )
    }

    // ----- Writing, as plain values CypherJson writes -----

    private fun request(request: PlanRequest): Map<String, Any?> = linkedMapOf(
        "database" to request.database,
        "operation" to operation(request.operation),
        "catalogs" to request.catalogs.map(::catalog),
        "runtime" to request.runtime.map(::catalog),
        "policy" to with(request.policy) {
            linkedMapOf(
                "mode" to mode.name,
                "recreateOnDrift" to recreateOnDrift,
                "recreateOnStartup" to recreateOnStartup,
                "violationSampleSize" to violationSampleSize,
                "replaceBlockingIndex" to replaceBlockingIndex,
            )
        },
        "buildTimeoutMillis" to request.buildTimeoutMillis,
    )

    private fun owners(owners: Owners): Map<String, Any?> = when (owners) {
        Owners.All -> mapOf("all" to true)
        is Owners.One -> mapOf("all" to false, "owner" to owners.name)
    }

    private fun operation(operation: Operation): Map<String, Any?> = when (operation) {
        is Operation.Enforce -> mapOf("type" to "enforce", "owners" to owners(operation.owners))
        is Operation.RecreateAll -> mapOf("type" to "recreateAll", "owners" to owners(operation.owners))
        is Operation.Ensure -> mapOf("type" to "ensure", "owner" to operation.owner, "items" to operation.items.map(::spec))
        is Operation.Remove -> mapOf("type" to "remove", "owner" to operation.owner, "items" to operation.items.map(::spec))
        is Operation.Item -> mapOf("type" to "item", "call" to operation.call.name, "spec" to spec(operation.spec))
    }

    private fun catalog(catalog: DeclaredCatalog): Map<String, Any?> =
        linkedMapOf("owner" to catalog.owner, "version" to catalog.version, "items" to catalog.items.map(::spec))

    fun spec(spec: SchemaItemSpec): Map<String, Any?> {
        val fields = linkedMapOf<String, Any?>("kind" to spec.kind.name, "label" to spec.label, "properties" to spec.properties, "name" to spec.name)
        when (spec) {
            is RangeIndexSpec -> fields["ignoreCase"] = spec.ignoreCase
            is TextIndexSpec -> fields["ignoreCase"] = spec.ignoreCase
            is UniquenessConstraintSpec -> {
                fields["ignoreCase"] = spec.ignoreCase
                fields["nodeIdProperty"] = spec.nodeIdProperty
            }
            is FullTextIndexSpec -> fields["analyzer"] = spec.analyzer
            is VectorIndexSpec -> {
                fields["dimensions"] = spec.dimensions
                fields["similarity"] = spec.similarity.name
                fields["hnswM"] = spec.hnswM
                fields["hnswEfConstruction"] = spec.hnswEfConstruction
                fields["engineOptions"] = spec.engineOptions.map(::engineOptions)
            }
        }
        return fields
    }

    private fun engineOptions(options: EngineVectorOptions): Map<String, Any?> = when (options) {
        is Neo4jVectorOptions -> linkedMapOf(
            "engine" to options.engine, "quantizationEnabled" to options.quantizationEnabled,
            "hnswM" to options.hnswM, "hnswEfConstruction" to options.hnswEfConstruction,
        )
        is FalkorDbVectorOptions -> linkedMapOf(
            "engine" to options.engine, "efRuntime" to options.efRuntime,
            "hnswM" to options.hnswM, "hnswEfConstruction" to options.hnswEfConstruction,
        )
    }

    private fun round(round: PlanRound): Map<String, Any?> =
        linkedMapOf("now" to round.now, "actions" to round.actions.map(::action), "outcomes" to round.outcomes.map(::outcome))

    fun action(action: PlanAction): Map<String, Any?> = when (action) {
        is PlanAction.Query -> linkedMapOf(
            "type" to "query", "statement" to action.statement, "parameters" to action.parameters,
            "purpose" to action.purpose.name, "delayMillis" to action.delayMillis, "compiled" to action.compiled,
        )
        is PlanAction.Execute -> linkedMapOf(
            "type" to "execute", "statement" to statement(action.statement), "parameters" to action.parameters,
            "purpose" to action.purpose.name, "retry" to action.retry.name,
        )
        is PlanAction.AwaitIndex -> linkedMapOf("type" to "awaitIndex", "statement" to action.statement, "parameters" to action.parameters)
        is PlanAction.ReadLowerCopies -> linkedMapOf("type" to "readLowerCopies", "label" to action.label, "properties" to action.properties)
        is PlanAction.FillLowerCopies -> linkedMapOf("type" to "fillLowerCopies", "label" to action.label, "properties" to action.properties)
        is PlanAction.WriteMarker -> linkedMapOf("type" to "writeMarker", "scope" to action.scope, "statement" to action.statement, "parameters" to action.parameters)
    }

    private fun statement(statement: DdlStatement): Any = when (statement) {
        is DdlStatement.Text -> statement.statement
        is DdlStatement.Native -> mapOf("native" to statement.args)
    }

    private fun outcome(outcome: PlanOutcome): Map<String, Any?> = when (outcome) {
        is PlanOutcome.Rows -> mapOf("type" to "rows", "rows" to outcome.rows)
        PlanOutcome.Ran -> mapOf("type" to "ran")
        is PlanOutcome.Failed -> mapOf("type" to "failed", "causes" to outcome.error.causes.map(::cause))
    }

    private fun cause(cause: Cause): Map<String, Any?> = linkedMapOf(
        "sqlState" to cause.sqlState, "vendorCode" to cause.vendorCode, "code" to cause.code,
        "gqlStatus" to cause.gqlStatus, "message" to cause.message,
    )

    // ----- Reading -----

    private fun JsonNode.at(key: String): JsonNode = requireNotNull(this[key]) { "A planner state has no $key" }

    private fun JsonNode.text(key: String): String? = this[key]?.takeUnless { it.isNull }?.textValue()

    private fun JsonNode.int(key: String): Int? = this[key]?.takeUnless { it.isNull }?.longValue()?.toInt()

    private fun JsonNode.texts(key: String): List<String> = at(key).map { it.textValue()!! }

    private fun request(node: JsonNode): PlanRequest {
        val policy = node.at("policy")
        return PlanRequest(
            database = node.text("database")!!,
            operation = operation(node.at("operation")),
            catalogs = node.at("catalogs").map(::catalog),
            runtime = node.at("runtime").map(::catalog),
            policy = SchemaPolicy(
                mode = SchemaMode.valueOf(policy.text("mode")!!),
                recreateOnDrift = policy.at("recreateOnDrift").booleanValue(),
                recreateOnStartup = policy.at("recreateOnStartup").booleanValue(),
                violationSampleSize = policy.int("violationSampleSize")!!,
                replaceBlockingIndex = policy.at("replaceBlockingIndex").booleanValue(),
            ),
            buildTimeoutMillis = node.at("buildTimeoutMillis").longValue(),
        )
    }

    private fun owners(node: JsonNode): Owners = if (node.at("all").booleanValue()) Owners.All else Owners.One(node.text("owner"))

    private fun operation(node: JsonNode): Operation = when (val type = node.text("type")) {
        "enforce" -> Operation.Enforce(owners(node.at("owners")))
        "recreateAll" -> Operation.RecreateAll(owners(node.at("owners")))
        "ensure" -> Operation.Ensure(node.text("owner"), node.at("items").map(::spec))
        "remove" -> Operation.Remove(node.text("owner"), node.at("items").map(::spec))
        "item" -> Operation.Item(ItemCall.valueOf(node.text("call")!!), spec(node.at("spec")))
        else -> throw IllegalArgumentException("No operation is $type")
    }

    private fun catalog(node: JsonNode) = DeclaredCatalog(node.text("owner"), node.text("version"), node.at("items").map(::spec))

    fun spec(node: JsonNode): SchemaItemSpec {
        val label = node.text("label")!!
        val properties = node.texts("properties")
        val name = node.text("name")
        val ignoreCase = node["ignoreCase"]?.booleanValue() ?: false
        return when (SchemaItemKind.valueOf(node.text("kind")!!)) {
            SchemaItemKind.RANGE_INDEX -> RangeIndexSpec(label, properties, name, ignoreCase)
            SchemaItemKind.TEXT_INDEX -> TextIndexSpec(label, properties.single(), name, ignoreCase)
            SchemaItemKind.UNIQUENESS_CONSTRAINT -> UniquenessConstraintSpec(label, properties, name, node.text("nodeIdProperty"), ignoreCase)
            SchemaItemKind.FULLTEXT_INDEX -> FullTextIndexSpec(label, properties, name, node.text("analyzer"))
            SchemaItemKind.VECTOR_INDEX -> VectorIndexSpec(
                label, properties.single(), node.int("dimensions")!!, SimilarityFunction.valueOf(node.text("similarity")!!), name,
                node.int("hnswM"), node.int("hnswEfConstruction"), node.at("engineOptions").map(::engineOptions),
            )
        }
    }

    private fun engineOptions(node: JsonNode): EngineVectorOptions = when (val engine = node.text("engine")) {
        Neo4jVectorOptions.ENGINE -> Neo4jVectorOptions(node["quantizationEnabled"]?.takeUnless { it.isNull }?.booleanValue(), node.int("hnswM"), node.int("hnswEfConstruction"))
        FalkorDbVectorOptions.ENGINE -> FalkorDbVectorOptions(node.int("hnswM"), node.int("hnswEfConstruction"), node.int("efRuntime"))
        else -> throw IllegalArgumentException("No vector options are known for $engine")
    }

    private fun round(node: JsonNode) = PlanRound(node.at("now").longValue(), node.at("actions").map(::action), node.at("outcomes").map(::outcome))

    fun action(node: JsonNode): PlanAction {
        @Suppress("UNCHECKED_CAST")
        fun parameters() = plain(node.at("parameters")) as Map<String, Any?>
        return when (val type = node.text("type")) {
            "query" -> PlanAction.Query(
                node.text("statement")!!, parameters(), Purpose.valueOf(node.text("purpose")!!),
                node.at("delayMillis").longValue(), node.at("compiled").booleanValue(),
            )
            "execute" -> PlanAction.Execute(statement(node.at("statement")), parameters(), Purpose.valueOf(node.text("purpose")!!), Retry.valueOf(node.text("retry")!!))
            "awaitIndex" -> PlanAction.AwaitIndex(node.text("statement")!!, parameters())
            "readLowerCopies" -> PlanAction.ReadLowerCopies(node.text("label")!!, node.texts("properties"))
            "fillLowerCopies" -> PlanAction.FillLowerCopies(node.text("label")!!, node.texts("properties"))
            "writeMarker" -> PlanAction.WriteMarker(node.text("scope")!!, node.text("statement")!!, parameters())
            else -> throw IllegalArgumentException("No action is $type")
        }
    }

    private fun statement(node: JsonNode): DdlStatement =
        if (node.isTextual) DdlStatement.Text(node.textValue()!!) else DdlStatement.Native(node.texts("native"))

    private fun outcome(node: JsonNode): PlanOutcome = when (val type = node.text("type")) {
        "rows" -> PlanOutcome.Rows(plain(node.at("rows")) as List<*>)
        "ran" -> PlanOutcome.Ran
        "failed" -> PlanOutcome.Failed(StoreError(node.at("causes").map(::cause)))
        else -> throw IllegalArgumentException("No outcome is $type")
    }

    private fun cause(node: JsonNode) = Cause(node.text("sqlState"), node.int("vendorCode"), node.text("code"), node.text("gqlStatus"), node.text("message"))

    /** The plain value [node] holds: a map, a list, text, a whole number as a `Long`, a double, a boolean or null. */
    fun plain(node: JsonNode): Any? = when {
        node.isNull -> null
        node.isTextual -> node.textValue()
        node.isBoolean -> node.booleanValue()
        node.isIntegralNumber -> node.longValue()
        node.isNumber -> node.doubleValue()
        node is ArrayNode -> node.map(::plain)
        node is ObjectNode -> node.properties().associate { it.key to plain(it.value) }
        else -> throw IllegalArgumentException("Not a plain value: ${node.asText()}")
    }
}
