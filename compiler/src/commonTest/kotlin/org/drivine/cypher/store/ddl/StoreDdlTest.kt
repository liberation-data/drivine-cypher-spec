package org.drivine.cypher.store.ddl

import org.drivine.cypher.CompilerException
import org.drivine.cypher.api.Compiled
import org.drivine.cypher.api.CypherCompiler
import org.drivine.cypher.api.GraphOptions
import org.drivine.cypher.api.Refusal
import org.drivine.cypher.api.Store
import org.drivine.cypher.json.CypherJson
import org.drivine.cypher.json.JsonNode
import org.drivine.cypher.json.ObjectNode
import org.drivine.cypher.json.Sha256
import org.drivine.cypher.number.oracleFile
import org.drivine.cypher.number.target
import org.drivine.schema.ConstraintSpec
import org.drivine.schema.FalkorDbVectorOptions
import org.drivine.schema.FullTextIndexSpec
import org.drivine.schema.Neo4jVectorOptions
import org.drivine.schema.RangeIndexSpec
import org.drivine.schema.SchemaItemKind
import org.drivine.schema.SchemaItemSpec
import org.drivine.schema.SimilarityFunction
import org.drivine.schema.TextIndexSpec
import org.drivine.schema.UniquenessConstraintSpec
import org.drivine.schema.VectorIndexSpec
import org.drivine.schema.made
import org.drivine.cypher.store.PostgresSql
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.test.fail

/**
 * The spec's `store.yaml`, the store layout's DDL, against the module's generators (`store.ddl`) and the compiler, on each
 * target: the JVM writes it as JSON with its answers (`./gradlew jvmOracle`, `store.json`), as the other targets read no YAML.
 *
 * - each generator gives the statements `store.yaml` holds, as drivine4j gave them: the tables and the probe, each case's
 *   index or constraint made and dropped, the catalog's statements, and the graph-name refusals;
 * - [SqlStoreDdl.objectName] gives each name `store.yaml` holds: Java's `String.hashCode`, written out, on every target;
 * - each case's read, compiled on each SQL store, tests the expressions the case's index is made over where a planner
 *   reads an index by them: a conjunct of its `WHERE`, or the left side of a conjunct's `=`.
 */
class StoreDdlTest {

    private val store: JsonNode = CypherJson.readTree(oracleFile("store.json"))

    private val graph = store["graph"]!!.textValue()!!

    private val postgres = PostgresDdl(graph)
    private val oracle = OracleDdl(graph)
    private val sqlite = SqliteDdl(graph)

    private val stores: Map<String, StoreDdl> = linkedMapOf(
        "postgres" to postgres,
        "oracle" to oracle,
        "sqlite" to sqlite,
        "neo4j" to Neo4jDdl,
        "memgraph" to MemgraphDdl(),
        "falkordb" to FalkorDbDdl,
    )

    /** What a SQL store's create begins with, which `store.yaml` leaves out of each case: the statements that make the store. */
    private val tables: Map<String, List<String>> = mapOf(
        "postgres" to listOf(postgres.ensuring),
        "oracle" to listOf(oracle.ensuring),
        "sqlite" to sqlite.ddl,
    )

    @Test
    fun tablesAndProbeAreDrivine4js() {
        val tables = store["tables"]!!
        assertEquals(texts(tables["postgres"]!!["ddl"]!!), postgres.ddl, "postgres ddl")
        assertEquals(tables["postgres"]!!["ensuring"]!!.textValue(), postgres.ensuring, "postgres ensuring")
        assertEquals(texts(tables["oracle"]!!["ddl"]!!), oracle.ddl, "oracle ddl")
        assertEquals(texts(tables["oracle"]!!["functions"]!!), oracle.functions, "oracle functions")
        assertEquals(tables["oracle"]!!["ensuring"]!!.textValue(), oracle.ensuring, "oracle ensuring")
        assertEquals(texts(tables["sqlite"]!!["ddl"]!!), sqlite.ddl, "sqlite ddl")
        assertEquals(tables["sqlite"]!!["made"]!!.textValue(), sqlite.made, "sqlite made")
        assertEquals(tables["sqlite"]!!["file"]!!.textValue(), SqliteDdl.FILE, "sqlite file")
    }

    @Test
    fun eachIndexAndConstraintIsMadeAndDroppedAsDrivine4jDid() {
        var checked = 0
        for (case in store["cases"]!!) {
            val name = case["name"]!!.textValue()!!
            val made = spec(case["spec"]!!).made()
            val existing = case["existing"]?.let { CatalogItem(made.kind, made.label, texts(it)) }
            val named = CatalogItem(made.kind, made.label, made.properties, made.effectiveName)
            val unnamed = CatalogItem(made.kind, made.label, made.properties)
            for ((id, ddl) in stores) {
                val expected = case["stores"]!![id] ?: fail("$name: no statements for $id")
                val prefix = tables[id].orEmpty().map { mapOf("text" to it) }
                val create = expected["create"]!!
                assertEquals(
                    if (create.isArray) prefix + statements(create) else refusal(create),
                    attempt { ddl.create(made, existing) },
                    "$name: create on $id",
                )
                assertEquals(expectedOf(expected["drop"]!!), attempt { ddl.drop(named) }, "$name: drop on $id")
                assertEquals(expectedOf(expected["dropUnnamed"] ?: expected["drop"]!!), attempt { ddl.drop(unnamed) }, "$name: drop unnamed on $id")
                checked++
            }
            // FalkorDB's refusal of a constraint GRAPH.CONSTRAINT can't make, which a library asks before its create.
            if (made is ConstraintSpec) {
                val refused = case["stores"]!!["falkordb"]!!["constrainable"]?.let(::refusal) ?: emptyList<Any>()
                assertEquals(refused, attempt { FalkorDbDdl.requireConstrainable(made); emptyList() }, "$name: constrainable on falkordb")
            }
        }
        assertTrue(checked > 0, "store.yaml has no cases")
    }

    /**
     * Each SQL store's create is pinned to the form its record says ([SqlStoreDdl.CURRENT_FORM]), with a digest of its
     * statements: SHA-256 of their UTF-8, each after the first behind U+0000. A change to what a create makes changes its
     * digest, and is a new form: [SqlStoreDdl.CURRENT_FORM] goes up, and each case's `form` with it, so a store made in
     * the form before is told apart from one made now (`store.schema`'s `ItemForm`).
     */
    @Test
    fun eachSqlStoresCreateIsPinnedToItsFormByItsDigest() {
        var checked = 0
        for (case in store["cases"]!!) {
            val name = case["name"]!!.textValue()!!
            val made = spec(case["spec"]!!).made()
            val existing = case["existing"]?.let { CatalogItem(made.kind, made.label, texts(it)) }
            for (id in listOf("postgres", "oracle", "sqlite")) {
                val expected = case["stores"]!![id]!!
                if (!expected["create"]!!.isArray) {
                    assertTrue(expected["form"] == null && expected["digest"] == null, "$name on $id: a refusal makes no form")
                    continue
                }
                assertEquals(SqlStoreDdl.CURRENT_FORM.toLong(), expected["form"]?.longValue(), "$name on $id: the form it is pinned to")
                val statements = stores.getValue(id).create(made, existing).drop(tables.getValue(id).size).map { (it as DdlStatement.Text).statement }
                assertEquals(
                    expected["digest"]?.textValue(), digest(statements),
                    "$name on $id: its create is not the form ${SqlStoreDdl.CURRENT_FORM} it is pinned to. A change to what a create makes is a new form",
                )
                checked++
            }
        }
        assertTrue(checked > 0, "store.yaml pins no form")
    }

    /**
     * drivine-cypher-spec#135 §4: each SQL store's safe recreate of a uniqueness constraint is pinned, with the form its
     * record then says and a digest of its statements (`leftover`, `build`, `swap`, `finish`, then `remade`), as a create is. The
     * index it leaves is the one the create makes, built under another name first and put in its place: the build is the
     * create's unique index under [SqlStoreDdl.temporaryName], without its `IF NOT EXISTS`, and SQLite's swap makes the
     * create's index under its own name, so the form stays the create's.
     */
    @Test
    fun eachSqlStoresUniqueRecreateIsPinnedAndLeavesTheIndexItsCreateMakes() {
        var checked = 0
        for (case in store["cases"]!!) {
            val name = case["name"]!!.textValue()!!
            val made = spec(case["spec"]!!).made()
            for ((id, ddl) in stores) {
                val expected = case["stores"]!![id]!!["recreate"]
                if (made !is ConstraintSpec || ddl !is SqlStoreDdl) {
                    assertEquals(null, expected, "$name on $id: no recreate of its own")
                    continue
                }
                expected ?: fail("$name on $id: its recreate isn't pinned")
                val recreation = ddl.recreation(made)
                fun texts(statements: List<DdlStatement>) = statements.map { (it as DdlStatement.Text).statement }
                assertEquals(texts(expected["leftover"]!!), texts(recreation.leftover), "$name on $id: leftover")
                assertEquals(texts(expected["build"]!!), texts(recreation.build), "$name on $id: build")
                assertEquals(texts(expected["swap"]!!), texts(recreation.swap), "$name on $id: swap")
                assertEquals(texts(expected["finish"]!!), texts(recreation.finish), "$name on $id: finish")
                assertEquals(expected["remade"]!!.textValue(), recreation.remade, "$name on $id: remade")
                assertEquals(SqlStoreDdl.CURRENT_FORM.toLong(), expected["form"]?.longValue(), "$name on $id: the form its record says")
                val statements = texts(recreation.leftover + recreation.build + recreation.swap + recreation.finish) + recreation.remade
                assertEquals(expected["digest"]?.textValue(), digest(statements), "$name on $id: its recreate is not the form ${SqlStoreDdl.CURRENT_FORM} it is pinned to")

                // What it leaves is what the create makes.
                val create = texts(ddl.createConstraint(made))
                val unique = create.single { "CREATE UNIQUE INDEX IF NOT EXISTS ${recreation.name} " in it }
                assertEquals(unique.replace("IF NOT EXISTS ${recreation.name} ", "${recreation.temporary} "), texts(recreation.build).single(), "$name on $id: the build is the create's index")
                assertEquals(recreation.name + SqlStoreDdl.TEMPORARY, recreation.temporary, "$name on $id: the name built under first")
                if (ddl is SqliteDdl) {
                    assertEquals(unique.replace("IF NOT EXISTS ", ""), texts(recreation.swap)[1], "$name on $id: the index made again under its name")
                    assertEquals(texts(recreation.swap).drop(1), texts(recreation.finish), "$name on $id: the swap's end, which finishes one cut off")
                } else {
                    // Oracle's swap ends with it; Postgres's block does.
                    val rename = texts(recreation.finish).single()
                    assertTrue(texts(recreation.swap).last().let { it == rename || "    $rename;\nEND" in it }, "$name on $id: the rename, which finishes one cut off")
                }
                // The record is left as one made anew: under the item's name, as the index under its own.
                assertTrue("name = ${ddl.recreation(made).name.let { "'${made.effectiveName.replace("'", "''")}'" }}" in recreation.remade, "$name on $id: remade names the record as the item")
                checked++
            }
        }
        assertEquals(18, checked, "six constraints on three SQL stores")
    }

    /**
     * A recreate builds its index over the keys the create indexes, so over a property whose name begins with `$`, held as
     * `$$` ([SqlStoreDdl.storedKeys]), it builds over `$$x`, as the create does, not over `$x`, which no node holds: an
     * index over that, swapped in, would hold nothing unique (drivine-cypher-spec#161).
     */
    @Test
    fun aUniqueRecreateBuildsOverTheStoredKeysOfAPropertyThatBeginsWithADollar() {
        val made = UniquenessConstraintSpec("Person", listOf("${'$'}x", "y")).made()
        for (ddl in listOf(postgres, oracle, sqlite)) {
            val recreation = ddl.recreation(made)
            val create = ddl.createConstraint(made).map { (it as DdlStatement.Text).statement }
            val unique = create.single { "CREATE UNIQUE INDEX IF NOT EXISTS ${recreation.name} " in it }
            assertEquals(unique.replace("IF NOT EXISTS ${recreation.name} ", "${recreation.temporary} "), (recreation.build.single() as DdlStatement.Text).statement, ddl.engine)
        }
    }

    /**
     * The name a unique index is built under first fits within the store's longest name, and is never a name Drivine
     * gives: [SqlStoreDdl.objectName] leaves two characters for it, and a graph's name is at most the store's longest less
     * 16 ([SqlStoreDdl.refusal]), so even its `uq2` names leave them.
     */
    @Test
    fun theNameAUniqueIndexIsBuiltUnderFirstFitsTheStoresNames() {
        val makers: List<Triple<String, Int, (String) -> SqlStoreDdl>> = listOf(
            Triple("Postgres", PostgresDdl.MAX_OBJECT_NAME) { g -> PostgresDdl(g) },
            Triple("Oracle", OracleDdl.MAX_OBJECT_NAME) { g -> OracleDdl(g) },
            Triple("SQLite", SqliteDdl.MAX_OBJECT_NAME) { g -> SqliteDdl(g) },
        )
        for ((engine, max, make) in makers) {
            // The longest name a graph may have, and one shorter.
            val longest = max - ("_uq2__".length + 8 + "_t".length)
            for (graph in listOf("g" + "x".repeat(longest - 1), "g" + "x".repeat(longest - 2), "drivine")) {
                val ddl = make(graph)
                for (parts in listOf(listOf("Person", "email"), listOf("Membership", "tenantId", "userId"), listOf("P".repeat(80), "q"))) {
                    val name = ddl.objectName("uq", parts)
                    val temporary = ddl.temporaryName(name)
                    assertTrue(temporary.length <= max, "$engine, $graph, $parts: $temporary is at most $max long")
                    assertTrue(temporary.endsWith("_r") && !temporary.equals(name, ignoreCase = true), "$engine, $graph, $parts: $temporary is a name of its own")
                    val expected = if (name.length + 2 <= max) name + "_r" else name.dropLast(1) + "_r"
                    assertEquals(expected, temporary, "$engine, $graph, $parts")
                }
            }
            assertEquals("drivine_uq2_Personemail_5482a751_r", make("drivine").temporaryName("drivine_uq2_Personemail_5482a751"), engine)
        }
        // At the longest a graph's name may be, a `uq2` name with a hash of eight digits is two short of the store's longest,
        // so `_r` fits whole.
        val longest = PostgresDdl("g" + "x".repeat(PostgresDdl.MAX_OBJECT_NAME - 17))
        val full = longest.objectName("uq", listOf("Person", "email"))
        assertEquals(PostgresDdl.MAX_OBJECT_NAME - 2, full.length, full)
        assertEquals(full + "_r", longest.temporaryName(full))
    }

    private fun digest(statements: List<String>): String =
        Sha256.digest(statements.joinToString("\u0000").encodeToByteArray()).joinToString("") { (it.toInt() and 0xff).toString(16).padStart(2, '0') }

    /**
     * liberation-data/drivine4j#517: Oracle's DDL doesn't wait for a lock, so a manager making the store's key constraint
     * while another builds it, or writes its stamp, was turned away at once (`ORA-00054`), and could go on without the
     * constraint. Each index over the node table, the shared, the labelled and the unique, is run again a tenth of a second
     * later, up to [OracleDdl.BUSY_ATTEMPTS] tries, and nothing else waits. One that another session made after this one's
     * `IF NOT EXISTS` found none (`ORA-00955`) is taken as made, as `ensuring` takes it.
     */
    @Test
    fun oracleRunsEachIndexOverTheNodeTableAgainWhileAnotherSessionsLockTurnsItAwayUpToABound() {
        // Each statement after the one that makes the store.
        fun made(statements: List<DdlStatement>) = statements.drop(1).map { (it as DdlStatement.Text).statement }
        val key = made(oracle.createConstraint(UniquenessConstraintSpec("DrivineStore", listOf("key"))))
        val pair = made(oracle.createConstraint(UniquenessConstraintSpec("Person", listOf("first", "last"))))
        val range = made(oracle.createIndex(RangeIndexSpec("Person", listOf("name")), null))
        val labelled = "CREATE INDEX IF NOT EXISTS ${oracle.objectName("lx", listOf("Person", "name"))} "
        val blocks = listOf(
            "the key's unique index" to key.single { "CREATE UNIQUE INDEX" in it },
            "the key's shared index" to key.single { "CREATE INDEX" in it },
            "a composite constraint's unique index" to pair.single { "CREATE UNIQUE INDEX" in it },
            "a composite constraint's labelled index" to pair.single { "CREATE INDEX IF NOT EXISTS ${oracle.objectName("lx", listOf("Person", "first", "last"))} " in it },
            "a range index's shared index" to range.single { "CREATE INDEX IF NOT EXISTS ${oracle.objectName("ix", listOf("name"))} " in it },
            "a range index's labelled index" to range.single { labelled in it },
        )
        val attempts = OracleDdl.BUSY_ATTEMPTS
        for ((what, block) in blocks) {
            assertTrue(block.startsWith("DECLARE\n") && block.endsWith("\nEND;"), "$what is a block of its own:\n$block")
            assertTrue("e_busy EXCEPTION; PRAGMA EXCEPTION_INIT(e_busy, -54);" in block, "$what catches ORA-00054:\n$block")
            assertTrue("e_exists EXCEPTION; PRAGMA EXCEPTION_INIT(e_exists, -955);" in block, "$what catches ORA-00955:\n$block")
            assertTrue(
                Regex("""EXCEPTION\s+WHEN e_exists THEN EXIT;\s+WHEN e_busy THEN""").containsMatchIn(block),
                "$what takes an index another session made in the meantime as made, before waiting out a lock:\n$block",
            )
            assertTrue("FOR attempt IN 1 .. $attempts LOOP" in block, "$what is tried a bounded number of times:\n$block")
            assertTrue(
                Regex("""WHEN e_busy THEN\s+IF attempt = $attempts THEN RAISE; END IF;\s+DBMS_SESSION\.SLEEP\(0\.1\);""").containsMatchIn(block),
                "$what is run again a tenth of a second later, and raised on its last try:\n$block",
            )
            assertTrue(Regex("""EXECUTE IMMEDIATE 'CREATE[^;]*;\s+EXIT;""").containsMatchIn(block), "$what stops trying once it runs:\n$block")
            assertEquals(1, Regex("EXECUTE IMMEDIATE").findAll(block).count(), "$what runs only the index:\n$block")
            assertTrue(block.lines().none { it.isNotEmpty() && it.isBlank() }, "$what has no line of spaces alone:\n$block")
        }
        // The labelled index is still made only where no uniqueness constraint's index stands for it.
        val ranged = range.single { labelled in it }
        assertTrue(ranged.indexOf("IF v_unique = 0 THEN") < ranged.indexOf("FOR attempt") && ranged.indexOf("END LOOP;") < ranged.lastIndexOf("END IF;"), ranged)
        // Nothing else waits: a record of what was made, or the drop of a labelled index the unique one serves.
        (key + pair + range).filter { "CREATE" !in it }.forEach { assertTrue("e_busy" !in it && "e_exists" !in it, it) }
    }

    @Test
    fun catalogIsListedAndAliasedAsDrivine4jDid() {
        val catalog = store["catalog"]!!
        val item = catalog["item"]!!.let { CatalogItem(SchemaItemKind.valueOf(it["kind"]!!.textValue()!!), it["label"]!!.textValue()!!, texts(it["properties"]!!), it["name"]?.textValue()) }
        val aliases = texts(catalog["aliases"]!!)
        for ((id, ddl) in stores) {
            val expected = catalog["stores"]!![id]!!
            for (kind in SchemaItemKind.entries) {
                assertEquals(expected["indexes"]!![kind.name]!!.textValue(), ddl.listIndexesQuery(kind), "$id: list of $kind")
            }
            assertEquals(expected["constraints"]!!.textValue(), ddl.listConstraintsQuery(), "$id: list of constraints")
            if (ddl is SqlStoreDdl) {
                assertEquals(expected["aliased"]!!.textValue(), ddl.aliased(item, aliases), "$id: aliased")
                assertEquals(expected["unaliased"]!!.textValue(), ddl.aliased(item, emptyList()), "$id: unaliased")
            }
        }
        assertEquals(catalog["stores"]!!["oracle"]!!["labelled"]!!.textValue(), oracle.labelledIndexesQuery(), "oracle: labelled indexes")
    }

    @Test
    fun objectsAreNamedByJavasHashOnEveryTarget() {
        val names = store["names"]!!
        for (entry in names) {
            val graph = entry["graph"]!!.textValue()!!
            val ddl = when (val id = entry["store"]!!.textValue()) {
                "postgres" -> PostgresDdl(graph)
                "oracle" -> OracleDdl(graph)
                "sqlite" -> SqliteDdl(graph)
                else -> fail("No SQL store $id")
            }
            val parts = texts(entry["parts"]!!)
            val name = ddl.objectName(entry["kind"]!!.textValue()!!, parts)
            assertEquals(entry["name"]!!.textValue(), name, "on $target: ${entry["store"]!!.textValue()} $graph $parts")
            // What is made beside it, a trigger's `_t`, an index's `_i`, a unique index built anew's `_r`, fits too.
            val longest = when (ddl) { is OracleDdl -> OracleDdl.MAX_OBJECT_NAME; is PostgresDdl -> PostgresDdl.MAX_OBJECT_NAME; else -> SqliteDdl.MAX_OBJECT_NAME }
            assertTrue(name.length + 2 <= longest, "${entry["store"]!!.textValue()} $graph $parts: $name and a suffix fit in $longest")
        }
        assertTrue(names.size() > 0)
    }

    @Test
    fun graphNamesAreRefusedAsDrivine4jRefusedThem() {
        for (entry in store["refusals"]!!) {
            val name = entry["graph"]!!.textValue()!!
            val refusal = when (val id = entry["store"]!!.textValue()) {
                "postgres" -> PostgresDdl.refusal(name)
                "oracle" -> OracleDdl.refusal(name)
                "sqlite" -> SqliteDdl.refusal(name)
                else -> fail("No SQL store $id")
            }
            assertEquals(entry["refusal"]?.takeUnless { it.isNull }?.textValue(), refusal, "${entry["store"]!!.textValue()}: $name")
        }
    }

    /**
     * Postgres's write function finds a node by its id where a range index or a uniqueness constraint's index finds it: its
     * lookup and its check of which nodes are there compare the property as the index is made over it, its JSON
     * ([PostgresSql.indexed]), with the id as the store layout writes it, the plan step's `id`. Compared as text, `->>`, no
     * index answered it, and a temporal id's tag never equalled its text (#245).
     */
    @Test
    fun postgresWriteFunctionFindsANodeByItsIdAsTheIndexIsMadeOverIt() {
        val ddl = PostgresDdl(graph).writes.ddl.joinToString("\n")
        val compared = Regex("""WHERE (\(n\.props -> %L\)) = (\$1|s\.op -> 'id')""").findAll(ddl).toList()
        assertEquals(2, compared.size, "the find's lookup and the check of which nodes are there:\n$ddl")
        for (match in compared) assertEquals(PostgresSql.indexed("n", "k"), match.groupValues[1].replace("%L", "'k'"), match.value)
        // Nor is the plan step's `k`, the id's text, read at all: the nodes a plan made together are told apart, and handed
        // to their steps, by the id's JSON too, so a temporal id's step takes the node made for it, as made.
        assertTrue("(n.props ->> %L)" !in ddl && "->> 'k'" !in ddl, "no id is compared as text:\n$ddl")
        assertTrue("RETURNING nid, (props -> %L)::text AS k" in ddl && "->> ((v_op -> 'id')::text)" in ddl, "the nodes made are keyed by the id's JSON:\n$ddl")
        assertTrue("p_id jsonb" in ddl && "DROP FUNCTION IF EXISTS ${graph}_find(jsonb, text, text)" in ddl, ddl)
        // A ref or patch step of a library that sends no id is refused, rather than finding no node and reading as absent.
        assertTrue("IF p_id IS NULL THEN\n        RAISE EXCEPTION" in ddl, "a step without an id is refused:\n$ddl")
    }

    @Test
    fun eachReadCompilesToSqlThatHoldsItsIndexsExpressions() {
        var checked = 0
        for (case in store["cases"]!!) for (read in listOfNotNull(case["read"]) + (case["reads"]?.toList() ?: emptyList())) {
            val name = case["name"]!!.textValue()!!
            val cypher = read["cypher"]!!.textValue()!!
            val parameters = (read["parameters"] as? ObjectNode)?.properties()?.associate { (k, v) -> k to v.textValue() }.orEmpty()
            val indexed = read["indexedProperties"]!!.map { it[0]!!.textValue()!! to it[1]!!.textValue()!! }.toSet()
            for (id in listOf("postgres", "oracle", "sqlite")) {
                val options = GraphOptions(graph, indexedProperties = if (id == "oracle") indexed else emptySet())
                val sql = when (val compiled = CypherCompiler.compile(Store.of(id)!!, options, cypher, parameters)) {
                    is Compiled -> compiled.sql
                    is Refusal -> fail("$name: $cypher is refused on $id: ${compiled.message}")
                }
                val alias = Regex("FROM ${graph}_node (\\w+)").find(sql)?.groupValues?.get(1) ?: fail("$name: no node table read on $id in\n$sql")
                val made = statements(case["stores"]!![id]!!["create"]!!).joinToString("\n") { it.values.single().toString() }
                for (expression in texts(read["holds"]!![id]!!)) {
                    assertTrue(expression in made || expression.replace("'", "''") in made, "$name: the index on $id is not made over\n$expression")
                    val qualified = qualified(expression, alias)
                    assertTrue(sargable(sql, qualified), "$name: $cypher on $id does not test its index's\n$qualified\nin\n$sql")
                    checked++
                }
            }
        }
        assertTrue(checked > 0, "store.yaml has no reads")
    }

    /**
     * Whether [sql] tests [expression] where a planner reads an index by it: as a conjunct of a `WHERE`, the test of a
     * label, or as the left side of a conjunct's `=`; and not only within a `CASE`, beside an `OR` or among the columns.
     */
    private fun sargable(sql: String, expression: String): Boolean {
        var at = sql.indexOf(expression)
        while (at >= 0) {
            val before = sql.substring(0, at).trimEnd(' ', '(')
            val after = sql.substring(at + expression.length)
            val conjunct = before.endsWith("WHERE") || before.endsWith(" AND")
            if (conjunct && listOf(" = ", "\n", " AND ", ")").any { after.startsWith(it) }) return true
            at = sql.indexOf(expression, at + 1)
        }
        return false
    }

    /**
     * [expression], over a table's own columns as a Postgres or SQLite index names them, or over Oracle's `n`, as a
     * statement reads them over [alias]: the columns `props`, `labels` and `stamp`, outside a literal, are [alias]'s.
     */
    private fun qualified(expression: String, alias: String): String = expression.split('\'').mapIndexed { i, part ->
        if (i % 2 == 1) part
        else Regex("\\b(n\\.)?(props|labels|stamp)\\b").replace(part) { m ->
            val before = part.getOrNull(m.range.first - 1)
            if (before != null && (before == '.' || before == '_' || before.isLetterOrDigit())) m.value else "$alias.${m.groupValues[2]}"
        }
    }.joinToString("'")

    /** The spec `store.yaml` writes, as `schema.yaml` writes one, with `ignoreCase` and each engine's vector options beside. */
    private fun spec(node: JsonNode): SchemaItemSpec {
        val label = node["label"]!!.textValue()!!
        val properties = texts(node["properties"]!!)
        val name = node["name"]?.textValue()
        val ignoreCase = node["ignoreCase"]?.booleanValue() ?: false
        val options = node["options"]
        fun option(key: String) = options?.get(key)?.takeUnless { it.isNull }
        return when (SchemaItemKind.valueOf(node["kind"]!!.textValue()!!)) {
            SchemaItemKind.RANGE_INDEX -> RangeIndexSpec(label, properties, name, ignoreCase)
            SchemaItemKind.UNIQUENESS_CONSTRAINT -> UniquenessConstraintSpec(label, properties, name, ignoreCase = ignoreCase)
            SchemaItemKind.FULLTEXT_INDEX -> FullTextIndexSpec(label, properties, name, option("analyzer")?.textValue())
            SchemaItemKind.TEXT_INDEX -> TextIndexSpec(label, properties.single(), name, ignoreCase)
            SchemaItemKind.VECTOR_INDEX -> VectorIndexSpec(
                label, properties.single(), option("dimensions")!!.longValue().toInt(),
                SimilarityFunction.valueOf(option("similarity")!!.textValue()!!), name,
                option("hnswM")?.longValue()?.toInt(), option("hnswEfConstruction")?.longValue()?.toInt(),
                listOfNotNull(
                    option("quantizationEnabled")?.let { Neo4jVectorOptions(quantizationEnabled = it.booleanValue()) },
                    option("efRuntime")?.let { FalkorDbVectorOptions(efRuntime = it.longValue().toInt()) },
                ),
            )
        }
    }

    private fun texts(node: JsonNode): List<String> = node.map { it.textValue()!! }

    /** Each statement as `{text}` or `{native}`, as [attempt] gives a generator's. */
    private fun statements(node: JsonNode): List<Map<String, Any>> = node.map {
        if (it.isTextual) mapOf("text" to it.textValue()!!) else mapOf("native" to texts(it["native"]!!))
    }

    private fun refusal(node: JsonNode): Map<String, Any> = mapOf("error" to node["error"]!!.textValue()!!)

    private fun expectedOf(node: JsonNode): Any = if (node.isArray) statements(node) else refusal(node)

    private fun attempt(block: () -> List<DdlStatement>): Any = try {
        block().map {
            when (it) {
                is DdlStatement.Text -> mapOf("text" to it.statement)
                is DdlStatement.Native -> mapOf("native" to it.args)
            }
        }
    } catch (e: CompilerException) {
        mapOf("error" to (e.message ?: ""))
    }
}
