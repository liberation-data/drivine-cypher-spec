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
| `wasmJs` | `@drivine/cypher-compiler`, an npm package (`./gradlew npmPackage`, in `build/npm`) | Drivine for TypeScript |
| `macosArm64`, `linuxX64`, `linuxArm64`, `mingwX64` | `libdrivinecypher`, a shared library with a C ABI | drivine-python, over ctypes |

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

### drivine4j's own calls

drivine4j calls more of the module than the API above: the compiler classes themselves, the parts of its own SQL
helpers that the compiler calls too (`org.drivine.cypher.store`), and a few of the schema specs' and compilers'
functions. The compiler classes are public and unmarked; the rest are public only so that drivine4j, built apart from
the module, can call them, and are marked `@org.drivine.DrivineInternalApi`, an opt-in at the level of an error:
drivine4j opts in module-wide, and any other caller that doesn't is refused at compile time. They may change or go in
any release.

Among them, a compiler can be made with a `CypherEncoder`, the host's writing of the values it gives as JSON, which the
compiler asks before its own. drivine4j gives its Jackson mapper's, so that a parameter is written as drivine4j has
always written it. An encoder is the compiler's own, for as long as it compiles: no state is shared between hosts.

A host with no Kotlin types calls `CompileJson.compile(request)`, JSON text in and out. The wasm build exports it as
`compile`, and the shared library as `drivine_compile`:

```json
{"store": "oracle",
 "graph": {"name": "g", "nullsEqualStored": true, "eraStored": true, "indexedProperties": [["Person", "name"]]},
 "cypher": "MATCH (p:Person {name: $name}) RETURN p",
 "parameters": {"name": "Ada"}}
```

`store` is `postgres`, `oracle` or `sqlite`. `graph` has the graph's `name`; the rest of it is Oracle's, and may be left
out. The answer is one of:

- `{"sql": …, "parameters": {…}, "setup": […], "warnings": […], "nonFinite": false}`, and on Oracle
  `"storedFunctions": {"nullsEqual": …, "era": …}`. Run `setup` first (on Postgres, the function a statement that
  writes is), then `sql`, binding `parameters`. `nonFinite` says NaN or an infinity may come back in the rows as
  `{"$numberDouble": "NaN"}`. `storedFunctions` says which of the graph's stored functions the statement calls: a store
  without them is compiled for again with `nullsEqualStored` or `eraStored` false.
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

### npm

```js
const { compile } = require('@drivine/cypher-compiler');      // or: import { compile } from '@drivine/cypher-compiler'
const answer = JSON.parse(compile(JSON.stringify({ store: 'sqlite', graph: { name: 'g' }, cypher: 'RETURN 1' })));
```

The CommonJS entry (`npm/index.cjs`) loads the wasm synchronously. Kotlin/Wasm writes only an ES module loader, with a
top-level await, so the entry runs that loader's text as a function with its Node branch made synchronous. Each edit
is checked, so a Kotlin release that writes the loader otherwise fails at once. The corpus check on wasmJs goes through
this entry. Each entry has its TypeScript types: Kotlin's `.d.mts` for `import`, and `npm/index.d.cts` for `require`.

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
```

Run `./gradlew clean` before a build you check: the wasm optimize step can leave stale output behind.

What the build generates (`gen/Generate.java`, run on the pinned JDK): the zone ids that JDK's `ZoneId.of` takes, which
the other targets check a zone name against, as their own zone databases (the OS's, Node's ICU) are other tzdb releases;
and the tables of powers of ten and five the double text and parse use.

### The pinned JDK

`gradle/pinned-jdk.properties` pins one JDK exactly: Temurin 21.0.13+9, which brings tzdb 2026e. It writes the zone ids
and the JVM's answers (`jvmOracle`), and CI runs every job on it. Compiling and the JVM tests take any Temurin 21.
Where the pinned JDK isn't installed, the build fetches Adoptium's archive of it once, into `~/.gradle/drivine-jdks`,
and checks it against the SHA-256 pinned for the platform.

A JDK release can bring a new tzdb, and with it zones the compiler takes or refuses, so the pin changes only in a
reviewed pull request. To bump it:

1. Set `version` to the new Temurin 21 release, and each `sha256.<os>-<arch>` to Adoptium's checksum of that release's
   archive (`https://api.adoptium.net/v3/assets/release_name/eclipse/jdk-<version>?os=<os>&architecture=<arch>&image_type=jdk`).
2. Set `java-version` in `.github/workflows/compiler.yml` to the same release, as setup-java names it (`21.0.13+9.0.LTS`).
3. Run `./gradlew generateTables`. Where the JDK brings another tzdb, it stops and names it: set `tzdb` to that.
4. Open the pull request, and say in it which zones the new tzdb adds or removes (the diff of the generated
   `build/generated/tables/org/drivine/cypher/time/JvmZoneIds.kt` against the old one's).

## Checks

The JVM is the oracle. `./gradlew jvmOracle` writes, into `build/oracle`:

- `corpus.jsonl`: every statement in the spec's files (`queries.yaml`, `writes.yaml`, `temporal.yaml`, `refusals.yaml`,
  `schema.yaml`), compiled for Postgres, Oracle and SQLite, and some with parameters the spec has no form for (whole
  floats, temporal values, bytes, `$` keys) and Oracle's graph options: each request and the JVM's answer. A statement
  whose SQL embeds the moment it was compiled, as SQLite's does for `datetime()` and the like, is left out, found by its
  SQL holding that moment.
- `doubles.txt`: random doubles (subnormals included) and Java's text of each, and random decimal and hexadecimal texts,
  halfway points between doubles and texts a digit either side, and Java's reading of each.
- `mutants.jsonl`: a sample of the spec's statements mutated (`Mutants`), which no corpus statement is: truncated,
  a character or a token deleted, junk inserted, two tokens swapped, a clause written twice, a literal made an edge
  value, a function's name a letter off. Only a mutant whose answer isn't the parser's syntax refusal is kept, as the
  stdlib's exceptions and texts reach a refusal off those paths: 10,000 of them (`-Pmutants.count=<n>`), from the seed.
  Where fewer are of interest, `jvmOracle` fails.
- `standins-<name>.txt`: what Java gives where the module stands in for it, worked out by Java's own API on the pinned
  JDK (`StandIns`): `ZoneId.of`'s reading of a zone; java.time's calendar dates, and its parse and text of a temporal
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

The compiler's sources come from drivine4j (`src/main/kotlin/org/drivine/cypher` and the few helpers it calls), and its
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

## Not yet

- WASI: left out. It comes back as drivine-python's `py3-none-any` fallback wheel, for platforms with no native wheel,
  when Python switches to this compiler.
- Publishing: the Maven publications are built (`./gradlew publishJvmPublicationToMavenLocal
  publishKotlinMultiplatformPublicationToMavenLocal -Pcompiler.version=<v>`), and drivine4j builds against the JVM's
  published from a pinned commit into a repository of its own checkout. Not yet: the jar to Maven Central, the npm
  package, Python wheels and an iOS XCFramework, from this repository's tags.
- A host's reading of schema DDL: `compile` refuses it with drivine4j's schema-command message, and no entry gives a
  host the spec a DDL statement reads as.
- `Float` text off the JVM is the platform's own: only a JVM host binds a `Float`.
