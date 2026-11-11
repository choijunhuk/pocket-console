import importlib.util
from pathlib import Path
import struct
import tempfile
import unittest
from zipfile import ZipFile


SCRIPT = Path(__file__).resolve().parents[1] / "scripts/verify-release-apk.py"
SPEC = importlib.util.spec_from_file_location("verify_release_apk", SCRIPT)
release_apk = importlib.util.module_from_spec(SPEC)
SPEC.loader.exec_module(release_apk)


def elf(machine, alignment):
    data = bytearray(120)
    data[:7] = b"\x7fELF\x02\x01\x01"
    struct.pack_into("<H", data, 18, machine)
    struct.pack_into("<Q", data, 32, 64)
    struct.pack_into("<HH", data, 54, 56, 1)
    struct.pack_into("<I", data, 64, 1)
    struct.pack_into("<Q", data, 64 + 48, alignment)
    return data


class ReleaseApkTests(unittest.TestCase):
    def setUp(self):
        self.temp = tempfile.TemporaryDirectory()
        self.addCleanup(self.temp.cleanup)
        self.apk = Path(self.temp.name) / "app.apk"

    def make_apk(self, changed=None):
        with ZipFile(self.apk, "w") as package:
            for abi, machine in release_apk.MACHINES.items():
                for library in release_apk.LIBRARIES:
                    name = f"lib/{abi}/{library}"
                    if changed and name == changed[0]:
                        if changed[1] is None:
                            continue
                        package.writestr(name, elf(machine, changed[1]))
                    else:
                        package.writestr(name, elf(machine, 16384))

    def test_accepts_both_verified_abis(self):
        self.make_apk()
        release_apk.verify(self.apk)

    def test_rejects_misaligned_library(self):
        self.make_apk(("lib/arm64-v8a/libpocket_ds.so", 4096))
        with self.assertRaisesRegex(ValueError, "below 16 KB"):
            release_apk.verify(self.apk)

    def test_rejects_missing_library(self):
        self.make_apk(("lib/x86_64/libretro.so", None))
        with self.assertRaisesRegex(ValueError, "Missing native libraries"):
            release_apk.verify(self.apk)


if __name__ == "__main__":
    unittest.main()
