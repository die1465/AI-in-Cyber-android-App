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
import android.content.pm.PackageManager
import android.os.Bundle
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
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.wear.compose.material.*
import androidx.wear.tooling.preview.devices.WearDevices
import com.example.watchclientapp.presentation.theme.WatchClientAppTheme
import io.socket.client.IO
import io.socket.client.Socket
import kotlinx.coroutines.*
import kotlinx.coroutines.channels.Channel
import com.example.watchclientapp.presentation.startRecording
import com.example.watchclientapp.presentation.stopRecording
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


    override fun onCreate(savedInstanceState: Bundle?) {
        installSplashScreen()
        super.onCreate(savedInstanceState)
        setTheme(android.R.style.Theme_DeviceDefault)
        checkAndRequestPermission()
        setContent {
            WearApp("Android")
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
    }
}

var socket: Socket? = null

@Composable
fun WearApp(greetingName: String) {
    // Socket.IO state
//    var socket by remember { mutableStateOf<Socket?>(null) }
    var isConnected by remember { mutableStateOf(false) }
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


                // Connect Button
                Button(
                    onClick = {
                        coroutineScope.launch {
                            connectSocketIO(
                                onSuccess = { s ->
                                    socket = s
                                    isConnected = true
                                },
                                onError = { error ->
                                    println("Connection error: $error")

                                }
                            )
                        }
                    },
                    enabled = !isConnected
                ) {
                    Text("Connect")
                }

                Spacer(modifier = Modifier.height(8.dp))

                // Disconnect Button
                Button(
                    onClick = {
                        socket?.disconnect()
                        socket = null
                        isConnected = false
                    },
                    enabled = isConnected
                ) {
                    Text("Disconnect")
                }
            }
        }
    }
}



// Function to connect to Socket.IO
private fun connectSocketIO(
    onSuccess: (Socket) -> Unit,
    onError: (String) -> Unit
) {
    CoroutineScope(Dispatchers.IO).launch {
        try {
            val options = IO.Options.builder()
                .setTransports(arrayOf("websocket")) // Use WebSocket transport
                .build()

            val socketInner = IO.socket("http://192.168.104.227:5001", options)

            val channel = Channel<String>()

            // Start the coroutine
            val recording = recordAudio(channel)


            // Listen for connection events
            socketInner.on(Socket.EVENT_CONNECT) {
                println("Connected to Socket.IO server")
                onSuccess(socketInner)
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

                CoroutineScope(Dispatchers.Default).launch {
                    channel.send("start recording")

                }
            }.on("stopRecordingAudio"){
                CoroutineScope(Dispatchers.Default).launch {
                    channel.send("stop and send recording")

                }
            }

            // Connect to the server
            socketInner.connect()

        } catch (e: Exception) {
            onError(e.message ?: "Unknown error")
        }
    }
}

@SuppressLint("MissingPermission")
private fun recordAudio(channel: Channel<String>) = CoroutineScope(Dispatchers.IO).launch  {
    for (message in channel) {
        if(message == "start recording"){
            CoroutineScope(Dispatchers.IO).launch {
                socket?.let { startRecording(it) }
            }

        }

        if (message == "stop and send recording"){
            socket?.let { stopRecording(it) }
        }
    }
}




@Preview(device = WearDevices.SMALL_ROUND, showSystemUi = true)
@Composable
fun DefaultPreview() {
    WearApp("Preview Android")
}
