package com.bedroom.remote

import android.Manifest
import android.content.Intent
import android.os.Build
import android.os.Bundle
import android.view.KeyEvent
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

class MainActivity : ComponentActivity() {

    private lateinit var tv: TvClient

    private val askNotifications =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
            if (granted) refreshNotification()
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        tv = TvClient(this)
        handlePairing(intent)
        setContent { RemoteApp(tv) }
        ensureNotification()
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        handlePairing(intent)
    }

    override fun onStart() {
        super.onStart()
        // Start the TV service while we're on screen (Android won't let it start from the background).
        if (tv.configured && TvNotification.canPost(this)) TvNotification.show(applicationContext)
    }

    // Low-latency Wi-Fi while the app is on screen (the phone's Wi-Fi power saving adds 100-300 ms per press).
    private var fastWifi: android.net.wifi.WifiManager.WifiLock? = null

    override fun onResume() {
        super.onResume()
        val wm = applicationContext.getSystemService(android.net.wifi.WifiManager::class.java)
        @Suppress("DEPRECATION")
        val mode = if (Build.VERSION.SDK_INT >= 29) android.net.wifi.WifiManager.WIFI_MODE_FULL_LOW_LATENCY
        else android.net.wifi.WifiManager.WIFI_MODE_FULL_HIGH_PERF
        fastWifi = wm?.createWifiLock(mode, "BedroomRemote:fast")?.apply { setReferenceCounted(false); acquire() }
    }

    override fun onPause() {
        runCatching { fastWifi?.release() }
        fastWifi = null
        super.onPause()
    }

    override fun onStop() {
        super.onStop()
        // Leaving the app: make sure the TV controls are waiting in the notification shade.
        refreshNotification()
    }

    /**
     * bedroomtv://pair?h=192.168.1.50&k=<32 chars> from the TV's QR page.
     * Any website could try to open this link, so it's checked and you're always asked first.
     */
    private fun handlePairing(intent: Intent?) {
        val data = intent?.data ?: return
        if (data.scheme != "bedroomtv" || data.host != "pair") return
        val h = data.getQueryParameter("h").orEmpty()
        val k = data.getQueryParameter("k").orEmpty().lowercase()
        setIntent(Intent(this, MainActivity::class.java)) // don't re-run on rotation/restart
        if (!Signer.validHost(h) || !Signer.validKey(k)) {
            Toast.makeText(this, "That pairing link isn't valid. Scan the QR code on the TV's Phone screen.", Toast.LENGTH_LONG).show()
            return
        }
        android.app.AlertDialog.Builder(this)
            .setTitle("Pair with this TV?")
            .setMessage(
                "Connect TV Remote to the TV at $h?\n\nOnly say yes if you just scanned the QR code on your own TV." +
                    if (tv.configured && tv.host != h) "\n\nThis replaces your current TV (${tv.host})." else "",
            )
            .setPositiveButton("Pair") { _, _ ->
                if (tv.save(h, k)) {
                    Toast.makeText(this, "Paired with your TV", Toast.LENGTH_SHORT).show()
                    ensureNotification()
                }
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun ensureNotification() {
        if (!TvNotification.enabled(this) || !tv.configured) return
        if (Build.VERSION.SDK_INT >= 33 && !TvNotification.canPost(this)) {
            askNotifications.launch(Manifest.permission.POST_NOTIFICATIONS)
        } else {
            refreshNotification()
        }
    }

    /** Re-post the notification with a fresh status line (network call off the main thread). */
    private fun refreshNotification() {
        val app = applicationContext
        lifecycleScope.launch(Dispatchers.IO) {
            if (!TvClient.isPaired(app)) return@launch
            TvNotification.show(app, TvClient.statusLine(TvClient.callBlocking(app, "status")))
        }
    }

    /** The phone's own volume buttons control the TV while the app is open. */
    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        val action = when (event.keyCode) {
            KeyEvent.KEYCODE_VOLUME_UP -> "volup"
            KeyEvent.KEYCODE_VOLUME_DOWN -> "voldown"
            else -> null
        }
        if (action != null && tv.configured) {
            if (event.action == KeyEvent.ACTION_DOWN) tv.send(lifecycleScope, action)
            return true
        }
        return super.dispatchKeyEvent(event)
    }
}
