package com.lekozaur.ndiviewer

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.os.Handler
import android.os.Looper
import android.util.AttributeSet
import android.view.MotionEvent
import android.view.SurfaceHolder
import android.view.SurfaceView
import java.nio.ByteBuffer
import kotlin.math.roundToInt

class FitSurfaceView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
) : SurfaceView(context, attrs) {

    @Volatile
    var videoAspect: Float = 0f
        set(value) {
            field = value
            postInvalidate()
            requestLayout()
        }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val pw = MeasureSpec.getSize(widthMeasureSpec)
        val ph = MeasureSpec.getSize(heightMeasureSpec)
        val a = videoAspect
        if (a <= 0f || pw <= 0 || ph <= 0) {
            super.onMeasure(widthMeasureSpec, heightMeasureSpec)
            return
        }
        var vw = pw
        var vh = (pw / a).roundToInt()
        if (vh > ph) {
            vh = ph
            vw = (ph * a).roundToInt()
        }
        setMeasuredDimension(vw, vh)
    }

}

class VideoRenderer(private val view: FitSurfaceView) {

    private val main = Handler(Looper.getMainLooper())
    private val paint = Paint(Paint.FILTER_BITMAP_FLAG)

    @Volatile var falseColorEnabled = false

    private var bitmaps = arrayOfNulls<Bitmap>(2)
    private var writeIdx = 0

    @Volatile
    private var latestIdx = -1

    @Volatile
    private var drawing = false

    private var surfaceReady = false

    private val frameRunnable = object : Runnable {
        override fun run() {
            drawLatest()
            main.postDelayed(this, 8)
        }
    }

    @Volatile
    private var lastDrawn = -2

    init {
        view.holder.addCallback(object : SurfaceHolder.Callback {
            override fun surfaceCreated(holder: SurfaceHolder) {
                surfaceReady = true
            }

            override fun surfaceChanged(holder: SurfaceHolder, format: Int, width: Int, height: Int) {}

            override fun surfaceDestroyed(holder: SurfaceHolder) {
                surfaceReady = false
            }
        })
        main.post(frameRunnable)
    }

    fun onFrame(buf: ByteBuffer, f: NdiVideoFrame) {
        val w = f.xres
        val h = f.yres
        if (w <= 0 || h <= 0) return
        val i = writeIdx
        var bmp = bitmaps[i]
        if (bmp == null || bmp.width != w || bmp.height != h) {
            bmp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
            bitmaps[i] = bmp
        }
        if (drawing) return
        buf.rewind()
        try {
            bmp.copyPixelsFromBuffer(buf)
            if (falseColorEnabled) applyFalseColor(bmp)
        } catch (_: Throwable) {
            return
        }
        latestIdx = i
        writeIdx = i xor 1

        val a = if (f.aspect > 0.01f) f.aspect else w.toFloat() / h.toFloat()
        if (view.videoAspect != a) {
            view.post { view.videoAspect = a }
        }
    }

    private fun drawLatest() {
        val idx = latestIdx
        if (idx == lastDrawn) return
        if (!surfaceReady || drawing) return
        val bmp = if (idx >= 0) bitmaps[idx] else null
        val surface = view.holder.surface ?: return
        val canvas = try {
            surface.lockHardwareCanvas()
        } catch (_: Throwable) {
            try {
                surface.lockCanvas(null)
            } catch (_: Throwable) {
                return
            }
        } ?: return
        drawing = true
        try {
            canvas.drawColor(Color.BLACK)
            if (bmp != null) {
                val cw = canvas.width.toFloat()
                val ch = canvas.height.toFloat()
                val ba = bmp.width.toFloat() / bmp.height.toFloat()
                var dw = cw
                var dh = cw / ba
                if (dh > ch) {
                    dh = ch
                    dw = ch * ba
                }
                val dst =
                    RectF((cw - dw) / 2f, (ch - dh) / 2f, (cw + dw) / 2f, (ch + dh) / 2f)
                canvas.drawBitmap(bmp, null, dst, paint)
            }
            lastDrawn = idx
        } catch (_: Throwable) {
        } finally {
            drawing = false
            try {
                surface.unlockCanvasAndPost(canvas)
            } catch (_: Throwable) {
            }
        }
    }

    private fun applyFalseColor(bmp: Bitmap) {
        // Use table from falsecolor.json via FalseColorTable (IRE 0..108)
        val w = bmp.width; val h = bmp.height
        val pixels = IntArray(w * h)
        bmp.getPixels(pixels, 0, w, 0, 0, w, h)
        for (i in pixels.indices) {
            val c = pixels[i]
            val r = (c shr 16) and 0xFF; val g = (c shr 8) and 0xFF; val b = c and 0xFF
            val luma = (0.299 * r + 0.587 * g + 0.114 * b).toInt().coerceIn(0, 255)
            val fc = FalseColorTable.lumaToColor(luma)
            pixels[i] = 0xFF000000.toInt() or (fc and 0x00FFFFFF)
        }
        bmp.setPixels(pixels, 0, w, 0, 0, w, h)
    }

    fun clear() {
        latestIdx = -1
        lastDrawn = -2
        view.videoAspect = 0f
        view.requestLayout()
    }

    fun release() {
        main.removeCallbacks(frameRunnable)
        bitmaps.forEach { it?.recycle() }
        bitmaps = arrayOfNulls(2)
    }
}