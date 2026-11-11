#!/usr/bin/env python3
"""Fetch exact Khronos headers for the Linux host smoke build."""

from hashlib import sha256
import json
from pathlib import Path
import sys
import tempfile
from urllib.request import Request, urlopen


ROOT = Path(__file__).resolve().parent.parent
LOCK = ROOT / "third_party/host-graphics.lock.json"
OUTPUT = ROOT / ".cache/host-graphics/include"
PINNED = {
    "KhronosGroup/EGL-Registry": (
        "8e56e86e65687697ef54114cdb1afabdfac756bf",
        {"api/EGL/egl.h", "api/EGL/eglext.h", "api/EGL/eglplatform.h",
         "api/KHR/khrplatform.h"},
    ),
    "KhronosGroup/OpenGL-Registry": (
        "6af574a14089ccfee87efe230ebcdd8742859813",
        {"api/GL/glcorearb.h"},
    ),
}


def load_specs(path=LOCK):
    lock = json.loads(Path(path).read_text())
    if lock.get("version") != 1 or len(lock.get("sources", [])) != len(PINNED):
        raise ValueError("Unexpected host graphics lock format")
    specs = []
    seen = set()
    for source in lock["sources"]:
        repository = source.get("repository")
        if repository not in PINNED or repository in seen:
            raise ValueError("Unexpected Khronos repository")
        seen.add(repository)
        commit, paths = PINNED[repository]
        if source.get("commit") != commit:
            raise ValueError("Khronos commit does not match the reviewed pin")
        files = source.get("files", [])
        if {item.get("path") for item in files} != paths or len(files) != len(paths):
            raise ValueError("Khronos header paths do not match the reviewed pin")
        for item in files:
            digest = item.get("sha256", "")
            if len(digest) != 64 or any(char not in "0123456789abcdef" for char in digest):
                raise ValueError("Invalid Khronos header SHA256")
            header = item["path"].removeprefix("api/")
            specs.append((header, digest,
                          f"https://raw.githubusercontent.com/{repository}/{commit}/{item['path']}"))
    return specs


def download(url):
    with urlopen(Request(url, headers={"User-Agent": "pocket-console-host-graphics"}),
                 timeout=30) as response:
        return response.read()


def matches(output, specs):
    if not output.is_dir() or output.is_symlink():
        return False
    files = {path.relative_to(output).as_posix() for path in output.rglob("*") if path.is_file()}
    if files != {name for name, _, _ in specs}:
        return False
    if any(path.is_symlink() for path in output.rglob("*")):
        return False
    return all(sha256((output / name).read_bytes()).hexdigest() == digest
               for name, digest, _ in specs)


def prepare(output=OUTPUT, specs=None, fetch=download):
    output = Path(output)
    specs = load_specs() if specs is None else specs
    if matches(output, specs):
        return "reused"
    if output.is_symlink():
        raise ValueError("Refusing symlinked host graphics output")
    payloads = {}
    for name, digest, url in specs:
        data = fetch(url)
        if sha256(data).hexdigest() != digest:
            raise ValueError(f"Khronos header SHA256 mismatch: {name}")
        payloads[name] = data
    output.parent.mkdir(parents=True, exist_ok=True)
    with tempfile.TemporaryDirectory(prefix="host-graphics-", dir=output.parent) as temporary:
        stage = Path(temporary) / "include"
        for name, data in payloads.items():
            target = stage / name
            target.parent.mkdir(parents=True, exist_ok=True)
            target.write_bytes(data)
        backup = Path(temporary) / "previous"
        if output.exists():
            output.replace(backup)
        try:
            stage.replace(output)
        except OSError:
            if backup.exists():
                backup.replace(output)
            raise
    return "prepared"


if __name__ == "__main__":
    try:
        result = prepare()
        print(f"Host Khronos headers {result}: {OUTPUT}")
    except (OSError, ValueError) as error:
        print(f"Host Khronos header preparation failed: {error}", file=sys.stderr)
        sys.exit(1)
