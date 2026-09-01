package com.lekozaur.ndiviewer

import java.nio.ByteBuffer
import java.util.concurrent.atomic.AtomicBoolean

object NdiNative {
    @Volatile
    var version: String = ""
        private set

    @Volatile
    var cpuSupported: Boolean = true
        private set

    private val loaded = AtomicBoolean(false)

    fun init(): Boolean {
        if (!loaded.compareAndSet(false, true)) return true
        System.loadLibrary("ndi")
        System.loadLibrary("ndiviewer")
        cpuSupported = initialize()
        version = version() ?: ""
        return cpuSupported
    }

    private external fun initialize(): Boolean
    private external fun version(): String?
}

class NdiVideoFrame {
    @JvmField var frameType: Int = 0
    @JvmField var xres: Int = 0
    @JvmField var yres: Int = 0
    @JvmField var fourcc: Int = 0
    @JvmField var fpsNum: Int = 0
    @JvmField var fpsDen: Int = 0
    @JvmField var aspect: Float = 0f
    @JvmField var timestamp: Long = 0
}

class NdiStats {
    @JvmField var totalFrames: Long = 0
    @JvmField var droppedFrames: Long = 0
    @JvmField var connections: Int = 0
}

data class NdiSource(val name: String, val url: String)

class NdiFinderJni {
    private var handle: Long = 0

    val valid: Boolean get() = handle != 0L

    fun create(showLocal: Boolean = true, extraIps: String? = null): Boolean {
        handle = nativeCreate(showLocal, extraIps)
        return handle != 0L
    }

    fun waitForSources(timeoutMs: Int): Boolean = nativeWaitForSources(handle, timeoutMs)

    fun currentSources(): List<NdiSource> {
        val a = nativeGetSources(handle) ?: return emptyList()
        val out = ArrayList<NdiSource>(a.size / 2)
        var i = 0
        while (i + 1 < a.size) {
            val n = a[i]
            val u = a[i + 1]
            if (n != null && u != null && n.isNotEmpty()) out.add(NdiSource(n, u))
            i += 2
        }
        return out
    }

    fun destroy() {
        if (handle != 0L) {
            nativeDestroy(handle)
            handle = 0
        }
    }

    private external fun nativeCreate(showLocal: Boolean, extraIps: String?): Long
    private external fun nativeDestroy(handle: Long)
    private external fun nativeWaitForSources(handle: Long, timeoutMs: Int): Boolean
    private external fun nativeGetSources(handle: Long): Array<String?>?
}

class NdiReceiverJni {
    private var handle: Long = 0

    val valid: Boolean get() = handle != 0L

    fun create(recvName: String, bandwidth: Int): Boolean {
        handle = nativeCreate(recvName, bandwidth)
        return handle != 0L
    }

    fun connect(url: String?, name: String?) = nativeConnect(handle, url, name)
    fun disconnect() = nativeDisconnect(handle)
    fun capture(timeoutMs: Int, frame: NdiVideoFrame): ByteBuffer? =
        nativeCapture(handle, timeoutMs, frame)

    fun stats(s: NdiStats) = nativeGetStats(handle, s)
    fun setMuted(muted: Boolean) = nativeSetMuted(handle, muted)

    fun destroy() {
        if (handle != 0L) {
            nativeDestroy(handle)
            handle = 0
        }
    }

    private external fun nativeCreate(recvName: String, bandwidth: Int): Long
    private external fun nativeConnect(handle: Long, url: String?, name: String?)
    private external fun nativeDisconnect(handle: Long)
    private external fun nativeCapture(handle: Long, timeoutMs: Int, frame: NdiVideoFrame): ByteBuffer?
    private external fun nativeGetStats(handle: Long, stats: NdiStats)
    private external fun nativeSetMuted(handle: Long, muted: Boolean)
    private external fun nativeDestroy(handle: Long)
}
