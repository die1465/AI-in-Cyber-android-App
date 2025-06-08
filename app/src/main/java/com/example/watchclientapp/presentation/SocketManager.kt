package com.example.watchclientapp.presentation


import android.app.ActivityManager
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.Handler
import android.os.Looper
import androidx.core.content.ContextCompat
import io.socket.client.IO
import io.socket.client.Socket
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import okhttp3.*
import org.json.JSONObject
import java.io.IOException
import java.util.*
import java.util.concurrent.atomic.AtomicBoolean

object SocketManager {
    private var socket: Socket? = null
    private val ServerIP = "192.168.183.8"
    private val ServerPort = "5001"
    private val watchServerURL = "http://$ServerIP:$ServerPort"

    private val sensorServiceStarted = AtomicBoolean(false)
    private val linearAccelServiceStarted = AtomicBoolean(false)
    private val recordingServiceStarted = AtomicBoolean(false)
    private val isRecording = AtomicBoolean(false)

    private fun runOnMainThread(block: () -> Unit) {
        if (Looper.myLooper() == Looper.getMainLooper()) {
            block()
        } else {
            Handler(Looper.getMainLooper()).post { block() }
        }
    }

    fun initializeSocket(
        onSuccess: (Socket) -> Unit,
        onError: (String) -> Unit,
        context: Context
    ) {
        Thread.setDefaultUncaughtExceptionHandler { thread, throwable ->
            debug("Uncaught exception in thread socket manager ${thread.name}: ${throwable.message}\n${throwable.printStackTrace()}")
            throwable.printStackTrace()
        }



        try {
            val options = IO.Options.builder()
                .setTransports(arrayOf("websocket"))
                .setExtraHeaders(mapOf("device-type" to listOf("MainWatchConnection")))
                .build()

            socket = IO.socket(watchServerURL, options)

            socket!!.on(Socket.EVENT_CONNECT) {
                println("Connected to Socket.IO server")
                onSuccess(socket!!)
            }.on(Socket.EVENT_DISCONNECT) {
                println("Disconnected from Socket.IO server")
            }.on(Socket.EVENT_CONNECT_ERROR) { args ->
                val error = args[0].toString()
                println("Connection error: $error")
                onError(error)
            }.on("message") { args ->
                val message = args[0].toString()
                println("Received message: $message")
            }
//                .on("StartRecordingSensors") { args ->
//                val streamName = args[0].toString()
//                runOnMainThread {
//                    StartRecordingSensors(context, streamName)
//                }
//            }.on("StopRecordingSensors") {
//                runOnMainThread {
//                    StopRecordingSensors(context)
//                }
//            }
            .on("StartRecordingLinearAcceleration") {
                runOnMainThread {
                    if (linearAccelServiceStarted.compareAndSet(false, true)) {
                        val startIntent = Intent(context, LinearAccelerationRecordingService::class.java).apply {
                            action = "START_SERVICE"
                        }
                        ContextCompat.startForegroundService(context, startIntent)
                    }

                    val recordIntent = Intent(context, LinearAccelerationRecordingService::class.java).apply {
                        action = "START_RECORDING"
                    }
                    ContextCompat.startForegroundService(context, recordIntent)
                }
            }.on("StopRecordingLinearAcceleration") {
                runOnMainThread {
                    if (linearAccelServiceStarted.get()) {
                        val stopIntent = Intent(context, LinearAccelerationRecordingService::class.java).apply {
                            action = "STOP_RECORDING"
                        }
                        ContextCompat.startForegroundService(context, stopIntent)
                    }
                }
            }.on("getTime") {
//                getSocket().emit("WatchTime", NtpTimeProvider.nowMs())
                getSocket().emit("WatchTime", System.currentTimeMillis())
            }

            socket?.connect()
        } catch (e: Exception) {
            onError(e.message ?: "Unknown error")
        }
    }

    fun getSocket(): Socket {
        return socket ?: throw IllegalStateException("Socket is not initialized. Call initializeSocket() first.")
    }

    fun disconnectSocket() {
        socket?.disconnect()
        socket = null
    }

    fun getServerIP(): String = ServerIP

    fun getServerPort(): String = ServerPort
    fun getServerURL(): String = watchServerURL

    fun debug(Msg: String) {
        socket?.emit("testingDebug", Msg)
    }

    private fun StartRecordingSensors(context: Context, SocketSensorEventName: String) {
        if (sensorServiceStarted.compareAndSet(false, true)) {
            val sensorService = Intent(context, SensorRecordingService::class.java).apply {
                action = "START_SERVICE"
            }
            ContextCompat.startForegroundService(context, sensorService)
        }

        val intent = Intent(context, SensorRecordingService::class.java).apply {
            action = "START_RECORDING"
            putExtra("APIEndpoint", SocketSensorEventName)
        }
        ContextCompat.startForegroundService(context, intent)
    }

    private fun StopRecordingSensors(context: Context) {
        if (sensorServiceStarted.get()) {
            val stopIntent = Intent(context, SensorRecordingService::class.java).apply {
                action = "STOP_RECORDING"
            }
            ContextCompat.startForegroundService(context, stopIntent)
        }
    }
}
