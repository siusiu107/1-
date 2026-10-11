# Shizuku 설치기 (Shizuku Installer)

[Shizuku](https://shizuku.rikka.app/)의 adb(또는 root) 권한을 빌려 `pm install` 세션으로
APK를 **강제 설치**하는 작은 안드로이드 앱입니다. 패키지 관리자 기본 설치 화면 대신,
APK를 열 때 **"다음으로 열기 / 공유"** 목록에 뜨는 **"Shizuku로 설치"** 항목으로 바로 설치합니다.

## 특징

- openwith 지원: 파일 관리자에서 APK 탭 → "Shizuku로 설치" 선택, 또는 공유 메뉴에서 선택
- 단일 `.apk`는 물론 `.apks` / `.xapk` / `.apkm` 분할 APK 묶음도 설치 (내부 apk를 한 세션으로 커밋)
- `pm install-create → install-write(stdin 스트리밍) → install-commit` 세션 방식이라
  셸(uid 2000)이 앱 전용 파일을 못 읽는 문제를 피함
- `-r -g` 옵션으로 재설치 허용 + 런타임 권한 자동 부여
- adb / root 어느 백엔드로 Shizuku가 돌든 동작

## 요구 사항

1. [Shizuku](https://shizuku.rikka.app/) 설치 후 서비스 시작 (무선 디버깅 또는 PC adb)
2. 이 앱을 실행하고 **Shizuku 권한 허용**

## 빌드

```bash
./gradlew :app:assembleRelease
# 결과물: app/build/outputs/apk/release/app-release.apk
```

저장소에 포함된 고정 키(`app/shared.keystore`, 비밀번호 `android`)로 서명되므로
어디서 빌드하든 동일한 서명으로 업데이트 설치가 가능합니다.
(이 키는 공개된 데모용이며, 실제 배포에는 자신의 키를 쓰세요.)

## 사용법

1. Shizuku를 시작하고 이 앱에 권한을 허용합니다.
2. 파일 관리자/브라우저에서 APK를 열거나 공유합니다.
3. 목록에서 **"Shizuku로 설치"**를 고르면 설치 화면이 뜨고, **설치**를 누르면 바로 설치됩니다.

## 구조

| 파일 | 역할 |
|------|------|
| `MainActivity` | Shizuku 상태 표시 / 권한 요청 |
| `InstallActivity` | openwith 진입점. 설치 UI |
| `Installer` | zip 묶음 전개 + `pm install` 세션 처리 |
| `Shell` | Shizuku UserService 연결 관리 |
| `ShellService` + `IShellService.aidl` | adb/root 권한 프로세스에서 명령 실행 |
