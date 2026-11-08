package org.drivine.cypher.store

import org.drivine.DrivineInternalApi
import org.drivine.cypher.json.CypherJson
import org.drivine.cypher.json.JsonNode
import org.drivine.cypher.json.JsonNodeFactory

/**
 * The write side of drivine4j's `org.drivine.backend.sqlite.SqliteCypherValues`, which the compiler calls; the functions
 * SQLite runs stay in drivine4j, and call these.
 */
@DrivineInternalApi
object SqliteCypherValues {
    /** [node] as JSON text: an infinity as SQLite writes one, and NaN as [NAN]. */
    fun write(node: JsonNode): String = CypherJson.writeValueAsString(nonFinite(node))

    /** NaN, as a statement given one writes it, where SQLite's JSON holds no NaN: see [org.drivine.cypher.sql.SqliteCypherCompiler]. */
    val NAN: JsonNode = JsonNodeFactory.instance.objectNode().put("\$numberDouble", "NaN")

    /** [node] with each NaN in it, at any depth, [NAN], and each infinity the number SQLite writes for one. */
    fun nonFinite(node: JsonNode): JsonNode = when {
        node.isFloatingPointNumber && node.doubleValue().isNaN() -> NAN
        node.isFloatingPointNumber && node.doubleValue().isInfinite() ->
            JsonNodeFactory.instance.rawValueNode(if (node.doubleValue() > 0) INFINITY else "-$INFINITY")
        node is org.drivine.cypher.json.ObjectNode -> JsonNodeFactory.instance.objectNode().apply { node.properties().forEach { (key, value) -> set<JsonNode>(key, nonFinite(value)) } }
        node is org.drivine.cypher.json.ArrayNode -> JsonNodeFactory.instance.arrayNode().apply { node.forEach { add(nonFinite(it)) } }
        else -> node
    }

    /** An infinity as SQLite reads it, as it reads `9.0e+999`, which its JSON writes for one. */
    const val INFINITY = "9e999"
}
