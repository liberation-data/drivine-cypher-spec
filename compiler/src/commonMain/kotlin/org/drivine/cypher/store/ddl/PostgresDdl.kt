package org.drivine.cypher.store.ddl

import org.drivine.DrivineInternalApi
import org.drivine.cypher.sql.Temporals.Companion.NANOS
import org.drivine.cypher.sql.Temporals.Companion.OFFSET_BIAS
import org.drivine.cypher.sql.Temporals.Companion.TIME_BIAS
import org.drivine.cypher.sql.Temporals.Companion.YEAR_BIAS
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
        """
        CREATE TABLE IF NOT EXISTS $counts (
            kind varchar(16) NOT NULL,
            name varchar(1000) NOT NULL,
            n    bigint NOT NULL,
            PRIMARY KEY (kind, name)
        )
        """,
        """
        CREATE TABLE IF NOT EXISTS $countDeltas (
            id   bigint GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
            kind varchar(16) NOT NULL,
            name varchar(1000) NOT NULL,
            d    bigint NOT NULL
        )
        """,
        "CREATE INDEX IF NOT EXISTS $countDeltasIndex ON $countDeltas (kind, name)",
        lockTable,
        lockSeed,
    ).map { it.trimIndent() } + writes.ddl + temporalKeyFunction() + "COMMENT ON TABLE $nodes IS '$VERSION'"

    override val lockTable: String get() = "CREATE TABLE IF NOT EXISTS $lock (name text PRIMARY KEY)"

    override val lockSeed: String get() = "INSERT INTO $lock (name) VALUES ('$lockRow') ON CONFLICT DO NOTHING"

    /** The function compiled Cypher works out a temporal value's sort key by: see [temporalKeyFunction]. */
    val temporalKey: String = temporalKeyName(name)

    /**
     * Makes [temporalKey]: the sort key `s` of the canonical text `t` of a temporal value of the kind `k`, its word, as
     * `Temporals.key` works it out in Kotlin, by text and integer arithmetic on the text alone; null for a duration and for
     * null. PL/pgSQL, which reads each part of the text once into a variable: a SQL function written out where it is called
     * reads each part again wherever it is used, and a zoned moment's key, its UTC day and clock, cost some ten times as much
     * (#161). Compiled Cypher calls it where a temporal value it makes as it runs is compared, sorted or leaves the expression
     * it is made in. Never in an index's expression. Its body is a text in quotes, not dollars, so the block [ensuring] makes
     * the store in reads it as one statement.
     */
    private fun temporalKeyFunction(): String = """
        CREATE OR REPLACE FUNCTION ${temporalKeyName(name)}(k text, t text) RETURNS text LANGUAGE plpgsql IMMUTABLE PARALLEL SAFE AS '
        DECLARE
            n int := length(t);
            zone int := 0;
            o int := 0;
            p int := 0;
            y bigint;
            m int;
            d int;
            u bigint;
            f text;
            days int;
        BEGIN
            IF k IS NULL OR t IS NULL OR k NOT IN ('date', 'localtime', 'time', 'localdatetime', 'datetime') THEN
                RETURN NULL;
            END IF;
            -- A zoned text, its offset, in seconds, and how long its text is: Z, or +hh:mm.
            IF k IN ('time', 'datetime') THEN
                IF substr(t, n, 1) = 'Z' THEN
                    zone := 1;
                ELSE
                    zone := 6;
                    o := CASE WHEN substr(t, n - 5, 1) = '-' THEN -1 ELSE 1 END * (CAST(substr(t, n - 4, 2) AS int) * 3600 + CAST(substr(t, n - 1, 2) AS int) * 60);
                END IF;
            END IF;
            -- The day, where the kind has one: the year is all before the month, signed past 9999 and before 0.
            IF k IN ('date', 'localdatetime', 'datetime') THEN
                p := CASE WHEN k = 'date' THEN n + 1 ELSE strpos(t, 'T') END;
                y := CAST(substr(t, 1, p - 7) AS bigint);
                m := CAST(substr(t, p - 5, 2) AS int);
                d := CAST(substr(t, p - 2, 2) AS int);
                IF k = 'date' THEN
                    RETURN lpad(CAST(y + $YEAR_BIAS AS text), 10, '0') || lpad(CAST(m AS text), 2, '0') || lpad(CAST(d AS text), 2, '0');
                END IF;
            END IF;
            -- The clock from the character after the day's T, in seconds, and the nine digits of its fraction.
            u := CAST(substr(t, p + 1, 2) AS int) * 3600 + CAST(substr(t, p + 4, 2) AS int) * 60 + CAST(substr(t, p + 7, 2) AS int);
            f := substr(CASE WHEN n - zone - p > 9 THEN substr(t, p + 10, n - zone - p - 9) ELSE '' END || '000000000', 1, 9);
            IF k = 'localtime' THEN
                RETURN lpad(CAST(u * $NANOS + CAST(f AS bigint) AS text), 14, '0');
            ELSIF k = 'time' THEN
                RETURN lpad(CAST((u - o) * $NANOS + CAST(f AS bigint) + $TIME_BIAS AS text), 15, '0') || lpad(CAST(o + $OFFSET_BIAS AS text), 6, '0');
            ELSIF k = 'localdatetime' THEN
                RETURN lpad(CAST(y + $YEAR_BIAS AS text), 10, '0') || lpad(CAST(m AS text), 2, '0') || lpad(CAST(d AS text), 2, '0') ||
                    substr(t, p + 1, 2) || substr(t, p + 4, 2) || substr(t, p + 7, 2) || f;
            END IF;
            -- A moment by its UTC day and clock, a day before or after its own where the offset carries it past midnight.
            u := u - o;
            IF u < 0 THEN
                u := u + 86400;
                IF d > 1 THEN
                    d := d - 1;
                ELSIF m > 1 THEN
                    m := m - 1;
                    d := CASE WHEN m IN (4, 6, 9, 11) THEN 30 WHEN m = 2 THEN CASE WHEN y % 4 = 0 AND (y % 100 <> 0 OR y % 400 = 0) THEN 29 ELSE 28 END ELSE 31 END;
                ELSE
                    y := y - 1;
                    m := 12;
                    d := 31;
                END IF;
            ELSIF u >= 86400 THEN
                u := u - 86400;
                -- The days of its month, worked out before the test: an IF reads its condition up to the first THEN.
                days := CASE WHEN m IN (4, 6, 9, 11) THEN 30 WHEN m = 2 THEN CASE WHEN y % 4 = 0 AND (y % 100 <> 0 OR y % 400 = 0) THEN 29 ELSE 28 END ELSE 31 END;
                IF d < days THEN
                    d := d + 1;
                ELSIF m < 12 THEN
                    m := m + 1;
                    d := 1;
                ELSE
                    y := y + 1;
                    m := 1;
                    d := 1;
                END IF;
            END IF;
            RETURN lpad(CAST(y + $YEAR_BIAS AS text), 10, '0') || lpad(CAST(m AS text), 2, '0') || lpad(CAST(d AS text), 2, '0') ||
                lpad(CAST(u / 3600 AS text), 2, '0') || lpad(CAST(u % 3600 / 60 AS text), 2, '0') || lpad(CAST(u % 60 AS text), 2, '0') || f ||
                lpad(CAST(o + $OFFSET_BIAS AS text), 6, '0');
        END
        '
    """.trimIndent().let { function ->
        val (start, end) = function.indexOf("AS '\n") + 5 to function.lastIndexOf("\n'")
        function.substring(0, start) + function.substring(start, end).replace("'", "''") + function.substring(end)
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

    /**
     * The partial index over [properties] of the nodes of [label]: each property as a statement reads it, and the test of
     * the label a statement makes, both of the table's own columns.
     */
    private fun over(label: String, properties: List<String>): String =
        "$nodes (${indexColumns(properties).joinToString(", ")}) WHERE ${hasLabel("", label)}"

    /**
     * The columns of a range index's or a uniqueness constraint's index over the stored [keys], in order, each as the
     * module writes it: what the store's schema compares the columns it finds under the index's name with.
     */
    fun indexColumns(keys: List<String>): List<String> = keys.map { "(${PostgresSql.indexed("", it)})" }

    override fun createIndex(spec: IndexSpec, existing: CatalogItem?): List<DdlStatement> = when (spec) {
        is RangeIndexSpec -> {
            val name = objectName(RANGE, listOf(spec.label) + spec.properties)
            statements("CREATE INDEX IF NOT EXISTS $name ON ${over(spec.label, spec.keys)}", recorded(spec, name))
        }
        is VectorIndexSpec -> {
            // An HNSW index over the embeddings of the label's nodes that are of its size: one of another size is left out, not refused.
            val name = objectName(VECTOR, listOf(spec.label, spec.properties.single()))
            val key = spec.keys.single()
            statements(
                "CREATE EXTENSION IF NOT EXISTS vector",
                "CREATE INDEX IF NOT EXISTS $name ON $nodes USING hnsw ((${PostgresSql.embedding("", key, spec.dimensions)}) ${PostgresSql.operators(spec.similarity)}) " +
                    "WHERE ${hasLabel("", spec.label)} AND ${PostgresSql.holdsEmbedding("", key, spec.dimensions)}",
                recorded(spec, name, vectorDetails(spec)),
            )
        }
        is FullTextIndexSpec -> {
            // Trigrams of the text a search reads, which answer whether it holds a term's words.
            val name = objectName(FULLTEXT, listOf(spec.label) + spec.properties)
            statements(
                "CREATE EXTENSION IF NOT EXISTS pg_trgm",
                "CREATE INDEX IF NOT EXISTS $name ON $nodes USING gin ((${PostgresSql.searchedText("", spec.keys)}) gin_trgm_ops) WHERE ${hasLabel("", spec.label)}",
                recorded(spec, name, analyzerDetails(spec.analyzer)),
            )
        }
        is TextIndexSpec -> throw unsupportedTextIndex(spec, engine)
    }

    override fun dropIndex(item: CatalogItem): List<DdlStatement> = forgotten(item)

    override fun createConstraint(spec: ConstraintSpec): List<DdlStatement> = when (spec) {
        is UniquenessConstraintSpec -> {
            val name = objectName(UNIQUE, listOf(spec.label) + spec.properties)
            statements("CREATE UNIQUE INDEX IF NOT EXISTS $name ON ${over(spec.label, spec.keys)}", recorded(spec, name))
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
        const val VERSION = "drivine:8"

        /** The function the store of the graph [graph] keeps that works out a temporal value's sort key: see [temporalKey]. */
        fun temporalKeyName(graph: String) = "${graph}_temporal_key"

        /** Why [name] cannot name a graph here; null where it can (see [SqlStoreDdl.refusal]). */
        fun refusal(name: String): String? = SqlStoreDdl.refusal(name, ENGINE, MAX_OBJECT_NAME)
    }
}
