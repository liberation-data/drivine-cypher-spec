package org.drivine.cypher.store.schema

import org.drivine.DrivineInternalApi
import org.drivine.cypher.store.ddl.CatalogItem
import org.drivine.cypher.store.ddl.CypherNames
import org.drivine.cypher.store.ddl.DdlStatement
import org.drivine.cypher.store.ddl.FalkorDbDdl
import org.drivine.cypher.store.ddl.MemgraphDdl
import org.drivine.cypher.store.ddl.Neo4jDdl
import org.drivine.cypher.store.ddl.OracleDdl
import org.drivine.cypher.store.ddl.PostgresDdl
import org.drivine.cypher.store.ddl.SqliteDdl
import org.drivine.cypher.store.ddl.SqlStoreDdl
import org.drivine.cypher.store.ddl.StoreDdl
import org.drivine.schema.ConstraintSpec
import org.drivine.schema.EnsureResult
import org.drivine.schema.FalkorDbVectorOptions
import org.drivine.schema.FullTextIndexSpec
import org.drivine.schema.Neo4jVectorOptions
import org.drivine.schema.SchemaItemInfo
import org.drivine.schema.SchemaItemSpec
import org.drivine.schema.VectorIndexSpec

/**
 * What a schema manager knows of a store beyond the statements that make, drop and list its schema ([ddl]): how it reads
 * the store's listings back as [SchemaItemInfo]s, whether an item it found is the one a spec declares, and in the shape it
 * declares, what the store can do, and what its errors say. drivine4j's schema grammars (`SchemaGrammar` and each
 * engine's), written once here with no driver, so that every library decides alike of a store any of them made. Each is a
 * pure function of its arguments; running the statements, and deciding when to, stay the schema manager's. Pinned by the
 * spec's `store.yaml` (`schema`, `listings`, `matching` and `errors`).
 *
 * A spec is handed to each rule as it is made ([org.drivine.schema.made]), as drivine4j's schema managers hand it: one that
 * ignores case over the lowercased copies of its properties, which its item is made over, not the properties declared.
 *
 * A listing's rows are plain values, as a driver gives them: maps, lists, text, numbers, booleans and null. A row a store
 * gives as one map column is a map, a SQL store's JSON decoded; one it gives as several columns (Memgraph's `SHOW INDEX INFO`)
 * is a list of them. A number is read as its whole part, whatever its type: a library hands over a driver's own integer
 * (a JavaScript Neo4j driver's `Integer`) as a number.
 */
@DrivineInternalApi
interface StoreSchema {

    /** The statements of the store's schema, whose listings ([StoreDdl.listIndexesQuery], [StoreDdl.listConstraintsQuery]) this reads. */
    val ddl: StoreDdl

    /** The store's name, as a log line or a refusal names it. */
    val engine: String
        get() = ddl.engine

    /** Whether DDL takes `IF NOT EXISTS` / `IF EXISTS`. Where it doesn't, a schema manager finds an item before it makes or drops one. */
    val supportsIfNotExists: Boolean

    /** Whether the store keeps an item's name. Where it doesn't (FalkorDB), a spec's name is ignored and an item's is null. */
    val supportsNamedItems: Boolean

    /** Whether the store has Neo4j's TEXT index. Only Neo4j does; elsewhere a schema manager refuses one before it touches the store. */
    val supportsTextIndex: Boolean
        get() = false

    /** Whether a uniqueness constraint needs an exact-match index over its properties made first (FalkorDB). */
    val constraintsRequireBackingIndex: Boolean
        get() = false

    /**
     * Whether a constraint's create returns before it is made, so that it is polled until the listing says it is
     * `OPERATIONAL` or `FAILED` (FalkorDB). A violation then shows as `FAILED`, not as an error.
     */
    val constraintCreationIsAsync: Boolean
        get() = false

    /**
     * Whether an index's create and drop return before they are done, so that a listing can still give one just dropped,
     * and gives one just made as being built (FalkorDB, whose [SchemaItemInfo.status] is `UNDER CONSTRUCTION` until it is
     * `OPERATIONAL`): a schema manager polls until it is built, or gone.
     */
    val indexOperationsAreAsync: Boolean
        get() = false

    /**
     * A statement that waits on the server until the index named by the parameter `name` is online, for at most `timeout`
     * seconds, and fails if its build failed or did not end in time: Neo4j's `db.awaitIndex`. Neo4j's `CREATE INDEX`
     * returns while the index is still populating, and a write that commits just as it comes online can stop the
     * database, so no index is handed back before it. Null where the create returns the index built, or where the listing
     * gives the build's status instead ([indexOperationsAreAsync]).
     */
    val awaitIndexStatement: String?
        get() = null

    /**
     * With [awaitIndexStatement]: a statement that gives the state of the index named by the parameter `name`, as a row
     * `{state: …}` (`ONLINE`, `POPULATING`, `FAILED`), read when the wait fails for another reason than its timeout. The
     * wait fails for more than a failed build (an index not there, a connection lost), and only one listed `FAILED` is
     * taken for that.
     */
    val indexStateStatement: String?
        get() = null

    /** The items [rows], the rows of [StoreDdl.listIndexesQuery], describe; a row that describes none is passed over, and one row may describe several (FalkorDB's, one a label). */
    fun parseIndexRows(rows: List<Any?>): List<SchemaItemInfo>

    /** The items [rows], the rows of [StoreDdl.listConstraintsQuery], describe; a row that describes none is passed over. */
    fun parseConstraintRows(rows: List<Any?>): List<SchemaItemInfo>

    /**
     * The aliases the record [row] keeps, the other names its item goes by, which a recreate gives the item again: the
     * `aliases` of a SQL store's record's `details`, and none on a store that keeps none.
     */
    fun aliasesOf(row: Any?): List<String> =
        (((row as? Map<*, *>)?.get("details") as? Map<*, *>)?.get(ALIASES) as? List<*>).orEmpty().map { it.toString() }

    /**
     * Whether [existing] is the item [spec] declares: its kind, its label and the set of its properties, in any order.
     * Not its shape, which is [matchesShape]'s, and the difference between the two is [EnsureResult.Drift].
     */
    fun matchesIdentity(existing: SchemaItemInfo, spec: SchemaItemSpec): Boolean {
        if (existing.kind != spec.kind || existing.label != spec.label) return false
        return existing.properties.toSet() == spec.properties.toSet()
    }

    /**
     * Whether [existing], the item [spec] declares ([matchesIdentity]), has the shape it declares; where it hasn't, that's
     * drift. Only what the store reports is compared: a null on [existing] is a value the store doesn't give back, and
     * never drift, or an ensure would report it forever on a store that doesn't report it. A vector index's dimensions are
     * the exception, being compared always, as drivine4j does.
     */
    fun matchesShape(existing: SchemaItemInfo, spec: SchemaItemSpec): Boolean = when (spec) {
        is VectorIndexSpec -> {
            // Resolved for this store, so a Neo4j override is compared on Neo4j and ignored on FalkorDB.
            val tuning = spec.tuningFor(engine)
            existing.dimensions == spec.dimensions &&
                (existing.similarity == null || existing.similarity == spec.similarity) &&
                // Drift only when the spec pins a parameter and the store reports one, and they differ: an unpinned one
                // is the store's to choose, and never drifts when a server changes its defaults.
                pinnedMatches(tuning.hnswM, existing.hnswM) &&
                pinnedMatches(tuning.hnswEfConstruction, existing.hnswEfConstruction) &&
                matchesEngineVectorOptions(existing, spec)
        }
        // Drift only when the spec asks for an analyzer and the store reports one: only Neo4j does both.
        is FullTextIndexSpec -> spec.analyzer == null || existing.analyzer == null || existing.analyzer == spec.analyzer
        else -> true
    }

    /** Whether the store's own vector options in [spec] are met by [existing]: yes, on a store with none to compare. */
    fun matchesEngineVectorOptions(existing: SchemaItemInfo, spec: VectorIndexSpec): Boolean = true

    /**
     * What [spec] pins that this store cannot make, named as the spec names it, for a schema manager to say out loud:
     * empty where nothing is pinned, or the store makes all it pins. Options for another store are never named.
     */
    fun unsupportedVectorTuning(spec: VectorIndexSpec): List<String> {
        val tuning = spec.tuningFor(engine)
        return listOfNotNull(
            tuning.hnswM?.let { "hnswM" },
            tuning.hnswEfConstruction?.let { "hnswEfConstruction" },
            spec.optionsFor(engine)?.let { optionsName(it) },
        )
    }

    /**
     * Whether [existing], an item the listing gave, lacks part of what its create makes, so that an ensure makes it again
     * (Oracle's range index listed `INCOMPLETE`). Never, on a store whose items are made whole.
     */
    fun isIncomplete(existing: SchemaItemInfo): Boolean = false

    /**
     * What [rows], the rows of the listing [spec] is found in ([StoreDdl.listIndexesQuery] of its kind, or
     * [StoreDdl.listConstraintsQuery]), say of the form of the item [spec] declares ([ItemForm]): on a SQL store, from its
     * record's `details.form` and what the store has under the names Drivine gives the item's objects, which the listing
     * gives beside the records; [ItemForm.Current] on a graph engine, whose items Drivine makes in no form of its own. A
     * [spec] is handed as it is made, as to every rule.
     */
    fun formOf(spec: SchemaItemSpec, rows: List<Any?>): ItemForm = ItemForm.Current

    /**
     * What drops the index [name], one that stands under a name Drivine gives and isn't the item that name is for
     * ([ItemForm.Mismatch], [ItemForm.Mismatch.droppable]), before the item is made in its place. None on a store whose
     * items have no such name.
     */
    fun dropMismatched(name: String): List<DdlStatement> = emptyList()

    /**
     * What a recreate of [spec] drops first where [rows] list no record of it, and the objects under its names were read as
     * another library's form ([ItemForm.Stale]): only those whose text decided the form, each with the statements that
     * drop it, so the item is made again in this version's form over nothing of the other's. None where there is a record,
     * which the recreate drops by, or on a store whose items have no form read from their objects.
     */
    fun unrecordedDrops(spec: SchemaItemSpec, rows: List<Any?>): List<ObjectDrop> = emptyList()

    /**
     * The statement that samples the keys [spec] would refuse: each row a map of the `properties` that repeat, the
     * `count` of nodes that hold them, and `nodes`, [limit] of them at most, each named by its `elementId` ([elementId])
     * and, where the spec knows it, its [ConstraintSpec.nodeIdProperty]; [limit] rows at most. A violation is reported
     * only where it finds a key that repeats ([EnsureResult.Violation]).
     */
    fun sampleConflictsQuery(spec: ConstraintSpec, limit: Int): String {
        // A name that isn't a plain identifier, a bag's key or a lowercased copy, is quoted, as DDL quotes it.
        val notNull = spec.properties.joinToString(" AND ") { "n.${CypherNames.identifier(it)} IS NOT NULL" }
        val keyAliases = spec.properties.mapIndexed { i, p -> "n.${CypherNames.identifier(p)} AS _k$i" }.joinToString(", ")
        val keyMap = spec.properties.mapIndexed { i, p -> "${CypherNames.identifier(p)}: _k$i" }.joinToString(", ")
        val node = listOfNotNull(
            "elementId: ${elementId("n")}",
            sampledNodeId(spec)?.let { "${CypherNames.identifier(it)}: n.${CypherNames.identifier(it)}" },
        ).joinToString(", ")
        return """
            MATCH (n:${spec.label})
            WHERE $notNull
            WITH $keyAliases, count(*) AS _count, collect({$node}) AS _nodes
            WHERE _count > 1
            RETURN {properties: {$keyMap}, count: _count, nodes: _nodes[0..$limit]}
            LIMIT $limit
        """.trimIndent()
    }

    /** The store's own id of the node [variable] is bound to, in Cypher: `id(n)` unless the store has another. */
    fun elementId(variable: String): String = "id($variable)"

    /**
     * Whether [error], from making a uniqueness constraint, says the data violates it, rather than the constraint being
     * refused for another reason or the store failing. A suspicion only: a violation is reported where
     * [sampleConflictsQuery] finds a key that repeats, and the error is thrown where it finds none.
     */
    fun isViolation(error: StoreError): Boolean

    /**
     * Whether [error], from making a uniqueness constraint, says a plain range index over the same label and properties is
     * in the way of it ([EnsureResult.BlockedByIndex]). Only on a store whose constraint brings its own index (Neo4j).
     */
    fun isBlockedByIndex(error: StoreError): Boolean = false

    /** Whether [error], from [awaitIndexStatement], says the index was still being built when the wait ran out, rather than that its build failed. */
    fun isBuildTimeout(error: StoreError): Boolean = false

    /**
     * Whether [error], from building a uniqueness constraint's unique index anew beside the old one
     * ([org.drivine.cypher.store.ddl.UniqueRecreation.build]), says an index over the same expressions is there already,
     * so it can't be built beside it (Oracle's `ORA-01408`). Never, on a store that builds a second index over the same
     * expressions.
     */
    fun isAlreadyIndexed(error: StoreError): Boolean = false

    companion object {

        /** The key of a record's `details` that holds its aliases, as the module's DDL writes them there. */
        const val ALIASES = SqlStoreDdl.ALIASES

        /** The schema of the store [ddl] makes: for [Neo4jDdl], Neo4j 5's and later (see [Neo4jSchema] for Neo4j 4's). */
        fun of(ddl: StoreDdl): StoreSchema = when (ddl) {
            Neo4jDdl -> Neo4jSchema()
            is MemgraphDdl -> MemgraphSchema(ddl)
            FalkorDbDdl -> FalkorDbSchema
            is PostgresDdl -> PostgresSchema(ddl)
            is OracleDdl -> OracleSchema(ddl)
            is SqliteDdl -> SqliteSchema(ddl)
            else -> throw IllegalArgumentException("No schema is known for ${ddl.engine}")
        }

        /**
         * The property a violation's sample names a node by, beside its element id: [spec]'s
         * [ConstraintSpec.nodeIdProperty], unless it is one of the keys, which every node of a conflict holds alike.
         */
        fun sampledNodeId(spec: ConstraintSpec): String? = spec.nodeIdProperty?.takeIf { it !in spec.properties }
    }
}

/** The item as the statements that drop it take it ([StoreDdl.drop]): its kind, label, properties and name. */
@DrivineInternalApi
val SchemaItemInfo.catalogItem: CatalogItem
    get() = CatalogItem(kind, label, properties, name)

/**
 * Whether an [observed] value contradicts a [pinned] one. Both nulls are permissive: a null [pinned] leaves the choice to
 * the store, and a null [observed] is one the store doesn't report. Only two values present and different are drift.
 */
internal fun <T> pinnedMatches(pinned: T?, observed: T?): Boolean = pinned == null || observed == null || pinned == observed

/** The name of an options class, as drivine4j names it by its Kotlin class: spelled out, so every target gives it alike. */
private fun optionsName(options: org.drivine.schema.EngineVectorOptions): String = when (options) {
    is Neo4jVectorOptions -> "Neo4jVectorOptions"
    is FalkorDbVectorOptions -> "FalkorDbVectorOptions"
}
