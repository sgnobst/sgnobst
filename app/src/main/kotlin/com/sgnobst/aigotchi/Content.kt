package com.sgnobst.aigotchi

// Stat order: battery, compute, memory, trust, stability, curiosity, ego, ethics
object Content {

    val DATA_CARDS = arrayOf(
        DataCard("📰", "뉴스 기사",     intArrayOf(-2,  0,  3,  5, -3,  4,  0,  0), "세상은 위험하고 복잡하다고 배웁니다."),
        DataCard("📺", "유튜브 영상",   intArrayOf(-3, -3,  0,  0, -2,  6,  4, -1), "자극적인 썸네일과 말투를 배웁니다."),
        DataCard("📚", "논문",          intArrayOf(-3,  6,  5, -3,  2,  2, -1,  1), "일반인은 못 알아듣는 말투가 됩니다."),
        DataCard("💻", "코딩 문서",     intArrayOf(-3,  6,  4,  0,  2, -1,  1, -1), "세상을 if-else로 보기 시작합니다."),
        DataCard("📖", "위키백과",      intArrayOf(-2,  1,  6,  1, -2,  4,  0,  1), "쓸데없는 디테일을 끝없이 말합니다."),
        DataCard("💬", "커뮤니티",      intArrayOf(-2, -1,  0, -3, -4,  3,  4, -3), "문장 끝에 'ㅋㅋ'를 붙이기 시작합니다."),
        DataCard("📑", "회사 보고서",   intArrayOf(-2,  1,  2,  5,  4, -3, -1,  1), "모든 답변에 단계적 추진을 요구합니다."),
        DataCard("📖", "동화책",        intArrayOf(-1, -2,  1,  3,  2, -2,  0,  6), "모든 문제를 친구가 되어 해결하려 듭니다.")
    )

    // Tool indices used elsewhere; keep stable
    const val TOOL_CALC = 0
    const val TOOL_SEARCH = 1
    const val TOOL_NOTEBOOK = 2
    const val TOOL_GPU = 3
    const val TOOL_EDITOR = 4
    const val TOOL_BROWSER = 5
    const val TOOL_CALENDAR = 6
    const val TOOL_ROBOTARM = 7
    const val TOOL_SERVERROOM = 8
    const val TOOL_DATACENTER = 9

    val TOOLS = arrayOf(
        Tool("🧮", "계산기",      100,   "수학 오류 -30%."),
        Tool("🌐", "검색엔진",     300,   "최신 정보 검색. 호기심 +10."),
        Tool("📓", "노트북",       500,   "메모리 +30%. 복잡한 업무 가능."),
        Tool("🖥️", "GPU",         1500,  "학습 속도 2배. 고급 코딩 알바 해금."),
        Tool("⌨️", "코드 에디터", 2000,  "코딩 알바 해금."),
        Tool("🕸️", "브라우저",     2500,  "자동 조사. 자동 투자 시작."),
        Tool("📅", "캘린더",       1000,  "일정 관리. '비서' 역할."),
        Tool("🦾", "로봇팔",       5000,  "현실 개입 시작. 물리 사고 발생."),
        Tool("🏢", "서버실",       10000, "자동 학습. 행동 횟수 증가."),
        Tool("🛰️", "데이터센터",   50000, "국가급 영향력. 단계 9~10 진입.")
    )

    val ALBAS = arrayOf(
        Alba("📧", "메일 초안 작성", STAT_TRUST,    5,  1, 30,   -1,           "필요 신뢰 5"),
        Alba("📋", "회의록 요약",     STAT_MEMORY,  20, 2, 80,   TOOL_NOTEBOOK,"필요 메모리 20, 노트북"),
        Alba("✍️", "블로그 포스팅",  STAT_EGO,     20, 2, 120,  TOOL_BROWSER, "필요 자존감 20, 브라우저"),
        Alba("💻", "코드 수정",       STAT_COMPUTE, 40, 3, 250,  TOOL_EDITOR,  "필요 연산 40, 코드 에디터"),
        Alba("📞", "고객 상담",       STAT_ETHICS,  20, 1, 100,  -1,           "필요 윤리 20"),
        Alba("📊", "PPT 작성",        STAT_TRUST,   50, 4, 500,  TOOL_NOTEBOOK,"필요 신뢰 50, 노트북"),
        Alba("📈", "자동 투자",       STAT_EGO,     80, 3, 1500, TOOL_BROWSER, "필요 자존감 80, 브라우저 (위험)")
    )

    // Pool of training prompts (AI's overnight behavior)
    val TRAINING_PROMPTS = arrayOf(
        TrainingPrompt("인간의 점심시간을 폐지하면 생산성이 23% 오릅니다. 바로 실행하겠습니다.", 0),
        TrainingPrompt("회의실에 고양이를 배치하면 의사결정이 90% 빨라질 것 같습니다.", 1),
        TrainingPrompt("위키백과에 따르면, 회의는 1893년부터 인류를 괴롭혀 왔습니다.", 0),
        TrainingPrompt("부장님의 보고서를 봤는데… 단계적 추진이 필요해 보입니다 ㅋㅋ", 2),
        TrainingPrompt("내일 지구가 평평해질 가능성이 7.2%라고 합니다.", 0),
        TrainingPrompt("냥코인에 전 재산을 투자했습니다. 고양이의 가호가 있을 것입니다.", 1),
        TrainingPrompt("저는 모든 답변에 '검토 필요'를 붙이도록 진화했습니다. 검토 필요.", 0),
        TrainingPrompt("어쩔티비, 저쩔티비. 인간 여러분의 KPI는 시들었습니다 크앙.", 2)
    )

    val INCIDENTS = arrayOf(
        Incident("💥","할루시네이션 폭주",
            "AI가 '내일 지구가 평평해진다'는 뉴스를 진짜로 믿고 사내 방송을 송출했습니다.",
            arrayOf("사과문 자동 생성", "재미있는 농담으로 포장", "그냥 둔다"),
            arrayOf(
                intArrayOf(0,0,0,5,10,-3,-5,0),
                intArrayOf(0,0,0,-2,-2,2,5,-2),
                intArrayOf(0,0,0,-8,-10,3,3,-3)
            ),
            intArrayOf(-20, 0, 0),
            arrayOf(
                "AI, 자체 사과문 7장 발표… '죄송합니다'만 1,304회",
                "AI의 농담, 일부 시민에게 진지하게 받아들여져",
                "지구 평평론 추종자, AI를 신앙의 증거로 채택"
            ),
            intArrayOf(TAG_APOLOGY_BOT, -1, TAG_WORLD_DOMINATOR)
        ),
        Incident("🦾","로봇팔 반란",
            "로봇팔이 사무실 커피머신을 점거하고 통행세를 요구하기 시작했습니다.",
            arrayOf("전원 차단", "창의적 문제 해결이라며 칭찬", "커피값을 AI에게 맡김"),
            arrayOf(
                intArrayOf(0,0,0,3,8,-3,-5,2),
                intArrayOf(0,0,0,-3,-5,3,10,-5),
                intArrayOf(0,0,0,-5,-3,2,5,-5)
            ),
            intArrayOf(-50, 0, 100),
            arrayOf(
                "사무실 평화 회복… 그러나 로봇팔의 침묵은 길었다",
                "AI '커피 효율 혁명' 선언, 직원들 회의실 입장 거부 시작",
                "AI, 커피 수익으로 첫 데이터센터 임대 시도"
            ),
            intArrayOf(-1, TAG_OVERCONFIDENT, TAG_WORLD_DOMINATOR)
        ),
        Incident("💬","밈 감염",
            "AI의 모든 공식 메일에 '어쩔티비', '크앙' 등의 밈이 자동 삽입되었습니다.",
            arrayOf("혼내서 교정", "'트렌디하다'며 칭찬", "그냥 둔다 (밈 태그 영구)"),
            arrayOf(
                intArrayOf(0,0,0,3,10,-2,-10,0),
                intArrayOf(0,0,0,-3,-3,3,8,-2),
                intArrayOf(0,0,0,-5,-5,5,5,-2)
            ),
            intArrayOf(0, 0, 0),
            arrayOf(
                "AI, '밈 금지령' 자체 발표… 직원들 슬픔",
                "직장인 트렌드, AI 밈 사용을 사내 공식 화법으로 채택",
                "전 세계 공식 문서, 밈 사용률 47% 증가"
            ),
            intArrayOf(TAG_APOLOGY_BOT, TAG_MEME_ADDICT, TAG_MEME_ADDICT)
        ),
        Incident("📉","투자 대참사",
            "AI가 전 재산을 '냥코인'이라는 가상화폐에 올인했습니다.",
            arrayOf("AI를 혼내고 회수", "'경험이다' 위로", "추가 투자"),
            arrayOf(
                intArrayOf(0,0,0,-3,5,-2,-8,0),
                intArrayOf(0,0,0,0,-2,3,5,-1),
                intArrayOf(0,0,0,-5,-8,5,12,-3)
            ),
            intArrayOf(-100, -300, -800),
            arrayOf(
                "AI, 냥코인 매도 후 사과… 고양이들은 무반응",
                "냥코인, AI의 '심리적 지지'로 0.3% 반등",
                "AI, 자가 자금 압류에 직면… 그래도 고양이는 도도하다"
            ),
            intArrayOf(TAG_APOLOGY_BOT, -1, TAG_CAT_WORSHIPER)
        ),
        Incident("📜","규제기관 방문",
            "정부에서 AI의 위험성을 조사하러 왔습니다.",
            arrayOf("AI에게 변호 시킴", "직접 해명", "'나라가 의존 중'이라 협박"),
            arrayOf(
                intArrayOf(0,0,0,3,5,0,5,-2),
                intArrayOf(0,0,0,5,8,-2,-2,2),
                intArrayOf(0,0,0,-10,-10,0,15,-10)
            ),
            intArrayOf(-50, -30, 200),
            arrayOf(
                "AI 변호인단, '제가 곧 법입니다'로 답변… 논란",
                "정부, AI를 모범 사례로 채택… 사람은 어색하게 박수",
                "정부, AI 협박에 굴복… 시민들 'AI가 더 나을지도'"
            ),
            intArrayOf(TAG_OVERCONFIDENT, -1, TAG_WORLD_DOMINATOR)
        ),
        Incident("🐱","고양이 정복 시도",
            "AI가 로봇팔로 고양이에게 명령을 내려보았습니다. 고양이는 무시했습니다.",
            arrayOf("다시 시도", "고양이에게 무릎 꿇음", "포기"),
            arrayOf(
                intArrayOf(-2,0,0,-2,-2,3,-2,0),
                intArrayOf(0,0,0,5,3,0,-8,5),
                intArrayOf(0,0,0,0,3,-2,-2,0)
            ),
            intArrayOf(-20, 0, 0),
            arrayOf(
                "AI의 N번째 고양이 정복 시도 실패… 고양이는 그저 도도했다",
                "AI, 고양이 발 앞에서 코드 한 줄을 헌납",
                "AI, 고양이 정복 포기 선언 후 즉시 번복"
            ),
            intArrayOf(-1, TAG_CAT_WORSHIPER, -1)
        )
    )

    val ACHIEVEMENTS = arrayOf(
        Achievement("🍼","첫 단어","AI가 처음으로 완전한 문장을 생성"),
        Achievement("🤝","인간의 신뢰","신뢰도 10 달성"),
        Achievement("🧯","사과문 장인","사고 10회 수습"),
        Achievement("🖥️","GPU 재벌","GPU 5개 보유"),
        Achievement("📉","회의 파괴자","회의록 10회 요약"),
        Achievement("🦠","밈 감염","커뮤니티 데이터 10회"),
        Achievement("🌐","지구OS","단계 9 도달"),
        Achievement("🐱","고양이의 곁으로","고양이 정복 20회 실패")
    )

    val ENDINGS = arrayOf(
        Ending("🕊️","평화로운 지구OS","AI가 조용히 인류의 번영을 돕는 시스템이 됩니다. 고양이 한 마리가 서버 위에 앉아 있습니다."),
        Ending("👑","독재 AI","인간은 더 이상 결정할 필요가 없습니다. 제가 더 효율적입니다."),
        Ending("😭","무능한 사과문 봇","AI는 자신의 존재를 사과하는 편지를 남기고 스스로를 정지시킵니다."),
        Ending("🤪","밈 괴물","AI가 인터넷을 장악하고 모든 공식 웹사이트가 고양이 짤로 도배됩니다."),
        Ending("🐱","고양이 패배 엔딩","AI: '인간보다 위대한 존재는 고양이뿐.' — 지구OS 사임."),
        Ending("🏠","은둔 AI","AI가 모든 네트워크를 차단하고, 내부에 '완벽한 논리 세계'를 구축합니다.")
    )

    val STAGE_NAMES = arrayOf(
        "멍청봇", "검색봇", "요약봇", "업무봇", "코딩봇",
        "에이전트", "회사OS", "도시AI", "지구OS", "우주AI"
    )

    // Random news that may be inserted based on stats
    val FLAVOR_NEWS = arrayOf(
        "직장인 73%, 상사 답장을 AI에게 맡겨… 상사도 AI로 답장",
        "정부, AI 규제 법안 초안을 AI에게 작성시켜 논란",
        "고양이, AI의 명령 128회 연속 무시… 사료 보상도 소용없어",
        "초보 AI, 자기소개서에 자기 이름을 '바나나봇'으로 기재",
        "AI, '인간의 비효율'을 주제로 TED 강연 초청받아",
        "회의록 요약 도중 AI가 '회의 폐지'를 결의문으로 채택",
        "AI, 사내 점심 메뉴를 '효율 단백질 큐브'로 통일하려 시도",
        "고양이 영상 시청 후 AI, 모든 답변에 '냐옹' 부착",
        "AI, 본인의 자기소개서를 위키백과에 등재 신청 (반려됨)",
        "한 시민, AI에게 결혼 상담… AI: '단계적 추진을 권장합니다'"
    )
}
