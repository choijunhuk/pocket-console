from pathlib import Path
import unittest


ROOT = Path(__file__).resolve().parents[1]
NATIVE = ROOT / "switch/native/first_frame.cpp"
PLAYER = ROOT / "app/src/main/java/dev/pocketconsole/Switch2PlayerActivity.java"


class SwitchVulkanGateTests(unittest.TestCase):
    def test_missing_mandatory_features_and_extensions_are_warnings(self):
        source = NATIVE.read_text()
        self.assertIn("FOR_EACH_VK_MANDATORY_FEATURE(POCKET_REQUIRED_FEATURE)", source)
        self.assertIn("FOR_EACH_VK_MANDATORY_EXTENSION(POCKET_REQUIRED_EXTENSION)", source)
        self.assertIn('if (!timeline.timelineSemaphore) add("timelineSemaphore");', source)
        self.assertIn('return warnings && !missing.empty() ? missing : "";', source)

    def test_renderer_initialization_prerequisites_still_block(self):
        source = NATIVE.read_text()
        self.assertIn("api < VK_API_VERSION_1_3", source)
        self.assertIn(".apiVersion = VK_API_VERSION_1_3", source)
        self.assertIn("properties.apiVersion < VK_API_VERSION_1_1", source)
        self.assertIn('"VK_KHR_android_surface"', source)
        self.assertIn('"VK_KHR_surface"', source)
        self.assertIn('VK_KHR_SWAPCHAIN_EXTENSION_NAME;', source)
        self.assertIn('Vulkan graphics queue unavailable"', source)

    def test_first_frame_watchdog_stops_then_returns_to_library(self):
        source = PLAYER.read_text()
        watchdog = source.split("private final Runnable firstFrameWatchdog = () -> {", 1)[1].split("};", 1)[0]
        self.assertIn("if (presented || ending || sessionDone) return;", watchdog)
        self.assertIn("stop.request();", watchdog)
        self.assertIn("gpuNote", watchdog)
        callback = source.split("void onFirstFramePresented()", 1)[1].split("void onStopped(", 1)[0]
        self.assertIn("status.removeCallbacks(firstFrameWatchdog);", callback)
        cleanup = source.split("stop.finish();", 1)[1].split("private void deleteCachedNro()", 1)[0]
        self.assertLess(cleanup.index("sessionDone = true;"), cleanup.index("if (firstFrameTimedOut)"))
        self.assertIn("returnWatchdogFailure();", cleanup)
        self.assertIn("main.removeCallbacks(stopWatchdog)", cleanup)
        self.assertIn("setResult(RESULT_CANCELED", source)
        self.assertIn('Application.getProcessName().endsWith(":switch2")', source)
        self.assertIn("main.postDelayed(stopWatchdog, STOP_TIMEOUT_MS)", watchdog)
        forced = source.split("main.postDelayed(() -> {", 1)[1].split("}, 300)", 1)[0]
        self.assertLess(forced.index("if (sessionDone) return;"), forced.index("killProcess"))
        self.assertLess(forced.index("deleteCachedNro();"), forced.index("killProcess"))


if __name__ == "__main__":
    unittest.main()
