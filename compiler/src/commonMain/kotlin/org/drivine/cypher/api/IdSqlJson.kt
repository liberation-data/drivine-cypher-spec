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
 * - `keyText`, `property` and `hasProperty`, with `alias` and `key` ([IdSql.keyText], [IdSql.property], [IdSql.hasProperty]).
 *
 * Each key is given as the node holds it, escaped where it begins with `$`. `json` and `parameter` are written into the SQL as
 * given, in the host's own placeholder; on Oracle and SQLite `json` is written more than once, so it is bound by name.
 * Answered `{"sql": "…"}`.
 */
@DrivineInternalApi
internal object IdSqlJson {

    const val KIND = "idSql"

    private val HELPERS = listOf("hasId", "hasIdIn", "keyText", "property", "hasProperty")

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
            "property" -> IdSql.property(store, text("alias"), text("key"))
            "hasProperty" -> IdSql.hasProperty(store, text("alias"), text("key"))
            else -> throw IllegalArgumentException("An $KIND request names its helper, one of ${HELPERS.joinToString(", ")}, not $helper.")
        }
        "{\"sql\":${CypherJson.writeValueAsString(CypherJson.valueToTree(sql))}}"
    } catch (e: Exception) {
        // As every other request answers a failure: a host is answered, never thrown at.
        CompileJson.error(e.message ?: e.toString())
    }
}
