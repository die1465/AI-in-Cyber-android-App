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
import java.io.IOException
import java.util.concurrent.atomic.AtomicBoolean
import java.time.Instant
import java.util.concurrent.TimeUnit

class LinearAccelerationRecordingService : Service(), SensorEventListener {

    private lateinit var sensorManager: SensorManager
    private lateinit var wakeLock: PowerManager.WakeLock
    private var isRecording = false

    // Linear acceleration specific variables
    private var latestLinearAccelData: FloatArray? = null
    private val hasNewLinearAccelData = AtomicBoolean(false)
    private var maxSamplingRate = SensorManager.SENSOR_DELAY_FASTEST

    // SensorDirectChannel variables
    private var directChannel: SensorDirectChannel? = null
    private var memoryFile: MemoryFile? = null
    private var pfd: ParcelFileDescriptor? = null
    private var recordingStartTime = 0L

    override fun onCreate() {
        super.onCreate()
        sensorManager = getSystemService(Context.SENSOR_SERVICE) as SensorManager
        acquireWakeLock()
        startForegroundService()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            "START_SERVICE" -> {
                if(!isRecording) {
                    startForegroundService()
                    SocketManager.debug("Linear acceleration service initialized")
                }
            }
            "START_RECORDING" -> {
                if (!isRecording) {
                    registerSensor()
                    isRecording = true
                    SocketManager.debug("Linear acceleration recording started")
                }
            }
            "STOP_RECORDING" -> {
                if (isRecording) {
                    unregisterSensor()
                    isRecording = false
                    SocketManager.debug("Linear acceleration recording stopped")
                }
            }
            "STOP_SERVICE" -> {
                if (isRecording) unregisterSensor()
                stopSelf()
            }
        }
        return START_STICKY
    }

    // ... [onBind, onDestroy, wakeLock methods remain same] ...
    override fun onBind(intent: Intent?): IBinder? {
        return null
    }

    override fun onDestroy() {
        super.onDestroy()
        unregisterSensor()
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
        val channelId = "linear_accel_channel"
        val notificationManager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager


        NotificationChannel(
            channelId,
            "Linear Acceleration Recording",
            NotificationManager.IMPORTANCE_LOW
        ).apply { notificationManager.createNotificationChannel(this) }


        val notification: Notification = NotificationCompat.Builder(this, channelId)
            .setContentTitle("Recording Linear Acceleration")
            .setContentText("Measuring device movement without gravity")
            .build()

        startForeground(3, notification)
    }

    private fun registerSensor() {
        val linearAccel = sensorManager.getDefaultSensor(Sensor.TYPE_LINEAR_ACCELERATION)


        val accelerometer = sensorManager.getDefaultSensor(Sensor.TYPE_ACCELEROMETER)
        val gyroscope = sensorManager.getDefaultSensor(Sensor.TYPE_GYROSCOPE)
        val magnetometer = sensorManager.getDefaultSensor(Sensor.TYPE_MAGNETIC_FIELD)
        val stepDetector = sensorManager.getDefaultSensor(Sensor.TYPE_STEP_DETECTOR)
        val stepCounter = sensorManager.getDefaultSensor(Sensor.TYPE_STEP_COUNTER)
        // Add PPG sensor registration
//        val ppgSensor = sensorManager.getDefaultSensor(Sensor.TYPE_HEART_RATE)

        if (linearAccel == null) {
            SocketManager.debug("Linear acceleration sensor not available")
            stopSelf()
            return
        }
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

        // Use maximum supported sampling rate
        val minDelayMicros = linearAccel.minDelay
        maxSamplingRate = when {
            minDelayMicros <= 5000 -> SensorManager.SENSOR_DELAY_FASTEST
            minDelayMicros <= 20000 -> SensorManager.SENSOR_DELAY_GAME
            else -> SensorManager.SENSOR_DELAY_NORMAL
        }

        sensorManager.registerListener(
            this,
            linearAccel,
            SensorManager.SENSOR_DELAY_FASTEST,  // Directly use sensor's minimum delay
            0
        )

        SocketManager.debug("Linear acceleration sensor registered\n" +
                "Max sampling rate: ${1000000 / minDelayMicros}Hz ${linearAccel.minDelay}Hz")
    }

    private fun unregisterSensor() {
        sensorManager.unregisterListener(this)
    }

    override fun onSensorChanged(event: SensorEvent?) {

//        event?.let {
//            when (event.sensor.type) {
//                Sensor.TYPE_ACCELEROMETER -> {
//                    val timestamp = NtpTimeProvider.nowMs()
//                    val (x, y, z) = event.values
//                    // sensor type 10 for accel
//                    val line = "10,$timestamp,${event.timestamp},$x,$y,$z\n"
//                    try {
//                        fileWriter.write(line)
//                    } catch (e: IOException) {
//                        e.printStackTrace()
//                        SocketManager.debug("File write failed: ${e.message}")
//                    }
//                }
//
//                Sensor.TYPE_GYROSCOPE -> {
//                    val timestamp = NtpTimeProvider.nowMs()
//                    val (x, y, z) = event.values
//                    // sensor type 4 for gyro
//                    val line = "4,$timestamp,${event.timestamp},$x,$y,$z\n"
//                    try {
//                        fileWriter.write(line)
//                    } catch (e: IOException) {
//                        e.printStackTrace()
//                        SocketManager.debug("File write failed: ${e.message}")
//                    }
//                }
//
//                Sensor.TYPE_MAGNETIC_FIELD -> {
//                    val timestamp = NtpTimeProvider.nowMs()
//                    val (x, y, z) = event.values
//                    val line = "2,$timestamp,${event.timestamp},$x,$y,$z\n"
//                    try {
//                        fileWriter.write(line)
//                    } catch (e: IOException) {
//                        e.printStackTrace()
//                        SocketManager.debug("File write failed: ${e.message}")
//                    }
//                }
//
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
//
//                Sensor.TYPE_STEP_DETECTOR -> {
//                    val timestamp = NtpTimeProvider.nowMs()
//                    val steps = event.values[0] // 1.0 per step event
//                    val line = "19,$timestamp,${event.timestamp},$steps,0,0\n"
//                    try {
//                        fileWriter.write(line)
//                    } catch (e: IOException) {
//                        e.printStackTrace()
//                        SocketManager.debug("Step write failed: ${e.message}")
//                    }
//                }
//
//                Sensor.TYPE_STEP_COUNTER -> {
//                    val timestamp = NtpTimeProvider.nowMs()
//                    val count = event.values[0].toInt()
//                    if (startStepCount == null) {
//                        startStepCount = count
//                        // write a “header” CSV line, e.g.:
//                        fileWriter.write("18,$timestamp,0,$count,0,0\n")
//                    }else{
//                        endStepCount = count
//                    }
//                }
//
//
//            }
//        }

        event?.let {
            if (event.sensor.type == Sensor.TYPE_LINEAR_ACCELERATION) {
                latestLinearAccelData = event.values.copyOf()
                hasNewLinearAccelData.set(true)
                sendLinearAccelData()
            }
        }
    }

    private fun sendLinearAccelData() {
        latestLinearAccelData?.let { data ->
            val timestamp = NtpTimeProvider.nowMs()
            val formattedData = "$timestamp,${data[0]},${data[1]},${data[2]}"
            SocketManager.getSocket().emit("LinearAccelStream", formattedData)
            hasNewLinearAccelData.set(false)
        }


    }

    override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) {
        // Handle accuracy changes if needed
    }
}