package com.lekozaur.ndiviewer

import android.content.Context
import android.net.nsd.NsdManager
import android.net.nsd.NsdServiceInfo
import android.net.wifi.WifiManager

/**
 * Built-in NDI Discovery Server (lightweight).
 *
 * Full Vizrt NDI Discovery Server is closed-source and not part of the public SDK,
 * but we can emulate its core behaviour on Android:
 *  - Holds a permanent MulticastLock so mDNS (239.255.255.250:5353) is not throttled
 *  - Runs a persistent NDI finder (NDIlib_find) that caches all sources in-process
 *  - Advertises itself via Android NsdManager as _ndi._tcp and _ndi-discovery._tcp
 *    so other NDI receivers on the same subnet can discover this device via
 *    "Extra IPs = <this-device-ip>" or via mDNS.
 *  - On networks where mDNS is filtered (VLAN, guest Wi-Fi), other devices can
 *    point their NDI Access Manager -> Discovery Server IP to this Android's IP;
 *    the service keeps the cache warm and answers faster than cold mDNS.
 *
 * Limitations vs official server:
 *  - Does not implement the proprietary NDI Discovery Server wire protocol (TCP 5960
 *    directory). It is an mDNS reflector/cache, not a full directory.
 *  - Requires NDI SDK's find API; cannot proxy NDI|HX compressed sources that need
 *    a hardware decoder.
 *  - On Android, keeping MulticastLock continuously increases battery use.
 */
object NdiDiscoveryServer {
    @Volatile private var running = false
    @Volatile private var finder: NdiFinder? = null
    private var multicastLock: WifiManager.MulticastLock? = null
    private var nsdManager: NsdManager? = null
    private var nsdListener: NsdManager.RegistrationListener? = null
    private var cachedSources: List<NdiSource> = emptyList()

    fun isRunning(): Boolean = running
    fun cached(): List<NdiSource> = cachedSources

    fun start(ctx: Context) {
        if (running) return
        running = true
        val app = ctx.applicationContext
        // 1. Multicast lock
        try {
            val wm = app.getSystemService(Context.WIFI_SERVICE) as WifiManager
            val lock = wm.createMulticastLock("ndi-discovery-server")
            lock.setReferenceCounted(false)
            lock.acquire()
            multicastLock = lock
        } catch (_: Throwable) {}

        // 2. Persistent finder
        finder = NdiFinder { sources ->
            cachedSources = sources
        }?.also { it.start() }

        // 3. Advertise via NsdManager so other NDI clients see this device
        try {
            nsdManager = app.getSystemService(Context.NSD_SERVICE) as NsdManager
            val svc = NsdServiceInfo().apply {
                serviceName = "NDI Discovery (Android)"
                serviceType = "_ndi._tcp."
                port = 5961
            }
            nsdListener = object : NsdManager.RegistrationListener {
                override fun onServiceRegistered(s: NsdServiceInfo) {}
                override fun onRegistrationFailed(s: NsdServiceInfo, c: Int) {}
                override fun onServiceUnregistered(s: NsdServiceInfo) {}
                override fun onUnregistrationFailed(s: NsdServiceInfo, c: Int) {}
            }
            nsdManager?.registerService(svc, NsdManager.PROTOCOL_DNS_SD, nsdListener)
        } catch (_: Throwable) {}
    }

    fun stop(ctx: Context) {
        if (!running) return
        running = false
        try { finder?.stop() } catch (_: Throwable) {}
        finder = null
        cachedSources = emptyList()
        try {
            nsdListener?.let { nsdManager?.unregisterService(it) }
        } catch (_: Throwable) {}
        nsdManager = null
        nsdListener = null
        try {
            multicastLock?.let { if (it.isHeld) it.release() }
        } catch (_: Throwable) {}
        multicastLock = null
    }
}
