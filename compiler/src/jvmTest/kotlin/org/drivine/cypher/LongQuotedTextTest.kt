package org.drivine.cypher

import org.drivine.cypher.sql.OracleCypherCompiler
import org.drivine.cypher.sql.PostgresCypherCompiler
import org.drivine.cypher.sql.SqliteCypherCompiler
import org.drivine.cypher.store.ddl.OracleDdl
import org.drivine.cypher.store.ddl.PostgresDdl
import org.drivine.cypher.store.ddl.SqliteDdl
import org.drivine.cypher.store.schema.ItemForm
import org.drivine.cypher.store.schema.OracleSchema
import org.drivine.cypher.store.schema.PostgresSchema
import org.drivine.cypher.store.schema.SqlStoreSchema
import org.drivine.cypher.store.schema.SqliteSchema
import org.drivine.schema.RangeIndexSpec
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * A long quoted key, literal or label is compiled on each store (#258). The compiler reads a quoted text in its SQL by
 * regex. Read as an alternation repeated once a character, `(?:[^']|'')*`, it recursed once a character in Java's regex
 * engine, and a key of 400,000 characters overflowed the stack: in a WHERE on SQLite and Oracle, in an ORDER BY on Oracle.
 */
class LongQuotedTextTest {

    private val long = "k".repeat(400_000)

    /** A key Oracle's paths quote, `'$."k k …"'`, as a plain one, [long], they don't. */
    private val spaced = "k ".repeat(200_000)

    private fun statements(key: String) = listOf(
        "MATCH (n:L) WHERE n.`$key` = 1 RETURN n",
        "MATCH (n:L) RETURN n ORDER BY n.`$key`",
        "MATCH (n:L) RETURN n.`$key` AS v",
        "MATCH (n:L) SET n.`$key` = 1",
        "MATCH (n:L) WHERE n.a = '$key' RETURN n",
        "RETURN '$key' AS v",
        "MATCH (n:`$key`) RETURN n",
        "MATCH (n:`$key`) DETACH DELETE n",
        "MATCH (n:L) WHERE n.`$key` < 'x' RETURN n",
        "UNWIND [{x: 1}, {x: 'a'}] AS r MATCH (n:L {`$key`: r.x}) RETURN n",
        "MATCH (n:L) WHERE n.`$key` = date() RETURN n",
        "MATCH (n:L) RETURN n.`$key`.x AS v",
        "MATCH (n:L) RETURN n.m.`$key` AS v",
        "MATCH (n:L) WHERE n.`$key`.x = 1 RETURN n",
    )

    @Test
    fun `a statement with a long quoted key, literal or label is compiled on each store, not overflowing the stack`() {
        val stores = mapOf<String, (String) -> String>(
            "Postgres" to { PostgresCypherCompiler("g").compile(it).sql },
            "SQLite" to { SqliteCypherCompiler("g").compile(it).sql },
            "Oracle" to { OracleCypherCompiler("g").compile(it).sql },
        )
        for ((store, compile) in stores) for (key in listOf(long, spaced)) for (cypher in statements(key)) {
            val sql = compile(cypher)
            assertTrue(sql.isNotEmpty(), "$store: ${cypher.replace(key, "<${key.length} characters>")}")
        }
    }

    /**
     * An index whose text, as the store gives it back, holds a long key or label is read on each SQL store: the schema reads
     * a SQL literal in it ([org.drivine.cypher.store.schema.SqlText.LITERAL]), and Oracle's label as a JSON string, by regex too.
     */
    @Test
    fun `an index with a long key or label is read on each SQL store, not overflowing the stack`() {
        val spec = RangeIndexSpec("Person", listOf("name"))
        val label = "L".repeat(400_000)
        fun nameOf(schema: SqlStoreSchema) = schema.expectedColumns(spec).keys.first()

        val postgres = PostgresSchema(PostgresDdl("g"))
        val pg = nameOf(postgres)
        fun pgRows(column: String, where: String) = listOf(mapOf("object" to pg, "type" to "i", "table" to "g_node", "unique" to false, "method" to "btree",
            "where" to where, "columns" to listOf(column)))
        assertEquals(ItemForm.Mismatch(pg, "an index over the nodes of $label", true), postgres.formOf(spec, pgRows("(props -> 'name'::text)", "labels @> '[\"$label\"]'::jsonb")), "Postgres: a long label")
        assertEquals(ItemForm.Mismatch(pg, "an index over [$long]", true), postgres.formOf(spec, pgRows("(props -> '$long'::text)", "labels @> '[\"Person\"]'::jsonb")), "Postgres: a long key")

        val oracle = OracleSchema(OracleDdl("g"))
        val ora = nameOf(oracle)
        fun oraRows(column: String) = listOf(mapOf("object" to ora, "type" to "INDEX", "table" to "G_NODE", "uniqueness" to "NONUNIQUE", "columns" to listOf(column)))
        assertEquals(ItemForm.Mismatch(ora, "an index over [$spaced]", true),
            oracle.formOf(spec, oraRows("JSON_VALUE(\"PROPS\" FORMAT OSON , '$.\"$spaced\"' RETURNING VARCHAR2(4000) NULL ON ERROR TYPE(LAX) )")), "Oracle: a long key")
        assertEquals(ItemForm.Mismatch(ora, "an index over the nodes of $label", true),
            oracle.formOf(spec, oraRows("JSON_EXISTS2(\"LABELS\" FORMAT OSON , '$[*]?(@ == \"$label\")' FALSE ON ERROR TYPE(LAX) )")), "Oracle: a long label")

        val sqlite = SqliteSchema(SqliteDdl("g"))
        val lite = nameOf(sqlite)
        fun liteRows(column: String, label: String) = listOf(mapOf("object" to lite, "type" to "index", "table" to "g_node", "unique" to 0,
            "sql" to "CREATE INDEX $lite ON g_node ($column) WHERE instr(labels, '\"$label\"') > 0"))
        assertEquals(ItemForm.Mismatch(lite, "an index over [$long]", true), sqlite.formOf(spec, liteRows("json_type(props, '$.\"$long\"')", "Person")), "SQLite: a long key")
        val columns = sqlite.expectedColumns(spec).getValue(lite).joinToString(", ")
        assertEquals(ItemForm.Mismatch(lite, "an index over the nodes of $label", true), sqlite.formOf(spec, liteRows(columns, label)), "SQLite: a long label")
    }
}
