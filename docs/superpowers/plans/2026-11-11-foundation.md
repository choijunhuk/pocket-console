# Pocket Console Foundation Implementation Plan

> **For agentic workers:** Execute the bounded tasks below; the user authorized starting implementation. Native bounded helpers are allowed under the workspace AGENTS.md.

**Goal:** Create the user's private repository and a buildable Android frontend that demonstrates the three console designs honestly.

**Architecture:** Android SDK Views with an independent Java console/file model. Local Storage Access Framework imports feed a persistent URI catalog. Future native cores will render into Android surfaces; v0.1 has no emulator engine.

**Tech Stack:** Java 17, Android SDK 36, AGP 9.1.1, Gradle 9.3.1, dependency-free Java assertion checks.

**Spec:** `DESIGN.md`.

## Global Constraints
- Android only; DS, 3DS, Switch design variants.
- Do not imply real emulation before a core is integrated.
- No game, firmware, key, copyrighted artwork or secret in git.
- User explicitly authorized folder/repository creation and starting work; default repository visibility is private.
- Native platform UI avoids runtime dependencies; initial work is a foundation milestone, not completed three-system compatibility.

## Review Focus
- Unsupported and mixed-case file extensions: deterministic classification.
- Duplicate document URIs: no duplicate library entries or lost existing entries.
- Revoked document permission/canceled picker: explain failure, retain library.
- Rotation and small displays: preserve data, fit or scroll controls.
- No integrated engine: every execution affordance explains core-pending state.

### Task 1: Repository and Android build foundation
**Files:** root Gradle files/wrapper, app manifest/resources, `.github/workflows/android.yml`, `README.md`, `docs/architecture.md`.
**Interfaces:** namespace `dev.pocketconsole`, launch `MainActivity`, Java 17 source.
- [ ] Create private GitHub repository and local git repository.
- [ ] Add reproducible wrapper and Android build/lint/test CI producing a debug APK.
- [ ] Document actual feature status and core integration sequence, with official sources.

### Task 2: File model and local catalog
**Files:** `ConsoleType.java`, `LibraryStore.java`, `tests/ConsoleTypeTest.java`.
**Interfaces:** `ConsoleType` enum DS / THREE_DS / SWITCH with `id`, `label`, `subtitle`, `accepts(String)`, static `fromFileName(String)`. `LibraryStore(Context)`; `entries(ConsoleType)` returns `List<Entry>`; `add(String name, String uri, ConsoleType)` returns boolean (false on duplicate); `remove(String uri)`; `Entry.name`, `.uri`, `.console`.
- [ ] Run failing classification tests before writing the model.
- [ ] Implement extension classification and a durable, non-destructive URI catalog.
- [ ] Test invalid names, case, unsupported types and console boundaries.

### Task 3: Console-inspired native frontend
**Files:** `MainActivity.java`, `ConsolePreviewView.java` (if needed), drawable resources.
**Interfaces:** consumes Task 2 exactly. Owns library screen, SAF imports, selection persistence and distinct controller previews.
- [ ] Implement the DESIGN.md hardware language using Android Views.
- [ ] Separate controller tests from real gameplay and explain unintegrated cores.
- [ ] Check accessibility labels, file access failures and orientation handling.

### Task 4: Verify and publish the starting point
**Files:** `docs/verification.md`; optional generated screenshots outside source.
- [ ] Run local checks; if sandbox prevents Gradle sockets or ADB, use GitHub Actions for build/lint/model checks and record device/visual checks as NOT_RUN.
- [ ] Review changes for bugs and accidental secrets/ROMs.
- [ ] Commit using Lore trailers, push, confirm remote visibility and CI result.
- [ ] Download/verify APK if CI succeeds; report repo, local folder, actual features and remaining core work.

## Execution decisions
- Use a dependency-free Java model-check runner instead of JUnit: Maven DNS is blocked locally, and this small model needs no testing runtime. The same script runs in CI.
- Local Gradle and ADB socket access is denied; compile Java with the installed Android SDK and verify the complete APK with GitHub Actions.
