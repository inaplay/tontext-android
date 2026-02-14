package com.tontext.app

import android.content.Intent
import android.inputmethodservice.InputMethodService
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.view.View
import android.os.Build
import com.tontext.app.audio.AudioRecorder
import com.tontext.app.ui.KeyboardState
import com.tontext.app.ui.KeyboardView
import com.tontext.app.whisper.WhisperTranscriber
import kotlinx.coroutines.*

private const val LOG_TAG = "TonTextIME"

class TonTextIMEService : InputMethodService() {

    private var keyboardView: KeyboardView? = null
    private var audioRecorder: AudioRecorder? = null
    private var transcriber: WhisperTranscriber? = null
    private var transcriptionJob: Job? = null
    private val mainHandler = Handler(Looper.getMainLooper())
    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.Main)

    override fun onCreateInputView(): View {
        keyboardView = KeyboardView(this).apply {
            onRecordStart = { startRecording() }
            onRecordStop = { stopRecordingAndTranscribe() }
            onCancelTranscription = { cancelTranscription() }
            onSwitchKeyboard = { switchToNextKeyboard() }
            onBackspace = { deleteOneCharacter() }
        }

        // Lazily init transcriber
        if (transcriber == null) {
            transcriber = WhisperTranscriber(this)
            serviceScope.launch(Dispatchers.IO) {
                transcriber?.loadModel()
            }
        }

        return keyboardView!!
    }

    private fun startRecording() {
        if (!WhisperTranscriber.isModelDownloaded(this)) {
            // Model not downloaded yet, open setup
            val intent = Intent(this, SetupActivity::class.java).apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            startActivity(intent)
            return
        }

        // Check if model is loaded, load it if not
        if (transcriber?.isModelLoaded != true) {
            serviceScope.launch(Dispatchers.IO) {
                transcriber?.loadModel()
            }
        }

        audioRecorder = AudioRecorder().apply {
            onAmplitude { amplitude ->
                mainHandler.post {
                    keyboardView?.waveformView?.addAmplitude(amplitude)
                }
            }
            start()
        }
        keyboardView?.setState(KeyboardState.RECORDING)
        Log.d(LOG_TAG, "Recording started")
    }

    private fun stopRecordingAndTranscribe() {
        val audioData = audioRecorder?.stop() ?: return
        audioRecorder = null
        keyboardView?.setState(KeyboardState.TRANSCRIBING)

        Log.d(LOG_TAG, "Transcribing ${audioData.size} samples")

        transcriptionJob = serviceScope.launch {
            try {
                val result = withContext(Dispatchers.IO) {
                    transcriber?.transcribe(audioData) ?: ""
                }
                if (result.isNotBlank()) {
                    currentInputConnection?.commitText("$result ", 1)
                    Log.d(LOG_TAG, "Committed text: $result")
                }
            } catch (e: CancellationException) {
                Log.d(LOG_TAG, "Transcription cancelled")
            } catch (e: Exception) {
                Log.e(LOG_TAG, "Transcription failed", e)
            } finally {
                keyboardView?.setState(KeyboardState.IDLE)
            }
        }
    }

    private fun cancelTranscription() {
        transcriptionJob?.cancel()
        transcriptionJob = null
        audioRecorder?.stop()
        audioRecorder = null
        keyboardView?.setState(KeyboardState.IDLE)
        Log.d(LOG_TAG, "Transcription cancelled by user")
    }

    private fun deleteOneCharacter() {
        currentInputConnection?.deleteSurroundingText(1, 0)
    }

    private fun switchToNextKeyboard() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            val switched = switchToPreviousInputMethod()
            if (!switched) {
                switchToNextInputMethod(false)
            }
        } else {
            switchToNextInputMethod(false)
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        serviceScope.cancel()
        audioRecorder?.stop()
        transcriber?.release()
    }
}
