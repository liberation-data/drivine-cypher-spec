package org.drivine.cypher.oracle

import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.dataformat.yaml.YAMLFactory
import org.drivine.cypher.api.CompileJson
import java.io.File
import java.math.BigDecimal
import java.time.LocalDateTime
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter

/**
 * The JVM's answers, which the other targets are checked against, byte for byte (`./gradlew jvmOracle`):
 *
 * - `corpus.jsonl`: every statement in the spec's files, compiled for Postgres, Oracle and SQLite, and a few with typed
 *   parameters, Oracle's graph options, and on Oracle with `oracleRowsAsText` each statement whose SQL it changes and
 *   [ROWS_AS_TEXT_WRITES], with `oracleReencodeJson` each statement whose SQL it changes, and with `oracleReadsAsText`
 *   each statement whose SQL it changes: each line `{"request": …, "answer": …}`, both JSON text, the answer as [CompileJson] gives it on the JVM.
 *   A statement whose SQL embeds the moment it was compiled at, as SQLite's does of `datetime()` and the like, is left
 *   out: no two runs give it alike. It is found by its SQL holding that moment, so no list of statements is kept.
 * - `doubles.txt`: doubles as Java writes them (`T<tab>bits<tab>text`) and texts as Java reads them
 *   (`P<tab>bits<tab>text`, the bits `-` where Java throws NumberFormatException). See [Doubles].
 * - `standins-<name>.txt`: what Java gives where the module stands in for it, a file for each stand-in. See [StandIns].
 * - `mutants.jsonl`: a sample of the spec's statements mutated, whose answers the parser's syntax refusal isn't, as
 *   `corpus.jsonl` has them. See [Mutants].
 * - `store.json`: the spec's `store.yaml`, the store layout's DDL, as JSON, which every target's JSON reader reads: the
 *   other targets have no YAML reader. See `StoreDdlTest`.
 * - `marker.json`: the spec's `marker.yaml`, the schema marker and the stamps, as JSON likewise. See `StoreMarkerTest`.
 * - `plans.json`: the spec's `plans.yaml`, the schema plan's cases, as JSON likewise. See `StorePlanTest`.
 * - `host.json`: the spec's `host.yaml`, the host functions' cases, as JSON likewise. See [HostSpecJson] and `HostSpecTest`.
 * - `search-golden.json`: drivine4j's full-text search statements, the module's golden (`src/commonTest/resources`), for
 *   each target's `FullTextSearchGoldenTest`. Its requests are in `corpus.jsonl` too, as [CompileJson] answers them.
 *
 *   Oracle <spec directory> <output directory> <fuzz count> <seed> <mutant count>
 */
object Oracle {
    private val json = ObjectMapper()
    private val yaml = ObjectMapper(YAMLFactory())

    @JvmStatic
    fun main(args: Array<String>) {
        val spec = File(args[0])
        val out = File(args[1]).apply { mkdirs() }
        val count = args.getOrElse(2) { "100000" }.toInt()
        val seed = args.getOrElse(3) { "646" }.toLong()
        val mutantCount = args.getOrElse(4) { "10000" }.toInt()
        val corpus = corpus(spec, File(out, "corpus.jsonl"))
        val mutants = Mutants.write(statements(spec), STORES, File(out, "mutants.jsonl"), mutantCount, seed, ::answerUnlessClocked)
        // Fewer than asked for would pass the checks over less than the README says they cover.
        check(mutants.written == mutantCount) {
            "jvmOracle: ${mutants.kept} mutants of interest, not the $mutantCount asked for: ask for fewer (-Pmutants.count) or mutate each statement more (-Poracle.jvmArgs=-Dmutants.perStatement=<n>)."
        }
        val doubles = Doubles.write(File(out, "doubles.txt"), count, seed)
        val standIns = StandIns.write(out, seed)
        // The store layout's DDL, for each target's StoreDdlTest.
        File(out, "store.json").writeText(json.writeValueAsString(yaml.readTree(File(spec, "store.yaml"))))
        // The schema marker and the stamps, for each target's StoreMarkerTest.
        File(out, "marker.json").writeText(json.writeValueAsString(yaml.readTree(File(spec, "marker.yaml"))))
        // The schema plan's cases, for each target's StorePlanTest.
        File(out, "plans.json").writeText(json.writeValueAsString(yaml.readTree(File(spec, "plans.yaml"))))
        // The host functions' cases, for each target's HostSpecTest.
        HostSpecJson.write(spec, out)
        // drivine4j's full-text search statements, for each target's FullTextSearchGoldenTest.
        File(out, "search-golden.json").writeBytes(searchGolden())
        println("jvmOracle: ${corpus.first} statements in the corpus (${corpus.second} left out, as they embed the clock); $doubles double cases.")
        println("jvmOracle: ${mutants.made} mutant requests, ${mutants.kept} of interest, ${mutants.written} of them in mutants.jsonl.")
        println("jvmOracle: stand-in cases ${standIns.entries.joinToString { (name, n) -> "$name $n" }}.")
    }

    private val FILES = listOf("queries.yaml", "writes.yaml", "temporal.yaml", "refusals.yaml", "schema.yaml")
    internal val STORES = listOf("postgres", "oracle", "sqlite")

    /** Writes the corpus to [into]: how many lines, and how many statements left out for the clock. */
    private fun corpus(spec: File, into: File): Pair<Int, Int> {
        var lines = 0
        var clocked = 0
        into.printWriter().use { w ->
            for (request in corpusRequests(spec)) {
                val answer = answerUnlessClocked(request) ?: run {
                    clocked++
                    continue
                }
                w.println(json.writeValueAsString(linkedMapOf("request" to request, "answer" to answer)))
                lines++
            }
        }
        return lines to clocked
    }

    /**
     * The corpus's requests, in its order: each spec statement for each store, the typed ones, Oracle's graph options, Oracle's
     * rows as text, and requests that are no request.
     */
    internal fun corpusRequests(spec: File): List<String> = buildList {
        val statements = statements(spec)
        for ((cypher, parameters) in statements) for (store in STORES) add(request(store, cypher, parameters))
        for ((cypher, parameters) in TYPED) for (store in STORES) add(request(store, cypher, parameters))
        // Oracle's graph options.
        for (options in ORACLE_OPTIONS) {
            add(json.writeValueAsString(linkedMapOf("store" to "oracle", "graph" to options, "cypher" to "MATCH (p:Person {name: \$name}) WHERE p.age = \$age RETURN p.name, p.born", "parameters" to mapOf("name" to "Ada", "age" to 36))))
            add(json.writeValueAsString(linkedMapOf("store" to "oracle", "graph" to options, "cypher" to "MATCH (p:Person) WHERE p.nick = \$nick RETURN p", "parameters" to mapOf("nick" to null))))
            add(json.writeValueAsString(linkedMapOf("store" to "oracle", "graph" to options, "cypher" to "RETURN date({year: -44, month: 3, day: 15}) AS d")))
        }
        // Oracle's rows as text: each spec and typed statement whose SQL the option changes, a write that returns rows.
        for ((cypher, parameters) in statements + (TYPED + ROWS_AS_TEXT_WRITES).map { (cypher, parameters) -> Mutants.Statement(cypher, parameters) }) {
            val text = request("oracle", cypher, parameters, ROWS_AS_TEXT)
            if (CompileJson.compile(text) != CompileJson.compile(request("oracle", cypher, parameters))) add(text)
        }
        // Oracle's values encoded again (#165): each spec and typed statement whose SQL the option changes, every one that returns.
        for ((cypher, parameters) in statements + TYPED.map { (cypher, parameters) -> Mutants.Statement(cypher, parameters) }) {
            val again = request("oracle", cypher, parameters, REENCODED)
            if (CompileJson.compile(again) != CompileJson.compile(request("oracle", cypher, parameters))) add(again)
        }
        // Oracle's reads as text (#198): each spec statement whose SQL the option changes, every one that only reads.
        for ((cypher, parameters) in statements) {
            val text = request("oracle", cypher, parameters, READS_AS_TEXT)
            if (CompileJson.compile(text) != CompileJson.compile(request("oracle", cypher, parameters))) add(text)
        }
        // Full-text searches: each of the golden's requests, and searches that are no request.
        addAll(searchRequests())
        // Requests that are no request.
        addAll(listOf("", "[]", "{\"store\": \"neo4j\", \"graph\": {\"name\": \"g\"}, \"cypher\": \"RETURN 1\"}", "{\"store\": \"postgres\", \"cypher\": \"RETURN 1\"}"))
        // Compiled typed, last, so every request before is where it was: results.yaml's statements, and each other spec
        // statement whose answer typed differs from untyped by more than its columns, one that returns a node or a relationship.
        val results = statements(spec, listOf("results.yaml"))
        for ((cypher, parameters) in results) for (store in STORES) add(typedRequest(request(store, cypher, parameters)))
        for ((cypher, parameters) in statements - results.toSet()) for (store in STORES) {
            val untyped = request(store, cypher, parameters)
            val typed = typedRequest(untyped)
            val plain = CompileJson.compile(untyped)
            val answer = CompileJson.compile(typed)
            if (answer != plain && answer.substringBefore(",\"columns\":") != plain.removeSuffix("}")) add(typed)
        }
        // And on Oracle with its rows as text, each statement whose typed SQL the option changes: a write that returns a node or a relationship.
        for ((cypher, parameters) in results + statements) {
            val untyped = request("oracle", cypher, parameters, ROWS_AS_TEXT)
            val typed = typedRequest(untyped)
            val plain = CompileJson.compile(untyped)
            val answer = CompileJson.compile(typed)
            val changed = answer != CompileJson.compile(typedRequest(request("oracle", cypher, parameters)))
            if (changed && answer.substringBefore(",\"columns\":") != plain.removeSuffix("}")) add(typed)
        }
        // And on Oracle with its values encoded again (#165): results.yaml's statements, and each other one whose typed SQL
        // tags a node or a relationship, where the option changes it.
        for ((cypher, parameters) in results + (statements - results.toSet())) {
            val plain = CompileJson.compile(request("oracle", cypher, parameters))
            val plainTyped = CompileJson.compile(typedRequest(request("oracle", cypher, parameters)))
            val tags = plainTyped.substringBefore(",\"columns\":") != plain.removeSuffix("}")
            val again = typedRequest(request("oracle", cypher, parameters, REENCODED))
            if ((tags || results.any { it.cypher == cypher }) && CompileJson.compile(again) != plainTyped) add(again)
        }
        add("{\"store\": \"sqlite\", \"graph\": {\"name\": \"g\"}, \"typed\": \"yes\", \"cypher\": \"RETURN 1\"}")
    }

    /** [request], a request's JSON text, asking for its statement compiled typed. */
    private fun typedRequest(request: String): String = request.replaceFirst("{", "{\"typed\":true,")

    /** The module's golden of drivine4j's full-text search statements, `src/commonTest/resources/search-golden.json`. */
    private fun searchGolden(): ByteArray = requireNotNull(Oracle::class.java.getResource("/search-golden.json")) { "No search-golden.json on the classpath." }.readBytes()

    /** Written with every character outside ASCII escaped, so a lone surrogate in a query is a request's as it is. */
    private val ascii = ObjectMapper().also { it.factory.enable(com.fasterxml.jackson.core.JsonGenerator.Feature.ESCAPE_NON_ASCII) }

    /** Each search request of the golden, as JSON text, and those that are no request. */
    private fun searchRequests(): List<String> =
        json.readTree(searchGolden())["entries"].map { ascii.writeValueAsString(it["request"]) } + listOf(
            """{"store": "redis", "graph": {"name": "g"}, "search": {"labels": ["A"], "properties": ["t"], "query": "x"}}""",
            """{"store": "postgres", "graph": {"name": "g"}, "search": {"labels": ["A"], "properties": ["t"]}}""",
            """{"store": "postgres", "graph": {"name": "g"}, "search": {"labels": [], "properties": ["t"], "query": "x"}}""",
            """{"store": "postgres", "graph": {"name": "9g"}, "search": {"labels": ["A"], "properties": ["t"], "query": "x"}}""",
            """{"store": "postgres", "graph": {"name": "g"}, "search": {"label": "A", "properties": ["t"], "query": "x", "index": {"trigram": "yes"}}}""",
            """{"store": "neo4j", "search": {"labels": ["A"], "properties": ["t"], "query": "x"}}""",
            """{"store": "neo4j", "search": {"labels": ["A"], "properties": ["t"], "query": "x", "topK": 2147483647, "filtered": true}}""",
            """{"store": "sqlite", "graph": {"name": "g"}, "searchIndex": {"label": "A", "properties": ["t"]}}""",
            """{"store": "oracle", "graph": {"name": "g"}, "search": [1]}""",
        )

    /** Every statement in the spec's files, with its parameters, in the files' order. */
    internal fun statements(spec: File, files: List<String> = FILES): List<Mutants.Statement> = buildList {
        for (file in files) {
            val tree = yaml.readTree(File(spec, file))
            val cases = if (tree.isArray) tree else tree["cases"]
            for (case in cases) for (step in case["steps"] ?: continue) {
                val cypher = (step["cypher"]?.takeIf { it.isTextual } ?: step["ddl"])?.textValue() ?: continue
                val parameters = step["parameters"]?.let { typed(json.treeToValue(it, Any::class.java)) } ?: emptyMap<String, Any?>()
                add(Mutants.Statement(cypher, parameters))
            }
        }
    }

    /** [request]'s answer on the JVM, or null where it embeds the moment it was compiled (see [embedsClock]). */
    internal fun answerUnlessClocked(request: String): String? {
        val before = LocalDateTime.now(ZoneOffset.UTC)
        val answer = CompileJson.compile(request)
        return answer.takeUnless { embedsClock(it, before, LocalDateTime.now(ZoneOffset.UTC)) }
    }

    private val SECONDS = DateTimeFormatter.ofPattern("uuuu-MM-dd'T'HH:mm")

    /** Whether [answer] holds the moment it was compiled, between [before] and [after], as SQLite's SQL writes a moment. */
    private fun embedsClock(answer: String, before: LocalDateTime, after: LocalDateTime): Boolean =
        listOf(before, after).any { answer.contains("'" + SECONDS.format(it)) }

    private fun request(store: String, cypher: String, parameters: Map<String, Any?>, graph: Map<String, Any?> = mapOf("name" to "g")): String =
        json.writeValueAsString(linkedMapOf("store" to store, "graph" to graph, "cypher" to cypher, "parameters" to parameters))

    /**
     * The spec's values (see its README) as [CompileJson]'s typed JSON: `{$repeat, times}` written out, `{$float}` as it is,
     * and any other map keyed with `$` wrapped as `{$map}`, as the spec reads it as the map it is.
     */
    @Suppress("UNCHECKED_CAST")
    private fun typed(value: Any?): Map<String, Any?> = typedValue(value) as Map<String, Any?>

    private fun typedValue(value: Any?): Any? = when (value) {
        is Map<*, *> -> when {
            value.keys == setOf("\$repeat", "times") -> (value["\$repeat"] as String).repeat((value["times"] as Number).toInt())
            value.keys == setOf("\$float") -> value
            value.keys.any { it.toString().startsWith("$") } -> mapOf("\$map" to value.entries.associate { (k, v) -> k.toString() to typedValue(v) })
            else -> value.entries.associate { (k, v) -> k.toString() to typedValue(v) }
        }
        is List<*> -> value.map(::typedValue)
        else -> value
    }

    private fun float(text: String) = mapOf("\$float" to text)
    private fun temporal(kind: String, text: String) = mapOf("\$temporal" to kind, "text" to text)

    /** Statements given values the spec has no form for: whole floats, temporal values, bytes, and maps keyed with `$`. */
    private val TYPED: List<Pair<String, Map<String, Any?>>> = listOf(
        "RETURN \$x AS x, \$x + 1 AS y" to mapOf("x" to float("2.0")),
        "RETURN \$x AS x" to mapOf("x" to float("1e23")),
        "RETURN \$x * 2 AS x" to mapOf("x" to mapOf("\$float" to 3)),
        "CREATE (n:N {x: \$x}) RETURN n.x" to mapOf("x" to float("10000000.0")),
        "CREATE (n:N {x: \$x}) RETURN n.x" to mapOf("x" to 0.1),
        "CREATE (n:N {x: \$x}) RETURN n.x" to mapOf("x" to BigDecimal("4.9E-324")),
        "RETURN \$xs AS xs" to mapOf("xs" to listOf(float("NaN"), float("-Infinity"), 1, 1.5, float("1.0"))),
        "RETURN \$d AS d, \$d.year AS y" to mapOf("d" to temporal("DATE", "2026-11-07")),
        "RETURN \$t AS t" to mapOf("t" to temporal("LOCAL_TIME", "12:34:56.789")),
        "RETURN \$t AS t" to mapOf("t" to temporal("TIME", "12:34:56+10:00")),
        "RETURN \$t AS t, \$t.month AS m" to mapOf("t" to temporal("LOCAL_DATE_TIME", "2026-11-07T09:30")),
        "RETURN \$t AS t, \$t.timezone AS z" to mapOf("t" to temporal("DATE_TIME", "2026-11-07T09:30:00Z")),
        "RETURN \$d AS d, \$d.days AS days" to mapOf("d" to temporal("DURATION", "P1Y2M3DT4H5M6.5S")),
        "CREATE (n:N {at: \$at}) RETURN n.at" to mapOf("at" to temporal("DATE_TIME", "2026-11-07T09:30:00.123Z")),
        "RETURN \$b AS b" to mapOf("b" to mapOf("\$bytes" to "AAECAwQ=")),
        "CREATE (n:N {b: \$b}) RETURN n.b" to mapOf("b" to mapOf("\$bytes" to "/w==")),
        "RETURN \$m AS m, \$m.`\$a` AS a" to mapOf("m" to mapOf("\$map" to mapOf("\$a" to 1, "b" to float("2.0")))),
        "RETURN \$big AS big" to mapOf("big" to java.math.BigInteger("123456789012345678901234567890")),
        "MATCH (p:Person) RETURN p.name, p.age SKIP \$s LIMIT \$l" to mapOf("s" to 1, "l" to 2),
    )

    /** The graph of Oracle's rows as text: see [corpusRequests]. */
    private val ROWS_AS_TEXT: Map<String, Any?> = mapOf("name" to "g", "oracleRowsAsText" to true)

    /** The graph of Oracle's values encoded again: see [corpusRequests]. */
    private val REENCODED: Map<String, Any?> = mapOf("name" to "g", "oracleReencodeJson" to true)

    /** The graph of Oracle's reads as text: see [corpusRequests]. */
    private val READS_AS_TEXT: Map<String, Any?> = mapOf("name" to "g", "oracleReadsAsText" to true)

    /** Writes that return rows the spec's statements have no form of, for Oracle's rows as text: a long text given, many rows. */
    private val ROWS_AS_TEXT_WRITES: List<Pair<String, Map<String, Any?>>> = listOf(
        "UNWIND [1, 2] AS i CREATE (n:N {i: i, long: \$long}) RETURN n.i, n.long ORDER BY n.i" to mapOf("long" to "x".repeat(40_000)),
        "UNWIND range(1, 250) AS i CREATE (n:N {i: i}) RETURN n.i ORDER BY n.i DESC" to emptyMap(),
    )

    private val ORACLE_OPTIONS: List<Map<String, Any?>> = listOf(
        mapOf("name" to "g", "nullsEqualStored" to false),
        mapOf("name" to "g", "eraStored" to false),
        mapOf("name" to "g", "indexedProperties" to listOf(listOf("Person", "name"), listOf("Person", "age"))),
    )
}
