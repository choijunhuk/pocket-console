import hashlib
import importlib.util
import io
import json
import os
from pathlib import Path
import stat
import tempfile
import unittest
from unittest.mock import patch
import zipfile

ROOT = Path(__file__).resolve().parents[1]


def load(name, file):
    spec = importlib.util.spec_from_file_location(name, ROOT / "scripts" / file)
    module = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(module)
    return module


FETCH = load("fetch_switch_sdk", "fetch-switch-sdk.py")
PREPARE = load("prepare_switch_jni2", "prepare-switch-jni.py")

# Fake gh: mirror listing from FAKE_MIRROR_ASSETS, release download copies FAKE_MIRROR_ZIP,
# artifact download streams FAKE_ARTIFACT_ZIP. Calls are logged to FAKE_GH_LOG.
FAKE_GH = """#!/bin/sh
echo "$@" >> "$FAKE_GH_LOG"
case "$1 $2" in
  "api repos/choijunhuk/pocket-console/releases/tags/switch-sdk-mirror")
    [ -z "$FAKE_MIRROR_FAIL" ] || { echo "gh: Server Error (HTTP 500)" >&2; exit 1; }
    [ -n "$FAKE_MIRROR_ASSETS" ] || { echo "gh: Not Found (HTTP 404)" >&2; exit 1; }
    printf '{"assets":[{"name":"%s"}]}' "$FAKE_MIRROR_ASSETS" ;;
  "release download") cp "$FAKE_MIRROR_ZIP" "$7/$5" ;;
  api*) cat "$FAKE_ARTIFACT_ZIP" ;;
  *) exit 2 ;;
esac
"""


def make_zip(path, entries):
    with zipfile.ZipFile(path, "w") as archive:
        for name, data in entries.items():
            if isinstance(data, zipfile.ZipInfo):
                archive.writestr(data, "target")
            else:
                archive.writestr(name, data)
    return hashlib.sha256(path.read_bytes()).hexdigest()


class FetchTests(unittest.TestCase):
    def setUp(self):
        temp = tempfile.TemporaryDirectory()
        self.addCleanup(temp.cleanup)
        self.root = Path(temp.name)
        bin_dir = self.root / "bin"
        bin_dir.mkdir()
        gh = bin_dir / "gh"
        gh.write_text(FAKE_GH)
        gh.chmod(0o755)
        self.zip = self.root / "sdk.zip"
        self.dest = self.root / "cache/sdk"
        self.log = self.root / "gh.log"
        self.log.write_text("")
        self.env = {"PATH": f"{bin_dir}:{os.environ['PATH']}", "FAKE_GH_LOG": str(self.log),
                    "FAKE_MIRROR_ASSETS": "", "FAKE_MIRROR_FAIL": "", "FAKE_MIRROR_ZIP": str(self.zip),
                    "FAKE_ARTIFACT_ZIP": str(self.zip)}
        self.lock = self.root / "lock.json"

    def pin(self, sha):
        self.lock.write_text(json.dumps({"run_id": 7, "artifact_id": 9, "artifact_sha256": sha}))

    def fetch(self, *extra):
        with patch.dict(os.environ, self.env):
            FETCH.main(["--lock", str(self.lock), "--dest", str(self.dest), *extra])
        return self.log.read_text()

    def test_mirror_asset_used_first(self):
        self.pin(make_zip(self.zip, {"a.txt": "mirror"}))
        self.env["FAKE_MIRROR_ASSETS"] = "pocket-switch2-sdk-7.zip"
        log = self.fetch()
        self.assertIn("release download switch-sdk-mirror -p pocket-switch2-sdk-7.zip", log)
        self.assertNotIn("actions/artifacts", log)
        self.assertEqual((self.dest / "a.txt").read_text(), "mirror")

    def test_artifact_fallback_when_mirror_asset_missing(self):
        self.pin(make_zip(self.zip, {"a.txt": "artifact"}))
        self.dest.mkdir(parents=True)
        (self.dest / "stale").write_text("old")
        log = self.fetch()
        self.assertIn("actions/artifacts/9/zip", log)
        self.assertNotIn("release download", log)
        self.assertEqual((self.dest / "a.txt").read_text(), "artifact")
        self.assertFalse((self.dest / "stale").exists())

    def test_zip_out_writes_verified_zip_only(self):
        self.pin(make_zip(self.zip, {"a.txt": "x"}))
        out = self.root / "out.zip"
        self.fetch("--zip-out", str(out))
        self.assertEqual(out.read_bytes(), self.zip.read_bytes())
        self.assertFalse(self.dest.exists())

    def test_artifact_digest_mismatch_rejected_without_extracting(self):
        make_zip(self.zip, {"a.txt": "x"})
        self.pin("0" * 64)
        with self.assertRaisesRegex(ValueError, "does not match the lock"):
            self.fetch()
        self.assertFalse(self.dest.exists())

    def test_bad_mirror_digest_fails_without_artifact_fallback(self):
        make_zip(self.zip, {"a.txt": "x"})
        self.pin("0" * 64)
        self.env["FAKE_MIRROR_ASSETS"] = "pocket-switch2-sdk-7.zip"
        with self.assertRaisesRegex(ValueError, "does not match the lock"):
            self.fetch()
        self.assertNotIn("actions/artifacts", self.log.read_text())
        self.assertFalse(self.dest.exists())

    def test_non_404_mirror_lookup_failure_raises(self):
        self.pin(make_zip(self.zip, {"a.txt": "x"}))
        self.env["FAKE_MIRROR_FAIL"] = "1"
        with self.assertRaisesRegex(ValueError, "HTTP 500"):
            self.fetch()
        self.assertNotIn("actions/artifacts", self.log.read_text())

    def test_unsafe_zip_entries_rejected_and_dest_untouched(self):
        link = zipfile.ZipInfo("link")
        link.external_attr = (stat.S_IFLNK | 0o777) << 16
        dirlink = zipfile.ZipInfo("dirlink/")
        dirlink.external_attr = (stat.S_IFLNK | 0o777) << 16
        self.dest.mkdir(parents=True)
        (self.dest / "keep").write_text("old")
        for entries in ({"../evil": "x"}, {"a/../b": "x"}, {"/abs": "x"}, {"a\\b": "x"},
                        {"link": link}, {"dirlink/": dirlink}):
            self.pin(make_zip(self.zip, entries))
            with self.assertRaisesRegex(ValueError, "unsafe entry"):
                self.fetch()
            self.assertEqual((self.dest / "keep").read_text(), "old")
            self.assertEqual(sorted(p.name for p in self.dest.parent.iterdir()), ["sdk"])
            self.assertFalse((self.root / "cache/evil").exists() or (self.root / "evil").exists())


class ProducerMirrorTests(unittest.TestCase):
    def test_expired_artifact_needs_mirror_asset(self):
        with tempfile.TemporaryDirectory() as temp:
            lock = Path(temp) / "lock.json"
            lock.write_text(json.dumps({
                "workflow": "switch-sdk.yml", "run_id": 42, "head_sha": "a" * 40,
                "artifact_name": "pocket-switch2-sdk-source-arm64", "artifact_sha256": "b" * 64}))
            run = {"conclusion": "success", "head_sha": "a" * 40,
                   "path": ".github/workflows/switch-sdk.yml@refs/heads/main"}
            expired = {"artifacts": [{"name": "pocket-switch2-sdk-source-arm64",
                                      "digest": "sha256:" + "b" * 64, "expired": True}]}
            def listing(body, code=0, err=""):
                return PREPARE.subprocess.CompletedProcess([], code, json.dumps(body), err)

            def verify(release):
                with patch.object(PREPARE.subprocess, "check_output", side_effect=[
                        json.dumps(run), json.dumps(expired)]), patch.object(
                        PREPARE.subprocess, "run", return_value=release):
                    return PREPARE.verify_producer(lock)

            with patch.dict(os.environ, {"GH_TOKEN": "t"}):
                self.assertEqual(verify(listing({"assets": [{"name": "pocket-switch2-sdk-42.zip"}]})), 42)
                for release in (listing({"assets": [{"name": "pocket-switch2-sdk-41.zip"}]}),
                                listing({}, 1, "gh: Not Found (HTTP 404)")):
                    with self.assertRaisesRegex(ValueError, "missing or expired"):
                        verify(release)
                with self.assertRaisesRegex(ValueError, "HTTP 500"):
                    verify(listing({}, 1, "gh: Server Error (HTTP 500)"))


class WorkflowTextTests(unittest.TestCase):
    def test_ci_fetches_sdk_through_mirror_script(self):
        for name in ("android.yml", "release.yml"):
            text = (ROOT / ".github/workflows" / name).read_text()
            self.assertNotIn("pocket-switch2-sdk-source-arm64", text)
            self.assertIn("python3 scripts/fetch-switch-sdk.py", text)
            self.assertIn("--check-run", text)
            self.assertIn("--require-sdk", text)

    def test_mirror_workflow_is_least_privilege_and_verifies_before_upload(self):
        text = (ROOT / ".github/workflows/switch-sdk-mirror.yml").read_text()
        top, job = text.split("jobs:", 1)
        self.assertNotIn("permissions:", top)
        self.assertIn("    permissions:\n      contents: write\n      actions: read", job)
        self.assertIn("persist-credentials: false", job)
        self.assertIn("concurrency:\n  group: switch-sdk-mirror", top)
        self.assertNotIn("--clobber", text)
        self.assertLess(text.index('fetch-switch-sdk.py --zip-out "$name"'),
                        text.index('gh release upload switch-sdk-mirror "$name"'))
        self.assertIn("pavelgajdo/Hokkaido/tree/${hokkaido}", text)
        self.assertIn("pocket-console/tree/${head_sha}", text)


if __name__ == "__main__":
    unittest.main()
