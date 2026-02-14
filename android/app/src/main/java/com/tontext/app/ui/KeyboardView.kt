package com.tontext.app.ui

import android.animation.ValueAnimator
import android.content.Context
import android.os.Handler
import android.os.Looper
import android.util.AttributeSet
import android.view.LayoutInflater
import android.view.MotionEvent
import android.view.View
import android.view.animation.AccelerateDecelerateInterpolator
import android.widget.FrameLayout
import android.widget.ImageButton
import android.widget.TextView
import com.tontext.app.R

private const val BACKSPACE_INITIAL_DELAY_MS = 400L
private const val BACKSPACE_REPEAT_DELAY_MS = 50L
private const val PULSE_DURATION_MS = 1500L
private const val DOT_BLINK_DURATION_MS = 1200L

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
    val backspaceButton: ImageButton
    private val pulseCircle: View
    private val recordingDot: View
    private val transcribingText: TextView

    var onRecordStart: (() -> Unit)? = null
    var onRecordStop: (() -> Unit)? = null
    var onCancelTranscription: (() -> Unit)? = null
    var onSwitchKeyboard: (() -> Unit)? = null
    var onBackspace: (() -> Unit)? = null

    private var state = KeyboardState.IDLE
    private var backspaceHandler: Handler? = null
    private var isBackspaceLongPress = false
    private var pulseAnimator: ValueAnimator? = null
    private var dotBlinkAnimator: ValueAnimator? = null
    private var breathingScale = 1f
    private var currentAmplitude = 0f

    private val backspaceRepeatRunnable = object : Runnable {
        override fun run() {
            onBackspace?.invoke()
            backspaceHandler?.postDelayed(this, BACKSPACE_REPEAT_DELAY_MS)
        }
    }

    init {
        LayoutInflater.from(context).inflate(R.layout.keyboard_view, this, true)
        waveformView = findViewById(R.id.waveformView)
        micButton = findViewById(R.id.micButton)
        statusText = findViewById(R.id.statusText)
        switchKeyboardButton = findViewById(R.id.switchKeyboardButton)
        backspaceButton = findViewById(R.id.backspaceButton)
        pulseCircle = findViewById(R.id.pulseCircle)
        recordingDot = findViewById(R.id.recordingDot)
        transcribingText = findViewById(R.id.transcribingText)

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

        backspaceButton.setOnClickListener {
            if (!isBackspaceLongPress) {
                onBackspace?.invoke()
            }
            isBackspaceLongPress = false
        }

        backspaceButton.setOnLongClickListener {
            isBackspaceLongPress = true
            startBackspaceRepeat()
            true
        }

        backspaceButton.setOnTouchListener { _, event ->
            if (event.action == MotionEvent.ACTION_UP || event.action == MotionEvent.ACTION_CANCEL) {
                stopBackspaceRepeat()
            }
            false
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
                waveformView.visibility = View.VISIBLE
                pulseCircle.visibility = View.GONE
                recordingDot.visibility = View.GONE
                transcribingText.visibility = View.GONE
                stopPulseAnimation()
                stopDotBlinkAnimation()
            }
            KeyboardState.RECORDING -> {
                micButton.setImageResource(R.drawable.ic_mic)
                statusText.text = context.getString(R.string.release_to_cancel)
                statusText.setTextColor(context.getColor(R.color.text_secondary))
                waveformView.visibility = View.VISIBLE
                pulseCircle.visibility = View.VISIBLE
                recordingDot.visibility = View.VISIBLE
                transcribingText.visibility = View.GONE
                startPulseAnimation()
                startDotBlinkAnimation()
            }
            KeyboardState.TRANSCRIBING -> {
                micButton.setImageResource(R.drawable.ic_stop)
                statusText.text = ""
                waveformView.visibility = View.INVISIBLE
                waveformView.clear()
                pulseCircle.visibility = View.GONE
                recordingDot.visibility = View.GONE
                transcribingText.visibility = View.VISIBLE
                stopPulseAnimation()
                stopDotBlinkAnimation()
            }
        }
    }

    fun updatePulseWithAmplitude(amplitude: Float) {
        if (state != KeyboardState.RECORDING) return
        currentAmplitude = amplitude.coerceIn(0f, 1f)
        applyPulseScale()
    }

    private fun applyPulseScale() {
        val ampBoost = currentAmplitude * 0.25f
        val scale = breathingScale + ampBoost
        pulseCircle.scaleX = scale
        pulseCircle.scaleY = scale
    }

    private fun startPulseAnimation() {
        pulseAnimator?.cancel()
        currentAmplitude = 0f
        pulseAnimator = ValueAnimator.ofFloat(0.9f, 1.1f).apply {
            duration = PULSE_DURATION_MS
            repeatMode = ValueAnimator.REVERSE
            repeatCount = ValueAnimator.INFINITE
            interpolator = AccelerateDecelerateInterpolator()
            addUpdateListener { animation ->
                breathingScale = animation.animatedValue as Float
                applyPulseScale()
            }
            start()
        }
    }

    private fun stopPulseAnimation() {
        pulseAnimator?.cancel()
        pulseAnimator = null
        breathingScale = 1f
        currentAmplitude = 0f
        pulseCircle.scaleX = 1f
        pulseCircle.scaleY = 1f
    }

    private fun startDotBlinkAnimation() {
        dotBlinkAnimator?.cancel()
        dotBlinkAnimator = ValueAnimator.ofFloat(1f, 0.15f).apply {
            duration = DOT_BLINK_DURATION_MS
            repeatMode = ValueAnimator.REVERSE
            repeatCount = ValueAnimator.INFINITE
            interpolator = AccelerateDecelerateInterpolator()
            addUpdateListener { animation ->
                recordingDot.alpha = animation.animatedValue as Float
            }
            start()
        }
    }

    private fun stopDotBlinkAnimation() {
        dotBlinkAnimator?.cancel()
        dotBlinkAnimator = null
        recordingDot.alpha = 1f
    }

    private fun startBackspaceRepeat() {
        backspaceHandler = Handler(Looper.getMainLooper())
        onBackspace?.invoke()
        backspaceHandler?.postDelayed(backspaceRepeatRunnable, BACKSPACE_REPEAT_DELAY_MS)
    }

    private fun stopBackspaceRepeat() {
        backspaceHandler?.removeCallbacks(backspaceRepeatRunnable)
        backspaceHandler = null
    }
}
