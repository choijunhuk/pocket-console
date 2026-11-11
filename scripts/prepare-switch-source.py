#!/usr/bin/env python3
"""Fetch the exact detached Hokkaido source and its pinned Git submodules."""

import argparse
import json
from pathlib import Path
import re
import subprocess
import tempfile


ROOT = Path(__file__).resolve().parents[1]
DEFAULT_LOCK = ROOT / "third_party/switch-hokkaido.lock.json"
DEFAULT_OUTPUT = ROOT / ".cache/hokkaido-source"
MARKER = ".pocket-console-source.json"


def git(*args, cwd=None):
    result = subprocess.run(["git", *args], cwd=cwd, text=True,
                            stdout=subprocess.PIPE, stderr=subprocess.PIPE)
    if result.returncode:
        raise RuntimeError(f"git {args[0]} failed: {result.stderr.strip()}")
    return result.stdout.strip()


def verify_source(output, lock):
    if git("rev-parse", "HEAD", cwd=output) != lock["commit"]:
        raise ValueError("Hokkaido source commit mismatch")
    if subprocess.run(["git", "symbolic-ref", "-q", "HEAD"], cwd=output,
                      stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL).returncode == 0:
        raise ValueError("Hokkaido source must be detached")
    status = git("submodule", "status", "--recursive", cwd=output)
    if any(line.startswith(("-", "+", "U")) for line in status.splitlines()):
        raise ValueError("Hokkaido submodule is missing or differs from its pinned gitlink")
    if git("status", "--porcelain", "--untracked-files=no", "--ignore-submodules=untracked", cwd=output):
        raise ValueError("Hokkaido source has modified tracked files")
    nested = subprocess.run(["git", "submodule", "foreach", "--quiet", "--recursive",
                             "git diff --quiet HEAD --"], cwd=output,
                            stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL)
    if nested.returncode:
        raise ValueError("Hokkaido submodule has modified tracked files")
    if not (output / "CMakeLists.txt").is_file() or not (
            output / "src/android/app/src/main/jni/CMakeLists.txt").is_file():
        raise ValueError("Hokkaido Android native target is missing")
    return status


def prepare(lock_path, output, repository=None):
    lock = json.loads(lock_path.read_text())
    if not re.fullmatch(r"[0-9a-f]{40}", lock["commit"]):
        raise ValueError("invalid Hokkaido commit pin")
    if lock["abi"] != "arm64-v8a":
        raise ValueError("Hokkaido probe supports arm64-v8a only")
    if not re.fullmatch(r"https://github\.com/[A-Za-z0-9_.-]+/[A-Za-z0-9_.-]+\.git",
                        lock["repository"]):
        raise ValueError("Hokkaido repository must be a credential-free GitHub HTTPS URL")
    if not re.fullmatch(r"[A-Za-z0-9_.-]+", lock["tag"]):
        raise ValueError("invalid Hokkaido tag")

    output = output.resolve()
    if output.exists():
        if not (output / MARKER).is_file() or json.loads((output / MARKER).read_text()) != lock:
            raise ValueError(f"existing Hokkaido source has a different lock: {output}")
        verify_source(output, lock)
        return output

    output.parent.mkdir(parents=True, exist_ok=True)
    with tempfile.TemporaryDirectory(prefix="hokkaido-source-", dir=output.parent) as temp_name:
        source = Path(temp_name) / "source"
        git("clone", "--depth", "1", "--single-branch", "--branch", lock["tag"],
            repository or lock["repository"], str(source))
        if git("rev-parse", "HEAD", cwd=source) != lock["commit"]:
            raise ValueError("Hokkaido tag resolved to a different commit")
        git("checkout", "--detach", lock["commit"], cwd=source)
        git("submodule", "update", "--init", "--recursive", cwd=source)
        status = verify_source(source, lock)
        (source / MARKER).write_text(json.dumps(lock, indent=2) + "\n")
        (source / ".pocket-console-submodules.txt").write_text(status + "\n" if status else "")
        source.rename(output)
    return output


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--lock", type=Path, default=DEFAULT_LOCK)
    parser.add_argument("--output", type=Path, default=DEFAULT_OUTPUT)
    parser.add_argument("--repository", help="Local Git repository for offline tests")
    args = parser.parse_args()
    print(prepare(args.lock, args.output, args.repository))


if __name__ == "__main__":
    main()
