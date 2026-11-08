#!/usr/bin/env bash
# Checks the compiler's Central Portal bundle before it is kept or uploaded: exactly the artifacts in $ARTIFACTS, each
# with the files Maven Central asks for at the version given, and a signature beside every file. Copies the bundle to
# <out>/publicationAggregated.zip and unpacks it to <out>/maven-repo, and writes what it staged to the job's summary.
#   compiler-release-check.sh <bundle zip> <version> verify|present <out>
# verify checks each signature with the key in the runner's keyring (the build job's throwaway key); present only asks
# that each signature is there (the publish job, whose release key's public half isn't on the runner).
set -euo pipefail
zip="$1" version="$2" signatures="$3" out="$4"
fail() { echo "::error::$*"; exit 1; }
[[ "$signatures" == verify || "$signatures" == present ]] || fail "The third argument is verify or present, not $signatures."
[[ -n "${ARTIFACTS:-}" ]] || fail "ARTIFACTS, the artifacts to publish, isn't set."

rm -rf "$out"
mkdir -p "$out/maven-repo"
cp "$zip" "$out/publicationAggregated.zip"
unzip -q "$out/publicationAggregated.zip" -d "$out/maven-repo"
repo="$out/maven-repo/org/drivine"

staged=$(ls "$repo" | sort | tr '\n' ' ')
expected=$(tr ' ' '\n' <<< "$ARTIFACTS" | grep . | sort | tr '\n' ' ')
[[ "$staged" == "$expected" ]] || fail "The staged artifacts are [$staged], not [$expected]."

for a in $ARTIFACTS; do
    dir="$repo/$a/$version"
    [[ -d "$dir" ]] || fail "$a has no version $version: it has $(ls "$repo/$a" | tr '\n' ' ')."
    # The root publication and the JVM's carry a jar; every other target's, a klib.
    case "$a" in
        drivine-cypher-compiler|drivine-cypher-compiler-jvm) main="$a-$version.jar" ;;
        *) main="$a-$version.klib" ;;
    esac
    for f in "$main" "$a-$version-sources.jar" "$a-$version-javadoc.jar" "$a-$version.pom" "$a-$version.module"; do
        [[ -f "$dir/$f" ]] || fail "$a has no $f."
    done
    grep -q "<version>$version</version>" "$dir/$a-$version.pom" || fail "$a's pom isn't version $version."
    # Every file Maven Central serves, not its checksums, is signed.
    for f in "$dir"/*; do
        case "$f" in *.asc|*.md5|*.sha1|*.sha256|*.sha512) continue ;; esac
        [[ -f "$f.asc" ]] || fail "$(basename "$f") has no signature."
        if [[ "$signatures" == verify ]]; then
            gpg --batch --verify "$f.asc" "$f" 2> /dev/null || fail "$(basename "$f")'s signature doesn't verify."
        fi
    done
done

{
    echo "### Staged for Maven Central: org.drivine:drivine-cypher-compiler $version"
    echo '```'
    (cd "$out/maven-repo" && find . -type f ! -name '*.md5' ! -name '*.sha*' ! -name '*.asc' | sort)
    echo '```'
} >> "${GITHUB_STEP_SUMMARY:-/dev/null}"
echo "The bundle holds every artifact of $version, each signed."
