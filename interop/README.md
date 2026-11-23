# The run across the libraries

`interop.py` checks that a store one Drivine library indexed is up to date for the other two, with nothing made again (#135). It covers every case of [`declarations.yaml`](../declarations.yaml), on Postgres, Oracle and SQLite.

## What it does

The run goes store by store. Each library takes its turn as the writer, and each of the other two as its checker. For each such pair:
1. The writer enforces every case, each on a graph of its own, and ensures the items the case ensures at runtime.
2. The checker enforces the same declarations over the same graph.

Each pair has graphs of its own, written for that check alone. A check after another would otherwise see what that one changed, or what it put back, and not the store as the writer left it.

A check passes when the store is as the writer left it. The driver reads each store itself, before and after each check, rather than taking a library's word for what it did:

| Store | What is compared |
|---|---|
| Postgres | the oids of the graph's tables, sequences, views, indexes, constraints and triggers, with each index's, constraint's and trigger's definition |
| Oracle | each object's id, `created` and `last_ddl_time`, and each unique or primary key constraint's `last_change` |
| SQLite | `sqlite_master`, each object's `rootpage` among it, and `PRAGMA schema_version` |
| each store | the rows of the graph's node and schema tables, the schema marker among them, but for the marker's `appliedAt`, which every enforce writes |

Each result is one of these verdicts:
- **matching:** the check left the store as the writer left it.
- **refused, agreed:** the writer refused the case, and so did the checker.
- **known gap:** a case the library can't yet declare as drivine4j, the reference, does, with the issue that brings it level. `KNOWN_GAPS` pins these, as each library's own test lists them:
  - drivine4j has none;
  - Drivine for TypeScript lists them as `GAPS` in `test/1.Unit/schema/Declarations.ts`;
  - drivine-python lists them as `GAPS` in `tests/unit/test_declarations_spec.py`.
- **changed**, **failed**, **refused by the writer only**: fails of the run.
- **empty:** neither library failed the case, but its graph holds no objects, so it was never written where the driver reads it. It is a fail of the run, never a known gap.
- **store unavailable:** a store's capacity, such as Oracle's listener refusing a session. It is a fail of the run, never a refusal, nor a known gap.
- **no report:** the writer's or the checker's report has no entry for the case. It is a fail of the run, never a refusal, nor a known gap.

The run also fails when a library's report marks other gaps than `KNOWN_GAPS` pins for the cases it ran, so a gap closed or opened in a library is seen here.

## Each library's half

Each half is a test in the library's own repo. It runs only when the driver sets `INTEROP_PHASE` (drivine4j) or `INTEROP_STORE` (the other two), and is skipped otherwise. It declares each case in that library's API, enforces it, and writes a report of its outcome, the statements it ran, and its failure.

| Library | Test | What the checkout needs |
|---|---|---|
| drivine4j | `DeclarationsInteropTest` (`src/test/kotlin/org/drivine/schema/`) | its Gradle wrapper |
| Drivine for TypeScript | `test/2.Integration/interop/Interop135.spec.ts` | `node_modules`, installed |
| drivine-python | `tests/interop` | a `.venv`, made with `pip install -e '.[compiler,sqlite,postgres,oracle]' --group dev` (Python 3.11 or later) |

The driver sets these for each half:
- `INTEROP_STORE`: `sqlite`, `postgres` or `oracle`.
- `INTEROP_GRAPH`: the prefix of each case's graph, `<prefix>_<index>`.
- `INTEROP_PHASE`: `write` or `check`.
- `INTEROP_CASES` and `INTEROP_REPORT`: the cases' file and the report's path.
- `INTEROP_HOST`, `INTEROP_PORT`, `INTEROP_USER`, `INTEROP_PASSWORD`, `INTEROP_DATABASE`: the server. On SQLite, `INTEROP_DATABASE` is the directory of the files, one `<graph>.db` for each case.

## Running it

It needs Docker. It starts its own Postgres (`pgvector/pgvector:pg17`) and Oracle (`gvenzl/oracle-free:23-faststart`) containers, and removes them at the end; `POSTGRES_TEST_IMAGE` and `ORACLE_TEST_IMAGE` change the images. The driver itself is Python 3 with nothing to install.

    python3 drivine-cypher-spec/interop/interop.py \
        --d4j drivine4j --ts drivine --py drivine-python \
        --stores postgres,oracle,sqlite

- **Arguments:** `--libs` picks the libraries, all three by default. `--cases` reads another `declarations.yaml` than this repo's. `--self-check` has each writer check its own store too, as a trial of the driver. `--libs` names each library once, and two or more without `--self-check`.
- **What it writes:** each half's report, and `results.json`, which holds each verdict and, for a case that changed, the snapshots before and after. They go to `--out`, which is `out` under the current directory. A half that exits nonzero also keeps its output there, as `<store>-<prefix>-<library>-<phase>.log`.
- **Its result:** it logs the tally and how many objects each store's graphs hold, then `PASS`, and exits 0. Otherwise it gives each fail and exits 1.

A run of all three stores and all three libraries takes about 20 minutes, most of it Oracle.
