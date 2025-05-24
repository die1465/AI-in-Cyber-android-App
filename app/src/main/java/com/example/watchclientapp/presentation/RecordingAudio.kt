package com.example.watchclientapp.presentation

import android.Manifest
import android.annotation.SuppressLint
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
import android.os.Handler
import android.os.HandlerThread
import android.os.IBinder
import android.util.Base64
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import com.example.watchclientapp.R
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers

import kotlinx.coroutines.Job
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import okhttp3.Request
import org.json.JSONObject
import java.io.File
import java.io.FileOutputStream


import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress

import okhttp3.OkHttpClient
import okhttp3.RequestBody.Companion.asRequestBody
import okhttp3.MediaType.Companion.toMediaType

/**
 * Fetches the current time from an NTP server, returning
 * the UNIX‐epoch timestamp in milliseconds.
 *
 * @param host      the NTP host (e.g. "time.google.com")
 * @param timeoutMs socket timeout in ms
 * @throws Exception on network or parse errors
 */





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
    private var _APIEndPointName : String = "uploadAudioPCM"
    private var _finishedRecordingSocketEventName : String = "DoneStreamingAudioData"

    private lateinit var handlerThread: HandlerThread
    private lateinit var serviceHandler: Handler

    private lateinit var pcmFile: File
    private var pcmOut: FileOutputStream? = null

    private val httpClient = OkHttpClient()
    private val _NTPHost = "time.google.com"



    override fun onBind(intent: Intent?): IBinder? {
        return null
    }



    override fun onCreate() {
        super.onCreate()

        // 1) spin up a background thread for the service
        handlerThread = HandlerThread("AudioRecorderServiceThread").apply { start() }
        serviceHandler = Handler(handlerThread.looper)

        createNotificationChannel()

        createNotificationChannel()

        Thread.setDefaultUncaughtExceptionHandler { thread, throwable ->
            SocketManager.debug("Uncaught exception in thread recording audio ${thread.name}: ${throwable.message}\n${throwable.printStackTrace()}")
            throwable.printStackTrace()
        }
    }



    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        serviceHandler.post {

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
            intent?.getStringExtra("APIEndpointName")?.let { endpoint ->
                _APIEndPointName = endpoint

            }

            intent?.getStringExtra("WhenDoneRecording")?.let { socketEventName ->
                _finishedRecordingSocketEventName = socketEventName
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


            pcmFile = File(cacheDir, "recording_${System.currentTimeMillis()}.pcm")
            pcmOut = FileOutputStream(pcmFile)

            watchOffset = 0

            startRecording()




        }
    }

    private fun stopRecordingInternal() {
        if (isRecording) {
            stopRecording()
            SocketManager.getSocket().emit("StoppedRecordingFromWatch")
        }
    }

    override fun onDestroy() {
        stopRecording()
        // shut down the thread cleanly
        handlerThread.quitSafely()


        super.onDestroy()
    }

    private fun createNotificationChannel() {

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
//            CoroutineScope(Dispatchers.IO).launch {
//            recordingStartTime = fetchNtpTimeMillis(_NTPHost)
            NtpTimeProvider.forceSync()
            recordingStartTime = NtpTimeProvider.nowMs()
//            }
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
                            else -> pcmOut?.write(audioBuffer, 0, bytesRead) //sendAudioBuffer(audioBuffer, bytesRead)
                        }
                    } catch (e: Exception) {
                        handleRecordingError(e)
                        break
                    }
                }
//                sendDoneRecordingMessage()
            }
        } catch (e: Exception) {
            handleRecordingError(e)
            stopSelf()
        }
    }
/*
* when the watch start recording send a message to the server to say started recording
* then start tracking what keys are being recorded by the watch
* then use that to trim the audio to make sure it ends in one second
* then correlate as normal
*
* */



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
//        CoroutineScope(Dispatchers.IO).launch {
//            recordingEndTime = fetchNtpTimeMillis(_NTPHost) // Adjusted recording end time
//        recordingEndTime = System.currentTimeMillis() // Adjusted recording end time
        recordingEndTime = NtpTimeProvider.nowMs() // Adjusted recording end time
//        }
        val jsonData = JSONObject(
            mapOf(
                "startTimestamp" to recordingStartTime,
                "endTimestamp" to recordingEndTime,
                "watchOffset" to watchOffset
            )
        )


        // Send the "DoneStreamingAudioData" event with the start and end timestamps
        SocketManager.getSocket().emit(
            _finishedRecordingSocketEventName,
            jsonData
        )
    }

//    private fun sendAudioBuffer(buffer: ByteArray, bytesRead: Int) {
//        val bufferToSend = buffer.copyOf(bytesRead)
//        val base64Data = Base64.encodeToString(bufferToSend, Base64.DEFAULT)
//        SocketManager.getSocket().emit(_socketStreamEventName, base64Data)
//    }

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
        // 1) close the PCM stream
        pcmOut?.flush()
        pcmOut?.close()

// 2) POST it
        sendPcmToServer(pcmFile)
//        SocketManager.getSocket().emit("StoppedRecordingFromWatch")
    }

    private fun sendPcmToServer(file: File) {
        CoroutineScope(Dispatchers.IO).launch {
            try {
                val mediaType = "audio/pcm".toMediaType()
                val body = file.asRequestBody(mediaType)

                val request = Request.Builder()
                    .url("http://${SocketManager.getServerIP()}:${SocketManager.getServerPort()}/$_APIEndPointName")
                    .post(body)
                    .build()

                httpClient.newCall(request).execute().use { resp ->
                    if (resp.isSuccessful) {
                        SocketManager.debug("PCM upload succeeded, deleting file")
                        sendDoneRecordingMessage()
                        val deleted = file.delete()
                        if (deleted) {
                            SocketManager.debug("File deleted successfully")
                        } else {
                            SocketManager.debug("Failed to delete file")
                        }
                    } else {
                        SocketManager.debug("PCM upload failed: ${resp.code}")
                    }
                }

            } catch (e: Exception) {
                SocketManager.debug("Upload error: ${e.message}")
            }
        }
    }

    @Throws(Exception::class)
    fun fetchNtpTimeMillis(host: String, timeoutMs: Int = 3000): Long {
        // --- NTP protocol constants ---
        val NTP_PORT = 123
        val PACKET_SIZE = 48
        val MODE_CLIENT = 3
        val VERSION = 4
        val TRANSMIT_TIME_OFFSET = 40
        // Seconds from Jan 1 1900 → Jan 1 1970
        val OFFSET_1900_TO_1970 = 2_208_988_800L

        // 1) Build & send the NTP request packet
        val buffer = ByteArray(PACKET_SIZE).apply {
            // LI = 0 (no warning), VN = VERSION, Mode = client
            this[0] = ((VERSION shl 3) or MODE_CLIENT).toByte()
        }
        val address = InetAddress.getByName(host)
        DatagramSocket().use { socket ->
            socket.soTimeout = timeoutMs
            socket.send(DatagramPacket(buffer, buffer.size, address, NTP_PORT))
            socket.receive(DatagramPacket(buffer, buffer.size))
        }

        // 2) Parse the server Transmit Timestamp (seconds + fraction)
        val seconds = (
                (buffer[TRANSMIT_TIME_OFFSET].toLong() and 0xFF shl 24) or
                        (buffer[TRANSMIT_TIME_OFFSET + 1].toLong() and 0xFF shl 16) or
                        (buffer[TRANSMIT_TIME_OFFSET + 2].toLong() and 0xFF shl 8) or
                        (buffer[TRANSMIT_TIME_OFFSET + 3].toLong() and 0xFF)
                )
        val fraction = (
                (buffer[TRANSMIT_TIME_OFFSET + 4].toLong() and 0xFF shl 24) or
                        (buffer[TRANSMIT_TIME_OFFSET + 5].toLong() and 0xFF shl 16) or
                        (buffer[TRANSMIT_TIME_OFFSET + 6].toLong() and 0xFF shl 8) or
                        (buffer[TRANSMIT_TIME_OFFSET + 7].toLong() and 0xFF)
                )

        // 3) Convert NTP time → Epoch milliseconds
        val epochSeconds = seconds - OFFSET_1900_TO_1970
        val msFromSeconds = epochSeconds * 1_000L
        // fraction/2^32 * 1000 = (fraction * 1000) >>> 32
        val msFromFraction = (fraction * 1_000L ushr 32)

        return msFromSeconds + msFromFraction
    }

}