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
    private var SocketStreamEventName : String = ""



    // SensorDirectChannel variables
    private var directChannel: SensorDirectChannel? = null
    private var memoryFile: MemoryFile? = null
    private var pfd: ParcelFileDescriptor? = null


    override fun onCreate() {
        super.onCreate()
        sensorManager = getSystemService(Context.SENSOR_SERVICE) as SensorManager
        acquireWakeLock()
//        SocketManager.getSocket().emit("testingDebug", "sensor recording service is created")
        startForegroundService()

    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            "START_SERVICE" -> {
                if(!isRecording) {
                    startForegroundService() // Just initialize but don't start recording yet
                    SocketManager.debug("Service initialized, awaiting recording commands")
                }
            }
            "START_RECORDING" -> {
                if (!isRecording) {
                    registerSensors()
                    isRecording = true
                    SocketManager.debug("Recording started")
                } else {
                    SocketManager.debug("Already recording, ignoring start command")
                }
            }
            "STOP_RECORDING" -> {
                if (isRecording) {
                    unregisterSensors()
                    isRecording = false
                    SocketManager.debug("Recording stopped")
                } else {
                    SocketManager.debug("Not currently recording, ignoring stop command")
                }
            }
            "STOP_SERVICE" -> {
                if (isRecording) {
                    unregisterSensors()
                    isRecording = false
                }

//                SocketManager.debug("Service stopping completely")
            }
        }

        when(intent?.getStringExtra("SocketEventName") ){
            "KeystrokeSensorStream" -> {
                SocketStreamEventName = "KeystrokeSensorStream"
            }
            "SensorStream" -> {
                SocketStreamEventName = "SensorStream"
            }
        }
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
//        SocketManager.getSocket().emit("testingDebug", "sensor the foreground service is started")

    }



    private fun registerSensors() {

        val desiredHZ = 10_000  // 100Hz = 10ms per sample
        val accelerometer = sensorManager.getDefaultSensor(Sensor.TYPE_ACCELEROMETER)
        val gyroscope = sensorManager.getDefaultSensor(Sensor.TYPE_GYROSCOPE)

        if (accelerometer == null || gyroscope == null) {
            SocketManager.debug("the sensors are null")
            stopSelf() // Stop the service if sensors are not available
            return
        }

        sensorManager.registerListener(this, accelerometer, desiredHZ, 0)
        sensorManager.registerListener(this, gyroscope, desiredHZ, 0)

        SocketManager.debug("the sensors are registered, sampling rate \naccel ${accelerometer.minDelay}" +
                "\n gyro ${gyroscope.minDelay}")

    }

    private fun unregisterSensors() {
        sensorManager.unregisterListener(this)
    }

    override fun onSensorChanged(event: SensorEvent?) {
        event?.let {
            if (event.sensor.type == Sensor.TYPE_ACCELEROMETER) {
                latestAccelData = event.values
                hasNewAccelData.set(true)
            }

            if(event.sensor.type == Sensor.TYPE_GYROSCOPE){
                latestGyroData = event.values
                hasNewGyroData.set(true)
            }

            // Check if both sensors have new data
            if (hasNewAccelData.get() || hasNewGyroData.get()) {
                sendSensorData(SocketStreamEventName)
                hasNewAccelData.set(false)
                hasNewGyroData.set(false)
            }
        }
    }

    private fun sendSensorData(eventName : String = "SensorStream") {


        val timestamp = System.currentTimeMillis()
        val accelX = latestAccelData?.get(0) ?: 0f
        val accelY = latestAccelData?.get(1) ?: 0f
        val accelZ = latestAccelData?.get(2) ?: 0f
        val gyroX = latestGyroData?.get(0) ?: 0f
        val gyroY = latestGyroData?.get(1) ?: 0f
        val gyroZ = latestGyroData?.get(2) ?: 0f

        val data = "$timestamp,$accelX,$accelY,$accelZ,$gyroX,$gyroY,$gyroZ"
        SocketManager.getSocket().emit(eventName,data)


    }


    override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) {
        // Handle accuracy changes if needed
    }
}