package com.bedroom.launcher

import android.content.Intent
import android.os.Bundle
import android.util.Log
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.runtime.mutableIntStateOf

const val TAG = "BedroomLauncher"

class MainActivity : ComponentActivity() {

    /** Bumps every time the launcher comes back to the front (e.g. after Hulu closes). */
    private val resumeTick = mutableIntStateOf(0)

    /** Bumps every time the Home button is pressed while we're the home screen. */
    private val homeTick = mutableIntStateOf(0)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        Log.i(TAG, "onCreate v${BuildConfig.VERSION_NAME} (${BuildConfig.VERSION_CODE})")
        // Deliberately NOT keeping the screen on: this is a bedroom TV and the
        // TV's own sleep timer / screensaver must still work.
        setContent {
            LauncherApp(resumeTick = resumeTick.intValue, homeTick = homeTick.intValue)
        }
    }

    override fun onResume() {
        super.onResume()
        resumeTick.intValue++
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        if (intent.hasCategory(Intent.CATEGORY_HOME)) {
            Log.i(TAG, "Home pressed")
            homeTick.intValue++
        }
    }
}
