package com.example.watchclientapp.presentation

import android.annotation.SuppressLint
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Intent
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import android.os.Build
import android.os.IBinder
import android.util.Base64
import androidx.core.app.NotificationCompat
import io.socket.client.IO
import io.socket.client.Socket
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers

import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock


class AudioRecorderService : Service() {

    private var audioRecord: AudioRecord? = null
    private var isRecording = false
    private val bufferSize = 1024 // Buffer size in bytes
    private val audioBuffer = ByteArray(bufferSize) // Buffer to store audio data
    private var recordingJob: Job? = null
    private val recordingMutex = Mutex() // Mutex for synchronization

    override fun onBind(intent: Intent?): IBinder? {
        return null
    }

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
        startForegroundService()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val serverUrl = intent?.getStringExtra("serverUrl") ?: return START_NOT_STICKY

        startRecording()
        return START_STICKY
    }


    override fun onDestroy() {
        stopRecording()

        super.onDestroy()
    }

    private fun createNotificationChannel() {

        val serviceChannel = NotificationChannel(
            "audio_recorder_service",
            "Audio Recorder Service",
            NotificationManager.IMPORTANCE_DEFAULT
        )
        val manager = getSystemService(NotificationManager::class.java)
        manager.createNotificationChannel(serviceChannel)

    }

    private fun startForegroundService() {
        val notification: Notification = NotificationCompat.Builder(this, "audio_recorder_service")
            .setContentTitle("Audio Recorder Service")
            .setContentText("Recording audio...")
            .build()

        startForeground(1, notification)
    }

    @SuppressLint("MissingPermission")
    private fun startRecording() {
        val sampleRate = 44100 // 44.1 kHz
        val channelConfig = AudioFormat.CHANNEL_IN_MONO
        val audioFormat = AudioFormat.ENCODING_PCM_16BIT

        val minBufferSize = AudioRecord.getMinBufferSize(sampleRate, channelConfig, audioFormat)

        audioRecord = AudioRecord(
            MediaRecorder.AudioSource.DEFAULT,
            sampleRate,
            channelConfig,
            audioFormat,
            minBufferSize
        )

        if (audioRecord?.state == AudioRecord.STATE_INITIALIZED) {
            debug("AudioRecord initialized successfully")
        } else {
            debug("AudioRecord initialization failed")
            return
        }

        audioRecord?.startRecording()
        isRecording = true

        recordingJob = CoroutineScope(Dispatchers.IO).launch {
            var shouldContinue = true
            while (shouldContinue) {
                recordingMutex.withLock {
                    shouldContinue = isRecording
                }

                val bytesRead = audioRecord?.read(audioBuffer, 0, bufferSize, AudioRecord.READ_BLOCKING) ?: 0

                if (bytesRead > 0) {
                    sendAudioBuffer(audioBuffer, bytesRead)
                }
            }
        }
    }

    private fun sendAudioBuffer(buffer: ByteArray, bytesRead: Int) {
        val bufferToSend = buffer.copyOf(bytesRead)
        val base64Data = Base64.encodeToString(bufferToSend, Base64.DEFAULT)
        SocketManager.getSocket().emit("audioStream", base64Data)
    }

    private fun stopRecording() {
        isRecording = false
        recordingJob?.cancel()
        audioRecord?.stop()
        audioRecord?.release()
        audioRecord = null
    }

    private fun debug(message: String) {
        print(message)
    }
}