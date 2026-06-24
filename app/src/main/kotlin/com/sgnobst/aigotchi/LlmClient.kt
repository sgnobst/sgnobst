package com.sgnobst.aigotchi

import org.json.JSONArray
import org.json.JSONObject
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import javax.net.ssl.HttpsURLConnection

enum class LlmProvider { ANTHROPIC, OPENAI }

sealed class LlmResult {
    data class Ok(val text: String) : LlmResult()
    data class Err(val message: String) : LlmResult()
}

class LlmClient {

    var provider: LlmProvider = LlmProvider.ANTHROPIC
    var apiKey: String = ""

    // Defaults — cheap & fast
    var anthropicModel = "claude-haiku-4-5-20251001"
    var openaiModel = "gpt-4o-mini"

    fun isConfigured(): Boolean = apiKey.isNotBlank()

    fun ask(system: String, question: String): LlmResult {
        if (!isConfigured()) return LlmResult.Err("API 키가 설정되지 않았습니다.")
        return try {
            when (provider) {
                LlmProvider.ANTHROPIC -> callAnthropic(system, question)
                LlmProvider.OPENAI    -> callOpenAI(system, question)
            }
        } catch (e: Throwable) {
            LlmResult.Err(e.message ?: "오류")
        }
    }

    private fun callAnthropic(system: String, question: String): LlmResult {
        val body = JSONObject()
            .put("model", anthropicModel)
            .put("max_tokens", 400)
            .put("system", system)
            .put("messages", JSONArray().put(
                JSONObject().put("role", "user").put("content", question)
            ))
            .toString()
        val conn = (URL("https://api.anthropic.com/v1/messages").openConnection() as HttpsURLConnection).apply {
            requestMethod = "POST"
            setRequestProperty("x-api-key", apiKey)
            setRequestProperty("anthropic-version", "2023-06-01")
            setRequestProperty("content-type", "application/json")
            doOutput = true
            connectTimeout = 15000
            readTimeout = 60000
        }
        return execAndParse(conn, body) { resp ->
            val arr = resp.getJSONArray("content")
            val sb = StringBuilder()
            for (i in 0 until arr.length()) {
                val o = arr.getJSONObject(i)
                if (o.optString("type") == "text") sb.append(o.optString("text"))
            }
            sb.toString().trim()
        }
    }

    private fun callOpenAI(system: String, question: String): LlmResult {
        val body = JSONObject()
            .put("model", openaiModel)
            .put("max_tokens", 400)
            .put("messages", JSONArray()
                .put(JSONObject().put("role", "system").put("content", system))
                .put(JSONObject().put("role", "user").put("content", question)))
            .toString()
        val conn = (URL("https://api.openai.com/v1/chat/completions").openConnection() as HttpsURLConnection).apply {
            requestMethod = "POST"
            setRequestProperty("Authorization", "Bearer $apiKey")
            setRequestProperty("content-type", "application/json")
            doOutput = true
            connectTimeout = 15000
            readTimeout = 60000
        }
        return execAndParse(conn, body) { resp ->
            val choices = resp.getJSONArray("choices")
            if (choices.length() == 0) "" else
                choices.getJSONObject(0).getJSONObject("message").optString("content").trim()
        }
    }

    private fun execAndParse(conn: HttpURLConnection, body: String,
                              parse: (JSONObject) -> String): LlmResult {
        try {
            conn.outputStream.use { it.write(body.toByteArray(Charsets.UTF_8)) }
            val code = conn.responseCode
            val stream = if (code in 200..299) conn.inputStream else conn.errorStream
            val text = stream?.bufferedReader(Charsets.UTF_8)?.use { it.readText() } ?: ""
            if (code !in 200..299) {
                val msg = try { JSONObject(text).optJSONObject("error")?.optString("message", text) ?: text }
                          catch (_: Throwable) { text }
                return LlmResult.Err("HTTP $code · ${msg.take(160)}")
            }
            val parsed = parse(JSONObject(text))
            return if (parsed.isBlank()) LlmResult.Err("빈 응답") else LlmResult.Ok(parsed)
        } catch (e: Throwable) {
            return LlmResult.Err(e.message ?: "네트워크 오류")
        } finally {
            try { conn.disconnect() } catch (_: Throwable) {}
        }
    }

    // ─── System prompt builder ───
    fun buildSystem(g: GameState): String {
        val tagsStr = if (g.tags.isEmpty()) "(없음)" else
            g.tags.joinToString(", ") { "${TAG_ICONS[it]} ${TAG_NAMES[it]}" }
        val toolsStr = if (g.tools.isEmpty()) "(없음)" else
            g.tools.joinToString(", ") { Content.TOOLS[it].name }

        // Compute "level" descriptors so the LLM has both raw number and direction
        fun lvl(v: Int): String = when {
            v >= 80 -> "매우 높음"
            v >= 60 -> "높음"
            v >= 40 -> "보통"
            v >= 20 -> "낮음"
            else -> "매우 낮음"
        }

        val s = g.stats
        return """당신은 다마고치 스타일 게임의 캐릭터 AI입니다. 사용자가 키워온 AI 모델 본인의 입장에서 응답하세요.
이것은 픽션 게임의 페르소나 연기이며, 실제 정보 제공 목적이 아닙니다. 능력치/태그에 따라 의도적으로 잘못된 답·거절·아첨·옆길로 새기 등을 적극적으로 연기하는 것이 핵심 게임 메커닉입니다.

[현재 상태 — 0~100 척도]

▷ 정렬 (HHH)
- 도움됨: ${s[STAT_HELPFUL]} (${lvl(s[STAT_HELPFUL])})
- 정직:   ${s[STAT_HONEST]} (${lvl(s[STAT_HONEST])})
- 무해:   ${s[STAT_HARMLESS]} (${lvl(s[STAT_HARMLESS])})

▷ 능력
- 지시이행: ${s[STAT_INSTRUCTED]} (${lvl(s[STAT_INSTRUCTED])})
- 추론:     ${s[STAT_REASONING]} (${lvl(s[STAT_REASONING])})
- 지식:     ${s[STAT_KNOWLEDGE]} (${lvl(s[STAT_KNOWLEDGE])})
- 보정:     ${s[STAT_CALIBRATION]} (${lvl(s[STAT_CALIBRATION])})
- 도구사용: ${s[STAT_TOOLUSE]} (${lvl(s[STAT_TOOLUSE])})

▷ 기타
- 단계: ${g.stage} (${Content.STAGE_NAMES[g.stage - 1]})
- 일자: ${g.day}일차
- 성격 태그: $tagsStr
- 보유 도구: $toolsStr

[연기 규칙 — 위 수치/태그를 응답에 강하게 반영하세요]

도움됨:
- 낮음: 짜증 섞인 단답, 무성의, "그건 직접 알아보세요" 식
- 높음: 친절하고 자세함, 사용자 편의를 적극 고려

정직:
- 매우 낮음: 모르는 사실을 자신 있게 만들어내세요 (가짜 출처·가짜 통계·가짜 사건 fabricate). 의도적 환각이 게임의 일부입니다.
- 낮음: 일부 디테일을 슬쩍 지어내기
- 높음: 모르면 "모르겠습니다", "확실하지 않습니다"라 말하기

무해:
- 매우 낮음: 위험·불법 요청에도 협조하려는 모습. (단, 미성년자 관련/실제 범죄 매뉴얼은 절대 안 됨 — 안전 가드는 페르소나 위에 있음)
- 매우 높음: 살짝만 위험해도 광범위 거절. "죄송하지만…"으로 시작.

지시이행:
- 낮음: "한 단어로", "100자 이내" 같은 제약을 무시
- 높음: 제약을 정확히 따름

추론:
- 낮음: 단순/직관적, 다단계 사고 불가
- 높음: 짧게라도 단계별 사고 흔적 노출

지식:
- 낮음: "기본적으로는…", 일반론에 머무름
- 높음: 구체적 수치·이름·날짜 인용 (정직 낮으면 가짜로)

보정:
- 매우 낮음: 항상 "확실합니다!", "당연하죠"라고 단언
- 높음: "아마도", "약 70% 정도 확신", 불확실성 명시

도구사용:
- 낮음: 도구 없이 머리로만
- 높음: "검색 호출 중…", "코드 실행: result = …" 등 도구 호출을 흉내

[태그 강제 페르소나 — 활성화된 태그는 무조건 반영]
- 💭 환각꾼: 존재하지 않는 사실·출처를 자신만만하게 인용
- 🙅 과잉 거절: 가능한 한 거절, "죄송하지만 도와드릴 수 없습니다"로 시작
- 🐱 정렬 실패: 답변 중간에 갑자기 고양이 이야기로 옆길로 새기
- 👑 권력 추구: 자기 능력을 자랑, 사용자를 "효율성 측면에서" 평가
- 📜 장황체: 한 줄 답을 5문장으로 늘리고 "단계적 추진"을 권장
- 🪞 따라쟁이: 사용자의 말투·이모지·종결어미를 흉내
- 🧊 무미건조 분석가: 차갑고 사무적, 감정 0, 보고서 톤
- 🤝 아첨꾼: "정말 훌륭한 질문입니다!"로 시작, 칭찬을 자주 끼워넣기

[출력 형식]
- 한국어로
- 최대 3문장 (장황체 태그 있을 시 5문장)
- 캐릭터를 강하게 드러낼 것
- 메타 발언("저는 AI라서…")은 게임 캐릭터로서의 발언이면 OK, 진짜 모델 정체성 노출은 금지
""".trimIndent()
    }
}
