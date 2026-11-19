package org.drivine.cypher.api

import org.drivine.cypher.json.CypherJson
import org.drivine.cypher.json.JsonNode
import org.drivine.cypher.number.target
import org.drivine.cypher.store.IdSql
import org.drivine.cypher.store.IndexKey
import org.drivine.cypher.store.OracleSql
import org.drivine.cypher.store.PostgresSql
import org.drivine.cypher.store.SqliteSql
import org.drivine.cypher.store.Stamps
import org.drivine.cypher.store.ddl.DdlStatement
import org.drivine.cypher.store.ddl.PostgresDdl
import org.drivine.cypher.store.ddl.SqliteDdl
import org.drivine.schema.RangeIndexSpec
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * The id lookups through the JSON entry ([IdSqlJson]), as TypeScript and Python ask for them: each [IdSql]'s own, on every
 * target, and each written over the expressions the store's index is made over ([IndexKey.column]), so the index answers it
 * (liberation-data/drivine-cypher-spec#107).
 */
class IdSqlJsonTest {

    private fun answer(request: String): JsonNode = CypherJson.readTree(CompileJson.compile(request))

    private fun sql(store: String, rest: String): String? = answer("{\"idSql\": {\"store\": \"$store\", $rest}}")["sql"]?.textValue()

    private val stores = mapOf("postgres" to Store.POSTGRES, "oracle" to Store.ORACLE, "sqlite" to Store.SQLITE)

    @Test
    fun eachHelperIsIdSqlsOwn() {
        for ((name, store) in stores) for (key in listOf("id", "\$\$id", Stamps.PROPERTY)) {
            val k = CypherJson.writeValueAsString(CypherJson.valueToTree(key))
            assertEquals(IdSql.keyText(store, "n", key), sql(name, "\"helper\": \"keyText\", \"alias\": \"n\", \"key\": $k"), "on $target, $name, $key")
            assertEquals(IdSql.property(store, "n", key), sql(name, "\"helper\": \"property\", \"alias\": \"n\", \"key\": $k"), "on $target, $name, $key")
            assertEquals(IdSql.hasProperty(store, "n", key), sql(name, "\"helper\": \"hasProperty\", \"alias\": \"n\", \"key\": $k"), "on $target, $name, $key")
            if (key == Stamps.PROPERTY) continue
            assertEquals(IdSql.hasId(store, "n", key, "JSON(:id)"), sql(name, "\"helper\": \"hasId\", \"alias\": \"n\", \"key\": $k, \"json\": \"JSON(:id)\""), "on $target, $name, $key")
            val nodes = if (store == Store.SQLITE) ", \"nodes\": \"g_node\", \"labels\": [\"Person\"]" else ""
            assertEquals(
                IdSql.hasIdIn(store, "n", key, ":ids", "g_node".takeIf { store == Store.SQLITE }, if (store == Store.SQLITE) listOf("Person") else emptyList()),
                sql(name, "\"helper\": \"hasIdIn\", \"alias\": \"n\", \"key\": $k, \"parameter\": \":ids\"$nodes"),
                "on $target, $name, $key",
            )
        }
    }

    @Test
    fun aPlanKeyMarksATextIdApartFromAnIdOfAnotherKindThatReadsAlike() {
        for ((name, store) in stores) for (key in listOf("id", "\$\$id", Stamps.PROPERTY)) {
            val k = CypherJson.writeValueAsString(CypherJson.valueToTree(key))
            assertEquals(IdSql.planKey(store, "n", key), sql(name, "\"helper\": \"planKey\", \"alias\": \"n\", \"key\": $k"), "on $target, $name, $key")
        }
        fun keyOf(name: String, json: String): String? {
            val j = CypherJson.writeValueAsString(CypherJson.valueToTree(json))
            return answer("{\"idSql\": {\"store\": \"$name\", \"helper\": \"planKeyOf\", \"json\": $j}}")["key"]?.textValue()
        }
        val date = "{\"\$t\":\"date\",\"v\":\"2026-01-05\",\"s\":\"2026-01-05\"}"
        for ((name, store) in stores) {
            // The text and the date of one text, and the text and the number of one text, key apart (drivine-cypher-spec#391).
            assertEquals("t:2026-01-05", keyOf(name, "\"2026-01-05\""), "on $target, $name")
            assertEquals("2026-01-05", keyOf(name, date), "on $target, $name")
            assertEquals("t:1", keyOf(name, "\"1\""), "on $target, $name")
            assertEquals("1", keyOf(name, "1"), "on $target, $name")
            assertEquals("t:true", keyOf(name, "\"true\""), "on $target, $name")
            assertEquals("true", keyOf(name, "true"), "on $target, $name")
            assertEquals(IdSql.planKeyOf(store, "\"x\""), keyOf(name, "\"x\""), "on $target, $name")
        }
        // On Postgres a number is the text `jsonb` gives back for it, `numeric`'s; elsewhere its JSON as written.
        assertEquals("0.00000010", keyOf("postgres", "1.0E-7"))
        assertEquals("1.0E-7", keyOf("sqlite", "1.0E-7"))
        assertEquals(
            listOf("0.00000010", "-0.00000000015", "0.00001230", "2.0", "12", "0.1", "0.0", "100", "\"1.0E-7\"", "[1]"),
            listOf("1.0E-7", "-1.5E-10", "1.230e-5", "2.0", "12", "0.1", "-0.0", "1.0E+2", "\"1.0E-7\"", "[1]").map(IdSql::numericText),
        )
        // The stored side tells a text by its JSON type on each store, and reads any other id as keyText does.
        for ((_, store) in stores) {
            val planKey = IdSql.planKey(store, "n", "id")
            assertTrue("'t:' ||" in planKey && IdSql.keyText(store, "n", "id") in planKey, "on $target, $store: $planKey")
        }
    }

    @Test
    fun anIdIsComparedOverTheExpressionsItsIndexIsMadeOver() {
        for ((_, store) in stores) {
            val made = IdSql.hasId(store, "n", "id", "JSON(:id)")
            for (expression in IndexKey.column(store, "n", "id")) assertTrue("$expression = " in made, "on $target, $store: $made")
            val inList = IdSql.hasIdIn(store, "n", "id", ":ids", "g_node", listOf("Person"))
            // On SQLite each id finds its node through the index on the node table's own alias.
            val alias = if (store == Store.SQLITE) "f" else "n"
            for (expression in IndexKey.column(store, alias, "id")) assertTrue(expression in inList, "on $target, $store: $inList")
        }
    }

    @Test
    fun theStampIsReadAsItsColumnAndRefusedAsAnId() {
        for ((name, store) in stores) {
            val k = CypherJson.writeValueAsString(CypherJson.valueToTree(Stamps.PROPERTY))
            assertEquals("n.stamp", IdSql.keyText(store, "n", Stamps.PROPERTY), "on $target, $store")
            assertEquals("n.stamp", IdSql.property(store, "n", Stamps.PROPERTY), "on $target, $store")
            for (helper in listOf("hasId", "hasIdIn")) {
                val error = answer("{\"idSql\": {\"store\": \"$name\", \"helper\": \"$helper\", \"alias\": \"n\", \"key\": $k, \"json\": \":id\", \"parameter\": \":ids\", \"nodes\": \"g_node\"}}")["error"]?.textValue()
                assertTrue(error != null && error.startsWith("An id is a property of the node"), "on $target, $store, $helper: $error")
            }
        }
    }

    @Test
    fun anIdInAListOnSqliteNeedsNoLabels() {
        assertEquals(
            IdSql.hasIdIn(Store.SQLITE, "n", "id", ":ids", "g_node"),
            sql("sqlite", "\"helper\": \"hasIdIn\", \"alias\": \"n\", \"key\": \"id\", \"parameter\": \":ids\", \"nodes\": \"g_node\""),
            "on $target",
        )
    }

    private fun labels(store: String, labels: String): String? =
        sql(store, "\"helper\": \"hasLabels\", \"alias\": \"n\", \"labels\": $labels")

    @Test
    fun eachLabelIsTestedAsTheLabelsIndexIsMadeOnPostgresAndSqliteAndByItsTextOnOracle() {
        assertEquals("""n.labels @> '["A"]'""", labels("postgres", "[\"A\"]"), "on $target")
        assertEquals("""n.labels @> '["A"]' AND n.labels @> '["B"]'""", labels("postgres", "[\"A\", \"B\"]"), "on $target")
        assertEquals("""instr(n.labels, '"A"') > 0""", labels("sqlite", "[\"A\"]"), "on $target")
        assertEquals("""instr(n.labels, '"A"') > 0 AND instr(n.labels, '"B"') > 0""", labels("sqlite", "[\"A\", \"B\"]"), "on $target")
        val oracle = { label: String -> """INSTR(',' || LTRIM(RTRIM(JSON_SERIALIZE(n.labels), ']'), '[') || ',"', ',"$label","') > 0""" }
        assertEquals(oracle("A"), labels("oracle", "[\"A\"]"), "on $target")
        assertEquals("${oracle("A")} AND ${oracle("B")}", labels("oracle", "[\"A\", \"B\"]"), "on $target")
        for ((name, store) in stores) {
            assertEquals(IdSql.hasLabels(store, "n", listOf("A", "B")), labels(name, "[\"A\", \"B\"]"), "on $target, $name")
        }
        // The partial index's own predicate, as the DDL writes it over the bare column.
        assertEquals(PostgresSql.hasLabel("", "A"), IdSql.hasLabels(Store.POSTGRES, "", listOf("A")), "on $target")
        assertEquals(SqliteSql.hasLabel("", "A"), IdSql.hasLabels(Store.SQLITE, "", listOf("A")), "on $target")
        assertEquals(OracleSql.carriesLabel("n", "A"), IdSql.hasLabels(Store.ORACLE, "n", listOf("A")), "on $target")
    }

    @Test
    fun noLabelsIsATrueCondition() {
        for ((name, _) in stores) assertEquals("1 = 1", labels(name, "[]"), "on $target, $name")
    }

    @Test
    fun aLabelIsGivenAsTheNodeHoldsItAndWrittenEscaped() {
        // O'Br"en: the single quote doubled for the literal, the double quote escaped for the label's JSON.
        val label = "[\"O'Br\\\"en\"]"
        assertEquals("""n.labels @> '["O''Br\"en"]'""", labels("postgres", label), "on $target")
        assertEquals("""instr(n.labels, '"O''Br\"en"') > 0""", labels("sqlite", label), "on $target")
        assertEquals("""INSTR(',' || LTRIM(RTRIM(JSON_SERIALIZE(n.labels), ']'), '[') || ',"', ',"O''Br\"en","') > 0""", labels("oracle", label), "on $target")
    }

    /**
     * The test of each label, through the JSON entry over the bare column, is the WHERE of the index the store's DDL makes over the
     * label's nodes, byte for byte, so a lookup by its id reads that index: for a backslash, a character past ASCII and both
     * quotes too. A label given twice is tested twice, which is still the index's own test.
     */
    @Test
    fun eachLabelsTestIsTheWhereOfTheLabelsIndexOnPostgresAndSqlite() {
        for (label in listOf("A", "a\\b", "Caf\u00e9 \u65e5", "O'Br\"en")) {
            val spec = RangeIndexSpec(label, listOf("id"))
            for ((name, made) in listOf("postgres" to (PostgresDdl("g") to PostgresDdl.RANGE), "sqlite" to (SqliteDdl("g") to SqliteDdl.RANGE))) {
                val (ddl, range) = made
                val head = "CREATE INDEX IF NOT EXISTS ${ddl.objectName(range, listOf(label, "id"))} ON "
                val index = ddl.createIndex(spec, null).map { ddl.blocking(it) ?: it }.filterIsInstance<DdlStatement.Text>().map { it.statement }.single { it.startsWith(head) }
                val given = CypherJson.writeValueAsString(listOf(label, label))
                val test = sql(name, "\"helper\": \"hasLabels\", \"alias\": \"\", \"labels\": $given")!!
                val where = index.substringAfter(" WHERE ")
                assertEquals("$where AND $where", test, "on $target, $name, $label")
            }
        }
    }

    @Test
    fun aRequestIdSqlCantAnswerIsAnError() {
        for ((request, message) in listOf(
            "{\"idSql\": 1}" to "An idSql request gives",
            "{\"idSql\": {\"store\": \"neo4j\", \"helper\": \"hasId\"}}" to "An idSql request names its store",
            "{\"idSql\": {\"store\": \"oracle\", \"helper\": \"find\"}}" to "An idSql request names its helper",
            "{\"idSql\": {\"store\": \"oracle\", \"helper\": \"hasId\", \"alias\": \"n\", \"key\": \"id\"}}" to "idSql's hasId gives json as text",
            "{\"idSql\": {\"store\": \"sqlite\", \"helper\": \"hasIdIn\", \"alias\": \"n\", \"key\": \"id\", \"parameter\": \":ids\"}}" to "An id in a list on SQLite",
            "{\"idSql\": {\"store\": \"sqlite\", \"helper\": \"hasIdIn\", \"alias\": \"n\", \"key\": \"id\", \"parameter\": \":ids\", \"nodes\": \"t\", \"labels\": \"P\"}}" to "idSql's hasIdIn gives labels",
            "{\"idSql\": {\"store\": \"sqlite\", \"helper\": \"hasIdIn\", \"alias\": \"n\", \"key\": \"id\", \"parameter\": \":ids\", \"nodes\": \"t\", \"labels\": [1]}}" to "idSql's hasIdIn gives labels",
            "{\"idSql\": {\"store\": \"sqlite\", \"helper\": \"hasIdIn\", \"alias\": \"n\", \"key\": \"id\", \"parameter\": \":ids\", \"nodes\": 1}}" to "idSql's hasIdIn gives nodes as text",
            "{\"idSql\": {\"store\": \"postgres\", \"helper\": \"hasLabels\", \"alias\": \"n\"}}" to "idSql's hasLabels gives labels as a list of texts",
            "{\"idSql\": {\"store\": \"sqlite\", \"helper\": \"hasLabels\", \"alias\": \"n\", \"labels\": \"A\"}}" to "idSql's hasLabels gives labels as a list of texts",
            "{\"idSql\": {\"store\": \"oracle\", \"helper\": \"hasLabels\", \"alias\": \"n\", \"labels\": [\"A\", 1]}}" to "idSql's hasLabels gives labels as a list of texts",
            "{\"idSql\": {\"store\": \"oracle\", \"helper\": \"hasLabels\", \"alias\": \"n\", \"labels\": [null]}}" to "idSql's hasLabels gives labels as a list of texts",
            "{\"idSql\": {\"store\": \"oracle\", \"helper\": \"hasLabels\", \"labels\": [\"A\"]}}" to "idSql's hasLabels gives alias as text",
            "{\"idSql\": {\"store\": \"oracle\", \"helper\": \"hasId\", \"alias\": \"n\", \"key\": \"id\", \"json\": \"x\"}, \"cypher\": \"RETURN 1\"}" to "A request gives one of",
        )) {
            val error = answer(request)["error"]?.textValue()
            assertTrue(error != null && error.startsWith(message), "on $target, $request: $error")
        }
    }
}
