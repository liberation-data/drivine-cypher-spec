#!/usr/bin/env bash
# The compiler checks' results for a job's summary: the tests counted, the double and stand-in fuzz's lines, and the corpus check's and the mutants'.
#   compiler-summary.sh <test results directory> [<log with the corpus check's lines>]
results="$1"
if [ -d "$results" ]; then
    python3 - "$results" <<'PY'
import glob, re, sys
tests = failed = 0
fuzz = []
for path in glob.glob(sys.argv[1] + "/*.xml"):
    text = open(path, encoding="utf-8").read()
    m = re.search(r'tests="(\d+)" skipped="\d+" failures="(\d+)" errors="(\d+)"', text)
    if m:
        tests += int(m.group(1)); failed += int(m.group(2)) + int(m.group(3))
    fuzz += re.findall(r"(?:Double|Stand-in) fuzz on [^\n<]*", text)
print(f"- Tests: {tests}, {failed} failed")
for line in sorted(set(fuzz)):
    print(f"- {line}")
PY
fi
[ -n "${2:-}" ] && [ -f "$2" ] && grep -hE "^(Corpus|Mutants) on" "$2" | sed 's/^/- /'
exit 0
