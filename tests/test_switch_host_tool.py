import json
from pathlib import Path
import subprocess
import sys
import tempfile
import unittest


ROOT = Path(__file__).resolve().parents[1]
SCRIPT = ROOT / "scripts/prepare-switch-host-tool.py"
PIN = "8a85691a0740d390761a1008b4696f57facd02c4"


def git(path, *args):
    return subprocess.check_output(["git", "-C", str(path), *args], text=True).strip()


class HostToolPreparationTest(unittest.TestCase):
    def setUp(self):
        self.temp = tempfile.TemporaryDirectory()
        self.addCleanup(self.temp.cleanup)
        self.root = Path(self.temp.name)
        self.repo = self.root / "repo"
        self.repo.mkdir()
        subprocess.run(["git", "init", "-q", str(self.repo)], check=True)
        git(self.repo, "config", "user.email", "probe@example.invalid")
        git(self.repo, "config", "user.name", "Probe")
        (self.repo / "CMakeLists.txt").write_text("project(glslang)\n")
        (self.repo / "LICENSE.txt").write_text("test license\n")
        (self.repo / ".gitignore").write_text("ignored.tmp\n")
        git(self.repo, "add", ".")
        git(self.repo, "commit", "-qm", "source")
        self.commit = git(self.repo, "rev-parse", "HEAD")
        git(self.repo, "tag", "15.4.0")
        self.lock = self.root / "lock.json"
        self.output = self.root / "source"
        self.write_lock()

    def write_lock(self, **changes):
        lock = {"repository": "https://github.com/KhronosGroup/glslang.git",
                "tag": "15.4.0", "commit": self.commit}
        lock.update(changes)
        self.lock.write_text(json.dumps(lock))

    def prepare(self):
        return subprocess.run([sys.executable, str(SCRIPT), "--lock", str(self.lock),
                               "--source", str(self.output), "--repository", str(self.repo),
                               "--source-only"], text=True, capture_output=True)

    def test_release_pin(self):
        lock = json.loads((ROOT / "third_party/switch-glslang.lock.json").read_text())
        self.assertEqual(lock["commit"], PIN)
        self.assertEqual(lock["repository"], "https://github.com/KhronosGroup/glslang.git")

    def test_detached_exact_source_reuse(self):
        result = self.prepare()
        self.assertEqual(result.returncode, 0, result.stderr)
        self.assertEqual(git(self.output, "rev-parse", "HEAD"), self.commit)
        self.assertEqual(subprocess.run(["git", "-C", str(self.output), "symbolic-ref", "-q", "HEAD"],
                                        capture_output=True).returncode, 1)
        self.assertEqual(self.prepare().returncode, 0)

    def test_moved_tag_and_dirty_source_rejected(self):
        self.write_lock(commit="0" * 40)
        self.assertNotEqual(self.prepare().returncode, 0)
        self.assertFalse(self.output.exists())
        self.write_lock()
        self.assertEqual(self.prepare().returncode, 0)
        (self.output / "CMakeLists.txt").write_text("project(modified)\n")
        self.assertNotEqual(self.prepare().returncode, 0)

    def test_untracked_source_addition_rejected(self):
        self.assertEqual(self.prepare().returncode, 0)
        (self.output / "extra.txt").write_text("injected\n")
        self.assertNotEqual(self.prepare().returncode, 0)

    def test_ignored_source_addition_rejected(self):
        self.assertEqual(self.prepare().returncode, 0)
        (self.output / "ignored.tmp").write_text("injected\n")
        self.assertNotEqual(self.prepare().returncode, 0)


if __name__ == "__main__":
    unittest.main()
