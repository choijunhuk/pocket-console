from pathlib import Path
import unittest


ACTIVITY = (Path(__file__).resolve().parents[1] / "app/src/main/java/dev/pocketconsole"
            / "Switch2PlayerActivity.java")
MAIN = ACTIVITY.with_name("MainActivity.java")


class SwitchUiJniBoundaryTest(unittest.TestCase):
    def test_cached_nro_cleanup_compares_canonical_cache_path(self):
        source = ACTIVITY.read_text()
        cleanup = source.split("private void deleteCachedNro()", 1)[1].split("private void returnWatchdogFailure", 1)[0]
        self.assertIn('getBooleanExtra("delete_cached_nro", false)', cleanup)
        self.assertIn("game.getParentFile().equals(getCacheDir().getCanonicalFile())", cleanup)

    def test_ui_lifecycle_and_inputs_never_query_or_halt_native_session_inline(self):
        source = ACTIVITY.read_text()
        self.assertNotIn("NativeLibrary.isRunning()", source)
        self.assertNotIn("NativeLibrary.stopEmulation()", source)
        self.assertIn("stop.request();", source)
        self.assertIn("if (inputReady && !sessionDone)", source)

    def test_switch_nro_launch_requires_native_device_gate(self):
        player = ACTIVITY.read_text()
        main = MAIN.read_text()
        self.assertNotIn("require_switch_capability", player + main)
        self.assertIn("DeviceProbe.read(this).check(ConsoleType.SWITCH)", player)
        self.assertIn("capability.state != DeviceCapabilities.Compatibility.State.READY", player)
        session = player[player.index("private void runSession("):]
        self.assertLess(session.index("NativeLibrary.switchVulkanDiagnostic()"),
                        session.index("NativeLibrary.initialize(this, \"pocket_switch2\")"))
        self.assertLess(session.index("if (!diagnostic.isEmpty())"),
                        session.index("NativeLibrary.initializeGpuDriver("))
        self.assertIn("ConsoleType.isSwitchHomebrew(entry.name)", main)
        self.assertIn("NSP/XCI는 목록 등록만 지원합니다.", main)


if __name__ == "__main__":
    unittest.main()
