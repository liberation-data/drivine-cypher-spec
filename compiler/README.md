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
| a float | a JSON number with a fraction or an exponent (`2.5`, `1e-7`), or `{"$float": "2.0"}` (or `2`): JavaScript can't write a whole float |
| NaN, the infinities | `{"$float": "NaN"}`, `{"$float": "Infinity"}`, `{"$float": "-Infinity"}` |
| a temporal value | `{"$temporal": "DATE", "text": "2026-11-07"}`: `DATE`, `LOCAL_TIME`, `TIME`, `LOCAL_DATE_TIME`, `DATE_TIME` or `DURATION`, and its ISO 8601 text |
| bytes | `{"$bytes": "<base64>"}` |
| a map with a key that starts with `$` | `{"$map": {"$key": …}}` |

The values bound come back the same way, a float written as Java writes it (`2.0`, `1.0E23`), so a host that reads a
number's text can tell it from a whole number.

### npm

```js
const { compile } = require('@drivine/cypher-compiler');      // or: import { compile } from '@drivine/cypher-compiler'
const answer = JSON.parse(compile(JSON.stringify({ store: 'sqlite', graph: { name: 'g' }, cypher: 'RETURN 1' })));
```

The CommonJS entry (`npm/index.cjs`) loads the wasm synchronously. Kotlin/Wasm writes only an ES module loader, with a
top-level await, so the entry runs that loader's text as a function with its Node branch made synchronous. Each edit
is checked, so a Kotlin release that writes the loader otherwise fails at once. The corpus check on wasmJs goes through
this entry.

### C

```c
char *drivine_compile(const char *request);   /* NUL-terminated UTF-8 JSON in, and out */
void drivine_free(char *answer);              /* frees what drivine_compile gave */
```

Each call stands alone, so a host may call it from several threads at once.

## Building

JDK 21 runs Gradle; the build pins Temurin 21 for its own work, and provisions it where it isn't installed.

```sh
./gradlew jvmTest                  # the JVM's tests, and the double fuzz on the JVM
./gradlew wasmJsNodeTest           # the double fuzz on wasmJs
./gradlew macosArm64Test           # the double fuzz on native (linuxX64Test on Linux)
./gradlew wasmJsCorpusCheck        # the corpus on wasmJs (node on the PATH, or -Pnode=<path>)
./gradlew nativeCorpusCheck        # the corpus on the host's native target (python3, or -Ppython=<path>)
./gradlew metadataCompile          # common code compiled alone
./gradlew npmPackage               # the npm package, in build/npm
```

Run `./gradlew clean` before a build you check: the wasm optimize step can leave stale output behind.

What the build generates (`gen/Generate.java`, run on the pinned JVM): the zone ids that JVM's `ZoneId.of` takes, which
the other targets check a zone name against, as their own zone databases (the OS's, Node's ICU) are other tzdb releases;
and the tables of powers of ten and five the double text and parse use.

## Checks

The JVM is the oracle. `./gradlew jvmOracle` writes, into `build/oracle`:

- `corpus.jsonl`: every statement in the spec's files (`queries.yaml`, `writes.yaml`, `temporal.yaml`, `refusals.yaml`,
  `schema.yaml`), compiled for Postgres, Oracle and SQLite, and some with parameters the spec has no form for (whole
  floats, temporal values, bytes, `$` keys) and Oracle's graph options: each request and the JVM's answer. A statement
  whose SQL embeds the moment it was compiled, as SQLite's does for `datetime()` and the like, is left out, found by its
  SQL holding that moment.
- `doubles.txt`: random doubles (subnormals included) and Java's text of each, and random decimal and hexadecimal texts,
  halfway points between doubles and texts a digit either side, and Java's reading of each.

The corpus checks compile each request on wasmJs (the npm package, through its CommonJS entry) and on native (the
release shared library, through its C ABI from Python) and fail on any byte that differs from the JVM's answer. The
double fuzz checks each target's text and reading of each double against Java's.

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
module's edits for common Kotlin are kept, copies in a file drivine4j added, writes the new commit to `DRIVINE4J`, and
lists conflicts to resolve and what changed in drivine4j outside the mapped files. Then build, run every check, and
commit.
