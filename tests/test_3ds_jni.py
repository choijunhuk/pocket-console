import hashlib
import importlib.util
import json
from pathlib import Path
import struct
import tempfile
import unittest

ROOT = Path(__file__).resolve().parents[1]
SPEC = importlib.util.spec_from_file_location("prepare_3ds_jni", ROOT / "scripts/prepare-3ds-jni.py")
MODULE = importlib.util.module_from_spec(SPEC)
SPEC.loader.exec_module(MODULE)


class JniPreparationTest(unittest.TestCase):
    def setUp(self):
        self.temp = tempfile.TemporaryDirectory()
        self.root = Path(self.temp.name)
        self.source = self.root / "cores"
        self.output = self.root / "jni"
        self.lock = self.root / "lock.json"
        self.pin = {"version": "2126.2", "source_commit": "a" * 40}
        self.lock.write_text(json.dumps(self.pin))
        for abi, machine in (("arm64-v8a", 183), ("x86_64", 62)):
            folder = self.source / abi
            folder.mkdir(parents=True)
            image = bytearray(64)
            image[:7] = b"\x7fELF\x02\x01\x01"
            struct.pack_into("<H", image, 18, machine)
            binary = folder / "libazahar_libretro.so"
            binary.write_bytes(image)
            (folder / "source-manifest.json").write_text(json.dumps(self.pin))
            digest = hashlib.sha256(image).hexdigest()
            (folder / "SHA256SUMS").write_text(f"{digest}  libazahar_libretro.so\n")

    def tearDown(self):
        self.temp.cleanup()

    def test_copies_both_verified_abis(self):
        MODULE.prepare(self.source, self.output, self.lock)
        for abi in ("arm64-v8a", "x86_64"):
            self.assertTrue((self.output / abi / "libazahar_libretro.so").is_file())
            self.assertEqual((self.output / abi / "libazahar_libretro.so").read_bytes(),
                             (self.source / abi / "libazahar_libretro.so").read_bytes())

    def test_rejects_corrupt_core_before_writing_any_output(self):
        (self.source / "x86_64/libazahar_libretro.so").write_bytes(b"broken")
        with self.assertRaises(ValueError):
            MODULE.prepare(self.source, self.output, self.lock)
        self.assertFalse(self.output.exists())

    def test_rejects_wrong_source_manifest(self):
        (self.source / "arm64-v8a/source-manifest.json").write_text('{}')
        with self.assertRaises(ValueError):
            MODULE.prepare(self.source, self.output, self.lock)
        self.assertFalse(self.output.exists())

    def test_rejects_abi_mismatch_even_with_matching_hash(self):
        folder = self.source / "arm64-v8a"
        image = bytearray((folder / "libazahar_libretro.so").read_bytes())
        struct.pack_into("<H", image, 18, 62)
        (folder / "libazahar_libretro.so").write_bytes(image)
        (folder / "SHA256SUMS").write_text(f"{hashlib.sha256(image).hexdigest()}  libazahar_libretro.so\n")
        with self.assertRaises(ValueError):
            MODULE.prepare(self.source, self.output, self.lock)
        self.assertFalse(self.output.exists())


if __name__ == "__main__":
    unittest.main()
