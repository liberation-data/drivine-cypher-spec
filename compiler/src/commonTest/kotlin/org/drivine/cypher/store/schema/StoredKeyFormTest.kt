package org.drivine.cypher.store.schema

import org.drivine.cypher.store.PostgresSql
import org.drivine.cypher.store.ddl.DdlStatement
import org.drivine.cypher.store.ddl.OracleDdl
import org.drivine.cypher.store.ddl.PostgresDdl
import org.drivine.cypher.store.ddl.SqlStoreDdl
import org.drivine.schema.FullTextIndexSpec
import org.drivine.schema.SchemaItemSpec
import org.drivine.schema.VectorIndexSpec
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNotNull

/**
 * A vector or a full-text index over a property whose name holds a `'` or begins with `$` reads as current against what
 * the module makes for it (drivine-cypher-spec#266): the store's objects read the key the property is held at
 * ([SqlStoreDdl.storedKeys], `$x` at `$$x`), and a key in a SQL literal has its quotes doubled. An object that reads
 * another key is still a mismatch.
 */
class StoredKeyFormTest {

    private val names = listOf("body", "it's", "\$x", "\$\$y", "a''b", "say \"hi\"", "a\\b", "a.b", "a\$b", "\$it's.\"x\"")

    private val oracle = OracleDdl("drivine")
    private val postgres = PostgresDdl("drivine")

    private fun specs(property: String): List<SchemaItemSpec> =
        listOf(VectorIndexSpec("Doc", property, 3), FullTextIndexSpec("Doc", listOf(property)))

    // ----- Oracle: the trigger beside the nodes -----

    /**
     * The trigger the module's own DDL makes for [spec] (`OracleDdl.createIndex`), as Oracle's listing gives it: its
     * `CREATE OR REPLACE TRIGGER` statement, taken from the literal the DDL runs it from, as `TRIGGER …`, under [name].
     */
    private fun oracleTrigger(spec: SchemaItemSpec, name: String = oracle.sideName(spec) + "_t"): Map<String, Any?> {
        val text = oracle.createIndex(spec as org.drivine.schema.IndexSpec, null).filterIsInstance<DdlStatement.Text>().joinToString("\n") { it.statement }
        val opens = text.indexOf("'CREATE OR REPLACE TRIGGER ")
        assertNotNull(opens.takeIf { it >= 0 }, "no trigger made for $spec:\n$text")
        val body = Regex("'${SqlText.LITERAL}'").find(text, opens)!!.groupValues[1]
        val source = SqlText.unquoted(body).removePrefix("CREATE OR REPLACE ")
        return mapOf("object" to name.uppercase(), "type" to "TRIGGER", "table" to oracle.nodes.uppercase(), "source" to listOf(source))
    }

    private fun OracleDdl.sideName(spec: SchemaItemSpec) =
        if (spec is VectorIndexSpec) vectors(spec.label, spec.properties.single()) else texts(spec.label, spec.properties)

    @Test
    fun oracleReadsTheTriggerItMakesForEachPropertyAsCurrent() {
        val schema = OracleSchema(oracle)
        for (spec in names.flatMap(::specs) + FullTextIndexSpec("Doc", listOf("title", "it's", "\$x"))) {
            assertEquals(ItemForm.Current, schema.formOf(spec, listOf(oracleTrigger(spec))), "$spec")
        }
    }

    @Test
    fun oracleStillTellsATriggerOverAnotherProperty() {
        val schema = OracleSchema(oracle)
        // The trigger made for the second property, under the name of the first's: it reads the second's stored key.
        val pairs = listOf("\$x" to "other", "\$y" to "\$\$y", "it's" to "it''s", "body" to "\$body")
        for ((declared, made) in pairs) {
            for ((spec, other) in specs(declared).zip(specs(made))) {
                val name = oracle.sideName(spec) + "_t"
                val form = schema.formOf(spec, listOf(oracleTrigger(other, name)))
                assertEquals(
                    ItemForm.Mismatch(name.uppercase(), "a trigger over ${SqlStoreDdl.storedKeys(listOf(made))}", droppable = false),
                    form, "$spec against the trigger made for $other",
                )
            }
        }
    }

    /**
     * A trigger over the key a property had before layout 2 (`$x` itself, not `$$x`) reads no key a statement writes: it
     * is drift. A store of layout 1 is refused unless empty, so only an object Drivine didn't make reads so.
     */
    @Test
    fun oracleTellsATriggerOverAnUnescapedKey() {
        val schema = OracleSchema(oracle)
        val spec = FullTextIndexSpec("Doc", listOf("title", "\$x"))
        val made = oracleTrigger(spec)
        @Suppress("UNCHECKED_CAST")
        val unescaped = made + ("source" to (made["source"] as List<String>).map { it.replace("\"\$\$x\"", "\"\$x\"") })
        val name = oracle.sideName(spec) + "_t"
        assertEquals(ItemForm.Mismatch(name.uppercase(), "a trigger over [title, \$x]", droppable = false), schema.formOf(spec, listOf(unescaped)))
    }

    // ----- Postgres: the index's columns -----

    /**
     * The index the module makes for [spec], as Postgres's listing gives it back: its column as `pg_get_indexdef` prints the
     * module's expression again (`::text` after each literal, as `store.yaml`'s rows captured from a store show), over the
     * stored [keys]. Postgres writes the expression anew, so it is written here as the store gives it, not taken from the DDL.
     */
    private fun postgresIndex(spec: SchemaItemSpec, keys: List<String> = SqlStoreDdl.storedKeys(spec.properties)): Map<String, Any?> {
        fun key(k: String) = "${PostgresSql.literal(k)}::text"
        return if (spec is VectorIndexSpec) {
            mapOf(
                "object" to postgres.objectName(PostgresDdl.VECTOR, listOf(spec.label, spec.properties.single())).lowercase(), "type" to "i",
                "table" to postgres.nodes, "unique" to false, "method" to "hnsw", "opclasses" to listOf("vector_cosine_ops"),
                "where" to "labels @> '[\"Doc\"]'::jsonb AND jsonb_typeof(props -> ${key(keys.single())}) = 'array'::text AND jsonb_array_length(props -> ${key(keys.single())}) = 3",
                "columns" to listOf("((((props -> ${key(keys.single())})::text))::vector(3))"),
            )
        } else {
            val text = keys.joinToString(" || ' '::text) || ") { "COALESCE(COALESCE((props -> ${key(it)}) ->> 'v'::text, props ->> ${key(it)}), ''::text)" }
            mapOf(
                "object" to postgres.objectName(PostgresDdl.FULLTEXT, listOf(spec.label) + spec.properties).lowercase(), "type" to "i",
                "table" to postgres.nodes, "unique" to false, "method" to "gin", "opclasses" to listOf("gin_trgm_ops"),
                "where" to "labels @> '[\"Doc\"]'::jsonb",
                "columns" to listOf("lower(${"(".repeat(keys.size - 1)}$text)"),
            )
        }
    }

    @Test
    fun postgresReadsTheIndexItMakesForEachPropertyAsCurrent() {
        val schema = PostgresSchema(postgres)
        for (spec in names.flatMap(::specs) + FullTextIndexSpec("Doc", listOf("title", "it's", "\$x"))) {
            assertEquals(ItemForm.Current, schema.formOf(spec, listOf(postgresIndex(spec))), "$spec")
        }
    }

    @Test
    fun postgresStillTellsAnIndexOverAnotherProperty() {
        val schema = PostgresSchema(postgres)
        val pairs = listOf("\$x" to "other", "\$y" to "\$\$y", "it's" to "it''s", "body" to "\$body")
        for ((declared, made) in pairs) {
            for (spec in specs(declared)) {
                val form = schema.formOf(spec, listOf(postgresIndex(spec, SqlStoreDdl.storedKeys(listOf(made)))))
                assertIs<ItemForm.Mismatch>(form, "$spec against an index over $made")
                assertEquals("an index over ${SqlStoreDdl.storedKeys(listOf(made))}", form.reason, "$spec")
                assertEquals(true, form.droppable, "$spec")
            }
        }
    }

    /** An index over the key a property had before layout 2 (`$x` itself, not `$$x`) is drift, as on Oracle. */
    @Test
    fun postgresTellsAnIndexOverAnUnescapedKey() {
        val schema = PostgresSchema(postgres)
        for (spec in listOf(VectorIndexSpec("Doc", "\$x", 3), FullTextIndexSpec("Doc", listOf("title", "\$x")))) {
            val form = schema.formOf(spec, listOf(postgresIndex(spec, spec.properties)))
            assertEquals(ItemForm.Mismatch(postgres.objectName(if (spec is VectorIndexSpec) PostgresDdl.VECTOR else PostgresDdl.FULLTEXT, listOf("Doc") + spec.properties).lowercase(), "an index over ${spec.properties}", droppable = true), form, "$spec")
        }
    }
}
