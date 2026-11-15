package org.drivine.cypher.api

import org.drivine.DrivineInternalApi
import org.drivine.cypher.json.CypherJson
import org.drivine.cypher.json.JsonNode
import org.drivine.cypher.json.ObjectNode
import org.drivine.cypher.store.ddl.FalkorDbDdl
import org.drivine.cypher.store.ddl.MemgraphDdl
import org.drivine.cypher.store.ddl.Neo4jDdl
import org.drivine.cypher.store.ddl.OracleDdl
import org.drivine.cypher.store.ddl.OracleFunctionProbes
import org.drivine.cypher.store.ddl.PostgresDdl
import org.drivine.cypher.store.ddl.SqliteDdl
import org.drivine.cypher.store.ddl.StoreDdl
import org.drivine.cypher.store.marker.MarkerStatements
import org.drivine.cypher.store.marker.SchemaMarker
import org.drivine.cypher.store.marker.StoreLayout
import org.drivine.cypher.store.marker.StoreStamp
import org.drivine.cypher.store.marker.StoreStampStatements
import org.drivine.schema.made

/**
 * A store's own statements for a host with no Kotlin types, asked of the JSON entry ([CompileJson]) in place of a statement
 * (liberation-data/drivine-cypher-spec#107): the store's DDL, the block that makes it at its version, and the statements
 * of each node's stamp and of the schema marker, each the module's own, so TypeScript and Python make and stamp a store as
 * drivine4j does through the Kotlin types, with no copy of their own:
 *
 * - `{"storeStatements": {"store": "postgres", "graph": "g"}}`, the store one of `postgres`, `oracle`, `sqlite`, `neo4j`,
 *   `memgraph` and `falkordb`, and the graph its name on a SQL store (a graph engine takes none). Answered with:
 *   - `engine`, the store as a stamp names it ([StoreStamp.engine]), and `layout`, the layout a stamp of it records
 *     ([StoreLayout.current]);
 *   - on a SQL store, `ddl`, the statements that make the graph's tables, indexes and functions, in order; `ensuring`, the
 *     block that makes them only where the store's version is not the module's (null on SQLite, whose `ddl` is all
 *     `IF NOT EXISTS`); and on Oracle, `functions`, each function's own statement, and `versions`, each function's
 *     `-- drivine:<hash>` by name, which `ensuring` reads to make again a function of another version; and `storedFunctions`, the statement that
 *     finds whether the session may call the functions each compiler flag names, by the flag's name (`nullsEqualStored`,
 *     `eraStored`, `temporalArithmeticStored`), and `noFunction`, the error numbers a probe fails with where it finds none
 *     ([OracleFunctionProbes]);
 *   - `stamp`, [StoreStampStatements.of] the store: `prepare`, `assignIfAbsent`, `readStamps`, `foreignNodes`,
 *     `restampLayout`, `layoutObjects` and `constraint`, the statements that make the stamp's uniqueness constraint after
 *     `prepare`, each null where the store has none;
 *   - `marker`, [MarkerStatements.of] the store: `prepare`, `read` (the statement that reads each of the marker's
 *     properties, by its name), `merge`, `deleteAllButNewest`, `delete`, `scopes`, `itemsMark`, the text the store keeps
 *     before a marker's items ([MarkerStatements.storedItems]), empty where it keeps them as they are, and `constraint`,
 *     the statements that make the marker's uniqueness constraint after `prepare`.
 * - The same request with `stampParameters: {"storeId": …, "assignedAt": …}`, `restamp: true` or
 *   `markerParameters: {"scope": …, "version": …, "items": …, "appliedAt": <ms>}` is answered `{"parameters": {…}}`, what
 *   the stamp's `assignIfAbsent`, its `restampLayout` or the marker's `merge` binds ([StoreStamp.assignParameters],
 *   [StoreStamp.restampParameters], [MarkerStatements.mergeParameters]), so a host binds them as drivine4j does.
 */
@DrivineInternalApi
internal object StoreJson {

    const val KIND = "storeStatements"

    /** The answer to [tree], a request that gives [KIND]. */
    fun answer(tree: ObjectNode): String = try {
        val given = tree[KIND] as? ObjectNode ?: throw IllegalArgumentException("A $KIND request gives {\"store\": …, \"graph\": …}.")
        val ddl = ddl(given)
        when {
            given.has("stampParameters") -> parameters(stampParameters(ddl, given["stampParameters"]))
            given.has("restamp") -> parameters(StoreStamp.restampParameters(StoreStamp.engine(ddl)))
            given.has("markerParameters") -> parameters(markerParameters(ddl, given["markerParameters"]))
            else -> statements(ddl)
        }
    } catch (e: Exception) {
        // As every other request answers a failure: a host is answered, never thrown at.
        CompileJson.error(e.message ?: e.toString())
    }

    /** The store [given] names, `{"store": …, "graph": …}`, as every request of a store reads it. */
    internal fun ddl(given: ObjectNode, kind: String = KIND): StoreDdl {
        val store = given["store"]?.takeIf { it.isTextual }?.textValue()
            ?: throw IllegalArgumentException("A $kind request names its store, one of ${STORES.joinToString(", ")}.")
        fun graph(): String = given["graph"]?.takeIf { it.isTextual }?.textValue()
            ?: throw IllegalArgumentException("A $kind request on $store names its graph.")
        return when (store) {
            "postgres" -> PostgresDdl(graph())
            "oracle" -> OracleDdl(graph())
            "sqlite" -> SqliteDdl(graph())
            "neo4j" -> Neo4jDdl
            "memgraph" -> MemgraphDdl()
            "falkordb" -> FalkorDbDdl
            else -> throw IllegalArgumentException("No store $store: ${STORES.joinToString(", ")}.")
        }
    }

    private fun statements(ddl: StoreDdl): String {
        val engine = StoreStamp.engine(ddl)
        val stamp = StoreStampStatements.of(ddl)
        val marker = MarkerStatements.of(ddl)
        val answer = linkedMapOf<String, Any?>("engine" to engine, "layout" to StoreLayout.current(engine))
        when (ddl) {
            is PostgresDdl -> { answer["ddl"] = ddl.ddl; answer["ensuring"] = ddl.ensuring }
            is OracleDdl -> {
                answer["ddl"] = ddl.ddl; answer["ensuring"] = ddl.ensuring; answer["functions"] = ddl.functions; answer["versions"] = ddl.versions
                answer["storedFunctions"] = linkedMapOf("probes" to OracleFunctionProbes.probes(ddl.name), "noFunction" to OracleFunctionProbes.NO_FUNCTION)
            }
            is SqliteDdl -> { answer["ddl"] = ddl.ddl; answer["ensuring"] = null }
            else -> {}
        }
        answer["stamp"] = linkedMapOf(
            "prepare" to stamp.prepare, "assignIfAbsent" to stamp.assignIfAbsent, "readStamps" to stamp.readStamps,
            "foreignNodes" to stamp.foreignNodes, "restampLayout" to stamp.restampLayout, "layoutObjects" to stamp.layoutObjects,
            "constraint" to constraint(ddl, StoreStamp.CONSTRAINT),
        )
        answer["marker"] = linkedMapOf(
            "prepare" to marker.prepare,
            "read" to MARKER_PROPERTIES.associateWith(marker::read),
            "merge" to marker.merge, "deleteAllButNewest" to marker.deleteAllButNewest, "delete" to marker.delete, "scopes" to marker.scopes,
            "itemsMark" to marker.storedItems(""),
            "constraint" to constraint(ddl, SchemaMarker.CONSTRAINT),
        )
        return CypherJson.writeValueAsString(CypherJson.valueToTree(answer))
    }

    /**
     * The statements that make [spec], the stamp's or the marker's uniqueness constraint, on the store [ddl] makes, after its
     * `prepare`: each a text, or a store's native command as `{"native": [<arg>, …]}` (FalkorDB's), as a plan's action writes one.
     */
    private fun constraint(ddl: StoreDdl, spec: org.drivine.schema.UniquenessConstraintSpec): List<Any> {
        val made = ddl.createConstraint(spec.made()).map {
            when (it) {
                is org.drivine.cypher.store.ddl.DdlStatement.Text -> it.statement
                is org.drivine.cypher.store.ddl.DdlStatement.Native -> mapOf("native" to it.args)
            }
        }
        // A SQL store's create makes the store first, as `prepare` does: those statements are left out, as `marker.yaml` pins them.
        val prefix: List<String> = when (ddl) {
            is PostgresDdl -> listOf(ddl.ensuring)
            is OracleDdl -> listOf(ddl.ensuring)
            is SqliteDdl -> ddl.ddl
            else -> emptyList()
        }
        check(made.take(prefix.size) == prefix) { "The constraint's create on ${StoreStamp.engine(ddl)} doesn't make the store first" }
        return made.drop(prefix.size)
    }

    private fun stampParameters(ddl: StoreDdl, given: JsonNode?): Map<String, Any?> {
        fun text(name: String) = given?.get(name)?.takeIf { it.isTextual }?.textValue()
            ?: throw IllegalArgumentException("stampParameters gives $name as text.")
        return StoreStamp.assignParameters(text("storeId"), text("assignedAt"), StoreStamp.engine(ddl))
    }

    private fun markerParameters(ddl: StoreDdl, given: JsonNode?): Map<String, Any?> {
        require(given is ObjectNode) { "markerParameters gives {\"scope\": …, \"version\": …, \"items\": …, \"appliedAt\": …}." }
        val scope = given["scope"]?.takeIf { it.isTextual }?.textValue() ?: throw IllegalArgumentException("markerParameters gives scope as text.")
        val version = given["version"]?.takeUnless { it.isNull }?.let { it.textValue() ?: throw IllegalArgumentException("markerParameters gives version as text or null.") }
        val items = given["items"]?.takeIf { it.isTextual }?.textValue() ?: throw IllegalArgumentException("markerParameters gives items as text.")
        val appliedAt = given["appliedAt"]?.takeIf { it.isIntegralNumber }?.longValue()
            ?: throw IllegalArgumentException("markerParameters gives appliedAt as a whole number of milliseconds.")
        return MarkerStatements.of(ddl).mergeParameters(scope, version, items, appliedAt)
    }

    private fun parameters(parameters: Map<String, Any?>): String =
        CypherJson.writeValueAsString(CypherJson.valueToTree(mapOf("parameters" to parameters)))

    private val STORES = listOf("postgres", "oracle", "sqlite", "neo4j", "memgraph", "falkordb")

    /** The marker's properties a host reads, each by [MarkerStatements.read]. */
    private val MARKER_PROPERTIES = listOf(SchemaMarker.VERSION, SchemaMarker.ITEMS)
}
