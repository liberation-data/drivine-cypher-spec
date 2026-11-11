"""
The SQLite extension's check: the shared library loaded by a real SQLite, apsw's, as a loadable extension, and its
functions called through SQL; then drivine_compile and drivine_gc_collect called through ctypes from the same path, in
the same process, so one image serves both.

    python3 check/extension-native.py <libdrivinecypher.so|.dylib|.dll> <compiler version>

In place of the library, `--installed` checks the installed Python package drivine-cypher-compiler's library instead,
as a host finds it. It needs apsw (check/extension-requirements.txt). It prints what it checked, and exits 1 naming
each check that failed.
"""
import ctypes
import json
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


def main(library, version):
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
    if len(sys.argv) != 3:
        print(__doc__, file=sys.stderr)
        sys.exit(2)
    sys.exit(main(sys.argv[1], sys.argv[2]))
