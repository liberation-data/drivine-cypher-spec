package org.drivine.backend.sqlite

import org.drivine.cypher.json.CypherJson
import org.drivine.cypher.json.JsonNode
import org.drivine.cypher.json.JsonNodeFactory

/** SPIKE: the write side of drivine4j's `SqliteCypherValues`, which the compiler calls; the functions SQLite runs stay in drivine4j. */
internal object SqliteCypherValues {
    fun write(node: JsonNode): String = CypherJson.writeValueAsString(nonFinite(node))

    val NAN: JsonNode = JsonNodeFactory.instance.objectNode().put("\$numberDouble", "NaN")

    fun nonFinite(node: JsonNode): JsonNode = when {
        node.isFloatingPointNumber && node.doubleValue().isNaN() -> NAN
        node.isFloatingPointNumber && node.doubleValue().isInfinite() ->
            JsonNodeFactory.instance.rawValueNode(if (node.doubleValue() > 0) INFINITY else "-$INFINITY")
        node is org.drivine.cypher.json.ObjectNode -> JsonNodeFactory.instance.objectNode().apply { node.properties().forEach { (key, value) -> set<JsonNode>(key, nonFinite(value)) } }
        node is org.drivine.cypher.json.ArrayNode -> JsonNodeFactory.instance.arrayNode().apply { node.forEach { add(nonFinite(it)) } }
        else -> node
    }

    const val INFINITY = "9e999"
}
