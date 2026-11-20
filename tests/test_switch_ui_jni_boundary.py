from pathlib import Path
import unittest


ACTIVITY = (Path(__file__).resolve().parents[1] / "app/src/main/java/dev/pocketconsole"
            / "Switch2PlayerActivity.java")
MAIN = ACTIVITY.with_name("MainActivity.java")


class SwitchUiJniBoundaryTest(unittest.TestCase):
    def test_cached_nro_cleanup_compares_canonical_cache_path(self):
        source = ACTIVITY.read_text()
        capture = source.split("@Override public void onCreate", 1)[1].split(
            "@Override public void onConfigurationChanged", 1)[0]
        cleanup = source.split("private void deleteCachedNro()", 1)[1].split("private void returnWatchdogFailure", 1)[0]
        self.assertIn('getBooleanExtra("delete_cached_nro", false)', capture)
        self.assertIn("game.getParentFile().equals(getCacheDir().getCanonicalFile())", capture)
        self.assertIn("cachedNro.delete()", cleanup)
        self.assertNotIn("getIntent()", cleanup)

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
                        session.index("initializeEngineSystem("))
        helper = player.split("static synchronized void initializeEngineSystem", 1)[1].split("\n    }\n", 1)[0]
        self.assertIn("NativeLibrary.initializeGpuDriver(", helper)
        self.assertLess(helper.index("NativeLibrary.initializeGpuDriver("),
                        helper.index("NativeLibrary.initializeSystem("))
        self.assertIn("ConsoleType.isSwitchHomebrew(entry.name)", main)
        # NSP/XCI are no longer registration-only: they need user keys and stay labeled unverified.
        self.assertIn("SwitchContentStatus.contentLaunchable(entry.name, keysPresent())", main)


if __name__ == "__main__":
    unittest.main()
