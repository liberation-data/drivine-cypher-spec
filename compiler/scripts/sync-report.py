"""
What a sync from drivine4j leaves to look at by hand, which scripts/sync-from-drivine4j.sh prints after it merges:

- Files outside the mapping (scripts/sync-paths.txt) that the compiler imports and that changed between the two commits.
  The compiler's imports are followed from drivine4j's mapped sources at the new commit, through `import org.drivine...`
  lines, from file to file. An import the module's copy of a file dropped is one the port replaced on purpose (Jackson's
  mapper by the module's JSON tree, say): its file is listed apart, as one to check the replacement of, and not followed.
  A file only the library uses (full-text search, the connections) is in neither list.
- New tests of the compiler's package (src/test/kotlin/org/drivine/cypher) that the mapping has no line for.

    python3 scripts/sync-report.py <drivine4j clone> <old commit> <new commit> <module directory>
"""
import os
import re
import subprocess
import sys

repo, base, to, module = sys.argv[1:5]
MAIN = "src/main/kotlin/"
IMPORT = re.compile(r"^import (org\.drivine\.[A-Za-z0-9_]+(?:\.[A-Za-z0-9_]+)*)", re.M)


def git(*args):
    return subprocess.run(["git", "-C", repo, *args], check=True, capture_output=True, text=True).stdout


def mapping():
    pairs = []
    with open(os.path.join(module, "scripts/sync-paths.txt"), encoding="utf-8") as f:
        for line in f:
            line = line.strip()
            if line and not line.startswith("#"):
                source, into = line.split()
                pairs.append((source, into))
    return pairs


pairs = mapping()
files_at_to = set(git("ls-tree", "-r", "--name-only", to).split("\n"))


def mapped_to(path):
    """The module file drivine4j's [path] is ported to, or None where the mapping has none."""
    for source, into in pairs:
        if source.endswith("/") and path.startswith(source):
            return into + path[len(source):]
        if path == source:
            return into
    return None


def resolve(name):
    """The drivine4j source file an import names: the file of its class, or the file in its package that declares it."""
    parts = name.split(".")
    for cut in range(len(parts), 1, -1):
        candidate = MAIN + "/".join(parts[:cut]) + ".kt"
        if candidate in files_at_to:
            return candidate
    package, simple = MAIN + "/".join(parts[:-1]) + "/", parts[-1]
    declares = re.compile(r"^(?:[a-z ]*)(?:class|object|interface|fun|val|typealias)\s+(?:<[^>]*>\s*)?" + re.escape(simple) + r"\b", re.M)
    for path in sorted(files_at_to):
        if path.startswith(package) and "/" not in path[len(package):] and path.endswith(".kt"):
            if declares.search(git("show", f"{to}:{path}")):
                return path
    return None


def imports(text):
    return set(IMPORT.findall(text))


changed = set(git("diff", "--name-only", base, to).split("\n")) - {""}
start = [p for p in files_at_to if p.startswith(MAIN) and p.endswith(".kt") and mapped_to(p)]
seen, replaced, queue = set(start), set(), list(start)
while queue:
    path = queue.pop()
    theirs = imports(git("show", f"{to}:{path}"))
    ours_path = mapped_to(path)
    ours = os.path.join(module, ours_path) if ours_path else None
    kept = imports(open(ours, encoding="utf-8").read()) if ours and os.path.isfile(ours) else None
    for name in theirs:
        target = resolve(name)
        if target is None or target in seen:
            continue
        if kept is not None and name not in kept and mapped_to(target) is None:
            # The port dropped the import: replaced on purpose, so its file is checked, not followed.
            replaced.add(target)
            continue
        seen.add(target)
        queue.append(target)

imported = sorted(p for p in seen if mapped_to(p) is None and p in changed)
replaced = sorted(p for p in replaced - seen if p in changed)
new_tests = sorted(
    line.split("\t", 1)[1] for line in git("diff", "--no-renames", "--name-status", base, to, "--", "src/test/kotlin/org/drivine/cypher").split("\n")
    if line.startswith("A\t") and mapped_to(line.split("\t", 1)[1]) is None
)
if imported:
    print("  changed in drivine4j outside the mapped files, which the compiler imports:")
    print("\n".join("    " + p for p in imported))
if replaced:
    print("  changed in drivine4j, imported by the compiler there and replaced in the port: check the replacement:")
    print("\n".join("    " + p for p in replaced))
if new_tests:
    print("  unmapped compiler test, consider adding to scripts/sync-paths.txt:")
    print("\n".join("    " + p for p in new_tests))
