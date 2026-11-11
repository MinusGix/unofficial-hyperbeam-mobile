package dev.minusgix.hyperbeam

import android.app.Application
import android.app.NotificationChannel
import android.app.NotificationManager
import dev.minusgix.hyperbeam.room.RoomSession

class HyperbeamApp : Application() {
    override fun onCreate() {
        super.onCreate()
        RoomSession.init(this)
        val nm = getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(
            NotificationChannel(CHANNEL_ROOM, getString(R.string.channel_room), NotificationManager.IMPORTANCE_LOW).apply {
                description = getString(R.string.channel_room_desc)
                setShowBadge(false)
            },
        )
    }

    companion object {
        const val CHANNEL_ROOM = "room"
    }
}
