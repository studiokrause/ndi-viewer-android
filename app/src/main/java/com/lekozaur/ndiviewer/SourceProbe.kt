package com.lekozaur.ndiviewer

import java.util.concurrent.Executors

object SourceProbe {
    private val exec = Executors.newCachedThreadPool { r -> Thread(r, "ndi-probe").apply { isDaemon = true } }

    fun probe(src: NdiSource, callback: (DecodeStatus) -> Unit) {
        // Quick heuristic before native probe
        val heuristic = DecodeClassifier.fromNameHeuristic(src.name)
        if (heuristic == DecodeStatus.RED) {
            callback(DecodeStatus.RED)
            return
        }
        exec.execute {
            var status: DecodeStatus = heuristic
            var jni: NdiReceiverJni? = null
            try {
                jni = NdiReceiverJni()
                if (!jni.create("Probe-${src.name.take(20)}", NdiStream.BANDWIDTH_HIGHEST)) {
                    status = DecodeStatus.YELLOW
                } else {
                    jni.connect(src.url, src.name)
                    val frame = NdiVideoFrame()
                    var tries = 0
                    var got = false
                    while (tries < 3) {
                        val buf = jni.capture(900, frame)
                        if (frame.frameType == 1 && buf != null) { // FRAME_VIDEO
                            status = DecodeClassifier.fromFourCC(frame.fourcc)
                            got = true
                            break
                        } else if (frame.frameType == 4) { // FRAME_ERROR -> compressed
                            status = DecodeClassifier.fromFourCC(frame.fourcc)
                            if (status == DecodeStatus.UNKNOWN) status = DecodeStatus.RED
                            got = true
                            break
                        }
                        tries++
                    }
                    if (!got) {
                        // No frame in time — treat as maybe (yellow) unless heuristic already red
                        status = if (heuristic != DecodeStatus.UNKNOWN) heuristic else DecodeStatus.YELLOW
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
