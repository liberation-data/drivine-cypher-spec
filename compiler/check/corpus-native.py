"""
The corpus check on native: each request of the JVM's corpus (build/oracle/corpus.jsonl) compiled by the shared library
through its C ABI (drivine_compile, drivine_free), as a Python host loads it, and its answer compared with the JVM's, byte
for byte. Then the same of the mutants (build/oracle/mutants.jsonl), where given.

    python3 check/corpus-native.py <libdrivinecypher.so|.dylib|.dll> <corpus.jsonl> [<differences.jsonl> [<mutants.jsonl> <their differences.jsonl>]]
"""
import ctypes
import json
import platform
import sys
import time


def main(library, corpus, differences=None, mutants=None, mutant_differences=None):
    started = time.perf_counter()
    lib = ctypes.CDLL(library)
    lib.drivine_compile.argtypes = [ctypes.c_char_p]
    lib.drivine_compile.restype = ctypes.c_void_p
    lib.drivine_free.argtypes = [ctypes.c_void_p]
    lib.drivine_free.restype = None

    def compile_(request):
        answer = lib.drivine_compile(request.encode("utf-8"))
        try:
            return ctypes.string_at(answer).decode("utf-8")
        finally:
            lib.drivine_free(answer)

    compile_(json.dumps({"store": "postgres", "graph": {"name": "g"}, "cypher": "RETURN 1"}))
    load_ms = (time.perf_counter() - started) * 1000

    def run(file):
        results = []
        with open(file, encoding="utf-8") as f:
            for line in f:
                if not line.strip():
                    continue
                case = json.loads(line)
                start = time.perf_counter()
                got = compile_(case["request"])
                results.append({**case, "got": got, "ms": (time.perf_counter() - start) * 1000})
        return results

    target = f"native {platform.system().lower()} {platform.machine().lower()}"
    exit_code = report(target, run(corpus), load_ms, differences)
    if mutants:
        exit_code |= report(target, run(mutants), load_ms, mutant_differences, "Mutants")
    return exit_code


def report(target, results, load_ms, differences, what="Corpus"):
    """Prints how many of results match the JVM's answer, and their times; writes the differences. The exit code, 1 where any differs or there are none."""
    if not results:
        print(f"{what} on {target}: no cases, so nothing is checked.")
        return 1
    differ = [r for r in results if r["got"] != r["answer"]]
    if differences:
        with open(differences, "w", encoding="utf-8") as f:
            f.write("\n".join(json.dumps(d) for d in differ))
    ms = sorted(r["ms"] for r in results)
    at = lambda p: ms[min(len(ms) - 1, int(p * len(ms)))]
    slowest = max(results, key=lambda r: r["ms"])
    print(
        f"{what} on {target}: {len(results) - len(differ)} of {len(results)} answers byte-identical to the JVM's, "
        f"{len(differ)} differ. Load and first compile {load_ms:.1f} ms; compile mean {sum(ms) / len(ms):.3f} ms, "
        f"median {at(0.5):.3f} ms, p99 {at(0.99):.3f} ms, max {ms[-1]:.1f} ms "
        f"({json.loads(slowest['request'])['cypher'][:80]})."
    )
    for d in differ[:5]:
        print(f"  differs: {d['request'][:300]}\n    JVM:  {d['answer'][:300]}\n    here: {d['got'][:300]}")
    return 0 if not differ else 1


if __name__ == "__main__":
    sys.exit(main(*sys.argv[1:]))
