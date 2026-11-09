#!/usr/bin/env bash
# The release build's dependencies, kept between runs in the Actions cache (compiler/README.md, "Releasing"), as files
# whose contents are each checked before use, so what an entry holds can't change what is built, nor fail the build: at
# worst the build fetches what the entry doesn't hold, or holds wrongly.
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
#     Puts the files of a zip of such a <dir> where the build looks for them. The zip is the dependencies job's upload
#     of a cache entry, and an entry is unpacked by actions/cache with tar -P, which writes to any path the entry names:
#     so the release build never restores the cache itself, and unpacks only this, checked. Its names first: every
#     entry is a plain file or a directory at one of the paths stage makes, no name climbs out of it, none is there
#     twice (in any case), and there are at most 5,000 entries and 3 GB unpacked. Then, unpacked, each of Gradle's files
#     is kept only where its SHA-1 is the directory it sits in and its SHA-256 is the one gradle/verification-metadata.xml
#     pins for it: Gradle takes a file at its SHA-1's path without checking it, and strict verification would then fail
#     every release that read the entry, so a file of other bytes is dropped, and Gradle fetches it. (So are the POMs
#     Gradle reads but doesn't check, of modules it reads Gradle's module metadata for: small files, fetched again.) A
#     toolchain or Kotlin/Native archive is kept only where its name and SHA-256 are ones their pins name, so nothing
#     unused sits on the disk, and is checked again where it is used, and fetched where it isn't kept. A zip that fails
#     a check, or a step that fails, is refused whole, with a warning: nothing of it is left in place, and the build
#     fetches everything. So an entry, whatever it holds, can't fail a release: at worst
#     the build fetches what it lacks. It also lays out this runner's binaryen archive among Gradle's files (on macOS
#     arm64, binaryen-<v>-arm64-macos.tar.gz) as binaryen's releases are, in ~/.cache/drivine-binaryen, for
#     -Pcompiler.binaryenMirror (see build.gradle.kts): Gradle can't take it from its own files without asking GitHub,
#     and only this runner's, as a mirror holding the version but not this runner's archive would fail the build.
set -euo pipefail

gradle_files="$HOME/.gradle/caches/modules-2/files-2.1"
toolchain_files="$HOME/.cache/drivine-pinned-toolchain"
konan_files="$HOME/.cache/drivine-konan-dependencies"
binaryen_mirror="$HOME/.cache/drivine-binaryen"
# binaryen's name for this runner's platform, as its releases name their archives.
case "$(uname -s)-$(uname -m)" in
Darwin-arm64) binaryen_platform=arm64-macos ;; Darwin-x86_64) binaryen_platform=x86_64-macos ;;
Linux-x86_64) binaryen_platform=x86_64-linux ;; Linux-aarch64) binaryen_platform=aarch64-linux ;;
*) binaryen_platform= ;;
esac
# What pins the dependencies: gradle/verification-metadata.xml Gradle's files, and the rest the toolchain and
# Kotlin/Native archives.
pins="$(cd "$(dirname "$0")/../.." && pwd)/compiler/gradle"
# At most this much, unpacked, and this many entries: an entry is about 1.2 GB in some 300 files.
max_bytes=$((3 * 1024 * 1024 * 1024)) max_entries=5000
# compiler-release-dependencies-test.sh names its own platform, pins and limits; nothing else can.
if [[ "${COMPILER_RELEASE_DEPENDENCIES_TEST:-}" == true ]]; then
    binaryen_platform="${COMPILER_BINARYEN_PLATFORM:-$binaryen_platform}"
    pins="${COMPILER_PINS:-$pins}"
    max_bytes="${COMPILER_MAX_BYTES:-$max_bytes}" max_entries="${COMPILER_MAX_ENTRIES:-$max_entries}"
fi

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
    # Made writable first: an entry can carry a mode that makes its directory one rm can't empty.
    trap 'chmod -R u+rwX "$work" 2> /dev/null || true; rm -rf "$work"' EXIT
    placed=false
    # Whatever stops unpack, from a check that fails to a command that does, leaves nothing of the zip in place: it runs
    # on a fresh runner, before anything else is put in these directories, so it takes them away whole.
    refuse() {
        trap - ERR
        # set -E runs the ERR trap in a $(...) too, where exit would end only that subshell and unpack would go on: there
        # it fails instead, so the command the $(...) is in fails, and unpack refuses.
        if (( BASH_SUBSHELL > 0 )); then
            exit 1
        fi
        if [[ "$placed" == true ]]; then
            chmod -R u+rwX "$gradle_files" "$toolchain_files" "$konan_files" "$binaryen_mirror" 2> /dev/null || true
            rm -rf "$gradle_files" "$toolchain_files" "$konan_files" "$binaryen_mirror"
        fi
        echo "::warning::The release dependencies' zip is refused, and nothing of it is used: $*. The build fetches every dependency."
        exit 0
    }
    set -E
    trap 'refuse "a step of unpack failed (line $LINENO)"' ERR
    unzip -Z1 "$zip" > "$work/names" 2> "$work/error" || refuse "unzip can't list it ($(head -n 1 "$work/error"))"
    [[ -s "$work/names" ]] || refuse "it holds nothing"
    # Within what an entry holds (about 1.2 GB in some 300 files): a bigger zip fills the runner's disk.
    entries=$(wc -l < "$work/names" | tr -d ' ')
    (( entries <= max_entries )) || refuse "it holds $entries entries, more than $max_entries"
    size=$(unzip -Zt "$zip" | awk '{ print $3 }')
    [[ "$size" =~ ^[0-9]+$ ]] || refuse "unzip doesn't give its size"
    (( size <= max_bytes )) || refuse "it unpacks to $size bytes, more than $max_bytes"
    if bad=$(grep -m 1 -vE "$allowed" "$work/names"); then
        refuse "it holds $bad, which isn't a path stage makes"
    fi
    if grep -qE '(^|/)\.\.?(/|$)' "$work/names"; then
        refuse "a name has a . or .. part"
    fi
    # Each name once, in any case: unzip asks whether to replace a file it has already written (on macOS, of a name
    # that differs only in case too), and what it is told decides which of the two is used.
    if bad=$(sort -f "$work/names" | uniq -di | head -n 1); [[ -n "$bad" ]]; then
        refuse "it holds $bad twice"
    fi
    # The type of each entry, from its Unix mode where the zip has one: only files (-) and directories (d).
    if bad=$(unzip -Z -l "$zip" | awk 'NR > 2 && /^[^ ]{10} / && $1 !~ /^[-d]/ { print $NF; exit }'); [[ -n "$bad" ]]; then
        refuse "$bad isn't a plain file or directory"
    fi
    mkdir "$work/out"
    # Unpacked by Python, not unzip: unzip writes as much as an entry's data holds, whatever size the zip declares for
    # it, so a zip of a few MB declaring a few bytes would fill the disk. Python's zipfile writes no more than each
    # entry's declared size (and fails on its CRC where the data is other than that), and the sizes it reads are
    # within the limit together. It writes only the names checked above, each once (in any case, on macOS), as plain
    # files and directories of the owner's modes, whatever modes the entries name, so every check below sees every
    # file, and everything can be moved and removed.
    python3 -I - "$zip" "$work/out" "$work/names" "$max_bytes" "$max_entries" 2> "$work/error" <<'PY' || refuse "it can't be unpacked ($(tail -n 1 "$work/error"))"
import os, shutil, sys, zipfile
zip_path, out, names_file, max_bytes, max_entries = sys.argv[1], sys.argv[2], sys.argv[3], int(sys.argv[4]), int(sys.argv[5])
with open(names_file, "rb") as f:
    listed = set(f.read().decode("latin-1").splitlines())
with zipfile.ZipFile(zip_path) as z:
    infos = z.infolist()
    if len(infos) > max_entries:
        sys.exit(f"it holds {len(infos)} entries, more than {max_entries}")
    total = sum(info.file_size for info in infos)
    if total > max_bytes:
        sys.exit(f"it declares {total} bytes, more than {max_bytes}")
    for info in infos:
        if info.filename not in listed:
            sys.exit(f"{info.filename!r} isn't a name unzip listed")
        path = os.path.join(out, *info.filename.rstrip("/").split("/"))
        if info.is_dir():
            os.makedirs(path, exist_ok=True)
            continue
        os.makedirs(os.path.dirname(path), exist_ok=True)
        with z.open(info) as source, open(path, "xb") as target:
            shutil.copyfileobj(source, target, 1 << 20)
PY
    # As unpacked: still nothing but files and directories, so nothing below is a link.
    if bad=$(find "$work/out" ! -type f ! -type d -print -quit); [[ -n "$bad" ]]; then
        refuse "${bad#"$work/out/"} isn't a plain file or directory"
    fi
    used=$(du -sk "$work/out" | cut -f1)
    (( used * 1024 <= max_bytes )) || refuse "it unpacked to ${used} KB, more than $max_bytes bytes"
    # Gradle's files: each kept only where its SHA-1 is the directory it sits in (Gradle's own layout, which names it
    # without leading zeros) and its SHA-256 one gradle/verification-metadata.xml pins for that file of that component.
    # Gradle takes a file it finds at its SHA-1's path without checking it, and strict verification then fails the
    # build, so a file of other bytes there would fail every release that read the entry; dropped, Gradle fetches it.
    if [[ -d "$work/out/gradle" ]]; then
        python3 -I - "$work/out/gradle" "$pins/verification-metadata.xml" <<'PY' || refuse "its Gradle files can't be checked"
import hashlib, os, sys
import xml.etree.ElementTree as ET
root, metadata = sys.argv[1], sys.argv[2]
ns = "{https://schema.gradle.org/dependency-verification}"
pinned = {}
for component in ET.parse(metadata).getroot().iter(ns + "component"):
    for artifact in component.iter(ns + "artifact"):
        key = (component.get("group"), component.get("name"), component.get("version"), artifact.get("name"))
        pinned.setdefault(key, set()).update(s.get("value") for s in artifact.iter(ns + "sha256"))
kept = dropped = 0
example = None
for directory, _, files in os.walk(root):
    for name in files:
        path = os.path.join(directory, name)
        parts = os.path.relpath(path, root).split(os.sep)
        ok = len(parts) == 5
        if ok:
            group, module, version, sha1_dir, _ = parts
            sha1, sha256 = hashlib.sha1(), hashlib.sha256()
            with open(path, "rb") as f:
                for block in iter(lambda: f.read(1 << 20), b""):
                    sha1.update(block)
                    sha256.update(block)
            ok = (sha1.hexdigest().lstrip("0") == sha1_dir.lstrip("0")
                  and sha256.hexdigest() in pinned.get((group, module, version, name), ()))
        if ok:
            kept += 1
        else:
            os.remove(path)
            dropped += 1
            example = example or os.path.relpath(path, root)
for directory, _, _ in sorted(os.walk(root), reverse=True):
    if directory != root and not os.listdir(directory):
        os.rmdir(directory)
if dropped:
    print(f"::warning::{dropped} of the release dependencies' Gradle files aren't the bytes gradle/verification-metadata.xml pins (such as {example}), so the build fetches them.")
print(f"{kept} of Gradle's files are the ones gradle/verification-metadata.xml pins.")
PY
    fi
    # The toolchain and Kotlin/Native archives: each kept only where its name and SHA-256 are ones pinned (the JDK's
    # archive as .github/actions/pinned-toolchain names it, for a platform gradle/pinned-jdk.properties pins; the Gradle
    # zip gradle/wrapper/gradle-wrapper.properties names; an archive gradle/kotlin-native-dependencies.txt names). Each
    # is checked again where it is used, and fetched where it isn't kept: dropped here, a file of other bytes, or that
    # nothing uses, doesn't sit on the runner's disk through the build.
    python3 -I - "$work/out" "$pins" <<'PY' || refuse "its toolchain and Kotlin/Native archives can't be checked"
import hashlib, os, sys
out, pins = sys.argv[1], sys.argv[2]
def properties(path):
    with open(path) as f:
        return dict(line.strip().split("=", 1) for line in f if "=" in line and not line.lstrip().startswith("#"))
pinned = {"toolchain": {}, "konan": {}}
jdk = properties(os.path.join(pins, "pinned-jdk.properties"))
for key, sha256 in jdk.items():
    if key.startswith("sha256."):
        platform = key[len("sha256."):]
        extension = "zip" if platform.startswith("windows") else "tar.gz"
        pinned["toolchain"][f"temurin-{jdk['version']}-{platform}.{extension}"] = sha256
wrapper = properties(os.path.join(pins, "wrapper", "gradle-wrapper.properties"))
pinned["toolchain"][wrapper["distributionUrl"].replace("\\:", ":").rsplit("/", 1)[1]] = wrapper["distributionSha256Sum"]
with open(os.path.join(pins, "kotlin-native-dependencies.txt")) as f:
    for line in f:
        fields = line.split()
        if fields and not fields[0].startswith("#"):
            pinned["konan"][fields[1].rsplit("/", 1)[1]] = fields[0]
dropped = 0
example = None
for part, names in pinned.items():
    directory = os.path.join(out, part)
    if not os.path.isdir(directory):
        continue
    for name in os.listdir(directory):
        path = os.path.join(directory, name)
        sha256 = hashlib.sha256()
        if name in names:
            with open(path, "rb") as f:
                for block in iter(lambda: f.read(1 << 20), b""):
                    sha256.update(block)
        if sha256.hexdigest() != names.get(name):
            os.remove(path)
            dropped += 1
            example = example or f"{part}/{name}"
if dropped:
    print(f"::warning::{dropped} of the release dependencies' toolchain and Kotlin/Native archives aren't ones pinned, by name and SHA-256 (such as {example}), so the build fetches what it uses of them.")
PY
    # Moved, not copied, into directories nothing else has put anything in yet.
    for target in "$gradle_files" "$toolchain_files" "$konan_files" "$binaryen_mirror"; do
        if [[ -e "$target" && -n "$(ls -A "$target")" ]]; then
            refuse "$target isn't empty, so unpack can't take it away whole where it fails"
        fi
    done
    placed=true
    for pair in "gradle:$gradle_files" "toolchain:$toolchain_files" "konan:$konan_files"; do
        from="$work/out/${pair%%:*}" target="${pair#*:}"
        [[ -d "$from" ]] || continue
        rm -rf "$target"
        mkdir -p "$(dirname "$target")"
        mv "$from" "$target"
    done
    # gradle/com.github.webassembly/binaryen/<v>/<sha1>/binaryen-<v>-<platform>.tar.gz, checked as all Gradle's files
    # are, as version_<v>/binaryen-version_<v>-<platform>.tar.gz, where <platform> is this runner's. Only this runner's:
    # a version_<v> without it would send Gradle to the mirror for an archive it doesn't hold.
    if [[ -n "$binaryen_platform" && -d "$gradle_files/com.github.webassembly/binaryen" ]]; then
        while IFS= read -r -d '' file; do
            # Apart: of two assignments in one command, a failure of the first isn't seen.
            v=$(basename "$(dirname "$(dirname "$file")")")
            name=$(basename "$file")
            [[ "$name" == "binaryen-$v-$binaryen_platform.tar.gz" ]] || continue
            mkdir -p "$binaryen_mirror/version_$v"
            cp "$file" "$binaryen_mirror/version_$v/binaryen-version_$v-$binaryen_platform.tar.gz"
        done < <(find "$gradle_files/com.github.webassembly/binaryen" -mindepth 3 -maxdepth 3 -type f -name 'binaryen-*.tar.gz' -print0)
    fi
    trap - ERR
    count() { find "$1" -type f 2> /dev/null | wc -l | tr -d ' '; }
    echo "Unpacked $(count "$gradle_files") of Gradle's files, checked, and $(count "$toolchain_files") toolchain archives and $(count "$konan_files") of Kotlin/Native's archives, each checked, and checked again as it is used."
    ;;
*)
    echo "compiler-release-dependencies.sh stage <dir> | unpack <zip>" >&2
    exit 2
    ;;
esac
