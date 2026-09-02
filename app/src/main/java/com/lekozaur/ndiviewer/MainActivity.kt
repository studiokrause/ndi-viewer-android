package com.lekozaur.ndiviewer

import android.annotation.SuppressLint
import android.app.Dialog
import android.content.Context
import android.net.wifi.WifiManager
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.content.Intent
import android.net.Uri
import android.text.method.LinkMovementMethod
import android.widget.Button
import android.widget.CheckBox
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.ImageButton
import android.widget.PopupMenu
import android.widget.SeekBar
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.view.ContextThemeWrapper
import androidx.core.view.WindowCompat
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import java.nio.ByteBuffer
import kotlin.concurrent.thread

class MainActivity : AppCompatActivity(), NdiStreamListener {

    private lateinit var renderer: VideoRenderer
    private lateinit var surface: FitSurfaceView
    private lateinit var statusText: TextView
    private lateinit var statusChip: TextView
    private lateinit var statsText: TextView
    private lateinit var btnMute: ImageButton
    private lateinit var btnSources: ImageButton
    private lateinit var btnLang: ImageButton
    private lateinit var btnFalseColor: ImageButton
    private lateinit var btnHistogram: ImageButton
    private lateinit var btnAbout: ImageButton
    private lateinit var leftBar: View
    private lateinit var histogramContainer: FrameLayout
    private lateinit var histogramView: HistogramView
    private lateinit var histogramControls: View
    private lateinit var seekHistSize: SeekBar
    private lateinit var seekHistAlpha: SeekBar
    private lateinit var falseColorScaleContainer: FrameLayout

    private var stream: NdiStream? = null
    private var streamBandwidth = -1
    private var finder: NdiFinder? = null
    private var sheet: Dialog? = null
    private var multicastLock: WifiManager.MulticastLock? = null
    private var sourceAdapter: SourceAdapter? = null
    private var sheetEmptyView: TextView? = null
    private var sheetRefreshBtn: ImageButton? = null
    private val probedUrls = mutableSetOf<String>()

    @Volatile private var bandwidth = NdiStream.BANDWIDTH_HIGHEST
    private var muted = false
    private var nativeReady = false
    @Volatile private var isConnected = false
    private var falseColorOn = false
    private var histogramOn = false

    private val uiHandler = Handler(Looper.getMainLooper())
    private val hideUiRunnable = Runnable { setUiVisibleInternal(false) }
    private val showUiRunnable = Runnable { setUiVisibleInternal(true) }
    private val uiHideDelayMs = 3000L
    private val uiVisible = java.util.concurrent.atomic.AtomicBoolean(true)

    override fun attachBaseContext(newBase: Context) {
        super.attachBaseContext(LocaleHelper.wrapContext(newBase))
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        WindowCompat.setDecorFitsSystemWindows(window, false)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        setContentView(R.layout.activity_main)

        surface = findViewById(R.id.surface)
        statusText = findViewById(R.id.statusText)
        statusChip = findViewById(R.id.statusChip)
        statsText = findViewById(R.id.statsText)
        btnMute = findViewById(R.id.btnMute)
        btnSources = findViewById(R.id.btnSources)
        btnLang = findViewById(R.id.btnLang)
        btnFalseColor = findViewById(R.id.btnFalseColor)
        btnHistogram = findViewById(R.id.btnHistogram)
        btnAbout = findViewById(R.id.btnAbout)
        leftBar = findViewById(R.id.leftBar)
        histogramContainer = findViewById(R.id.histogramContainer)
        histogramView = findViewById(R.id.histogramView)
        histogramControls = findViewById(R.id.histogramControls)
        seekHistSize = findViewById(R.id.seekHistSize)
        seekHistAlpha = findViewById(R.id.seekHistAlpha)
        falseColorScaleContainer = findViewById(R.id.falseColorScaleContainer)
        renderer = VideoRenderer(surface)

        try {
            val controller = window.insetsController
            if (controller != null) {
                controller.hide(androidx.core.view.WindowInsetsCompat.Type.systemBars())
                controller.systemBarsBehavior = androidx.core.view.WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
            }
        } catch (_: Throwable) {}

        statusText.text = getString(R.string.status_init)
        statusChip.text = getString(R.string.status_init)
        updateLangButton()

        thread(name = "ndi-init") {
            val ok = try { NdiNative.init() } catch (t: Throwable) {
                runOnUiThread { Toast.makeText(this, "NDI init failed: ${t.message}", Toast.LENGTH_LONG).show() }
                false
            }
            runOnUiThread {
                nativeReady = ok
                if (ok) {
                    statusText.text = getString(R.string.status_no_source)
                    statusChip.text = getString(R.string.status_idle)
                    updateStatusChip(false)
                } else {
                    statusText.text = getString(R.string.status_init_failed)
                    statusChip.text = getString(R.string.status_init_failed)
                    updateStatusChip(false)
                }
            }
        }

        btnSources.setOnClickListener { if (nativeReady) openSources() }
        btnMute.setOnClickListener { toggleMute() }
        btnLang.setOnClickListener { showLanguageMenu(it) }
        btnFalseColor.setOnClickListener { toggleFalseColor() }
        btnHistogram.setOnClickListener { toggleHistogram() }
        btnAbout.setOnClickListener { showAboutDialog() }

        setupHistogramDrag()
        setupFalseColorScaleDrag()
        setupHistogramControls()
        renderer.histogramCallback = { r, g, b, l ->
            if (histogramOn) runOnUiThread { histogramView.updateHistogram(r, g, b, l) }
        }

        surface.setOnTouchListener { _, event ->
            if (event.action == MotionEvent.ACTION_DOWN) {
                if (uiVisible.get()) hideUi() else showUi()
                true
            } else false
        }
    }

    private fun updateLangButton() {
        val lang = LocaleHelper.getLang(this)
        btnLang.contentDescription = "${getString(R.string.language)}: ${LocaleHelper.NAMES[lang]}"
    }

    private fun showLanguageMenu(anchor: View) {
        val wrapper = ContextThemeWrapper(this, R.style.Theme_NDIViewer)
        val popup = PopupMenu(wrapper, anchor)
        LocaleHelper.SUPPORTED.forEachIndexed { idx, code ->
            val name = when (code) {
                "pl" -> "Polski"; "en" -> "English"; "de" -> "Deutsch"
                "es" -> "Español"; "it" -> "Italiano"; "fr" -> "Français"; else -> code.uppercase()
            }
            popup.menu.add(0, idx, idx, "${LocaleHelper.NAMES[code]} — $name")
        }
        popup.setOnMenuItemClickListener { item ->
            val code = LocaleHelper.SUPPORTED[item.itemId]
            if (code != LocaleHelper.getLang(this)) { LocaleHelper.setLang(this, code); recreate() }
            true
        }
        popup.show()
    }

    private fun showUi() {
        uiHandler.removeCallbacks(hideUiRunnable)
        uiHandler.post(showUiRunnable)
    }
    private fun hideUi() {
        uiHandler.removeCallbacks(showUiRunnable)
        uiHandler.postDelayed(hideUiRunnable, uiHideDelayMs)
    }
    private fun setUiVisibleInternal(visible: Boolean) {
        uiVisible.set(visible)
        val vis = if (visible) View.VISIBLE else View.GONE
        statusChip.visibility = vis
        if (visible && !isConnected) statusText.visibility = View.VISIBLE else statusText.visibility = View.GONE
        statsText.visibility = vis
        leftBar.visibility = vis
        // Helpers stay visible even when UI hides (unless toggled off)
        // histogramControls is part of UI chrome, so it hides; histogram/scale stay
        histogramControls.visibility = if (visible && histogramOn) View.VISIBLE else View.GONE
        histogramContainer.visibility = if (histogramOn) View.VISIBLE else View.GONE
        falseColorScaleContainer.visibility = if (falseColorOn) View.VISIBLE else View.GONE
        if (visible) {
            uiHandler.removeCallbacks(hideUiRunnable)
            uiHandler.postDelayed(hideUiRunnable, uiHideDelayMs)
        } else uiHandler.removeCallbacks(hideUiRunnable)
    }

    private fun updateStatusChip(isLive: Boolean) {
        if (isLive) {
            statusChip.setBackgroundResource(R.drawable.chip_live_bg)
            statusChip.setTextColor(0xFFFFFFFF.toInt())
        } else {
            statusChip.setBackgroundResource(R.drawable.chip_bg)
            statusChip.setTextColor(0xFFE8EAED.toInt())
        }
    }

    private fun toggleFalseColor() {
        falseColorOn = !falseColorOn
        renderer.falseColorEnabled = falseColorOn
        btnFalseColor.alpha = if (falseColorOn) 1f else 0.5f
        falseColorScaleContainer.visibility = if (falseColorOn) View.VISIBLE else View.GONE
        if (falseColorOn) showUi()
        Toast.makeText(this, if (falseColorOn) "False color ON" else "False color OFF", Toast.LENGTH_SHORT).show()
    }

    private fun toggleHistogram() {
        histogramOn = !histogramOn
        histogramContainer.visibility = if (histogramOn) View.VISIBLE else View.GONE
        histogramControls.visibility = if (histogramOn) View.VISIBLE else View.GONE
        btnHistogram.alpha = if (histogramOn) 1f else 0.5f
        if (histogramOn) {
            showUi()
            // If no video, show synthetic histogram so user sees it's working
            if (!isConnected) {
                val dummyR = IntArray(256) { i -> (80 + 40 * kotlin.math.sin(i * Math.PI / 64)).toInt().coerceIn(0, 255) }
                val dummyG = IntArray(256) { i -> (100 + 30 * kotlin.math.cos(i * Math.PI / 64)).toInt().coerceIn(0, 255) }
                val dummyB = IntArray(256) { i -> (90 + 50 * kotlin.math.sin(i * Math.PI / 32)).toInt().coerceIn(0, 255) }
                val dummyL = IntArray(256) { i -> (120 - kotlin.math.abs(i - 128) ).coerceIn(0, 255) }
                histogramView.updateHistogram(dummyR, dummyG, dummyB, dummyL)
            }
        }
    }

    private fun setupHistogramDrag() {
        var dX = 0f; var dY = 0f
        histogramContainer.setOnTouchListener { v, event ->
            when (event.action) {
                MotionEvent.ACTION_DOWN -> { dX = v.x - event.rawX; dY = v.y - event.rawY; true }
                MotionEvent.ACTION_MOVE -> { v.animate().x(event.rawX + dX).y(event.rawY + dY).setDuration(0).start(); true }
                else -> false
            }
        }
    }
    private fun setupFalseColorScaleDrag() {
        var dX = 0f; var dY = 0f
        falseColorScaleContainer.setOnTouchListener { v, event ->
            when (event.action) {
                MotionEvent.ACTION_DOWN -> { dX = v.x - event.rawX; dY = v.y - event.rawY; true }
                MotionEvent.ACTION_MOVE -> { v.animate().x(event.rawX + dX).y(event.rawY + dY).setDuration(0).start(); true }
                else -> false
            }
        }
    }
    private fun setupHistogramControls() {
        seekHistSize.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(s: SeekBar?, p: Int, u: Boolean) {
                val scale = 0.6f + p / 100f * 1.4f
                val baseW = (160 * resources.displayMetrics.density).toInt()
                val baseH = (100 * resources.displayMetrics.density).toInt()
                val lp = histogramContainer.layoutParams
                lp.width = (baseW * scale).toInt()
                lp.height = (baseH * scale).toInt()
                histogramContainer.layoutParams = lp
            }
            override fun onStartTrackingTouch(s: SeekBar?) {}
            override fun onStopTrackingTouch(s: SeekBar?) {}
        })
        seekHistAlpha.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(s: SeekBar?, p: Int, u: Boolean) {
                val a = 0.2f + p / 100f * 0.8f
                histogramContainer.alpha = a
                falseColorScaleContainer.alpha = a
                // histogramControls stays opaque - do not change its alpha
            }
            override fun onStartTrackingTouch(s: SeekBar?) {}
            override fun onStopTrackingTouch(s: SeekBar?) {}
        })
        histogramContainer.alpha = 0.84f
        falseColorScaleContainer.alpha = 0.92f
        btnFalseColor.alpha = 0.5f
        btnHistogram.alpha = 0.5f
    }

    private fun showAboutDialog() {
        val msg = """
            NDI Viewer for Android
            Branch: visual-helpers (BETA)

            Author: studio.krause
            Generator: OpenCode / Muse Spark 1.2 Contributor

            NDI® is a registered trademark of Vizrt NDI AB.
            This app uses the NDI SDK (libndi.so + headers) provided by Vizrt (formerly NewTek) under the NDI SDK License Agreement.
            This project is not affiliated with, endorsed by, or sponsored by Vizrt/NewTek.

            Learn more: https://ndi.video
        """.trimIndent()
        val dlg = AlertDialog.Builder(this)
            .setTitle("About NDI Viewer")
            .setMessage(msg)
            .setPositiveButton("OK", null)
            .setNeutralButton("Open ndi.video") { _, _ ->
                try { startActivity(Intent(Intent.ACTION_VIEW, Uri.parse("https://ndi.video"))) } catch (_: Throwable) {}
            }
            .create()
        dlg.show()
        // make link clickable if needed
        try { dlg.findViewById<TextView>(android.R.id.message)?.movementMethod = LinkMovementMethod.getInstance() } catch (_: Throwable) {}
    }

    // ---------------------------------------------------------- sources sheet
    @SuppressLint("InflateParams")
    private fun openSources() {
        if (sheet?.isShowing == true) return
        acquireMulticastLock()
        val v = layoutInflater.inflate(R.layout.sheet_sources, null)
        val list = v.findViewById<RecyclerView>(R.id.sourceList)
        val empty = v.findViewById<TextView>(R.id.emptyText)
        val edit = v.findViewById<EditText>(R.id.editUrl)
        val lowBw = v.findViewById<CheckBox>(R.id.chkLowBandwidth)
        val btnRefresh = v.findViewById<ImageButton>(R.id.btnRefresh)
        sheetEmptyView = empty
        sheetRefreshBtn = btnRefresh
        sourceAdapter = SourceAdapter { src ->
            bandwidth = if (lowBw.isChecked) NdiStream.BANDWIDTH_LOWEST else NdiStream.BANDWIDTH_HIGHEST
            connectTo(src); sheet?.dismiss()
        }
        list.layoutManager = LinearLayoutManager(this)
        list.adapter = sourceAdapter
        list.layoutManager?.scrollToPosition(0)
        btnRefresh.setOnClickListener { refreshSources() }
        v.findViewById<Button>(R.id.btnManual).setOnClickListener {
            val raw = edit.text.toString().trim()
            if (raw.isEmpty()) return@setOnClickListener
            bandwidth = if (lowBw.isChecked) NdiStream.BANDWIDTH_LOWEST else NdiStream.BANDWIDTH_HIGHEST
            connectTo(NdiSource(raw, normalizeUrl(raw))); sheet?.dismiss()
        }
        val d = Dialog(this)
        d.setContentView(v)
        d.window?.let { w ->
            w.setLayout(WindowManager.LayoutParams.MATCH_PARENT, WindowManager.LayoutParams.WRAP_CONTENT)
            w.setGravity(Gravity.TOP)
            w.setBackgroundDrawableResource(android.R.color.transparent)
        }
        d.setOnDismissListener {
            sheet = null; finder?.stop(); finder = null
            sheetEmptyView = null; sheetRefreshBtn = null; sourceAdapter = null
        }
        sheet = d; d.show()
        probedUrls.clear(); startFinder()
        sheetEmptyView?.text = getString(R.string.searching)
    }

    private fun startFinder() {
        finder?.stop()
        finder = NdiFinder { sources ->
            runOnUiThread {
                if (sheet == null) return@runOnUiThread
                sourceAdapter?.submit(sources)
                if (sources.isNotEmpty()) {
                    sheetEmptyView?.text = ""
                    for (src in sources) {
                        if (probedUrls.contains(src.url)) continue
                        probedUrls.add(src.url)
                        SourceProbe.probe(src) { status -> runOnUiThread { sourceAdapter?.updateStatus(src.url, status) } }
                    }
                } else sheetEmptyView?.text = getString(R.string.searching)
            }
        }?.also { it.start() }
    }

    private fun refreshSources() {
        sheetEmptyView?.text = getString(R.string.searching)
        sourceAdapter?.submit(emptyList())
        probedUrls.clear()
        startFinder()
        Toast.makeText(this, getString(R.string.refresh), Toast.LENGTH_SHORT).show()
    }

    private fun normalizeUrl(raw: String): String {
        var s = raw
        if (!s.contains("://") && !s.contains(":")) s = "$s:5961"
        if (!s.contains("://")) s = "ndi://$s"
        return s
    }

    // ---------------------------------------------------------- connection
    private fun connectTo(src: NdiSource) {
        if (!nativeReady) return
        if (stream != null && streamBandwidth != bandwidth) { stream?.close(); stream = null }
        val s = stream ?: NdiStream(bandwidth, this).also { stream = it; streamBandwidth = bandwidth }
        renderer.clear()
        muted = false; updateMuteIcon()
        isConnected = false; updateStatusChip(false)
        statusText.visibility = View.VISIBLE
        statusText.text = getString(R.string.status_connecting, src.name)
        statusChip.text = getString(R.string.status_connecting, src.name)
        showUi(); s.connectTo(src.url, src.name)
    }

    private fun toggleMute() {
        muted = !muted; stream?.setMuted(muted); updateMuteIcon()
    }
    private fun updateMuteIcon() {
        btnMute.setImageResource(if (muted) R.drawable.ic_volume_off else R.drawable.ic_volume_up)
    }

    // ---------------------------------------------------------- callbacks
    override fun onFrame(buf: ByteBuffer, f: NdiVideoFrame) {
        renderer.onFrame(buf, f)
        if (!isConnected) runOnUiThread {
            if (!isConnected) {
                isConnected = true
                statusText.visibility = View.GONE
                statusChip.text = getString(R.string.status_live)
                updateStatusChip(true)
            }
        }
    }
    override fun onConnection(connected: Boolean) {
        runOnUiThread {
            isConnected = connected
            if (connected) {
                statusText.visibility = View.GONE
                statusChip.text = getString(R.string.status_live); updateStatusChip(true)
            } else {
                if (uiVisible.get()) statusText.visibility = View.VISIBLE else statusText.visibility = View.GONE
                statusText.text = getString(R.string.status_no_signal)
                statusChip.text = getString(R.string.status_no_signal); updateStatusChip(false)
            }
        }
    }
    override fun onStats(s: NdiStreamStats) {
        runOnUiThread {
            if (!isConnected && s.connections > 0 && s.width > 0) {
                isConnected = true
                statusText.visibility = View.GONE
                statusChip.text = getString(R.string.status_live); updateStatusChip(true)
            }
            if (s.connections > 0 && s.width > 0) {
                statsText.text = getString(R.string.stats_fmt, s.width, s.height, if (s.fpsN > 0) "${s.fpsN}/${s.fpsD}" else "-", s.fps, s.dropped, s.connections)
            } else statsText.text = ""
        }
    }
    override fun onError(message: String) {
        runOnUiThread { Toast.makeText(this, message, Toast.LENGTH_SHORT).show() }
    }

    // ---------------------------------------------------------- multicast lock
    private fun acquireMulticastLock() {
        if (multicastLock?.isHeld == true) return
        val wifi = applicationContext.getSystemService(Context.WIFI_SERVICE) as WifiManager
        val lock = multicastLock ?: wifi.createMulticastLock("ndi-viewer-mdns").apply { setReferenceCounted(false) }
        multicastLock = lock
        try { lock.acquire() } catch (_: Throwable) {}
    }

    // ---------------------------------------------------------- lifecycle
    override fun onDestroy() {
        super.onDestroy()
        uiHandler.removeCallbacks(hideUiRunnable); uiHandler.removeCallbacks(showUiRunnable)
        sheet?.dismiss(); finder?.stop(); finder = null
        stream?.close(); stream = null; renderer.release()
        multicastLock?.let { try { if (it.isHeld) it.release() } catch (_: Throwable) {} }
        multicastLock = null
    }
}
