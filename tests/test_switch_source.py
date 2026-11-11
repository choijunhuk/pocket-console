import json
import os
from pathlib import Path
import subprocess
import sys
import tempfile
import unittest


ROOT = Path(__file__).resolve().parents[1]
SCRIPT = ROOT / "scripts/prepare-switch-source.py"
PIN = "eb85a37114f492ce186a23a82ea0965e7139dcb6"


def git(directory, *args):
    return subprocess.check_output(["git", "-C", str(directory), *args], text=True).strip()


class SwitchSourceTest(unittest.TestCase):
    def setUp(self):
        self.temp = tempfile.TemporaryDirectory()
        self.addCleanup(self.temp.cleanup)
        self.root = Path(self.temp.name)
        self.repo = self.root / "repo"
        self.repo.mkdir()
        subprocess.run(["git", "init", "-q", str(self.repo)], check=True)
        git(self.repo, "config", "user.email", "probe@example.invalid")
        git(self.repo, "config", "user.name", "Probe")
        (self.repo / "CMakeLists.txt").write_text("project(yuzu)\n")
        target = self.repo / "src/android/app/src/main/jni/CMakeLists.txt"
        target.parent.mkdir(parents=True)
        target.write_text("add_library(yuzu-android SHARED native.cpp)\n")
        git(self.repo, "add", ".")
        git(self.repo, "commit", "-qm", "source")
        self.commit = git(self.repo, "rev-parse", "HEAD")
        git(self.repo, "tag", "android-0.6.2")
        self.lock = self.root / "lock.json"
        self.output = self.root / "output"
        self.write_lock()

    def write_lock(self, **changes):
        lock = {"name": "Hokkaido", "repository": "https://github.com/pavelgajdo/Hokkaido.git",
                "tag": "android-0.6.2", "commit": self.commit, "abi": "arm64-v8a"}
        lock.update(changes)
        self.lock.write_text(json.dumps(lock))

    def prepare(self):
        return subprocess.run([sys.executable, str(SCRIPT), "--lock", str(self.lock),
                               "--output", str(self.output), "--repository", str(self.repo)],
                              text=True, capture_output=True)

    def test_repository_lock_is_exact_and_arm64_only(self):
        lock = json.loads((ROOT / "third_party/switch-hokkaido.lock.json").read_text())
        self.assertEqual(lock["commit"], PIN)
        self.assertEqual(lock["abi"], "arm64-v8a")
        self.assertEqual(lock["repository"], "https://github.com/pavelgajdo/Hokkaido.git")

    def test_prepares_detached_exact_source_and_reuses_it(self):
        result = self.prepare()
        self.assertEqual(result.returncode, 0, result.stderr)
        self.assertEqual(git(self.output, "rev-parse", "HEAD"), self.commit)
        self.assertEqual(subprocess.run(["git", "-C", str(self.output), "symbolic-ref", "-q", "HEAD"],
                                        capture_output=True).returncode, 1)
        self.assertEqual(json.loads((self.output / ".pocket-console-source.json").read_text())["commit"], self.commit)
        self.assertEqual(self.prepare().returncode, 0)

    def test_rejects_wrong_commit_without_output(self):
        self.write_lock(commit="0" * 40)
        result = self.prepare()
        self.assertNotEqual(result.returncode, 0)
        self.assertIn("commit", result.stderr)
        self.assertFalse(self.output.exists())

    def test_rejects_modified_tracked_source_on_reuse(self):
        self.assertEqual(self.prepare().returncode, 0)
        (self.output / "CMakeLists.txt").write_text("project(modified)\n")
        result = self.prepare()
        self.assertNotEqual(result.returncode, 0)
        self.assertIn("modified tracked", result.stderr)

    def test_rejects_modified_tracked_submodule_on_reuse(self):
        child = self.root / "child"
        child.mkdir()
        subprocess.run(["git", "init", "-q", str(child)], check=True)
        git(child, "config", "user.email", "probe@example.invalid")
        git(child, "config", "user.name", "Probe")
        (child / "file.txt").write_text("pinned\n")
        git(child, "add", ".")
        git(child, "commit", "-qm", "child")
        git(self.repo, "tag", "-d", "android-0.6.2")
        subprocess.run(["git", "-C", str(self.repo), "-c", "protocol.file.allow=always",
                        "submodule", "add", str(child), "dependency"], check=True, capture_output=True)
        git(self.repo, "commit", "-qam", "add submodule")
        self.commit = git(self.repo, "rev-parse", "HEAD")
        git(self.repo, "tag", "android-0.6.2")
        self.write_lock()
        result = subprocess.run([sys.executable, str(SCRIPT), "--lock", str(self.lock),
                                 "--output", str(self.output), "--repository", str(self.repo)],
                                text=True, capture_output=True, env={**os.environ,
                                                                      "GIT_ALLOW_PROTOCOL": "file"})
        self.assertEqual(result.returncode, 0, result.stderr)
        (self.output / "dependency/file.txt").write_text("modified\n")
        self.assertNotEqual(self.prepare().returncode, 0)

    def test_rejects_invalid_pin_and_abi_before_fetch(self):
        for changes in ({"commit": "a" * 41}, {"abi": "x86_64"}):
            self.write_lock(**changes)
            result = self.prepare()
            self.assertNotEqual(result.returncode, 0)
            self.assertFalse(self.output.exists())


if __name__ == "__main__":
    unittest.main()
