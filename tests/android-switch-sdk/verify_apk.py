#!/usr/bin/env python3
"""Confirm the isolated APK packages the exact verified ARM64 runtime closure."""

import argparse
import hashlib
import json
import zipfile
from pathlib import Path


def verify(apk, sdk):
    manifest = json.loads((sdk / "runtime-deps.json").read_text())
    expected = {entry["name"]: entry["sha256"] for entry in manifest["libraries"]}
    with zipfile.ZipFile(apk) as package:
        actual = {name.removeprefix("lib/arm64-v8a/"): name
                  for name in package.namelist() if name.startswith("lib/arm64-v8a/")
                  and name.endswith(".so")}
        if set(actual) != set(expected):
            raise ValueError(f"APK ARM64 DSO set differs: expected={sorted(expected)} actual={sorted(actual)}")
        if any(name.startswith("lib/x86") for name in package.namelist()):
            raise ValueError("APK unexpectedly packages x86 native code")
        for name, wanted in expected.items():
            found = hashlib.sha256(package.read(actual[name])).hexdigest()
            if found != wanted:
                raise ValueError(f"APK DSO checksum mismatch: {name}")
    print(f"SWITCH_SDK_APK_CLOSURE_PASS dsos={len(expected)}")


if __name__ == "__main__":
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("apk", type=Path)
    parser.add_argument("sdk", type=Path)
    args = parser.parse_args()
    verify(args.apk, args.sdk)
