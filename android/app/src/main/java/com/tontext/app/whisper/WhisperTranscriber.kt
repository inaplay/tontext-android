package com.tontext.app.whisper

import android.content.Context
import android.util.Log
import com.whispercpp.whisper.WhisperContext
import java.io.File

private const val LOG_TAG = "WhisperTranscriber"
private const val MODEL_FILENAME = "ggml-base-q5_1.bin"

class WhisperTranscriber(private val context: Context) {
    private var whisperContext: WhisperContext? = null

    val isModelLoaded: Boolean
        get() = whisperContext != null

    fun loadModel(): Boolean {
        val modelFile = getModelFile()
        if (!modelFile.exists()) {
            Log.w(LOG_TAG, "Model file not found: ${modelFile.absolutePath}")
            return false
        }
        return try {
            whisperContext = WhisperContext.createContextFromFile(modelFile.absolutePath)
            Log.d(LOG_TAG, "Model loaded successfully")
            true
        } catch (e: Exception) {
            Log.e(LOG_TAG, "Failed to load model", e)
            false
        }
    }

    suspend fun transcribe(audioData: FloatArray): String {
        val ctx = whisperContext ?: throw IllegalStateException("Model not loaded")
        return ctx.transcribeData(audioData)
    }

    fun release() {
        // Note: WhisperContext.release() is suspend but we call from non-suspend
        // The context will be freed via finalizer if not explicitly released
        whisperContext = null
    }

    fun getModelFile(): File {
        return File(context.filesDir, MODEL_FILENAME)
    }

    companion object {
        fun isModelDownloaded(context: Context): Boolean {
            return File(context.filesDir, MODEL_FILENAME).exists()
        }
    }
}
