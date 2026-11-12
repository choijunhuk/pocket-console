import importlib.util
from contextlib import redirect_stdout
import io
import json
import tarfile
import tempfile
import unittest
from pathlib import Path
from unittest.mock import patch


spec = importlib.util.spec_from_file_location("switch_sdk_prepare", Path(__file__).with_name("prepare.py"))
prepare = importlib.util.module_from_spec(spec)
spec.loader.exec_module(prepare)


class PrepareTest(unittest.TestCase):
    def setUp(self):
        self.temp = tempfile.TemporaryDirectory()
        self.addCleanup(self.temp.cleanup)
        self.base = Path(self.temp.name)
        self.sdk = self.base / "sdk"
        runtime = self.sdk / "runtime" / "arm64-v8a"
        runtime.mkdir(parents=True)
        self.binary = runtime / "libpocket_switch2.so"
        self.binary.write_bytes(b"test-only closure bytes")
        (self.sdk / "runtime-deps.json").write_text(json.dumps({
            "abi": "arm64-v8a", "android_api": 33,
            "libraries": [{"name": self.binary.name, "sha256": prepare.digest(self.binary),
                           "needed": ["liblog.so"], "origin": "test"}],
            "system_needed": ["liblog.so"],
        }))
        with tarfile.open(self.sdk / "sdl-java-bootstrap.tar.gz", "w:gz") as archive:
            data = b"package org.libsdl.app; class SDL {}"
            member = tarfile.TarInfo("org/libsdl/app/SDL.java")
            member.size = len(data)
            archive.addfile(member, io.BytesIO(data))
        self.nro = self.base / "smoke.nro"
        image = bytearray(0x80)
        image[0x10:0x14] = b"NRO0"
        self.nro.write_bytes(image)
        self.stage_root = self.base / "tests" / "android-switch-sdk"
        production = self.base / "app" / "src" / "main" / "java"
        for name in prepare.PRODUCTION_JAVA:
            source = production / name
            source.parent.mkdir(parents=True, exist_ok=True)
            source.write_text("// source used by staging test\n")

    def test_stages_exact_verified_closure(self):
        with patch.object(prepare, "ROOT", self.stage_root), redirect_stdout(io.StringIO()):
            prepare.stage(self.sdk, self.nro, self.base)
        self.assertEqual((self.stage_root / ".cache/sdk/runtime/arm64-v8a"
                          / self.binary.name).read_bytes(), self.binary.read_bytes())
        self.assertTrue((self.stage_root / ".cache/sdl-java/org/libsdl/app/SDL.java").is_file())
        self.assertTrue((self.stage_root / ".cache/linked-java"
                         / prepare.PRODUCTION_JAVA[0]).is_symlink())
        self.assertEqual((self.stage_root / ".cache/linked-java"
                          / prepare.PRODUCTION_JAVA[0]).resolve(),
                         (self.base / "app/src/main/java" / prepare.PRODUCTION_JAVA[0]).resolve())
        self.assertEqual((self.stage_root / ".cache/test-assets/smoke.nro").read_bytes(),
                         self.nro.read_bytes())

    def test_rejects_extra_runtime_library(self):
        (self.binary.parent / "libextra.so").write_bytes(b"extra")
        with patch.object(prepare, "ROOT", self.stage_root):
            with self.assertRaisesRegex(ValueError, "DSO set differs"):
                prepare.stage(self.sdk, self.nro)

    def test_rejects_runtime_checksum_mismatch(self):
        self.binary.write_bytes(b"changed")
        with patch.object(prepare, "ROOT", self.stage_root):
            with self.assertRaisesRegex(ValueError, "checksum mismatch"):
                prepare.stage(self.sdk, self.nro)


if __name__ == "__main__":
    unittest.main()
