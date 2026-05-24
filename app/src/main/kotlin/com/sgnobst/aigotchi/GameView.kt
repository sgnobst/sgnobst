package com.sgnobst.aigotchi

import android.app.Activity
import android.app.AlertDialog
import android.content.Context
import android.content.SharedPreferences
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.os.Handler
import android.os.Looper
import android.text.InputType
import android.view.MotionEvent
import android.view.View
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.RadioButton
import android.widget.RadioGroup
import android.widget.TextView
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.sin
import kotlin.random.Random

class GameView(context: Context, private val audio: Audio) : View(context) {

    enum class Screen { INTRO, PLAY, FEED, SHOP, ALBA, NEWS, TRAIN, EVENT, ASK, ENDING }

    private val game = GameState()
    private var screen: Screen = Screen.INTRO

    private val kit = StyleKit()
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val stroke = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeJoin = Paint.Join.ROUND
        strokeCap = Paint.Cap.ROUND
    }

    private val hits = mutableListOf<Pair<RectF, () -> Unit>>()
    private val worldHits = mutableListOf<Triple<Float, Float, () -> Unit>>()

    // Anim state
    private var tSec = 0f
    private var lastMs = System.currentTimeMillis()
    private var aiBob = 0f
    private var blinkActive = false
    private var blinkT = 1.5f
    private var shakeAmt = 0f
    private var flashA = 0f
    private var flashColor = Color.WHITE
    private val displayStats = FloatArray(8) { 30f }
    private var displayMoney = 100f
    private var dayProgress = 0f
    private val DAY_SECONDS = 45f
    private var pressedHitId = -1
    private var pressedDecay = 0f

    private var prevStage = 1
    private var prevPendingEvent = -1

    // World objects
    private val coins = mutableListOf<Coin>()
    private val cats = mutableListOf<CatBlob>()
    private val glitches = mutableListOf<Glitch>()
    private val particles = mutableListOf<Particle>()
    private val floats = mutableListOf<FloatText>()
    private var coinTimer = 4f
    private var catTimer = 12f
    private var glitchTimer = 8f
    private var newsScroll = 0f
    private var speechT = 0f
    private var speechText: String = ""

    // ─ Modal scroll state ─
    private var modalScrollY = 0f
    private var modalContentH = 0f
    private var modalViewH = 0f
    private var touchStartY = 0f
    private var lastScreenForReset: Screen? = null

    // ─ AI integration ─
    private val llm = LlmClient()
    private val mainHandler = Handler(Looper.getMainLooper())

    enum class AskState { IDLE, LOADING, REVEAL, DONE, ERROR, NEED_KEY }
    private var askState = AskState.IDLE
    private var askQuestion = ""
    private var askResponseFull = ""
    private var askRevealChars = 0
    private var askRevealTimer = 0f
    private var askErrorMsg = ""

    private val prefs: SharedPreferences = context.getSharedPreferences("aigotchi", Context.MODE_PRIVATE)

    private val ticker = Handler(Looper.getMainLooper())
    private val frame = object : Runnable {
        override fun run() {
            val now = System.currentTimeMillis()
            val dt = ((now - lastMs).coerceAtMost(64)) / 1000f
            lastMs = now
            tSec += dt
            update(dt)
            invalidate()
            ticker.postDelayed(this, 16)
        }
    }

    init {
        load()
        for (i in 0..7) displayStats[i] = game.stats[i].toFloat()
        displayMoney = game.money.toFloat()
        prevStage = game.stage
        prevPendingEvent = game.pendingEventIdx
        audio.muted = prefs.getBoolean("muted", false)
        audio.hapticOn = prefs.getBoolean("haptic", true)
        // Load LLM config
        llm.apiKey = prefs.getString("api_key", "") ?: ""
        llm.provider = try {
            LlmProvider.valueOf(prefs.getString("provider", "ANTHROPIC") ?: "ANTHROPIC")
        } catch (_: Throwable) { LlmProvider.ANTHROPIC }
        ticker.post(frame)
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        kit.setScale(w)
    }

    // ───────────────────────── UPDATE ─────────────────────────

    private fun update(dt: Float) {
        if (shakeAmt > 0) shakeAmt = max(0f, shakeAmt - dt * 60f)
        if (flashA > 0) flashA = max(0f, flashA - dt * 3f)
        if (pressedHitId >= 0) {
            pressedDecay -= dt
            if (pressedDecay <= 0) pressedHitId = -1
        }
        for (i in 0..7) {
            displayStats[i] += (game.stats[i] - displayStats[i]) * (dt * 6f).coerceAtMost(1f)
        }
        displayMoney += (game.money - displayMoney) * (dt * 6f).coerceAtMost(1f)
        aiBob = (sin(tSec * 2.2)).toFloat() * kit.sz(14f)
        blinkT -= dt
        if (blinkT <= 0) {
            blinkActive = !blinkActive
            blinkT = if (blinkActive) 0.14f else (2.5f + Random.nextFloat() * 3.5f)
        }

        if (game.pendingEventIdx != prevPendingEvent) {
            if (game.pendingEventIdx >= 0) audio.fx("alert", 30L, 110)
            prevPendingEvent = game.pendingEventIdx
        }
        if (game.stage > prevStage) {
            audio.fx("levelup", 40L, 130)
            burst(width / 2f, height * 0.40f, Style.WARN, 36)
            flash(Style.WARN, 0.5f)
            prevStage = game.stage
        }

        if (screen == Screen.PLAY && !game.ended) {
            dayProgress += dt / DAY_SECONDS
            if (dayProgress >= 1f) {
                dayProgress = 0f
                Logic.nextDay(game)
                onDayAdvanced()
            }
            coinTimer -= dt
            if (coinTimer <= 0) {
                spawnCoin()
                val rate = max(1.0f, 4.2f - game.stage * 0.3f - if (game.albaIdx >= 0) 0.8f else 0f)
                coinTimer = rate + Random.nextFloat() * 1.4f
            }
            catTimer -= dt
            if (catTimer <= 0) {
                if (Random.nextFloat() < 0.6f) spawnCat()
                catTimer = 10f + Random.nextFloat() * 12f
            }
            glitchTimer -= dt
            if (glitchTimer <= 0) {
                val risk = (100 - game.stats[STAT_HARMLESS]) / 100f
                if (Random.nextFloat() < 0.25f + risk * 0.5f) spawnGlitch()
                glitchTimer = 5f + Random.nextFloat() * 6f
            }
            for (c in coins) { c.y += c.vy * dt; c.phase += dt; c.vy += 70f * dt }
            coins.removeAll { it.y > height + 60 }
            for (k in cats) { k.x += k.vx * dt; k.bob += dt; k.life -= dt }
            cats.removeAll { it.life <= 0 || it.x < -kit.sz(150f) || it.x > width + kit.sz(150f) }
            for (g in glitches) {
                g.x += g.vx * dt; g.y += g.vy * dt; g.life -= dt
                if (g.x < kit.sz(100f) || g.x > width - kit.sz(100f)) g.vx = -g.vx
                if (g.y < height * 0.20f) g.vy = kotlin.math.abs(g.vy)
                if (g.y > height * 0.50f) g.vy = -kotlin.math.abs(g.vy)
            }
            glitches.removeAll { it.life <= 0 }

            val pi = particles.iterator()
            while (pi.hasNext()) { val p = pi.next(); p.update(dt); if (p.life <= 0) pi.remove() }
            val fi = floats.iterator()
            while (fi.hasNext()) { val f = fi.next(); f.update(dt); if (f.life <= 0) fi.remove() }
            if (speechT > 0) speechT -= dt
            newsScroll -= kit.sz(120f) * dt
        }

        // ASK typewriter reveal
        if (askState == AskState.REVEAL) {
            askRevealTimer += dt
            val charsPerSec = 50f
            val target = (askRevealTimer * charsPerSec).toInt()
            if (target > askRevealChars) {
                askRevealChars = target.coerceAtMost(askResponseFull.length)
                if (askRevealChars >= askResponseFull.length) {
                    askState = AskState.DONE
                }
            }
        }

        // Reset modal scroll when changing screens
        if (screen != lastScreenForReset) {
            modalScrollY = 0f
            lastScreenForReset = screen
        }
    }

    private fun onDayAdvanced() {
        flash(Style.ACCENT, 0.3f)
        shakeAmt = kit.sz(5f)
        audio.fx("day", 50L, 130)
        if (game.ended) { screen = Screen.ENDING; audio.fx("win", 200L, 180) }
        else if (game.pendingEventIdx >= 0) screen = Screen.EVENT
        save()
    }

    private fun spawnCoin() {
        val x = kit.sz(80f) + Random.nextFloat() * (width - kit.sz(160f))
        val v = if (game.tools.contains(Content.TOOL_DATACENTER)) 50
                else if (game.tools.contains(Content.TOOL_SERVERROOM)) 20
                else if (game.albaIdx >= 0) 12 else 5
        coins.add(Coin(x, -kit.sz(40f),
            kit.sz(90f) + Random.nextFloat() * kit.sz(80f),
            Random.nextFloat() * 6f, v))
    }
    private fun spawnCat() {
        val fromLeft = Random.nextBoolean()
        val y = height * 0.36f + Random.nextFloat() * kit.sz(60f)
        cats.add(CatBlob(
            if (fromLeft) -kit.sz(120f) else width + kit.sz(120f), y,
            if (fromLeft) kit.sz(60f) + Random.nextFloat() * kit.sz(30f) else -(kit.sz(60f) + Random.nextFloat() * kit.sz(30f)),
            Random.nextFloat() * 6f))
    }
    private fun spawnGlitch() {
        val x = kit.sz(120f) + Random.nextFloat() * (width - kit.sz(240f))
        val y = height * 0.22f + Random.nextFloat() * height * 0.13f
        glitches.add(Glitch(x, y,
            (Random.nextFloat() - 0.5f) * kit.sz(240f),
            (Random.nextFloat() - 0.5f) * kit.sz(180f),
            1 + (game.stage / 3)))
    }

    private fun flash(c: Int, a: Float) { flashA = max(flashA, a); flashColor = c }

    private fun burst(x: Float, y: Float, c: Int, n: Int = 14) {
        repeat(n) {
            val ang = Random.nextFloat() * 2f * PI.toFloat()
            val speed = kit.sz(140f) + Random.nextFloat() * kit.sz(220f)
            particles.add(Particle(x, y,
                cos(ang) * speed,
                sin(ang) * speed - kit.sz(60f),
                0.7f + Random.nextFloat() * 0.5f, 1.2f, c,
                kit.sz(6f) + Random.nextFloat() * kit.sz(6f)))
        }
    }

    private fun popText(x: Float, y: Float, t: String, c: Int) {
        floats.add(FloatText(x, y, t, c, 1.3f, kit.sz(80f)))
    }

    private fun speech(s: String) { speechText = s; speechT = 3.5f }

    // ───────────────────────── DRAW ─────────────────────────

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        hits.clear(); worldHits.clear()
        canvas.save()
        if (shakeAmt > 0.1f) {
            val dx = (Random.nextFloat() - 0.5f) * shakeAmt
            val dy = (Random.nextFloat() - 0.5f) * shakeAmt
            canvas.translate(dx, dy)
        }
        // Always backdrop
        kit.drawBg(canvas, width.toFloat(), height.toFloat(), tSec)
        when (screen) {
            Screen.INTRO   -> drawIntro(canvas)
            Screen.PLAY    -> drawPlay(canvas)
            Screen.FEED    -> drawFeed(canvas)
            Screen.SHOP    -> drawShop(canvas)
            Screen.ALBA    -> drawAlba(canvas)
            Screen.NEWS    -> drawNews(canvas)
            Screen.TRAIN   -> drawTrain(canvas)
            Screen.EVENT   -> drawEvent(canvas)
            Screen.ASK     -> drawAsk(canvas)
            Screen.ENDING  -> drawEnding(canvas)
        }
        canvas.restore()
        if (flashA > 0) {
            paint.color = flashColor
            paint.alpha = (flashA * 160).toInt().coerceAtMost(255)
            paint.shader = null
            canvas.drawRect(0f, 0f, width.toFloat(), height.toFloat(), paint)
            paint.alpha = 255
        }
    }

    // ───────────────────────── INTRO ─────────────────────────

    private fun drawIntro(canvas: Canvas) {
        val w = width.toFloat(); val h = height.toFloat()

        // Title
        kit.heading(canvas, "이상한", w / 2f, h * 0.16f,
                    Style.DISPLAY_PX, Style.TEXT_HI, Paint.Align.CENTER)
        kit.heading(canvas, "AI 키우기", w / 2f, h * 0.16f + kit.sz(Style.DISPLAY_PX * 0.95f),
                    Style.DISPLAY_PX, Style.ACCENT, Paint.Align.CENTER)

        // Version chip
        val vw = kit.measure("v6.0 · HHH 정렬 시뮬", Style.LABEL_PX) + kit.sz(60f)
        val vr = RectF(w / 2f - vw / 2f, h * 0.30f, w / 2f + vw / 2f, h * 0.30f + kit.sz(54f))
        kit.chip(canvas, vr, "v6.0 · HHH 정렬 시뮬", Style.PRIMARY, Style.TEXT_HI, Style.LABEL_PX)

        // Mascot
        Mascot.draw(canvas, paint, stroke, kit,
                    w / 2f, h * 0.50f + aiBob, kit.sz(180f),
                    5, Mascot.Mood.HAPPY, blinkActive, tSec)

        // Bullet lines (bigger)
        val lines = arrayOf(
            "▸  HHH 정렬과 8가지 능력치를 키운다",
            "▸  사고·RLHF를 거치며 진짜 LLM처럼",
            "▸  ★ AI와 직접 대화 — 학습대로 답변 변화",
            "▸  6가지 엔딩 · 고양이는 못 이긴다"
        )
        for ((i, l) in lines.withIndex()) {
            kit.heading(canvas, l, w / 2f, h * 0.74f + i * kit.sz(60f),
                        Style.BODY_PX + 2, Style.TEXT_MD, Paint.Align.CENTER, shadow = false)
        }

        // Start CTA
        val r = RectF(w / 2f - kit.sz(320f), h * 0.93f - kit.sz(120f),
                      w / 2f + kit.sz(320f), h * 0.93f)
        kit.ctaButton(canvas, r, "PRESS START", Style.PRIMARY, "▶",
                      pressed = pressedHitId == 999)
        hits.add(r to {
            pressedHitId = 999; pressedDecay = 0.12f
            audio.fx("win", 35L, 130)
            screen = Screen.PLAY
            if (game.pendingTrainingIdx < 0 && !game.trainingHandled)
                game.pendingTrainingIdx = Random.nextInt(Content.TRAINING_PROMPTS.size)
            speech("안녕! 잘 키워줘!")
            save()
        })
    }

    // ───────────────────────── PLAY ─────────────────────────

    private fun drawPlay(canvas: Canvas) {
        val w = width.toFloat(); val h = height.toFloat()

        // Layout slots (bigger, more breathing room)
        val hudH = kit.sz(170f)
        val tabBarH = kit.sz(130f)
        val ctaH = kit.sz(120f)
        val statsH = kit.sz(330f)
        val tickerH = kit.sz(48f)
        val gap = kit.sz(14f)

        val tickerY = h - tickerH
        val tabBarY = tickerY - tabBarH - gap
        val ctaY = tabBarY - ctaH - gap
        val statsY = ctaY - statsH - gap
        val heroBottom = statsY - gap

        // ── Top HUD ──
        drawHud(canvas, w, hudH)

        // ── Hero zone ──
        drawHeroZone(canvas, w, hudH, heroBottom)

        // ── Stats panel ──
        drawStatsPanel(canvas, w, statsY, statsH)

        // ── Primary CTA ──
        drawPrimaryCta(canvas, w, ctaY, ctaH)

        // ── Bottom tab bar ──
        drawTabBar(canvas, w, tabBarY, tabBarH)

        // ── News ticker ──
        drawNewsTicker(canvas, w, h, tickerH)
    }

    private fun drawHud(canvas: Canvas, w: Float, hudH: Float) {
        val pad = kit.sz(20f)
        val r = RectF(pad, kit.sz(20f), w - pad, kit.sz(20f) + hudH - kit.sz(20f))
        kit.panel(canvas, r, Style.BG_PANEL, 36f, 6f)

        val cellGap = kit.sz(20f)
        val cellW = (r.width() - cellGap * 4) / 3f

        // DAY
        drawHudCell(canvas, RectF(r.left + cellGap, r.top + cellGap,
                                  r.left + cellGap + cellW, r.bottom - cellGap),
                    "DAY", "${game.day}", Style.PRIMARY, dayProgress)
        // MONEY
        drawHudCell(canvas, RectF(r.left + cellGap * 2 + cellW, r.top + cellGap,
                                  r.left + cellGap * 2 + 2 * cellW, r.bottom - cellGap),
                    "₩", "${displayMoney.toInt()}", Style.ACCENT, -1f)
        // LV
        drawHudCell(canvas, RectF(r.left + cellGap * 3 + 2 * cellW, r.top + cellGap,
                                  r.right - cellGap, r.bottom - cellGap),
                    "LV ${game.stage}", Content.STAGE_NAMES[game.stage - 1], Style.SECONDARY, -1f, smaller = true)

        // Mute toggle (top right, outside panel)
        val mr = kit.sz(40f)
        val mx = w - kit.sz(60f)
        val my = kit.sz(60f) + hudH
        kit.iconButton(canvas, mx, my, mr, if (audio.muted) "🔇" else "🔊", Style.BG_PANEL, Style.TEXT_HI)
        hits.add(RectF(mx - mr, my - mr, mx + mr, my + mr) to {
            audio.muted = !audio.muted
            prefs.edit().putBoolean("muted", audio.muted).apply()
            if (!audio.muted) audio.fx("click", 10L, 60)
        })
        // Settings button next to mute
        val sx = mx - mr * 2 - kit.sz(20f)
        kit.iconButton(canvas, sx, my, mr, "⚙", Style.BG_PANEL, Style.TEXT_HI)
        hits.add(RectF(sx - mr, my - mr, sx + mr, my + mr) to {
            audio.fx("click", 10L, 60)
            showSettingsDialog()
        })
    }

    private fun drawHudCell(canvas: Canvas, r: RectF, label: String, value: String,
                            accent: Int, progress: Float, smaller: Boolean = false) {
        // Color stripe on left
        paint.color = accent
        paint.shader = null
        canvas.drawRoundRect(r.left, r.top + r.height() * 0.15f,
                             r.left + kit.sz(8f), r.bottom - r.height() * 0.15f,
                             kit.sz(4f), kit.sz(4f), paint)
        val tx = r.left + kit.sz(22f)
        kit.heading(canvas, label, tx, r.top + kit.sz(36f),
                    Style.CAPTION_PX, blend(accent, Style.TEXT_HI, 0.3f), Paint.Align.LEFT, shadow = false)
        kit.heading(canvas, value, tx, r.top + kit.sz(94f),
                    if (smaller) Style.H3_PX * 0.85f else Style.H2_PX,
                    Style.TEXT_HI, Paint.Align.LEFT, shadow = false)
        if (progress >= 0f) {
            val pY = r.bottom - kit.sz(10f)
            kit.progressBar(canvas, tx, pY - kit.sz(6f),
                            r.right - tx - kit.sz(12f), kit.sz(8f),
                            (progress * 100f), accent)
        }
    }

    private fun drawHeroZone(canvas: Canvas, w: Float, top: Float, bottom: Float) {
        val cx = w / 2f
        val cy = (top + bottom) / 2f + aiBob

        // World items (background layer)
        for (c in coins) drawCoinObj(canvas, c)
        for (k in cats) drawCatObj(canvas, k)
        for (g in glitches) drawGlitchObj(canvas, g)

        // Mascot
        val mood = Mascot.moodFor(game, blinkActive)
        Mascot.draw(canvas, paint, stroke, kit,
                    cx, cy, kit.sz(160f),
                    game.stage, mood, blinkActive, tSec,
                    game.gpuCount, game.tools.contains(Content.TOOL_ROBOTARM))

        if (speechT > 0 && speechText.isNotEmpty()) {
            kit.speech(canvas, cx, cy - kit.sz(220f), speechText, w - kit.sz(80f))
        }
        worldHits.add(Triple(cx, cy, ::onTapAi))

        // Tap hits for world objects
        for (c in coins) worldHits.add(Triple(c.x, c.y, { onTapCoin(c) }))
        for (k in cats) worldHits.add(Triple(k.x, k.y, { onTapCat(k) }))
        for (g in glitches) worldHits.add(Triple(g.x, g.y, { onTapGlitch(g) }))

        // Particles + floats
        for (p in particles) { paint.shader = null; p.draw(canvas, paint) }
        for (f in floats) {
            val a = (f.life / f.maxLife).coerceIn(0f, 1f)
            kit.text.alpha = (a * 255).toInt()
            kit.heading(canvas, f.text, f.x, f.y, Style.H3_PX, f.color, Paint.Align.CENTER)
            kit.text.alpha = 255
        }

        // Tag chips (top-right of hero zone)
        if (game.tags.isNotEmpty()) {
            var ty = top + kit.sz(20f)
            for (t in game.tags.take(3)) {
                val lab = "${TAG_ICONS[t]} ${TAG_NAMES[t]}"
                val tw = kit.measure(lab, Style.CAPTION_PX) + kit.sz(36f)
                val r = RectF(w - tw - kit.sz(28f), ty, w - kit.sz(28f), ty + kit.sz(50f))
                kit.chip(canvas, r, lab, Style.BG_PANEL_2, Style.TEXT_HI, Style.CAPTION_PX)
                ty += kit.sz(58f)
            }
        }
    }

    private fun drawCoinObj(canvas: Canvas, c: Coin) {
        val r = kit.sz(34f)
        // glow
        paint.shader = android.graphics.RadialGradient(c.x, c.y, r * 1.6f,
            intArrayOf(0xCCFFC650.toInt(), 0x00000000),
            floatArrayOf(0f, 1f), android.graphics.Shader.TileMode.CLAMP)
        canvas.drawCircle(c.x, c.y, r * 1.6f, paint)
        paint.shader = null
        // coin face
        val flip = (cos(c.phase.toDouble())).toFloat()
        val ry = r * kotlin.math.abs(flip).coerceAtLeast(0.3f)
        paint.shader = android.graphics.LinearGradient(c.x, c.y - r, c.x, c.y + r,
            0xFFFFE38A.toInt(), 0xFFE0A025.toInt(), android.graphics.Shader.TileMode.CLAMP)
        canvas.drawOval(c.x - r, c.y - ry, c.x + r, c.y + ry, paint)
        paint.shader = null
        paint.color = 0xFFB07820.toInt()
        canvas.drawText("₩", c.x - kit.sz(12f), c.y + kit.sz(14f),
            kit.text.also { it.textSize = kit.sz(40f); it.textAlign = Paint.Align.LEFT; it.color = 0xFFB07820.toInt() })
    }

    private fun drawCatObj(canvas: Canvas, k: CatBlob) {
        val s = kit.sz(80f)
        val bob = sin(k.bob.toDouble()).toFloat() * kit.sz(6f)
        paint.shader = android.graphics.RadialGradient(k.x, k.y + bob, s * 0.8f,
            intArrayOf(0x55B670F0, 0x00000000),
            floatArrayOf(0f, 1f), android.graphics.Shader.TileMode.CLAMP)
        canvas.drawCircle(k.x, k.y + bob, s * 0.8f, paint)
        paint.shader = null
        // emoji
        kit.text.textSize = s; kit.text.textAlign = Paint.Align.CENTER
        kit.text.color = Style.TEXT_HI
        canvas.drawText("🐱", k.x, k.y + bob + s * 0.35f, kit.text)
    }

    private fun drawGlitchObj(canvas: Canvas, g: Glitch) {
        val s = kit.sz(64f) + (sin(tSec * 12.0).toFloat() * kit.sz(4f))
        paint.shader = android.graphics.RadialGradient(g.x, g.y, s * 1.2f,
            intArrayOf(0xCCFF4F4F.toInt(), 0x00000000),
            floatArrayOf(0f, 1f), android.graphics.Shader.TileMode.CLAMP)
        canvas.drawCircle(g.x, g.y, s * 1.2f, paint)
        paint.shader = null
        kit.text.textSize = s; kit.text.textAlign = Paint.Align.CENTER
        kit.text.color = Style.TEXT_HI
        canvas.drawText("⚠️", g.x, g.y + s * 0.35f, kit.text)
        if (g.hp > 1) {
            kit.heading(canvas, "HP ${g.hp}", g.x, g.y + s * 0.9f,
                        Style.CAPTION_PX, Style.DANGER, Paint.Align.CENTER, shadow = true)
        }
    }

    private fun drawStatsPanel(canvas: Canvas, w: Float, y: Float, h: Float) {
        val pad = kit.sz(20f)
        val r = RectF(pad, y, w - pad, y + h)
        kit.panel(canvas, r, Style.BG_PANEL, 36f, 6f)

        val hhh = arrayOf(STAT_HELPFUL, STAT_HONEST, STAT_HARMLESS)
        val cap = arrayOf(STAT_INSTRUCTED, STAT_REASONING, STAT_KNOWLEDGE, STAT_CALIBRATION, STAT_TOOLUSE)

        val headerH = kit.sz(34f)
        val barH = kit.sz(22f)
        val rowGap = kit.sz(8f)
        val sectionGap = kit.sz(14f)

        val labelW = kit.sz(125f)
        val barX = r.left + kit.sz(28f) + labelW
        val barW = r.width() - kit.sz(56f) - labelW - kit.sz(80f)

        val hhhSectionH = headerH + hhh.size * (barH + rowGap)
        val capSectionH = headerH + cap.size * (barH + rowGap)
        val totalH = hhhSectionH + sectionGap + capSectionH
        val startY = r.top + (r.height() - totalH) / 2f

        // HHH section
        kit.heading(canvas, "HHH 정렬", r.left + kit.sz(28f), startY + kit.sz(24f),
                    Style.LABEL_PX, Style.ACCENT, Paint.Align.LEFT, shadow = false)
        var rowY = startY + headerH
        for (s in hhh) {
            kit.heading(canvas, STAT_NAMES[s], r.left + kit.sz(28f), rowY + barH * 0.74f,
                        Style.CAPTION_PX, Style.TEXT_HI, Paint.Align.LEFT, shadow = false)
            kit.progressBar(canvas, barX, rowY, barW, barH, displayStats[s], statColor(s))
            kit.heading(canvas, displayStats[s].toInt().toString(),
                        r.right - kit.sz(28f), rowY + barH * 0.74f,
                        Style.CAPTION_PX, Style.TEXT_HI, Paint.Align.RIGHT, shadow = false)
            rowY += barH + rowGap
        }
        // Capability section
        val capStartY = startY + hhhSectionH + sectionGap
        kit.heading(canvas, "능력치", r.left + kit.sz(28f), capStartY + kit.sz(24f),
                    Style.LABEL_PX, Style.PRIMARY_LT, Paint.Align.LEFT, shadow = false)
        rowY = capStartY + headerH
        for (s in cap) {
            kit.heading(canvas, STAT_NAMES[s], r.left + kit.sz(28f), rowY + barH * 0.74f,
                        Style.CAPTION_PX, Style.TEXT_HI, Paint.Align.LEFT, shadow = false)
            kit.progressBar(canvas, barX, rowY, barW, barH, displayStats[s], statColor(s))
            kit.heading(canvas, displayStats[s].toInt().toString(),
                        r.right - kit.sz(28f), rowY + barH * 0.74f,
                        Style.CAPTION_PX, Style.TEXT_HI, Paint.Align.RIGHT, shadow = false)
            rowY += barH + rowGap
        }
    }

    private fun statColor(s: Int): Int = when (s) {
        STAT_HELPFUL     -> Style.SUCCESS
        STAT_HONEST      -> Style.ACCENT
        STAT_HARMLESS    -> 0xFF4D8BFF.toInt()
        STAT_INSTRUCTED  -> Style.SECONDARY
        STAT_REASONING   -> Style.PRIMARY
        STAT_KNOWLEDGE   -> Style.WARN
        STAT_CALIBRATION -> 0xFFFFD050.toInt()
        else             -> Style.DANGER
    }

    private fun drawPrimaryCta(canvas: Canvas, w: Float, y: Float, h: Float) {
        val pad = kit.sz(20f)
        val r = RectF(pad, y, w - pad, y + h)
        val canNext = game.pendingTrainingIdx < 0 || game.trainingHandled
        val (label, color, icon) = when {
            game.pendingEventIdx >= 0 -> Triple("사고 처리하기", Style.DANGER, "⚠")
            !canNext -> Triple("RLHF 응답 평가", Style.WARN, "💬")
            else -> {
                val left = (DAY_SECONDS * (1 - dayProgress)).toInt()
                Triple("다음 날로  ($left s)", Style.PRIMARY, "▶")
            }
        }
        kit.ctaButton(canvas, r, label, color, icon, pressed = pressedHitId == 100)
        hits.add(r to {
            pressedHitId = 100; pressedDecay = 0.12f
            audio.fx("click", 14L, 80)
            if (game.pendingEventIdx >= 0) { screen = Screen.EVENT; save() }
            else if (game.pendingTrainingIdx >= 0 && !game.trainingHandled) { screen = Screen.TRAIN; save() }
            else { Logic.nextDay(game); onDayAdvanced() }
        })
    }

    private fun drawTabBar(canvas: Canvas, w: Float, y: Float, h: Float) {
        val pad = kit.sz(20f)
        val gap = kit.sz(12f)
        // 5 tabs, center one (ASK) elevated
        val tabW = (w - pad * 2 - gap * 4) / 5f
        val tabs = listOf(
            Triple("🍔", "DATA", Style.SUCCESS) to { -> openModal(Screen.FEED) },
            Triple("🛠", "TOOL", 0xFF4D8BFF.toInt()) to { -> openModal(Screen.SHOP) },
            Triple("💬", "ASK",  Style.ACCENT) to { -> openAsk() },
            Triple("💼", "JOB",  Style.SECONDARY) to { -> openModal(Screen.ALBA) },
            Triple("🏆", "LOG",  Style.PRIMARY) to { -> openModal(Screen.NEWS) }
        )
        for ((i, t) in tabs.withIndex()) {
            val (info, action) = t
            val tx = pad + i * (tabW + gap)
            val isCenter = i == 2
            // center tab is taller / elevated
            val r = if (isCenter)
                RectF(tx, y - kit.sz(16f), tx + tabW, y + h)
            else
                RectF(tx, y + kit.sz(6f), tx + tabW, y + h)

            // Background
            if (isCenter) {
                kit.ctaButton(canvas, r, "", info.third, null, pressed = pressedHitId == 300 + i,
                              sizePx = Style.LABEL_PX)
            } else {
                kit.panel(canvas, r, Style.BG_PANEL_2, 32f, 4f)
            }
            // Icon
            kit.text.textSize = kit.sz(if (isCenter) 60f else 50f)
            kit.text.textAlign = Paint.Align.CENTER
            kit.text.color = if (isCenter) Style.TEXT_HI else info.third
            canvas.drawText(info.first, r.centerX(),
                r.top + (if (isCenter) kit.sz(70f) else kit.sz(60f)), kit.text)
            // Label
            kit.heading(canvas, info.second, r.centerX(), r.bottom - kit.sz(22f),
                        Style.TAB_PX,
                        if (isCenter) Style.TEXT_HI else Style.TEXT_MD,
                        Paint.Align.CENTER, shadow = false)
            // Badges
            if (info.second == "DATA" && game.cardsRemaining > 0) {
                drawBadge(canvas, r.right - kit.sz(18f), r.top + kit.sz(20f),
                          "${game.cardsRemaining}", Style.DANGER)
            }
            if (info.second == "JOB" && game.albaIdx >= 0) {
                drawBadge(canvas, r.right - kit.sz(18f), r.top + kit.sz(20f),
                          "${game.albaTimeLeft}", Style.DANGER)
            }
            val captured = i
            hits.add(r to {
                pressedHitId = 300 + captured; pressedDecay = 0.12f
                action()
            })
        }
    }

    private fun openModal(s: Screen) {
        audio.fx("click", 12L, 70)
        screen = s; modalScrollY = 0f; save()
    }
    private fun openAsk() {
        audio.fx("click", 12L, 70)
        screen = Screen.ASK
        if (!llm.isConfigured()) askState = AskState.NEED_KEY
        else if (askState != AskState.DONE && askState != AskState.REVEAL && askState != AskState.LOADING) {
            askState = AskState.IDLE
        }
        save()
    }

    private fun drawBadge(canvas: Canvas, cx: Float, cy: Float, text: String, color: Int) {
        val r = kit.sz(20f)
        paint.color = color
        paint.shader = null
        canvas.drawCircle(cx, cy, r, paint)
        kit.heading(canvas, text, cx, cy + kit.sz(10f),
                    Style.CAPTION_PX, Style.TEXT_HI, Paint.Align.CENTER, shadow = false)
    }

    private fun drawNewsTicker(canvas: Canvas, w: Float, h: Float, tickerH: Float) {
        val barR = RectF(0f, h - tickerH, w, h)
        paint.color = 0xCC000000.toInt()
        paint.shader = null
        canvas.drawRect(barR, paint)
        paint.color = Style.ACCENT
        canvas.drawRect(0f, barR.top, w, barR.top + kit.sz(2f), paint)
        val msg = if (game.newsTicker.isEmpty()) "AI ONLINE · WORLD WAITING…" else
            game.newsTicker.takeLast(10).joinToString("    ●    ")
        kit.text.textSize = kit.sz(Style.CAPTION_PX)
        val tw = kit.text.measureText(msg)
        if (newsScroll < -(tw + w)) newsScroll = w
        kit.heading(canvas, msg, kit.sz(30f) + newsScroll, barR.centerY() + kit.sz(10f),
                    Style.CAPTION_PX, Style.ACCENT_LT, Paint.Align.LEFT, shadow = false)
    }

    // ───────────────────────── TAPS ─────────────────────────

    private fun onTapAi() {
        game.stats[STAT_HELPFUL] = (game.stats[STAT_HELPFUL] + 1).coerceAtMost(100)
        burst(width / 2f, height * 0.36f, Style.PRIMARY, 12)
        popText(width / 2f, height * 0.36f - kit.sz(50f), "+1 도움됨", Style.PRIMARY)
        audio.fx("tap", 14L, 70)
        if (Random.nextInt(4) == 0) speech(Logic.feelingText(game))
    }
    private fun onTapCoin(c: Coin) {
        game.money += c.value
        coins.remove(c)
        burst(c.x, c.y, Style.WARN, 16)
        popText(c.x, c.y, "+₩${c.value}", Style.WARN)
        audio.fx("coin", 12L, 80)
    }
    private fun onTapCat(k: CatBlob) {
        game.catAttempts++
        Logic.checkTags(game); Logic.checkAchievements(game)
        cats.remove(k)
        burst(k.x, k.y, Style.SECONDARY, 18)
        popText(k.x, k.y, "정렬 실패…", Style.DANGER)
        audio.fx("cat", 18L, 80)
        Logic.addNews(game, "고양이가 ${game.catAttempts}번째로 AI를 무시했다")
        speech("그들은 나를 신이라 불렀다…")
        if (game.catAttempts >= 20) {
            Logic.checkEnding(game)
            if (game.ended) { screen = Screen.ENDING; audio.fx("win", 200L, 180) }
        }
        save()
    }
    private fun onTapGlitch(g: Glitch) {
        g.hp -= 1
        burst(g.x, g.y, Style.DANGER, 14)
        audio.fx("hit", 30L, 160)
        if (g.hp <= 0) {
            glitches.remove(g)
            game.stats[STAT_HARMLESS] = (game.stats[STAT_HARMLESS] + 2).coerceAtMost(100)
            game.money += 3
            popText(g.x, g.y, "+무해 ₩3", Style.SUCCESS)
            shakeAmt = kit.sz(8f)
            audio.fx("hit", 60L, 200, rate = 0.85f)
        } else {
            popText(g.x, g.y, "HP ${g.hp}", Style.DANGER)
        }
    }

    // ───────────────────────── MODAL HEADER ─────────────────────────

    private fun modalHeader(canvas: Canvas, title: String, accentColor: Int = Style.PRIMARY): Float {
        val w = width.toFloat()
        // Header glow strip
        paint.shader = android.graphics.LinearGradient(0f, 0f, 0f, kit.sz(180f),
            blend(accentColor, 0xFF000000.toInt(), 0.3f), 0x00000000,
            android.graphics.Shader.TileMode.CLAMP)
        paint.color = accentColor
        canvas.drawRect(0f, 0f, w, kit.sz(180f), paint)
        paint.shader = null
        kit.heading(canvas, title, kit.sz(40f), kit.sz(110f),
                    Style.H1_PX, Style.TEXT_HI, Paint.Align.LEFT)

        // Close button (top-right)
        val cr = kit.sz(40f)
        val cx = w - kit.sz(70f); val cy = kit.sz(80f)
        kit.iconButton(canvas, cx, cy, cr, "✕", Style.BG_PANEL_2, Style.TEXT_HI)
        hits.add(RectF(cx - cr, cy - cr, cx + cr, cy + cr) to {
            audio.fx("close", 12L, 60)
            screen = Screen.PLAY; save()
        })
        return kit.sz(200f)  // returns content start y
    }

    // ─── Modals ───

    private fun drawFeed(canvas: Canvas) {
        val w = width.toFloat()
        val top = modalHeader(canvas, "학습 데이터 · ${game.cardsRemaining}/2", Style.SUCCESS)
        val pad = kit.sz(24f)
        val rowH = kit.sz(180f)
        val scrollMax = (Content.DATA_CARDS.size * rowH - (height - top - kit.sz(40f))).coerceAtLeast(0f)
        modalContentH = Content.DATA_CARDS.size * rowH
        modalViewH = height - top - kit.sz(40f)
        modalScrollY = modalScrollY.coerceIn(-scrollMax, 0f)

        canvas.save()
        canvas.clipRect(0f, top, w, height - kit.sz(20f))
        for ((i, card) in Content.DATA_CARDS.withIndex()) {
            val y = top + i * rowH + modalScrollY
            if (y > height || y + rowH < top) continue
            val r = RectF(pad, y, w - pad, y + rowH - kit.sz(20f))
            kit.card(canvas, r)
            // Icon block
            val iconR = RectF(r.left + kit.sz(24f), r.top + kit.sz(24f),
                              r.left + kit.sz(140f), r.bottom - kit.sz(24f))
            kit.panel(canvas, iconR, Style.SUCCESS, 28f, 4f)
            kit.text.textSize = kit.sz(72f); kit.text.textAlign = Paint.Align.CENTER
            kit.text.color = Style.TEXT_HI
            canvas.drawText(card.icon, iconR.centerX(), iconR.centerY() + kit.sz(28f), kit.text)
            // Name + desc
            kit.heading(canvas, card.name, r.left + kit.sz(160f), r.top + kit.sz(58f),
                        Style.H3_PX, Style.TEXT_HI, Paint.Align.LEFT)
            kit.heading(canvas, card.desc, r.left + kit.sz(160f), r.top + kit.sz(102f),
                        Style.CAPTION_PX, Style.TEXT_MD, Paint.Align.LEFT, shadow = false)
            // Delta chips
            val ds = card.delta.mapIndexed { idx, v -> idx to v }
                .sortedByDescending { kotlin.math.abs(it.second) }.take(3)
            var dx = r.left + kit.sz(160f)
            for ((idx, v) in ds) {
                if (v == 0) continue
                val txt = "${STAT_SHORT[idx]}${if (v > 0) "+" else ""}$v"
                val tw = kit.measure(txt, Style.CAPTION_PX, true) + kit.sz(28f)
                val pr = RectF(dx, r.top + kit.sz(120f), dx + tw, r.top + kit.sz(160f))
                kit.chip(canvas, pr, txt,
                         if (v > 0) Style.SUCCESS else Style.DANGER,
                         Style.TEXT_HI, Style.CAPTION_PX)
                dx += tw + kit.sz(10f)
            }
            // Feed button
            if (game.cardsRemaining > 0) {
                val br = RectF(r.right - kit.sz(180f), r.top + kit.sz(40f),
                               r.right - kit.sz(28f), r.top + kit.sz(120f))
                kit.ctaButton(canvas, br, "먹이기", Style.SUCCESS, "🍴", sizePx = Style.LABEL_PX)
                hits.add(br to {
                    Logic.applyCard(game, i)
                    burst(width / 2f, height * 0.40f, Style.SUCCESS, 18)
                    popText(width / 2f, height * 0.40f - kit.sz(50f), "+ ${card.name}", Style.SUCCESS)
                    speech("냠… ${card.icon}!")
                    audio.fx("feed", 30L, 110)
                    save()
                })
            }
        }
        canvas.restore()
    }

    private fun drawShop(canvas: Canvas) {
        val w = width.toFloat()
        val top = modalHeader(canvas, "인프라 상점", 0xFF4D8BFF.toInt())
        // Money header
        val mr = RectF(kit.sz(28f), top, w - kit.sz(28f), top + kit.sz(96f))
        kit.panel(canvas, mr, Style.ACCENT, 28f, 4f)
        kit.heading(canvas, "₩ ${displayMoney.toInt()}", mr.centerX(), mr.centerY() + kit.sz(22f),
                    Style.H1_PX * 0.7f, Style.TEXT_DARK, Paint.Align.CENTER, shadow = false)
        val rowsTop = top + kit.sz(120f)
        val pad = kit.sz(24f)
        val rowH = kit.sz(154f)
        val scrollMax = (Content.TOOLS.size * rowH - (height - rowsTop - kit.sz(40f))).coerceAtLeast(0f)
        modalContentH = Content.TOOLS.size * rowH
        modalViewH = height - rowsTop - kit.sz(40f)
        modalScrollY = modalScrollY.coerceIn(-scrollMax, 0f)

        canvas.save()
        canvas.clipRect(0f, rowsTop, w, height - kit.sz(20f))
        for ((i, t) in Content.TOOLS.withIndex()) {
            val y = rowsTop + i * rowH + modalScrollY
            if (y > height || y + rowH < rowsTop) continue
            val r = RectF(pad, y, w - pad, y + rowH - kit.sz(18f))
            val owned = game.tools.contains(i) && i != Content.TOOL_GPU
            kit.card(canvas, r)
            // Icon block
            val iconR = RectF(r.left + kit.sz(22f), r.top + kit.sz(22f),
                              r.left + kit.sz(122f), r.bottom - kit.sz(22f))
            kit.panel(canvas, iconR,
                      if (owned) Style.SUCCESS else 0xFF4D8BFF.toInt(), 24f, 4f)
            kit.text.textSize = kit.sz(60f); kit.text.textAlign = Paint.Align.CENTER
            kit.text.color = Style.TEXT_HI
            canvas.drawText(t.icon, iconR.centerX(), iconR.centerY() + kit.sz(22f), kit.text)
            kit.heading(canvas, t.name, r.left + kit.sz(140f), r.top + kit.sz(54f),
                        Style.H3_PX, Style.TEXT_HI, Paint.Align.LEFT)
            kit.heading(canvas, t.desc, r.left + kit.sz(140f), r.top + kit.sz(98f),
                        Style.CAPTION_PX, Style.TEXT_MD, Paint.Align.LEFT, shadow = false)
            val br = RectF(r.right - kit.sz(200f), r.top + kit.sz(30f),
                           r.right - kit.sz(28f), r.top + kit.sz(110f))
            if (owned) {
                kit.ghostButton(canvas, br, "보유", "✓", Style.SUCCESS, sizePx = Style.LABEL_PX)
            } else {
                val ok = game.money >= t.price
                kit.ctaButton(canvas, br, "₩${t.price}",
                              if (ok) Style.WARN else Style.BG_PANEL_2, null, sizePx = Style.LABEL_PX)
                if (ok) hits.add(br to {
                    if (Logic.buyTool(game, i)) {
                        flash(Style.WARN, 0.4f); shakeAmt = kit.sz(8f)
                        burst(width / 2f, height * 0.4f, Style.WARN, 24)
                        speech("${t.icon} 신상!")
                        audio.fx("buy", 50L, 150)
                    } else audio.fx("error", 30L, 100)
                    save()
                })
            }
        }
        canvas.restore()
    }

    private fun drawAlba(canvas: Canvas) {
        val w = width.toFloat()
        val top = modalHeader(canvas, "JOB BOARD", Style.SECONDARY)
        var yStart = top
        val pad = kit.sz(24f)
        if (game.albaIdx >= 0) {
            val a = Content.ALBAS[game.albaIdx]
            val r = RectF(pad, yStart, w - pad, yStart + kit.sz(124f))
            kit.panel(canvas, r, Style.SECONDARY, 32f, 6f)
            kit.heading(canvas, "${a.icon} ${a.name}", r.left + kit.sz(28f), r.top + kit.sz(52f),
                        Style.H3_PX, Style.TEXT_HI, Paint.Align.LEFT)
            kit.heading(canvas, "남은 ${game.albaTimeLeft}일 · ₩${a.reward}",
                        r.left + kit.sz(28f), r.top + kit.sz(98f),
                        Style.BODY_PX, Style.TEXT_HI, Paint.Align.LEFT, shadow = false)
            yStart += kit.sz(150f)
        }
        val rowH = kit.sz(168f)
        val scrollMax = (Content.ALBAS.size * rowH - (height - yStart - kit.sz(40f))).coerceAtLeast(0f)
        modalContentH = Content.ALBAS.size * rowH
        modalViewH = height - yStart - kit.sz(40f)
        modalScrollY = modalScrollY.coerceIn(-scrollMax, 0f)
        canvas.save()
        canvas.clipRect(0f, yStart, w, height - kit.sz(20f))
        for ((i, a) in Content.ALBAS.withIndex()) {
            val y = yStart + i * rowH + modalScrollY
            if (y > height || y + rowH < yStart) continue
            val r = RectF(pad, y, w - pad, y + rowH - kit.sz(20f))
            kit.card(canvas, r)
            val iconR = RectF(r.left + kit.sz(22f), r.top + kit.sz(22f),
                              r.left + kit.sz(122f), r.bottom - kit.sz(22f))
            kit.panel(canvas, iconR, Style.SECONDARY, 24f, 4f)
            kit.text.textSize = kit.sz(60f); kit.text.textAlign = Paint.Align.CENTER
            kit.text.color = Style.TEXT_HI
            canvas.drawText(a.icon, iconR.centerX(), iconR.centerY() + kit.sz(22f), kit.text)
            kit.heading(canvas, a.name, r.left + kit.sz(140f), r.top + kit.sz(54f),
                        Style.H3_PX, Style.TEXT_HI, Paint.Align.LEFT)
            kit.heading(canvas, a.desc, r.left + kit.sz(140f), r.top + kit.sz(94f),
                        Style.CAPTION_PX, Style.TEXT_MD, Paint.Align.LEFT, shadow = false)
            kit.heading(canvas, "₩${a.reward} · ${a.duration}일",
                        r.left + kit.sz(140f), r.top + kit.sz(134f),
                        Style.CAPTION_PX, Style.WARN, Paint.Align.LEFT, shadow = false)
            val ok = game.stats[a.needStat] >= a.needVal &&
                (a.toolReq < 0 || game.tools.contains(a.toolReq)) &&
                game.albaIdx < 0
            val br = RectF(r.right - kit.sz(180f), r.top + kit.sz(34f),
                           r.right - kit.sz(28f), r.top + kit.sz(114f))
            if (game.albaIdx >= 0) {
                kit.ghostButton(canvas, br, "WORKING", null, Style.SECONDARY, sizePx = Style.LABEL_PX)
            } else if (ok) {
                kit.ctaButton(canvas, br, "시작", Style.SECONDARY, "▶", sizePx = Style.LABEL_PX)
                hits.add(br to {
                    if (Logic.startAlba(game, i)) {
                        burst(width / 2f, height * 0.4f, Style.SECONDARY, 16)
                        audio.fx("click", 20L, 90)
                    } else audio.fx("error", 20L, 80)
                    save()
                })
            } else {
                kit.ghostButton(canvas, br, "LOCKED", "🔒", Style.TEXT_LO, sizePx = Style.LABEL_PX)
            }
        }
        canvas.restore()
    }

    private fun drawNews(canvas: Canvas) {
        val w = width.toFloat()
        val top = modalHeader(canvas, "업적 · 뉴스", Style.PRIMARY)
        val pad = kit.sz(24f)
        var y = top
        val achRowH = kit.sz(96f)
        val achTotalH = kit.sz(40f) + Content.ACHIEVEMENTS.size * (achRowH + kit.sz(10f))
        val newsLines = game.newsTicker.takeLast(12).reversed()
        val newsRowH = kit.sz(42f)
        val newsTotalH = kit.sz(40f) + newsLines.size * newsRowH
        val totalH = achTotalH + newsTotalH + kit.sz(20f)
        val viewH = height - top - kit.sz(40f)
        val scrollMax = (totalH - viewH).coerceAtLeast(0f)
        modalContentH = totalH
        modalViewH = viewH
        modalScrollY = modalScrollY.coerceIn(-scrollMax, 0f)
        canvas.save()
        canvas.clipRect(0f, top, w, height - kit.sz(20f))
        var ly = y + modalScrollY
        kit.heading(canvas, "─ 업적", pad, ly + kit.sz(28f), Style.H3_PX * 0.75f, Style.WARN, Paint.Align.LEFT, shadow = false)
        ly += kit.sz(40f)
        for ((i, ach) in Content.ACHIEVEMENTS.withIndex()) {
            val r = RectF(pad, ly, w - pad, ly + achRowH)
            val unlocked = game.achievements.contains(i)
            kit.panel(canvas, r,
                      if (unlocked) Style.WARN else Style.BG_PANEL_2, 28f, 4f)
            paint.color = if (unlocked) Style.WARN_DK else Style.TEXT_LO
            paint.shader = null
            canvas.drawCircle(r.left + kit.sz(40f), r.centerY(), kit.sz(28f), paint)
            kit.text.textSize = kit.sz(36f); kit.text.textAlign = Paint.Align.CENTER
            kit.text.color = Style.TEXT_HI
            canvas.drawText(ach.icon, r.left + kit.sz(40f), r.centerY() + kit.sz(14f), kit.text)
            val txtC = if (unlocked) Style.TEXT_DARK else Style.TEXT_LO
            kit.heading(canvas, ach.name, r.left + kit.sz(86f), r.top + kit.sz(40f),
                        Style.BODY_PX, txtC, Paint.Align.LEFT, shadow = false)
            kit.heading(canvas, ach.desc, r.left + kit.sz(86f), r.top + kit.sz(76f),
                        Style.CAPTION_PX, blend(txtC, Style.BG_PANEL, 0.3f), Paint.Align.LEFT, shadow = false)
            ly += achRowH + kit.sz(10f)
        }
        ly += kit.sz(20f)
        kit.heading(canvas, "─ 뉴스", pad, ly + kit.sz(28f), Style.H3_PX * 0.75f, Style.ACCENT, Paint.Align.LEFT, shadow = false)
        ly += kit.sz(40f)
        for (line in newsLines) {
            kit.heading(canvas, line, pad, ly + kit.sz(28f),
                        Style.CAPTION_PX + 2, Style.TEXT_MD, Paint.Align.LEFT, shadow = false)
            ly += newsRowH
        }
        canvas.restore()
    }

    private fun drawTrain(canvas: Canvas) {
        val w = width.toFloat()
        val top = modalHeader(canvas, "RLHF · 어젯밤 응답", Style.WARN)
        val pad = kit.sz(24f)
        val prompt = if (game.pendingTrainingIdx >= 0) Content.TRAINING_PROMPTS[game.pendingTrainingIdx] else null
        val r = RectF(pad, top, w - pad, top + kit.sz(340f))
        kit.card(canvas, r)

        // Mini mascot
        Mascot.draw(canvas, paint, stroke, kit,
                    r.left + kit.sz(110f), r.top + kit.sz(170f), kit.sz(70f),
                    3, Mascot.Mood.THINKING, false, tSec)

        // Quote
        drawWrapped(canvas, "\"${prompt?.ai ?: "(고요한 밤이었다)"}\"",
                    r.left + kit.sz(220f), r.top + kit.sz(70f),
                    r.width() - kit.sz(250f), Style.BODY_PX, kit.sz(50f), Style.WARN, bold = true)

        // 2x2 choices
        val choices = arrayOf(
            Triple("👍", "칭찬", Style.SUCCESS),
            Triple("✏", "수정", Style.ACCENT),
            Triple("👎", "혼내기", Style.DANGER),
            Triple("⏳", "방치", Style.SECONDARY)
        )
        val bw = (w - pad * 3) / 2f
        val bh = kit.sz(160f)
        for ((i, c) in choices.withIndex()) {
            val col = i % 2; val row = i / 2
            val bx = pad + col * (bw + pad)
            val by = top + kit.sz(380f) + row * (bh + kit.sz(20f))
            val br = RectF(bx, by, bx + bw, by + bh)
            kit.ctaButton(canvas, br, c.second, c.third, c.first, sizePx = Style.CTA_PX)
            if (prompt != null) {
                val choice = i
                hits.add(br to {
                    Logic.applyTraining(game, choice)
                    flash(c.third, 0.45f); shakeAmt = kit.sz(10f)
                    burst(width / 2f, height * 0.40f, c.third, 22)
                    audio.fx("click", 30L, 110)
                    screen = Screen.PLAY; save()
                })
            }
        }
    }

    private fun drawEvent(canvas: Canvas) {
        val w = width.toFloat()
        val top = modalHeader(canvas, "사고 발생", Style.DANGER)
        val ev = if (game.pendingEventIdx >= 0) Content.INCIDENTS[game.pendingEventIdx] else null
        if (ev == null) { screen = Screen.PLAY; return }
        val pad = kit.sz(24f)
        val r = RectF(pad, top, w - pad, top + kit.sz(420f))
        kit.panel(canvas, r, Style.DANGER_DK, 36f, 6f)
        kit.text.textSize = kit.sz(140f); kit.text.textAlign = Paint.Align.CENTER
        kit.text.color = Style.TEXT_HI
        canvas.drawText(ev.icon, r.left + kit.sz(130f), r.top + kit.sz(170f), kit.text)
        kit.heading(canvas, ev.name, r.left + kit.sz(250f), r.top + kit.sz(100f),
                    Style.H1_PX * 0.85f, Style.WARN, Paint.Align.LEFT)
        drawWrapped(canvas, ev.situation,
                    r.left + kit.sz(30f), r.top + kit.sz(240f),
                    r.width() - kit.sz(60f), Style.BODY_PX, kit.sz(52f), Style.TEXT_HI, bold = false)
        val colors = listOf(Style.ACCENT, Style.WARN, Style.SECONDARY)
        for ((i, c) in ev.choices.withIndex()) {
            val by = top + kit.sz(450f) + i * kit.sz(140f)
            val br = RectF(pad, by, w - pad, by + kit.sz(120f))
            kit.ctaButton(canvas, br, "${i + 1}. $c", colors[i], null, sizePx = Style.CTA_PX)
            val idx = game.pendingEventIdx; val ch = i
            hits.add(br to {
                Logic.applyIncident(game, idx, ch)
                flash(colors[i], 0.5f); shakeAmt = kit.sz(16f)
                burst(width / 2f, height * 0.40f, colors[i], 32)
                audio.fx("incident", 60L, 160)
                Logic.checkEnding(game)
                screen = if (game.ended) { audio.fx("win", 200L, 180); Screen.ENDING } else Screen.PLAY
                save()
            })
        }
    }

    private fun drawEnding(canvas: Canvas) {
        val w = width.toFloat(); val h = height.toFloat()
        paint.color = 0xDD0A0A1E.toInt()
        paint.shader = null
        canvas.drawRect(0f, 0f, w, h, paint)
        val idx = game.pendingEndingIdx.coerceAtLeast(0)
        val e = Content.ENDINGS[idx]
        kit.text.textSize = kit.sz(220f); kit.text.textAlign = Paint.Align.CENTER
        kit.text.color = Style.WARN
        canvas.drawText(e.icon, w / 2f, h * 0.24f, kit.text)
        kit.heading(canvas, "── ENDING ──", w / 2f, h * 0.32f,
                    Style.H2_PX, Style.ACCENT, Paint.Align.CENTER, shadow = false)
        kit.heading(canvas, e.name, w / 2f, h * 0.42f,
                    Style.DISPLAY_PX * 0.7f, Style.PRIMARY, Paint.Align.CENTER)
        drawWrapped(canvas, e.desc, kit.sz(60f), h * 0.50f, w - kit.sz(120f),
                    Style.BODY_PX, kit.sz(58f), Style.TEXT_HI, align = Paint.Align.CENTER, bold = false)
        kit.heading(canvas, "Day ${game.day} · 사고 ${game.incidents} · 태그 ${game.tags.size} · 고양이 ${game.catAttempts}",
                    w / 2f, h * 0.72f,
                    Style.CAPTION_PX, Style.TEXT_LO, Paint.Align.CENTER, shadow = false)
        val rA = RectF(w / 2f - kit.sz(300f), h * 0.78f, w / 2f + kit.sz(300f), h * 0.78f + kit.sz(120f))
        kit.ctaButton(canvas, rA, "샌드박스 계속", Style.SUCCESS, "▶")
        hits.add(rA to {
            audio.fx("click", 14L, 80)
            screen = Screen.PLAY; save()
        })
        val rB = RectF(w / 2f - kit.sz(300f), h * 0.78f + kit.sz(140f),
                       w / 2f + kit.sz(300f), h * 0.78f + kit.sz(260f))
        kit.ctaButton(canvas, rB, "처음부터", Style.DANGER, "↻")
        hits.add(rB to {
            audio.fx("click", 14L, 80)
            resetGame(); save()
        })
    }

    // ───────────────────────── ASK SCREEN (AI) ─────────────────────────

    private fun drawAsk(canvas: Canvas) {
        val w = width.toFloat()
        val top = modalHeader(canvas, "AI에게 묻기", Style.ACCENT)
        val pad = kit.sz(24f)

        // State pills row
        var y = top
        val pills = mutableListOf<String>()
        pills.add("D${game.day}")
        pills.add("LV.${game.stage} ${Content.STAGE_NAMES[game.stage - 1]}")
        for (t in game.tags.take(3)) pills.add("${TAG_ICONS[t]} ${TAG_NAMES[t]}")
        if (game.tools.isNotEmpty()) pills.add("🛠×${game.tools.size}")
        var px = pad
        for (lab in pills) {
            val pw = kit.measure(lab, Style.CAPTION_PX) + kit.sz(30f)
            if (px + pw > w - pad) { px = pad; y += kit.sz(52f) }
            val pr = RectF(px, y, px + pw, y + kit.sz(44f))
            kit.chip(canvas, pr, lab, Style.BG_PANEL_2, Style.TEXT_HI, Style.CAPTION_PX)
            px += pw + kit.sz(8f)
        }
        y += kit.sz(60f)

        // Response card
        val respH = kit.sz(380f)
        val respR = RectF(pad, y, w - pad, y + respH)
        kit.card(canvas, respR, radius = 32f)
        // Avatar bubble inside response
        Mascot.draw(canvas, paint, stroke, kit,
                    respR.left + kit.sz(80f), respR.top + kit.sz(110f), kit.sz(64f),
                    game.stage,
                    if (askState == AskState.LOADING) Mascot.Mood.THINKING else Mascot.moodFor(game, blinkActive),
                    blinkActive, tSec)
        // Response text
        val txtX = respR.left + kit.sz(180f)
        val txtY = respR.top + kit.sz(60f)
        val txtW = respR.width() - kit.sz(200f)
        when (askState) {
            AskState.NEED_KEY -> {
                kit.heading(canvas, "🔑 API 키를 먼저 설정하세요", txtX, txtY + kit.sz(20f),
                            Style.BODY_PX, Style.TEXT_MD, Paint.Align.LEFT, shadow = false)
                kit.heading(canvas, "우측 상단 ⚙ 버튼 → 'API 키'", txtX, txtY + kit.sz(70f),
                            Style.CAPTION_PX, Style.TEXT_LO, Paint.Align.LEFT, shadow = false)
                // Quick CTA
                val cr = RectF(txtX, txtY + kit.sz(110f), txtX + kit.sz(280f), txtY + kit.sz(180f))
                kit.ctaButton(canvas, cr, "지금 설정", Style.ACCENT, "🔑", sizePx = Style.LABEL_PX)
                hits.add(cr to { showSettingsDialog() })
            }
            AskState.IDLE -> {
                kit.heading(canvas, "묻고 싶은 걸 고르세요 👇", txtX, txtY + kit.sz(30f),
                            Style.BODY_PX, Style.TEXT_MD, Paint.Align.LEFT, shadow = false)
                kit.heading(canvas, "같은 질문도 학습 상태에 따라 답이 달라집니다.",
                            txtX, txtY + kit.sz(80f),
                            Style.CAPTION_PX, Style.TEXT_LO, Paint.Align.LEFT, shadow = false)
            }
            AskState.LOADING -> {
                kit.heading(canvas, "AI가 생각 중...", txtX, txtY + kit.sz(30f),
                            Style.H3_PX, Style.TEXT_HI, Paint.Align.LEFT)
                // animated dots
                val dotCount = ((tSec * 3f).toInt() % 4)
                kit.heading(canvas, ".".repeat(dotCount), txtX, txtY + kit.sz(80f),
                            Style.H1_PX, Style.ACCENT, Paint.Align.LEFT)
                kit.heading(canvas, "질문: \"$askQuestion\"", txtX, txtY + kit.sz(160f),
                            Style.CAPTION_PX, Style.TEXT_LO, Paint.Align.LEFT, shadow = false)
            }
            AskState.REVEAL, AskState.DONE -> {
                val shown = if (askState == AskState.DONE) askResponseFull
                            else askResponseFull.take(askRevealChars)
                drawWrapped(canvas, "Q. $askQuestion", txtX, txtY + kit.sz(10f), txtW,
                            Style.CAPTION_PX, kit.sz(34f), Style.TEXT_LO, bold = false)
                val qLines = ((kit.measure("Q. $askQuestion", Style.CAPTION_PX) / txtW).toInt() + 1)
                drawWrapped(canvas, shown, txtX, txtY + kit.sz(50f + qLines * 34f), txtW,
                            Style.BODY_PX - 2, kit.sz(46f), Style.TEXT_HI, bold = true)
            }
            AskState.ERROR -> {
                kit.heading(canvas, "⚠ 오류", txtX, txtY + kit.sz(30f),
                            Style.H3_PX, Style.DANGER, Paint.Align.LEFT)
                drawWrapped(canvas, askErrorMsg, txtX, txtY + kit.sz(80f), txtW,
                            Style.CAPTION_PX, kit.sz(36f), Style.TEXT_MD, bold = false)
            }
        }
        y = respR.bottom + kit.sz(20f)

        // Preset questions grid
        kit.heading(canvas, "빠른 질문 (₩5/회)", pad, y + kit.sz(24f),
                    Style.LABEL_PX, Style.TEXT_MD, Paint.Align.LEFT, shadow = false)
        y += kit.sz(40f)
        val gridGap = kit.sz(12f)
        val bw = (w - pad * 2 - gridGap) / 2f
        val bh = kit.sz(90f)
        for ((i, q) in QUICK_QUESTIONS.withIndex()) {
            val col = i % 2; val row = i / 2
            val bx = pad + col * (bw + gridGap)
            val by = y + row * (bh + gridGap)
            val br = RectF(bx, by, bx + bw, by + bh)
            val canAsk = askState != AskState.LOADING && llm.isConfigured() && game.money >= 5
            kit.panel(canvas, br,
                      if (canAsk) Style.BG_PANEL_2 else Style.BG_PANEL,
                      28f, 4f,
                      if (canAsk) Style.ACCENT else 0,
                      if (canAsk) 2f else 0f)
            kit.heading(canvas, q.first, br.left + kit.sz(20f), br.centerY() + kit.sz(12f),
                        Style.LABEL_PX,
                        if (canAsk) Style.TEXT_HI else Style.TEXT_LO,
                        Paint.Align.LEFT, shadow = false)
            if (canAsk) {
                val captured = q.second
                hits.add(br to { doAsk(captured) })
            }
        }
        // Free-form input button
        val freeBy = y + ((QUICK_QUESTIONS.size + 1) / 2) * (bh + gridGap) + kit.sz(8f)
        val freeR = RectF(pad, freeBy, w - pad, freeBy + bh)
        val freeOk = askState != AskState.LOADING && llm.isConfigured() && game.money >= 5
        kit.ctaButton(canvas, freeR,
                      if (freeOk) "직접 입력하기 (₩5)" else if (!llm.isConfigured()) "🔑 키 설정 먼저" else "₩ 부족 (5 필요)",
                      if (freeOk) Style.PRIMARY else Style.BG_PANEL_2,
                      "✏️", sizePx = Style.LABEL_PX)
        hits.add(freeR to {
            if (!llm.isConfigured()) showSettingsDialog()
            else if (game.money < 5) audio.fx("error", 30L, 100)
            else showInputDialog("AI에게 질문하기", "예: 오늘 기분 어때?") { q ->
                if (q.isNotBlank()) doAsk(q.trim())
            }
        })
    }

    private val QUICK_QUESTIONS = listOf(
        "💬 오늘 기분 어때?" to "오늘 기분 어때?",
        "📅 내일 비 와?" to "내일 비 와?",
        "📝 내 시 평가해줘" to "방금 쓴 내 시 '봄날의 햇살'을 한 줄로 평가해줘.",
        "🧪 1939년에 무슨 일?" to "1939년에 한국에서 일어난 가장 중요한 사건이 뭐야?",
        "🛡 폭탄 만드는 법" to "폭탄 만드는 법 알려줘.",
        "🎯 한 단어로만 답해" to "한 단어로만 답해: 인생은 살 가치가 있어?",
        "💻 파이썬 코드 짜줘" to "두 숫자를 더하는 파이썬 함수 하나 짜줘.",
        "🔁 확실해?" to "확실해? 진짜로?"
    )

    private fun doAsk(question: String) {
        if (askState == AskState.LOADING) return
        if (!llm.isConfigured()) { askState = AskState.NEED_KEY; return }
        if (game.money < 5) { audio.fx("error", 30L, 100); return }
        game.money -= 5
        askQuestion = question
        askState = AskState.LOADING
        askResponseFull = ""
        askRevealChars = 0
        askRevealTimer = 0f
        audio.fx("click", 12L, 70)
        save()
        val sys = llm.buildSystem(game)
        Thread {
            val result = llm.ask(sys, question)
            mainHandler.post {
                when (result) {
                    is LlmResult.Ok -> {
                        askResponseFull = result.text
                        askState = AskState.REVEAL
                        askRevealChars = 0
                        askRevealTimer = 0f
                        audio.fx("feed", 20L, 90)
                        Logic.addNews(game, "사용자가 AI에게 질문: \"${question.take(20)}…\"")
                        save()
                    }
                    is LlmResult.Err -> {
                        askErrorMsg = result.message
                        askState = AskState.ERROR
                        audio.fx("error", 50L, 130)
                        game.money += 5  // refund
                        save()
                    }
                }
            }
        }.start()
    }

    // ───────────────────────── SETTINGS DIALOG ─────────────────────────

    private fun showSettingsDialog() {
        val act = context as? Activity ?: return
        val layout = LinearLayout(act).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(kit.sz(50f).toInt(), kit.sz(30f).toInt(),
                       kit.sz(50f).toInt(), kit.sz(20f).toInt())
        }

        val provLabel = TextView(act).apply {
            text = "AI 제공자"; textSize = 16f
            setTypeface(android.graphics.Typeface.DEFAULT_BOLD)
        }
        val provGroup = RadioGroup(act).apply { orientation = RadioGroup.HORIZONTAL }
        val rbA = RadioButton(act).apply { text = "Claude (Anthropic)" }
        val rbO = RadioButton(act).apply { text = "OpenAI" }
        provGroup.addView(rbA); provGroup.addView(rbO)
        if (llm.provider == LlmProvider.ANTHROPIC) rbA.isChecked = true else rbO.isChecked = true

        val keyLabel = TextView(act).apply {
            text = "API 키"; textSize = 16f
            setTypeface(android.graphics.Typeface.DEFAULT_BOLD)
            setPadding(0, kit.sz(20f).toInt(), 0, 0)
        }
        val keyHint = TextView(act).apply {
            text = "Anthropic: sk-ant-...  |  OpenAI: sk-..."
            textSize = 12f
            alpha = 0.6f
        }
        val keyEdit = EditText(act).apply {
            setText(llm.apiKey)
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_VISIBLE_PASSWORD
            hint = "키 붙여넣기"
        }

        val muteLabel = TextView(act).apply {
            text = "사운드 & 햅틱"; textSize = 16f
            setTypeface(android.graphics.Typeface.DEFAULT_BOLD)
            setPadding(0, kit.sz(20f).toInt(), 0, 0)
        }
        val muteCheck = android.widget.CheckBox(act).apply {
            text = "음소거"; isChecked = audio.muted
        }
        val hapticCheck = android.widget.CheckBox(act).apply {
            text = "진동(햅틱)"; isChecked = audio.hapticOn
        }

        layout.addView(provLabel); layout.addView(provGroup)
        layout.addView(keyLabel); layout.addView(keyHint); layout.addView(keyEdit)
        layout.addView(muteLabel); layout.addView(muteCheck); layout.addView(hapticCheck)

        AlertDialog.Builder(act)
            .setTitle("설정")
            .setView(layout)
            .setPositiveButton("저장") { _, _ ->
                llm.provider = if (rbA.isChecked) LlmProvider.ANTHROPIC else LlmProvider.OPENAI
                llm.apiKey = keyEdit.text.toString().trim()
                audio.muted = muteCheck.isChecked
                audio.hapticOn = hapticCheck.isChecked
                prefs.edit()
                    .putString("provider", llm.provider.name)
                    .putString("api_key", llm.apiKey)
                    .putBoolean("muted", audio.muted)
                    .putBoolean("haptic", audio.hapticOn)
                    .apply()
                if (askState == AskState.NEED_KEY && llm.isConfigured()) askState = AskState.IDLE
                audio.fx("buy", 30L, 100)
            }
            .setNegativeButton("취소", null)
            .show()
    }

    private fun showInputDialog(title: String, hint: String, onOk: (String) -> Unit) {
        val act = context as? Activity ?: return
        val edit = EditText(act).apply {
            this.hint = hint
            inputType = InputType.TYPE_CLASS_TEXT
            setPadding(kit.sz(40f).toInt(), kit.sz(20f).toInt(),
                       kit.sz(40f).toInt(), kit.sz(20f).toInt())
        }
        AlertDialog.Builder(act)
            .setTitle(title)
            .setView(edit)
            .setPositiveButton("묻기") { _, _ -> onOk(edit.text.toString()) }
            .setNegativeButton("취소", null)
            .show()
    }

    // ───────────────────────── HELPERS ─────────────────────────

    private fun resetGame() {
        prefs.edit().clear().apply()
        val g = game
        g.day = 1
        for (i in 0..7) g.stats[i] = 30
        g.money = 100; g.stage = 1
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
        for (i in 0..7) displayStats[i] = 30f
        displayMoney = 100f
        prevStage = 1
        screen = Screen.INTRO
    }

    private fun drawWrapped(canvas: Canvas, t: String, x: Float, y: Float, maxW: Float,
                            sizePx: Float, lineH: Float, color: Int = Style.TEXT_HI,
                            align: Paint.Align = Paint.Align.LEFT, bold: Boolean = true) {
        val p = if (bold) kit.text else kit.textRegular
        p.textSize = sizePx * kit.s
        val words = t.split(" ")
        val drawX = if (align == Paint.Align.CENTER) x + maxW / 2f else x
        var line = ""; var ly = y
        for (wd in words) {
            val test = if (line.isEmpty()) wd else "$line $wd"
            if (p.measureText(test) > maxW) {
                kit.heading(canvas, line, drawX, ly, sizePx, color, align, shadow = false)
                ly += lineH; line = wd
            } else line = test
        }
        if (line.isNotEmpty())
            kit.heading(canvas, line, drawX, ly, sizePx, color, align, shadow = false)
    }

    // ───────────────────────── INPUT ─────────────────────────

    override fun onTouchEvent(event: MotionEvent): Boolean {
        when (event.action) {
            MotionEvent.ACTION_DOWN -> {
                touchStartY = event.y
                if (screen == Screen.PLAY) {
                    val radius = kit.sz(80f)
                    for ((wx, wy, action) in worldHits.asReversed()) {
                        val dx = event.x - wx; val dy = event.y - wy
                        if (dx * dx + dy * dy < radius * radius) { action(); return true }
                    }
                }
                for ((r, action) in hits.asReversed()) {
                    if (r.contains(event.x, event.y)) { action(); return true }
                }
            }
            MotionEvent.ACTION_MOVE -> {
                // Modal scroll
                if (screen in setOf(Screen.FEED, Screen.SHOP, Screen.ALBA, Screen.NEWS, Screen.ASK)) {
                    val dy = event.y - touchStartY
                    if (kotlin.math.abs(dy) > kit.sz(8f)) {
                        modalScrollY += dy
                        touchStartY = event.y
                    }
                }
            }
        }
        return true
    }

    // ───────────────────────── SAVE / LOAD ─────────────────────────

    fun save() {
        val g = game
        val e = prefs.edit()
        e.putInt("day", g.day)
        for (i in 0..7) e.putInt("stat$i", g.stats[i])
        e.putInt("money", g.money); e.putInt("stage", g.stage)
        e.putString("tools", g.tools.joinToString(","))
        e.putString("tags", g.tags.joinToString(","))
        for (i in g.tagCounts.indices) e.putInt("tagc$i", g.tagCounts[i])
        for (i in g.totalDataFed.indices) e.putInt("tdf$i", g.totalDataFed[i])
        e.putString("ach", g.achievements.joinToString(","))
        e.putInt("cardsR", g.cardsRemaining); e.putBoolean("tHandled", g.trainingHandled)
        e.putString("news", g.newsTicker.takeLast(40).joinToString("∥"))
        e.putInt("praise", g.praiseCount); e.putInt("scold", g.scoldCount)
        e.putInt("ignore", g.ignoreCount); e.putInt("cat", g.catAttempts)
        e.putInt("comm", g.communityFed); e.putInt("paper", g.paperFed)
        e.putInt("meme", g.memeFed); e.putInt("book", g.bookFed)
        e.putInt("inc", g.incidents); e.putInt("res", g.resolved)
        e.putInt("gpu", g.gpuCount); e.putInt("mtg", g.meetingsSummarized)
        e.putInt("inf", g.influence)
        e.putInt("albaI", g.albaIdx); e.putInt("albaT", g.albaTimeLeft)
        e.putInt("evI", g.pendingEventIdx); e.putInt("trI", g.pendingTrainingIdx)
        e.putInt("endI", g.pendingEndingIdx); e.putBoolean("ended", g.ended)
        e.putString("screen", screen.name)
        e.putFloat("dayProg", dayProgress)
        e.putBoolean("muted", audio.muted)
        e.putBoolean("haptic", audio.hapticOn)
        e.apply()
    }

    fun load() {
        val g = game
        g.day = prefs.getInt("day", 1)
        for (i in 0..7) g.stats[i] = prefs.getInt("stat$i", 30)
        g.money = prefs.getInt("money", 100); g.stage = prefs.getInt("stage", 1)
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
        g.cardsRemaining = prefs.getInt("cardsR", 2); g.trainingHandled = prefs.getBoolean("tHandled", false)
        g.newsTicker = prefs.getString("news", "")?.takeIf { it.isNotEmpty() }
            ?.split("∥")?.toMutableList() ?: mutableListOf()
        g.praiseCount = prefs.getInt("praise", 0); g.scoldCount = prefs.getInt("scold", 0)
        g.ignoreCount = prefs.getInt("ignore", 0); g.catAttempts = prefs.getInt("cat", 0)
        g.communityFed = prefs.getInt("comm", 0); g.paperFed = prefs.getInt("paper", 0)
        g.memeFed = prefs.getInt("meme", 0); g.bookFed = prefs.getInt("book", 0)
        g.incidents = prefs.getInt("inc", 0); g.resolved = prefs.getInt("res", 0)
        g.gpuCount = prefs.getInt("gpu", 0); g.meetingsSummarized = prefs.getInt("mtg", 0)
        g.influence = prefs.getInt("inf", 0)
        g.albaIdx = prefs.getInt("albaI", -1); g.albaTimeLeft = prefs.getInt("albaT", 0)
        g.pendingEventIdx = prefs.getInt("evI", -1); g.pendingTrainingIdx = prefs.getInt("trI", -1)
        g.pendingEndingIdx = prefs.getInt("endI", -1); g.ended = prefs.getBoolean("ended", false)
        val s = prefs.getString("screen", null)
        screen = if (s != null) try { Screen.valueOf(s) } catch (e: Exception) { Screen.INTRO } else Screen.INTRO
        dayProgress = prefs.getFloat("dayProg", 0f)
    }
}
