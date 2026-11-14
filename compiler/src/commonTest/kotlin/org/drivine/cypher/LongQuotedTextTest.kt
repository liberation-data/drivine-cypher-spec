package org.drivine.cypher

import org.drivine.cypher.sql.OracleCypherCompiler
import org.drivine.cypher.sql.PostgresCypherCompiler
import org.drivine.cypher.sql.SqliteCypherCompiler
import org.drivine.cypher.store.OracleSql
import org.drivine.cypher.store.ddl.OracleDdl
import org.drivine.cypher.store.ddl.PostgresDdl
import org.drivine.cypher.store.ddl.SqliteDdl
import org.drivine.cypher.store.schema.ItemForm
import org.drivine.cypher.store.schema.OracleSchema
import org.drivine.cypher.store.schema.PostgresSchema
import org.drivine.cypher.store.schema.SqlStoreSchema
import org.drivine.cypher.store.schema.SqliteSchema
import org.drivine.schema.FullTextIndexSpec
import org.drivine.schema.RangeIndexSpec
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * A long or quote-heavy key, literal or label is compiled on each store, and read back from an index's text, on every
 * target (#258). The compiler once read a quoted text in its SQL by regex, and the JVM's and the stdlib's regex engines
 * recurse as a group repeats: once a character for `(?:[^']|'')*`, which overflowed the stack on a key of 400,000
 * characters, and once a doubled quote or an escape for the unrolled `[^']*(?:''[^']*)*`, which overflowed on a key of a
 * few thousand quotes or backslashes. On Kotlin/Native an overflow ends the process. The compiler now reads each quoted
 * text with a loop ([org.drivine.cypher.store.schema.SqlText.scanQuoted]), so no key, literal or label crashes it: each
 * statement here is compiled, or refused with a [CompilerException].
 */
class LongQuotedTextTest {

    /** Each key, literal or label a statement is compiled with, by what it is. */
    private val keys = linkedMapOf(
        "400,000 characters" to "k".repeat(400_000),
        // A key Oracle's paths quote, `'$."k k …"'`, as a plain one they don't.
        "400,000 characters with spaces" to "k ".repeat(200_000),
        "10,000 single quotes" to "'".repeat(10_000),
        "10,000 double quotes" to "\"".repeat(10_000),
        "10,000 backslashes" to "\\".repeat(10_000),
        "mixed" to "a'\"\\b''\\\"[ \$.(),`c".repeat(1_000),
    )

    /** [key] as a name in backticks. */
    private fun name(key: String) = "`" + key.replace("`", "``") + "`"

    /** [key] as a Cypher string literal. */
    private fun text(key: String) = "'" + key.replace("\\", "\\\\").replace("'", "\\'") + "'"

    private fun statements(key: String): List<String> {
        val k = name(key)
        return listOf(
            "MATCH (n:L) WHERE n.$k = 1 RETURN n",
            "MATCH (n:L) RETURN n ORDER BY n.$k",
            "MATCH (n:L) RETURN n.$k AS v",
            "MATCH (n:L) SET n.$k = 1",
            "MATCH (n:L) WHERE n.a = ${text(key)} RETURN n",
            "RETURN ${text(key)} AS v",
            "MATCH (n:$k) RETURN n",
            "MATCH (n:$k) DETACH DELETE n",
            "MATCH (n:L) WHERE n.$k < 'x' RETURN n",
            "UNWIND [{x: 1}, {x: 'a'}] AS r MATCH (n:L {$k: r.x}) RETURN n",
            "MATCH (n:L) WHERE n.$k = date() RETURN n",
            "MATCH (n:L) RETURN n.$k.x AS v",
            "MATCH (n:L) RETURN n.m.$k AS v",
            "MATCH (n:L) WHERE n.$k.x = 1 RETURN n",
            "MATCH (n:L) WHERE n.a = [${text(key)}, 1.5] RETURN n",
        )
    }

    @Test
    fun aStatementWithALongOrQuoteHeavyKeyLiteralOrLabelIsCompiledOrRefusedOnEachStoreNeverCrashing() {
        val stores = mapOf<String, (String) -> String>(
            "Postgres" to { PostgresCypherCompiler("g").compile(it).sql },
            "SQLite" to { SqliteCypherCompiler("g").compile(it).sql },
            "Oracle" to { OracleCypherCompiler("g").compile(it).sql },
        )
        var compiled = 0
        for ((store, compile) in stores) for ((what, key) in keys) for (cypher in statements(key)) {
            val shown = "$store, a key of $what: ${cypher.replace(name(key), "<key>").replace(text(key), "<text>")}"
            val sql = try {
                compile(cypher)
            } catch (refused: CompilerException) {
                continue
            }
            assertTrue(sql.isNotEmpty(), shown)
            compiled++
        }
        // Most are compiled: a refusal of them all would prove nothing of the reads.
        assertTrue(compiled >= stores.size * keys.size * statements("k").size * 3 / 4, "compiled: $compiled")
    }

    /**
     * Oracle's delete of the nodes of a label, where nodes of another exist, tests each label by its text, not by the index
     * over labels: each label's JSON string is read back from the test the query wrote, through its escapes and its
     * literal's doubled quotes, however long, and whatever `"`, `\`, `'` or `")'` it holds; each test in turn.
     */
    @Test
    fun aDeleteOnOracleTestsEachLabelByItsTextWhateverItHolds() {
        val labels = keys.values + listOf("a\")'b", "a\\\")'b", "\\", "it's")
        for (label in labels) {
            val sql = OracleCypherCompiler("g").compile("MATCH (n:${name(label)}) WHERE EXISTS { MATCH (m:${name("$label 2")}) } DETACH DELETE n").sql
            val shown = label.take(20)
            assertTrue(OracleSql.holdsLabel("n1.labels", label) in sql, "the first label of $shown")
            assertTrue(OracleSql.holdsLabel("n2.labels", "$label 2") in sql, "the second label of $shown")
            assertTrue("JSON_EXISTS(n1.labels" !in sql && "JSON_EXISTS(n2.labels" !in sql, "a test by the index of $shown")
        }
    }

    /** [text] as JSON writes a string, in its quotes. */
    private fun json(text: String) = "\"" + text.replace("\\", "\\\\").replace("\"", "\\\"") + "\""

    /** [text] as the body of a SQL literal, its quotes doubled. */
    private fun sql(text: String) = text.replace("'", "''")

    /**
     * An index whose text, as the store gives it back, holds a long or quote-heavy key or label is read on each SQL store:
     * the schema reads each SQL literal in it, and Oracle's label and SQLite's key and label as a JSON string in one.
     */
    @Test
    fun anIndexWithALongOrQuoteHeavyKeyOrLabelIsReadOnEachSqlStore() {
        val spec = RangeIndexSpec("Person", listOf("name"))
        fun nameOf(schema: SqlStoreSchema) = schema.expectedColumns(spec).keys.first()

        val postgres = PostgresSchema(PostgresDdl("g"))
        val pg = nameOf(postgres)
        fun pgRows(column: String, where: String) = listOf(mapOf("object" to pg, "type" to "i", "table" to "g_node", "unique" to false, "method" to "btree",
            "where" to where, "columns" to listOf(column)))

        val oracle = OracleSchema(OracleDdl("g"))
        val ora = nameOf(oracle)
        fun oraRows(column: String) = listOf(mapOf("object" to ora, "type" to "INDEX", "table" to "G_NODE", "uniqueness" to "NONUNIQUE", "columns" to listOf(column)))

        val sqlite = SqliteSchema(SqliteDdl("g"))
        val lite = nameOf(sqlite)
        fun liteRows(column: String, label: String) = listOf(mapOf("object" to lite, "type" to "index", "table" to "g_node", "unique" to 0,
            "sql" to "CREATE INDEX $lite ON g_node ($column) WHERE instr(labels, '${sql(json(label))}') > 0"))
        val liteColumns = sqlite.expectedColumns(spec).getValue(lite).joinToString(", ")

        for ((what, key) in keys) {
            assertEquals(ItemForm.Mismatch(pg, "an index over [$key]", true),
                postgres.formOf(spec, pgRows("(props -> '${sql(key)}'::text)", "labels @> '[\"Person\"]'::jsonb")), "Postgres: a key of $what")
            assertEquals(ItemForm.Mismatch(pg, "an index over [$key]", true),
                postgres.formOf(spec, pgRows("(props ->> '${sql(key)}'::text)", "labels @> '[\"Person\"]'::jsonb")), "Postgres: a key of $what, as text")
            assertEquals(ItemForm.Mismatch(pg, "an index over the nodes of $key", true),
                postgres.formOf(spec, pgRows("(props -> 'name'::text)", "labels @> '${sql("[" + json(key) + "]")}'::jsonb")), "Postgres: a label of $what")

            assertEquals(ItemForm.Mismatch(ora, "an index over [$key]", true),
                oracle.formOf(spec, oraRows("JSON_VALUE(\"PROPS\" FORMAT OSON , '${sql("$." + json(key))}' RETURNING VARCHAR2(4000) NULL ON ERROR TYPE(LAX) )")),
                "Oracle: a key of $what")
            assertEquals(ItemForm.Mismatch(ora, "an index over the nodes of $key", true),
                oracle.formOf(spec, oraRows("JSON_EXISTS2(\"LABELS\" FORMAT OSON , '${sql("$[*]?(@ == " + json(key) + ")")}' FALSE ON ERROR TYPE(LAX) )")),
                "Oracle: a label of $what")

            assertEquals(ItemForm.Mismatch(lite, "an index over [$key]", true),
                sqlite.formOf(spec, liteRows("json_type(props, '${sql("$." + json(key))}')", "Person")), "SQLite: a key of $what")
            assertEquals(ItemForm.Mismatch(lite, "an index over the nodes of $key", true), sqlite.formOf(spec, liteRows(liteColumns, key)), "SQLite: a label of $what")
        }
    }

    /**
     * A full-text index's trigger on Oracle, as drivine4j writes its test of a label and as the other libraries write theirs,
     * with a long or quote-heavy label or key, is read: each SQL literal in its source, and the label as a JSON string.
     */
    @Test
    fun aTriggerWithALongOrQuoteHeavyKeyOrLabelIsReadOnOracle() {
        val ddl = OracleDdl("g")
        val oracle = OracleSchema(ddl)
        val spec = FullTextIndexSpec("Doc", listOf("body"))
        val trigger = ddl.texts(spec.label, spec.properties) + "_t"
        fun rows(source: String) = listOf(mapOf("object" to trigger, "type" to "TRIGGER", "table" to "G_NODE", "source" to source))
        for ((what, key) in keys) {
            val modules = "IF INSTR(REPLACE(REPLACE(JSON_SERIALIZE(:NEW.labels), ']'), '[') || ',\"', '${sql("," + json(key) + ",\"")}') > 0 THEN"
            assertEquals(ItemForm.Mismatch(trigger, "a trigger over the nodes of $key", false), oracle.formOf(spec, rows(modules)), "drivine4j's label of $what")
            val libraries = "IF JSON_EXISTS(:NEW.labels, '${sql("$?(@ == " + json(key) + ")")}') THEN"
            assertEquals(ItemForm.Mismatch(trigger, "a trigger over the nodes of $key", false), oracle.formOf(spec, rows(libraries)), "the libraries' label of $what")
            val read = "IF JSON_EXISTS(:NEW.labels, '\$?(@ == \"Doc\")') THEN x := JSON_VALUE(:NEW.props, '${sql("$." + json(key))}' RETURNING CLOB); END IF;"
            // The trigger's path is read as its literal's body with its quotes un-doubled (#266): the key itself, which isn't `body`.
            assertEquals(ItemForm.Mismatch(trigger, "a trigger over [$key]", false), oracle.formOf(spec, rows(read)), "a key of $what")
        }
    }

    /**
     * A full-text index's trigger on Oracle that reads a long or quote-heavy key, as the index over that key's property, is
     * read as current: its path, un-doubled, is the key the property is held at (#266), however long and whatever it holds.
     */
    @Test
    fun aTriggerOverALongOrQuoteHeavyKeyIsCurrentForTheIndexOverItOnOracle() {
        val ddl = OracleDdl("g")
        val oracle = OracleSchema(ddl)
        for ((what, key) in keys) {
            val spec = FullTextIndexSpec("Doc", listOf(key))
            val trigger = ddl.texts(spec.label, spec.properties) + "_t"
            val read = "IF JSON_EXISTS(:NEW.labels, '\$?(@ == \"Doc\")') THEN x := JSON_VALUE(:NEW.props, '${sql("$." + json(key))}' RETURNING CLOB); END IF;"
            val rows = listOf(mapOf("object" to trigger, "type" to "TRIGGER", "table" to "G_NODE", "source" to read))
            assertEquals(ItemForm.Current, oracle.formOf(spec, rows), "a key of $what")
        }
    }
}
