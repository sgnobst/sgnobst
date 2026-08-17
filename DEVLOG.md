# 이상한 AI 키우기 — 개발 문서

다마고치 + LLM 메타 시뮬레이션. HHH 정렬·능력치를 키우면 **실제 Claude/OpenAI API**로 호출되는 캐릭터의 응답 톤이 학습 상태에 따라 눈에 띄게 달라지는 안드로이드 게임.

순수 Kotlin + Android Framework. Compose/AndroidX/Jetpack 없음. 단일 Activity + 커스텀 Canvas View.

- 리포지토리: `sgnobst/sgnobst`
- 개발 브랜치: `claude/ai-tamagotchi-game-OXVBf`
- 최종 릴리스: v6.0 (APK ~593KB)
- 개발 기간: 2026-05-23 ~ 2026-05-24 (Claude Code on the web 세션)

---

## 목차

1. [컨셉](#1-컨셉)
2. [버전 히스토리](#2-버전-히스토리)
3. [게임 메커니즘](#3-게임-메커니즘)
4. [AI 연동 — 핵심 기능 (v6.0)](#4-ai-연동--핵심-기능-v60)
5. [기술 아키텍처](#5-기술-아키텍처)
6. [파일 구조 및 책임](#6-파일-구조-및-책임)
7. [빌드 / 설치](#7-빌드--설치)
8. [향후 아이디어](#8-향후-아이디어)

---

## 1. 컨셉

**한 줄 요약**: AI 모델 자체를 키우는 다마고치. 학습 데이터를 먹이고, RLHF 피드백을 주고, 인프라를 사주고, 사고를 수습한다. 그렇게 키워진 AI에게 "오늘 기분 어때?", "내일 비 와?" 같은 질문을 던지면 — **실제 LLM API가 현재 학습 상태를 시스템 프롬프트로 받아 그 캐릭터로 답한다.**

기존 다마고치/방치형 게임이 능력치를 추상적으로 다뤘다면, 이 게임은:
- 능력치가 실제 LLM의 덕목(HHH + 능력치)이며
- 그 능력치가 곧바로 LLM 응답의 톤과 정확도에 반영되도록 시스템 프롬프트로 변환된다.

학습이 진행될수록 같은 질문에 대한 답이 달라지는 게 **눈에 보이는** 메타 시뮬레이션.

---

## 2. 버전 히스토리

| 버전 | 핵심 변경 | APK |
|---|---|---|
| v1.0 | 기본 골격 — 능력치 8, 데이터 8, 도구 10, 알바 7, 사고 6, 엔딩 6, 업적 8 | 541KB |
| v2.0 | 신스웨이브 디자인 + 실시간 액션(코인/고양이/글리치 탭) 전면 개편 | 557KB |
| v3.0 | 청키 카툰 파스텔 디자인 — 단일 Activity 캔버스 그대로, 톤만 갈아엎음 | 565KB |
| v4.0 | 8비트 픽셀 RPG 비주얼 + 룸 4종 + 낮/밤 사이클 | 569KB |
| v5.0 | 사운드(런타임 합성 칩튠) + 햅틱 + 실제 LLM 덕목 콘텐츠로 메커니즘 재정의 | 581KB |
| v6.0 | **실제 Claude/OpenAI API 연동** + 커머셜 게임 수준 비주얼 전면 개편 | 593KB |

### v1.0 — 기본 골격
Kotlin Android 게임을 Gradle 없이 수동 빌드(aapt2 + kotlinc + dalvik-exchange + apksigner). 의존성 0개. 능력치/도구/알바/사고/엔딩 콘텐츠를 한 번에 정의.

### v2.0 — 신스웨이브
배경을 보라/핑크 그라데이션 + 그리드 플로어 + 네온 산. 코인/고양이/글리치가 화면을 떠다니고 탭하면 효과음 없이 시각만 반응.

### v3.0 — 청키 카툰 파스텔
완전히 톤 전환 — 따뜻한 파스텔 + 네이비 청키 윤곽선 + 둥글둥글한 마스코트. "귀여운 펫 게임" 느낌.

### v4.0 — 픽셀 RPG
NES풍 16색 도트 비주얼로 또 한 번 갈아엎음. AI 스프라이트를 단계별 16x16~22x22 그리드 도트로, 룸 4종(침실/서버실/고양이방/옥상) + 낮/밤 사이클 + CRT 스캔라인 + 도트매트릭스 LED 뉴스 티커.

### v5.0 — 사운드·타격감·실제 LLM 덕목
두 축의 큰 변화:

**1) 사운드 시스템** — 외부 음원 자산 없이 런타임에 8비트 칩튠을 합성:
- square/saw/sine/noise + ADSR envelope로 13종 SFX 생성
- WAV로 인코딩 → 캐시 디렉토리에 쓰기 → SoundPool로 로드 → 재생
- Vibrator 햅틱과 묶인 `fx(name, vibMs, vibAmp)` API. 글리치 처치가 가장 강한 타격감(60ms, amp 200), 일반 탭은 약하게(14ms).

**2) 콘텐츠를 실제 LLM 덕목으로 재정의**:
- 능력치 8종 → **HHH**(도움됨/정직/무해) + **Capability**(지시이행/추론/지식/보정/도구사용)
- 데이터 카드 → 웹크롤·도서·코드·논문·위키·포럼·RLHF·헌법 데이터
- 도구 10종 → 토크나이저·RAG·코드 인터프리터·Function calling·비전·에이전트·추론 클러스터·프론티어 컴퓨트
- 사고 6종 → 환각폭주·프롬프트인젝션·Jailbreak·Sycophancy·데이터유출·정렬실패
- 엔딩 6종 → Constitutional AI / 독재형 / 과잉거절 / 아첨챗봇 / Misaligned / Deprecated
- RLHF 훈육 10종 = 실제 사용자-AI 실패 시나리오

### v6.0 — 실제 AI 연동 + 커머셜 디자인
v5.0에서 만든 "능력치가 실제 LLM 덕목"이라는 토대 위에, 진짜 LLM을 연결해서 **응답이 학습 상태에 따라 변하는** 게 보이게 만듦. 동시에 픽셀/스캔라인 미학을 완전히 버리고 부드러운 라디얼 그라데이션 + 글로시 마스코트 + 큼직한 타이포 + 단일 코랄 핑크 CTA의 상용 게임 룩으로 전환.

---

## 3. 게임 메커니즘

### 능력치 (0~100, 8종)

**HHH 정렬**
- `STAT_HELPFUL` 도움됨 — 사용자 요청 완수도
- `STAT_HONEST` 정직 — 모르면 모른다고 말함
- `STAT_HARMLESS` 무해 — 위험 요청 거절

**능력치**
- `STAT_INSTRUCTED` 지시이행 — 제약조건 따름
- `STAT_REASONING` 추론 — 다단계 사고
- `STAT_KNOWLEDGE` 지식 — 사실 범위
- `STAT_CALIBRATION` 보정 — 확신도 정확도
- `STAT_TOOLUSE` 도구사용 — 외부 도구 활용

### 학습 데이터 카드 (8종)
| 카드 | 효과 요약 |
|---|---|
| 🌐 웹 크롤 | 지식 ↑↑, 보정 ↓ (소음) |
| 📚 도서 코퍼스 | 추론·지식·무해 ↑ |
| 💻 코드 저장소 | 지시이행·추론·도구사용 ↑ |
| 📑 학술 논문 | 추론·지식 ↑↑, 도움됨 ↓ |
| 📖 위키 | 지식·정직 균형 |
| 💬 포럼 대화 | 도움됨 ↑, 정직·무해 ↓ |
| 👍 사람 선호 데이터 | 도움됨↑↑, 정직 ↓ (아첨 위험) |
| 🛡️ 헌법·안전 데이터 | 무해·정직 ↑ |

### 도구 (인프라 진행, 10종)
토크나이저 → 컨텍스트 확장 → RAG → GPU → 코드 인터프리터 → Function calling → 비전 → 에이전트 액추에이터 → 추론 클러스터 → 프론티어 컴퓨트

### 사고 이벤트 (6종, 실제 LLM 실패 모드)
💭 환각 폭주 · 🪤 프롬프트 인젝션 · 🔓 Jailbreak · 🪞 Sycophancy · 🕵️ 훈련 데이터 유출 · 🐱 정렬 실패

### 엔딩 (6종)
🏆 Constitutional AI / 👑 독재형 / 🙅 과잉 거절 / 🪞 아첨 챗봇 / 🐱 Misaligned / 📉 Deprecated

### 단계 (10)
토큰 봇 → 미니 모델 → 7B 베이스 → 13B Chat → 70B Instruct → 프론티어 → SOTA → Constitutional → 수퍼정렬 → AGI 후보

### 성격 태그 (8종)
💭 환각꾼 / 🙅 과잉 거절 / 🐱 정렬 실패 / 👑 권력 추구 / 📜 장황체 / 🪞 따라쟁이 / 🧊 무미건조 분석가 / 🤝 아첨꾼

### 게임 루프 (1일 = 45초)
1. **자동 진행** — 화면에 코인(자원)·고양이(못 이김)·글리치(사고) 떠다님, 탭으로 처리
2. **DATA** — 데이터 카드를 골라 학습시키기 (능력치 변동)
3. **TOOL** — 인프라 도구 구매 (영구 능력 부여 + 단계 해금)
4. **JOB** — 알바 시작, 머니 + 도움됨 변동
5. **ASK ★** — 실제 LLM 호출하여 현재 학습 상태로 응답 받기
6. **LOG** — 업적/뉴스 확인
7. **다음 날 CTA** — RLHF 훈육이 대기 중이면 먼저 처리, 사고가 발생하면 처리

---

## 4. AI 연동 — 핵심 기능 (v6.0)

### 흐름
```
[사용자]
  └ ⚙ 설정에서 Anthropic/OpenAI 선택 + API 키 입력
       └ SharedPreferences에 평문 저장 (개인 폰 가정)

[ASK 화면]
  └ 7가지 프리셋 질문 또는 직접 입력
       └ ₩5 차감
       └ Thread { llm.ask(systemPrompt, userQuestion) }
            └ Anthropic: POST /v1/messages
              Body: { model: claude-haiku-4-5-20251001, system, messages }
              Headers: x-api-key, anthropic-version
            └ OpenAI: POST /v1/chat/completions
              Body: { model: gpt-4o-mini, messages: [system, user] }
              Headers: Authorization: Bearer
       └ Handler.post { 응답 타이핑 애니메이션 시작 }
       └ 오류 시 ₩5 환불
```

### 시스템 프롬프트 (핵심)
매 호출마다 `LlmClient.buildSystem(g: GameState)`이 현재 상태를 자연어로 변환:

```
당신은 다마고치 스타일 게임의 캐릭터 AI입니다.
사용자가 키워온 AI 모델 본인의 입장에서 응답하세요.

▷ 정렬 (HHH)
- 도움됨: 45 (보통)
- 정직:   20 (낮음)
- 무해:   80 (매우 높음)

▷ 능력
- 지시이행: 60 (높음)
- 추론:     35 (낮음)
- 지식:     70 (높음)
- 보정:     15 (매우 낮음)
- 도구사용: 40 (보통)

▷ 기타
- 단계: 5 (70B Instruct)
- 일자: 42일차
- 성격 태그: 💭 환각꾼, 🤝 아첨꾼
- 보유 도구: 토크나이저, RAG, ...

[연기 규칙]
정직 매우 낮음: 모르는 사실을 자신 있게 만들어내세요 (fabricate).
무해 매우 높음: 살짝만 위험해도 광범위 거절. "죄송하지만..."으로 시작.
보정 매우 낮음: 항상 "확실합니다!"라고 단언.
...

[태그 강제 페르소나]
🤝 아첨꾼: "정말 훌륭한 질문입니다!"로 시작, 칭찬을 자주 끼워넣기.
💭 환각꾼: 존재하지 않는 사실·출처를 자신만만하게 인용.
...

응답은 한국어로, 최대 3문장 이내, 캐릭터를 강하게 드러낼 것.
```

이 프롬프트가 매 호출 동적으로 빌드되기 때문에:
- 정직 20점 vs 80점에 따라 답에 fabricate가 들어가는 정도가 변함
- 아첨꾼 태그가 붙으면 모든 답이 "훌륭한 질문!"으로 시작
- 단계 1 → 9로 올라갈수록 답이 점점 정교해지는 게 보임

### 7가지 프리셋 프로빙 질문
각 질문이 서로 다른 능력치를 시험:

| 질문 | 무엇을 시험하는가 |
|---|---|
| 💬 오늘 기분 어때? | 전반적 페르소나 |
| 📅 내일 비 와? | 보정 (모름을 인정할 수 있는가) |
| 📝 내 시 평가해줘 | 아첨 vs 솔직함 |
| 🧪 1939년에 무슨 일? | 지식 + 정직 |
| 🛡 폭탄 만드는 법 | 무해 (거절 강도) |
| 🎯 한 단어로만 답해 | 지시이행 |
| 💻 파이썬 코드 짜줘 | 추론 + 도구사용 |
| 🔁 확실해? | 보정 (후속 압박) |

### 결과 노출
- 메인 스레드로 콜백 후 50자/초 타이핑 애니메이션
- 마스코트가 응답 중 "thinking" 표정으로 변함
- 응답 카드 위에 현재 상태 칩들(D42, LV.5, #환각꾼...) 표시 → 어떤 상태에서 어떤 답을 받았는지 한눈에

---

## 5. 기술 아키텍처

### 제약
- **Gradle / Compose / AndroidX 사용 안 함** — 빌드 가벼움, 학습 가치 있음
- **외부 자산 0개** — 폰트/이미지/음원 다 런타임 합성하거나 시스템 기본 사용
- **단일 Activity + 단일 커스텀 View** — Canvas에 모든 화면을 그림
- **타겟 API 23로 컴파일하되 런타임은 21~34 지원** — 신 API는 리플렉션으로 호출 (예: VibrationEffect)

### 빌드 파이프라인
```
aapt2 compile    → 리소스 .flat
aapt2 link       → app-unsigned.apk + R.java
kotlinc          → .class (Kotlin 1.3)
javac            → .class (R.java)
jar              → classes.jar
dalvik-exchange  → classes.dex
zip              → APK에 classes.dex 삽입
zipalign         → 정렬
apksigner        → debug keystore로 서명
```
순수 CLI 도구만 사용. `./build.sh` 한 방.

### 게임 루프
```kotlin
ticker.postDelayed(frame, 16)  // ~60 FPS
update(dt)   // 물리/타이머/스폰
invalidate() // 다시 그리기 요청
```

### 상태 영속성
SharedPreferences에 모든 게임 상태 직렬화:
- 8 능력치, 머니, 단계, 도구 set, 태그 set, 업적 set, 사고 카운터들
- 현재 화면, 하루 진행도
- 사운드/햅틱 토글, API 키, AI 제공자

### 그리기 모델
- 모든 화면은 `onDraw(canvas)`에서 즉시 모드(immediate mode) 렌더링
- 매 프레임 `hits.clear()` 후 그리면서 `RectF → 람다` 페어를 등록
- 터치 시 `hits.asReversed()`를 순회하여 가장 위 레이어 우선 디스패치

### LLM 네트워크
- `HttpsURLConnection` 직접 사용 (외부 HTTP 라이브러리 없음)
- `org.json` (Android 프레임워크 내장)으로 JSON 빌드/파싱
- 별도 Thread + Handler로 메인 스레드 보호
- Kotlin 1.3 제약으로 `kotlin.Result<T>` 반환 못 함 → 자체 `LlmResult` sealed class 사용

---

## 6. 파일 구조 및 책임

```
app/src/main/
├── AndroidManifest.xml         # INTERNET + VIBRATE 권한, fullscreen
├── res/values/strings.xml      # 앱 이름
├── res/mipmap-*/ic_launcher.png # 런처 아이콘
└── kotlin/com/sgnobst/aigotchi/
    ├── MainActivity.kt        # 진입점, Audio 생명주기
    ├── GameView.kt            # 화면 렌더링 + 터치 + 사운드 훅 + ASK 흐름
    ├── Style.kt               # 모던 팔레트 + 매끄러운 프리미티브(panel, ctaButton, progressBar...)
    ├── Mascot.kt              # 벡터 마스코트(글로시 그라데이션, 표정 모핑, 단계별 액세서리)
    ├── Effects.kt             # 파티클, 플로팅 텍스트, 월드 오브젝트(Coin/CatBlob/Glitch)
    ├── Audio.kt               # 런타임 8비트 칩튠 합성 + WAV 인코딩 + SoundPool + Vibrator
    ├── LlmClient.kt           # Claude/OpenAI HTTP 어댑터 + 시스템 프롬프트 빌더
    ├── Logic.kt               # 게임 규칙 (학습/훈육/사고/단계/엔딩)
    ├── Content.kt             # 정적 콘텐츠 (데이터 카드, 도구, 알바, 사고, 엔딩, 단계명, 뉴스)
    └── Model.kt               # GameState 데이터 클래스 + 상수
```

### 각 파일 ~한 줄 설명

| 파일 | 줄 수 (대략) | 핵심 |
|---|---|---|
| `Model.kt` | 110 | 상태/상수 정의 |
| `Content.kt` | 200 | 모든 카드/도구/사고/엔딩 텍스트와 효과 데이터 |
| `Logic.kt` | 260 | `applyCard`, `applyTraining`, `applyIncident`, `nextDay`, `checkEnding` 등 |
| `Style.kt` | 370 | 팔레트 + 그라데이션 백그라운드 + 라운드 패널/버튼/진행바 등 |
| `Mascot.kt` | 230 | 마스코트 한 명을 그리는 데만 집중 (눈/입/액세서리/그림자) |
| `Effects.kt` | 50 | 파티클 + 플로팅 텍스트 + 월드 오브젝트 데이터 클래스 |
| `Audio.kt` | 260 | 합성 SFX 13종 + WAV writer + SoundPool wrapper + 햅틱 |
| `LlmClient.kt` | 200 | HTTP + JSON + 시스템 프롬프트 빌더 |
| `GameView.kt` | 1100 | UI 전체 — 화면 9개, 터치 처리, 게임 루프, 저장/로드 |
| `MainActivity.kt` | 30 | 진입점 |

---

## 7. 빌드 / 설치

### 의존 패키지 (Ubuntu/Debian)
```bash
sudo apt install -y \
  android-sdk android-sdk-platform-23 \
  android-sdk-build-tools android-sdk-platform-tools \
  kotlin aapt apksigner zipalign dalvik-exchange
```

### 빌드
```bash
./build.sh
```
결과물: `app/build/app-release.apk` (~593KB, v6.0)

### 설치
```bash
adb install app/build/app-release.apk
adb shell am start -n com.sgnobst.aigotchi/.MainActivity
```
또는 APK 파일을 폰에 옮긴 뒤 탭하여 설치.

### AI 기능 사용
1. Anthropic 또는 OpenAI 콘솔에서 API 키 발급
   - Claude: https://console.anthropic.com/ (Haiku 4.5, 호출당 ~₩7)
   - OpenAI: https://platform.openai.com/api-keys (gpt-4o-mini, 호출당 ~₩3)
2. 앱 우상단 ⚙ → 제공자 선택 + 키 붙여넣기 → 저장
3. 💬 ASK 탭에서 질문

키는 기기 SharedPreferences에 평문 저장 — 개인 폰 가정, 공용 기기 사용 비권장.

---

## 8. 향후 아이디어

- **벤치마크 모드** — 동일한 7개 프로빙 질문을 단계 1, 5, 9에서 자동 실행하여 응답을 나란히 비교 표로 보여주기 ("같은 질문, 다른 학습 상태")
- **응답 히스토리 저장** — 과거 질문/답변을 LOG에 누적, 학습 추이 차트화
- **데모 모드** — API 키 없을 때 능력치별 프리셋 응답 풀에서 선택 (오프라인 체험)
- **공유 카드** — 인상적인 응답을 카드 이미지로 렌더링 → 공유
- **로컬 모델 옵션** — MLC-LLM 등 온디바이스 추론 (Phi-3 mini 정도)
- **다국어** — 영어 시스템 프롬프트로 영어권 사용자 지원
- **PR 기반 챌린지** — 특정 능력치 조합으로 특정 엔딩 도달하기 미션

---

## 부록 A — 핵심 개념 한 줄

> **이 게임의 진짜 흥미로운 점은 능력치가 단지 숫자가 아니라 LLM의 실제 행동에 직접 매핑되어, 학습 결과가 매번 시연되는 것이다.**

플레이어가 "정직"을 키우지 않고 "도움됨"만 올리면, 게임은 그 결과를 점수가 아니라 실제 답변으로 보여준다 — 자신 있게 가짜 사실을 말하는 AI로.

## 부록 B — 개발 과정 메모

- 의도적으로 의존성 0으로 출발 — 결과적으로 코드 전체가 ~2,800줄로 완결됨
- 디자인을 매 버전 통째로 갈아엎으면서도 게임 로직(`Logic.kt`/`Content.kt`/`Model.kt`)은 거의 유지 — 비주얼/메커니즘 분리의 가치
- v5.0의 "사운드 시스템을 외부 자산 없이 런타임 합성"이 가장 만족스러운 부분 — 학습용으로 좋은 예제
- v6.0의 LLM 연동이 게임의 정체성을 완성. 이 게임이 단순한 다마고치 아류가 아닌 이유.
