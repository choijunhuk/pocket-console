#!/usr/bin/env python3
"""Check the generated original NRO header before uploading a smoke asset."""

from pathlib import Path
import struct
import sys


def verify(path):
    path = Path(path)
    with path.open("rb") as rom:
        header = rom.read(0x20)
    if len(header) != 0x20 or header[0x10:0x14] != b"NRO0":
        raise ValueError("original Switch NRO header is missing")
    declared = struct.unpack_from("<I", header, 0x18)[0]
    if declared < 0x20 or declared > path.stat().st_size:
        raise ValueError("Switch NRO size is outside the file")
    return declared


if __name__ == "__main__":
    print(f"SWITCH_HOMEBREW_BUILD_PASS {verify(sys.argv[1])} bytes")
