import importlib.util
import json
from pathlib import Path
import subprocess
import tarfile
import tempfile
import unittest


SCRIPT = Path(__file__).resolve().parents[1] / "scripts/build-release-source.py"
SPEC = importlib.util.spec_from_file_location("build_release_source", SCRIPT)
source_bundle = importlib.util.module_from_spec(SPEC)
SPEC.loader.exec_module(source_bundle)


def git(root, *args):
    subprocess.run(["git", *args], cwd=root, check=True, capture_output=True)


class ReleaseSourceTests(unittest.TestCase):
    def setUp(self):
        self.temp = tempfile.TemporaryDirectory()
        self.addCleanup(self.temp.cleanup)
        self.work = Path(self.temp.name)
        self.repo = self.work / "repo"
        self.repo.mkdir()
        git(self.repo, "init", "-q")
        (self.repo / "app").mkdir()
        (self.repo / "app/build.gradle.kts").write_text('versionName = "0.3.0-alpha.1"\n')
        (self.repo / "LICENSE").write_text("app license")
        (self.repo / "third_party").mkdir()
        (self.repo / "third_party/azahar.lock.json").write_text(json.dumps({
            "source_commit": "a" * 40, "source_sha256": "b" * 64
        }))
        self.melon = self.repo / "third_party/melonDS"
        self.melon.mkdir()
        git(self.melon, "init", "-q")
        (self.melon / "LICENSE").write_text("DS license")
        (self.melon / "core.cpp").write_text("real DS source")
        git(self.melon, "add", ".")
        git(self.melon, "-c", "user.name=Test", "-c", "user.email=test@example.com",
            "commit", "-qm", "source")
        git(self.repo, "add", ".")
        git(self.repo, "-c", "user.name=Test", "-c", "user.email=test@example.com",
            "commit", "-qm", "source")
        self.azahar = self.work / "prepared-azahar"
        self.azahar.mkdir()
        (self.azahar / "license.txt").write_text("Azahar license")
        (self.azahar / "engine.cpp").write_text("real 3DS source")
        (self.azahar / ".git").mkdir()
        (self.azahar / ".git/config").write_text("private metadata")
        (self.repo / ".cache").mkdir()
        (self.repo / ".cache/private-token").write_text("do not ship")
        self.bundle = self.work / "source.tar.gz"

    def test_bundles_only_tracked_project_and_prepared_cores(self):
        source_bundle.bundle(self.repo, self.azahar, self.bundle, "v0.3.0-alpha.1")
        with tarfile.open(self.bundle, "r:gz") as archive:
            names = set(archive.getnames())
            self.assertIn("pocket-console/app/build.gradle.kts", names)
            self.assertIn("pocket-console/third_party/melonDS/core.cpp", names)
            self.assertIn("pocket-console/third_party/azahar/engine.cpp", names)
            self.assertNotIn("pocket-console/.cache/private-token", names)
            self.assertNotIn("pocket-console/third_party/azahar/.git/config", names)
            manifest = json.load(archive.extractfile("pocket-console/SOURCE-MANIFEST.json"))
            self.assertEqual(manifest["tag"], "v0.3.0-alpha.1")
            self.assertEqual(manifest["melon_ds_commit"], source_bundle.git(self.melon, "rev-parse", "HEAD"))

    def test_rejects_wrong_tag_without_replacing_existing_archive(self):
        self.bundle.write_bytes(b"existing")
        with self.assertRaisesRegex(ValueError, "does not match"):
            source_bundle.bundle(self.repo, self.azahar, self.bundle, "v0.4.0")
        self.assertEqual(self.bundle.read_bytes(), b"existing")

    def test_rejects_escape_symlink_before_publication(self):
        (self.melon / "escape").symlink_to("/private/secret")
        with self.assertRaisesRegex(ValueError, "source symlink"):
            source_bundle.bundle(self.repo, self.azahar, self.bundle, "v0.3.0-alpha.1")
        self.assertFalse(self.bundle.exists())


if __name__ == "__main__":
    unittest.main()
