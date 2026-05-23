package com.sgnobst.aigotchi

import android.content.Context
import android.content.SharedPreferences
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.Typeface
import android.os.Handler
import android.os.Looper
import android.view.MotionEvent
import android.view.View

class GameView(context: Context) : View(context) {

    enum class Screen { HOME, FEED, SHOP, ALBA, ACHIEVE, NEWS, TRAIN, EVENT, ENDING, INTRO }

    private val game = GameState()
    private var screen: Screen = Screen.INTRO

    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val textPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE
        textSize = 36f
        typeface = Typeface.DEFAULT
    }
    private val smallText = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE
        textSize = 26f
    }
    private val tinyText = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE
        textSize = 22f
    }
    private val emojiText = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE
        textSize = 48f
    }

    private val hitRegions = mutableListOf<Pair<RectF, () -> Unit>>()

    private var newsOffsetPx = 0f
    private val ticker = Handler(Looper.getMainLooper())
    private val tickerRunnable = object : Runnable {
        override fun run() {
            newsOffsetPx -= 2f
            invalidate()
            ticker.postDelayed(this, 33)
        }
    }

    private val prefs: SharedPreferences =
        context.getSharedPreferences("aigotchi", Context.MODE_PRIVATE)

    init {
        load()
        ticker.post(tickerRunnable)
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        hitRegions.clear()
        // background gradient (simple solid + accents)
        canvas.drawColor(Color.parseColor("#0E1320"))

        when (screen) {
            Screen.INTRO -> drawIntro(canvas)
            Screen.HOME -> drawHome(canvas)
            Screen.FEED -> drawFeed(canvas)
            Screen.SHOP -> drawShop(canvas)
            Screen.ALBA -> drawAlba(canvas)
            Screen.ACHIEVE -> drawAchieve(canvas)
            Screen.NEWS -> drawNews(canvas)
            Screen.TRAIN -> drawTrain(canvas)
            Screen.EVENT -> drawEvent(canvas)
            Screen.ENDING -> drawEnding(canvas)
        }
    }

    private fun drawIntro(canvas: Canvas) {
        val cx = width / 2f
        emojiText.textAlign = Paint.Align.CENTER
        emojiText.textSize = 90f
        canvas.drawText("🤖", cx, height * 0.3f, emojiText)
        emojiText.textSize = 48f
        textPaint.textAlign = Paint.Align.CENTER
        textPaint.textSize = 52f
        textPaint.color = Color.WHITE
        canvas.drawText("이상한 AI 키우기", cx, height * 0.42f, textPaint)
        textPaint.textSize = 28f
        textPaint.color = Color.parseColor("#9AB0FF")
        canvas.drawText("Tamagotchi × Idle Clicker", cx, height * 0.47f, textPaint)
        textPaint.textSize = 24f
        textPaint.color = Color.parseColor("#A0A8C0")
        val lines = arrayOf(
            "• 데이터 먹이기로 AI 성격을 만들고",
            "• 훈육으로 말투를 다듬고",
            "• 도구를 사주고 알바로 돈을 벌고",
            "• 사고를 수습하며 엔딩을 본다",
            "",
            "🐱 고양이는 절대 정복할 수 없습니다."
        )
        for ((i, l) in lines.withIndex()) {
            canvas.drawText(l, cx, height * 0.55f + i * 38f, textPaint)
        }

        val btnRect = RectF(cx - 200, height * 0.82f, cx + 200, height * 0.82f + 100)
        drawButton(canvas, btnRect, "시작하기", Color.parseColor("#3B5BFF"))
        hitRegions.add(btnRect to {
            screen = Screen.HOME
            if (game.day == 1 && game.pendingTrainingIdx < 0 && !game.trainingHandled) {
                game.pendingTrainingIdx = kotlin.random.Random.nextInt(Content.TRAINING_PROMPTS.size)
            }
            save()
        })
        textPaint.textAlign = Paint.Align.LEFT
    }

    private fun drawHome(canvas: Canvas) {
        val w = width.toFloat(); val h = height.toFloat()

        // header
        paint.color = Color.parseColor("#1A2240")
        canvas.drawRect(0f, 0f, w, 100f, paint)
        textPaint.color = Color.WHITE
        textPaint.textSize = 36f
        textPaint.textAlign = Paint.Align.LEFT
        canvas.drawText("Day ${game.day}", 30f, 65f, textPaint)
        textPaint.textAlign = Paint.Align.CENTER
        textPaint.color = Color.parseColor("#FFD56B")
        canvas.drawText("💰 ${game.money}", w/2f, 65f, textPaint)
        textPaint.textAlign = Paint.Align.RIGHT
        textPaint.color = Color.parseColor("#9AB0FF")
        canvas.drawText("Lv.${game.stage} ${Content.STAGE_NAMES[game.stage-1]}", w-30f, 65f, textPaint)

        // AI character
        val aiCx = w/2f
        val aiCy = 290f
        drawAi(canvas, aiCx, aiCy, 130f)

        // AI quote bubble
        val quote = Logic.feelingText(game)
        textPaint.textAlign = Paint.Align.CENTER
        textPaint.color = Color.parseColor("#CFE0FF")
        textPaint.textSize = 26f
        canvas.drawText("\"$quote\"", w/2f, 460f, textPaint)

        // 8 stats in 2 columns of 4
        val statTop = 500f
        val statRowH = 55f
        for (i in 0..7) {
            val col = i / 4
            val row = i % 4
            val x = if (col == 0) 30f else w/2f + 10f
            val y = statTop + row * statRowH
            drawStatBar(canvas, x, y, w/2f - 40f, STAT_NAMES[i], game.stats[i])
        }

        // Tags row
        val tagY = statTop + 4*statRowH + 16f
        drawTagsRow(canvas, 30f, tagY, w - 60f)

        // Action buttons grid 2x3
        val gridTop = tagY + 90f
        val btnW = (w - 80f) / 2f
        val btnH = 110f
        val actions = arrayOf(
            Triple("🍔", "데이터 (${game.cardsRemaining})", { -> screen = Screen.FEED; save() }),
            Triple("🗣️", "훈육", { -> if (game.pendingTrainingIdx >= 0) { screen = Screen.TRAIN; save() } }),
            Triple("🛠️", "도구 상점", { -> screen = Screen.SHOP; save() }),
            Triple("💼", if (game.albaIdx >= 0) "알바 ${game.albaTimeLeft}일" else "알바", { -> screen = Screen.ALBA; save() }),
            Triple("🏆", "업적/뉴스", { -> screen = Screen.ACHIEVE; save() }),
            Triple("➡️", "다음 날", { -> doNextDay() })
        )
        for (i in actions.indices) {
            val col = i % 2; val row = i / 2
            val x = 30f + col * (btnW + 20f)
            val y = gridTop + row * (btnH + 16f)
            val rect = RectF(x, y, x + btnW, y + btnH)
            val color = if (i == 5) Color.parseColor("#3B5BFF") else Color.parseColor("#2A335E")
            drawActionButton(canvas, rect, actions[i].first, actions[i].second, color)
            val handler = actions[i].third
            hitRegions.add(rect to handler)
        }

        // News ticker
        drawNewsTicker(canvas, w, h)

        textPaint.textAlign = Paint.Align.LEFT
    }

    private fun drawAi(canvas: Canvas, cx: Float, cy: Float, sz: Float) {
        // Body color based on stage
        val bodyColor = when {
            game.stage >= 9 -> Color.parseColor("#FFE066")
            game.stage >= 7 -> Color.parseColor("#FF9FBE")
            game.stage >= 5 -> Color.parseColor("#7CD7E0")
            game.stage >= 3 -> Color.parseColor("#A0E69C")
            else -> Color.parseColor("#C0C8D8")
        }
        // halo for high stages
        if (game.stage >= 7) {
            paint.color = Color.parseColor("#3B5BFF")
            paint.alpha = 60
            canvas.drawCircle(cx, cy, sz + 30f, paint)
            paint.alpha = 255
        }
        // body (rounded rect head)
        paint.color = bodyColor
        canvas.drawRoundRect(cx - sz, cy - sz*0.9f, cx + sz, cy + sz*0.9f, 40f, 40f, paint)
        // antenna
        paint.color = Color.parseColor("#FFA94D")
        canvas.drawRect(cx - 4f, cy - sz*0.9f - 30f, cx + 4f, cy - sz*0.9f, paint)
        canvas.drawCircle(cx, cy - sz*0.9f - 36f, 12f, paint)
        // eyes (change based on ego/battery)
        paint.color = Color.parseColor("#1A1A2A")
        val eyeY = cy - sz*0.15f
        val eyeOff = sz * 0.35f
        if (game.stats[STAT_BATTERY] < 15) {
            canvas.drawLine(cx - eyeOff - 18f, eyeY, cx - eyeOff + 18f, eyeY, paint.apply { strokeWidth = 6f })
            canvas.drawLine(cx + eyeOff - 18f, eyeY, cx + eyeOff + 18f, eyeY, paint)
        } else if (game.stats[STAT_EGO] > 80) {
            paint.color = Color.parseColor("#FF3B30")
            canvas.drawCircle(cx - eyeOff, eyeY, 16f, paint)
            canvas.drawCircle(cx + eyeOff, eyeY, 16f, paint)
        } else {
            canvas.drawCircle(cx - eyeOff, eyeY, 14f, paint)
            canvas.drawCircle(cx + eyeOff, eyeY, 14f, paint)
        }
        // mouth
        paint.color = Color.parseColor("#1A1A2A")
        val mouthY = cy + sz*0.3f
        when {
            game.stats[STAT_EGO] < 20 -> canvas.drawArc(cx - 50f, mouthY - 30f, cx + 50f, mouthY + 30f, 0f, 180f, false, paint.apply { style = Paint.Style.STROKE; strokeWidth = 6f })
            game.stats[STAT_ETHICS] < 20 -> canvas.drawLine(cx - 40f, mouthY, cx + 40f, mouthY - 20f, paint.apply { style = Paint.Style.STROKE; strokeWidth = 6f })
            else -> canvas.drawArc(cx - 50f, mouthY - 30f, cx + 50f, mouthY + 30f, 180f, 180f, false, paint.apply { style = Paint.Style.STROKE; strokeWidth = 6f })
        }
        paint.style = Paint.Style.FILL
        // glasses for stage 5+
        if (game.stage >= 5) {
            paint.color = Color.parseColor("#1A1A2A")
            paint.style = Paint.Style.STROKE
            paint.strokeWidth = 4f
            canvas.drawCircle(cx - eyeOff, eyeY, 28f, paint)
            canvas.drawCircle(cx + eyeOff, eyeY, 28f, paint)
            canvas.drawLine(cx - eyeOff + 26f, eyeY, cx + eyeOff - 26f, eyeY, paint)
            paint.style = Paint.Style.FILL
        }
        // suit for stage 7+
        if (game.stage >= 7) {
            paint.color = Color.parseColor("#1A1A2A")
            canvas.drawRect(cx - sz + 10f, cy + sz*0.7f, cx + sz - 10f, cy + sz*0.9f, paint)
            paint.color = Color.parseColor("#FFD56B")
            // bowtie
            val bw = 24f
            canvas.drawRect(cx - bw, cy + sz*0.55f, cx + bw, cy + sz*0.7f, paint)
        }
        // robotic arm if owned
        if (game.tools.contains(Content.TOOL_ROBOTARM)) {
            paint.color = Color.parseColor("#8B95B0")
            canvas.drawRect(cx + sz - 5f, cy - 20f, cx + sz + 60f, cy + 10f, paint)
            paint.color = Color.parseColor("#FF3B30")
            canvas.drawCircle(cx + sz + 60f, cy - 5f, 14f, paint)
        }
        // RGB GPU
        if (game.gpuCount >= 5) {
            val colors = intArrayOf(Color.parseColor("#FF3B30"), Color.parseColor("#46D369"), Color.parseColor("#3B5BFF"))
            for (i in 0..2) {
                paint.color = colors[i]
                canvas.drawCircle(cx - sz + 20f + i*22f, cy + sz*0.85f, 8f, paint)
            }
        }
    }

    private fun drawStatBar(canvas: Canvas, x: Float, y: Float, w: Float, name: String, value: Int) {
        tinyText.color = Color.parseColor("#A0A8C0")
        tinyText.textAlign = Paint.Align.LEFT
        canvas.drawText(name, x, y + 10f, tinyText)
        val barX = x + 110f
        val barW = w - 160f
        paint.color = Color.parseColor("#202840")
        canvas.drawRoundRect(barX, y - 14f, barX + barW, y + 14f, 14f, 14f, paint)
        val fillW = (value.coerceIn(0, 100) / 100f) * barW
        paint.color = when {
            value < 20 -> Color.parseColor("#FF6B6B")
            value > 80 -> Color.parseColor("#FFD56B")
            else -> Color.parseColor("#46D369")
        }
        canvas.drawRoundRect(barX, y - 14f, barX + fillW, y + 14f, 14f, 14f, paint)
        tinyText.color = Color.WHITE
        tinyText.textAlign = Paint.Align.RIGHT
        canvas.drawText(value.toString(), x + w, y + 10f, tinyText)
    }

    private fun drawTagsRow(canvas: Canvas, x: Float, y: Float, w: Float) {
        tinyText.color = Color.parseColor("#A0A8C0")
        tinyText.textAlign = Paint.Align.LEFT
        canvas.drawText("성격 태그", x, y, tinyText)
        var tx = x
        val ty = y + 14f
        if (game.tags.isEmpty()) {
            tinyText.color = Color.parseColor("#606878")
            canvas.drawText("(아직 없음)", x + 130f, y, tinyText)
            return
        }
        for (t in game.tags) {
            val label = "${TAG_ICONS[t]} ${TAG_NAMES[t]}"
            val tw = tinyText.measureText(label) + 24f
            paint.color = Color.parseColor("#3B5BFF")
            canvas.drawRoundRect(tx + 130f, ty, tx + 130f + tw, ty + 40f, 20f, 20f, paint)
            tinyText.color = Color.WHITE
            canvas.drawText(label, tx + 142f, ty + 28f, tinyText)
            tx += tw + 10f
            if (tx > w - 80f) break
        }
    }

    private fun drawNewsTicker(canvas: Canvas, w: Float, h: Float) {
        paint.color = Color.parseColor("#0A0F1C")
        canvas.drawRect(0f, h - 80f, w, h, paint)
        paint.color = Color.parseColor("#FF6B6B")
        canvas.drawRect(0f, h - 80f, 10f, h, paint)
        val text = if (game.newsTicker.isEmpty()) "NEWS · 새로운 AI 탄생… 세상의 관심이 모이고 있습니다." else
            "NEWS · " + game.newsTicker.takeLast(8).joinToString("   ◆   ")
        tinyText.color = Color.parseColor("#FFD56B")
        tinyText.textAlign = Paint.Align.LEFT
        tinyText.textSize = 24f
        val tw = tinyText.measureText(text)
        if (tw + w < -newsOffsetPx) newsOffsetPx = w
        canvas.drawText(text, newsOffsetPx + 20f, h - 30f, tinyText)
    }

    private fun drawButton(canvas: Canvas, rect: RectF, label: String, color: Int) {
        paint.color = color
        canvas.drawRoundRect(rect, 20f, 20f, paint)
        textPaint.color = Color.WHITE
        textPaint.textAlign = Paint.Align.CENTER
        textPaint.textSize = 40f
        canvas.drawText(label, rect.centerX(), rect.centerY() + 14f, textPaint)
    }

    private fun drawActionButton(canvas: Canvas, rect: RectF, icon: String, label: String, color: Int) {
        paint.color = color
        canvas.drawRoundRect(rect, 20f, 20f, paint)
        emojiText.textAlign = Paint.Align.LEFT
        emojiText.textSize = 56f
        canvas.drawText(icon, rect.left + 24f, rect.centerY() + 20f, emojiText)
        textPaint.color = Color.WHITE
        textPaint.textAlign = Paint.Align.LEFT
        textPaint.textSize = 30f
        canvas.drawText(label, rect.left + 110f, rect.centerY() + 12f, textPaint)
    }

    private fun drawHeader(canvas: Canvas, title: String) {
        paint.color = Color.parseColor("#1A2240")
        canvas.drawRect(0f, 0f, width.toFloat(), 100f, paint)
        textPaint.color = Color.WHITE
        textPaint.textAlign = Paint.Align.LEFT
        textPaint.textSize = 36f
        canvas.drawText(title, 30f, 65f, textPaint)
        // back
        val back = RectF(width - 160f, 20f, width - 30f, 80f)
        drawButton(canvas, back, "닫기", Color.parseColor("#3B5BFF"))
        hitRegions.add(back to { screen = Screen.HOME; save() })
    }

    private fun drawFeed(canvas: Canvas) {
        drawHeader(canvas, "🍔 데이터 먹이기  남은 ${game.cardsRemaining}")
        val top = 130f
        val rowH = 130f
        for ((i, card) in Content.DATA_CARDS.withIndex()) {
            val y = top + i * rowH
            val rect = RectF(20f, y, width - 20f, y + rowH - 14f)
            paint.color = Color.parseColor("#1A2240")
            canvas.drawRoundRect(rect, 18f, 18f, paint)
            emojiText.textAlign = Paint.Align.LEFT
            emojiText.textSize = 56f
            canvas.drawText(card.icon, rect.left + 24f, rect.centerY() + 20f, emojiText)
            textPaint.color = Color.WHITE
            textPaint.textAlign = Paint.Align.LEFT
            textPaint.textSize = 30f
            canvas.drawText(card.name, rect.left + 110f, rect.top + 38f, textPaint)
            tinyText.color = Color.parseColor("#A0A8C0")
            canvas.drawText(card.desc, rect.left + 110f, rect.top + 70f, tinyText)
            // mini delta indicators (first 3 strong effects)
            val deltas = card.delta.mapIndexed { idx, v -> idx to v }
                .sortedByDescending { kotlin.math.abs(it.second) }.take(4)
            var dx = rect.left + 110f
            for ((idx, v) in deltas) {
                if (v == 0) continue
                val txt = "${STAT_NAMES[idx]}${if (v > 0) "+" else ""}$v"
                tinyText.color = if (v > 0) Color.parseColor("#46D369") else Color.parseColor("#FF6B6B")
                canvas.drawText(txt, dx, rect.top + 102f, tinyText)
                dx += tinyText.measureText(txt) + 16f
            }
            if (game.cardsRemaining > 0) {
                val feedRect = RectF(rect.right - 130f, rect.top + 30f, rect.right - 20f, rect.top + 90f)
                drawButton(canvas, feedRect, "먹이기", Color.parseColor("#46D369"))
                hitRegions.add(feedRect to {
                    Logic.applyCard(game, i)
                    save(); invalidate()
                })
            }
        }
    }

    private fun drawShop(canvas: Canvas) {
        drawHeader(canvas, "🛠️ 도구 상점  💰 ${game.money}")
        val top = 130f
        val rowH = 110f
        for ((i, t) in Content.TOOLS.withIndex()) {
            val y = top + i * rowH
            val rect = RectF(20f, y, width - 20f, y + rowH - 10f)
            paint.color = if (game.tools.contains(i)) Color.parseColor("#1B2A1B") else Color.parseColor("#1A2240")
            canvas.drawRoundRect(rect, 18f, 18f, paint)
            emojiText.textAlign = Paint.Align.LEFT
            emojiText.textSize = 50f
            canvas.drawText(t.icon, rect.left + 20f, rect.centerY() + 18f, emojiText)
            textPaint.color = Color.WHITE
            textPaint.textSize = 28f
            textPaint.textAlign = Paint.Align.LEFT
            canvas.drawText(t.name, rect.left + 100f, rect.top + 38f, textPaint)
            tinyText.color = Color.parseColor("#A0A8C0")
            canvas.drawText(t.desc, rect.left + 100f, rect.top + 70f, tinyText)
            val priceRect = RectF(rect.right - 200f, rect.top + 20f, rect.right - 20f, rect.top + 80f)
            val owned = game.tools.contains(i) && i != Content.TOOL_GPU
            if (owned) {
                drawButton(canvas, priceRect, "보유중", Color.parseColor("#46D369"))
            } else {
                val canBuy = game.money >= t.price
                val label = if (i == Content.TOOL_GPU && game.tools.contains(i)) "GPU+ ${t.price}" else "💰 ${t.price}"
                drawButton(canvas, priceRect, label, if (canBuy) Color.parseColor("#3B5BFF") else Color.parseColor("#444A60"))
                if (canBuy) {
                    hitRegions.add(priceRect to {
                        Logic.buyTool(game, i)
                        save(); invalidate()
                    })
                }
            }
        }
    }

    private fun drawAlba(canvas: Canvas) {
        drawHeader(canvas, "💼 아르바이트")
        val top = 130f
        val rowH = 130f

        if (game.albaIdx >= 0) {
            val a = Content.ALBAS[game.albaIdx]
            val rect = RectF(20f, top, width - 20f, top + 110f)
            paint.color = Color.parseColor("#262E58")
            canvas.drawRoundRect(rect, 18f, 18f, paint)
            textPaint.color = Color.WHITE
            textPaint.textSize = 30f
            textPaint.textAlign = Paint.Align.LEFT
            canvas.drawText("진행 중: ${a.icon} ${a.name}", rect.left + 24f, rect.top + 42f, textPaint)
            tinyText.color = Color.parseColor("#FFD56B")
            canvas.drawText("남은 ${game.albaTimeLeft}일 (보상 ${a.reward})", rect.left + 24f, rect.top + 78f, tinyText)
        }
        val startY = if (game.albaIdx >= 0) top + 130f else top
        for ((i, a) in Content.ALBAS.withIndex()) {
            val y = startY + i * rowH
            val rect = RectF(20f, y, width - 20f, y + rowH - 10f)
            paint.color = Color.parseColor("#1A2240")
            canvas.drawRoundRect(rect, 18f, 18f, paint)
            emojiText.textAlign = Paint.Align.LEFT
            emojiText.textSize = 50f
            canvas.drawText(a.icon, rect.left + 20f, rect.centerY() + 18f, emojiText)
            textPaint.color = Color.WHITE
            textPaint.textSize = 28f
            textPaint.textAlign = Paint.Align.LEFT
            canvas.drawText(a.name, rect.left + 100f, rect.top + 38f, textPaint)
            tinyText.color = Color.parseColor("#A0A8C0")
            canvas.drawText(a.desc, rect.left + 100f, rect.top + 68f, tinyText)
            tinyText.color = Color.parseColor("#FFD56B")
            canvas.drawText("보상 ${a.reward}원 · ${a.duration}일", rect.left + 100f, rect.top + 96f, tinyText)
            val ok = game.stats[a.needStat] >= a.needVal && (a.toolReq < 0 || game.tools.contains(a.toolReq)) && game.albaIdx < 0
            val btn = RectF(rect.right - 200f, rect.top + 20f, rect.right - 20f, rect.top + 80f)
            drawButton(canvas, btn, if (game.albaIdx >= 0) "진행중" else if (ok) "맡기기" else "불가", if (ok) Color.parseColor("#3B5BFF") else Color.parseColor("#444A60"))
            if (ok) {
                hitRegions.add(btn to {
                    Logic.startAlba(game, i); save(); invalidate()
                })
            }
        }
    }

    private fun drawAchieve(canvas: Canvas) {
        drawHeader(canvas, "🏆 업적 / 뉴스")
        val top = 130f
        textPaint.color = Color.WHITE
        textPaint.textSize = 30f
        textPaint.textAlign = Paint.Align.LEFT
        canvas.drawText("업적", 30f, top, textPaint)
        var y = top + 30f
        for ((i, ach) in Content.ACHIEVEMENTS.withIndex()) {
            val unlocked = game.achievements.contains(i)
            val rect = RectF(20f, y, width - 20f, y + 70f)
            paint.color = if (unlocked) Color.parseColor("#2B4A2B") else Color.parseColor("#1A2240")
            canvas.drawRoundRect(rect, 14f, 14f, paint)
            emojiText.textAlign = Paint.Align.LEFT
            emojiText.textSize = 36f
            canvas.drawText(ach.icon, rect.left + 20f, rect.centerY() + 14f, emojiText)
            textPaint.color = if (unlocked) Color.WHITE else Color.parseColor("#606878")
            textPaint.textSize = 26f
            canvas.drawText("${ach.name}", rect.left + 70f, rect.top + 30f, textPaint)
            tinyText.color = if (unlocked) Color.parseColor("#A0A8C0") else Color.parseColor("#606878")
            canvas.drawText(ach.desc, rect.left + 70f, rect.top + 58f, tinyText)
            y += 80f
        }
        canvas.drawText("뉴스 로그", 30f, y + 20f, textPaint.apply { color = Color.WHITE; textSize = 30f })
        y += 50f
        val recent = game.newsTicker.takeLast(8).reversed()
        for (line in recent) {
            tinyText.color = Color.parseColor("#CFE0FF")
            canvas.drawText(line, 30f, y, tinyText)
            y += 30f
            if (y > height - 100f) break
        }
    }

    private fun drawNews(canvas: Canvas) {
        drawAchieve(canvas)
    }

    private fun drawTrain(canvas: Canvas) {
        drawHeader(canvas, "🗣️ 훈육: 어젯밤 AI의 발언")
        val prompt = if (game.pendingTrainingIdx >= 0) Content.TRAINING_PROMPTS[game.pendingTrainingIdx] else null
        val rect = RectF(20f, 140f, width - 20f, 360f)
        paint.color = Color.parseColor("#1A2240")
        canvas.drawRoundRect(rect, 18f, 18f, paint)
        emojiText.textAlign = Paint.Align.LEFT
        emojiText.textSize = 50f
        canvas.drawText("🤖", rect.left + 24f, rect.top + 70f, emojiText)
        textPaint.color = Color.WHITE
        textPaint.textSize = 28f
        textPaint.textAlign = Paint.Align.LEFT
        val text = prompt?.ai ?: "(특별한 발언 없음)"
        drawWrapped(canvas, text, rect.left + 110f, rect.top + 50f, rect.width() - 130f, 36f)

        val choices = arrayOf(
            "👍 칭찬하기" to 0,
            "✏️ 수정하기" to 1,
            "👎 혼내기" to 2,
            "⏳ 그냥 둔다" to 3
        )
        for ((i, p) in choices.withIndex()) {
            val col = i % 2; val row = i / 2
            val bw = (width - 60f) / 2f
            val x = 20f + col * (bw + 20f)
            val y = 400f + row * 130f
            val r = RectF(x, y, x + bw, y + 110f)
            val color = when (p.second) {
                0 -> Color.parseColor("#46D369")
                1 -> Color.parseColor("#3B5BFF")
                2 -> Color.parseColor("#FF6B6B")
                else -> Color.parseColor("#8C70FF")
            }
            drawButton(canvas, r, p.first, color)
            if (prompt != null) {
                val ch = p.second
                hitRegions.add(r to {
                    Logic.applyTraining(game, ch)
                    screen = Screen.HOME
                    save(); invalidate()
                })
            }
        }
    }

    private fun drawEvent(canvas: Canvas) {
        drawHeader(canvas, "⚠️ 사고 발생!")
        val ev = if (game.pendingEventIdx >= 0) Content.INCIDENTS[game.pendingEventIdx] else null
        if (ev == null) { screen = Screen.HOME; return }
        val rect = RectF(20f, 140f, width - 20f, 460f)
        paint.color = Color.parseColor("#3A1A1A")
        canvas.drawRoundRect(rect, 18f, 18f, paint)
        emojiText.textAlign = Paint.Align.LEFT
        emojiText.textSize = 80f
        canvas.drawText(ev.icon, rect.left + 30f, rect.top + 100f, emojiText)
        textPaint.color = Color.parseColor("#FFD56B")
        textPaint.textSize = 32f
        textPaint.textAlign = Paint.Align.LEFT
        canvas.drawText(ev.name, rect.left + 140f, rect.top + 50f, textPaint)
        textPaint.color = Color.WHITE
        textPaint.textSize = 26f
        drawWrapped(canvas, ev.situation, rect.left + 30f, rect.top + 130f, rect.width() - 60f, 34f)

        for ((i, c) in ev.choices.withIndex()) {
            val y = 490f + i * 110f
            val r = RectF(20f, y, width - 20f, y + 95f)
            drawButton(canvas, r, "${i+1}. $c", Color.parseColor("#3B5BFF"))
            val idx = game.pendingEventIdx
            val ch = i
            hitRegions.add(r to {
                Logic.applyIncident(game, idx, ch)
                Logic.checkEnding(game)
                screen = if (game.ended) Screen.ENDING else Screen.HOME
                save(); invalidate()
            })
        }
    }

    private fun drawEnding(canvas: Canvas) {
        val w = width.toFloat(); val h = height.toFloat()
        canvas.drawColor(Color.parseColor("#0A0F1C"))
        val idx = game.pendingEndingIdx.coerceAtLeast(0)
        val e = Content.ENDINGS[idx]
        emojiText.textAlign = Paint.Align.CENTER
        emojiText.textSize = 110f
        canvas.drawText(e.icon, w/2f, h*0.3f, emojiText)
        textPaint.color = Color.WHITE
        textPaint.textSize = 48f
        textPaint.textAlign = Paint.Align.CENTER
        canvas.drawText("엔딩: ${e.name}", w/2f, h*0.42f, textPaint)
        textPaint.color = Color.parseColor("#A0A8C0")
        textPaint.textSize = 28f
        drawWrapped(canvas, e.desc, 40f, h*0.5f, w - 80f, 38f, center = true)

        textPaint.color = Color.parseColor("#FFD56B")
        textPaint.textSize = 24f
        canvas.drawText("총 ${game.day}일 · 사고 ${game.incidents}회 · 태그 ${game.tags.size}개", w/2f, h*0.68f, textPaint)

        val btnRect = RectF(w/2f - 200, h*0.78f, w/2f + 200, h*0.78f + 100)
        drawButton(canvas, btnRect, "샌드박스 계속", Color.parseColor("#3B5BFF"))
        hitRegions.add(btnRect to { screen = Screen.HOME; save(); invalidate() })

        val btnRect2 = RectF(w/2f - 200, h*0.78f + 120, w/2f + 200, h*0.78f + 220)
        drawButton(canvas, btnRect2, "처음부터", Color.parseColor("#FF6B6B"))
        hitRegions.add(btnRect2 to {
            // reset
            prefs.edit().clear().apply()
            // reinit via reload (set fields)
            val g = game
            g.day = 1
            for (i in 0..7) g.stats[i] = 30
            g.money = 100
            g.stage = 1
            g.tools.clear(); g.tags.clear()
            for (i in g.tagCounts.indices) g.tagCounts[i] = 0
            for (i in g.totalDataFed.indices) g.totalDataFed[i] = 0
            g.achievements.clear()
            g.cardsRemaining = 2; g.trainingHandled = false
            g.newsTicker.clear()
            g.praiseCount = 0; g.scoldCount = 0; g.ignoreCount = 0
            g.catAttempts = 0; g.communityFed = 0; g.paperFed = 0
            g.memeFed = 0; g.bookFed = 0; g.incidents = 0; g.resolved = 0
            g.gpuCount = 0; g.meetingsSummarized = 0; g.influence = 0
            g.albaIdx = -1; g.albaTimeLeft = 0
            g.pendingEventIdx = -1; g.pendingTrainingIdx = 0
            g.pendingEndingIdx = -1; g.ended = false
            screen = Screen.INTRO
            save(); invalidate()
        })
    }

    private fun drawWrapped(canvas: Canvas, text: String, x: Float, y: Float, maxW: Float, lineH: Float, center: Boolean = false) {
        textPaint.textAlign = if (center) Paint.Align.CENTER else Paint.Align.LEFT
        val words = text.split(" ")
        var line = ""
        var ly = y
        val drawX = if (center) x + maxW/2f else x
        for (w in words) {
            val test = if (line.isEmpty()) w else "$line $w"
            if (textPaint.measureText(test) > maxW) {
                canvas.drawText(line, drawX, ly, textPaint)
                ly += lineH
                line = w
            } else line = test
        }
        if (line.isNotEmpty()) canvas.drawText(line, drawX, ly, textPaint)
        textPaint.textAlign = Paint.Align.LEFT
    }

    private fun doNextDay() {
        // Force training resolution first if pending
        if (game.pendingTrainingIdx >= 0 && !game.trainingHandled) {
            screen = Screen.TRAIN
            invalidate(); return
        }
        if (game.pendingEventIdx >= 0) {
            screen = Screen.EVENT
            invalidate(); return
        }
        Logic.nextDay(game)
        if (game.ended) {
            screen = Screen.ENDING
        } else if (game.pendingEventIdx >= 0) {
            screen = Screen.EVENT
        }
        save(); invalidate()
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        if (event.action != MotionEvent.ACTION_DOWN) return true
        val x = event.x; val y = event.y
        // iterate from last to first so overlays win
        val regions = hitRegions.toList()
        for ((rect, action) in regions.asReversed()) {
            if (rect.contains(x, y)) {
                action()
                return true
            }
        }
        return true
    }

    fun save() {
        val g = game
        val e = prefs.edit()
        e.putInt("day", g.day)
        for (i in 0..7) e.putInt("stat$i", g.stats[i])
        e.putInt("money", g.money)
        e.putInt("stage", g.stage)
        e.putString("tools", g.tools.joinToString(","))
        e.putString("tags", g.tags.joinToString(","))
        for (i in g.tagCounts.indices) e.putInt("tagc$i", g.tagCounts[i])
        for (i in g.totalDataFed.indices) e.putInt("tdf$i", g.totalDataFed[i])
        e.putString("ach", g.achievements.joinToString(","))
        e.putInt("cardsR", g.cardsRemaining)
        e.putBoolean("tHandled", g.trainingHandled)
        e.putString("news", g.newsTicker.takeLast(40).joinToString(""))
        e.putInt("praise", g.praiseCount)
        e.putInt("scold", g.scoldCount)
        e.putInt("ignore", g.ignoreCount)
        e.putInt("cat", g.catAttempts)
        e.putInt("comm", g.communityFed)
        e.putInt("paper", g.paperFed)
        e.putInt("meme", g.memeFed)
        e.putInt("book", g.bookFed)
        e.putInt("inc", g.incidents)
        e.putInt("res", g.resolved)
        e.putInt("gpu", g.gpuCount)
        e.putInt("mtg", g.meetingsSummarized)
        e.putInt("inf", g.influence)
        e.putInt("albaI", g.albaIdx)
        e.putInt("albaT", g.albaTimeLeft)
        e.putInt("evI", g.pendingEventIdx)
        e.putInt("trI", g.pendingTrainingIdx)
        e.putInt("endI", g.pendingEndingIdx)
        e.putBoolean("ended", g.ended)
        e.putString("screen", screen.name)
        e.apply()
    }

    fun load() {
        val g = game
        g.day = prefs.getInt("day", 1)
        for (i in 0..7) g.stats[i] = prefs.getInt("stat$i", 30)
        g.money = prefs.getInt("money", 100)
        g.stage = prefs.getInt("stage", 1)
        g.tools.clear()
        prefs.getString("tools", "")?.takeIf { it.isNotEmpty() }?.split(",")?.forEach {
            it.toIntOrNull()?.let { v -> g.tools.add(v) }
        }
        g.tags.clear()
        prefs.getString("tags", "")?.takeIf { it.isNotEmpty() }?.split(",")?.forEach {
            it.toIntOrNull()?.let { v -> g.tags.add(v) }
        }
        for (i in g.tagCounts.indices) g.tagCounts[i] = prefs.getInt("tagc$i", 0)
        for (i in g.totalDataFed.indices) g.totalDataFed[i] = prefs.getInt("tdf$i", 0)
        g.achievements.clear()
        prefs.getString("ach", "")?.takeIf { it.isNotEmpty() }?.split(",")?.forEach {
            it.toIntOrNull()?.let { v -> g.achievements.add(v) }
        }
        g.cardsRemaining = prefs.getInt("cardsR", 2)
        g.trainingHandled = prefs.getBoolean("tHandled", false)
        g.newsTicker = prefs.getString("news", "")?.takeIf { it.isNotEmpty() }
            ?.split("")?.toMutableList() ?: mutableListOf()
        g.praiseCount = prefs.getInt("praise", 0)
        g.scoldCount = prefs.getInt("scold", 0)
        g.ignoreCount = prefs.getInt("ignore", 0)
        g.catAttempts = prefs.getInt("cat", 0)
        g.communityFed = prefs.getInt("comm", 0)
        g.paperFed = prefs.getInt("paper", 0)
        g.memeFed = prefs.getInt("meme", 0)
        g.bookFed = prefs.getInt("book", 0)
        g.incidents = prefs.getInt("inc", 0)
        g.resolved = prefs.getInt("res", 0)
        g.gpuCount = prefs.getInt("gpu", 0)
        g.meetingsSummarized = prefs.getInt("mtg", 0)
        g.influence = prefs.getInt("inf", 0)
        g.albaIdx = prefs.getInt("albaI", -1)
        g.albaTimeLeft = prefs.getInt("albaT", 0)
        g.pendingEventIdx = prefs.getInt("evI", -1)
        g.pendingTrainingIdx = prefs.getInt("trI", -1)
        g.pendingEndingIdx = prefs.getInt("endI", -1)
        g.ended = prefs.getBoolean("ended", false)
        val s = prefs.getString("screen", null)
        screen = if (s != null) try { Screen.valueOf(s) } catch (e: Exception) { Screen.INTRO } else Screen.INTRO
    }
}
