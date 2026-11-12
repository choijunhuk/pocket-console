import importlib.util
import hashlib
import io
from contextlib import redirect_stdout
import json
import tempfile
import unittest
import zipfile
from pathlib import Path


spec = importlib.util.spec_from_file_location("switch_sdk_verify_apk", Path(__file__).with_name("verify_apk.py"))
verify_apk = importlib.util.module_from_spec(spec)
spec.loader.exec_module(verify_apk)


class VerifyApkTest(unittest.TestCase):
    def setUp(self):
        self.temp = tempfile.TemporaryDirectory()
        self.addCleanup(self.temp.cleanup)
        self.root = Path(self.temp.name)
        self.apk = self.root / "app.apk"
        content = b"test-only ELF bytes"
        self.digest = hashlib.sha256(content).hexdigest()
        (self.root / "runtime-deps.json").write_text(json.dumps({
            "libraries": [{"name": "libpocket_switch2.so", "sha256": self.digest}]
        }))
        with zipfile.ZipFile(self.apk, "w") as package:
            package.writestr("lib/arm64-v8a/libpocket_switch2.so", content)

    def test_accepts_exact_arm64_closure(self):
        with redirect_stdout(io.StringIO()):
            verify_apk.verify(self.apk, self.root)

    def test_rejects_extra_x86_library(self):
        with zipfile.ZipFile(self.apk, "a") as package:
            package.writestr("lib/x86_64/libextra.so", b"extra")
        with self.assertRaisesRegex(ValueError, "x86 native code"):
            verify_apk.verify(self.apk, self.root)


if __name__ == "__main__":
    unittest.main()
