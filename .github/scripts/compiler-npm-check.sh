#!/usr/bin/env bash
# Checks the npm package's tarball, as `npm pack` made it from compiler/build/npm, before it is published: its name is
# gradle.properties' compiler.npmName, its version the one given, and it holds the package's files and nothing else.
# Prints the tarball's npm integrity (sha512, as npm's registry gives it).
#
#   .github/scripts/compiler-npm-check.sh <package .tgz> <version> <name>
set -euo pipefail

tgz="${1:?usage: compiler-npm-check.sh <package .tgz> <version> <name>}"
version="${2:?no version}"
name="${3:?no name}"

# The files npmPackage puts in build/npm, as npm pack writes them, under package/.
expected="package/LICENSE
package/README.md
package/drivine-cypher-compiler.d.mts
package/drivine-cypher-compiler.mjs
package/drivine-cypher-compiler.uninstantiated.mjs
package/drivine-cypher-compiler.wasm
package/index.cjs
package/index.d.cts
package/package.json"

files="$(tar -tzf "$tgz" | sort)"
if [[ "$files" != "$expected" ]]; then
    echo "::error::The npm package holds other files than the package's:"
    diff <(echo "$expected") <(echo "$files") || true
    exit 1
fi

manifest="$(tar -xOzf "$tgz" package/package.json)"
got_name="$(node -e 'process.stdout.write(JSON.parse(process.argv[1]).name)' "$manifest")"
got_version="$(node -e 'process.stdout.write(JSON.parse(process.argv[1]).version)' "$manifest")"
if [[ "$got_name" != "$name" || "$got_version" != "$version" ]]; then
    echo "::error::The npm package is $got_name@$got_version, not $name@$version."
    exit 1
fi

echo "integrity=sha512-$(openssl dgst -sha512 -binary "$tgz" | base64 | tr -d '\n')"
