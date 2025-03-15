package com.example.watchclientapp.presentation


import android.app.ActivityManager
import android.content.Context
import android.content.Intent
import android.os.Handler
import android.os.Looper
import io.socket.client.IO
import io.socket.client.Socket
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

object SocketManager {
    private var socket: Socket? = null

    fun initializeSocket( onSuccess: (Socket) -> Unit,
                          onError: (String) -> Unit,
                          context: Context
    ) {

            var audioRecorderServiceIntent: Intent? = null
            var sensorRecorderServiceIntent: Intent? = null
            var XYPlaneServiceIntent: Intent? = null
            var sensorServiceStarted: Boolean = false
            val watchServerURL = "http://192.168.104.227:5001"

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
                    Handler(Looper.getMainLooper()).post {
                        audioRecorderServiceIntent = Intent(context, AudioRecorderService::class.java).apply {
                            putExtra("serverUrl", watchServerURL) // Pass the server URL
                        }

                        context.startForegroundService(audioRecorderServiceIntent)


                    }
                }.on("stopRecordingAudio") {
                    // Stop the service on the main thread using the stored Intent
                    Handler(Looper.getMainLooper()).post {
                        context.stopService(audioRecorderServiceIntent)

                    }
                }.on("StartRecordingSensors"){
                    // start recording sensors
                    Handler(Looper.getMainLooper()).post {
                        if (!sensorServiceStarted) {
                            sensorRecorderServiceIntent =
                                Intent(context, SensorRecordingService::class.java)

                            context.startForegroundService(sensorRecorderServiceIntent)
                            sensorServiceStarted = true
                        }
                    }
                }.on("StopRecordingSensors"){
                    //stop and recording sensors
                    // Stop the service on the main thread using the stored Intent
                    Handler(Looper.getMainLooper()).post {
                        if(sensorServiceStarted){
                            context.stopService(sensorRecorderServiceIntent)
                            sensorServiceStarted = false

                        }


                    }
                }.on("StartEncodingIntoXYPlane"){
                    Handler(Looper.getMainLooper()).post {
                        if (!sensorServiceStarted) {
                            XYPlaneServiceIntent =
                                Intent(context, XYPlaneEncoderService::class.java)

                            context.startForegroundService(XYPlaneServiceIntent)
                        }
                    }
                }.on("StopEncodingIntoXYPlane"){
                    Handler(Looper.getMainLooper()).post {
                        if(sensorServiceStarted){
                            context.stopService(XYPlaneServiceIntent)

                        }
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



}