package com.lekozaur.ndiviewer

import java.io.Closeable
import java.nio.ByteBuffer
import kotlin.concurrent.thread

class NdiStreamStats {
    var fps: Float = 0f
    var total: Long = 0
    var dropped: Long = 0
    var connections: Int = 0
    var width: Int = 0
    var height: Int = 0
    var fpsN: Int = 0
    var fpsD: Int = 0
}

interface NdiStreamListener {
    fun onFrame(buf: ByteBuffer, f: NdiVideoFrame)
    fun onConnection(connected: Boolean)
    fun onStats(s: NdiStreamStats)
    fun onError(message: String)
}

class NdiStream(
    private val bandwidth: Int,
    private val listener: NdiStreamListener,
) : AutoCloseable {

    private val jni = NdiReceiverJni()
    private val frame = NdiVideoFrame()
    private val statsRaw = NdiStats()

    @Volatile
    private var running = false

    private var videoThread: Thread? = null
    private var statsThread: Thread? = null

    @Volatile
    private var lastTotal = 0L

    @Volatile
    private var connected = false

    val isCreated: Boolean get() = jni.valid

    fun connectTo(url: String, name: String?) {
        if (!running) {
            if (!jni.create("NDI Viewer (Android)", bandwidth)) {
                listener.onError("Nie udało się utworzyć odbiornika NDI")
                return
            }
            running = true
            videoThread = thread(name = "ndi-video") { videoLoop() }
            statsThread = thread(name = "ndi-stats") { statsLoop() }
        }
        jni.connect(url, name)
    }

    fun disconnect() {
        connected = false
        if (jni.valid) jni.disconnect()
    }

    fun setMuted(muted: Boolean) {
        if (jni.valid) jni.setMuted(muted)
    }

    override fun close() {
        running = false
        videoThread?.let { t -> try { t.join(1000) } catch (_: InterruptedException) {} }
        statsThread?.let { t -> try { t.join(1000) } catch (_: InterruptedException) {} }
        videoThread = null
        statsThread = null
        jni.destroy()
        connected = false
    }

    private fun videoLoop() {
        while (running) {
            val buf = try {
                jni.capture(500, frame)
            } catch (t: Throwable) {
                listener.onError(t.message ?: "Błąd odbioru")
                break
            }
            if (frame.frameType == FRAME_VIDEO && buf != null) {
                listener.onFrame(buf, frame)
            }
            if (frame.frameType == FRAME_STATUS_CHANGE || frame.frameType == FRAME_SOURCE_CHANGE) {
                checkConnection()
            }
        }
    }

    private fun statsLoop() {
        while (running) {
            try {
                Thread.sleep(1000)
            } catch (_: InterruptedException) {
                break
            }
            val s = pollStats() ?: break
            listener.onStats(s)
        }
    }

    private fun checkConnection() {
        if (!jni.valid) return
        jni.stats(statsRaw)
        val now = statsRaw.connections > 0
        if (now != connected) {
            connected = now
            listener.onConnection(now)
        }
    }

    private fun pollStats(): NdiStreamStats? {
        if (!running || !jni.valid) return null
        jni.stats(statsRaw)
        val s = NdiStreamStats()
        s.total = statsRaw.totalFrames
        s.dropped = statsRaw.droppedFrames
        s.connections = statsRaw.connections
        s.fps = (statsRaw.totalFrames - lastTotal).toFloat().coerceAtLeast(0f)
        lastTotal = statsRaw.totalFrames
        if (frame.frameType == FRAME_VIDEO) {
            s.width = frame.xres
            s.height = frame.yres
            s.fpsN = frame.fpsNum
            s.fpsD = frame.fpsDen
        }
        val now = statsRaw.connections > 0
        if (now != connected) {
            connected = now
            listener.onConnection(now)
        }
        return s
    }

    companion object {
        const val FRAME_VIDEO = 1
        const val FRAME_STATUS_CHANGE = 100
        const val FRAME_SOURCE_CHANGE = 101
        const val BANDWIDTH_LOWEST = 0
        const val BANDWIDTH_HIGHEST = 100
    }
}
