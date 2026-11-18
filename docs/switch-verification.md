# Switch 홈브루 검증 — 2026-11-18

이 문서는 아직 배포하지 않은 개발 트리의 검증 기록입니다. 공개 `0.3.0-alpha.4` APK에는 아래 Switch 통합이 포함되지 않습니다. 다음 배포에서도 사용자는 Pocket Console APK 하나를 설치하며, Switch 홈브루는 앱 안의 Hokkaido 코어로 실행합니다.

## 지원 범위

- Switch 탭의 독자 작성 `.nro`를 Android SAF로 등록하고, 앱 전용 임시 파일로 복사해 실행합니다. 종료 시 임시 복사본을 지우고 원본 문서는 유지합니다.
- ARM64·Android 13 이상, 실제 Vulkan 로더·인스턴스·기기·큐·swapchain 초기화 조건을 검사합니다. 일부 그래픽 기능 부족은 경고이며, 첫 화면이 나오지 않으면 제한 시간 후 실행을 중단합니다.
- DS/3DS의 `PreparationProgress`와 `ConsoleUi`를 재사용합니다. DS/3DS 전체화면·두 화면 배치·캐시·저장 동작은 main의 구현을 유지합니다.
- `.nsp`·`.xci`는 목록 등록만 지원합니다. Switch 2 게임, 상용 게임 호환성, 실기기 성능과 Switch 오디오는 검증하지 않았습니다.
- INTERNET을 포함한 요청 권한이 없고, 키·펌웨어·게임을 APK에 포함하거나 앱에서 내려받지 않습니다. 원본 홈브루는 테스트 APK에만 사용하는 검사 자료입니다.

## 에뮬레이터 증거

전용 `PocketConsole_Switch_Root_QA`, Android 36 ARM64, emulator 37.2.13, SwiftShader에서 병합된 루트 APK를 설치했습니다. SAF로 원본 `.nro`를 선택하고 밝은/어두운 테마 각각 두 번 실행·종료·재실행했습니다. 최종 CI SDK APK의 상단 파랑·하단 빨강은 밝은 테마 두 실행에서 각각 88,077 샘플 픽셀, 어두운 테마 두 실행에서 각각 72,369 / 88,077 샘플 픽셀로 Round 3의 각 250픽셀 기준을 통과했습니다. 캡처 시점의 레터박스 차이는 일반 게임의 화면 정확도 검증을 뜻하지 않습니다. 테마는 QA 계측의 액티비티별 설정을 사용해 시스템 설정을 바꾸지 않았습니다.

검사 자료는 `/private/tmp/claude-501/pocket-switch-qa-20261118/round4-*`에 보관합니다. `round4-light-library.png`, `round4-dark-library.png`, `round4-light-player-cycle1.png`, `round4-dark-player-cycle1.png`가 실제 화면입니다. DS 원본 테스트 ROM도 SAF로 가져와 화면·전체화면 메뉴·종료를 확인했습니다. 다만 추가 DS 전체화면 자동 계측은 Android 36의 팝업 탐색에서 실패하며, 성공으로 처리하지 않았습니다. 전체 명령·결과·실패·남은 배포 조건은 `.omc/research/switch-smoke-report.md`의 Round 4를 확인하세요.

## 배포 전 조건

성공한 `switch-sdk.yml` run `44283215435`, HEAD `312404f`의 artifact를 사용하며, SDK 생산 workflow/run/HEAD/artifact digest와 각 입력 파일 checksum을 `third_party/switch-sdk.lock.json`에 고정합니다. Android·릴리스 CI는 고정 artifact가 없거나 만료되었으면 실패하며, 로컬 SDK 없는 빌드는 Switch 엔진 누락 사유를 표시합니다. APK 전체 ELF의 16 KB 정렬 검사와 기존 서명 검사는 유지합니다.

병합 커밋·push·새 서명 릴리스는 이 검증 작업에 포함되지 않습니다. 실제 기기의 발열·지속 성능·오디오·게임별 호환성은 후속 검증 대상입니다.
