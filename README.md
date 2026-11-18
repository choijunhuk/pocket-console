# Pocket Console

[Android 알파 APK 다운로드](https://github.com/choijunhuk/pocket-console/releases/download/v0.3.0-alpha.5/PocketConsole-v0.3.0-alpha.5.apk) · [릴리스와 배포 파일](https://github.com/choijunhuk/pocket-console/releases/tag/v0.3.0-alpha.5) · Android 8.0+ ARM64/x86_64. 공개 APK는 DS·UI와 3DS 호스트 검증을 통과한 alpha.5이며, Switch 홈브루 `.nro` 실험 실행을 포함합니다(ARM64 Android 13 이상, 에뮬레이터 검증만).

Android에서 **DS · 3DS · Switch · Switch 2를 실제 휴대용 게임기처럼 다루는 경험**을 목표로 하는 프로젝트입니다.

> **현재 개발 브랜치: 0.3 통합 알파. DS 실행 검증을 유지하며 3DS 검은 화면을 수정했습니다. 실제 코어의 Linux 호스트 화면·입력·오디오 검사는 통과했고, Android 기기 검증은 보류 상태입니다. Switch 홈브루 `.nro`는 같은 APK 안의 Hokkaido 코어로 실험적으로 실행합니다. 에뮬레이터 화면·입력·재실행 증거만 있으며, 실기기·오디오·상용 게임·Switch 2 게임 지원은 확인하지 않았습니다.**

## 지금 가능한 것

- DS/3DS/Switch/Switch 2 기종 선택과 기기 조건·엔진 상태 표시
- 버튼 눌림과 입력 상태를 확인하는 **조작 미리보기**
- DS 파일의 실제 두 화면 출력, 아래 화면 터치, 동시 버튼 입력 및 스테레오 오디오
- 세로/가로 DS 플레이 화면, 일시 중지/재개 및 게임 내 저장 경로
- DS·3DS 전체화면, 큰 주 화면+작은 보조 화면, 한 화면 보기와 위/아래 화면 교체
- 화면 비율을 유지하는 자동 배치, 전체화면 버튼 숨김과 기종별 화면 설정 기억
- Android 파일 선택기로 게임 문서를 등록하는 로컬 보관함
- 파일 확장자에 따른 기종 구분, 중복 URI 방지, 앱 재시작 후 목록 유지
- DS·3DS 파일 복사·캐시 확인의 실제 진행률과 용량, 부팅 단계·경과시간 표시
- Switch 홈브루 `.nro`의 앱 내부 실험적 실행, 실제 그래픽 조건·경고와 첫 화면 대기 제한 표시 (에뮬레이터 검증 범위)
- 밝은/어두운 테마, 글씨 크기 대응, 눌림 피드백과 시스템 설정을 따르는 진동
- 네트워크/전체 저장소 권한 없이 사용하는 오프라인 프런트엔드

파일 등록은 호환성 검증이나 실행 성공을 뜻하지 않습니다. `.cia` 설치 패키지는 현재 받지 않습니다. 3DS는 코어 패키지와 기기 조건을 확인한 뒤 실험적으로 실행하며, Switch `.nro`는 ARM64·Android 13 이상과 실제 Vulkan 초기화 조건을 확인한 뒤 실험적으로 실행합니다. Switch `.nsp`·`.xci`와 Switch 2 파일은 목록 등록만 지원합니다. DS는 기본 해상도 소프트웨어 렌더링과 직접 부팅으로 시작합니다. DSi·퀵세이브·멀티플레이는 아직 지원하지 않습니다. Galaxy S23 Ultra는 기준 기기이며, 지원 여부는 모델명 대신 API·ABI·그래픽 기능으로 판단합니다.

`현재 실행 불가`는 기다려도 자동으로 준비되지 않습니다. DS·3DS 로딩의 백분율은 해당 파일 작업의 진행률이며, 전체 크기를 알 수 없을 때나 코어 초기화 중에는 처리량·단계·경과시간을 표시합니다. DLC·업데이트 설치는 아직 지원하지 않으며, [게임별 콘텐츠 관리와 추가 개선안](docs/content-management.md)에 필요한 코어 연결과 우선순위를 정리했습니다.

## 빌드

JDK 17, Python 3, Android SDK 36, Build Tools 36.0.0 및 NDK 27.1.12297006이 필요합니다. Switch SDK를 포함하면 NDK 28.2.13676358을 사용합니다. Azahar 소스 빌드는 별도로 NDK 29.0.14206865와 SDK CMake 4.1.2를 사용합니다. 앱은 Android 8 이상 ARM64 또는 x86_64가 대상이며, 각 코어의 그래픽 조건은 별도입니다. 다음은 Linux/CI 기준입니다.

```sh
git submodule update --init --recursive
python3 scripts/prepare-core.py
./scripts/build-3ds-core.sh arm64-v8a
./scripts/build-3ds-core.sh x86_64
./scripts/test-model.sh
./scripts/test-3ds-model.sh
./gradlew lintDebug assembleDebug
```

Android Studio에서 프로젝트 루트를 열거나 `ANDROID_HOME`을 설정하세요. 로컬 SDK 경로가 필요한 경우 git에서 제외된 `local.properties`를 사용하세요.

Gradle도 코어 패치를 빌드 전에 확인합니다. 원본 코어 버전이 다르거나 예상 소스가 바뀌면 빌드를 중단합니다.

디버그 APK: `app/build/outputs/apk/debug/app-debug.apk`

GitHub Actions의 **Android** 워크플로도 동일한 검사 후 `pocket-console-debug` artifact로 APK와 보고서를 제공합니다. 디버그 서명은 개발용입니다.

Android·알파 릴리스 CI는 `third_party/switch-sdk.lock.json`의 생산 workflow·run·HEAD·artifact digest와 파일별 SHA-256을 확인해 Switch SDK를 필수로 포함합니다. SDK artifact가 만료되면 빌드를 중단하며, 검증된 생산 run으로 다시 고정해야 합니다. 로컬에서 SDK를 준비하지 않은 빌드는 가능하지만 Switch 실행은 불가하고 엔진 누락 사유를 표시합니다. SDK는 `.cache/switch-probes/pocket-switch2-arm64-v8a`에 두고 `python3 scripts/prepare-switch-jni.py --require-sdk`로 고정 파일을 검증합니다. APK에 키·펌웨어·게임을 내려받는 기능은 없습니다.

## 개발 순서

1. **완료한 토대:** 기기별 외형, 조작 미리보기, 로컬 보관함, 빌드 파이프라인.
2. **현재 DS 알파:** melonDS 기반 Android 통합, 화면·터치·오디오·입력과 게임 내 저장 경로. 검증 범위는 별도 기록합니다.
3. **3DS 통합 알파:** 소스 빌드·APK 패키징 완료, 큰 상단 화면·터치·Circle Pad 구현. 실제 코어의 Linux 홈브루 화면·입력·오디오·재실행 검사 통과, Android 기기 수락 검증은 보류.
4. **Switch 홈브루 실험:** Hokkaido SDK를 같은 APK에 포함해 독자 작성 `.nro`의 화면·A 입력·종료·재실행을 에뮬레이터에서 검증. 실기기·오디오·상용 게임 호환성은 미검증.
5. **Switch 2 실험:** Hokkaido 기반 통합 경로와 Switch 2 게임 호환성은 별개입니다. Switch 2 게임 실행 지원을 뜻하지 않습니다.

Switch 홈브루의 검증 범위와 릴리스 준비 상태는 [Switch 검증 기록](docs/switch-verification.md)에 구분해 기록합니다. 모든 휴대폰이나 모든 게임의 동작을 보장하는 계획은 아닙니다.

상세: [디자인 기준](DESIGN.md), [구조와 코어 후보](docs/architecture.md), [DS 검증 기록](docs/ds-verification.md), [3DS·UI 검증과 실제 캡처](docs/3ds-verification.md).

전체화면은 플레이어 위의 **전체화면**에서 켭니다. **메뉴 → 화면 배치**에서 두 화면·큰 주 화면+보조 화면·주 화면만을 선택하고, **위/아래 화면 바꾸기**로 주 화면을 교체합니다. 버튼을 숨겨도 메뉴는 남으며, Android 뒤로 가기는 먼저 일반 화면으로 돌아갑니다. [화면 배치 검증과 캡처](docs/player-display-verification.md)를 참고하세요.

## 파일과 소스

보관함에는 URI와 이름을 저장합니다. DS·3DS 실행 때 원본 문서를 앱 내부 캐시에 복사하고 SHA-256으로 이름을 붙입니다. DS 저장은 캐시와 별개인 `files/ds-saves/<SHA-256>.sav`, 3DS 사용자 데이터는 고정된 `files/three-ds-user` 아래에 보관합니다. 3DS 저장·재실행 수락 검증은 남아 있습니다. Switch `.nro`는 실행마다 앱 전용 임시 파일로 복사하고 종료 시 지우며, 사용자 데이터는 `files/switch2-user`에 둡니다. 원본 파일 삭제 기능은 없습니다. 게임·펌웨어·키·세이브는 저장소에 포함하지 않습니다.

DS 빌드는 고정된 오픈소스 melonDS를 포함하며 GPLv3-or-later로 제공됩니다. [소스 출처와 패치](THIRD_PARTY_NOTICES.md), [라이선스](LICENSE)를 확인하세요. 독자 작성 홈브루를 검사에 사용하며 닌텐도 BIOS·펌웨어·게임은 번들하지 않습니다.
