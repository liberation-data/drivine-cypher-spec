#!/usr/bin/env bash
# One archive of .github/actions/pinned-toolchain: as kept at <file>, or where none is kept (or not this one) and <fetch>
# is true, fetched from <url>, with retries over a few minutes, as GitHub's release downloads fail on and off in an
# outage. Either way its SHA-256 is <sha>, or it is refused. Writes fetched=true to the step's output where it fetched.
#   archive.sh <url> <sha> <file> <fetch>
set -euo pipefail
url="$1" want="$2" file="$3" fetch="$4"
name=$(basename "$file")
sha() { shasum -a 256 "$1" | cut -d' ' -f1; }
if [[ -f "$file" && "$(sha "$file")" == "$want" ]]; then
    echo "$name: kept, SHA-256 $want."
    exit 0
fi
if [[ "$fetch" != true ]]; then
    echo "::error::The artifact holds no $name with SHA-256 $want."
    exit 1
fi
rm -f "$file"
mkdir -p "$(dirname "$file")"
if ! curl -fsSL --retry 8 --retry-all-errors --retry-delay 20 --connect-timeout 20 --max-time 600 -o "$file" "$url"; then
    rm -f "$file"
    echo "::error::Couldn't fetch $url, nor was $name cached. It is served from GitHub's release downloads: re-run this job once those work."
    exit 1
fi
got=$(sha "$file")
if [[ "$got" != "$want" ]]; then
    rm -f "$file"
    echo "::error::$url has SHA-256 $got, not $want as pinned."
    exit 1
fi
echo "$name: fetched, SHA-256 $want."
echo "fetched=true" >> "${GITHUB_OUTPUT:-/dev/null}"
