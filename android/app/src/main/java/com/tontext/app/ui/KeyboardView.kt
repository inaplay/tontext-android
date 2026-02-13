package com.tontext.app.ui

import android.content.Context
import android.util.AttributeSet
import android.view.LayoutInflater
import android.view.MotionEvent
import android.view.View
import android.widget.FrameLayout
import android.widget.ImageButton
import android.widget.TextView
import com.tontext.app.R

enum class KeyboardState {
    IDLE, RECORDING, TRANSCRIBING
}

class KeyboardView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : FrameLayout(context, attrs, defStyleAttr) {

    val waveformView: WaveformView
    val micButton: ImageButton
    val statusText: TextView
    val switchKeyboardButton: ImageButton

    var onRecordStart: (() -> Unit)? = null
    var onRecordStop: (() -> Unit)? = null
    var onCancelTranscription: (() -> Unit)? = null
    var onSwitchKeyboard: (() -> Unit)? = null

    private var state = KeyboardState.IDLE

    init {
        LayoutInflater.from(context).inflate(R.layout.keyboard_view, this, true)
        waveformView = findViewById(R.id.waveformView)
        micButton = findViewById(R.id.micButton)
        statusText = findViewById(R.id.statusText)
        switchKeyboardButton = findViewById(R.id.switchKeyboardButton)

        micButton.setOnTouchListener { _, event ->
            when (event.action) {
                MotionEvent.ACTION_DOWN -> {
                    if (state == KeyboardState.IDLE) {
                        onRecordStart?.invoke()
                    }
                    true
                }
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                    if (state == KeyboardState.RECORDING) {
                        onRecordStop?.invoke()
                    } else if (state == KeyboardState.TRANSCRIBING) {
                        onCancelTranscription?.invoke()
                    }
                    true
                }
                else -> false
            }
        }

        switchKeyboardButton.setOnClickListener {
            onSwitchKeyboard?.invoke()
        }
    }

    fun setState(newState: KeyboardState) {
        state = newState
        when (state) {
            KeyboardState.IDLE -> {
                micButton.setImageResource(R.drawable.ic_mic)
                micButton.background = context.getDrawable(R.drawable.mic_button_bg)
                statusText.text = ""
                waveformView.clear()
            }
            KeyboardState.RECORDING -> {
                micButton.setImageResource(R.drawable.ic_mic)
                statusText.text = context.getString(R.string.recording)
                statusText.setTextColor(context.getColor(R.color.accent))
            }
            KeyboardState.TRANSCRIBING -> {
                micButton.setImageResource(R.drawable.ic_stop)
                statusText.text = context.getString(R.string.transcribing)
                statusText.setTextColor(context.getColor(R.color.text_secondary))
                waveformView.clear()
            }
        }
    }
}
