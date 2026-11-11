#!/usr/bin/env bash
# cli-cases-check.py's own test: made-up cases that break each of its rules are refused, each with its own message, and
# the spec's own cli/cli.yaml passes. No CLI and no store.
#
#   .github/scripts/cli-cases-check-test.sh
set -euo pipefail

here="$(cd "$(dirname "$0")" && pwd)"
check="$here/cli-cases-check.py"
readme="$here/../../cli/README.md"
root="$(mktemp -d)"
trap 'rm -rf "$root"' EXIT
fail() { echo "cli-cases-check-test: $*" >&2; exit 1; }

# A case the README allows, to vary.
ok='  - name: "a"
    args: ["--format", "json", "RETURN 1 AS n"]
    stdout: "{\"columns\":[\"n\"],\"rows\":[[1]]}\n"
    exit: 0'

passes() {
    local what="$1" text="$2" out
    printf '%s\n' "$text" > "$root/cases.yaml"
    out="$(python3 -I "$check" "$root/cases.yaml" "$readme" 2>&1)" || fail "$what was refused: $out"
}
refused() {
    local what="$1" text="$2" expect="$3" out
    printf '%s\n' "$text" > "$root/cases.yaml"
    if out="$(python3 -I "$check" "$root/cases.yaml" "$readme" 2>&1)"; then fail "$what passed"; fi
    grep -qF -- "$expect" <<<"$out" || fail "$what: not its failure: $out"
}

passes "a case the README allows" "cases:
$ok"
passes "a plain case whose stdout isn't JSON" 'cases:
  - name: "p"
    args: ["--format", "plain", "RETURN 1 AS n"]
    stdout: "n\n1\n"
    exit: 0'
passes "a json case that fails, with nothing on stdout" 'cases:
  - name: "f"
    args: ["--format", "json", "RETURN 1/0"]
    stdout: ""
    exit: 1'

refused "a file that isn't YAML" "cases: [" "isn't YAML"
refused "a file of another shape" "- name: a" "whose one key is \`cases\`"
refused "a key besides cases" "cases: []
other: 1" "whose one key is \`cases\`"
refused "a case with no name" 'cases:
  - args: []
    exit: 0' "has no name"
refused "two cases of one name" "cases:
$ok
$ok" "its name is another case's too"
refused "a key the README doesn't name" "cases:
$ok
    expected: 1" "\`expected\` isn't a key the README's table names"
refused "args that aren't a list of texts" 'cases:
  - name: "a"
    args: "RETURN 1"
    exit: 0' "\`args\` is a list of texts"
refused "an exit that isn't a whole number" 'cases:
  - name: "a"
    args: []
    exit: "0"' "\`exit\` is a whole number"
refused "a store the README doesn't name" "cases:
$ok
    only: [neptune]" "\`only\` names 'neptune'"
refused "except that isn't a list" "cases:
$ok
    except: sql" "\`except\` is a list"
refused "an operating system the README doesn't name" "cases:
$ok
    os: [freebsd]" "\`os\` names 'freebsd'"
refused "a json line that isn't JSON" 'cases:
  - name: "a"
    args: ["--format", "json", "RETURN 1 AS n"]
    stdout: "{\"columns\":[\"n\"],\"rows\":[[1]]}\n{\"columns\":\n"
    exit: 0' "line 2 of its json stdout isn't JSON"
refused "json's stdout with no last line break" 'cases:
  - name: "a"
    args: ["--format", "json", "RETURN 1 AS n"]
    stdout: "{\"columns\":[\"n\"],\"rows\":[[1]]}"
    exit: 0' "json's stdout ends in a line break"

# The README's table is where the keys come from: one without the section is refused, not passed.
printf '# No cases here\n' > "$root/README.md"
printf '%s\n' "cases:
$ok" > "$root/cases.yaml"
if out="$(python3 -I "$check" "$root/cases.yaml" "$root/README.md" 2>&1)"; then fail "a README with no Cases section passed"; fi
grep -qF 'no "## Cases" section' <<<"$out" || fail "a README with no Cases section: not its failure: $out"

out="$(python3 -I "$check" "$here/../../cli/cli.yaml" "$readme")" || fail "the spec's own cli.yaml was refused: $out"
echo "cli-cases-check-test: passed. $out"
