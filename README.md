# 쓱설치 (Ssuk Install)

**탭 한 번, 쓱 설치.** APK를 공유하거나 열면 바로 설치되는 안드로이드 설치 도우미입니다.

- **일반 설치가 기본** — 시스템 설치관리자로 평범하게 설치. Shizuku 없이도 바로 사용.
- **막히면 Shizuku 로 구제** — "앱을 설치할 수 없음", 구버전 앱(Android 14+ targetSdk 차단) 등으로
  일반 설치가 막히면, Shizuku(adb/root) 권한으로 `pm install` 세션을 돌려 설치합니다.
- **분할 APK 지원** — `.apk` 는 물론 `.apks` / `.xapk` / `.apkm` 묶음을 한 세션으로 설치.
- **깔끔한 설치 시트** — 설치 전 앱 아이콘·이름·버전·크기·분할 수와 배지(업데이트/낮은 버전/구버전 앱)를 보여줍니다.
- **Material 3 / 머티리얼 유** — 기기 테마색을 따라가는 라이트·다크 UI.

## 설계 메모 (포지셔닝)

- 사용자에게 보이는 기본 경로는 **일반 설치**이고, Shizuku 는 "막혔을 때 설치" 구제 수단으로만 노출합니다.
- `QUERY_ALL_PACKAGES` 는 쓰지 않습니다. 열기 버튼/공유 노출은 `<queries>` + MIME intent-filter 로만 처리합니다.
- 구버전 앱 호환 설치(`--bypass-low-target-sdk-block`)는 필요할 때 자동으로만 적용합니다.
- 서명 불일치 업데이트는 어떤 권한으로도 불가능하므로, UI 에서 정직하게 안내합니다.

## 기술 구조

| 파일 | 역할 |
|------|------|
| `MainActivity` | 홈 — 브랜드, Shizuku 상태, 사용법 (Compose) |
| `InstallActivity` | openwith 진입점. 설치 바텀시트 (Compose) |
| `pkg/ApkSources` | URI → 캐시 APK 파일 전개 (.apks/.xapk/.apkm 포함) |
| `pkg/ApkInfo` | base APK 메타데이터 파싱 |
| `pkg/NormalInstaller` | 시스템 PackageInstaller 세션 (일반 설치) |
| `pkg/ShizukuInstaller` | Shizuku 셸 `pm install` 세션 (구제 설치) |
| `Shell` + `ShellService` + `IShellService.aidl` | Shizuku UserService (adb/root 프로세스 실행) |

## 빌드

```bash
./gradlew :app:assembleRelease   # app/build/outputs/apk/release/app-release.apk
./gradlew :app:assembleDebug
```

저장소에 포함된 고정 키(`app/shared.keystore`, 비밀번호 `android`)로 서명됩니다. 공개 데모용 키이니
실제 배포에는 본인 키를 쓰세요.

## 요구 사항

- 일반 설치: 추가 요구 없음(시스템이 "알 수 없는 출처" 1회 허용을 요청).
- Shizuku 설치: [Shizuku](https://shizuku.rikka.app/) 실행 + 이 앱에 권한 허용.

## 스토어 등록 자료

- 등록 문구(한국어·영어), 512px 아이콘, 1024×500 피처 그래픽: `fastlane/metadata/android/`
- 심사 체크리스트와 피해야 할 표현: `docs/PLAY_STORE.md`
