#!/usr/bin/env python3
"""Apply the reviewed save-close fix to the pinned melonDS checkout."""

from hashlib import sha256
from pathlib import Path
import subprocess
import sys


ROOT = Path(__file__).resolve().parent.parent
CORE = ROOT / "third_party" / "melonDS"
PIN = "66b5d2634cd0a79030562811e6e05f5532f800ba"
PATCH = ROOT / "patches" / "melonds-save-close.patch"
BASE = {
    "src/libretro/libretro.cpp": "e9a63878f0ca803a43c2c36c37d0991c03baf3cde750c9a45a8ad2bf69854d37",
    "src/NDSCart_SRAMManager.cpp": "8689b109939bb2fdc182f2fe338fe5ef053e342e7c8609d9f955b68a2a383218",
    "src/dolphin/CommonFuncs.cpp": "b65ab444408cff3f04dfa91c5a3002f852959a3840d89da4f588d06e83a2e75e",
}
PATCHED = {
    "src/libretro/libretro.cpp": "4cf979bdd3bc39f322c9143dd67153a78b7c4ffcda4fd5fdaa2f5de63d8638ca",
    "src/NDSCart_SRAMManager.cpp": "8e0baedfe1ede93b37597e18f98c834d44bc50ffd3f71a2f72277f2f27c653ad",
    "src/dolphin/CommonFuncs.cpp": "9a03d896dbd964a3bed0da24a604c1a5851f6e6ab4a496b2b632e909bbfdabf7",
}


def main() -> int:
    if not (CORE / "Makefile").is_file():
        print("melonDS submodule is absent; initialize third_party/melonDS", file=sys.stderr)
        return 2
    head = subprocess.check_output(
        ["git", "-C", str(CORE), "rev-parse", "HEAD"], text=True
    ).strip()
    if head != PIN:
        print(f"melonDS checkout is {head}, expected {PIN}", file=sys.stderr)
        return 2
    hashes = {name: sha256((CORE / name).read_bytes()).hexdigest() for name in BASE}
    if hashes == PATCHED:
        print("melonDS save-close patch already applied")
        return 0
    if hashes != BASE:
        print("melonDS sources differ from the reviewed pin; refusing patch", file=sys.stderr)
        return 2
    subprocess.run(["git", "apply", "--check", str(PATCH)], cwd=CORE, check=True)
    subprocess.run(["git", "apply", str(PATCH)], cwd=CORE, check=True)
    hashes = {name: sha256((CORE / name).read_bytes()).hexdigest() for name in BASE}
    if hashes != PATCHED:
        print("melonDS patch result differs from reviewed sources", file=sys.stderr)
        return 2
    print("melonDS save-close patch applied")
    return 0


if __name__ == "__main__":
    sys.exit(main())
