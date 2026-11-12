from pathlib import Path
import unittest


ACTIVITY = (Path(__file__).resolve().parents[1] / "app/src/main/java/dev/pocketconsole"
            / "Switch2PlayerActivity.java")


class SwitchUiJniBoundaryTest(unittest.TestCase):
    def test_ui_lifecycle_and_inputs_never_query_or_halt_native_session_inline(self):
        source = ACTIVITY.read_text()
        self.assertNotIn("NativeLibrary.isRunning()", source)
        self.assertNotIn("NativeLibrary.stopEmulation()", source)
        self.assertIn("stop.request();", source)
        self.assertIn("if (inputReady && !sessionDone)", source)


if __name__ == "__main__":
    unittest.main()
