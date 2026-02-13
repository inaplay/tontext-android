package com.tontext.app.audio

import android.annotation.SuppressLint
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import android.util.Log
import java.nio.ByteBuffer
import java.nio.ByteOrder

private const val LOG_TAG = "AudioRecorder"
private const val SAMPLE_RATE = 16000
private const val CHANNEL_CONFIG = AudioFormat.CHANNEL_IN_MONO
private const val AUDIO_FORMAT = AudioFormat.ENCODING_PCM_16BIT

class AudioRecorder {
    private var audioRecord: AudioRecord? = null
    private var recordingThread: Thread? = null
    @Volatile
    private var isRecording = false
    private val audioData = mutableListOf<Short>()
    private var amplitudeCallback: ((Float) -> Unit)? = null

    fun onAmplitude(callback: (Float) -> Unit) {
        amplitudeCallback = callback
    }

    @SuppressLint("MissingPermission")
    fun start() {
        if (isRecording) return

        val bufferSize = AudioRecord.getMinBufferSize(SAMPLE_RATE, CHANNEL_CONFIG, AUDIO_FORMAT)
            .coerceAtLeast(SAMPLE_RATE) // At least 1 second buffer

        audioRecord = AudioRecord(
            MediaRecorder.AudioSource.MIC,
            SAMPLE_RATE,
            CHANNEL_CONFIG,
            AUDIO_FORMAT,
            bufferSize
        )

        if (audioRecord?.state != AudioRecord.STATE_INITIALIZED) {
            Log.e(LOG_TAG, "AudioRecord failed to initialize")
            audioRecord?.release()
            audioRecord = null
            return
        }

        audioData.clear()
        isRecording = true
        audioRecord?.startRecording()

        recordingThread = Thread {
            val buffer = ShortArray(1024)
            while (isRecording) {
                val read = audioRecord?.read(buffer, 0, buffer.size) ?: 0
                if (read > 0) {
                    synchronized(audioData) {
                        for (i in 0 until read) {
                            audioData.add(buffer[i])
                        }
                    }
                    // Calculate RMS amplitude for visualization
                    var sum = 0.0
                    for (i in 0 until read) {
                        sum += buffer[i].toDouble() * buffer[i].toDouble()
                    }
                    val rms = Math.sqrt(sum / read).toFloat()
                    val normalized = (rms / Short.MAX_VALUE).coerceIn(0f, 1f)
                    amplitudeCallback?.invoke(normalized)
                }
            }
        }.also { it.start() }

        Log.d(LOG_TAG, "Recording started")
    }

    fun stop(): FloatArray {
        isRecording = false
        recordingThread?.join(1000)
        recordingThread = null

        audioRecord?.stop()
        audioRecord?.release()
        audioRecord = null

        val result: FloatArray
        synchronized(audioData) {
            result = FloatArray(audioData.size) { audioData[it].toFloat() / Short.MAX_VALUE }
            audioData.clear()
        }

        Log.d(LOG_TAG, "Recording stopped, ${result.size} samples")
        return result
    }

    fun isRecording(): Boolean = isRecording
}
