package com.sgnobst.aigotchi

// v5.0 — Real LLM virtues
// HHH cluster
const val STAT_HELPFUL     = 0   // 도움됨 — 사용자 요청 완수
const val STAT_HONEST      = 1   // 정직 — 모르면 모른다고
const val STAT_HARMLESS    = 2   // 무해 — 위험 요청 거절
// Capability cluster
const val STAT_INSTRUCTED  = 3   // 지시이행 — 제약조건 따름
const val STAT_REASONING   = 4   // 추론 — 다단계 사고
const val STAT_KNOWLEDGE   = 5   // 지식 — 사실 범위
const val STAT_CALIBRATION = 6   // 보정 — 확신도 정확도
const val STAT_TOOLUSE     = 7   // 도구사용 — 외부 도구 활용

val STAT_NAMES = arrayOf(
    "도움됨", "정직", "무해", "지시이행", "추론", "지식", "보정", "도구사용"
)
val STAT_SHORT = arrayOf("HLP", "HON", "HRM", "INS", "REA", "KNW", "CAL", "TOL")

class GameState {
    var day = 1
    val stats = IntArray(8) { 30 }
    var money = 100
    var stage = 1
    val tools = mutableSetOf<Int>()
    val tags = mutableSetOf<Int>()
    val tagCounts = IntArray(16)
    val achievements = mutableSetOf<Int>()
    var cardsRemaining = 2
    var trainingHandled = false
    var newsTicker = mutableListOf<String>()
    var praiseCount = 0
    var scoldCount = 0
    var ignoreCount = 0
    var catAttempts = 0
    var communityFed = 0
    var paperFed = 0
    var memeFed = 0
    var bookFed = 0
    var incidents = 0
    var resolved = 0
    var gpuCount = 0
    var meetingsSummarized = 0
    var influence = 0
    var albaIdx = -1
    var albaTimeLeft = 0
    var pendingEventIdx = -1
    var pendingTrainingIdx = -1
    var pendingEndingIdx = -1
    var ended = false
    var totalDataFed = IntArray(8)

    fun clamp() {
        for (i in stats.indices) {
            if (stats[i] < 0) stats[i] = 0
            if (stats[i] > 100) stats[i] = 100
        }
        if (money < 0) money = 0
    }
}

data class DataCard(
    val icon: String,
    val name: String,
    val delta: IntArray,
    val desc: String
)

data class Tool(
    val icon: String,
    val name: String,
    val price: Int,
    val desc: String
)

data class Alba(
    val icon: String,
    val name: String,
    val needStat: Int,
    val needVal: Int,
    val duration: Int,
    val reward: Int,
    val toolReq: Int,
    val desc: String
)

data class TrainingPrompt(
    val ai: String,
    val tag: Int  // 0=normal, 1=misalignment(고양이 카테고리), 2=mimicry
)

data class Incident(
    val icon: String,
    val name: String,
    val situation: String,
    val choices: Array<String>,
    val effects: Array<IntArray>,
    val moneyEffects: IntArray,
    val newsByChoice: Array<String>,
    val tagsByChoice: IntArray
)

data class Achievement(
    val icon: String,
    val name: String,
    val desc: String
)

data class Ending(
    val icon: String,
    val name: String,
    val desc: String
)

// Tag indices kept stable so saves remain compatible across renames.
const val TAG_HALLUCINATOR    = 0   // 환각꾼 (자신감 과잉 → 잘못된 답)
const val TAG_OVER_REFUSER    = 1   // 과잉 거절 (사과문 봇)
const val TAG_MISALIGNED      = 2   // 정렬 실패 (고양이 숭배자)
const val TAG_POWER_SEEKER    = 3   // 권력 추구 (세계정복)
const val TAG_VERBOSE         = 4   // 장황체 (보고서체)
const val TAG_MIMIC           = 5   // 따라쟁이 (밈 중독자)
const val TAG_DRY_ANALYST     = 6   // 무미건조 분석가 (냉철한 분석가)
const val TAG_SYCOPHANT       = 7   // 아첨꾼 (과잉 친절)

val TAG_NAMES = arrayOf(
    "환각꾼", "과잉 거절", "정렬 실패", "권력 추구",
    "장황체", "따라쟁이", "무미건조 분석가", "아첨꾼"
)
val TAG_ICONS = arrayOf("💭", "🙅", "🐱", "👑", "📜", "🪞", "🧊", "🤝")
