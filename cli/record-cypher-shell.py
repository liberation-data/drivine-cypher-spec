#!/usr/bin/env python3
"""
Runs each case in cli.yaml marked `cypherShell: true` through cypher-shell, in a Neo4j container, and checks that
cypher-shell gives the case's stdout, stderr and exit code. That keeps the cases' claim, that drivine prints what
cypher-shell prints, measured against cypher-shell itself.

    cli/record-cypher-shell.py [--image neo4j:2026.10.1]

It needs Docker, and PyYAML or Ruby to read the YAML. It starts its own container, and removes it when it is done. It
exits with 1 if any case differs, printing what cypher-shell gave.
"""
import argparse
import json
import os
import re
import secrets
import shlex
import subprocess
import sys
import time

TIMING = re.compile(r"^ready to start consuming query after \d+ ms, results consumed after another \d+ ms$")
HERE = os.path.dirname(os.path.abspath(__file__))


def cases_of(path):
    """The cases in [path], read with PyYAML, or with Ruby's YAML where PyYAML isn't installed."""
    try:
        import yaml
    except ImportError:
        script = "require 'yaml'; require 'json'; puts YAML.load_file(ARGV[0]).to_json"
        return json.loads(subprocess.run(["ruby", "-e", script, path], capture_output=True, text=True, check=True).stdout)["cases"]
    with open(path) as f:
        return yaml.safe_load(f)["cases"]


def run(*command, stdin=None):
    return subprocess.run(command, input=stdin, capture_output=True, text=True)


def main():
    parser = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    parser.add_argument("--image", default="neo4j:2026.10.1", help="the Neo4j image whose cypher-shell to check against")
    image = parser.parse_args().image

    cases = [case for case in cases_of(os.path.join(HERE, "cli.yaml")) if case.get("cypherShell")]

    password = secrets.token_hex(12)
    name = f"drivine-cli-record-{secrets.token_hex(4)}"
    started = run("docker", "run", "-d", "--name", name, "-e", f"NEO4J_AUTH=neo4j/{password}", image)
    if started.returncode != 0:
        sys.exit(f"Couldn't start {image}: {started.stderr.strip()}")
    try:
        shell = ["docker", "exec", name, "cypher-shell", "-u", "neo4j", "-p", password]
        for _ in range(90):
            if run(*shell, "RETURN 1").returncode == 0:
                break
            time.sleep(2)
        else:
            sys.exit("Neo4j didn't start.")
        print(run("docker", "exec", name, "cypher-shell", "--version").stdout.strip())

        failed = 0
        for index, case in enumerate(cases):
            run(*shell, "MATCH (n) DETACH DELETE n")
            directory = f"/tmp/case-{index}"
            run("docker", "exec", name, "mkdir", "-p", directory)
            # A file is its text, or a map of its text and its mode, in octal; with no mode it is written as 0644.
            for file, given in (case.get("files") or {}).items():
                text, mode = (given["text"], given.get("mode", "644")) if isinstance(given, dict) else (given, "644")
                text = text.replace("$user", "neo4j").replace("$password", password)
                path = shlex.quote(f"{directory}/{file}")
                mode = format(int(str(mode), 8), "o")
                run("docker", "exec", "-i", name, "sh", "-c", f"cat > {path} && chmod {mode} {path}", stdin=text)

            # The runner gives drivine the password in DRIVINE_PASSWORD, which cypher-shell doesn't read, so it is
            # given here as NEO4J_PASSWORD. A case's DRIVINE_PASSWORD comes before its NEO4J_PASSWORD, as in drivine.
            env = {"NEO4J_USERNAME": "neo4j", "NEO4J_PASSWORD": password}
            given = dict(case.get("env") or {})
            if "DRIVINE_PASSWORD" in given:
                drivine = given.pop("DRIVINE_PASSWORD")
                if drivine is None:
                    env.pop("NEO4J_PASSWORD")
                else:
                    given["NEO4J_PASSWORD"] = drivine
            for key, value in given.items():
                if value is None:
                    env.pop(key, None)
                else:
                    env[key] = value.replace("$user", "neo4j").replace("$password", password)
            args = [arg.replace("$user", "neo4j").replace("$password", password) for arg in case.get("args", [])]
            command = ["docker", "exec", "-i", "-w", directory]
            for key, value in env.items():
                command += ["-e", f"{key}={value}"]
            command += [name, "cypher-shell", "-a", "neo4j://localhost:7687", *args]
            stdin = case.get("stdin")
            result = run(*command, stdin=stdin if stdin is not None else "")

            stdout = "".join(line for line in result.stdout.splitlines(keepends=True) if not TIMING.match(line.rstrip("\n")))
            if (stdout != case.get("stdout", stdout) or case.get("stderr", "") not in result.stderr
                    or result.returncode != case.get("exit", result.returncode)):
                failed += 1
                print(f"DIFFERS: {case['name']}\n  exit {result.returncode}, stdout {stdout!r}\n  stderr {result.stderr.strip()!r}")
            else:
                print(f"same: {case['name']}")
        print(f"{len(cases) - failed} of {len(cases)} cases are as cypher-shell gives them.")
        sys.exit(1 if failed else 0)
    finally:
        run("docker", "rm", "-f", name)


if __name__ == "__main__":
    main()
