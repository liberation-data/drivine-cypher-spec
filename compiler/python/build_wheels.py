"""
Packs the compiler's shared libraries as the Python package drivine-cypher-compiler: one wheel a platform, each holding
the package's Python (drivine_cypher_compiler/, which loads the library over ctypes) and that platform's library. No
build backend and no per-Python build: a wheel is a zip of a known layout, and the library is the same for every Python
3, so each wheel is py3-none-<platform>.

    python3 build_wheels.py <compiler version> <out dir> <target>=<library> ...

<target> is the Kotlin/Native target the library was built for: macosArm64, linuxX64, linuxArm64 or mingwX64. The same
version and libraries give the same bytes: entries in order, each dated 1980-01-01, so a wheel's SHA-256 says what is in
it. The standard library alone, so the release builds it with nothing installed.
"""

from __future__ import annotations

import base64
import hashlib
import re
import sys
import zipfile
from pathlib import Path

NAME = "drivine-cypher-compiler"
PACKAGE = "drivine_cypher_compiler"
HERE = Path(__file__).resolve().parent

PLATFORMS = {
    # Kotlin/Native's macosArm64 builds for macOS 11 and later (the library's LC_BUILD_VERSION minos).
    "macosArm64": ("macosx_11_0_arm64", "libdrivinecypher.dylib"),
    # manylinux2014 is glibc 2.17. The x86_64 library calls nothing newer than GLIBC_2.14, and the aarch64 one nothing
    # newer than GLIBC_2.17, and both link only libraries manylinux2014 lets a wheel take from the system: see
    # check_wheel.py, which checks each.
    "linuxX64": ("manylinux_2_17_x86_64.manylinux2014_x86_64", "libdrivinecypher.so"),
    "linuxArm64": ("manylinux_2_17_aarch64.manylinux2014_aarch64", "libdrivinecypher.so"),
    "mingwX64": ("win_amd64", "drivinecypher.dll"),
}

DATE = (1980, 1, 1, 0, 0, 0)


def python_version(version: str) -> str:
    """
    The compiler's version as PEP 440 has it: 1.2.3 as it is, 1.2.3-rc.1 as 1.2.3rc1 (and alpha, beta as a, b), and a
    dry run's 0.0.0-dry-run as 0.0.0.dev0. Anything else is refused, as PyPI would refuse it.
    """
    if version == "0.0.0-dry-run":
        return "0.0.0.dev0"
    match = re.fullmatch(r"(\d+\.\d+\.\d+)(?:-(alpha|beta|rc)\.(\d+))?", version)
    if not match:
        raise SystemExit(f"{version} is no version a wheel can have: X.Y.Z or X.Y.Z-(alpha|beta|rc).N.")
    release, pre, number = match.groups()
    return release + ({"alpha": "a", "beta": "b", "rc": "rc"}[pre] + number if pre else "")


def metadata(version: str) -> str:
    readme = (HERE / "README.md").read_text(encoding="utf-8")
    return (
        "Metadata-Version: 2.1\n"
        f"Name: {NAME}\n"
        f"Version: {version}\n"
        "Summary: Drivine's Cypher compiler: Cypher as SQL for Postgres, Oracle and SQLite, as a native library.\n"
        "Home-page: https://github.com/liberation-data/drivine-cypher-spec\n"
        "License: Apache-2.0\n"
        "Project-URL: Source, https://github.com/liberation-data/drivine-cypher-spec/tree/main/compiler\n"
        "Classifier: License :: OSI Approved :: Apache Software License\n"
        "Classifier: Programming Language :: Python :: 3\n"
        "Requires-Python: >=3.9\n"
        "Description-Content-Type: text/markdown\n"
        "\n" + readme
    )


def record_line(path: str, data: bytes) -> str:
    digest = base64.urlsafe_b64encode(hashlib.sha256(data).digest()).rstrip(b"=").decode("ascii")
    return f"{path},sha256={digest},{len(data)}"


def wheel(version: str, target: str, library: Path, out: Path) -> Path:
    tag, library_name = PLATFORMS[target]
    dist_info = f"{PACKAGE}-{version}.dist-info"
    files: dict[str, tuple[bytes, int]] = {}
    for source in sorted((HERE / PACKAGE).iterdir()):
        if source.name == "_version.py" or source.suffix not in (".py", ".typed"):
            continue
        files[f"{PACKAGE}/{source.name}"] = (source.read_bytes(), 0o644)
    files[f"{PACKAGE}/_version.py"] = (f'__version__ = "{version}"\n'.encode(), 0o644)
    files[f"{PACKAGE}/{library_name}"] = (library.read_bytes(), 0o755)
    files[f"{dist_info}/METADATA"] = (metadata(version).encode("utf-8"), 0o644)
    tags = "".join(f"Tag: py3-none-{it}\n" for it in tag.split("."))
    files[f"{dist_info}/WHEEL"] = (
        f"Wheel-Version: 1.0\nGenerator: drivine-cypher-spec build_wheels.py\nRoot-Is-Purelib: false\n{tags}".encode(),
        0o644,
    )
    files[f"{dist_info}/LICENSE"] = ((HERE.parent.parent / "LICENSE").read_bytes(), 0o644)
    record = "".join(record_line(path, data) + "\n" for path, (data, _) in files.items())
    files[f"{dist_info}/RECORD"] = ((record + f"{dist_info}/RECORD,,\n").encode(), 0o644)

    path = out / f"{PACKAGE}-{version}-py3-none-{tag}.whl"
    with zipfile.ZipFile(path, "w", zipfile.ZIP_DEFLATED, compresslevel=9) as zf:
        for name, (data, mode) in files.items():
            info = zipfile.ZipInfo(name, DATE)
            info.compress_type = zipfile.ZIP_DEFLATED
            info.external_attr = (0o100000 | mode) << 16
            info.create_system = 3
            zf.writestr(info, data)
    return path


def main(argv: list[str]) -> int:
    if len(argv) < 3:
        print(__doc__, file=sys.stderr)
        return 2
    version = python_version(argv[0])
    out = Path(argv[1])
    out.mkdir(parents=True, exist_ok=True)
    for pair in argv[2:]:
        target, _, library = pair.partition("=")
        if target not in PLATFORMS or not library:
            raise SystemExit(f"{pair}: give <target>=<library>, the target one of {', '.join(PLATFORMS)}.")
        print(wheel(version, target, Path(library), out))
    return 0


if __name__ == "__main__":
    sys.exit(main(sys.argv[1:]))
