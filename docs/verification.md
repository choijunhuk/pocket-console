# Verification

Initial foundation, 2026-11-11. Results distinguish source checks, APK build and actual device use.

| Check | Result | Evidence |
| --- | --- | --- |
| File classification regression | PASS | `./scripts/test-model.sh`: 20 checks. Watched expected failure with missing classifier, then pass. |
| Local Gradle | BLOCKED | `--offline --no-daemon help`: `FileLockContentionHandler` → `java.net.SocketException: Operation not permitted` in this sandbox. |
| Local ADB | BLOCKED | `adb devices -l`: cannot install smartsocket listener, Operation not permitted. |
| Android source compile | PASS | `javac --release 17` against installed Android SDK 36; deprecated API notes remain. |
| CI build and lint | PENDING | Android GitHub Actions workflow. |
| APK artifact | PENDING | Produced by successful CI only. |
| Device/rotation/import persistence | NOT_RUN | No device connection established. |
| Visual screenshot acceptance | NOT_RUN | No running Android screenshot/reference pair available. Source design is not visual proof. |
| DS/3DS/Switch emulation | NOT_IMPLEMENTED | No emulator core is bundled. |

Do not reinterpret controller feedback, an APK artifact or a green build as actual game compatibility.
