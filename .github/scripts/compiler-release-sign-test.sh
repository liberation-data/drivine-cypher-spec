#!/usr/bin/env bash
# Tests that compiler-release-sign.sh refuses a bundle it must not sign, made from build's unsigned bundle with one thing
# added: a link among thousands (which once got past its check, find killed by SIGPIPE under pipefail), a file no
# artifact's publication makes, a version other than the one released, and a path outside org/drivine. Each must be
# refused, and leave no signed bundle. Run by compiler-release.yml's dry run, with its throwaway key in SIGNING_KEY and
# SIGNING_PASSWORD, on Linux, whose unzip makes the links a zip holds.
#   compiler-release-sign-test.sh <unsigned bundle zip> <version>
set -euo pipefail
unsigned="$1" version="$2"
here=$(cd "$(dirname "$0")" && pwd)
work=$(mktemp -d "${RUNNER_TEMP:-${TMPDIR:-/tmp}}/compiler-sign-test.XXXXXX")
trap 'rm -rf "$work"' EXIT

# A copy of the bundle with entries added: <kind> <path> [<target>], a file of a few bytes, or a link to <target>.
made() {
    local out="$1"; shift
    python3 -I - "$unsigned" "$out" "$@" <<'PY'
import sys, zipfile
src, out, entries = sys.argv[1], sys.argv[2], sys.argv[3:]
with zipfile.ZipFile(src) as zin, zipfile.ZipFile(out, "w", zipfile.ZIP_DEFLATED) as zout:
    for item in zin.infolist():
        zout.writestr(item, zin.read(item.filename))
    for entry in entries:
        kind, path, *rest = entry.split("|")
        info = zipfile.ZipInfo(path)
        if kind == "link":
            info.create_system = 3
            info.external_attr = (0o120777 << 16)
            zout.writestr(info, rest[0])
        else:
            zout.writestr(info, "not the compiler's\n")
PY
}

failures=0
refused() {
    local name="$1" bundle="$2" expect="$3"
    local log="$bundle.log"
    rm -f "$work/signed.zip"
    if "$here/compiler-release-sign.sh" "$bundle" "$version" "$work/signed.zip" "$bundle.out" > "$log" 2>&1; then
        echo "::error::$name: signed, where it must be refused."; failures=$((failures + 1)); return
    fi
    if [[ -e "$work/signed.zip" ]]; then
        echo "::error::$name: refused, but left a signed bundle."; failures=$((failures + 1)); return
    fi
    if ! grep -qF -- "$expect" "$log"; then
        echo "::error::$name: refused, but not for what it holds: $(tail -c 500 "$log")"; failures=$((failures + 1)); return
    fi
    echo "$name: refused."
}

dir="org/drivine/drivine-cypher-compiler-jvm/$version"
links=()
for i in $(seq 1 5000); do links+=("link|$dir/x$i.pom|drivine-cypher-compiler-jvm-$version.pom"); done
made "$work/links.zip" "${links[@]}" "link|$dir/leak.sha1|../../../../../gnupg/pubring.kbx"
refused "Thousands of links" "$work/links.zip" "neither a file nor a directory"

made "$work/extra.zip" "file|$dir/notes.txt"
refused "A file no publication makes" "$work/extra.zip" "what no artifact's publication"

made "$work/version.zip" "file|org/drivine/drivine-cypher-compiler-jvm/9.9.9/drivine-cypher-compiler-jvm-9.9.9.pom"
refused "Another version" "$work/version.zip" "what no artifact's publication"

made "$work/outside.zip" "file|org/other/x.pom"
refused "A path outside org/drivine" "$work/outside.zip" "what no artifact's publication"

made "$work/top.zip" "file|leak.txt"
refused "A file at the top" "$work/top.zip" "what no artifact's publication"

(( failures == 0 )) || { echo "::error::$failures of the sign script's refusals failed."; exit 1; }
echo "The sign script refused each bundle it must not sign."
