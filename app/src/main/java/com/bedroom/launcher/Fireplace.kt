package com.bedroom.launcher

import android.app.Activity
import android.content.Context
import android.graphics.Color
import android.media.MediaPlayer
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.view.Gravity
import android.view.KeyEvent
import android.view.SurfaceHolder
import android.view.SurfaceView
import android.view.View
import android.view.WindowManager
import android.widget.FrameLayout
import android.widget.TextView
import java.io.File

/**
 * "Fireplace": its own app in the TV's app list. A 4K fireplace video (made by tools/fireplace_render.py,
 * an original procedural fire, no footage) played on the TV's video layer, which is the only part of this
 * TV that shows true 4K, with the crackling-fire sound from Sleep Sounds.
 *
 * OK = sound on / off, Up / Down = louder / quieter, Back = leave. Turns itself off after 4 hours.
 * The video is copied to the TV by tools\update.ps1 (it's too big to go inside the app).
 */
class FireplaceActivity : Activity(), SurfaceHolder.Callback {
    companion object {
        fun videoFile(ctx: Context) = File(ctx.getExternalFilesDir(null), "fireplace/fireplace-4k.mp4")
        private const val AUTO_OFF_MS = 4 * 60 * 60_000L
    }

    private var player: MediaPlayer? = null
    private lateinit var note: TextView
    private val handler = Handler(Looper.getMainLooper())
    private var startedSound = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        @Suppress("DEPRECATION")
        window.decorView.systemUiVisibility = View.SYSTEM_UI_FLAG_FULLSCREEN or View.SYSTEM_UI_FLAG_HIDE_NAVIGATION
        val root = FrameLayout(this).apply { setBackgroundColor(Color.BLACK) }
        val surface = SurfaceView(this)
        surface.holder.addCallback(this)
        root.addView(surface, FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT))
        note = TextView(this).apply {
            setTextColor(0xCCFFE0B0.toInt())
            textSize = 16f
            setShadowLayer(8f, 0f, 0f, Color.BLACK)
            setPadding(48, 32, 48, 32)
        }
        root.addView(note, FrameLayout.LayoutParams(FrameLayout.LayoutParams.WRAP_CONTENT, FrameLayout.LayoutParams.WRAP_CONTENT, Gravity.BOTTOM or Gravity.START))
        setContentView(root)
        SleepSoundEngine.appCtx = applicationContext
        if (BedroomPrefs.get(this).getBoolean("fireSound", true)) startSound()
        flash("OK: sound on/off · Up/Down: volume · Back: leave")
        handler.postDelayed({ finish() }, AUTO_OFF_MS)
    }

    private fun flash(text: String) {
        note.text = text
        note.alpha = 1f
        note.animate().cancel()
        note.animate().alpha(0f).setStartDelay(3500).setDuration(1200).start()
    }

    private fun startSound() {
        if (SleepSoundEngine.playing.value == null) {
            SleepSoundEngine.play("fire")
            startedSound = true
        }
    }

    override fun surfaceCreated(holder: SurfaceHolder) {
        val f = videoFile(this)
        if (!f.exists()) {
            note.animate().cancel()
            note.alpha = 1f
            note.text = "The fireplace video isn't on the TV yet.\nRun tools\\update.cmd on the PC (it copies it over)."
            return
        }
        try {
            player = MediaPlayer().apply {
                setDataSource(f.path)
                setDisplay(holder)
                isLooping = true
                setVolume(0f, 0f) // the video is silent; the crackle comes from Sleep Sounds
                setOnPreparedListener { it.start() }
                setOnErrorListener { _, what, extra ->
                    Log.w(TAG, "Fireplace video error $what/$extra")
                    note.alpha = 1f
                    note.text = "This TV couldn't play the fireplace video ($what/$extra)."
                    true
                }
                prepareAsync()
            }
        } catch (e: Exception) {
            Log.w(TAG, "Fireplace video failed", e)
            note.alpha = 1f
            note.text = "Couldn't open the fireplace video."
        }
    }

    override fun surfaceChanged(holder: SurfaceHolder, format: Int, width: Int, height: Int) {}

    override fun surfaceDestroyed(holder: SurfaceHolder) {
        player?.release()
        player = null
    }

    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        Idle.poke()
        if (event.action != KeyEvent.ACTION_UP) return when (event.keyCode) {
            KeyEvent.KEYCODE_DPAD_CENTER, KeyEvent.KEYCODE_ENTER, KeyEvent.KEYCODE_DPAD_UP, KeyEvent.KEYCODE_DPAD_DOWN -> true
            else -> super.dispatchKeyEvent(event)
        }
        when (event.keyCode) {
            KeyEvent.KEYCODE_DPAD_CENTER, KeyEvent.KEYCODE_ENTER -> {
                val on = SleepSoundEngine.playing.value == "fire"
                if (on) { SleepSoundEngine.stop(); startedSound = false } else startSound()
                BedroomPrefs.get(this).edit().putBoolean("fireSound", !on).apply()
                flash(if (on) "Sound off" else "Crackling sound on")
                return true
            }
            KeyEvent.KEYCODE_DPAD_UP -> { SleepSoundEngine.setVolume(SleepSoundEngine.volume.value + 10); flash("Sound ${SleepSoundEngine.volume.value}%"); return true }
            KeyEvent.KEYCODE_DPAD_DOWN -> { SleepSoundEngine.setVolume(SleepSoundEngine.volume.value - 10); flash("Sound ${SleepSoundEngine.volume.value}%"); return true }
        }
        return super.dispatchKeyEvent(event)
    }

    override fun onStop() {
        super.onStop()
        // Leaving the fireplace: stop the crackle we started (not a sleep sound you picked yourself).
        if (startedSound && SleepSoundEngine.playing.value == "fire") SleepSoundEngine.stop()
        startedSound = false
        if (!isFinishing) finish()
    }

    override fun onDestroy() {
        handler.removeCallbacksAndMessages(null)
        player?.release()
        player = null
        super.onDestroy()
    }
}
