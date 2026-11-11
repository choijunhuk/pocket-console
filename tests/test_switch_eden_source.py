import json
import os
from pathlib import Path
import subprocess
import sys
import tempfile
import unittest


ROOT = Path(__file__).resolve().parents[1]
SCRIPT = ROOT / "scripts/prepare-switch-source.py"
PIN = "cf654052c416f9e7c80cef0a7d321476b1c52f99"


def git(directory, *args):
    return subprocess.check_output(["git", "-C", str(directory), *args], text=True).strip()


class EdenSourceTest(unittest.TestCase):
    def setUp(self):
        self.temp = tempfile.TemporaryDirectory()
        self.addCleanup(self.temp.cleanup)
        self.root = Path(self.temp.name)
        self.repo = self.root / "repo"
        self.repo.mkdir()
        subprocess.run(["git", "init", "-q", "-b", "master", str(self.repo)], check=True)
        git(self.repo, "config", "user.email", "eden-probe@example.invalid")
        git(self.repo, "config", "user.name", "Eden Probe")
        (self.repo / "CMakeLists.txt").write_text("project(yuzu)\n")
        target = self.repo / "src/android/app/src/main/jni/CMakeLists.txt"
        target.parent.mkdir(parents=True)
        target.write_text("add_library(yuzu-android SHARED native.cpp)\n")
        git(self.repo, "add", ".")
        git(self.repo, "commit", "-qm", "source")
        self.commit = git(self.repo, "rev-parse", "HEAD")
        self.lock = self.root / "lock.json"
        self.output = self.root / "output"
        self.write_lock()

    def write_lock(self, **changes):
        lock = json.loads((ROOT / "third_party/switch-eden.lock.json").read_text())
        lock["commit"] = self.commit
        lock.update(changes)
        self.lock.write_text(json.dumps(lock))

    def prepare(self):
        return subprocess.run([sys.executable, str(SCRIPT), "--lock", str(self.lock),
                               "--output", str(self.output), "--repository", str(self.repo)],
                              text=True, capture_output=True)

    def test_official_pin_and_toolchain(self):
        lock = json.loads((ROOT / "third_party/switch-eden.lock.json").read_text())
        self.assertEqual((lock["commit"], lock["repository"], lock["ndk"], lock["cmake"]),
                         (PIN, "https://github.com/eden-emulator/mirror.git",
                          "30.0.16248370", "3.31.6"))
        self.assertEqual((lock["abi"], lock["build_android_api"]), ("arm64-v8a", 26))

    def test_fetches_original_commit_after_master_moves(self):
        (self.repo / "moved.txt").write_text("later master\n")
        git(self.repo, "add", ".")
        git(self.repo, "commit", "-qm", "move master")
        result = self.prepare()
        self.assertEqual(result.returncode, 0, result.stderr)
        self.assertEqual(git(self.output, "rev-parse", "HEAD"), self.commit)
        self.assertFalse((self.output / "moved.txt").exists())
        self.assertEqual(self.prepare().returncode, 0)

    def test_rejects_unapproved_repository_before_fetch(self):
        self.write_lock(repository="https://github.com/other/mirror.git")
        result = self.prepare()
        self.assertNotEqual(result.returncode, 0)
        self.assertIn("repository", result.stderr)
        self.assertFalse(self.output.exists())


if __name__ == "__main__":
    unittest.main()
