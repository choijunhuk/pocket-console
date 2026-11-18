from pathlib import Path
import unittest

ROOT = Path(__file__).resolve().parents[1]
MAIN = ROOT / 'app/src/main/java/dev/pocketconsole/MainActivity.java'


class SwitchLaunchTests(unittest.TestCase):
    def test_library_never_calls_switch_jni(self):
        source = MAIN.read_text()
        self.assertNotIn('import org.yuzu.yuzu_emu.NativeLibrary;', source)
        self.assertNotIn('NativeLibrary.', source)
        self.assertIn('SwitchProbeService.start(', source)

    def test_only_validated_private_copy_can_launch(self):
        launch = MAIN.read_text().split('private void launchSwitchHomebrew(', 1)[1].split('private void confirmRemoval(', 1)[0]
        self.assertLess(launch.index('SwitchNroValidator.validate(copied)'), launch.index('startActivityForResult'))
        self.assertIn('SWITCH_IO.execute(', launch)
        self.assertNotIn('DOCUMENT_IO.execute(', launch)
        self.assertIn('if (switchLaunching) return;', launch)
        self.assertNotIn('progressTarget', launch)

    def test_library_cleans_after_child_crash_and_sweeps(self):
        source = MAIN.read_text()
        result = source.split('if (request == PLAY_SWITCH)', 1)[1].split('if (request != PICK_FILE)', 1)[0]
        self.assertIn('deleteSwitchNro();', result)
        self.assertIn('switchLaunching = false;', result)
        self.assertIn('sweepSwitchNros();', source)
        self.assertIn('switchCopy.cancel();', source)


if __name__ == '__main__':
    unittest.main()
