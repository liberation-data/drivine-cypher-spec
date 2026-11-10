"""
check_wheel.py's and build_wheels.py's own test, over made-up libraries: the release's versions as PEP 440 has them, and
others refused; wheels of every target built twice the same bytes, and passed; a wheel with a file too many, a file
RECORD doesn't name as it is, WHEEL's tags not its name's, or another version, refused; and --are refusing a directory
with a wheel too many or one of another SHA-256. No build, the standard library alone.

    python3 python/check_wheel_test.py
"""

from __future__ import annotations

import contextlib
import hashlib
import io
import os
import shutil
import sys
import tempfile
import zipfile
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parent))

import build_wheels  # noqa: E402
import check_wheel  # noqa: E402


def fail(message: str) -> None:
    raise SystemExit(f"check_wheel_test: {message}")


def run(argv: list[str], wheels: str | None = None) -> tuple[int, str]:
    out = io.StringIO()
    old = os.environ.get("WHEELS")
    if wheels is not None:
        os.environ["WHEELS"] = wheels
    try:
        with contextlib.redirect_stdout(out):
            code = check_wheel.main(argv)
    finally:
        if old is None:
            os.environ.pop("WHEELS", None)
        else:
            os.environ["WHEELS"] = old
    return code, out.getvalue()


def refused(what: str, argv: list[str], expect: str, wheels: str | None = None) -> None:
    code, out = run(argv, wheels)
    if code == 0:
        fail(f"{what} passed: {out}")
    if expect not in out:
        fail(f"{what}: not its failure: {out}")


def rewrite(source: Path, target: Path, change) -> None:
    """A copy of the wheel source, each entry's data given to change(name, data), which gives the new data or None to drop it."""
    with zipfile.ZipFile(source) as zin, zipfile.ZipFile(target, "w") as zout:
        for info in zin.infolist():
            data = change(info.filename, zin.read(info))
            if data is not None:
                zout.writestr(info, data)


def main() -> None:
    for version, expected in [
        ("1.2.3", "1.2.3"),
        ("0.35.0", "0.35.0"),
        ("1.2.3-rc.1", "1.2.3rc1"),
        ("1.2.3-alpha.2", "1.2.3a2"),
        ("1.2.3-beta.10", "1.2.3b10"),
        ("0.0.0-dry-run", "0.0.0.dev0"),
    ]:
        if build_wheels.python_version(version) != expected:
            fail(f"{version} as {build_wheels.python_version(version)}, not {expected}")
    for version in ["1.2", "v1.2.3", "1.2.3-rc1", "1.2.3-SNAPSHOT", "1.2.3-rc.1.2", "1.2.3+local", "0.0.0-dependencies"]:
        try:
            build_wheels.python_version(version)
        except SystemExit:
            continue
        fail(f"{version} wasn't refused")

    root = Path(tempfile.mkdtemp())
    try:
        libraries = []
        for target, (_, name) in build_wheels.PLATFORMS.items():
            library = root / "lib" / target / name
            library.parent.mkdir(parents=True)
            library.write_bytes(f"a made-up {target} library\n".encode() * 100)
            libraries.append(f"{target}={library}")
        for out in ("one", "two"):
            with contextlib.redirect_stdout(io.StringIO()):
                if build_wheels.main(["1.2.3-rc.1", str(root / out), *libraries]) != 0:
                    fail("build_wheels.py failed")
        wheels = sorted((root / "one").iterdir())
        if len(wheels) != len(build_wheels.PLATFORMS):
            fail(f"build_wheels.py made {[it.name for it in wheels]}")
        for wheel in wheels:
            if wheel.read_bytes() != (root / "two" / wheel.name).read_bytes():
                fail(f"{wheel.name} isn't the same bytes built twice")

        code, listed = run(["1.2.3-rc.1", *map(str, wheels)])
        if code != 0:
            fail(f"the wheels build_wheels.py made were refused: {listed}")
        if listed.splitlines() != [f"{it.name} {hashlib.sha256(it.read_bytes()).hexdigest()}" for it in wheels]:
            fail(f"not a line a wheel, `<file name> <sha256>`: {listed}")
        refused("another version", ["1.2.3", str(wheels[0])], "not a wheel of drivine_cypher_compiler 1.2.3")
        refused("a wheel given twice", ["1.2.3-rc.1", str(wheels[0]), str(wheels[0])], "A wheel is given twice.")

        linux = next(it for it in wheels if "manylinux_2_17_x86_64" in it.name)
        bad = root / "bad"
        bad.mkdir()

        rewrite(linux, bad / linux.name, lambda name, data: data)
        with zipfile.ZipFile(bad / linux.name, "a") as zf:
            zf.writestr("drivine_cypher_compiler/extra.py", "")
        refused("a file too many", ["1.2.3-rc.1", str(bad / linux.name)], "['drivine_cypher_compiler/extra.py'] it shouldn't")

        rewrite(linux, bad / linux.name, lambda name, data: data + b"x" if name.endswith(".so") else data)
        refused("a library RECORD doesn't name", ["1.2.3-rc.1", str(bad / linux.name)], "libdrivinecypher.so isn't the file RECORD names")

        rewrite(
            linux,
            bad / linux.name,
            lambda name, data: data.replace(b"Tag: py3-none-manylinux2014_x86_64\n", b"") if name.endswith("/WHEEL") else data,
        )
        refused("WHEEL's tags not its name's", ["1.2.3-rc.1", str(bad / linux.name)], "WHEEL's tags")

        unknown = bad / linux.name.replace("manylinux_2_17_x86_64.manylinux2014_x86_64", "musllinux_1_2_x86_64")
        shutil.copy(linux, unknown)
        refused("a platform build_wheels.py doesn't build", ["1.2.3-rc.1", str(unknown)], "is no platform build_wheels.py builds")

        code, out = run(["--are", str(root / "one")], listed)
        if code != 0:
            fail(f"--are refused the wheels build made: {out}")
        refused("--are with no list", ["--are", str(root / "one")], "WHEELS lists no wheel", "")
        refused("--are with a wheel missing", ["--are", str(root / "one")], "holds", "\n".join(listed.splitlines()[1:]))
        shutil.copy(bad / linux.name, root / "one" / linux.name)
        refused("--are with another wheel in its place", ["--are", str(root / "one")], f"{linux.name} has SHA-256", listed)
        shutil.copy(root / "two" / linux.name, linux)
        shutil.copy(unknown, root / "one" / unknown.name)
        refused("--are with a wheel too many", ["--are", str(root / "one")], unknown.name, listed)
    finally:
        shutil.rmtree(root)
    print("check_wheel_test: passed")


if __name__ == "__main__":
    main()
