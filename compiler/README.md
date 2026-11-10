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
| `macosArm64`, `linuxX64`, `linuxArm64`, `mingwX64` | `libdrivinecypher`, a shared library with a C ABI, packed for PyPI as `drivine-cypher-compiler` wheels (`./gradlew pythonWheels`, in `build/wheels`) | drivine-python, over ctypes |

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

A host with no Kotlin types calls `CompileJson.compile(request)`, JSON text in and out. The wasm build exports it as
`compile`, and the shared library as `drivine_compile`:

```json
{"store": "oracle",
 "graph": {"name": "g", "nullsEqualStored": true, "eraStored": true, "indexedProperties": [["Person", "name"]]},
 "cypher": "MATCH (p:Person {name: $name}) RETURN p",
 "parameters": {"name": "Ada"}}
```

`store` is `postgres`, `oracle` or `sqlite`. `graph` has the graph's `name`; the rest of it is Oracle's, and may be left
out: `nullsEqualStored` and `eraStored` (true where left out), `indexedProperties`, and `oracleRowsAsText` (false where
left out, see [Oracle's rows as text](#oracles-rows-as-text)). `typed`, false where left out, asks for the rows' nodes and
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
| a temporal value | `{"$temporal": "DATE", "text": "2026-11-07"}`: `DATE`, `LOCAL_TIME`, `TIME`, `LOCAL_DATE_TIME`, `DATE_TIME` or `DURATION`, and its ISO 8601 text |
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

### npm

```js
const { compile } = require('@liberation-data/drivine-cypher-compiler');      // or: import { compile } from '@liberation-data/drivine-cypher-compiler'
const answer = JSON.parse(compile(JSON.stringify({ store: 'sqlite', graph: { name: 'g' }, cypher: 'RETURN 1' })));
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
finds no wheel there, and drivine-python names the platform where it would load the compiler. The library exports the C
API alone (a version script on Linux and a symbol list on macOS, in `native/`, which CI checks the library's exports
against, and Kotlin/Native's `.def` on Windows, which adds only its `drivinecypher_symbols`), so the Kotlin/Native
runtime's symbols can't clash with another library in the host's process; on Linux it is linked `--as-needed`, which
leaves out `libcrypt.so.1` and the other system libraries it doesn't call. The binding loads the library on the first
compile, once, and frees each answer with `drivine_free`. `python/build_wheels.py` packs the wheels with the standard
library alone, the same bytes from the same libraries; `python/check_wheel.py` checks each, and
`python/check_wheel_test.py`, which CI runs, tests both over made-up libraries.

### C

```c
char *drivine_compile(const char *request);   /* NUL-terminated UTF-8 JSON in, and out */
void drivine_free(char *answer);              /* frees what drivine_compile gave */
```

A NULL request is answered as an empty one, with `{"error": …}`. `drivine_compile` gives NULL only where it can't allocate
the answer. The answer is the host's to free, with `drivine_free` and nothing else, as the library allocates it.

Each call stands alone, so a host may call it from several threads at once.

## Building

JDK 21 runs Gradle. The build compiles with Temurin 21, provisioned where it isn't installed, and writes the zone ids
and the JVM's answers on the exact JDK it pins (see [The pinned JDK](#the-pinned-jdk)).

```sh
./gradlew jvmTest                  # the JVM's tests, and the double and stand-in fuzz on the JVM
./gradlew wasmJsNodeTest           # the double and stand-in fuzz on wasmJs
./gradlew macosArm64Test           # the double and stand-in fuzz on native (linuxX64Test on Linux)
./gradlew wasmJsCorpusCheck        # the corpus on wasmJs (node on the PATH, or -Pnode=<path>)
./gradlew nativeCorpusCheck        # the corpus on the host's native target (python3, or -Ppython=<path>)
./gradlew metadataCompile          # common code compiled alone
./gradlew npmPackage               # the npm package, in build/npm
./gradlew pythonWheels             # the Python wheels, one a native target, in build/wheels (python3, or -Ppython=<path>)
```

Run `./gradlew clean` before a build you check: the wasm optimize step can leave stale output behind.

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
  many rows. Last, compiled typed: `results.yaml`'s statements, and each other statement whose SQL typed changes (one
  that returns a node or a relationship), on Oracle with `oracleRowsAsText` too where that changes it. Each request and
  the JVM's answer. A statement whose SQL embeds the moment it was compiled, as SQLite's
  does for `datetime()` and the like, is left out, found by its SQL holding that moment.
- `doubles.txt`: random doubles (subnormals included) and Java's text of each, and random decimal and hexadecimal texts,
  halfway points between doubles and texts a digit either side, and Java's reading of each.
- `mutants.jsonl`: a sample of the spec's statements mutated (`Mutants`), which no corpus statement is: truncated,
  a character or a token deleted, junk inserted, two tokens swapped, a clause written twice, a literal made an edge
  value, a function's name a letter off. Only a mutant whose answer isn't the parser's syntax refusal is kept, as the
  stdlib's exceptions and texts reach a refusal off those paths: 10,000 of them (`-Pmutants.count=<n>`), from the seed.
  Where fewer are of interest, `jvmOracle` fails.
- `store.json`: the spec's `store.yaml` as JSON, which `StoreDdlTest` reads on every target: the store layout's DDL
  each generator in `org.drivine.cypher.store.ddl` must give, the object names Java's `String.hashCode` gives, and the
  reads the compiler must answer from each index.
- `marker.json`: the spec's `marker.yaml` as JSON, which `StoreMarkerTest` reads on every target: each store's
  statements for the schema marker and the store's stamp that `org.drivine.cypher.store.marker` must give, the
  inventories it must write and read back, the version tokens, and the node stamps `Stamps` must read and write.
- `plans.json`: the spec's `plans.yaml` as JSON, which `StorePlanTest` reads on every target: each case's rounds that
  `org.drivine.cypher.store.plan`'s planner must give on each store, the answers it is given back, and its report.
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
   javadoc, POM and module at the tag's version. The bundle is kept as the run's artifact.
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
mutants through the installed package, every answer byte for byte the JVM's (`check/corpus-native.py --installed`). On
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
