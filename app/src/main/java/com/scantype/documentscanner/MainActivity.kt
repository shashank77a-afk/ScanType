package com.scantype.documentscanner

import android.os.Bundle
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.fragment.app.FragmentActivity
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver

class MainActivity : FragmentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        val prefs = Prefs(this)
        setContent { ScanTypeTheme(prefs.theme) { Gate(prefs, this) } }
    }
}

/** Decides what to show first: lock screen, onboarding or the app. */
@Composable
fun Gate(prefs: Prefs, activity: FragmentActivity) {
    var onboarded by remember { mutableStateOf(prefs.onboarded) }
    var locked by remember { mutableStateOf(prefs.lockEnabled) }
    DisposableEffect(activity) {
        var awayAt = 0L
        val obs = LifecycleEventObserver { _, e ->
            if (e == Lifecycle.Event.ON_STOP) awayAt = System.currentTimeMillis()
            if (e == Lifecycle.Event.ON_START && prefs.lockEnabled && awayAt > 0 && System.currentTimeMillis() - awayAt > 30000) locked = true
        }
        activity.lifecycle.addObserver(obs)
        onDispose { activity.lifecycle.removeObserver(obs) }
    }
    Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
        when {
            locked && prefs.lockEnabled -> LockScreen(prefs, activity) { locked = false }
            !onboarded -> Onboarding { prefs.onboarded = true; onboarded = true }
            else -> App(prefs)
        }
    }
}
