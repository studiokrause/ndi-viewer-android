package com.lekozaur.ndiviewer

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.util.AttributeSet
import android.view.View

class HistogramView @JvmOverloads constructor(
    context: Context, attrs: AttributeSet? = null
) : View(context, attrs) {

    private val bgPaint = Paint().apply { color = Color.parseColor("#1A1A1F") }
    private val gridPaint = Paint().apply { color = Color.parseColor("#2A2A33"); strokeWidth = 1f }
    private val lumaPaint = Paint().apply { color = Color.WHITE; strokeWidth = 1.5f; style = Paint.Style.STROKE }
    private val rPaint = Paint().apply { color = Color.parseColor("#E74C3C"); strokeWidth = 1f; style = Paint.Style.STROKE; alpha = 180 }
    private val gPaint = Paint().apply { color = Color.parseColor("#2ECC71"); strokeWidth = 1f; style = Paint.Style.STROKE; alpha = 180 }
    private val bPaint = Paint().apply { color = Color.parseColor("#3498DB"); strokeWidth = 1f; style = Paint.Style.STROKE; alpha = 180 }

    private var histR = IntArray(256)
    private var histG = IntArray(256)
    private var histB = IntArray(256)
    private var histL = IntArray(256)
    private var maxCount = 1

    fun updateHistogram(r: IntArray, g: IntArray, b: IntArray, l: IntArray) {
        histR = r.copyOf()
        histG = g.copyOf()
        histB = b.copyOf()
        histL = l.copyOf()
        maxCount = (histL.maxOrNull() ?: 1).coerceAtLeast(1)
        invalidate()
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val w = width.toFloat()
        val h = height.toFloat()
        canvas.drawRect(0f, 0f, w, h, bgPaint)
        // grid
        for (i in 1..3) {
            val x = w * i / 4f
            canvas.drawLine(x, 0f, x, h, gridPaint)
        }
        for (i in 1..2) {
            val y = h * i / 3f
            canvas.drawLine(0f, y, w, y, gridPaint)
        }
        fun drawHist(hist: IntArray, paint: Paint) {
            var lastX = 0f
            var lastY = h
            for (i in 0..255) {
                val x = w * i / 255f
                val norm = hist[i].toFloat() / maxCount.toFloat()
                // log scale for visibility
                val logNorm = if (norm > 0) (Math.log10((1 + 9 * norm).toDouble()) / Math.log10(10.0)).toFloat() else 0f
                val y = h * (1f - logNorm)
                if (i > 0) canvas.drawLine(lastX, lastY, x, y, paint)
                lastX = x
                lastY = y
            }
        }
        drawHist(histR, rPaint)
        drawHist(histG, gPaint)
        drawHist(histB, bPaint)
        drawHist(histL, lumaPaint)
        // border
        canvas.drawRect(0f, 0f, w, h, gridPaint)
    }
}
