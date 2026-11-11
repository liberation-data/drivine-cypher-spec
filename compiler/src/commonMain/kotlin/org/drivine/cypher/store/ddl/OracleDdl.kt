package org.drivine.cypher.store.ddl

import org.drivine.DrivineInternalApi
import org.drivine.cypher.sql.OracleCypherCompiler
import org.drivine.cypher.store.OracleSql
import org.drivine.cypher.store.OracleSql.path
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
 * A graph as Oracle keeps it: the tables of a [SqlStoreDdl], the SQL property graph over the two that reads go through,
 * the functions compiled Cypher calls, and the graph's indexes and constraints.
 *
 * A relationship's type is a column. The property graph therefore has one vertex label and one edge label, and what Cypher
 * writes as a label or a type is a predicate on `labels` or `type`. The graph gives each node one more property, `doc`: the
 * node as the map a load returns for it ([OracleSql.transform]).
 *
 * A property is a path into a JSON column, so each index is over an expression:
 *
 * - a range index is two indexes. One reads the property as text whatever the label, the expression a save and a load by
 *   id find a node by, shared by two labels that index a property of one name. The other, the labelled index, reads the
 *   property of the nodes that carry the label and is null for every other node ([OracleSql.labelled]): a filter on the
 *   label's nodes is read through it, so one index answers both the label and the property;
 * - a uniqueness constraint is a unique index over the property of the nodes that carry the label, and null for every other
 *   node ([OracleSql.unique]). It comes with the range index a node is found by. Over one property its index is the same
 *   value a labelled index would be, so it stands for one, and no labelled index is made beside it.
 *
 * A vector index is a table of embeddings beside the nodes, and a full-text index a table of their text, each with an
 * index over it ([OracleSideTable]). Oracle's dictionary holds the expressions and not what they were made for, so what
 * Drivine made is recorded in the catalog table, and read back from there.
 */
@DrivineInternalApi
class OracleDdl(name: String) : SqlStoreDdl(name, ENGINE, MAX_OBJECT_NAME) {

    override val lockTable: String get() = "CREATE TABLE IF NOT EXISTS $lock (name VARCHAR2(128) PRIMARY KEY)"

    override val lockSeed: String get() =
        "MERGE INTO $lock l USING (SELECT '$lockRow' AS name FROM dual) s ON (l.name = s.name) WHEN NOT MATCHED THEN INSERT (name) VALUES (s.name)"

    /** The count store's tables and index (see [counts]) and the lock table (see [lock]), made with the rest, and on a store an earlier version made. */
    private val countStore: List<String> = listOf(
        """
        CREATE TABLE IF NOT EXISTS $counts (
            kind VARCHAR2(16) NOT NULL,
            name VARCHAR2(1000) NOT NULL,
            n    NUMBER(19) NOT NULL,
            PRIMARY KEY (kind, name)
        )
        """,
        """
        CREATE TABLE IF NOT EXISTS $countDeltas (
            id   NUMBER GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
            kind VARCHAR2(16) NOT NULL,
            name VARCHAR2(1000) NOT NULL,
            d    NUMBER(19) NOT NULL
        )
        """,
        "CREATE INDEX IF NOT EXISTS $countDeltasIndex ON $countDeltas (kind, name)",
        lockTable,
        lockSeed,
    ).map { it.trimIndent() }

    /** What makes the store, when it is not there. Each statement is safe to run again. */
    val ddl: List<String> = (listOf(
        """
        CREATE TABLE IF NOT EXISTS $nodes (
            nid    NUMBER GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
            labels JSON NOT NULL,
            props  JSON NOT NULL,
            stamp  VARCHAR2(33),
            words  JSON
        )
        """,
        """
        CREATE TABLE IF NOT EXISTS $edges (
            eid   NUMBER GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
            src   NUMBER NOT NULL REFERENCES $nodes (nid) ON DELETE CASCADE,
            dst   NUMBER NOT NULL REFERENCES $nodes (nid) ON DELETE CASCADE,
            type  VARCHAR2(128) NOT NULL,
            props JSON DEFAULT '{}' NOT NULL
        )
        """,
        """
        CREATE TABLE IF NOT EXISTS $catalog (
            kind        VARCHAR2(40) NOT NULL,
            label       VARCHAR2(400) NOT NULL,
            properties  VARCHAR2(2000) NOT NULL,
            name        VARCHAR2(400),
            object_name VARCHAR2(128),
            details     JSON NOT NULL,
            PRIMARY KEY (kind, label, properties)
        )
        """,
        "CREATE INDEX IF NOT EXISTS ${name}_edge_src ON $edges (src, type)",
        "CREATE INDEX IF NOT EXISTS ${name}_edge_dst ON $edges (dst, type)",
        "CREATE MULTIVALUE INDEX IF NOT EXISTS ${name}_node_labels ON $nodes n (n.labels.string())",
    ).map { it.trimIndent() } + countStore + listOf(
        """
        CREATE OR REPLACE PROPERTY GRAPH $name
            VERTEX TABLES ($nodes KEY (nid) LABEL node PROPERTIES (nid, labels, props, stamp, ${OracleSql.transform("")} AS doc))
            EDGE TABLES ($edges KEY (eid)
                SOURCE KEY (src) REFERENCES $nodes (nid)
                DESTINATION KEY (dst) REFERENCES $nodes (nid)
                LABEL edge PROPERTIES (eid, src, dst, type, props))
        """,
    )).map { it.trimIndent() }

    /**
     * The function a compiled Cypher statement compares two values that may hold a null by, list item by list item and
     * map key by map key (see `OracleCypherCompiler.nullsEqual`): a statement that writes is a PL/SQL block, whose queries
     * call no function of its own, so the store keeps it. Drivine for TypeScript and for Python make it byte for byte alike.
     */
    val nullsEqual: String = OracleCypherCompiler.nullsEqualName(name)

    /**
     * The functions a compiled Cypher statement renumbers a year that may be BC by, as it reads an ISO text ([era]) and as
     * it writes one ([eraDate]): each reads its text once, where the SQL it stands for read it three or four times, which
     * Oracle took far longer to plan (see `OracleTemporalSql.era`). Drivine for TypeScript and for Python make them byte
     * for byte alike.
     */
    val era: String = OracleCypherCompiler.eraName(name)

    /** See [era]. */
    val eraDate: String = OracleCypherCompiler.eraDateName(name)

    /**
     * The function a compiled Cypher statement works out the sort key of a temporal value it makes by (see
     * `OracleCypherCompiler.temporalKeyFunction`), called where [era]'s are. Never in an index.
     */
    val temporalKey: String = OracleCypherCompiler.temporalKeyName(name)

    /** Each function the store keeps beside the graph's tables, by name, and what makes it: safe to run again. */
    private val made: Map<String, String> = linkedMapOf(
        nullsEqual to OracleCypherCompiler.nullsEqualFunction(nullsEqual),
        era to OracleCypherCompiler.eraFunction(era),
        eraDate to OracleCypherCompiler.eraDateFunction(eraDate),
        temporalKey to OracleCypherCompiler.temporalKeyFunction(temporalKey),
    ).mapValues { (_, function) -> "CREATE OR REPLACE $function" }

    /** What makes [nullsEqual], [era] and [eraDate]: safe to run again. */
    val functions: List<String> = made.values.toList()

    /**
     * The block that makes the store when it is not there, and does nothing when it is. The
     * property graph is made last, so one that is there, with the `doc` property this version gives
     * it, says the rest is. It is looked for before anything is run: a schema statement wants its
     * table to itself, and would be turned away by a writer at work on a store that needs nothing
     * made. A graph made by an earlier version is replaced, and keeps its tables. One that another
     * process made in the meantime (`ORA-00955`) is taken as made. A statement turned away because
     * another session holds a lock it wants (`ORA-00054`), as one making the same store from another
     * pool or process does, is tried again a tenth of a second later, for up to [BUSY_ATTEMPTS] tries:
     * Oracle's DDL does not wait for a lock unless the session is told to, and telling it would outlast
     * the block on a pooled connection.
     *
     * Each of the [functions] is made where it is not there, or not valid, so a store an earlier version made gains those it
     * lacks. A user who may not make a function (`ORA-01031`: making one takes `CREATE PROCEDURE`, which the `RESOURCE` role
     * grants) still has the store made: a statement then calls none of those it lacks (see `GraphOptions.nullsEqualStored`).
     */
    val ensuring: String = """
        DECLARE
            v_made NUMBER;
            e_exists EXCEPTION;
            PRAGMA EXCEPTION_INIT(e_exists, -955);
            e_denied EXCEPTION;
            PRAGMA EXCEPTION_INIT(e_denied, -1031);
            e_busy EXCEPTION;
            PRAGMA EXCEPTION_INIT(e_busy, -54);
            PROCEDURE make(p_ddl VARCHAR2) IS
            BEGIN
                FOR attempt IN 1 .. $BUSY_ATTEMPTS LOOP
                    BEGIN
                        EXECUTE IMMEDIATE p_ddl;
                        RETURN;
                    EXCEPTION
                        WHEN e_exists THEN RETURN;
                        WHEN e_busy THEN
                            IF attempt = $BUSY_ATTEMPTS THEN RAISE; END IF;
                            DBMS_SESSION.SLEEP(0.1);
                    END;
                END LOOP;
            END;
        BEGIN
            SELECT COUNT(*) INTO v_made FROM user_pg_prop_definitions WHERE graph_name = '${name.uppercase()}' AND property_name = 'DOC';
            IF v_made = 0 THEN
        ${ddl.joinToString("\n") { "        make(${OracleSql.literal(it)});" }}
            END IF;
            -- The store layout 2's count store and the node's derived fields, where an earlier version made the store.
            SELECT COUNT(*) INTO v_made FROM user_tables WHERE table_name = '${counts.uppercase()}';
            IF v_made = 0 THEN
        ${countStore.joinToString("\n") { "        make(${OracleSql.literal(it)});" }}
            END IF;
            SELECT COUNT(*) INTO v_made FROM user_tab_columns WHERE table_name = '${nodes.uppercase()}' AND column_name = 'WORDS';
            IF v_made = 0 THEN
                make('ALTER TABLE $nodes ADD (words JSON)');
            END IF;
${made.entries.joinToString("\n") { (function, making) ->
            listOf(
                "SELECT COUNT(*) INTO v_made FROM user_objects WHERE object_type = 'FUNCTION' AND object_name = '${function.uppercase()}' AND status = 'VALID';",
                "IF v_made = 0 THEN",
                "    BEGIN EXECUTE IMMEDIATE ${OracleSql.literal(making)}; EXCEPTION WHEN e_denied THEN NULL; END;",
                "END IF;",
            ).joinToString("\n") { "            $it" }
        }}
        END;
    """.trimIndent()

    /** [text] as a schema statement. */
    private fun statement(text: String): String = text.trimIndent()

    /**
     * The PL/SQL that runs [ddl], an index over the node table, again a tenth of a second after another session's lock
     * turns it away (`ORA-00054`), for up to [BUSY_ATTEMPTS] tries, as [ensuring] runs its statements
     * (liberation-data/drivine4j#517). Oracle's DDL does not wait for a lock: managers starting together each make the
     * store's key constraint, and one whose statement met another's build over the node table, or a write of its stamp,
     * was turned away at once, and went on without the constraint where it then looked before the other's build was done.
     * Waited out, it finds the index the other made. One that another session made in the meantime, after this one's
     * `IF NOT EXISTS` found none (`ORA-00955`), is taken as made, as [ensuring] takes it. It goes inside a block that
     * declares [EXISTS] and [BUSY], at the column of the template it is written into, [indent]: each line after the first
     * is indented to it, so the statement keeps its shape once trimmed, with no line of spaces alone.
     */
    private fun waitingOutBusy(ddl: String, indent: Int): String = listOf(
        "FOR attempt IN 1 .. $BUSY_ATTEMPTS LOOP",
        "    BEGIN",
        "        EXECUTE IMMEDIATE ${literal(ddl)};",
        "        EXIT;",
        "    EXCEPTION",
        "        WHEN e_exists THEN EXIT;",
        "        WHEN e_busy THEN",
        "            IF attempt = $BUSY_ATTEMPTS THEN RAISE; END IF;",
        "            DBMS_SESSION.SLEEP(0.1);",
        "    END;",
        "END LOOP;",
    ).joinToString("\n" + " ".repeat(indent))

    /** [ddl], an index over the node table, as a block of its own that waits out another session's lock: see [waitingOutBusy]. */
    private fun busyWaiting(ddl: String): String = """
        DECLARE
            $EXISTS
            $BUSY
        BEGIN
            ${waitingOutBusy(ddl, 12)}
        END;
    """

    /** [text] as schema statements, after the one that makes the store when it is not there. */
    private fun statements(vararg text: String): List<DdlStatement> = (listOf(ensuring) + text).map { DdlStatement.Text(statement(it)) }

    /**
     * Records [spec] as made, as [objectName] (null for one nothing was made for), unless it is
     * recorded already; with [updated], a record already there takes [details], a range index's, and
     * keeps its aliases (see [rangeDetails]). A `MERGE` does not
     * read again before it inserts, so of two processes recording it at once the second is refused on
     * the catalog's key; that refusal is taken as recorded, and never read as the constraint itself
     * violated.
     */
    private fun recorded(spec: SchemaItemSpec, objectName: String?, details: String = "{}", updated: Boolean = false): String = """
        BEGIN
            MERGE INTO $catalog c
            USING (SELECT ${literal(spec.kind.name)} AS kind, ${literal(spec.label)} AS label, ${literal(json(spec.properties))} AS properties FROM dual) k
            ON (c.kind = k.kind AND c.label = k.label AND c.properties = k.properties)${if (updated) "\n            WHEN MATCHED THEN UPDATE SET c.details = JSON_MERGEPATCH(c.details, ${literal(rangeDetails(formed(details)))})" else ""}
            WHEN NOT MATCHED THEN INSERT (kind, label, properties, name, object_name, details)
                VALUES (k.kind, k.label, k.properties, ${literal(spec.effectiveName)}, ${objectName?.let(::literal) ?: "NULL"}, ${literal(formed(details))});
        EXCEPTION WHEN DUP_VAL_ON_INDEX THEN
            -- Another process recorded it between the MERGE's read and its insert: it is recorded.
            NULL;
        END;
    """

    /**
     * [details], a range index's, as the patch of a record's details that gives it them: each other key a range
     * index's details hold is taken out, and the aliases the record keeps (see [aliased]) are kept.
     */
    private fun rangeDetails(details: String): String {
        val absent = RANGE_DETAILS.filter { "\"$it\"" !in details }.joinToString(", ") { "\"$it\": null" }
        if (absent.isEmpty()) return details
        val body = details.trim().removePrefix("{").removeSuffix("}").trim()
        return if (body.isEmpty()) "{$absent}" else "{$body, $absent}"
    }

    override fun aliased(item: CatalogItem, aliases: List<String>): String {
        val patch = "{\"$ALIASES\": ${if (aliases.isEmpty()) "null" else aliasesJson(aliases)}}"
        return statement("UPDATE $catalog SET details = JSON_MERGEPATCH(details, ${literal(patch)}) WHERE ${recordOf(item)}")
    }

    /**
     * Forgets [item], and drops what it was made as when nothing else recorded uses it: an index, or
     * for a vector or a full-text index the table beside the nodes with its trigger, which takes the
     * index over it along. What is dropped is the object the record names, and not one named again
     * from the label and the properties, so an item is dropped as it was made whatever a later
     * version would call it. An item nothing was made for, a full-text index where there is no
     * Oracle Text, records no object and has none dropped. A range index's labelled index is its
     * own, and is dropped with the record.
     */
    private fun forgotten(item: CatalogItem): List<DdlStatement> {
        val drops = when (item.kind) {
            SchemaItemKind.VECTOR_INDEX, SchemaItemKind.FULLTEXT_INDEX -> OracleSideTable.dropping("v_object")
            else -> listOf("'DROP INDEX IF EXISTS ' || v_object")
        }
        val labelled = if (item.kind != SchemaItemKind.RANGE_INDEX) "" else """
                IF v_labelled IS NOT NULL THEN
                    EXECUTE IMMEDIATE 'DROP INDEX IF EXISTS ' || v_labelled;
                END IF;"""
        val record = recordOf(item)
        // Dropped first and forgotten last: a drop commits what came before it, and one that fails
        // (another session writing the table) then leaves the record naming what is still there,
        // for the next drop to find. A record naming what is gone is listed as not there.
        return listOf(DdlStatement.Text(statement(
            """
            DECLARE
                v_object VARCHAR2(128);
                v_labelled VARCHAR2(128);
                v_others NUMBER;
            BEGIN
                SELECT MAX(object_name), MAX(JSON_VALUE(details, '$.labelled')) INTO v_object, v_labelled
                FROM $catalog WHERE $record;
                SELECT COUNT(*) INTO v_others FROM $catalog WHERE object_name = v_object AND NOT ($record);$labelled
                IF v_object IS NOT NULL AND v_others = 0 THEN
                    ${drops.joinToString("\n                    ") { "EXECUTE IMMEDIATE $it;" }}
                END IF;
                DELETE FROM $catalog WHERE $record;
                COMMIT;
            END;
            """
        )))
    }

    override fun createIndex(spec: IndexSpec, existing: CatalogItem?): List<DdlStatement> = when (spec) {
        is RangeIndexSpec -> statements(*rangeIndex(spec).toTypedArray())
        is VectorIndexSpec -> vectorIndex(spec)
        is FullTextIndexSpec -> statements(textIndex(spec))
        is TextIndexSpec -> throw unsupportedTextIndex(spec, engine)
    }

    /** The index over the property of every node, whatever the label: what a save and a load by id find a node by. */
    private fun sharedIndex(spec: RangeIndexSpec): Pair<String, String> {
        val name = objectName(SHARED, spec.properties)
        val expressions = spec.keys.joinToString(", ") { "${OracleSql.indexClass("props", it)}, ${OracleSql.indexKey("props", it)}" }
        return name to busyWaiting("CREATE INDEX IF NOT EXISTS $name ON $nodes ($expressions)")
    }

    /**
     * A range index: the index over the property of every node, and the labelled index. It is
     * recorded with the name of its labelled index among its details, and a record already there
     * takes them: one made before there were labelled indexes is listed as incomplete (see
     * [listed]), so `ensure` makes it again, and its labelled index with it.
     */
    private fun rangeIndex(spec: RangeIndexSpec): List<String> {
        val (name, shared) = sharedIndex(spec)
        val labelled = objectName(LABELLED, listOf(spec.label) + spec.properties)
        val scoped = spec.keys.flatMap { OracleSql.labelledIndex("n", spec.label, it) }.joinToString(", ")
        // Not where a uniqueness constraint's index over the one property stands for it, as when the
        // range index is made again under a constraint: the record then names an index that is not
        // there, and the unique index is read through.
        val standing = "SELECT COUNT(*) INTO v_unique FROM $catalog u WHERE u.label = ${literal(spec.label)} AND u.properties = ${literal(json(spec.properties))} AND $uniqueThere"
        return listOf(
            shared,
            """
            DECLARE
                v_unique NUMBER;
                $EXISTS
                $BUSY
            BEGIN
                $standing;
                IF v_unique = 0 THEN
                    ${waitingOutBusy("CREATE INDEX IF NOT EXISTS $labelled ON $nodes n ($scoped)", 20)}
                END IF;
            END;
            """,
            recorded(spec, name, "{\"labelled\": \"$labelled\"}", updated = true),
        )
    }

    override fun dropIndex(item: CatalogItem): List<DdlStatement> = forgotten(item)

    /**
     * A vector index. Oracle indexes a `VECTOR` column, and an embedding is a list among a node's
     * JSON properties, so the embeddings of the label's nodes are kept beside them in a table of
     * their own (see [OracleSideTable]). The index is over that table, by neighbour partitions, which
     * needs no memory set aside in the database as a neighbour graph does. The table is filled before
     * the index is made, so the partitions are trained on the embeddings there are.
     */
    private fun vectorIndex(spec: VectorIndexSpec): List<DdlStatement> {
        val property = spec.keys.single()
        val side = OracleSideTable(
            name = vectors(spec.label, spec.properties.single()),
            nodes = nodes,
            column = "embedding",
            columnType = "VECTOR(${spec.dimensions}, FLOAT32)",
            payload = { row -> OracleSql.embedding(row, property) },
            indexed = { row -> "${OracleSql.carriesLabel(row, spec.label)} AND JSON_EXISTS($row.props, ${path(property)})" },
            backfillFilter = { row -> OracleSql.holdsEmbedding(row, property, spec.dimensions) },
            tolerant = true,
        )
        return statements(
            side.create,
            side.triggerStatement,
            side.backfill,
            "CREATE VECTOR INDEX IF NOT EXISTS ${side.index} ON ${side.name} (embedding) ORGANIZATION NEIGHBOR PARTITIONS DISTANCE ${OracleSql.metric(spec.similarity)}",
            recorded(spec, side.name, vectorDetails(spec)),
        )
    }

    /**
     * A full-text index, where the database has Oracle Text. The text of the label's nodes is kept
     * beside them in a table of its own (see [OracleSideTable]), as a vector index keeps embeddings,
     * and the Oracle Text index is over that table.
     *
     * It is not over the node table, though it could be. An Oracle Text index holds what has been
     * committed, and once one is there Oracle answers ordinary tests of a JSON property from it: a save
     * then cannot see a node it made a moment before in the same statement, and makes it again.
     *
     * A database without Oracle Text cannot make the index. Nothing is made there, the index is
     * recorded, and a search reads the text of every node of the label.
     *
     * Table, index, trigger and backfill are each made when they are not there, so a run that stopped
     * short, or that another process ran at the same moment, is finished rather than repeated.
     */
    private fun textIndex(spec: FullTextIndexSpec): String {
        val side = OracleSideTable(
            name = texts(spec.label, spec.properties),
            nodes = nodes,
            column = "doc",
            columnType = "JSON",
            payload = { row ->
                // Under each property's own name, as a search through the index reads it; of the key it is held at.
                spec.properties.zip(spec.keys).joinToString(", ", "JSON_OBJECT(", " ABSENT ON NULL RETURNING JSON)") { (property, key) ->
                    "${literal(property)} VALUE ${OracleSql.searchedText(row, key)}"
                }
            },
            indexed = { row -> OracleSql.carriesLabel(row, spec.label) },
        )
        val details = analyzerDetails(spec.analyzer)
        return """
            DECLARE
                v_indexed NUMBER;
                e_no_text EXCEPTION;
                e_exists EXCEPTION;
                PRAGMA EXCEPTION_INIT(e_no_text, -29833);
                PRAGMA EXCEPTION_INIT(e_exists, -955);
            BEGIN
                EXECUTE IMMEDIATE ${literal(side.create)};
                SELECT COUNT(*) INTO v_indexed FROM user_indexes WHERE index_name = '${side.index.uppercase()}';
                IF v_indexed = 0 THEN
                    BEGIN
                        EXECUTE IMMEDIATE ${literal("CREATE SEARCH INDEX ${side.index} ON ${side.name} (doc) FOR JSON PARAMETERS ('SYNC (ON COMMIT)')")};
                    EXCEPTION
                        WHEN e_exists THEN
                            NULL; -- made by another process at this moment
                        WHEN e_no_text THEN
                            EXECUTE IMMEDIATE 'DROP TABLE ${side.name} PURGE';
                            ${recorded(spec, null, details).trimIndent().replace("\n", "\n                            ")}
                            RETURN;
                    END;
                END IF;
                EXECUTE IMMEDIATE ${literal(side.triggerDdl)};
                EXECUTE IMMEDIATE ${literal(side.backfill)};
                ${recorded(spec, side.name, details).trimIndent().replace("\n", "\n                ")}
            END;
        """
    }

    /**
     * A uniqueness constraint, with the range index a node is found by. Over several properties the
     * range index is made whole. Over one, the unique index stands for its labelled index: the range
     * index is recorded as served by it, and a labelled index made for it before is dropped, once the
     * unique index is there. Data the constraint refuses stops the statements at the unique index,
     * and the range index is left incomplete, to be made whole by an `ensure` of it.
     */
    override fun createConstraint(spec: ConstraintSpec): List<DdlStatement> = when (spec) {
        is UniquenessConstraintSpec -> {
            val name = objectName(UNIQUE, listOf(spec.label) + spec.properties)
            // A node that lacks any of the properties is not constrained, as on the graph engines.
            val expressions = spec.keys.flatMap { OracleSql.uniqueIndex("n", spec.label, spec.keys, it) }.joinToString(", ")
            val unique = busyWaiting("CREATE UNIQUE INDEX IF NOT EXISTS $name ON $nodes n ($expressions)")
            val range = RangeIndexSpec(spec.label, spec.properties)
            if (spec.properties.size > 1) {
                statements(*rangeIndex(range).toTypedArray(), unique, recorded(spec, name))
            } else {
                val (shared, index) = sharedIndex(range)
                val served = "{\"unique\": \"$name\"}"
                statements(index, recorded(range, shared, served), unique, servedBy(range, served), recorded(spec, name))
            }
        }
    }

    /**
     * Records the range index [spec] as served by a uniqueness constraint's index, [details], keeping
     * its aliases, and drops the labelled index it was made with, when it was. The index is dropped first: a drop that
     * fails (another session writing the table) leaves the record naming it, for the next
     * constraint made to find and drop.
     */
    private fun servedBy(spec: RangeIndexSpec, details: String): String {
        val record = recordOf(CatalogItem(spec.kind, spec.label, spec.properties))
        return """
            DECLARE
                v_labelled VARCHAR2(128);
            BEGIN
                SELECT MAX(JSON_VALUE(details, '$.labelled')) INTO v_labelled FROM $catalog WHERE $record;
                IF v_labelled IS NOT NULL THEN
                    EXECUTE IMMEDIATE 'DROP INDEX IF EXISTS ' || v_labelled;
                END IF;
                UPDATE $catalog SET details = JSON_MERGEPATCH(details, ${literal(rangeDetails(formed(details)))}) WHERE $record;
                COMMIT;
            END;
        """
    }

    override fun dropConstraint(item: CatalogItem): List<DdlStatement> = forgotten(item)

    /** Whether the labelled index the record `c` names is there. */
    private val labelledThere = "EXISTS (SELECT 1 FROM user_indexes l WHERE l.index_name = UPPER(JSON_VALUE(c.details, '$.labelled')))"

    /** Whether the record `u` is of a uniqueness constraint over one property whose index is there: an index that stands for a labelled one. */
    private val uniqueThere = "u.kind = ${literal(SchemaItemKind.UNIQUENESS_CONSTRAINT.name)} AND JSON_VALUE(u.properties, '$.size()' RETURNING NUMBER) = 1 " +
        "AND EXISTS (SELECT 1 FROM user_indexes q WHERE q.index_name = UPPER(u.object_name))"

    /**
     * What is recorded of [kinds], and still there: an index or a table beside the nodes dropped by
     * hand is not listed, so that it is made again when asked for. A range index with neither its
     * labelled index nor a uniqueness constraint's to stand for it, dropped by hand or made before
     * there were any, is listed as it is, [INCOMPLETE], so that `ensure` makes the rest.
     *
     * After the records, where [objects] names any, a row for each of `user_objects` under a name Drivine gives an object
     * of [objects], recorded or not: its `type`; of an index its `table`, its `uniqueness`, its `indexType`, and its
     * `columns` in order, each the text Oracle keeps of the hidden column it made for an expression
     * (`user_tab_cols.data_default_vc`, null where it keeps none), or a plain column's name; of a table its `tableColumns`,
     * each its name and type; and of a trigger its `table` and its `source`, line by line (`user_source`). `store.schema`
     * reads them to tell another item under the name, and the form of the item's ([org.drivine.cypher.store.schema.ItemForm]).
     */
    private fun listed(kinds: String, objects: List<String>): String {
        val record = """
            JSON_OBJECT('kind' VALUE kind, 'label' VALUE label, 'properties' VALUE JSON(properties), 'name' VALUE name, 'details' VALUE details,
                               'status' VALUE CASE WHEN kind = ${literal(SchemaItemKind.RANGE_INDEX.name)} AND NOT $labelledThere
                                                    AND NOT EXISTS (SELECT 1 FROM $catalog u WHERE u.label = c.label AND u.properties = c.properties AND $uniqueThere)
                                              THEN '$INCOMPLETE' END
                               RETURNING JSON)
        """.trimIndent()
        val recorded = """
            FROM $catalog c
            WHERE kind IN ($kinds)
              AND (object_name IS NULL
                   OR EXISTS (SELECT 1 FROM user_indexes i WHERE i.index_name = UPPER(c.object_name))
                   OR EXISTS (SELECT 1 FROM user_tables t WHERE t.table_name = UPPER(c.object_name)))
        """.trimIndent()
        if (objects.isEmpty()) return "SELECT $record\n$recorded\nORDER BY label, properties"
        return """
            SELECT listed FROM (
                SELECT 0 AS part, label, properties, ${record.replace("\n", "\n                ")} AS listed
                ${recorded.replace("\n", "\n                ")}
                UNION ALL
                SELECT 1, o.object_name, NULL,
                       JSON_OBJECT('object' VALUE o.object_name, 'type' VALUE o.object_type, 'table' VALUE NVL(i.table_name, g.table_name),
                                   'uniqueness' VALUE i.uniqueness, 'indexType' VALUE i.index_type,
                                   'columns' VALUE (SELECT JSON_ARRAYAGG(CASE WHEN v.column_name IS NULL THEN ic.column_name ELSE v.data_default_vc END ORDER BY ic.column_position NULL ON NULL RETURNING JSON)
                                                    FROM user_ind_columns ic
                                                    LEFT JOIN user_tab_cols v ON v.table_name = ic.table_name AND v.column_name = ic.column_name AND v.virtual_column = 'YES'
                                                    WHERE ic.index_name = i.index_name),
                                   'tableColumns' VALUE (SELECT JSON_ARRAYAGG(tc.column_name || ' ' || tc.data_type ORDER BY tc.column_id RETURNING JSON)
                                                         FROM user_tab_columns tc WHERE o.object_type = 'TABLE' AND tc.table_name = o.object_name),
                                   'source' VALUE (SELECT JSON_ARRAYAGG(s.text ORDER BY s.line RETURNING JSON)
                                                   FROM user_source s WHERE o.object_type = 'TRIGGER' AND s.type = 'TRIGGER' AND s.name = o.object_name)
                                   RETURNING JSON)
                FROM user_objects o
                LEFT JOIN user_indexes i ON i.index_name = o.object_name AND o.object_type = 'INDEX'
                LEFT JOIN user_triggers g ON g.trigger_name = o.object_name AND o.object_type = 'TRIGGER'
                WHERE ${namedAs("o.object_name", objects, upper = true)}
            )
            ORDER BY part, label, properties
        """.trimIndent()
    }

    /**
     * The indexes over the nodes of one label a filter can be read through, as rows of `kind`,
     * `label` and `properties`: each range index whose labelled index is there, and each uniqueness
     * constraint over one property whose index is there: what a library reads to tell the compiler `GraphOptions.indexedProperties`.
     */
    fun labelledIndexesQuery(): String = """
        SELECT JSON_OBJECT('kind' VALUE kind, 'label' VALUE label, 'properties' VALUE JSON(properties) RETURNING JSON)
        FROM $catalog u
        WHERE ($uniqueThere)
           OR (kind = ${literal(SchemaItemKind.RANGE_INDEX.name)} AND ${labelledThere.replace("c.details", "u.details")})
    """.trimIndent()

    override fun listIndexesQuery(kind: SchemaItemKind): String =
        listed(
            literal(kind.name),
            when (kind) {
                SchemaItemKind.RANGE_INDEX -> listOf(SHARED, LABELLED)
                SchemaItemKind.VECTOR_INDEX -> listOf(VECTORS)
                SchemaItemKind.FULLTEXT_INDEX -> listOf(TEXTS)
                else -> emptyList()
            },
        )

    override fun listConstraintsQuery(): String = listed(literal(SchemaItemKind.UNIQUENESS_CONSTRAINT.name), listOf(UNIQUE, SHARED))
    companion object {
        /** The store's name, for what is said of a graph name it cannot take. */
        const val ENGINE = "Oracle"

        /** Oracle names are at most 128 characters. */
        const val MAX_OBJECT_NAME = 128

        /**
         * How many times [ensuring], and an index over the node table ([waitingOutBusy]), tries a statement that another
         * session's lock turns away: thirty seconds' worth.
         */
        const val BUSY_ATTEMPTS = 300

        /** An exception [waitingOutBusy] catches: another session made the index in the meantime (`ORA-00955`). */
        private const val EXISTS = "e_exists EXCEPTION; PRAGMA EXCEPTION_INIT(e_exists, -955);"

        /** An exception [waitingOutBusy] catches: another session's lock turned the DDL away (`ORA-00054`). */
        private const val BUSY = "e_busy EXCEPTION; PRAGMA EXCEPTION_INIT(e_busy, -54);"

        /** The keys of a range index's details: the name of its labelled index, or of the uniqueness constraint's index that stands for it. */
        private val RANGE_DETAILS = listOf("labelled", "unique")

        /**
         * What the names of a range index's index over every node, its labelled index, and a uniqueness constraint's index
         * say they are.
         */
        const val SHARED = "ix"
        const val LABELLED = "lx"
        const val UNIQUE = "uq"

        /** What the names of a vector index's and a full-text index's tables beside the nodes say they are, and their triggers' and indexes' after them. */
        const val VECTORS = "v"
        const val TEXTS = "t"

        /** The status a range index is listed with when it lacks what this version makes for it. */
        const val INCOMPLETE = "INCOMPLETE"

        /** Why [name] cannot name a graph here; null where it can (see [SqlStoreDdl.refusal]). */
        fun refusal(name: String): String? = SqlStoreDdl.refusal(name, ENGINE, MAX_OBJECT_NAME)
    }
}
