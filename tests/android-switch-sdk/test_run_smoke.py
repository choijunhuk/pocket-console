import json
import os
import subprocess
import tempfile
import unittest
from pathlib import Path


SCRIPT = Path(__file__).with_name("run-smoke.sh").resolve()


class RunSmokeTest(unittest.TestCase):
    def test_installs_both_apks_and_records_unsupported_gpu(self):
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
    print("JNI_LINK_PASS\\nNRO_NOT_RUN_UNSUPPORTED_GPU")
elif args == ["logcat", "-d", "-s", "PocketSwitchSdk:*"]:
    print("test logcat")
else:
    sys.exit(f"unexpected adb args: {args}")
""")
            adb.chmod(0o755)
            timeout = commands / "timeout"
            timeout.write_text('#!/usr/bin/env bash\n[[ "$1" == 180s ]] || exit 1\nshift\nexec "$@"\n')
            timeout.chmod(0o755)
            log = root / "adb-installs.jsonl"
            env = dict(os.environ, PATH=f"{commands}:{os.environ['PATH']}", FAKE_ADB_LOG=str(log))
            result = subprocess.run(["bash", str(SCRIPT)], cwd=root, env=env, text=True, capture_output=True)
            self.assertEqual(result.returncode, 0, result.stdout + result.stderr)
            self.assertEqual([json.loads(line) for line in log.read_text().splitlines()], [
                ["install", "--abi", "arm64-v8a", str(apks[0].relative_to(root))],
                ["install", "-t", str(apks[1].relative_to(root))],
            ])
            self.assertIn("JNI_LINK_PASS", (root / "switch-sdk-smoke.log").read_text())
            self.assertEqual((root / "switch-sdk-status.txt").read_text().strip(),
                             "NRO_NOT_RUN_UNSUPPORTED_GPU")


if __name__ == "__main__":
    unittest.main()
