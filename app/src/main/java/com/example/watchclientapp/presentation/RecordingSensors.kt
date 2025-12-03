package com.example.watchclientapp.presentation

import android.annotation.SuppressLint
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.hardware.Sensor
import android.hardware.SensorDirectChannel
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import android.os.Build
import android.os.IBinder
import android.os.MemoryFile
import android.os.ParcelFileDescriptor
import android.os.PowerManager
import android.util.Base64
import androidx.core.app.NotificationCompat
import com.example.watchclientapp.R
import io.socket.client.IO
import io.socket.client.Socket
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.MultipartBody
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.asRequestBody
import org.json.JSONObject
import java.io.BufferedWriter
import java.io.File
import java.io.FileWriter
import java.io.IOException
import java.util.concurrent.atomic.AtomicBoolean
import java.time.Instant
import java.util.concurrent.TimeUnit

class SensorRecordingService : Service(), SensorEventListener {

    private lateinit var sensorManager: SensorManager
    private lateinit var wakeLock: PowerManager.WakeLock

    private var isRecording = false
    // Variables to store the latest sensor data
    private var latestAccelData: FloatArray? = null
    private var latestGyroData: FloatArray? = null
    private val hasNewAccelData = AtomicBoolean(false)
    private val hasNewGyroData = AtomicBoolean(false)
    private var totalMicrosSinceEpoch: Long = 0
    private lateinit var sensorDataFile: File
    private lateinit var fileWriter: BufferedWriter
    private var _postAPIEndpoint = ""

    // SensorDirectChannel variables
    private var directChannel: SensorDirectChannel? = null
    private var memoryFile: MemoryFile? = null
    private var pfd: ParcelFileDescriptor? = null

    //for keeping track of how many steps the user walked
    private var startStepCount: Int? = null
    private var endStepCount: Int? = null

    private lateinit var localSocket: Socket

    override fun onCreate() {
        super.onCreate()
        sensorManager = getSystemService(Context.SENSOR_SERVICE) as SensorManager
        acquireWakeLock()

        // Init local socket
        val options = IO.Options.builder()
            .setTransports(arrayOf("websocket"))
            .setExtraHeaders(mapOf("device-type" to listOf("SensorService")))
            .build()

        localSocket = IO.socket(SocketManager.getServerURL(), options)

        localSocket.on(Socket.EVENT_CONNECT) {
            SocketManager.debug("RecordingSensorsService socket connected")
        }

        localSocket.on(Socket.EVENT_CONNECT_ERROR) { args ->
            SocketManager.debug("RecordingSensorsService socket error: ${args[0]}")
        }

        localSocket.on("StartRecordingSensors"){args ->
            SocketManager.debug("got start Recording sensors ${args[0]}")
            val endpoint = args[0].toString()
            _postAPIEndpoint = "http://${SocketManager.getServerIP()}:${SocketManager.getServerPort()}/$endpoint"
            if (!isRecording) {
                val filename = "sensor_data_${System.currentTimeMillis()}.csv"
                sensorDataFile = File(filesDir, filename)
                fileWriter = BufferedWriter(FileWriter(sensorDataFile, true)) // append mode

                registerSensors()
                isRecording = true
                SocketManager.debug("Recording started")
            } else {
                SocketManager.debug("Already recording sensors, ignoring start command")
            }
        }.on("StopRecordingSensors") {
            if (isRecording) {
                val timestamp = NtpTimeProvider.nowMs()
                endStepCount?.let { end ->
                    fileWriter.write("18,$timestamp,0,$end,0,0\n")
                }
                unregisterSensors()
                isRecording = false
                try {
                    fileWriter.flush()
                    fileWriter.close()
                    sendSensorFileToServer(sensorDataFile)
                } catch (e: IOException) {
                    e.printStackTrace()
                    SocketManager.debug("File close or send failed: ${e.message}")
                }
                SocketManager.debug("Recording stopped")
            } else {
                SocketManager.debug("Not currently recording, ignoring stop command")
            }
        }

        localSocket.connect()
        startForegroundService()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        intent?.getStringExtra("APIEndpoint")?.let { endpoint ->
            _postAPIEndpoint = "http://${SocketManager.getServerIP()}:${SocketManager.getServerPort()}/$endpoint"
        }
        when (intent?.action) {
            "START_SERVICE" -> {
                // Just initialize but don't start recording yet
            }
            "CONNECT_SERVICE" -> {
                localSocket.connect()
            }
            "DISCONNECT_SERVICE" -> {
                localSocket.disconnect()
            }
            "STOP_SERVICE" -> {
                if (isRecording) {
                    unregisterSensors()
                    isRecording = false
                }
            }
        }

//        SocketManager.debug("socket event name for sensors: $_postAPIEndpoint")
        return START_STICKY
    }

    override fun onBind(intent: Intent?): IBinder? {
        return null
    }

    override fun onDestroy() {
        super.onDestroy()
        unregisterSensors()
        releaseWakeLock()
    }

    private fun acquireWakeLock() {
        val powerManager = getSystemService(Context.POWER_SERVICE) as PowerManager
        wakeLock = powerManager.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "SensorRecordingService::WakeLock")
        wakeLock.acquire(60* 60 * 1000L) //keep the service awake for 1 hour
    }

    private fun releaseWakeLock() {
        if (wakeLock.isHeld) {
            wakeLock.release()
        }
    }

    private fun startForegroundService() {
        val channelId = "sensor_recording_channel"
        val notificationManager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager

        val channel = NotificationChannel(
            channelId,
            "Sensor Recording",
            NotificationManager.IMPORTANCE_LOW
        )
        notificationManager.createNotificationChannel(channel)

        val notification: Notification = NotificationCompat.Builder(this, channelId)
            .setContentTitle("Recording Sensor Data")
            .setContentText("Sensor data is being recorded...")
            .build()

        startForeground(2, notification)
    }

    private fun registerSensors() {
        val desiredHZ = 10_000  // 100Hz = 10ms per sample
        val accelerometer = sensorManager.getDefaultSensor(Sensor.TYPE_ACCELEROMETER)
        val gyroscope = sensorManager.getDefaultSensor(Sensor.TYPE_GYROSCOPE)
        val magnetometer = sensorManager.getDefaultSensor(Sensor.TYPE_MAGNETIC_FIELD)
        val stepDetector = sensorManager.getDefaultSensor(Sensor.TYPE_STEP_DETECTOR)
        val stepCounter = sensorManager.getDefaultSensor(Sensor.TYPE_STEP_COUNTER)
        // Add PPG sensor registration
//        val ppgSensor = sensorManager.getDefaultSensor(Sensor.TYPE_HEART_RATE)

        // Alternative PPG sensors to try if TYPE_HEART_RATE isn't available
//        val ppgSensorAlt = if (ppgSensor == null) {
//            // Try vendor-specific PPG sensor types
//            sensorManager.getDefaultSensor(65572) // Common vendor-specific PPG type
//        } else null



        if (accelerometer == null || gyroscope == null || magnetometer == null || stepDetector == null) {
            SocketManager.debug("Core sensors are null")
            stopSelf()
            return
        }

        // Register core sensors
        sensorManager.registerListener(this, accelerometer, SensorManager.SENSOR_DELAY_FASTEST, 0)
        sensorManager.registerListener(this, gyroscope, SensorManager.SENSOR_DELAY_FASTEST, 0)
        sensorManager.registerListener(this, magnetometer, SensorManager.SENSOR_DELAY_FASTEST, 0)
        sensorManager.registerListener(this, stepDetector, SensorManager.SENSOR_DELAY_NORMAL, 0)
        sensorManager.registerListener(this, stepCounter, SensorManager.SENSOR_DELAY_NORMAL, 0)

        // Register PPG sensor if available
//        if (ppgSensor != null) {
//            // PPG sensors typically work at lower frequencies (1-10Hz)
//            val ppgSampleRate = SensorManager.SENSOR_DELAY_FASTEST
//            sensorManager.registerListener(this, ppgSensor, ppgSampleRate, 0)
//            SocketManager.debug("PPG sensor registered (TYPE_HEART_RATE)")
//        } else {
//            SocketManager.debug("No PPG sensor available on this device")
//        }

        SocketManager.debug("Sensors registered, sampling rates:" +
                "\naccel ${accelerometer.minDelay}" +
                "\ngyro ${gyroscope.minDelay}" +
                "\nmagnetometer ${magnetometer.minDelay}" +
//                "\nppg available: ${ppgSensor != null || ppgSensorAlt != null}" +
                "\nstepDetector: ${stepDetector.minDelay}, ${stepCounter?.minDelay}")
    }

    private fun unregisterSensors() {
        sensorManager.unregisterListener(this)
    }

    override fun onSensorChanged(event: SensorEvent?) {
        event?.let {
            when (event.sensor.type) {
                Sensor.TYPE_ACCELEROMETER -> {
                    val timestamp = NtpTimeProvider.nowMs()
                    val (x, y, z) = event.values
                    // sensor type 10 for accel
                    val line = "10,$timestamp,${event.timestamp},$x,$y,$z\n"
                    try {
                        fileWriter.write(line)
                    } catch (e: IOException) {
                        e.printStackTrace()
                        SocketManager.debug("File write failed: ${e.message}")
                    }
                }

                Sensor.TYPE_GYROSCOPE -> {
                    val timestamp = NtpTimeProvider.nowMs()
                    val (x, y, z) = event.values
                    // sensor type 4 for gyro
                    val line = "4,$timestamp,${event.timestamp},$x,$y,$z\n"
                    try {
                        fileWriter.write(line)
                    } catch (e: IOException) {
                        e.printStackTrace()
                        SocketManager.debug("File write failed: ${e.message}")
                    }
                }

                Sensor.TYPE_MAGNETIC_FIELD -> {
                    val timestamp = NtpTimeProvider.nowMs()
                    val (x, y, z) = event.values
                    val line = "2,$timestamp,${event.timestamp},$x,$y,$z\n"
                    try {
                        fileWriter.write(line)
                    } catch (e: IOException) {
                        e.printStackTrace()
                        SocketManager.debug("File write failed: ${e.message}")
                    }
                }

//                Sensor.TYPE_HEART_RATE -> {
//                    val timestamp = NtpTimeProvider.nowMs()
//                    val heartRate = event.values[0] // Heart rate in BPM
//                    // sensor type 21 for heart rate/PPG
//                    val line = "21,$timestamp,${event.timestamp},$heartRate,0,0\n"
//                    try {
//                        fileWriter.write(line)
//
//                    } catch (e: IOException) {
//                        e.printStackTrace()
//                        SocketManager.debug("PPG file write failed: ${e.message}")
//                    }
//                }

                Sensor.TYPE_STEP_DETECTOR -> {
                    val timestamp = NtpTimeProvider.nowMs()
                    val steps = event.values[0] // 1.0 per step event
                    val line = "19,$timestamp,${event.timestamp},$steps,0,0\n"
                    try {
                        fileWriter.write(line)
                    } catch (e: IOException) {
                        e.printStackTrace()
                        SocketManager.debug("Step write failed: ${e.message}")
                    }
                }

                Sensor.TYPE_STEP_COUNTER -> {
                    val timestamp = NtpTimeProvider.nowMs()
                    val count = event.values[0].toInt()
                    if (startStepCount == null) {
                        startStepCount = count
                        // write a “header” CSV line, e.g.:
                        fileWriter.write("18,$timestamp,0,$count,0,0\n")
                    }else{
                        endStepCount = count
                    }
                }


            }
        }
    }

    private fun sendSensorData(eventName: String = "SensorStream") {
        val timestamp = System.currentTimeMillis()
        val accelX = latestAccelData?.get(0) ?: 0f
        val accelY = latestAccelData?.get(1) ?: 0f
        val accelZ = latestAccelData?.get(2) ?: 0f
        val gyroX = latestGyroData?.get(0) ?: 0f
        val gyroY = latestGyroData?.get(1) ?: 0f
        val gyroZ = latestGyroData?.get(2) ?: 0f

        val data = "$timestamp,$accelX,$accelY,$accelZ,$gyroX,$gyroY,$gyroZ"
        SocketManager.getSocket().emit(eventName, data)
    }

    private fun sendSensorFileToServer(file: File) {
        val client = OkHttpClient.Builder()
            .connectTimeout(10, TimeUnit.MINUTES)
            .readTimeout(10, TimeUnit.MINUTES)
            .writeTimeout(10, TimeUnit.MINUTES)
            .build()
        val mediaType = "text/csv".toMediaType()
        val requestBody = file.asRequestBody(mediaType)

        val multipartBody = MultipartBody.Builder()
            .setType(MultipartBody.FORM)
            .addFormDataPart("file", file.name, requestBody)
            .build()

        val request = Request.Builder()
            .url(_postAPIEndpoint)
            .post(multipartBody)
            .build()

        CoroutineScope(Dispatchers.IO).launch {
            try {
                val response = client.newCall(request).execute()
                if (response.isSuccessful) {
                    SocketManager.debug("Sensor file uploaded successfully")
                    val deleted = file.delete()
                    if (deleted) {
                        SocketManager.debug("File deleted: ${file.name}")
                    } else {
                        SocketManager.debug("Failed to delete file: ${file.name}")
                    }
                } else {
                    SocketManager.debug("Upload failed: ${response.code}")
                }
            } catch (e: Exception) {
                e.printStackTrace()
                SocketManager.debug("Upload exception: ${e.message}")
            }
        }
    }

    override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) {
        // Handle accuracy changes if needed
    }
}