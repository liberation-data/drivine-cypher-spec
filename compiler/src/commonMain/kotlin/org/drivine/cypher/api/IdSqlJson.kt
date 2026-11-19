package org.drivine.cypher.api

import org.drivine.DrivineInternalApi
import org.drivine.cypher.json.ArrayNode
import org.drivine.cypher.json.CypherJson
import org.drivine.cypher.json.ObjectNode
import org.drivine.cypher.store.IdSql

/**
 * The SQL a library writes to find a node by its id, and to read it, for a host with no Kotlin types, asked of the JSON entry
 * ([CompileJson]) in place of a statement (liberation-data/drivine-cypher-spec#107): [IdSql]'s own, so the three libraries
 * compare an id as the store's index is made over it.
 *
 * `{"idSql": {"store": "oracle", "helper": "hasId", "alias": "n", "key": "id", "json": "JSON(:id)"}}`, the store one of
 * `postgres`, `oracle` and `sqlite`, and the helper one of:
 * - `hasId`, with `alias`, `key` and `json`, the SQL of the id's JSON as the host binds it ([IdSql.hasId]);
 * - `hasIdIn`, with `alias`, `key` and `parameter`, the SQL of a JSON array of each id's JSON text (`["\"a\"", "1"]`), and on
 *   SQLite `nodes`, the node table, and `labels`, a list of the node's labels ([IdSql.hasIdIn]);
 * - `keyText`, `planKey`, `property` and `hasProperty`, with `alias` and `key` ([IdSql.keyText], [IdSql.planKey],
 *   [IdSql.property], [IdSql.hasProperty]);
 * - `planKeyOf`, with `json`, an id's JSON as the store's layout holds it: the key a write plan keys that id by, as `planKey`
 *   reads it back ([IdSql.planKeyOf]), answered `{"key": "…"}`;
 * - `hasLabels`, with `alias` and `labels`, a list of the node's labels, the test a lookup by its id makes of each beside
 *   `hasId`: on Postgres and SQLite as the label's partial index is made, on Oracle by its text; `1 = 1` for none
 *   ([IdSql.hasLabels]).
 *
 * Each key is given as the node holds it, escaped where it begins with `$`. Each label is given as the node holds it too, and
 * written into the SQL escaped, so a label with a quote (`'` or `"`) in it needs nothing of the host. `json` and `parameter` are written into the SQL as
 * given, in the host's own placeholder; on Oracle and SQLite `json` is written more than once, so it is bound by name.
 * Answered `{"sql": "…"}`.
 */
@DrivineInternalApi
internal object IdSqlJson {

    const val KIND = "idSql"

    private val HELPERS = listOf("hasId", "hasIdIn", "keyText", "planKey", "planKeyOf", "property", "hasProperty", "hasLabels")

    /** The answer to [tree], a request that gives [KIND]. */
    fun answer(tree: ObjectNode): String = try {
        val given = tree[KIND] as? ObjectNode ?: throw IllegalArgumentException("An $KIND request gives {\"store\": …, \"helper\": …, …}.")
        val store = when (val name = given["store"]?.textValue()) {
            "postgres" -> Store.POSTGRES
            "oracle" -> Store.ORACLE
            "sqlite" -> Store.SQLITE
            else -> throw IllegalArgumentException("An $KIND request names its store, one of postgres, oracle and sqlite, not $name.")
        }
        fun text(name: String): String = given[name]?.takeIf { it.isTextual }?.textValue()
            ?: throw IllegalArgumentException("$KIND's ${given["helper"]?.textValue()} gives $name as text.")
        val sql = when (val helper = given["helper"]?.textValue()) {
            "hasId" -> IdSql.hasId(store, text("alias"), text("key"), text("json"))
            "hasIdIn" -> IdSql.hasIdIn(
                store, text("alias"), text("key"), text("parameter"),
                given["nodes"]?.takeUnless { it.isNull }?.let { it.textValue() ?: throw IllegalArgumentException("$KIND's hasIdIn gives nodes as text.") },
                given["labels"]?.takeUnless { it.isNull }?.let { labels ->
                    (labels as? ArrayNode ?: throw IllegalArgumentException("$KIND's hasIdIn gives labels as a list of texts."))
                        .map { it.textValue() ?: throw IllegalArgumentException("$KIND's hasIdIn gives labels as a list of texts.") }
                }.orEmpty(),
            )
            "keyText" -> IdSql.keyText(store, text("alias"), text("key"))
            "planKey" -> IdSql.planKey(store, text("alias"), text("key"))
            "planKeyOf" -> {
                val json = text("json")
                // Named as the request's own fault: the parser's message, which differs from target to target, says only where.
                val key = try { IdSql.planKeyOf(store, json) } catch (e: Exception) {
                    throw IllegalArgumentException("$KIND's planKeyOf gives json as an id's JSON, not $json (${e.message}).")
                }
                return "{\"key\":${CypherJson.writeValueAsString(CypherJson.valueToTree(key))}}"
            }
            "property" -> IdSql.property(store, text("alias"), text("key"))
            "hasProperty" -> IdSql.hasProperty(store, text("alias"), text("key"))
            "hasLabels" -> IdSql.hasLabels(
                store, text("alias"),
                (given["labels"] as? ArrayNode ?: throw IllegalArgumentException("$KIND's hasLabels gives labels as a list of texts."))
                    .map { it.takeIf { it.isTextual }?.textValue() ?: throw IllegalArgumentException("$KIND's hasLabels gives labels as a list of texts.") },
            )
            else -> throw IllegalArgumentException("An $KIND request names its helper, one of ${HELPERS.joinToString(", ")}, not $helper.")
        }
        "{\"sql\":${CypherJson.writeValueAsString(CypherJson.valueToTree(sql))}}"
    } catch (e: Exception) {
        // As every other request answers a failure: a host is answered, never thrown at.
        CompileJson.error(e.message ?: e.toString())
    }
}
