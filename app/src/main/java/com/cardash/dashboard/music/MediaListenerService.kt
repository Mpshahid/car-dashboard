package com.cardash.dashboard.music

import android.service.notification.NotificationListenerService

/**
 * Exists only so Android lets the app see and control other apps' media sessions
 * (YouTube Music, Spotify…). The user enables it once under Notification access.
 * We don't read or store notifications.
 */
class MediaListenerService : NotificationListenerService() {
    override fun onListenerConnected() {
        super.onListenerConnected()
        MusicController.refresh()
    }
}
