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

    fun write(state: PlannerState): String = CypherJson.writeValueAsString(
        mapOf("request" to request(state.request), "rounds" to state.rounds.map(::round)),
    )

    fun readState(text: String): PlannerState {
        val node = CypherJson.readTree(text)
        return PlannerState(request(node.at("request")), node.at("rounds").map(::round))
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
        is PlanAction.FillLowerCopies -> linkedMapOf("type" to "fillLowerCopies", "label" to action.label, "properties" to action.properties, "again" to action.again)
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
            "fillLowerCopies" -> PlanAction.FillLowerCopies(node.text("label")!!, node.texts("properties"), node.at("again").booleanValue())
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
