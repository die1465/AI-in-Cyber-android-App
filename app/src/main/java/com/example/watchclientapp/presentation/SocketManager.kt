package com.example.watchclientapp.presentation


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
                }.on("stopRecordingAudio"){
                    // Stop the service on the main thread using the stored Intent
                    Handler(Looper.getMainLooper()).post {
                        context.stopService(audioRecorderServiceIntent)

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