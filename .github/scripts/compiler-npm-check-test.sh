#!/usr/bin/env bash
# compiler-npm-check.sh's own test: made-up tarballs of the package's files, with a file too many or too few, another
# name or version, or a package.json with `scripts` or `publishConfig`, are refused, and the package's own passes with its
# integrity. No build.
#
#   .github/scripts/compiler-npm-check-test.sh
set -euo pipefail

check="$(cd "$(dirname "$0")" && pwd)/compiler-npm-check.sh"
root="$(mktemp -d)"
trap 'rm -rf "$root"' EXIT
fail() { echo "compiler-npm-check-test: $*" >&2; exit 1; }

name=@liberation-data/drivine-cypher-compiler
files=(LICENSE README.md drivine-cypher-compiler.d.mts drivine-cypher-compiler.mjs drivine-cypher-compiler.uninstantiated.mjs
    drivine-cypher-compiler.wasm index.cjs index.d.cts)

# A tarball as npm pack makes it, of package/<file> entries and no directory entries: $1 the tarball, $2 the manifest,
# then any files beyond the package's.
pack() {
    local tgz="$1" manifest="$2" dir
    shift 2
    dir="$(mktemp -d "$root/pkg.XXXXXX")"
    mkdir "$dir/package"
    for f in "${files[@]}" "$@"; do echo "$f" > "$dir/package/$f"; done
    echo "$manifest" > "$dir/package/package.json"
    (shopt -s dotglob; cd "$dir" && tar -czf "$tgz" package/*)
}
refused() {
    local what="$1" tgz="$2" expect="$3" out
    if out="$("$check" "$tgz" 1.2.3 "$name" 2>&1)"; then fail "$what passed"; fi
    grep -qF -- "$expect" <<<"$out" || fail "$what: not its failure: $out"
    # The workflow writes the check's stdout to the job's outputs, so a failure says nothing there.
    [[ -z "$("$check" "$tgz" 1.2.3 "$name" 2> /dev/null)" ]] || fail "$what: written to stdout, which is the job's outputs"
}

pack "$root/ok.tgz" "{\"name\": \"$name\", \"version\": \"1.2.3\"}"
out="$("$check" "$root/ok.tgz" 1.2.3 "$name")" || fail "the package's own tarball was refused: $out"
[[ "$out" == "integrity=sha512-$(openssl dgst -sha512 -binary "$root/ok.tgz" | base64 | tr -d '\n')" ]] || fail "not its integrity: $out"
# In a locale that sorts LICENSE among the lower-case names, as macOS's en_US does, the same tarball passes.
LC_ALL=en_US.UTF-8 "$check" "$root/ok.tgz" 1.2.3 "$name" > /dev/null || fail "the package's own tarball was refused in en_US.UTF-8"

pack "$root/extra.tgz" "{\"name\": \"$name\", \"version\": \"1.2.3\"}" .npmrc
refused 'a file too many' "$root/extra.tgz" '> package/.npmrc'

files=("${files[@]:1}")
pack "$root/missing.tgz" "{\"name\": \"$name\", \"version\": \"1.2.3\"}"
refused 'a file too few' "$root/missing.tgz" '< package/LICENSE'
files=(LICENSE "${files[@]}")

pack "$root/version.tgz" "{\"name\": \"$name\", \"version\": \"1.2.4\"}"
refused 'another version' "$root/version.tgz" "is $name@1.2.4, not $name@1.2.3"

pack "$root/name.tgz" '{"name": "@drivine/cypher-compiler", "version": "1.2.3"}'
refused 'another name' "$root/name.tgz" "is @drivine/cypher-compiler@1.2.3, not $name@1.2.3"

pack "$root/scripts.tgz" "{\"name\": \"$name\", \"version\": \"1.2.3\", \"scripts\": {\"postinstall\": \"node index.cjs\"}}"
refused 'scripts' "$root/scripts.tgz" "package.json has scripts, which"

pack "$root/publish-config.tgz" "{\"name\": \"$name\", \"version\": \"1.2.3\", \"publishConfig\": {\"registry\": \"https://registry.invalid/\"}}"
refused 'publishConfig' "$root/publish-config.tgz" "package.json has publishConfig, which"

pack "$root/both.tgz" "{\"name\": \"$name\", \"version\": \"1.2.3\", \"scripts\": {}, \"publishConfig\": {}}"
refused 'scripts and publishConfig' "$root/both.tgz" "package.json has scripts and publishConfig, which"
echo "compiler-npm-check-test: passed"
