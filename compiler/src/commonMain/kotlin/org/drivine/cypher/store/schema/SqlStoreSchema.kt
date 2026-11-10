package org.drivine.cypher.store.schema

import org.drivine.DrivineInternalApi
import org.drivine.cypher.store.OracleSql
import org.drivine.cypher.store.PostgresSql
import org.drivine.cypher.store.SqliteSql
import org.drivine.cypher.store.ddl.OracleDdl
import org.drivine.cypher.store.ddl.PostgresDdl
import org.drivine.cypher.store.ddl.SqlStoreDdl
import org.drivine.cypher.store.ddl.SqliteDdl
import org.drivine.schema.ConstraintSpec
import org.drivine.schema.SchemaItemInfo
import org.drivine.schema.SchemaItemKind
import org.drivine.schema.SimilarityFunction

/**
 * The schema of a SQL store: what Drivine made is read back from the graph's catalog, a record a row, as a map of its
 * `kind`, `label`, `properties`, `name` and `details` (and on Oracle its `status`), since an index's expression doesn't say
 * what it was made for. An index and a constraint are listed alike, and read alike.
 *
 * A record of a kind or a similarity this module doesn't know is refused, as drivine4j refuses it: the catalog is
 * Drivine's own, and such a record isn't one it wrote.
 */
@DrivineInternalApi
abstract class SqlStoreSchema internal constructor() : StoreSchema {

    abstract override val ddl: SqlStoreDdl

    override val supportsIfNotExists = true
    override val supportsNamedItems = true

    /** Whether a record's `status` is read: Oracle's, which lists a range index `INCOMPLETE`; the others list none. */
    protected open val readsStatus: Boolean
        get() = false

    override fun parseIndexRows(rows: List<Any?>): List<SchemaItemInfo> = rows.mapNotNull { row ->
        val map = row as? Map<*, *> ?: return@mapNotNull null
        val details = map["details"] as? Map<*, *>
        SchemaItemInfo(
            kind = SchemaItemKind.valueOf(map["kind"] as String),
            label = map["label"] as String,
            properties = (map["properties"] as List<*>).map { it.toString() },
            name = map["name"] as? String,
            dimensions = (details?.get("dimensions") as? Number)?.toInt(),
            similarity = (details?.get("similarity") as? String)?.let(SimilarityFunction::valueOf),
            analyzer = details?.get("analyzer") as? String,
            status = if (readsStatus) map["status"] as? String else null,
        )
    }

    override fun parseConstraintRows(rows: List<Any?>): List<SchemaItemInfo> = parseIndexRows(rows)
}

/** Postgres's schema: errors told by their SQL state. */
@DrivineInternalApi
class PostgresSchema(override val ddl: PostgresDdl) : SqlStoreSchema() {

    /** `23505`: a second row for a unique key, or a unique index made over rows that already repeat one. */
    override fun isViolation(error: StoreError): Boolean = error.causes.any { it.isSqlError && it.sqlState == "23505" }

    /** A node's element id is its `nid`; [limit] of a key's nodes at most are named, the first made first. */
    override fun sampleConflictsQuery(spec: ConstraintSpec, limit: Int): String {
        val keys = spec.properties.map { PostgresSql.property("n", it) }
        val entries = spec.properties.zip(keys).joinToString(", ") { (property, key) -> "${PostgresSql.literal(property)}, $key" }
        val node = listOfNotNull(
            "'elementId', n.nid",
            StoreSchema.sampledNodeId(spec)?.let { "${PostgresSql.literal(it)}, ${PostgresSql.property("n", it)}" },
        ).joinToString(", ")
        return """
            SELECT jsonb_build_object('properties', jsonb_build_object($entries), 'count', COUNT(*),
                                      'nodes', to_jsonb((array_agg(jsonb_build_object($node) ORDER BY n.nid))[1:$limit]))
            FROM ${ddl.nodes} n
            WHERE ${PostgresSql.hasLabel("n", spec.label)} AND ${keys.joinToString(" AND ") { "$it IS NOT NULL" }}
            GROUP BY ${keys.joinToString(", ")}
            HAVING COUNT(*) > 1
            LIMIT $limit
        """.trimIndent()
    }
}

/** Oracle's schema: errors told by their vendor code, and a range index listed `INCOMPLETE` made whole. */
@DrivineInternalApi
class OracleSchema(override val ddl: OracleDdl) : SqlStoreSchema() {

    override val readsStatus: Boolean
        get() = true

    /**
     * A range index made before there were labelled indexes, or whose labelled index was dropped, with no uniqueness
     * constraint's index to stand for it: the listing gives it [OracleDdl.INCOMPLETE], and an ensure makes the rest.
     */
    override fun isIncomplete(existing: SchemaItemInfo): Boolean = existing.status == OracleDdl.INCOMPLETE

    /** `ORA-00001`, a second row for a unique key; `ORA-01452`, a unique index over rows that already repeat one. */
    override fun isViolation(error: StoreError): Boolean = error.causes.any { it.isSqlError && it.vendorCode in VIOLATIONS }

    /**
     * Each node of a key that repeats is numbered within its key, and counted with it, so that a row names [limit] of its
     * nodes at most however many hold the key. A node's element id is its `nid`.
     */
    override fun sampleConflictsQuery(spec: ConstraintSpec, limit: Int): String {
        val keys = spec.properties.map { "JSON_VALUE(n.props, ${OracleSql.path(it)})" }
        val aliases = keys.indices.map { "k$it" }
        val entries = spec.properties.zip(aliases).joinToString(", ") { (property, key) -> "${OracleSql.literal(property)} VALUE $key" }
        val nodeId = StoreSchema.sampledNodeId(spec)
        val node = listOfNotNull("'elementId' VALUE nid", nodeId?.let { "${OracleSql.literal(it)} VALUE i" }).joinToString(", ")
        val selected = (keys.zip(aliases).map { (key, alias) -> "$key AS $alias" } + listOfNotNull(nodeId?.let { "JSON_VALUE(n.props, ${OracleSql.path(it)}) AS i" }))
            .joinToString(", ")
        val partition = keys.joinToString(", ")
        return """
            SELECT JSON_OBJECT('properties' VALUE JSON_OBJECT($entries), 'count' VALUE MAX(c),
                               'nodes' VALUE JSON_ARRAYAGG(JSON_OBJECT($node) ORDER BY nid RETURNING JSON) RETURNING JSON)
            FROM (SELECT n.nid, $selected, COUNT(*) OVER (PARTITION BY $partition) AS c, ROW_NUMBER() OVER (PARTITION BY $partition ORDER BY n.nid) AS r
                  FROM ${ddl.nodes} n
                  WHERE ${OracleSql.hasLabel("n", spec.label)} AND ${keys.joinToString(" AND ") { "$it IS NOT NULL" }})
            WHERE c > 1 AND r <= $limit
            GROUP BY ${aliases.joinToString(", ")}
            FETCH FIRST $limit ROWS ONLY
        """.trimIndent()
    }

    private companion object {
        val VIOLATIONS = setOf(1, 1452)
    }
}

/** SQLite's schema: errors told by their message, as its driver gives one code for every constraint. */
@DrivineInternalApi
class SqliteSchema(override val ddl: SqliteDdl) : SqlStoreSchema() {

    /**
     * A second row for a unique key, or a unique index made over rows that already repeat one: `SQLITE_CONSTRAINT_UNIQUE`,
     * said in a driver's error's own message.
     */
    override fun isViolation(error: StoreError): Boolean =
        error.causes.any { it.isSqlError && it.message.orEmpty().contains("UNIQUE constraint failed") }

    /** A node's element id is its `nid`; [limit] of a key's nodes at most are named, the first made first. Its rows are JSON ([SqliteDdl.JSON_ROWS]). */
    override fun sampleConflictsQuery(spec: ConstraintSpec, limit: Int): String {
        val keys = spec.properties.map { SqliteSql.property("n", it) }
        val columns = keys.mapIndexed { i, key -> "$key AS k$i" }.joinToString(", ")
        val grouping = keys.indices.joinToString(", ") { "k$it" }
        val entries = spec.properties.mapIndexed { i, property -> "${SqliteSql.literal(property)}, k.k$i" }.joinToString(", ")
        val sampled = StoreSchema.sampledNodeId(spec)
        val node = listOfNotNull("'elementId', k.nid", sampled?.let { "${SqliteSql.literal(it)}, k.sampled" }).joinToString(", ")
        return """
            ${SqliteDdl.JSON_ROWS}
            SELECT json_object('properties', json_object($entries), 'count', MAX(k.total),
                               'nodes', json_group_array(json_object($node) ORDER BY k.nid) FILTER (WHERE k.position <= $limit))
            FROM (
                SELECT n.nid, $columns${sampled?.let { ", ${SqliteSql.property("n", it)} AS sampled" }.orEmpty()},
                       row_number() OVER (PARTITION BY ${keys.joinToString(", ")} ORDER BY n.nid) AS position,
                       COUNT(*) OVER (PARTITION BY ${keys.joinToString(", ")}) AS total
                FROM ${ddl.nodes} n
                WHERE ${SqliteSql.hasLabel("n", spec.label)} AND ${keys.joinToString(" AND ") { "$it IS NOT NULL" }}
            ) k
            WHERE k.total > 1
            GROUP BY $grouping
            LIMIT $limit
        """.trimIndent()
    }
}
