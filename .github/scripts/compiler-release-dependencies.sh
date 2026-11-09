#!/usr/bin/env bash
# The release build's dependencies, kept between runs in the Actions cache (compiler/README.md, "Releasing"), as files
# whose contents are each checked before use, so what an entry holds can't change what is built: at worst the build
# fetches what the entry doesn't hold, or holds wrongly.
#
#   compiler-release-dependencies.sh stage <dir>
#     Copies this runner's dependencies into <dir>, empty: gradle/, Gradle's downloaded files (the jars, POMs, modules
#     and archives of ~/.gradle/caches/modules-2/files-2.1, which Gradle checks against gradle/verification-metadata.xml
#     as it uses them); toolchain/, the JDK and Gradle archives of .github/actions/pinned-toolchain, which it checks
#     against their pinned SHA-256s; and konan/, the archives of Kotlin/Native's own dependencies, which
#     compiler-konan-dependencies.sh checks against compiler/gradle/kotlin-native-dependencies.txt. Nothing Gradle or
#     Kotlin/Native derives from them (unpacked, transformed, compiled or indexed) is copied, as nothing checks it. It
#     fails where a file it copied has a name unpack would refuse, so a name outside the layout below fails the job that
#     keeps the entry, rather than leaving every release to fetch everything with only a warning.
#
#   compiler-release-dependencies.sh unpack <zip>
#     Puts the files of a zip of such a <dir> where the build looks for them, once every entry is a plain file or a
#     directory at one of the paths stage makes, no name climbs out of it, and none is there twice: a zip with any other
#     entry is refused whole, with a warning, and nothing is put anywhere. The zip is the dependencies job's upload of a
#     cache entry, and an entry is unpacked by actions/cache with tar -P, which writes to any path the entry names: so
#     the release build never restores the cache itself, and unpacks only this, its plain files, checked by name. It
#     also lays out the binaryen archives among Gradle's files as binaryen's releases are, in ~/.cache/drivine-binaryen,
#     for -Pcompiler.binaryenMirror (see build.gradle.kts): Gradle can't take them from its own files without asking
#     GitHub.
set -euo pipefail

gradle_files="$HOME/.gradle/caches/modules-2/files-2.1"
toolchain_files="$HOME/.cache/drivine-pinned-toolchain"
konan_files="$HOME/.cache/drivine-konan-dependencies"
binaryen_mirror="$HOME/.cache/drivine-binaryen"

# A name stage makes, as zip lists it: no empty, . or .. part, no leading /, and only the characters Maven coordinates,
# Gradle's SHA-1 directories and the archives' names use.
part='[A-Za-z0-9_+][A-Za-z0-9_.+-]*'
allowed="^(gradle/|toolchain/|konan/|gradle/$part/|gradle/$part/$part/|gradle/$part/$part/$part/|gradle/$part/$part/$part/[0-9a-f]{1,40}/|gradle/$part/$part/$part/[0-9a-f]{1,40}/$part|toolchain/$part|konan/$part\\.tar\\.gz)\$"

case "${1:-}" in
stage)
    dir="${2:?compiler-release-dependencies.sh stage <dir>}"
    mkdir -p "$dir"
    if [[ -n "$(ls -A "$dir")" ]]; then
        echo "::error::$dir isn't empty."
        exit 1
    fi
    mkdir "$dir/gradle" "$dir/toolchain" "$dir/konan"
    [[ -d "$gradle_files" ]] && cp -R "$gradle_files/." "$dir/gradle/"
    [[ -d "$toolchain_files" ]] && find "$toolchain_files" -maxdepth 1 -type f -exec cp {} "$dir/toolchain/" \;
    [[ -d "$konan_files" ]] && find "$konan_files" -maxdepth 1 -type f -name '*.tar.gz' -exec cp {} "$dir/konan/" \;
    if bad=$(cd "$dir" && find . ! -type f ! -type d -print -quit); [[ -n "$bad" ]]; then
        echo "::error::${bad#./} isn't a plain file or directory, so unpack would refuse the entry."
        exit 1
    fi
    # Tested by what it prints, not by its status: with pipefail, a head that stops reading fails the pipe.
    if bad=$(cd "$dir" && find . -type f | sed 's|^\./||' | grep -vE "$allowed" | head -n 1); [[ -n "$bad" ]]; then
        echo "::error::$bad isn't a name unpack takes, so it would refuse the entry: widen its pattern, and its test."
        exit 1
    fi
    echo "Staged $(find "$dir" -type f | wc -l | tr -d ' ') files, $(du -sh "$dir" | cut -f1), in $dir."
    ;;
unpack)
    zip="${2:?compiler-release-dependencies.sh unpack <zip>}"
    work=$(mktemp -d "${RUNNER_TEMP:-${TMPDIR:-/tmp}}/compiler-release-dependencies.XXXXXX")
    trap 'rm -rf "$work"' EXIT
    refuse() {
        echo "::warning::The release dependencies' zip is refused, and nothing of it is used: $*. The build fetches every dependency."
        exit 0
    }
    unzip -Z1 "$zip" > "$work/names" 2> "$work/error" || refuse "unzip can't list it ($(head -n 1 "$work/error"))"
    [[ -s "$work/names" ]] || refuse "it holds nothing"
    if bad=$(grep -m 1 -vE "$allowed" "$work/names"); then
        refuse "it holds $bad, which isn't a path stage makes"
    fi
    if grep -qE '(^|/)\.\.?(/|$)' "$work/names"; then
        refuse "a name has a . or .. part"
    fi
    # Each name once: unzip asks whether to replace a file it has already written, and what it is told decides which
    # of the two is used.
    if bad=$(sort "$work/names" | uniq -d | head -n 1); [[ -n "$bad" ]]; then
        refuse "it holds $bad twice"
    fi
    # The type of each entry, from its Unix mode where the zip has one: only files (-) and directories (d).
    if bad=$(unzip -Z -l "$zip" | awk 'NR > 2 && /^[^ ]{10} / && $1 !~ /^[-d]/ { print $NF; exit }'); [[ -n "$bad" ]]; then
        refuse "$bad isn't a plain file or directory"
    fi
    mkdir "$work/out"
    unzip -q "$zip" -d "$work/out" < /dev/null 2> "$work/error" || refuse "unzip can't unpack it ($(head -n 1 "$work/error"))"
    # As unpacked: still nothing but files and directories, so nothing below is a link.
    if bad=$(find "$work/out" ! -type f ! -type d -print -quit); [[ -n "$bad" ]]; then
        refuse "${bad#"$work/out/"} isn't a plain file or directory"
    fi
    count() { find "$1" -type f 2> /dev/null | wc -l | tr -d ' '; }
    mkdir -p "$gradle_files" "$toolchain_files" "$konan_files"
    [[ -d "$work/out/gradle" ]] && cp -R "$work/out/gradle/." "$gradle_files/"
    [[ -d "$work/out/toolchain" ]] && cp -R "$work/out/toolchain/." "$toolchain_files/"
    [[ -d "$work/out/konan" ]] && cp -R "$work/out/konan/." "$konan_files/"
    # gradle/com.github.webassembly/binaryen/<v>/<sha1>/binaryen-<v>-<platform>.tar.gz, as
    # version_<v>/binaryen-version_<v>-<platform>.tar.gz.
    if [[ -d "$work/out/gradle/com.github.webassembly/binaryen" ]]; then
        while IFS= read -r -d '' file; do
            v=$(basename "$(dirname "$(dirname "$file")")") name=$(basename "$file")
            [[ "$name" == "binaryen-$v-"* ]] || continue
            mkdir -p "$binaryen_mirror/version_$v"
            cp "$file" "$binaryen_mirror/version_$v/binaryen-version_$v-${name#"binaryen-$v-"}"
        done < <(find "$work/out/gradle/com.github.webassembly/binaryen" -mindepth 3 -maxdepth 3 -type f -name 'binaryen-*.tar.gz' -print0)
    fi
    echo "Unpacked $(count "$work/out/gradle") of Gradle's files, $(count "$work/out/toolchain") toolchain archives and $(count "$work/out/konan") of Kotlin/Native's archives, each checked as it is used."
    ;;
*)
    echo "compiler-release-dependencies.sh stage <dir> | unpack <zip>" >&2
    exit 2
    ;;
esac
