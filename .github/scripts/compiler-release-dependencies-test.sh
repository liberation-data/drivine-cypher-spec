#!/usr/bin/env bash
# compiler-release-dependencies.sh's and compiler-konan-dependencies.sh's own test, over made-up zips and dependencies,
# in a made-up home. stage must copy a home's dependencies into the layout unpack takes, and fail on a name or a link
# unpack would refuse. unpack must put a zip of stage's paths where the build looks for them (and lay out binaryen's
# archives as its releases are), and must refuse whole, putting nothing anywhere, a zip with a name that climbs out, an
# absolute name, a name at a path stage doesn't make, a name twice, a link, or that isn't a zip. check must pass the
# dependencies the list pins, and fail one it doesn't, or a list that pins nothing. Run by compiler-release.yml's dry
# run, in build, on macOS as build unpacks there.
#   compiler-release-dependencies-test.sh
set -euo pipefail
here=$(cd "$(dirname "$0")" && pwd)
work=$(mktemp -d "${RUNNER_TEMP:-${TMPDIR:-/tmp}}/compiler-release-dependencies-test.XXXXXX")
trap 'rm -rf "$work"' EXIT
fail() { echo "compiler-release-dependencies-test: $*" >&2; exit 1; }

jar=gradle/org.example/lib/1.0/0123456789abcdef0123456789abcdef01234567/lib-1.0.jar
binaryen=gradle/com.github.webassembly/binaryen/123/89abcdef0123456789abcdef0123456789abcdef/binaryen-123-arm64-macos.tar.gz
good=("$jar" "$binaryen" toolchain/gradle-9.6.1-bin.zip konan/llvm-19-aarch64-macos-essentials-79.tar.gz)

# A zip of <entry>s: <path> a file of a few bytes, <path>/ a directory, link|<path>|<target> a link.
zipped() {
    local out="$1"; shift
    python3 -I - "$out" "$@" <<'PY'
import sys, zipfile
out, entries = sys.argv[1], sys.argv[2:]
with zipfile.ZipFile(out, "w") as z:
    for entry in entries:
        kind, _, rest = entry.partition("|")
        if kind == "link":
            path, target = rest.split("|")
            info = zipfile.ZipInfo(path)
            info.create_system = 3
            info.external_attr = 0o120777 << 16
            z.writestr(info, target)
        else:
            info = zipfile.ZipInfo(entry)
            info.create_system = 3
            info.external_attr = (0o40755 if entry.endswith("/") else 0o100644) << 16
            z.writestr(info, "" if entry.endswith("/") else "a dependency\n")
PY
}

# A zip of <dir>'s files, named from <dir>, as upload-artifact zips a directory.
zipdir() {
    python3 -I - "$1" "$2" <<'PY2'
import os, sys, zipfile
out, root = sys.argv[1], sys.argv[2]
with zipfile.ZipFile(out, "w") as z:
    for top, _, files in os.walk(root):
        for f in files:
            path = os.path.join(top, f)
            z.write(path, os.path.relpath(path, root))
PY2
}

# unpack <zip> in a home of its own: its output, and what it put in the home.
unpacked() {
    local zip="$1" home="$work/home-${RANDOM}${RANDOM}"
    mkdir "$home"
    HOME="$home" "$here/compiler-release-dependencies.sh" unpack "$zip" > "$home.out" 2>&1 || fail "unpack of $zip exited $?: $(cat "$home.out")"
    UNPACK_OUT=$(cat "$home.out")
    UNPACKED=$(cd "$home" && find . -type f | sort)
}

zipped "$work/good.zip" gradle/ "${good[@]}"
unpacked "$work/good.zip"
for want in ./.gradle/caches/modules-2/files-2.1/${jar#gradle/} ./.gradle/caches/modules-2/files-2.1/${binaryen#gradle/} \
    ./.cache/drivine-pinned-toolchain/gradle-9.6.1-bin.zip ./.cache/drivine-konan-dependencies/llvm-19-aarch64-macos-essentials-79.tar.gz \
    ./.cache/drivine-binaryen/version_123/binaryen-version_123-arm64-macos.tar.gz; do
    grep -qxF "$want" <<< "$UNPACKED" || fail "unpack of stage's paths didn't put $want: $UNPACKED"
done
(( $(wc -l <<< "$UNPACKED") == 5 )) || fail "unpack put more than stage's paths: $UNPACKED"
echo "unpack puts stage's paths, and binaryen's archive as its releases are"
zipped "$work/no-binaryen.zip" "$jar"
unpacked "$work/no-binaryen.zip"
[[ "$UNPACKED" == "./.gradle/caches/modules-2/files-2.1/${jar#gradle/}" ]] || fail "unpack of a zip without binaryen put $UNPACKED"
echo "unpack takes a zip without binaryen, or without toolchain or Kotlin/Native archives"

# stage, from a made-up home: Gradle's files, the toolchain archives, and Kotlin/Native's archives but nothing else of
# its, into what unpack takes whole.
home="$work/stage-home"
jardir="$home/.gradle/caches/modules-2/files-2.1/$(dirname "${jar#gradle/}")"
mkdir -p "$jardir" "$home/.cache/drivine-pinned-toolchain" "$home/.cache/drivine-konan-dependencies"
echo jar > "$jardir/${jar##*/}"
echo zip > "$home/.cache/drivine-pinned-toolchain/gradle-9.6.1-bin.zip"
echo llvm > "$home/.cache/drivine-konan-dependencies/llvm-19-aarch64-macos-essentials-79.tar.gz"
echo partial > "$home/.cache/drivine-konan-dependencies/llvm.part"
HOME="$home" "$here/compiler-release-dependencies.sh" stage "$work/staged" > "$work/stage.out" 2>&1 || fail "stage exited $?: $(cat "$work/stage.out")"
staged=$(cd "$work/staged" && find . -type f | sort)
[[ "$staged" == "$(printf '%s\n' ./${jar} ./konan/llvm-19-aarch64-macos-essentials-79.tar.gz ./toolchain/gradle-9.6.1-bin.zip | sort)" ]] || fail "stage put $staged"
zipdir "$work/staged.zip" "$work/staged"
unpacked "$work/staged.zip"
(( $(wc -l <<< "$UNPACKED") == 3 )) || fail "unpack of stage's zip put $UNPACKED: $UNPACK_OUT"
echo "stage copies the dependencies, and nothing else, into what unpack takes"
echo odd > "$jardir/lib 1.0.jar"
if HOME="$home" "$here/compiler-release-dependencies.sh" stage "$work/staged-odd" > "$work/stage.out" 2>&1; then
    fail "stage took a name unpack refuses"
fi
grep -q "::error::.*lib 1.0.jar isn't a name unpack takes" "$work/stage.out" || fail "stage didn't name the file: $(cat "$work/stage.out")"
rm "$jardir/lib 1.0.jar"
ln -s "$home/.cache" "$jardir/lib-1.1.jar"
if HOME="$home" "$here/compiler-release-dependencies.sh" stage "$work/staged-link" > "$work/stage.out" 2>&1; then
    fail "stage took a link"
fi
grep -q "::error::.*lib-1.1.jar isn't a plain file" "$work/stage.out" || fail "stage didn't name the link: $(cat "$work/stage.out")"
echo "stage fails on a name, or a link, unpack would refuse"

refused() {
    local what="$1" zip="$2" expect="$3"
    unpacked "$zip"
    [[ "$UNPACK_OUT" == *"::warning::The release dependencies' zip is refused"*"$expect"* ]] || fail "$what: not refused for \"$expect\": $UNPACK_OUT"
    [[ -z "$UNPACKED" ]] || fail "$what: refused, but put $UNPACKED"
    echo "refused: $what"
}
zipped "$work/climbs.zip" "${good[@]}" gradle/org.example/lib/1.0/0123456789abcdef0123456789abcdef01234567/../../../../../../.bashrc
refused "a name that climbs out" "$work/climbs.zip" "isn't a path stage makes"
zipped "$work/dot.zip" "${good[@]}" gradle/org.example/lib/1.0/0123456789abcdef0123456789abcdef01234567/.
refused "a . part" "$work/dot.zip" "isn't a path stage makes"
zipped "$work/absolute.zip" "${good[@]}" /tmp/compiler-release-dependencies-test
refused "an absolute name" "$work/absolute.zip" "isn't a path stage makes"
zipped "$work/init.zip" "${good[@]}" init.d/evil.gradle
refused "a path outside stage's" "$work/init.zip" "init.d/evil.gradle"
zipped "$work/shallow.zip" "${good[@]}" gradle/lib-1.0.jar
refused "a file of Gradle's at a path Gradle doesn't make" "$work/shallow.zip" "gradle/lib-1.0.jar"
zipped "$work/konan.zip" "${good[@]}" konan/llvm.sh
refused "a Kotlin/Native file that isn't an archive" "$work/konan.zip" "konan/llvm.sh"
zipped "$work/link.zip" "${good[@]}" "link|gradle/org.example/lib/1.0/0123456789abcdef0123456789abcdef01234567/lib-1.1.jar|$HOME/.bashrc"
refused "a link" "$work/link.zip" "isn't a plain file or directory"
python3 -I - "$work/twice.zip" "$jar" <<'PY2'
import sys, warnings, zipfile
warnings.simplefilter("ignore")
with zipfile.ZipFile(sys.argv[1], "w") as z:
    z.writestr(sys.argv[2], "the dependency")
    z.writestr(sys.argv[2], "another")
PY2
refused "a name twice" "$work/twice.zip" "twice"
zipped "$work/newline.zip" "${good[@]}" "$(printf '%s\n%s' "$jar" "${jar%.jar}-2.jar")"
refused "a name with a newline" "$work/newline.zip" "isn't a path stage makes"
zipped "$work/empty.zip"
refused "an empty zip" "$work/empty.zip" "nothing of it is used"
echo "not a zip" > "$work/not.zip"
refused "a file that isn't a zip" "$work/not.zip" "unzip can't list it"

# check: the dependencies Kotlin/Native unpacked, against a list of two.
list="$work/pins.txt"
{
    echo "# made up"
    echo "$(printf 'a%.0s' {1..64}) https://download.example/native/llvm-19-aarch64-macos-essentials-79.tar.gz"
    echo "$(printf 'b%.0s' {1..64}) https://download.example/native/libffi-3.3-1-macos-arm64.tar.gz"
} > "$list"
konan="$work/konan"
mkdir -p "$konan/dependencies/cache" "$konan/dependencies/llvm-19-aarch64-macos-essentials-79"
touch "$konan/dependencies/.extracted" "$konan/dependencies/cache/.lock"
KONAN_DATA_DIR="$konan" "$here/compiler-konan-dependencies.sh" check "$list" > "$work/check.out" 2>&1 || fail "check refused a pinned dependency: $(cat "$work/check.out")"
echo "check passes a pinned dependency"
mkdir "$konan/dependencies/lldb-4-macos"
if KONAN_DATA_DIR="$konan" "$here/compiler-konan-dependencies.sh" check "$list" > "$work/check.out" 2>&1; then
    fail "check passed a dependency the list doesn't pin"
fi
grep -q 'unpacked lldb-4-macos' "$work/check.out" || fail "check didn't name the dependency it refused: $(cat "$work/check.out")"
echo "check fails a dependency the list doesn't pin"
echo "$(printf 'c%.0s' {1..63}) https://download.example/native/x.tar.gz" >> "$list"
if KONAN_DATA_DIR="$konan" "$here/compiler-konan-dependencies.sh" check "$list" > "$work/check.out" 2>&1; then
    fail "check took a list with a malformed line"
fi
echo "check refuses a malformed list"
echo "# nothing" > "$list"
if KONAN_DATA_DIR="$konan" "$here/compiler-konan-dependencies.sh" check "$list" > "$work/check.out" 2>&1; then
    fail "check took a list that pins nothing"
fi
grep -q 'pins nothing' "$work/check.out" || fail "check didn't say the list pins nothing: $(cat "$work/check.out")"
echo "check refuses a list that pins nothing"
rm -rf "$konan/dependencies/lldb-4-macos"
printf '%s https://download.example/native/llvm-19-aarch64-macos-essentials-79.tar.gz' "$(printf 'a%.0s' {1..64})" > "$list"
KONAN_DATA_DIR="$konan" "$here/compiler-konan-dependencies.sh" check "$list" > "$work/check.out" 2>&1 || fail "check didn't read a last line without a newline: $(cat "$work/check.out")"
echo "check reads a last line without a newline"
