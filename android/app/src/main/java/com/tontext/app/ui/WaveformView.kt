package com.tontext.app.ui

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.util.AttributeSet
import android.view.View
import com.tontext.app.R

class WaveformView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : View(context, attrs, defStyleAttr) {

    private val amplitudes = FloatArray(MAX_BARS)
    private var writeIndex = 0
    private var barCount = 0

    private val barPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = context.getColor(R.color.waveform_bar)
        style = Paint.Style.FILL
    }

    fun addAmplitude(amplitude: Float) {
        amplitudes[writeIndex] = amplitude
        writeIndex = (writeIndex + 1) % MAX_BARS
        if (barCount < MAX_BARS) barCount++
        postInvalidate()
    }

    fun clear() {
        amplitudes.fill(0f)
        writeIndex = 0
        barCount = 0
        postInvalidate()
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)

        if (barCount == 0) return

        val w = width.toFloat()
        val h = height.toFloat()
        val totalBars = MAX_BARS
        val barWidth = w / totalBars
        val gap = barWidth * 0.2f
        val drawBarWidth = barWidth - gap

        for (i in 0 until barCount) {
            // Read oldest to newest
            val idx = if (barCount < MAX_BARS) i else (writeIndex + i) % MAX_BARS
            val amp = amplitudes[idx]
            val barHeight = (amp * h * 3f).coerceAtMost(h) // Amplify for visibility
            // Right-align: newest bar at right edge, older bars pushed left
            val left = w - (barCount - i) * barWidth
            val top = h - barHeight
            canvas.drawRoundRect(left, top, left + drawBarWidth, h, 2f, 2f, barPaint)
        }
    }

    companion object {
        private const val MAX_BARS = 100
    }
}
