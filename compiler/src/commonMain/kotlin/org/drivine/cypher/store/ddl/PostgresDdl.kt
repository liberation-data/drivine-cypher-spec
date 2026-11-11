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
            stamp  varchar(33)
        )
        """,
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
    ).map { it.trimIndent() } + writes.ddl + "COMMENT ON TABLE $nodes IS '$VERSION'"

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
        VALUES (${literal(spec.kind.name)}, ${literal(spec.label)}, ${literal(json(spec.properties))}, ${literal(spec.effectiveName)}, ${objectName?.let(::literal) ?: "NULL"}, ${literal(formed(details))})
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

    override fun formRecorded(item: CatalogItem): String =
        statement("UPDATE $catalog SET details = jsonb_set(details, '{$FORM}', '$CURRENT_FORM') WHERE ${recordOf(item)}")

    /**
     * The partial index over [properties] of the nodes of [label]: each property as a statement reads it, and the test of
     * the label a statement makes, both of the table's own columns.
     */
    private fun over(label: String, properties: List<String>): String =
        "$nodes (${properties.joinToString(", ") { "(${PostgresSql.property("", it)})" }}) WHERE ${hasLabel("", label)}"

    override fun createIndex(spec: IndexSpec, existing: CatalogItem?): List<DdlStatement> = when (spec) {
        is RangeIndexSpec -> {
            val name = objectName(RANGE, listOf(spec.label) + spec.properties)
            statements("CREATE INDEX IF NOT EXISTS $name ON ${over(spec.label, spec.properties)}", recorded(spec, name))
        }
        is VectorIndexSpec -> {
            // An HNSW index over the embeddings of the label's nodes that are of its size: one of another size is left out, not refused.
            val property = spec.properties.single()
            val name = objectName(VECTOR, listOf(spec.label, property))
            statements(
                "CREATE EXTENSION IF NOT EXISTS vector",
                "CREATE INDEX IF NOT EXISTS $name ON $nodes USING hnsw ((${PostgresSql.embedding("", property, spec.dimensions)}) ${PostgresSql.operators(spec.similarity)}) " +
                    "WHERE ${hasLabel("", spec.label)} AND ${PostgresSql.holdsEmbedding("", property, spec.dimensions)}",
                recorded(spec, name, vectorDetails(spec)),
            )
        }
        is FullTextIndexSpec -> {
            // Trigrams of the text a search reads, which answer whether it holds a term's words.
            val name = objectName(FULLTEXT, listOf(spec.label) + spec.properties)
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
            val name = objectName(UNIQUE, listOf(spec.label) + spec.properties)
            statements("CREATE UNIQUE INDEX IF NOT EXISTS $name ON ${over(spec.label, spec.properties)}", recorded(spec, name))
        }
    }

    override fun dropConstraint(item: CatalogItem): List<DdlStatement> = forgotten(item)

    /**
     * The unique index built under [temporaryName] while the old one stands, and swapped into its place in one block,
     * which Postgres runs in one transaction: the old index is gone only where the new one has its name.
     */
    override fun recreation(spec: ConstraintSpec): UniqueRecreation = when (spec) {
        is UniquenessConstraintSpec -> {
            val name = objectName(UNIQUE, listOf(spec.label) + spec.properties)
            val temporary = temporaryName(name)
            UniqueRecreation(
                name = name,
                temporary = temporary,
                leftover = listOf(DdlStatement.Text(droppedIndex(temporary))),
                build = listOf(DdlStatement.Text(statement("CREATE UNIQUE INDEX $temporary ON ${over(spec.label, spec.properties)}"))),
                swap = listOf(
                    DdlStatement.Text(
                        statement(
                            """
                            DO ${'$'}swap${'$'}
                            BEGIN
                                DROP INDEX IF EXISTS $name;
                                ALTER INDEX $temporary RENAME TO $name;
                            END
                            ${'$'}swap${'$'}
                            """
                        )
                    )
                ),
                finish = listOf(DdlStatement.Text("ALTER INDEX $temporary RENAME TO $name")),
                remade = remade(CatalogItem(spec.kind, spec.label, spec.properties, spec.effectiveName), name),
            )
        }
    }

    override fun remade(item: CatalogItem, objectName: String): String =
        statement("UPDATE $catalog SET name = ${item.name?.let(::literal) ?: "name"}, object_name = ${literal(objectName)}, details = jsonb_set(details, '{$FORM}', '$CURRENT_FORM') WHERE ${recordOf(item)}")

    /**
     * What is recorded of [kinds], and still there: an index dropped by hand is not listed, so that it is made again when
     * asked for. After the records, a row for each relation of the schema the store reads under a name Drivine gives an
     * object of [objects], recorded or not, with what it is: its `type` (`relkind`), and of an index its `table`, whether
     * it is `unique`, its access `method`, the text of each of its `columns`, its predicate (`where`), as Postgres
     * writes them, and its operator classes (`opclasses`), which `store.schema` reads to tell another item under the name ([org.drivine.cypher.store.schema.ItemForm]).
     */
    private fun listed(kinds: String, objects: List<String>): String = if (objects.isEmpty()) """
        SELECT jsonb_build_object('kind', kind, 'label', label, 'properties', properties::jsonb, 'name', name, 'details', details)
        FROM $catalog c
        WHERE kind IN ($kinds)
          AND (object_name IS NULL OR to_regclass(object_name) IS NOT NULL)
        ORDER BY label, properties
    """.trimIndent() else """
        SELECT listed FROM (
            SELECT 0 AS part, label, properties, jsonb_build_object('kind', kind, 'label', label, 'properties', properties::jsonb, 'name', name, 'details', details) AS listed
            FROM $catalog c
            WHERE kind IN ($kinds)
              AND (object_name IS NULL OR to_regclass(object_name) IS NOT NULL)
            UNION ALL
            SELECT 1, o.relname::varchar, NULL,
                   jsonb_build_object('object', o.relname, 'type', o.relkind, 'table', t.relname, 'unique', i.indisunique, 'method', a.amname,
                                      'columns', (SELECT jsonb_agg(pg_get_indexdef(i.indexrelid, k, true) ORDER BY k) FROM generate_series(1, i.indnatts) k),
                                      'where', pg_get_expr(i.indpred, i.indrelid, true),
                                      'opclasses', (SELECT jsonb_agg(oc.opcname ORDER BY k.n) FROM unnest(i.indclass::oid[]) WITH ORDINALITY AS k(oid, n) JOIN pg_opclass oc ON oc.oid = k.oid))
            FROM pg_class o
            LEFT JOIN pg_index i ON i.indexrelid = o.oid
            LEFT JOIN pg_class t ON t.oid = i.indrelid
            LEFT JOIN pg_am a ON a.oid = o.relam
            WHERE (${namedAs("o.relname", objects, upper = false)}) AND pg_table_is_visible(o.oid)
        ) l
        ORDER BY part, label, properties
    """.trimIndent()

    override fun listIndexesQuery(kind: SchemaItemKind): String = listed(literal(kind.name), objectKinds(kind))

    override fun listConstraintsQuery(): String = listed(literal(SchemaItemKind.UNIQUENESS_CONSTRAINT.name), listOf(UNIQUE))

    /** What Drivine names the objects of an index of [kind]: its own index's kind. */
    private fun objectKinds(kind: SchemaItemKind): List<String> = when (kind) {
        SchemaItemKind.RANGE_INDEX -> listOf(RANGE)
        SchemaItemKind.VECTOR_INDEX -> listOf(VECTOR)
        SchemaItemKind.FULLTEXT_INDEX -> listOf(FULLTEXT)
        SchemaItemKind.UNIQUENESS_CONSTRAINT, SchemaItemKind.TEXT_INDEX -> emptyList()
    }

    companion object {
        /** The store's name, for what is said of a graph name it cannot take. */
        const val ENGINE = "Postgres"

        /** Postgres names are at most 63 characters: past that, it cuts them short without a word. */
        const val MAX_OBJECT_NAME = 63

        /** What the names of a range index, a uniqueness constraint's index, a vector index and a full-text index say they are. */
        const val RANGE = "ix"
        const val UNIQUE = "uq"
        const val VECTOR = "v"
        const val FULLTEXT = "t"

        /** What the node table's comment says of a store this version made. */
        const val VERSION = "drivine:7"

        /** Why [name] cannot name a graph here; null where it can (see [SqlStoreDdl.refusal]). */
        fun refusal(name: String): String? = SqlStoreDdl.refusal(name, ENGINE, MAX_OBJECT_NAME)
    }
}
