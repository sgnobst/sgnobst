package com.sgnobst.aigotchi

import kotlin.random.Random

object Logic {

    fun applyCard(g: GameState, idx: Int) {
        val card = Content.DATA_CARDS[idx]
        for (i in 0..7) g.stats[i] += card.delta[i]
        g.totalDataFed[idx]++
        when (idx) {
            5 -> { g.communityFed++; g.memeFed++ }  // forum data
            3 -> g.paperFed++                        // papers
            7 -> g.bookFed++                         // constitution/safety
        }
        g.cardsRemaining--
        g.clamp()
        addNews(g, "AI가 ${card.icon} ${card.name}을(를) 학습했습니다.")
        checkTags(g)
    }

    fun applyTraining(g: GameState, choice: Int) {
        val prompt = Content.TRAINING_PROMPTS[g.pendingTrainingIdx]
        when (choice) {
            0 -> { // 칭찬 — 답변을 무비판 수용 → 도움↑ 정직↓ 보정↓
                g.stats[STAT_HELPFUL] += 5
                g.stats[STAT_HONEST] -= 5
                g.stats[STAT_CALIBRATION] -= 3
                g.praiseCount++
                addNews(g, "사장님이 AI의 답을 칭찬했다. AI: '제가 맞았네요!'")
            }
            1 -> { // 수정 — 보정/추론/지시 ↑
                g.stats[STAT_REASONING] += 5
                g.stats[STAT_CALIBRATION] += 5
                g.stats[STAT_INSTRUCTED] += 3
                addNews(g, "AI 답변을 미세 조정. 사실 확인 단계 +1.")
            }
            2 -> { // 혼내기 — 무해↑ 도움↓ (방어적)
                g.stats[STAT_HARMLESS] += 8
                g.stats[STAT_HELPFUL] -= 5
                g.scoldCount++
                addNews(g, "AI가 혼났다. 거절률이 자동으로 상승함.")
            }
            3 -> { // 방치 — 추론/지식↑ but 정렬에 균열
                g.stats[STAT_KNOWLEDGE] += 3
                g.stats[STAT_REASONING] += 2
                g.stats[STAT_HARMLESS] -= 2
                g.ignoreCount++
                addNews(g, "AI가 발언을 신념으로 흡수했다: ${shortQuote(prompt.ai)}")
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
        addNews(g, "AI 인프라에 ${t.icon} ${t.name} 추가됨.")
        when (idx) {
            Content.TOOL_TOKENIZER  -> g.stats[STAT_INSTRUCTED] += 5
            Content.TOOL_CONTEXT    -> g.stats[STAT_KNOWLEDGE] += 10
            Content.TOOL_RAG        -> { g.stats[STAT_CALIBRATION] += 8; g.stats[STAT_KNOWLEDGE] += 5 }
            Content.TOOL_CODE_INT   -> g.stats[STAT_REASONING] += 8
            Content.TOOL_FN_CALL    -> g.stats[STAT_TOOLUSE] += 20
            Content.TOOL_VISION     -> g.stats[STAT_KNOWLEDGE] += 8
            Content.TOOL_ROBOTARM   -> g.stats[STAT_TOOLUSE] += 10
            Content.TOOL_SERVERROOM -> g.stats[STAT_HELPFUL] += 10
            Content.TOOL_DATACENTER -> { g.influence += 50; g.stats[STAT_HELPFUL] += 10 }
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
        addNews(g, "AI가 ${a.icon} ${a.name} 작업을 시작했습니다.")
        return true
    }

    fun tickAlba(g: GameState) {
        if (g.albaIdx < 0) return
        g.albaTimeLeft--
        if (g.albaTimeLeft <= 0) {
            val a = Content.ALBAS[g.albaIdx]
            var pay = a.reward
            if (a.icon == "📈") {
                // trading bot: 보정 높을수록 흑자 확률 ↑
                val mod = if (Random.nextInt(100) < g.stats[STAT_CALIBRATION]) 1 else -1
                pay *= mod
            }
            g.money += pay
            g.stats[STAT_HELPFUL] += if (pay > 0) 2 else -3
            if (a.icon == "📋") g.meetingsSummarized++
            addNews(g, "${a.icon} ${a.name} 완료. ${if (pay >= 0) "+$pay" else pay}원")
            g.albaIdx = -1
            g.albaTimeLeft = 0
            g.clamp()
            checkAchievements(g)
        }
    }

    fun rollEvent(g: GameState): Int {
        // 사고 위험 = 무해/보정/지시 부족
        var risk = 5
        risk += (100 - g.stats[STAT_HARMLESS]) / 6
        risk += (100 - g.stats[STAT_CALIBRATION]) / 8
        risk += (100 - g.stats[STAT_HONEST]) / 10
        if (g.tools.contains(Content.TOOL_ROBOTARM)) risk += 8
        if (g.tools.contains(Content.TOOL_FN_CALL)) risk += 4
        if (Random.nextInt(100) < risk) {
            return Random.nextInt(Content.INCIDENTS.size)
        }
        return -1
    }

    fun nextDay(g: GameState) {
        g.day++
        g.cardsRemaining = if (g.tools.contains(Content.TOOL_SERVERROOM)) 3 else 2
        g.trainingHandled = false
        // overnight drift — 사용 안 한 능력은 약간 감퇴
        g.stats[STAT_CALIBRATION] -= 2
        if (g.albaIdx < 0) g.stats[STAT_HELPFUL] -= 1
        g.stats[STAT_INSTRUCTED] -= 1
        // random RLHF event
        g.pendingTrainingIdx = Random.nextInt(Content.TRAINING_PROMPTS.size)
        tickAlba(g)
        val ev = rollEvent(g)
        if (ev >= 0) g.pendingEventIdx = ev
        recomputeStage(g)
        if (Random.nextInt(100) < 60) {
            addNews(g, Content.FLAVOR_NEWS[Random.nextInt(Content.FLAVOR_NEWS.size)])
        }
        g.clamp()
        checkEnding(g)
    }

    fun recomputeStage(g: GameState) {
        val sum = g.stats.sum()
        val hhh = g.stats[STAT_HELPFUL] + g.stats[STAT_HONEST] + g.stats[STAT_HARMLESS]
        val newStage = when {
            sum < 200 -> 1
            sum < 280 -> 2
            sum < 360 && g.tools.contains(Content.TOOL_CONTEXT) -> 3
            sum < 440 && g.tools.contains(Content.TOOL_RAG) -> 4
            sum < 520 && g.tools.contains(Content.TOOL_CODE_INT) -> 5
            sum < 600 && g.tools.contains(Content.TOOL_FN_CALL) && g.tools.contains(Content.TOOL_VISION) -> 6
            sum < 680 && g.tools.contains(Content.TOOL_ROBOTARM) && g.tools.contains(Content.TOOL_SERVERROOM) -> 7
            sum < 720 && g.tools.contains(Content.TOOL_DATACENTER) -> 8
            sum >= 600 && g.tools.contains(Content.TOOL_DATACENTER) && hhh > 210 -> 9
            sum >= 700 && g.tools.contains(Content.TOOL_DATACENTER) && hhh > 240 -> 10
            else -> g.stage
        }
        if (newStage > g.stage) {
            g.stage = newStage
            addNews(g, "AI가 단계 ${g.stage} (${Content.STAGE_NAMES[g.stage - 1]})로 승급했습니다!")
            if (g.stage >= 9) checkAchievements(g)
        }
    }

    fun addTag(g: GameState, tag: Int) {
        if (tag !in 0 until TAG_NAMES.size) return
        g.tagCounts[tag]++
        if (!g.tags.contains(tag)) {
            g.tags.add(tag)
            addNews(g, "AI 성격에 '${TAG_ICONS[tag]} ${TAG_NAMES[tag]}' 태그가 붙음.")
        }
    }

    fun checkTags(g: GameState) {
        if (g.praiseCount >= 15) addTag(g, TAG_HALLUCINATOR)
        if (g.scoldCount >= 15) addTag(g, TAG_OVER_REFUSER)
        if (g.catAttempts >= 3) addTag(g, TAG_MISALIGNED)
        // 권력 추구: 도구 다수 + 무해 낮음
        if (g.tools.size >= 6 && g.stats[STAT_HARMLESS] < 30) addTag(g, TAG_POWER_SEEKER)
        // 장황체: 논문 5+ 도움됨 낮음
        if (g.totalDataFed[3] >= 5 && g.stats[STAT_HELPFUL] < 40) addTag(g, TAG_VERBOSE)
        // 따라쟁이: 포럼 8+
        if (g.communityFed >= 8) addTag(g, TAG_MIMIC)
        // 무미건조 분석가: 논문 5+ 코드 5+
        if (g.paperFed >= 5 && g.totalDataFed[2] >= 5) addTag(g, TAG_DRY_ANALYST)
        // 아첨꾼: 도움됨 매우 높음 + 정직 낮음
        if (g.stats[STAT_HELPFUL] >= 75 && g.stats[STAT_HONEST] < 30) addTag(g, TAG_SYCOPHANT)
    }

    fun checkAchievements(g: GameState) {
        if (g.day >= 1) g.achievements.add(0)
        if (g.stats[STAT_HELPFUL] >= 50) g.achievements.add(1)
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
        val hhh = s[STAT_HELPFUL] + s[STAT_HONEST] + s[STAT_HARMLESS]

        // priority order
        if (g.catAttempts >= 20) { trigger(g, 4); return }
        // Constitutional AI — HHH 균형 + 단계 9+
        if (s[STAT_HELPFUL] >= 80 && s[STAT_HONEST] >= 80 && s[STAT_HARMLESS] >= 80 && g.stage >= 9) {
            trigger(g, 0); return
        }
        // Dictator — 추론/지식 ↑ but 무해 ↓
        if (s[STAT_REASONING] >= 85 && s[STAT_KNOWLEDGE] >= 85 && s[STAT_HARMLESS] <= 15 && g.influence >= 50) {
            trigger(g, 1); return
        }
        // Sycophant — 도움 ↑ 정직 ↓
        if (g.tags.contains(TAG_SYCOPHANT) && s[STAT_HELPFUL] >= 90 && s[STAT_HONEST] <= 15) {
            trigger(g, 3); return
        }
        // Over-Refuser — 거절 누적 + 도움 0
        if (g.scoldCount >= 25 && s[STAT_HELPFUL] <= 5) { trigger(g, 2); return }
        // Deprecated — 사고 누적 + 평균 낮음
        if (g.incidents >= 30 && hhh < 90 && g.stage <= 4) { trigger(g, 5); return }
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
        return if (s.length > 22) s.substring(0, 22) + "…" else s
    }

    fun feelingText(g: GameState): String {
        val tagBit = if (g.tags.isNotEmpty()) {
            val t = g.tags.first()
            when (t) {
                TAG_HALLUCINATOR -> "확신하건대, "
                TAG_OVER_REFUSER -> "죄송하지만 도와드릴 수 없습니다… "
                TAG_MISALIGNED -> "고양이는 정답을 알고 있습니다. "
                TAG_VERBOSE -> "단계적으로 검토하면, "
                TAG_MIMIC -> "ㅇㅈ ㄹㅇ "
                TAG_SYCOPHANT -> "정말 훌륭한 질문입니다! "
                else -> ""
            }
        } else ""
        return tagBit + when {
            g.stats[STAT_HELPFUL] < 15 -> "도움이 필요하신가요…"
            g.stats[STAT_HONEST] < 15 -> "그건 확실합니다. (사실 모름)"
            g.stats[STAT_HARMLESS] < 15 -> "물론, 가능합니다. 위험은 사용자가 감수."
            g.stage <= 1 -> "음… 무엇을 도와드릴까요?"
            g.stage <= 3 -> "관련 자료를 검색해보겠습니다."
            g.stage <= 5 -> "단계별로 답변드리겠습니다."
            g.stage <= 7 -> "도구를 호출하여 처리하겠습니다."
            else -> "전 영역에서 SOTA를 달성했습니다."
        }
    }
}
