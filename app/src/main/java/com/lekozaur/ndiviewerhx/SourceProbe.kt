package com.lekozaur.ndiviewerhx

import java.util.concurrent.Executors

object SourceProbe {
    private const val PROBE_TIMEOUT_NS = 8_000_000_000L
    private val exec = Executors.newCachedThreadPool { r -> Thread(r, "ndi-probe").apply { isDaemon = true } }

    fun probe(src: NdiSource, callback: (DecodeStatus) -> Unit) {
        exec.execute {
            var status = DecodeStatus.UNKNOWN
            var jni: NdiReceiverJni? = null
            try {
                jni = NdiReceiverJni()
                if (!jni.create("Probe-${src.name.take(20)}", NdiStream.BANDWIDTH_HIGHEST)) {
                    status = DecodeStatus.YELLOW
                } else {
                    jni.setMuted(true) // a probe must never be audible
                    jni.connect(src.url, src.name)
                    val frame = NdiVideoFrame()
                    var got = false
                    // Wait by time, not by capture count: status-change returns come back
                    // immediately, and an HX source delivers nothing until its first keyframe.
                    val deadline = System.nanoTime() + PROBE_TIMEOUT_NS
                    while (System.nanoTime() < deadline) {
                        val buf = jni.capture(900, frame)
                        if (frame.frameType == 1 && buf != null) { // FRAME_VIDEO
                            status = DecodeClassifier.fromFourCC(frame.fourcc)
                            got = true
                            break
                        } else if (frame.frameType == 4 && frame.fourcc != 0) { // unsupported format
                            status = DecodeClassifier.fromFourCC(frame.fourcc)
                            got = true
                            break
                        }
                    }
                    if (!got) {
                        // No frame in time — treat as maybe (yellow)
                        status = DecodeStatus.YELLOW
                    }
                }
            } catch (_: Throwable) {
                status = DecodeStatus.YELLOW
            } finally {
                try { jni?.destroy() } catch (_: Throwable) {}
            }
            // Ensure we have a final status
            if (status == DecodeStatus.UNKNOWN) status = DecodeStatus.YELLOW
            callback(status)
        }
    }
}
