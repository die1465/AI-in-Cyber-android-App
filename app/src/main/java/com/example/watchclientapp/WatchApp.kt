package com.example.watchclientapp

import android.app.Application
import android.content.Intent
import androidx.core.content.ContextCompat
import com.example.watchclientapp.presentation.AudioRecorderService
import com.example.watchclientapp.presentation.NtpTimeProvider
import com.example.watchclientapp.presentation.SensorRecordingService

class WatchApp : Application() {
    override fun onCreate() {
        super.onCreate()
        NtpTimeProvider.initialize(this)

        val intent = Intent(this, AudioRecorderService::class.java).apply {
            action = "START_SERVICE"
        }
        ContextCompat.startForegroundService(this, intent)

        val sensorServiceIntent = Intent(this, SensorRecordingService::class.java).apply {
            action = "START_SERVICE"
        }
        ContextCompat.startForegroundService(this, sensorServiceIntent)
    }
}