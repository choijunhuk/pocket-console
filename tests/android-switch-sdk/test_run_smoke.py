import json
import os
import subprocess
import tempfile
import unittest
from pathlib import Path


SCRIPT = Path(__file__).with_name("run-smoke.sh").resolve()


class RunSmokeTest(unittest.TestCase):
    def _run_smoke(self, crash):
        with tempfile.TemporaryDirectory() as temp:
            root = Path(temp)
            apks = (
                root / "tests/android-switch-sdk/app/build/outputs/apk/debug/app-debug.apk",
                root / "tests/android-switch-sdk/app/build/outputs/apk/androidTest/debug/app-debug-androidTest.apk",
            )
            for apk in apks:
                apk.parent.mkdir(parents=True, exist_ok=True)
                apk.write_bytes(b"test apk")
            commands = root / "bin"
            commands.mkdir()
            adb = commands / "adb"
            adb.write_text("""#!/usr/bin/env python3
import json
import os
import sys
from pathlib import Path

args = sys.argv[1:]
if args[:1] == ["install"]:
    assert args in (["install", "--abi", "arm64-v8a", "tests/android-switch-sdk/app/build/outputs/apk/debug/app-debug.apk"],
                    ["install", "-t", "tests/android-switch-sdk/app/build/outputs/apk/androidTest/debug/app-debug-androidTest.apk"])
    assert Path(args[-1]).is_file()
    with open(os.environ["FAKE_ADB_LOG"], "a") as log:
        log.write(json.dumps(args) + "\\n")
elif args == ["shell", "getprop", "ro.product.cpu.abilist"]:
    print("x86_64,arm64-v8a")
elif args == ["shell", "getprop", "ro.dalvik.vm.native.bridge"]:
    print("libndk_translation.so")
elif args == ["shell", "dumpsys", "package", "dev.pocketconsole.sdkprobe"]:
    print("primaryCpuAbi=arm64-v8a")
elif args == ["shell", "pm", "list", "instrumentation"]:
    print("dev.pocketconsole.sdkprobe.test/dev.pocketconsole.SwitchSdkSmokeInstrumentation")
elif args == ["shell", "am", "instrument", "-w", "-r", "dev.pocketconsole.sdkprobe.test/dev.pocketconsole.SwitchSdkSmokeInstrumentation"]:
    if os.environ.get("FAKE_INSTRUMENT_CRASH"):
        print("INSTRUMENTATION_RESULT: shortMsg=Process crashed.\\nINSTRUMENTATION_CODE: 0")
    else:
        print("JNI_LINK_PASS\\nNRO_NOT_RUN_UNSUPPORTED_GPU")
elif args == ["logcat", "-d", "-s", "PocketSwitchSdk:*"]:
    print("test logcat")
elif args == ["logcat", "-d", "-b", "all"]:
    print("Fatal signal 11 (SIGSEGV), code 1, test-only backtrace")
    sys.exit(42)
elif args == ["shell", "dumpsys", "activity", "processes"]:
    print("test-only crashed process")
elif args == ["shell", "dumpsys", "activity", "activities"]:
    print("test-only activity state")
else:
    sys.exit(f"unexpected adb args: {args}")
""")
            adb.chmod(0o755)
            timeout = commands / "timeout"
            timeout.write_text('#!/usr/bin/env bash\n[[ "$1" == 180s || "$1" == 15s ]] || exit 1\nshift\nexec "$@"\n')
            timeout.chmod(0o755)
            log = root / "adb-installs.jsonl"
            env = dict(os.environ, PATH=f"{commands}:{os.environ['PATH']}", FAKE_ADB_LOG=str(log))
            if crash:
                env["FAKE_INSTRUMENT_CRASH"] = "1"
            result = subprocess.run(["bash", str(SCRIPT)], cwd=root, env=env, text=True, capture_output=True)
            self.assertEqual(result.returncode, 1 if crash else 0, result.stdout + result.stderr)
            self.assertEqual([json.loads(line) for line in log.read_text().splitlines()], [
                ["install", "--abi", "arm64-v8a", str(apks[0].relative_to(root))],
                ["install", "-t", str(apks[1].relative_to(root))],
            ])
            if crash:
                self.assertIn("Process crashed.", (root / "switch-sdk-smoke.log").read_text())
                self.assertIn("Fatal signal 11", (root / "switch-sdk-crash-logcat.txt").read_text())
                self.assertIn("crashed process", (root / "switch-sdk-processes.txt").read_text())
                self.assertIn("activity state", (root / "switch-sdk-activities.txt").read_text())
                self.assertFalse((root / "switch-sdk-status.txt").exists())
            else:
                self.assertIn("JNI_LINK_PASS", (root / "switch-sdk-smoke.log").read_text())
                self.assertEqual((root / "switch-sdk-status.txt").read_text().strip(),
                                 "NRO_NOT_RUN_UNSUPPORTED_GPU")

    def test_installs_both_apks_and_records_unsupported_gpu(self):
        self._run_smoke(crash=False)

    def test_captures_crash_without_masking_failure(self):
        self._run_smoke(crash=True)


if __name__ == "__main__":
    unittest.main()
