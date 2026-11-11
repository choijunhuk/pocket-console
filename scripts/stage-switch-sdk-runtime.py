#!/usr/bin/env python3
"""Stage the exact ARM64 shared-library closure of the Pocket Switch SDK."""

import hashlib
import json
from pathlib import Path
import re
import shutil
import subprocess
import sys


def digest(path):
    checksum = hashlib.sha256()
    with path.open("rb") as source:
        for block in iter(lambda: source.read(1024 * 1024), b""):
            checksum.update(block)
    return checksum.hexdigest()


def inspect_elf(readelf, path):
    try:
        header = subprocess.check_output([readelf, "-h", str(path)], text=True,
                                         stderr=subprocess.DEVNULL)
    except subprocess.CalledProcessError:
        return None
    if not re.search(r"Machine:\s+AArch64\b", header):
        return None
    dynamic = subprocess.check_output([readelf, "-d", str(path)], text=True)
    needed = re.findall(r"\(NEEDED\).*\[([^\]]+)\]", dynamic)
    sonames = re.findall(r"\(SONAME\).*\[([^\]]+)\]", dynamic)
    if len(sonames) != 1:
        raise ValueError(f"Expected one SONAME in {path}")
    return sonames[0], needed


def stage_bundle(stage, build, source, ndk, inspect):
    stage, build, source, ndk = map(Path, (stage, build, source, ndk))
    sysroot = ndk / "toolchains/llvm/prebuilt/linux-x86_64/sysroot/usr/lib/aarch64-linux-android"
    system_dir = sysroot / "33"
    cxx = sysroot / "libc++_shared.so"
    if not system_dir.is_dir() or not cxx.is_file():
        raise ValueError("Pinned NDK ARM64 API 33 runtime is missing")
    system = {path.name for path in system_dir.glob("*.so")}
    system.discard("libc++_shared.so")
    main = stage / "libpocket_switch2.so"
    if not main.is_file():
        raise ValueError("Pocket Switch SDK binary is missing")
    runtime = stage / "runtime/arm64-v8a"
    runtime.mkdir(parents=True, exist_ok=False)
    candidates = {}
    for origin, root in (("build", build), ("source", source)):
        for path in root.rglob("*.so"):
            if path.is_file():
                candidates.setdefault(path.name, []).append((origin, path))

    pending = [(main.name, "sdk", main)]
    visited = set()
    libraries = []
    system_needed = set()
    while pending:
        name, origin, path = pending.pop(0)
        if name in visited:
            continue
        info = inspect(path)
        if info is None or info[0] != name:
            raise ValueError(f"Wrong ABI or SONAME for {name}: {path}")
        soname, needed = info
        destination = runtime / name
        shutil.copy2(path, destination)
        libraries.append({"name": soname, "sha256": digest(destination),
                          "needed": sorted(set(needed)), "origin": origin})
        visited.add(name)
        for dependency in needed:
            if Path(dependency).name != dependency or not dependency.endswith(".so"):
                raise ValueError(f"Unexpected DT_NEEDED name: {dependency}")
            if dependency in system:
                system_needed.add(dependency)
                continue
            if dependency in visited or any(item[0] == dependency for item in pending):
                continue
            if dependency == "libc++_shared.so":
                pending.append((dependency, "ndk-28.2.13676358", cxx))
                continue
            matches = []
            for candidate_origin, candidate in candidates.get(dependency, []):
                candidate_info = inspect(candidate)
                if candidate_info is not None and candidate_info[0] == dependency:
                    matches.append((candidate_origin, candidate))
            if not matches:
                raise ValueError(f"Missing ARM64 runtime dependency: {dependency}")
            built = [match for match in matches if match[0] == "build"]
            if built:
                matches = built
            if len({digest(candidate) for _, candidate in matches}) != 1:
                raise ValueError(f"Conflicting ARM64 runtime dependency: {dependency}")
            pending.append((dependency, *matches[0]))

    manifest = {"abi": "arm64-v8a", "android_api": 33,
                "libraries": sorted(libraries, key=lambda item: item["name"]),
                "system_needed": sorted(system_needed)}
    (stage / "runtime-deps.json").write_text(json.dumps(manifest, indent=2) + "\n")
    return manifest


if __name__ == "__main__":
    if len(sys.argv) != 6:
        raise SystemExit("usage: stage-switch-sdk-runtime.py stage build source ndk readelf")
    stage, build, source, ndk, readelf = sys.argv[1:]
    stage_bundle(stage, build, source, ndk, lambda path: inspect_elf(readelf, path))
