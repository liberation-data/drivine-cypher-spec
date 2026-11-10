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
 * The store's identity: one node of the graph, `(:DrivineStore {key: 'singleton', storeId, assignedAt, engine, layout})`, made on
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
        mapOf("key" to KEY, "storeId" to storeId, "assignedAt" to assignedAt, "engine" to engine, "layout" to StoreLayout.current(engine))

    /** What [StoreStampStatements.restampLayout] binds on the store [engine] names: the stamp's key and the layout it is given. */
    fun restampParameters(engine: String): Map<String, Any?> = mapOf("key" to KEY, "layout" to StoreLayout.current(engine))

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
     * Reads every stamp as a map `{id, assignedAt, layout}`, the oldest first, tie-broken by id, so every reader picks the
     * same one, the first. A map and not several columns, as a row of several columns comes back as a list, which can't be
     * read by name. `layout` is null where the stamp has none: see [StoreLayout].
     */
    val readStamps: String

    /**
     * Gives a row where the graph holds a node other than Drivine's own, the stamp and the schema marker, and none where it
     * holds none: what [StoreLayout.verdict] is given as `foreignNodes`.
     */
    val foreignNodes: String

    /**
     * Sets the stamp's `layout`, binding what [StoreStamp.restampParameters] gives, only while the graph holds no node but
     * Drivine's own, in one statement: see [StoreLayout.Verdict.Stamp]. Null on a graph engine, whose layout is its own.
     */
    val restampLayout: String?

    /**
     * Lists the indexes, tables and triggers Drivine may have made for the graph, each a map `{name, type}`, so that
     * [StoreLayout.drops] finds an earlier layout's among them. Null on a graph engine.
     */
    val layoutObjects: String?

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
        RETURN { id: s.storeId, assignedAt: s.assignedAt, layout: s.layout } AS stamp
        ORDER BY s.assignedAt, s.storeId
    """.trimIndent()

    override val foreignNodes: String = """
        MATCH (n)
        WHERE ${StoreLayout.OWN_LABELS.joinToString(" AND ") { "NOT n:`$it`" }}
        RETURN 1 AS foreignNode
        LIMIT 1
    """.trimIndent()

    override val restampLayout: String? = null

    override val layoutObjects: String? = null
}

/** The stamp on Postgres: a node of the graph, as on the graph engines. */
@DrivineInternalApi
class PostgresStoreStampStatements(private val ddl: PostgresDdl) : StoreStampStatements {
    private val stored = PostgresSql.hasLabel("n", LABEL)

    override val prepare: String get() = ddl.ensuring

    override val assignIfAbsent: String = """
        INSERT INTO ${ddl.nodes} (labels, props)
        SELECT ${PostgresSql.labelArray(LABEL)},
               jsonb_build_object(${PostgresSql.literal(KEY_PROPERTY)}, CAST(${'$'}key AS text), 'storeId', CAST(${'$'}storeId AS text), 'assignedAt', CAST(${'$'}assignedAt AS text), 'engine', CAST(${'$'}engine AS text), 'layout', CAST(${'$'}layout AS integer))
        WHERE NOT EXISTS (SELECT 1 FROM ${ddl.nodes} n WHERE $stored AND ${PostgresSql.property("n", KEY_PROPERTY)} = CAST(${'$'}key AS text))
    """.trimIndent()

    override val readStamps: String = """
        SELECT jsonb_build_object('id', ${PostgresSql.property("n", "storeId")}, 'assignedAt', ${PostgresSql.property("n", "assignedAt")}, 'layout', ${PostgresSql.value("n", "layout")})
        FROM ${ddl.nodes} n WHERE $stored
        ORDER BY ${PostgresSql.property("n", "assignedAt")}, ${PostgresSql.property("n", "storeId")}
    """.trimIndent()

    override val foreignNodes: String = "SELECT 1 FROM ${ddl.nodes} n WHERE NOT ${StoreLayout.postgresOwn("n")} LIMIT 1"

    override val restampLayout: String = """
        UPDATE ${ddl.nodes} n SET props = n.props || jsonb_build_object('layout', CAST(${'$'}layout AS integer))
        WHERE $stored AND ${PostgresSql.property("n", KEY_PROPERTY)} = CAST(${'$'}key AS text)
          AND NOT EXISTS (SELECT 1 FROM ${ddl.nodes} o WHERE NOT ${StoreLayout.postgresOwn("o")})
    """.trimIndent()

    override val layoutObjects: String = """
        SELECT jsonb_build_object('name', indexname, 'type', 'INDEX') FROM pg_indexes
        WHERE schemaname = current_schema() AND tablename = ${PostgresSql.literal(ddl.nodes.lowercase())}
        ORDER BY indexname
    """.trimIndent()
}

/** The stamp on Oracle: a node of the graph, as on the graph engines. */
@DrivineInternalApi
class OracleStoreStampStatements(private val ddl: OracleDdl) : StoreStampStatements {
    private val stored = OracleSql.hasLabel("n", LABEL)

    override val prepare: String get() = ddl.ensuring

    override val assignIfAbsent: String = """
        INSERT INTO ${ddl.nodes} (labels, props)
        SELECT ${OracleSql.literal("[\"$LABEL\"]")}, JSON_OBJECT(${OracleSql.literal(KEY_PROPERTY)} VALUE ${'$'}key, 'storeId' VALUE ${'$'}storeId, 'assignedAt' VALUE ${'$'}assignedAt, 'engine' VALUE ${'$'}engine, 'layout' VALUE TO_NUMBER(${'$'}layout))
        FROM dual
        WHERE NOT EXISTS (SELECT 1 FROM ${ddl.nodes} n WHERE $stored AND ${OracleSql.property("n", KEY_PROPERTY)} = ${'$'}key)
    """.trimIndent()

    override val readStamps: String = """
        SELECT JSON_OBJECT('id' VALUE ${OracleSql.property("n", "storeId")}, 'assignedAt' VALUE ${OracleSql.property("n", "assignedAt")}, 'layout' VALUE ${OracleSql.number("n", "layout")} RETURNING JSON)
        FROM ${ddl.nodes} n WHERE $stored
        ORDER BY ${OracleSql.property("n", "assignedAt")}, ${OracleSql.property("n", "storeId")}
    """.trimIndent()

    override val foreignNodes: String = "SELECT 1 FROM ${ddl.nodes} n WHERE NOT ${StoreLayout.oracleOwn("n")} FETCH FIRST 1 ROWS ONLY"

    override val restampLayout: String = """
        UPDATE ${ddl.nodes} n SET n.props = JSON_TRANSFORM(n.props, SET '${'$'}.layout' = TO_NUMBER(${'$'}layout))
        WHERE $stored AND ${OracleSql.property("n", KEY_PROPERTY)} = ${'$'}key
          AND NOT EXISTS (SELECT 1 FROM ${ddl.nodes} o WHERE NOT ${StoreLayout.oracleOwn("o")})
    """.trimIndent()

    override val layoutObjects: String = """
        SELECT JSON_OBJECT('name' VALUE LOWER(object_name), 'type' VALUE object_type RETURNING JSON) FROM user_objects
        WHERE object_type IN ('INDEX', 'TABLE', 'TRIGGER') AND LOWER(object_name) LIKE ${OracleSql.literal(ddl.name.lowercase().replace("_", "\\_") + "\\_%")} ESCAPE '\'
        ORDER BY object_name
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
               json_object(${SqliteSql.literal(KEY_PROPERTY)}, CAST(${'$'}key AS TEXT), 'storeId', CAST(${'$'}storeId AS TEXT), 'assignedAt', CAST(${'$'}assignedAt AS TEXT), 'engine', CAST(${'$'}engine AS TEXT), 'layout', CAST(${'$'}layout AS INTEGER))
        WHERE NOT EXISTS (SELECT 1 FROM ${ddl.nodes} n WHERE $stored AND ${SqliteSql.property("n", KEY_PROPERTY)} = CAST(${'$'}key AS TEXT))
    """.trimIndent()

    override val readStamps: String = """
        ${SqliteDdl.JSON_ROWS}
        SELECT json_object('id', ${SqliteSql.property("n", "storeId")}, 'assignedAt', ${SqliteSql.property("n", "assignedAt")}, 'layout', ${SqliteSql.number("n", "layout")})
        FROM ${ddl.nodes} n WHERE $stored
        ORDER BY ${SqliteSql.property("n", "assignedAt")}, ${SqliteSql.property("n", "storeId")}
    """.trimIndent()

    override val foreignNodes: String = "SELECT 1 FROM ${ddl.nodes} n WHERE NOT ${StoreLayout.sqliteOwn("n")} LIMIT 1"

    override val restampLayout: String = """
        UPDATE ${ddl.nodes} SET props = json_set(props, '${'$'}.layout', CAST(${'$'}layout AS INTEGER))
        WHERE nid IN (SELECT n.nid FROM ${ddl.nodes} n WHERE $stored AND ${SqliteSql.property("n", KEY_PROPERTY)} = CAST(${'$'}key AS TEXT))
          AND NOT EXISTS (SELECT 1 FROM ${ddl.nodes} o WHERE NOT ${StoreLayout.sqliteOwn("o")})
    """.trimIndent()

    override val layoutObjects: String = """
        ${SqliteDdl.JSON_ROWS}
        SELECT json_object('name', name, 'type', 'INDEX') FROM sqlite_master
        WHERE type = 'index' AND tbl_name = ${SqliteSql.literal(ddl.nodes)}
        ORDER BY name
    """.trimIndent()
}
