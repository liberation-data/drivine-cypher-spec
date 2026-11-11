#!/usr/bin/env python3
"""
Checks the drivine CLI's cases, cli/cli.yaml, against what cli/README.md says a case is, with no CLI and no store:

- the file is YAML, a map whose one key is `cases`, a list of maps;
- each case has a `name`, a text, unique in the file, its `args`, a list of texts, and its `exit`, a whole number;
- each case's keys are the ones the README's table of keys names ("Cases"), and no others;
- `only` and `except` name stores the README names, or `graph` and `sql`; `os` names linux, macos or windows;
- `stdin`, `stdout`, `stderr`, `description` and `note` are texts, and `stdoutLines` a list of texts;
- `files` maps each name to its text, or to a map of its `text` and an octal `mode`; `env` maps each name to a text or
  null; `store` is only ever false, `cypherShell` only ever true, and `mode` only ever cypher-shell;
- where a case runs with `--format json`, and with no `--explain`, `--help`, `--version` or `--driver-version` and no
  `:params` or `:help` in its args' Cypher, its stdin or its files, which print something else to stdout, each line of
  the `stdout` it expects is one JSON object of `columns` and `rows`, with no bare NaN or infinity, and its last line
  ends in a line break, as json writes one line per statement.

    .github/scripts/cli-cases-check.py [cli.yaml [README.md]]
"""
import json
import re
import sys

import yaml

STORES = {"neo4j", "memgraph", "falkordb", "postgres", "oracle", "sqlite", "graph", "sql"}
SYSTEMS = {"linux", "macos", "windows"}


def keys(readme):
    """The keys the README's table under "## Cases", before the next section, names: each backticked name in its first
    column."""
    section = readme.split("\n## Cases\n", 1)
    if len(section) < 2:
        raise ValueError("the README has no \"## Cases\" section")
    names = set()
    table = False
    for line in section[1].split("\n## ", 1)[0].split("\n"):
        if line.startswith("| Key |"):
            table = True
        elif table and line.startswith("|"):
            names.update(re.findall(r"`([^`]+)`", line.split("|")[1]))
        elif table:
            break
    if not names:
        raise ValueError("the README's \"## Cases\" section has no table of keys")
    return names


# The flags that print something of their own in place of the statements' results, and the commands that print to
# stdout between them.
OWN_OUTPUT = {"--explain", "-h", "--help", "-v", "--version", "--driver-version"}
PRINTING_COMMAND = re.compile(r"^[ \t]*:(params|help)[ \t]*\r?$", re.MULTILINE)


def json_lines(case, args):
    """Whether args ask for --format json, and nothing but the statements' results is written to stdout: no flag of
    OWN_OUTPUT, and no `:params` or `:help` in an argument, as the positional Cypher or `-c`'s, stdin or a file."""
    if not (any(a == "--format" and b == "json" for a, b in zip(args, args[1:])) or "--format=json" in args):
        return False
    if OWN_OUTPUT & set(args):
        return False
    files = case.get("files") if isinstance(case.get("files"), dict) else {}
    texts = args + [case.get("stdin")] + [f.get("text") if isinstance(f, dict) else f for f in files.values()]
    return not any(isinstance(t, str) and PRINTING_COMMAND.search(t) for t in texts)


def a_file(file):
    """Whether a file of `files` is its text, or a map of its `text` and, optionally, its `mode` in octal."""
    if isinstance(file, str):
        return True
    return (isinstance(file, dict) and set(file) in ({"text"}, {"text", "mode"}) and isinstance(file["text"], str)
            and isinstance(file.get("mode", "644"), str) and re.fullmatch(r"[0-7]+", file.get("mode", "644")) is not None)


def bare(constant):
    """Refuses the NaN and infinities Python's json reads but JSON hasn't: json writes them as {"$float": ...}."""
    raise ValueError(f"{constant} isn't JSON")


def problems(cases_text, readme_text):
    """Each way the cases break the README's rules, as a line naming the case; none where they keep them."""
    try:
        document = yaml.safe_load(cases_text)
    except yaml.YAMLError as e:
        return [f"cli.yaml isn't YAML: {e}"]
    if not isinstance(document, dict) or set(document) != {"cases"} or not isinstance(document["cases"], list):
        return ["cli.yaml is a map whose one key is `cases`, a list of cases"]
    try:
        allowed = keys(readme_text)
    except ValueError as e:
        return [str(e)]
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
        else:
            seen.add(name)
        for key in sorted(set(case) - allowed, key=str):
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
                if not isinstance(value, str) or value not in names:
                    found.append(f"{label}: `{key}` names {value!r}, which isn't one of {', '.join(sorted(names))}")
        for key in ("stdin", "stdout", "stderr", "description", "note"):
            if key in case and not isinstance(case[key], str):
                found.append(f"{label}: `{key}` is a text")
        if "stdoutLines" in case and not (
                isinstance(case["stdoutLines"], list) and all(isinstance(e, str) for e in case["stdoutLines"])):
            found.append(f"{label}: `stdoutLines` is a list of texts")
        files = case.get("files", {})
        if not isinstance(files, dict) or not all(isinstance(f, str) and a_file(t) for f, t in files.items()):
            found.append(f"{label}: `files` maps each name to its text, or to a map of its `text` and an octal `mode`")
        env = case.get("env", {})
        if not isinstance(env, dict) or not all(
                isinstance(k, str) and (v is None or isinstance(v, str)) for k, v in env.items()):
            found.append(f"{label}: `env` maps each name to a text or null")
        for key, value in (("store", False), ("cypherShell", True), ("mode", "cypher-shell")):
            if key in case and (case[key] != value or type(case[key]) is not type(value)):
                found.append(f"{label}: `{key}` is only ever {json.dumps(value)}")
        stdout = case.get("stdout")
        if json_lines(case, args) and isinstance(stdout, str) and stdout:
            if not stdout.endswith("\n"):
                found.append(f"{label}: json's stdout ends in a line break")
            for n, line in enumerate(stdout.split("\n")[:-1] if stdout.endswith("\n") else stdout.split("\n"), 1):
                try:
                    value = json.loads(line, parse_constant=bare)
                except ValueError as e:
                    found.append(f"{label}: line {n} of its json stdout isn't JSON: {e}")
                    continue
                if not isinstance(value, dict) or set(value) != {"columns", "rows"}:
                    found.append(f"{label}: line {n} of its json stdout isn't an object of `columns` and `rows`")
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
