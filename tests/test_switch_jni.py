import hashlib
import importlib.util
import io
import json
import os
from pathlib import Path
import struct
import tarfile
import tempfile
import unittest
from unittest.mock import patch

ROOT = Path(__file__).resolve().parents[1]
SPEC = importlib.util.spec_from_file_location("switch_jni", ROOT / "scripts/prepare-switch-jni.py")
MODULE = importlib.util.module_from_spec(SPEC)
SPEC.loader.exec_module(MODULE)


class SwitchJniTests(unittest.TestCase):
    def setUp(self):
        self.temp = tempfile.TemporaryDirectory()
        self.addCleanup(self.temp.cleanup)
        self.root = Path(self.temp.name)
        self.sdk = self.root / "sdk"
        self.output = self.root / "output"
        self.lock = self.root / "lock.json"
        self.ndk = self.root / "android-sdk"
        self.lock.write_text(json.dumps({"commit": "pinned"}))
        (self.sdk / "runtime/arm64-v8a").mkdir(parents=True)
        (self.sdk / "source-lock.json").write_text(self.lock.read_text())
        runtime = self.ndk / "ndk/28.2.13676358/toolchains/llvm/prebuilt/darwin-arm64/sysroot/usr/lib/aarch64-linux-android"
        runtime.mkdir(parents=True)
        image = bytearray(64)
        image[:7] = b"\x7fELF\x02\x01\x01"
        struct.pack_into("<H", image, 18, 183)
        (runtime / "libc++_shared.so").write_bytes(image)
        libraries = []
        for name in MODULE.NAMES:
            binary = self.sdk / "runtime/arm64-v8a" / name
            binary.write_bytes(image)
            libraries.append({"name": name, "sha256": hashlib.sha256(image).hexdigest(), "needed": []})
        (self.sdk / "runtime-deps.json").write_text(json.dumps({
            "abi": "arm64-v8a", "android_api": 33, "system_needed": [], "libraries": libraries}))
        with tarfile.open(self.sdk / "sdl-java-bootstrap.tar.gz", "w:gz") as archive:
            data = b"package org.libsdl.app; class SDL {}"
            member = tarfile.TarInfo("org/libsdl/app/SDL.java")
            member.size = len(data)
            archive.addfile(member, io.BytesIO(data))
        (self.sdk / "SHA256SUMS").write_text("\n".join(
            f"{MODULE.digest(path)}  {path.relative_to(self.sdk)}" for path in self.sdk.rglob("*")
            if path.is_file() and path.name != "SHA256SUMS") + "\n")
        self.producer = self.root / "producer.json"
        self.producer.write_text(json.dumps({
            "sha256s_sha256": MODULE.digest(self.sdk / "SHA256SUMS"),
            "files": {name: MODULE.digest(self.sdk / name) for name in MODULE.PINNED_FILES},
        }))

    def prepare(self, strict=False):
        return MODULE.prepare(self.sdk, self.output, self.lock, self.ndk, self.producer, strict)

    def test_stages_only_verified_runtime_and_java(self):
        self.assertEqual(self.prepare(), "SWITCH_JNI_PREPARATION_PASS")
        self.assertEqual({path.name for path in (self.output / "runtime/arm64-v8a").iterdir()},
                         MODULE.NAMES - {"libc++_shared.so"})
        self.assertTrue((self.output / "java/org/libsdl/app/SDL.java").is_file())

    def test_rejects_tampered_sdk_before_staging(self):
        (self.sdk / "runtime/arm64-v8a/libhook_impl.so").write_bytes(b"tampered")
        with self.assertRaisesRegex(ValueError, "checksum mismatch"):
            self.prepare()
        self.assertFalse(self.output.exists())

    def test_rejects_mismatched_cxx_runtime(self):
        runtime = next(self.ndk.rglob("libc++_shared.so"))
        runtime.write_bytes(b"different")
        with self.assertRaisesRegex(ValueError, r"C\+\+ runtime differs"):
            self.prepare()
        self.assertFalse(self.output.exists())

    def test_strict_ci_requires_exact_pinned_sdk(self):
        self.assertEqual(self.prepare(strict=True), "SWITCH_JNI_PREPARATION_PASS")
        (self.sdk / "SHA256SUMS").write_text((self.sdk / "SHA256SUMS").read_text() + "\n")
        with self.assertRaisesRegex(ValueError, "differs from the pinned CI artifact"):
            self.prepare(strict=True)

    def test_strict_ci_rejects_absent_sdk(self):
        with self.assertRaisesRegex(ValueError, "Pinned Switch SDK is missing"):
            MODULE.prepare(self.root / "missing", self.output, self.lock, self.ndk,
                           self.producer, True)

    def test_strict_ci_rejects_incomplete_producer_lock(self):
        pin = json.loads(self.producer.read_text())
        pin["files"].pop("runtime/arm64-v8a/libhook_impl.so")
        self.producer.write_text(json.dumps(pin))
        with self.assertRaisesRegex(ValueError, "omits required file SHA256s"):
            self.prepare(strict=True)

    def test_blocked_pin_fails_ci_but_keeps_optional_local_staging(self):
        pin = json.loads(self.producer.read_text())
        pin["unavailable_reason"] = "run 42 is in_progress; artifact SHA256 is missing"
        self.producer.write_text(json.dumps(pin))
        with self.assertRaisesRegex(ValueError, "run 42 is in_progress; artifact SHA256 is missing"):
            MODULE.verify_producer(self.producer)
        with self.assertRaisesRegex(ValueError, "run 42 is in_progress; artifact SHA256 is missing"):
            self.prepare(strict=True)
        self.assertEqual(self.prepare(), "SWITCH_JNI_PREPARATION_PASS")

    def test_producer_requires_successful_exact_run_and_unexpired_artifact(self):
        pin = json.loads(self.producer.read_text())
        pin.update({"workflow": "switch-sdk.yml", "run_id": 42,
                    "head_sha": "a" * 40, "artifact_name": "pocket-switch2-sdk-source-arm64",
                    "artifact_sha256": "b" * 64})
        self.producer.write_text(json.dumps(pin))
        run = {"conclusion": "success", "head_sha": "a" * 40,
               "path": ".github/workflows/switch-sdk.yml@refs/heads/feat/switch-backends"}
        artifact = {"artifacts": [{"name": pin["artifact_name"],
                                   "digest": "sha256:" + "b" * 64, "expired": False}]}
        with patch.dict(os.environ, {"GH_TOKEN": "test"}):
            with patch.object(MODULE.subprocess, "check_output", side_effect=[
                    json.dumps(run), json.dumps(artifact)]):
                self.assertEqual(MODULE.verify_producer(self.producer), 42)
            for changed in ({**run, "conclusion": "failure"},
                            {**run, "head_sha": "c" * 40},
                            {**run, "path": ".github/workflows/other.yml"}):
                with patch.object(MODULE.subprocess, "check_output", return_value=json.dumps(changed)):
                    with self.assertRaisesRegex(ValueError, "does not match its lock"):
                        MODULE.verify_producer(self.producer)
            artifact["artifacts"][0]["digest"] = "sha256:" + "c" * 64
            with patch.object(MODULE.subprocess, "check_output", side_effect=[
                    json.dumps(run), json.dumps(artifact)]):
                with self.assertRaisesRegex(ValueError, "SHA256 does not match"):
                    MODULE.verify_producer(self.producer)
            artifact["artifacts"][0]["digest"] = "sha256:" + "b" * 64
            artifact["artifacts"][0]["expired"] = True
            with patch.object(MODULE.subprocess, "check_output", side_effect=[
                    json.dumps(run), json.dumps(artifact)]):
                with self.assertRaisesRegex(ValueError, "missing or expired"):
                    MODULE.verify_producer(self.producer)

    def test_missing_sdk_clears_only_generated_output(self):
        self.prepare()
        self.assertEqual(MODULE.prepare(self.root / "missing", self.output, self.lock, self.ndk),
                         "SWITCH_JNI_NOT_PACKAGED")
        self.assertFalse(self.output.exists())


if __name__ == "__main__":
    unittest.main()
