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
import com.example.watchclientapp.presentation.SocketManager.getSocket
import io.socket.client.IO
import io.socket.client.Socket
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
import java.util.concurrent.TimeUnit

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

    private val httpClient = OkHttpClient.Builder()
        .connectTimeout(20, TimeUnit.SECONDS)
        .readTimeout(20, TimeUnit.SECONDS)
        .writeTimeout(20, TimeUnit.SECONDS)
        .build()
    private val _NTPHost = "time.google.com"
    private lateinit var localSocket: Socket




    override fun onBind(intent: Intent?): IBinder? {
        return null
    }



    override fun onCreate() {
        super.onCreate()

        // 1) spin up a background thread for the service
        handlerThread = HandlerThread("AudioRecorderServiceThread").apply { start() }
        serviceHandler = Handler(handlerThread.looper)

        createNotificationChannel()

        // Init local socket
        val options = IO.Options.builder()
            .setTransports(arrayOf("websocket"))
            .setExtraHeaders(mapOf("device-type" to listOf("AudioService")))
            .build()

        localSocket = IO.socket(SocketManager.getServerURL(), options)


        localSocket.on(Socket.EVENT_CONNECT) {
            debug("AudioRecorderService socket connected")
        }

        localSocket.on(Socket.EVENT_CONNECT_ERROR) { args ->
            debug("AudioRecorderService socket error: ${args[0]}")
        }

        localSocket.on("startRecordingAudio"){args ->
            debug("got start Recording audio ${args[0]}")
            val data = args[0] as JSONObject
            _APIEndPointName = data.getString("endpoint")
            _finishedRecordingSocketEventName = data.getString("WhenDoneRecording")
            startRecordingInternal()
        }.on("stopRecordingAudio") {
            stopRecordingInternal()
        }

        localSocket.connect()

        Thread.setDefaultUncaughtExceptionHandler { thread, throwable ->
            debug("Uncaught exception in thread recording audio ${thread.name}: ${throwable.message}\n${throwable.printStackTrace()}")
            throwable.printStackTrace()
        }
    }

    private fun debug(msg: String) {
        if (::localSocket.isInitialized && localSocket.connected()) {
            localSocket.emit("testingDebug", msg)
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        serviceHandler.post {
//            intent?.getStringExtra("APIEndpointName")?.let { endpoint ->
//                _APIEndPointName = endpoint
//
//            }
//
//            intent?.getStringExtra("WhenDoneRecording")?.let { socketEventName ->
//                _finishedRecordingSocketEventName = socketEventName
//            }
            when (intent?.action) {
//                "START_RECORDING" -> {
//
//                    startRecordingInternal()
//                }
//
//                "STOP_RECORDING" -> {
//                    stopRecordingInternal()
//                }

                "START_SERVICE" -> {
                    // Normal service start
                    startForegroundService()
                }
                "DISCONNECT_SERVICE" -> {
                    // Normal service start
                    localSocket.disconnect()
                }
                "CONNECT_SERVICE" -> {
                    // Normal service start
                    localSocket.connect()
                }
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
            debug("AudioRecord creation failed: ${e.message}")
            null
        }

        if (audioRecord?.state != AudioRecord.STATE_INITIALIZED) {
            debug("AudioRecord initialization failed")
            stopSelf()
            return
        }

        try {
//            CoroutineScope(Dispatchers.IO).launch {
//            recordingStartTime = fetchNtpTimeMillisWithRtt(_NTPHost)
//            debug("time when audio starts recording ${System.currentTimeMillis()}")
//            NtpTimeProvider.forceSync()
            recordingStartTime = NtpTimeProvider.nowMs()

//            }
            audioRecord?.startRecording()
//            recordingStartTime = System.currentTimeMillis()
            isRecording = true
            var shouldRecord = true
            recordingJob = CoroutineScope(Dispatchers.IO).launch {
//                debug("WatchTime after recording started  ${NtpTimeProvider.nowMs()}")
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
                debug("the recording stopped on the watch at ${System.currentTimeMillis()}")
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
                debug("ERROR_INVALID_OPERATION")
            AudioRecord.ERROR_BAD_VALUE ->
                debug("ERROR_BAD_VALUE")
            AudioRecord.ERROR_DEAD_OBJECT ->
                debug("ERROR_DEAD_OBJECT")
            AudioRecord.ERROR ->
                debug("GENERIC_ERROR")
            else ->
                debug("No data read")
        }

    }

    private fun handleRecordingError(e: Exception) {
        debug("Recording error: ${e.message}")

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
            debug("Error stopping recording: ${e.message}")
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
                        debug("PCM upload succeeded, deleting file")

                        val deleted = file.delete()
                        sendDoneRecordingMessage()
                        if (deleted) {
                            debug("File deleted successfully")
                        } else {
                            debug("Failed to delete file")
                        }
                    } else {
                        debug("PCM upload failed: ${resp.code}")
                    }
                }

            } catch (e: Exception) {
                debug("PCM Upload error: ${e.message}")
            }
        }
    }

    @Throws(Exception::class)
    fun fetchNtpTimeMillisWithRtt(host: String, timeoutMs: Int = 3000): Long {
        val NTP_PORT = 123
        val PACKET_SIZE = 48
        val MODE_CLIENT = 3
        val VERSION = 4
        val OFFSET_1900_TO_1970 = 2_208_988_800L
        val TRANSMIT_TIME_OFFSET = 40
        val RECEIVE_TIME_OFFSET = 32

        val buffer = ByteArray(PACKET_SIZE).apply {
            this[0] = ((VERSION shl 3) or MODE_CLIENT).toByte()
        }

        val address = InetAddress.getByName(host)
        val socket = DatagramSocket().apply { soTimeout = timeoutMs }

        val requestPacket = DatagramPacket(buffer, buffer.size, address, NTP_PORT)

        // T1: time request sent (local system time in ms)
        val t1 = System.currentTimeMillis()
        socket.send(requestPacket)

        val responsePacket = DatagramPacket(buffer, buffer.size)
        socket.receive(responsePacket)
        val t4 = System.currentTimeMillis() // T4: time response received

        socket.close()

        // Parse timestamps from NTP packet
        fun readTimestamp(offset: Int): Long {
            val seconds = (
                    (buffer[offset].toLong() and 0xFF shl 24) or
                            (buffer[offset + 1].toLong() and 0xFF shl 16) or
                            (buffer[offset + 2].toLong() and 0xFF shl 8) or
                            (buffer[offset + 3].toLong() and 0xFF)
                    )
            val fraction = (
                    (buffer[offset + 4].toLong() and 0xFF shl 24) or
                            (buffer[offset + 5].toLong() and 0xFF shl 16) or
                            (buffer[offset + 6].toLong() and 0xFF shl 8) or
                            (buffer[offset + 7].toLong() and 0xFF)
                    )
            val ms = ((seconds - OFFSET_1900_TO_1970) * 1000L) + ((fraction * 1000L) ushr 32)
            return ms
        }

        val t2 = readTimestamp(RECEIVE_TIME_OFFSET) // server received client request
        val t3 = readTimestamp(TRANSMIT_TIME_OFFSET) // server sent response

        // RTT and offset calculations
        val rtt = (t4 - t1) - (t3 - t2)
        val offset = ((t2 - t1) + (t3 - t4)) / 2
        debug("rtt of ntp function ${(t4 - t1)}")
        return t4 + offset // corrected current time in ms
    }


}