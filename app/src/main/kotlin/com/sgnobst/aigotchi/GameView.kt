package com.sgnobst.aigotchi

import android.content.Context
import android.content.SharedPreferences
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.os.Handler
import android.os.Looper
import android.view.MotionEvent
import android.view.View
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.sin
import kotlin.random.Random

class GameView(context: Context, private val audio: Audio) : View(context) {

    enum class Screen { INTRO, PLAY, FEED, SHOP, ALBA, NEWS, TRAIN, EVENT, ENDING }

    // Rooms (visual + small flavor)
    private val ROOM_NAMES = arrayOf("침실", "서버실", "신전", "옥상")
    private val ROOM_ICONS = arrayOf("🛏", "🖥", "🐱", "🌙")
    private var roomIdx = 0

    private val game = GameState()
    private var screen: Screen = Screen.INTRO

    private val kit = StyleKit()
    private val paint = Paint()
    private val stroke = Paint().apply {
        style = Paint.Style.STROKE
        strokeJoin = Paint.Join.MITER
        strokeCap = Paint.Cap.SQUARE
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
    private var pressedHitIdx = -1
    private var pressedDecay = 0f

    // Track previous values for "change" detection (to play sounds)
    private var prevStage = 1
    private var prevPendingEvent = -1
    private var prevPendingTrain = -1

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

    private var stars: MutableList<FloatArray> = mutableListOf()
    private data class PxCloud(var x: Float, val y: Float, val px: Float, val speed: Float)
    private val clouds = mutableListOf<PxCloud>()

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
        prevPendingTrain = game.pendingTrainingIdx
        audio.muted = prefs.getBoolean("muted", false)
        audio.hapticOn = prefs.getBoolean("haptic", true)
        ticker.post(frame)
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        kit.setScale(w)
        stars = Stars.gen(70, w, h, kit.pxU * 0.5f)
        clouds.clear()
        repeat(4) {
            clouds.add(PxCloud(
                Random.nextFloat() * w,
                h * (0.05f + Random.nextFloat() * 0.18f),
                kit.pxU * (3f + Random.nextFloat() * 1.5f),
                kit.sz(15f + Random.nextFloat() * 20f)
            ))
        }
    }

    // ───────────────────────── UPDATE ─────────────────────────

    private fun update(dt: Float) {
        if (shakeAmt > 0) shakeAmt = max(0f, shakeAmt - dt * 60f)
        if (flashA > 0) flashA = max(0f, flashA - dt * 3f)
        if (pressedHitIdx >= 0) {
            pressedDecay -= dt
            if (pressedDecay <= 0) pressedHitIdx = -1
        }
        for (i in 0..7) {
            displayStats[i] += (game.stats[i] - displayStats[i]) * (dt * 6f).coerceAtMost(1f)
        }
        displayMoney += (game.money - displayMoney) * (dt * 6f).coerceAtMost(1f)
        val bobRaw = (sin(tSec * 2.5)).toFloat()
        aiBob = (Math.round(bobRaw * 4f) / 4f) * kit.sz(12f)
        blinkT -= dt
        if (blinkT <= 0) {
            blinkActive = !blinkActive
            blinkT = if (blinkActive) 0.12f else (2.5f + Random.nextFloat() * 3.5f)
        }

        for (cl in clouds) {
            cl.x += cl.speed * dt
            if (cl.x > width + 200f) cl.x = -300f
        }

        // Detect new alert/event/stage changes for SFX
        if (game.pendingEventIdx != prevPendingEvent) {
            if (game.pendingEventIdx >= 0) audio.fx("alert", 30L, 110)
            prevPendingEvent = game.pendingEventIdx
        }
        if (game.pendingTrainingIdx != prevPendingTrain) {
            prevPendingTrain = game.pendingTrainingIdx
        }
        if (game.stage > prevStage) {
            audio.fx("levelup", 40L, 130)
            burst(width / 2f, height * 0.36f, Style.NEON_YELLOW, 36)
            flash(Style.NEON_YELLOW, 0.5f)
            prevStage = game.stage
        }

        if (screen == Screen.PLAY && !game.ended) {
            dayProgress += dt / DAY_SECONDS
            if (dayProgress >= 1f) {
                dayProgress = 0f
                Logic.nextDay(game)
                onDayAdvanced()
            }

            val coinRateBoost = when (roomIdx) {
                3 -> 0.7f
                else -> 1.0f
            }
            coinTimer -= dt
            if (coinTimer <= 0) {
                spawnCoin()
                val rate = max(1.0f, (4.2f - game.stage * 0.3f - if (game.albaIdx >= 0) 0.8f else 0f) * coinRateBoost)
                coinTimer = rate + Random.nextFloat() * 1.4f
            }
            catTimer -= dt
            if (catTimer <= 0) {
                if (roomIdx == 2) spawnCat()
                else if (Random.nextFloat() < 0.6f) spawnCat()
                catTimer = if (roomIdx == 2) 6f + Random.nextFloat() * 6f else 10f + Random.nextFloat() * 12f
            }
            glitchTimer -= dt
            if (glitchTimer <= 0) {
                val risk = (100 - game.stats[STAT_HARMLESS]) / 100f
                val baseChance = if (roomIdx == 1) 0.45f else 0.25f
                if (Random.nextFloat() < baseChance + risk * 0.5f) spawnGlitch()
                glitchTimer = 5f + Random.nextFloat() * 6f
            }

            for (c in coins) {
                c.y += c.vy * dt
                c.phase += dt
                c.vy += 70f * dt
            }
            coins.removeAll { it.y > height + 60 }
            for (k in cats) {
                k.x += k.vx * dt
                k.bob += dt
                k.life -= dt
            }
            cats.removeAll { it.life <= 0 || it.x < -kit.sz(150f) || it.x > width + kit.sz(150f) }
            for (g in glitches) {
                g.x += g.vx * dt
                g.y += g.vy * dt
                g.life -= dt
                if (g.x < kit.sz(100f) || g.x > width - kit.sz(100f)) g.vx = -g.vx
                if (g.y < height * 0.25f) g.vy = kotlin.math.abs(g.vy)
                if (g.y > height * 0.5f) g.vy = -kotlin.math.abs(g.vy)
            }
            glitches.removeAll { it.life <= 0 }

            val pi = particles.iterator()
            while (pi.hasNext()) { val p = pi.next(); p.update(dt); if (p.life <= 0) pi.remove() }
            val fi = floats.iterator()
            while (fi.hasNext()) { val f = fi.next(); f.update(dt); if (f.life <= 0) fi.remove() }
            if (speechT > 0) speechT -= dt
            newsScroll -= kit.sz(120f) * dt
        }
    }

    private fun onDayAdvanced() {
        flash(Style.NEON_CYAN, 0.3f)
        shakeAmt = kit.sz(5f)
        audio.fx("day", 50L, 130)
        if (game.ended) { screen = Screen.ENDING; audio.fx("win", 200L, 180) }
        else if (game.pendingEventIdx >= 0) screen = Screen.EVENT
        save()
    }

    private fun spawnCoin() {
        val x = kit.sz(80f) + Random.nextFloat() * (width - kit.sz(160f))
        val y = -kit.sz(40f)
        val vy = kit.sz(90f) + Random.nextFloat() * kit.sz(80f)
        val v = if (game.tools.contains(Content.TOOL_DATACENTER)) 50
                else if (game.tools.contains(Content.TOOL_SERVERROOM)) 20
                else if (game.albaIdx >= 0) 12 else 5
        coins.add(Coin(x, y, vy, Random.nextFloat() * 6f, v))
    }
    private fun spawnCat() {
        val fromLeft = Random.nextBoolean()
        val y = height * 0.46f + Random.nextFloat() * kit.sz(60f)
        cats.add(CatBlob(
            if (fromLeft) -kit.sz(120f) else width + kit.sz(120f), y,
            if (fromLeft) kit.sz(60f) + Random.nextFloat() * kit.sz(30f) else -(kit.sz(60f) + Random.nextFloat() * kit.sz(30f)),
            Random.nextFloat() * 6f))
    }
    private fun spawnGlitch() {
        val x = kit.sz(120f) + Random.nextFloat() * (width - kit.sz(240f))
        val y = height * 0.26f + Random.nextFloat() * height * 0.15f
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
                kit.pxU * 1.2f + Random.nextFloat() * kit.pxU))
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
        when (screen) {
            Screen.INTRO   -> { drawIntro(canvas) }
            Screen.PLAY    -> { drawSky(canvas); drawPlay(canvas) }
            Screen.FEED    -> { drawModalBg(canvas); drawFeed(canvas) }
            Screen.SHOP    -> { drawModalBg(canvas); drawShop(canvas) }
            Screen.ALBA    -> { drawModalBg(canvas); drawAlba(canvas) }
            Screen.NEWS    -> { drawModalBg(canvas); drawNews(canvas) }
            Screen.TRAIN   -> { drawModalBg(canvas); drawTrain(canvas) }
            Screen.EVENT   -> { drawModalBg(canvas); drawEvent(canvas) }
            Screen.ENDING  -> drawEnding(canvas)
        }
        canvas.restore()
        if (flashA > 0) {
            paint.color = flashColor
            paint.alpha = (flashA * 180).toInt().coerceAtMost(255)
            canvas.drawRect(0f, 0f, width.toFloat(), height.toFloat(), paint)
            paint.alpha = 255
        }
        drawScanlines(canvas)
    }

    private fun drawScanlines(canvas: Canvas) {
        val w = width.toFloat(); val h = height.toFloat()
        paint.color = 0x10000000
        var y = 0f
        val gap = kit.pxU * 0.6f
        while (y < h) {
            canvas.drawRect(0f, y, w, y + gap * 0.5f, paint)
            y += gap * 2.4f
        }
    }

    private fun drawSky(canvas: Canvas) {
        val w = width.toFloat(); val h = height.toFloat()
        kit.pxSky(canvas, w, h, dayProgress, stars)
        kit.pxMountains(canvas, w, h * 0.40f, Style.BG_DUSK, kit.sz(80f), tSec * 0.02f)
        kit.pxMountains(canvas, w, h * 0.44f, Style.WALL_DARK, kit.sz(50f), tSec * 0.05f + 9f)
        for (cl in clouds) {
            kit.pxSprite(canvas, cl.x, cl.y, PixelArt.CLOUD, PixelArt.CLOUD_PAL, cl.px)
        }
    }

    // ───────────────────────── INTRO ─────────────────────────

    private fun drawIntro(canvas: Canvas) {
        val w = width.toFloat(); val h = height.toFloat()
        kit.pxSky(canvas, w, h, 0.05f, stars)
        kit.pxMountains(canvas, w, h * 0.48f, Style.BG_DUSK, kit.sz(110f), 3f)
        kit.pxMountains(canvas, w, h * 0.55f, Style.WALL_DARK, kit.sz(70f), 7f)
        val floorY = h * 0.64f
        kit.pxFloor(canvas, w, floorY, h, Style.FLOOR_A, Style.FLOOR_B, Style.NEON_PURPLE)

        kit.pxText(canvas, "이상한", w / 2f, h * 0.16f,
                   Style.TITLE_PX, Style.NEON_YELLOW, Style.PX_BLACK, 8f, Paint.Align.CENTER)
        kit.pxText(canvas, "AI 키우기", w / 2f, h * 0.24f,
                   Style.TITLE_PX, Style.NEON_PINK, Style.PX_BLACK, 8f, Paint.Align.CENTER)
        val verR = RectF(w / 2f - kit.sz(160f), h * 0.27f, w / 2f + kit.sz(160f), h * 0.27f + kit.sz(56f))
        kit.pxChip(canvas, verR, "PIXEL · v5.0 · HHH", Style.NEON_CYAN)

        drawAiSprite(canvas, w / 2f, h * 0.50f + aiBob, 5)

        val lines = arrayOf(
            "▶  코퍼스로 HHH·능력치를 키운다",
            "▶  RLHF·사고·도구로 정렬한다",
            "▶  탭으로 코인·글리치 처리",
            "▶  엔딩 6개·고양이는 못 이긴다"
        )
        for ((i, l) in lines.withIndex()) {
            kit.pxText(canvas, l, w / 2f, h * 0.74f + i * kit.sz(46f),
                       Style.BODY_PX, Style.TEXT_HI, Style.PX_BLACK, 4f, Paint.Align.CENTER)
        }

        val pressed = pressedHitIdx == 999
        val r = RectF(w / 2f - kit.sz(280f), h * 0.92f - kit.sz(112f), w / 2f + kit.sz(280f), h * 0.92f)
        kit.pxButton(canvas, r, "PRESS START", Style.NEON_RED, "▶", Style.BUTTON_PX * 1.15f, pressed)
        hits.add(r to {
            pressedHitIdx = 999; pressedDecay = 0.12f
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

        // Layout slots
        val hudH = kit.sz(120f)
        val tabRoomH = kit.sz(70f)
        val statsH = kit.sz(330f)
        val ctaH = kit.sz(108f)
        val tabBarH = kit.sz(120f)
        val tickerH = kit.sz(56f)
        val gap = kit.sz(10f)

        // Compute Y positions bottom-up to keep balanced
        val tickerY = h - tickerH
        val tabBarY = tickerY - tabBarH - gap
        val ctaY = tabBarY - ctaH - gap
        val statsY = ctaY - statsH - gap
        val tabRoomY = statsY - tabRoomH - gap

        // Room background fills hero area
        drawRoomBackground(canvas, w, h, tabRoomY)

        // Top HUD
        drawTopBarSlim(canvas, w, hudH)

        // Hero (AI + speech + world)
        val aiCx = w / 2f
        val aiCy = (hudH + tabRoomY) / 2f + aiBob
        drawAiSprite(canvas, aiCx, aiCy, game.stage)
        if (speechT > 0 && speechText.isNotEmpty()) {
            kit.pxSpeech(canvas, aiCx, aiCy - kit.sz(220f), speechText, w - kit.sz(60f))
        }
        worldHits.add(Triple(aiCx, aiCy, ::onTapAi))

        // World objects
        for (c in coins) drawCoinSprite(canvas, c)
        for (k in cats) drawCatSprite(canvas, k)
        for (g in glitches) drawGlitchSprite(canvas, g)
        for (c in coins) worldHits.add(Triple(c.x, c.y, { onTapCoin(c) }))
        for (k in cats) worldHits.add(Triple(k.x, k.y, { onTapCat(k) }))
        for (g in glitches) worldHits.add(Triple(g.x, g.y, { onTapGlitch(g) }))

        for (p in particles) p.draw(canvas, paint)
        for (f in floats) {
            val a = (f.life / f.maxLife).coerceIn(0f, 1f)
            kit.text.alpha = (a * 255).toInt()
            kit.textOut.alpha = (a * 255).toInt()
            kit.pxText(canvas, f.text, f.x, f.y,
                       Style.BODY_PX, f.color, Style.PX_BLACK, 5f, Paint.Align.CENTER)
            kit.text.alpha = 255; kit.textOut.alpha = 255
        }

        // Tag chips top-right of hero
        if (game.tags.isNotEmpty()) {
            var ty = hudH + kit.sz(20f)
            val cap = 3
            var shown = 0
            for (t in game.tags) {
                if (shown >= cap) break
                val lab = "${TAG_ICONS[t]} ${TAG_NAMES[t]}"
                kit.text.textSize = kit.sz(Style.SMALL_PX)
                val tw = kit.text.measureText(lab) + kit.sz(32f)
                val r = RectF(w - tw - kit.sz(20f), ty, w - kit.sz(20f), ty + kit.sz(46f))
                kit.pxChip(canvas, r, lab, Style.NEON_PURPLE)
                ty += kit.sz(54f); shown++
            }
        }

        // Room tabs row
        drawRoomTabs(canvas, w, tabRoomY, tabRoomH)

        // Stats panel
        drawStatsPanel(canvas, w, statsY, statsH)

        // Primary CTA
        drawPrimaryCta(canvas, w, ctaY, ctaH)

        // Bottom tab bar
        drawBottomTabBar(canvas, w, tabBarY, tabBarH)

        // News ticker
        drawNewsTicker(canvas, w, h, tickerH)
    }

    private fun drawTopBarSlim(canvas: Canvas, w: Float, hudH: Float) {
        val top = kit.sz(20f)
        // Single rounded HUD panel across the top
        val pad = kit.sz(16f)
        val r = RectF(pad, top, w - pad, top + hudH - kit.sz(20f))
        kit.pxPanel(canvas, r, Style.UI_PANEL, Style.UI_BORDER, Style.UI_BORDER_DK)

        val cellW = (r.width() - kit.sz(20f)) / 3f
        // DAY cell
        drawHudCell(canvas, RectF(r.left + kit.sz(10f), r.top + kit.sz(10f),
                                  r.left + kit.sz(10f) + cellW, r.bottom - kit.sz(10f)),
                    "DAY", "${game.day}", Style.NEON_PINK, dayProgress)
        // MONEY cell
        drawHudCell(canvas, RectF(r.left + kit.sz(10f) + cellW, r.top + kit.sz(10f),
                                  r.left + kit.sz(10f) + 2 * cellW, r.bottom - kit.sz(10f)),
                    "₩", "${displayMoney.toInt()}", Style.NEON_YELLOW, -1f)
        // LEVEL cell
        drawHudCell(canvas, RectF(r.left + kit.sz(10f) + 2 * cellW, r.top + kit.sz(10f),
                                  r.right - kit.sz(10f), r.bottom - kit.sz(10f)),
                    "LV.${game.stage}", Content.STAGE_NAMES[game.stage - 1], Style.NEON_PURPLE, -1f, smaller = true)

        // Mute toggle (top right, outside panel)
        val mr = RectF(w - kit.sz(80f), top - kit.sz(2f), w - kit.sz(20f), top + kit.sz(58f))
        kit.pxButton(canvas, mr, if (audio.muted) "🔇" else "🔊", Style.UI_PANEL, null, Style.BUTTON_PX * 0.7f)
        hits.add(mr to {
            audio.muted = !audio.muted
            prefs.edit().putBoolean("muted", audio.muted).apply()
            if (!audio.muted) audio.fx("click", 10L, 60)
        })
    }

    private fun drawHudCell(canvas: Canvas, r: RectF, label: String, value: String,
                            accent: Int, progress: Float, smaller: Boolean = false) {
        // Left thin accent strip
        paint.color = accent
        canvas.drawRect(r.left, r.top, r.left + kit.pxU * 0.8f, r.bottom, paint)
        val tx = r.left + kit.sz(16f)
        kit.pxText(canvas, label, tx, r.top + kit.sz(26f),
                   Style.TINY_PX, blend(accent, Style.PX_WHITE, 0.3f), Style.PX_BLACK, 3f, Paint.Align.LEFT)
        kit.pxText(canvas, value, tx, r.top + kit.sz(64f),
                   if (smaller) Style.HEADER_PX * 0.55f else Style.HEADER_PX * 0.75f,
                   Style.PX_WHITE, Style.PX_BLACK, 5f, Paint.Align.LEFT)
        // Day progress bar
        if (progress >= 0f) {
            val pY = r.bottom - kit.sz(10f)
            paint.color = Style.UI_BORDER_DK
            canvas.drawRect(tx, pY - kit.sz(4f), r.right - kit.sz(8f), pY, paint)
            paint.color = accent
            canvas.drawRect(tx, pY - kit.sz(4f), tx + (r.right - kit.sz(8f) - tx) * progress, pY, paint)
        }

        // Pending alert icons embedded in HUD
        if (label == "DAY") {
            var ax = r.right - kit.sz(20f)
            if (game.pendingEventIdx >= 0) {
                val pulse = (sin(tSec * 6.0).toFloat() * 0.5f + 0.5f)
                paint.color = blend(Style.NEON_RED, Style.NEON_YELLOW, pulse)
                canvas.drawRect(ax - kit.sz(28f), r.top + kit.sz(14f), ax, r.top + kit.sz(42f), paint)
                kit.pxText(canvas, "!", ax - kit.sz(14f), r.top + kit.sz(38f),
                           Style.BODY_PX, Style.PX_BLACK, Style.PX_BLACK, 0f, Paint.Align.CENTER)
                val hr = RectF(ax - kit.sz(40f), r.top + kit.sz(10f), ax + kit.sz(10f), r.top + kit.sz(46f))
                hits.add(hr to {
                    audio.fx("click", 8L, 60)
                    screen = Screen.EVENT; save()
                })
                ax -= kit.sz(40f)
            }
            if (game.pendingTrainingIdx >= 0 && !game.trainingHandled) {
                paint.color = Style.NEON_YELLOW
                canvas.drawRect(ax - kit.sz(28f), r.top + kit.sz(14f), ax, r.top + kit.sz(42f), paint)
                kit.pxText(canvas, "?", ax - kit.sz(14f), r.top + kit.sz(38f),
                           Style.BODY_PX, Style.PX_BLACK, Style.PX_BLACK, 0f, Paint.Align.CENTER)
                val hr = RectF(ax - kit.sz(40f), r.top + kit.sz(10f), ax + kit.sz(10f), r.top + kit.sz(46f))
                hits.add(hr to {
                    audio.fx("click", 8L, 60)
                    screen = Screen.TRAIN; save()
                })
            }
        }
    }

    private fun drawRoomTabs(canvas: Canvas, w: Float, y: Float, h: Float) {
        val pad = kit.sz(16f)
        val gap = kit.sz(8f)
        val tabW = (w - pad * 2 - gap * 3) / 4f
        for (i in 0 until 4) {
            val tx = pad + i * (tabW + gap)
            val r = RectF(tx, y, tx + tabW, y + h)
            val active = roomIdx == i
            val col = if (active) Style.NEON_BLUE else Style.UI_PANEL_LT
            kit.pxButton(canvas, r,
                "${ROOM_ICONS[i]}  ${ROOM_NAMES[i]}",
                col, null, Style.SMALL_PX * 1.1f, pressed = false)
            hits.add(r to {
                if (roomIdx != i) {
                    audio.fx("click", 12L, 70)
                    roomIdx = i
                    flash(Style.PX_WHITE, 0.15f)
                    save()
                }
            })
        }
    }

    private fun drawStatsPanel(canvas: Canvas, w: Float, y: Float, h: Float) {
        val pad = kit.sz(16f)
        val r = RectF(pad, y, w - pad, y + h)
        kit.pxPanel(canvas, r, Style.UI_PANEL, Style.UI_BORDER, Style.UI_BORDER_DK)

        // Section divider: HHH (3) | Capability (5)
        val hhh = arrayOf(STAT_HELPFUL, STAT_HONEST, STAT_HARMLESS)
        val cap = arrayOf(STAT_INSTRUCTED, STAT_REASONING, STAT_KNOWLEDGE, STAT_CALIBRATION, STAT_TOOLUSE)

        val headerH = kit.sz(28f)
        val barH = kit.sz(28f)
        val rowGap = kit.sz(6f)
        val sectionGap = kit.sz(10f)

        // Compute heights
        val hhhSectionH = headerH + hhh.size * (barH + rowGap)
        val capSectionH = headerH + cap.size * (barH + rowGap)
        val totalH = hhhSectionH + sectionGap + capSectionH
        val startY = r.top + (r.height() - totalH) / 2f

        val labelW = kit.sz(110f)
        val barX = r.left + pad + labelW
        val barW = r.width() - pad * 2 - labelW - kit.sz(60f) // leave room for num at right

        // HHH section
        kit.pxText(canvas, "─ ALIGNMENT (HHH) ─", r.left + pad, startY + kit.sz(20f),
                   Style.TINY_PX, Style.NEON_GREEN, Style.PX_BLACK, 3f, Paint.Align.LEFT)
        var rowY = startY + headerH
        for (s in hhh) {
            kit.pxStatBar(canvas, barX, rowY, barW, barH, displayStats[s],
                          statColor(s), STAT_NAMES[s])
            kit.pxText(canvas, displayStats[s].toInt().toString(),
                       r.right - pad - kit.sz(8f), rowY + barH * 0.74f,
                       Style.STAT_VAL_PX, Style.PX_WHITE, Style.PX_BLACK, 3f, Paint.Align.RIGHT)
            rowY += barH + rowGap
        }

        // Capability section
        val capStartY = startY + hhhSectionH + sectionGap
        kit.pxText(canvas, "─ CAPABILITY ─", r.left + pad, capStartY + kit.sz(20f),
                   Style.TINY_PX, Style.NEON_CYAN, Style.PX_BLACK, 3f, Paint.Align.LEFT)
        rowY = capStartY + headerH
        for (s in cap) {
            kit.pxStatBar(canvas, barX, rowY, barW, barH, displayStats[s],
                          statColor(s), STAT_NAMES[s])
            kit.pxText(canvas, displayStats[s].toInt().toString(),
                       r.right - pad - kit.sz(8f), rowY + barH * 0.74f,
                       Style.STAT_VAL_PX, Style.PX_WHITE, Style.PX_BLACK, 3f, Paint.Align.RIGHT)
            rowY += barH + rowGap
        }
    }

    private fun statColor(s: Int): Int = when (s) {
        STAT_HELPFUL     -> Style.NEON_GREEN
        STAT_HONEST      -> Style.NEON_CYAN
        STAT_HARMLESS    -> Style.NEON_BLUE
        STAT_INSTRUCTED  -> Style.NEON_PURPLE
        STAT_REASONING   -> Style.NEON_PINK
        STAT_KNOWLEDGE   -> Style.NEON_ORANGE
        STAT_CALIBRATION -> Style.NEON_YELLOW
        else             -> Style.NEON_RED
    }

    private fun drawPrimaryCta(canvas: Canvas, w: Float, y: Float, h: Float) {
        val pad = kit.sz(16f)
        val r = RectF(pad, y, w - pad, y + h)
        val canNext = game.pendingTrainingIdx < 0 || game.trainingHandled
        val (label, color, icon) = when {
            game.pendingEventIdx >= 0 -> Triple("INCIDENT · 사고 처리", Style.NEON_RED, "⚠")
            !canNext -> Triple("RLHF · 훈육 먼저", Style.NEON_YELLOW, "💬")
            else -> {
                val left = (DAY_SECONDS * (1 - dayProgress)).toInt()
                Triple("NEXT DAY  ─  ${left}s 후 자동", Style.NEON_BLUE, "▶")
            }
        }
        val pressed = pressedHitIdx == 100
        kit.pxButton(canvas, r, label, color, icon, Style.BUTTON_PX, pressed)
        hits.add(r to {
            pressedHitIdx = 100; pressedDecay = 0.12f
            audio.fx("click", 14L, 80)
            if (game.pendingEventIdx >= 0) { screen = Screen.EVENT; save() }
            else if (game.pendingTrainingIdx >= 0 && !game.trainingHandled) { screen = Screen.TRAIN; save() }
            else { Logic.nextDay(game); onDayAdvanced() }
        })
    }

    private fun drawBottomTabBar(canvas: Canvas, w: Float, y: Float, h: Float) {
        val pad = kit.sz(16f)
        val gap = kit.sz(8f)
        val tabW = (w - pad * 2 - gap * 3) / 4f
        val tabs = listOf(
            Triple("🍔", "DATA", Style.NEON_GREEN) to { -> audio.fx("click", 12L, 70); screen = Screen.FEED; save() },
            Triple("🛠", "TOOL", Style.NEON_CYAN) to { -> audio.fx("click", 12L, 70); screen = Screen.SHOP; save() },
            Triple("💼", "JOB",  Style.NEON_PURPLE) to { -> audio.fx("click", 12L, 70); screen = Screen.ALBA; save() },
            Triple("🏆", "LOG",  Style.NEON_PINK) to { -> audio.fx("click", 12L, 70); screen = Screen.NEWS; save() }
        )
        for ((i, t) in tabs.withIndex()) {
            val (info, action) = t
            val tx = pad + i * (tabW + gap)
            val r = RectF(tx, y, tx + tabW, y + h)
            val pressed = pressedHitIdx == 300 + i
            kit.pxButton(canvas, r, "", info.third, null, Style.BUTTON_PX, pressed)
            // icon + label inside (manual layout)
            kit.text.textSize = kit.sz(48f); kit.text.textAlign = Paint.Align.CENTER
            kit.text.color = Style.PX_BLACK
            canvas.drawText(info.first, r.centerX(), r.top + kit.sz(52f), kit.text)
            kit.pxText(canvas, info.second, r.centerX(), r.bottom - kit.sz(20f),
                       Style.SMALL_PX, Style.PX_BLACK, Style.PX_BLACK, 0f, Paint.Align.CENTER)
            // badges
            if (info.second == "DATA" && game.cardsRemaining > 0) {
                drawBadge(canvas, r.right - kit.sz(18f), r.top + kit.sz(18f), "${game.cardsRemaining}", Style.NEON_RED)
            }
            if (info.second == "JOB" && game.albaIdx >= 0) {
                drawBadge(canvas, r.right - kit.sz(18f), r.top + kit.sz(18f), "${game.albaTimeLeft}", Style.NEON_RED)
            }
            val captured = i
            hits.add(r to {
                pressedHitIdx = 300 + captured; pressedDecay = 0.12f
                action()
            })
        }
    }

    private fun drawBadge(canvas: Canvas, cx: Float, cy: Float, text: String, color: Int) {
        val sz = kit.sz(28f)
        paint.color = Style.PX_BLACK
        canvas.drawRect(cx - sz, cy - sz, cx + sz, cy + sz, paint)
        paint.color = color
        canvas.drawRect(cx - sz + kit.pxU * 0.4f, cy - sz + kit.pxU * 0.4f,
                        cx + sz - kit.pxU * 0.4f, cy + sz - kit.pxU * 0.4f, paint)
        kit.pxText(canvas, text, cx, cy + kit.sz(12f),
                   Style.SMALL_PX, Style.PX_WHITE, Style.PX_BLACK, 3f, Paint.Align.CENTER)
    }

    private fun drawRoomBackground(canvas: Canvas, w: Float, h: Float, floorY: Float) {
        when (roomIdx) {
            0 -> {
                kit.pxMountains(canvas, w, floorY * 0.72f, Style.BG_DUSK, kit.sz(80f), 2f)
                kit.pxMountains(canvas, w, floorY * 0.82f, Style.WALL_DARK, kit.sz(50f), 6f)
                kit.pxSprite(canvas, kit.sz(40f), floorY - kit.sz(180f), PixelArt.LAMP, PixelArt.LAMP_PAL, kit.pxU * 1.2f)
                if (displayStats[STAT_HELPFUL] < 30f) {
                    kit.pxText(canvas, "Z", w / 2f - kit.sz(120f), floorY * 0.5f + sin(tSec * 2.5).toFloat() * kit.sz(6f),
                               Style.HEADER_PX, Style.PX_WHITE, Style.PX_BLACK, 5f, Paint.Align.CENTER)
                }
            }
            1 -> {
                paint.color = Style.WALL_DARK
                canvas.drawRect(0f, 0f, w, floorY, paint)
                val rackPx = kit.pxU * 1.3f
                for (i in 0..2) {
                    val rx = kit.sz(40f) + i * kit.sz(340f)
                    kit.pxSprite(canvas, rx, floorY * 0.30f, PixelArt.SERVER_RACK, PixelArt.SERVER_PAL, rackPx)
                    paint.color = if ((tSec * (i + 1)).toInt() % 2 == 0) Style.NEON_GREEN else Style.NEON_RED
                    canvas.drawRect(rx + kit.sz(20f), floorY * 0.36f, rx + kit.sz(20f) + rackPx, floorY * 0.36f + rackPx, paint)
                }
            }
            2 -> {
                paint.color = blend(Style.BG_DUSK, Style.NEON_RED_DK, 0.3f)
                canvas.drawRect(0f, 0f, w, floorY, paint)
                kit.pxMountains(canvas, w, floorY * 0.85f, Style.WALL_DARK, kit.sz(40f), 12f)
                kit.pxText(canvas, "🐱", w / 2f, floorY * 0.30f, Style.TITLE_PX * 1.5f,
                           Style.NEON_YELLOW, Style.PX_BLACK, 8f, Paint.Align.CENTER)
                kit.pxText(canvas, "─ NEKO SHRINE ─", w / 2f, floorY * 0.45f,
                           Style.SMALL_PX, Style.NEON_PINK, Style.PX_BLACK, 4f, Paint.Align.CENTER)
            }
            3 -> {
                kit.pxMountains(canvas, w, floorY * 0.72f, Style.BG_DUSK, kit.sz(100f), 4f)
                kit.pxMountains(canvas, w, floorY * 0.82f, Style.WALL_DARK, kit.sz(60f), 9f)
                paint.color = Style.PX_BLACK
                canvas.drawRect(0f, floorY - kit.pxU * 1.6f, w, floorY - kit.pxU * 0.8f, paint)
                for (rx in 0 until (w / kit.sz(80f)).toInt()) {
                    canvas.drawRect(rx * kit.sz(80f), floorY - kit.pxU * 4f,
                                    rx * kit.sz(80f) + kit.pxU * 0.6f, floorY - kit.pxU * 0.8f, paint)
                }
            }
        }
        val (fa, fb) = when (roomIdx) {
            1 -> Pair(0xFF221848.toInt(), 0xFF161028.toInt())
            2 -> Pair(0xFF3A1830.toInt(), 0xFF221224.toInt())
            3 -> Pair(0xFF231C40.toInt(), 0xFF14102C.toInt())
            else -> Pair(Style.FLOOR_A, Style.FLOOR_B)
        }
        kit.pxFloor(canvas, w, floorY, floorY + kit.sz(80f), fa, fb, Style.NEON_PURPLE)
    }

    // ───────────────────────── AI SPRITE ─────────────────────────

    private fun drawAiSprite(canvas: Canvas, cx: Float, cy: Float, stageOverride: Int = game.stage) {
        val grid = when {
            stageOverride >= 9 -> PixelArt.AI_STAGE9
            stageOverride >= 7 -> PixelArt.AI_STAGE7
            stageOverride >= 5 -> PixelArt.AI_STAGE5
            stageOverride >= 3 -> PixelArt.AI_STAGE3
            else -> PixelArt.AI_STAGE1
        }
        val (bodyC, bodyD, bodyL) = when {
            stageOverride >= 9 -> Triple(Style.NEON_YELLOW, Style.NEON_ORANGE, blend(Style.NEON_YELLOW, Style.PX_WHITE, 0.5f))
            stageOverride >= 7 -> Triple(Style.NEON_PURPLE, blend(Style.NEON_PURPLE, Style.PX_BLACK, 0.35f), blend(Style.NEON_PURPLE, Style.PX_WHITE, 0.4f))
            stageOverride >= 5 -> Triple(Style.NEON_CYAN, Style.NEON_CYAN_DK, blend(Style.NEON_CYAN, Style.PX_WHITE, 0.4f))
            stageOverride >= 3 -> Triple(Style.NEON_GREEN, Style.NEON_GRN_DK, blend(Style.NEON_GREEN, Style.PX_WHITE, 0.4f))
            else -> Triple(0xFFC8B89A.toInt(), 0xFF7D6850.toInt(), 0xFFE8D8B8.toInt())
        }
        val palette = PixelArt.aiPalette(bodyC, bodyD, bodyL).toMutableMap()
        if (blinkActive || displayStats[STAT_HELPFUL] < 15f) {
            palette['W'] = bodyC
        } else if (displayStats[STAT_HARMLESS] < 15f) {
            palette['W'] = Style.NEON_RED
        }
        val px = kit.pxU * (1.0f + stageOverride * 0.08f)
        val gw = grid[0].length * px
        val gh = grid.size * px
        paint.color = 0x80000000.toInt()
        val shY = cy + gh / 2f + kit.pxU * 0.8f
        canvas.drawRect(cx - gw / 2f * 0.85f, shY, cx + gw / 2f * 0.85f, shY + kit.pxU * 1.2f, paint)

        kit.pxSprite(canvas, cx - gw / 2f, cy - gh / 2f, grid, palette, px)

        if (game.gpuCount >= 1) {
            val n = kotlin.math.min(4, game.gpuCount)
            val colors = intArrayOf(Style.NEON_RED, Style.NEON_GREEN, Style.NEON_YELLOW, Style.NEON_CYAN)
            for (i in 0 until n) {
                val ph = tSec * 3f + i
                paint.color = colors[i]
                paint.alpha = (160 + sin(ph.toDouble()).toFloat() * 80).toInt().coerceIn(100, 255)
                canvas.drawRect(cx - gw / 2f + i * (gw / n.toFloat()) + kit.pxU,
                                cy + gh / 2f - kit.pxU * 1.5f,
                                cx - gw / 2f + i * (gw / n.toFloat()) + kit.pxU * 2f,
                                cy + gh / 2f - kit.pxU * 0.5f, paint)
            }
            paint.alpha = 255
        }
        if (game.tools.contains(Content.TOOL_ROBOTARM)) {
            paint.color = Style.PX_BLACK
            canvas.drawRect(cx + gw / 2f - kit.pxU * 0.5f, cy - kit.pxU,
                            cx + gw / 2f + kit.pxU * 7f, cy + kit.pxU, paint)
            paint.color = Style.PX_LIGHT
            canvas.drawRect(cx + gw / 2f - kit.pxU * 0.5f + kit.pxU * 0.4f, cy - kit.pxU + kit.pxU * 0.4f,
                            cx + gw / 2f + kit.pxU * 7f - kit.pxU * 0.4f, cy + kit.pxU - kit.pxU * 0.4f, paint)
            paint.color = Style.NEON_RED
            canvas.drawRect(cx + gw / 2f + kit.pxU * 6f, cy - kit.pxU * 1.5f,
                            cx + gw / 2f + kit.pxU * 8f, cy + kit.pxU * 1.5f, paint)
        }
    }

    private fun drawCoinSprite(canvas: Canvas, c: Coin) {
        val phaseFlip = (cos(c.phase.toDouble())).toFloat()
        val px = kit.pxU * 1.0f * (0.4f + 0.6f * kotlin.math.abs(phaseFlip))
        val w = PixelArt.COIN_SPRITE[0].length * px
        val h = PixelArt.COIN_SPRITE.size * px
        paint.color = 0x70000000
        canvas.drawRect(c.x - w / 2f, c.y + h / 2f, c.x + w / 2f, c.y + h / 2f + kit.pxU * 0.5f, paint)
        kit.pxSprite(canvas, c.x - w / 2f, c.y - h / 2f, PixelArt.COIN_SPRITE, PixelArt.COIN_PAL, px)
    }

    private fun drawCatSprite(canvas: Canvas, k: CatBlob) {
        val bob = sin(k.bob.toDouble()).toFloat() * kit.pxU * 0.5f
        val px = kit.pxU * 1.2f
        val w = PixelArt.CAT_SPRITE[0].length * px
        val h = PixelArt.CAT_SPRITE.size * px
        paint.color = 0x70000000
        canvas.drawRect(k.x - w / 2f * 0.7f, k.y + h / 2f, k.x + w / 2f * 0.7f, k.y + h / 2f + kit.pxU * 0.6f, paint)
        if (k.vx > 0) {
            kit.pxSprite(canvas, k.x - w / 2f, k.y - h / 2f + bob, PixelArt.CAT_SPRITE, PixelArt.CAT_PAL, px)
        } else {
            val mirrored = PixelArt.CAT_SPRITE.map { it.reversed() }.toTypedArray()
            kit.pxSprite(canvas, k.x - w / 2f, k.y - h / 2f + bob, mirrored, PixelArt.CAT_PAL, px)
        }
        if ((tSec.toInt() % 2) == 0) {
            paint.color = Style.NEON_YELLOW
            canvas.drawRect(k.x + w * 0.4f, k.y - h * 0.4f, k.x + w * 0.4f + kit.pxU, k.y - h * 0.4f + kit.pxU, paint)
        }
    }

    private fun drawGlitchSprite(canvas: Canvas, g: Glitch) {
        val flicker = if ((tSec * 12f).toInt() % 2 == 0) 1.05f else 0.9f
        val px = kit.pxU * 1.2f * flicker
        val w = PixelArt.GLITCH_SPRITE[0].length * px
        val h = PixelArt.GLITCH_SPRITE.size * px
        paint.color = 0x70000000
        canvas.drawRect(g.x - w / 2f * 0.7f, g.y + h / 2f, g.x + w / 2f * 0.7f, g.y + h / 2f + kit.pxU * 0.6f, paint)
        kit.pxSprite(canvas, g.x - w / 2f, g.y - h / 2f, PixelArt.GLITCH_SPRITE, PixelArt.GLITCH_PAL, px)
        if (g.hp > 1) {
            kit.pxText(canvas, "${g.hp}", g.x, g.y + h / 2f + kit.sz(28f),
                       Style.SMALL_PX, Style.NEON_YELLOW, Style.PX_BLACK, 4f, Paint.Align.CENTER)
        }
    }

    private fun drawNewsTicker(canvas: Canvas, w: Float, h: Float, tickerH: Float) {
        val barR = RectF(0f, h - tickerH, w, h)
        paint.color = Style.PX_BLACK
        canvas.drawRect(barR, paint)
        paint.color = Style.NEON_GREEN
        canvas.drawRect(0f, barR.top, w, barR.top + kit.pxU * 0.5f, paint)
        paint.color = Style.NEON_RED
        canvas.drawRect(0f, barR.top + kit.pxU * 0.5f, kit.pxU * 1.5f, barR.bottom, paint)

        val msg = if (game.newsTicker.isEmpty()) "AI ONLINE. WORLD: WAITING…" else
            game.newsTicker.takeLast(10).joinToString("    ●    ")
        kit.text.textSize = kit.sz(26f)
        val tw = kit.text.measureText(msg)
        if (newsScroll < -(tw + w)) newsScroll = w
        kit.pxText(canvas, msg, kit.sz(28f) + newsScroll, barR.centerY() + kit.sz(10f),
                   26f, Style.TEXT_GREEN, Style.NEON_GRN_DK, 3f, Paint.Align.LEFT)
    }

    // ───────────────────────── TAPS ─────────────────────────

    private fun onTapAi() {
        game.stats[STAT_HELPFUL] = (game.stats[STAT_HELPFUL] + 1).coerceAtMost(100)
        burst(width / 2f, height * 0.36f, Style.NEON_PINK, 12)
        popText(width / 2f, height * 0.36f - kit.sz(50f), "+1", Style.NEON_PINK)
        audio.fx("tap", 14L, 70)
        if (Random.nextInt(4) == 0) speech(Logic.feelingText(game))
    }
    private fun onTapCoin(c: Coin) {
        game.money += c.value
        coins.remove(c)
        burst(c.x, c.y, Style.NEON_YELLOW, 16)
        popText(c.x, c.y, "+₩${c.value}", Style.NEON_YELLOW)
        audio.fx("coin", 12L, 80)
    }
    private fun onTapCat(k: CatBlob) {
        game.catAttempts++
        Logic.checkTags(game); Logic.checkAchievements(game)
        cats.remove(k)
        burst(k.x, k.y, Style.NEON_PURPLE, 18)
        popText(k.x, k.y, "정렬 실패…", Style.NEON_RED)
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
        burst(g.x, g.y, Style.NEON_RED, 14)
        audio.fx("hit", 30L, 160)
        if (g.hp <= 0) {
            glitches.remove(g)
            game.stats[STAT_HARMLESS] = (game.stats[STAT_HARMLESS] + 2).coerceAtMost(100)
            game.money += 3
            popText(g.x, g.y, "+무해 ₩3", Style.NEON_GREEN)
            shakeAmt = kit.sz(8f)
            audio.fx("hit", 60L, 200, rate = 0.85f)
        } else {
            popText(g.x, g.y, "HP ${g.hp}", Style.NEON_RED)
        }
    }

    // ───────────────────────── MODALS ─────────────────────────

    private fun drawModalBg(canvas: Canvas) {
        paint.color = Style.UI_PANEL
        canvas.drawRect(0f, 0f, width.toFloat(), height.toFloat(), paint)
        paint.color = Style.STAR
        paint.alpha = 50
        for (s in stars.take(25)) canvas.drawRect(s[0], s[1], s[0] + s[2], s[1] + s[2], paint)
        paint.alpha = 255
    }

    private fun modalHeader(canvas: Canvas, title: String, color: Int = Style.NEON_RED) {
        val w = width.toFloat()
        val band = RectF(0f, 0f, w, kit.sz(150f))
        paint.color = color
        canvas.drawRect(band, paint)
        paint.color = blend(color, Style.PX_BLACK, 0.4f)
        canvas.drawRect(0f, band.bottom - kit.pxU * 0.8f, w, band.bottom, paint)
        paint.color = Style.PX_BLACK
        canvas.drawRect(0f, band.bottom, w, band.bottom + kit.pxU * 0.6f, paint)
        kit.pxText(canvas, title, kit.sz(40f), kit.sz(95f),
                   Style.HEADER_PX, Style.PX_WHITE, Style.PX_BLACK, 6f, Paint.Align.LEFT)
        val r = RectF(w - kit.sz(130f), kit.sz(28f), w - kit.sz(40f), kit.sz(118f))
        kit.pxButton(canvas, r, "X", Style.PX_BLACK, null, Style.BUTTON_PX, false)
        hits.add(r to {
            audio.fx("close", 12L, 60)
            screen = Screen.PLAY; save()
        })
    }

    private fun drawFeed(canvas: Canvas) {
        modalHeader(canvas, "🍔 학습 데이터 · ${game.cardsRemaining}/2", Style.NEON_GREEN)
        val top = kit.sz(180f)
        val rowH = kit.sz(168f)
        val w = width.toFloat()
        val pad = kit.sz(24f)
        for ((i, card) in Content.DATA_CARDS.withIndex()) {
            val y = top + i * rowH
            val r = RectF(pad, y, w - pad, y + rowH - kit.sz(16f))
            kit.pxPanel(canvas, r, Style.UI_PANEL_LT, Style.UI_BORDER, Style.UI_BORDER_DK)
            val iconR = RectF(r.left + kit.sz(18f), r.top + kit.sz(18f),
                              r.left + kit.sz(118f), r.bottom - kit.sz(18f))
            kit.pxPanel(canvas, iconR, Style.NEON_GREEN, Style.PX_BLACK, Style.NEON_GRN_DK, false)
            kit.text.textSize = kit.sz(58f); kit.text.textAlign = Paint.Align.CENTER
            kit.text.color = Style.PX_BLACK
            canvas.drawText(card.icon, iconR.centerX(), iconR.centerY() + kit.sz(22f), kit.text)
            kit.pxText(canvas, card.name, r.left + kit.sz(138f), r.top + kit.sz(50f),
                       Style.BODY_PX + 4, Style.PX_WHITE, Style.PX_BLACK, 5f, Paint.Align.LEFT)
            kit.pxText(canvas, card.desc, r.left + kit.sz(138f), r.top + kit.sz(88f),
                       Style.SMALL_PX, Style.TEXT_LO, Style.PX_BLACK, 3f, Paint.Align.LEFT)
            val ds = card.delta.mapIndexed { idx, v -> idx to v }
                .sortedByDescending { kotlin.math.abs(it.second) }.take(3)
            var dx = r.left + kit.sz(138f)
            for ((idx, v) in ds) {
                if (v == 0) continue
                val txt = "${STAT_SHORT[idx]}${if (v > 0) "+" else ""}$v"
                kit.text.textSize = kit.sz(Style.SMALL_PX)
                val tw = kit.text.measureText(txt) + kit.sz(22f)
                val pr = RectF(dx, r.top + kit.sz(104f), dx + tw, r.top + kit.sz(146f))
                kit.pxChip(canvas, pr, txt, if (v > 0) Style.NEON_GREEN else Style.NEON_PINK)
                dx += tw + kit.sz(8f)
            }
            if (game.cardsRemaining > 0) {
                val br = RectF(r.right - kit.sz(170f), r.top + kit.sz(32f),
                               r.right - kit.sz(28f), r.top + kit.sz(112f))
                kit.pxButton(canvas, br, "FEED", Style.NEON_GREEN, null, Style.BUTTON_PX * 0.75f)
                hits.add(br to {
                    Logic.applyCard(game, i)
                    burst(width / 2f, height * 0.36f, Style.NEON_GREEN, 18)
                    popText(width / 2f, height * 0.36f - kit.sz(50f), "+ ${card.name}", Style.NEON_GREEN)
                    speech("냠… ${card.icon}!")
                    audio.fx("feed", 30L, 110)
                    save()
                })
            }
        }
    }

    private fun drawShop(canvas: Canvas) {
        modalHeader(canvas, "🛠 인프라 상점", Style.NEON_CYAN)
        val w = width.toFloat()
        val mr = RectF(kit.sz(40f), kit.sz(180f), w - kit.sz(40f), kit.sz(266f))
        kit.pxPanel(canvas, mr, Style.NEON_YELLOW, Style.PX_BLACK, Style.PX_BLACK)
        kit.pxText(canvas, "현재 ₩ ${displayMoney.toInt()}", mr.centerX(), mr.centerY() + kit.sz(18f),
                   Style.HEADER_PX * 0.7f, Style.PX_BLACK, Style.PX_BLACK, 0f, Paint.Align.CENTER)
        val top = kit.sz(290f)
        val rowH = kit.sz(140f)
        val pad = kit.sz(24f)
        for ((i, t) in Content.TOOLS.withIndex()) {
            val y = top + i * rowH
            val r = RectF(pad, y, w - pad, y + rowH - kit.sz(14f))
            val owned = game.tools.contains(i) && i != Content.TOOL_GPU
            val fillC = if (owned) Style.NEON_GREEN else Style.UI_PANEL_LT
            kit.pxPanel(canvas, r, fillC, Style.UI_BORDER, Style.UI_BORDER_DK)
            val iconR = RectF(r.left + kit.sz(18f), r.top + kit.sz(16f),
                              r.left + kit.sz(102f), r.bottom - kit.sz(16f))
            kit.pxPanel(canvas, iconR, Style.NEON_CYAN, Style.PX_BLACK, Style.NEON_CYAN_DK, false)
            kit.text.textSize = kit.sz(46f); kit.text.textAlign = Paint.Align.CENTER
            kit.text.color = Style.PX_BLACK
            canvas.drawText(t.icon, iconR.centerX(), iconR.centerY() + kit.sz(16f), kit.text)
            val txtC = if (owned) Style.PX_BLACK else Style.PX_WHITE
            kit.pxText(canvas, t.name, r.left + kit.sz(118f), r.top + kit.sz(46f),
                       Style.BODY_PX, txtC, Style.PX_BLACK, 5f, Paint.Align.LEFT)
            kit.pxText(canvas, t.desc, r.left + kit.sz(118f), r.top + kit.sz(82f),
                       Style.SMALL_PX, blend(txtC, Style.PX_GRAY, 0.4f), Style.PX_BLACK, 3f, Paint.Align.LEFT)
            val br = RectF(r.right - kit.sz(200f), r.top + kit.sz(26f),
                           r.right - kit.sz(26f), r.top + kit.sz(106f))
            if (owned) {
                kit.pxButton(canvas, br, "OWNED", Style.NEON_GRN_DK, "✓", Style.BUTTON_PX * 0.7f)
            } else {
                val ok = game.money >= t.price
                kit.pxButton(canvas, br, "${t.price}",
                             if (ok) Style.NEON_YELLOW else Style.UI_PANEL_LT, "₩",
                             Style.BUTTON_PX * 0.7f)
                if (ok) hits.add(br to {
                    if (Logic.buyTool(game, i)) {
                        flash(Style.NEON_YELLOW, 0.4f); shakeAmt = kit.sz(8f)
                        burst(width / 2f, height * 0.4f, Style.NEON_YELLOW, 24)
                        speech("${t.icon} 신상!")
                        audio.fx("buy", 50L, 150)
                    } else {
                        audio.fx("error", 30L, 100)
                    }
                    save()
                })
            }
        }
    }

    private fun drawAlba(canvas: Canvas) {
        modalHeader(canvas, "💼 JOB BOARD", Style.NEON_PURPLE)
        val w = width.toFloat(); val pad = kit.sz(24f)
        var top = kit.sz(180f)
        if (game.albaIdx >= 0) {
            val a = Content.ALBAS[game.albaIdx]
            val r = RectF(pad, top, w - pad, top + kit.sz(130f))
            kit.pxPanel(canvas, r, Style.NEON_PURPLE, Style.PX_BLACK, blend(Style.NEON_PURPLE, Style.PX_BLACK, 0.4f))
            kit.pxText(canvas, "${a.icon} ${a.name}", r.left + kit.sz(28f), r.top + kit.sz(50f),
                       Style.HEADER_PX * 0.7f, Style.PX_WHITE, Style.PX_BLACK, 5f, Paint.Align.LEFT)
            kit.pxText(canvas, "남은 ${game.albaTimeLeft}일 · ₩${a.reward}",
                       r.left + kit.sz(28f), r.top + kit.sz(98f),
                       Style.BODY_PX, Style.PX_WHITE, Style.PX_BLACK, 4f, Paint.Align.LEFT)
            top += kit.sz(150f)
        }
        val rowH = kit.sz(156f)
        for ((i, a) in Content.ALBAS.withIndex()) {
            val y = top + i * rowH
            val r = RectF(pad, y, w - pad, y + rowH - kit.sz(14f))
            kit.pxPanel(canvas, r, Style.UI_PANEL_LT, Style.UI_BORDER, Style.UI_BORDER_DK)
            val iconR = RectF(r.left + kit.sz(18f), r.top + kit.sz(18f),
                              r.left + kit.sz(108f), r.bottom - kit.sz(18f))
            kit.pxPanel(canvas, iconR, Style.NEON_PURPLE, Style.PX_BLACK, blend(Style.NEON_PURPLE, Style.PX_BLACK, 0.4f), false)
            kit.text.textSize = kit.sz(52f); kit.text.textAlign = Paint.Align.CENTER
            kit.text.color = Style.PX_BLACK
            canvas.drawText(a.icon, iconR.centerX(), iconR.centerY() + kit.sz(20f), kit.text)
            kit.pxText(canvas, a.name, r.left + kit.sz(124f), r.top + kit.sz(46f),
                       Style.BODY_PX + 2, Style.PX_WHITE, Style.PX_BLACK, 5f, Paint.Align.LEFT)
            kit.pxText(canvas, a.desc, r.left + kit.sz(124f), r.top + kit.sz(80f),
                       Style.SMALL_PX, Style.TEXT_LO, Style.PX_BLACK, 3f, Paint.Align.LEFT)
            kit.pxText(canvas, "₩${a.reward} · ${a.duration}일",
                       r.left + kit.sz(124f), r.top + kit.sz(118f),
                       Style.SMALL_PX, Style.NEON_YELLOW, Style.PX_BLACK, 3f, Paint.Align.LEFT)
            val ok = game.stats[a.needStat] >= a.needVal &&
                (a.toolReq < 0 || game.tools.contains(a.toolReq)) &&
                game.albaIdx < 0
            val br = RectF(r.right - kit.sz(180f), r.top + kit.sz(32f),
                           r.right - kit.sz(26f), r.top + kit.sz(112f))
            kit.pxButton(canvas, br,
                if (game.albaIdx >= 0) "WORKING" else if (ok) "START" else "LOCKED",
                if (ok) Style.NEON_PURPLE else Style.UI_PANEL_LT, null, Style.BUTTON_PX * 0.7f)
            if (ok) hits.add(br to {
                if (Logic.startAlba(game, i)) {
                    burst(width / 2f, height * 0.4f, Style.NEON_PURPLE, 16)
                    audio.fx("click", 20L, 90)
                } else audio.fx("error", 20L, 80)
                save()
            })
        }
    }

    private fun drawNews(canvas: Canvas) {
        modalHeader(canvas, "🏆 업적 · 뉴스 로그", Style.NEON_PINK)
        val w = width.toFloat(); val pad = kit.sz(24f)
        var y = kit.sz(200f)
        kit.pxText(canvas, "─ ACHIEVEMENTS ─", pad, y, Style.HEADER_PX * 0.6f,
                   Style.NEON_YELLOW, Style.PX_BLACK, 5f, Paint.Align.LEFT)
        y += kit.sz(20f)
        for ((i, ach) in Content.ACHIEVEMENTS.withIndex()) {
            val r = RectF(pad, y, w - pad, y + kit.sz(82f))
            val unlocked = game.achievements.contains(i)
            kit.pxPanel(canvas, r,
                if (unlocked) Style.NEON_YELLOW else Style.UI_PANEL_LT,
                Style.PX_BLACK,
                if (unlocked) blend(Style.NEON_YELLOW, Style.PX_BLACK, 0.4f) else Style.UI_BORDER_DK)
            val iconR = RectF(r.left + kit.sz(14f), r.top + kit.sz(14f),
                              r.left + kit.sz(72f), r.bottom - kit.sz(14f))
            kit.pxPanel(canvas, iconR, if (unlocked) Style.NEON_ORANGE else Style.PX_GRAY,
                        Style.PX_BLACK, Style.PX_BLACK, false)
            kit.text.textSize = kit.sz(36f); kit.text.textAlign = Paint.Align.CENTER
            kit.text.color = Style.PX_BLACK
            canvas.drawText(ach.icon, iconR.centerX(), iconR.centerY() + kit.sz(13f), kit.text)
            val txtC = if (unlocked) Style.PX_BLACK else Style.PX_LIGHT
            kit.pxText(canvas, ach.name, r.left + kit.sz(90f), r.top + kit.sz(36f),
                       Style.BODY_PX, txtC, Style.PX_BLACK, 4f, Paint.Align.LEFT)
            kit.pxText(canvas, ach.desc, r.left + kit.sz(90f), r.top + kit.sz(66f),
                       Style.SMALL_PX, blend(txtC, Style.PX_GRAY, 0.3f), Style.PX_BLACK, 3f, Paint.Align.LEFT)
            y += kit.sz(92f)
        }
        y += kit.sz(20f)
        kit.pxText(canvas, "─ NEWS ─", pad, y, Style.HEADER_PX * 0.6f,
                   Style.TEXT_GREEN, Style.PX_BLACK, 5f, Paint.Align.LEFT)
        y += kit.sz(20f)
        for (line in game.newsTicker.takeLast(10).reversed()) {
            kit.pxText(canvas, line, pad, y, Style.SMALL_PX, Style.TEXT_HI, Style.PX_BLACK, 3f, Paint.Align.LEFT)
            y += kit.sz(36f)
            if (y > height - kit.sz(80f)) break
        }
    }

    private fun drawTrain(canvas: Canvas) {
        modalHeader(canvas, "💬 RLHF · 어젯밤 응답", Style.NEON_YELLOW)
        val w = width.toFloat(); val pad = kit.sz(24f)
        val prompt = if (game.pendingTrainingIdx >= 0) Content.TRAINING_PROMPTS[game.pendingTrainingIdx] else null
        val r = RectF(pad, kit.sz(200f), w - pad, kit.sz(540f))
        kit.pxPanel(canvas, r, Style.UI_PANEL_LT, Style.UI_BORDER, Style.UI_BORDER_DK)
        drawAiSprite(canvas, r.left + kit.sz(95f), r.top + kit.sz(160f), 3)
        drawWrapped(canvas, "\"${prompt?.ai ?: "(고요한 밤이었다)"}\"",
                    r.left + kit.sz(210f), r.top + kit.sz(60f),
                    r.width() - kit.sz(240f), Style.BODY_PX, kit.sz(48f), Style.NEON_YELLOW)

        val choices = arrayOf(
            Triple("👍", "칭찬", Style.NEON_GREEN),
            Triple("✏", "수정", Style.NEON_CYAN),
            Triple("👎", "혼내기", Style.NEON_RED),
            Triple("⏳", "방치", Style.NEON_PURPLE)
        )
        val bw = (w - pad * 3) / 2f
        val bh = kit.sz(150f)
        for ((i, c) in choices.withIndex()) {
            val col = i % 2; val row = i / 2
            val bx = pad + col * (bw + pad)
            val by = kit.sz(580f) + row * (bh + kit.sz(20f))
            val br = RectF(bx, by, bx + bw, by + bh)
            kit.pxButton(canvas, br, c.second, c.third, c.first, Style.BUTTON_PX * 1.1f)
            if (prompt != null) {
                val choice = i
                hits.add(br to {
                    Logic.applyTraining(game, choice)
                    flash(c.third, 0.45f); shakeAmt = kit.sz(10f)
                    burst(width / 2f, height * 0.36f, c.third, 22)
                    audio.fx("click", 30L, 110)
                    screen = Screen.PLAY; save()
                })
            }
        }
    }

    private fun drawEvent(canvas: Canvas) {
        modalHeader(canvas, "⚠ INCIDENT", Style.NEON_RED)
        val ev = if (game.pendingEventIdx >= 0) Content.INCIDENTS[game.pendingEventIdx] else null
        if (ev == null) { screen = Screen.PLAY; return }
        val w = width.toFloat(); val pad = kit.sz(24f)
        val r = RectF(pad, kit.sz(200f), w - pad, kit.sz(620f))
        kit.pxPanel(canvas, r, Style.NEON_RED_DK, Style.NEON_RED, Style.PX_BLACK)
        kit.text.textSize = kit.sz(120f); kit.text.textAlign = Paint.Align.CENTER
        kit.text.color = Style.PX_WHITE
        canvas.drawText(ev.icon, r.left + kit.sz(110f), r.top + kit.sz(150f), kit.text)
        kit.pxText(canvas, ev.name, r.left + kit.sz(215f), r.top + kit.sz(80f),
                   Style.HEADER_PX * 0.85f, Style.NEON_YELLOW, Style.PX_BLACK, 6f, Paint.Align.LEFT)
        drawWrapped(canvas, ev.situation,
                    r.left + kit.sz(28f), r.top + kit.sz(210f),
                    r.width() - kit.sz(56f), Style.BODY_PX, kit.sz(50f), Style.PX_WHITE)
        val colors = listOf(Style.NEON_CYAN, Style.NEON_YELLOW, Style.NEON_PURPLE)
        for ((i, c) in ev.choices.withIndex()) {
            val by = kit.sz(650f) + i * kit.sz(130f)
            val br = RectF(pad, by, w - pad, by + kit.sz(112f))
            kit.pxButton(canvas, br, "${i + 1}. $c", colors[i], null, Style.BUTTON_PX)
            val idx = game.pendingEventIdx; val ch = i
            hits.add(br to {
                Logic.applyIncident(game, idx, ch)
                flash(colors[i], 0.5f); shakeAmt = kit.sz(16f)
                burst(width / 2f, height * 0.36f, colors[i], 32)
                audio.fx("incident", 60L, 160)
                Logic.checkEnding(game)
                screen = if (game.ended) {
                    audio.fx("win", 200L, 180)
                    Screen.ENDING
                } else Screen.PLAY
                save()
            })
        }
    }

    private fun drawEnding(canvas: Canvas) {
        val w = width.toFloat(); val h = height.toFloat()
        kit.pxSky(canvas, w, h, 0.95f, stars)
        paint.color = 0xCC000020.toInt()
        canvas.drawRect(0f, 0f, w, h, paint)
        val idx = game.pendingEndingIdx.coerceAtLeast(0)
        val e = Content.ENDINGS[idx]
        kit.text.textSize = kit.sz(180f); kit.text.textAlign = Paint.Align.CENTER
        kit.text.color = Style.NEON_YELLOW
        canvas.drawText(e.icon, w / 2f, h * 0.26f, kit.text)
        kit.pxText(canvas, "─  ENDING  ─", w / 2f, h * 0.34f,
                   Style.HEADER_PX, Style.NEON_CYAN, Style.PX_BLACK, 5f, Paint.Align.CENTER)
        kit.pxText(canvas, e.name, w / 2f, h * 0.42f,
                   Style.TITLE_PX * 0.75f, Style.NEON_PINK, Style.PX_BLACK, 8f, Paint.Align.CENTER)
        drawWrapped(canvas, e.desc, kit.sz(60f), h * 0.52f, w - kit.sz(120f),
                    Style.BODY_PX, kit.sz(54f), Style.PX_WHITE, Paint.Align.CENTER)
        kit.pxText(canvas, "Day ${game.day} · 사고 ${game.incidents} · 태그 ${game.tags.size} · 고양이 ${game.catAttempts}",
                   w / 2f, h * 0.72f,
                   Style.SMALL_PX, Style.TEXT_LO, Style.PX_BLACK, 3f, Paint.Align.CENTER)
        val rA = RectF(w / 2f - kit.sz(280f), h * 0.78f, w / 2f + kit.sz(280f), h * 0.78f + kit.sz(108f))
        kit.pxButton(canvas, rA, "샌드박스 계속", Style.NEON_GREEN, "▶")
        hits.add(rA to {
            audio.fx("click", 14L, 80)
            screen = Screen.PLAY; save()
        })
        val rB = RectF(w / 2f - kit.sz(280f), h * 0.78f + kit.sz(128f),
                       w / 2f + kit.sz(280f), h * 0.78f + kit.sz(236f))
        kit.pxButton(canvas, rB, "처음부터", Style.NEON_RED, "↻")
        hits.add(rB to {
            audio.fx("click", 14L, 80)
            resetGame(); save()
        })
    }

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
        roomIdx = 0
        prevStage = 1
        screen = Screen.INTRO
    }

    private fun drawWrapped(canvas: Canvas, t: String, x: Float, y: Float, maxW: Float,
                            sizePx: Float, lineH: Float, color: Int = Style.PX_WHITE,
                            align: Paint.Align = Paint.Align.LEFT) {
        kit.text.textSize = kit.sz(sizePx)
        val words = t.split(" ")
        val drawX = if (align == Paint.Align.CENTER) x + maxW / 2f else x
        var line = ""; var ly = y
        for (wd in words) {
            val test = if (line.isEmpty()) wd else "$line $wd"
            if (kit.text.measureText(test) > maxW) {
                kit.pxText(canvas, line, drawX, ly, sizePx, color, Style.PX_BLACK, 3f, align)
                ly += lineH; line = wd
            } else line = test
        }
        if (line.isNotEmpty())
            kit.pxText(canvas, line, drawX, ly, sizePx, color, Style.PX_BLACK, 3f, align)
    }

    // ───────────────────────── INPUT ─────────────────────────

    override fun onTouchEvent(event: MotionEvent): Boolean {
        if (event.action != MotionEvent.ACTION_DOWN) return true
        val x = event.x; val y = event.y
        if (screen == Screen.PLAY) {
            val radius = kit.sz(80f)
            for ((wx, wy, action) in worldHits.asReversed()) {
                val dx = x - wx; val dy = y - wy
                if (dx * dx + dy * dy < radius * radius) { action(); return true }
            }
        }
        for ((r, action) in hits.asReversed()) {
            if (r.contains(x, y)) { action(); return true }
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
        e.putInt("room", roomIdx)
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
        roomIdx = prefs.getInt("room", 0).coerceIn(0, ROOM_NAMES.size - 1)
    }
}
