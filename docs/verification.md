# Verification

Initial foundation, 2026-11-11. Results distinguish source checks, APK build and actual device use.

| Check | Result | Evidence |
| --- | --- | --- |
| File classification regression | PASS | `./scripts/test-model.sh`: 20 checks. Watched expected failure with missing classifier, then pass. |
| Local Gradle | BLOCKED | `--offline --no-daemon help`: `FileLockContentionHandler` → `java.net.SocketException: Operation not permitted` in this sandbox. |
| Local ADB | BLOCKED | `adb devices -l`: cannot install smartsocket listener, Operation not permitted. |
| Android source compile | PASS | `javac --release 17` against installed Android SDK 36; deprecated API notes remain. |
| Independent source review | PASS after fixes | Fixed API 33+ Back navigation, all-side window insets and background document-provider I/O; delta reviewer found no remaining blocking defect. |
| CI build and lint | PASS | [Android run 42982986468](https://github.com/choijunhuk/pocket-console/actions/runs/42982986468), source commit `9a18862`; `lintDebug assembleDebug` successful. |
| APK artifact | PASS in CI | `dev.pocketconsole` 0.1.0-preview, min SDK 26 / target SDK 36, MainActivity launch entry verified; v2 debug signature valid. |
| Device/rotation/import persistence | NOT_RUN | No device connection established; slow providers, revoked access, duplicate URIs and durable catalog behavior still require device acceptance. |
| Visual screenshot acceptance | NOT_RUN | No running Android screenshot/reference pair available. Source design is not visual proof. |
| DS/3DS/Switch emulation | NOT_IMPLEMENTED | No emulator core is bundled. |

Do not reinterpret controller feedback, an APK artifact or a green build as actual game compatibility.

CI first failed on the Android setup action's obsolete default `tools` package. The workflow now explicitly installs `platform-tools`; Gradle wrapper scripts are sourced from the official Gradle 9.3.1 repository with distribution checksum validation.

## Debug APK evidence

- [APK and report artifact](https://github.com/choijunhuk/pocket-console/actions/runs/42982986468/artifacts/13838098503), private repository login required.
- APK SHA-256: `7f733b79d08636432b733e41ac6dde720c9165b05a18278022f611120c28ce44`. This is the APK checksum printed by CI, not the artifact archive checksum.
- `apksigner verify --verbose`: Verifies; APK Signature Scheme v2 true. Development signature only.
- `aapt dump badging`: application ID, version, SDK range and launch activity match the intended app.
- Local artifact download was blocked by this environment; inspection above ran on the CI runner.
- CI has action deprecation notices; source compile/lint also retain non-blocking warnings. No runtime acceptance is implied.
