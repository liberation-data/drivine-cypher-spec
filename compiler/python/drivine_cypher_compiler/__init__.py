"""
Drivine's Cypher compiler for Python: Cypher as SQL for Postgres, Oracle and SQLite. It is the compiler the other Drivine
libraries run (built from drivine-cypher-spec's compiler/, by Kotlin/Native), loaded as the shared library beside this
file, through its C API:

    char *drivine_compile(const char *request);  // NUL-terminated UTF-8 JSON in, and out
    void drivine_free(char *answer);             // frees what drivine_compile gave

`compile` takes the request and gives the answer, as JSON text; their shape is the compiler's, described in its README
("The C API"). The library is loaded on the first compile, not on import, and once. A compile releases the GIL while it
runs, and the library takes calls from several threads at once.
"""

from __future__ import annotations

import ctypes
import os
import sys
import threading

from drivine_cypher_compiler._version import __version__

__all__ = ["__version__", "compile", "library_path"]

_LIBRARY = {"darwin": "libdrivinecypher.dylib", "win32": "drivinecypher.dll"}.get(sys.platform, "libdrivinecypher.so")

_lock = threading.Lock()
_library: ctypes.CDLL | None = None


def library_path() -> str:
    """The shared library this package loads: the one file of the wheel that isn't Python."""
    return os.path.join(os.path.dirname(os.path.abspath(__file__)), _LIBRARY)


def _loaded() -> ctypes.CDLL:
    global _library
    if _library is None:
        with _lock:
            if _library is None:
                library = ctypes.CDLL(library_path())
                library.drivine_compile.argtypes = [ctypes.c_char_p]
                # A pointer, not c_char_p: c_char_p would copy the answer into bytes and drop the pointer, which then
                # couldn't be freed.
                library.drivine_compile.restype = ctypes.c_void_p
                library.drivine_free.argtypes = [ctypes.c_void_p]
                library.drivine_free.restype = None
                _library = library
    return _library


def compile(request: str) -> str:  # noqa: A001 (the compiler's own word, as in its other bindings)
    """The compiler's answer to the request, both JSON text."""
    library = _loaded()
    answer = library.drivine_compile(request.encode("utf-8"))
    if not answer:
        raise MemoryError("drivine_compile gave no answer: the library couldn't allocate one.")
    try:
        return ctypes.string_at(answer).decode("utf-8")
    finally:
        library.drivine_free(answer)
