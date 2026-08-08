package com.sgnobst.aigotchi

import android.app.Activity
import android.os.Bundle
import android.view.Window
import android.view.WindowManager

class MainActivity : Activity() {
    private lateinit var view: GameView
    private lateinit var audio: Audio

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        requestWindowFeature(Window.FEATURE_NO_TITLE)
        window.setFlags(
            WindowManager.LayoutParams.FLAG_FULLSCREEN,
            WindowManager.LayoutParams.FLAG_FULLSCREEN
        )
        volumeControlStream = android.media.AudioManager.STREAM_MUSIC
        audio = Audio(this)
        view = GameView(this, audio)
        setContentView(view)
    }

    override fun onPause() {
        super.onPause()
        view.save()
    }

    override fun onDestroy() {
        super.onDestroy()
        audio.release()
    }
}
