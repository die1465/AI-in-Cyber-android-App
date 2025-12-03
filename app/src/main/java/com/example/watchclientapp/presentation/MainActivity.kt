/* While this template provides a good starting point for using Wear Compose, you can always
 * take a look at https://github.com/android/wear-os-samples/tree/main/ComposeStarter to find the
 * most up to date changes to the libraries and their usages.
 */

package com.example.watchclientapp.presentation
///* While this template provides a good starting point for using Wear Compose, you can always
// * take a look at https://github.com/android/wear-os-samples/tree/main/ComposeStarter to find the
// * most up to date changes to the libraries and their usages.
// */
//


import android.annotation.SuppressLint
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.PowerManager
import android.provider.Settings
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.splashscreen.SplashScreen.Companion.installSplashScreen
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.core.content.ContextCompat.startForegroundService
import androidx.wear.compose.material.*
import androidx.wear.tooling.preview.devices.WearDevices
import com.example.watchclientapp.presentation.theme.WatchClientAppTheme
import io.socket.client.IO
import io.socket.client.Socket
import kotlinx.coroutines.*
import kotlinx.coroutines.channels.Channel
//import com.example.watchclientapp.presentation.startRecording
//import com.example.watchclientapp.presentation.stopRecording
import com.example.watchclientapp.presentation.AudioRecorderService
import okhttp3.Dispatcher


class MainActivity : ComponentActivity() {

    private val requestPermissionLauncher =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { isGranted ->
            if(!isGranted){
                // Permission is denied, show a message or close the app
                Toast.makeText(this, "Permission denied", Toast.LENGTH_SHORT).show()
                finish()
            }
        }

    private lateinit var wakeLock: PowerManager.WakeLock

    override fun onCreate(savedInstanceState: Bundle?) {
        installSplashScreen()
        super.onCreate(savedInstanceState)
        setTheme(android.R.style.Theme_DeviceDefault)
        checkAndRequestPermission()

        NtpTimeProvider.initialize(this)

        val powerManager = getSystemService(Context.POWER_SERVICE) as PowerManager
        wakeLock = powerManager.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "MyApp::MyWakelockTag")
        wakeLock.acquire(60 * 60 * 1000L * 24 * 24 /*1 hour*/) // Timeout to prevent battery drain


            val intent = Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS)
            startActivity(intent)



        Thread.setDefaultUncaughtExceptionHandler { thread, throwable ->
            SocketManager.debug("Uncaught exception in thread Main Acitivity ${thread.name}: ${throwable.message}\n${throwable.printStackTrace()}")
            throwable.printStackTrace()
        }
        setContent {
            WearApp("Android", this@MainActivity)
        }
    }

    private fun checkAndRequestPermission() {
        if (ContextCompat.checkSelfPermission(
                this,
                android.Manifest.permission.RECORD_AUDIO
            ) == PackageManager.PERMISSION_GRANTED
        ) {
            // Permission is already granted, proceed with recording

        } else {
            // Request the permission
            requestPermissionLauncher.launch(android.Manifest.permission.RECORD_AUDIO)
        }

        if (ContextCompat.checkSelfPermission(
                this,
                android.Manifest.permission.BODY_SENSORS
            ) == PackageManager.PERMISSION_GRANTED
        ) {
            // Permission is already granted, proceed with recording

        } else {
            // Request the permission
            requestPermissionLauncher.launch(android.Manifest.permission.BODY_SENSORS)
        }

        if (ContextCompat.checkSelfPermission(
                this,
                android.Manifest.permission.ACTIVITY_RECOGNITION
            ) == PackageManager.PERMISSION_GRANTED
        ) {
            // Permission is already granted, proceed with recording

        } else {
            // Request the permission
            requestPermissionLauncher.launch(android.Manifest.permission.ACTIVITY_RECOGNITION)
        }
    }

    override fun onDestroy() {
        // Release the WakeLock
        if (::wakeLock.isInitialized && wakeLock.isHeld) {
            wakeLock.release()
        }
        super.onDestroy()
    }



}

var socket: Socket? = null

enum class ConnectionState {
    Disconnected,
    Connecting,
    Connected
}

@Composable
fun WearApp(greetingName: String, context: Context) {
    // track where we are in the connect/disconnect flow
    var connectionState by remember { mutableStateOf(ConnectionState.Disconnected) }
    val coroutineScope = rememberCoroutineScope()

    WatchClientAppTheme {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(MaterialTheme.colors.background),
            contentAlignment = Alignment.Center
        ) {
            TimeText()
            Column(
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.Center,
                modifier = Modifier.fillMaxSize()
            ) {
                when (connectionState) {
                    ConnectionState.Disconnected -> {
                        Button(
                            onClick = {
                                // flip into “connecting” immediately
                                connectionState = ConnectionState.Connecting
                                coroutineScope.launch {
                                    SocketManager.initializeSocket(
                                        onSuccess = { s ->
                                            socket = s
                                            connectionState = ConnectionState.Connected
                                            NtpTimeProvider.forceSync()
                                        },
                                        onError = { error ->
                                            println("Connection error: $error")
                                            // go back to “disconnected” so they can retry
                                            connectionState = ConnectionState.Disconnected
                                        },
                                        context = context
                                    )

                                }
                                coroutineScope.launch {
                                    val intent = Intent(context, AudioRecorderService::class.java).apply {
                                        action = "CONNECT_SERVICE"
                                    }
                                    ContextCompat.startForegroundService(context, intent)
                                }

                                coroutineScope.launch {
                                    val intent = Intent(context, SensorRecordingService::class.java).apply {
                                        action = "CONNECT_SERVICE"
                                    }
                                    ContextCompat.startForegroundService(context, intent)
                                }
                            },
                            // only enabled if we're fully disconnected
                            enabled = true
                        ) {
                            Text("Connect")
                        }
                    }
                    ConnectionState.Connecting -> {
                        // you can swap the button out for a spinner, or just disable it
                        Button(
                            onClick = { /* no-op */ },
                            enabled = false
                        ) {
                            // or replace this with CircularProgressIndicator(...)
                            Text("Connecting…")
                        }
                    }
                    ConnectionState.Connected -> {
                        Button(
                            onClick = {
                                SocketManager.disconnectSocket()
                                socket = null
                                connectionState = ConnectionState.Disconnected
                                val intent = Intent(context, AudioRecorderService::class.java).apply {
                                    action = "DISCONNECT_SERVICE"
                                }
                                ContextCompat.startForegroundService(context, intent)

                                val sensorIntent = Intent(context, SensorRecordingService::class.java).apply {
                                    action = "DISCONNECT_SERVICE"
                                }
                                ContextCompat.startForegroundService(context, sensorIntent)
                            },
                            enabled = true
                        ) {
                            Text("Disconnect")
                        }
                    }
                }
            }
        }
    }
}

// Function to connect to Socket.IO
//@SuppressLint("MissingPermission")
//private fun connectSocketIO(
//    onSuccess: (Socket) -> Unit,
//    onError: (String) -> Unit,
//    context: Context
//) {
//    CoroutineScope(Dispatchers.IO).launch {
//        var audioRecorderServiceIntent: Intent? = null
//        val watchServerURL = "http://192.168.104.227:5001"
//
//        try {
//            val options = IO.Options.builder()
//                .setTransports(arrayOf("websocket")) // Use WebSocket transport
//                .setExtraHeaders(mapOf("device-type" to listOf("MainWatchConnection")))
//                .build()
//            SocketManager.initializeSocket(watchServerURL)
//            val socketInner = SocketManager.getSocket()
//
//
//
//            // Listen for connection events
//            socketInner.on(Socket.EVENT_CONNECT) {
//                println("Connected to Socket.IO server")
//                onSuccess(socketInner)
//            }.on(Socket.EVENT_DISCONNECT) {
//                println("Disconnected from Socket.IO server")
//            }.on(Socket.EVENT_CONNECT_ERROR) { args ->
//                val error = args[0].toString()
//                println("Connection error: $error")
//                onError(error)
//            }.on("message") { args ->
//                val message = args[0].toString()
//                println("Received message: $message")
//            }.on("startRecordingAudio"){
//
//                // Start the service on the main thread
//                Handler(Looper.getMainLooper()).post {
//                    audioRecorderServiceIntent = Intent(context, AudioRecorderService::class.java).apply {
//                        putExtra("serverUrl", watchServerURL) // Pass the server URL
//                    }
//
//                    context.startForegroundService(audioRecorderServiceIntent)
//
//
//                }
//            }.on("stopRecordingAudio"){
//                // Stop the service on the main thread using the stored Intent
//                Handler(Looper.getMainLooper()).post {
//                    context.stopService(audioRecorderServiceIntent)
//
//                }
//            }
//
//            // Connect to the server
//            socketInner.connect()
//
//        } catch (e: Exception) {
//            onError(e.message ?: "Unknown error")
//        }
//    }
//}





@Preview(device = WearDevices.SMALL_ROUND, showSystemUi = true)
@Composable
fun DefaultPreview() {
    WearApp("Preview Android", LocalContext.current)
}
