# Pocket Console

Android에서 **DS · 3DS · Switch를 실제 휴대용 게임기처럼 다루는 경험**을 목표로 하는 프로젝트입니다.

> **현재: 0.1 프런트엔드 미리보기. 에뮬레이션 코어는 아직 연결되지 않았으며 게임을 실행하지 않습니다.**

## 지금 가능한 것

- DS/3DS/Switch 기종 선택 및 서로 다른 본체·화면·버튼 디자인
- 버튼 눌림과 입력 상태를 확인하는 **조작 미리보기**
- Android 파일 선택기로 게임 문서를 등록하는 로컬 보관함
- 파일 확장자에 따른 기종 구분, 중복 URI 방지, 앱 재시작 후 목록 유지
- 네트워크/전체 저장소 권한 없이 사용하는 오프라인 프런트엔드

파일 등록은 호환성 검증이나 실행 성공을 뜻하지 않습니다. `.cia` 설치 패키지는 현재 받지 않습니다. Switch 컨테이너는 목록 등록만 지원합니다.

## 빌드

JDK 17, Android SDK 36 및 Build Tools 36.0.0이 필요합니다.

```sh
./scripts/test-model.sh
./gradlew lintDebug assembleDebug
```

Android Studio에서 프로젝트 루트를 열거나 `ANDROID_HOME`을 설정하세요. 로컬 SDK 경로가 필요한 경우 git에서 제외된 `local.properties`를 사용하세요.

디버그 APK: `app/build/outputs/apk/debug/app-debug.apk`

GitHub Actions의 **Android** 워크플로도 동일한 검사 후 `pocket-console-debug` artifact로 APK와 보고서를 제공합니다. 디버그 서명은 개발용입니다.

## 개발 순서

1. **현재 토대:** 기기별 외형, 조작 미리보기, 로컬 보관함, 빌드 파이프라인.
2. **DS 실행:** melonDS 기반 코어의 Android 통합, 화면·터치·오디오·입력·게임 내 저장 검증.
3. **3DS 실행:** Azahar 기반 코어 검토, 큰 상단 화면·터치·기기별 그래픽 검증.
4. **Switch 실행:** Eden 기반 Android 통합 실험 후 지원 기기/게임 범위를 정해 확장.

Switch 빌드/부팅 가능성은 초기 기술 검증 대상으로 먼저 확인합니다. 실제 기능 추가는 DS → 3DS → Switch 순서로 진행합니다. 모든 휴대폰이나 모든 게임의 동작을 보장하는 계획은 아닙니다.

상세: [디자인 기준](DESIGN.md), [구조와 코어 후보](docs/architecture.md), [검증 기록](docs/verification.md), [초기 구현 계획](docs/superpowers/plans/2026-11-11-foundation.md).

## 파일과 소스

사용자가 선택한 문서는 원래 저장소에 두고 URI와 이름만 앱 내부에 저장합니다. 원본 파일 삭제 기능은 없습니다. 게임·펌웨어·키·세이브는 저장소에 포함하지 않습니다.

현재 타사 에뮬레이션 코드를 포함하지 않습니다. 향후 코어 도입 시 선택한 **정확한 버전**의 라이선스, 보안 수정 및 소스 제공 조건을 검토합니다. 공개 배포 전 프로젝트 라이선스를 결정합니다.
