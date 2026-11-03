package com.avshy.bayitvoice.ui

import android.Manifest
import android.content.pm.PackageManager
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.core.content.ContextCompat
import com.avshy.bayitvoice.service.WakeWordService

/** Invisible activity that starts the wake-word service while the app counts as visible, then closes. */
class StartListeningActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED) {
            WakeWordService.start(this)
        }
        finish()
    }
}
