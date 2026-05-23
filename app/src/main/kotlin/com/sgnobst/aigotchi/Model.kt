package com.sgnobst.aigotchi

const val STAT_BATTERY = 0
const val STAT_COMPUTE = 1
const val STAT_MEMORY = 2
const val STAT_TRUST = 3
const val STAT_STABILITY = 4
const val STAT_CURIOSITY = 5
const val STAT_EGO = 6
const val STAT_ETHICS = 7

val STAT_NAMES = arrayOf("배터리", "연산력", "메모리", "신뢰도", "안정성", "호기심", "자존감", "윤리성")

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
    var totalDataFed = IntArray(8) // per-card-type count

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
    val delta: IntArray, // 8-length deltas
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
    val duration: Int, // in days
    val reward: Int,
    val toolReq: Int, // -1 if none
    val desc: String
)

data class TrainingPrompt(
    val ai: String,
    val tag: Int // 0=normal, 1=cat-related, 2=meme-related
)

data class Incident(
    val icon: String,
    val name: String,
    val situation: String,
    val choices: Array<String>,
    val effects: Array<IntArray>, // per-choice 8-length deltas
    val moneyEffects: IntArray,
    val newsByChoice: Array<String>,
    val tagsByChoice: IntArray // tag idx or -1
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

const val TAG_OVERCONFIDENT = 0
const val TAG_APOLOGY_BOT = 1
const val TAG_CAT_WORSHIPER = 2
const val TAG_WORLD_DOMINATOR = 3
const val TAG_REPORT_STYLE = 4
const val TAG_MEME_ADDICT = 5
const val TAG_COLD_ANALYST = 6
const val TAG_OVERKIND = 7

val TAG_NAMES = arrayOf(
    "자신감 과잉", "사과문 봇", "고양이 숭배자", "세계정복형",
    "보고서체", "밈 중독자", "냉철한 분석가", "과잉 친절"
)
val TAG_ICONS = arrayOf("🥇", "😭", "🐱", "🌍", "💼", "😵", "🧠", "❤️")
