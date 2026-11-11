import hashlib
import io
import json
from pathlib import Path
import subprocess
import sys
import tarfile
import tempfile
import unittest


ROOT = Path(__file__).resolve().parents[1]
SCRIPT = ROOT / "scripts" / "prepare-3ds-source.py"
TOP = "azahar-unified-source-2126.2"


class SourcePreparationTest(unittest.TestCase):
    def setUp(self):
        self.temp = tempfile.TemporaryDirectory()
        self.addCleanup(self.temp.cleanup)
        self.dir = Path(self.temp.name)
        self.archive = self.dir / "source.tar.xz"
        self.output = self.dir / "source"
        self.lock = self.dir / "lock.json"

    def make_archive(self, extra=None):
        with tarfile.open(self.archive, "w:xz") as tar:
            for name, data in [(f"{TOP}/CMakeLists.txt", b"project(citra)"), *(extra or [])]:
                info = tarfile.TarInfo(name)
                info.size = len(data)
                tar.addfile(info, io.BytesIO(data))
        self.lock.write_text(json.dumps({"version": "2126.2", "source_commit": "219d743a1e698101fe1ab8bef43e6563126c3c27", "source_url": "https://example.invalid/source.tar.xz", "source_sha256": hashlib.sha256(self.archive.read_bytes()).hexdigest(), "archive_root": TOP}))

    def run_prepare(self):
        return subprocess.run([sys.executable, str(SCRIPT), "--lock", str(self.lock), "--archive", str(self.archive), "--output", str(self.output)], text=True, capture_output=True)

    def test_extracts_verified_source_and_writes_marker(self):
        self.make_archive()
        result = self.run_prepare()
        self.assertEqual(result.returncode, 0, result.stderr)
        self.assertTrue((self.output / "CMakeLists.txt").is_file())
        self.assertEqual(json.loads((self.output / ".pocket-console-source.json").read_text())["source_sha256"], json.loads(self.lock.read_text())["source_sha256"])

    def test_rejects_hash_mismatch_without_output(self):
        self.make_archive()
        self.archive.write_bytes(self.archive.read_bytes() + b"tamper")
        self.assertNotEqual(self.run_prepare().returncode, 0)
        self.assertFalse(self.output.exists())

    def test_rejects_path_traversal_without_writing_outside(self):
        self.make_archive([(f"{TOP}/../../escaped.txt", b"escape")])
        self.assertNotEqual(self.run_prepare().returncode, 0)
        self.assertFalse((self.dir / "escaped.txt").exists())
        self.assertFalse(self.output.exists())

    def test_rejects_symlink_outside_archive(self):
        with tarfile.open(self.archive, "w:xz") as tar:
            info = tarfile.TarInfo(f"{TOP}/CMakeLists.txt")
            data = b"project(citra)"
            info.size = len(data)
            tar.addfile(info, io.BytesIO(data))
            link = tarfile.TarInfo(f"{TOP}/external")
            link.type = tarfile.SYMTYPE
            link.linkname = "../../escaped.txt"
            tar.addfile(link)
        self.lock.write_text(json.dumps({"version": "2126.2", "source_commit": "219d743a1e698101fe1ab8bef43e6563126c3c27", "source_url": "https://example.invalid/source.tar.xz", "source_sha256": hashlib.sha256(self.archive.read_bytes()).hexdigest(), "archive_root": TOP}))
        self.assertNotEqual(self.run_prepare().returncode, 0)
        self.assertFalse(self.output.exists())


if __name__ == "__main__":
    unittest.main()
