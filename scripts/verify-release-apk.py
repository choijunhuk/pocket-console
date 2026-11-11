#!/usr/bin/env python3
"""Reject APKs whose packaged native libraries cannot load on 16 KB devices."""

from pathlib import Path
import struct
import sys
from zipfile import ZipFile


MACHINES = {"arm64-v8a": 183, "x86_64": 62}
LIBRARIES = {"libazahar_libretro.so", "libc++_shared.so", "libpocket_3ds.so",
             "libpocket_ds.so", "libretro.so"}
PAGE = 16384


def check_elf(data, machine, name):
    if len(data) < 64 or data[:7] != b"\x7fELF\x02\x01\x01":
        raise ValueError(f"Invalid 64-bit ELF: {name}")
    if struct.unpack_from("<H", data, 18)[0] != machine:
        raise ValueError(f"Native library ABI mismatch: {name}")
    offset = struct.unpack_from("<Q", data, 32)[0]
    entry_size, count = struct.unpack_from("<HH", data, 54)
    if entry_size < 56 or count == 0 or offset + entry_size * count > len(data):
        raise ValueError(f"Invalid ELF program headers: {name}")
    alignments = []
    for index in range(count):
        header = offset + index * entry_size
        kind, = struct.unpack_from("<I", data, header)
        if kind == 1:
            alignments.append(struct.unpack_from("<Q", data, header + 48)[0])
    if not alignments or min(alignments) < PAGE:
        raise ValueError(f"Native library has a LOAD segment below 16 KB alignment: {name}")


def verify(apk):
    required = {f"lib/{abi}/{library}" for abi in MACHINES for library in LIBRARIES}
    with ZipFile(apk) as package:
        found = set()
        for name in package.namelist():
            if name.startswith("lib/") and name.endswith(".so"):
                parts = name.split("/")
                if len(parts) != 3 or parts[1] not in MACHINES:
                    raise ValueError(f"Unsupported native ABI: {name}")
                check_elf(package.read(name), MACHINES[parts[1]], name)
                found.add(name)
        if missing := required - found:
            raise ValueError(f"Missing native libraries: {', '.join(sorted(missing))}")
    print(f"APK_ELF_16K_PASS {apk}")


if __name__ == "__main__":
    verify(Path(sys.argv[1]))
