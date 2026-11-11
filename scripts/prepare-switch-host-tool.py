#!/usr/bin/env python3
"""Build the pinned Khronos glslangValidator for host shader generation."""

import argparse
import json
import os
from pathlib import Path
import re
import subprocess
import tempfile


ROOT = Path(__file__).resolve().parents[1]
DEFAULT_LOCK = ROOT / "third_party/switch-glslang.lock.json"
DEFAULT_SOURCE = ROOT / ".cache/glslang-host-source"
DEFAULT_BUILD = ROOT / ".cache/glslang-host-build"


def git(*args, cwd=None):
    result = subprocess.run(["git", *args], cwd=cwd, text=True, capture_output=True)
    if result.returncode:
        raise RuntimeError(f"git {args[0]} failed: {result.stderr.strip()}")
    return result.stdout.strip()


def verify(source, lock):
    if git("rev-parse", "HEAD", cwd=source) != lock["commit"]:
        raise ValueError("glslang source commit mismatch")
    if subprocess.run(["git", "symbolic-ref", "-q", "HEAD"], cwd=source,
                      stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL).returncode == 0:
        raise ValueError("glslang source must be detached")
    if git("status", "--porcelain", "--untracked-files=all", "--ignored=matching", cwd=source):
        raise ValueError("glslang source contains modified, untracked, or ignored files")
    if not (source / "CMakeLists.txt").is_file() or not (source / "LICENSE.txt").is_file():
        raise ValueError("glslang source or license is missing")


def prepare_source(lock, source, repository):
    source = source.resolve()
    if source.exists():
        verify(source, lock)
        return source
    source.parent.mkdir(parents=True, exist_ok=True)
    with tempfile.TemporaryDirectory(prefix="glslang-source-", dir=source.parent) as temp_name:
        checkout = Path(temp_name) / "source"
        git("clone", "--depth", "1", "--single-branch", "--branch", lock["tag"],
            repository or lock["repository"], str(checkout))
        if git("rev-parse", "HEAD", cwd=checkout) != lock["commit"]:
            raise ValueError("glslang tag resolved to a different commit")
        git("checkout", "--detach", lock["commit"], cwd=checkout)
        verify(checkout, lock)
        checkout.rename(source)
    return source


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--lock", type=Path, default=DEFAULT_LOCK)
    parser.add_argument("--source", type=Path, default=DEFAULT_SOURCE)
    parser.add_argument("--build", type=Path, default=DEFAULT_BUILD)
    parser.add_argument("--repository", help="Local Git repository for offline tests")
    parser.add_argument("--source-only", action="store_true", help="Verify source without building")
    args = parser.parse_args()

    lock = json.loads(args.lock.read_text())
    if not re.fullmatch(r"[0-9a-f]{40}", lock["commit"]):
        raise ValueError("invalid glslang commit pin")
    if lock["repository"] != "https://github.com/KhronosGroup/glslang.git" or lock["tag"] != "15.4.0":
        raise ValueError("unexpected glslang source provenance")
    source = prepare_source(lock, args.source, args.repository)
    if args.source_only:
        print(source)
        return

    sdk = Path(os.environ.get("ANDROID_SDK_ROOT") or os.environ.get("ANDROID_HOME") or "")
    cmake = sdk / "cmake/3.31.5/bin/cmake"
    ninja = sdk / "cmake/3.31.5/bin/ninja"
    if not cmake.is_file() or not ninja.is_file():
        raise RuntimeError("Android SDK CMake 3.31.5 including Ninja is required for the host shader tool")
    build = args.build.resolve()
    subprocess.run([str(cmake), "-S", str(source), "-B", str(build), "-G", "Ninja",
                    f"-DCMAKE_MAKE_PROGRAM={ninja}", "-DCMAKE_BUILD_TYPE=Release",
                    "-DENABLE_OPT=OFF", "-DGLSLANG_TESTS=OFF",
                    "-DENABLE_GLSLANG_BINARIES=ON", "-DBUILD_EXTERNAL=OFF"], check=True)
    subprocess.run([str(cmake), "--build", str(build), "--target", lock["target"],
                    "--parallel", "2"], check=True)
    validator = build / "StandAlone" / lock["executable"]
    if not validator.is_file() or not os.access(validator, os.X_OK):
        raise RuntimeError("pinned glslang build produced no glslangValidator")
    subprocess.run([str(validator), "--version"], check=True)
    with tempfile.TemporaryDirectory(prefix="glslang-smoke-") as temp_name:
        shader = Path(temp_name) / "probe.comp"
        shader.write_text("#version 450\nlayout(local_size_x = 1) in;\nvoid main() {}\n")
        output = shader.with_suffix(".spv")
        subprocess.run([str(validator), "-V", "-S", "comp", "-o", str(output),
                        str(shader)], check=True)
        if not output.is_file() or output.stat().st_size == 0:
            raise RuntimeError("glslangValidator smoke compilation produced no SPIR-V")
    print(validator)


if __name__ == "__main__":
    main()
