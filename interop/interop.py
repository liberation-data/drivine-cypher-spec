#!/usr/bin/env python3
"""
The run that checks a store one library indexed is up to date for the other two (#135), over each case of this repo's
declarations.yaml.

For each store, and each library in turn as the writer with each other library as its checker, the writer enforces
every case of declarations.yaml on a graph of its own, written for that checker alone; then the checker enforces the
same case on the same graph. A check passes when the store is as the
writer left it: the same indexes, constraints, tables and triggers, each the same object (not one made again under its
name), and the same rows in the graph's node and schema tables, the schema marker among them. The store is read here,
not through any library, so no library's word is taken for what it did.

A case the writer could not apply is "refused": it passes when every library refuses it too. A case a library declares
otherwise than the reference, known and filed, is a "known gap" and scored apart.

Each library's half is a test in its own repo, run only when this driver sets INTEROP_PHASE (drivine4j) or
INTEROP_STORE (the other two): drivine4j's DeclarationsInteropTest, Drivine for TypeScript's
test/2.Integration/interop/Interop135.spec.ts, and drivine-python's tests/interop. It starts its own Postgres and Oracle containers, and removes them at the end. See README.md.

    interop.py --d4j DIR --ts DIR --py DIR [--stores sqlite,postgres,oracle] [--libs d4j,ts,py] [--cases FILE] [--out DIR] [--self-check]
"""
import argparse
import json
import os
import shutil
import sqlite3
import subprocess
import sys
import time
import uuid

HERE = os.path.dirname(os.path.abspath(__file__))
CASES = os.path.join(HERE, os.pardir, "declarations.yaml")

# The cases each library declares otherwise than the reference, by issue: scored apart, never as a fail.
KNOWN_GAPS = {
    # Drivine for TypeScript (liberation-data/drivine), as GAPS in its test/1.Unit/schema/Declarations.ts.
    "ts": {
        "full-text": ["#648"],
        "text": ["#541"],
        "fragment": ["#800"],
        "fragment class items": ["#800", "#648"],
        "fragments merged": ["#800"],
        "fragment ignoring case": ["#800"],
    },
    # drivine-python (liberation-data/drivine-python), as GAPS in its tests/unit/test_declarations_spec.py.
    "py": {
        "text": ["#356"],
        "fragment class items": ["#442"],
    },
    "d4j": {},
}
# Failures that are the store's capacity, not what a case declares: never a refusal, and a fail of the run.
INFRA = ("ORA-12516", "ORA-12519", "ORA-12520", "Connection refused")
REPOS = {"ts": "drivine", "py": "drivine-python", "d4j": "drivine4j"}


def gap_of(lib, case):
    """The issues a case is a known gap under in `lib`, as `repo#n`, or None."""
    issues = KNOWN_GAPS.get(lib, {}).get(case)
    return ", ".join(f"{REPOS[lib]}{n}" for n in issues) if issues else None


PG_IMAGE = os.environ.get("POSTGRES_TEST_IMAGE", "pgvector/pgvector:pg17")
ORACLE_IMAGE = os.environ.get("ORACLE_TEST_IMAGE", "gvenzl/oracle-free:23-faststart")
USER = PASSWORD = "drivine"


def log(*args):
    """Prints a line, stamped with the time, at once: a run is long, and is followed as it goes."""
    print(time.strftime("%H:%M:%S"), *args, flush=True)


def run(cmd, **kw):
    """Runs a command and gives its output; one that exits nonzero raises, with the end of its output."""
    done = subprocess.run(cmd, capture_output=True, text=True, **kw)
    if done.returncode != 0:
        raise RuntimeError(f"{cmd[:4]} exited {done.returncode}: {done.stderr.strip()[-2000:]} {done.stdout.strip()[-2000:]}")
    return done.stdout


# ---------------------------------------------------------------------------------------------------------------- stores

class Containers:
    """The job's own Postgres and Oracle, each removed by its exact name at the end."""

    def __init__(self):
        self.names = []

    def start(self, image, name, port, env, ready, times=1):
        """Starts a container, waits until its log holds `ready` `times` times, and gives the host port it serves on."""
        self.names.append(name)
        args = ["docker", "run", "-d", "--name", name, "-p", f"127.0.0.1::{port}"]
        for k, v in env.items():
            args += ["-e", f"{k}={v}"]
        run(args + [image])
        deadline = time.time() + 600
        while subprocess.run(["docker", "logs", name], stdout=subprocess.PIPE, stderr=subprocess.STDOUT,
                             text=True).stdout.count(ready) < times:
            if time.time() > deadline:
                raise RuntimeError(f"{name} not ready in 10 minutes")
            time.sleep(2)
        host_port = run(["docker", "port", name, f"{port}/tcp"]).strip().splitlines()[0].rsplit(":", 1)[1]
        return int(host_port)

    def remove(self):
        for name in self.names:
            subprocess.run(["docker", "rm", "-f", "-v", name], capture_output=True)


class Sqlite:
    name = "sqlite"

    def __init__(self, directory):
        # A run starts from no files: one left by an earlier run into the same --out would be enforced over again.
        self.directory = directory
        shutil.rmtree(directory, ignore_errors=True)
        os.makedirs(directory)

    def env(self):
        return {"INTEROP_DATABASE": self.directory}

    def snapshot(self, graph):
        path = os.path.join(self.directory, f"{graph}.db")
        if not os.path.exists(path):
            return {"missing": True}
        db = sqlite3.connect(path)
        try:
            objects = db.execute(
                "SELECT type, name, tbl_name, rootpage, sql FROM sqlite_master ORDER BY type, name"
            ).fetchall()
            tables = {r[1] for r in objects if r[0] == "table"}
            rows = {}
            for table in sorted(t for t in tables if t in (f"{graph}_node", f"{graph}_schema")):
                rows[table] = [list(r) for r in db.execute(f'SELECT * FROM "{table}" ORDER BY 1').fetchall()]
            # SQLite gives an object no identity of its own, and an index made again under its name may take its page
            # again: the schema version, which any change to the schema moves and an `IF NOT EXISTS` that makes
            # nothing does not, tells it was.
            version = db.execute("PRAGMA schema_version").fetchone()[0]
            return {"objects": [list(r) for r in objects], "rows": rows, "schema_version": version}
        finally:
            db.close()


class Postgres:
    name = "postgres"

    def __init__(self, containers):
        self.container = f"drivine-interop-pg-{uuid.uuid4().hex[:8]}"
        self.port = containers.start(
            PG_IMAGE, self.container, 5432,
            {"POSTGRES_USER": USER, "POSTGRES_PASSWORD": PASSWORD, "POSTGRES_DB": "drivine"},
            # The image's init runs a server of its own first, which says so too, then stops it and starts the real one.
            "database system is ready to accept connections", times=2,
        )

    def env(self):
        return {"INTEROP_HOST": "127.0.0.1", "INTEROP_PORT": str(self.port), "INTEROP_USER": USER,
                "INTEROP_PASSWORD": PASSWORD, "INTEROP_DATABASE": "drivine"}

    def sql(self, statement):
        return run(["docker", "exec", self.container, "psql", "-U", USER, "-d", "drivine", "-At", "-c", statement])

    def snapshot(self, graph):
        prefix = graph.replace("_", "\\_") + "\\_%"
        objects = self.sql(
            "SELECT json_agg(o ORDER BY o.kind, o.name) FROM ("
            # The graph's tables, sequences and views, its indexes over them, their constraints and triggers.
            f" SELECT 'rel:' || c.relkind::text AS kind, c.relname AS name, c.oid::text AS id, NULL::text AS def FROM pg_class c"
            f"  WHERE c.relname LIKE '{prefix}' AND c.relkind IN ('r','S','v','m')"
            " UNION ALL SELECT 'index', i.relname, i.oid::text, pg_get_indexdef(i.oid) FROM pg_index x"
            "  JOIN pg_class i ON i.oid = x.indexrelid JOIN pg_class t ON t.oid = x.indrelid"
            f"  WHERE t.relname LIKE '{prefix}'"
            " UNION ALL SELECT 'constraint', k.conname, k.oid::text, pg_get_constraintdef(k.oid) FROM pg_constraint k"
            f"  JOIN pg_class t ON t.oid = k.conrelid WHERE t.relname LIKE '{prefix}'"
            " UNION ALL SELECT 'trigger', g.tgname, g.oid::text, pg_get_triggerdef(g.oid) FROM pg_trigger g"
            f"  JOIN pg_class t ON t.oid = g.tgrelid WHERE t.relname LIKE '{prefix}' AND NOT g.tgisinternal"
            ") o"
        ).strip()
        rows = {}
        for table in (f"{graph}_node", f"{graph}_schema"):
            if self.sql(f"SELECT to_regclass('{table}') IS NOT NULL").strip() == "t":
                rows[table] = self.sql(f"SELECT json_agg(r ORDER BY 1) FROM (SELECT * FROM {table}) r").strip()
        return {"objects": objects, "rows": rows}


class Oracle:
    name = "oracle"

    def __init__(self, containers):
        self.container = f"drivine-interop-ora-{uuid.uuid4().hex[:8]}"
        self.port = containers.start(
            ORACLE_IMAGE, self.container, 1521,
            {"ORACLE_PASSWORD": PASSWORD, "APP_USER": USER, "APP_USER_PASSWORD": PASSWORD},
            "DATABASE IS READY TO USE!",
        )

    def env(self):
        return {"INTEROP_HOST": "127.0.0.1", "INTEROP_PORT": str(self.port), "INTEROP_USER": USER,
                "INTEROP_PASSWORD": PASSWORD, "INTEROP_DATABASE": "FREEPDB1"}

    def sql(self, statement):
        # sqlplus exits 0 after an error of its statement, and prints it: without WHENEVER, a snapshot would be the
        # error's text, the same before and after, and so a match.
        script = ("WHENEVER SQLERROR EXIT FAILURE\nSET HEADING OFF FEEDBACK OFF PAGESIZE 0 LINESIZE 32767 LONG 10000000 LONGCHUNKSIZE 10000000 "
                  "TRIMSPOOL ON TRIMOUT ON\n" + statement + ";\nEXIT\n")
        return run(["docker", "exec", "-i", self.container, "sqlplus", "-S", f"{USER}/{PASSWORD}@localhost/FREEPDB1"],
                   input=script)

    def snapshot(self, graph):
        prefix = graph.upper().replace("_", "\\_") + "\\_%"
        # Every object of the graph by name, and every index and constraint on its tables, whatever it is named.
        objects = self.sql(
            "SELECT JSON_ARRAYAGG(JSON_ARRAY(kind, name, id, created, ddl) ORDER BY kind, name RETURNING CLOB) FROM ("
            " SELECT object_type kind, object_name name, object_id id, TO_CHAR(created, 'YYYY-MM-DD HH24:MI:SS') created,"
            "  TO_CHAR(last_ddl_time, 'YYYY-MM-DD HH24:MI:SS') ddl FROM user_objects o"
            f"  WHERE object_name LIKE '{prefix}' ESCAPE '\\'"
            "  OR object_name IN (SELECT index_name FROM user_indexes"
            f"   WHERE table_name LIKE '{prefix}' ESCAPE '\\')"
            " UNION ALL SELECT 'CONSTRAINT', constraint_name, NULL, NULL, TO_CHAR(last_change, 'YYYY-MM-DD HH24:MI:SS')"
            f"  FROM user_constraints WHERE table_name LIKE '{prefix}' ESCAPE '\\' AND constraint_type IN ('U', 'P'))"
        ).strip()
        rows = {}
        for table in (f"{graph}_node", f"{graph}_schema"):
            exists = self.sql(f"SELECT COUNT(*) FROM user_tables WHERE table_name = '{table.upper()}'").strip()
            if exists == "1":
                rows[table] = self.sql(
                    f"SELECT JSON_ARRAYAGG(JSON_OBJECT(* RETURNING CLOB) ORDER BY 1 RETURNING CLOB) FROM {table}"
                ).strip()
        return {"objects": objects, "rows": rows}


# ------------------------------------------------------------------------------------------------------------- libraries

def half(lib, checkout, cases, store, graph, phase, report):
    """Runs one library's half, from its checkout: it enforces every case on `<graph>_<index>`, and writes its report."""
    env = dict(os.environ, INTEROP_STORE=store.name, INTEROP_GRAPH=graph, INTEROP_PHASE=phase,
               INTEROP_CASES=cases, INTEROP_REPORT=report, **store.env())
    if lib == "d4j":
        cmd, cwd = ["./gradlew", "-q", "-Pkotlin.daemon.jvmargs=-Xmx3g", "cleanTest", ":test",
                    "--tests", "org.drivine.schema.DeclarationsInteropTest"], checkout
    elif lib == "ts":
        env["DRIVINE_TEST_CONTAINERS_STARTED"] = "true"
        cmd, cwd = ["npx", "jest", "--runInBand", "--testTimeout=120000", "test/2.Integration/interop/Interop135.spec.ts"], checkout
    elif lib == "py":
        # The venv's own Python, not `uv run`, which would sync, and so download, what the lock file names.
        env["ORACLE_TESTS"] = "true"
        cmd, cwd = [".venv/bin/python", "-m", "pytest", "-q", "-o", "drivine_test_containers=false",
                    "tests/interop"], checkout
    else:
        raise ValueError(lib)
    output = report[: -len(".json")] + ".log"
    for path in (report, output):
        if os.path.exists(path):
            os.remove(path)  # An earlier run's, into the same --out, would be read as this one's.
    started = time.time()
    done = subprocess.run(cmd, cwd=cwd, env=env, capture_output=True, text=True, stdin=subprocess.DEVNULL)
    log(f"  {lib} {phase} on {store.name}/{graph}: exit {done.returncode} in {time.time() - started:.0f}s")
    if done.returncode != 0:
        # A half that exits nonzero beside a whole report (a teardown's failure, say) keeps its output to be read.
        with open(output, "w") as f:
            f.write(done.stdout + "\n----- stderr -----\n" + done.stderr)
    if not os.path.exists(report):
        sys.stderr.write(done.stdout[-4000:] + done.stderr[-4000:])
        return None
    with open(report) as f:
        return json.load(f)


def comparable(value):
    """
    A snapshot as it is compared: the same but for the schema marker's `appliedAt`, the time of the last enforce, which
    every enforce writes. Text that is a JSON object or array is read, so a row is compared by what it holds.
    """
    if isinstance(value, str) and value[:1] in "{[":
        try:
            value = json.loads(value)
        except ValueError:
            return value
    if isinstance(value, dict):
        return {k: comparable(v) for k, v in value.items() if not (k == "appliedAt" and "scope" in value)}
    if isinstance(value, list):
        return [comparable(v) for v in value]
    return value


def same(after, before):
    """
    Whether two snapshots are alike, as `comparable` gives them, with each table's rows in any order: the stores' row
    queries order by a constant (an aggregate's `ORDER BY 1` is not a column's place), and a row an enforce updates,
    the marker, may move on Postgres.
    """
    def ordered(snapshot):
        snapshot = comparable(snapshot)
        if isinstance(snapshot, dict) and isinstance(snapshot.get("rows"), dict):
            snapshot["rows"] = {t: sorted(r, key=lambda row: json.dumps(row, sort_keys=True)) if isinstance(r, list) else r
                                for t, r in snapshot["rows"].items()}
        return snapshot
    return ordered(after) == ordered(before)


def seen(snapshot):
    """How much a snapshot holds: its objects, and the rows of each table, so an empty one is not taken for a match."""
    objects = comparable(snapshot.get("objects")) or []
    rows = {t: len(comparable(v) or []) for t, v in snapshot.get("rows", {}).items()}
    return {"objects": len(objects), "rows": rows}


def entries(report):
    """A report's cases: under `cases` (drivine4j) or `reports` (Drivine for TypeScript, drivine-python)."""
    return (report or {}).get("cases") or (report or {}).get("reports") or []


def drift(lib, report):
    """
    Where a library's report and its pinned known gaps disagree: each case marked on one side only, or otherwise. A
    pin is held against the cases the report has, so a --cases file without a pinned case is no drift.
    """
    marked = {c["case"]: set(c.get("gap") or []) for c in entries(report) if c.get("gap")}
    reported = {c.get("case") for c in entries(report)}
    pinned = {case: set(issues) for case, issues in KNOWN_GAPS.get(lib, {}).items() if case in reported}
    return sorted(f"{case}: report {sorted(marked.get(case, []))}, pinned {sorted(pinned.get(case, []))}"
                  for case in set(marked) | set(pinned) if marked.get(case) != pinned.get(case))


def failed(report, case):
    """The failure a library's report gives for a case, or None; "no report" when the report has no entry for it."""
    entry = next((c for c in entries(report) if c.get("case") == case), None)
    if entry is None:
        return "no report"
    return entry.get("failure") or entry.get("refused")


def main():
    parser = argparse.ArgumentParser(
        description="Checks a store one Drivine library indexed is up to date for the other two, over each case of declarations.yaml.")
    parser.add_argument("--d4j", help="drivine4j's checkout")
    parser.add_argument("--ts", help="Drivine for TypeScript's checkout, its node_modules installed")
    parser.add_argument("--py", help="drivine-python's checkout, with a .venv holding its install")
    parser.add_argument("--stores", default="sqlite,postgres,oracle")
    parser.add_argument("--libs", default="d4j,ts,py")
    parser.add_argument("--cases", default=CASES, help="declarations.yaml (this repo's)")
    parser.add_argument("--out", default="out", help="where the reports and results.json go (out)")
    parser.add_argument("--self-check", action="store_true", help="each writer also checks its own store: a trial of the driver")
    args = parser.parse_args()
    libs = args.libs.split(",")
    unknown = [lib for lib in libs if lib not in REPOS]
    if unknown:
        parser.error(f"--libs: {', '.join(unknown)} is not one of d4j, ts, py")
    if len(set(libs)) < len(libs):
        parser.error(f"--libs: {args.libs} names a library twice")
    if len(libs) < 2 and not args.self_check:
        # A lone writer has no other library to check its store: the run would check nothing, and pass.
        parser.error(f"--libs: {args.libs} checks no other library's store: give two or more, or --self-check")
    missing = [lib for lib in libs if not getattr(args, lib)]
    if missing:
        parser.error(f"--libs runs {', '.join(missing)}: give {' '.join('--' + lib for lib in missing)}, the checkout of each")
    checkouts = {lib: os.path.abspath(getattr(args, lib)) for lib in libs}
    absent = [f"--{lib} {path}" for lib, path in checkouts.items() if not os.path.isdir(path)]
    if absent:
        parser.error(f"no such directory: {', '.join(absent)}")
    stores = args.stores.split(",")
    unknown = [name for name in stores if name not in ("sqlite", "postgres", "oracle")]
    if unknown:
        parser.error(f"--stores: {', '.join(unknown)} is not one of sqlite, postgres, oracle")
    if len(set(stores)) < len(stores):
        parser.error(f"--stores: {args.stores} names a store twice")
    cases_file = os.path.abspath(args.cases)
    if not os.path.isfile(cases_file):
        parser.error(f"--cases: no such file: {cases_file}")
    args.out = os.path.abspath(args.out)
    os.makedirs(args.out, exist_ok=True)
    # The case names, in order: a Python with no YAML reader can run this, and each case opens `  - case: "<name>"`.
    with open(cases_file) as f:
        cases = [json.loads(line.split("case:", 1)[1]) for line in f if line.startswith("  - case:")]
    if not cases:
        parser.error(f"--cases: {cases_file} has no line opening `  - case: \"<name>\"`")
    results = []
    drifts = []
    containers = Containers()
    try:
        for store_name in stores:
            store = {"sqlite": lambda: Sqlite(os.path.join(args.out, "sqlite")),
                     "postgres": lambda: Postgres(containers), "oracle": lambda: Oracle(containers)}[store_name]()
            log(f"{store_name}: ready")
            for rotation, writer in enumerate(libs, start=1):
                checkers = [lib for lib in libs if lib != writer or args.self_check]
                # Each checker checks a store of the writer's own, written for it alone: a check judged after another
                # would see that one's changes (a known gap's, say), or the store it put back, and not the writer's.
                for turn, checker in enumerate(checkers):
                    prefix = f"i{rotation}{chr(ord('a') + turn)}"
                    tag = f"{store_name}-{prefix}"
                    reports = {writer: half(writer, checkouts[writer], cases_file, store, prefix, "write", os.path.join(args.out, f"{tag}-{writer}-write.json"))}
                    if reports[writer] is None:
                        raise RuntimeError(f"{writer} gave no report writing {tag}")
                    drifts += [f"{writer} writing {tag}: {d}" for d in drift(writer, reports[writer])]
                    left = {i: store.snapshot(f"{prefix}_{i}") for i in range(len(cases))}
                    # Under --self-check the writer checks its own store: its check's report is kept apart from its write's.
                    checked = half(checker, checkouts[checker], cases_file, store, prefix, "check", os.path.join(args.out, f"{tag}-{checker}-check.json"))
                    if checked is None:
                        raise RuntimeError(f"{checker} gave no report checking {tag}")
                    drifts += [f"{checker} checking {tag}: {d}" for d in drift(checker, checked)]
                    for i, case in enumerate(cases):
                        after = store.snapshot(f"{prefix}_{i}")
                        refused_by_writer = failed(reports[writer], case)
                        refused_here = failed(checked, case)
                        gap = ", ".join(g for g in (gap_of(writer, case), gap_of(checker, case)) if g) or None
                        if any(code in (refused_by_writer or "") + (refused_here or "") for code in INFRA):
                            verdict = "store unavailable"
                        elif "no report" in (refused_by_writer, refused_here):
                            verdict = "no report"  # a library that gave no entry didn't refuse it: never agreed
                        elif refused_by_writer:
                            verdict = "refused, agreed" if refused_here else "refused by the writer only"
                        elif refused_here:
                            verdict = "failed"
                        elif not seen(after)["objects"]:
                            # An enforce leaves at least the graph's tables: a graph with none was never written (a
                            # half that set out another graph, say), and alike before and after would pass unseen.
                            verdict = "empty"
                        elif same(after, left[i]):
                            verdict = "matching"
                        else:
                            verdict = "changed"
                        if gap and verdict not in ("matching", "refused, agreed", "store unavailable", "no report", "empty"):
                            verdict = f"known gap ({gap}): {verdict}"
                        results.append({"store": store_name, "writer": writer, "checker": checker, "case": case,
                                        "graph": f"{prefix}_{i}", "verdict": verdict, "seen": seen(after),
                                        "writer failure": refused_by_writer, "checker failure": refused_here,
                                        **({"before": left[i], "after": after} if verdict.endswith("changed") else {})})
    finally:
        containers.remove()
    with open(os.path.join(args.out, "results.json"), "w") as f:
        json.dump({"results": results, "drifts": drifts}, f, indent=2)
    bad = [r for r in results if r["verdict"] in ("changed", "failed", "refused by the writer only", "store unavailable",
                                                 "no report", "empty")]
    tally = {}
    for r in results:
        key = r["verdict"].split(":")[0]
        tally[key] = tally.get(key, 0) + 1
    log("tally:", json.dumps(tally))
    for store_name in stores:
        held = [r["seen"] for r in results if r["store"] == store_name]
        if held:
            log(f"{store_name}: objects per graph {min(h['objects'] for h in held)}-{max(h['objects'] for h in held)},"
                f" graphs with no objects {sum(1 for h in held if h['objects'] == 0)}")
    for r in bad:
        log(f"FAIL {r['store']} {r['writer']}->{r['checker']} {r['case']!r}: {r['verdict']}"
            f" {(r['checker failure'] or r['writer failure'] or '')[:200]}")
    for d in drifts:
        log(f"FAIL known gaps drifted: {d}")
    log("PASS" if not bad and not drifts else f"{len(bad) + len(drifts)} FAILED")
    return 0 if not bad and not drifts else 1


if __name__ == "__main__":
    sys.exit(main())
