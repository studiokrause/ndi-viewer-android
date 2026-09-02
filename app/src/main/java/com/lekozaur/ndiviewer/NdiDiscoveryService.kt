package com.lekozaur.ndiviewer

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Intent
import android.os.IBinder
import androidx.core.app.NotificationCompat

class NdiDiscoveryService : Service() {
    companion object {
        const val ACTION_START = "NDI_DISCOVERY_START"
        const val ACTION_STOP = "NDI_DISCOVERY_STOP"
        private const val CH_ID = "ndi_discovery"
        private const val NOTIF_ID = 0x4E44
    }

    override fun onCreate() {
        super.onCreate()
        val nm = getSystemService(NotificationManager::class.java)
        val ch = NotificationChannel(CH_ID, "NDI Discovery Server", NotificationManager.IMPORTANCE_LOW)
        ch.description = "Keeps NDI mDNS discovery warm and advertises this device"
        nm.createNotificationChannel(ch)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_STOP -> {
                NdiDiscoveryServer.stop(this)
                stopForeground(STOP_FOREGROUND_REMOVE)
                stopSelf()
                return START_NOT_STICKY
            }
            else -> {
                NdiDiscoveryServer.start(this)
                val notif: Notification = NotificationCompat.Builder(this, CH_ID)
                    .setSmallIcon(R.drawable.ic_launcher_foreground)
                    .setContentTitle("NDI Discovery Server — ON")
                    .setContentText("mDNS warm, cache ${NdiDiscoveryServer.cached().size} sources, advertised as _ndi._tcp")
                    .setOngoing(true)
                    .build()
                startForeground(NOTIF_ID, notif)
                return START_STICKY
            }
        }
    }

    override fun onDestroy() {
        NdiDiscoveryServer.stop(this)
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null
}
