package com.example.watchclientapp.presentation

import android.Manifest
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import android.widget.Toast
import androidx.annotation.RequiresPermission
import io.socket.client.Socket
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import android.util.Base64
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock


private var audioRecord: AudioRecord? = null
private val recordingMutex = Mutex()

private var isRecording = false
private val bufferSize = 1024 // Buffer size in bytes
private val audioBuffer = ByteArray(bufferSize) // Buffer to store audio data

@RequiresPermission(Manifest.permission.RECORD_AUDIO)
 suspend fun startRecording(socket: Socket) {
    // Audio configuration
    val sampleRate = 44100 // 44.1 kHz
    val channelConfig = AudioFormat.CHANNEL_IN_MONO
    val audioFormat = AudioFormat.ENCODING_PCM_16BIT

    // Calculate minimum buffer size
    val minBufferSize = AudioRecord.getMinBufferSize(sampleRate, channelConfig, audioFormat)

    // Initialize AudioRecord
    audioRecord = AudioRecord(
        MediaRecorder.AudioSource.MIC,
        sampleRate,
        channelConfig,
        audioFormat,
        minBufferSize
    )

    // Start recording
    audioRecord?.startRecording()
    recordingMutex.withLock {
        isRecording = true
    }

    // Read audio data into the buffer and send it over Socket.IO
    try {
        var shouldContinue = true
        while (shouldContinue) {
            recordingMutex.withLock {
                if (!isRecording) shouldContinue = false
            }

            val bytesRead = audioRecord?.read(audioBuffer, 0, bufferSize) ?: 0
            if (bytesRead > 0) {
                // Send the audio buffer over Socket.IO
                sendAudioBuffer(audioBuffer, bytesRead, socket)
            }
        }
    } finally {
        // Clean up
        audioRecord?.stop()
        audioRecord?.release()
        audioRecord = null
    }



}

 suspend fun stopRecording(socket: Socket) {
     recordingMutex.withLock {
         isRecording = false
     }
    socket.emit("test")

}

private fun sendAudioBuffer(buffer: ByteArray, bytesRead: Int, socket: Socket) {
    // Send the buffer over Socket.IO
    val bufferToSend = buffer.copyOf(bytesRead) // Send only the actual data read
    val base64Data = Base64.encodeToString(bufferToSend, Base64.DEFAULT)

    socket.emit("audioStream", base64Data)
}