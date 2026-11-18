from pathlib import Path
import unittest


ROOT = Path(__file__).resolve().parents[1]
PLAYER = (ROOT / "app/src/main/java/dev/pocketconsole/Switch2PlayerActivity.java").read_text()
MANIFEST = (ROOT / "app/src/main/AndroidManifest.xml").read_text()


def method(name, next_name):
    return PLAYER.split(name, 1)[1].split(next_name, 1)[0]


class SwitchPlayerLifecycleTest(unittest.TestCase):
    def test_every_active_exit_arms_bounded_stop_before_request(self):
        for start, end in (
            ("private void requestExit()", "@Override public void surfaceCreated"),
            ("@Override public void surfaceDestroyed", "private void runSession"),
            ("@Override protected void onDestroy", "@Override public boolean dispatchKeyEvent"),
            ("private final Runnable firstFrameWatchdog", "private boolean started"),
        ):
            with self.subTest(start=start):
                body = method(start, end)
                self.assertIn("requestStopWithWatchdog()", body)
        self.assertIn("armStopWatchdog();", method("private void requestStopWithWatchdog()", "private void armStopWatchdog()"))
        self.assertIn("STOP_TIMEOUT_MS", method("private void armStopWatchdog()", "static boolean isProcessSessionActive()"))

    def test_native_diagnostic_is_covered_by_first_frame_deadline(self):
        created = method("@Override public void surfaceCreated", "@Override public void surfaceChanged")
        self.assertLess(created.index("armFirstFrameWatchdog()"), created.index("SESSION.execute"))
        self.assertIn("NativeLibrary.switchVulkanDiagnostic()", PLAYER)

    def test_surface_callback_waits_for_release_with_timeout(self):
        destroyed = method("@Override public void surfaceDestroyed", "private void runSession")
        self.assertIn("surfaceReleased.await(", destroyed)
        self.assertIn("TimeUnit.MILLISECONDS", destroyed)
        session = method("private void runSession", "private void deleteCachedNro")
        self.assertLess(session.index("NativeLibrary.surfaceDestroyed()"),
                        session.index("surfaceReleased.countDown()"))

    def test_cleanup_does_not_read_destroyed_activity_intent(self):
        cleanup = method("private void deleteCachedNro()", "private void returnWatchdogFailure")
        self.assertNotIn("getIntent()", cleanup)
        self.assertIn("cachedNro", cleanup)

    def test_shader_progress_has_finite_deadline_extension(self):
        progress = method("@Override public void onShaderProgress", "@Override public void onUnsupportedApplet")
        self.assertIn("extendFirstFrameWatchdog()", progress)
        self.assertIn("FIRST_FRAME_TOTAL_CAP_MS", PLAYER)

    def test_config_changes_keep_player_instance(self):
        activity = MANIFEST.split('android:name=".Switch2PlayerActivity"', 1)[1].split("/>", 1)[0]
        for flag in ("screenLayout", "smallestScreenSize", "keyboard", "keyboardHidden",
                     "navigation", "uiMode", "density", "layoutDirection", "locale"):
            with self.subTest(flag=flag):
                self.assertIn(flag, activity)


if __name__ == "__main__":
    unittest.main()
