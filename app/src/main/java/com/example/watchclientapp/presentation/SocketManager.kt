package com.example.watchclientapp.presentation


import android.app.ActivityManager
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.Handler
import android.os.Looper
import io.socket.client.IO
import io.socket.client.Socket
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import okhttp3.*
import java.io.IOException
import java.util.*

object SocketManager {
    private var socket: Socket? = null
    private val ServerIP = "192.168.140.8"


    // Move these outside the initializeSocket method so they persist
    private var audioRecorderServiceIntent: Intent? = null
    private var sensorRecorderServiceIntent: Intent? = null
    private var XYPlaneServiceIntent: Intent? = null
    private var sensorServiceStarted = false
    private var linearAccelServiceStarted = false
    private var RecordingServiceStarted = false

    
    fun initializeSocket( onSuccess: (Socket) -> Unit,
                          onError: (String) -> Unit,
                          context: Context
    ) {

        Thread.setDefaultUncaughtExceptionHandler { thread, throwable ->
            debug("Uncaught exception in thread socket manager ${thread.name}: ${throwable.message}\n${throwable.printStackTrace()}")
            throwable.printStackTrace()
        }






            val watchServerURL = "http://$ServerIP:5001"

            try {
                val options = IO.Options.builder()
                    .setTransports(arrayOf("websocket")) // Use WebSocket transport
                    .setExtraHeaders(mapOf("device-type" to listOf("MainWatchConnection")))
                    .build()

                socket = IO.socket(watchServerURL, options)



                // Listen for connection events
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
                }.on("startRecordingAudio"){

                    // Start the service on the main thread
                    if (!RecordingServiceStarted) {

                        val startAudioRecordingServiceIntent =
                            Intent(context, AudioRecorderService::class.java).apply {
                                action = "START_SERVICE"
                            }
                        context.startService(startAudioRecordingServiceIntent)
                        RecordingServiceStarted = true

                    }

                    // Just tell the existing service to start recording
                    val recordIntent = Intent(context, AudioRecorderService::class.java).apply {
                        action = "START_RECORDING"
                    }
                    context.startService(recordIntent)

                    StartRecordingSensors(context, "KeystrokeSensorStream")
                    debug("Sent start recording command to service")



                }.on("stopRecordingAudio") {
                    // Stop the service on the main thread using the stored Intent
//                    Handler(Looper.getMainLooper()).post {

                        val intent = Intent(context, AudioRecorderService::class.java).apply {
                            action = "STOP_RECORDING"
                        }
                        context.startService(intent)

                        StopRecordingSensors(context)

//                    }
                }.on("StartRecordingSensors"){
                    // start recording sensor
//                    debug("got start Recording sensors")
                    StartRecordingSensors(context, "SensorStream")


                }.on("StopRecordingSensors"){
                    //stop and recording sensors
                    // Stop the service on the main thread using the stored Intent

                    StopRecordingSensors(context)




                }.on("StartRecordingLinearAcceleration"){
                    if (!linearAccelServiceStarted) {
                        initializeLinearAccelSensorService(context)
                        linearAccelServiceStarted = true
                    }

                    val StartIntent = Intent(context, LinearAccelerationRecordingService::class.java).apply {
                        action = "START_RECORDING"
                    }
                    context.startService(StartIntent)
                }.on("StopRecordingLinearAcceleration"){
                    if (linearAccelServiceStarted) {
                        val stopRecordIntent = Intent(context, LinearAccelerationRecordingService::class.java).apply {
                            action = "STOP_RECORDING"
                        }
                        context.startService(stopRecordIntent)
//                        debug("Sent stop recording command to service")
                    }
                }

                // Connect to the server
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

    fun getServerIP(): String{
        return ServerIP;
    }

    fun debug(Msg: String){
        socket!!.emit("testingDebug", Msg);
    }

    // Initialize service only once at appropriate time (app start or connection)
    private fun initializeSensorService(context : Context
                                ) {
        val sensorService = Intent(context, SensorRecordingService::class.java).apply {
            action = "START_SERVICE"
        }
        context.startForegroundService(sensorService)
        sensorServiceStarted = true
//        debug("Sensor service initialized")
    }


    // Initialize service only once at appropriate time (app start or connection)
    private fun initializeLinearAccelSensorService(context : Context
    ) {
        val sensorService = Intent(context, LinearAccelerationRecordingService::class.java).apply {
            action = "START_SERVICE"
        }
        context.startForegroundService(sensorService)
        sensorServiceStarted = true
        debug("Sensor service initialized")
    }

    private fun StartRecordingSensors(context: Context, SocketSensorEventName: String){
        if (!sensorServiceStarted) {
            initializeSensorService(context)
            sensorServiceStarted = true
        }

        val intent = Intent(context, SensorRecordingService::class.java).apply {
            action = "START_RECORDING"
            putExtra("SocketEventName", SocketSensorEventName)
        }
        context.startService(intent)
    }

    private fun StopRecordingSensors(context: Context){
        if (sensorServiceStarted) {
            val stopRecordIntent = Intent(context, SensorRecordingService::class.java).apply {
                action = "STOP_RECORDING"
            }
            context.startService(stopRecordIntent)
//                        debug("Sent stop recording command to service")
        }
    }
}