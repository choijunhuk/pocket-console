#!/usr/bin/env python3
"""Verify the diagnostic patch against exact Azahar renderer bytes."""

from hashlib import sha256
from pathlib import Path
import shutil
import subprocess
import tempfile
import unittest


ROOT = Path(__file__).resolve().parent.parent
PATCH = ROOT / "patches/azahar-host-render-diag.patch"
BASE_HASH = "22006232527c236f72cfd2bc1310e3f198c5e2a88a6fbb0a700062b10880f09e"
PATCHED_HASH = "11ea5e71564c08c77ca2486d27884a226124cc5fedc7f8962a0d04e845e817f0"
PATCH_HASH = "84b04abd60e6abf27bf35b8be8daeee9098dbb706b9cf52e920bbb000afeda57"
RELATIVE = Path("src/video_core/renderer_opengl/renderer_opengl.cpp")


class AzaharHostDiagnosticTest(unittest.TestCase):
    def test_exact_patch_round_trip(self):
        self.assertEqual(sha256(PATCH.read_bytes()).hexdigest(), PATCH_HASH)
        source = ROOT / ".cache/azahar-host-source" / RELATIVE
        if not source.is_file():
            source = ROOT / ".cache/azahar-renderer-base.cpp"
        if not source.is_file():
            self.skipTest("exact Azahar source is unavailable until host preparation")

        with tempfile.TemporaryDirectory() as temporary:
            target = Path(temporary) / RELATIVE
            target.parent.mkdir(parents=True)
            shutil.copyfile(source, target)
            current = sha256(target.read_bytes()).hexdigest()
            self.assertIn(current, (BASE_HASH, PATCHED_HASH))
            if current == PATCHED_HASH:
                subprocess.run(["git", "apply", "--reverse", "--check", str(PATCH)],
                               cwd=temporary, check=True)
                subprocess.run(["git", "apply", "--reverse", str(PATCH)],
                               cwd=temporary, check=True)
                self.assertEqual(sha256(target.read_bytes()).hexdigest(), BASE_HASH)
            subprocess.run(["git", "apply", "--check", str(PATCH)], cwd=temporary, check=True)
            subprocess.run(["git", "apply", str(PATCH)], cwd=temporary, check=True)
            self.assertEqual(sha256(target.read_bytes()).hexdigest(), PATCHED_HASH)


if __name__ == "__main__":
    unittest.main()
