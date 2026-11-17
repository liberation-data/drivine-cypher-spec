package org.drivine.cypher.api

import org.drivine.cypher.store.ddl.GeneratedFunctions
import org.drivine.DrivineInternalApi
import org.drivine.cypher.json.ArrayNode
import org.drivine.cypher.json.CypherJson
import org.drivine.cypher.json.JsonNode
import org.drivine.cypher.json.ObjectNode
import org.drivine.cypher.sql.Temporals
import org.drivine.cypher.store.SqliteCypherValues
import org.drivine.cypher.store.StoreValues
import org.drivine.cypher.store.ValueLayout
import org.drivine.cypher.store.ddl.OracleDdl
import org.drivine.cypher.store.ddl.PostgresDdl
import org.drivine.cypher.store.ddl.SqliteDdl
import org.drivine.cypher.store.marker.StoreLayout
import org.drivine.cypher.store.migrate.LayoutMigration

/**
 * The store layout's questions for a host with no Kotlin types, asked of the JSON entry ([CompileJson]) as a request
 * of its own in place of a statement (liberation-data/drivine-cypher-spec#161). Each is the module's own answer, so the three
 * libraries read rows, key a temporal value and judge a graph's layout alike, as drivine4j does through the Kotlin types:
 *
 * - `{"read": "<a column's JSON text>", "typed": false}`: [ValueLayout.read] of the column, a tag read as its text and each
 *   map's keys unescaped, at any depth (a column that nests past [org.drivine.cypher.json.MAX_DEPTH] is an error, as a
 *   request is, as its read would overflow a native host's stack); with `typed`, a tag as `{"$t", "v"}` and a map that has a `$t` key of its own as
 *   `{"$t": "map", "v": …}`. Answered `{"value": <the value as JSON>}`, an infinity as `9e999` or `-9e999`, as SQLite writes one. The column is given as its text, as the store gave it,
 *   so a host's JSON reader never rounds a whole number past 2^53 first (a fraction is read as a double, as the host's own reader would).
 * - `{"temporalKey": {"kind": "date", "v": "2024-01-01"}}`: [Temporals.key], the sort key `s` a tag of that kind and
 *   canonical text holds, as a host writes a temporal value of its own into a stored map. Answered `{"key": "…"}`, null
 *   for a duration, which has no order; a kind that is no temporal kind's word ([ValueLayout.word]) is an error, as is a `v`
 *   that is not that kind's canonical text ([Temporals.canonical]), as the key is read off its places.
 * - `{"layout": {"engine": "POSTGRES", "graph": "g", "stamps": [{"layout": 2}], "foreignNodes": false, "objects": [{"name": …, "type": …}]}}`:
 *   [StoreLayout.verdict] on the graph, its stamps as `StoreStampStatements.readStamps` gave them, whether it holds a node
 *   other than Drivine's own, and the objects `StoreStampStatements.layoutObjects` listed (`objects` may be left out).
 *   The engine is named as a stamp names it (`POSTGRES`, not `postgres`), and a stamp's `layout` is a whole number, its text, or null.
 *   Answered `{"verdict": "current"}`, `{"verdict": "stamp", "drops": [<statement>, …], "assign": false, "parameters": {…}}`:
 *   run the drops, then the restamp with the parameters, or where `assign`, the stamp's `assignIfAbsent` with its own
 *   parameters, as the graph has no stamp to give the layout, then read the stamps, list the objects again, and ask again; or
 *   `{"verdict": "refuse", "message": "…"}`, with `"from": 1` where the graph holds values of that earlier layout, which a
 *   migration moves to this one (`migrate`, [MigrateJson]), and `"inProgress": true` where it holds a migration's
 *   checkpoint, which the migration resumes.
 * - `{"write": <a value>, "store": "postgres" | "oracle" | "sqlite"}`: the value as that store's layout holds it, [StoreValues.text]
 *   (liberation-data/drivine-cypher-spec#107, #329), the text a compiled statement that writes binds it as: each temporal value,
 *   given as a request's parameter gives one (`{"$temporal": "DATE", "text": "2024-01-01"}`, its kind as
 *   [org.drivine.cypher.sql.Temporal] names it), its tag `{"$t", "v", "s"}`, each map key that begins with `$` escaped, at any
 *   depth, and bytes the list of their signed values, `[1, 2, -1]`; one whose text is no value of its kind is an error. Each
 *   float as the store holds it: on Postgres a whole one with its fraction (`10000000.0`), on Oracle every one as extended
 *   JSON's double (`{"$numberDouble": "1.5"}`, NaN and the infinities `"NaN"`, `"Infinity"`, `"-Infinity"`), on SQLite an
 *   infinity as `9e999` or `-9e999`. A float the store holds as no number, Postgres's NaN and infinities and SQLite's NaN, is an
 *   error, with the message a statement that writes is refused with. Every other value is written as the request gives it, a
 *   whole number past 2^53 exactly. The store is required: a graph engine's (`neo4j`) is an error. Answered
 *   `{"value": <the text, as it is>}`, so a host that writes its parameters, its save plan or a node's properties in layout 2
 *   writes them as the other libraries do, with no writer of its own.
 * - `{"rewrite": "<a row's JSON text>", "store": "postgres" | "oracle" | "sqlite", "labels": ["Person"], "declarations":
 *   [{"label": "Person", "property": "born", "kind": "date"}]}`: [LayoutMigration.rewrite] of a layout-1 node's or
 *   relationship's properties, as the store gives their text (Oracle's `JSON_SERIALIZE(props EXTENDED)`), for a node of
 *   the labels (a relationship's type, for one), each declared property's value made of its kind (a temporal kind's word,
 *   `float` or `bytes`). Answered `{"value": <the text layout 2 holds, as it is>, "changed": true, "changes": [{"property":
 *   "born", "change": "temporal"}, …], "invalid": [{"property": …, "kind": …, "value": <its JSON text>, "message": …}, …]}`;
 *   a row to write where `changed`. Declarations that give one label's property two kinds are an error, with
 *   [LayoutMigration.refusal]'s message. See `migrate.yaml`.
 * - `{"expand": "<a statement of store.yaml's>"}`: [GeneratedFunctions.expand], each marker of a generated function in it
 *   (`<TemporalArithmeticFunction.postgres(drivine)>`) written out as the module makes it, as a runner of `store.yaml` reads
 *   the statements it compares. Answered `{"text": "…"}`.
 */
@DrivineInternalApi
internal object LayoutJson {

    /** The request kinds this answers, each the key a request gives in place of `cypher`. */
    val KINDS = listOf("read", "temporalKey", "layout", "expand", "write", "rewrite")

    /** The answer to [tree], a request that gives one of [KINDS]. */
    fun answer(tree: ObjectNode): String = try {
        when {
            tree.has("read") -> read(tree)
            tree.has("temporalKey") -> temporalKey(tree["temporalKey"])
            tree.has("expand") -> expand(tree["expand"])
            tree.has("write") -> write(tree["write"], tree["store"])
            tree.has("rewrite") -> rewrite(tree)
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
        // An infinity is written as the number SQLite writes for one, 9e999, which a host's JSON reader reads back as one:
        // the writer gives a double it can't write as a number as the text "Infinity", which a host would take for a text.
        // A column's JSON holds no NaN, so none is read.
        return "{\"value\":${SqliteCypherValues.write(ValueLayout.read(value, typed?.booleanValue() ?: false))}}"
    }

    private fun write(given: JsonNode?, store: JsonNode?): String {
        // The store first: each holds a value otherwise, and a graph engine's are no layout this writes.
        val named = store?.textValue() ?: throw IllegalArgumentException("A write request gives the store: $STORES.")
        val of = Store.of(named) ?: throw IllegalArgumentException("A write request gives the store as $STORES, not $named.")
        // As a parameter is read: the value is at 2, inside the request's object.
        val value = TypedJson.value(given ?: throw IllegalArgumentException("A write request gives the value."), depth = 2)
        temporalsOfTheirKind(value)
        // The text the store holds, spliced in as it is: a float's fraction, an infinity's 9e999 and Oracle's extended doubles kept.
        return "{\"value\":${StoreValues.text(value, of)}}"
    }

    private fun rewrite(tree: ObjectNode): String {
        val props = tree["rewrite"]?.takeIf { it.isTextual }?.textValue()
            ?: throw IllegalArgumentException("A rewrite request gives the row as its JSON text.")
        val named = tree["store"]?.textValue() ?: throw IllegalArgumentException("A rewrite request gives the store: $STORES.")
        val store = Store.of(named) ?: throw IllegalArgumentException("A rewrite request gives the store as $STORES, not $named.")
        val labels = (tree["labels"] as? ArrayNode ?: throw IllegalArgumentException("A rewrite request gives the labels as a list."))
            .map { it.textValue() ?: throw IllegalArgumentException("A rewrite request's label is a text.") }
        val declarations = when (val given = tree["declarations"]?.takeUnless { it.isNull }) {
            null -> emptyList()
            is ArrayNode -> given.map { row ->
                fun text(key: String) = row[key]?.textValue() ?: throw IllegalArgumentException("A rewrite request's declaration gives its $key as a text.")
                val word = text("kind")
                val kind = LayoutMigration.Kind.of(word)
                    ?: throw IllegalArgumentException("No kind $word to declare: ${LayoutMigration.Kind.entries.joinToString { it.word }}.")
                LayoutMigration.Declaration(text("label"), text("property"), kind)
            }
            else -> throw IllegalArgumentException("A rewrite request gives the declarations as a list.")
        }
        LayoutMigration.refusal(declarations)?.let { throw IllegalArgumentException(it) }
        val done = LayoutMigration.rewrite(props, store, labels, declarations)
        fun json(value: Any?) = CypherJson.writeValueAsString(CypherJson.valueToTree(value))
        val changes = done.changes.joinToString(",", "[", "]") { "{\"property\":${json(it.property)},\"change\":${json(it.change.word)}}" }
        val invalid = done.invalid.joinToString(",", "[", "]") {
            "{\"property\":${json(it.property)},\"kind\":${json(it.kind.word)},\"value\":${json(it.value)},\"message\":${json(it.message)}}"
        }
        return "{\"value\":${done.props},\"changed\":${done.changed},\"changes\":$changes,\"invalid\":$invalid}"
    }

    /** The stores a write request names, as a statement's request names them. */
    private const val STORES = "postgres, oracle or sqlite"

    /**
     * Refuses a temporal value in [value], at any depth, whose text is no value of its kind: [CypherJson.valueToTree] would
     * write it as plain text, which the layout reads back as a string, not as the value the host marked.
     */
    private fun temporalsOfTheirKind(value: Any?) {
        when (value) {
            is org.drivine.cypher.json.TemporalValue ->
                require(CypherJson.temporalTag(value) is ObjectNode) { "No ${value.kind.name} is ${value.text}." }
            is Map<*, *> -> value.values.forEach(::temporalsOfTheirKind)
            is List<*> -> value.forEach(::temporalsOfTheirKind)
        }
    }

    private fun expand(given: JsonNode?): String {
        val text = given?.takeIf { it.isTextual }?.textValue() ?: throw IllegalArgumentException("An expand request gives the statement as a text.")
        return "{\"text\":${CypherJson.writeValueAsString(GeneratedFunctions.expand(text))}}"
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
                "{\"verdict\":\"stamp\",\"drops\":${CypherJson.writeValueAsString(CypherJson.valueToTree(verdict.drops))},\"assign\":${verdict.assign}," +
                    "\"parameters\":${CypherJson.writeValueAsString(CypherJson.valueToTree(verdict.parameters))}}"
            is StoreLayout.Verdict.Refuse -> "{\"verdict\":\"refuse\",\"message\":${CypherJson.writeValueAsString(CypherJson.valueToTree(verdict.message))}" +
                (verdict.from?.let { ",\"from\":$it" } ?: "") + (if (verdict.inProgress) ",\"inProgress\":true" else "") + "}"
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
