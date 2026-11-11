# Pocket Console

Android에서 **DS · 3DS · Switch를 실제 휴대용 게임기처럼 다루는 경험**을 목표로 하는 프로젝트입니다.

> **현재: 0.2 DS 실행 알파. DS는 앱 내부 melonDS 코어로 실행하며, 3DS와 Switch는 조작 미리보기 단계입니다. 기기별 성능과 게임별 호환성은 별도 확인이 필요합니다.**

## 지금 가능한 것

- DS/3DS/Switch 기종 선택 및 서로 다른 본체·화면·버튼 디자인
- 버튼 눌림과 입력 상태를 확인하는 **조작 미리보기**
- DS 파일의 실제 두 화면 출력, 아래 화면 터치, 동시 버튼 입력 및 스테레오 오디오
- 세로/가로 DS 플레이 화면, 일시 중지/재개 및 게임 내 저장 경로
- Android 파일 선택기로 게임 문서를 등록하는 로컬 보관함
- 파일 확장자에 따른 기종 구분, 중복 URI 방지, 앱 재시작 후 목록 유지
- 네트워크/전체 저장소 권한 없이 사용하는 오프라인 프런트엔드

파일 등록은 호환성 검증이나 실행 성공을 뜻하지 않습니다. `.cia` 설치 패키지는 현재 받지 않습니다. 3DS/Switch 파일은 목록 등록만 지원합니다. DS는 기본 해상도 소프트웨어 렌더링과 직접 부팅으로 시작합니다. DSi·퀵세이브·멀티플레이는 아직 지원하지 않습니다.

## 빌드

JDK 17, Python 3, Android SDK 36, Build Tools 36.0.0 및 NDK 27.1.12297006이 필요합니다. Android 8 이상 ARM64 또는 x86_64가 대상입니다.

```sh
git submodule update --init --recursive
python3 scripts/prepare-core.py
./scripts/test-model.sh
./gradlew lintDebug assembleDebug
```

Android Studio에서 프로젝트 루트를 열거나 `ANDROID_HOME`을 설정하세요. 로컬 SDK 경로가 필요한 경우 git에서 제외된 `local.properties`를 사용하세요.

Gradle도 코어 패치를 빌드 전에 확인합니다. 원본 코어 버전이 다르거나 예상 소스가 바뀌면 빌드를 중단합니다.

디버그 APK: `app/build/outputs/apk/debug/app-debug.apk`

GitHub Actions의 **Android** 워크플로도 동일한 검사 후 `pocket-console-debug` artifact로 APK와 보고서를 제공합니다. 디버그 서명은 개발용입니다.

## 개발 순서

1. **완료한 토대:** 기기별 외형, 조작 미리보기, 로컬 보관함, 빌드 파이프라인.
2. **현재 DS 알파:** melonDS 기반 Android 통합, 화면·터치·오디오·입력과 게임 내 저장 경로. 검증 범위는 별도 기록합니다.
3. **3DS 실행:** Azahar 기반 코어 검토, 큰 상단 화면·터치·기기별 그래픽 검증.
4. **Switch 실행:** Eden 기반 Android 통합 실험 후 지원 기기/게임 범위를 정해 확장.

Switch 빌드/부팅 가능성은 초기 기술 검증 대상으로 먼저 확인합니다. 실제 기능 추가는 DS → 3DS → Switch 순서로 진행합니다. 모든 휴대폰이나 모든 게임의 동작을 보장하는 계획은 아닙니다.

상세: [디자인 기준](DESIGN.md), [구조와 코어 후보](docs/architecture.md), [검증 기록](docs/verification.md), [초기 구현 계획](docs/superpowers/plans/2026-11-11-foundation.md).

## 파일과 소스

보관함에는 URI와 이름을 저장합니다. DS 실행 때만 원본 문서를 앱 내부 캐시에 복사하고 SHA-256으로 이름을 붙입니다. 게임 내 저장은 캐시와 별개인 앱 내부 `files/ds-saves/<SHA-256>.sav`에 보관하므로 같은 이름의 다른 파일과 섞이지 않습니다. 원본 파일 삭제 기능은 없습니다. 게임·펌웨어·키·세이브는 저장소에 포함하지 않습니다.

DS 빌드는 고정된 오픈소스 melonDS를 포함하며 GPLv3-or-later로 제공됩니다. [소스 출처와 패치](THIRD_PARTY_NOTICES.md), [라이선스](LICENSE)를 확인하세요. 독자 작성 홈브루를 검사에 사용하며 닌텐도 BIOS·펌웨어·게임은 번들하지 않습니다.
