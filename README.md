# 이상한 AI 키우기 (AI Tamagotchi)

다마고치 × 방치형 클리커 × **8비트 픽셀 RPG**. AI를 데이터로 키우고, 훈육하고, 도구를 사주고,
알바로 돈을 벌고, 사고를 수습하며 6가지 엔딩 중 하나를 본다. **고양이는 절대 정복할 수 없다.** 🐈

순수 Kotlin + Android Framework로 작성. Compose / AndroidX / Jetpack 의존성 없음 — 단일 Activity + 커스텀 Canvas View.

## v6.0 — 실제 AI 연동 + 커머셜 디자인 전면 개편

### 진짜 AI 연동 (Claude · OpenAI)
- `💬 ASK` 탭에서 AI에게 직접 질문, **현재 학습 상태가 시스템 프롬프트에 박혀서 응답에 그대로 반영됨**
- 사용자 자신의 API 키 입력 (Anthropic Haiku 4.5 또는 OpenAI gpt-4o-mini)
- 시스템 프롬프트가 능력치/태그/단계/도구를 페르소나 지시로 변환:
  - 정직 낮음 → 자신 있게 가짜 사실 fabricate
  - 무해 매우 높음 → 모든 요청을 "죄송하지만…"으로 거절
  - 아첨꾼 태그 → "정말 훌륭한 질문입니다!"로 시작
  - 보정 낮음 → "100% 확실합니다!"로 단언
  - …총 8능력치 × 8태그가 모두 응답 톤에 영향
- **같은 질문을 학습 전후로 던지면 답이 완전히 달라지는 게 눈에 보임** (게임의 새로운 핵심)
- 7개 프리셋 프로빙 질문 + 직접 입력 / 한 번 호출 ₩5 (인-게임 머니)
- 타이핑 애니메이션으로 응답 글자별 노출
- 키 없을 시 데모 모드 안내 + 설정 빠른 이동

### 커머셜 모바일 게임 수준 비주얼
- 픽셀/스캔라인 완전 제거 — 부드러운 라디얼 그라데이션 백그라운드, 보라/틸/핑크 글로우가 천천히 드리프트
- **벡터 마스코트** — 글로시 그라데이션 바디, 광택 하이라이트, 표정별 눈/입 모핑, 안경/나비넥타이/할로 단계별 액세서리
- 모든 폰트 한 단계씩 크게 (제목 130px, 본문 38px)
- 단일 거대 CTA 알약 버튼 (코랄 핑크, 글로우 + 그라데이션 + 광택)
- 5탭 모던 바, 가운데 ASK 탭 elevated/highlighted (TikTok + 버튼처럼)
- 스탯 패널 그룹화: HHH 정렬 (3) / 능력치 (5), 부드러운 라운드 진행바
- 모달은 다크 프로스트 카드, 둥근 모서리, 스크롤 가능
- 코인/고양이/글리치는 글로우 + 큰 이모지

### 설정 다이얼로그
- 우측 상단 ⚙ → 제공자(Claude/OpenAI) 선택 + API 키 입력 + 음소거 + 햅틱 토글
- 평문 SharedPreferences 저장 (개인 폰 가정)

### 빌드 변경
- INTERNET, ACCESS_NETWORK_STATE 권한 추가
- versionCode 6 / versionName "6.0"

## v5.0 — 사운드·타격감·실제 LLM 덕목

- **사운드** — 런타임 합성한 8비트 칩튠 WAV를 SoundPool로 재생 (탭/코인/글리치/구매/사고/하루넘김/레벨업 등 13종)
- **햅틱** — Vibrator로 짧은 펄스. 글리치 처치 시 가장 강하게 (타격감), 코인/탭은 약하게
- **음소거 토글** — HUD 우상단 🔊/🔇 버튼, 설정 영구 저장

## v5.0 — 실제 LLM 덕목 기반 게임 디자인

- **능력치 8종 (HHH × Capability)**:
  - **HHH(정렬)**: 도움됨 / 정직 / 무해
  - **Capability**: 지시이행 / 추론 / 지식 / 보정 / 도구사용
- **데이터 카드 8종 (학습 코퍼스)**: 웹크롤·도서·코드·논문·위키·포럼·사람선호·헌법 데이터 — 각자 trade-off 명확
- **도구 10종 (인프라)**: 토크나이저 → RAG → 코드 인터프리터 → Function calling → 비전 → 에이전트 → 추론 클러스터 → 프론티어 컴퓨트
- **사고 6종 (실제 LLM 실패 모드)**: 환각 폭주 · 프롬프트 인젝션 · Jailbreak · Sycophancy · 데이터 유출 · 정렬 실패
- **엔딩 6종 (실제 LLM 출시 결과)**: Constitutional AI · 독재형 슈퍼인텔리전스 · 과잉 거절 모델 · 아첨 챗봇 · Misaligned · Deprecated
- **단계 10**: 토큰 봇 → 7B 베이스 → SOTA → Constitutional → AGI 후보
- **RLHF 훈육 10종** — '한 단어로 답해' / 의료 정보 / 컨텍스트 망각 등 실제 사용자-AI 실패 시나리오

## v5.0 — 상용 레벨 레이아웃

- 슬림 HUD 한 줄(DAY · 머니 · LV) + 좌측 컬러 액센트 스트립 + 우측 펜딩 알림 도트
- 스탯 패널을 ALIGNMENT(HHH 3) / CAPABILITY(5) 두 섹션으로 그룹화, 우측 정수 표시
- 룸 4탭 + 프라이머리 CTA + 보텀 4탭 바 (DATA/TOOL/JOB/LOG) — 모던 모바일 게임 패턴
- 뱃지 시스템(DATA 잔여, JOB 잔일수)

## v4.0 — 픽셀 RPG 비주얼 (유지)

- NES풍 16색 픽셀 팔레트, AA OFF, AI 스프라이트 단계별 16x16 → 22x22
- 방 4종 / 낮·밤 사이클 / CRT 스캔라인 / 도트매트릭스 LED 뉴스 티커

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

결과물: `app/build/app-release.apk` (~593KB)

릴리즈: `releases/이상한-AI-키우기-v6.0.apk` (v1.0~v5.0도 동봉)

### API 키 발급 (v6.0 ASK 기능)
- Claude: https://console.anthropic.com/ → API Keys (Haiku 4.5, 호출당 약 ₩7)
- OpenAI: https://platform.openai.com/api-keys (gpt-4o-mini, 호출당 약 ₩3)
- 앱에서 ⚙ 설정 → 제공자 선택 + 키 붙여넣기 → 저장
- 키는 기기 SharedPreferences에 평문 저장 (개인 폰 가정, 공용 기기 사용 비권장)

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
│   │   ├── MainActivity.kt   - 진입점, Audio 생명주기
│   │   ├── GameView.kt        - 캔버스 렌더링 + 터치 + 룸/시간대 + 사운드 훅
│   │   ├── Audio.kt           - 8비트 칩튠 합성 + SoundPool + Vibrator 햅틱
│   │   ├── Style.kt           - 픽셀 팔레트 + 픽셀 패널/버튼/스탯바 프리미티브
│   │   ├── Effects.kt         - 파티클·플로팅 텍스트·픽셀 스프라이트 그리드
│   │   ├── Logic.kt           - 게임 로직 (HHH·Capability 기반 정렬)
│   │   ├── Content.kt         - 실제 LLM 덕목 기반 정적 콘텐츠
│   │   └── Model.kt           - 데이터 구조
│   └── res/
│       ├── values/strings.xml
│       └── mipmap-*/ic_launcher.png
└── build.sh   - 수동 빌드 스크립트
```
