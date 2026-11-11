import importlib.util
from pathlib import Path
import tempfile
import unittest


ROOT = Path(__file__).resolve().parents[1]
spec = importlib.util.spec_from_file_location(
    "switch_sdk_runtime", ROOT / "scripts/stage-switch-sdk-runtime.py")
runtime = importlib.util.module_from_spec(spec)
spec.loader.exec_module(runtime)


class RuntimeBundleTest(unittest.TestCase):
    def setUp(self):
        self.temporary = tempfile.TemporaryDirectory()
        self.addCleanup(self.temporary.cleanup)
        self.root = Path(self.temporary.name)
        self.stage = self.root / "stage"
        self.build = self.root / "build"
        self.source = self.root / "source"
        self.ndk = self.root / "ndk"
        self.system = (self.ndk / "toolchains/llvm/prebuilt/linux-x86_64"
                       "/sysroot/usr/lib/aarch64-linux-android/33")
        self.system.mkdir(parents=True)
        (self.system / "liblog.so").write_bytes(b"system stub")
        self.cxx = self.system.parent / "libc++_shared.so"
        self.cxx.write_bytes(b"NDK C++")
        self.stage.mkdir()
        self.build.mkdir()
        self.source.mkdir()
        (self.stage / "libpocket_switch2.so").write_bytes(b"main")
        (self.build / "libSDL3.so").write_bytes(b"SDL")
        (self.source / "libavcodec.so").write_bytes(b"FFmpeg")

    @staticmethod
    def inspect(path):
        return {
            "libpocket_switch2.so": ("libpocket_switch2.so", ["libSDL3.so", "libc++_shared.so", "liblog.so"]),
            "libSDL3.so": ("libSDL3.so", ["libavcodec.so", "liblog.so"]),
            "libavcodec.so": ("libavcodec.so", ["libc++_shared.so"]),
            "libc++_shared.so": ("libc++_shared.so", ["liblog.so"]),
        }.get(path.name)

    def test_stages_transitive_arm64_closure_and_manifest(self):
        manifest = runtime.stage_bundle(self.stage, self.build, self.source,
                                        self.ndk, self.inspect)
        names = {entry["name"] for entry in manifest["libraries"]}
        self.assertEqual(names, {"libpocket_switch2.so", "libSDL3.so",
                                 "libavcodec.so", "libc++_shared.so"})
        self.assertEqual(manifest["system_needed"], ["liblog.so"])
        self.assertEqual({path.name for path in (self.stage / "runtime/arm64-v8a").iterdir()}, names)
        self.assertEqual((self.stage / "runtime/arm64-v8a/libc++_shared.so").read_bytes(),
                         b"NDK C++")
        self.assertTrue((self.stage / "runtime-deps.json").is_file())

    def test_rejects_conflicting_same_soname(self):
        duplicate = self.build / "other/libSDL3.so"
        duplicate.parent.mkdir()
        duplicate.write_bytes(b"different SDL")
        with self.assertRaisesRegex(ValueError, "Conflicting ARM64 runtime dependency"):
            runtime.stage_bundle(self.stage, self.build, self.source,
                                 self.ndk, self.inspect)

    def test_prefers_built_dso_over_unused_source_copy(self):
        (self.source / "libSDL3.so").write_bytes(b"unlinked source copy")
        manifest = runtime.stage_bundle(self.stage, self.build, self.source,
                                        self.ndk, self.inspect)
        sdl = next(entry for entry in manifest["libraries"]
                   if entry["name"] == "libSDL3.so")
        self.assertEqual(sdl["origin"], "build")
        self.assertEqual((self.stage / "runtime/arm64-v8a/libSDL3.so").read_bytes(), b"SDL")

    def test_rejects_missing_dependency(self):
        (self.build / "libSDL3.so").unlink()
        with self.assertRaisesRegex(ValueError, "Missing ARM64 runtime dependency"):
            runtime.stage_bundle(self.stage, self.build, self.source,
                                 self.ndk, self.inspect)


if __name__ == "__main__":
    unittest.main()
