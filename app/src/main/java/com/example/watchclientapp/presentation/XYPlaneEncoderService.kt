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

class XYPlaneEncoderService : Service(), SensorEventListener {

    private lateinit var sensorManager: SensorManager
    private lateinit var wakeLock: PowerManager.WakeLock

    // Variables to store the latest sensor data
    private var latestAccelData: FloatArray? = null
    private var latestGyroData: FloatArray? = null

    // Variables for velocity and displacement
    private var velocityX = 0f
    private var velocityY = 0f
    private var displacementX = 0f
    private var displacementY = 0f

    // Timestamp for delta time calculation
    private var lastTimestamp = 0L

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
        wakeLock = powerManager.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "XYPlaneEncoderService::WakeLock")
        wakeLock.acquire(60 * 60 * 1000L) // Keep the service awake for 1 hour
    }

    private fun releaseWakeLock() {
        if (wakeLock.isHeld) {
            wakeLock.release()
        }
    }

    private fun startForegroundService() {
        val channelId = "xy_plane_encoder_channel"
        val notificationManager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager

        val channel = NotificationChannel(
            channelId,
            "XY Plane Encoder",
            NotificationManager.IMPORTANCE_LOW
        )
        notificationManager.createNotificationChannel(channel)

        val notification: Notification = NotificationCompat.Builder(this, channelId)
            .setContentTitle("XY Plane Encoder")
            .setContentText("Tracking movement in XY plane...")
            .build()

        startForeground(3, notification)
        SocketManager.getSocket().emit("testingDebug", "the foreground service is started")
    }

    private fun registerSensors() {
        val accelerometer = sensorManager.getDefaultSensor(Sensor.TYPE_ACCELEROMETER)
        val gyroscope = sensorManager.getDefaultSensor(Sensor.TYPE_GYROSCOPE)

        if (accelerometer == null || gyroscope == null) {
            SocketManager.getSocket().emit("testingDebug", "the sensors are null")
            stopSelf() // Stop the service if sensors are not available
            return
        }

        sensorManager.registerListener(this, accelerometer, SensorManager.SENSOR_DELAY_FASTEST)
        sensorManager.registerListener(this, gyroscope, SensorManager.SENSOR_DELAY_FASTEST)
    }

    private fun unregisterSensors() {
        sensorManager.unregisterListener(this)
    }

    override fun onSensorChanged(event: SensorEvent?) {
        event?.let {
            val currentTimestamp = System.currentTimeMillis()
            val deltaTime = (currentTimestamp - lastTimestamp) / 1000f // Convert to seconds
            lastTimestamp = currentTimestamp

            when (event.sensor.type) {
                Sensor.TYPE_ACCELEROMETER -> {
                    latestAccelData = event.values

                    // Remove gravity (if needed) and get linear acceleration
                    val linearAccelerationX = event.values[0] // Assuming gravity is already removed
                    val linearAccelerationY = event.values[1]

                    // Integrate acceleration to get velocity
                    velocityX += linearAccelerationX * deltaTime
                    velocityY += linearAccelerationY * deltaTime

                    // Integrate velocity to get displacement
                    displacementX += velocityX * deltaTime
                    displacementY += velocityY * deltaTime

                    // Emit the XY coordinates
                    sendXYCoordinates()
                }
                Sensor.TYPE_GYROSCOPE -> {
                    latestGyroData = event.values

                    // Use gyroscope data to adjust orientation (if needed)
                    val angularVelocityZ = event.values[2] // Rotation around the z-axis
                    // Adjust the coordinate system based on rotation
                }
            }
        }
    }

    private fun sendXYCoordinates() {
        val timestamp = System.currentTimeMillis()
        val data = "$timestamp,$displacementX,$displacementY"
        SocketManager.getSocket().emit("XYCoordinates", data)
    }

    private fun resetOrigin() {
        displacementX = 0f
        displacementY = 0f
        velocityX = 0f
        velocityY = 0f
    }

    override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) {
        // Handle accuracy changes if needed
    }
}