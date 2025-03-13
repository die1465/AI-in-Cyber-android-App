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
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import android.os.Build
import android.os.IBinder
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


class SensorRecordingService : Service(), SensorEventListener {

    private lateinit var sensorManager: SensorManager
    private lateinit var wakeLock: PowerManager.WakeLock


    // Variables to store the latest sensor data
    private var latestAccelData: FloatArray? = null
    private var latestGyroData: FloatArray? = null
    private val hasNewAccelData = AtomicBoolean(false)
    private val hasNewGyroData = AtomicBoolean(false)

    override fun onCreate() {
        super.onCreate()
        sensorManager = getSystemService(Context.SENSOR_SERVICE) as SensorManager
        acquireWakeLock()
        SocketManager.getSocket().emit("testingDebug", "this service is created")
        startForegroundService()

    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        SocketManager.getSocket().emit("testingDebug", "this service is started")
        registerSensors()
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
        SocketManager.getSocket().emit("testingDebug", "the foreground service is started")

    }



    private fun registerSensors() {

//        SocketManager.getSocket().emit("testingDebug", "entered registerSensors function")
        val accelerometer = sensorManager.getDefaultSensor(Sensor.TYPE_ACCELEROMETER)
        val gyroscope = sensorManager.getDefaultSensor(Sensor.TYPE_GYROSCOPE)

        if (accelerometer == null || gyroscope == null) {
            SocketManager.getSocket().emit("testingDebug", "the sensors are null")
            stopSelf() // Stop the service if sensors are not available
            return
        }

        sensorManager.registerListener(this, accelerometer, SensorManager.SENSOR_DELAY_FASTEST)
        sensorManager.registerListener(this, gyroscope, SensorManager.SENSOR_DELAY_FASTEST)

//        SocketManager.getSocket().emit("testingDebug", "the sensors are registered")

    }

    private fun unregisterSensors() {
        sensorManager.unregisterListener(this)
    }

    override fun onSensorChanged(event: SensorEvent?) {
        event?.let {
            when (event.sensor.type) {
                Sensor.TYPE_ACCELEROMETER -> {
                    latestAccelData = event.values
                    hasNewAccelData.set(true)
                }
                Sensor.TYPE_GYROSCOPE -> {
                    latestGyroData = event.values
                    hasNewGyroData.set(true)
                }
            }

            // Check if both sensors have new data
            if (hasNewAccelData.get() || hasNewGyroData.get()) {
                sendSensorData()
                hasNewAccelData.set(false)
                hasNewGyroData.set(false)
            }
        }
    }

    private fun sendSensorData() {
        val timestamp = System.currentTimeMillis()
        val accelX = latestAccelData?.get(0) ?: 0f
        val accelY = latestAccelData?.get(1) ?: 0f
        val accelZ = latestAccelData?.get(2) ?: 0f
        val gyroX = latestGyroData?.get(0) ?: 0f
        val gyroY = latestGyroData?.get(1) ?: 0f
        val gyroZ = latestGyroData?.get(2) ?: 0f

        val data = "$timestamp,$accelX,$accelY,$accelZ,$gyroX,$gyroY,$gyroZ"
        SocketManager.getSocket().emit("SensorStream",data)
    }

    override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) {
        // Handle accuracy changes if needed
    }
}