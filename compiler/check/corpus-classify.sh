#!/usr/bin/env bash
# Sorts every answer the store layout 2 changes into the cause that changed it (see corpus-classify.py), and fails where
# one is put down to no cause.
#
#   check/corpus-classify.sh [ref] [scratch dir]
#
# Compiles this checkout's spec statements with the compiler at [ref] (origin/main by default), unpacked with `git archive`
# into [scratch dir] (a temporary one by default), so the checkout is never touched; compiles them again with this
# checkout's compiler; and sorts the answers that differ. Run from the compiler directory, with a JDK on the PATH.
set -euo pipefail
cd "$(dirname "$0")/.."
ref="${1:-origin/main}"
scratch="${2:-$(mktemp -d)}"
root="$(git rev-parse --show-toplevel)"
rm -rf "$scratch/main"
mkdir -p "$scratch/main"
git -C "$root" archive "$ref" | tar -x -C "$scratch/main"
# The same requests: this checkout's spec files, read by the other compiler.
cp "$root"/*.yaml "$scratch/main/"
rm -rf "$scratch/main/search"
cp -R "$root/search" "$scratch/main/search"
(cd "$scratch/main/compiler" && ./gradlew -q jvmOracle >/dev/null)
./gradlew -q jvmOracle >/dev/null
python3 check/corpus-classify.py "$scratch/main/compiler/build/oracle/corpus.jsonl" build/oracle/corpus.jsonl
