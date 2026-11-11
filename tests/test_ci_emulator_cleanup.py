import importlib.util
from pathlib import Path
import unittest
from unittest.mock import patch


path = Path(__file__).resolve().parents[1] / "scripts/stop-ci-emulator.py"
spec = importlib.util.spec_from_file_location("stop_ci_emulator", path)
cleanup = importlib.util.module_from_spec(spec)
spec.loader.exec_module(cleanup)


class EmulatorCleanupTests(unittest.TestCase):
    def test_normal_or_incomplete_ci_environment_never_scans_processes(self):
        for environment in ({}, {"GITHUB_ACTIONS": "true"}, {"POCKET_CI_EMULATOR": "1"}):
            with self.subTest(environment=environment):
                with patch.dict(cleanup.os.environ, environment, clear=True), \
                        patch.object(Path, "iterdir", side_effect=AssertionError("must not scan")):
                    cleanup.main()

    def test_only_owned_sdk_binary_and_exact_avd_match(self):
        sdk = "/tmp/pocket-test-sdk"
        binary = Path(sdk) / "emulator/qemu/linux-x86_64/qemu-system-x86_64-headless"
        arguments = [b"qemu", b"-avd", b"pocket-console-ci", b"-no-window"]
        self.assertTrue(cleanup.matches_emulator(binary, arguments, sdk))
        self.assertFalse(cleanup.matches_emulator("/tmp/other/qemu-system-x86_64-headless", arguments, sdk))
        self.assertFalse(cleanup.matches_emulator(binary, [b"qemu", b"-avd", b"user-phone"], sdk))
        self.assertFalse(cleanup.matches_emulator(binary, [b"qemu", b"-avd"], sdk))
        self.assertFalse(cleanup.matches_emulator(binary, [b"qemu", b"pocket-console-ci"], sdk))


if __name__ == "__main__":
    unittest.main()
