package com.bedroom.launcher

import android.app.Activity
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.os.Bundle
import android.os.Handler
import android.util.Log
import android.view.KeyEvent
import android.view.View
import android.view.WindowManager
import org.json.JSONObject
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlin.concurrent.thread

/**
 * "Screen off, sound on".
 *
 * Real mode: the TV chip's own backlight switch (MStar TvPictureManager.disableBacklight), called from a
 * separate ":picture" process so a crash in the vendor library can't take the home screen down.
 * Fallback: a black, minimum-brightness screen (if the vendor call isn't allowed).
 *
 * While it's on, an invisible [ScreenOffActivity] sits on top: any remote button brings the picture back
 * (volume and media keys still work normally and don't wake it).
 */
object Backlight {
    private const val JAR = "/vendor/framework/com.mstar.android.jar"
    @Volatile private var binder: android.os.IBinder? = null
    private var loader: ClassLoader? = null

    /** Connect to the TV's own picture service (com.mstar.tv.service), the same one its menus use. */
    private fun service(ctx: Context): android.os.IBinder {
        binder?.takeIf { it.isBinderAlive }?.let { return it }
        val latch = CountDownLatch(1)
        val conn = object : android.content.ServiceConnection {
            override fun onServiceConnected(n: android.content.ComponentName, b: android.os.IBinder) {
                binder = b
                latch.countDown()
            }
            override fun onServiceDisconnected(n: android.content.ComponentName) { binder = null }
        }
        val i = Intent("com.mstar.tv.service.TvService").setPackage("com.mstar.tv.service")
        if (!ctx.bindService(i, conn, Context.BIND_AUTO_CREATE)) error("the TV service refused the connection")
        if (!latch.await(6, TimeUnit.SECONDS)) error("the TV service didn't answer")
        return binder ?: error("no TV service")
    }

    private fun cls(ctx: Context, name: String): Class<*> {
        val cl = loader ?: dalvik.system.PathClassLoader(JAR, ctx.classLoader).also { loader = it }
        return cl.loadClass(name)
    }

    /** Runs in the :picture process, on a binder thread (never the main thread). */
    fun call(ctx: Context, op: String): String = try {
        val b = service(ctx)
        val desc = b.interfaceDescriptor ?: "?"
        val svcIface = cls(ctx, desc)
        val svc = cls(ctx, "$desc\$Stub").getMethod("asInterface", android.os.IBinder::class.java).invoke(null, b)
        val getPic = svcIface.methods.firstOrNull { it.name == "getTvPicture" && it.parameterTypes.isEmpty() }
            ?: error("$desc has no getTvPicture; has: " + svcIface.methods.map { it.name }.sorted().joinToString(","))
        val pic = getPic.invoke(svc) ?: error("getTvPicture returned nothing")
        val picIface = getPic.returnType
        when {
            op == "off" -> { picIface.getMethod("disableBacklight").invoke(pic); "ok off" }
            op == "on" -> { picIface.getMethod("enableBacklight").invoke(pic); "ok on" }
            // Night mode: read / set the backlight level (0-100), the same setting as the TV's own menu.
            op == "level" -> "ok " + picIface.getMethod("getBacklight").invoke(pic)
            op.startsWith("set:") -> {
                val v = op.removePrefix("set:").toInt().coerceIn(0, 100)
                val m = picIface.methods.firstOrNull { it.name == "setBacklight" && it.parameterTypes.size == 1 }
                    ?: error("no setBacklight; has: " + picIface.methods.map { it.name }.filter { "acklight" in it }.joinToString(","))
                m.invoke(pic, v)
                "ok $v"
            }
            else -> "ok service=$desc backlight=" + picIface.getMethod("getBacklight").invoke(pic)
        }
    } catch (t: Throwable) {
        val root = generateSequence(t) { it.cause }.last()
        Log.w(TAG, "Backlight $op failed", t)
        "error ${root.javaClass.simpleName}: ${root.message}"
    }
}

/**
 * Lives in the :picture process (see manifest). A ContentProvider "call" is a direct, synchronous
 * cross-process call: no broadcast queue, and if the vendor library crashes the caller finds out at once.
 */
class BacklightProvider : android.content.ContentProvider() {
    override fun call(method: String, arg: String?, extras: Bundle?): Bundle =
        Bundle().apply { putString("result", Backlight.call(context!!.applicationContext, method)) }

    override fun onCreate() = true
    override fun query(u: android.net.Uri, p: Array<String>?, s: String?, a: Array<String>?, o: String?): android.database.Cursor? = null
    override fun getType(u: android.net.Uri): String? = null
    override fun insert(u: android.net.Uri, v: android.content.ContentValues?): android.net.Uri? = null
    override fun delete(u: android.net.Uri, s: String?, a: Array<String>?) = 0
    override fun update(u: android.net.Uri, v: android.content.ContentValues?, s: String?, a: Array<String>?) = 0
}

object ScreenOff {
    private val URI = android.net.Uri.parse("content://com.bedroom.launcher.backlight")

    /** Ask the :picture process to switch the backlight ("off", "on", "get"). */
    fun request(ctx: Context, op: String): String = try {
        // "Unstable" client: if the helper process crashes, the home screen is NOT taken down with it.
        val client = ctx.contentResolver.acquireUnstableContentProviderClient(URI)
            ?: return "error: picture helper not found"
        try {
            client.call(op, null, null)?.getString("result") ?: "error: empty reply"
        } finally {
            client.close()
        }
    } catch (e: android.os.DeadObjectException) {
        "error: the picture helper crashed (vendor library)"
    } catch (t: Throwable) {
        val root = generateSequence(t) { it.cause }.last()
        "error ${root.javaClass.simpleName}: ${root.message}"
    }

    val isOff: Boolean get() = ScreenOffActivity.current != null

    fun off(ctx: Context, rest: Boolean = false): String {
        val r = request(ctx, "off")
        val real = r.startsWith("ok")
        ctx.startActivity(
            Intent(ctx, ScreenOffActivity::class.java)
                .putExtra("fallback", !real)
                .putExtra("rest", rest)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_NO_ANIMATION),
        )
        return JSONObject().put("ok", true).put("mode", if (real) "backlight" else "black").put("detail", r).toString()
    }

    fun on(ctx: Context): String {
        ScreenOffActivity.current?.let { a -> a.runOnUiThread { a.wake() } }
        val r = request(ctx, "on")
        return JSONObject().put("ok", true).put("detail", r).toString()
    }
}

class ScreenOffActivity : Activity() {
    companion object {
        @Volatile
        var current: ScreenOffActivity? = null
            private set
    }

    private var fallback = false
    private var rest = false
    private var woke = false
    private var startedAt = 0L

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        current = this
        fallback = intent.getBooleanExtra("fallback", false)
        rest = intent.getBooleanExtra("rest", false)
        startedAt = android.os.SystemClock.elapsedRealtime()
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        val v = View(this)
        if (fallback) {
            // Black screen at the lowest brightness Android allows.
            v.setBackgroundColor(Color.BLACK)
            window.attributes = window.attributes.apply { screenBrightness = 0f }
        } else {
            v.setBackgroundColor(Color.TRANSPARENT)
        }
        v.systemUiVisibility = View.SYSTEM_UI_FLAG_FULLSCREEN or View.SYSTEM_UI_FLAG_HIDE_NAVIGATION
        setContentView(v)
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        if (intent.getBooleanExtra("rest", false)) rest = true
    }

    private fun passThrough(code: Int) = code in setOf(
        KeyEvent.KEYCODE_VOLUME_UP, KeyEvent.KEYCODE_VOLUME_DOWN, KeyEvent.KEYCODE_VOLUME_MUTE,
        KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE, KeyEvent.KEYCODE_MEDIA_PLAY, KeyEvent.KEYCODE_MEDIA_PAUSE,
        KeyEvent.KEYCODE_MEDIA_NEXT, KeyEvent.KEYCODE_MEDIA_PREVIOUS, KeyEvent.KEYCODE_MEDIA_FAST_FORWARD,
        KeyEvent.KEYCODE_MEDIA_REWIND, KeyEvent.KEYCODE_MEDIA_STOP,
    )

    // Resting: every button wakes the TV (the sound is muted, so volume/media keys would seem to do nothing).
    override fun onKeyDown(keyCode: Int, event: KeyEvent): Boolean {
        if (!rest && passThrough(keyCode)) return super.onKeyDown(keyCode, event)
        wake()
        return true
    }

    override fun onKeyUp(keyCode: Int, event: KeyEvent): Boolean =
        if (!rest && passThrough(keyCode)) super.onKeyUp(keyCode, event) else true

    override fun onBackPressed() = wake()

    fun wake() {
        if (woke) return
        woke = true
        val app = applicationContext
        if (rest) Rest.woke(app)
        if (!fallback) thread { ScreenOff.request(app, "on") }
        finish()
        overridePendingTransition(0, 0)
    }

    override fun onStop() {
        super.onStop()
        if (isFinishing) return
        // Just after resting starts, a closing screensaver or the Home screen can briefly cover us.
        // That isn't the user: come back to the front instead of switching the picture back on.
        if (rest && android.os.SystemClock.elapsedRealtime() - startedAt < 5000) {
            val me = this
            window.decorView.postDelayed({
                if (!woke && !isFinishing) {
                    startActivity(
                        Intent(me, ScreenOffActivity::class.java)
                            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_REORDER_TO_FRONT or Intent.FLAG_ACTIVITY_NO_ANIMATION),
                    )
                }
            }, 400)
            return
        }
        // Something else came on screen (another app, Home): bring the picture back.
        wake()
    }

    override fun onDestroy() {
        if (current === this) current = null
        if (!woke && rest) Rest.woke(applicationContext)
        if (!woke && !fallback) {
            val app = applicationContext
            thread { ScreenOff.request(app, "on") }
        }
        super.onDestroy()
    }
}

/**
 * "Rest": the phone app's replacement for turning the TV off.
 *
 * A TV in real standby drops off Wi-Fi, so the phone can't turn it back on. Resting looks and sounds off
 * (playback paused, back to Home, sound muted, backlight off: the backlight is most of the TV's power draw)
 * but Android stays awake, so the phone link keeps working and the phone can wake it in about a second.
 * Any button on the TV's own remote also wakes it. Power on the TV remote still does a real standby.
 */
object Rest {
    @Volatile private var mutedByUs = false
    @Volatile private var resting = false

    val isResting: Boolean get() = resting && ScreenOff.isOff

    /** Call from a background thread. */
    fun enter(ctx: Context): String {
        val app = ctx.applicationContext
        // If the screensaver is running, end it first; otherwise it closes a moment later, lands on top of
        // the rest screen and the picture pops back on. WAKEUP does nothing when the TV is already awake.
        if (app.getSystemService(android.os.PowerManager::class.java)?.isInteractive != false) {
            try {
                dadb.Dadb.create("127.0.0.1", 5555, KeyInjector.keyPair(app)).use {
                    it.shell("input keyevent KEYCODE_WAKEUP")
                }
                Thread.sleep(800)
            } catch (e: Throwable) {
                Log.w(TAG, "Couldn't end the screensaver before resting", e)
            }
        }
        Media.key(app, KeyEvent.KEYCODE_MEDIA_PAUSE)
        app.startActivity(
            Intent(Intent.ACTION_MAIN)
                .addCategory(Intent.CATEGORY_HOME)
                .setPackage(app.packageName)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
        )
        val am = app.getSystemService(android.media.AudioManager::class.java)
        if (am != null && !am.isStreamMute(android.media.AudioManager.STREAM_MUSIC)) {
            am.adjustStreamVolume(android.media.AudioManager.STREAM_MUSIC, android.media.AudioManager.ADJUST_MUTE, 0)
            mutedByUs = true
        }
        Thread.sleep(700) // let Home come up first, so the rest screen sits on top of it
        resting = true
        Log.i(TAG, "Resting")
        return ScreenOff.off(app, rest = true)
    }

    fun exit(ctx: Context): String = ScreenOff.on(ctx.applicationContext) // ScreenOffActivity.wake() calls woke()

    /** The rest screen went away (phone, remote button, or anything else): sound back on. */
    fun woke(ctx: Context) {
        if (!resting) return
        resting = false
        if (mutedByUs) {
            mutedByUs = false
            ctx.getSystemService(android.media.AudioManager::class.java)
                ?.adjustStreamVolume(android.media.AudioManager.STREAM_MUSIC, android.media.AudioManager.ADJUST_UNMUTE, 0)
        }
        Log.i(TAG, "Woke from rest")
        if (NightMode.isOn) kotlin.concurrent.thread { NightMode.off(ctx) } // morning: normal picture again
    }
}

/** adb / PC panel: am broadcast -n com.bedroom.launcher/.ScreenControlReceiver --es op off|on|rest|wake|bedtime|nighton|nightoff|frame|alarmtest|bright [--ei level 0-100]|get */
class ScreenControlReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val pending = goAsync()
        val app = context.applicationContext
        val op = intent.getStringExtra("op") ?: "get"
        thread {
            try {
                val r = when (op) {
                    "off" -> ScreenOff.off(app)
                    "on" -> ScreenOff.on(app)
                    "rest" -> Rest.enter(app)
                    "wake" -> Rest.exit(app)
                    "bedtime" -> Bedtime.run(app)
                    "nighton" -> NightMode.on(app)
                    "nightoff" -> NightMode.off(app)
                    "frame" -> PhotoFrame.show(app)
                    "alarmtest" -> { Alarm.fire(app); "ok alarm" }
                    "bright" -> Brightness.json(app, intent.getIntExtra("level", -1).takeIf { it >= 0 })
                    else -> ScreenOff.request(app, "get")
                }
                Log.i(TAG, "Screen $op: $r")
                pending.resultData = r
            } finally {
                pending.finish()
            }
        }
    }
}
