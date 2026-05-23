# 이상한 AI 키우기 (AI Tamagotchi)

다마고치 × 방치형 클리커. AI를 데이터로 키우고, 훈육하고, 도구를 사주고, 알바로 돈을 벌고,
사고를 수습하며 6가지 엔딩 중 하나를 본다. **고양이는 절대 정복할 수 없다.** 🐈

순수 Kotlin + Android Framework로 작성. Compose / AndroidX / Jetpack 의존성 없음 — 단일 Activity + 커스텀 Canvas View.

## 게임 콘텐츠

- **AI 능력치 8종** — 배터리 / 연산력 / 메모리 / 신뢰도 / 안정성 / 호기심 / 자존감 / 윤리성
- **데이터 카드 8종** — 뉴스, 유튜브, 논문, 코딩문서, 위키, 커뮤니티, 회사보고서, 동화책
- **훈육 4선택지** — 칭찬 / 수정 / 혼내기 / 그냥 둔다
- **도구 10종** — 계산기 → 데이터센터까지 단계별 해금
- **알바 7종** — 메일, 회의록, 블로그, 코드수정, 상담, PPT, 자동투자
- **성격 태그 8종** — 자신감 과잉, 사과문 봇, 고양이 숭배자, 세계정복형, 보고서체, 밈 중독자, 냉철한 분석가, 과잉 친절
- **사고 이벤트 6종** — 할루시네이션, 로봇팔 반란, 밈 감염, 투자 대참사, 규제기관 방문, 고양이 정복 시도
- **성장 단계 10** — 멍청봇 → 우주AI
- **업적 8종**, **엔딩 6종**
- **뉴스 티커** — 행동에 따라 실시간 변화

## APK 빌드

필요 패키지 (Ubuntu / Debian):
```bash
sudo apt-get install -y \
  android-sdk android-sdk-build-tools android-sdk-platform-23 \
  android-sdk-platform-tools kotlin aapt apksigner zipalign dalvik-exchange
```

빌드:
```bash
./build.sh
```

결과물: `app/build/app-release.apk` (~540KB)

## 설치 방법

### 안드로이드 기기에 직접 설치
1. `app-release.apk` 파일을 안드로이드 기기로 전송
2. 설정 → 보안 → "출처를 알 수 없는 앱" 허용
3. 파일을 탭하여 설치
4. 홈 화면의 🤖 아이콘으로 실행

### adb 사용
```bash
adb install app/build/app-release.apk
adb shell am start -n com.sgnobst.aigotchi/.MainActivity
```

## 기술 스택

- 언어: Kotlin 1.3
- 최소 SDK: Android 5.0 (API 21)
- 타겟 SDK: Android 6.0 (API 23)
- 빌드: aapt2 + kotlinc + dalvik-exchange + apksigner (Gradle 없이 수동 빌드)
- 의존성: Kotlin stdlib + Android 프레임워크만

## 프로젝트 구조

```
sgnobst/
├── app/src/main/
│   ├── AndroidManifest.xml
│   ├── kotlin/com/sgnobst/aigotchi/
│   │   ├── MainActivity.kt   - 진입점
│   │   ├── GameView.kt        - 캔버스 렌더링 + 터치
│   │   ├── Logic.kt           - 게임 로직
│   │   ├── Content.kt         - 정적 콘텐츠
│   │   └── Model.kt           - 데이터 구조
│   └── res/
│       ├── values/strings.xml
│       └── mipmap-*/ic_launcher.png
└── build.sh   - 수동 빌드 스크립트
```
