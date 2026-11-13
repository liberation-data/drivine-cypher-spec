package org.drivine.cypher.api

import org.drivine.DrivineInternalApi
import org.drivine.cypher.json.ArrayNode
import org.drivine.cypher.json.JsonNode
import org.drivine.cypher.json.ObjectNode
import org.drivine.cypher.sql.FragmentVariable
import org.drivine.cypher.sql.SqlFragment

/**
 * A part of a statement a library writes itself ([SqlFragment]), asked of the JSON entry ([CompileJson]) as drivine4j asks
 * the store's compiler for it: a request that gives `fragment` in place of `cypher`, beside the `store`, the `graph` and the
 * `parameters` a statement's request gives (liberation-data/drivine-cypher-spec#161).
 *
 * ```json
 * {"store": "oracle", "graph": {"name": "g", "indexedProperties": [["Person", "name"]]},
 *  "fragment": {"condition": "n.name = $p", "variables": {"n": {"node": "people_", "labels": ["Person"]}}},
 *  "parameters": {"p": "Ada"}}
 * ```
 *
 * `fragment` gives one of `condition` ([org.drivine.cypher.sql.SqlCypherCompiler.condition]), `value`, or `order`, with
 * `descending` (false where left out), each a Cypher expression; and `variables`, each a row of the library's SQL: `{"node":
 * "<alias>", "labels": […]}` or `{"relationship": "<alias>"}`, the alias ending with `_` ([FragmentVariable.row]). Answered `{"terms": […], "parameters": {…}}`, on Oracle with
 * `storedFunctions` as a statement's answer has it, the parameters written as a statement's are; `{"refusal": …}` where the
 * compiler refuses the expression; `{"error": …}` where the request is no request.
 */
@DrivineInternalApi
internal object FragmentJson {

    private val PARTS = listOf("condition", "value", "order")

    fun answer(tree: ObjectNode): String {
        val request = try {
            read(tree)
        } catch (e: Exception) {
            return CompileJson.error(e.message ?: e.toString())
        }
        val compiler = CypherCompiler.compiler(request.store, request.graph)
        val fragment = try {
            when (request.part) {
                "condition" -> compiler.condition(request.cypher, request.variables, request.parameters)
                "value" -> compiler.value(request.cypher, request.variables, request.parameters)
                else -> compiler.order(request.cypher, request.descending, request.variables, request.parameters)
            }
        } catch (e: Exception) {
            return CompileJson.refusal(e.message ?: e.toString())
        }
        return written(fragment, CypherCompiler.storedFunctions(compiler))
    }

    private class Request(
        val store: Store,
        val graph: GraphOptions,
        val part: String,
        val cypher: String,
        val descending: Boolean,
        val variables: Map<String, FragmentVariable>,
        val parameters: Map<String, Any?>,
    )

    private fun read(tree: ObjectNode): Request {
        val store = CompileJson.store(tree)
        val graph = CompileJson.graph(tree)
        val fragment = tree["fragment"] as? ObjectNode
            ?: throw IllegalArgumentException("A request's fragment is {\"condition\" | \"value\" | \"order\": …, \"variables\": {…}}.")
        val parts = PARTS.filter { fragment.has(it) }
        require(parts.size == 1) { "A fragment gives one of condition, value and order." }
        val part = parts.single()
        val cypher = fragment[part]?.textValue() ?: throw IllegalArgumentException("A fragment gives its $part as a Cypher expression's text.")
        val descending = fragment["descending"]?.takeUnless { it.isNull }
        require(descending == null || descending.isBoolean) { "A fragment gives descending as true or false." }
        require(descending == null || part == "order") { "Only an order is descending." }
        val variables = when (val given = fragment["variables"]?.takeUnless { it.isNull }) {
            null -> emptyMap()
            is ObjectNode -> given.properties().associate { (name, variable) -> name to variable(name, variable) }
            else -> throw IllegalArgumentException("A fragment gives its variables as {\"<name>\": {…}, …}.")
        }
        return Request(store, graph, part, cypher, descending?.booleanValue() ?: false, variables, CompileJson.parameters(tree))
    }

    private fun variable(name: String, given: JsonNode): FragmentVariable {
        val variable = given as? ObjectNode
            ?: throw IllegalArgumentException("A fragment's variable $name is {\"node\": <row>, \"labels\": […]} or {\"relationship\": <row>}.")
        val node = variable["node"]?.textValue()
        val relationship = variable["relationship"]?.textValue()
        require((node == null) != (relationship == null)) {
            "A fragment's variable $name is {\"node\": <row>, \"labels\": […]} or {\"relationship\": <row>}."
        }
        if (relationship != null) {
            require(!variable.has("labels")) { "A fragment's variable $name is a relationship, which has no labels." }
            return FragmentVariable.Relationship(relationship)
        }
        val labels = when (val listed = variable["labels"]?.takeUnless { it.isNull }) {
            null -> emptyList()
            is ArrayNode -> listed.map { it.textValue() ?: throw IllegalArgumentException("A fragment's variable $name gives its labels as texts.") }
            else -> throw IllegalArgumentException("A fragment's variable $name gives its labels as a list.")
        }
        return FragmentVariable.Node(node!!, labels)
    }

    private fun written(fragment: SqlFragment, stored: StoredFunctions?) = StringBuilder().apply {
        append("{\"terms\":"); TypedJson.write(fragment.terms, this)
        append(",\"parameters\":"); TypedJson.write(fragment.parameters, this, 2)
        stored?.let { append(",\"storedFunctions\":{\"nullsEqual\":").append(it.nullsEqual).append(",\"era\":").append(it.era).append('}') }
        append('}')
    }.toString()
}
