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
    private var currentState = KeyboardState.IDLE

    private val allowedTransitions = mapOf(
        KeyboardState.IDLE to setOf(KeyboardState.RECORDING),
        KeyboardState.RECORDING to setOf(KeyboardState.TRANSCRIBING, KeyboardState.IDLE),
        KeyboardState.TRANSCRIBING to setOf(KeyboardState.IDLE),
    )

    private fun transitionTo(newState: KeyboardState) {
        if (newState == currentState) return
        val allowed = allowedTransitions[currentState]
        if (allowed == null || newState !in allowed) {
            Log.w(LOG_TAG, "Invalid state transition: $currentState → $newState, ignoring")
            return
        }
        Log.d(LOG_TAG, "State: $currentState → $newState")
        currentState = newState
        keyboardView?.setState(newState)
    }

    private fun forceIdle() {
        if (currentState == KeyboardState.RECORDING) {
            audioRecorder?.stop()
            audioRecorder = null
        }
        if (currentState == KeyboardState.TRANSCRIBING) {
            transcriptionJob?.cancel()
            transcriptionJob = null
        }
        if (currentState != KeyboardState.IDLE) {
            Log.d(LOG_TAG, "State: $currentState → IDLE (forced)")
            currentState = KeyboardState.IDLE
            keyboardView?.setState(KeyboardState.IDLE)
        }
    }

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

    override fun onFinishInputView(finishingInput: Boolean) {
        super.onFinishInputView(finishingInput)
        forceIdle()
    }

    override fun onWindowHidden() {
        super.onWindowHidden()
        forceIdle()
    }

    private fun startRecording() {
        if (currentState != KeyboardState.IDLE) {
            Log.w(LOG_TAG, "startRecording() ignored, currentState=$currentState")
            return
        }

        if (!WhisperTranscriber.isModelDownloaded(this)) {
            val intent = Intent(this, SetupActivity::class.java).apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            startActivity(intent)
            return
        }

        if (transcriber?.isModelLoaded != true) {
            serviceScope.launch(Dispatchers.IO) {
                transcriber?.loadModel()
            }
        }

        audioRecorder = AudioRecorder().apply {
            onAmplitude { amplitude ->
                mainHandler.post {
                    keyboardView?.waveformView?.addAmplitude(amplitude)
                    keyboardView?.updatePulseWithAmplitude(amplitude)
                }
            }
            start()
        }
        transitionTo(KeyboardState.RECORDING)
        Log.d(LOG_TAG, "Recording started")
    }

    private fun stopRecordingAndTranscribe() {
        if (currentState != KeyboardState.RECORDING) {
            Log.w(LOG_TAG, "stopRecordingAndTranscribe() ignored, currentState=$currentState")
            return
        }

        val audioData = audioRecorder?.stop() ?: return
        audioRecorder = null
        transitionTo(KeyboardState.TRANSCRIBING)

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
                transitionTo(KeyboardState.IDLE)
            }
        }
    }

    private fun cancelTranscription() {
        if (currentState != KeyboardState.RECORDING && currentState != KeyboardState.TRANSCRIBING) {
            Log.w(LOG_TAG, "cancelTranscription() ignored, currentState=$currentState")
            return
        }
        forceIdle()
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
        forceIdle()
        super.onDestroy()
        serviceScope.cancel()
        transcriber?.release()
    }
}
