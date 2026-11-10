package org.drivine.cypher.store.ddl

import org.drivine.DrivineInternalApi
import org.drivine.cypher.store.PostgresSql
import org.drivine.cypher.store.PostgresSql.hasLabel
import org.drivine.schema.ConstraintSpec
import org.drivine.schema.FullTextIndexSpec
import org.drivine.schema.IndexSpec
import org.drivine.schema.RangeIndexSpec
import org.drivine.schema.SchemaItemKind
import org.drivine.schema.SchemaItemSpec
import org.drivine.schema.TextIndexSpec
import org.drivine.schema.UniquenessConstraintSpec
import org.drivine.schema.VectorIndexSpec

/**
 * A graph as Postgres keeps it: the tables of a [SqlStoreDdl], with `labels` and `props` as `jsonb`, the functions it is
 * written through ([writes]), and its indexes and constraints.
 *
 * The labels are a JSON array under a GIN index that answers whether a node carries a label (`labels @> '["Person"]'`).
 * Names are written bare, so Postgres folds them to lower case: a graph called `Contracts` keeps its nodes in
 * `contracts_node`, and is the same graph as one called `contracts`.
 *
 * A property is a key of `props`, and Postgres indexes the part of a table a predicate selects, so each index is a partial
 * index over an expression, for the nodes of one label ([PostgresSql]):
 *
 * - a range index is `((props ->> 'p')) WHERE labels @> '["L"]'`, which a statement reads a property and tests a label as,
 *   so the planner sees the index holds the nodes it asks for;
 * - a uniqueness constraint is that index made `UNIQUE`. A node without the property reads it as null, which a unique index
 *   does not hold, so it is not constrained, as on the graph engines; and it finds a node as a range index does, so no
 *   range index is made beside it;
 * - a vector index is an HNSW index of pgvector over the embeddings of the label's nodes that are of its size;
 * - a full-text index is a GIN index of the trigrams of the text a search reads.
 *
 * What Drivine made is recorded in the catalog table and read back from there: an index's expression does not say which
 * label and properties it was made for.
 */
@DrivineInternalApi
class PostgresDdl(name: String) : SqlStoreDdl(name, ENGINE, MAX_OBJECT_NAME) {

    /** The functions the graph is written through. */
    val writes = PostgresWriteFunctions(this)

    /** What makes the store, when it is not there. Each statement is safe to run again. */
    val ddl: List<String> = listOf(
        """
        CREATE TABLE IF NOT EXISTS $nodes (
            nid    bigint GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
            labels jsonb NOT NULL,
            props  jsonb NOT NULL,
            stamp  varchar(33),
            words  jsonb
        )
        """,
        // The derived fields of a node beside its properties (see the README's Values in the store), on a store an earlier
        // version made: nothing writes or reads them yet.
        "ALTER TABLE $nodes ADD COLUMN IF NOT EXISTS words jsonb",
        """
        CREATE TABLE IF NOT EXISTS $edges (
            eid   bigint GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
            src   bigint NOT NULL REFERENCES $nodes (nid) ON DELETE CASCADE,
            dst   bigint NOT NULL REFERENCES $nodes (nid) ON DELETE CASCADE,
            type  varchar(128) NOT NULL,
            props jsonb NOT NULL DEFAULT '{}'
        )
        """,
        """
        CREATE TABLE IF NOT EXISTS $catalog (
            kind        varchar(40) NOT NULL,
            label       varchar(400) NOT NULL,
            properties  varchar(2000) NOT NULL,
            name        varchar(400),
            object_name varchar($MAX_OBJECT_NAME),
            details     jsonb NOT NULL,
            PRIMARY KEY (kind, label, properties)
        )
        """,
        "CREATE INDEX IF NOT EXISTS ${name}_edge_src ON $edges (src, type)",
        "CREATE INDEX IF NOT EXISTS ${name}_edge_dst ON $edges (dst, type)",
        "CREATE INDEX IF NOT EXISTS ${name}_node_labels ON $nodes USING GIN (labels jsonb_path_ops)",
    ).map { it.trimIndent() } + writes.ddl + temporalKeyFunction() + "COMMENT ON TABLE $nodes IS '$VERSION'"

    /** The function compiled Cypher works out a temporal value's sort key by: see [temporalKeyFunction]. */
    val temporalKey: String = temporalKeyName(name)

    /**
     * Makes [temporalKey]: the sort key `s` of the canonical text `t` of a temporal value of the kind `k`, its word, as
     * `Temporals.key` works it out in Kotlin, by the text and integer arithmetic of `Temporals.sortKey`; null for a duration
     * and for null. A SQL function, immutable, which Postgres writes out where it is called, so it costs nothing to run;
     * compiled Cypher calls it where it makes a temporal value as it runs. Never in an index's expression.
     */
    private fun temporalKeyFunction(): String {
        val inline: (List<String>, (List<String>) -> String) -> String = { values, body -> body(values) }
        val temporals = org.drivine.cypher.sql.Temporals(org.drivine.cypher.sql.PostgresTemporalSql(inline), inline)
        val kinds = org.drivine.cypher.sql.Temporal.instants.joinToString("\n") { "        WHEN '${it.function}' THEN ${temporals.sortKey("t", it)}" }
        return "CREATE OR REPLACE FUNCTION ${temporalKeyName(name)}(k text, t text) RETURNS text LANGUAGE sql IMMUTABLE PARALLEL SAFE AS ${'$'}fn${'$'}\n" +
            "    SELECT CASE k\n$kinds\n    END\n${'$'}fn${'$'}"
    }

    /**
     * The block that makes the store when it is not there, and does nothing when it is: the probe, the lock and the [ddl]
     * in one. The node table's comment, written last, says which version made the store: one this version made is left
     * alone, and the rest is made again over one an earlier version made, which keeps its rows and has its functions
     * replaced. The block takes a lock of its own for the graph first, so two processes that each find the store missing
     * make it one after the other, and the second, looking again once it has the lock, makes nothing. Postgres runs a
     * block's schema statements in its transaction, so the store is made whole or not at all.
     */
    val ensuring: String = """
        DO ${'$'}ensure${'$'}
        BEGIN
            IF obj_description(to_regclass('$nodes'), 'pg_class') IS DISTINCT FROM '$VERSION' THEN
                PERFORM pg_advisory_xact_lock(hashtext('drivine:$name'));
                -- Looked for again under the lock: another session may have made it while this one waited.
                IF obj_description(to_regclass('$nodes'), 'pg_class') IS DISTINCT FROM '$VERSION' THEN
                    -- pgvector, for vector search, where the server has it and the user may make it: without it,
                    -- everything but a vector search works.
                    BEGIN
                        CREATE EXTENSION IF NOT EXISTS vector;
                    EXCEPTION WHEN OTHERS THEN
                        NULL;
                    END;
        ${ddl.joinToString("\n") { "            ${it.replace("\n", "\n            ")};" }}
                END IF;
            END IF;
        END
        ${'$'}ensure${'$'}
    """.trimIndent()

    /** [text] as a schema statement, as drivine4j's grammar gave it. */
    private fun statement(text: String): String = text.trimIndent()

    /** [text] as schema statements, after the one that makes the store when it is not there. */
    private fun statements(vararg text: String): List<DdlStatement> = (listOf(ensuring) + text).map { DdlStatement.Text(statement(it)) }

    /**
     * Records [spec] as made, as [objectName] (null for one nothing was made for), unless it is recorded already. Two
     * processes recording it at once record it once.
     */
    private fun recorded(spec: SchemaItemSpec, objectName: String?, details: String = "{}"): String = """
        INSERT INTO $catalog (kind, label, properties, name, object_name, details)
        VALUES (${literal(spec.kind.name)}, ${literal(spec.label)}, ${literal(json(spec.properties))}, ${literal(spec.effectiveName)}, ${objectName?.let(::literal) ?: "NULL"}, ${literal(details)})
        ON CONFLICT (kind, label, properties) DO NOTHING
    """

    /**
     * Forgets [item], and drops what it was made as when nothing else recorded uses it. What is dropped is the object the
     * record names, and not one named again from the label and the properties, so an item is dropped as it was made
     * whatever a later version would call it.
     */
    private fun forgotten(item: CatalogItem): List<DdlStatement> {
        val record = recordOf(item)
        return listOf(
            DdlStatement.Text(
                statement(
                    """
                    DO ${'$'}forget${'$'}
                    DECLARE
                        v_object text;
                        v_others int;
                    BEGIN
                        SELECT max(object_name) INTO v_object FROM $catalog WHERE $record;
                        SELECT count(*) INTO v_others FROM $catalog WHERE object_name = v_object AND NOT ($record);
                        IF v_object IS NOT NULL AND v_others = 0 THEN
                            -- Written bare, as it was made: Postgres folds the name to lower case either way.
                            EXECUTE 'DROP INDEX IF EXISTS ' || v_object;
                        END IF;
                        DELETE FROM $catalog WHERE $record;
                    END
                    ${'$'}forget${'$'}
                    """
                )
            )
        )
    }

    override fun aliased(item: CatalogItem, aliases: List<String>): String {
        val details = if (aliases.isEmpty()) "details - '$ALIASES'" else "jsonb_set(details, '{$ALIASES}', ${literal(aliasesJson(aliases))}::jsonb)"
        return statement("UPDATE $catalog SET details = $details WHERE ${recordOf(item)}")
    }

    /**
     * The partial index over [properties] of the nodes of [label]: each property as a statement reads it, and the test of
     * the label a statement makes, both of the table's own columns.
     */
    private fun over(label: String, properties: List<String>): String =
        "$nodes (${properties.joinToString(", ") { "(${PostgresSql.indexed("", it)})" }}) WHERE ${hasLabel("", label)}"

    override fun createIndex(spec: IndexSpec, existing: CatalogItem?): List<DdlStatement> = when (spec) {
        is RangeIndexSpec -> {
            val name = objectName("ix", listOf(spec.label) + spec.properties)
            statements("CREATE INDEX IF NOT EXISTS $name ON ${over(spec.label, spec.properties)}", recorded(spec, name))
        }
        is VectorIndexSpec -> {
            // An HNSW index over the embeddings of the label's nodes that are of its size: one of another size is left out, not refused.
            val property = spec.properties.single()
            val name = objectName("v", listOf(spec.label, property))
            statements(
                "CREATE EXTENSION IF NOT EXISTS vector",
                "CREATE INDEX IF NOT EXISTS $name ON $nodes USING hnsw ((${PostgresSql.embedding("", property, spec.dimensions)}) ${PostgresSql.operators(spec.similarity)}) " +
                    "WHERE ${hasLabel("", spec.label)} AND ${PostgresSql.holdsEmbedding("", property, spec.dimensions)}",
                recorded(spec, name, vectorDetails(spec)),
            )
        }
        is FullTextIndexSpec -> {
            // Trigrams of the text a search reads, which answer whether it holds a term's words.
            val name = objectName("t", listOf(spec.label) + spec.properties)
            statements(
                "CREATE EXTENSION IF NOT EXISTS pg_trgm",
                "CREATE INDEX IF NOT EXISTS $name ON $nodes USING gin ((${PostgresSql.searchedText("", spec.properties)}) gin_trgm_ops) WHERE ${hasLabel("", spec.label)}",
                recorded(spec, name, analyzerDetails(spec.analyzer)),
            )
        }
        is TextIndexSpec -> throw unsupportedTextIndex(spec, engine)
    }

    override fun dropIndex(item: CatalogItem): List<DdlStatement> = forgotten(item)

    override fun createConstraint(spec: ConstraintSpec): List<DdlStatement> = when (spec) {
        is UniquenessConstraintSpec -> {
            val name = objectName("uq", listOf(spec.label) + spec.properties)
            statements("CREATE UNIQUE INDEX IF NOT EXISTS $name ON ${over(spec.label, spec.properties)}", recorded(spec, name))
        }
    }

    override fun dropConstraint(item: CatalogItem): List<DdlStatement> = forgotten(item)

    /** What is recorded of [kinds], and still there: an index dropped by hand is not listed, so that it is made again when asked for. */
    private fun listed(kinds: String): String = """
        SELECT jsonb_build_object('kind', kind, 'label', label, 'properties', properties::jsonb, 'name', name, 'details', details)
        FROM $catalog c
        WHERE kind IN ($kinds)
          AND (object_name IS NULL OR to_regclass(object_name) IS NOT NULL)
        ORDER BY label, properties
    """.trimIndent()

    override fun listIndexesQuery(kind: SchemaItemKind): String = listed(literal(kind.name))

    override fun listConstraintsQuery(): String = listed(literal(SchemaItemKind.UNIQUENESS_CONSTRAINT.name))

    companion object {
        /** The store's name, for what is said of a graph name it cannot take. */
        const val ENGINE = "Postgres"

        /** Postgres names are at most 63 characters: past that, it cuts them short without a word. */
        const val MAX_OBJECT_NAME = 63

        /** What the node table's comment says of a store this version made. */
        const val VERSION = "drivine:8"

        /** The function the store of the graph [graph] keeps that works out a temporal value's sort key: see [temporalKey]. */
        fun temporalKeyName(graph: String) = "${graph}_temporal_key"

        /** Why [name] cannot name a graph here; null where it can (see [SqlStoreDdl.refusal]). */
        fun refusal(name: String): String? = SqlStoreDdl.refusal(name, ENGINE, MAX_OBJECT_NAME)
    }
}
