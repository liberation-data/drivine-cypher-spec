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

passes "a json case whose flag prints something else" 'cases:
  - name: "v"
    args: ["--format", "json", "--version"]
    stdout: "drivine 1.0.0\n"
    exit: 0'
passes "a json case whose stdin prints its parameters" 'cases:
  - name: "q"
    args: ["--format", "json"]
    stdin: ":param a => 1\n:params\n"
    stdout: "{\n  a: 1\n}\n"
    exit: 0'
passes "a json case whose -c prints its parameters" 'cases:
  - name: "c"
    args: ["--format", "json", "-c", ":params"]
    stdout: "{\n}\n"
    exit: 0'
passes "a json case whose file prints the commands" 'cases:
  - name: "h"
    args: ["--format", "json", "-f", "a.cypher"]
    files: {a.cypher: {text: "RETURN 1;\n:help\n"}}
    stdout: "{\"columns\":[\"1\"],\"rows\":[[1]]}\nAvailable commands:\n"
    exit: 0'

refused "a file that isn't YAML" "cases: [" "isn't YAML"
refused "YAML nested too deep to read" "cases: $(python3 -c 'print("[" * 100000 + "]" * 100000, end="")')" "isn't YAML: maximum recursion depth"
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
refused "an exit of true, which Python counts as 1" 'cases:
  - name: "a"
    args: []
    exit: true' "\`exit\` is a whole number"
refused "a case with no exit" 'cases:
  - name: "a"
    args: []' "\`exit\` is a whole number"
refused "a case with no args" 'cases:
  - name: "a"
    exit: 0' "\`args\` is a list of texts"
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

refused "a json line of NaN, which JSON hasn't" 'cases:
  - name: "a"
    args: ["--format", "json", "RETURN 0.0/0.0"]
    stdout: "NaN\n"
    exit: 0' "line 1 of its json stdout isn't JSON"
refused "a json line that isn't a result" 'cases:
  - name: "a"
    args: ["--format=json", "RETURN 1 AS n"]
    stdout: "[1]\n"
    exit: 0' "line 1 of its json stdout isn't an object of \`columns\` and \`rows\`"
refused "keys the README doesn't name, a number among them" "cases:
$ok
    1: x
    other: y" "\`1\` isn't a key the README's table names"
refused "a case that isn't a map" "cases:
  - a" "case 1 isn't a map"
refused "a name that isn't a text" "cases:
  - name: [a]
    args: []
    exit: 0" "has no name"
refused "an only that names a list" "cases:
$ok
    only: [[neo4j]]" "\`only\` names ['neo4j']"
refused "a stdin that isn't a text" "cases:
$ok
    stdin: 1" "\`stdin\` is a text"
refused "stdoutLines that aren't a list of texts" "cases:
$ok
    stdoutLines: \"^n$\"" "\`stdoutLines\` is a list of texts"
refused "a key given twice in one case" "cases:
$ok
    stdout: \"\"" "'stdout' is given twice in one map"
passes "a key a merge brings in, given again" 'cases:
  - <<: {name: "m", exit: 1}
    args: []
    exit: 0'
refused "a json line nested too deep to read" "cases:
  - name: \"a\"
    args: [\"--format\", \"json\", \"RETURN 1\"]
    stdout: \"$(python3 -c 'print("[" * 100000 + "]" * 100000, end="")')\\n\"
    exit: 0" "line 1 of its json stdout isn't JSON: maximum recursion depth"
passes "files of each form the README allows" "cases:
$ok
    files: {a.cypher: \"RETURN 1;\", b.yml: {text: \"x\", mode: \"600\"}, c: {text: \"y\"}}"
refused "a file whose mode isn't octal" "cases:
$ok
    files: {b.yml: {text: \"x\", mode: \"rw\"}}" "\`files\` maps each name to its text"
refused "a file with no text" "cases:
$ok
    files: {b.yml: {mode: \"600\"}}" "\`files\` maps each name to its text"
refused "a variable that isn't a text or null" "cases:
$ok
    env: {DRIVINE_USER: 1}" "\`env\` maps each name to a text or null"
refused "store: true" "cases:
$ok
    store: true" "\`store\` is only ever false"
refused "store: 0, which Python counts as false" "cases:
$ok
    store: 0" "\`store\` is only ever false"
refused "a json case whose stdin clears its parameters, which prints nothing" 'cases:
  - name: "a"
    args: ["--format", "json"]
    stdin: ":params clear\nRETURN 1 AS n;\n"
    stdout: "n\n1\n"
    exit: 0' "line 1 of its json stdout isn't JSON"
refused "cypherShell: false" "cases:
$ok
    cypherShell: false" "\`cypherShell\` is only ever true"
refused "a mode the README doesn't name" "cases:
$ok
    mode: default" "\`mode\` is only ever \"cypher-shell\""

# Whatever the check can't read is a finding about the file, not a traceback: a README that isn't UTF-8 is one such input.
printf '%s\n' "cases:
$ok" > "$root/cases.yaml"
printf '\xff\xfe' > "$root/odd.md"
if out="$(python3 -I "$check" "$root/cases.yaml" "$root/odd.md" 2>&1)"; then fail "a README that isn't UTF-8 passed"; fi
grep -qF "can't be checked" <<<"$out" || grep -qF 'no "## Cases" section' <<<"$out" || fail "a README that isn't UTF-8: not a finding: $out"
! grep -qF "Traceback" <<<"$out" || fail "a README that isn't UTF-8 gave a traceback: $out"

# The README's table is where the keys come from: one without the section is refused, not passed.
printf '# No cases here\n' > "$root/README.md"
printf '%s\n' "cases:
$ok" > "$root/cases.yaml"
if out="$(python3 -I "$check" "$root/cases.yaml" "$root/README.md" 2>&1)"; then fail "a README with no Cases section passed"; fi
grep -qF 'no "## Cases" section' <<<"$out" || fail "a README with no Cases section: not its failure: $out"

# A Cases section whose table is gone is refused, though a later section has a table of keys.
printf '# A README\n\n## Cases\n\nNo table.\n\n## Later\n\n| Key | What it is |\n|---|---|\n| `name` | A name. |\n' > "$root/README.md"
if out="$(python3 -I "$check" "$root/cases.yaml" "$root/README.md" 2>&1)"; then fail "a README with no table of keys passed"; fi
grep -qF 'has no table of keys' <<<"$out" || fail "a README with no table of keys: not its failure: $out"

out="$(python3 -I "$check" "$here/../../cli/cli.yaml" "$readme")" || fail "the spec's own cli.yaml was refused: $out"
echo "cli-cases-check-test: passed. $out"
