"""
Checks the wheels build_wheels.py made, before they are published or installed: each is drivine-cypher-compiler of the
version given, for one platform build_wheels.py knows, holding the package's Python, that platform's library and its
.dist-info, and nothing else; its WHEEL tags are its file name's; and every file's SHA-256 and size are RECORD's.

    python3 check_wheel.py <compiler version> <wheel> ...

It prints one line a wheel, as `<file name> <sha256>`. It exits 1 naming each problem. The standard library alone.

    python3 check_wheel.py --are <dir>

checks that <dir> holds exactly the wheels the environment's WHEELS lists, as the release's build job gave them (a line
each, `<file name> <sha256>`), each of that SHA-256: so a job after build installs or publishes build's wheels, and no
others put in their place.
"""

from __future__ import annotations

import base64
import hashlib
import os
import sys
import zipfile
from pathlib import Path

from build_wheels import PACKAGE, PLATFORMS, python_version

PYTHON = {f"{PACKAGE}/__init__.py", f"{PACKAGE}/_version.py", f"{PACKAGE}/py.typed"}


def problems(path: Path, version: str) -> list[str]:
    found: list[str] = []
    stem = f"{PACKAGE}-{version}-py3-none-"
    if not (path.name.startswith(stem) and path.name.endswith(".whl")):
        return [f"{path.name}: not a wheel of {PACKAGE} {version} (expected {stem}<platform>.whl)"]
    tag = path.name[len(stem) : -len(".whl")]
    platform = next((p for p, (t, _) in PLATFORMS.items() if t == tag), None)
    if platform is None:
        return [f"{path.name}: {tag} is no platform build_wheels.py builds"]
    library = f"{PACKAGE}/{PLATFORMS[platform][1]}"
    dist_info = f"{PACKAGE}-{version}.dist-info"
    expected = PYTHON | {library} | {f"{dist_info}/{it}" for it in ("METADATA", "WHEEL", "LICENSE", "RECORD")}
    with zipfile.ZipFile(path) as zf:
        names = zf.namelist()
        if len(names) != len(set(names)):
            found.append(f"{path.name}: an entry is in it twice")
        if set(names) != expected:
            extra, missing = sorted(set(names) - expected), sorted(expected - set(names))
            found.append(f"{path.name}: holds {extra or 'nothing'} it shouldn't, lacks {missing or 'nothing'}")
            return found
        wheel = zf.read(f"{dist_info}/WHEEL").decode("utf-8").splitlines()
        tags = {line.removeprefix("Tag: ") for line in wheel if line.startswith("Tag: ")}
        if tags != {f"py3-none-{it}" for it in tag.split(".")}:
            found.append(f"{path.name}: WHEEL's tags {sorted(tags)} aren't its file name's")
        if "Root-Is-Purelib: false" not in wheel:
            found.append(f"{path.name}: WHEEL doesn't say Root-Is-Purelib: false, for a wheel with a library in it")
        meta = zf.read(f"{dist_info}/METADATA").decode("utf-8").splitlines()
        if "Name: drivine-cypher-compiler" not in meta or f"Version: {version}" not in meta:
            found.append(f"{path.name}: METADATA isn't drivine-cypher-compiler {version}")
        if zf.read(f"{PACKAGE}/_version.py").decode("utf-8").strip() != f'__version__ = "{version}"':
            found.append(f"{path.name}: _version.py isn't {version}")
        record: dict[str, tuple[str, str]] = {}
        for line in zf.read(f"{dist_info}/RECORD").decode("utf-8").splitlines():
            name, digest, size = line.rsplit(",", 2)
            record[name] = (digest, size)
        if set(record) != expected:
            found.append(f"{path.name}: RECORD names {sorted(set(record) ^ expected)} wrongly")
        for name in sorted(expected - {f"{dist_info}/RECORD"}):
            data = zf.read(name)
            digest = "sha256=" + base64.urlsafe_b64encode(hashlib.sha256(data).digest()).rstrip(b"=").decode("ascii")
            if record.get(name) != (digest, str(len(data))):
                found.append(f"{path.name}: {name} isn't the file RECORD names")
    return found


def are(directory: Path, listed: str) -> list[str]:
    expected = dict(line.split(" ", 1) for line in listed.strip().splitlines() if line.strip())
    if not expected:
        return ["WHEELS lists no wheel"]
    found = {path.name: hashlib.sha256(path.read_bytes()).hexdigest() for path in directory.iterdir()}
    wrong = [f"{name} has SHA-256 {got}, not {expected[name]}" for name, got in found.items() if expected.get(name, got) != got]
    if set(found) != set(expected):
        wrong.append(f"{directory} holds {sorted(found)}, not {sorted(expected)}")
    return wrong


def main(argv: list[str]) -> int:
    if len(argv) == 2 and argv[0] == "--are":
        wrong = are(Path(argv[1]), os.environ.get("WHEELS", ""))
        for it in wrong:
            print(f"::error::{it}")
        if not wrong:
            print(f"{argv[1]} holds the wheels build made, and no others.")
        return 1 if wrong else 0
    if len(argv) < 2:
        print(__doc__, file=sys.stderr)
        return 2
    version = python_version(argv[0])
    failed = False
    platforms: list[str] = []
    for arg in argv[1:]:
        path = Path(arg)
        found = problems(path, version)
        for it in found:
            print(f"::error::{it}")
        failed |= bool(found)
        platforms.append(path.name)
        if not found:
            print(f"{path.name} {hashlib.sha256(path.read_bytes()).hexdigest()}")
    if len(set(platforms)) != len(platforms):
        print("::error::A wheel is given twice.")
        failed = True
    return 1 if failed else 0


if __name__ == "__main__":
    sys.exit(main(sys.argv[1:]))
