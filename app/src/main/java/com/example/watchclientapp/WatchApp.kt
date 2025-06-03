package com.example.watchclientapp

import android.app.Application
import android.content.Intent
import androidx.core.content.ContextCompat
import com.example.watchclientapp.presentation.AudioRecorderService
import com.example.watchclientapp.presentation.NtpTimeProvider

class WatchApp : Application() {
    override fun onCreate() {
        super.onCreate()
        NtpTimeProvider.initialize(this)

        val intent = Intent(this, AudioRecorderService::class.java).apply {
            action = "START_SERVICE"
        }
        ContextCompat.startForegroundService(this, intent)
    }
}