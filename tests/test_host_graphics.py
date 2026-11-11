#!/usr/bin/env python3
"""Offline checks for the pinned host graphics header fetcher."""

from hashlib import sha256
import importlib.util
import json
from pathlib import Path
import tempfile
import unittest


ROOT = Path(__file__).resolve().parent.parent
spec = importlib.util.spec_from_file_location(
    "prepare_host_graphics", ROOT / "scripts/prepare-host-graphics.py")
graphics = importlib.util.module_from_spec(spec)
spec.loader.exec_module(graphics)


class HostGraphicsTest(unittest.TestCase):
    def test_reviewed_lock_uses_exact_official_pins(self):
        entries = graphics.load_specs()
        self.assertEqual(len(entries), 2)
        self.assertEqual({name for name, _, _ in entries}, {
            "KHR/khrplatform.h", "GL/glcorearb.h"})
        self.assertTrue(all(url.startswith("https://raw.githubusercontent.com/KhronosGroup/")
                            for _, _, url in entries))

    def test_changed_commit_or_path_is_rejected(self):
        with tempfile.TemporaryDirectory() as temporary:
            lock = json.loads(graphics.LOCK.read_text())
            path = Path(temporary) / "lock.json"
            lock["sources"][0]["commit"] = "0" * 40
            path.write_text(json.dumps(lock))
            with self.assertRaisesRegex(ValueError, "commit"):
                graphics.load_specs(path)
            lock["sources"][0]["commit"] = graphics.PINNED["KhronosGroup/EGL-Registry"][0]
            lock["sources"][0]["files"][0]["path"] = "api/KHR/../../outside.h"
            path.write_text(json.dumps(lock))
            with self.assertRaisesRegex(ValueError, "paths"):
                graphics.load_specs(path)

    def test_verify_all_before_publish_reuse_and_repair(self):
        first = b"official header one\n"
        second = b"official header two\n"
        specs = [("KHR/khrplatform.h", sha256(first).hexdigest(), "first"),
                 ("GL/glcorearb.h", sha256(second).hexdigest(), "second")]
        payload = {"first": first, "second": second}
        calls = []

        def fetch(url):
            calls.append(url)
            return payload[url]

        with tempfile.TemporaryDirectory() as temporary:
            output = Path(temporary) / "include"
            payload["second"] = b"corrupted"
            with self.assertRaisesRegex(ValueError, "SHA256 mismatch"):
                graphics.prepare(output, specs, fetch)
            self.assertFalse(output.exists())
            payload["second"] = second
            self.assertEqual(graphics.prepare(output, specs, fetch), "prepared")
            calls.clear()
            self.assertEqual(graphics.prepare(output, specs, fetch), "reused")
            self.assertEqual(calls, [])
            (output / "KHR/khrplatform.h").write_bytes(b"corrupted")
            self.assertEqual(graphics.prepare(output, specs, fetch), "prepared")
            self.assertEqual((output / "KHR/khrplatform.h").read_bytes(), first)


if __name__ == "__main__":
    unittest.main()
