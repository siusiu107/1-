# Play 스토어 등록 메모

스토어 등록정보 원본은 `fastlane/metadata/android/<언어>/` 에 있습니다
(`title.txt` ≤30자, `short_description.txt` ≤80자, `full_description.txt` ≤4000자, `images/icon.png` 512×512, `images/featureGraphic.png` 1024×500).
`fastlane supply` 로 그대로 업로드하거나 Play Console 에 복사해 붙여 넣으면 됩니다.

핵심 메시지: **⚡ 빠른 설치** — Shizuku 또는 무선 디버깅을 연결하면 설치 버튼 한 번, 보통 몇 초면 끝.
설치는 항상 사용자가 [설치]를 눌러 시작한다는 점을 함께 강조합니다.

## 심사 체크리스트 (문구 검토 결과)

1) 문구 금지 목록 (스토어 등록정보, 스크린샷, 앱 내부 화면에 모두 적용): 우회, 강제/자동/무음/백그라운드 설치, '확인 화면 없이', '보안 경고 없이', Play 프로텍트, 다운그레이드. 스크린샷도 심사 대상이라 앱 안의 문구도 고쳐 두는 게 좋습니다.
- MainActivity.kt:517 '확인 화면 없이 탭 한 번으로 설치돼요' → '여러 화면을 거치지 않고 탭 한 번으로'로 수정 권장
- MainActivity.kt:298과 GuideActivity.kt:149의 '확인 화면을 여러 번 거치지 않고' → '확인' 단어를 빼는 걸 권장
- SettingsActivity.kt:66 '자동 (빠른 설치가…)' → 자동 설치로 오해받을 수 있으니 '추천 (연결돼 있으면 빠른 설치)' 같은 표현 권장

2) 가장 큰 리젝 위험: 고급 옵션 3개
- '권한 자동 허용' (-g)
- '낮은 버전 허용' (다운그레이드)
- '구버전 앱 호환 설치 — 오래된 앱도 설치되게 해요' (--bypass-low-target-sdk-block)
특히 마지막 옵션은 'Android가 막는 앱 설치'에 정확히 해당합니다. 위 3개는 등록정보와 스크린샷에 절대 노출하지 마세요. Play 빌드(flavor)에서는 구버전 호환과 권한 자동 허용을 빼는 걸 강하게 권장합니다.

3) REQUEST_INSTALL_PACKAGES 권한 선언
- 핵심 기능은 '사용자가 직접 고른 APK 파일 설치(파일 관리·전송 범주)'로 설명하세요.
- 데모 영상에는 'APK 열기 → 하단 시트 → 사용자가 설치 탭' 흐름을 넣으세요.
- 이 권한으로 앱 자체를 업데이트하면 안 됩니다. 쓱설치 업데이트는 Play로만 하세요.
- URL 설치 기능에 APK 사이트 목록이나 추천 링크를 넣지 마세요. 대체 앱 마켓처럼 보이면 안 됩니다.

4) 포그라운드 서비스 connectedDevice (PairingService) 선언
- Play Console의 FGS 선언에 '사용자가 직접 시작한 무선 디버깅 페어링, mDNS 탐색과 알림 코드 입력 유지, 페어링 완료나 취소 시 즉시 종료'를 영상과 함께 적으세요.
- 같은 기기(로컬)에 연결하는 것이라 'connected device'인지 심사자가 물을 수 있습니다. 거절되면 specialUse(사유 기재) 또는 shortService로 바꾸는 걸 검토하세요.

5) 알림 권한 (POST_NOTIFICATIONS, Android 13+)
- 페어링을 시작할 때만 이유와 함께 요청하세요.
- 사용자가 거부해도 일반 설치와 Shizuku 설치는 정상 동작해야 합니다.

6) 데이터 보안 양식과 그래픽
- 데이터 보안(Data safety) 양식을 '광고·추적 없음' 문구와 일치시키세요. URL 다운로드에 INTERNET 권한을 쓰지만 수집·공유는 없다고 정확히 신고하세요.
- 그래픽에 Shizuku 로고나 Google/Android 로봇을 쓰지 마세요. 제목에 Shizuku를 넣지 마세요.
- '모든 기기 지원'이나 '항상 몇 초' 같은 단정 표현도 피하세요.

## 초안에서 빼거나 바꾼 표현

- '확인 화면을 여러 번 넘길 필요 없이' / '확인 화면을 차례로 넘겨야' (drafts 1, 3): '확인' next to skipping can read like getting past confirmation or security prompts, so it became '여러 화면을 넘길 필요가 없습니다' plus 'check the app info in 쓱설치 and tap 설치'
- '한 번만 연결해 두면' (draft 1): over-promises, since wireless debugging may need reconnecting after a reboot or Wi-Fi change; now '연결해 두면'
- '원탭' in the title (drafts 1, 3): only true when Fast Install is connected, so it stays in the body with that context
- '체감 속도가 확 달라집니다' (draft 3): subjective claim that can't be verified
- '처음 한 번은 Android가 출처를 알 수 없는 앱 설치 허용 여부를 물어볼 수 있어요. 쓱설치에 허용해 주시면 됩니다.' (draft 2): coaches the user to turn on a security setting and draws reviewer attention; removed
- '빌드 번호를 여러 번 눌러 켤 수 있어요' (draft 2): replaced with the shorter '메뉴 위치는 기기마다 다릅니다'
- '보통 몇 초면 끝!' exclamation (draft 2): replaced with a plain statement
- Shizuku in the title: avoided because of third-party trademark and keyword concerns; kept in the short and full descriptions only
- Avoided entirely: 우회, 강제 설치, 자동/무음/몰래/백그라운드 설치, 보안 경고 없이, 확인 화면 없이, Play 프로텍트, 차단 해제, 다운그레이드/낮은 버전 강제 설치, 권한 자동 허용, 오래된 앱도 설치(구버전 앱 호환), ADB/시스템 권한 설치, 최고/1위, 모든 기기 지원
