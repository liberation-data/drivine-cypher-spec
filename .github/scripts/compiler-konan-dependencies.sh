#!/usr/bin/env bash
# Kotlin/Native's own dependencies (its LLVM, libffi, and the sysroots and toolchains of the targets it cross-compiles),
# pinned for the release build in compiler/gradle/kotlin-native-dependencies.txt, one archive a line: its SHA-256 and the
# URL Kotlin/Native fetches it from. Kotlin/Native fetches these itself, from download.jetbrains.com, and checks nothing;
# but where it finds an archive in ~/.konan/dependencies/cache (or $KONAN_DATA_DIR's), it unpacks that in place of
# fetching it, and deletes it.
#
#   compiler-konan-dependencies.sh fetch <list>
#     Keeps each archive of <list> in ~/.cache/drivine-konan-dependencies: as kept there already (from the release
#     dependencies' cache), or where none is kept, or not this one, fetched with retries; either way checked against its
#     SHA-256, or refused. Then copies each into Kotlin/Native's cache, for it to unpack.
#   compiler-konan-dependencies.sh check <list>
#     After the build: every dependency Kotlin/Native unpacked is one <list> pins, so it fetched and ran nothing else.
#     Where it fetched another, as after a bump of Kotlin, it fails naming each: see compiler/README.md, "Releasing".
set -euo pipefail
mode="${1:-}" list="${2:-}"
if [[ ( "$mode" != fetch && "$mode" != check ) || ! -f "$list" ]]; then
    echo "compiler-konan-dependencies.sh fetch|check <list>" >&2
    exit 2
fi
archive="$(cd "$(dirname "$0")/../actions/pinned-toolchain" && pwd)/archive.sh"
kept="$HOME/.cache/drivine-konan-dependencies"
deps="${KONAN_DATA_DIR:-$HOME/.konan}/dependencies"

# <sha256> <url> of each pin, checked for form.
pins=()
while read -r want url rest; do
    [[ -z "$want" || "$want" == \#* ]] && continue
    if [[ ! "$want" =~ ^[0-9a-f]{64}$ || ! "$url" =~ ^https://[^/]+/.+\.tar\.gz$ || -n "$rest" ]]; then
        echo "::error::$list: a line isn't <sha256> <https URL of a .tar.gz>: $want $url $rest"
        exit 1
    fi
    pins+=("$want $url")
done < "$list"

if [[ "$mode" == fetch ]]; then
    mkdir -p "$kept" "$deps/cache"
    for pin in "${pins[@]}"; do
        want="${pin%% *}" url="${pin#* }"
        GITHUB_OUTPUT=/dev/null "$archive" "$url" "$want" "$kept/${url##*/}"
        cp "$kept/${url##*/}" "$deps/cache/"
    done
    exit 0
fi

pinned() {
    local pin
    for pin in "${pins[@]}"; do [[ "${pin##*/}" == "$1.tar.gz" ]] && return 0; done
    return 1
}
failed=0 used=0
if [[ -d "$deps" ]]; then
    while IFS= read -r -d '' dir; do
        name="${dir##*/}"
        [[ "$name" == cache || "$name" == .* ]] && continue
        if pinned "$name"; then
            used=$((used + 1))
        else
            echo "::error::Kotlin/Native unpacked $name into $deps, from an archive $list doesn't pin."
            failed=1
        fi
    done < <(find "$deps" -mindepth 1 -maxdepth 1 -print0)
fi
(( failed == 0 )) || exit 1
echo "Kotlin/Native used $used of its dependencies, each one $list pins."
