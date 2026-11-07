#!/usr/bin/env bash
# Merges drivine4j's compiler changes since the commit in compiler/DRIVINE4J into the module, up to <commit>.
#
#   compiler/scripts/sync-from-drivine4j.sh <commit>       # a drivine4j commit, such as origin/main's
#
# The module's sources were ported from drivine4j's (scripts/sync-paths.txt says which from which), with edits for
# common Kotlin: no java.*, no Jackson, the JVM's number text. So each changed file is merged three ways: drivine4j's file
# at the old commit is the base, the module's file is ours, and drivine4j's at <commit> is theirs. A change of drivine4j's
# that doesn't touch what the port edited merges cleanly; one that does is left with conflict markers to resolve.
# A file drivine4j adds under a mapped directory is copied in as it is, to be made common Kotlin where it isn't.
#
# DRIVINE4J_REPO names a local clone of drivine4j to read from, in place of a fresh clone of GitHub's.
#
# Then: resolve any conflicts, build and run every check (see compiler/README.md), and commit with DRIVINE4J.
set -euo pipefail

target="${1:?usage: compiler/scripts/sync-from-drivine4j.sh <drivine4j commit>}"
module="$(cd "$(dirname "$0")/.." && pwd)"
base="$(tr -d '[:space:]' < "$module/DRIVINE4J")"

if [ -n "${DRIVINE4J_REPO:-}" ]; then
    repo="$DRIVINE4J_REPO"
    git -C "$repo" fetch --quiet origin || true
else
    repo="$(mktemp -d)"
    trap 'rm -rf "$repo"' EXIT
    git clone --quiet --filter=blob:none --no-checkout https://github.com/liberation-data/drivine4j.git "$repo"
fi
to="$(git -C "$repo" rev-parse --verify "$target^{commit}")"
git -C "$repo" cat-file -e "$base^{commit}" 2>/dev/null || { echo "drivine4j has no commit $base (compiler/DRIVINE4J)." >&2; exit 1; }
if [ "$to" = "$(git -C "$repo" rev-parse "$base^{commit}")" ]; then
    echo "The module is synced from drivine4j $to already."
    exit 0
fi

work="$(mktemp -d)"
clean=0; conflicted=(); added=(); deleted=(); kept=()
mapped=()
while read -r from into; do
    case "$from" in ''|'#'*) continue ;; esac
    mapped+=("$from")
    # Each file under the drivine4j path that changed between the two commits, renames as a delete and an add.
    while IFS=$'\t' read -r status path; do
        [ -n "$path" ] || continue
        ours="$module/$into${path#"$from"}"
        case "$status" in
            D)
                if [ -f "$ours" ] && git -C "$repo" show "$base:$path" | cmp -s - "$ours"; then
                    rm "$ours"; deleted+=("${ours#"$module/"}")
                else
                    kept+=("${ours#"$module/"} (drivine4j deleted $path; the module's edited copy is kept)")
                fi
                ;;
            A)
                mkdir -p "$(dirname "$ours")"
                if [ -f "$ours" ]; then
                    : > "$work/base"
                    git -C "$repo" show "$to:$path" > "$work/theirs"
                    if git merge-file -L module -L "drivine4j $base" -L "drivine4j $to" "$ours" "$work/base" "$work/theirs"; then clean=$((clean + 1)); else conflicted+=("${ours#"$module/"}"); fi
                else
                    git -C "$repo" show "$to:$path" > "$ours"; added+=("${ours#"$module/"}")
                fi
                ;;
            *)
                git -C "$repo" show "$base:$path" > "$work/base"
                git -C "$repo" show "$to:$path" > "$work/theirs"
                if [ ! -f "$ours" ]; then
                    cp "$work/theirs" "$ours"; added+=("${ours#"$module/"}")
                elif git merge-file -L module -L "drivine4j $base" -L "drivine4j $to" "$ours" "$work/base" "$work/theirs"; then
                    clean=$((clean + 1))
                else
                    conflicted+=("${ours#"$module/"}")
                fi
                ;;
        esac
    done < <(git -C "$repo" diff --no-renames --name-status "$base" "$to" -- "$from")
done < "$module/scripts/sync-paths.txt"
rm -rf "$work"

echo "$to" > "$module/DRIVINE4J"
echo "Merged drivine4j $base..$to into the module: $clean files merged cleanly."
[ ${#added[@]} -eq 0 ] || printf '  added, to make common Kotlin where it is not: %s\n' "${added[@]}"
[ ${#deleted[@]} -eq 0 ] || printf '  deleted: %s\n' "${deleted[@]}"
[ ${#kept[@]} -eq 0 ] || printf '  kept: %s\n' "${kept[@]}"
[ ${#conflicted[@]} -eq 0 ] || printf '  CONFLICTS, to resolve: %s\n' "${conflicted[@]}"

# What else changed in drivine4j's sources the compiler may call: a helper of the backends, a schema spec.
others="$(git -C "$repo" diff --name-only "$base" "$to" -- src/main/kotlin/org/drivine/backend src/main/kotlin/org/drivine/schema src/main/kotlin/org/drivine/model src/test/kotlin/org/drivine/cypher \
    | grep -v -x -F -f <(printf '%s\n' "${mapped[@]}") | grep -v -F -f <(printf '%s\n' "${mapped[@]}" | grep '/$') || true)"
[ -z "$others" ] || { echo "  changed in drivine4j outside the mapped files, which the compiler may need:"; echo "$others" | sed 's/^/    /'; }
[ ${#conflicted[@]} -eq 0 ]
