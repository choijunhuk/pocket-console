from pathlib import Path
import shutil
import subprocess
import tempfile
import unittest


SCRIPT = Path(__file__).resolve().parents[1] / "scripts/build-switch1-sdk.sh"


class EdenSdkArtifactTests(unittest.TestCase):
    def setUp(self):
        self.temp = tempfile.TemporaryDirectory()
        self.addCleanup(self.temp.cleanup)
        self.root = Path(self.temp.name)
        (self.root / "scripts").mkdir()
        self.script = self.root / "scripts/build-switch1-sdk.sh"
        shutil.copyfile(SCRIPT, self.script)
        self.probes = self.root / ".cache/switch-probes"
        self.probes.mkdir(parents=True)

    def run_script(self):
        return subprocess.run(["bash", str(self.script)], text=True, capture_output=True)

    def test_existing_sdk_artifact_is_preserved(self):
        target = self.probes / "pocket-switch-arm64-v8a"
        target.mkdir()
        marker = target / "keep.txt"
        marker.write_text("existing artifact\n")
        result = self.run_script()
        self.assertEqual(result.returncode, 2)
        self.assertIn("already exists", result.stderr)
        self.assertEqual(marker.read_text(), "existing artifact\n")

    def test_corrupt_source_probe_cannot_publish_sdk(self):
        original = self.probes / "eden-arm64-v8a"
        original.mkdir()
        (original / "source-lock.json").write_text("source\n")
        (original / "SHA256SUMS").write_text("0" * 64 + "  source-lock.json\n")
        result = self.run_script()
        self.assertNotEqual(result.returncode, 0)
        self.assertIn("FAILED", result.stdout)
        self.assertFalse((self.probes / "pocket-switch-arm64-v8a").exists())
        self.assertEqual((original / "source-lock.json").read_text(), "source\n")


if __name__ == "__main__":
    unittest.main()
