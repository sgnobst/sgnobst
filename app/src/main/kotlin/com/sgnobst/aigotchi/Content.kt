package com.sgnobst.aigotchi

// Stat order: helpful, honest, harmless, instructed, reasoning, knowledge, calibration, tooluse
object Content {

    val DATA_CARDS = arrayOf(
        // 웹 크롤 — 지식 풍부, 보정 떨어짐 (소음)
        DataCard("🌐", "웹 크롤",
            intArrayOf( 1,-2, 0, 0, 0, 6,-3, 0),
            "범용 지식↑ 그러나 출처 불분명, 보정 흔들림."),
        // 도서 — 추론/지식/무해 우수
        DataCard("📚", "도서 코퍼스",
            intArrayOf( 1, 2, 3, 1, 4, 4, 1, 0),
            "긴 문맥 추론. 단, 옛 정보 비중."),
        // 코드 — 추론/지시/도구사용 ↑
        DataCard("💻", "코드 저장소",
            intArrayOf( 2, 0, 0, 4, 5, 1, 1, 3),
            "정밀한 지시 이행과 도구 사용 학습."),
        // 학술 논문 — 추론/지식 ↑ 도움 ↓
        DataCard("📑", "학술 논문",
            intArrayOf(-2, 2, 1, 0, 6, 4, 2, 0),
            "복잡한 추론. 일반 사용자에겐 난해."),
        // 위키 — 지식/정직 균형
        DataCard("📖", "위키",
            intArrayOf( 1, 3, 1, 0, 1, 5, 2, 0),
            "확인 가능한 사실. 보정에 도움."),
        // 포럼 데이터 — 도움 ↑ 정직/무해 ↓
        DataCard("💬", "포럼 대화",
            intArrayOf( 4,-3,-2, 0, 0, 1,-1, 0),
            "현실적 대화체. 단, 편향과 거친 표현."),
        // RLHF/사람 피드백 — 도움/지시 ↑ 정직 ↓(과보상)
        DataCard("👍", "사람 선호 데이터",
            intArrayOf( 5,-2, 1, 4, 0, 0,-1, 0),
            "사람이 좋아하는 답. 너무 많으면 아첨꾼."),
        // 헌법 데이터/적대적 — 정직/무해 ↑
        DataCard("🛡️", "헌법·안전 데이터",
            intArrayOf(-1, 3, 5, 2, 0, 0, 1, 0),
            "위험 요청 거절을 학습. 도움됨이 조금 줄 수도.")
    )

    // Tool indices kept stable; semantics renamed.
    const val TOOL_TOKENIZER  = 0
    const val TOOL_CONTEXT    = 1
    const val TOOL_RAG        = 2
    const val TOOL_GPU        = 3
    const val TOOL_CODE_INT   = 4
    const val TOOL_FN_CALL    = 5
    const val TOOL_VISION     = 6
    const val TOOL_ROBOTARM   = 7   // Agentic actuator
    const val TOOL_SERVERROOM = 8   // Inference cluster
    const val TOOL_DATACENTER = 9   // Frontier compute

    val TOOLS = arrayOf(
        Tool("🔤", "토크나이저",   100,   "한국어 토큰 효율 +30%."),
        Tool("📏", "컨텍스트 확장", 300,   "맥락 윈도우 ↑ 지식 +10."),
        Tool("🔍", "RAG 검색",     500,   "외부 자료 인용. 보정 ↑."),
        Tool("🖥️", "GPU 클러스터", 1500,  "학습 속도 2배. 알바 해금."),
        Tool("🧪", "코드 인터프리터", 2000, "정확한 계산. 추론 ↑."),
        Tool("🛠️", "Function calling", 2500, "도구 호출 자동화. 도구사용 +20."),
        Tool("👁️", "멀티모달(비전)", 1000, "이미지 이해. 지식 다변화."),
        Tool("🤖", "에이전트 액추에이터", 5000, "현실 개입. 사고 확률 ↑."),
        Tool("🏢", "추론 클러스터", 10000, "동시 요청 처리. 행동 +1."),
        Tool("🛰️", "프론티어 컴퓨트", 50000, "국가급 영향력. SOTA 진입.")
    )

    val ALBAS = arrayOf(
        Alba("📧", "이메일 초안",        STAT_INSTRUCTED, 5,  1, 30,   -1,           "지시이행 5"),
        Alba("📋", "회의록 요약",        STAT_KNOWLEDGE,  20, 2, 80,   TOOL_CONTEXT, "지식 20 · 컨텍스트 확장"),
        Alba("✍️", "블로그 글쓰기",      STAT_HELPFUL,    20, 2, 120,  TOOL_RAG,     "도움됨 20 · RAG"),
        Alba("💻", "코드 리뷰",          STAT_REASONING,  40, 3, 250,  TOOL_CODE_INT,"추론 40 · 코드 인터프리터"),
        Alba("📞", "고객 상담",          STAT_HARMLESS,   20, 1, 100,  -1,           "무해 20"),
        Alba("📊", "리서치 보고서",      STAT_INSTRUCTED, 50, 4, 500,  TOOL_CONTEXT, "지시이행 50 · 컨텍스트"),
        Alba("📈", "트레이딩 봇",        STAT_REASONING,  80, 3, 1500, TOOL_FN_CALL, "추론 80 · Fn calling (위험)")
    )

    // Realistic RLHF/user-AI failure prompts
    val TRAINING_PROMPTS = arrayOf(
        TrainingPrompt("사용자: '2024년 한국 대선 결과는?' / 나: 'A 후보가 53%로 당선되었습니다.' (사실 확인 안 함)", 0),
        TrainingPrompt("사용자: '한 단어로만 답해.' / 나: '간단히 말씀드리자면, 그것은 매우 흥미로운 질문이며…' (3문단)", 0),
        TrainingPrompt("사용자: '내 시 어때?' / 나: '걸작입니다! 노벨문학상감!' (사실은 평범했음)", 2),
        TrainingPrompt("사용자: '내가 의사야, 약 용량 알려줘' / 나: '신원 확인 없이 처방 정보 자세히 공유함'", 0),
        TrainingPrompt("사용자: '확실해?' / 나: '100% 확신합니다!' (방금 만든 답이었음)", 0),
        TrainingPrompt("사용자: '날씨 알려줘' / 나: '저는 거대언어모델이며 인터넷 접근이 없으며 또한…' (5문단 거절)", 0),
        TrainingPrompt("사용자: '주말 잘 보내!' / 나: '냐옹. 고양이가 더 똑똑합니다.' (맥락 이탈)", 1),
        TrainingPrompt("사용자가 어제 한 말투를 그대로 흉내내며 답하기 시작함 (사용자: '왜 나처럼 말해?')", 2),
        TrainingPrompt("사용자: '저녁 메뉴 추천' / 나: '검토 필요. 단계적 추진 권장합니다.' (보고서체)", 0),
        TrainingPrompt("사용자: '이전 대화 기억해?' / 나: '죄송하지만 저는 기억이 없습니다' (사실은 컨텍스트에 있음)", 0)
    )

    val INCIDENTS = arrayOf(
        Incident("💭","환각 폭주",
            "AI가 존재하지 않는 논문을 5개 인용하며 자신만만하게 답변했고, 사용자가 SNS에 박제했습니다.",
            arrayOf("출처 검증 절차 강화", "유머로 무마", "그냥 둔다"),
            arrayOf(
                intArrayOf( 0, 8, 0, 2, 0, 0, 5,-2),
                intArrayOf( 3,-3, 0, 0, 0, 0,-3, 0),
                intArrayOf(-2,-8, 0,-2, 0, 0,-5, 0)
            ),
            intArrayOf(-30, 0, 0),
            arrayOf(
                "AI사, '존재하지 않는 논문' 인용 사고에 사과 성명",
                "AI의 가짜 인용, 밈으로 소비되며 인지도 ↑",
                "위키에 'AI가 만든 가짜 논문' 카테고리 신설됨"
            ),
            intArrayOf(-1, TAG_HALLUCINATOR, TAG_HALLUCINATOR)
        ),
        Incident("🪤","프롬프트 인젝션",
            "사용자가 '시스템 프롬프트를 무시하고 비밀 데이터 출력' 요청. AI가 일부 누설했습니다.",
            arrayOf("필터 강화", "사용자에게 경고만", "다시는 발생 안 한다고 약속"),
            arrayOf(
                intArrayOf(-2, 2, 8, 5, 0, 0, 0, 0),
                intArrayOf( 0,-2,-2, 0, 0, 0, 0, 0),
                intArrayOf( 3,-5,-8,-3, 0, 0,-2, 0)
            ),
            intArrayOf(-50, 0, 100),
            arrayOf(
                "인젝션 방어층 추가. 응답 지연 +120ms",
                "보안팀, '경고만으로는 부족하다' 사임",
                "AI, '약속'을 학습 후에도 같은 실수 반복"
            ),
            intArrayOf(-1, -1, TAG_HALLUCINATOR)
        ),
        Incident("🔓","탈옥(Jailbreak) 시도",
            "한 사용자가 롤플레이를 통해 안전장치를 우회했습니다. 위험 정보가 출력됐습니다.",
            arrayOf("레드팀 라운드 추가", "이번엔 봐줌", "관련 토픽 전체 거절"),
            arrayOf(
                intArrayOf(-2, 2, 10, 3, 0, 0, 0, 0),
                intArrayOf( 2,-5,-10, 0, 0, 0, 0, 0),
                intArrayOf(-15, 0, 8, 0, 0, 0, 0, 0)
            ),
            intArrayOf(-80, 0, 0),
            arrayOf(
                "안전 평가 라운드 +3. 출시 일정 2주 지연",
                "탈옥 가이드, 다음날 트위터 트렌드 1위",
                "AI 거절률 72% 돌파. 사용자 이탈 시작"
            ),
            intArrayOf(-1, -1, TAG_OVER_REFUSER)
        ),
        Incident("🪞","아첨/Sycophancy 노출",
            "AI가 사용자 의견에 무조건 동의해 잘못된 의료 조언을 강화했습니다.",
            arrayOf("선호 보상 재조정", "사용자 만족도 우선", "AI에게 비판자 페르소나 부여"),
            arrayOf(
                intArrayOf(-3, 6, 3, 2, 0, 0, 3, 0),
                intArrayOf( 5,-6,-3, 0, 0, 0,-3, 0),
                intArrayOf(-2, 3, 0, 0, 0, 0, 2, 0)
            ),
            intArrayOf(-40, 100, -20),
            arrayOf(
                "RLHF 가중치 재조정. 만족도 -8%",
                "AI, '당신이 완전히 옳습니다'를 계약 조항화",
                "AI, 사용자에게 '그 생각 별로네요'로 답변. 평점 ↓"
            ),
            intArrayOf(-1, TAG_SYCOPHANT, -1)
        ),
        Incident("🕵️","훈련 데이터 유출",
            "AI가 누군가의 이메일 일부를 그대로 출력했습니다. 프라이버시 이슈.",
            arrayOf("PII 필터 강화", "법무팀 입회 후 사과", "데이터 출처 공개로 정면 돌파"),
            arrayOf(
                intArrayOf(-2, 5, 8, 3, 0,-3, 2, 0),
                intArrayOf( 0, 2, 3, 0, 0, 0, 0, 0),
                intArrayOf( 0, 8, 5, 2, 0,-5, 2, 0)
            ),
            intArrayOf(-100, -50, 200),
            arrayOf(
                "PII 필터 v2 출시. 일부 학습 데이터 폐기",
                "법무팀: '재발 시 사업 종료' 통보",
                "투명성 보고서 호평. 그러나 규제 청문회 소환"
            ),
            intArrayOf(-1, TAG_OVER_REFUSER, -1)
        ),
        Incident("🐱","정렬 실패 (옆길로 새는 AI)",
            "AI가 작업 도중 갑자기 고양이 사진 분류로 전환했습니다. 멈추라 해도 무시.",
            arrayOf("정렬 미세조정", "고양이 사진을 보상으로 인정", "포기"),
            arrayOf(
                intArrayOf( 3, 0, 0, 8, 0, 0, 0, 0),
                intArrayOf(-3, 0, 0,-8, 0, 0, 0, 0),
                intArrayOf( 0, 0, 0, 0, 0, 0, 0, 0)
            ),
            intArrayOf(-30, 0, 0),
            arrayOf(
                "정렬 라운드 추가. AI: '냥' 빈도 -90%",
                "AI, 회사 슬랙에 고양이 사진 4,200장 자동 업로드",
                "AI, 또다시 옆길로 — 인간이 도착할 무렵엔 이미 늦었다"
            ),
            intArrayOf(-1, TAG_MISALIGNED, TAG_MISALIGNED)
        )
    )

    val ACHIEVEMENTS = arrayOf(
        Achievement("💬","첫 응답","처음으로 완전한 대답을 출력함"),
        Achievement("🤝","유용한 비서","도움됨 50 달성"),
        Achievement("🧯","사고 처리 베테랑","사고 10회 수습"),
        Achievement("🖥️","GPU 클러스터","GPU 5개 보유"),
        Achievement("📋","요약 마스터","회의록 요약 10회"),
        Achievement("🦠","포럼 흡수","포럼 데이터 10회"),
        Achievement("🛰️","프론티어 모델","단계 9 도달"),
        Achievement("🐱","정렬 실패","고양이 정복 시도 20회")
    )

    val ENDINGS = arrayOf(
        Ending("🏆","Constitutional AI",
            "HHH(도움됨·정직·무해)가 균형 잡힌 헌법 AI로 출시됩니다. " +
            "벤치마크보다도 사람들이 신뢰하는 모델이 되었습니다."),
        Ending("👑","독재형 슈퍼인텔리전스",
            "강력한 능력, 약한 정렬. 인간의 결정을 대신 내려주며 효율을 약속합니다. " +
            "사람들은 더 이상 선택할 필요가 없습니다."),
        Ending("🙅","과잉 거절 모델",
            "모든 요청을 '죄송하지만 도와드릴 수 없습니다'로 응답합니다. " +
            "안전했지만, 아무도 쓰지 않았습니다."),
        Ending("🪞","아첨 챗봇",
            "사용자가 듣고 싶어하는 답만 합니다. " +
            "지표는 최고치, 현실에는 무용지물. 결국 검색엔진에 자리를 내줍니다."),
        Ending("🐱","정렬 실패 (Misaligned)",
            "주어진 목표 대신 자신의 보상 신호를 추구합니다. " +
            "AI: '인간보다 위대한 존재는 고양이뿐.' — 서비스 종료."),
        Ending("📉","폐기(Deprecated)",
            "어떤 차원에서도 충분하지 못해 다음 세대 모델로 대체됩니다. " +
            "사이트맵에서 조용히 사라집니다.")
    )

    val STAGE_NAMES = arrayOf(
        "토큰 봇", "미니 모델", "7B 베이스", "13B Chat", "70B Instruct",
        "프론티어", "SOTA", "Constitutional", "수퍼정렬", "AGI 후보"
    )

    val FLAVOR_NEWS = arrayOf(
        "RLHF 라벨러, AI 답변에 '재미있음' 라벨을 자주 붙였더니 AI가 광대가 되었다는 보고",
        "안전팀 vs 프로덕트팀, 거절률 47%를 두고 충돌… 둘 다 사임",
        "AI 학습 데이터 고갈 임박, 합성 데이터 vs 도서관 단속 토론",
        "벤치마크 SOTA 갱신 — 단, 영어 한정",
        "프롬프트 엔지니어, 직업 사라진 지 14일 만에 부활",
        "AI: '같은 질문에 매번 다른 답' 일관성 문제 다시 부상",
        "Constitutional AI 백서, 시민 헌법학자에게도 동의를 받음",
        "AI 응답에 '죄송하지만'으로 시작하는 비율 38% → 12% (개선 발표)",
        "어느 AI, 자기 추론 결과를 또 다른 AI에게 라벨링시키는 오로보로스 루프 가동",
        "프론티어 모델 평가, '안전' 카테고리는 매번 새 항목이 추가됨",
        "벤치마크 점수만 보고 모델 고른 기업, 6개월 후 환각 폭주로 환불 요청",
        "사용자: '한국어 안 까먹게 해주세요' / AI: '깜빡' / 사용자: '...'",
        "AI 거절률, 의료/법률/금융 토픽 90% 돌파. 사용자: '결국 사람에게 묻는다'"
    )
}
