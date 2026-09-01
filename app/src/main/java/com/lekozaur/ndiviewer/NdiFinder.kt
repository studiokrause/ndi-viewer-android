package com.lekozaur.ndiviewer

class NdiFinder(private val onUpdate: (List<NdiSource>) -> Unit) {
    private val jni = NdiFinderJni()

    @Volatile
    private var running = false

    private var thread: Thread? = null

    fun start() {
        if (running) return
        if (!jni.create(showLocal = false)) {
            onUpdate(emptyList())
            return
        }
        running = true
        thread = Thread({
            while (running) {
                try {
                    jni.waitForSources(500)
                    onUpdate(jni.currentSources())
                } catch (_: Throwable) {
                    break
                }
            }
        }, "ndi-find").also { it.start() }
    }

    fun stop() {
        running = false
        thread?.let { t ->
            try {
                t.join(1500)
            } catch (_: InterruptedException) {
            }
        }
        thread = null
        jni.destroy()
    }
}
