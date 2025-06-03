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

        if (linearAccel == null) {
            SocketManager.debug("Linear acceleration sensor not available")
            stopSelf()
            return
        }

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