"""
The SQLite extension's check: the shared library loaded by a real SQLite, apsw's, as a loadable extension, and its
functions called through SQL; then drivine_compile and drivine_gc_collect called through ctypes from the same path, in
the same process, so one image serves both.

    python3 check/extension-native.py <libdrivinecypher.so|.dylib|.dll> <compiler version> [host.json]

With host.json (the spec's host.yaml as `./gradlew hostSpecJson` writes it), each `function` step of host.yaml whose
function the extension registers is called through SQL, as SQLite calls it for a compiled statement, and must give its
`result`, fail with its `error`, or its values ascend; the functions it doesn't register yet are named. HostSpecTest runs the same steps on the
module's code directly, on every target.

In place of the library, `--installed` checks the installed Python package drivine-cypher-compiler's library instead,
as a host finds it. It needs apsw (check/extension-requirements.txt). It prints what it checked, and exits 1 naming
each check that failed.
"""
import ctypes
import json
import math
import sys

import apsw

FLOOR = 64 * 1024 * 1024
failures = []


def check(what, got, expected):
    report(what, got == expected, f"got {got!r}, expected {expected!r}")


def report(what, passed, why):
    if passed:
        print(f"ok: {what}")
    else:
        failures.append(what)
        print(f"::error::{what}: {why}")


def refused(what, connection, sql, message):
    """The statement fails, with SQLite's error holding the message."""
    try:
        connection.execute(sql).fetchall()
        got = "no error"
    except apsw.Error as e:
        got = f"{type(e).__name__}: {e}"
    report(what, message in got, f"got {got!r}, expected an error with {message!r}")


def sql_value(value):
    """A value of the spec's files as a SQL value: {$float: ...} the float it names, and the rest as JSON gives it."""
    if isinstance(value, dict) and set(value) == {"$float"}:
        return float(value["$float"].replace("Infinity", "inf"))
    if isinstance(value, (dict, list, bool)):
        raise ValueError(f"No SQL value of {value!r}")
    return value


def same(got, expected):
    """Equal, and of the same type: a whole number is not a float, and NaN is NaN."""
    if type(got) is not type(expected):
        return False
    if isinstance(got, float) and math.isnan(got):
        return math.isnan(expected)
    return got == expected


def refuse_nan(values):
    """SQLite binds a NaN as NULL, so the function would be given null, not what the step says."""
    if any(isinstance(v, float) and math.isnan(v) for v in values):
        raise ValueError("SQLite can't be given NaN as an argument")


def host_steps(connection, host):
    """Each function step of host.yaml whose function the extension registers, through SQL; the functions it doesn't."""
    registered = {row[0] for row in connection.execute("SELECT name FROM pragma_function_list")}
    ran, pending = 0, set()
    for case in host["cases"]:
        for index, step in enumerate(case["steps"], 1):
            name = step.get("function")
            if name is None:
                continue  # A read, of each library's row reader.
            if name not in registered:
                pending.add(name)
                continue
            where = f"host.yaml {case['name']}, step {index}"
            try:
                if "ascending" in step:
                    values = [sql_value(v) for v in step["ascending"]]
                    if len(values) < 2:
                        raise ValueError("ascending has two values or more, each before the next")
                    refuse_nan(values)
                    for a, b in zip(values, values[1:]):
                        before = connection.execute(f"SELECT {name}(?) < {name}(?)", (a, b)).fetchall()[0][0]
                        if before != 1:
                            report(f"{where}: {name}({a!r}) before {name}({b!r})", False, f"got {before!r}")
                elif "error" in step:
                    args = [sql_value(v) for v in step["args"]]
                    refuse_nan(args)
                    try:
                        got = connection.execute(f"SELECT {name}({', '.join('?' for _ in args)})", args).fetchall()[0][0]
                        report(f"{where}: {name}{tuple(args)!r}", False, f"gave {got!r}, expected it to fail with {step['error']!r}")
                    except apsw.Error as e:
                        if step["error"] not in str(e):
                            report(f"{where}: {name}{tuple(args)!r}", False, f"failed with {e}, expected {step['error']!r}")
                else:
                    args = [sql_value(v) for v in step["args"]]
                    refuse_nan(args)
                    got = connection.execute(f"SELECT {name}({', '.join('?' for _ in args)})", args).fetchall()[0][0]
                    expected = sql_value(step["result"])
                    if not same(got, expected):
                        report(f"{where}: {name}{tuple(args)!r}", False, f"got {got!r}, expected {expected!r}")
            except (apsw.Error, ValueError) as e:
                report(f"{where}: {name}", False, f"failed: {type(e).__name__}: {e}")
            ran += 1
    report(f"host.yaml: {ran} function steps through SQL", ran > 0, "no function step ran")
    print(f"host.yaml: not registered yet: {', '.join(sorted(pending)) or 'none'}")


def main(library, version, host=None):
    if library == "--installed":
        import drivine_cypher_compiler

        library = drivine_cypher_compiler.library_path()
    print(f"apsw {apsw.apswversion()}, SQLite {apsw.sqlite_lib_version()}, loading {library}")

    connection = apsw.Connection(":memory:")
    connection.enable_load_extension(True)
    connection.load_extension(library)
    one = lambda sql, c=connection: c.execute(sql).fetchall()[0][0]  # noqa: E731

    check("drivine_extension_version() is the module's version", one("SELECT drivine_extension_version()"), version)
    check("drivine_gc_collect() gives NULL", one("SELECT drivine_gc_collect()"), None)
    check("drivine_gc_heap() is the deferred floor", one("SELECT drivine_gc_heap()"), FLOOR)
    check("drivine_gc_heap(n) sets the floor", one(f"SELECT drivine_gc_heap({2 * FLOOR})"), 2 * FLOOR)
    check("drivine_gc_heap(n) sets it back", one(f"SELECT drivine_gc_heap({FLOOR})"), FLOOR)
    # An exception thrown in a function's body is the statement's error, and the process carries on.
    refused("A function that throws fails its statement", connection, "SELECT drivine_gc_heap(-1)",
            "drivine_gc_heap: the floor is 0 bytes or more, not -1.")
    refused("A function that throws on a text fails its statement", connection, "SELECT drivine_gc_heap('x')",
            "drivine_gc_heap: the floor is a whole number of bytes.")
    check("The connection still answers", one("SELECT drivine_extension_version()"), version)
    # A pattern the platform's regular expressions can't match as Java's do fails its statement, naming what it can't.
    refused("A pattern Kotlin/Native can't match as Java does fails its statement by name", connection, "SELECT 'a' REGEXP '\\Ga'",
            "regexp: \\G is not supported on this platform; rewrite the pattern without it.")
    check("A rewritten pattern matches as Java's does: \\w is Unicode's", one("SELECT 'café' REGEXP '^\\w+\\z'"), 1)
    # A finite float, which host.yaml's steps don't give it: Java's shortest text, not SQLite's 17 digits.
    check("drivine_float_text(1.0 / 3) writes it as Java does", one("SELECT drivine_float_text(1.0 / 3)"),
          "0.3333333333333333")
    if host is not None:
        with open(host, encoding="utf-8") as f:
            host_steps(connection, json.load(f))
    # Deterministic, so a generated column may call it; the GC's, direct-only, so a view may not.
    connection.execute("CREATE TABLE t(a, v AS (drivine_extension_version()))")
    connection.execute("INSERT INTO t(a) VALUES (1)")
    check("A deterministic function serves a generated column", one("SELECT v FROM t"), version)
    connection.execute("CREATE VIEW gc AS SELECT drivine_gc_collect() AS c")
    refused("A direct-only function is refused in a view", connection, "SELECT c FROM gc", "unsafe use of drivine_gc_collect()")

    # A second connection loads it again: the floor a host set stands.
    other = apsw.Connection(":memory:")
    other.enable_load_extension(True)
    one(f"SELECT drivine_gc_heap({3 * FLOOR})")
    other.load_extension(library)
    check("A second connection's load leaves the floor be", one("SELECT drivine_gc_heap()", other), 3 * FLOOR)
    one(f"SELECT drivine_gc_heap({FLOOR})")

    # The C API, from the same path, in the same process.
    lib = ctypes.CDLL(library)
    lib.drivine_compile.argtypes = [ctypes.c_char_p]
    lib.drivine_compile.restype = ctypes.c_void_p
    lib.drivine_free.argtypes = [ctypes.c_void_p]
    lib.drivine_free.restype = None
    lib.drivine_gc_collect.argtypes = []
    lib.drivine_gc_collect.restype = None
    request = json.dumps({"store": "sqlite", "graph": {"name": "g"}, "cypher": "RETURN 1 AS one"})
    answer = lib.drivine_compile(request.encode("utf-8"))
    try:
        compiled = json.loads(ctypes.string_at(answer).decode("utf-8"))
    finally:
        lib.drivine_free(answer)
    check("drivine_compile through ctypes compiles", "sql" in compiled, True)
    lib.drivine_gc_collect()
    check("drivine_gc_collect through ctypes returns, and SQL still answers", one("SELECT drivine_extension_version()"), version)
    connection.close()
    other.close()

    if failures:
        print(f"{len(failures)} of the extension's checks failed.")
        return 1
    print("The extension loads in SQLite, and its functions and the C API answer, in one process.")
    return 0


if __name__ == "__main__":
    if len(sys.argv) not in (3, 4):
        print(__doc__, file=sys.stderr)
        sys.exit(2)
    sys.exit(main(*sys.argv[1:]))
