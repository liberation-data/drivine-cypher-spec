package org.drivine.cypher.store.plan

import org.drivine.cypher.json.CypherJson
import org.drivine.cypher.json.JsonNode
import org.drivine.cypher.json.ObjectNode
import org.drivine.cypher.number.oracleFile
import org.drivine.cypher.store.ddl.DdlStatement
import org.drivine.cypher.store.ddl.FalkorDbDdl
import org.drivine.cypher.store.ddl.MemgraphDdl
import org.drivine.cypher.store.ddl.Neo4jDdl
import org.drivine.cypher.store.ddl.OracleDdl
import org.drivine.cypher.store.ddl.PostgresDdl
import org.drivine.cypher.store.ddl.SqlStoreDdl
import org.drivine.cypher.store.ddl.SqliteDdl
import org.drivine.cypher.store.ddl.StoreDdl
import org.drivine.cypher.store.schema.Cause
import org.drivine.cypher.store.schema.StoreError
import org.drivine.cypher.store.schema.StoreSchema
import org.drivine.schema.EnsureResult
import org.drivine.schema.FalkorDbVectorOptions
import org.drivine.schema.FullTextIndexSpec
import org.drivine.schema.Neo4jVectorOptions
import org.drivine.schema.RangeIndexSpec
import org.drivine.schema.SchemaItemInfo
import org.drivine.schema.SchemaItemKind
import org.drivine.schema.SchemaItemSpec
import org.drivine.schema.SchemaMode
import org.drivine.schema.SchemaPolicy
import org.drivine.schema.SimilarityFunction
import org.drivine.schema.TextIndexSpec
import org.drivine.schema.UniquenessConstraintSpec
import org.drivine.schema.VectorIndexSpec
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue
import kotlin.test.fail

/**
 * The spec's `plans.yaml` against the module's [SchemaPlanner], on each target: the JVM writes it as JSON with its answers
 * (`./gradlew jvmOracle`, `plans.json`), as `store.yaml` and `marker.yaml`, whose statements its cases name.
 *
 * Each case's steps are planned on each store it runs on: every round's actions must be those the case gives, each
 * statement as `store.yaml` or `marker.yaml` pins it; the case's answers are given back, its listings as the rows each
 * store lists its items as ([ListingRows]); the state crosses as JSON before each resume; and the report must be the
 * case's, its log lines word for word.
 */
class StorePlanTest {

    private val plans: JsonNode = CypherJson.readTree(oracleFile("plans.json"))
    private val store: JsonNode = CypherJson.readTree(oracleFile("store.json"))
    private val marker: JsonNode = CypherJson.readTree(oracleFile("marker.json"))
    private val graph = store["graph"]!!.textValue()!!

    private val ddls: Map<String, StoreDdl> = linkedMapOf(
        "neo4j" to Neo4jDdl,
        "memgraph" to MemgraphDdl(),
        "falkordb" to FalkorDbDdl,
        "postgres" to PostgresDdl(graph),
        "oracle" to OracleDdl(graph),
        "sqlite" to SqliteDdl(graph),
    )

    private val cases: List<JsonNode> = plans["cases"]!!.toList()

    @Test
    fun theCasesAreTheDesignsCasesOneToThirtyOneAndTheItemCalls() {
        val names = cases.map { it["case"]!!.textValue()!! }
        val numbers = names.filter { it.first().isDigit() }.map { it.takeWhile(Char::isDigit).toInt() }.distinct().sorted()
        assertEquals((1..31).toList(), numbers)
        assertEquals(listOf("item"), names.filterNot { it.first().isDigit() })
        assertEquals(names.distinct(), names, "each case once")
    }

    @Test
    fun eachPlanRunsOnEachStoreAsDrivine4jRanIt() {
        for (case in cases) {
            val name = "${case["case"]!!.textValue()} ${case["name"]!!.textValue()}"
            val stores = texts(case["stores"]!!)
            val catalogs = case["catalogs"]!!.map(::catalog)
            val policy = policy(case["policy"])
            val timeout = case["buildTimeoutMillis"]?.longValue() ?: PlanRequest.DEFAULT_BUILD_TIMEOUT_MILLIS
            // The runtime items the manager holds, as the module changes them.
            var held = emptyList<RuntimeCatalog>()
            for ((index, step) in case["steps"]!!.withIndex()) {
                val database = plans["database"]!!.textValue()!!
                val operation = operation(step["operation"]!!)
                val candidate = SchemaPlanner.runtimeAfter(operation, held)
                val request = PlanRequest(
                    database = database,
                    operation = operation,
                    catalogs = catalogs,
                    runtime = SchemaPlanner.runtimeOn(database, candidate),
                    policy = policy,
                    buildTimeoutMillis = timeout,
                )
                val refused = step["refused"]?.textValue()
                if (refused == null) {
                    assertEquals(step["runtime"]?.map(::catalog).orEmpty(), request.runtime, "$name, step $index: the runtime items planned with")
                    held = candidate
                }
                step["runtimeAfter"]?.let { after ->
                    assertEquals(after.map { RuntimeCatalog(it["owner"]?.textValue(), items = it["items"]!!.map(::spec)) }, held, "$name, step $index: the runtime items after it")
                }
                if (operation is Operation.Ensure || operation is Operation.Remove) assertTrue(step.has("runtimeAfter"), "$name, step $index: pins the runtime items after it")
                if (refused != null) {
                    for (id in stores) assertEquals(refused, planner(id).validate(request), "$name, step $index on $id: refused")
                    continue
                }
                val run = step["runs"]!!.toList()
                assertEquals(stores.toSet(), run.flatMap { texts(it["stores"]!!) }.toSet(), "$name, step $index: every store runs")
                for (each in run) for (id in texts(each["stores"]!!)) replay("$name, step $index on $id", id, request, each)
            }
        }
    }

    /** Plans [request] on [id] round by round, as [expected] gives the rounds, and checks the report. */
    private fun replay(name: String, id: String, request: PlanRequest, expected: JsonNode) {
        val planner = planner(id)
        val rounds = expected["rounds"]!!.toList()
        val done = expected["done"]!!.longValue()
        var step = planner.start(request, rounds.firstOrNull()?.get("now")?.longValue() ?: done)
        for ((r, round) in rounds.withIndex()) {
            val run = assertIs<PlanStep.Run>(step, "$name: round $r is planned")
            val (actions, outcomes) = expand(id, round)
            assertEquals(actions, run.actions, "$name: round $r")
            // The state crosses as JSON, and reads back the same.
            val crossed = PlannerState.fromJson(run.state.toJson())
            assertEquals(run.state.toJson(), crossed.toJson(), "$name: round $r's state as JSON")
            step = planner.resume(crossed, outcomes, rounds.getOrNull(r + 1)?.get("now")?.longValue() ?: done)
        }
        val report = assertIs<PlanStep.Done>(step, "$name: done after ${rounds.size} round(s)").report
        val engine = ddls.getValue(id).engine
        assertEquals(engined(plain(expected["report"]!!), engine), written(report), "$name: report")
    }

    private fun planner(id: String) = SchemaPlanner(StoreSchema.of(ddls.getValue(id)))

    // ----- The statements, by their names in store.yaml and marker.yaml -----

    private fun markerStore(id: String): JsonNode = marker["marker"]!!["stores"]!![id]!!

    /** What every create on [id] begins with: the statements that make the store, as `store.yaml`'s `tables` give them. */
    private fun prefix(id: String): List<DdlStatement> {
        val tables = store["tables"]!![id] ?: return emptyList()
        val made = tables["ensuring"]?.let { listOf(it) } ?: tables["ddl"]!!.toList()
        return made.map { DdlStatement.Text(it.textValue()!!) }
    }

    private fun statement(node: JsonNode): DdlStatement =
        if (node.isTextual) DdlStatement.Text(node.textValue()!!) else DdlStatement.Native(texts(node["native"]!!))

    private fun storeCase(name: String): JsonNode = store["cases"]!!.firstOrNull { it["name"]!!.textValue() == name } ?: fail("store.yaml has no case $name")

    /**
     * The DDL [execute] names on [id]: a `store.yaml` case's create, drop or dropUnnamed, or a step of its `recreate`
     * (`leftover`, `build`, `swap`, `remade`), the marker's, a statement given whole, or the record of the catalog's item
     * made in the current form (`formRecorded`).
     */
    private fun ddl(id: String, execute: JsonNode): List<PlanAction.Execute> {
        fun ddl(statements: List<DdlStatement>) = statements.map { PlanAction.Execute(it, purpose = Purpose.DDL) }
        execute["create"]?.let { return ddl(prefix(id) + storeCase(it.textValue()!!)["stores"]!![id]!!["create"]!!.map(::statement)) }
        execute["drop"]?.let { return ddl(storeCase(it.textValue()!!)["stores"]!![id]!!["drop"]!!.map(::statement)) }
        execute["dropUnnamed"]?.let { return ddl(storeCase(it.textValue()!!)["stores"]!![id]!!["dropUnnamed"]!!.map(::statement)) }
        // A uniqueness constraint made again in its place (drivine-cypher-spec#135 §4), as its case's `recreate` pins it.
        for (step in listOf("leftover", "build", "swap")) {
            execute[step]?.let { return ddl(storeCase(it.textValue()!!)["stores"]!![id]!!["recreate"]!![step]!!.map(::statement)) }
        }
        execute["remade"]?.let {
            val remade = storeCase(it.textValue()!!)["stores"]!![id]!!["recreate"]!!["remade"]!!.textValue()!!
            return listOf(PlanAction.Execute(DdlStatement.Text(remade), purpose = Purpose.ALIASES))
        }
        execute["statement"]?.let { statement ->
            val purpose = execute["purpose"]?.textValue()?.let(Purpose::valueOf) ?: return ddl(listOf(DdlStatement.Text(statement.textValue()!!)))
            return listOf(PlanAction.Execute(DdlStatement.Text(statement.textValue()!!), purpose = purpose))
        }
        // The record of the catalog's item made in the current form, recorded so (`store.yaml`'s `catalog`).
        execute["formRecorded"]?.let {
            return listOf(PlanAction.Execute(DdlStatement.Text(store["catalog"]!!["stores"]!![id]!!["formRecorded"]!!.textValue()!!), purpose = Purpose.ALIASES))
        }
        execute["native"]?.let { return ddl(listOf(DdlStatement.Native(texts(it)))) }
        return when (val name = execute["marker"]!!.textValue()) {
            "prepare" -> {
                val prepare = markerStore(id)["prepare"]!!.textValue()!!
                val tables = store["tables"]!![id]!!
                val text = if (prepare.startsWith("ddl[")) tables["ddl"]!![prepare.removePrefix("ddl[").removeSuffix("]").toInt()]!! else tables[prepare]!!
                listOf(PlanAction.Execute(DdlStatement.Text(text.textValue()!!), purpose = Purpose.PREPARE))
            }
            "deleteAllButNewest" -> listOf(
                PlanAction.Execute(DdlStatement.Text(markerStore(id)["deleteAllButNewest"]!!.textValue()!!), mapOf("scope" to execute["scope"]!!.textValue()), Purpose.MARKER),
            )
            "constraint" -> ddl(prefix(id) + markerStore(id)["constraint"]!!.map(::statement))
            else -> fail("no marker statement $name")
        }
    }

    private fun query(id: String, query: JsonNode, delay: Long): PlanAction.Query {
        query["listing"]?.let { listing ->
            val catalog = store["catalog"]!!["stores"]!![id]!!
            val name = listing.textValue()!!
            val statement = if (name == "constraints") catalog["constraints"]!! else catalog["indexes"]!![name.removePrefix("indexes.")]!!
            return PlanAction.Query(statement.textValue()!!, purpose = Purpose.LISTING, delayMillis = delay)
        }
        query["indexState"]?.let {
            val statement = store["schema"]!!["stores"]!![id]!!["indexState"]!!.textValue()!!
            return PlanAction.Query(statement, mapOf("name" to it.textValue()), Purpose.STATE, delay)
        }
        query["sample"]?.let { sample ->
            val spec = spec(sample["spec"]!!)
            val limit = sample["limit"]!!.longValue()
            val pinned = store["schema"]!!["samples"]!!.firstOrNull { spec(it["spec"]!!) == spec && it["limit"]!!.longValue() == limit }
                ?: fail("store.yaml samples no $spec with the limit $limit")
            return PlanAction.Query(pinned["stores"]!![id]!!.textValue()!!, purpose = Purpose.SAMPLE, delayMillis = delay)
        }
        query["statement"]?.let { return PlanAction.Query(it.textValue()!!, purpose = Purpose.LISTING, delayMillis = delay) }
        return when (val name = query["marker"]!!.textValue()) {
            "read.version", "read.items" -> PlanAction.Query(
                markerStore(id)["read"]!![name.removePrefix("read.")]!!.textValue()!!, mapOf("scope" to query["scope"]!!.textValue()), Purpose.MARKER, delay,
            )
            "scopes" -> {
                val sql = markerStore(id)["scopes"]?.takeUnless { it.isNull }?.textValue()
                PlanAction.Query(sql ?: marker["marker"]!!["scopesCypher"]!!.textValue()!!, purpose = Purpose.SCOPES, delayMillis = delay, compiled = sql == null && ddls.getValue(id) is SqlStoreDdl)
            }
            else -> fail("no marker query $name")
        }
    }

    /** A round's actions on [id], each named statement as its store pins it, and the outcomes its answers give, up to the first that fails. */
    private fun expand(id: String, round: JsonNode): Pair<List<PlanAction>, List<PlanOutcome>> {
        val now = round["now"]!!.longValue()
        val schema = StoreSchema.of(ddls.getValue(id))
        val actions = mutableListOf<PlanAction>()
        val outcomes = mutableListOf<PlanOutcome>()
        var failed = false
        // An action after the one that failed is planned, and not run: it has no answer.
        fun answer(outcome: () -> PlanOutcome) {
            if (failed) return
            outcomes += outcome().also { failed = it is PlanOutcome.Failed }
        }
        for (entry in round["actions"]!!) {
            when {
                entry["execute"] != null -> {
                    val group = ddl(id, entry["execute"]!!)
                    val failure = entry["failed"]
                    val at = failure?.get("at")?.longValue()?.toInt() ?: 0
                    val causes = failure?.let { it["causes"] ?: it }
                    group.forEachIndexed { k, action ->
                        actions += action
                        answer { if (causes != null && k == at) failedWith(causes) else PlanOutcome.Ran }
                    }
                }
                entry["query"] != null -> {
                    val action = query(id, entry["query"]!!, entry["delay"]?.longValue() ?: 0)
                    actions += action
                    answer {
                        when {
                            entry["failed"] != null -> failedWith(entry["failed"]!!)
                            entry["items"] != null -> {
                                val items = entry["items"]!!.map(::item)
                                val constraints = action.statement == ddls.getValue(id).listConstraintsQuery()
                                val rows = ListingRows.rows(id, items, constraints)
                                // The rows read back to the items given, so the case says what the store has.
                                assertEquals(items, if (constraints) schema.parseConstraintRows(rows) else schema.parseIndexRows(rows), "$id: the rows of $items")
                                PlanOutcome.Rows(rows)
                            }
                            else -> PlanOutcome.Rows(entry["rows"]!!.map(::plain))
                        }
                    }
                }
                entry["awaitIndex"] != null -> {
                    val await = entry["awaitIndex"]!!
                    actions += PlanAction.AwaitIndex(
                        store["schema"]!!["stores"]!![id]!!["awaitIndex"]!!.textValue()!!,
                        mapOf("name" to await["name"]!!.textValue(), "timeout" to await["timeout"]!!.longValue()),
                    )
                    answer { entry["failed"]?.let(::failedWith) ?: PlanOutcome.Ran }
                }
                entry["readLowerCopies"] != null -> {
                    val read = entry["readLowerCopies"]!!
                    actions += PlanAction.ReadLowerCopies(read["label"]!!.textValue()!!, texts(read["properties"]!!))
                    answer { entry["failed"]?.let(::failedWith) ?: PlanOutcome.Rows(entry["rows"]!!.map(::plain)) }
                }
                entry["fillLowerCopies"] != null -> {
                    val fill = entry["fillLowerCopies"]!!
                    actions += PlanAction.FillLowerCopies(fill["label"]!!.textValue()!!, texts(fill["properties"]!!))
                    answer { entry["failed"]?.let(::failedWith) ?: PlanOutcome.Ran }
                }
                entry["writeMarker"] != null -> {
                    val write = entry["writeMarker"]!!
                    val scope = write["scope"]!!.textValue()!!
                    val stored = markerStore(id)["itemsMark"]!!.textValue()!! + write["items"]!!.textValue()!!
                    val bound = mapOf("scope" to scope, "version" to write["version"]?.textValue(), "items" to stored, "now" to now)
                    val parameters = texts(markerStore(id)["mergeParameters"]!!).associateWith { bound[it] }
                    actions += PlanAction.WriteMarker(scope, markerStore(id)["merge"]!!.textValue()!!, parameters)
                    answer { entry["failed"]?.let(::failedWith) ?: PlanOutcome.Ran }
                }
                else -> fail("$id: no action $entry")
            }
        }
        return actions to outcomes
    }

    private fun failedWith(causes: JsonNode) = PlanOutcome.Failed(StoreError(causes.map(::cause)))

    // ----- The request -----

    private fun operation(node: JsonNode): Operation {
        fun owners(of: JsonNode): Owners = if (of.isTextual) Owners.All else Owners.One(of["owner"]?.textValue())
        node["enforce"]?.let { return Operation.Enforce(owners(it)) }
        node["recreateAll"]?.let { return Operation.RecreateAll(owners(it)) }
        node["ensure"]?.let { return Operation.Ensure(it["owner"]?.textValue(), it["items"]!!.map(::spec)) }
        node["remove"]?.let { return Operation.Remove(it["owner"]?.textValue(), it["items"]!!.map(::spec)) }
        node["item"]?.let { return Operation.Item(ItemCall.valueOf(it["call"]!!.textValue()!!), spec(it["spec"]!!)) }
        fail("no operation $node")
    }

    private fun catalog(node: JsonNode) = DeclaredCatalog(node["owner"]?.textValue(), node["version"]?.textValue(), node["items"]!!.map(::spec))

    private fun policy(node: JsonNode?): SchemaPolicy {
        val defaults = SchemaPolicy()
        if (node == null) return defaults
        return SchemaPolicy(
            mode = node["mode"]?.textValue()?.let(SchemaMode::valueOf) ?: defaults.mode,
            recreateOnDrift = node["recreateOnDrift"]?.booleanValue() ?: defaults.recreateOnDrift,
            recreateOnStartup = node["recreateOnStartup"]?.booleanValue() ?: defaults.recreateOnStartup,
            violationSampleSize = node["violationSampleSize"]?.longValue()?.toInt() ?: defaults.violationSampleSize,
            replaceBlockingIndex = node["replaceBlockingIndex"]?.booleanValue() ?: defaults.replaceBlockingIndex,
        )
    }

    /** A spec: a `store.yaml` case's by its name, or written as its cases write one. */
    private fun spec(node: JsonNode): SchemaItemSpec {
        if (node.isTextual) return spec(storeCase(node.textValue()!!)["spec"]!!)
        val label = node["label"]!!.textValue()!!
        val properties = texts(node["properties"]!!)
        val name = node["name"]?.textValue()
        val ignoreCase = node["ignoreCase"]?.booleanValue() ?: false
        val options = node["options"]
        fun option(key: String) = options?.get(key)?.takeUnless { it.isNull }
        return when (SchemaItemKind.valueOf(node["kind"]!!.textValue()!!)) {
            SchemaItemKind.RANGE_INDEX -> RangeIndexSpec(label, properties, name, ignoreCase)
            SchemaItemKind.UNIQUENESS_CONSTRAINT -> UniquenessConstraintSpec(label, properties, name, node["nodeIdProperty"]?.textValue(), ignoreCase)
            SchemaItemKind.FULLTEXT_INDEX -> FullTextIndexSpec(label, properties, name, option("analyzer")?.textValue())
            SchemaItemKind.TEXT_INDEX -> TextIndexSpec(label, properties.single(), name, ignoreCase)
            SchemaItemKind.VECTOR_INDEX -> VectorIndexSpec(
                label, properties.single(), option("dimensions")!!.longValue().toInt(),
                SimilarityFunction.valueOf(option("similarity")!!.textValue()!!), name,
                option("hnswM")?.longValue()?.toInt(), option("hnswEfConstruction")?.longValue()?.toInt(),
                listOfNotNull(
                    option("quantizationEnabled")?.let { Neo4jVectorOptions(quantizationEnabled = it.booleanValue()) },
                    option("efRuntime")?.let { FalkorDbVectorOptions(efRuntime = it.longValue().toInt()) },
                ),
            )
        }
    }

    // ----- The report, written as the cases write it -----

    /** [spec] as a case names it: by the `store.yaml` case that declares it, or written out. */
    private fun specRef(spec: SchemaItemSpec): Any? =
        store["cases"]!!.firstOrNull { spec(it["spec"]!!) == spec }?.get("name")?.textValue() ?: specWritten(spec)

    private fun specWritten(spec: SchemaItemSpec): Map<String, Any?> {
        val m = linkedMapOf<String, Any?>("kind" to spec.kind.name, "label" to spec.label, "properties" to spec.properties)
        spec.name?.let { m["name"] = it }
        when (spec) {
            is RangeIndexSpec -> if (spec.ignoreCase) m["ignoreCase"] = true
            is TextIndexSpec -> if (spec.ignoreCase) m["ignoreCase"] = true
            is UniquenessConstraintSpec -> {
                if (spec.ignoreCase) m["ignoreCase"] = true
                spec.nodeIdProperty?.let { m["nodeIdProperty"] = it }
            }
            is FullTextIndexSpec -> spec.analyzer?.let { m["options"] = mapOf("analyzer" to it) }
            is VectorIndexSpec -> m["options"] = linkedMapOf<String, Any?>("dimensions" to spec.dimensions.toLong(), "similarity" to spec.similarity.name).also { o ->
                spec.hnswM?.let { o["hnswM"] = it.toLong() }
                spec.hnswEfConstruction?.let { o["hnswEfConstruction"] = it.toLong() }
                (spec.optionsFor(Neo4jVectorOptions.ENGINE) as Neo4jVectorOptions?)?.quantizationEnabled?.let { o["quantizationEnabled"] = it }
                (spec.optionsFor(FalkorDbVectorOptions.ENGINE) as FalkorDbVectorOptions?)?.efRuntime?.let { o["efRuntime"] = it.toLong() }
            }
        }
        return m
    }

    private fun itemWritten(item: SchemaItemInfo?): Any? = item?.let {
        linkedMapOf<String, Any?>("kind" to it.kind.name, "label" to it.label, "properties" to it.properties).also { m ->
            it.name?.let { v -> m["name"] = v }
            it.dimensions?.let { v -> m["dimensions"] = v.toLong() }
            it.similarity?.let { v -> m["similarity"] = v.name }
            it.analyzer?.let { v -> m["analyzer"] = v }
            it.status?.let { v -> m["status"] = v }
            it.quantizationEnabled?.let { v -> m["quantizationEnabled"] = v }
            it.hnswM?.let { v -> m["hnswM"] = v.toLong() }
            it.hnswEfConstruction?.let { v -> m["hnswEfConstruction"] = v.toLong() }
        }
    }

    private fun causesWritten(causes: List<Cause>) = causes.map { cause ->
        linkedMapOf<String, Any?>().also { m ->
            cause.sqlState?.let { m["sqlState"] = it }
            cause.vendorCode?.let { m["vendorCode"] = it.toLong() }
            cause.code?.let { m["code"] = it }
            cause.gqlStatus?.let { m["gqlStatus"] = it }
            cause.message?.let { m["message"] = it }
        }
    }

    private fun resultWritten(result: EnsureResult): Map<String, Any?> = when (result) {
        is EnsureResult.Created -> mapOf("created" to itemWritten(result.info))
        is EnsureResult.AlreadyMatching -> mapOf("alreadyMatching" to itemWritten(result.info))
        is EnsureResult.Drift -> mapOf("drift" to linkedMapOf("existing" to itemWritten(result.existing), "requested" to specRef(result.requested)))
        is EnsureResult.Recreated -> mapOf("recreated" to linkedMapOf("previous" to itemWritten(result.previous), "current" to itemWritten(result.current)))
        is EnsureResult.Violation -> mapOf("violation" to linkedMapOf("requested" to specRef(result.requested), "sample" to result.conflictingSample))
        is EnsureResult.BlockedByIndex -> mapOf("blockedByIndex" to linkedMapOf("requested" to specRef(result.requested), "index" to itemWritten(result.index)))
    }

    /** [report] as a case writes one, its numbers as JSON reads them back. */
    private fun written(report: DatabaseReport): Any? {
        val m = linkedMapOf<String, Any?>()
        if (report.owners.isNotEmpty()) m["owners"] = report.owners.map { owner ->
            linkedMapOf<String, Any?>("owner" to owner.owner).also { w ->
                if (owner.results.isNotEmpty()) w["results"] = owner.results.map { linkedMapOf<String, Any?>("spec" to specRef(it.spec)) + resultWritten(it.result) }
                if (owner.unbuilt.isNotEmpty()) w["unbuilt"] = owner.unbuilt.map { linkedMapOf("spec" to specRef(it.spec), "stillBuilding" to it.stillBuilding, "message" to it.message) }
                if (owner.orphans.isNotEmpty()) w["orphans"] = owner.orphans
                w["marker"] = owner.marker?.let { linkedMapOf("version" to it.version, "items" to it.items) }
                owner.failure?.let { w["failure"] = it }
                owner.removed?.let { w["removed"] = linkedMapOf("dropped" to it.dropped.toLong(), "kept" to it.kept.map(::specRef)) }
            }
        }
        report.item?.let { m["item"] = linkedMapOf("result" to it.result?.let(::resultWritten), "dropped" to it.dropped) }
        report.failure?.let { m["failure"] = it }
        report.rethrown?.let { m["rethrown"] = linkedMapOf("round" to it.round.toLong(), "causes" to causesWritten(it.error.causes)) }
        m["log"] = report.log.map { listOf(it.level.name, it.logger, it.message) }
        return plain(CypherJson.readTree(CypherJson.writeValueAsString(m)))
    }

    /** [value] with `{engine}` in its texts as [engine], as a case writes a store's name. */
    private fun engined(value: Any?, engine: String): Any? = when (value) {
        is String -> value.replace("{engine}", engine)
        is List<*> -> value.map { engined(it, engine) }
        is Map<*, *> -> value.entries.associate { (k, v) -> k to engined(v, engine) }
        else -> value
    }

    // ----- Plain values -----

    private fun item(node: JsonNode): SchemaItemInfo = SchemaItemInfo(
        kind = SchemaItemKind.valueOf(node["kind"]!!.textValue()!!),
        label = node["label"]!!.textValue()!!,
        properties = texts(node["properties"]!!),
        name = node["name"]?.textValue(),
        dimensions = node["dimensions"]?.longValue()?.toInt(),
        similarity = node["similarity"]?.textValue()?.let(SimilarityFunction::valueOf),
        analyzer = node["analyzer"]?.textValue(),
        status = node["status"]?.textValue(),
        quantizationEnabled = node["quantizationEnabled"]?.booleanValue(),
        hnswM = node["hnswM"]?.longValue()?.toInt(),
        hnswEfConstruction = node["hnswEfConstruction"]?.longValue()?.toInt(),
    )

    private fun cause(node: JsonNode): Cause = Cause(
        sqlState = node["sqlState"]?.textValue(),
        vendorCode = node["vendorCode"]?.longValue()?.toInt(),
        code = node["code"]?.textValue(),
        gqlStatus = node["gqlStatus"]?.textValue(),
        message = node["message"]?.textValue(),
    )

    private fun plain(node: JsonNode): Any? = when {
        node.isNull -> null
        node.isTextual -> node.textValue()
        node.isBoolean -> node.booleanValue()
        node.isIntegralNumber -> node.longValue()
        node.isNumber -> node.doubleValue()
        node.isArray -> node.map(::plain)
        node is ObjectNode -> node.properties().associate { it.key to plain(it.value) }
        else -> fail("not a value: $node")
    }

    private fun texts(node: JsonNode): List<String> = node.map { it.textValue()!! }

    // ----- The listing encoder, against store.yaml's listings -----

    @Test
    fun eachStoresRowsReadBackToTheItemsTheyAreWrittenFrom() {
        var checked = 0
        for (listing in store["listings"]!!) {
            val constraints = listing["listing"]!!.textValue() == "constraints"
            for ((id, expected) in (listing["stores"] as ObjectNode).properties()) {
                if (expected["invalid"]?.booleanValue() == true) continue
                val schema = StoreSchema.of(ddls[id] ?: continue)
                val items = expected["items"]!!.map(::item)
                // A listing of one label's several items (FalkorDB's) is read back alike only where they share its status.
                if (id == "falkordb" && items.groupBy { it.label }.values.any { of -> of.map { it.status }.distinct().size > 1 }) continue
                val rows = ListingRows.rows(id, items, constraints)
                val read = if (constraints) schema.parseConstraintRows(rows) else schema.parseIndexRows(rows)
                assertEquals(items.sortedBy { it.toString() }, read.sortedBy { it.toString() }, "$id, ${listing["name"]!!.textValue()}: the rows of its items")
                checked++
            }
        }
        assertTrue(checked > 0, "no listing was read back")
    }
}
