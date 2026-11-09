#!/usr/bin/env bash
# The npm corpus check's own test: check/corpus-node.cjs over made-up packages, for the failures it gives before it loads
# one. No build.
#
#   compiler/check/corpus-node-test.sh
set -euo pipefail

check="$(cd "$(dirname "$0")" && pwd)/corpus-node.cjs"
root="$(mktemp -d)"
trap 'rm -rf "$root"' EXIT
fail() { echo "corpus-node-test: $*" >&2; exit 1; }

echo '{"name": "@drivine/cypher-compiler", "main": "index.cjs"}' > "$root/package.json"

# A package of another name than DRIVINE_NPM_NAME fails, naming both.
if out="$(DRIVINE_NPM_NAME=@liberation-data/drivine-cypher-compiler node "$check" "$root" none 2>&1)"; then
    fail "a package of another name passed"
fi
grep -qF 'named @drivine/cypher-compiler, not @liberation-data/drivine-cypher-compiler' <<<"$out" || fail "not the name's failure: $out"

# Of the name, or with no name asked, it goes on to the next check: here, the entry it hasn't.
for name in @drivine/cypher-compiler ''; do
    if out="$(DRIVINE_NPM_NAME="$name" node "$check" "$root" none 2>&1)"; then
        fail "a package without its entry passed"
    fi
    grep -qF "names files it hasn't: index.cjs" <<<"$out" || fail "DRIVINE_NPM_NAME='$name': not the entry's failure: $out"
done
echo "corpus-node-test: passed"
