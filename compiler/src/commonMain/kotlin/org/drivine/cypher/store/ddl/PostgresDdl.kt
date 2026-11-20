package org.drivine.cypher.store.ddl

import org.drivine.cypher.sql.PostgresCypherCompiler
import org.drivine.cypher.sql.TemporalArithmeticFunction
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

    /**
     * What makes the store, when it is not there. Each statement is safe to run again. Made where it is first read: it holds
     * the function of temporal arithmetic, which takes some milliseconds to compile, and a search makes the DDL for its names
     * alone, on every call.
     */
    val ddl: List<String> by lazy { listOf(
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
    ).map { it.trimIndent() } + writes.ddl + temporalKeyFunction() + heldFunction() + TemporalArithmeticFunction.postgres(name) + listKeyFunction() + "COMMENT ON TABLE $nodes IS '$VERSION'" }

    override val lockTable: String get() = "CREATE TABLE IF NOT EXISTS $lock (name text PRIMARY KEY)"

    override val lockSeed: String get() = "INSERT INTO $lock (name) VALUES ('$lockRow') ON CONFLICT DO NOTHING"

    /** The function compiled Cypher reads a temporal value's text through before a timestamp is read of it: see [heldFunction]. */
    val held: String = heldName(name)

    /**
     * Makes [held]: the text `t` as it is, or the statement failing by name where its fraction of a second has a seventh,
     * eighth or ninth digit that isn't 0, which a timestamp's microseconds would round away (drivine4j#421). The digits are
     * read one by one from the point, to the first that is none, an offset's sign or `Z`, or the text's end: no regular
     * expression, whose `REGEXP_REPLACE` cost Postgres some 60 µs a row, and a text with no point is given back at once. Its
     * message is [org.drivine.cypher.sql.Temporals.SUB_MICRO], as the test the SQL writes where the store keeps no such function.
     */
    private fun heldFunction(): String = quotedBody("""
        CREATE OR REPLACE FUNCTION ${heldName(name)}(t text) RETURNS text LANGUAGE plpgsql IMMUTABLE PARALLEL SAFE AS '
        DECLARE
            p int := strpos(t, '.');
            c text;
        BEGIN
            IF p = 0 THEN
                RETURN t;
            END IF;
            FOR i IN 1 .. 9 LOOP
                c := substr(t, p + i, 1);
                EXIT WHEN c = '' OR c < '0' OR c > '9';
                IF i > 6 AND c <> '0' THEN
                    RAISE EXCEPTION '% %', '${org.drivine.cypher.sql.Temporals.SUB_MICRO.replace("'", "''")}', t;
                END IF;
            END LOOP;
            RETURN t;
        END
        '
    """.trimIndent())

    /** The function compiled Cypher sorts a list of a kind the statement leaves open by: see [listKeyFunction]. */
    val listKey: String = listKeyName(name)

    /**
     * Makes [listKey]: the sort key, as text, of a list whose kind the statement leaves open, as one read from a property, the
     * query the compiler writes for it (`PostgresCypherCompiler.flatListKeyBody`) over its argument. SQL, which the planner
     * doesn't write out where it is called, as it holds a query: so it costs the call as declared, low, where the query written
     * out in a sort key was costed on every row, brought in its JIT and ruled out a parallel plan (drivine-cypher-spec#284).
     * Its body is a text in quotes, not dollars, so the block [ensuring] makes the store in reads it as one statement.
     */
    private fun listKeyFunction(): String =
        "CREATE OR REPLACE FUNCTION $listKey(cy_list jsonb) RETURNS text LANGUAGE sql IMMUTABLE PARALLEL SAFE COST 5 AS '" +
            PostgresCypherCompiler(name).flatListKeyBody("cy_list").replace("'", "''") + "'"

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
    """.trimIndent().let(::quotedBody)

    /** [function] with each quote of its body, between `AS '` and the last line's `'`, written twice, as Postgres reads a body in quotes. */
    private fun quotedBody(function: String): String {
        val (start, end) = function.indexOf("AS '\n") + 5 to function.lastIndexOf("\n'")
        return function.substring(0, start) + function.substring(start, end).replace("'", "''") + function.substring(end)
    }

    /**
     * The block that makes the store when it is not there, and does nothing when it is: the probe, the lock and the [ddl]
     * in one. The node table's comment, written last, says which version made the store: one this version made is left
     * alone, and the rest is made again over one an earlier version made, which keeps its rows and has its functions
     * replaced. The block takes a lock of its own for the graph first, so two processes that each find the store missing
     * make it one after the other, and the second, looking again once it has the lock, makes nothing. Postgres runs a
     * block's schema statements in its transaction, so the store is made whole or not at all. Made where it is first read, as [ddl] is.
     */
    val ensuring: String by lazy { """
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
    """.trimIndent() }

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

    /**
     * What makes [spec]: its index built `CONCURRENTLY`, so that writes to the graph go on while it is built, a statement
     * of its own outside any block, as Postgres builds one so only there. Where a transaction holds the connection, Postgres
     * refuses it, and the index is built as [blocking] gives it. A build cut short leaves the index invalid under its name:
     * the listing says so ([INVALID]), and [completeIndex] builds it again.
     */
    override fun createIndex(spec: IndexSpec, existing: CatalogItem?): List<DdlStatement> = when (spec) {
        is RangeIndexSpec -> statements("$CREATE_INDEX ${madeName(spec)} ON ${over(spec.label, spec.keys)}", recorded(spec, madeName(spec)))
        is VectorIndexSpec -> {
            // An HNSW index over the embeddings of the label's nodes that are of its size: one of another size is left out, not refused.
            val key = spec.keys.single()
            statements(
                "CREATE EXTENSION IF NOT EXISTS vector",
                "$CREATE_INDEX ${madeName(spec)} ON $nodes USING hnsw ((${PostgresSql.embedding("", key, spec.dimensions)}) ${PostgresSql.operators(spec.similarity)}) " +
                    "WHERE ${hasLabel("", spec.label)} AND ${PostgresSql.holdsEmbedding("", key, spec.dimensions)}",
                recorded(spec, madeName(spec), vectorDetails(spec)),
            )
        }
        is FullTextIndexSpec -> {
            // Trigrams of the text a search reads, which answer whether it holds a term's words.
            statements(
                "CREATE EXTENSION IF NOT EXISTS pg_trgm",
                "$CREATE_INDEX ${madeName(spec)} ON $nodes USING gin ((${PostgresSql.searchedText("", spec.keys)}) gin_trgm_ops) WHERE ${hasLabel("", spec.label)}",
                recorded(spec, madeName(spec), analyzerDetails(spec.analyzer)),
            )
        }
        is TextIndexSpec -> throw unsupportedTextIndex(spec, engine)
    }

    /** The name the index of [spec] is made under. */
    private fun madeName(spec: IndexSpec): String = when (spec) {
        is RangeIndexSpec -> objectName(RANGE, listOf(spec.label) + spec.properties)
        is VectorIndexSpec -> objectName(VECTOR, listOf(spec.label, spec.properties.single()))
        is FullTextIndexSpec -> objectName(FULLTEXT, listOf(spec.label) + spec.properties)
        is TextIndexSpec -> throw unsupportedTextIndex(spec, engine)
    }

    /**
     * The index of [spec] that the listing found invalid, an online build of it cut short, dropped and built again. It is
     * dropped by the name this version makes it under, which is the invalid one's: an ensure completes only an item of this
     * version's form, and one no record lists is found under that name ([org.drivine.cypher.store.schema.StoreSchema.isLeftIncomplete]).
     * `IF NOT EXISTS` would pass over the invalid index, which stands under the name.
     */
    override fun completeIndex(spec: IndexSpec, existing: CatalogItem?): List<DdlStatement> = droppedIncomplete(spec) + createIndex(spec, existing)

    /** A `CONCURRENTLY` build the data refuses leaves its index invalid under its name. */
    override val buildsLeaveInvalidIndexes: Boolean
        get() = true

    /**
     * Each online build under way on the node table, by `pg_stat_progress_create_index`; and as Postgres hides which table
     * another role's build is on (its `relid` reads null), any build in this database whose table is hidden, as
     * [statusOf] takes one.
     */
    override val onlineBuildsQuery: String
        get() = "SELECT p.pid FROM pg_stat_progress_create_index p WHERE p.relid = to_regclass(${literal(nodes)}) " +
            "OR (p.relid IS NULL AND p.datname = current_database())"

    /**
     * A claim in the catalog, of the kind `CLAIM:` and the item's, which no listing reads; taken over where it is older
     * than [CLAIM_STALE_SECONDS] and no online build is under way on the node table ([onlineBuildsQuery]), as a session
     * that holds it is building, or between its statements.
     */
    override fun buildClaim(spec: SchemaItemSpec): BuildClaim {
        val key = "kind = ${literal(CLAIM + spec.kind.name)} AND label = ${literal(spec.label)} AND properties = ${literal(json(spec.properties))}"
        val take = """
            INSERT INTO $catalog AS c (kind, label, properties, details)
            VALUES (${literal(CLAIM + spec.kind.name)}, ${literal(spec.label)}, ${literal(json(spec.properties))}, jsonb_build_object('at', clock_timestamp()::text))
            ON CONFLICT (kind, label, properties) DO UPDATE SET details = EXCLUDED.details
            WHERE (c.details ->> 'at')::timestamptz < clock_timestamp() - interval '$CLAIM_STALE_SECONDS seconds'
              AND NOT EXISTS ($onlineBuildsQuery)
            RETURNING jsonb_build_object('at', c.details ->> 'at')
        """.trimIndent()
        return BuildClaim(take, "DELETE FROM $catalog WHERE $key AND details ->> 'at' = CAST(${'$'}at AS text)")
    }

    /**
     * The index of [spec] an online build left invalid, dropped by the name this version makes it under, which is the
     * invalid one's: as before [completeIndex] builds an index again, and as after a uniqueness constraint's online build
     * the data refuses (#382), which leaves its unique index invalid under the name, where `IF NOT EXISTS` would pass over it.
     *
     * Dropped only where it is invalid with no build of it under way, as the listing gives it [INVALID] ([statusOf]), and
     * still invalid once its table is held: another session may have dropped it since it was listed, and built it again
     * online, which is invalid too until it is done. One being built is left, and the ensure waits for that build in the
     * listing: a lock queued behind the build would hold up every read and write of the graph's nodes until it ended, and
     * the block's snapshot would hold up the build's last phase, which waits for older snapshots, a deadlock. A
     * `DROP INDEX CONCURRENTLY` would wait for that build and then drop a valid index that holds the data unique. The cost
     * is that reads and writes of the graph's nodes wait while the block holds the table, which is only for the drop of an
     * invalid index, or for no time where there is none.
     */
    override fun droppedIncomplete(spec: SchemaItemSpec): List<DdlStatement> {
        val name = madeObjectName(spec)
        return listOf(
            DdlStatement.Text(
                statement(
                    """
                    DO ${'$'}incomplete${'$'}
                    DECLARE
                        v_table regclass;
                    BEGIN
                        SELECT x.indrelid::regclass INTO v_table FROM pg_index x WHERE x.indexrelid = to_regclass(${literal(name)}) AND ${statusOf("x")} = ${literal(INVALID)};
                        IF v_table IS NOT NULL THEN
                            EXECUTE 'LOCK TABLE ' || v_table || ' IN ACCESS EXCLUSIVE MODE';
                            IF EXISTS (SELECT 1 FROM pg_index WHERE indexrelid = to_regclass(${literal(name)}) AND NOT indisvalid) THEN
                                DROP INDEX $name;
                            END IF;
                        END IF;
                    END
                    ${'$'}incomplete${'$'}
                    """
                )
            )
        )
    }

    /** The name the index of [spec], an index or a uniqueness constraint, is made under. */
    private fun madeObjectName(spec: SchemaItemSpec): String = when (spec) {
        is IndexSpec -> madeName(spec)
        is UniquenessConstraintSpec -> objectName(UNIQUE, listOf(spec.label) + spec.properties)
    }

    /**
     * [statement] without `CONCURRENTLY`, which a transaction refuses: a build or a drop that blocks writes while it runs.
     * Only a statement that begins so is one: a label or a name holding the words elsewhere is left as it is.
     */
    override fun blocking(statement: DdlStatement): DdlStatement? {
        val text = (statement as? DdlStatement.Text)?.statement ?: return null
        val verb = listOf("CREATE UNIQUE", "CREATE", "DROP").firstOrNull { text.startsWith("$it INDEX CONCURRENTLY ") } ?: return null
        return DdlStatement.Text("$verb INDEX " + text.removePrefix("$verb INDEX CONCURRENTLY "))
    }

    override fun dropIndex(item: CatalogItem): List<DdlStatement> = forgotten(item)

    override fun createConstraint(spec: ConstraintSpec): List<DdlStatement> = when (spec) {
        is UniquenessConstraintSpec -> {
            val name = objectName(UNIQUE, listOf(spec.label) + spec.properties)
            // Online, as an index is (#382): a build the data refuses leaves it invalid, which the planner drops.
            statements("CREATE UNIQUE INDEX CONCURRENTLY IF NOT EXISTS $name ON ${over(spec.label, spec.keys)}", recorded(spec, name))
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
                build = listOf(DdlStatement.Text(statement("CREATE UNIQUE INDEX $temporary ON ${over(spec.label, spec.keys)}"))),
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
     * A record whose index is invalid has a `status` ([recordStatus]): [BUILDING] while a build of it is under way, and
     * [INVALID] where none is. So has such an index among the objects, recorded or not: a build under way, or cut short,
     * before its record was written leaves none.
     */
    private fun listed(kinds: String, objects: List<String>): String = if (objects.isEmpty()) """
        SELECT jsonb_build_object('kind', kind, 'label', label, 'properties', properties::jsonb, 'name', name, 'details', details, 'status', $recordStatus)
        FROM $catalog c
        WHERE kind IN ($kinds)
          AND (object_name IS NULL OR to_regclass(object_name) IS NOT NULL)
        ORDER BY label, properties
    """.trimIndent() else """
        SELECT listed FROM (
            SELECT 0 AS part, label, properties, jsonb_build_object('kind', kind, 'label', label, 'properties', properties::jsonb, 'name', name, 'details', details, 'status', $recordStatus) AS listed
            FROM $catalog c
            WHERE kind IN ($kinds)
              AND (object_name IS NULL OR to_regclass(object_name) IS NOT NULL)
            UNION ALL
            SELECT 1, o.relname::varchar, NULL,
                   jsonb_build_object('object', o.relname, 'type', o.relkind, 'table', t.relname, 'unique', i.indisunique, 'method', a.amname,
                                      'columns', (SELECT jsonb_agg(pg_get_indexdef(i.indexrelid, k, true) ORDER BY k) FROM generate_series(1, i.indnatts) k),
                                      'where', pg_get_expr(i.indpred, i.indrelid, true),
                                      'opclasses', (SELECT jsonb_agg(oc.opcname ORDER BY k.n) FROM unnest(i.indclass::oid[]) WITH ORDINALITY AS k(oid, n) JOIN pg_opclass oc ON oc.oid = k.oid),
                                      'status', ${statusOf("i")})
            FROM pg_class o
            LEFT JOIN pg_index i ON i.indexrelid = o.oid
            LEFT JOIN pg_class t ON t.oid = i.indrelid
            LEFT JOIN pg_am a ON a.oid = o.relam
            WHERE (${namedAs("o.relname", objects, upper = false)}) AND pg_table_is_visible(o.oid)
        ) l
        ORDER BY part, label, properties
    """.trimIndent()

    /** The `status` of the record's index, as [statusOf] gives it: null where it is valid, or the record names none. */
    private val recordStatus = """(SELECT ${statusOf("x")} FROM pg_index x WHERE x.indexrelid = to_regclass(c.object_name))"""

    /**
     * The `status` of the index of [index], a `pg_index` row: null where it is valid; [BUILDING] where it is invalid and a
     * build of it is under way, an online build that isn't done; and [INVALID] where it is invalid and none is, a build cut
     * short, or failed. Postgres hides which index another role's build is of (its `index_relid` reads null), so any build
     * in this database whose index is hidden is taken as maybe this one's: an ensure that waits on it waits for that build.
     */
    private fun statusOf(index: String): String =
        "CASE WHEN $index.indisvalid THEN NULL " +
            "WHEN EXISTS (SELECT 1 FROM pg_stat_progress_create_index p WHERE p.index_relid = $index.indexrelid " +
            "OR (p.index_relid IS NULL AND p.datname = current_database())) THEN ${literal(BUILDING)} " +
            "ELSE ${literal(INVALID)} END"

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

        /** How an index is begun: online, and only where there is none under its name. */
        const val CREATE_INDEX = "CREATE INDEX CONCURRENTLY IF NOT EXISTS"

        /**
         * The `status` the listing gives a record whose index is invalid and not being built: an online build of it was cut
         * short, or failed, and no statement reads it.
         */
        const val INVALID = "INVALID"

        /**
         * The `status` the listing gives a record, or an index among the objects, whose index another session is building
         * online: invalid until the build is done, when it is valid, or [INVALID] where the data refuses it or it is cut
         * short. An ensure waits for the build and reads how it ended, rather than take it as made or drop it (#382).
         */
        /** What a [buildClaim]'s kind begins with, before the item's: no item's kind does. */
        const val CLAIM = "CLAIM:"

        /** How old a [buildClaim] is taken over at: a build's default timeout (`PlanRequest.DEFAULT_BUILD_TIMEOUT_MILLIS`). */
        const val CLAIM_STALE_SECONDS = 60

        const val BUILDING = "BUILDING"

        /**
         * What the node table's comment says of a store this version made. A store an earlier version made has its functions
         * made again when it is ensured: 9 finds a node by its id's JSON (#245), and 10 keeps the function of temporal arithmetic
         * (drivine4j#418), whose body changes with the compiler's: a change of the DDL bumps it (`store.yaml`'s `generated`), as
         * 11 does, the function cutting a duration's quotients with `div` (drivine-cypher-spec#275), the `_held` function made beside it
         * (drivine4j#421), and the `_list_key` function a sort calls (drivine-cypher-spec#284); 12 keys an unlink's targets with a
         * text id marked apart from a temporal value or a number that reads alike (drivine-cypher-spec#391).
         */
        const val VERSION = "drivine:12"

        /** The function the store of the graph [graph] keeps that works out a temporal value's sort key: see [temporalKey]. */
        fun temporalKeyName(graph: String) = "${graph}_temporal_key"

        /** The function the store of the graph [graph] keeps that refuses a nanosecond past the microsecond: see [held]. */
        fun heldName(graph: String) = "${graph}_held"

        /** [listKey]'s name, for the graph [graph]: what it adds to the name is at most 16 long, as each name the store gives is. */
        fun listKeyName(graph: String) = "${graph}_list_key"

        /** Why [name] cannot name a graph here; null where it can (see [SqlStoreDdl.refusal]). */
        fun refusal(name: String): String? = SqlStoreDdl.refusal(name, ENGINE, MAX_OBJECT_NAME)
    }
}
