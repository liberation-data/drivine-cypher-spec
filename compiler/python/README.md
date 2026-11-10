# drivine-cypher-compiler

Drivine's Cypher compiler for Python: Cypher as SQL for Postgres, Oracle and SQLite. It is the same compiler Drivine for
the JVM and Drivine for TypeScript run, built from [drivine-cypher-spec](https://github.com/liberation-data/drivine-cypher-spec)'s
`compiler/` by Kotlin/Native, and shipped here as a native library with a small Python binding. It is what
[drivine-python](https://github.com/liberation-data/drivine-python) compiles Cypher with; you don't need it on its own.

```python
import json
import drivine_cypher_compiler

answer = json.loads(drivine_cypher_compiler.compile(json.dumps({
    "store": "postgres",
    "graph": {"name": "g"},
    "cypher": "MATCH (p:Person) RETURN p.name AS name",
})))
```

The request and the answer are JSON, as the compiler's README describes ("The C API"). The library is loaded on the
first compile, not on import. A compile releases the GIL, and several threads may compile at once.

## Platforms

There's a wheel for:

- macOS 11 or later on Apple Silicon (`macosx_11_0_arm64`)
- Linux with glibc 2.17 or later, x86_64 and aarch64 (`manylinux2014`)
- Windows x64 (`win_amd64`)

There's none for Intel Macs or for musl Linux (Alpine): Kotlin/Native is dropping its Intel Mac target and has no musl
one. On Alpine, a Debian image such as `python:3.x-slim` works. If you need another platform, open an issue or a pull
request at drivine-cypher-spec.
