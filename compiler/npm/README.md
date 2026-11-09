# Drivine's Cypher compiler

Cypher compiled to SQL for Postgres, Oracle and SQLite, built for Wasm from the Kotlin Multiplatform compiler in
[drivine-cypher-spec](https://github.com/liberation-data/drivine-cypher-spec/tree/main/compiler). It is what
[Drivine for TypeScript](https://github.com/liberation-data/drivine) compiles Cypher with on its SQL stores, and the same
compiler [drivine4j](https://github.com/liberation-data/drivine4j) runs on the JVM: given the same request, both give the
same SQL, byte for byte.

Each version is the Cypher spec's tag of that number, so the compiler and the spec it was checked against share one
number. Node 22 or later.

```js
const { compile } = require('@liberation-data/drivine-cypher-compiler'); // or: import { compile } from '…'
const answer = JSON.parse(compile(JSON.stringify({ store: 'sqlite', graph: { name: 'g' }, cypher: 'RETURN 1' })));
```

A request and its answer are JSON text. The request names the store, the graph and the Cypher, with its parameters; the
answer is the SQL and how to read its rows, or why the statement is refused. The module's README describes both:
[compiler/README.md](https://github.com/liberation-data/drivine-cypher-spec/blob/main/compiler/README.md).

The Wasm module is read and started on the first `compile`, not on `require`.

Apache License 2.0.
