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
REPOSITORIES = {
    "Hokkaido": "https://github.com/pavelgajdo/Hokkaido.git",
    "Eden": "https://github.com/eden-emulator/mirror.git",
}


def git(*args, cwd=None):
    result = subprocess.run(["git", *args], cwd=cwd, text=True,
                            stdout=subprocess.PIPE, stderr=subprocess.PIPE)
    if result.returncode:
        raise RuntimeError(f"git {args[0]} failed: {result.stderr.strip()}")
    return result.stdout.strip()


def verify_source(output, lock):
    name = lock["name"]
    if git("rev-parse", "HEAD", cwd=output) != lock["commit"]:
        raise ValueError(f"{name} source commit mismatch")
    if subprocess.run(["git", "symbolic-ref", "-q", "HEAD"], cwd=output,
                      stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL).returncode == 0:
        raise ValueError(f"{name} source must be detached")
    status = git("submodule", "status", "--recursive", cwd=output)
    if any(line.startswith(("-", "+", "U")) for line in status.splitlines()):
        raise ValueError(f"{name} submodule is missing or differs from its pinned gitlink")
    if git("status", "--porcelain", "--untracked-files=no", "--ignore-submodules=untracked", cwd=output):
        raise ValueError(f"{name} source has modified tracked files")
    nested = subprocess.run(["git", "submodule", "foreach", "--quiet", "--recursive",
                             "git diff --quiet HEAD --"], cwd=output,
                            stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL)
    if nested.returncode:
        raise ValueError(f"{name} submodule has modified tracked files")
    if not (output / "CMakeLists.txt").is_file() or not (
            output / "src/android/app/src/main/jni/CMakeLists.txt").is_file():
        raise ValueError(f"{name} Android native target is missing")
    return status


def prepare(lock_path, output, repository=None):
    lock = json.loads(lock_path.read_text())
    name = lock.get("name")
    if not re.fullmatch(r"[0-9a-f]{40}", lock["commit"]):
        raise ValueError("invalid Switch source commit pin")
    if lock["abi"] != "arm64-v8a":
        raise ValueError("Switch source probe supports arm64-v8a only")
    if name not in REPOSITORIES or lock["repository"] != REPOSITORIES[name]:
        raise ValueError("Switch source repository is not allowlisted")
    if name == "Hokkaido" and not re.fullmatch(r"[A-Za-z0-9_.-]+", lock.get("tag", "")):
        raise ValueError("invalid Hokkaido tag")
    if name == "Eden" and lock.get("ref") != "master":
        raise ValueError("unexpected Eden source reference")

    output = output.resolve()
    if output.exists():
        if not (output / MARKER).is_file() or json.loads((output / MARKER).read_text()) != lock:
            raise ValueError(f"existing {name} source has a different lock: {output}")
        verify_source(output, lock)
        return output

    output.parent.mkdir(parents=True, exist_ok=True)
    with tempfile.TemporaryDirectory(prefix=f"{name.lower()}-source-", dir=output.parent) as temp_name:
        source = Path(temp_name) / "source"
        if name == "Hokkaido":
            git("clone", "--depth", "1", "--single-branch", "--branch", lock["tag"],
                repository or lock["repository"], str(source))
            if git("rev-parse", "HEAD", cwd=source) != lock["commit"]:
                raise ValueError("Hokkaido tag resolved to a different commit")
        else:
            git("init", "-q", str(source))
            git("remote", "add", "origin", repository or lock["repository"], cwd=source)
            git("fetch", "--depth", "1", "origin", lock["commit"], cwd=source)
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
