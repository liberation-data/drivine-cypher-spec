#!/usr/bin/env bash
# Checks the compiler's Central Portal bundle before it is kept or uploaded: exactly the artifacts in $ARTIFACTS, each
# with the files Maven Central asks for at the version given, and with verify a signature beside every file. It holds
# nothing else: no link, no other version, no file an artifact's publication doesn't make, nothing outside org/drivine. Copies the bundle to
# <out>/publicationAggregated.zip and unpacks it to <out>/maven-repo, and writes what it staged to the job's summary.
#   compiler-release-check.sh <bundle zip> <version> verify|none <out>
# verify checks each signature with the key in the keyring GNUPGHOME names (compiler-release-sign.sh's, of the key it
# signed with); none asks for no signature (the build job's bundle, which the job after the gate signs).
set -euo pipefail
zip="$1" version="$2" signatures="$3" out="$4"
fail() { echo "::error::$*"; exit 1; }
[[ "$signatures" == verify || "$signatures" == none ]] || fail "The third argument is verify or none, not $signatures."
[[ -n "${ARTIFACTS:-}" ]] || fail "ARTIFACTS, the artifacts to publish, isn't set."

rm -rf "$out"
mkdir -p "$out/maven-repo"
cp "$zip" "$out/publicationAggregated.zip"
unzip -q "$out/publicationAggregated.zip" -d "$out/maven-repo"
repo="$out/maven-repo/org/drivine"

# Only files and directories: asked of find alone, which stops at the first, not piped (see compiler-release-sign.sh).
[[ -z "$(find "$out/maven-repo" ! -type f ! -type d -print -quit)" ]] || fail "The bundle holds an entry that is neither a file nor a directory, such as a link."
# Only what each artifact's publication makes at this version: its files (a target's metadata jar, the root's Kotlin
# tooling metadata, where it makes them), each file's checksums, and with verify each file's signature.
allowed="$out/allowed.txt" present="$out/present.txt" extra="$out/extra.txt"
{
    printf '%s\n' org org/drivine
    for a in $ARTIFACTS; do
        d="org/drivine/$a/$version"
        printf '%s\n' "org/drivine/$a" "$d"
        for s in .pom .module -sources.jar -javadoc.jar .jar .klib -metadata.jar -kotlin-tooling-metadata.json; do
            for c in "" .md5 .sha1 .sha256 .sha512; do printf '%s\n' "$d/$a-$version$s$c"; done
            if [[ "$signatures" == verify ]]; then printf '%s\n' "$d/$a-$version$s.asc"; fi
        done
    done
} | LC_ALL=C sort -u > "$allowed"
(cd "$out/maven-repo" && find . -mindepth 1 | sed 's#^\./##' | LC_ALL=C sort) > "$present"
LC_ALL=C comm -13 "$allowed" "$present" > "$extra"
[[ ! -s "$extra" ]] || fail "The bundle holds what no artifact's publication at $version makes: $(head -5 "$extra" | tr '\n' ' ')"

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
        [[ "$signatures" == none ]] && continue
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
echo "The bundle holds every artifact of $version$([[ "$signatures" == none ]] || echo ", each signed")."
