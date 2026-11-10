package org.drivine.cypher.store.marker

import org.drivine.DrivineInternalApi
import org.drivine.cypher.store.OracleSql
import org.drivine.cypher.store.PostgresSql
import org.drivine.cypher.store.SqliteSql
import org.drivine.cypher.store.ddl.FalkorDbDdl
import org.drivine.cypher.store.ddl.MemgraphDdl
import org.drivine.cypher.store.ddl.Neo4jDdl
import org.drivine.cypher.store.ddl.OracleDdl
import org.drivine.cypher.store.ddl.PostgresDdl
import org.drivine.cypher.store.ddl.SqliteDdl
import org.drivine.cypher.store.ddl.StoreDdl
import org.drivine.cypher.store.marker.StoreStamp.KEY_PROPERTY
import org.drivine.cypher.store.marker.StoreStamp.LABEL
import org.drivine.schema.UniquenessConstraintSpec

/**
 * The store's identity: one node of the graph, `(:DrivineStore {key: 'singleton', storeId, assignedAt, engine})`, made on
 * the first sight of the store, so that it travels with the data and every client of the store reads the same one. What it
 * holds is written once, here, as drivine4j's `StoreIdentityResolver` writes it, and pinned by the spec's `marker.yaml`.
 */
@DrivineInternalApi
object StoreStamp {

    /** The stamp's label, reserved to Drivine. */
    const val LABEL = "DrivineStore"

    /** The property a stamp is merged on, which [CONSTRAINT] makes unique. */
    const val KEY_PROPERTY = "key"

    /** The value of [KEY_PROPERTY]: fixed, so a second boot matches the node the first one wrote. */
    const val KEY = "singleton"

    /** What keeps concurrent first boots from leaving two stamps, where the store can make it. */
    val CONSTRAINT: UniquenessConstraintSpec = UniquenessConstraintSpec(LABEL, KEY_PROPERTY)

    /**
     * What [StoreStampStatements.assignIfAbsent] binds for a stamp of [storeId], a new UUID's text, assigned at
     * [assignedAt], ISO-8601 text as Java's `Instant.toString` writes it, on [engine], the store's name as drivine4j's
     * `DatabaseType.value` gives it (`NEO4J`, `POSTGRES` …). The caller gives each, as the module has no clock and no
     * random source of its own.
     */
    fun assignParameters(storeId: String, assignedAt: String, engine: String): Map<String, Any?> =
        mapOf("key" to KEY, "storeId" to storeId, "assignedAt" to assignedAt, "engine" to engine)

    /**
     * The `engine` a stamp of the store [ddl] makes records, as drivine4j's `DatabaseType.value` names it, so a stamp
     * reads the same whichever library wrote it. A store with no [StoreDdl] here, as Neptune, is named by its library.
     */
    fun engine(ddl: StoreDdl): String = when (ddl) {
        is PostgresDdl -> "POSTGRES"
        is OracleDdl -> "ORACLE"
        is SqliteDdl -> "SQLITE"
        is MemgraphDdl -> "MEMGRAPH"
        Neo4jDdl -> "NEO4J"
        FalkorDbDdl -> "FALKORDB"
        else -> throw IllegalArgumentException("No engine name for ${ddl.engine}: its library names it.")
    }
}

/**
 * The statements that keep the store's stamp ([StoreStamp]), in a store's own language, as drivine4j's
 * `StoreStampStatements` of each store writes them. Running them stays each library's; of the stamps read, each takes the
 * first, as drivine4j's `StoreIdentityResolver` does.
 */
@DrivineInternalApi
interface StoreStampStatements {

    /** What makes what the other statements need, run once before them: the graph's tables on a SQL store; else null. */
    val prepare: String?

    /** Makes the stamp if there is none: binds what [StoreStamp.assignParameters] gives. */
    val assignIfAbsent: String

    /**
     * Reads every stamp as a map `{id, assignedAt}`, the oldest first, tie-broken by id, so every reader picks the same
     * one, the first. A map and not two columns, as a row of several columns comes back as a list, which can't be read by
     * name.
     */
    val readStamps: String

    companion object {
        /** The stamp's statements on the store [ddl] makes: in SQL on Postgres, Oracle and SQLite, and in Cypher on a graph engine. */
        fun of(ddl: StoreDdl): StoreStampStatements = when (ddl) {
            is PostgresDdl -> PostgresStoreStampStatements(ddl)
            is OracleDdl -> OracleStoreStampStatements(ddl)
            is SqliteDdl -> SqliteStoreStampStatements(ddl)
            else -> CypherStoreStampStatements
        }
    }
}

/** The stamp's statements in Cypher, which Neo4j, Memgraph and FalkorDB share. */
@DrivineInternalApi
object CypherStoreStampStatements : StoreStampStatements {

    override val prepare: String? = null

    override val assignIfAbsent: String = """
        MERGE (s:$LABEL { $KEY_PROPERTY: ${'$'}key })
        ON CREATE SET s.storeId = ${'$'}storeId,
                      s.assignedAt = ${'$'}assignedAt,
                      s.engine = ${'$'}engine
    """.trimIndent()

    override val readStamps: String = """
        MATCH (s:$LABEL)
        RETURN { id: s.storeId, assignedAt: s.assignedAt } AS stamp
        ORDER BY s.assignedAt, s.storeId
    """.trimIndent()
}

/** The stamp on Postgres: a node of the graph, as on the graph engines. */
@DrivineInternalApi
class PostgresStoreStampStatements(private val ddl: PostgresDdl) : StoreStampStatements {
    private val stored = PostgresSql.hasLabel("n", LABEL)

    override val prepare: String get() = ddl.ensuring

    override val assignIfAbsent: String = """
        INSERT INTO ${ddl.nodes} (labels, props)
        SELECT ${PostgresSql.labelArray(LABEL)},
               jsonb_build_object(${PostgresSql.literal(KEY_PROPERTY)}, CAST(${'$'}key AS text), 'storeId', CAST(${'$'}storeId AS text), 'assignedAt', CAST(${'$'}assignedAt AS text), 'engine', CAST(${'$'}engine AS text))
        WHERE NOT EXISTS (SELECT 1 FROM ${ddl.nodes} n WHERE $stored AND ${PostgresSql.property("n", KEY_PROPERTY)} = CAST(${'$'}key AS text))
    """.trimIndent()

    override val readStamps: String = """
        SELECT jsonb_build_object('id', ${PostgresSql.property("n", "storeId")}, 'assignedAt', ${PostgresSql.property("n", "assignedAt")})
        FROM ${ddl.nodes} n WHERE $stored
        ORDER BY ${PostgresSql.property("n", "assignedAt")}, ${PostgresSql.property("n", "storeId")}
    """.trimIndent()
}

/** The stamp on Oracle: a node of the graph, as on the graph engines. */
@DrivineInternalApi
class OracleStoreStampStatements(private val ddl: OracleDdl) : StoreStampStatements {
    private val stored = OracleSql.hasLabel("n", LABEL)

    override val prepare: String get() = ddl.ensuring

    override val assignIfAbsent: String = """
        INSERT INTO ${ddl.nodes} (labels, props)
        SELECT ${OracleSql.literal("[\"$LABEL\"]")}, JSON_OBJECT(${OracleSql.literal(KEY_PROPERTY)} VALUE ${'$'}key, 'storeId' VALUE ${'$'}storeId, 'assignedAt' VALUE ${'$'}assignedAt, 'engine' VALUE ${'$'}engine)
        FROM dual
        WHERE NOT EXISTS (SELECT 1 FROM ${ddl.nodes} n WHERE $stored AND ${OracleSql.property("n", KEY_PROPERTY)} = ${'$'}key)
    """.trimIndent()

    override val readStamps: String = """
        SELECT JSON_OBJECT('id' VALUE ${OracleSql.property("n", "storeId")}, 'assignedAt' VALUE ${OracleSql.property("n", "assignedAt")} RETURNING JSON)
        FROM ${ddl.nodes} n WHERE $stored
        ORDER BY ${OracleSql.property("n", "assignedAt")}, ${OracleSql.property("n", "storeId")}
    """.trimIndent()
}

/** The stamp on SQLite: a node of the graph, as on the graph engines. Its stamps are read as JSON ([SqliteDdl.JSON_ROWS]). */
@DrivineInternalApi
class SqliteStoreStampStatements(private val ddl: SqliteDdl) : StoreStampStatements {
    private val stored = SqliteSql.hasLabel("n", LABEL)

    /** The table of nodes, which is all the stamp needs made. */
    override val prepare: String get() = ddl.ddl.first()

    override val assignIfAbsent: String = """
        INSERT INTO ${ddl.nodes} (labels, props)
        SELECT ${SqliteSql.labelArray(LABEL)},
               json_object(${SqliteSql.literal(KEY_PROPERTY)}, CAST(${'$'}key AS TEXT), 'storeId', CAST(${'$'}storeId AS TEXT), 'assignedAt', CAST(${'$'}assignedAt AS TEXT), 'engine', CAST(${'$'}engine AS TEXT))
        WHERE NOT EXISTS (SELECT 1 FROM ${ddl.nodes} n WHERE $stored AND ${SqliteSql.property("n", KEY_PROPERTY)} = CAST(${'$'}key AS TEXT))
    """.trimIndent()

    override val readStamps: String = """
        ${SqliteDdl.JSON_ROWS}
        SELECT json_object('id', ${SqliteSql.property("n", "storeId")}, 'assignedAt', ${SqliteSql.property("n", "assignedAt")})
        FROM ${ddl.nodes} n WHERE $stored
        ORDER BY ${SqliteSql.property("n", "assignedAt")}, ${SqliteSql.property("n", "storeId")}
    """.trimIndent()
}
