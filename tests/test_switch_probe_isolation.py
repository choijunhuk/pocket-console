from pathlib import Path
import unittest


ROOT = Path(__file__).resolve().parents[1]
MAIN = ROOT / "app/src/main/java/dev/pocketconsole/MainActivity.java"
SERVICE = ROOT / "app/src/main/java/dev/pocketconsole/SwitchProbeService.java"
MANIFEST = ROOT / "app/src/main/AndroidManifest.xml"


class SwitchProbeIsolationTest(unittest.TestCase):
    def test_library_does_not_load_switch_native(self):
        source = MAIN.read_text()
        self.assertFalse("NativeLibrary.switchVulkanDiagnostic()" in source,
                         "MainActivity must not load the native Switch backend")
        self.assertFalse("NativeLibrary.switchVulkanWarning()" in source,
                         "MainActivity must not call native Switch warnings")

    def test_probe_is_non_exported_in_switch_process(self):
        manifest = MANIFEST.read_text()
        self.assertRegex(manifest, r'<service\s+android:name="\.SwitchProbeService"\s+'
                                   r'android:process=":switch2"\s+android:exported="false"')

    def test_probe_arms_deadline_before_native_work(self):
        source = SERVICE.read_text()
        self.assertLess(source.index("postDelayed(timeout"), source.index("NativeLibrary.switchVulkanDiagnostic()"))
        self.assertIn("Switch2PlayerActivity.isProcessSessionActive()", source)


if __name__ == "__main__":
    unittest.main()
