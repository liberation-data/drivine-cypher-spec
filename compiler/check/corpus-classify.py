#!/usr/bin/env python3
"""
Sorts each answer the store layout 2 changed (liberation-data/drivine-cypher-spec#161) into the cause that changed it.

    corpus-classify.py <main's corpus.jsonl> <this branch's corpus.jsonl>

Both corpora are of the same requests: this branch's spec statements, compiled by main's compiler and by this branch's
(see corpus-classify.sh, which makes main's). An answer that differs where the statement involves no temporal value and no
key that begins with `$` is put down to exactly one of CAUSES, by a marker the cause leaves in the SQL, never by the
statement: the first cause whose marker the changed SQL holds. Prints the counts for each cause and store, and exits 1
where an answer is put down to none, printing it.
"""
import difflib
import json
import re
import sys
from collections import Counter

# A statement that involves a temporal value or a key that begins with $ is the layout's own business, and is not sorted.
TEMPORAL = re.compile(r"\b(date|datetime|localdatetime|time|localtime|duration)\s*[.(]|\btruncate\b|\$\w*`|`\$", re.I)
DOLLAR_KEY = re.compile(r"[{,]\s*`?\$\w|\[\s*'\$|\.`\$")

ALL = {"postgres", "oracle", "sqlite"}

# Each cause, the stores it is on, and the marker it leaves in the SQL it changes, in the order a changed answer is tried against them.
CAUSES = [
    ("full-text search reads a tag's v", ALL, re.compile(r"COALESCE\(\(?(JSON_VALUE\()?c\.props")),
    ("Postgres equality reads (props -> 'k')", {"postgres"}, re.compile(r"->'[^']*'\)=|props->>'[^']*'\)=\(CAST")),
    ("SQLite and Oracle equality through the index's class and key", {"sqlite", "oracle"}, re.compile(r"\"\$t\"',CASEjson_type|RETURNINGVARCHAR2\(16\)")),
    ("runtime $-key escaping", ALL, re.compile(r"='\$'|''\$''|'\$'\|\|")),
    ("Oracle's handling of a given whole number from 2^53 on", {"oracle"}, re.compile(r"RETURNINGNUMBERNULLONERROR")),
    ("Oracle floats as binary doubles (#146)", {"oracle"}, re.compile(r"BINARY_DOUBLE|\$numberDouble|EXTENDED")),
    ("a value of unknown kind that may be a tag at run time", ALL, re.compile(r"\$t|'2\.[1-6]'|THEN2\.[1-6]|'2~|drivine_list_order|drivine_sort_key")),
]

# Aliases the compiler numbers as it writes: a binding more or fewer renumbers those after it, which is no change of its own.
ALIAS = re.compile(r"\b(sh|u|p|w|n|e|q|c|v|i|o|k|r|s|t|x|y|m|cy|lw|eq\w|rw)(\d+)(_\d+)?\b")


def load(path):
    answers = {}
    for line in open(path):
        entry = json.loads(line)
        answers[entry["request"]] = entry["answer"]
    return answers


def text_of(answer):
    """The answer's SQL, and every text its parameters hold (a procedure's body among them), unescaped from the JSON."""
    try:
        parsed = json.loads(answer)
    except Exception:
        return answer
    texts = []

    def walk(value):
        if isinstance(value, str):
            texts.append(value)
        elif isinstance(value, dict):
            for key in sorted(value):
                texts.append(key)
                walk(value[key])
        elif isinstance(value, list):
            for item in value:
                walk(item)
        elif value is not None:
            texts.append(json.dumps(value))

    walk(parsed)
    return "\n".join(texts)


# A procedure is named by a hash of its body: a change of the body renames it, which is that change's, not one of its own.
HASHED = re.compile(r"drivine_cypher_[0-9a-f]{16,}")


def tokens(sql):
    return re.findall(r"\w+|[^\w\s]", ALIAS.sub(lambda m: m.group(1) + "#", HASHED.sub("drivine_cypher_#", sql)))


def changed(before, after):
    a, b = tokens(before), tokens(after)
    out = []
    for op, i1, i2, j1, j2 in difflib.SequenceMatcher(None, a, b, autojunk=False).get_opcodes():
        if op != "equal":
            out.append("".join(a[max(0, i1 - 40):i2 + 40]))
            out.append("".join(b[max(0, j1 - 40):j2 + 40]))
    return out


def main():
    explain = "--explain" in sys.argv
    sys.argv = [a for a in sys.argv if a != "--explain"]
    main_answers, branch_answers = load(sys.argv[1]), load(sys.argv[2])
    counts = Counter()
    unexplained = []
    for request, answer in branch_answers.items():
        before = main_answers.get(request)
        if before is None or before == answer:
            continue
        r = json.loads(request) if request.startswith("{") else {}
        cypher = r.get("cypher") or ""
        store = r.get("store", "?")
        if TEMPORAL.search(cypher) or DOLLAR_KEY.search(cypher) or '"$' in json.dumps(r.get("parameters", {})):
            counts[(store, "involves a temporal value or a $ key")] += 1
            continue
        # The whole answer, so a procedure's body, which its parameters carry, is compared too.
        hunks = changed(text_of(before), text_of(answer))
        if not hunks:
            counts[(store, "aliases renumbered alone")] += 1
            continue
        text = "\n".join(hunks)
        cause = next((name for name, stores, marker in CAUSES if store in stores and marker.search(text)), None)
        if cause is None:
            unexplained.append((store, cypher, hunks[:2]))
        else:
            counts[(store, cause)] += 1
            if explain:
                print(f"{store}\t{cause}\t{cypher[:120]}")
    for store in sorted({s for s, _ in counts}):
        print(store)
        for (s, cause), n in sorted(counts.items(), key=lambda kv: (-kv[1], kv[0][1])):
            if s == store:
                print(f"  {n:6}  {cause}")
    if unexplained:
        print(f"\n{len(unexplained)} changed answers put down to no cause:")
        for store, cypher, hunks in unexplained[:20]:
            print(f"  {store}: {cypher[:160]}")
            for h in hunks:
                print(f"      {h[:300]}")
        sys.exit(1)


if __name__ == "__main__":
    main()
