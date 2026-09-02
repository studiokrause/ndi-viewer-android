package com.lekozaur.ndiviewer

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.util.AttributeSet
import android.view.View

class FalseColorScaleView @JvmOverloads constructor(
    context: Context, attrs: AttributeSet? = null
) : View(context, attrs) {

    private val barPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val textPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE
        textSize = 39f // 1.5x larger (26*1.5 = 39)
        setShadowLayer(3f, 1f, 1f, Color.BLACK)
        isFakeBoldText = true
    }
    private val smallTextPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#E8EAED")
        textSize = 13f
        setShadowLayer(2f, 1f, 1f, Color.BLACK)
    }
    private val borderPaint = Paint().apply { color = Color.parseColor("#2A2A33"); style = Paint.Style.STROKE; strokeWidth = 2f }
    private val tickPaint = Paint().apply { color = Color.WHITE; strokeWidth = 1.5f }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val w = width.toFloat()
        val h = height.toFloat()
        val barLeft = w * 0.52f // half-width bar, left half for numbers (larger font)
        val barRight = w - 2f
        val barTop = 12f
        val barBottom = h - 4f
        val barW = barRight - barLeft

        // draw gradient using table: IRE 0..108 maps to bar height (bottom 0 -> top 108)
        val barH = barBottom - barTop
        for (ire in 0..108) {
            val y = barTop + barH * (1f - ire / 108f)
            val yNext = barTop + barH * (1f - (ire + 1) / 108f)
            barPaint.color = FalseColorTable.colorForIRE(ire)
            canvas.drawRect(barLeft, yNext, barRight, y, barPaint)
        }
        canvas.drawRect(barLeft, barTop, barRight, barBottom, borderPaint)

        // labels: draw each entry's ire with number and color name
        for (entry in FalseColorTable.entries) {
            val y = barTop + barH * (1f - entry.ire / 108f)
            canvas.drawLine(barLeft - 6f, y, barLeft, y, tickPaint)
            // IRE number - larger font
            canvas.drawText(entry.ire.toString(), 2f, y + 5f, textPaint)
        }
    }
}
