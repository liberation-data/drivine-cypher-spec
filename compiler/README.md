# The Cypher compiler

Drivine's Cypher compiler, the one drivine4j runs, as a Kotlin Multiplatform module: one build of it for the JVM, for
Wasm (Node 22 and later) and as a native shared library, so the three Drivine libraries can share one compiler in place
of three. It turns a statement in Drivine's flavour of Cypher into SQL for Postgres, Oracle or SQLite, as the spec in
this repository says each store gives it.

Every target gives the same answer as the JVM, byte for byte, and CI checks that over the whole spec (see
[Checks](#checks)).

This folder is the module's own. A library's copy of the spec (`scripts/sync-cypher-spec.sh` in each library) takes
the spec's files at the top of the repository and nothing here.

## Targets

| Target | What it builds | Who loads it |
|---|---|---|
| `jvm` | a jar | drivine4j, and Android |
| `wasmJs` | `@liberation-data/drivine-cypher-compiler`, an npm package (`./gradlew npmPackage`, in `build/npm`) | Drivine for TypeScript |
| `macosArm64`, `linuxX64`, `linuxArm64`, `mingwX64` | `libdrivinecypher`, a shared library with a C ABI and a SQLite extension, packed for PyPI as `drivine-cypher-compiler` wheels (`./gradlew pythonWheels`, in `build/wheels`) | drivine-python, over ctypes |

macosArm64 builds on macOS only. The others build anywhere Kotlin/Native runs.

## The API

On the JVM (and in any Kotlin code), `CypherCompiler.compile(store, graph, cypher, parameters)` gives `Compiled` or
`Refusal`:

```kotlin
val result = CypherCompiler.compile(Store.POSTGRES, GraphOptions("g"), "MATCH (p:Person {name: \$name}) RETURN p", mapOf("name" to "Ada"))
when (result) {
    is Compiled -> run(result.setup, result.sql, result.parameters)   // and log result.warnings
    is Refusal -> throw DrivineException(result.message)
}
```

The compiler throws `org.drivine.cypher.CompilerException` where it refuses a statement or a value; `compile` gives that
refusal as `Refusal`. drivine4j turns it into its own `DrivineException`, which names the query run, at each of its
calls into the compiler.

### Columns and typed rows

A compiled statement gives its columns (`CompiledCypher.columns`, `Compiled.columns`): each one's name, as a graph engine
names it (an item's alias, or its text as written, `RETURN *`'s variables in the order of their names, a `UNION`'s first
query's names), and the kind the compiler knows it holds (`ColumnKind`). That's `node`, `relationship`, `nodes` or
`relationships` (a list of them), a temporal kind (`date`, `localtime`, `time`, `localdatetime`, `datetime`,
`duration`), or `value`. A `UNION`'s column is of the kind each of its queries gives there, and `value` where they
differ. A result with no rows still has them, and a statement that returns nothing has none.

Compiled typed (`compile(cypher, parameters, typed = true)`, or the request's `"typed": true`), the columns of a node,
a relationship or a list of them hold each one tagged:

- a node as `{"$t": "node", "labels": [...], "properties": {...}}`;
- a relationship as `{"$t": "relationship", "type": "...", "properties": {...}}`.

A host reads tags only in those columns. Every other column holds what compiling untyped gives, so a map there with a
`$t` key of its own is a map, and a node one query of a `UNION` gives in a `value` column is its properties. A temporal value is its ISO text until liberation-data/drivine-cypher-spec#161. After that it's
`{"$t": "datetime", "v": "..."}` and the like: the tag scheme is agreed with #161's, and output stays a contract apart
from how the store keeps a value. A path is still refused (#163). A node held in a map or a list the statement builds
comes back as its properties (#164). Each library decodes the rows itself. Untyped, a statement compiles byte for byte
as it did before, and the corpus checks that.

### drivine4j's own calls

drivine4j calls more of the module than the API above: the compiler classes themselves, the parts of its own SQL
helpers that the compiler calls too (`org.drivine.cypher.store`), the store layout's DDL (`org.drivine.cypher.store.ddl`:
each store's tables, the probe that finds them made, and its indexes, constraints and catalog statements, as the spec's
`store.yaml` pins them), the schema marker and the stamps (`org.drivine.cypher.store.marker`, and
`org.drivine.cypher.store.Stamps`: each store's statements for the marker and the store's stamp, the marker's inventory
and version token, and the node stamp's form, as the spec's `marker.yaml` pins them), the schema plan
(`org.drivine.cypher.store.plan`: drivine4j's schema manager as a planner with no store, which gives a library each round
of statements to run and decides from what the store gave back, as the spec's `plans.yaml` pins it), full-text search
(`org.drivine.cypher.api.FullTextSearch`, see [Full-text search](#full-text-search)), and a few of the schema specs' and
compilers' functions. The compiler classes are public and unmarked; the rest are public only so that drivine4j, built apart
from the module, can call them, and are marked `@org.drivine.DrivineInternalApi`, an opt-in at the level of an error:
drivine4j opts in module-wide, and any other caller that doesn't is refused at compile time. They may change or go in
any release.

Among them, a compiler can be made with a `CypherEncoder`, the host's writing of the values it gives as JSON, which the
compiler asks before its own. drivine4j gives its Jackson mapper's, so that a parameter is written as drivine4j has
always written it. An encoder is the compiler's own, for as long as it compiles: no state is shared between hosts.

`OracleCypherCompiler` also takes `rowsAsText`, false where left out, the Kotlin form of the request's `oracleRowsAsText`
(see [Oracle's rows as text](#oracles-rows-as-text)). drivine4j reads the `JSON` rows over JDBC, which reads every row,
so it leaves it false; it is for a host whose Oracle driver can't, node-oracledb's thin mode
(oracle/node-oracledb#1795), until a fixed driver release.

It takes `reencodeJson` too, false where left out, the Kotlin form of the request's `oracleReencodeJson` (see
[Oracle's documents encoded again](#oracles-documents-encoded-again)). JDBC reads every OSON version, so drivine4j leaves it
false; the hosts on thin drivers, python-oracledb's and node-oracledb's, set it.

It takes `readsAsText` too, false where left out, the Kotlin form of the request's `oracleReadsAsText` (see
[Oracle's reads as text](#oracles-reads-as-text)). JDBC keeps a number's digits, so drivine4j leaves it false.

A host with no Kotlin types calls `CompileJson.compile(request)`, JSON text in and out. The wasm build exports it as
`compile`, and the shared library as `drivine_compile`. Each also exports the SQLite host functions' version the
compiled SQL needs, `hostVersion()` and `drivine_host_version()`: see [The host functions' version](#the-host-functions-version).

```json
{"store": "oracle",
 "graph": {"name": "g", "nullsEqualStored": true, "eraStored": true, "indexedProperties": [["Person", "name"]]},
 "cypher": "MATCH (p:Person {name: $name}) RETURN p",
 "parameters": {"name": "Ada"}}
```

`store` is `postgres`, `oracle` or `sqlite`. `graph` has the graph's `name`; the rest of it is Oracle's, and may be left
out: `nullsEqualStored` and `eraStored` (true where left out), `indexedProperties`, `oracleRowsAsText`, `oracleReencodeJson` and
`oracleReadsAsText` (false where left out, see [Oracle's rows as text](#oracles-rows-as-text),
[Oracle's documents encoded again](#oracles-documents-encoded-again) and [Oracle's reads as text](#oracles-reads-as-text)). `typed`, false where left out, asks for the rows' nodes and
relationships tagged and for the columns: see [Columns and typed rows](#columns-and-typed-rows). The answer is one of:

- `{"sql": …, "parameters": {…}, "setup": […], "warnings": […], "nonFinite": false}`, and on Oracle
  `"storedFunctions": {"nullsEqual": …, "era": …}`. Run `setup` first (on Postgres, the function a statement that
  writes is), then `sql`, binding `parameters`. `nonFinite` says NaN or an infinity may come back in the rows as
  `{"$numberDouble": "NaN"}`. `storedFunctions` says which of the graph's stored functions the statement calls: a store
  without them is compiled for again with `nullsEqualStored` or `eraStored` false.
  With `typed`, it has `"columns"` too: `[{"name": "p", "kind": "node"}, …]`.
- `{"refusal": "<message>"}`: the compiler refuses the statement, with the message the spec's refusals pin.
- `{"error": "<message>"}`: the request is no request.

Values are plain JSON, tagged only where JSON, or a host's reading of it, loses what the SQL depends on:

| Value | Written |
|---|---|
| a whole number (Long, or past a Long a big integer) | a JSON integer: `2` |
| a float | `{"$float": "2.0"}` (or `{"$float": 2}`), or in a request a JSON number with a fraction or an exponent (`2.5`, `1e-7`). A host whose JSON writer drops a whole float's fraction (JavaScript writes `2.0` as `2`) tags its floats |
| NaN, the infinities | `{"$float": "NaN"}`, `{"$float": "Infinity"}`, `{"$float": "-Infinity"}` |
| a temporal value | `{"$temporal": "DATE", "text": "2026-11-07"}`: `DATE`, `LOCAL_TIME`, `TIME`, `LOCAL_DATE_TIME`, `DATE_TIME` or `DURATION`, and its ISO 8601 text. Untagged, its ISO text is a text: as in Neo4j, it is equal to no temporal value the statement makes and is not ordered beside one (`date('2026-11-07') = $d` is false for `"2026-11-07"`) |
| bytes | `{"$bytes": "<base64>"}` |
| a map with a key that starts with `$` | `{"$map": {"$key": …}}` |

The values bound come back the same way, except that every float is tagged, whole or not: `{"$float": "2.0"}`,
`{"$float": "2.5"}`, its text as Java writes it. JavaScript's `JSON.parse` reads `2.0` as the integer 2, and Postgres
types a parameter by the value it is given, so an untagged float would be bound as an integer. A decimal, which only a
Kotlin host gives (bignum's `BigDecimal`, or java.math's), comes back as `{"$decimal": "2.5"}`, its plain text (java.math's
keeps its scale, `"2.50"`; bignum's, on any target, keeps none): neither a float, whose text is a double's, nor a number
a host's JSON reads as a double, losing digits. An untagged JSON number in the answer is always a whole number.

A request nests its arrays and objects at most 64 deep, its own object and `parameters` two of them; a deeper one is
an error.

### Full-text search

`org.drivine.cypher.api.FullTextSearch` writes a full-text search for each store, as drivine4j writes it: the user's query
read (words ORed, a `"phrase"`, `+required`, `-excluded` and `NOT`, `prefix*`, `AND`/`OR`, `field:word`, and backslash
escapes), and the statement each store answers it with. It is pure, and marked `@DrivineInternalApi` as the store's DDL is:
the library runs the statements it gives, and keeps its own projection.

```kotlin
FullTextSearch.lookup(store, graph, label, properties): CatalogLookup?                 // the catalog's record of the index
FullTextSearch.plan(store, graph, SearchRequest(labels, properties, query, index)): SearchResult   // SearchPlan or Refusal
```

`store` is a `SearchStore`: `POSTGRES`, `ORACLE`, `SQLITE`, `NEO4J`, `MEMGRAPH` or `FALKORDB`.

1. `lookup` gives the statement that finds what the graph's catalog records of the full-text index on the label's
   properties: on Postgres a row where its trigram index is made, on Oracle the side table of its Oracle Text index (the
   row's `object_name`), and no row where none is made. It is null on the other stores.
   `label` is the one the index is on, and `properties` every property the index covers, in the order its declaration
   gives them, as the record holds them: not only one a caller named to choose the index. Choosing the index, and refusing a
   property no index covers, is the library's, as drivine4j's `FullTextIndexResolver` chooses it. The search's `labels` are
   the root's, that label first, and its `properties` the same list, in the same order: Postgres's trigram index, Oracle's
   side table and the derived index name are all over that order.
2. The library gives what it found as the request's `SearchIndex`: `trigram` on Postgres, `oracleText` (the side table, or
   null) on Oracle, and on Neo4j and Memgraph the index's `name` where it isn't `<label>_<properties joined by _>_fulltext` (a blank one is none).
3. `plan` gives a `SearchPlan`, or a `Refusal` of a query with no word to score (`A full-text query needs a word to look
   for: '-only' has none.`), or of a graph name the store can't take.
   - On a SQL store, `sql` is one `SELECT` of the ranked candidates, `nid` and `score`: the nodes whose text meets the query,
     each scored by BM25 as Lucene scores it (or on Oracle with an Oracle Text index, by Oracle Text), against the best of
     them, so the best scores 1, and only those above 0. The library joins them back to its node table by `nid` for its
     projection, and adds its threshold (`score >= threshold`), its predicates, the view's required relationships, `ORDER BY score DESC, nid` and
     its `LIMIT`. On SQLite it calls the functions the library registers, `drivine_lower`, `drivine_regexp_count` and
     `drivine_math`, and its expressions hold what the compiler's own don't (see the spec's README, *Regular
     expressions*): POSIX's `[[:alnum:]]`, read as Java's `\p{Alnum}` with Unicode's classes, `$` and a lookahead `(?=…)`,
     as drivine4j's `SqlitePatterns` reads them. `TextQueryCountTest` counts its terms so, by Java's expressions, on the JVM
     alone.
   - On a graph engine, `cypher` is the search's head: the index's `CALL` given the query as it is, the hits that have every
     root label, the best of them, and each node and its score against the best, as `alias` and `scoreAlias` (`n` and
     `_score` where left out), best first. The index's name (FalkorDB's label) is a Cypher string, its quotes and
     backslashes escaped, and each label a backtick-quoted name, whatever they hold. The index is asked for `topK` hits, or with `filtered` (a caller's filter or a
     required relationship prunes them after the index; a threshold, which keeps a prefix of the ranking, does not) or a
     root label beyond the first ten times as many (`indexLimit`), and Memgraph's no more than 1,000,000.
   - `parameters` are named with the compiler's reserved prefix, `_cy_ft1`, `_cy_ft2`, …, which no library binds, so they
     never clash with the library's own; each is written `$_cy_ft<n>` in the statement, as the compiler writes its own.

Over JSON it is the same entry, `CompileJson.compile` (`compile`, `drivine_compile`): a request with `search` in place of
`cypher` is a search, and one that gives more than one of `cypher`, `search` and `searchIndex` is an error.

```json
{"store": "postgres", "graph": {"name": "g"},
 "search": {"labels": ["Chunk"], "properties": ["text"], "query": "how do I define an action", "index": {"trigram": true}}}
```

`search` holds `labels` (or one `label`, not both), `properties`, `query`, `index` (`{"trigram": …}`, `{"oracleText": "<side table>" |
null}` or `{"name": …}`, left out where there's none), and on a graph engine `topK`, and `filtered`, `alias` and
`scoreAlias` where they aren't false, `n` and `_score`. `graph` is the SQL stores' alone. The answer is
`{"sql": …, "parameters": {…}, "warnings": […]}`, with `cypher` in place of `sql` on a graph engine, or `{"refusal": …}`, or
`{"error": …}` where the request is no request. A request with `searchIndex`, `{"label": …, "properties": […]}`, in place of
`cypher` is given the lookup: `{"sql": …, "parameters": {…}}`, `sql` null on a store with no record of the index.

drivine4j's statements are the module's byte for byte. `src/commonTest/resources/search-golden.json` holds them, written by
drivine4j's own code (`matching` and `matchingThrough` of its Postgres, Oracle and SQLite selects, and its grammars'
`fullTextSearchHead`) at the commit it names, for every query of drivine4j's `TextQueryTest` and `OracleSelectTest`, the 22 of
its ranking test over `docs-corpus.json`, and labels and properties of other shapes, on each store and in each state of its
index: only the parameters' names were changed, drivine4j's `$_pN` to `$_cy_ft<N+1>`, and on the graph engines an index
name or label with a quote or a backslash, which drivine4j wrote raw, is written escaped (liberation-data/drivine4j#767).
`FullTextSearchGoldenTest` checks
`plan`, and the JSON request, against each on every target. On a SQL store drivine4j's statement goes on to its projection of
each node's row, which the module leaves to the library, so the test puts that back before it compares: the rest is
byte for byte.

The query is read as Java reads it on every target: a word's characters are Java's `\p{IsAlphabetic}`, `\p{Nd}` and `_`, and
its lower case is Java's `String.toLowerCase(Locale.ROOT)`, a final sigma among it, which Java reads by the word around it as
`BreakIterator` finds the word. The JVM asks Java; the other targets read tables of the pinned JDK's answers
(`org.drivine.cypher.text.CommonUnicode`, see [Building](#building)), which the stand-in fuzz checks on every target and the
JVM's tests check against Java on every code point. Lengths are in characters (drivine4j#665 measures them in words), and the
graph engines are given the query as it is (FalkorDB's own syntax is drivine4j#666).

### Oracle's rows as text

On Oracle a statement that writes is a PL/SQL block, which returns its rows as an implicit result
(`DBMS_SQL.RETURN_RESULT`), a row of `JSON` each, as drivine4j reads them. node-oracledb's thin mode can't read that
result: it gives only the prefetched rows of an implicit result with a `JSON` column, 2 by default and none with
`prefetchRows: 0`, with no error (oracle/node-oracledb#1795, open, in every thin release to 26.0.0), and some values fail
with `NJS-126: OSON version 2 is not supported`. A row of text each is no way round it: a `CLOB` column is cut short
alike, and a `VARCHAR2` holds too little of a row.

So a host whose driver can't read it sets `"oracleRowsAsText": true` in `graph` (`GraphOptions.oracleRowsAsText` in
Kotlin). A statement that writes and returns rows then returns them all as one row, a JSON list in their order, as text in
a column named `rows:json`:

```sql
OPEN cy_cursor FOR
    SELECT JSON_SERIALIZE(COALESCE(JSON_ARRAYAGG(cy_r.result ORDER BY cy_r.cy_n NULL ON NULL RETURNING JSON), JSON('[]')) RETURNING CLOB EXTENDED) AS "rows:json" FROM (
        SELECT cy_q.result, ROWNUM AS cy_n FROM (
            <the rows' query>
        ) cy_q
    ) cy_r;
DBMS_SQL.RETURN_RESULT(cy_cursor);
```

The text is Oracle's extended JSON, so a float stays a float: an object of the one key `$numberDouble` is one,
`{"$numberDouble": 2}` or `{"$numberDouble": 2.5}`, and NaN and the infinities are `{"$numberDouble": "Nan"}`, `"Inf"`
and `"-Inf"`. Every other value is plain JSON, as the `JSON` rows hold it: a whole number in full, a text, a temporal
value as its ISO 8601 text, a list, a map. A map the statement returns whose one key is `$numberDouble` is written as a
float is. The host reads the one row while its driver prefetches at least one row (node-oracledb's default is 2). The
rows are one JSON value, so a write whose rows together pass Oracle's limit on the size of one fails, where a row of
`JSON` each would not.

Every other statement, a read or a write that returns nothing, compiles alike either way, as does every statement on the
other stores. The option is temporary: Drivine for TypeScript sets it until a node-oracledb release with
oracle/node-oracledb#1795's fix ships, and drivine-python until python-oracledb's thin mode, another driver, is known to
read every row (liberation-data/drivine-python#468); then they drop it, and it goes from the module with them.
drivine4j's JDBC reads the `JSON` rows, and leaves it false.

### Oracle's documents encoded again

Oracle keeps a `JSON` column updated in place, as a write updates a node's properties, as OSON version 2, and gives it back
so where the statement doesn't make the document anew: as a node with no stamp, which every node Cypher writes is, is
returned. The thin drivers read only versions 1 and 3: python-oracledb's thin mode fails with `DPY-3021: OSON version 2
is not supported` (oracle/python-oracledb#622), node-oracledb's with `NJS-126` (oracle/node-oracledb#1796). JDBC reads it.
Oracle's x86-64 build sends it; the arm64 build of the same image (`gvenzl/oracle-free:23-faststart`, 23.26) has not been
seen to.

So a host on a thin driver sets `"oracleReencodeJson": true` in `graph` (`GraphOptions.oracleReencodeJson` in Kotlin),
reading or writing. Each value a statement returns, its rows' `result`, is then encoded again, as a new document:

```sql
SELECT JSON_TRANSFORM(<the value>, KEEP '$') AS result
```

With `oracleRowsAsText` too, a write's rows are serialized to text already; its reads still return `JSON`, so the host
sets both. A statement that returns nothing, and every statement on the other stores, is compiled alike either way. The
option is temporary: a host sets it until its thin driver reads OSON version 2 (oracle/python-oracledb#622,
oracle/node-oracledb#1796), then drops it, and it goes from the module with them.

### Oracle's reads as text

A statement that reads returns a row of `JSON` each. node-oracledb's thin mode reads a `JSON` value's numbers through a
double, so a whole number past 2^53 loses its last digits: 9007199254740993 comes back 9007199254740992 (#198).

So a host whose driver does that sets `"oracleReadsAsText": true` in `graph` (`GraphOptions.oracleReadsAsText` in Kotlin).
A statement that only reads then returns each row's value as its text in Oracle's extended JSON, a `CLOB` in the column
`result`, in the read's order:

```sql
SELECT JSON_SERIALIZE(cy_r.result RETURNING CLOB EXTENDED) AS result FROM (
    SELECT cy_q.result, ROWNUM AS cy_n FROM (
        <the read>
    ) cy_q
) cy_r
ORDER BY cy_r.cy_n
```

A hint the read starts with, and the functions it declares before it (`WITH FUNCTION …`), still start the statement. The
text is the extended JSON of [Oracle's rows as text](#oracles-rows-as-text): a whole number in full, and a float marked as
one, `{"$numberDouble": 2}`. A statement that writes, one compiled typed, and every statement on the other stores, is
compiled alike either way. With `oracleReencodeJson` too, the value encoded again is the one serialized. A host on
node-oracledb sets it; drivine4j's JDBC keeps a number's digits, and leaves it false.

### The parser's caches

ANTLR keeps what it learns of the grammar as it parses, its DFA states and the prediction contexts they share, for the
life of the process. Unbounded, they grew to 42 MB over the spec's corpus of 9,136 distinct statements, and ran an
Android app out of a 48 MB heap (#179). The module bounds them: once they hold more than 5,000 DFA states, about 15 MB at
their most, a fresh set takes their place, and a parse under way keeps the one it began with. 5,000 states hold about
the first 300 distinct statements of the corpus, a varied mix; an application's statements are fewer and more alike,
and pay nothing. Past the cap, the corpus's median compile went from 135 to 167 µs.

A server that compiles many more distinct statements may raise the cap for the whole process: in Kotlin,
`CypherCompiler.parserCacheStates`; through the JSON entry, a request of settings in place of a statement:

```json
{"settings": {"parserCacheStates": 20000}}
```

The answer gives each setting as it now is, how many times the caches have been made anew since the process began, and
the states they hold: `{"settings":{"parserCacheStates":20000,"parserCacheRenewals":3,"parserCacheHeld":4211}}`.
`{"settings": {}}` asks and sets nothing. A library logs the renewals at debug where they have grown since it last
asked, so a statement set too large for the cap shows in the log. The answer to a statement is as it was: when the caches
are made anew depends on what the process compiled before, which the corpus check's byte-for-byte comparison can't
hold to.

### npm

```js
const { compile } = require('@liberation-data/drivine-cypher-compiler');      // or: import { compile } from '@liberation-data/drivine-cypher-compiler'
const answer = JSON.parse(compile(JSON.stringify({ store: 'sqlite', graph: { name: 'g' }, cypher: 'RETURN 1' })));
const { hostVersion } = require('@liberation-data/drivine-cypher-compiler');  // what SELECT drivine_host_version() must give
```

The CommonJS entry (`npm/index.cjs`) loads the wasm synchronously. Kotlin/Wasm writes only an ES module loader, with a
top-level await, so the entry runs that loader's text as a function with its Node branch made synchronous. Each edit
is checked, so a Kotlin release that writes the loader otherwise fails at once. The corpus check on wasmJs goes through
this entry. Each entry has its TypeScript types: Kotlin's `.d.mts` for `import`, and `npm/index.d.cts` for `require`.

The package holds the wasm, its loaders, the CommonJS entry, the types of each, a short README (`npm/README.md`) and
the repository's LICENSE. The package's name is set once, as `compiler.npmName` in `gradle.properties`. The corpus check on wasmJs fails when the
package it loads has another (`check/corpus-node-test.sh` tests that).

### Python

```python
import json, drivine_cypher_compiler
answer = json.loads(drivine_cypher_compiler.compile(json.dumps({"store": "sqlite", "graph": {"name": "g"}, "cypher": "RETURN 1"})))
```

The package `drivine-cypher-compiler` is the shared library with a small ctypes binding (`python/drivine_cypher_compiler`),
one wheel a platform, `py3-none-<platform>`, as the library is the same for every Python 3:

| Target | Wheel's platform tag | Why that one |
|---|---|---|
| `macosArm64` | `macosx_11_0_arm64` | Kotlin/Native builds for macOS 11 and later. |
| `linuxX64` | `manylinux_2_17_x86_64`, `manylinux2014_x86_64` | It calls nothing newer than `GLIBC_2.14`; auditwheel checks it. |
| `linuxArm64` | `manylinux_2_17_aarch64`, `manylinux2014_aarch64` | It calls nothing newer than `GLIBC_2.17`; auditwheel checks it. |
| `mingwX64` | `win_amd64` | |

There's none for Intel Macs (Kotlin/Native deprecates `macosX64`) or musl Linux (Kotlin/Native has no musl target): pip
finds no wheel there, and drivine-python names the platform where it would load the compiler. On Linux and macOS the
library exports the C API alone (a version script on Linux and a symbol list on macOS, in `native/`, which CI checks the
library's exports against), so the Kotlin/Native runtime's symbols can't clash with another library in the host's
process. On Windows, Kotlin/Native's `.def` names the C API and its own `drivinecypher_symbols`, which CI checks, but the
DLL also exports the C runtime it links statically, about 170 symbols of libgcc's unwinder and winpthreads
(`_Unwind_*`, `pthread_*`, `sem_*`); none of the Kotlin/Native runtime's, and a Windows DLL's exports bind only to a
caller that imports them by this DLL's name, so they can't clash either. On Linux it is linked `--as-needed`, which
leaves out `libcrypt.so.1` and the other system libraries it doesn't call. The binding loads the library on the first
compile, once, and frees each answer with `drivine_free`. `python/build_wheels.py` packs the wheels with the standard
library alone, the same bytes from the same libraries; `python/check_wheel.py` checks each, and
`python/check_wheel_test.py`, which CI runs, tests both over made-up libraries.

### C

```c
char *drivine_compile(const char *request);   /* NUL-terminated UTF-8 JSON in, and out */
void drivine_free(char *answer);              /* frees what drivine_compile gave */
int drivine_host_version(void);               /* the host functions' version the compiled SQL needs */
```

A NULL request is answered as an empty one, with `{"error": …}`. `drivine_compile` gives NULL only where it can't allocate
the answer. The answer is the host's to free, with `drivine_free` and nothing else, as the library allocates it.

Each call stands alone, so a host may call it from several threads at once.

### The SQLite extension

`libdrivinecypher` is also a SQLite loadable extension: the host functions that compiled SQL calls on SQLite, written once
in the module, in place of a copy in each library (#108). It is the same library as `drivine_compile`, so a process that
compiles and runs SQLite has one Kotlin/Native runtime. It links against no SQLite: every call into SQLite goes through
the routines the loading SQLite passes to its entry point, so it binds to whatever SQLite loads it (CI checks, on Linux
and macOS, that the library imports no `sqlite3_` symbol and exports only the C API and these, and on Windows that the
`.def` names them; see [Python](#python) for what else the DLL exports):

```c
int sqlite3_drivinecypher_init(sqlite3 *db, char **errorMessage, const sqlite3_api_routines *routines);
void drivine_gc_collect(void);   /* collects now: see below */
int drivine_host_version(void);  /* the host functions' version the compiled SQL needs: see below */
```

SQLite derives the entry point's name from the file's (`libdrivinecypher` or `drivinecypher`), so a host loads it by
path alone. The host functions are ported from drivine4j's: each is common code
(`org.drivine.cypher.host.HostFunctions`), which the extension registers and a host on the JVM can call from its own
registrations, and each comes with the spec's `host.yaml` cases that pin it. A function of JSON values fails its statement
on a text that is no JSON, and takes an empty one as no value, as drivine4j's do. It registers every function `host.yaml` pins:

| Function | What it gives |
|---|---|
| `drivine_float_text(real)` | the float as Java writes a double, as Cypher's `toString` gives it (`2.0`, `1.0E7`, `Infinity`), where SQLite's own text has 17 digits; null of null. Deterministic. |
| `drivine_equal(a, b)` | 1 or 0, whether the JSON values are equal as Cypher compares them (`1` equal to `1.0`, maps by their entries in any order, NaN equal to nothing); null where either is no value or a null in them decides. Deterministic. |
| `drivine_add(a, b)` | Cypher's `a + b` of the JSON values, as JSON text: numbers summed (a whole number where both are, past a long's range too), lists joined or an item added, texts and numbers written together; null where Cypher adds no such two. Deterministic. |
| `drivine_canonical(json)` | the JSON text with each map in it, at any depth, its keys in order, so two maps of the same entries group together; JSON's null as given, null of null. Deterministic. |
| `drivine_sort_key(json)` | of a JSON list, a text that orders, as SQLite orders text, as Cypher orders lists; of any other value its JSON text as given, null of null. Deterministic. |
| `drivine_list_order(a, b)` | -1, 0 or 1, where the JSON list `a` comes beside the list `b` in a comparison (`<`, `>=` …); null where either is no list or the pair that decides isn't ordered. Deterministic. |
| `drivine_round(number, precision, mode)` | the number rounded to as many decimal places by the mode (`HALF_UP`, `HALF_EVEN` … as Java names them), in decimal as Neo4j rounds, so 2.345 to 2 places `HALF_UP` is 2.35; a float. An infinity is itself; null where the number or the precision is. Deterministic. |
| `regexp(pattern, text)` | 1 where the regular expression matches somewhere in the text, 0 where it matches nowhere; null where either is null. SQLite calls it for `text REGEXP pattern`. The pattern is Java's: see [Regular expressions](#regular-expressions). Deterministic. |
| `drivine_regex_group(text, pattern, group)` | the text the group of that number matches where the pattern first matches (0 the whole match), as Postgres's `regexp_match` gives it; null where it matches nowhere, the group takes no part or is past the pattern's, or the text or pattern is null. A null group is 0; a negative one fails the statement. Deterministic. |
| `drivine_regexp_replace(text, pattern, replacement)` | the text with each match replaced, `\1` in the replacement naming a group as Postgres's and Oracle's do, anything else itself; null where any is null. A match whose replacement names a group the pattern hasn't fails the statement, as Java's does. Deterministic. |
| `drivine_temporal(operation, …)` | what compiled SQL works out of a timestamp, the text of a wall clock to the microsecond (`2024-03-15T10:30:00.000000`), as java.time works it out, by its operation: `local(text, kind)` and `utc(text, kind)`, the wall clock an ISO text of a kind (`DATE_TIME` …) reads, its offset (`Z`, `±hh:mm` or `±hhmm`; one with seconds is none) left out or moved to UTC; `extract(timestamp, field)`, `YEAR`, `MONTH`, `DAY`, `HOUR`, `MINUTE` or `NANOSECOND_OF_MINUTE` (no `SECOND`); `day_of_week(timestamp)`; `add_months(timestamp, months)`; `add_time(timestamp, days, seconds, nanos)`, added as Java's `plusDays`, `plusSeconds` and `plusNanos` add them; `months_between(from, to)`; `seconds_between(from, to)`, whole seconds, floored, and `nanos_between(from, to)`, the nanoseconds past them, 0 to 999,999,999, so the two are exact over the whole range of years; `truncate(timestamp, unit)`. Whole numbers each, but for the timestamps; a count given as a float or a text is read as a decimal rounded half away from zero. A timestamp it writes keeps the microsecond, cut, never rounded. Null where an argument after the operation is null; a text that is no temporal value fails the statement, naming it. Deterministic. |
| `drivine_trunc(number)` | the number with its fraction cut off, a whole number where one holds it, as Postgres's and Oracle's `TRUNC` give it; a float within two units of its last place of a whole number is that whole number (SQLite's binary misses `33.001 * 1e6` by that much); past a whole number's range, the float. Null of null. Deterministic. |
| `drivine_to_char(value, pattern)` | a timestamp in a pattern of `YYYY`, `MM`, `DD`, `HH24`, `MI`, `SS`, `IYYY`, `IW`, `DDD` and quoted text, its year signed before 0 (`-0044`), or a number in an `FM` pattern (`FM0000`, `FM9999999999990.999999999`), rounded half away from zero, as Postgres's and Oracle's `to_char` write those compiled SQL writes; null where either is null. Deterministic. |
| `drivine_host_version()` | the version of these functions' forms, `SqliteFunctions.HOST_VERSION`, which a host checks: see below. Deterministic. |
| `drivine_extension_version()` | the module's version, as it was built (`-Pcompiler.version`, the Maven form: `0.36.0`, `0.36.0-rc.1`), so a host can check it loaded the library it meant to. Deterministic. |
| `drivine_gc_collect()` | NULL, having collected: see below. Direct-only. |
| `drivine_gc_heap()`, `drivine_gc_heap(bytes)` | the GC's floor, in bytes; given bytes, it sets the floor first, and 0 restores the runtime's own. Direct-only. |

#### The host functions' version

The compiled SQL calls the host functions in the forms the spec's `host.yaml` pins, which change now and then
(`drivine_temporal`'s `add_time` took a float of seconds before version 1). `SqliteFunctions.HOST_VERSION` is the version
of the forms the compiler's SQL calls, bumped with each change to them, in the same change as their `host.yaml` steps;
`drivine_host_version()` gives the version of the functions registered. A host that registers the functions itself
(drivine4j on xerial, the TypeScript and Python libraries) checks the two agree once on each connection, right after
registering, so a stale copy fails on connecting rather than mid-statement: `HostCheck.SQL` is the statement to run, and
`HostCheck.verify(reported)` throws where its answer (null where SQLite has no such function, as with drivine4j's older
functions) is not the compiler's. A host with no Kotlin types reads the compiler's version from `drivine_host_version()`
in C or `hostVersion()` in wasm, and words the failure as `HostCheck` does:

```text
SQLite's drivine_ functions are version 0, the compiler needs 1: register the module's host functions
SQLite has no drivine_host_version(): its drivine_ functions are older than version 1, the compiler's: register the module's host functions
```

The extension registers the module's own functions, so on a connection that loaded it the two always agree.

#### Temporal functions without java.time

`drivine_temporal`, `drivine_trunc` and `drivine_to_char` are drivine4j's, ported onto kotlinx-datetime's values alone
(`org.drivine.cypher.host.time`): java.time's ISO parsers' acceptance (a year of four digits, or signed with more; `T` or
`t`; a fraction of up to nine digits, or none after the point), its calendar arithmetic (`plusMonths`, `plusSeconds`,
`plusNanos`, `ChronoUnit.MONTHS.between`, `Duration.between`, the ISO week-based year) with its range checks and
messages, and java.math's `BigDecimal` reading, `HALF_UP` and `stripTrailingZeros`, are written out in common code, as
kotlinx-datetime's own parse and arithmetic check and word otherwise. A decimal is held as its digits and its scale,
never as a power of ten, so a stored text of an exponent near ±2^31 (`1e2147483647`) is answered, or fails with
java.math's message (`BigInteger would overflow supported range`, `Underflow`), at once, where Java can take
minutes; a whole number's wrapped low 64 bits are worked out of the digits alone. The JVM writes java.time's answers
to 120,000 calls or so (`host-temporal.json`, `org.drivine.cypher.oracle.HostTemporals`): every edge year from -999,999,999 to 999,999,999,
ISO-week turns, the texts of every temporal kind with and without offsets (those of real zones' transitions among them),
and floats and decimal texts for `drivine_trunc` and `drivine_to_char`. `HostTemporalOracleTest` checks each on every
target, its result exactly and of its type, or its failure, and `jvmOracle` checks `host.yaml`'s steps of them against
java.time first. A step noted `drivine4j#421 (layout 2)` needs nine-digit timestamps, and must fail until they are the store's.

On SQLite the compiler reads no float of seconds: a duration is added as whole seconds and nanoseconds read from its text,
`duration.between`, `epochSeconds` and `epochMillis` are worked out of `seconds_between` and `nanos_between`, and a
clock's `second`, `millisecond`, `microsecond` and `nanosecond` of `NANOSECOND_OF_MINUTE`, whole numbers each
(`TemporalSql.exactTime`). Postgres's and Oracle's SQL is unchanged.

A direct-only function changes the process's state, so SQLite lets only the host's own statements call it, never a
trigger or view of the database's. A function that throws fails its statement with its message (`drivine_gc_heap: the
floor is 0 bytes or more, not -1.`), as an exception escaping into SQLite would end the host's process.

It takes SQLite 3.31.0 or later (the init refuses an older one), and serves one SQLite a process: a second SQLite (a copy
of another version, linked into another library) that loads it is refused, as its functions would call the first one's
routines. The headers it is compiled against are SQLite 3.54.1's, pinned in `native/sqlite/sqlite.properties` with the
amalgamation's URL and SHA3-256 and each header's SHA-256; to move them, take both headers from a newer amalgamation,
unchanged, and record it there. The routines struct only grows, so headers newer than the loading SQLite are fine as long
as the extension calls nothing that SQLite lacks.

Loading it, with the library's path (`drivine_cypher_compiler.library_path()` in the Python package):

```python
connection = apsw.Connection(path_to_db)                       # Python: apsw
connection.enable_load_extension(True)
connection.load_extension(drivine_cypher_compiler.library_path())
```

```js
new Database(file).loadExtension(path)                          // Node: better-sqlite3
new DatabaseSync(file, { allowExtension: true }).loadExtension(path)   // Node: node:sqlite
```

On the JVM, sqlite-jdbc loads it with `SQLiteConfig.enableLoadExtension(true)` and `SELECT load_extension(?)`; drivine4j
doesn't need it, as it calls the module's Kotlin itself. A host that links SQLite statically (iOS, Android) registers the
entry point for every connection with `sqlite3_auto_extension((void (*)(void)) sqlite3_drivinecypher_init)`. That SQLite
must be built without `SQLITE_OMIT_LOAD_EXTENSION`: built with it, SQLite gives an auto-extension no routines, and the
init refuses.

**One image.** The C API and the extension must come from the same file, loaded by the same path: `dlopen` (and
`LoadLibrary`) gives the image already loaded for a path it has loaded, so the compiler and the extension share one
runtime. Two copies of the library at two paths are two Kotlin/Native runtimes in one process, each with its own heap and
collector.

**The GC, deferred.** Kotlin/Native schedules a collection when its heap reaches a target, which it tunes after each
collection from the heap still live, but never below a floor, 5 MiB by default. With little live, that is a collection
every few MiB allocated: 60 to 65 stop-the-world collections in a 1M-row `regexp` statement in #108's prototype, whose
`regexp` made about 300 bytes of garbage a row. Raising the target alone doesn't hold, as the tuning lowers it again;
raising the floor does. So the extension's first init in a process raises the floor (`minHeapBytes`, and the target with
it) to 64 MiB, about 200,000 rows of that garbage: few statements set off a collection, and a long one far fewer. The
floor stays the backstop: past it, a collection runs as before, so garbage holds at most about the floor even where a
host never collects. A host collects between statements, where a pause costs nothing, with `drivine_gc_collect()`, in
SQL or C. A host that wants another floor sets it in SQL, `SELECT drivine_gc_heap(268435456)`, which every host of the
extension can run (Node's better-sqlite3 can call no C function), and `drivine_gc_heap(0)` gives the runtime's own back. A
later connection's init leaves the floor as the host set it. A host that loads only the C API, and never the extension,
keeps Kotlin/Native's defaults.

`./gradlew nativeExtensionCheck` loads the host's release library into apsw's SQLite (`check/extension-native.py`; a
Python with apsw, installed by hash from `check/extension-requirements.txt`, as `-Ppython=<path>`), calls each function
through SQL, the throwing ones too, and then the C API by ctypes from the same path. It also calls, through SQL, each
`function` step of the spec's `host.yaml` whose function the extension registers (`./gradlew hostSpecJson` writes the
file as JSON, in `build/host`), and names those it doesn't register yet. CI runs it on Linux x64 and macOS, and the
release runs it on each platform's installed wheel, Windows and Linux arm64 too, without `host.yaml`.

### Regular expressions

The regular expression functions read a pattern as drivine4j's do: Java's, compiled with `UNICODE_CHARACTER_CLASS`, with a
POSIX class (`[:alnum:]`, `[:alpha:]`, `[:digit:]`, `[:space:]`, `[:upper:]`, `[:lower:]`, `[:punct:]`) read as Java names it
(`\p{Alnum}` …), so a full-text search's expressions, written for Postgres, read the same. `org.drivine.cypher.host.regex`:

- On the JVM, `java.util.regex` itself.
- On Kotlin/Native and Kotlin/Wasm, `kotlin.text.Regex`, which on both is the stdlib's own engine (a port of Apache
  Harmony's), not JavaScript's `RegExp` on wasm. It reads Java's syntax, but its classes are ASCII's, its case-insensitivity
  and some of its constructs aren't Java's, and it has bugs with code points past the BMP. So there the functions take a
  fixed grammar, the part of Java's that is simple and that `HarmonyRewrite` makes Harmony match exactly, and refuse the
  rest by name.

**Native and wasm take**, each matching as Java's does:

- literals and escapes (`\t`, `\n`, `\xNN`, `\x{…}`, `\uNNNN`, `\0`…, `\cX`, a backslash before punctuation, `\Q…\E`);
- classes, `[…]`, nested, negated and intersected as Java reads them, POSIX's (`[:alnum:]` …), `\p{…}` and `\P{…}` by every
  name Java knows, `\w`, `\d`, `\s`, `\h`, `\v` and their negations, and `.`: each written out as the code points Java's
  holds with `UNICODE_CHARACTER_CLASS`, from tables gen/Generate.java writes on the pinned JDK (`RegexTables`);
- greedy and lazy quantifiers (`*`, `+`, `?`, `{n}`, `{n,}`, `{n,m}`, and each with `?`) on one of those;
- greedy quantifiers on a group, and `*?` and `??` on one;
- capturing, named and non-capturing groups, and alternatives;
- `^`, `$`, `\b`, `\B`, `\A`, `\z` and `\Z`;
- the inline flags `(?i)`, `(?s)`, `(?m)`, `(?x)`, `(?d)`, `(?u)` and `(?U)`, alone or as `(?i:…)`;
- and one lookahead: `(?=…)` or `(?!…)` of literals, classes, alternatives, `$` and `\z` alone, unquantified, as a
  full-text search's `(?=$|[^[:alnum:]_])` is.

Every pattern the compiler, a full-text search and `host.yaml` write is inside it: `DrivinePatternsTest` builds each from
the compiler's own constants and functions and compiles it on every target, and `RegexOracleTest` fails where any the
compiler writes over the corpus is refused.

**Native and wasm refuse**, failing the statement with the construct's name (`regexp: a lookbehind is not supported on
this platform; rewrite the pattern without it.`):

- a lookbehind, and any lookahead but the one above (`a lookahead holding a quantifier`, `… a group`, `… an anchor but $
  and \z`);
- a back reference, `\1` or `\k<name>`;
- a possessive quantifier, and an atomic group, `(?>…)`;
- `\G`, `\X`, `\R`, `\N{…}` and `\b{g}`;
- a quantifier on what has no width (`^*`, `\b{2}`, `(?=a)+`, and a group of nothing else, `(\b)*` or `()+`, which Java
  repeats otherwise), and a quantifier on a quantifier (`a{2}{3}`, which Java reads);
- a lazy quantifier on a group but `*?` and `??` (`(bc)+?`), which Harmony counts wrongly;
- a capturing group in a group repeated (by any quantifier but `?`) whose length can't vary, with no alternative and no
  quantifier but `{n}` in it (`(?:(a))*a`): Java leaves the group as a repetition it gave back set it;
- a group that can match nothing, under any quantifier (`?` and `{1}` too), in a group repeated (`(?:a(?:b*)*){2}`,
  `(?:a(?:b*)?)+`), which Harmony stops repeating early;
- groups repeated within each other more than 3 deep (`((((a)*b)+c)*d)*`): the compiler's take 2, and each level deeper
  multiplies the backtracking and stack Harmony takes where its repetition of groups is least like Java's;
- `(?-U)` and `(?-u)`, which turn off what the patterns always have on, and `(?c)`, after which Java reads each class and
  `\p{…}` by graphemes;
- and a pattern written as more than 2,000,000 characters (each `\b` is some 35,000: 30 million characters took 1.2 GB and
  two seconds to compile).

This is a documented limit of native and wasm alone: on the JVM the functions keep the whole of `java.util.regex`. The
comments in `HarmonyRewrite` and `HarmonyPattern` name the Kotlin 2.2.20 bugs each workaround within the grammar is for,
and `HarmonyBugsTest` fails once Kotlin fixes one, so its workaround can come out.

**The stack.** Harmony's engine recurses: as it compiles, into each group and along each node, and as it matches, for each
node it passes and again for each repetition of a group, so `(a|b)*` recurses once a character. Java's does too, and throws
`StackOverflowError` where its thread's stack runs out (measured on JDK 21: `(a|b)*` over about 770 characters on a 512 KiB
stack, 7,800 on 1 MiB, 73,000 on 8 MiB); on Kotlin/Native an overflow is a crash that ends the host's process. So on
Kotlin/Native (`StackRoom`) the module compiles a pattern only where the thread has stack left for the pattern's longest
path through Harmony's nodes, 1 KiB a node (4 KiB in a debug binary) and 64 KiB more. Only a group repeated (`*`, `+`,
`{n,}`, `{n,m}` with m over 1) takes more stack for a longer text, a path for each repetition: a quantifier on one
character, `.`, `\w` and a class, greedy or lazy, loops, and so do alternatives and lookaheads outside such a group. So
where the pattern repeats a group and the text is longer than the stack left covers at a path a character, or where it
repeats none and the stack doesn't cover the path once for each lookahead nested, the text is read through a check at each
character that the stack keeps that much, a check that made a full-text search's replace over 1,400 characters 984 µs
where it is 179 µs without; past it the statement fails (`regexp: the regular expression takes more of this thread's
stack than is left on this platform, …`). Measured on macOS arm64's release library, through SQLite: `(a|b)*` answers
over 29,694 characters on the main thread's 8 MiB, where it crashed from 29,772, and 818 on a Python thread of 512 KiB,
where it crashed from 949; groups nested 12,200 deep crashed compiling on 8 MiB, and are refused from 7,800; 20,300 nodes
in a row (`a?` …) crashed, and are refused from 8,200.
Kotlin/Wasm's overflow is a `RangeError` its host's JavaScript catches, and isn't checked.

**Long texts and `$`, `\Z`, `\b`, `\B`.** These look at the character before a place, and Harmony's lookbehind takes time
proportional to how far into the text it is tried, as it looks for its body ending there from each place back to the
text's start: a pattern written with one takes time quadratic in the text's length. So `HarmonyRewrite` writes them with
a lookbehind that holds at the code point before wherever it is tried, found at once, and captures what it reads there
in a group of its own, which a back reference after it reads. Measured on macOS arm64's release library, through SQLite,
before (#204) and now:

| Call | Text | Before | Now |
|---|---|---|---|
| `\b` replace | 10,000 chars | 1.06 s | 12 ms |
| `\b` replace | 40,000 chars | 16.8 s | 39 ms |
| `graph$` replace | 700,000 chars | 6.5 s | 11 ms |
| a full-text search's term, replace | 2.1M chars | 46 s | 0.22 s |

`LongTextAnchorsTest` fails on every target where a `\b` replace over 40,000 characters or a `$` over 700,000 takes over
5 s: about ten times what a debug test binary takes now, and well under the 133 s and 47 s it took before.

A process keeps the 256 patterns used last (`JavaPatterns`), for every connection: a pattern rewritten for Harmony takes a
millisecond or more to compile, its classes written out, where a find takes about a microsecond. `RegexOracleTest` checks
every target against Java's answers (`regex.json`, see [Checks](#checks)), both ways where it can: `JavaPatterns`, as the
functions compile a pattern there, and the rewrite, which on the JVM runs on `java.util.regex` without
`UNICODE_CHARACTER_CLASS`, so the JVM checks the rewrite's tables too.

`HostSpecTest`, in the common tests, runs the same steps on every target (the JVM, native and wasm), calling the module's
code as SQLite would. A function `host.yaml` calls that the module doesn't implement yet is on its `PENDING` list, which
must hold exactly those, and each function the module implements must have a step.

## Building

JDK 21 runs Gradle. The build compiles with Temurin 21, provisioned where it isn't installed, and writes the zone ids
and the JVM's answers on the exact JDK it pins (see [The pinned JDK](#the-pinned-jdk)).

```sh
./gradlew jvmTest                  # the JVM's tests, and the double and stand-in fuzz on the JVM
./gradlew wasmJsNodeTest           # the double and stand-in fuzz on wasmJs
./gradlew macosArm64Test           # the double and stand-in fuzz on native (linuxX64Test on Linux)
./gradlew wasmJsCorpusCheck        # the corpus on wasmJs (node on the PATH, or -Pnode=<path>)
./gradlew nativeCorpusCheck        # the corpus on the host's native target (python3, or -Ppython=<path>)
./gradlew nativeExtensionCheck     # the SQLite extension, loaded by apsw (a python with apsw, -Ppython=<path>)
./gradlew metadataCompile          # common code compiled alone
./gradlew npmPackage               # the npm package, in build/npm
./gradlew pythonWheels             # the Python wheels, one a native target, in build/wheels (python3, or -Ppython=<path>)
```

Run `./gradlew clean` before a build you check: the wasm optimize step can leave stale output behind.

`check/corpus-classify.sh [ref]` compiles this checkout's spec statements with the compiler at `ref` (`origin/main` by
default, unpacked with `git archive` into a directory of its own, so the checkout is not touched) and with this checkout's,
and puts each answer that differs, where the statement involves no temporal value and no key that begins with `$`, down to
one named cause of the store layout 2 by a marker it leaves in the SQL where the answers differ, not beside it
(`check/corpus-classify.py`). It prints the counts for each cause and store, and fails on an answer it can put down to none.

What the build generates (`gen/Generate.java`, run on the pinned JDK): the zone ids that JDK's `ZoneId.of` takes, which
the other targets check a zone name against, as their own zone databases (the OS's, Node's ICU) are other tzdb releases;
the tables of powers of ten and five the double text and parse use; and the answers of that JDK's Unicode that a full-text
query is read by off the JVM (`UnicodeTables`): the characters a word holds, those Oracle's `[[:alnum:]]` was measured to
hold, each code point's lower case, the characters `String.toLowerCase` counts as cased before a final sigma, and the class
each character is to the word breaks it reads a final sigma by, measured on `BreakIterator` itself.

### Dependency verification

Gradle checks every dependency the build uses, fetched or found in its cache, against `gradle/verification-metadata.xml`,
and refuses one whose SHA-256 isn't there: each plugin's and library's jars, klibs, POMs and modules, Kotlin's compiler,
Kotlin/Native's prebuilt bundle, and the Node, Yarn and binaryen archives Kotlin fetches for wasmJs. The file is what a
release is built from, so it changes only in a reviewed pull request, as code does. Sources and javadoc jars, which an
IDE fetches and no build runs, aren't checked. Kotlin/Native's own dependencies, which it fetches itself and Gradle never
sees, are pinned for the release apart (see [Releasing](#releasing)).

A change that adds, removes or moves a dependency or a plugin (a bump of Kotlin, antlr-kotlin, nmcp, kotlinx, Jackson,
JUnit, the Node version) updates the file in the same pull request, or every build stops at the first dependency it
doesn't name:

1. On macOS arm64, with a Gradle home and a Kotlin/Native home of their own, so every file is fetched now, not taken
   from an old cache, write the checksums of every file the CI jobs and the release use (`<jdk>` the pinned JDK's home):

   ```sh
   GRADLE_USER_HOME="$(mktemp -d)" KONAN_DATA_DIR="$(mktemp -d)" ./gradlew --no-daemon \
     -Porg.gradle.java.installations.paths=<jdk> --write-verification-metadata sha256 clean jvmTest metadataCompile \
     publishJvmPublicationToMavenLocal publishKotlinMultiplatformPublicationToMavenLocal -Dmaven.repo.local="$(mktemp -d)" \
     wasmJsNodeTest wasmJsCorpusCheck macosArm64Test nativeCorpusCheck linkDrivinecypherReleaseSharedLinuxX64 \
     linkDrivinecypherReleaseSharedLinuxArm64 linkDrivinecypherReleaseSharedMingwX64 zipAggregationPublication \
     npmPackage -Pcompiler.version=0.0.0-ci
   ```

   Gradle adds to the file and never removes from it: to drop what nothing uses any more, move the file aside first, and
   copy its `<trusted-artifacts>` and step 2's entries back after.
2. Linux CI fetches the Linux builds of three archives macOS doesn't: `kotlin-native-prebuilt-<kotlin>-linux-x86_64.tar.gz`,
   `node-<node>-linux-x64.tar.gz` and `binaryen-<version>-x86_64-linux.tar.gz`. Add each beside its macOS one, with the
   SHA-256 its publisher gives (Node's `SHASUMS256.txt`, the binaryen release's `.sha256`; Maven Central gives
   Kotlin/Native's only as a `.sha1`, so fetch the archive, check it against that, and take its SHA-256), saying where it
   came from in `origin`. Running step 1's tasks that Linux runs on Linux x64 adds them too.
3. In the pull request's review, read the file's diff, which names each file and its SHA-256: each new one is one the
   change means to bring (look into a new group, or a new dependency of a dependency), and each new archive, and a sample
   of the jars (Kotlin's compiler among them), has the checksum its publisher gives (Maven Central's `.sha1` or `.sha256`
   beside each file, the Plugin Portal's likewise).

A bump of Kotlin also moves Kotlin/Native's own dependencies, which `gradle/kotlin-native-dependencies.txt` pins (see
[Releasing](#releasing)).

### The pinned JDK

`gradle/pinned-jdk.properties` pins one JDK exactly: Temurin 21.0.13+9, which brings tzdb 2026e. It writes the zone ids
and the JVM's answers (`jvmOracle`), and CI runs every job on it. Compiling and the JVM tests take any Temurin 21.
Where the pinned JDK isn't installed, the build fetches Adoptium's archive of it once, into `~/.gradle/drivine-jdks`,
and checks it against the SHA-256 pinned for the platform.

A JDK release can bring a new tzdb, and with it zones the compiler takes or refuses, so the pin changes only in a
reviewed pull request. To bump it:

1. Set `version` to the new Temurin 21 release, and each `sha256.<os>-<arch>` to Adoptium's checksum of that release's
   archive (`https://api.adoptium.net/v3/assets/release_name/eclipse/jdk-<version>?os=<os>&architecture=<arch>&image_type=jdk`).
2. Nothing else names it but this section's first line (with `tzdb`): CI installs the JDK from Adoptium's archive of
   `version`, checked against its `sha256` (`.github/actions/pinned-toolchain`). Merge the bump to `main` before the next
   tag, so main's run fetches the new archive into the cache a tag's gate reads, and keeps the release's dependencies
   under the new pin (see [Releasing](#releasing)).
3. Run `./gradlew generateTables`. Where the JDK brings another tzdb, it stops and names it: set `tzdb` to that.
4. Open the pull request, and say in it which zones the new tzdb adds or removes (the diff of the generated
   `build/generated/tables/org/drivine/cypher/time/JvmZoneIds.kt` against the old one's).

## Checks

The JVM is the oracle. `./gradlew jvmOracle` writes, into `build/oracle`:

- `corpus.jsonl`: every statement in the spec's files (`queries.yaml`, `writes.yaml`, `temporal.yaml`, `refusals.yaml`,
  `schema.yaml`), compiled for Postgres, Oracle and SQLite, and some with parameters the spec has no form for (whole
  floats, temporal values, bytes, `$` keys) and Oracle's graph options, and on Oracle with `oracleRowsAsText` each of
  those statements whose SQL it changes (every write that returns rows), and two writes of its own, of a long text and of
  many rows, with `oracleReencodeJson` each of those statements whose SQL it changes (every one that returns rows), and
  with `oracleReadsAsText` each of those statements whose SQL it changes (every one that only reads).
  Last, compiled typed: `results.yaml`'s statements, and each other statement whose SQL typed changes (one
  that returns a node or a relationship), on Oracle with `oracleRowsAsText` too where that changes it, and with
  `oracleReencodeJson`. Each request and the JVM's answer. A statement whose SQL embeds the moment it was compiled, as SQLite's
  does for `datetime()` and the like, is left out, found by its SQL holding that moment.
- `doubles.txt`: random doubles (subnormals included) and Java's text of each, and random decimal and hexadecimal texts,
  halfway points between doubles and texts a digit either side, and Java's reading of each.
- `mutants.jsonl`: a sample of the spec's statements mutated (`Mutants`), which no corpus statement is: truncated,
  a character or a token deleted, junk inserted, two tokens swapped, a clause written twice, a literal made an edge
  value, a function's name a letter off. Only a mutant whose answer isn't the parser's syntax refusal is kept, as the
  stdlib's exceptions and texts reach a refusal off those paths: 10,000 of them (`-Pmutants.count=<n>`), from the seed.
  Where fewer are of interest, `jvmOracle` fails.
- `store.json`: the spec's `store.yaml` as JSON, which `StoreDdlTest` reads on every target: the store layout's DDL
  each generator in `org.drivine.cypher.store.ddl` must give, with the form each SQL store's create is pinned to by its
  digest, the object names Java's `String.hashCode` gives, and the reads the compiler must answer from each index; and,
  which `StoreSchemaTest` reads, the form `org.drivine.cypher.store.schema` must read each item as from listing rows
  captured from each SQL store.
- `marker.json`: the spec's `marker.yaml` as JSON, which `StoreMarkerTest` reads on every target: each store's
  statements for the schema marker and the store's stamp that `org.drivine.cypher.store.marker` must give, the
  inventories it must write and read back, the version tokens, and the node stamps `Stamps` must read and write.
- `plans.json`: the spec's `plans.yaml` as JSON, which `StorePlanTest` reads on every target: each case's rounds that
  `org.drivine.cypher.store.plan`'s planner must give on each store, the answers it is given back, and its report.
- `host.json`: the spec's `host.yaml` as JSON, which `HostSpecTest` reads on every target: the `function` steps the
  module's host functions (`org.drivine.cypher.host.HostFunctions`) must give. `./gradlew hostSpecJson` writes it alone,
  into `build/host`, for `nativeExtensionCheck`.
- `regex.json`: Java's answers for the regular expressions SQLite's functions see, which `RegexOracleTest` reads on every
  target (see [Regular expressions](#regular-expressions)): each pattern the compiler writes on SQLite over the corpus and
  the mutants, each a full-text search gives, each of `host.yaml`'s, and users' `=~` patterns written and wrapped as `=~`
  wraps them, over texts of many scripts, code points past the BMP and every line break; whether each is found, its first
  match's groups, its count and every match replaced, or Java's refusal; and what `(?i)` takes for each code point with a
  case. `RegexOracleTest` fails on any other answer, on any refusal but the platform's named ones, and on any refusal of a
  pattern the compiler, a full-text search or `host.yaml` gives.
- `search-golden.json`: drivine4j's full-text search statements (see [Full-text search](#full-text-search)), which
  `FullTextSearchGoldenTest` reads on every target. Its requests are in `corpus.jsonl` too.
- `standins-<name>.txt`: what Java gives where the module stands in for it, worked out by Java's own API on the pinned
  JDK (`StandIns`): `ZoneId.of`'s reading of a zone; a full-text query's classes of characters and its lower case; java.time's calendar dates, and its parse and text of a temporal
  value, with java.math's of a duration; `substring`, `Integer.parseInt(s, 16)` and `appendCodePoint` of a string
  literal's escape; a text's UTF-8; SHA-256; case mapping and character classes; `String.format`'s `%s`;
  `parseDouble`'s refusals, `BigInteger`'s reading and `doubleValue`, `BigDecimal`'s text of a double, `Math.rint`;
  Jackson's JSON text of a value; and SQLite's timestamp format.

The corpus checks compile each request on wasmJs (the npm package, through its CommonJS entry) and on native (the
release shared library, through its C ABI from Python), the corpus's and then the mutants', and fail on any byte that
differs from the JVM's answer. The double fuzz checks each target's text and reading of each double against Java's. The stand-in fuzz (`JavaStandInFuzzTest`) checks
each stand-in's answer, or the message it throws, against Java's on every target, the JVM included. Off the JVM it lets be
the case mapping Kotlin's own tables give a final sigma and Unicode 14's new case pairs, which no name the compiler
compares can hold.

## Doubles

Kotlin/Wasm and Kotlin/Native don't write or read doubles as the JVM does: Kotlin/Native writes about one double in 1,800
otherwise, and Kotlin/Wasm reads about one in 400 an ulp off. The SQL holds doubles' text, so off the JVM the module
writes and reads them itself (`org.drivine.cypher.number`), and the JVM keeps `Double.toString` and `parseDouble`:

- `DoubleText`: R. Giulietti's Schubfach, implemented from his paper ("The Schubfach way to render doubles", 2020,
  revised 2022), which is what `Double.toString` is since JDK 19. Not from OpenJDK's source, which is GPLv2 with the
  Classpath Exception: that exception covers linking, not a port of the source into this Apache-2.0 repository.
- `DoubleParse`: Java's grammar of `parseDouble`, a decimal read by Eisel and Lemire's algorithm (D. Lemire, "Number
  Parsing at a Gigabyte per Second", 2021), and where that can't tell which way a number rounds, exactly, with big
  integers.

## Syncing from drivine4j

**The sync ends when drivine4j switches to this module** (liberation-data/drivine4j#662, which builds drivine4j against
the JVM artifact and deletes the files `scripts/sync-paths.txt` maps). From that commit on, this module is the compiler's
one copy: a compiler change, its tests among it, is made here, and drivine4j takes it by moving the commit
`gradle/cypher-compiler.properties` pins. `scripts/sync-from-drivine4j.sh` refuses a drivine4j commit that holds that pin,
or that has no `src/main/kotlin/org/drivine/cypher`: a sync to one would read each mapped file's deletion as a change,
delete the module's files that still match drivine4j's, and leave half a module. What follows is how the module was kept
in step until then, for a change on a drivine4j branch from before the switch.

The compiler's sources came from drivine4j (`src/main/kotlin/org/drivine/cypher` and the few helpers it calls), and its
pure compiler tests from drivine4j's tests. `DRIVINE4J` holds the drivine4j commit they were last synced from, and
`scripts/sync-paths.txt` which file came from which. To take drivine4j's changes since:

```sh
compiler/scripts/sync-from-drivine4j.sh <drivine4j commit>      # DRIVINE4J_REPO=<clone> to read a local clone
```

It merges each changed file three ways (drivine4j's at the old commit, the module's, drivine4j's at the new one), so the
module's edits for common Kotlin are kept. It copies in a file drivine4j added, writes the new commit to `DRIVINE4J`, and
lists conflicts to resolve. Then build, run every check, and commit. `compiler/scripts/test-sync.sh` tests the sync itself,
over a made-up drivine4j.

Before it merges, it puts drivine4j's two files through the port's mechanical rewrites, `scripts/port-rewrites.sed`, so a
line the port changed by them alone isn't a conflict. Each rewrite maps a JVM library's name, or a drivine4j name the
module holds under another, to the module's name for the same thing, and nothing more:

| drivine4j | the module |
|---|---|
| `org.antlr.v4.runtime.…` | `org.antlr.v4.kotlinruntime.…` (antlr-kotlin) |
| `import com.fasterxml.jackson.databind.JsonNode`, `….node.ObjectNode` and the other node types | `import org.drivine.cypher.json.…`, the module's tree of the same names |
| `import java.math.BigDecimal`, `BigInteger` | bignum's |
| `syntaxError(recognizer: Recognizer<*, *>?, …, msg: String?, …)` | antlr-kotlin's signature of it, non-null |
| `org.drivine.DrivineException`, `DrivineException` | `org.drivine.cypher.CompilerException`, `CompilerException` |
| `org.drivine.backend.oracle.OracleSql`, `….backend.sqlite.SqliteSql`, `SqliteCypherValues`, `SqliteFunctions`, `SqliteTimestamps`, `org.drivine.model.Stamps`, `LowerCopies` | `org.drivine.cypher.store.…`, the part of each the compiler calls |

A file drivine4j adds is copied in with the same rewrites. Anything else the port changed (java.time, Jackson's mapper,
`String.format`, a double's text in a refusal) is its own edit, and a drivine4j change to such a line is a conflict to resolve by hand. Add a rewrite
only where it is a rename that can't change meaning.

After the merge, it lists what to look at by hand (`scripts/sync-report.py`):

- Files outside the mapping that the compiler imports, and that changed. The imports are followed from drivine4j's
  mapped sources through each file they import, and from a mapped source to a file of its own package whose names its
  code uses, which needs no import. A file only the library uses, such as full-text search, isn't listed.
- Files the compiler imports in drivine4j that the port replaced (the module's copy dropped the import), and that changed:
  check the replacement.
- New tests in drivine4j's `src/test/kotlin/org/drivine/cypher` that the mapping has no line for: add each that needs no
  store to `scripts/sync-paths.txt`.

## Releasing

A spec tag is a compiler release: pushing the tag `vX.Y.Z` on `main` publishes the compiler to Maven Central as
`org.drivine:drivine-cypher-compiler` `X.Y.Z`, with one artifact for each target (`-jvm`, `-wasm-js`, `-macosarm64`,
`-linuxx64`, `-linuxarm64`, `-mingwx64`), and its wasmJs build to npm as `@liberation-data/drivine-cypher-compiler`
`X.Y.Z` (the name is `compiler.npmName` in `gradle.properties`), and its native libraries to PyPI as `drivine-cypher-compiler` `X.Y.Z`. The compiler and the spec it was checked against then share one number, so a
library pins one tag for both. A tag that changes only the spec publishes the compiler again, unchanged, under the new
number.

`.github/workflows/compiler-release.yml` does it:

1. It checks the tag (`vX.Y.Z`, on `main`), and runs the compiler's CI, `compiler.yml`.
2. On macOS, which builds every target, it builds every publication, unsigned, into one bundle, and checks it
   (`.github/scripts/compiler-release-check.sh`): exactly the artifacts above, each with its jar or klib, sources,
   javadoc, POM and module at the tag's version (and each native target's klib of the SQLite extension's cinterop,
   `-cinterop-sqlite3ext.klib`, which Kotlin publishes beside its klib). The bundle is kept as the run's artifact.
3. The upload waits in the `release` environment for its reviewer. It signs that bundle with the release key
   (`.github/scripts/compiler-release-sign.sh`) and uploads it to the Central Portal
   (`.github/scripts/compiler-release-upload.sh`), as one deployment, which releases every target or none. It then
   follows the deployment until the Portal has published it, and fails with the Portal's reasons where it fails it.

What is published is the bundle step 2 checked, byte for byte, with a signature beside each file. Step 3 builds nothing,
and needs no JDK, Gradle or dependency: only the bundle, this commit's scripts, and the runner's gpg, curl, jq and zip.
Before it is given a secret, it refuses a bundle whose SHA-256 isn't the one step 2 gave as its job's output, which no
other job of the run can set, so an artifact of the same name put in the bundle's place is never signed; a re-run of the
failed upload alone keeps step 2's output, and its bundle. Before it imports the key, it checks the bundle holds every artifact at the tag's version and nothing else: no link,
which zip would follow into the job's keyring or environment, no file the publications don't make, no other version,
no checksum or signature of a file it doesn't hold, nothing outside `org/drivine`. Then it checks each file's checksums in the bundle; after signing, each signature against
the key, and the signed bundle as step 2 checked it. The key is imported into a keyring made for the job and deleted
after it, and the key's variables are unset before the signing runs anything, so no process the signing starts is given them. Neither the
key, its passphrase nor the Portal token is passed on a command line or written to the log: the token's base64, which
GitHub doesn't mask as it masks the secrets, is masked too. The signed bundle is kept as the run's artifact only once
it is published. A failed upload is re-run
alone (its own "Re-run this job", not "Re-run failed jobs", which also re-runs a `dependencies` job that failed or was
cancelled, and step 2 after it), and waits for the reviewer again; the upload itself isn't retried, as a second would make
a second deployment of the version, so look at https://central.sonatype.com/publishing/deployments before re-running one
that failed without the Portal's answer.

**npm.** Step 2 also builds the npm package (`./gradlew npmPackage`, at the tag's version), packs it as npm publishes it,
and checks the tarball (`.github/scripts/compiler-npm-check.sh`, which `compiler-npm-check-test.sh` tests over made-up tarballs in the gate): the package's name and the tag's version, its
files and nothing else (the wasm, its loaders, the CommonJS entry, the types, `npm/README.md` and the repository's
LICENSE), and no `scripts` or `publishConfig` in its `package.json`, which `npmPackage` never writes: scripts would run
on each install of the package, and npm publish takes any setting `publishConfig` holds that its command line doesn't,
the registry it publishes to among them. The tarball, its SHA-256 and its npm integrity are kept beside the bundle. Its publish is a job of its own,
`publish-npm`, which also waits in the `release` environment for its reviewer, and needs only the tarball: it checks
the tarball's SHA-256 is step 2's, then publishes it by npm's trusted publishing, with no npm token stored anywhere:
npm's settings for the package name this repository, `compiler-release.yml` and the `release` environment as its
publisher, and the job's OIDC token (`id-token: write`) proves it is that workflow. The same token gives npm's provenance
(`--provenance`), which records the run and the commit that built the package. The publish runs none of the package's
scripts (`--ignore-scripts`). The job runs on an exact Node release (24.22.2, of the 24 LTS), and installs npm 11.21.1, as
desk's and Drivine for TypeScript's releases do: trusted publishing needs 11.5.1 or later. It installs npm from its
registry tarball only once the tarball has the integrity pinned beside the version (`NPM_CLI_VERSION` and
`NPM_CLI_INTEGRITY` in `compiler-release.yml`; npm bundles its dependencies, so the tarball is the whole of it). To bump
npm, set both, the integrity from `npm view npm@<version> dist.integrity`; to bump Node, set `node-version` in both npm
jobs. A version with a suffix (`-alpha.N`, `-beta.N` or `-rc.N`, the ones PyPI's versions can say too: the release refuses
any other tag) is published under the npm tag `next`, and
any other under `latest`. The three publishes (`publish`, `publish-npm`, `publish-pypi`) each wait for every
`wheels-smoke` as well as step 2, so they wait in `release` at once and one approval releases them; a wheel that fails
its check holds the release back. A `wheels-smoke` that failed on its runner rather than its wheel is re-run with its own
"Re-run this job", which keeps step 2's outputs and runs the three publishes after it. Once approved they don't wait on each other, so a failure of one neither undoes nor
holds up the others. A re-run publishes nothing where npm has the version as this tarball (by its integrity), and fails where npm
has another tarball under the version, as npm never takes a version twice. Once Maven Central has the version, a
failed `publish-npm` is finished with its own "Re-run this job", which keeps step 2's tarball and its outputs, and never
with "Re-run all jobs", nor with "Re-run failed jobs" where the `dependencies` job failed or was cancelled, as either
re-runs step 2: a re-run of step 2 builds again, and its build stops at "Maven Central doesn't have this version yet", so
`publish-npm` never runs. The gate's wasmJs job packs the package
too, installs it into an empty project with only the dependencies its `package.json` names, and compiles the corpus
through it, byte for byte the JVM's (`.github/scripts/compiler-npm-clean-install.sh`). A dry run gives the tarball to
`npm publish --dry-run`, which sends nothing.

**PyPI.** Step 2 also links the four shared libraries and packs them as wheels (`./gradlew pythonWheels`, at the tag's
version, as PEP 440 writes it: `X.Y.Z-rc.N` as `X.Y.ZrcN`), and checks each (`python/check_wheel.py`): the package's
name and version, one platform's library, the binding and the `.dist-info`, and nothing else, with every file's hash its
`RECORD`'s. It keeps the wheels, in `python-release/` (not `release/`, which the bundle's check empties), as the run's
artifact, with their SHA-256s, a list as a job output, and the JVM's answers to the corpus and its mutants, with a SHA-256
each file. `wheels-smoke` then takes each wheel to its own platform (Linux x86_64 and
aarch64, macOS and Windows), checks it is step 2's, installs it alone into a fresh venv, and compiles the corpus and the
mutants through the installed package, every answer byte for byte the JVM's (`check/corpus-native.py --installed`). It
loads the installed library into apsw's SQLite, installed by hash into the same venv, as the SQLite extension
(`check/extension-native.py --installed`, see [The SQLite extension](#the-sqlite-extension)). On
Linux it checks the wheel holds to its manylinux tag with auditwheel, installed by hash
(`python/check-requirements.txt`); on macOS, that every library it loads (its `LC_LOAD_DYLIB`s) is the system's, under `/usr/lib/` or
`/System/Library/`. The publish is a job of its own,
`publish-pypi`, which needs every `wheels-smoke` and waits in the `release` environment for its reviewer, and needs only
the wheels: it downloads them into the workspace's `dist/` (the publish action runs in a Docker container, which sees
the workspace and not the runner's temp directory), checks they are exactly step 2's, by SHA-256, then publishes them with `pypa/gh-action-pypi-publish` by
PyPI's trusted publishing, with no PyPI token stored anywhere: PyPI's settings for `drivine-cypher-compiler` name this
repository, `compiler-release.yml` and the `release` environment as its publisher (the project was claimed with a
placeholder 0.0.0, by hand, before the first tag), and the job's OIDC token proves it. The same token signs a PEP 740 attestation of each wheel. A re-run leaves
out a wheel PyPI has already as this file (by its SHA-256), and fails where PyPI has another under its name, as PyPI
never replaces a file. Like `publish-npm`, a failed `publish-pypi` is finished with its own "Re-run this job", which
keeps step 2's wheels and outputs. A dry run checks the wheels as `publish-pypi` does and uploads nothing.

The jobs that build install the pinned JDK and Gradle with `.github/actions/pinned-toolchain`: from Adoptium's archive
of `gradle/pinned-jdk.properties`'s `version` and the zip `gradle/wrapper/gradle-wrapper.properties` names, each checked
against the SHA-256 pinned beside it, so a cache can't bring another. Adoptium and services.gradle.org serve those
archives as GitHub release assets, whose outage failed v0.31.0's upload three times, so `compiler.yml`'s jobs keep them
in the repository's Actions cache, each under its checksum, and saved as soon as it is fetched, so a re-run keeps what
an earlier attempt got. A tag's run reads the caches of `main`, so the first tag after a bump of the JDK or of Gradle
runs its gate fastest after a run on `main` since the bump (its merge, or workflow_dispatch of `Compiler` on `main`);
GitHub drops a cache no run has read for 7 days. Where nothing is cached, a job fetches them, with retries; where it
can't, it fails naming the URL: re-run it once GitHub's release downloads work.

**Step 2's dependencies.** Step 2 fetches Kotlin's compiler, Kotlin/Native's prebuilt bundle (about 250 MB), every
other dependency, the JDK and Gradle archives, and Kotlin/Native's own dependencies: Maven Central serves some of
Kotlin's large artifacts through GitHub's release downloads, whose outages failed v0.33.0's step 2 twice, so it takes
them from the Actions cache where they are kept. But it restores no cache itself. actions/cache unpacks an entry with
`tar -P`, which writes wherever the entry's names point, outside the cached paths too, and an entry can be saved by any
job of a run on its ref, the gate's among them, which run third-party actions and what those fetch. An entry restored
into step 2 could change what it builds whatever it then checked. So:

- `compiler.yml`'s `release-dependencies` job (on macOS, in every run but a tag's or a fork's pull request's) builds what
  step 2 builds, on a runner with no Gradle or Kotlin/Native cache restored (only the toolchain archives', which it
  checks), and keeps what that fetched, as files, in the Actions cache: Gradle's downloaded files
  (`~/.gradle/caches/modules-2/files-2.1`: the jars, klibs, POMs, modules and archives), the JDK and Gradle archives,
  and Kotlin/Native's own dependencies (`.github/scripts/compiler-release-dependencies.sh stage`). Nothing derived from
  them is kept (Gradle's index of them, transformed jars, compiled build scripts, unpacked distributions, the Kotlin
  daemon), as nothing would check it. Its key hashes what pins them (`gradle/verification-metadata.xml`,
  `gradle/kotlin-native-dependencies.txt`, `gradle/pinned-jdk.properties`, `gradle/wrapper/gradle-wrapper.properties`)
  and `compiler-release-dependencies.sh`, whose layout the entry is, after a version (`v2`), bumped in both workflows
  where step 2 comes to build more than the entry was made for, so a change of either makes a new entry, kept by
  the first run that finds none: the change's pull request, for its own runs, and its merge, for the tags after. A run
  that finds the entry kept does nothing more. `stage` keeps only the files `unpack` keeps, by the same check (below),
  so an entry it made unpacks with no warning, and a warning at `unpack` means the entry isn't one `stage` made. It
  leaves out, each time, the POMs Gradle downloads but doesn't record in `gradle/verification-metadata.xml`, of modules
  published with Gradle's module metadata (some 80 of them), so step 2 fetches those each release: small files, from
  Maven Central and the Gradle Plugin Portal. `stage` fails where a file it keeps has a name `unpack` would refuse, or
  where it holds more entries or bytes than `unpack` takes, so such an entry fails this job, not every release, quietly, with only a warning. GitHub drops an entry no run has read for
  7 days, so the first tag after a quiet week fetches everything; to keep it warm before a tag, run workflow_dispatch of
  `Compiler release` on `main` (a dry run), whose `dependencies` job restores it (`Compiler`'s own job only looks it
  up, which may not count as a read). An entry is about 1.2 GB, of the repository's 10 GB of Actions cache: a change of
  a pin makes two (its pull request's and `main`'s), and GitHub drops the least recently used caches beyond that, the
  gate's among them, or `main`'s entry itself after several such pull requests, which the next run on `main` keeps
  again. A fork's pull request keeps none, as no release reads it.
- In the release, the `dependencies` job restores that entry (its exact key, from the tag's scope or `main`'s) on a
  runner of its own, which holds no secret and builds nothing, and uploads its files as the run's artifact. Where none is
  kept, or the upload fails, it uploads nothing, and the job itself never fails the run; step 2, and the jobs after it,
  run however it ends, failed or cancelled at its 20-minute timeout (as a runner an entry took over could make it), as
  they ask only for the success of the jobs before them that they use. What its upload can do to step 2 is below.
- Step 2 asks GitHub the artifact's size first, and downloads it only where it is one artifact of at most 3 GB, as
  the download is written whole before anything checks it, and by the ID it asked about, so an artifact put in its
  place after isn't downloaded. It downloads it as the zip it is, unopened, and
  `compiler-release-dependencies.sh unpack` checks it before it puts anything in place: every entry is a plain file or directory at a path `stage` makes (no link, no `.`
  or `..`, nothing but Gradle's files, the toolchain archives and Kotlin/Native's archives), no name is there twice in
  any case, and there are at most 5,000 entries and 3 GB unpacked. It is unpacked by Python's `zipfile`, not `unzip`,
  which writes as much as an entry's data holds whatever size the zip declares for it: `zipfile` writes no more than the
  declared size, refuses an entry whose CRC is then wrong, and makes every file the owner's to read and write, whatever
  mode the zip gave it. Each of Gradle's files is kept only where its SHA-1 is the directory it sits
  in, as Gradle lays them out, and its SHA-256 is the one `gradle/verification-metadata.xml` pins for that file of that
  component. Gradle takes a file it finds at its SHA-1's path without checking it, and strict verification would then
  fail the build, so a file of other bytes left there would fail every release that read the entry; dropped, it is
  fetched. Each toolchain or Kotlin/Native archive is kept only where its name and SHA-256
  are ones pinned (the JDK's archive of a platform `gradle/pinned-jdk.properties` pins, the zip
  `gradle/wrapper/gradle-wrapper.properties` names, an archive `gradle/kotlin-native-dependencies.txt` names), so
  nothing but pinned archives sits on the runner's disk through it (at most the JDK of another platform pinned, which
  the build doesn't use, within the 3 GB). Where it drops a file, it warns, as `stage` keeps
  none such. Where a check fails, or a step of `unpack` does, it puts nothing in place, or
  takes away what it put, with a warning, and step 2 fetches everything; and should `unpack` itself fail, step 2 takes
  away those directories. Each file is then checked again as it is used: Gradle checks every dependency against
  `gradle/verification-metadata.xml` (`--dependency-verification strict`, whatever a `gradle.properties` says);
  `archive.sh` checks the JDK and Gradle archives against their pinned SHA-256s, and fetches one that isn't kept, or
  whose checksum isn't the pinned one; `.github/scripts/compiler-konan-dependencies.sh` does the same for Kotlin/Native's own dependencies
  against `gradle/kotlin-native-dependencies.txt`.

So the entry, whoever saved it, can't change what is published, nor make step 2 fail by what it holds: a Gradle file whose checksum
isn't the pinned one is dropped and fetched, as is an archive whose name or checksum isn't, and an entry that fails a
check is refused whole. At worst it makes step 2 fetch what the entry lacks, as where none is kept: Gradle tries each
fetch up to 8 times, over about 4 minutes (`gradle.properties`), and the archives are retried for a few minutes; where
it still can't, step 2 fails, before the reviewer is asked, and is re-run. What it can still do is take over the
`dependencies` job's own runner, which holds nothing step 2 needs but the upload: it could upload an artifact of a name
step 2 then can't use (`compiler-maven-central-<version>` or `compiler-npm-<version>`), which fails step 2's own upload,
and so the run, as any of the gate's jobs could too; `publish` signs only the
bundle whose SHA-256 is step 2's own output. What is still fetched: Gradle asks Maven Central and the Gradle Plugin
Portal for each dependency's POM, module and `.sha1`, small files, before it takes a file it already has, and fetches
the POMs `stage` leaves out. binaryen's
GitHub releases publish no `.sha1` it could ask for, so step 2 takes binaryen from a directory laid out as those
releases are, which `unpack` fills from the entry's copy, once checked (`-Pcompiler.binaryenMirror`, in
`build.gradle.kts`), and only this runner's: Gradle would fail where the directory holds the version but not this
runner's archive.

**Where an entry is no use.** Should a release still fail on what the cache held, or an entry need to go before GitHub
drops it, set the repository variable `COMPILER_RELEASE_SKIP_CACHE` to `true` (Settings, Secrets and variables, Actions,
Variables): the `dependencies` job then restores nothing, and step 2 fetches everything, as before this cache. Delete
the entry (`gh cache list --repo liberation-data/drivine-cypher-spec --key compiler-release-dependencies`, then
`gh cache delete <key> --repo liberation-data/drivine-cypher-spec`), and run workflow_dispatch of `Compiler` on `main`,
which keeps a fresh one; then delete the variable. Where step 2's upload failed because an artifact of its name was
already in the run, a re-run of that run may fail again, as the artifact stays: start a new run instead, with the
variable set, by deleting the tag and pushing it again.

The gate's jobs read their own caches, as before (setup-gradle's, the toolchain archives', `~/.konan`), and Gradle
checks the dependencies they use too; they build nothing that is released. Every action `compiler.yml` and this workflow
use is pinned to a commit.

**Kotlin/Native's own dependencies.** Kotlin/Native fetches its LLVM, libffi, and the sysroots and toolchains of the
targets it cross-compiles from download.jetbrains.com, as archives whose checksums aren't published, and checks nothing.
`gradle/kotlin-native-dependencies.txt` pins those step 2 uses on macOS arm64, each by its SHA-256 and URL, and
`compiler-konan-dependencies.sh fetch` puts each, checked, where Kotlin/Native looks for it before it would fetch one.
Then `block` points download.jetbrains.com nowhere for the rest of the job (Kotlin/Native's prebuilt comes from Maven
Central, through Gradle), so Kotlin/Native can't fetch, nor run, one the file doesn't pin: the build fails where it
tries. After the build, `check` also fails where Kotlin/Native unpacked a dependency whose directory's name isn't one
the file pins: a check of names, after the fact, which `block` makes a second line. A bump of Kotlin moves them:
step 2 and `release-dependencies` then fail, at the build (Kotlin/Native can't reach download.jetbrains.com for the
new one) or at "Kotlin/Native used only its pinned dependencies". To pin them, on macOS arm64, build what step 2 builds with a Kotlin/Native
home of its own, which prints each URL it fetches:

```sh
KONAN_DATA_DIR="$(mktemp -d)" ./gradlew --no-daemon zipAggregationPublication npmPackage | grep 'Downloading dependency'
```

Fetch each URL, take its SHA-256 (`shasum -a 256`), from a second network too where you can, as there is no published
checksum to compare with, and write the file's lines from them. The review checks each URL is download.jetbrains.com's,
under `kotlin/native`, and each name one that Kotlin's `konan.properties` gives for `macos_arm64` or a target it builds.

To bump Gradle, keep the checksum: `./gradlew wrapper --gradle-version <v> --gradle-distribution-sha256-sum <sum>`,
with the sum of the `-bin` zip from https://gradle.org/release-checksums/. Without one, every job stops at it. Check too
that Gradle still reads `gradle.properties`' retry settings, which are its internal names: with a repository that
can't be reached, `--debug` says `Waiting 2000ms before next retry. 7 retries left`.

A run from workflow_dispatch, or on a pull request that changes the release machinery, is a dry run: steps 1 and 2,
under the version `0.0.0-dry-run`, and step 3's signing, with a key made in the job and thrown away with it, with
GitHub's release downloads and Adoptium's API blocked. It checks the bundle's SHA-256 as step 3 does, and that the signed bundle is step 2's, file for file and
byte for byte, with a signature beside each file, keeps it as the run's artifact, and uploads it as step 3 does, to a
stand-in for the Central Portal on the runner, which refuses an upload that isn't the one nmcp made and a status call
for any other deployment. It also checks that the signing refuses a bundle it must not sign
(`.github/scripts/compiler-release-sign-test.sh`): one with a link among thousands, a file, a version or a path that no
publication makes, or a checksum of a file it doesn't hold. And in step 2, before it unpacks the dependencies, it checks
that `stage` keeps only the paths `unpack` takes, and of the files only those `unpack` keeps (leaving out a POM the
metadata doesn't name), so `unpack` of its entry warns of nothing, and fails beyond `unpack`'s limits, that `unpack` refuses whole a zip with a name that climbs out, an absolute
name, a path `stage` doesn't make, a name twice, a link, more entries or bytes than its limits, or an entry of more
bytes than it declares, drops a Gradle file
of other bytes at its real path, at another SHA-1's path or that the metadata doesn't name, takes entries whose modes
let no one read them and cleans up after them, takes away what it put in place where a step fails (in a `$(...)` too), drops a toolchain or Kotlin/Native archive whose name or SHA-256 isn't pinned, takes the test's
own pins only in the test, and lays out only the runner's binaryen archive with the pinned SHA-256, that the check of Kotlin/Native's dependencies fails one the list doesn't pin, and that their fetch
puts in Kotlin/Native's way only an archive with the pinned SHA-256
(`.github/scripts/compiler-release-dependencies-test.sh`).

The `release` environment is this repository's own, not drivine4j's, and is set up once in its settings before the first
release: a required reviewer, tags `v*` allowed to deploy to it, and the secrets drivine4j's release uses,
`CENTRAL_PORTAL_USERNAME`, `CENTRAL_PORTAL_PASSWORD`, `SIGNING_KEY` and `SIGNING_PASSWORD`. npm needs no secret. Without
the environment, a tag's run makes an empty one, with no reviewer, and step 3 stops at its check of the secrets. npm
trusts the environment by its name alone, so once its trusted publisher is set, `publish-npm` would publish from such an
empty `release` with no reviewer: keep the environment's required reviewer, and its rule that only tags `v*` deploy to
it, for as long as the publisher names it.

**npm's trusted publisher, once.** npm lets a trusted publisher be set only on a package that exists, so the package's
first version is published by hand, and every later one by `publish-npm`:

1. Take the newest tag's tarball: where that tag's run built one, its `compiler-npm-<version>` artifact
   (`gh run download <run id> -n compiler-npm-<version>`), already checked by step 2, so a re-run of its `publish-npm`
   finds npm has this tarball and publishes nothing. A tag from before the npm publish (v0.32.0) has no such artifact,
   and packs the old name, `@drivine/cypher-compiler`, without the README or LICENSE: build its tarball from the tag's
   compiler sources with `main`'s packaging (`npmPackage`, `compiler.npmName`, `npm/` and LICENSE), and check it as
   step 2 does (`main`'s `compiler-npm-check.sh`, and the corpus check against that tag's JVM).
2. A maintainer of the `@liberation-data` scope publishes that tarball once: `npm publish <tarball> --access public`,
   with `--tag next` for a version with a suffix. This one version has no provenance, as it isn't published from a run.
3. On npmjs.com, in the package's settings, add a trusted publisher: GitHub Actions, organization
   `liberation-data`, repository `drivine-cypher-spec`, workflow `compiler-release.yml`, environment `release`. Under
   "Allowed actions", allow `npm publish` as well as `npm stage publish`, then save it. By default a new publisher may
   only stage a version for a maintainer to publish (npm's staged publishing), and `npm publish` from the workflow then
   fails with "OIDC permission denied"; npm doesn't let a saved publisher be changed, so a stage-only one is deleted
   and added again. v0.33.0's publish failed on each in turn: first with the publisher not saved, then with a stage-only
   one. A new publisher expires if it hasn't published within 2 days, and can then be neither used nor changed; one set
   up here isn't used until the next tag, as the re-run below finds the version published and publishes nothing. So if
   the next tag comes more than 2 days later, check before pushing it that the publisher hasn't expired, and if it has,
   delete it and add it again.

Until then a tag's run publishes to Maven Central and `publish-npm` fails at npm's refusal: publish that tag's tarball
as above, set the publisher, and re-run `publish-npm` alone (its own "Re-run this job"), which waits for the reviewer again
and finds the version published. GitHub re-runs a job only within 30 days of its run; after that, push the next tag.
The workflow's file name is part of the publisher, so renaming `compiler-release.yml` needs the setting changed with it.

Push the tag with or after `main`: a tag pushed before the commit is on `main` is refused at step 1; push `main` and
re-run the workflow.

To build the bundle yourself, unsigned: `./gradlew zipAggregationPublication -Pcompiler.version=<v>`, which writes
`build/nmcp/zip/publicationAggregated.zip`. `.github/scripts/compiler-release-sign.sh` signs it, with `SIGNING_KEY` and
`SIGNING_PASSWORD`, and `ARTIFACTS` set as the workflow sets it.

Between tags, drivine4j can build against a commit of the module, published into a repository of its own checkout as
`0.0.0-<commit>`, but it can't release on one: its release refuses a `0.0.0-` pin.

### The libraries' copies of the spec

Each library's sync script copies named files out of the tag's tarball, not the whole of it, so the compiler in the
tarball never reaches a library's spec copy. A new spec file is copied only once it is named in all three libraries'
sync and check scripts (`scripts/sync-cypher-spec.sh` and `scripts/check-cypher-spec.sh` in drivine4j and Drivine for
TypeScript, `scripts/sync_cypher_spec.py` in drivine-python). So each library adds a new spec file to those lists in
the change that syncs the tag bringing it: the next is `host.yaml`, the host functions' cases, still being written.

## Not yet

- WASI: left out. It comes back as drivine-python's `py3-none-any` fallback wheel, for platforms with no native wheel,
  when Python switches to this compiler.
- Publishing beyond Maven Central: the npm package, Python wheels and an iOS XCFramework, from this repository's tags.
- A host's reading of schema DDL: `compile` refuses it with drivine4j's schema-command message, and no entry gives a
  host the spec a DDL statement reads as.
- `Float` text off the JVM is the platform's own: only a JVM host binds a `Float`.
