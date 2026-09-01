package com.lekozaur.ndiviewer

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Shader
import android.util.AttributeSet
import android.view.View

class FalseColorScaleView @JvmOverloads constructor(
    context: Context, attrs: AttributeSet? = null
) : View(context, attrs) {

    private val barPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val textPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE
        textSize = 22f
        setShadowLayer(3f, 1f, 1f, Color.BLACK)
    }
    private val borderPaint = Paint().apply { color = Color.parseColor("#2A2A33"); style = Paint.Style.STROKE; strokeWidth = 2f }
    private val tickPaint = Paint().apply { color = Color.WHITE; strokeWidth = 1.5f }

    private val labels = listOf(255, 192, 128, 64, 0)

    private fun lumaToColor(luma: Int): Int = when {
        luma < 64 -> Color.rgb(0, luma * 4, 255)
        luma < 128 -> Color.rgb(0, 255, 255 - (luma - 64) * 4)
        luma < 192 -> Color.rgb((luma - 128) * 4, 255, 0)
        else -> Color.rgb(255, 255 - (luma - 192) * 2, 0)
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val w = width.toFloat()
        val h = height.toFloat()
        val barLeft = 28f
        val barRight = w - 14f
        val barTop = 8f
        val barBottom = h - 8f
        val barW = barRight - barLeft

        // draw gradient bar vertically: top = 255, bottom = 0
        val barH = barBottom - barTop
        // draw 256 lines
        for (i in 0..255) {
            val y = barTop + barH * (1f - i / 255f)
            barPaint.color = lumaToColor(i)
            val yNext = barTop + barH * (1f - (i + 1) / 255f)
            canvas.drawRect(barLeft, yNext, barRight, y, barPaint)
        }
        canvas.drawRect(barLeft, barTop, barRight, barBottom, borderPaint)

        // ticks and labels on left side of bar
        for (l in labels) {
            val y = barTop + barH * (1f - l / 255f)
            canvas.drawLine(barLeft - 6f, y, barLeft, y, tickPaint)
            canvas.drawText(l.toString(), 2f, y + 5f, textPaint)
        }
        // title
        textPaint.textSize = 16f
        canvas.drawText("IRE", 2f, barTop - 2f, textPaint)
        textPaint.textSize = 22f
    }
}
