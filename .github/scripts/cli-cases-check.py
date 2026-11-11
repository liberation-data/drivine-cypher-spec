#!/usr/bin/env python3
"""
Checks the drivine CLI's cases, cli/cli.yaml, against what cli/README.md says a case is, with no CLI and no store:

- the file is YAML, a map whose one key is `cases`, a list of maps;
- each case has a `name`, a text, unique in the file, its `args`, a list of texts, and its `exit`, a whole number;
- each case's keys are the ones the README's table of keys names ("Cases"), and no others;
- `only` and `except` name stores the README names, or `graph` and `sql`; `os` names linux, macos or windows;
- where a case runs with `--format json`, each line of the `stdout` it expects is one JSON value, and its last line ends
  in a line break, as json writes one line per statement.

    .github/scripts/cli-cases-check.py [cli.yaml [README.md]]
"""
import json
import re
import sys

import yaml

STORES = {"neo4j", "memgraph", "falkordb", "postgres", "oracle", "sqlite", "graph", "sql"}
SYSTEMS = {"linux", "macos", "windows"}


def keys(readme):
    """The keys the README's table under "## Cases" names: each backticked name in its first column."""
    section = readme.split("\n## Cases\n", 1)
    if len(section) < 2:
        raise ValueError("the README has no \"## Cases\" section")
    names = set()
    table = False
    for line in section[1].split("\n"):
        if line.startswith("| Key |"):
            table = True
        elif table and line.startswith("|"):
            names.update(re.findall(r"`([^`]+)`", line.split("|")[1]))
        elif table:
            break
    if not names:
        raise ValueError("the README's \"## Cases\" section has no table of keys")
    return names


def json_format(args):
    """Whether args ask for --format json."""
    return any(a == "--format" and b == "json" for a, b in zip(args, args[1:])) or "--format=json" in args


def problems(cases_text, readme_text):
    """Each way the cases break the README's rules, as a line naming the case; none where they keep them."""
    try:
        document = yaml.safe_load(cases_text)
    except yaml.YAMLError as e:
        return [f"cli.yaml isn't YAML: {e}"]
    if not isinstance(document, dict) or set(document) != {"cases"} or not isinstance(document["cases"], list):
        return ["cli.yaml is a map whose one key is `cases`, a list of cases"]
    allowed = keys(readme_text)
    found = []
    seen = set()
    for i, case in enumerate(document["cases"], 1):
        if not isinstance(case, dict):
            found.append(f"case {i} isn't a map")
            continue
        name = case.get("name")
        label = f"case {i} ({name!r})"
        if not isinstance(name, str) or not name:
            found.append(f"{label} has no name")
        elif name in seen:
            found.append(f"{label}: its name is another case's too")
        seen.add(name)
        for key in sorted(set(case) - allowed):
            found.append(f"{label}: `{key}` isn't a key the README's table names")
        args = case.get("args")
        if not isinstance(args, list) or not all(isinstance(a, str) for a in args):
            found.append(f"{label}: `args` is a list of texts")
            args = []
        if type(case.get("exit")) is not int:
            found.append(f"{label}: `exit` is a whole number")
        for key, names in (("only", STORES), ("except", STORES), ("os", SYSTEMS)):
            given = case.get(key)
            if given is None:
                continue
            if not isinstance(given, list):
                found.append(f"{label}: `{key}` is a list")
                continue
            for value in given:
                if value not in names:
                    found.append(f"{label}: `{key}` names {value!r}, which isn't one of {', '.join(sorted(names))}")
        stdout = case.get("stdout")
        if json_format(args) and isinstance(stdout, str) and stdout:
            if not stdout.endswith("\n"):
                found.append(f"{label}: json's stdout ends in a line break")
            for n, line in enumerate(stdout.split("\n")[:-1] if stdout.endswith("\n") else stdout.split("\n"), 1):
                try:
                    json.loads(line)
                except ValueError as e:
                    found.append(f"{label}: line {n} of its json stdout isn't JSON: {e}")
    return found


def main(argv):
    cases = argv[1] if len(argv) > 1 else "cli/cli.yaml"
    readme = argv[2] if len(argv) > 2 else "cli/README.md"
    with open(cases, encoding="utf-8") as f:
        cases_text = f.read()
    with open(readme, encoding="utf-8") as f:
        readme_text = f.read()
    found = problems(cases_text, readme_text)
    for line in found:
        print(f"{cases}: {line}", file=sys.stderr)
    if found:
        return 1
    print(f"{cases}: {len(yaml.safe_load(cases_text)['cases'])} cases, as {readme} says a case is.")
    return 0


if __name__ == "__main__":
    sys.exit(main(sys.argv))
