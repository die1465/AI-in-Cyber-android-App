package com.example.watchclientapp.presentation

import android.Manifest
import android.annotation.SuppressLint
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import android.os.Build
import android.os.IBinder
import android.util.Base64
import androidx.core.app.ActivityCompat
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import com.example.watchclientapp.R
import io.socket.client.IO
import io.socket.client.Socket
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers

import kotlinx.coroutines.Job
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import okhttp3.Call
import okhttp3.Callback
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import org.json.JSONObject
import java.io.IOException



class AudioRecorderService : Service() {


    companion object {
        private const val NOTIFICATION_ID = 101
        private const val CHANNEL_ID = "audio_recorder_channel"
    }
    private var audioRecord: AudioRecord? = null
    private var isRecording = false
    private lateinit var audioBuffer: ByteArray // Will be initialized with correct size
    private var recordingJob: Job? = null
    private val recordingMutex = Mutex() // Mutex for synchronization
    private var watchOffset: Long? = null
    private var recordingStartTime: Long? = null
    private var recordingEndTime: Long? = null
    private val serverUrl = "http://192.168.76.227:5000/sync"

    override fun onBind(intent: Intent?): IBinder? {
        return null
    }



    override fun onCreate() {
        super.onCreate()

        createNotificationChannel()

        Thread.setDefaultUncaughtExceptionHandler { thread, throwable ->
            SocketManager.debug("Uncaught exception in thread recording audio ${thread.name}: ${throwable.message}\n${throwable.printStackTrace()}")
            throwable.printStackTrace()
        }
    }



    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            "START_RECORDING" -> {

                startRecordingInternal()
            }
            "STOP_RECORDING" -> {
                stopRecordingInternal()
            }
            "START_SERVICE" -> {
                // Normal service start
                startForegroundService()
            }
        }

        return START_STICKY
    }
    private fun hasAudioPermission(): Boolean {
        return ContextCompat.checkSelfPermission(
            this,
            Manifest.permission.RECORD_AUDIO
        ) == PackageManager.PERMISSION_GRANTED
    }

    // Add these control methods
    private fun startRecordingInternal() {
        if (!isRecording) {
            recordingStartTime = System.currentTimeMillis()
            watchOffset = 0

            startRecording()

        }
    }

    private fun stopRecordingInternal() {
        if (isRecording) {
            stopRecording()
        }
    }

    override fun onDestroy() {
        stopRecording()

        super.onDestroy()
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            NotificationChannel(
                CHANNEL_ID,
                "Audio Recorder",
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = "Audio recording service channel"
                (getSystemService(NOTIFICATION_SERVICE) as NotificationManager)
                    .createNotificationChannel(this)
            }
        }
    }

    private fun startForegroundService() {
        val notification = NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("Audio Recorder")
            .setContentText("Recording in progress")
            .setSmallIcon(R.drawable.splash_icon)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .build()

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            startForeground(
                NOTIFICATION_ID,
                notification,
                ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE
            )
        } else {
            startForeground(NOTIFICATION_ID, notification)
        }
    }



//    private fun synchronizeWithServer(callback: (Long) -> Unit) {
//        val watchTime = System.currentTimeMillis() // Watch local time in milliseconds
//        val client = OkHttpClient()
//        val request = Request.Builder()
//            .url(serverUrl) // Replace with your server URL
//            .build()
//
//        client.newCall(request).enqueue(object : Callback {
//            override fun onFailure(call: Call, e: IOException) {
//                e.printStackTrace()
//            }
//
//            override fun onResponse(call: Call, response: Response) {
//                if (response.isSuccessful) {
//                    val json = response.body?.string()
//                    val serverTime = json?.let { parseServerTime(it) } // Parse server time
//
//                    val offset = serverTime?.minus(watchTime) // Offset between server and watch
//                    SocketManager.debug("Watch offset: $offset, server time: $serverTime, watchTime: $watchTime")
//                    offset?.let { callback(it) }
//                }
//            }
//        })
//    }

    fun parseServerTime(json: String): Long {
        // Parse the server time from the JSON response
        val timestampString = json.split("\"timestamp\":")[1].split("}")[0].trim() // Trim spaces
        return timestampString.toLong() // Convert to Long
    }
    @SuppressLint("MissingPermission")
    private fun startRecording() {
        val sampleRate = 44100
        val channelConfig = AudioFormat.CHANNEL_IN_MONO
        val audioFormat = AudioFormat.ENCODING_PCM_16BIT
        val minBufferSize = AudioRecord.getMinBufferSize(sampleRate, channelConfig, audioFormat)
        val bufferSize = minBufferSize
        audioBuffer = ByteArray(bufferSize) // Initialize with correct size

        audioRecord = try {
            AudioRecord.Builder()
                .setAudioSource(MediaRecorder.AudioSource.MIC)
                .setAudioFormat(
                    AudioFormat.Builder()
                        .setEncoding(audioFormat)
                        .setSampleRate(sampleRate)
                        .setChannelMask(channelConfig)
                        .build()
                )
                .setBufferSizeInBytes(bufferSize)
                .build()
        } catch (e: Exception) {
            SocketManager.debug("AudioRecord creation failed: ${e.message}")
            null
        }

        if (audioRecord?.state != AudioRecord.STATE_INITIALIZED) {
            SocketManager.debug("AudioRecord initialization failed")
            stopSelf()
            return
        }

        try {
            audioRecord?.startRecording()
            isRecording = true
            var shouldRecord = true
            recordingJob = CoroutineScope(Dispatchers.IO).launch {
                while (isActive && shouldRecord) {
                    try {
                        recordingMutex.withLock {
                            if(!isRecording)
                                shouldRecord = false
                        }
                        val bytesRead = audioRecord?.read(audioBuffer, 0, bufferSize) ?: 0
                        when {
                            bytesRead <= 0 -> handleReadError(bytesRead)
                            else -> sendAudioBuffer(audioBuffer, bytesRead)
                        }
                    } catch (e: Exception) {
                        handleRecordingError(e)
                        break
                    }
                }
                sendDoneRecordingMessage()
            }
        } catch (e: Exception) {
            handleRecordingError(e)
            stopSelf()
        }
    }




    private fun handleReadError(errorCode: Int) {
        when (errorCode) {
            AudioRecord.ERROR_INVALID_OPERATION ->
                SocketManager.debug("ERROR_INVALID_OPERATION")
            AudioRecord.ERROR_BAD_VALUE ->
                SocketManager.debug("ERROR_BAD_VALUE")
            AudioRecord.ERROR_DEAD_OBJECT ->
                SocketManager.debug("ERROR_DEAD_OBJECT")
            AudioRecord.ERROR ->
                SocketManager.debug("GENERIC_ERROR")
            else ->
                SocketManager.debug("No data read")
        }

    }

    private fun handleRecordingError(e: Exception) {
        SocketManager.debug("Recording error: ${e.message}")

    }
    private fun sendDoneRecordingMessage(){
        // Calculate the adjusted recording end time
        recordingEndTime = System.currentTimeMillis() + (watchOffset ?: 0) // Adjusted recording end time
        val jsonData = JSONObject(
            mapOf(
                "startTimestamp" to recordingStartTime,
                "endTimestamp" to recordingEndTime,
                "watchOffset" to watchOffset
            )
        )


        // Send the "DoneStreamingAudioData" event with the start and end timestamps
        SocketManager.getSocket().emit(
            "DoneStreamingAudioData",
            jsonData
        )
    }

    private fun sendAudioBuffer(buffer: ByteArray, bytesRead: Int) {
        val bufferToSend = buffer.copyOf(bytesRead)
        val base64Data = Base64.encodeToString(bufferToSend, Base64.DEFAULT)
        SocketManager.getSocket().emit("audioStream", base64Data)
    }

    private fun stopRecording() {
        isRecording = false
        recordingJob?.cancel()
        try {
            audioRecord?.stop()
            audioRecord?.release()
        } catch (e: Exception) {
            SocketManager.debug("Error stopping recording: ${e.message}")
        }
        audioRecord = null
    }

}