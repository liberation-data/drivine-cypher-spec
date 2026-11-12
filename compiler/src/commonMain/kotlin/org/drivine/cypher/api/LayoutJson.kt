package org.drivine.cypher.api

import org.drivine.DrivineInternalApi
import org.drivine.cypher.json.ArrayNode
import org.drivine.cypher.json.CypherJson
import org.drivine.cypher.json.JsonNode
import org.drivine.cypher.json.ObjectNode
import org.drivine.cypher.sql.Temporals
import org.drivine.cypher.store.ValueLayout
import org.drivine.cypher.store.ddl.OracleDdl
import org.drivine.cypher.store.ddl.PostgresDdl
import org.drivine.cypher.store.ddl.SqliteDdl
import org.drivine.cypher.store.marker.StoreLayout

/**
 * The store layout's three questions for a host with no Kotlin types, asked of the JSON entry ([CompileJson]) as a request
 * of its own in place of a statement (liberation-data/drivine-cypher-spec#161). Each is the module's own answer, so the three
 * libraries read rows, key a temporal value and judge a graph's layout alike, as drivine4j does through the Kotlin types:
 *
 * - `{"read": "<a column's JSON text>", "typed": false}`: [ValueLayout.read] of the column, a tag read as its text and each
 *   map's keys unescaped, at any depth (a column that nests past [org.drivine.cypher.json.MAX_DEPTH] is an error, as a
 *   request is, as its read would overflow a native host's stack); with `typed`, a tag as `{"$t", "v"}` and a map that has a `$t` key of its own as
 *   `{"$t": "map", "v": …}`. Answered `{"value": <the value as JSON>}`. The column is given as its text, as the store gave it,
 *   so a host's JSON reader never rounds a whole number past 2^53 first (a fraction is read as a double, as the host's own reader would).
 * - `{"temporalKey": {"kind": "date", "v": "2024-01-01"}}`: [Temporals.key], the sort key `s` a tag of that kind and
 *   canonical text holds, as a host writes a temporal value of its own into a stored map. Answered `{"key": "…"}`, null
 *   for a duration, which has no order; a kind that is no temporal kind's word ([ValueLayout.word]) is an error, as is a `v`
 *   that is not that kind's canonical text ([Temporals.canonical]), as the key is read off its places.
 * - `{"layout": {"engine": "POSTGRES", "graph": "g", "stamps": [{"layout": 2}], "foreignNodes": false, "objects": [{"name": …, "type": …}]}}`:
 *   [StoreLayout.verdict] on the graph, its stamps as `StoreStampStatements.readStamps` gave them, whether it holds a node
 *   other than Drivine's own, and the objects `StoreStampStatements.layoutObjects` listed (`objects` may be left out).
 *   The engine is named as a stamp names it (`POSTGRES`, not `postgres`), and a stamp's `layout` is a whole number, its text, or null.
 *   Answered `{"verdict": "current"}`, `{"verdict": "stamp", "drops": [<statement>, …], "parameters": {…}}`: run the drops,
 *   then the restamp with the parameters, then read the stamps and ask again; or `{"verdict": "refuse", "message": "…"}`.
 */
@DrivineInternalApi
internal object LayoutJson {

    /** The request kinds this answers, each the key a request gives in place of `cypher`. */
    val KINDS = listOf("read", "temporalKey", "layout")

    /** The answer to [tree], a request that gives one of [KINDS]. */
    fun answer(tree: ObjectNode): String = try {
        when {
            tree.has("read") -> read(tree)
            tree.has("temporalKey") -> temporalKey(tree["temporalKey"])
            else -> layout(tree["layout"])
        }
    } catch (e: Exception) {
        // As SearchJson and a statement's request answer any failure: a host is answered, never thrown at.
        CompileJson.error(e.message ?: e.toString())
    }

    private fun read(tree: ObjectNode): String {
        val column = tree["read"]?.takeIf { it.isTextual }?.textValue()
            ?: throw IllegalArgumentException("A read request gives the column as its JSON text.")
        val typed = tree["typed"]?.takeUnless { it.isNull }
        require(typed == null || typed.isBoolean) { "A request gives typed as true or false." }
        val value = try {
            CypherJson.readTree(column)
        } catch (e: Exception) {
            throw IllegalArgumentException("A read request's column is no JSON: ${e.message}")
        }
        return "{\"value\":${CypherJson.writeValueAsString(ValueLayout.read(value, typed?.booleanValue() ?: false))}}"
    }

    private fun temporalKey(given: JsonNode?): String {
        require(given is ObjectNode) { "A temporalKey request gives {\"kind\": …, \"v\": …}." }
        val word = given["kind"]?.textValue() ?: throw IllegalArgumentException("A temporalKey request gives the kind as a text.")
        val kind = ValueLayout.kindOf(word)
            ?: throw IllegalArgumentException("No temporal kind $word: ${ValueLayout.ORDER.joinToString { ValueLayout.word(it) }}.")
        val v = given["v"]?.textValue() ?: throw IllegalArgumentException("A temporalKey request gives v, the canonical text, as a text.")
        // The key is read off the canonical text's places: another text of the same value would be keyed wrongly, or not at all.
        val canonical = try { Temporals.canonical(v, kind) } catch (e: Exception) { null }
        require(canonical == v) { "A temporalKey request gives v as a $word's canonical text: ${canonical ?: "no $word"}, not $v." }
        val key = Temporals.key(kind, v)
        return "{\"key\":${if (key == null) "null" else CypherJson.writeValueAsString(CypherJson.valueToTree(key))}}"
    }

    private fun layout(given: JsonNode?): String {
        require(given is ObjectNode) { "A layout request gives {\"engine\": …, \"graph\": …, \"stamps\": […], \"foreignNodes\": …}." }
        val engine = given["engine"]?.textValue() ?: throw IllegalArgumentException("A layout request gives the engine as a text.")
        // A SQL store's engine named in another case, as a statement's request names its store, would be judged a graph engine's,
        // and a graph engine's would be named so in a refusal's message, not as drivine4j names it.
        require(engine.uppercase() !in StoreLayout.ENGINE_NAMES || engine in StoreLayout.ENGINE_NAMES) {
            "A layout request names the engine as its stamp does: ${engine.uppercase()}, not $engine."
        }
        val graph = given["graph"]?.textValue() ?: throw IllegalArgumentException("A layout request gives the graph as a text.")
        val stamps = (given["stamps"] as? ArrayNode ?: throw IllegalArgumentException("A layout request gives the stamps as a list."))
            .map { row ->
                val stamp = row as? ObjectNode ?: throw IllegalArgumentException("A layout request's stamp is a map.")
                // StoreLayout reads a layout as an Int: a fraction, or a number past an Int's range, would be cut to another layout.
                val layout = stamp["layout"]
                require(layout == null || !layout.isNumber || layout.doubleValue().let { it % 1.0 == 0.0 && it >= Int.MIN_VALUE && it <= Int.MAX_VALUE }) {
                    "A layout request's stamp gives its layout as a whole number: ${CypherJson.writeValueAsString(layout)}."
                }
                plain(stamp)
            }
        val foreign = given["foreignNodes"]?.takeIf { it.isBoolean }?.booleanValue()
            ?: throw IllegalArgumentException("A layout request gives foreignNodes as true or false.")
        val objects = when (val listed = given["objects"]?.takeUnless { it.isNull }) {
            null -> emptyList()
            is ArrayNode -> listed.map { row -> (row as? ObjectNode ?: throw IllegalArgumentException("A layout request's object is a map.")).let(::plain) }
            else -> throw IllegalArgumentException("A layout request gives the objects as a list.")
        }
        // The store's DDL the graph was made with, which names what a stamp drops; a graph engine's has nothing to drop.
        val ddl = when (engine) {
            "POSTGRES" -> PostgresDdl(graph)
            "ORACLE" -> OracleDdl(graph)
            "SQLITE" -> SqliteDdl(graph)
            else -> null
        }
        return when (val verdict = StoreLayout.verdict(engine, graph, stamps, foreign, objects, ddl)) {
            StoreLayout.Verdict.Current -> "{\"verdict\":\"current\"}"
            is StoreLayout.Verdict.Stamp ->
                "{\"verdict\":\"stamp\",\"drops\":${CypherJson.writeValueAsString(CypherJson.valueToTree(verdict.drops))}," +
                    "\"parameters\":${CypherJson.writeValueAsString(CypherJson.valueToTree(verdict.parameters))}}"
            is StoreLayout.Verdict.Refuse -> "{\"verdict\":\"refuse\",\"message\":${CypherJson.writeValueAsString(CypherJson.valueToTree(verdict.message))}}"
        }
    }

    /** [node], a map of plain values (texts, numbers, truth values, null), as a Kotlin map. */
    private fun plain(node: ObjectNode): Map<String, Any?> = node.properties().associate { (key, value) ->
        key to when {
            value.isNull -> null
            value.isTextual -> value.textValue()
            value.isIntegralNumber -> value.longValue()
            value.isNumber -> value.doubleValue()
            value.isBoolean -> value.booleanValue()
            else -> CypherJson.writeValueAsString(value)
        }
    }
}
