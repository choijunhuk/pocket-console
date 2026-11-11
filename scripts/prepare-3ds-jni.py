#!/usr/bin/env python3
"""Verify built 3DS cores before exposing them to Android packaging."""

import argparse
from hashlib import sha256
import json
import os
from pathlib import Path
import shutil
import struct
import tempfile

ROOT = Path(__file__).resolve().parents[1]
LIBRARY = "libazahar_libretro.so"
MACHINES = {"arm64-v8a": 183, "x86_64": 62}


def digest(path):
    value = sha256()
    with path.open("rb") as source:
        while block := source.read(1024 * 1024):
            value.update(block)
    return value.hexdigest()


def prepare(source, output, lock_path):
    pin = json.loads(lock_path.read_text())
    verified = []
    for abi, machine in MACHINES.items():
        folder = source / abi
        if json.loads((folder / "source-manifest.json").read_text()) != pin:
            raise ValueError(f"3DS source manifest does not match the lock: {abi}")
        sums = {}
        for line in (folder / "SHA256SUMS").read_text().splitlines():
            parts = line.split()
            if len(parts) == 2:
                sums[parts[1].removeprefix("*")] = parts[0]
        binary = folder / LIBRARY
        if sums.get(LIBRARY) != digest(binary):
            raise ValueError(f"3DS binary SHA256 mismatch: {abi}")
        with binary.open("rb") as library:
            header = library.read(64)
        if (len(header) < 64 or header[:7] != b"\x7fELF\x02\x01\x01"
                or struct.unpack_from("<H", header, 18)[0] != machine):
            raise ValueError(f"3DS core does not match its 64-bit ABI: {abi}")
        verified.append((abi, binary))

    # Validate every input before replacing any previously packaged core.
    for abi, binary in verified:
        target = output / abi
        target.mkdir(parents=True, exist_ok=True)
        temporary = None
        try:
            with tempfile.NamedTemporaryFile(dir=target, suffix=".so", delete=False) as stream:
                temporary = Path(stream.name)
                with binary.open("rb") as library:
                    shutil.copyfileobj(library, stream)
            os.replace(temporary, target / LIBRARY)
        finally:
            if temporary is not None:
                temporary.unlink(missing_ok=True)


if __name__ == "__main__":
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--source", type=Path, default=ROOT / ".cache/3ds-cores")
    parser.add_argument("--output", type=Path, default=ROOT / ".cache/3ds-jni")
    parser.add_argument("--lock", type=Path, default=ROOT / "third_party/azahar.lock.json")
    args = parser.parse_args()
    prepare(args.source, args.output, args.lock)
    print("3DS_JNI_PREPARATION_PASS")
