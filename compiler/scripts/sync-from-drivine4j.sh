#!/usr/bin/env bash
# Merges drivine4j's compiler changes since the commit in compiler/DRIVINE4J into the module, up to <commit>.
#
#   compiler/scripts/sync-from-drivine4j.sh <commit>       # a drivine4j commit, such as origin/main's
#
# The module's sources were ported from drivine4j's (scripts/sync-paths.txt says which from which), with edits for
# common Kotlin: no java.*, no Jackson, the JVM's number text. So each changed file is merged three ways: drivine4j's file
# at the old commit is the base, the module's file is ours, and drivine4j's at <commit> is theirs. A change of drivine4j's
# that doesn't touch what the port edited merges cleanly; one that does is left with conflict markers to resolve.
# Before the merge, the base and theirs are put through the port's mechanical rewrites (scripts/port-rewrites.sed: the
# packages of antlr-kotlin, of the module's JSON tree and of bignum), so a line the port changed only by them merges.
# A file drivine4j adds under a mapped directory is copied in, rewritten so, to be made common Kotlin where it isn't.
#
# It then lists what to look at by hand: a file outside the mapping that the compiler's drivine4j sources import, directly
# or through each other, which changed; and a new test of the compiler's package that the mapping has no line for.
#
# DRIVINE4J_REPO names a local clone of drivine4j to read from, in place of a fresh clone of GitHub's.
#
# Then: resolve any conflicts, build and run every check (see compiler/README.md), and commit with DRIVINE4J.
#
# It refuses a drivine4j commit that builds against this module, or that has no compiler package: the sync ends where
# drivine4j switches to the module (drivine4j#662), and the module is the compiler's source from then on.
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
# The sync ends where drivine4j builds against this module (drivine4j#662): from then on the module is the compiler's one
# copy, its changes are made here, and drivine4j has none of the mapped files left to sync. A sync to such a commit would
# take drivine4j's deletion of each for a change, delete the module's files that still match it, and leave half a module.
# Such a commit is told by its gradle/cypher-compiler.properties, the pin drivine4j builds against, or by its having no
# compiler package at all.
if git -C "$repo" cat-file -e "$to:gradle/cypher-compiler.properties" 2>/dev/null; then
    echo "drivine4j $to builds against this module (gradle/cypher-compiler.properties pins it): the module is the compiler's source now, so there is nothing to sync from it. Make the change here, and move drivine4j's pin." >&2
    exit 1
fi
if [ -z "$(git -C "$repo" ls-tree "$to" -- src/main/kotlin/org/drivine/cypher/)" ]; then
    echo "drivine4j $to has no src/main/kotlin/org/drivine/cypher/: it doesn't hold the compiler, so a sync to it would delete the module's sources. The module is the compiler's source now." >&2
    exit 1
fi
git -C "$repo" cat-file -e "$base^{commit}" 2>/dev/null || { echo "drivine4j has no commit $base (compiler/DRIVINE4J)." >&2; exit 1; }
if [ "$to" = "$(git -C "$repo" rev-parse "$base^{commit}")" ]; then
    echo "The module is synced from drivine4j $to already."
    exit 0
fi

work="$(mktemp -d)"
rewrites="$module/scripts/port-rewrites.sed"

# drivine4j's file <path> at <commit>, with the port's mechanical rewrites where it is Kotlin.
show() {
    case "$2" in
        *.kt) git -C "$repo" show "$1:$2" | sed -E -f "$rewrites" ;;
        *) git -C "$repo" show "$1:$2" ;;
    esac
}
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
                if [ ! -f "$ours" ]; then
                    :
                elif show "$base" "$path" | cmp -s - "$ours"; then
                    rm "$ours"; deleted+=("${ours#"$module/"}")
                else
                    kept+=("${ours#"$module/"} (drivine4j deleted $path; the module's edited copy is kept)")
                fi
                ;;
            A)
                mkdir -p "$(dirname "$ours")"
                if [ -f "$ours" ]; then
                    : > "$work/base"
                    show "$to" "$path" > "$work/theirs"
                    if git merge-file -L module -L "drivine4j $base" -L "drivine4j $to" "$ours" "$work/base" "$work/theirs"; then clean=$((clean + 1)); else conflicted+=("${ours#"$module/"}"); fi
                else
                    show "$to" "$path" > "$ours"; added+=("${ours#"$module/"}")
                fi
                ;;
            *)
                show "$base" "$path" > "$work/base"
                show "$to" "$path" > "$work/theirs"
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

# What else to look at, worked out from drivine4j's imports: see scripts/sync-report.py.
python3 "$module/scripts/sync-report.py" "$repo" "$base" "$to" "$module"
[ ${#conflicted[@]} -eq 0 ]
