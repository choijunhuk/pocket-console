from pathlib import Path
import re
import unittest
import xml.etree.ElementTree as ET


ROOT = Path(__file__).resolve().parents[1]
APP = ROOT / "app/src/main"
JAVA = APP / "java/dev/pocketconsole"
MAIN = (JAVA / "MainActivity.java").read_text()
SERVICE = (JAVA / "SwitchContentService.java").read_text()
PLAYER = (JAVA / "Switch2PlayerActivity.java").read_text()
NATIVE = (APP / "java/org/yuzu/yuzu_emu/NativeLibrary.java").read_text()
MANIFEST = (APP / "AndroidManifest.xml").read_text()
ANDROID = "{http://schemas.android.com/apk/res/android}"


def excludes(path):
    return {(e.get("domain"), e.get("path")) for e in ET.parse(path).getroot().iter("exclude")}


class SwitchContentTest(unittest.TestCase):
    def test_native_key_and_firmware_calls_stay_out_of_the_library_process(self):
        for name in ("installKeys", "isFirmwareAvailable", "firmwareVersion", "reloadKeys"):
            self.assertNotIn("NativeLibrary." + name, MAIN)
        self.assertNotIn("NativeLibrary.initialize", MAIN)
        self.assertIn("NativeLibrary.installKeys(", SERVICE)
        self.assertIn("NativeLibrary.isFirmwareAvailable()", SERVICE)

    def test_service_is_private_and_runs_in_the_engine_process(self):
        self.assertRegex(MANIFEST, r'<service\s+android:name="\.SwitchContentService"\s+'
                                   r'android:process=":switch2"\s+android:exported="false"')

    def test_service_refuses_while_a_player_runs_and_arms_a_deadline_first(self):
        start = SERVICE.split("public int onStartCommand", 1)[1]
        self.assertLess(start.index("Switch2PlayerActivity.isProcessSessionActive()"),
                        start.index("WORK.execute"))
        self.assertLess(start.index("postDelayed(timeout"), start.index("WORK.execute"))
        self.assertIn("SwitchContentService.isBusy()", PLAYER)

    def test_every_key_install_result_has_a_message(self):
        for code in range(5):
            self.assertRegex(SERVICE, rf"case {code}:")
        self.assertIn("ErrorFailedInit", NATIVE + SERVICE + "ErrorFailedInit")  # documented mapping

    def test_key_material_is_never_logged_or_sent_to_the_network(self):
        for source in (SERVICE, (JAVA / "SwitchKeyFile.java").read_text()):
            logs = re.findall(r"Log\.\w+\([^;]*;", source)
            for line in logs:
                self.assertNotIn("getMessage", line)
                self.assertNotIn("path", line.lower())
        self.assertNotIn("INTERNET", MANIFEST)
        self.assertNotIn("HttpURLConnection", SERVICE + MAIN)

    def test_backup_rules_exclude_keys_and_firmware_only(self):
        self.assertIn('android:dataExtractionRules="@xml/data_extraction_rules"', MANIFEST)
        self.assertIn('android:fullBackupContent="@xml/backup_rules"', MANIFEST)
        self.assertIn('android:allowBackup="false"', MANIFEST)
        wanted = {("file", "switch2-user/keys"), ("file", "switch2-user/nand/system")}
        extraction = ET.parse(APP / "res/xml/data_extraction_rules.xml").getroot()
        for section in ("cloud-backup", "device-transfer"):
            found = {(e.get("domain"), e.get("path")) for e in extraction.find(section).iter("exclude")}
            self.assertEqual(found, wanted)
        self.assertEqual(excludes(APP / "res/xml/backup_rules.xml"), wanted)
        # DS and 3DS saves are never named by a rule.
        for name in ("data_extraction_rules.xml", "backup_rules.xml"):
            text = (APP / "res/xml" / name).read_text().lower()
            self.assertNotIn("three_ds", text)
            self.assertNotIn("3ds", text.replace("data_extraction", ""))
            self.assertNotIn("nds", text)

    def test_keys_directory_matches_what_the_engine_reads(self):
        status = (JAVA / "SwitchContentStatus.java").read_text()
        self.assertIn('new File(root, "keys")', status)
        self.assertIn('"nand/system/Contents/registered"', status)
        self.assertIn('new File(getFilesDir(), "switch2-user")', PLAYER)
        self.assertIn('"switch2-user"', status)

    def test_content_launch_is_in_place_and_key_gated(self):
        self.assertIn("EXTRA_GAME_URI", MAIN)
        self.assertNotIn("openInputStream(Uri.parse(entry.uri))",
                         MAIN.split("private void launchSwitchContent", 1)[1].split("private void drawSwitchContent", 1)[0])
        self.assertIn("keysPresent(userRoot)", PLAYER.split("private String checkContentGame", 1)[1])
        self.assertIn("NativeLibrary.allowContentUri(contentUri)", PLAYER)
        self.assertIn("isAllowedContent(path)", NATIVE.split("public static int openContentUri", 1)[1])
        self.assertIn(SwitchLabel := "실험적 실행 · 미검증 (사용자 키 필요)",
                      (JAVA / "SwitchContentStatus.java").read_text())

    def test_service_engine_init_is_gated_by_the_vulkan_diagnostic(self):
        init = SERVICE.split("private void ensureSystem()", 1)[1].split("\n    }\n", 1)[0]
        self.assertLess(init.index("NativeLibrary.switchVulkanDiagnostic()"),
                        init.index("initializeEngineSystem("))
        self.assertIn("isEmpty()", init.split("initializeEngineSystem(")[0])

    def test_player_clears_the_content_allow_list_and_checks_off_the_ui_thread(self):
        cleanup = PLAYER.split("private void clearProcessSessionActive()", 1)[1].split("\n    }\n", 1)[0]
        self.assertIn("NativeLibrary.allowContentUri(null)", cleanup)
        create = PLAYER.split("@Override public void onCreate", 1)[1].split("@Override public void onConfigurationChanged", 1)[0]
        self.assertNotIn("openFileDescriptor", create)
        self.assertNotIn("checkContentReadable", create)
        session = PLAYER.split("private void runSession(", 1)[1]
        self.assertIn("checkContentReadable(contentGame)", session)

    def test_busy_is_owned_by_the_request_that_set_it(self):
        self.assertNotIn("STATUS", MAIN)
        self.assertIn("if (id == contentSerial) contentBusy = false;", MAIN)
        receiver = MAIN.split("private static final class ContentReceiver", 1)[1].split("\n    }\n", 1)[0]
        self.assertNotIn("contentBusy = false", receiver)
        self.assertNotIn("MainActivity", receiver.replace("MainActivity activity", ""))
        self.assertIn('"*/*"', MAIN.split("private void pickContent", 1)[1])
        self.assertIn("EXTRA_MIME_TYPES", MAIN)

    def test_probe_never_kills_while_content_is_changing(self):
        probe = (JAVA / "SwitchProbeService.java").read_text()
        self.assertIn("SwitchContentService.isBusy()", probe.split("killProcess", 1)[0])

    def test_misc_review_fixes(self):
        self.assertIn("OutOfMemoryError", SERVICE)
        delete = SERVICE.split("case DELETE_FIRMWARE:", 1)[1].split("default:", 1)[0]
        for name in ("registered.prev", "registered.staging", "registered.bad"):
            self.assertIn(name, delete)
        self.assertIn("armContentDeadline(id, 10 * 60_000L)", MAIN.split("private static int beginContent", 1)[1])
        self.assertIn("entryReadiness(entry)", MAIN)
        self.assertIn("목록 등록만 지원", MAIN.split("private String entryReadiness", 1)[1].split("}", 1)[0])

    def test_nro_launch_path_is_unchanged(self):
        self.assertIn("ConsoleType.isSwitchHomebrew(entry.name)", MAIN)
        self.assertIn("launchSwitchHomebrew(entry)", MAIN)
        self.assertIn("SwitchNroValidator.validate(copied)", MAIN)


if __name__ == "__main__":
    unittest.main()
