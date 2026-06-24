package com.sgnobst.aigotchi

import android.content.Context
import android.media.AudioAttributes
import android.media.SoundPool
import android.os.Build
import android.os.Vibrator
import java.io.File
import java.io.FileOutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.PI
import kotlin.math.exp
import kotlin.math.sin
import kotlin.random.Random

// 8-bit chip SFX synthesizer + SoundPool wrapper + haptic.
// All sounds are generated at app start, written to cache as WAV, loaded into SoundPool.
class Audio(private val ctx: Context) {

    companion object {
        const val SR = 22050
    }

    private val pool: SoundPool
    private val ids = HashMap<String, Int>()
    private val loaded = HashSet<Int>()
    private val vibrator: Vibrator? = try {
        ctx.getSystemService(Context.VIBRATOR_SERVICE) as? Vibrator
    } catch (e: Throwable) { null }

    var muted = false
    var hapticOn = true

    init {
        val attrs = AudioAttributes.Builder()
            .setUsage(AudioAttributes.USAGE_GAME)
            .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
            .build()
        pool = SoundPool.Builder()
            .setMaxStreams(8)
            .setAudioAttributes(attrs)
            .build()
        pool.setOnLoadCompleteListener { _, id, status ->
            if (status == 0) loaded.add(id)
        }
        generateAll()
    }

    fun release() {
        try { pool.release() } catch (_: Throwable) {}
    }

    fun play(name: String, vol: Float = 0.85f, rate: Float = 1f) {
        if (muted) return
        val id = ids[name] ?: return
        try { pool.play(id, vol, vol, 1, 0, rate) } catch (_: Throwable) {}
    }

    fun haptic(ms: Long, amp: Int = 100) {
        if (!hapticOn || ms <= 0L) return
        val v = vibrator ?: return
        try {
            // On API 26+ use VibrationEffect.createOneShot via reflection (compile target = API 23 only).
            // Falls back to the deprecated vibrate(ms) on older devices.
            if (Build.VERSION.SDK_INT >= 26) {
                try {
                    val veCls = Class.forName("android.os.VibrationEffect")
                    val createOneShot = veCls.getMethod("createOneShot",
                        java.lang.Long.TYPE, java.lang.Integer.TYPE)
                    val effect = createOneShot.invoke(null, ms,
                        amp.coerceIn(1, 255))
                    val vibrateMethod = Vibrator::class.java.getMethod("vibrate", veCls)
                    vibrateMethod.invoke(v, effect)
                    return
                } catch (_: Throwable) { /* fall through */ }
            }
            @Suppress("DEPRECATION")
            v.vibrate(ms)
        } catch (_: Throwable) {}
    }

    /** Combined SFX + haptic. */
    fun fx(name: String, vibMs: Long = 0L, vibAmp: Int = 90, vol: Float = 0.85f, rate: Float = 1f) {
        play(name, vol, rate)
        if (vibMs > 0L) haptic(vibMs, vibAmp)
    }

    // ─────────── SFX library ───────────
    private fun generateAll() {
        // UI
        regBuf("click",    blip(900f, 35, 0.35f, env = Env(2f, 30f), wave = WaveType.SQUARE))
        regBuf("tap",      blip(700f, 60, 0.45f, env = Env(2f, 20f), wave = WaveType.SQUARE))
        regBuf("error",    chord(intArrayOf(180, 140), 200, 0.4f, WaveType.SQUARE))
        regBuf("close",    sweep(700f, 350f, 90, 0.4f, WaveType.SQUARE))

        // World feedback
        regBuf("coin",     arp(intArrayOf(880, 1175, 1760), 60, 0.5f))                       // 도-미-라
        regBuf("feed",     noisePuff(120, 0.55f, lowpass = 0.35f))
        regBuf("hit",      thump(220f, 120f, 140, 0.7f))                                      // glitch tap = punchy
        regBuf("cat",      slide(560f, 320f, 240, 0.5f, WaveType.SAW))                       // meowy slide

        // Status events
        regBuf("buy",      arp(intArrayOf(660, 990, 1320), 80, 0.55f))
        regBuf("levelup",  arp(intArrayOf(523, 659, 784, 1047, 1319), 90, 0.55f))
        regBuf("day",      gong(110f, 600, 0.55f))
        regBuf("alert",    twoTone(880, 600, 80, 4, 0.55f))
        regBuf("incident", twoTone(220, 320, 110, 3, 0.6f, WaveType.SQUARE))
        regBuf("win",      arp(intArrayOf(523, 659, 784, 1047, 1319, 1568, 2093), 110, 0.6f))
    }

    private fun regBuf(name: String, samples: ShortArray) {
        try {
            val f = File(ctx.cacheDir, "sfx_$name.wav")
            FileOutputStream(f).use { it.write(toWav(samples)) }
            val id = pool.load(f.absolutePath, 1)
            ids[name] = id
        } catch (_: Throwable) {
            // Skip — game still works without sound
        }
    }

    // ─────────── Synth primitives ───────────
    private enum class WaveType { SQUARE, SAW, SINE, NOISE }

    private data class Env(val attackMs: Float, val releaseMs: Float)

    private fun blip(freq: Float, durMs: Int, amp: Float,
                     env: Env = Env(3f, durMs * 0.7f),
                     wave: WaveType = WaveType.SQUARE): ShortArray {
        val n = (SR * durMs / 1000)
        val out = ShortArray(n)
        val attack = (SR * env.attackMs / 1000f).coerceAtLeast(1f)
        val release = (SR * env.releaseMs / 1000f).coerceAtLeast(1f)
        for (i in 0 until n) {
            val envVal = when {
                i < attack -> i / attack
                i > n - release -> ((n - i) / release).coerceAtLeast(0f)
                else -> 1f
            }
            val v = sample(wave, freq, i) * amp * envVal
            out[i] = clip(v)
        }
        return out
    }

    private fun chord(freqs: IntArray, durMs: Int, amp: Float, wave: WaveType): ShortArray {
        val n = (SR * durMs / 1000)
        val out = ShortArray(n)
        for (i in 0 until n) {
            var v = 0f
            for (f in freqs) v += sample(wave, f.toFloat(), i)
            v = v / freqs.size * amp * exp(-3f * i / n)
            out[i] = clip(v)
        }
        return out
    }

    private fun arp(freqs: IntArray, noteMs: Int, amp: Float,
                    wave: WaveType = WaveType.SQUARE): ShortArray {
        val total = freqs.size * noteMs
        val n = SR * total / 1000
        val out = ShortArray(n)
        val noteLen = SR * noteMs / 1000
        for (k in freqs.indices) {
            val f = freqs[k].toFloat()
            for (j in 0 until noteLen) {
                val i = k * noteLen + j
                if (i >= n) break
                val env = exp(-2f * j / noteLen)
                val v = sample(wave, f, j) * amp * env
                out[i] = clip(v)
            }
        }
        return out
    }

    private fun sweep(fromHz: Float, toHz: Float, durMs: Int, amp: Float,
                      wave: WaveType): ShortArray {
        val n = SR * durMs / 1000
        val out = ShortArray(n)
        var phase = 0f
        for (i in 0 until n) {
            val t = i / n.toFloat()
            val f = fromHz + (toHz - fromHz) * t
            val step = 2f * PI.toFloat() * f / SR
            phase += step
            val raw = when (wave) {
                WaveType.SQUARE -> if (sin(phase) >= 0) 1f else -1f
                WaveType.SAW    -> ((phase / (2f * PI.toFloat())) % 1f) * 2f - 1f
                WaveType.SINE   -> sin(phase)
                WaveType.NOISE  -> Random.nextFloat() * 2f - 1f
            }
            val env = exp(-2f * t)
            out[i] = clip(raw * amp * env)
        }
        return out
    }

    private fun slide(fromHz: Float, toHz: Float, durMs: Int, amp: Float,
                      wave: WaveType): ShortArray = sweep(fromHz, toHz, durMs, amp, wave)

    private fun noisePuff(durMs: Int, amp: Float, lowpass: Float = 0.4f): ShortArray {
        val n = SR * durMs / 1000
        val out = ShortArray(n)
        var prev = 0f
        for (i in 0 until n) {
            val raw = Random.nextFloat() * 2f - 1f
            // simple 1-pole low-pass
            prev = prev + lowpass * (raw - prev)
            val env = exp(-3f * i / n)
            out[i] = clip(prev * amp * env)
        }
        return out
    }

    private fun thump(startHz: Float, endHz: Float, durMs: Int, amp: Float): ShortArray {
        val n = SR * durMs / 1000
        val out = ShortArray(n)
        // pitch-falling sine + noise click at start
        val click = noisePuff(8, amp * 1.2f, lowpass = 0.9f)
        var phase = 0f
        for (i in 0 until n) {
            val t = i / n.toFloat()
            val f = endHz + (startHz - endHz) * exp(-6f * t)
            phase += 2f * PI.toFloat() * f / SR
            val env = exp(-4f * t)
            var v = sin(phase) * amp * env
            if (i < click.size) v += click[i] / 32767f * 0.6f
            out[i] = clip(v)
        }
        return out
    }

    private fun gong(baseHz: Float, durMs: Int, amp: Float): ShortArray {
        val n = SR * durMs / 1000
        val out = ShortArray(n)
        val partials = floatArrayOf(1f, 2.7f, 5.4f, 8.9f)
        val partialAmp = floatArrayOf(1f, 0.45f, 0.25f, 0.12f)
        val phases = FloatArray(partials.size)
        for (i in 0 until n) {
            var v = 0f
            for (p in partials.indices) {
                val f = baseHz * partials[p]
                phases[p] += 2f * PI.toFloat() * f / SR
                v += sin(phases[p]) * partialAmp[p]
            }
            val env = exp(-2.2f * i / n)
            out[i] = clip(v / 1.8f * amp * env)
        }
        return out
    }

    private fun twoTone(a: Int, b: Int, eachMs: Int, repeats: Int, amp: Float,
                        wave: WaveType = WaveType.SINE): ShortArray {
        val seg = SR * eachMs / 1000
        val n = seg * 2 * repeats
        val out = ShortArray(n)
        var phase = 0f
        for (i in 0 until n) {
            val seg2 = (i / seg) % 2
            val f = if (seg2 == 0) a.toFloat() else b.toFloat()
            phase += 2f * PI.toFloat() * f / SR
            val raw = when (wave) {
                WaveType.SQUARE -> if (sin(phase) >= 0) 1f else -1f
                WaveType.SAW    -> ((phase / (2f * PI.toFloat())) % 1f) * 2f - 1f
                WaveType.SINE   -> sin(phase)
                WaveType.NOISE  -> Random.nextFloat() * 2f - 1f
            }
            val env = 0.5f + 0.5f * exp(-3f * (i % seg) / seg)
            out[i] = clip(raw * amp * env)
        }
        return out
    }

    private fun sample(w: WaveType, freq: Float, i: Int): Float {
        val period = SR / freq
        return when (w) {
            WaveType.SQUARE -> if ((i % period.toInt()) < period / 2) 1f else -1f
            WaveType.SAW    -> ((i % period.toInt()) / period) * 2f - 1f
            WaveType.SINE   -> sin(2f * PI.toFloat() * freq * i / SR)
            WaveType.NOISE  -> Random.nextFloat() * 2f - 1f
        }
    }

    private fun clip(v: Float): Short {
        val s = (v * 32767f).toInt().coerceIn(-32768, 32767)
        return s.toShort()
    }

    // ─────────── WAV writer ───────────
    private fun toWav(samples: ShortArray): ByteArray {
        val n = samples.size
        val dataBytes = n * 2
        val total = 36 + dataBytes
        val bb = ByteBuffer.allocate(44 + dataBytes).order(ByteOrder.LITTLE_ENDIAN)
        bb.put("RIFF".toByteArray(Charsets.US_ASCII))
        bb.putInt(total)
        bb.put("WAVE".toByteArray(Charsets.US_ASCII))
        bb.put("fmt ".toByteArray(Charsets.US_ASCII))
        bb.putInt(16)
        bb.putShort(1)                  // PCM
        bb.putShort(1)                  // mono
        bb.putInt(SR)
        bb.putInt(SR * 2)               // byte rate (mono 16-bit)
        bb.putShort(2)                  // block align
        bb.putShort(16)                 // bits per sample
        bb.put("data".toByteArray(Charsets.US_ASCII))
        bb.putInt(dataBytes)
        for (s in samples) bb.putShort(s)
        return bb.array()
    }
}
