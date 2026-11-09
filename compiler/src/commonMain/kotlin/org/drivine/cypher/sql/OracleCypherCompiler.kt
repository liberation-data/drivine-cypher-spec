package org.drivine.cypher.sql

import org.drivine.DrivineInternalApi
import org.drivine.cypher.json.JsonNode
import org.drivine.cypher.json.ArrayNode
import org.drivine.cypher.json.JsonNodeFactory
import org.drivine.cypher.json.ObjectNode
import org.drivine.cypher.CompilerException
import org.drivine.cypher.store.OracleSql
import org.drivine.cypher.ast.RelationshipPattern
import org.drivine.cypher.json.CypherEncoder
import org.drivine.cypher.json.CypherJson
import org.drivine.cypher.store.Stamps

/**
 * Compiles a Cypher statement to SQL over a graph's tables on Oracle: see [SqlCypherCompiler].
 *
 * A value is Oracle's `JSON`, which compares, groups and orders as JSON and keeps a map's keys in the
 * order written. Oracle keeps a number as a number, so a stored `2.0` reads as `2`; a floating-point
 * number the statement writes, or a parameter's that is a whole number, is a `BINARY_DOUBLE`, and stays one. A text Oracle reads out of JSON is a
 * `VARCHAR2` of at most 4,000 bytes, and one longer is read as a CLOB where it is measured, cut, changed, searched or reversed (see
 * [texts]), or compared (see [jsonEquals] and [ordered]). An empty text is null, as Oracle's empty text is. `reduce` is a function the query
 * declares, and does not run in a statement that writes, in the step of a `reduce`, or where its step reads a path.
 *
 * A list of ids is text, each id between commas (`,1,2,`): Oracle has no array, and a recursive query
 * takes no `JSON` that changes from row to row. A recursive query reads no row outside it, so a walk
 * starts from every node that meets what the node walked from meets of its own, and carries the node
 * it started from.
 *
 * @param nullsEqualStored whether two values that may hold a null are compared by the function the store keeps
 * beside the graph's tables, [nullsEqualName]: see [nullsEqual].
 * @param eraStored whether a text or a timestamp whose year may be BC is renumbered by the functions the store keeps beside
 * the graph's tables, [eraName] and [eraDateName]: see [OracleTemporalSql.era].
 */
class OracleCypherCompiler(
    graph: String,
    private val nullsEqualStored: Boolean,
    eraStored: Boolean,
    /**
     * The labels and properties a range index or a one-property uniqueness constraint is over, each as `label to key`: a
     * node a pattern finds by one is read through that index ([indexedEquality]). Empty, as a statement compiled without
     * the store's catalog is, no node is: its labels tested as the index over them answers, a property with no index of its
     * own would read every node of the graph in place of those of the label (#617).
     */
    private val indexedProperties: Set<Pair<String, String>>,
    /** The host's writing of the values it gives as JSON: see [CypherEncoder]. */
    encoder: CypherEncoder?,
    /**
     * Whether a statement that writes returns its rows as one row of JSON text, in place of a row of `JSON` each: see
     * [returnRows], and [org.drivine.cypher.api.GraphOptions.oracleRowsAsText]. False where it is left out, as the other
     * constructors leave it.
     */
    private val rowsAsText: Boolean,
) : SqlCypherCompiler(graph, encoder) {

    // The constructor drivine4j's compiler had, its defaults among it, kept so that what was compiled against it still links.
    constructor(
        graph: String,
        nullsEqualStored: Boolean = true,
        eraStored: Boolean = true,
        indexedProperties: Set<Pair<String, String>> = emptySet(),
    ) : this(graph, nullsEqualStored, eraStored, indexedProperties, null, false)

    // The constructor with an encoder and without rowsAsText, kept so that what was compiled against it still links.
    constructor(
        graph: String,
        nullsEqualStored: Boolean,
        eraStored: Boolean,
        indexedProperties: Set<Pair<String, String>>,
        encoder: CypherEncoder?,
    ) : this(graph, nullsEqualStored, eraStored, indexedProperties, encoder, false)

    /** The function the store keeps that compares two values that may hold a null: see [nullsEqual]. */
    private val storedNullsEqual = nullsEqualName(graph)

    /**
     * Whether the statement compiled calls [nullsEqualName]'s function, which a store made by an earlier version, or by a
     * user who may not make a function, does not have: compiled again with `nullsEqualStored = false`, it calls none.
     */
    var callsStoredNullsEqual = false
        private set

    /**
     * Whether the statement compiled calls [eraName]'s or [eraDateName]'s function, which a store made by an earlier
     * version, or by a user who may not make a function, does not have: compiled again with `eraStored = false`, it calls
     * neither, and renumbers a year BC inline, as it gives the same rows but takes Oracle longer to plan.
     */
    var callsStoredEra = false
        private set

    override fun literalJson(value: Any?) =
        if (value is Double) "JSON_SCALAR(TO_BINARY_DOUBLE(${literal(org.drivine.cypher.json.jvmDoubleText(value))}))"
        else "JSON(${textLiteral(CypherJson.writeValueAsString(value, encoder = encoder))})"

    /**
     * A text that holds an extended JSON double is read as extended JSON, and only one: else a map of the parameter's whose
     * key is `${'$'}numberDouble` would be read as a double.
     */
    override fun parameterJson(marker: String, text: String?) = if (text != null && extended.any { it === text }) "JSON($marker EXTENDED)" else "JSON($marker)"

    /**
     * The parameters' texts that hold a float as Oracle's extended JSON writes one: see [parameterText]. Kept by identity, as
     * a map parameter `{"${'$'}numberDouble": "2.0"}` is written as the same text as the float 2.0.
     */
    private val extended: MutableList<String> = mutableListOf()

    /** The names of a procedure's parameters that hold a float as Oracle's extended JSON writes one: see [propertyOf]. */
    private val extendedParameters = mutableSetOf<String>()

    /**
     * A float that is a whole number, `2.0`, written as Oracle's extended JSON writes a double, `{"${'$'}numberDouble": "2.0"}`, which
     * `JSON(… EXTENDED)` reads as one: Oracle reads JSON's `2.0` as the number 2, a whole number, as it reads `2`. So are NaN and
     * the infinities, `{"${'$'}numberDouble": "NaN"}`, which JSON writes as no number, and `BINARY_DOUBLE` holds. Not in a
     * value that holds a map with a key as extended JSON's are, `{"${'$'}numberLong": "5"}`, which `JSON(… EXTENDED)` would read as
     * the value it writes: such a value is written as JSON is, its floats that are whole numbers read as whole numbers. A
     * statement that writes binds all its parameters as one text, and reads as extended JSON only those of them with such
     * a double in them: see [propertyOf].
     */
    override fun parameterText(value: Any?): String {
        val tree = CypherJson.valueToTree(value, encoder)
        if (keyedAsExtended(tree)) return CypherJson.writeValueAsString(tree, encoder = encoder)
        var doubled = false
        val written = doubles(tree) { doubled = true }
        // A procedure's parameters are one map: those of its entries with a double in them are read as extended JSON.
        if (doubled && written is ObjectNode) extendedParameters += written.properties().filter { keyedAsExtended(it.value) }.map { it.key }
        return CypherJson.writeValueAsString(written, encoder = encoder).also { if (doubled) extended += it }
    }

    /** Whether [node] holds a map with a key that starts with `$`, as extended JSON's keys do. */
    private fun keyedAsExtended(node: JsonNode): Boolean =
        (node is ObjectNode && node.fieldNames().asSequence().any { it.startsWith("$") }) || node.any(::keyedAsExtended)

    /**
     * [node] with each float in it that is a whole number written as extended JSON's double, calling [doubled] for each. A
     * `BigDecimal` is no float: it stays the number it is, every digit kept.
     */
    private fun doubles(node: JsonNode, doubled: () -> Unit): JsonNode = when {
        (node.isDouble || node.isFloat) && node.doubleValue().let { !it.isFinite() || it == kotlin.math.round(it) } -> {
            doubled()
            JsonNodeFactory.instance.objectNode().put(DOUBLE, org.drivine.cypher.json.jvmDoubleText(node.doubleValue()))
        }
        node is ObjectNode -> node.apply { properties().toList().forEach { (key, value) -> set<JsonNode>(key, doubles(value, doubled)) } }
        node is ArrayNode -> node.apply { (0 until size()).forEach { set(it, doubles(get(it), doubled)) } }
        else -> node
    }

    override fun parameterCount(marker: String) = "CAST($marker AS NUMBER)"

    override fun comparable(value: Value.Json) = value.sql

    /** A CLOB that [texts] gives its body is null where it is empty, as a `VARCHAR2` is, and as Oracle's empty text is. */
    override fun scalar(sql: String) = if (long) "JSON_SCALAR(CASE WHEN LENGTH($sql) > 0 THEN $sql END)" else "JSON_SCALAR($sql)"

    /** A floating-point number is a number, as JSON has it. */
    override fun typeOf(json: String) = shared(json) { (x) -> "DECODE(${kind(x)}, 'double', 'number', 'float', 'number', ${kind(x)})" }

    override fun isJsonNull(json: String) = "NVL(${kind(json)}, 'null') = 'null'"

    override fun nullIfJsonNull(json: String) = shared(json) { (x) -> "CASE WHEN ${kind(x)} <> 'null' THEN $x END" }

    /**
     * Where the kind comes, as [kindOrder] ranks them and null for null, then the value as `JSON` orders it, which is Cypher's
     * order within a kind: `JSON` orders a number before a text, a truth value after both, and a map and a list last. A null
     * is ranked null, so the value needs no [nullIfJsonNull], which would read it again: as fast as [sortKey] over 100,000 rows.
     */
    override fun openSortKey(json: String) = shared(json) { (x) ->
        "DECODE(${kind(x)}, 'object', 1, 'array', 2, 'string', 3, 'boolean', 4, 'number', 5, 'double', 5, 'float', 5)"
    }.let { rank -> listOf(rank, json) }

    /** JSON's null is ranked null, as SQL's is, but `JSON` orders it apart from SQL's: so the value is read as [groupedNull] has it (#500). */
    override fun jsonNullSortKey(json: String) = openSortKey(json).let { (rank) -> listOf(rank, groupedNull(json)) }

    /** Oracle groups by no subquery, so the value is not bound in one, as [nullIfJsonNull] binds a long one: it is read twice. */
    override fun comparedNull(json: String) = "CASE WHEN ${kind(json)} <> 'null' THEN $json END"

    /**
     * SQL's null read as JSON's, which reads the value once, and reads no kind of it: [comparedNull] reads both, and kept
     * `DISTINCT` over 300,000 maps' values 67% longer than without it, and an order by them 39% longer; this, 8% and 3% (#500).
     */
    override fun groupedNull(json: String) = "NVL($json, ${literalJson(null)})"

    override fun number(json: String) = shared(json) { (x) -> "CASE WHEN ${typeOf(x)} = 'number' THEN JSON_VALUE($x, '$' RETURNING NUMBER) END" }

    /** A whole number, and no floating-point number, as Cypher has an integer. */
    override fun integral(json: String) = shared(json) { (x) ->
        "(${kind(x)} = 'number' AND JSON_VALUE($x, '$' RETURNING NUMBER) = TRUNC(JSON_VALUE($x, '$' RETURNING NUMBER)))"
    }

    /** A `VARCHAR2`, which holds 4,000 bytes: a longer text is null. See [texts], which reads one as a CLOB. */
    override fun text(json: String) = shared(json) { (x) -> "CASE WHEN ${kind(x)} = 'string' THEN JSON_VALUE($x, '$') END" }

    /** The JSON [json] as a CLOB, where it is a string: a text of any length, slower to read than [text]'s `VARCHAR2`. */
    private fun longText(json: String) = shared(json) { (x) -> "CASE WHEN ${kind(x)} = 'string' THEN JSON_VALUE($x, '$' RETURNING CLOB) END" }

    /**
     * [body] over the texts as [text] reads them, `VARCHAR2`s, and where it gives null, over them as CLOBs (#298). A text
     * past 4,000 bytes is null as a `VARCHAR2`, where Neo4j measures it, cuts it and searches it whole. [body] gives null
     * where a text it is given is null, and where what it gives would not fit in a `VARCHAR2`: see [replaceText] and
     * [split]. A short text is not read as a CLOB, which is slower: `COALESCE` reads its second value only where the first
     * is null. [body] knows which it is given by [long].
     *
     * A property beside a long value, which is bound in a subquery, has its texts bound beside it, each read by the
     * property's own path (see [direct]): Oracle closed the session (ORA-03113) where a text past 32 KB was read as a
     * CLOB, and searched or compared, beside such a subquery for each of two rows or more (#370), where the property was
     * left where it is read, and where it was bound as JSON and read as a CLOB from that.
     */
    override fun texts(vararg jsons: String, body: (List<String>) -> String): String = texts(*jsons, lengths = null, body = body)

    /**
     * [texts], and where [lengths] is given, where [body] gives null over them as `VARCHAR2`s and as CLOBs, [lengths] over
     * the texts' lengths, as [textLength] reads them: null where a value is no text. An empty text is null as a `VARCHAR2`
     * and as a CLOB, as Oracle's empty text is, so [body] gives null where one is: [lengths] answers for it (#416). It is
     * read only there, as `COALESCE` reads a value only where those before it are null.
     */
    private fun texts(vararg jsons: String, lengths: ((List<String>) -> String)?, body: (List<String>) -> String): String {
        val properties = jsons.map(::besideLong)
        if (!binds(*jsons) || properties.none { it }) return shared(*jsons) { xs -> textsOf(xs.map { textsOf(it, lengths != null) }, lengths, body) }
        // Within a body given CLOBs, a property's CLOB alone: its VARCHAR2 is not read. Its length, where it is asked for.
        val columns = jsons.withIndex().flatMap { (i, json) ->
            fun length() = listOfNotNull(lengths?.let { textLengthOf(json) })
            when {
                !properties[i] -> listOf(json)
                long -> listOf(textOf(json, clob = true)) + length()
                else -> listOf(textOf(json, clob = false), textOf(json, clob = true)) + length()
            }
        }
        return sharedBound(*columns.toTypedArray()) { names ->
            val next = names.iterator()
            val texts = jsons.indices.map { i ->
                fun length() = if (lengths != null) next.next() else "NULL"
                when {
                    !properties[i] -> textsOf(next.next(), lengths != null)
                    long -> next.next().let { whole -> Texts(whole, whole, length()) }
                    else -> Texts(next.next(), next.next(), length())
                }
            }
            textsOf(texts, lengths, body)
        }
    }

    /** A text as a `VARCHAR2`, null past 4,000 bytes, and as a CLOB; and its [length]: see [texts]. */
    private data class Texts(val short: String, val whole: String, val length: String)

    /** The JSON [json]'s texts, and where [length], its length: see [textOf] and [textLengthOf]. */
    private fun textsOf(json: String, length: Boolean) = Texts(textOf(json, clob = false), textOf(json, clob = true), if (length) textLengthOf(json) else "NULL")

    /** [body] over [texts] as `VARCHAR2`s, and where it gives null, as CLOBs, and then [lengths] over their lengths: see [texts]. */
    private fun textsOf(texts: List<Texts>, lengths: ((List<String>) -> String)?, body: (List<String>) -> String): String {
        val empty = lengths?.invoke(texts.map { it.length })
        // Within a body given CLOBs, as CLOBs alone.
        if (long) return body(texts.map { it.whole }).let { whole -> if (empty == null) whole else "COALESCE($whole, $empty)" }
        val read = body(texts.map { it.short })
        long = true
        val whole = try { body(texts.map { it.whole }) } finally { long = false }
        return "COALESCE(${listOfNotNull(read, whole, empty).joinToString(", ")})"
    }

    /** The JSON [json] as text, where it is a string: a CLOB where [clob], else as [text] reads it; a property by its own path (see [direct]). */
    private fun textOf(json: String, clob: Boolean) = direct(if (clob) longText(json) else text(json), json)

    /**
     * The length of the JSON [json], where it is a string, an empty one too, and of any length; a property by its own path
     * (see [direct]). Oracle counts it in UTF-8 bytes, not characters (a character past U+FFFF is 4), so only whether it
     * is 0 is read.
     */
    private fun textLengthOf(json: String) = direct(textLength(json), json)

    private fun textLength(json: String) = shared(json) { (x) -> "CASE WHEN ${kind(x)} = 'string' THEN JSON_VALUE($x, '\$.length()' RETURNING NUMBER) END" }

    /**
     * Any text starts with, ends with and holds the empty text, and the empty text no other, as in Cypher: Oracle's empty
     * text is null as a `VARCHAR2` and as a CLOB, so [search] gives null where either is empty, and the texts' lengths
     * answer there (#416). A text compared with a value of another kind is still null.
     */
    override fun searchedTexts(a: String, b: String, search: (List<String>) -> String) =
        texts(a, b, lengths = { (x, y) -> "CASE WHEN $y = 0 AND $x IS NOT NULL THEN TRUE WHEN $x = 0 AND $y IS NOT NULL THEN FALSE END" }, body = search)

    /** Whether [json] is a property as [propertyOf] reads it, short beside a long value: see [texts] and [sharedSum]. */
    private fun besideLong(json: String) = json.length <= SHARED_ABOVE && propertyRead(json) != null

    /**
     * A side of a `+` that is a property, beside a long value, is bound by the property's own path, as [texts] binds its
     * texts: its kind and its CLOB, each a column the body's reads of them are rewritten to, and the property as JSON only where it is no text past [BOUND_TEXT] characters. Bound as
     * JSON whole and read as a CLOB from that, a text past 32 KB closed the session on two rows or more (ORA-03113, #370);
     * left where it is read beside the subquery, as [direct] reads it, a sum of short texts closed it on one,
     * `a.id + '->' + b.id`. A text past [BOUND_TEXT] characters is summed with a text or a number from its CLOB, and is null
     * added to a list, which takes it as JSON: read as JSON beside the subquery, it closed the session.
     */
    override fun sharedSum(a: String, b: String, body: (List<String>) -> String): String {
        val sides = listOf(a, b)
        val properties = sides.map(::besideLong)
        if (!binds(a, b) || properties.none { it }) return shared(a, b, body = body)
        val columns = sides.withIndex().flatMap { (i, json) ->
            if (!properties[i]) return@flatMap listOf(json)
            val (props, path) = propertyRead(json)!!
            fun at(method: String) = "${path.dropLast(1)}$method'"
            listOf("CASE WHEN JSON_VALUE($props, ${at(".length()")} RETURNING NUMBER) > $BOUND_TEXT THEN NULL ELSE $json END", "JSON_VALUE($props, ${at(".type()")})", "JSON_VALUE($props, $path RETURNING CLOB)")
        }
        return sharedBound(*columns.toTypedArray()) { names ->
            val next = names.iterator()
            val bound = properties.map { property -> if (property) listOf(next.next(), next.next(), next.next()) else listOf(next.next()) }
            // A property's kind and CLOB are read from their own columns: its JSON is null past BOUND_TEXT characters.
            bound.filter { it.size == 3 }.fold(body(bound.map { it.first() })) { sql, (json, kindColumn, clob) ->
                sql.replace(kind(json), kindColumn).replace("JSON_VALUE($json, '\$' RETURNING CLOB)", clob)
            }
        }
    }

    /**
     * The sides of a comparison, an `IN` among them, bound as a sum's are (see [sharedSum]), and each property read by its own
     * path where it is not bound (see [direct]): [jsonEquals] and [ordered] read a text as a `VARCHAR2` or a CLOB, and not as
     * JSON, which Oracle refuses to compare past about 32 KB (ORA-40180, #389). Taken out as JSON and compared, a stored text
     * past 32 KB, on two rows or more, closed the session on the first run or a later one (ORA-03113), as #255's did.
     *
     * Not in a statement that writes, nor in the step of a `reduce()`, where two values are compared as JSON, as before (see
     * [asJson]).
     */
    override fun sharedCompared(a: String, b: String, body: (List<String>) -> String): String {
        if (asJson) return shared(a, b, body = body)
        if (propertyRead(a) != null || propertyRead(b) != null) readsCompared = true
        return sharedSum(a, b) { sides -> sides.fold(body(sides)) { sql, side -> direct(sql, side) } }
    }

    /**
     * Whether a comparison reads a property by its own path (see [sharedCompared]): the statement is then not rewritten by
     * Oracle's JSON table transformation (see [planned]), which read the reads of one node's properties together, and lost
     * them where a quantifier's subquery, bound as a comparison's side, read them: `all(x IN [1] WHERE p.born > 1962) = true`
     * was null for each person (the spec's "Quantifiers of null"), and right with the hint, or with the property taken out
     * as JSON first.
     */
    private var readsCompared = false

    /**
     * Whether two values are compared as JSON, as [jsonEquals] and [ordered] compared them before #389: in a statement that
     * writes and in the step of a `reduce()`, which run as PL/SQL. There a CLOB read from an unwound row's JSON closed the
     * session (ORA-03113), where the JSON was refused (ORA-40180) and the session stayed open: `UNWIND $items AS t MERGE (n {a:
     * t.a})` with a text past 32 KB, `CypherWriteContract`'s "a text past 32 KB from an unwound parameter…". So a text past
     * 32 KB is refused there, as before.
     */
    private val asJson get() = procedural || folding

    /**
     * As JSON, but where either is a text past 4,000 bytes, or empty, which a `VARCHAR2` doesn't hold: then two texts as
     * [textOrder] compares them, and a text is equal to no value of another kind, and is compared with SQL's null, a property
     * that isn't there, as null. `=` of JSON refuses a value past about 32 KB,
     * a text among them, whatever the other is (ORA-40180, #389): `n.bio = 'abc'` failed where one node's bio was so long. Each
     * is read as a `VARCHAR2` first, and its kind only where that is null. A list or a map that holds a long text is compared as
     * JSON, and refused. Each side is read more than once, so a long one is bound, as [sharedCompared] binds it.
     *
     * Beside a value the statement writes (see [written]), as beside one it is given (see [givenEquals]), only the other side is
     * tested, and two such values are compared as JSON: tested on both sides, `list = list` of a few hundred literal items,
     * compared item by item, was about four times the SQL it had been (#476).
     *
     * Neither holds NaN, which is equal to nothing (#409): Oracle's JSON finds a `BINARY_DOUBLE` NaN equal to itself. Two values
     * that are equal as JSON hold NaN only where both do, so one side is tested, and neither beside a literal, which holds none
     * (see [nanFree]).
     */
    override fun jsonEquals(x: String, y: String) = jsonEquals(x, y, oneNaNFree = false)

    /** [jsonEquals], where [oneNaNFree] says one side holds no NaN, and so neither is tested for it. */
    private fun jsonEquals(x: String, y: String, oneNaNFree: Boolean): String {
        fun guarded(equal: String, json: String) = if (oneNaNFree) equal else notNaN(equal, json)
        if (asJson || written(x) && written(y)) {
            if (oneNaNFree || nanFree(x) || nanFree(y)) return "$x = $y"
            return sharedCompared(x, y) { (a, b) -> guarded("$a = $b", a) }
        }
        if (written(x) || written(y)) {
            val (other, value) = if (written(y)) x to y else y to x
            if (writtenScalar(value) == false) return sharedCompared(other, value) { (a, b) -> if (nanFree(value)) oneSided(a, b) else guarded(oneSided(a, b), b) }
            // Beside a value of one kind, a list or a map is not equal, and isn't compared as JSON, which refuses one that holds a
            // text past 32 KB.
            return sharedCompared(other, value) { (a, b) -> oneSided(a, b, containers = false) }
        }
        return sharedCompared(x, y) { (a, b) ->
            guarded("CASE WHEN ${short(a)} IS NOT NULL AND ${short(b)} IS NOT NULL THEN $a = $b " +
                "WHEN ${kind(a)} = 'string' AND ${kind(b)} = 'string' THEN ${clobOrder(a, "=", b)} " +
                "WHEN ${kind(a)} IS NULL OR ${kind(b)} IS NULL THEN NULL WHEN ${kind(a)} = 'string' OR ${kind(b)} = 'string' THEN FALSE ELSE $a = $b END", a)
        }
    }

    /**
     * [jsonEquals], but where [given] is a number, a truth value, or a text of 1 to 4,000 bytes, which is read as a `VARCHAR2`:
     * then only the other side is tested (see [oneSided]), and beside a value the statement writes (see [written]), neither. A
     * property read from the store is not null as JSON, and so needs no test of its own. Where [given] is or holds NaN, as
     * [jsonEquals] (#409).
     */
    override fun givenEquals(x: String, y: String, given: Any): String {
        if (holdsNonFinite(given, Double::isNaN)) return jsonEquals(x, y)
        if (asJson) return "$x = $y"
        if (!readShort(given)) return jsonEquals(x, y)
        if (written(x)) return "$x = $y"
        return sharedCompared(x, y) { (a, b) -> oneSided(a, b) }
    }

    /**
     * Whether the JSON value [a] is equal to [b], a value that is no text, or a text read as a `VARCHAR2`: as JSON, where [a] is
     * read as a `VARCHAR2` too or is no text; else [a] is a text that is longer, or empty, and not equal.
     */
    private fun oneSided(a: String, b: String, containers: Boolean = true) = "CASE WHEN ${shortOrNoText(a, containers)} THEN $a = $b ELSE FALSE END"

    /**
     * Whether the JSON value [json] is read as a `VARCHAR2`, a text of 1 to [SHORT_TEXT] bytes, or is no text; SQL's null is no
     * text. Where not [containers], a list or a map doesn't pass either: beside a value of one kind it is not equal.
     */
    private fun shortOrNoText(json: String, containers: Boolean = true) =
        "${short(json)} IS NOT NULL OR NVL(${kind(json)}, 'null') ${if (containers) "<> 'string'" else "NOT IN ('string', 'array', 'object')"}"

    /**
     * Where each of [items] is null or read as a `VARCHAR2` (see [givenEquals]), whether a value is read as one too, or is no
     * text: then each item is compared with it as JSON, a plain equality, as before #389, and where it is a text that isn't,
     * it is longer than any of them, or empty, and in none. Tested once for the value, beside the `EXISTS` over the items, and
     * not in each item's comparison: over 20,000 rows `n.name IN ['n1', 'n2', 'n3']` took 8.8 ms so, against 14.4 ms with
     * the test in each item's comparison (#475). Not where an item is NaN, which a plain equality finds equal to itself: each
     * item is then compared as [jsonEquals] compares them, which finds NaN equal to nothing (#409).
     */
    override fun givenItemsTest(items: List<Any?>): ((String) -> String)? =
        if (asJson || !items.all { it == null || readShort(it) && !holdsNonFinite(it, Double::isNaN) }) null else { value -> "(${shortOrNoText(value)})" }

    /** Not in a statement that writes, nor in the step of a `reduce()`, which compare as before (see [asJson]). */
    override val givenItemsDecided get() = !asJson

    /**
     * As [jsonEquals], but where none of [items] is NaN, neither side is tested for it: the item holds none, and two values
     * equal as JSON hold NaN only where both do. Tested, a stored text past 32 KB was taken out as JSON (#475).
     */
    override fun givenItemEquals(item: String, value: String, items: List<Any?>) =
        jsonEquals(item, value, oneNaNFree = items.none { holdsNonFinite(it, Double::isNaN) })

    /** [equal], a comparison of [json] with a value, but false where [json] is or holds NaN, which is equal to nothing (#409). */
    private fun notNaN(equal: String, json: String) = "($equal AND NOT ${holdsNaN(json)})"

    /**
     * Whether [json] is a literal the statement writes ([literalJson]), or a list or a map it makes of them, which hold no NaN:
     * Cypher writes none, and a float literal past a double's range is refused. Tested for NaN, two list literals of a hundred
     * items, compared item by item, were half as long again (#476).
     */
    private fun nanFree(json: String): Boolean {
        if (!written(json) || json.contains("TO_BINARY_DOUBLE('NaN')")) return false
        // A list or a map the statement makes of literals alone: no word but its own and theirs outside a quoted text.
        val words = json.replace(QUOTED, "").split(WRITTEN_SEPARATORS)
        return words.all { it.isEmpty() || it in WRITTEN_WORDS }
    }

    /** Whether the value [given] is a number, a truth value or a text of 1 to [SHORT_TEXT] bytes, which `JSON_VALUE` reads as a `VARCHAR2`. */
    private fun readShort(given: Any) =
        given is Number || given is Boolean || given is String && given.isNotEmpty() && org.drivine.cypher.text.JavaText.utf8(given).size <= SHORT_TEXT

    /**
     * Whether [json] is a value the statement writes that is no text, or a text of 1 to [SHORT_TEXT] bytes, which `JSON_VALUE`
     * reads as a `VARCHAR2`: a literal as [literalJson] writes it, a float among them, or a list or a map the statement makes
     * ([jsonArray], [jsonObject]), which is never SQL's null, whatever it holds, and is compared as JSON, as before. Beside it
     * the other side alone is tested (see [jsonEquals]), as beside a value the statement is given.
     */
    private fun written(json: String) = writtenScalar(json) != null

    /** Whether [json], where it is [written], is a value of one kind (true) or a list or a map (false); null where it isn't written. */
    private fun writtenScalar(json: String): Boolean? {
        if (json.startsWith("JSON('") && json.endsWith("')")) {
            // One literal, its quotes doubled: not two, `JSON('1') = JSON('2')`.
            val text = json.substring(6, json.length - 2)
            if (!text.replace("''", "").contains('\'')) {
                val value = runCatching { CypherJson.readTree(text.replace("''", "'")) }.getOrNull() ?: return null
                return when {
                    value.isContainerNode -> false
                    !value.isTextual || readShort(value.textValue()!!) -> true
                    else -> null
                }
            }
        }
        // A list or a map, one call whose parentheses close only at its end.
        if ((json.startsWith("JSON_ARRAY(") || json.startsWith("JSON_OBJECT(")) && json.endsWith(")") && balanced(json.substring(json.indexOf('(') + 1, json.length - 1))) return false
        return if (WRITTEN_FLOAT.matches(json)) true else null
    }

    /**
     * Two texts, as [textOrder] orders them; any other two of one kind as JSON. JSON ordered a text past 32 KB, read from the
     * store on two rows or more, and closed the session on the first run or a later one (ORA-03113, #389). Its sides are
     * bound, and a property read by its own path, by [sharedCompared]. NaN, which JSON orders after every number, and which
     * Cypher orders with nothing, is false (#409).
     */
    override fun ordered(x: String, operator: String, y: String): String {
        val numbers = shared(x, y) { (a, b) -> "(NOT (${isNaN(a)} OR ${isNaN(b)}) AND $a $operator $b)" }
        return if (asJson) numbers else "CASE WHEN ${kind(x)} = 'string' THEN ${textOrder(x, operator, y)} ELSE $numbers END"
    }

    /**
     * Whether the JSON texts [x] and [y] stand as [operator] says, `=`, `<`, `>=`…: as `VARCHAR2`s where both are read so, which
     * compare by their bytes, so by their code points; else as CLOBs, by `DBMS_LOB.COMPARE`, which compares the whole of each,
     * a shorter one that starts the other first. An empty text, null as a CLOB, comes before any other. A CLOB is compared by
     * its UTF-16 units, so where either text is past 4,000 bytes, between two that first differ where one has a character past
     * U+FFFF and the other one from U+E000 to U+FFFF, the order is the other way round from Cypher's.
     */
    private fun textOrder(x: String, operator: String, y: String) =
        "CASE WHEN ${short(x)} IS NOT NULL AND ${short(y)} IS NOT NULL THEN ${short(x)} $operator ${short(y)} ELSE ${clobOrder(x, operator, y)} END"

    /**
     * Whether the JSON texts [x] and [y] stand as [operator] says, as CLOBs: see [textOrder]. Each is a CLOB by `TO_CLOB` too:
     * `JSON_VALUE(JSON('null'), '$' RETURNING CLOB)`, of a null a list writes, is a null of no type to `DBMS_LOB.COMPARE`, which
     * then matched more than one of its declarations (PLS-307).
     */
    private fun clobOrder(x: String, operator: String, y: String): String {
        val (cx, cy) = "TO_CLOB(JSON_VALUE($x, '\$' RETURNING CLOB))" to "TO_CLOB(JSON_VALUE($y, '\$' RETURNING CLOB))"
        return "NVL(DBMS_LOB.COMPARE($cx, $cy), SIGN(NVL(LENGTH($cx), 0) - NVL(LENGTH($cy), 0))) $operator 0"
    }

    /** The JSON [json] as a `VARCHAR2`, where it is a text of at most 4,000 bytes, a number or a truth value; else null. */
    private fun short(json: String) = "JSON_VALUE($json, '\$')"

    /** The properties and the path of [json] where it is a property as [propertyOf] reads it, `JSON_QUERY(props, '$.a' RETURNING JSON)`. */
    private fun propertyRead(json: String): Pair<String, String>? =
        PROPERTY_READ.matchEntire(json)?.takeIf { balanced(it.groupValues[1]) }?.let { it.groupValues[1] to it.groupValues[2] }

    /** Whether [sql]'s parentheses outside its literals close each other: so it is the one argument, not two calls. */
    private fun balanced(sql: String): Boolean {
        var depth = 0
        var quoted = false
        for (c in sql) {
            when {
                c == '\'' -> quoted = !quoted
                quoted -> {}
                c == '(' -> depth++
                c == ')' -> if (--depth < 0) return false
            }
        }
        return depth == 0 && !quoted
    }

    /** Whether the texts [texts] gives its body are CLOBs: see [scalar], [startsWith], [endsWith], [containsText], [split], [replaceText] and [reversedText], which read them otherwise. */
    private var long = false

    override fun boolOf(json: String) = shared(json) { (x) -> "CASE WHEN ${kind(x)} = 'boolean' THEN JSON_VALUE($x, '$' RETURNING BOOLEAN) END" }

    override fun float(sql: String) = "TO_BINARY_DOUBLE($sql)"

    override fun jsonArray(items: List<String>) =
        if (items.isEmpty()) "JSON('[]')" else "JSON_ARRAY(${items.joinToString(", ")} NULL ON NULL RETURNING JSON)"

    override fun jsonObject(entries: List<Pair<String, String>>) =
        if (entries.isEmpty()) "JSON('{}')"
        else "JSON_OBJECT(${entries.joinToString(", ") { (key, value) -> "${literal(key)} VALUE $value" }} NULL ON NULL RETURNING JSON)"

    override fun withEntries(json: String, entries: List<Pair<String, String>>) =
        if (entries.isEmpty()) json else "JSON_TRANSFORM($json, ${entries.joinToString(", ") { (key, value) -> "SET ${OracleSql.path(key)} = $value" }})"

    /**
     * The node's property read as the labelled index over its label holds it, [OracleSql.labelled], equal to the [value]'s
     * text, for each label the pattern names for it: Oracle had no such test, so even a parameter's value was compared only
     * as JSON, inside a `CASE`, which no index answers, and a node found by a property was found by reading every node of
     * the label (#617).
     */
    override fun indexedEquality(property: String, value: String, of: IndexedProperty?): String? =
        labelledEquals(of, "JSON_VALUE($value, '\$')")

    /** An empty text, and one past [SHORT_TEXT] bytes, which the index's `JSON_VALUE` reads as null: see [readShort]. */
    override fun indexHolds(given: Any) = readShort(given)

    /**
     * As [indexedEquality], for a value of any kind: `JSON_VALUE` gives a number's text as Oracle holds the number, so `1` and
     * `1.0` are the same text. A list has no text, and neither does the property a list is in the labelled index, which holds
     * none: it is let through, to be compared as JSON, so no list a node holds is left out (#617). Oracle reads no index
     * beside that `OR`, so where the value is known to be no list or map ([scalar]) it is left out.
     */
    override fun indexedEqualityOfAny(property: String, value: String, of: IndexedProperty?, scalar: Boolean): String? {
        if (labelledEquals(of, value) == null) return null
        if (scalar) return labelledEquals(of, "JSON_VALUE($value, '\$')")
        return shared(value) { (x) -> "(${labelledEquals(of, "JSON_VALUE($x, '\$')")} OR JSON_VALUE($x, '\$') IS NULL)" }
    }

    /**
     * [text] equal to the property [of] names, as each index a label of its node may have over it holds it: a range index's
     * labelled one, and a uniqueness constraint's over the one property, which stands for it and has it dropped. Both are
     * written, for each label [indexedProperties] says has an index over the property: on a node with the label and the
     * property they are the same value, and Oracle reads through whichever index is there. Null where it names no such label.
     */
    private fun labelledEquals(of: IndexedProperty?, text: String): String? {
        if (of == null || of.labels.isEmpty() || !of.props.endsWith(".props")) return null
        val alias = of.props.removeSuffix(".props")
        val indexed = of.labels.filter { (it to of.key) in indexedProperties }.ifEmpty { return null }
        return indexed.joinToString(" AND ") { label ->
            "${OracleSql.labelled(alias, label, of.key)} = $text AND ${OracleSql.unique(alias, label, listOf(of.key), of.key)} = $text"
        }
    }

    /**
     * A procedure's parameter is read from the text its parameters are bound as, each where it is read, and one that holds
     * a double as extended JSON (see [parameterText]) from its own text, as extended JSON. Oracle crashes the session
     * (ORA-07445 `ksmem_malloc`, then ORA-00600 `kghfrempty:ds`) where a statement run more than once reads a value past
     * 32 KB from JSON it parsed, a `JSON` variable or `JSON(… EXTENDED)` of all the parameters, and not from the text.
     */
    override fun propertyOf(json: String, key: String) =
        if (json == PARAMS && key in extendedParameters) "JSON(JSON_QUERY($PARAMS, ${OracleSql.path(key)} RETURNING CLOB) EXTENDED)"
        else "JSON_QUERY($json, ${OracleSql.path(key)} RETURNING JSON)"

    /**
     * A property returned or passed on by a `RETURN` or a `WITH`, read through its text: only the key is kept
     * (`JSON_TRANSFORM … KEEP`), written out as extended JSON, then read back from that text as extended JSON, so a float
     * that is a whole number stays a float.
     *
     * **A workaround for an Oracle server bug (#255), to be removed once Oracle fixes it.** Seen on Oracle 23.26.3
     * (gvenzl/oracle-free 23). Where a statement takes a string value past about 32 KB from a node's properties with
     * `JSON_QUERY(props, '$.a' RETURNING JSON)` over two or more rows, as the compiled SQL does and then feeds it on
     * (an outer `SELECT`, an `ORDER BY`, a `JSON_ARRAY`), the long value is written over the server process's heap and
     * the process dies: the client sees ORA-03113 (end-of-file on communication channel), and the alert log shows
     * ORA-00600 `[17114]` and `[kghfrh1]`, and ORA-07445 in `kghalo`, `kghsrch_new` or `kxsFreePages`, faulting at
     * `0x78787878787878`, the bytes of the string. One row doesn't crash it, nor 30,000 characters, nor a long list,
     * nor a map that holds the long string. A plain-SQL repro is in `docs/oracle-long-json-string-crash.md`.
     *
     * It costs about 2.4 to 3.8 times the plain read on short values: 100,000 rows of `RETURN n.name` took 493 ms against
     * 147 ms. Cheaper forms were tried and rejected: each crashed, read a CLOB column back as text (5.0 as 5), or, as a
     * `CASE` on `$.a.type()` or on the value's length that reads the properties twice, made Oracle drop the row's label
     * test and return nodes it had ruled out. Only an item that is the property itself is read so, and a property that is a
     * direct member of a list or a map an item is (`[n.i, n.a]`, `{a: n.a}`, `n {.a}`, `collect(n.a)`, `[x IN [n] | x.a]`): a
     * long text property read as such a member is routed so on Oracle, to avoid an Oracle server defect (#670). Any other
     * read of a stored value as JSON that may give a long text, beside a variable-length walk among them, takes the guarded
     * route the last pass, [finished], gives it, to avoid the same defect. Read so, a text past 32 KB still can't be sorted by, grouped by or made `DISTINCT`: Oracle refuses it
     * (ORA-40180), as it does any JSON that large, and the session stays open. A write's block is worked around too, where
     * it takes a text from the row it is writing: see [rowValue]; so is an unwound item's value an item gives: see [itemValue];
     * so is an item of a list read by its place: see [listItem]; and so is a property that is an argument of `coalesce()`:
     * see [coalescedProperty]; and so is a property of a value the statement works out, a node a function gives or a map: see
     * [valueProperty]; and so is a text `reverse()` gives, and the kind of a property it is given: see [reversedPieces] and
     * [reversedKind].
     *
     * To remove it, delete this override, so the item is read as [propertyOf] reads it, `JSON_QUERY … RETURNING JSON`,
     * once an Oracle release no longer crashes on the repro, and `CypherWriteOracleTest`'s "a property past 32 KB read on
     * two nodes" test still passes without it. `OracleItemPropertyTest`, which pins this SQL, goes with it.
     */
    override fun itemProperty(props: String, key: String) = keptText(props, OracleSql.path(key))

    /**
     * A property that is an argument of `coalesce()`, `coalesce(${'$'}p.q, n.a)`, by its kind, read by the property's own path: a
     * text of at most 4,000 bytes as a `VARCHAR2`, a longer one (and an empty one, null as a `VARCHAR2`) through its text as
     * [itemProperty] reads it, JSON's null as null, and anything else, or nothing, as JSON, as before.
     *
     * **A workaround for the Oracle bug of [itemProperty] (#459), to be removed with it.** Taken out as JSON, `CASE WHEN
     * JSON_VALUE(JSON_QUERY(n1.props, '$.a' RETURNING JSON), '$.type()') <> 'null' THEN JSON_QUERY(n1.props, '$.a' RETURNING
     * JSON) END`, a text past 32 KB closed the session over two rows or more (ORA-03113, and ORA-00600 `[kghfrempty:ds]` in
     * a write), on the first run of the statement or a later one: returned, passed on, measured, read by a text function,
     * of a node or of a relationship, beside a variable-length walk and in a write. A property of a map, or of a node a
     * function gives (`coalesce(m.a, …)`, `coalesce(head([n]).a, …)`), is read as [valueProperty] reads one, and one of an
     * unwound item as before. The `CASE` on the property's kind, which made Oracle drop a label test where an item read a
     * node's properties twice (see [itemProperty]), kept each one here, in a `WHERE` and beside another node's table
     * (`CypherQueryContract`'s "a text past 32 KB that coalesce() gives").
     * [itemProperty]'s form alone cost about twice the read it replaced on short texts; a short text read as a `VARCHAR2`
     * costs none: 20,000 rows of `RETURN coalesce(${'$'}p.q, n.name)` took 72 ms either way, and of `RETURN
     * toLower(trim(coalesce(${'$'}p.q, n.name)))` 93 ms against 109 ms.
     *
     * To remove it, delete this override with [itemProperty]'s, once an Oracle release no longer crashes on the repro in
     * `docs/oracle-long-json-string-crash.md`, and `CypherQueryOracleTest`'s "a text past 32 KB that coalesce() gives"
     * test still passes without it.
     */
    override fun coalescedProperty(props: String, key: String) = shared(props) { (p) ->
        val path = OracleSql.path(key)
        "CASE JSON_VALUE($p, ${OracleSql.path(key, ".type()")}) WHEN 'string' THEN COALESCE(JSON_SCALAR(JSON_VALUE($p, $path)), ${itemProperty(p, key)}) " +
            "WHEN 'null' THEN NULL ELSE JSON_QUERY($p, $path RETURNING JSON) END"
    }

    /**
     * A property of a value the statement works out, a node or a relationship a function or an index gives (`head([n]).a`,
     * `[n][0].a`, `last(ns).a`) or a map (`properties(n).a`, `head([properties(n)]).a`): as JSON, as before, by a path whose
     * filter leaves out a text past 4,000 characters, `'$.a?(@.type() != "string" || @.length() <= 4000)'`, and such a text
     * through its text, as [itemProperty] reads it. Where the value is long, it is bound in a subquery, and the property read
     * there by its own path.
     *
     * **A workaround for the Oracle bug of [itemProperty] (#531), to be removed with it.** Taken out as JSON, `JSON_QUERY(value,
     * '$.a' RETURNING JSON)`, a text past 32 KB closed the session over two rows or more (ORA-03113, or ORA-00600 `[17114]`),
     * on the first run of the statement or a later one: returned, passed on, measured, searched, compared, in a `WHERE` and
     * in an `ORDER BY`. A property of an unwound item, which `JSON_TABLE` takes out, is read as before, and so is a key of a
     * map that is itself a property (`n.m.a`), which [direct] reads from the map where a text function, a measure or a
     * comparison reads it, and elsewhere by its whole path from the properties, as [finished] routes it, as it routes a map
     * made of a long property and then read (`head([n {.a}]).a`), and `=` of a long property with a value that may hold a null
     * (`head([n]).a = n.a`) (#533, #534, #536). Read by its kind, as
     * [coalescedProperty] reads one, a property of a map a `WITH` passes on cost about 1.5 times the plain read on short
     * texts, as the map was worked out again for each of the reads; read so, 20,000 rows of `WITH properties(n) AS m RETURN
     * m.t` take as long as before.
     *
     * To remove it, delete this override with [itemProperty]'s, once an Oracle release no longer crashes on the repro in
     * `docs/oracle-long-json-string-crash.md`, and `CypherQueryOracleTest`'s "a text past 32 KB that is a property of a node,
     * a relationship or a map a function gives" test still passes without it.
     */
    override fun valueProperty(json: String, key: String) =
        if (propertyRead(json) != null) propertyOf(json, key)
        else shared(json) { (x) ->
            val short = OracleSql.path(key, "?(@.type() != \"string\" || @.length() <= $SHORT_TEXT)")
            "COALESCE(JSON_QUERY($x, $short RETURNING JSON), ${itemProperty(x, key)})"
        }

    /**
     * A value that a write takes from the row it is writing, to work out what it writes (`SET n.a = t.a`, `CREATE (:X {a:
     * t.a})`, `MERGE … SET n.a = t.a`, where `t` is an item of an unwound parameter): a text read with one path from the
     * row written out as text, anything else with that path from the row as JSON, as before.
     *
     * **A workaround for the Oracle bug of [itemProperty] (#309), to be removed with it.** The block crashed the session where
     * it took a text past 32 KB from the row, a `JSON` variable, with `JSON_QUERY(JSON_QUERY(cy_row, '$.v1' RETURNING JSON),
     * '$.a' RETURNING JSON)`, for each of two or more rows. Read as [itemProperty] reads a property, through
     * `JSON_TRANSFORM(cy_row, KEEP …)`, it crashed too; written out whole, `JSON_SERIALIZE(cy_row …)`, it didn't. A text is
     * read from text that isn't extended JSON, which has no float to keep; anything else is read as JSON, as before, so a map
     * of the row with a key as extended JSON's are (`{"${'$'}numberLong": "5"}`) stays a map. A list or a map that holds a long
     * text is so read as JSON, which didn't crash: `SET n += t` sets such a map whole. The `CASE` on the value's type, which
     * in a read made Oracle drop a label test (see [itemProperty]), doesn't here: it reads the row variable, one value for the
     * whole statement, after the rows were matched, and a labelled pattern in a subquery of that statement keeps its test
     * (`CypherWriteContract`'s "an unwound item's text … written only to the nodes a labelled pattern matches").
     *
     * To remove it, delete this override, so the value is read as [propertyOf] reads each key, once an Oracle release no
     * longer crashes on the repro in `docs/oracle-long-json-string-crash.md`, and `CypherWriteOracleTest`'s "a text past
     * 32 KB set from an unwound parameter" test still passes without it.
     */
    override fun rowValue(keys: List<String>) = throughText(ROW, keys)

    /**
     * A value of an item of an unwound list, or of a row a write carries on, that a `RETURN` or a `WITH` returns or passes on
     * (`UNWIND ${'$'}items AS t RETURN t.a`, `WITH t.a AS a`, `SET n.a = t.a RETURN t.a`): read as [rowValue] reads one, a text
     * from the item written out as text, anything else from the item as JSON, as before.
     *
     * **A workaround for the Oracle bug of [itemProperty] (#322), to be removed with it.** Such a statement crashed the session
     * where it took a text past 32 KB from the item, `JSON_QUERY(u1.item, '$.a' RETURNING JSON)`, for each of two or more rows.
     * [itemProperty]'s form, and the item written out whole as extended JSON, ran clean, but read a map with a key as extended
     * JSON's are (`{"${'$'}numberLong": "5"}`) as the number it writes. The `CASE` on the value's type, which made Oracle drop a
     * label test where it read a node's properties twice (see [itemProperty]), reads the item here, a column of the list it
     * is unwound from, and the labelled patterns beside it kept their tests (`CypherWriteContract`'s "an unwound item's text
     * … returned only for the nodes a labelled pattern matches"). So only a value read from such an item is read so (see
     * [Value.Json.fromList]); a map made of a node's properties and not unwound (`WITH n {.a} AS m`, `properties(n)`) is read as [valueProperty] reads one. It cost about
     * 1.3 times the plain read on short values: 100,000 rows of `UNWIND ${'$'}items AS t RETURN t.a` took 860 to 880 ms against
     * 650 to 670 ms.
     *
     * A pattern that matches on such a text (`MERGE (n:X {a: t.a})`) still compares it as JSON: Oracle refuses that
     * (ORA-40180), as it refuses to sort or group a text past 32 KB, and the session stays open. To remove the workaround,
     * delete this override with [itemProperty]'s, once an Oracle release no longer crashes on the repro in
     * `docs/oracle-long-json-string-crash.md`, and `CypherWriteOracleTest`'s "a text past 32 KB from an unwound parameter
     * returned or passed on" test still passes without it.
     */
    override fun itemValue(json: String, key: String) = throughText(json, listOf(key))

    /** The value at [keys] in the JSON [json]: a text read with one path from [json] written out as text, anything else as JSON. */
    private fun throughText(json: String, keys: List<String>) = shared(json) { (x) -> throughText(x, OracleSql.path(keys), OracleSql.path(keys, ".type()")) }

    /**
     * The item at the path [path] of the JSON list [json], `'$[0]'`, read as [throughText] reads a value: a text from the list
     * written out as text, anything else as JSON, as before.
     *
     * **A workaround for the Oracle bug of [itemProperty] (#388), to be removed with it.** A statement crashed the session where
     * it took a text past 32 KB from a list as JSON, `JSON_QUERY(list, '$[$i]' RETURNING JSON)`, for each of two or more rows,
     * from the second run on: an item of a list `split` made, read by index, by `head` or `last`, or after a slice, of a stored
     * list, and of one a `WITH` passed on. Its text read straight from the list by its path, `JSON_VALUE(list, '$[0]'
     * RETURNING CLOB)`, ran clean too, but a value the item is read as whole, returned or passed on, needs it as JSON. An
     * item is read as text only where it is a text: the list is written out whole for it, so a short text of a long list
     * costs that list's length. To remove it, read the item with `JSON_QUERY(… RETURNING JSON)` again, once an Oracle release
     * no longer crashes on the repro in `docs/oracle-long-json-string-crash.md`, and `CypherQueryOracleTest`'s "a text past
     * 32 KB that is an item of a list" test still passes without it.
     */
    private fun listItem(json: String, path: String, passing: String = "") = throughText(json, path, "${path.dropLast(1)}.type()'", passing)

    /**
     * A value in a parameter that is a map, `${'$'}p.text`, read with one path from the text the parameter is bound as, and
     * not from the parameter read as JSON first: Oracle crashed the session (#256) where a statement read a value from a
     * parameter past 32 KB, taken as JSON, for each of two rows. Read as extended JSON where the parameter is (see
     * [parameterText]) and the value holds such a double.
     */
    override val parameterPath = { marker: String, text: String?, keys: List<String>, value: Any? ->
        val readsExtended = if (marker == PARAMS) keys.first() in extendedParameters else text != null && extended.any { it === text }
        val path = OracleSql.path(keys)
        if (readsExtended && doubled(value)) "JSON(JSON_QUERY($marker, $path RETURNING CLOB) EXTENDED)" else "JSON_QUERY($marker, $path RETURNING JSON)"
    }

    /** Whether [value] holds a float that is a whole number, as extended JSON's double: see [doubles]. */
    private fun doubled(value: Any?): Boolean {
        var doubled = false
        doubles(CypherJson.valueToTree(value, encoder)) { doubled = true }
        return doubled
    }

    /** Counted from the end where negative: a JSON path counts from the start. Read as [listItem] reads an item. */
    override fun element(json: String, index: String) = shared(json, index) { (list, i) ->
        val at = "JSON_VALUE($i, '$' RETURNING NUMBER)"
        listItem(list, "'\$[\$i]'", " PASSING CASE WHEN $at < 0 THEN JSON_VALUE($list, '\$.size()' RETURNING NUMBER) + $at ELSE $at END AS \"i\"")
    }

    /**
     * The entry of a map, a node or a relationship at a key an expression gives (`m[k]`, `n['nam' + 'e']`, `r[key]`): a JSON
     * path names a key only as it is written, so the map's entries are taken as `keyvalue()` gives them, a list of `{"key":
     * …, "value": …}`, and the entry whose key is [key], passed in as the variable `k`, read from it by
     * `'$[*]?(@.key == $k).value'`. Matched so, a key is compared as the text it is, whatever it holds: a quote, a dot, a
     * `$`, a space, a bracket, a backslash or any other character. A key the map doesn't hold is null, as on Neo4j. Only a
     * map's entries are taken, so a list read by a text is null, as on Postgres, and not each of its items' entries, as a lax
     * path reads them (drivine4j#417). Oracle (23.26) reads nothing by a filter after `keyvalue()` in the same path, nor any
     * item method after it: hence the list, and the filter over it.
     *
     * The entry is read as [listItem] reads an item, a text from the list written out as text, anything else as JSON: a text
     * past 32 KB taken out as JSON, for two rows or more, crashes the session (see [itemProperty]).
     *
     * Each read lists the map's entries again, and a text writes that list out whole, so a read costs the map's size, not
     * the entry's: 20,000 rows of `WITH n, 'name' AS k RETURN n[k]` took 474 ms against 127 ms for `n.name`; over 2,000
     * nodes of 50 keys `[k IN keys(n) | n[k]]` took 16 s against 35 ms for `keys(n)`, and a node of 1,000 keys read at each
     * took 33 s. A short text first read as a `VARCHAR2` saved about a quarter of that and cost as much on a number, so it was
     * left out. The cost is drivine4j#702.
     */
    override fun keyed(json: String, key: String): String {
        val entries = "JSON_QUERY($json, '\$.keyvalue()' RETURNING JSON WITH ARRAY WRAPPER)"
        val entry = shared(entries) { (e) -> listItem(e, KEYED, " PASSING $key AS \"k\"") }
        return "CASE WHEN ${kind(json)} = 'object' THEN $entry END"
    }

    /** A merge of [map] over [props] that changes nothing: each of its entries is there, with an equal value. */
    override fun containsAll(props: String, map: String) = shared(props) { (p) -> "JSON_EQUAL(JSON_MERGEPATCH($p, $map RETURNING JSON), $p)" }

    override fun hasLabel(labels: String, label: String) = "JSON_EXISTS($labels, ${literal("\$[*]?(@ == ${OracleSql.quoted(label)})")})"

    /**
     * The first label as the index over labels answers it, and the rest by the labels' text: when two tests
     * of the labels are answered by that index, Oracle (23.26) finds no node the open transaction has written
     * and not yet committed.
     */
    override fun hasLabels(labels: String, all: List<String>) =
        (listOf(hasLabel(labels, all.first())) + all.drop(1).map { OracleSql.holdsLabel(labels, it) }).joinToString(" AND ")

    /**
     * The keys compared with `+ 0` on one side, and again the other way round, for a node equal to a node and for the test
     * beside `id(a) = id(b)`. Compared column to column, Oracle (23.26) rewrites the join and finds no row, in two ways. Where
     * a node is joined to a relationship's end by the edge table's foreign key, it drops that node from the join, and still reads the other node by
     * the key of the node it dropped: `MATCH (p)-[:KNOWS]->(q) MATCH (c:Person) WHERE id(c) = id(q)` gave none (#575). And it
     * drops the later node's join on the primary key equal to a primary key, moving that node's label test onto the other
     * node as the multivalue index's filter, row by row, which misses a node with more than one label:
     * `MATCH (p)-[:KNOWS]->(q) MATCH (c:Person) WHERE c = q` gave none (#576). A key equal to an expression, not to a column,
     * is not carried across the join and can't eliminate it, and the bare key is still read by its index. Both ways round, so
     * either node is read by its key, whichever Oracle reads first: with `+ 0` on [b] alone, `MATCH (a) WHERE id(a) = $id
     * MATCH (b) WHERE a = b` read every node for `b`.
     */
    override fun sameKey(a: String, b: String) = "($a = $b + 0 AND $b = $a + 0)"

    /**
     * The key with `+ 0`, as [sameKey] has it: Oracle (23.26) unnests `nid IN (SELECT n1.nid FROM <nodes> n1 WHERE <label test>)`
     * into the table itself, the label test answered by the multivalue index, and finds no row, so `MATCH (g:Gone) DETACH
     * DELETE g` deleted nothing, and a `COUNT(*)` of the same found none (#587). The key of an expression is not rewritten so.
     */
    override fun inKey(key: String) = "$key + 0"

    /**
     * Each label by the labels' text ([OracleSql.holdsLabel]), the form for a node found some other way, which the index over
     * labels can't answer. With the first in the index's form ([hasLabels]), Oracle (23.26) read that index again for each row
     * of the node the key is equal to, and filtered it on the key, rather than read the one row by the primary key, whichever
     * way round [sameKey] writes the comparison: `MATCH (a:Big) MATCH (b:Big) WHERE a = b` took 406 ms over 2,000 nodes and
     * 1,537 ms over 4,000, and about 2 ms and 3 ms in this form (#578). A hint naming the key's index did as well, but names
     * the node's alias in the query block that reads it, which a statement's subqueries and `WITH`s move. The same index read
     * once for each id had `UNWIND $ids AS i MATCH (n:L) WHERE id(n) = i` take 183 ms for 100 ids over 8,000 nodes (#580), and
     * once for each edge had `MATCH (x:A)-[:R]->(y:B)` take 1.3 s over 8,000 edges (#581): 1.6 ms and 7 ms in this form.
     *
     * Each test so written is noted ([keyed]), and a query that holds one is planned without statistics feedback (see
     * [planned]).
     */
    override fun keyedLabels(labels: String, all: List<String>) =
        all.joinToString(" AND ") { OracleSql.holdsLabel(labels, it) }.also { keyed += it }

    /**
     * [FEW_ROWS] rows for the `JSON_TABLE` [alias]: Oracle (23.26) estimates one over a value given at 8,168, and so read every
     * node once and hashed it to a list of 100 ids, rather than read each node by its key: 12 ms over 8,000 nodes, and 1.6 ms
     * with this hint, 2.6 ms for 500 ids (#580). It is an estimate, not a limit: a longer list is read whole, each id by the
     * key, which takes time linear in the ids.
     */
    override fun fewRows(alias: String) = "CARDINALITY($alias $FEW_ROWS)"

    /**
     * Each label by the labels' text ([OracleSql.holdsLabel]), which the index over labels can't answer, for a node a `MATCH`
     * finds after a `WITH` that groups its rows. Answered by that index, Oracle (23.26) sometimes crashed the session building
     * the cursor (ORA-00600 `[QJSNMV_GET_MVI_MD:2]`, ORA-03113 to the client), after planning, as it put the label test it had
     * rewritten onto the index's column back as it was: `MATCH (p:Person)-[r:ACTED_IN]->() WITH p, collect(DISTINCT [r]) AS l,
     * count(DISTINCT [r]) AS n MATCH (p)-[r2:ACTED_IN]->(:Movie {title: 'The Matrix'}) RETURN …` (#601, #320,
     * liberation-data/drivine#620); after a rolled-back delete of nodes of the label it crashed in the same index as a
     * `DELETE`'s read did (ORA-07445 `[qjsnmvFindOpt()+16]`, #597). The nodes before the `WITH` keep the index's form. Such a
     * node is mostly found from a node the `WITH` passed on, by its key, so only a node found by its label and nothing else is
     * read without the index.
     */
    override fun groupedLabels(labels: String, all: List<String>) = all.joinToString(" AND ") { OracleSql.holdsLabel(labels, it) }

    /**
     * Each test of a node's labels [keyedLabels] wrote. Kept as text, and looked for in the query [planned] is given, so a
     * trial compile that writes one and is thrown away, or a step of a procedure that holds none, is planned as before.
     */
    private val keyed = mutableSetOf<String>()

    /** A number, as the key's `NUMBER`; anything else, or a number past one, null. */
    override fun keyValue(json: String) = "JSON_VALUE($json, '\$' RETURNING NUMBER NULL ON ERROR)"

    /**
     * A list that holds a subquery is read from a lateral view first, [listView]: a subquery in `JSON_TABLE`'s argument
     * reads no table beside it in the `FROM`.
     *
     * The rows of a `FOREACH` or a `CALL { … }` written for each row alone ([rowAlone]) are noted ([oneRow]), and the query
     * that reads them is planned for one row: see [oneRowPlanned].
     */
    override fun elements(json: String, alias: String): String {
        val items = "COLUMNS (item_no FOR ORDINALITY, item JSON PATH '\$')) $alias"
        if (json == ROWS && rowAlone > 0) oneRow += alias
        if (!json.contains("SELECT", ignoreCase = true)) return "JSON_TABLE($json, '\$[*]' $items"
        return "${listView(json, alias, hint = "")} ${alias}_l CROSS JOIN JSON_TABLE(${alias}_l.list, '\$[*]' $items"
    }

    /**
     * A lateral view of one column, `list`, the list [json] that holds a subquery, worked out in a derived table inside it.
     * Oracle 23 works out a subquery that reads the row outside it once, and not for each row, where it is in the lateral
     * view's own select list and that view is first in the `FROM` of a subquery: `WHERE 'b' IN keys(n)` then found nothing
     * (#433). Worked out in a derived table, it is worked out for each row. A derived table alone reads no table beside it
     * in the `FROM`, as `UNWIND` puts the list, so the lateral view stays around it. [hint] goes in both select lists.
     */
    private fun listView(json: String, alias: String, hint: String) =
        "LATERAL (SELECT $hint${alias}_v.list FROM (SELECT $hint$json AS list FROM DUAL) ${alias}_v)"

    /**
     * Each item read by its place, from as many rows as the list has items, as [range] makes them: Oracle 23 takes many
     * seconds, or minutes, to parse a `JSON_TABLE` over a list of temporal values worked out with temporal arithmetic, item
     * by item in a comprehension (#331), and neither a query timeout nor a kill ends the parse. `JSON_QUERY` reads the same
     * list in moments, at about ten times the cost of `JSON_TABLE` for a short list, so a list of other values is read by
     * [elements]. The list is worked out once, in a lateral view of its own ([listView] where it holds a subquery), and its
     * places counted in another: where they are counted over the list's own view, or the list is written out again where it
     * is read, Oracle reads an `EXISTS` over its items with the wrong row's columns. It reads as `JSON_TABLE`'s `$[*]` does:
     * no rows for null, and one for a value that is no list.
     */
    override fun temporalElements(json: String, alias: String): String {
        val (list, place) = "${alias}_l" to "${alias}_r"
        val size = "JSON_VALUE($list.list, '\$.size()' RETURNING NUMBER)"
        val view = if (json.contains("SELECT", ignoreCase = true)) listView(json, alias, hint = "/*+ NO_MERGE */ ") else "LATERAL (SELECT /*+ NO_MERGE */ $json AS list FROM DUAL)"
        return "$view $list CROSS JOIN " +
            "LATERAL (SELECT $place.n AS item_no, JSON_QUERY($list.list, '\$[\$i]' PASSING $place.n - 1 AS \"i\" RETURNING JSON) AS item " +
            "FROM (SELECT LEVEL n FROM DUAL CONNECT BY LEVEL <= GREATEST(NVL($size, 0), 1)) $place WHERE $place.n <= $size) $alias"
    }

    override fun aggregateList(value: String, order: String?) =
        "COALESCE(JSON_ARRAYAGG($value${order?.let { " ORDER BY $it" }.orEmpty()} NULL ON NULL RETURNING JSON), JSON('[]'))"

    /** `JSON_ARRAYAGG` leaves nulls out. */
    override fun collect(value: String, order: String?) =
        "COALESCE(JSON_ARRAYAGG($value${order?.let { " ORDER BY $it" }.orEmpty()} RETURNING JSON), JSON('[]'))"

    /** At most 4,000 bytes, as a `VARCHAR2`: the rest is left off, where it would fail the statement. */
    override fun joinedTexts(text: String, order: String) = "LISTAGG($text ON OVERFLOW TRUNCATE '' WITHOUT COUNT) WITHIN GROUP (ORDER BY $order)"

    /**
     * Each item where it is first among those equal to it as `JSON` compares them, kept as it is: not by its text, which is
     * at most 4,000 bytes and keeps a map's keys in the order written, and not read back from it, which would read a float
     * that is a whole number, `2.0`, as the number 2. Each that is NaN, equal to nothing, is kept ([bareNaN]).
     */
    override fun distinctItems(list: String, temporal: Boolean) =
        "(SELECT COALESCE(JSON_ARRAYAGG(d.item ORDER BY d.item_no RETURNING JSON), JSON('[]')) " +
            "FROM (SELECT e.item_no, e.item, ROW_NUMBER() OVER (PARTITION BY e.item${bareNaN("e.item")?.let { ", CASE WHEN $it THEN e.item_no END" }.orEmpty()} " +
            "ORDER BY e.item_no) AS n FROM ${elements(list, "e", temporal)}) d WHERE d.n = 1)"

    override fun firstOfEach(list: String, identities: String) =
        "(SELECT ${aggregateList("v.item", "v.item_no")} FROM ${elements(list, "v")} JOIN (SELECT d.item_no FROM (SELECT e.item_no, " +
            "ROW_NUMBER() OVER (PARTITION BY e.item${bareNaN("e.item")?.let { ", CASE WHEN $it THEN e.item_no END" }.orEmpty()} ORDER BY e.item_no) AS n " +
            "FROM ${elements(identities, "e")}) d WHERE d.n = 1) f ON f.item_no = v.item_no)"

    /** Of the values of the kind that comes first, or last, as [kindOrder] orders them; by [key] first where there is one. */
    override fun extreme(value: String, min: Boolean, key: String?): String {
        val direction = if (min) "ASC" else "DESC"
        return "${if (min) "MIN" else "MAX"}(${nullIfJsonNull(value)}) KEEP (DENSE_RANK FIRST ORDER BY ${kindOrder(value)} $direction NULLS LAST${key?.let { ", $it $direction NULLS LAST" }.orEmpty()})"
    }

    /**
     * A float, `BINARY_DOUBLE`, where any value summed is one, a double or a number with a fraction, as in Cypher: `NUMBER`
     * keeps no such kind, so a sum of floats that is whole, 0.5 + 1.0 + 0.5, would be the integer 2. Floats are summed as
     * doubles, as Neo4j sums them: as `NUMBER`s, 1e300 would be dropped, and 0.1 + 0.2 would be 0.3. Nothing is bound under
     * an aggregate, so each value is written out where it is read: each read is one path that tests the value's kind
     * itself, a text not summed and a number with a fraction written with a point, or with a negative exponent, `1E-41`,
     * as Oracle writes one of more than 40 digits, so a sum reads it four times, once more than [number] alone does. A
     * path compares no item to another, `@ != @.floor()` failing with ORA-40597, so a fraction is told by how it is
     * written. Integers are summed as `NUMBER`s, exactly, and as doubles where their sum is past a long's range: see
     * [passesLong].
     */
    override fun sum(json: String, distinct: String): String {
        fun summed(type: String) = "JSON_VALUE($json, '\$?(@.type() != \"string\")' RETURNING $type)"
        val fraction = "@.string() has substring \".\" || @.string() has substring \"E-\""
        val float = "MAX(JSON_VALUE($json, '\$?(@.type() == \"double\" || (@.type() == \"number\" && ($fraction)))')) IS NOT NULL"
        return "CASE WHEN $float OR ${passesLong(summed("NUMBER"), distinct)} THEN JSON_SCALAR(sum($distinct${summed("BINARY_DOUBLE")})) " +
            "ELSE JSON_SCALAR(COALESCE(sum($distinct${summed("NUMBER")}), 0)) END"
    }

    /**
     * In `BINARY_DOUBLE` where the group holds a double, as [sum] adds it: NaN and the infinities are doubles, which [number] reads
     * as no `NUMBER`, so the average would pass them by (#295).
     */
    override fun avg(json: String, distinct: String): String {
        val double = "MAX(JSON_VALUE($json, '\$?(@.type() == \"double\")')) IS NOT NULL"
        return "CASE WHEN $double THEN JSON_SCALAR(avg(${distinct}JSON_VALUE($json, '\$?(@.type() != \"string\")' RETURNING BINARY_DOUBLE))) " +
            "ELSE ${super.avg(json, distinct)} END"
    }

    /** Oracle's floats hold NaN and the infinities, so it compiles no statement as given one: wherever one may be worked out. */
    override fun foldsWrittenDivisor() = nanWorkedOut

    /**
     * In `BINARY_DOUBLE` where either is one, so a float stays a float, as in Cypher: `NUMBER` keeps no such kind. Each is read as
     * a `BINARY_DOUBLE` there, which NaN and the infinities are, where [number] reads them as no `NUMBER`.
     *
     * A number divided by an integer zero fails, as Neo4j fails one at run time ("/ by zero"), where `BINARY_DOUBLE`'s division
     * gives a float NaN or an infinity (drivine4j#559): NaN and the infinities too. An integer divided by one fails already, as
     * `NUMBER`'s division does. Neo4j gives an infinity or NaN where it works a float and an integer zero written in the
     * statement out as it plans, as Java does (`1.0 / 0`), and fails on `pi() / 0`: the divisor of the first comes here a float
     * ([foldsWrittenDivisor], drivine4j#686). A divisor written as any other number isn't tested.
     */
    override fun arithmetic(a: String, b: String, operator: String): String {
        val result = "CASE WHEN ${kind(a)} = 'double' OR ${kind(b)} = 'double' " +
            "THEN JSON_SCALAR(${binaryDouble(a)} $operator ${binaryDouble(b)}) " +
            "ELSE JSON_SCALAR(${number(a)} $operator ${number(b)}) END"
        if (operator != "/") return result
        return when (writtenNumber(b)) {
            WrittenNumber.ZERO -> byZero(a, b)
            null -> "CASE WHEN ${kind(b)} = 'number' AND ${number(b)} = 0 THEN ${byZero(a, b)} ELSE $result END"
            else -> result
        }
    }

    /**
     * The JSON [json] as a `BINARY_DOUBLE`, where it is a number: a double, NaN and the infinities among them, as it is, and a
     * `NUMBER` made one. A value of no other kind is read so: a list so read beside a list closed the session.
     */
    private fun binaryDouble(json: String) = shared(json) { (x) ->
        "CASE ${kind(x)} WHEN 'double' THEN JSON_VALUE($x, '\$' RETURNING BINARY_DOUBLE) WHEN 'float' THEN JSON_VALUE($x, '\$' RETURNING BINARY_DOUBLE) ELSE TO_BINARY_DOUBLE(${number(x)}) END"
    }

    /** A side that is a property is read from the properties by its own path: see [direct]. */
    override fun add(a: String, b: String, given: Pair<Any?, Any?>) = direct(direct(
        "CASE WHEN ${typeOf(a)} = 'number' AND ${typeOf(b)} = 'number' THEN ${arithmetic(a, b, "+")} " +
            "WHEN ${kind(a)} = 'array' AND ${kind(b)} = 'array' THEN JSON_QUERY(JSON_ARRAY($a, $b RETURNING JSON), '\$[*][*]' RETURNING JSON WITH ARRAY WRAPPER) " +
            "WHEN ${kind(a)} = 'array' THEN JSON_TRANSFORM($a, APPEND '\$' = $b) " +
            "WHEN ${kind(b)} = 'array' THEN JSON_TRANSFORM($b, INSERT '\$[0]' = $a) " +
            "WHEN ${typeOf(a)} IN ('string', 'number') AND ${typeOf(b)} IN ('string', 'number') THEN ${textSum(a, b, given)} END",
        a), b)

    /**
     * [sql] with each value it reads out of the JSON [json] with `JSON_VALUE(json, '$…')` read from the properties by the
     * property's own path instead, where [json] is a property as [propertyOf] reads it, `JSON_QUERY(props, '$.a' RETURNING
     * JSON)`. Oracle closed the session (ORA-03113) on statements, one row each, that took a text past 32 KB out of a
     * node's properties so and read it as a CLOB or put it together with another, and a later statement in the session lost
     * its rows: the memory corruption of #255. A value used whole, as a list a sum joins, still takes the property out.
     * It costs about a quarter more on short texts: 100,000 rows of `size(n.name)` took 208 ms against 162 ms, and of
     * `toUpper(n.name)` 196 ms against 164 ms.
     */
    private fun direct(sql: String, json: String): String {
        val (props, path) = propertyRead(json) ?: return sql
        return sql.replace("JSON_VALUE($json, '\$", "JSON_VALUE($props, ${path.dropLast(1)}")
    }

    /**
     * Two texts put together, as `VARCHAR2`s where both are read so and their sum fits in one, 4,000 bytes; else as a CLOB
     * (#298), where a text past 4,000 bytes was left out as null, and a longer sum failed. Whether they fit is told from the
     * sides' own texts, short to read: a number may be written otherwise ([asText]), though in at most [NUMBER_TEXT] bytes.
     * A text the statement gives is as long as it is, and not read for each row.
     */
    private fun textSum(a: String, b: String, given: Pair<Any?, Any?>): String {
        val swapped = given.second to given.first
        fun bytes(json: String, given: Any?) = (given as? String)?.let(org.drivine.cypher.text.JavaText::utf8)?.size?.toString() ?: "LENGTHB(JSON_VALUE($json, '\$'))"
        val fits = "${bytes(a, given.first)} + ${bytes(b, given.second)} <= ${SHORT_TEXT - 2 * NUMBER_TEXT}"
        val short = "${summedText(a, given, short = true)} || ${summedText(b, swapped, short = true)}"
        val whole = "TO_CLOB(${summedText(a, given, short = false)}) || ${summedText(b, swapped, short = false)}"
        // Two empty texts are null together, as Oracle's empty text is, and not an empty CLOB.
        val empty = "JSON_VALUE($a, '\$.length()') = 0 AND JSON_VALUE($b, '\$.length()') = 0"
        return "CASE WHEN $fits THEN JSON_SCALAR($short) WHEN $empty THEN NULL ELSE JSON_SCALAR($whole) END"
    }

    /**
     * The side [json] of a `+` whose sides the statement gives as [given], this side first, as text: see [summedText]. A text
     * as a `VARCHAR2` where [short], null past 4,000 bytes, and else as a CLOB; a number's text is short.
     */
    private fun summedText(json: String, given: Pair<Any?, Any?>, short: Boolean) =
        summedText(json, given.first, given.second, { if (short) "JSON_VALUE($it, '\$')" else "JSON_VALUE($it, '\$' RETURNING CLOB)" }) {
            if (short) asText(it) else "CASE WHEN ${kind(it)} = 'string' THEN JSON_VALUE($it, '\$' RETURNING CLOB) ELSE TO_CLOB(${asText(it)}) END"
        }

    /**
     * The JSON [json], a text, a number or a truth value, as `toString` gives it: a float (a double, or a number with a
     * fraction) as Java writes a double, from the shortest decimal Oracle writes it as, with no exponent from 10^-3 up to
     * 10^7, and else as `1.0E7`, `1.2E-4`; NaN and the infinities by Java's names. A double past a `NUMBER`'s range,
     * `1e300`, is written from Oracle's own text of it, its shortest digits, `1E+300`.
     */
    private fun asText(json: String) = shared(json) { (x) ->
        val written = "JSON_VALUE($x, '\$')"
        val float = "${kind(x)} = 'double' OR (${kind(x)} = 'number' AND NOT ${integral(x)})"
        val n = "TO_NUMBER($written DEFAULT NULL ON CONVERSION ERROR)"
        val double = "JSON_VALUE($x, '\$' RETURNING BINARY_DOUBLE)"
        // Past a NUMBER's range, which reads 1e-200 as 0, the double's own text, with a point where it has none: `1E-310`.
        // TO_CHAR of the double writes 15 digits, so 1e-310 as 9.99999999999997E-311.
        val scientific = scientific("NVL(TO_CHAR(NULLIF($n, 0), 'FM9.9999999999999999EEEE'), REGEXP_REPLACE($written, '^(-?[0-9]+)E', '\\1.0E'))")
        "CASE WHEN ${kind(x)} = 'double' AND $written IN ('Nan', 'Inf', '-Inf') THEN DECODE($written, 'Nan', 'NaN', 'Inf', 'Infinity', '-Infinity') " +
            "WHEN $float THEN CASE WHEN $double = 0 THEN '0.0' WHEN ABS($n) >= 0.001 AND ABS($n) < 10000000 THEN TO_CHAR($n, '$PLAIN_FLOAT') ELSE $scientific END " +
            "ELSE $written END"
    }

    /** `collect(CASE WHEN … THEN [r] ELSE [m.title] END)` fails with ORA-00979, or closes the session: see `casesFirst`. */
    override val aggregatesCaseSubqueries = false

    /** `min` and `max` of lists grouped and ordered by [listSortKey] close the session (ORA-03113): `JSON` orders them by itself. */
    override val extremesByListKey = false

    /** `TO_CHAR`'s scientific notation, `1.E+07`, `-1.2E-04`, as Java's: `1.0E7`, `-1.2E-4`. */
    private fun scientific(text: String) = "REGEXP_REPLACE(REPLACE($text, '.E', '.0E'), 'E\\+?(-?)0*([0-9])', 'E\\1\\2')"

    /** Whether the JSON [json] is NaN: a `BINARY_DOUBLE` that is. */
    private fun isNaN(json: String) = shared(json) { (x) -> "(${kind(x)} = 'double' AND JSON_VALUE($x, '\$' RETURNING BINARY_DOUBLE) IS NAN)" }

    /** Oracle's JSON holds NaN, which a write may store. */
    override fun writtenNaN(json: String) = isNaN(json)

    /**
     * Only where the statement is given or may work out NaN or an infinity ([nanWorkedOut]), as on the other stores: a NaN stored
     * and read by a statement that works none out is kept once, where Neo4j keeps each apart, as testing every value read made
     * `count(DISTINCT …)` 2.6 times as slow (see writes.yaml, "MERGE of a null or NaN property"). None in what the statement
     * writes alone.
     */
    override fun bareNaN(json: String) = if (!nanWorkedOut || nanFree(json)) null else isNaN(json)

    /**
     * Whether the JSON [json] is or holds NaN, at any depth: a list or a map by its text as extended JSON writes it, where a
     * double is `{"${'$'}numberDouble": "Nan"}`, which no text it holds can hold unescaped. Oracle's JSON path walks no list
     * within a list.
     */
    private fun holdsNaN(json: String) = shared(json) { (x) ->
        "(${isNaN(x)} OR ${kind(x)} IN ('array', 'object') AND INSTR(JSON_SERIALIZE($x RETURNING CLOB EXTENDED), '$EXTENDED_NAN') > 0)"
    }

    /** `JSON_EQUAL`, which compares values larger than `=` does, with ORA-40180 past about 32 KB. */
    override fun identityEquals(x: String, y: String) = "JSON_EQUAL($x, $y)"

    override fun idTokens(ids: String, key: String) = "JSON(REGEXP_REPLACE(JSON_SERIALIZE($ids RETURNING CLOB), '(-?[0-9]+)', ${literal("{\"$key\": \\1}")}))"

    override fun itemAt(json: String, index: Int) = "JSON_QUERY($json, '\$[$index]' RETURNING JSON)"

    override fun listSize(json: String) = "JSON_VALUE($json, '\$.size()' RETURNING NUMBER)"

    /**
     * A null as JSON's text writes one in a list or a map, its texts and keys taken out first, so a text that holds a null's
     * text (`"a,null]"`) does not say so: Oracle's JSON path walks no descendants. Read as a `VARCHAR2`, faster than as a
     * `CLOB`, which a value whose text is past 4,000 bytes is read as instead. It is exact, as a procedure's [nullsEqual] needs.
     */
    override fun holdsNull(json: String): String {
        fun nulls(text: String) = "REGEXP_LIKE(REGEXP_REPLACE($text, '\"([^\"\\\\]|\\\\.)*\"', '\"\"'), '[[,:]null[],}]')"
        val short = "JSON_SERIALIZE($json RETURNING VARCHAR2(4000) NULL ON ERROR)"
        return "($short IS NOT NULL AND ${nulls(short)} OR $short IS NULL AND ${nulls("JSON_SERIALIZE($json RETURNING CLOB)")})"
    }

    /**
     * Two values written as the same JSON text are null, as one holds a null and so its pair in the other does: that needs
     * no walk. The text is read as a `VARCHAR2`, as [holdsNull] reads it; a value past 4,000 bytes has none, and `=` and
     * `JSON_EQUAL` fail on one past about 32 KB (ORA-40180), so such a value is walked. Any other two are walked together by a
     * function, as Oracle's recursive queries take no `JSON`: the store's own ([nullsEqualName]), which the graph is made
     * with, so a procedure's queries and a `reduce`'s step's call it too. Where the store has none (see
     * [callsStoredNullsEqual]), a query declares it (see [reduce]); a procedure's queries call no function it declares, nor
     * do a `reduce`'s step's, so there two equal as JSON are null and any other two false, as before null was compared item
     * by item: `[null] = [1]` is false there, where Cypher's is null. Two of different kinds, a list beside a map or a
     * number, are false before either is walked: one that holds a null is a list or a map, and the function parses each
     * as one, which a number, a text or a truth value fails with ORA-40587 (drivine4j#518).
     */
    override fun nullsEqual(x: String, y: String, equals: (String, String) -> String): String {
        if (!nullsEqualStored && (procedural || folding)) return "CASE WHEN ${equals(x, y)} THEN NULL ELSE FALSE END"
        val walked = if (nullsEqualStored) {
            callsStoredNullsEqual = true
            "($storedNullsEqual($x, $y) = 1)"
        } else {
            // Declared once, however often it is called; and again where a compile only to see gave its declaration back.
            declare(nullsEqualFunction(NULLS_EQUAL))
            "($NULLS_EQUAL($x, $y) = 1)"
        }
        fun text(json: String) = "JSON_SERIALIZE($json RETURNING VARCHAR2(4000) NULL ON ERROR)"
        // Two of one text that hold NaN are not equal where it is: the pair it is in is not (#409).
        return "CASE WHEN ${text(x)} = ${text(y)} AND ${holdsNaN(x)} THEN FALSE WHEN ${text(x)} = ${text(y)} THEN NULL WHEN ${kind(x)} <> ${kind(y)} THEN FALSE ELSE $walked END"
    }

    /**
     * By [LIST_ORDER], a function the query declares, which walks the two with `JSON_ELEMENT_T`, as [nullsEqual]'s does: Oracle's
     * recursive queries take no `JSON`. A procedure's queries call no function it declares, nor do a `reduce`'s step's, so two
     * lists are not compared there.
     */
    override fun listOrder(x: String, y: String): String {
        if (procedural) throw CompilerException("Two lists compared with <, <=, > or >= do not run on Oracle in a statement that writes: its block calls no function of its own from SQL.")
        if (folding) throw CompilerException("Two lists compared with <, <=, > or >= do not run on Oracle in the step of a reduce(): its function calls no function of the query's from SQL.")
        // Declared once, however often it is called; and again where a compile only to see gave its declaration back.
        declare(listOrderFunction(LIST_ORDER))
        return "$LIST_ORDER($x, $y)"
    }

    override fun modulo(a: String, b: String) = "MOD($a, $b)"

    /**
     * As Java's `%` gives it, as Neo4j does, where [modulo]'s `MOD` of `NUMBER`s gives otherwise:
     * - Where either side is NaN or an infinity, which `NUMBER`s hold neither of, so `%` gave null (drivine4j#683): a finite
     *   number taken % by an infinity is that number, as a float, and NaN or an infinity taken % by any number, or any number
     *   taken % by NaN, is NaN.
     * - By an integer zero it fails, as `/` by one does (ORA-01476), where `MOD` gives the number taken (drivine4j#550): a
     *   float too, as Neo4j fails at run time. Neo4j gives NaN where it works a float and an integer zero written in the
     *   statement out as it plans, as Java does (`5.0 % 0`), and fails on `pi() % 0`: the divisor of the first comes here a
     *   float ([foldsWrittenDivisor], drivine4j#686).
     * - Where either side is a float, in `BINARY_DOUBLE`, so the remainder is a float, as in Cypher (drivine4j#427): `NUMBER`
     *   keeps no such kind, so `64.0 % 8.0` and `7 % 2.5` were the integers 0 and 2. By a float zero it is NaN.
     *
     * A number the statement writes ([writtenNumber]) is finite and of a kind known here, so it isn't tested for what it is not,
     * and `%` of two integers written, the divisor not zero, is `MOD`'s alone, as before.
     */
    override fun remainder(a: String, b: String): String {
        val (left, right) = writtenNumber(a) to writtenNumber(b)
        // By an integer zero written: `NUMBER`'s division by zero, which fails as `1 / 0` does, where `MOD` would give the number
        // taken. A null taken % by zero is null.
        if (right == WrittenNumber.ZERO) return byZero(a, b)
        val floats = left == WrittenNumber.FLOAT || right == WrittenNumber.FLOAT
        val byFloatZero = right != WrittenNumber.INTEGER
        if (left != null && right != null) return if (floats) "JSON_SCALAR(${floatRemainder(writtenDouble(a), writtenDouble(b), byFloatZero)})" else super.remainder(a, b)
        val (dividend, divisor) = mayBeNonFinite(a) to mayBeNonFinite(b)
        return shared(a, b) { (x, y) ->
            fun double(json: String) = "JSON_VALUE($json, '\$' RETURNING BINARY_DOUBLE)"
            val numbers = "${typeOf(x)} = 'number' AND ${typeOf(y)} = 'number'"
            val nan = listOfNotNull(
                "(${kind(x)} = 'double' AND (${double(x)} IS NAN OR ${double(x)} IS INFINITE))".takeIf { dividend },
                isNaN(y).takeIf { divisor },
            ).joinToString(" OR ")
            val nonFinite = if (nan.isEmpty()) "" else "WHEN $nan THEN CASE WHEN $numbers THEN JSON_SCALAR(BINARY_DOUBLE_NAN) END "
            val infinite = if (divisor) "WHEN ${kind(y)} = 'double' AND ${double(y)} IS INFINITE THEN CASE WHEN $numbers THEN JSON_SCALAR(${binaryDouble(x)}) END " else ""
            // The divisor isn't written, so it may be an integer zero, which is tested first: Neo4j fails on one whatever is taken % by it.
            val zero = if (right == null) "WHEN ${kind(y)} = 'number' AND ${number(y)} = 0 THEN ${byZero(x, y)} " else ""
            // A side written is a number of a kind known here, which isn't tested: a float makes the remainder one, and an integer
            // leaves it to the other side. Its double is written out, short, as `TO_BINARY_DOUBLE('2')`.
            val tests = listOfNotNull(
                "${typeOf(x)} = 'number'".takeIf { left == null }, "${typeOf(y)} = 'number'".takeIf { right == null },
                if (floats) null else "NOT (${listOfNotNull(integral(x).takeIf { left == null }, integral(y).takeIf { right == null }).joinToString(" AND ")})",
            ).joinToString(" AND ")
            val doubles = (if (left == null) binaryDouble(x) else writtenDouble(a)) to (if (right == null) binaryDouble(y) else writtenDouble(b))
            val float = "WHEN $tests THEN JSON_SCALAR(${floatRemainder(doubles.first, doubles.second, byFloatZero)}) "
            "CASE $zero$nonFinite$infinite${float}ELSE ${super.remainder(x, y)} END"
        }
    }

    /**
     * `a % b` or `a / b` of the JSON values [a] and [b], [b] an integer zero: where [a] is a number, NaN and the infinities among
     * them, `NUMBER`'s division by zero, which fails as `1 / 0` does (ORA-01476); else null, as `MOD` or `/` of no number gives.
     */
    private fun byZero(a: String, b: String) = shared(a, b) { (x, y) -> "JSON_SCALAR(CASE WHEN ${typeOf(x)} = 'number' THEN TRUNC(1 / ${number(y)}) END)" }

    /** The `BINARY_DOUBLE` of the JSON [json], a finite number the statement writes ([writtenNumber]). */
    private fun writtenDouble(json: String) =
        if (WRITTEN_FLOAT.matches(json)) json.removePrefix("JSON_SCALAR(").removeSuffix(")") else "TO_BINARY_DOUBLE(${json.removePrefix("JSON(").removeSuffix(")")})"

    /**
     * `x % y` of the finite `BINARY_DOUBLE`s [x] and [y], as Java's: NaN by zero, where `MOD` gives [x], unless [y] can't be
     * zero ([byZero] false). A zero is 0.0, whatever the sign of [x]: Oracle holds no negative zero, so `-7.5 % 2.5` is 0.0
     * where Java's is -0.0 (drivine4j#552).
     */
    private fun floatRemainder(x: String, y: String, byZero: Boolean) = if (byZero) "CASE WHEN $y = 0 THEN BINARY_DOUBLE_NAN ELSE MOD($x, $y) END" else "MOD($x, $y)"

    /** Whether the JSON [json] may be NaN or an infinity: where it is no value the statement writes, which is neither, or one written so. */
    private fun mayBeNonFinite(json: String) = !written(json) || json.contains("TO_BINARY_DOUBLE('NaN')") || json.contains("Infinity')")

    /** The kind of number the JSON [json] is, where the statement writes it as a finite one ([literalJson]); else null. */
    private fun writtenNumber(json: String): WrittenNumber? {
        if (WRITTEN_FLOAT.matches(json)) {
            val value = org.drivine.cypher.json.parseDoubleOrNull(json.substringAfter("('").substringBefore("')"))
            return WrittenNumber.FLOAT.takeIf { value != null && value.isFinite() }
        }
        if (writtenScalar(json) != true || !json.startsWith("JSON('")) return null
        val value = runCatching { CypherJson.readTree(json.substring(6, json.length - 2).replace("''", "'")) }.getOrNull() ?: return null
        return when {
            !value.isIntegralNumber -> null
            value.bigIntegerValue().isZero() -> WrittenNumber.ZERO
            else -> WrittenNumber.INTEGER
        }
    }

    /** What [writtenNumber] finds a side of `%` or `/` written as. */
    private enum class WrittenNumber { FLOAT, ZERO, INTEGER }

    /** CLOBs, which `=` does not compare (ORA-22848), by `DBMS_LOB.COMPARE`: see [texts]. */
    override fun startsWith(a: String, b: String) = shared(b) { (x) ->
        if (long) "(DBMS_LOB.COMPARE($a, $x, LENGTH($x), 1, 1) = 0)" else "(SUBSTR($a, 1, LENGTH($x)) = $x)"
    }

    override fun endsWith(a: String, b: String) =
        if (long) shared(a, b) { (t, x) -> "(LENGTH($t) >= LENGTH($x) AND DBMS_LOB.COMPARE($t, $x, LENGTH($x), LENGTH($t) - LENGTH($x) + 1, 1) = 0)" }
        else shared(b) { (x) -> "(SUBSTR($a, -LENGTH($x)) = $x)" }

    /**
     * By `INSTR`, of `VARCHAR2`s, and of CLOBs (see [texts]) where the text looked for is at most [SEARCHED_TEXT] UTF-16
     * units: `INSTR` refuses a longer one (ORA-22828, #312), and `DBMS_LOB.INSTR` one past 4,000 bytes (ORA-22835). A
     * longer one is in the text where the two are as long and `DBMS_LOB.COMPARE` finds them equal, and where it is
     * shorter, where [searched] finds it. The CLOBs are bound, so a property is read inside the bound subquery: see [texts].
     *
     * Each way gives a number, 1 where the text is found, and only the `CASE` that chooses between them is compared with 1:
     * `(SELECT … MODEL …) = 1` and `> 0` were false where the subquery alone gave 1, as was a `CASE` with the comparison
     * in a branch; a `CASE` of numbers compared outside gave the right answer in a select, a `WHERE`, a cross join, a
     * `COUNT(CASE …)` and a PL/SQL block.
     */
    override fun containsText(a: String, b: String) =
        if (!long) "(INSTR($a, $b) > 0)"
        else bound(a, b) { (t, x) ->
            "(CASE WHEN $t IS NULL OR $x IS NULL THEN NULL WHEN LENGTH($x) <= $SEARCHED_TEXT THEN SIGN(INSTR($t, $x)) " +
                "WHEN LENGTH($x) >= LENGTH($t) THEN CASE WHEN LENGTH($x) = LENGTH($t) AND DBMS_LOB.COMPARE($t, $x) = 0 THEN 1 ELSE 0 END " +
                "ELSE ${searched(t, x)} END = 1)"
        }

    /**
     * 1 where the CLOB [x], longer than [SEARCHED_TEXT] units and shorter than the CLOB [t], is in [t], else -1. At each
     * place in turn where its first [SEARCHED_PREFIX] units are, `INSTR` finding each from the one before, it is compared
     * whole by `DBMS_LOB.COMPARE`. A recursive `WITH` would carry the place from one to the next, but reads no column of
     * the row it is in (ORA-00904); the `MODEL` clause carries it, and does. So the time grows with the length of [t] and
     * with how many places are compared, each as far as the two are equal: as `INSTR`'s own, which compares at each place.
     *
     * The first units may end on the first of a pair, which `SUBSTR` of a CLOB cuts where `DBMS_LOB.SUBSTR` refuses to
     * (ORA-22831): `INSTR` finds the half where the whole pair is, and the comparison is of the whole text.
     */
    private fun searched(t: String, x: String): String {
        val prefix = "SUBSTR($x, 1, $SEARCHED_PREFIX)"
        val found = "CASE WHEN cs_at[0] = 0 OR cs_at[0] > LENGTH($t) - LENGTH($x) + 1 THEN -1 " +
            "WHEN DBMS_LOB.COMPARE($t, $x, LENGTH($x), cs_at[0], 1) = 0 THEN 1 ELSE 0 END"
        return "(SELECT cs_found FROM DUAL MODEL DIMENSION BY (0 cs_i) MEASURES (INSTR($t, $prefix) cs_at, 0 cs_found) " +
            "RULES ITERATE ($SEARCHED_PLACES) UNTIL (cs_found[0] <> 0) " +
            "(cs_found[0] = $found, cs_at[0] = CASE WHEN cs_found[0] = 0 THEN INSTR($t, $prefix, cs_at[0] + 1) ELSE cs_at[0] END))"
    }

    /** Of a property, read from the properties by its own path: see [direct]. */
    override fun function(name: String, json: String): String? = functionOf(name, json)?.let { direct(it, json) }

    private fun functionOf(name: String, json: String): String? = when (name) {
        // A text past 4,000 bytes, null as a VARCHAR2, is measured as a CLOB (#298); a text null as both is empty (#416).
        "size" -> "CASE ${kind(json)} WHEN 'array' THEN JSON_SCALAR(JSON_VALUE($json, '\$.size()' RETURNING NUMBER)) " +
            "WHEN 'string' THEN JSON_SCALAR(COALESCE(LENGTH(JSON_VALUE($json, '\$')), LENGTH(JSON_VALUE($json, '\$' RETURNING CLOB)), 0)) END"
        "tolower" -> texts(json) { (t) -> scalar("LOWER($t)") }
        "toupper" -> texts(json) { (t) -> scalar("UPPER($t)") }
        "trim" -> texts(json) { (t) -> scalar("TRIM($t)") }
        // A text past 4,000 bytes, which asText reads as null, is read as a CLOB.
        "tostring" -> "COALESCE(JSON_SCALAR(${asText(json)}), JSON_SCALAR(${longText(json)}))"
        // NaN and the infinities, which no NUMBER holds, as Java casts them to a long, as Neo4j does: 0, and the long's ends.
        "tointeger" -> shared(json) { (x) ->
            val double = "JSON_VALUE($x, '\$' RETURNING BINARY_DOUBLE)"
            "CASE WHEN ${kind(x)} <> 'double' THEN JSON_SCALAR(TRUNC(TO_NUMBER(JSON_VALUE($x, '\$') DEFAULT NULL ON CONVERSION ERROR))) " +
                "WHEN $double IS NAN THEN JSON_SCALAR(0) WHEN $double = BINARY_DOUBLE_INFINITY THEN JSON_SCALAR(${Long.MAX_VALUE}) " +
                "WHEN $double = -BINARY_DOUBLE_INFINITY THEN JSON_SCALAR(${Long.MIN_VALUE}) " +
                "ELSE JSON_SCALAR(TRUNC(TO_NUMBER(JSON_VALUE($x, '\$') DEFAULT NULL ON CONVERSION ERROR))) END"
        }
        // TO_BINARY_DOUBLE reads 'nan', 'inf' and 'INFINITY' too, and Neo4j only Java's names, on the text with U+0000 to the
        // space taken off its ends (drivine4j#551): a name is read so trimmed, as TO_BINARY_DOUBLE takes ASCII's spaces alone
        // off. A text with no N in it, a decimal, is read as before; so is any but a text.
        "tofloat" -> shared(json) { (x) ->
            val text = "JSON_VALUE($x, '\$')"
            val trimmed = "LTRIM(RTRIM($text, $JAVA_SPACES), $JAVA_SPACES)"
            val read = "TO_BINARY_DOUBLE($text DEFAULT NULL ON CONVERSION ERROR)"
            "JSON_SCALAR(CASE WHEN ${kind(x)} = 'string' AND INSTR(UPPER($text), 'N') > 0 THEN " +
                "CASE WHEN REGEXP_LIKE($trimmed, '$JAVA_NAMES', 'c') THEN TO_BINARY_DOUBLE($trimmed) END ELSE $read END)"
        }
        // An item read so where it is a text past 32 KB crashed the session: see [listItem].
        "head" -> listItem(json, "'\$[0]'")
        "last" -> listItem(json, "'\$[last]'")
        "abs" -> "CASE WHEN ${kind(json)} = 'double' THEN JSON_SCALAR(ABS(TO_BINARY_DOUBLE(${number(json)}))) ELSE JSON_SCALAR(ABS(${number(json)})) END"
        "keys" -> keys(json)
        else -> null
    }

    /**
     * A JSON path reads no map's keys, so they are read from the map's data guide: each path it lists one step
     * from the top is a key, written `$.name`, or `$."…"` as JSON writes a string where the name is any other.
     * The map is bound first: the data guide's query reads a column of a row outside it, and not an expression of one.
     * The guide's paths are read by their places, as [temporalElements] reads them: a `JSON_TABLE` over the guide of a map
     * that holds a list of temporal values worked out item by item stalls the parse as one over the list does (#331, #347).
     * They are so read for every map, not only one the statement makes hold such a list.
     * The guide is worked out by the lateral view itself, as its one aggregate over `DUAL`: a subquery that works it out,
     * in that view's select list, is worked out once, and every row then had the first row's keys (#414). The keys of
     * 100,000 maps so take 7 s, where a `JSON_TABLE` over each guide takes 4.4 s.
     * Null has no keys, and gives null; a value of another kind fails, as in Neo4j.
     */
    private fun keys(json: String): String = bound(json) { (map) -> keysOrFailure(kind(map), keysOf(map)) }

    private fun keysOf(json: String): String {
        val key = """CASE WHEN SUBSTR(d.p, 3, 1) = '"' THEN JSON_VALUE(JSON(SUBSTR(d.p, 3)), '${'$'}') ELSE SUBSTR(d.p, 3) END"""
        val paths = temporalElements("JSON_DATAGUIDE($json, 2)", "dg")
        val guide = """(SELECT JSON_VALUE(dg.item, '${'$'}."o:path"' RETURNING VARCHAR2(4000)) AS p FROM $paths) d"""
        val topLevel = """REGEXP_LIKE(d.p, '^\${'$'}\.(\w+|"([^"\\]|\\.)*")${'$'}')"""
        return "(SELECT COALESCE(JSON_ARRAYAGG(g.k ORDER BY g.k RETURNING JSON), JSON('[]')) FROM (SELECT $key AS k FROM $guide WHERE $topLevel) g)"
    }

    override fun page(skip: String?, limit: String?) = listOfNotNull(skip?.let { "OFFSET $it ROWS" }, limit?.let { "FETCH NEXT $it ROWS ONLY" })

    override fun ids(id: String) = "(',' || $id || ',')"

    override fun joinIds(lists: List<String>) = "(${lists.first()}${lists.drop(1).joinToString("") { " || SUBSTR($it, 2)" }})"

    override fun storeIds(json: String) = shared(json) { (ids) ->
        val listed = "LISTAGG(x.id, ',') WITHIN GROUP (ORDER BY x.ord)"
        "CASE WHEN $ids IS NOT NULL THEN (SELECT NVL2($listed, ',' || $listed || ',', ',') FROM JSON_TABLE($ids, '\$[*]' COLUMNS (ord FOR ORDINALITY, id NUMBER PATH '\$')) x) END"
    }

    override val noIds = "','"

    override fun idIn(id: String, list: String) = "INSTR($list, ',' || $id || ',') > 0"

    override fun idsOverlap(a: String, b: String) = "EXISTS (SELECT 1 FROM ${idRows(a, "ov")} WHERE INSTR($b, ',' || ov.id || ',') > 0)"

    override fun idCount(list: String) = "JSON_SCALAR(REGEXP_COUNT($list, ',') - 1)"

    override fun idRows(list: String, alias: String) =
        "JSON_TABLE(JSON('[' || TRIM(BOTH ',' FROM $list) || ']'), '\$[*]' COLUMNS (ord FOR ORDINALITY, id NUMBER PATH '\$')) $alias"

    /** Oracle's recursive queries read nothing of the row they are in. */
    override val walksReadRow = false

    /** A recursive query from each node that meets the seed, each row carrying the node it started from. */
    override fun walk(walk: Walk): Walked {
        val w = walk.step
        val n = "${walk.name}_n"
        val (joins, reached, distinct) = steps(walk, "$w.nid")
        val eids = walk.hops.map { "${it.edge}.eid" }
        val conditions = eids.map { "INSTR($w.eids, ',' || $it || ',') = 0" } + distinct + listOfNotNull(walk.max?.let { "$w.depth < $it" }) + walk.conditions
        // Oracle adapts a step's plan as the statement runs, and a second session that runs the statement then finds no
        // rows where a walk either way goes around a cycle (#182): no plan adapts.
        val select = "SELECT /*+ NO_ADAPTIVE_PLAN */"
        val text = listOf(
            "WITH $w (src, nid, depth, nids, eids) AS (",
            "    SELECT $n.nid, $n.nid, 0, CAST(',' AS VARCHAR2(4000)), CAST(',' AS VARCHAR2(4000))",
            "    FROM $nodes $n${walk.seed?.let { " WHERE ${it(n)}" }.orEmpty()}",
            "    UNION ALL",
            // Walked backwards, each node it leaves goes first, so its nodes end with the one it started from.
            if (walk.backwards) "    $select $w.src, ${reached.single()}, $w.depth + 1, ',' || $w.nid || $w.nids, ',' || ${eids.single()} || $w.eids"
            else "    $select $w.src, ${reached.last()}, $w.depth + 1, $w.nids${reached.joinToString("") { " || $it || ','" }}, $w.eids${eids.joinToString("") { " || $it || ','" }}",
            "    FROM $w",
            joins.joinToString("\n") { "    $it" },
            "    WHERE ${conditions.joinToString(" AND ")}",
            // A walk may come back to a node it left, which Oracle takes for a cycle unless told what one is: a step
            // takes no relationship twice, so no row's relationships are an earlier row's.
            ") CYCLE eids SET looped TO 'Y' DEFAULT 'N'",
            "SELECT src, nid, nids, eids FROM $w WHERE depth >= ${walk.min}",
        ).joinToString("\n")
        return Walked(
            "(\n${indent(text)}\n) ${walk.name}",
            "${walk.name}.src = ${walk.from} AND ${walk.name}.nid = ${walk.to}",
            "${walk.name}.eids",
            "${walk.name}.nids",
        )
    }

    override fun nodeJson(node: Value.Node) =
        "CASE WHEN ${node.nid} IS NOT NULL THEN JSON_TRANSFORM(${node.props}, SET ${OracleSql.path(Stamps.PROPERTY)} = ${node.stamp} IGNORE ON NULL) END"

    /**
     * A function the query declares, `WITH FUNCTION`, that folds the list: Oracle's recursive queries read nothing
     * of the row they are in. The step reads what it reads of the row from the map it is given. A procedure's
     * queries call no function it declares, so a statement that writes takes no `reduce`; nor does the step of one,
     * whose queries are its function's own.
     */
    override fun reduce(
        name: String,
        initial: String,
        list: String,
        step: (accumulator: String, item: String) -> String,
        context: () -> String,
        contextStep: (accumulator: String, item: String, context: String) -> String,
    ): String {
        if (procedural) throw CompilerException("reduce() does not run on Oracle in a statement that writes: its block calls no function of its own from SQL.")
        if (folding) throw CompilerException("reduce() does not run on Oracle in the step of a reduce(): its function calls no function of the query's from SQL.")
        val function = "cy_$name"
        val given = context()
        folding = true
        val folded = try {
            contextStep("cy_value", "cy_step.item", "cy_context")
        } finally {
            folding = false
        }
        declare(
            listOf(
                "FUNCTION $function(cy_initial JSON, cy_list JSON, cy_context JSON) RETURN JSON IS",
                "    cy_value JSON := cy_initial;",
                "    cy_kind VARCHAR2(20);",
                "BEGIN",
                "    SELECT JSON_VALUE(cy_list, '\$.type()') INTO cy_kind FROM DUAL;",
                "    IF cy_kind IS NULL OR cy_kind <> 'array' THEN RETURN NULL; END IF;",
                "    FOR cy_step IN (SELECT s.item FROM JSON_TABLE(cy_list, '\$[*]' COLUMNS (item_no FOR ORDINALITY, item JSON PATH '\$')) s ORDER BY s.item_no) LOOP",
                indent("    SELECT $folded INTO cy_value FROM DUAL;"),
                "    END LOOP;",
                "    RETURN cy_value;",
                "END;",
            ).joinToString("\n")
        )
        return "$function($initial, $list, $given)"
    }

    /** Whether the step of a `reduce` is being written, as its function's own: see [reduce]. */
    private var folding = false

    /** As many rows as the range has numbers, from a hierarchical query of one row. */
    override fun range(from: String, to: String, step: String) = shared(from, to, step) { (first, last, by) ->
        val count = "FLOOR(($last - $first) / $by) + 1"
        "(SELECT COALESCE(JSON_ARRAYAGG($first + (r.n - 1) * $by ORDER BY r.n RETURNING JSON), JSON('[]')) " +
            "FROM (SELECT LEVEL n FROM DUAL CONNECT BY LEVEL <= GREATEST($count, 1)) r WHERE r.n <= $count)"
    }

    override val temporalSql = OracleTemporalSql(sharedList, { procedural }, if (eraStored) graph else null) { callsStoredEra = true }

    /**
     * Oracle merges a view of no table into the subquery that reads it, and where that subquery is bound in
     * another and the row reads another property, the merged query reads the value as null: each is kept apart.
     */
    override fun boundSelect(columns: String) = "SELECT /*+ NO_MERGE */ $columns"

    /**
     * Oracle merges a `CALL { … }` query into the row's query that joins it, and a value bound in that row from what
     * the query returns is then read as null (`CALL { WITH n RETURN n.day AS d } RETURN d + duration('P1D')`).
     *
     * One that keeps its rows distinct is not decorrelated either (`NO_DECORRELATE`): where it keeps distinct a value of the
     * row's own `UNWIND`, or one worked out of it, Oracle reads the outer value as null when it decorrelates the lateral
     * (drivine4j#502). `UNWIND [1, 1, 2] AS i CALL (i) { WITH DISTINCT i RETURN i AS j } RETURN i, j` gave j null on each row,
     * and the `WHERE` of a `WITH DISTINCT i WHERE i > 1` kept none; a node or a property of the row was read right. Seen on
     * Oracle 23.26 (gvenzl/oracle-free 23). Any other query is decorrelated as before. Such a query is told by its text, a
     * `SELECT DISTINCT` at any depth ([DISTINCT_SELECT]), so the parts of a `UNION` in a `CALL` and `db.labels()`'s query are
     * hinted too, though each read right without, and so are a query [once] keeps apart, a `WITH`'s query kept apart, as
     * one that passes a path on is, and one whose string holds those words: the hint changes a plan, not its rows. `count(DISTINCT …)` read right, and is not hinted.
     */
    override fun called(query: String) =
        "SELECT /*+ NO_MERGE${if (DISTINCT_SELECT.containsMatchIn(query)) " NO_DECORRELATE" else ""} */ * FROM (\n${indent(query)}\n)"

    /**
     * Each read of a stored value as JSON left off the CLOB route is routed it: a long text property read as a direct member of
     * a list or a map, or as a nested property, is routed the CLOB way on Oracle, to avoid an Oracle server defect
     * (drivine4j#670). See [OracleLongReads].
     */
    override fun finished(compiled: CompiledCypher) = compiled.copy(sql = OracleLongReads.routed(compiled.sql))

    /**
     * A query that reads the graph's tables twice or more is not rewritten by Oracle's JSON table transformation
     * (`NO_JSON_TABLE_TRANSFORM`), which reads several JSON values of a column, `n1.props`, by one `JSON_TABLE`: so
     * rewritten, a node's table beside another table, or beside a subquery of one, lost the tests of that node's labels
     * and properties, and the query gave rows its pattern doesn't have, and values of the wrong node (#415):
     * `MATCH (a:N1), (b:N2) RETURN a.n + b.n` gave a row for each two nodes, and so did `a.n - a.m`, `toUpper(a.s),
     * size(a.s)` and `a.l + b.l`. Seen on Oracle 23.26.3 (gvenzl/oracle-free 23). The hint is the statement's, whichever
     * query of it holds it, so the outermost one holds it. A query of one table reads as it did, none lost a test, but where
     * a comparison reads a property by its own path (see [readsCompared]).
     *
     * A query that tests a node's labels as [keyedLabels] writes them, to read it by its key, is planned without statistics
     * feedback (`OPT_PARAM('_optimizer_use_feedback' 'false')`, [NO_FEEDBACK]). Oracle (23.26) estimates one row from the
     * index over labels, and plans the keyed read on a cursor's first run; the run's feedback then corrects that one row to
     * the label's count, and Oracle plans the next two runs again, reading every node in the label's text form and the index
     * once for each, before it went back to the keyed plan for the fourth run and after: `MATCH (a:Big) MATCH (b:Big) WHERE
     * a = b` took 7 ms, then 1,482 ms and 1,526 ms, then 7 ms over 4,000 nodes, so each new cursor paid the cost #578 took
     * away twice (#585). The hint leaves the first run's plan as it was. It is the statement's, so the outermost query holds it.
     *
     * A query of a procedure that reads the rows of a `FOREACH` or a `CALL { … }` written for each row alone is planned for
     * one row of them first: see [oneRowPlanned].
     */
    override fun planned(query: String): String {
        val estimated = oneRowPlanned(query)
        val hints = listOfNotNull(
            NO_JSON_TABLE_TRANSFORM.takeIf { readsCompared || graphTable.findAll(estimated).count() >= 2 },
            NO_FEEDBACK.takeIf { keyed.any { it in estimated } },
        ).joinToString(" ")
        if (hints.isEmpty()) return estimated
        // SPIKE: MatchGroup.range is not in common Kotlin; group 1 of HINTED runs from after `SELECT /*+ ` to before ` */`.
        val given = HINTED.find(estimated)?.takeIf { it.range.first == 0 }
        return if (given != null) estimated.replaceRange("SELECT /*+ ".length, given.range.last + 1 - " */".length, "${given.groupValues[1]} $hints")
        else estimated.replaceFirst(Regex("^SELECT "), "SELECT /*+ $hints */ ")
    }

    /**
     * The aliases of the `JSON_TABLE`s that read [ROWS] where a `FOREACH` or a `CALL { … }` is written for each row alone
     * ([rowAlone]): kept as text, as [keyed] is, and looked for in the query [planned] is given.
     */
    private val oneRow = mutableSetOf<String>()

    /**
     * [query] with each query of it that reads the rows of a `FOREACH` or a `CALL { … }` written for each row alone
     * ([oneRow]) told they are one row (`CARDINALITY(r 1)`), in its own query's hint, where Oracle reads it: one in the
     * statement's outermost query left the plan as it was. Oracle estimates 8,168 rows for a `JSON_TABLE` over a variable,
     * and joined the node table to them by a hash join over all its nodes, so a `CALL (n) { SET … RETURN n.i AS j }`
     * read every node for each row (191 buffers a row over 10,000 nodes, 38 over 2,000), and took 20 s over 10,000 rows
     * where it took 1 s over 2,000 (#588). Told of one row, Oracle reads each node by its key: 3 buffers a row, at any size.
     */
    private fun oneRowPlanned(query: String): String =
        oneRow.fold(query) { text, alias ->
            val read = Regex("""FROM JSON_TABLE\(${Regex.escape(ROWS)}, '\$\[\*]' COLUMNS \(item_no FOR ORDINALITY, item JSON PATH '\$'\)\) ${Regex.escape(alias)}\b""")
            // An alias names one source of the statement, so one query reads it.
            val select = read.find(text)?.let { owningSelect(text, it.range.first) } ?: return@fold text
            val hint = "CARDINALITY($alias 1)"
            val after = select + "SELECT ".length
            if (text.startsWith("/*+ ", after)) text.indexOf(" */", after).let { end -> text.replaceRange(end, end, " $hint") }
            else text.replaceRange(after, after, "/*+ $hint */ ")
        }

    /**
     * Where the `SELECT` whose `FROM` is at [from] in [query] begins: read back from there, past the brackets of its select
     * list and the quotes of its texts; null where a bracket opens before it.
     */
    private fun owningSelect(query: String, from: Int): Int? {
        var depth = 0
        var at = from - 1
        while (at >= 0) {
            when (query[at]) {
                '\'' -> at = query.lastIndexOf('\'', at - 1).takeIf { it >= 0 } ?: return null
                ')' -> depth++
                '(' -> if (depth == 0) return null else depth--
                'S' -> if (depth == 0 && query.startsWith("SELECT ", at) && (at == 0 || !query[at - 1].isLetterOrDigit() && query[at - 1] != '_')) return at
            }
            at--
        }
        return null
    }

    /**
     * Each label test of a node's labels the index over labels would answer ([hasLabel]) written by the labels' text instead
     * ([OracleSql.holdsLabel]), in each query of the subquery, a `LATERAL`'s too. Oracle (23.26) crashed the session (ORA-07445
     * `[qjsnmvFindOpt()+16] [SIGSEGV]`, in the multivalue index over labels; ORA-03113 to the client) planning `DELETE FROM
     * <edges> WHERE eid IN (SELECT … FROM <nodes> n1 LEFT JOIN LATERAL (… <nodes> n2 …) …)` of `MATCH (a:X) OPTIONAL MATCH
     * (a)-[r:R]->(:X {k: 2}) DELETE r`, and the `DETACH DELETE m` of `OPTIONAL MATCH (a)-[:R]->(m:X {k: 3})` alike, where
     * transactions that deleted and read nodes of the label had rolled back (#595). Off the index it ran clean; with
     * `NO_ADAPTIVE_PLAN` (already the session's) or `NO_INDEX` on the outer node alone it crashed as often. The subquery
     * then reads the outer node's table whole, once, and the lateral's node by its key.
     */
    override fun deletedIds(query: String): String =
        super.deletedIds(query).replace(INDEXED_LABEL) { m ->
            OracleSql.holdsLabel(m.groupValues[1], m.groupValues[2].replace("''", "'").replace(Regex("""\\(.)"""), "$1"))
        }

    /** A read of the graph's node or relationship table: see [planned]. */
    private val graphTable = Regex("\\b(?:${Regex.escape(nodes)}|${Regex.escape(edges)}) \\w")

    /**
     * An optional join is on `1 = 1`, not `ON TRUE`: in a scalar subquery, as a `COUNT { … }` is, Oracle refuses a join on
     * `TRUE` followed by a `WHERE … AND …` (ORA-00907), and crashes the session on one without (ORA-07445 in `qcsjccqb`,
     * ORA-03113 to the client) (#338). See docs/oracle-on-true-in-scalar-subquery-crash.md.
     */
    override fun lateral(query: String, name: String, optional: Boolean, columns: List<Pair<String, Boolean>>): String =
        if (optional) "LEFT JOIN LATERAL (\n${indent(query)}\n) $name ON 1 = 1" else super.lateral(query, name, optional = false, columns)

    /**
     * Oracle refuses an `ORDER BY` that ends the query of an `EXISTS` (ORA-00907), and the query of an `EXISTS { UNWIND … }`
     * ends in one, the order of the list's items, as does one whose body has its own `ORDER BY` (drivine4j#449). It is read
     * from a derived table, as a `COUNT { … }`'s is, where Oracle takes an `ORDER BY`, and where one that a `LIMIT` or a `SKIP`
     * of the body reads still picks the rows they keep.
     */
    override fun existsQuery(query: String) = "SELECT 1 FROM (\n${indent(query)}\n)"

    /**
     * Flags the pattern opens with, `(?i)`, are Oracle's match parameter, Java's `s` its `n`. The whole of the text
     * matches, as `=~` asks: it starts at `\A` and ends at `\z`, the text's start and end, which `(?m)` leaves as they are,
     * and Oracle's `$` also matches before a line break that ends the text. Under `(?m)`, Oracle's own `^` and `$` match
     * at a line break only where nothing in the pattern reads past it: `x$\n^ab` matches no text.
     */
    override fun matches(text: String, pattern: String) = shared(pattern) { (p) ->
        val flags = "REGEXP_SUBSTR($p, '^\\(\\?([a-z]+)\\)', 1, 1, NULL, 1)"
        "REGEXP_LIKE($text, '\\A(' || REGEXP_REPLACE($p, '^\\(\\?[a-z]+\\)', '') || ')\\z', NVL(REPLACE($flags, 's', 'n'), 'c'))"
    }

    /**
     * The text as JSON writes it, with each separator as JSON writes it made the end of one string and the start of the next.
     * A `VARCHAR2` is cut off past 4,000 bytes where `REPLACE` makes it longer, so a text is split as one only where what it
     * is made cannot be longer, each of its bytes written as six at most (`\u0001`), and else as a CLOB: see [texts].
     */
    override fun split(text: String, separator: String) = shared(text, separator) { (t, s) ->
        // A separator past 4,000 bytes is a CLOB too, and written as one (ORA-40478).
        fun serialized(x: String) = if (long) "JSON_SERIALIZE(JSON_SCALAR($x) RETURNING CLOB)" else "JSON_SERIALIZE(JSON_SCALAR($x))"
        val written = serialized(t)
        val separatorWritten = "SUBSTR(${serialized(s)}, 2, LENGTH(${serialized(s)}) - 2)"
        val fits = if (long) "" else " AND LENGTHB($t) <= ${(SHORT_TEXT - 2) / 6}"
        "CASE WHEN $t IS NOT NULL AND $s IS NOT NULL$fits THEN JSON('[' || REPLACE($written, $separatorWritten, '\",\"') || ']') END"
    }

    /**
     * A `VARCHAR2` is cut off past 4,000 bytes where `REPLACE` makes it longer: as one only where it cannot be, as each
     * [search] made [replacement] grows the text by their ratio at most, and else null, so [texts] replaces in a CLOB. Their
     * lengths are worked out here where the statement gives them, and not for each row.
     */
    override fun replaceText(text: String, search: String, replacement: String, searchBytes: Int?, replacementBytes: Int?): String {
        val replaced = "REPLACE($text, $search, $replacement)"
        if (long) return replaced
        if (searchBytes != null && replacementBytes != null) {
            return if (replacementBytes <= searchBytes) replaced else "CASE WHEN LENGTHB($text) * $replacementBytes <= ${SHORT_TEXT * searchBytes} THEN $replaced END"
        }
        val (s, r) = (searchBytes?.toString() ?: "LENGTHB($search)") to (replacementBytes?.toString() ?: "LENGTHB($replacement)")
        return "CASE WHEN $r <= $s OR LENGTHB($text) * $r <= $SHORT_TEXT * $s THEN $replaced END"
    }

    /** Oracle has no reverse of a text that it documents: its characters, read from the last, put together by `LISTAGG`. */
    override fun reverseText(text: String) = shared(text) { (t) ->
        "(SELECT LISTAGG(SUBSTR($t, LEVEL, 1)) WITHIN GROUP (ORDER BY LEVEL DESC) FROM DUAL CONNECT BY LEVEL <= LENGTH($t))"
    }

    /**
     * `LISTAGG` gives a `VARCHAR2`, and fails past 4,000 bytes (ORA-01489), so a CLOB that [texts] gives (#311) is reversed in
     * pieces, as JSON: see [reversedPieces]. The CLOB is bound once, as each piece reads it, and bound as a CLOB by `TO_CLOB`:
     * of a literal, `reverse('Keanu')` or `reverse([1, 2, 3])`, Oracle otherwise finds no one `DBMS_LOB.SUBSTR` to call (PLS-307).
     */
    override fun reversedText(text: String) = if (long) bound("TO_CLOB($text)") { (t) -> reversedPieces(t) } else super.reversedText(text)

    /**
     * The kind of a property `reverse()` is given, read by the property's own path, `JSON_VALUE(n1.props, '$.t.type()')`, as
     * [texts] reads its text (see [direct]); of any other value, as [typeOf] reads it.
     *
     * **A workaround for the Oracle bug of [itemProperty] (#538), to be removed with it.** Read from the property taken out as
     * JSON, `JSON_VALUE(JSON_QUERY(n1.props, '$.t' RETURNING JSON), '$.type()')`, the kind of a text past 32 KB closed the
     * session over two rows or more (ORA-03113, or ORA-00600 `[kghfrh:ds]`), as #255's did: returned, measured, searched, in
     * a `WHERE`, passed on and in a write. Read by its path, a short text's kind costs a little less than before: 20,000
     * rows of `RETURN reverse(n.name)` take about 310 ms, against 335 to 340 ms. To remove it, delete this override with
     * [itemProperty]'s, once an Oracle release no longer crashes on the repro in `docs/oracle-long-json-string-crash.md`,
     * and `CypherQueryOracleTest`'s "a text past 32 KB reversed" test still passes without it.
     */
    override fun reversedKind(json: String) = direct(typeOf(json), json)

    /**
     * The CLOB [text] reversed as a JSON text: cut into pieces of [REVERSED_PIECE] of its UTF-16 units, each a `VARCHAR2`
     * reversed by [reverseText], and the pieces put together last to first.
     *
     * A CLOB counts a character past U+FFFF as two units, and refuses a cut that starts between them (ORA-22831), so a piece
     * that would end on the first of two takes the second too, and the next starts after it: `REGEXP_SUBSTR` reads the
     * character at a unit, two units where one starts there, and from that unit, not from the text's start, so each piece of
     * a long text takes as long.
     *
     * The pieces are put together as a JSON list, each after an `x`, written in ASCII: a `","x` in it is then only ever
     * between two of them, as a quote in a text is written `\"`, so removing each, and the first `x`, leaves a list of one
     * text, which is taken out of it as text, in ASCII, and read as JSON, as [listItem] takes a text out through its text.
     * A null text has one piece, as a hierarchical query has a first row, which is left out, so the list is null, and so is
     * the text. Written otherwise, a character past U+FFFF whose two units are the 4,000th and the 4,001st of a CLOB Oracle
     * has made, as a CLOB put together or cut is, comes out of `JSON_SCALAR`, `JSON_ARRAY` and `JSON_OBJECT` as two U+FFFD;
     * in ASCII it is written `\uD83D\uDE00`, and read back whole. The pieces are `VARCHAR2`s.
     *
     * **A workaround for the Oracle bug of [itemProperty] (#538), to be removed with it.** Taken out of the list as JSON,
     * `JSON_QUERY(list, '$[0]' RETURNING JSON)`, a text past 32 KB closed the session over two rows (ORA-03113, or ORA-00600
     * `[kghfrh:ds]`), as #255's did. To remove it, take the text out as JSON again, once an Oracle release no longer crashes on
     * the repro in `docs/oracle-long-json-string-crash.md`, and `CypherQueryOracleTest`'s "a text past 32 KB reversed" test
     * still passes without it.
     */
    private fun reversedPieces(text: String): String {
        fun pairStarts(unit: String) = "LENGTH(REGEXP_SUBSTR($text, '.', $unit, 1, 'n')) = 2"
        val first = "((LEVEL - 1) * $REVERSED_PIECE + 1 + CASE WHEN LEVEL > 1 AND ${pairStarts("(LEVEL - 1) * $REVERSED_PIECE")} THEN 1 ELSE 0 END)"
        val last = "(LEVEL * $REVERSED_PIECE + CASE WHEN ${pairStarts("LEVEL * $REVERSED_PIECE")} THEN 1 ELSE 0 END)"
        val cut = "SELECT LEVEL n, DBMS_LOB.SUBSTR($text, $last - $first + 1, $first) s FROM DUAL CONNECT BY LEVEL <= CEIL(LENGTH($text) / $REVERSED_PIECE)"
        val joined = "JSON_SERIALIZE(JSON_ARRAYAGG('x' || rv_reversed.r ORDER BY rv_reversed.n DESC RETURNING JSON) RETURNING CLOB ASCII)"
        return "(SELECT JSON(JSON_QUERY(REPLACE(REPLACE($joined, '\",\"x'), '[\"x', '[\"'), '\$[0]' RETURNING CLOB ASCII)) " +
            "FROM (SELECT rv_piece.n, ${reverseText("rv_piece.s")} r FROM ($cut) rv_piece WHERE rv_piece.s IS NOT NULL) rv_reversed)"
    }

    /** `ROW_NUMBER()` takes an order of its own; `ROWNUM` counts the rows as the query gives them. */
    override val rowNumber = "ROWNUM"

    /**
     * Oracle's `UUID()`, a random version 4 UUID, as Neo4j's is, written in lower case with hyphens (#401). Oracle added it
     * in 23.9, which is why Drivine needs 23.9 or later: see `JdbcConnectionProvider.oracleRefusal`. Not `SYS_GUID()`, whose
     * consecutive values share all but a counter's digits, which left a stamp's token about 16 bits that changed, in order.
     * Short enough not to be bound once for the statement (see [SHARED_ABOVE]), so each row gets a UUID of its own.
     */
    override fun uuid() = "LOWER(REGEXP_REPLACE(RAWTOHEX(UUID()), '(.{8})(.{4})(.{4})(.{4})(.{12})', '\\1-\\2-\\3-\\4-\\5'))"

    /**
     * Worked out as a `NUMBER`, then made the nearest double: Oracle fails where Neo4j gives NaN or an infinity, `LN(0)`,
     * `SQRT(-1)`, `ASIN(2)` and `1 / TAN(0)` among them, and where a `NUMBER` overflows, past 1e126, as `EXP(300)` does.
     * One too small for a `NUMBER`, below 1e-130, is 0, as `EXP(-1000)` is in Java, and `EXP(-300)` is not.
     * A `BINARY_DOUBLE` would give NaN or an infinity, which JSON holds neither of.
     */
    override fun math(name: String, numbers: List<String>): String {
        val x = numbers[0]
        return float(
            when (name) {
                "log" -> "LN($x)"
                "log10" -> "LOG(10, $x)"
                "cot" -> "(1 / TAN($x))"
                // In doubles from the one nearest the cosine, as Java works it out: near 0, that loses digits a NUMBER keeps.
                "haversin" -> "((1 - TO_BINARY_DOUBLE(COS($x))) / 2)"
                // In doubles, by the ratio Java multiplies by: see [RADIANS_TO_DEGREES].
                "degrees" -> "(TO_BINARY_DOUBLE($x) * $RADIANS_TO_DEGREES)"
                "radians" -> "(TO_BINARY_DOUBLE($x) * $DEGREES_TO_RADIANS)"
                // Oracle overflows at ATAN2(0, 0), which Java gives as 0.0: a NUMBER has no negative zero to give π for.
                "atan2" -> shared(x, numbers[1]) { (y, z) -> "CASE WHEN $y = 0 AND $z = 0 THEN 0 ELSE ATAN2($y, $z) END" }
                else -> "${name.uppercase()}($x)"
            }
        )
    }

    /** SUBSTR from the end gives null where it is asked for more than there is. */
    override fun rightText(text: String, count: String) =
        shared(text, count) { (t, n) -> "CASE WHEN $n >= LENGTH($t) THEN $t WHEN $n > 0 THEN SUBSTR($t, -$n) END" }

    /** An anonymous block, which returns its rows as an implicit result. */
    override fun procedure(program: List<ProcedureStep>) = keptLists(program).let { kept -> (listOf(
        "DECLARE",
        // Text, read as JSON where each parameter is read: see [propertyOf].
        "    $PARAMS CLOB := \$${Parameters.OBJECT};",
        "    $ROWS JSON;",
        // A list in memory, which a row is appended to in place: see [keep].
        "    $OUT ${if (OUT in kept) "JSON_ARRAY_T" else "JSON"};",
        "    $ROW JSON;",
        "    $VALUE JSON;",
        "    $ID NUMBER;",
        "    $COUNT NUMBER;",
        "    $FOUND BOOLEAN;",
        "    $CURSOR SYS_REFCURSOR;",
        "    ${OracleTemporalSql.NOW} TIMESTAMP;",
    ) + (1..NESTING).flatMap { listOf("    $SAVED$it JSON;", "    $GATHERED$it JSON;", "    $CALLER$it JSON;") } +
        kept.filter { it != OUT }.map { "    $it JSON_ARRAY_T;" } + listOf(
        "BEGIN",
        // The clock is read once, by a query: see [OracleTemporalSql.now].
        "    SELECT ${OracleTemporalSql.CLOCK} INTO ${OracleTemporalSql.NOW} FROM DUAL;",
        indent(statements(program).joinToString("\n")),
        "END;",
    )).joinToString("\n") }

    /**
     * The rows of [query], returned as an implicit result: a row of `JSON` each, or with [rowsAsText] all of them as one
     * row, a JSON list in their order, as text in a column named [ROWS_AS_TEXT]. Its text is extended JSON, so a value
     * plain JSON has no text for is read back as it is: a `BINARY_DOUBLE` is `{"$numberDouble": 2}` (`"Nan"`, `"Inf"` and
     * `"-Inf"` for NaN and the infinities), as a whole float written `2` would be read as the whole number 2. One row, as
     * node-oracledb's thin mode gives only the prefetched rows of an implicit result with a `JSON` or a `CLOB` column
     * (oracle/node-oracledb#1795), and a row's text may be longer than a `VARCHAR2`.
     */
    override fun returnRows(query: String): List<String> {
        if (!rowsAsText) return listOf("OPEN $CURSOR FOR\n${indent(query)};", "DBMS_SQL.RETURN_RESULT($CURSOR);")
        val numbered = "SELECT cy_q.result, ROWNUM AS cy_n FROM (\n${indent(query)}\n) cy_q"
        val rows = "COALESCE(JSON_ARRAYAGG(cy_r.result ORDER BY cy_r.cy_n NULL ON NULL RETURNING JSON), JSON('[]'))"
        return listOf(
            "OPEN $CURSOR FOR\n${indent("SELECT JSON_SERIALIZE($rows RETURNING CLOB EXTENDED) AS \"$ROWS_AS_TEXT\" FROM (\n${indent(numbered)}\n) cy_r")};",
            "DBMS_SQL.RETURN_RESULT($CURSOR);",
        )
    }

    /**
     * Oracle's `SIGN` gives 0 for a positive number under 1.0E-128, from 1.0E-130 up, which NUMBER holds and compares
     * as greater than 0, and is right from 1.0E-128 up, for a negative number, 0, and null. So the number is held to
     * between -1 and 1, where a thousand times it cannot overflow, and that is signed, past 1.0E-128 where it is not 0:
     * [number] is read once, so a sign of a sign does not write it out twice at each level.
     */
    override fun signOf(number: String) = "SIGN(GREATEST(LEAST($number, 1), -1) * 1000)"

    override fun roundPlaces(number: String, precision: String) = pastItsDigits(number, precision) { n, p -> super.roundPlaces(n, p) }

    override fun roundTo(number: String, precision: String, mode: RoundingMode) = pastItsDigits(number, precision) { n, p -> super.roundTo(n, p, mode) }

    /**
     * [round] of [number] to [precision] places, or [number] itself where those places are past its digits: Oracle's NUMBER
     * stops near 10^126, so `ROUND(n, 400)`, and the scale `10^p` a mode rounds by, overflow (ORA-01426) where Neo4j gives
     * the number back. Neo4j rounds a double, of [DOUBLE_DIGITS] significant digits at most, so rounding it to places past
     * its last digit changes nothing; a number Oracle holds to more digits is a double again once rounded. Oracle works
     * out only the branch of a CASE a row takes, so the overflowing one is never reached. A null precision makes the test
     * null, so the row takes the rounding, which gives null. The digits are compared in decimal, not by `LOG(10, ABS(n))`,
     * which fails (ORA-01428) on 0 and on the least numbers NUMBER holds, near 1.0E-130; zero is given back where
     * `10^(17 - p)` underflows to it, and rounded to 0 short of that.
     * Short of its digits, a tiny number may still take more than [MOST_PLACES] places, as 1.0E-120 to 130 does: it is
     * rounded moved that many places fewer to the right, then moved back, which is exact in decimal.
     */
    private fun pastItsDigits(number: String, precision: String, round: (String, String) -> String) = shared(number, precision) { (n, p) ->
        val past = "GREATEST($p - $MOST_PLACES, 0)"
        val shift = "POWER(10, $past)"
        "CASE WHEN ABS($n) >= POWER(10, $DOUBLE_DIGITS - $p) THEN $n ELSE ${round("($n * $shift)", "($p - $past)")} / $shift END"
    }

    /**
     * NUMBER holds no infinity, so `JSON_VALUE(… RETURNING NUMBER)` of a stored one is null: round() gives an infinity
     * back, as Neo4j does, from the `BINARY_DOUBLE` it is (drivine4j#669). Its precision and mode are still read, by a
     * round of 0 by them, as Postgres and SQLite read them for an infinity: a null one gives null, and a precision out of
     * range fails.
     */
    override fun infinitiesKept(value: Value, json: () -> String, round: (Value) -> Value.Json): Value.Json =
        Value.Json(shared(json()) { (x) ->
            val zero = round(Value.Json(literalJson(0))).sql
            "CASE WHEN ${kind(x)} = 'double' AND JSON_VALUE($x, '\$' RETURNING BINARY_DOUBLE) IS INFINITE " +
                "THEN CASE WHEN NOT (${isJsonNull(zero)}) THEN $x END ELSE ${round(Value.Json(x)).sql} END"
        })

    /** A text that is no number, read as one: Oracle fails with ORA-01722, whose ORA-03302 detail gives the text. */
    override fun failure(message: String, value: String) = "TO_NUMBER(${literal("$message ")} || TO_CHAR($value))"

    override fun raise(message: String) = "RAISE_APPLICATION_ERROR(-20000, ${literal(message)});"

    override fun idOf(json: String, key: String?) = "JSON_VALUE($json, ${key?.let { OracleSql.path(it) } ?: "'$'"} RETURNING NUMBER)"

    override fun appended(list: String, item: String) = "JSON_TRANSFORM($list, APPEND '\$' = $item)"

    override fun emptyKept() = "JSON_ARRAY_T()"

    /**
     * A `JSON_ARRAY_T` is a list held in memory, which `append` adds to in place: `JSON_TRANSFORM`'s `APPEND` on a `JSON`
     * list made the list anew for each row (#588).
     */
    override fun keep(list: String, item: String) = "$list.append($item);"

    override fun keptRows(list: String) = "$list.to_json"

    override fun stripNulls(json: String) = "JSON_MERGEPATCH(JSON('{}'), $json RETURNING JSON)"

    override fun merged(props: String, map: String) = "JSON_MERGEPATCH($props, $map RETURNING JSON)"

    override fun withoutLabel(labels: String, label: String) = "JSON_TRANSFORM($labels, REMOVE ${literal("\$[*]?(@ == ${OracleSql.quoted(label)})")})"

    /** The kind of the JSON [json] as Oracle names it, a floating-point number by its own name. */
    private fun kind(json: String) = "JSON_VALUE($json, '\$.type()')"

    companion object {
        /** The cursor a procedure returns its rows by. */
        private const val CURSOR = "cy_cursor"

        /**
         * The column a statement that writes returns its rows in with `rowsAsText`, as one JSON list's text: see [returnRows].
         * A host reads a column named `<name>:json` as JSON text, named `<name>`.
         */
        const val ROWS_AS_TEXT = "rows:json"

        /** A property as [propertyOf] reads it, its properties and its path, a literal whose quotes are doubled: see [propertyRead]. */
        val PROPERTY_READ = Regex("""JSON_QUERY\((.+), ('\$(?:[^']|'')*') RETURNING JSON\)""")

        /**
         * A float [literalJson] writes: see [written]. Not NaN, written for an item of a list the statement is given, which is
         * equal to nothing, where Oracle's JSON finds it equal to itself: beside it the other side is tested for NaN (#409).
         */
        private val WRITTEN_FLOAT = Regex("""JSON_SCALAR\(TO_BINARY_DOUBLE\('(?!NaN')[^']*'\)\)""")

        /** The characters Java's `Double.parseDouble` takes off a text's ends, U+0000 to the space: see `toFloat` in [functionOf]. */
        private val JAVA_SPACES = "UNISTR('${(0..0x20).joinToString("") { "\\" + it.toString(16).padStart(4, '0') }}')"

        /** NaN and the infinities by Java's names, as `Double.parseDouble` reads them: see `toFloat` in [functionOf]. */
        private const val JAVA_NAMES = "^([+-]?NaN|[+]?Infinity|-Infinity)\$"

        /** A quoted SQL text, its quotes doubled: see [nanFree]. */
        private val QUOTED = Regex("'[^']*(?:''[^']*)*'")

        /** What separates the words of a value the statement writes: see [nanFree]. */
        private val WRITTEN_SEPARATORS = Regex("[\\s(),]+")

        /**
         * The words of a literal ([literalJson]), a long text's `TO_CLOB` pieces among them ([textLiteral]), and of a list or a
         * map made of them ([jsonArray], [jsonObject]): see [nanFree].
         */
        private val WRITTEN_WORDS = setOf("JSON", "JSON_SCALAR", "TO_BINARY_DOUBLE", "TO_CLOB", "||", "JSON_ARRAY", "JSON_OBJECT", "VALUE", "NULL", "ON", "RETURNING")

        /**
         * The value at the path [path] in the JSON [json], with [passing] its `PASSING` clause where it has one: a text read with
         * that path from [json] written out as text, anything else as JSON. [typed] is the path to its kind, `.type()`. Read so
         * by [itemValue], [rowValue] and [listItem], and by [OracleLongReads] for an item's or a row's value.
         */
        internal fun throughText(json: String, path: String, typed: String, passing: String = "") =
            "CASE WHEN JSON_VALUE($json, $typed$passing) = 'string' " +
                "THEN JSON(JSON_QUERY(JSON_SERIALIZE($json RETURNING CLOB), $path$passing RETURNING CLOB)) ELSE JSON_QUERY($json, $path$passing RETURNING JSON) END"

        /**
         * The value at the path [path] in the stored properties [props], read through its text as [itemProperty] reads one:
         * only that path kept, written out as extended JSON, and read back from that text. Read so by [OracleLongReads] too.
         */
        internal fun keptText(props: String, path: String) =
            "JSON(JSON_QUERY(JSON_SERIALIZE(JSON_TRANSFORM($props, KEEP $path) RETURNING CLOB EXTENDED), $path RETURNING CLOB) EXTENDED)"

        /** The path of the value of the entry, among a map's entries as `keyvalue()` lists them, whose key the variable `k` holds: see [keyed]. */
        private const val KEYED = "'\$[*]?(@.key == \$k).value'"

        /** The most bytes a `VARCHAR2` holds in SQL, as a database is made by default (`MAX_STRING_SIZE = STANDARD`). */
        const val SHORT_TEXT = 4000

        /**
         * [text] as SQL text: one string literal where it is written in at most [SHORT_TEXT] bytes, its quotes doubled, as Oracle
         * takes no longer one (ORA-01704, drivine4j#419); else a CLOB, `TO_CLOB` of literals of at most [SHORT_TEXT] bytes each,
         * joined with `||`. A piece ends only between two characters, never within a surrogate pair or a JSON escape (`\n`,
         * `\u0001`), and its quotes are doubled after the text is cut, so none is cut in two.
         */
        private fun textLiteral(text: String): String {
            if (literalBytes(text) <= SHORT_TEXT) return OracleSql.literal(text)
            val pieces = mutableListOf<String>()
            var start = 0
            var bytes = 0
            var i = 0
            while (i < text.length) {
                val end = when {
                    text[i] == '\\' && i + 1 < text.length -> if (text[i + 1] == 'u') minOf(i + 6, text.length) else i + 2
                    text[i].isHighSurrogate() && i + 1 < text.length && text[i + 1].isLowSurrogate() -> i + 2
                    else -> i + 1
                }
                val size = literalBytes(text.substring(i, end))
                if (bytes + size > SHORT_TEXT) {
                    pieces += text.substring(start, i)
                    start = i
                    bytes = 0
                }
                bytes += size
                i = end
            }
            pieces += text.substring(start)
            return pieces.joinToString(" || ") { "TO_CLOB(${OracleSql.literal(it)})" }
        }

        /** The bytes [text] is written in as a SQL string literal: its UTF-8, a quote doubled, and not the quotes around it. */
        private fun literalBytes(text: String) = org.drivine.cypher.text.JavaText.utf8(text).size + text.count { it == '\'' }

        /** The hint that turns Oracle's JSON table transformation off: see [planned]. */
        private const val NO_JSON_TABLE_TRANSFORM = "NO_JSON_TABLE_TRANSFORM"

        /** The hint that plans a statement without statistics feedback: see [planned]. */
        @DrivineInternalApi
        const val NO_FEEDBACK = "OPT_PARAM('_optimizer_use_feedback' 'false')"

        /** The rows [fewRows] estimates a list of ids at: few enough that Oracle reads each node it gives by its key. */
        @DrivineInternalApi
        const val FEW_ROWS = 10

        /**
         * A test of a column of labels, `n1.labels`, as [hasLabel] writes it, `JSON_EXISTS(n1.labels, '$[*]?(@ == "X")')`,
         * and the label, written as JSON writes a string and then as SQL quotes one: see [deletedIds].
         */
        private val INDEXED_LABEL = Regex("""JSON_EXISTS\(([\w.]+\.labels), '\$\[\*\]\?\(@ == "((?:[^"\\]|\\.)*)"\)'\)""")

        /** A query, or a subquery of one, that keeps its rows distinct, hinted or not: see [called]. */
        private val DISTINCT_SELECT = Regex("""\bSELECT (?:/\*\+ .*? \*/ )?DISTINCT\b""")

        /** A query that opens with hints, and those hints: Oracle reads the first hint comment of a query alone. */
        private val HINTED = Regex("""SELECT /\*\+ (.*?) \*/""")

        /**
         * The most UTF-16 units of a text `INSTR` looks for in a CLOB: 32,767 of one byte, or of three, or 16,385 pairs, each
         * two units; one more it refuses (ORA-22828). See [containsText].
         */
        const val SEARCHED_TEXT = 32_767

        /** How many units of a longer text [containsText] looks for first, with `INSTR`, before comparing it whole there. */
        const val SEARCHED_PREFIX = 1000

        /**
         * The most places [containsText] compares a long text at, as `MODEL`'s `ITERATE` takes a number: 2^32 - 1, far more
         * than a stored text has units, so the search stops where the text is found or no place is left first.
         */
        const val SEARCHED_PLACES = 4_294_967_295L

        /**
         * The most characters of a text [sharedSum] binds as JSON: one of 40,001, bound as JSON beside a long value, closed the
         * session (#370), where one of 12,000 three-byte characters, 36 KB, did not, so a text is counted in characters, as
         * `.length()` counts them. One as long or shorter is bound as it was.
         */
        const val BOUND_TEXT = 32_767

        /**
         * How many UTF-16 units of a CLOB [reversedPieces] reverses as one `VARCHAR2`: with one more, where a pair is not cut,
         * and the `x` the piece is put together after, at most 3,002 bytes, as a unit is three bytes at most and a pair four.
         */
        const val REVERSED_PIECE = 1000

        /** The most bytes [asText] writes a number in: Java's text of a double, `-2.2250738585072014E-308`, and some to spare. */
        const val NUMBER_TEXT = 32

        /** The key of a double in Oracle's extended JSON. */
        private const val DOUBLE = "\$numberDouble"

        /** NaN, as `JSON_SERIALIZE(… EXTENDED)` writes it: see [holdsNaN]. */
        private const val EXTENDED_NAN = "{\"\$numberDouble\":\"Nan\"}"

        /** The function a query declares that compares two values that may hold a null, where the store keeps none: see [nullsEqual]. */
        private const val NULLS_EQUAL = "cy_nulls_equal"

        /** The function a query declares that compares two lists as Cypher orders them: see [listOrder]. */
        private const val LIST_ORDER = "cy_list_order"

        /** How many significant digits a double holds at most, as Neo4j rounds one. */
        private const val DOUBLE_DIGITS = 17

        /** The most places [pastItsDigits] rounds to, whose scale `10^p` is well inside the NUMBER range. */
        private const val MOST_PLACES = 100

        /** The function the store keeps beside the graph [graph]'s tables that compares two values that may hold a null: see [nullsEqual]. */
        @DrivineInternalApi
        fun nullsEqualName(graph: String) = "${graph}_nulls_equal"

        /** The function the store keeps beside the graph [graph]'s tables that renumbers the year of an ISO text as Oracle does: see [eraFunction]. */
        @DrivineInternalApi
        fun eraName(graph: String) = "${graph}_era"

        /** The function the store keeps beside the graph [graph]'s tables that renumbers the year Oracle writes as ISO 8601 does: see [eraDateFunction]. */
        @DrivineInternalApi
        fun eraDateName(graph: String) = "${graph}_era_date"

        /**
         * The function [name], which gives the ISO 8601 text `t` of a date or a moment whose year may be BC as Oracle numbers
         * that year, with no year 0: the year `0000` (1 BC) as `-0001`, and `-0044` (45 BC) as `-0045`. Any other text, and
         * null, it gives as it is. A text whose first character's code is below [TemporalSql.BC_BELOW] starts with `-` or `0`,
         * as one with a year BC does. See `OracleTemporalSql.era`, which calls it where the store keeps it: the text is
         * written once, where the test and the regular expression it stands for wrote it four times, and Oracle's hard parse
         * of a statement grows with each copy (drivine#572).
         *
         * The store's copy is part of the store layout the three libraries share (see AGENTS.md), as [nullsEqualFunction]'s
         * is: it is `CREATE OR REPLACE ` and this text with [name] as [eraName] gives it, the graph's name as written and then
         * `_era`, its lines joined by `\n` with no newline after the last. Changing a byte of it changes the layout for all
         * three, so it changes in all three together.
         */
        @DrivineInternalApi
        fun eraFunction(name: String): String = listOf(
            "FUNCTION $name(t VARCHAR2) RETURN VARCHAR2 DETERMINISTIC IS",
            "BEGIN",
            "    IF ASCII(t) < ${TemporalSql.BC_BELOW} THEN",
            "        RETURN REGEXP_REPLACE(t, '^(-[0-9]{4}|0000)', TO_CHAR(TO_NUMBER(REGEXP_SUBSTR(t, '^-?[0-9]{4}')) - 1, 'FM0000'));",
            "    END IF;",
            "    RETURN t;",
            "END;",
        ).joinToString("\n")

        /**
         * The function [name], which gives the text `t` that `LTRIM(TO_CHAR(…, 'SYYYY-MM-DD…'))` writes of a timestamp,
         * its year numbered as ISO 8601 numbers it where it is BC: Oracle's `-0001` (1 BC) as `0000`, and `-0045` (45 BC) as
         * `-0044`. Any other text, and null, it gives as it is. See `OracleTemporalSql.date`, which calls it where the store
         * keeps it: the timestamp is written once, where the test and the regular expression it stands for wrote it three times.
         *
         * The store's copy is part of the store layout the three libraries share (see AGENTS.md), as [nullsEqualFunction]'s
         * is: it is `CREATE OR REPLACE ` and this text with [name] as [eraDateName] gives it, the graph's name as written and
         * then `_era_date`, its lines joined by `\n` with no newline after the last. Changing a byte of it changes the layout
         * for all three, so it changes in all three together.
         */
        @DrivineInternalApi
        fun eraDateFunction(name: String): String = listOf(
            "FUNCTION $name(t VARCHAR2) RETURN VARCHAR2 DETERMINISTIC IS",
            "BEGIN",
            "    IF SUBSTR(t, 1, 1) = '-' THEN",
            "        RETURN TO_CHAR(TO_NUMBER(SUBSTR(t, 1, 5)) + 1, 'FM0000') || SUBSTR(t, 6);",
            "    END IF;",
            "    RETURN t;",
            "END;",
        ).joinToString("\n")

        /**
         * The function [name], which compares two JSON values as Cypher compares values that may hold a null: 1 where
         * they are equal, 0 where they differ, and null where a pair of their items holds a null and no pair differs. It
         * walks the two together with `JSON_ELEMENT_T`, list item by list item and map key by map key, at any depth. A
         * query declares it with `WITH FUNCTION`; the store keeps it, made with the graph, as `CREATE OR REPLACE` of the same
         * text (see `OracleGraph`), which Drivine for TypeScript and for Python make byte for byte alike.
         *
         * The store's copy is part of the store layout the three libraries share (see AGENTS.md): it is
         * `CREATE OR REPLACE ` and this text with [name] as [nullsEqualName] gives it, the graph's name as written and then
         * `_nulls_equal`, its lines joined by `\n` with no newline after the last (the `//` comment below is not in it).
         * Changing a byte of it changes the layout for all three, so it changes in all three together.
         */
        @DrivineInternalApi
        fun nullsEqualFunction(name: String): String = listOf(
            "FUNCTION $name(cy_x JSON, cy_y JSON) RETURN NUMBER IS",
            "    FUNCTION eq(a JSON_ELEMENT_T, b JSON_ELEMENT_T) RETURN NUMBER IS",
            "        r NUMBER := 1;",
            "        e NUMBER;",
            "        la JSON_ARRAY_T;",
            "        lb JSON_ARRAY_T;",
            "        oa JSON_OBJECT_T;",
            "        ob JSON_OBJECT_T;",
            "        ks JSON_KEY_LIST;",
            "        too_long EXCEPTION;",
            "        PRAGMA EXCEPTION_INIT(too_long, -40478);",
            "    BEGIN",
            "        IF a.is_Null OR b.is_Null THEN RETURN NULL; END IF;",
            "        IF a.is_Array AND b.is_Array THEN",
            "            la := TREAT(a AS JSON_ARRAY_T);",
            "            lb := TREAT(b AS JSON_ARRAY_T);",
            "            IF la.get_Size <> lb.get_Size THEN RETURN 0; END IF;",
            "            FOR i IN 0 .. la.get_Size - 1 LOOP",
            "                e := eq(la.get(i), lb.get(i));",
            "                IF e = 0 THEN RETURN 0; END IF;",
            "                IF e IS NULL THEN r := NULL; END IF;",
            "            END LOOP;",
            "            RETURN r;",
            "        END IF;",
            "        IF a.is_Object AND b.is_Object THEN",
            "            oa := TREAT(a AS JSON_OBJECT_T);",
            "            ob := TREAT(b AS JSON_OBJECT_T);",
            "            IF oa.get_Size <> ob.get_Size THEN RETURN 0; END IF;",
            "            ks := oa.get_Keys;",
            "            FOR i IN 1 .. ks.COUNT LOOP",
            "                IF NOT ob.has(ks(i)) THEN RETURN 0; END IF;",
            "                e := eq(oa.get(ks(i)), ob.get(ks(i)));",
            "                IF e = 0 THEN RETURN 0; END IF;",
            "                IF e IS NULL THEN r := NULL; END IF;",
            "            END LOOP;",
            "            RETURN r;",
            "        END IF;",
            "        IF a.is_Number AND b.is_Number THEN RETURN CASE WHEN a.to_Number = b.to_Number THEN 1 ELSE 0 END; END IF;",
            // As VARCHAR2s, about 3.5 times as fast as CLOBs; as CLOBs where one is past 32 KB, which to_String
            // raises on (ORA-40478).
            "        IF a.is_String AND b.is_String THEN",
            "            BEGIN",
            "                RETURN CASE WHEN a.to_String = b.to_String THEN 1 ELSE 0 END;",
            "            EXCEPTION WHEN too_long THEN",
            "                RETURN CASE WHEN DBMS_LOB.COMPARE(a.to_Clob, b.to_Clob) = 0 THEN 1 ELSE 0 END;",
            "            END;",
            "        END IF;",
            "        IF a.is_Boolean AND b.is_Boolean THEN RETURN CASE WHEN a.to_Boolean = b.to_Boolean THEN 1 ELSE 0 END; END IF;",
            "        RETURN 0;",
            "    END;",
            "BEGIN",
            "    RETURN eq(JSON_ELEMENT_T.parse(JSON_SERIALIZE(cy_x RETURNING CLOB)), JSON_ELEMENT_T.parse(JSON_SERIALIZE(cy_y RETURNING CLOB)));",
            "END;",
        ).joinToString("\n")

        /**
         * The function [name], which gives where the JSON list `cy_x` comes beside the JSON list `cy_y` as Cypher orders two values in
         * a comparison (see `SqlCypherCompiler.listOrder`): -1, 0 or 1, and null where the pair that decides is not ordered. It walks
         * the two together with `JSON_ELEMENT_T`, as [nullsEqualFunction]'s does. A text is compared as a `VARCHAR2`, by its bytes,
         * which in UTF-8 order as its code points do; Oracle's empty text is null there, and comes before any other.
         */
        internal fun listOrderFunction(name: String): String = listOf(
            "FUNCTION $name(cy_x JSON, cy_y JSON) RETURN NUMBER IS",
            // The double extended JSON writes as `{"${'$'}numberDouble": "1.5"}`, NaN as "Nan" and the infinities as "Inf" and "-Inf"; null for any other value.
            "    FUNCTION dbl(a JSON_ELEMENT_T) RETURN BINARY_DOUBLE IS",
            "        o JSON_OBJECT_T;",
            "        s VARCHAR2(64);",
            "    BEGIN",
            "        IF NOT a.is_Object THEN RETURN NULL; END IF;",
            "        o := TREAT(a AS JSON_OBJECT_T);",
            "        IF o.get_Size <> 1 OR NOT o.has('\$numberDouble') THEN RETURN NULL; END IF;",
            "        s := o.get_String('\$numberDouble');",
            "        RETURN TO_BINARY_DOUBLE(CASE WHEN s = 'Nan' THEN 'NaN' ELSE s END DEFAULT NULL ON CONVERSION ERROR);",
            "    END;",
            "    FUNCTION cmp(a JSON_ELEMENT_T, b JSON_ELEMENT_T) RETURN NUMBER IS",
            "        c NUMBER;",
            "        sa VARCHAR2(32767);",
            "        sb VARCHAR2(32767);",
            "        la JSON_ARRAY_T;",
            "        lb JSON_ARRAY_T;",
            "        oa JSON_OBJECT_T;",
            "        ob JSON_OBJECT_T;",
            "        ks JSON_KEY_LIST;",
            "        too_long EXCEPTION;",
            "        PRAGMA EXCEPTION_INIT(too_long, -40478);",
            "        da BINARY_DOUBLE;",
            "        db BINARY_DOUBLE;",
            "    BEGIN",
            "        IF a.is_Null OR b.is_Null THEN RETURN NULL; END IF;",
            // A double, as extended JSON writes it, beside a number: NaN is ordered with nothing, and an infinity after any other.
            "        IF (dbl(a) IS NOT NULL OR a.is_Number) AND (dbl(b) IS NOT NULL OR b.is_Number) AND (dbl(a) IS NOT NULL OR dbl(b) IS NOT NULL) THEN",
            "            da := NVL(dbl(a), TO_BINARY_DOUBLE(a.to_Number));",
            "            db := NVL(dbl(b), TO_BINARY_DOUBLE(b.to_Number));",
            "            IF da IS NAN OR db IS NAN THEN RETURN NULL; END IF;",
            "            RETURN CASE WHEN da < db THEN -1 WHEN da > db THEN 1 ELSE 0 END;",
            "        END IF;",
            "        IF a.is_Array AND b.is_Array THEN",
            "            la := TREAT(a AS JSON_ARRAY_T);",
            "            lb := TREAT(b AS JSON_ARRAY_T);",
            "            FOR i IN 0 .. LEAST(la.get_Size, lb.get_Size) - 1 LOOP",
            "                c := cmp(la.get(i), lb.get(i));",
            "                IF c IS NULL OR c <> 0 THEN RETURN c; END IF;",
            "            END LOOP;",
            "            RETURN SIGN(la.get_Size - lb.get_Size);",
            "        END IF;",
            // Two maps are equal or not ordered: Cypher orders maps, which the compiler does not yet.
            "        IF a.is_Object AND b.is_Object THEN",
            "            oa := TREAT(a AS JSON_OBJECT_T);",
            "            ob := TREAT(b AS JSON_OBJECT_T);",
            "            IF oa.get_Size <> ob.get_Size THEN RETURN NULL; END IF;",
            "            ks := oa.get_Keys;",
            "            FOR i IN 1 .. ks.COUNT LOOP",
            "                IF NOT ob.has(ks(i)) THEN RETURN NULL; END IF;",
            "                IF NVL(cmp(oa.get(ks(i)), ob.get(ks(i))), 1) <> 0 THEN RETURN NULL; END IF;",
            "            END LOOP;",
            "            RETURN 0;",
            "        END IF;",
            "        IF a.is_Number AND b.is_Number THEN RETURN SIGN(a.to_Number - b.to_Number); END IF;",
            "        IF a.is_String AND b.is_String THEN",
            "            BEGIN",
            "                sa := a.to_String;",
            "                sb := b.to_String;",
            "            EXCEPTION WHEN too_long THEN",
            "                RETURN SIGN(DBMS_LOB.COMPARE(a.to_Clob, b.to_Clob));",
            "            END;",
            "            IF sa IS NULL OR sb IS NULL THEN RETURN CASE WHEN sb IS NOT NULL THEN -1 WHEN sa IS NOT NULL THEN 1 ELSE 0 END; END IF;",
            "            RETURN CASE WHEN sa = sb THEN 0 WHEN sa < sb THEN -1 ELSE 1 END;",
            "        END IF;",
            "        IF a.is_Boolean AND b.is_Boolean THEN RETURN CASE WHEN a.to_Boolean = b.to_Boolean THEN 0 WHEN b.to_Boolean THEN -1 ELSE 1 END; END IF;",
            "        RETURN NULL;",
            "    END;",
            "BEGIN",
            "    RETURN cmp(JSON_ELEMENT_T.parse(JSON_SERIALIZE(cy_x RETURNING CLOB EXTENDED)), JSON_ELEMENT_T.parse(JSON_SERIALIZE(cy_y RETURNING CLOB EXTENDED)));",
            "END;",
        ).joinToString("\n")
    }
}
