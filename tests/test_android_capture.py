import json
import os
from pathlib import Path
import subprocess
import sys
import tempfile
import unittest


SCRIPT = Path(__file__).resolve().parents[1] / "scripts/capture-android-ui.sh"
FAKE_ADB = r"""
import json, os, pathlib, sys
args = sys.argv[1:]
root = pathlib.Path(os.environ["FAKE_ADB_ROOT"])
with (root / "calls.jsonl").open("a") as log:
    log.write(json.dumps(args) + "\n")
if args == ["shell", "cmd", "uimode", "night"]:
    print("Night mode: auto")
elif args == ["shell", "settings", "get", "system", "font_scale"]:
    print("1.15")
elif args[:2] == ["exec-out", "cat"]:
    state = root / "hierarchy-count"
    count = int(state.read_text()) if state.exists() else 0
    state.write_text(str(count + 1))
    if count > 0 and os.environ["FAKE_READY"] == "yes":
        print('<hierarchy><node text="내 라이브러리"/><node text="파일 가져오기"/></hierarchy>')
    else:
        print('<hierarchy><node text="내 라이브러리"/></hierarchy>')
elif args == ["exec-out", "screencap", "-p"]:
    sys.stdout.buffer.write(b"test screenshot")
elif args[:2] == ["exec-out", "run-as"]:
    sys.stdout.buffer.write(b"test player screenshot")
elif args == ["shell", "getprop", "ro.opengles.version"]:
    print(os.environ.get("FAKE_GLES", "unknown"))
elif args[:3] == ["shell", "am", "instrument"]:
    if args[-1].endswith(".DsSmokeInstrumentation") and os.environ.get("FAKE_RELEASE") == "yes":
        print("INSTRUMENTATION_STATUS: stream=DS_SCREENSHOT_BASE64:iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAQAAAC1HAwCAAAAC0lEQVR42mP8/x8AAusB9Y9bnm4AAAAASUVORK5CYII=")
    print("DS_ANDROID_SMOKE_PASS" if args[-1].endswith(".DsSmokeInstrumentation")
          else "fixture runtime failure")
"""


class AndroidCaptureTests(unittest.TestCase):
    def run_capture(self, ready):
        with tempfile.TemporaryDirectory() as temp:
            root = Path(temp)
            adb = root / "adb"
            adb.write_text(f"#!{sys.executable}\n" + FAKE_ADB)
            adb.chmod(0o755)
            env = dict(os.environ, PATH=f"{root}:{os.environ['PATH']}",
                       FAKE_ADB_ROOT=str(root), FAKE_READY=ready)
            result = subprocess.run(["sh", str(SCRIPT)], cwd=root, env=env,
                                    text=True, capture_output=True)
            calls = [json.loads(line) for line in (root / "calls.jsonl").read_text().splitlines()]
            images = sorted(path.name for path in root.glob("*.png"))
            return result, calls, images

    def test_waits_for_complete_ui_and_restores_settings(self):
        result, calls, images = self.run_capture("yes")
        self.assertEqual(result.returncode, 0, result.stderr)
        first_capture = calls.index(["exec-out", "screencap", "-p"])
        self.assertEqual(sum(call[:2] == ["exec-out", "cat"] for call in calls[:first_capture]), 2)
        self.assertEqual(images, ["library-dark-font.png", "library-light.png"])
        self.assertIn("ANDROID_UI_CAPTURE_PASS", result.stdout)
        self.assertIn(["shell", "cmd", "uimode", "night", "auto"], calls)
        self.assertIn(["shell", "settings", "put", "system", "font_scale", "1.15"], calls)

    def test_never_captures_incomplete_ui(self):
        result, calls, images = self.run_capture("no")
        self.assertNotEqual(result.returncode, 0)
        self.assertEqual(images, [])
        self.assertNotIn("ANDROID_UI_CAPTURE_PASS", result.stdout)
        self.assertEqual(sum(call[:2] == ["exec-out", "cat"] for call in calls), 15)
        self.assertIn(["shell", "cmd", "uimode", "night", "auto"], calls)
        self.assertIn(["shell", "settings", "put", "system", "font_scale", "1.15"], calls)


class AndroidSmokePrerequisiteTests(unittest.TestCase):
    def run_smoke(self, gles, timed_out=False, release=False):
        with tempfile.TemporaryDirectory() as temp:
            root = Path(temp)
            adb = root / "adb"
            adb.write_text(f"#!{sys.executable}\n" + FAKE_ADB)
            adb.chmod(0o755)
            # CI uses GNU timeout; the fixture forwards commands on macOS.
            timeout = root / "timeout"
            timeout.write_text('#!/bin/sh\nif [ "$FAKE_TIMEOUT" = yes ]; then exit 124; fi\nshift 2\nexec "$@"\n')
            timeout.chmod(0o755)
            (root / "scripts").mkdir()
            (root / "scripts/capture-android-ui.sh").symlink_to(SCRIPT)
            (root / "scripts/stop-ci-emulator.py").symlink_to(SCRIPT.with_name("stop-ci-emulator.py"))
            env = dict(os.environ, PATH=f"{root}:{os.environ['PATH']}",
                       FAKE_ADB_ROOT=str(root), FAKE_READY="yes", FAKE_GLES=gles,
                       FAKE_TIMEOUT="yes" if timed_out else "no",
                       FAKE_RELEASE="yes" if release else "no",
                       POCKET_RELEASE_SMOKE="1" if release else "",
                       POCKET_ANDROID_APK="artifacts/outputs/apk/release/app-release.apk"
                       if release else "artifacts/outputs/apk/debug/app-debug.apk")
            result = subprocess.run(["sh", str(SCRIPT.with_name("test-android-smoke.sh"))],
                                    cwd=root, env=env, text=True, capture_output=True)
            calls = [json.loads(line) for line in (root / "calls.jsonl").read_text().splitlines()]
            return result, calls

    def test_insufficient_gles_is_not_run_never_pass(self):
        result, calls = self.run_smoke("196608")
        self.assertEqual(result.returncode, 0, result.stderr)
        self.assertIn("THREEDS_ANDROID_SMOKE_NOT_RUN_GLES", result.stdout)
        self.assertNotIn("THREEDS_ANDROID_SMOKE_PASS", result.stdout)
        self.assertFalse(any(call[-1].endswith(".ThreeDsSmokeInstrumentation") for call in calls))

    def test_unknown_capability_fails(self):
        result, _ = self.run_smoke("unknown")
        self.assertNotEqual(result.returncode, 0)
        self.assertIn("THREEDS_ANDROID_SMOKE_UNKNOWN_GLES", result.stdout)

    def test_sufficient_gles_still_requires_real_smoke_success(self):
        result, calls = self.run_smoke("196610")
        self.assertNotEqual(result.returncode, 0)
        self.assertIn("fixture runtime failure", result.stdout)
        self.assertTrue(any(call[-1].endswith(".ThreeDsSmokeInstrumentation") for call in calls))
        self.assertNotIn("THREEDS_ANDROID_SMOKE_NOT_RUN_GLES", result.stdout)

    def test_timeout_preserves_failure_and_collects_diagnostics(self):
        result, calls = self.run_smoke("196610", timed_out=True)
        self.assertEqual(result.returncode, 124)
        self.assertNotIn("DS_ANDROID_SMOKE_PASS", result.stdout)
        self.assertIn(["logcat", "-d", "-v", "threadtime"], calls)

    def test_release_uses_actual_instrumentation_png_without_run_as(self):
        result, calls = self.run_smoke("196608", release=True)
        self.assertEqual(result.returncode, 0, result.stderr)
        self.assertIn("DS_SCREENSHOT_FROM_INSTRUMENTATION_PASS", result.stdout)
        self.assertIn("THREEDS_ANDROID_SMOKE_NOT_RUN_EXPERIMENTAL", result.stdout)
        self.assertIn(["install", "artifacts/outputs/apk/release/app-release.apk"], calls)
        self.assertIn(["install", "-t", "artifacts/outputs/smoke-tests/ds-test.apk"], calls)
        self.assertFalse(any(call[:2] == ["exec-out", "run-as"] for call in calls))

    def test_release_does_not_accept_untested_3ds_on_gles32_driver(self):
        result, calls = self.run_smoke("196610", release=True)
        self.assertEqual(result.returncode, 0, result.stderr)
        self.assertIn("THREEDS_ANDROID_SMOKE_NOT_RUN_EXPERIMENTAL", result.stdout)
        self.assertNotIn("THREEDS_ANDROID_SMOKE_PASS", result.stdout)
        self.assertFalse(any(call[-1].endswith(".ThreeDsSmokeInstrumentation") for call in calls))
        self.assertNotIn(["shell", "getprop", "ro.opengles.version"], calls)


if __name__ == "__main__":
    unittest.main()
