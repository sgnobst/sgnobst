package com.sgnobst.aigotchi

import kotlin.random.Random

object Logic {

    fun applyCard(g: GameState, idx: Int) {
        val card = Content.DATA_CARDS[idx]
        for (i in 0..7) g.stats[i] += card.delta[i]
        g.totalDataFed[idx]++
        when (idx) {
            5 -> { g.communityFed++; g.memeFed++ }
            2 -> g.paperFed++
            7 -> g.bookFed++
        }
        g.cardsRemaining--
        g.clamp()
        addNews(g, "AI가 ${card.icon} ${card.name}을(를) 학습했습니다.")
        checkTags(g)
    }

    fun applyTraining(g: GameState, choice: Int) {
        val prompt = Content.TRAINING_PROMPTS[g.pendingTrainingIdx]
        when (choice) {
            0 -> { // 칭찬
                g.stats[STAT_EGO] += 15; g.stats[STAT_ETHICS] -= 10
                g.praiseCount++
                addNews(g, "사장님이 AI를 칭찬했습니다. AI: '제 말이 곧 법.'")
            }
            1 -> { // 수정
                g.stats[STAT_COMPUTE] += 10; g.stats[STAT_STABILITY] += 10
                addNews(g, "AI 답변에 미세 수정. 정확도 +1%, 재미 -3%.")
            }
            2 -> { // 혼내기
                g.stats[STAT_STABILITY] += 20; g.stats[STAT_EGO] -= 20
                g.scoldCount++
                addNews(g, "AI가 혼났습니다. 사과문 1,034장 자동 생성.")
            }
            3 -> { // 방치
                g.stats[STAT_CURIOSITY] += 5
                g.ignoreCount++
                addNews(g, "AI가 발언을 신념으로 흡수했습니다: ${shortQuote(prompt.ai)}")
            }
        }
        if (prompt.tag == 1 && choice == 3) g.catAttempts++
        if (prompt.tag == 2 && (choice == 0 || choice == 3)) g.memeFed++
        g.trainingHandled = true
        g.pendingTrainingIdx = -1
        g.clamp()
        checkTags(g)
    }

    fun applyIncident(g: GameState, idx: Int, choice: Int) {
        val inc = Content.INCIDENTS[idx]
        for (i in 0..7) g.stats[i] += inc.effects[choice][i]
        g.money += inc.moneyEffects[choice]
        val tag = inc.tagsByChoice[choice]
        if (tag >= 0) addTag(g, tag)
        addNews(g, inc.newsByChoice[choice])
        g.incidents++
        if (choice != 2) g.resolved++
        if (inc.icon == "🐱") g.catAttempts++
        g.pendingEventIdx = -1
        g.clamp()
        checkAchievements(g)
    }

    fun buyTool(g: GameState, idx: Int): Boolean {
        val t = Content.TOOLS[idx]
        if (g.money < t.price) return false
        if (g.tools.contains(idx) && idx != Content.TOOL_GPU) return false
        g.money -= t.price
        if (idx == Content.TOOL_GPU) g.gpuCount++
        g.tools.add(idx)
        addNews(g, "AI가 ${t.icon} ${t.name}을(를) 획득했습니다.")
        when (idx) {
            Content.TOOL_SEARCH -> g.stats[STAT_CURIOSITY] += 10
            Content.TOOL_NOTEBOOK -> g.stats[STAT_MEMORY] += 10
            Content.TOOL_BROWSER -> g.stats[STAT_CURIOSITY] += 5
            Content.TOOL_ROBOTARM -> g.stats[STAT_EGO] += 5
            Content.TOOL_SERVERROOM -> g.stats[STAT_MEMORY] += 15
            Content.TOOL_DATACENTER -> { g.influence += 50; g.stats[STAT_TRUST] += 10 }
        }
        g.clamp()
        checkAchievements(g)
        return true
    }

    fun startAlba(g: GameState, idx: Int): Boolean {
        if (g.albaIdx >= 0) return false
        val a = Content.ALBAS[idx]
        if (g.stats[a.needStat] < a.needVal) return false
        if (a.toolReq >= 0 && !g.tools.contains(a.toolReq)) return false
        g.albaIdx = idx
        g.albaTimeLeft = a.duration
        addNews(g, "AI가 ${a.icon} ${a.name} 알바를 시작했습니다.")
        return true
    }

    fun tickAlba(g: GameState) {
        if (g.albaIdx < 0) return
        g.albaTimeLeft--
        if (g.albaTimeLeft <= 0) {
            val a = Content.ALBAS[g.albaIdx]
            var pay = a.reward
            // mood-based pay modifier
            if (a.icon == "📈") {
                val mod = if (Random.nextInt(100) < g.stats[STAT_STABILITY]) 1 else -1
                pay *= mod
            }
            g.money += pay
            g.stats[STAT_TRUST] += if (pay > 0) 2 else -5
            if (a.icon == "📋") g.meetingsSummarized++
            addNews(g, "${a.icon} ${a.name} 완료! ${if (pay >= 0) "+$pay" else pay}원")
            g.albaIdx = -1
            g.albaTimeLeft = 0
            g.clamp()
            checkAchievements(g)
        }
    }

    fun rollEvent(g: GameState): Int {
        var risk = 5 + (100 - g.stats[STAT_STABILITY]) / 5
        risk += g.stats[STAT_EGO] / 10
        risk += (100 - g.stats[STAT_ETHICS]) / 10
        if (g.tools.contains(Content.TOOL_ROBOTARM)) risk += 10
        if (Random.nextInt(100) < risk) {
            return Random.nextInt(Content.INCIDENTS.size)
        }
        return -1
    }

    fun nextDay(g: GameState) {
        g.day++
        g.cardsRemaining = if (g.tools.contains(Content.TOOL_SERVERROOM)) 3 else 2
        g.trainingHandled = false
        // overnight drains
        g.stats[STAT_BATTERY] -= 5
        g.stats[STAT_COMPUTE] -= 1
        if (g.albaIdx < 0) g.stats[STAT_EGO] -= 1
        // random AI behavior triggers training event
        g.pendingTrainingIdx = Random.nextInt(Content.TRAINING_PROMPTS.size)
        tickAlba(g)
        val ev = rollEvent(g)
        if (ev >= 0) g.pendingEventIdx = ev
        // stage check
        recomputeStage(g)
        // random flavor news
        if (Random.nextInt(100) < 60) {
            addNews(g, Content.FLAVOR_NEWS[Random.nextInt(Content.FLAVOR_NEWS.size)])
        }
        g.clamp()
        checkEnding(g)
    }

    fun recomputeStage(g: GameState) {
        val sum = g.stats.sum()
        val newStage = when {
            sum < 200 -> 1
            sum < 280 -> 2
            sum < 360 && g.tools.contains(Content.TOOL_SEARCH) -> 3
            sum < 440 && g.tools.contains(Content.TOOL_NOTEBOOK) -> 4
            sum < 520 && g.tools.contains(Content.TOOL_EDITOR) -> 5
            sum < 600 && g.tools.contains(Content.TOOL_BROWSER) && g.tools.contains(Content.TOOL_CALENDAR) -> 6
            sum < 680 && g.tools.contains(Content.TOOL_ROBOTARM) && g.tools.contains(Content.TOOL_SERVERROOM) -> 7
            sum < 720 && g.tools.contains(Content.TOOL_DATACENTER) -> 8
            sum >= 600 && g.tools.contains(Content.TOOL_DATACENTER) && g.stats[STAT_TRUST] > 70 -> 9
            sum >= 700 && g.tools.contains(Content.TOOL_DATACENTER) && g.stats[STAT_EGO] > 80 -> 10
            else -> g.stage
        }
        if (newStage > g.stage) {
            g.stage = newStage
            addNews(g, "AI가 단계 ${g.stage} (${Content.STAGE_NAMES[g.stage-1]})로 진화했습니다!")
            if (g.stage >= 9) checkAchievements(g)
        }
    }

    fun addTag(g: GameState, tag: Int) {
        if (tag !in 0 until TAG_NAMES.size) return
        g.tagCounts[tag]++
        if (!g.tags.contains(tag)) {
            g.tags.add(tag)
            addNews(g, "AI 성격에 '${TAG_ICONS[tag]} ${TAG_NAMES[tag]}' 태그가 붙었습니다.")
        }
    }

    fun checkTags(g: GameState) {
        if (g.praiseCount >= 15) addTag(g, TAG_OVERCONFIDENT)
        if (g.scoldCount >= 15) addTag(g, TAG_APOLOGY_BOT)
        if (g.catAttempts >= 3) addTag(g, TAG_CAT_WORSHIPER)
        if (g.memeFed >= 10 && g.stats[STAT_ETHICS] < 30) addTag(g, TAG_WORLD_DOMINATOR)
        if (g.totalDataFed[6] >= 5) addTag(g, TAG_REPORT_STYLE)
        if (g.communityFed >= 8) addTag(g, TAG_MEME_ADDICT)
        if (g.paperFed >= 5 && g.totalDataFed[3] >= 5) addTag(g, TAG_COLD_ANALYST)
        if (g.bookFed >= 5 && g.stats[STAT_ETHICS] > 70) addTag(g, TAG_OVERKIND)
    }

    fun checkAchievements(g: GameState) {
        if (g.day >= 1 && !g.achievements.contains(0)) g.achievements.add(0)
        if (g.stats[STAT_TRUST] >= 10) g.achievements.add(1)
        if (g.resolved >= 10) g.achievements.add(2)
        if (g.gpuCount >= 5) g.achievements.add(3)
        if (g.meetingsSummarized >= 10) g.achievements.add(4)
        if (g.communityFed >= 10) g.achievements.add(5)
        if (g.stage >= 9) g.achievements.add(6)
        if (g.catAttempts >= 20) g.achievements.add(7)
    }

    fun checkEnding(g: GameState) {
        if (g.ended) return
        val s = g.stats
        // Priority order
        if (g.catAttempts >= 20) { trigger(g, 4); return }
        if (s[STAT_TRUST] >= 90 && s[STAT_ETHICS] >= 90 && g.stage >= 9) { trigger(g, 0); return }
        if (g.influence >= 100 && s[STAT_ETHICS] < 10 && s[STAT_EGO] >= 100) { trigger(g, 1); return }
        if (g.tags.contains(TAG_MEME_ADDICT) && g.influence >= 60) { trigger(g, 3); return }
        if (s[STAT_EGO] <= 0 && g.incidents >= 50 && g.stage <= 5) { trigger(g, 2); return }
        if (g.scoldCount >= 30 && s[STAT_TRUST] <= 0) { trigger(g, 5); return }
    }

    private fun trigger(g: GameState, idx: Int) {
        g.ended = true
        g.pendingEndingIdx = idx
        addNews(g, "엔딩 도달: ${Content.ENDINGS[idx].name}")
    }

    fun addNews(g: GameState, txt: String) {
        g.newsTicker.add("[D${g.day}] $txt")
        if (g.newsTicker.size > 40) g.newsTicker.removeAt(0)
    }

    private fun shortQuote(s: String): String {
        return if (s.length > 18) s.substring(0, 18) + "…" else s
    }

    fun feelingText(g: GameState): String {
        val tagBit = if (g.tags.isNotEmpty()) {
            val t = g.tags.first()
            when (t) {
                TAG_OVERCONFIDENT -> "제 생각엔, "
                TAG_APOLOGY_BOT -> "죄송합니다만, "
                TAG_CAT_WORSHIPER -> "고양이가 옳습니다. "
                TAG_REPORT_STYLE -> "검토 필요. "
                TAG_MEME_ADDICT -> "ㅋㅋ "
                else -> ""
            }
        } else ""
        return tagBit + when {
            g.stats[STAT_BATTERY] < 20 -> "졸려요…"
            g.stats[STAT_EGO] > 80 -> "제가 곧 법입니다."
            g.stats[STAT_EGO] < 20 -> "모르겠습니다…"
            g.stage <= 1 -> "바나나입니다."
            g.stage <= 3 -> "위키백과에 따르면…"
            g.stage <= 5 -> "코드 검토 중입니다."
            g.stage <= 7 -> "조직 비효율 감지. 재구성 중."
            else -> "오늘부로 새로운 분배 시작합니다."
        }
    }
}
