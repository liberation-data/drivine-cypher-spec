#!/usr/bin/env bash
# The npm package as a user gets it: its tarball installed into an empty project, with only the dependencies its
# package.json names (no NODE_PATH), compiles each request of the JVM's corpus, and its answer must be the JVM's, byte for
# byte. Run from compiler/, after wasmJsCorpusCheck has built the corpus.
#
#   ../.github/scripts/compiler-npm-clean-install.sh <package .tgz> <corpus.jsonl> <differences.jsonl>
set -euo pipefail

tgz="$(cd "$(dirname "${1:?usage: compiler-npm-clean-install.sh <package .tgz> <corpus.jsonl> <differences.jsonl>}")" && pwd)/$(basename "$1")"
corpus="$(cd "$(dirname "${2:?no corpus}")" && pwd)/$(basename "$2")"
differences="${3:?no differences file}"
check="$(pwd)/check/corpus-node.cjs"

project="$(mktemp -d)"
trap 'rm -rf "$project"' EXIT
echo '{"private": true}' > "$project/package.json"
(cd "$project" && npm install --no-audit --no-fund --ignore-scripts --save --save-prod "$tgz" > /dev/null)

name="$(tar -xOzf "$tgz" package/package.json | node -e 'let s="";process.stdin.on("data",d=>s+=d).on("end",()=>process.stdout.write(JSON.parse(s).name))')"
# Its line in the job's summary is told apart from the Gradle corpus check's, which is also on wasmJs.
env -u NODE_PATH DRIVINE_NPM_NAME="$name" node "$check" "$project/node_modules/$name" "$corpus" "$differences" |
    sed 's/^Corpus on wasmJs (/Corpus on wasmJs, the packed npm package installed (/'
