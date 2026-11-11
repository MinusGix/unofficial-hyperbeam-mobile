package dev.minusgix.hyperbeam.room

import android.app.Notification
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.net.wifi.WifiManager
import android.os.Build
import android.os.IBinder
import android.os.PowerManager
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import androidx.lifecycle.LifecycleService
import androidx.lifecycle.lifecycleScope
import dev.minusgix.hyperbeam.HyperbeamApp
import dev.minusgix.hyperbeam.R
import dev.minusgix.hyperbeam.ui.MainActivity
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.launch

/**
 * Keeps the app in the foreground while a page is open, so Android neither
 * freezes the WebView nor kills the process when you switch apps. Holds a
 * partial wake lock and a Wi-Fi lock only while you're actually in a room.
 */
class RoomService : LifecycleService() {

    private var wakeLock: PowerManager.WakeLock? = null
    private var wifiLock: WifiManager.WifiLock? = null

    override fun onCreate() {
        super.onCreate()
        startInForeground(RoomSession.state.value)
        lifecycleScope.launch {
            RoomSession.state.distinctUntilChanged().collect { st ->
                updateNotification(st)
                holdLocks(st.inRoom)
            }
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        super.onStartCommand(intent, flags, startId)
        when (intent?.action) {
            ACTION_EXIT -> {
                exitEverything()
                return START_NOT_STICKY
            }
            ACTION_TOGGLE_MUTE -> RoomSession.setMuted(!RoomSession.state.value.muted)
        }
        if (!RoomSession.isOpen) {
            // Process came back without a page (e.g. restarted); nothing to keep alive.
            stopSelf()
        }
        return START_NOT_STICKY
    }

    override fun onBind(intent: Intent): IBinder? {
        super.onBind(intent)
        return null
    }

    /** Swiping the app away from recents means "I'm done". */
    override fun onTaskRemoved(rootIntent: Intent?) {
        exitEverything()
        super.onTaskRemoved(rootIntent)
    }

    override fun onDestroy() {
        holdLocks(false)
        super.onDestroy()
    }

    private fun exitEverything() {
        RoomSession.close()
        holdLocks(false)
        ServiceCompat.stopForeground(this, ServiceCompat.STOP_FOREGROUND_REMOVE)
        stopSelf()
        sendBroadcast(Intent(ACTION_FINISH_UI).setPackage(packageName))
    }

    private fun startInForeground(st: RoomState) {
        val type = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PLAYBACK
        } else {
            0
        }
        ServiceCompat.startForeground(this, NOTIFICATION_ID, buildNotification(st), type)
    }

    private fun updateNotification(st: RoomState) {
        val nm = getSystemService(NOTIFICATION_SERVICE) as android.app.NotificationManager
        nm.notify(NOTIFICATION_ID, buildNotification(st))
    }

    private fun buildNotification(st: RoomState): Notification {
        val open = PendingIntent.getActivity(
            this, 0,
            Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        val exit = PendingIntent.getService(
            this, 1, Intent(this, RoomService::class.java).setAction(ACTION_EXIT),
            PendingIntent.FLAG_IMMUTABLE,
        )
        val mute = PendingIntent.getService(
            this, 2, Intent(this, RoomService::class.java).setAction(ACTION_TOGGLE_MUTE),
            PendingIntent.FLAG_IMMUTABLE,
        )
        val title = st.title.takeIf { it.isNotBlank() && !it.startsWith("http") } ?: getString(R.string.app_name)
        val b = NotificationCompat.Builder(this, HyperbeamApp.CHANNEL_ROOM)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle(title)
            .setContentText(st.statusText)
            .setContentIntent(open)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setSilent(true)
            .setShowWhen(false)
            .setCategory(NotificationCompat.CATEGORY_SERVICE)
            .setForegroundServiceBehavior(NotificationCompat.FOREGROUND_SERVICE_IMMEDIATE)
        if (st.inRoom) {
            b.addAction(0, getString(if (st.muted) R.string.action_unmute else R.string.action_mute), mute)
        }
        b.addAction(0, getString(R.string.action_exit), exit)
        return b.build()
    }

    private fun holdLocks(on: Boolean) {
        if (on) {
            if (wakeLock?.isHeld != true) {
                val pm = getSystemService(POWER_SERVICE) as PowerManager
                wakeLock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "Hyperbeam:room").apply {
                    setReferenceCounted(false)
                    acquire()
                }
            }
            if (wifiLock?.isHeld != true) {
                val wm = applicationContext.getSystemService(WIFI_SERVICE) as WifiManager
                val mode = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                    WifiManager.WIFI_MODE_FULL_LOW_LATENCY
                } else {
                    @Suppress("DEPRECATION")
                    WifiManager.WIFI_MODE_FULL_HIGH_PERF
                }
                wifiLock = wm.createWifiLock(mode, "Hyperbeam:room").apply {
                    setReferenceCounted(false)
                    acquire()
                }
            }
        } else {
            wakeLock?.takeIf { it.isHeld }?.release()
            wifiLock?.takeIf { it.isHeld }?.release()
            wakeLock = null
            wifiLock = null
        }
    }

    companion object {
        const val NOTIFICATION_ID = 1
        const val ACTION_EXIT = "dev.minusgix.hyperbeam.EXIT"
        const val ACTION_TOGGLE_MUTE = "dev.minusgix.hyperbeam.TOGGLE_MUTE"
        const val ACTION_FINISH_UI = "dev.minusgix.hyperbeam.FINISH_UI"

        /** Must be called while the app is in the foreground (Android 12+ rule). */
        fun start(context: Context) {
            ContextCompat.startForegroundService(context, Intent(context, RoomService::class.java))
        }
    }
}
