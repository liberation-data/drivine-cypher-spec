#!/usr/bin/env bash
# Signs the compiler's Central Portal bundle that compiler-release.yml's build job made and checked, unsigned, so that what
# is uploaded is that bundle, byte for byte, with a signature beside each file: no JDK, Gradle or dependency is fetched
# after the release's gate. Checks the bundle with compiler-release-check.sh first, before the key is imported: it holds
# every artifact at the version and nothing else, no link (zip would follow one into the keyring or the environment), no
# file its publications don't make, nothing outside org/drivine. Then checks each file's checksums the bundle holds,
# signs each file Maven Central asks a signature of, checks each signature against the key, and checks the signed bundle
# again. The key's variables are unset before anything else is run, so no process this starts is given them.
#   compiler-release-sign.sh <unsigned bundle zip> <version> <signed bundle zip> <check out dir>
# The key is SIGNING_KEY, an ASCII-armored private key, with its passphrase in SIGNING_PASSWORD: the release key in the
# publish job, a throwaway key in a dry run. It is imported into a keyring made for this run alone and deleted after,
# and neither it nor its passphrase is written to the log or to a file, nor passed on a command line.
set -euo pipefail
unsigned="$1" version="$2" signed="$3" out="$4"
fail() { echo "::error::$*"; exit 1; }
[[ "${SIGNING_KEY:-}" == *'BEGIN PGP PRIVATE KEY BLOCK'* ]] || fail "SIGNING_KEY isn't an ASCII-armored private key."
[[ -n "${SIGNING_PASSWORD:-}" ]] || fail "SIGNING_PASSWORD isn't set."
# Out of the environment from here on, so no process this script starts (the check, unzip, zip, gpg) is given the key or
# its passphrase: each is kept in a variable of this shell alone, for gpg's stdin and fd 3.
key="$SIGNING_KEY" passphrase="$SIGNING_PASSWORD"
unset SIGNING_KEY SIGNING_PASSWORD
signed=$(cd "$(dirname "$signed")" && pwd)/$(basename "$signed")

work=$(mktemp -d "${RUNNER_TEMP:-${TMPDIR:-/tmp}}/compiler-sign.XXXXXX")
# The bundle is build's, and nothing else, before the key is so much as imported: every artifact at the version, no link,
# no file its publications don't make, nothing outside org/drivine (see compiler-release-check.sh).
GITHUB_STEP_SUMMARY=/dev/null "$(dirname "$0")/compiler-release-check.sh" "$unsigned" "$version" none "$work/unsigned" ||
    { rm -rf "$work"; exit 1; }
export GNUPGHOME="$work/gnupg"
mkdir -m 700 "$GNUPGHOME"
cleanup() {
    gpgconf --kill gpg-agent 2> /dev/null || true
    rm -rf "$work"
}
trap cleanup EXIT

gpg --batch --quiet --pinentry-mode loopback --passphrase-fd 3 --import 3<<< "$passphrase" <<< "$key" 2> /dev/null ||
    fail "SIGNING_KEY couldn't be imported."
fingerprint=$(gpg --batch --with-colons --list-secret-keys | awk -F: '$1 == "fpr" { print $10; exit }')
[[ -n "$fingerprint" ]] || fail "SIGNING_KEY holds no secret key."

repo="$work/bundle"
mkdir "$repo"
unzip -q "$unsigned" -d "$repo"
cd "$repo"
# A link would be skipped below but followed by zip, putting what it names (this keyring, the step's environment) in the
# bundle. Asked of find alone, which stops at the first: piped to grep -q, find killed by SIGPIPE fails the pipeline under
# pipefail where grep matched, and a bundle of many links got past.
[[ -z "$(find . ! -type f ! -type d -print -quit)" ]] || fail "$unsigned holds an entry that is neither a file nor a directory, such as a link."
files=$(find . -type f ! -name '*.md5' ! -name '*.sha1' ! -name '*.sha256' ! -name '*.sha512' ! -name '*.asc' | sort)
[[ -n "$files" ]] || fail "$unsigned holds no file to sign."
[[ -z "$(find . -name '*.asc' -print -quit)" ]] || fail "$unsigned is already signed: build makes it unsigned."

# Each checksum the bundle holds is the file's, as the build wrote them.
digest() { case "$1" in md5) md5sum "$2" 2> /dev/null || md5 -r "$2" ;; *) shasum -a "${1#sha}" "$2" ;; esac | cut -d' ' -f1; }
while read -r f; do
    for kind in md5 sha1 sha256 sha512; do
        [[ -f "$f.$kind" ]] || fail "${f#./} has no .$kind."
        [[ "$(tr -d ' \n' < "$f.$kind")" == "$(digest "$kind" "$f")" ]] || fail "${f#./}'s .$kind isn't its checksum."
    done
done <<< "$files"

while read -r f; do
    gpg --batch --quiet --pinentry-mode loopback --passphrase-fd 3 --local-user "$fingerprint" --armor --detach-sign \
        --output "$f.asc" "$f" 3<<< "$passphrase" 2> /dev/null || fail "${f#./} couldn't be signed."
    gpg --batch --quiet --verify "$f.asc" "$f" 2> /dev/null || fail "${f#./}'s signature doesn't verify."
done <<< "$files"

rm -f "$signed"
# -y: a link, which there is none of, would be stored as one, never followed.
zip -q -X -y -r "$signed" org
cd - > /dev/null
# As the build's check, with this keyring's key: every artifact, each signed.
"$(dirname "$0")/compiler-release-check.sh" "$signed" "$version" verify "$out"
echo "Signed $(wc -l <<< "$files" | tr -d ' ') files with key ${fingerprint: -16}."
