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
#     After the build: every dependency Kotlin/Native unpacked is, by its directory's name, one <list> pins. This checks
#     names, after the fact; what keeps an unpinned one from being fetched and run is block. Where Kotlin/Native wanted
#     another, as after a bump of Kotlin, the build fails at block's doing, or this fails naming each: see
#     compiler/README.md, "Releasing".
#   compiler-konan-dependencies.sh block
#     After fetch, on a CI runner: points download.jetbrains.com nowhere (in /etc/hosts, with sudo), and checks it can't
#     be reached, so Kotlin/Native can't fetch a dependency the list doesn't pin, and the build fails before it would run
#     one, rather than check finding it after. Kotlin/Native's prebuilt bundle comes from Maven Central, through Gradle.
set -euo pipefail
mode="${1:-}" list="${2:-}"
if [[ "$mode" == block ]]; then
    printf '0.0.0.0 download.jetbrains.com\n:: download.jetbrains.com\n' | sudo tee -a /etc/hosts > /dev/null
    # macOS answers from its resolver's cache until told to read /etc/hosts again.
    if [[ "$(uname -s)" == Darwin ]]; then
        sudo dscacheutil -flushcache
        sudo killall -HUP mDNSResponder || true
    fi
    if curl -s --max-time 10 -o /dev/null https://download.jetbrains.com/; then
        echo "::error::download.jetbrains.com is still reachable, so Kotlin/Native could fetch what the list doesn't pin."
        exit 1
    fi
    echo "download.jetbrains.com is blocked: Kotlin/Native can use only the dependencies fetch put in its way."
    exit 0
fi
if [[ ( "$mode" != fetch && "$mode" != check ) || ! -f "$list" ]]; then
    echo "compiler-konan-dependencies.sh fetch|check <list> | block" >&2
    exit 2
fi
archive="$(cd "$(dirname "$0")/../actions/pinned-toolchain" && pwd)/archive.sh"
kept="$HOME/.cache/drivine-konan-dependencies"
deps="${KONAN_DATA_DIR:-$HOME/.konan}/dependencies"

# <sha256> <url> of each pin, checked for form.
pins=()
# The last line is read too where the file doesn't end in a newline.
while read -r want url rest || [[ -n "$want" ]]; do
    [[ -z "$want" || "$want" == \#* ]] && continue
    if [[ ! "$want" =~ ^[0-9a-f]{64}$ || ! "$url" =~ ^https://[^/]+/.+\.tar\.gz$ || -n "$rest" ]]; then
        echo "::error::$list: a line isn't <sha256> <https URL of a .tar.gz>: $want $url $rest"
        exit 1
    fi
    pins+=("$want $url")
done < "$list"
# A list that pins nothing is a mistake, and check would then pass only where Kotlin/Native unpacked nothing.
if (( ${#pins[@]} == 0 )); then
    echo "::error::$list pins nothing."
    exit 1
fi

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
