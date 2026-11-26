package com.cardash.dashboard.music

import android.app.SearchManager
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.media.MediaMetadata
import android.media.session.MediaController
import android.media.session.MediaSessionManager
import android.media.session.PlaybackState
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.provider.MediaStore
import android.provider.Settings
import android.util.Log
import androidx.core.app.NotificationManagerCompat
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/** What's playing in whichever media app is active (YouTube Music, Spotify, …). */
data class NowPlaying(
    val title: String = "",
    val artist: String = "",
    val art: Bitmap? = null,
    val playing: Boolean = false,
    val appPackage: String = "",
    val appLabel: String = "",
) {
    val hasTrack: Boolean get() = title.isNotBlank()
}

/** Reads and controls the active media session via the system MediaSessionManager. */
object MusicController {
    private const val TAG = "MusicController"
    const val YT_MUSIC = "com.google.android.apps.youtube.music"

    private lateinit var app: Context
    private var initialised = false
    private val main = Handler(Looper.getMainLooper())
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    private val _now = MutableStateFlow(NowPlaying())
    val now: StateFlow<NowPlaying> = _now.asStateFlow()

    private val _access = MutableStateFlow(false)
    /** True once the user has allowed notification access (needed to see other apps' music). */
    val access: StateFlow<Boolean> = _access.asStateFlow()

    private var controller: MediaController? = null
    private var msm: MediaSessionManager? = null

    private val callback = object : MediaController.Callback() {
        override fun onMetadataChanged(metadata: MediaMetadata?) = publish()
        override fun onPlaybackStateChanged(state: PlaybackState?) = publish()
        override fun onSessionDestroyed() { refresh() }
    }

    private val sessionsListener = MediaSessionManager.OnActiveSessionsChangedListener { list -> pick(list.orEmpty()) }

    fun init(context: Context) {
        if (initialised) return
        initialised = true
        app = context.applicationContext
        refresh()
    }

    fun hasAccess(): Boolean =
        initialised && NotificationManagerCompat.getEnabledListenerPackages(app).contains(app.packageName)

    /** Opens Android's Notification access screen so the user can allow music control. */
    fun openAccessSettings(context: Context) {
        runCatching {
            context.startActivity(Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        }
    }

    /** Re-check access and (re)attach to the active session. */
    fun refresh() {
        if (!initialised) return
        _access.value = hasAccess()
        if (!_access.value) return
        val m = msm ?: (app.getSystemService(Context.MEDIA_SESSION_SERVICE) as MediaSessionManager).also { msm = it }
        val cn = ComponentName(app, MediaListenerService::class.java)
        runCatching {
            m.removeOnActiveSessionsChangedListener(sessionsListener)
            m.addOnActiveSessionsChangedListener(sessionsListener, cn, main)
            pick(m.getActiveSessions(cn))
        }.onFailure { Log.w(TAG, "Media sessions unavailable", it) }
    }

    private fun pick(list: List<MediaController>) {
        // Prefer the one that's playing; otherwise the most recent.
        val best = list.firstOrNull { it.playbackState?.state == PlaybackState.STATE_PLAYING } ?: list.firstOrNull()
        if (best?.sessionToken != controller?.sessionToken) {
            controller?.unregisterCallback(callback)
            controller = best
            best?.registerCallback(callback, main)
        }
        publish()
    }

    private fun publish() {
        val c = controller
        if (c == null) { _now.value = NowPlaying(); return }
        val md = c.metadata
        val label = runCatching {
            app.packageManager.getApplicationLabel(app.packageManager.getApplicationInfo(c.packageName, 0)).toString()
        }.getOrNull()?.takeIf { it.isNotBlank() && it != c.packageName } ?: friendlyName(c.packageName)
        _now.value = NowPlaying(
            title = md?.getString(MediaMetadata.METADATA_KEY_TITLE).orEmpty(),
            artist = (md?.getString(MediaMetadata.METADATA_KEY_ARTIST) ?: md?.getString(MediaMetadata.METADATA_KEY_ALBUM_ARTIST)).orEmpty(),
            art = md?.getBitmap(MediaMetadata.METADATA_KEY_ALBUM_ART) ?: md?.getBitmap(MediaMetadata.METADATA_KEY_ART),
            playing = c.playbackState?.state == PlaybackState.STATE_PLAYING,
            appPackage = c.packageName,
            appLabel = label,
        )
    }

    // ------------------------------------------------------------ controls

    fun playPause() { val c = controller ?: return; if (_now.value.playing) c.transportControls.pause() else c.transportControls.play() }
    fun play() { controller?.transportControls?.play() ?: openMusicApp() }
    fun pause() { controller?.transportControls?.pause() }
    fun next() { controller?.transportControls?.skipToNext() }
    fun previous() { controller?.transportControls?.skipToPrevious() }

    /**
     * "Play Coldplay": asks the music app to search and play. Uses the active session when it
     * supports play-from-search (no app switch); otherwise asks YouTube Music (or any player)
     * via Android's play-from-search intent, then brings the dashboard back to the front.
     */
    fun playSearch(query: String): String {
        val q = query.trim()
        if (q.isEmpty()) { play(); return "Resuming music." }
        val c = controller
        val canSearch = c != null && ((c.playbackState?.actions ?: 0L) and PlaybackState.ACTION_PLAY_FROM_SEARCH) != 0L
        if (c != null && canSearch) {
            c.transportControls.playFromSearch(q, Bundle())
            return "Playing $q on ${_now.value.appLabel.ifBlank { "your music app" }}."
        }
        val intent = Intent(MediaStore.INTENT_ACTION_MEDIA_PLAY_FROM_SEARCH).apply {
            putExtra(MediaStore.EXTRA_MEDIA_FOCUS, "vnd.android.cursor.item/*")
            putExtra(SearchManager.QUERY, q)
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            if (installed(YT_MUSIC)) setPackage(YT_MUSIC)
        }
        val ok = runCatching { app.startActivity(intent) }.isSuccess
        if (!ok) return "I couldn't find a music app that can search. Install YouTube Music."
        bringDashboardBack()
        return "Playing $q${if (installed(YT_MUSIC)) " on YouTube Music" else ""}."
    }

    fun openMusicApp() {
        val launch = app.packageManager.getLaunchIntentForPackage(YT_MUSIC) ?: return
        runCatching { app.startActivity(launch.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }
        bringDashboardBack()
    }

    /** Never show or speak a raw package name ("com.google…") — fall back to a readable name. */
    private fun friendlyName(pkg: String) = when (pkg) {
        YT_MUSIC -> "YouTube Music"
        "com.spotify.music" -> "Spotify"
        "com.google.android.youtube" -> "YouTube"
        "com.amazon.mp3" -> "Amazon Music"
        "com.apple.android.music" -> "Apple Music"
        else -> "your music app"
    }

    private fun installed(pkg: String) = runCatching { app.packageManager.getPackageInfo(pkg, 0) }.isSuccess

    /** The music app may open its own screen; return to the dashboard once playback starts. */
    private fun bringDashboardBack() {
        scope.launch {
            delay(3500)
            val launch = app.packageManager.getLaunchIntentForPackage(app.packageName) ?: return@launch
            runCatching {
                app.startActivity(launch.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_REORDER_TO_FRONT))
            }
            refresh()
        }
    }

    fun status(): String {
        val n = _now.value
        return when {
            !hasAccess() -> "Music control isn't allowed yet. Turn on notification access for Car Dashboard in Setup."
            !n.hasTrack -> "Nothing is playing."
            else -> "${if (n.playing) "Playing" else "Paused"}: ${n.title}${if (n.artist.isNotBlank()) " by ${n.artist}" else ""} on ${n.appLabel}."
        }
    }
}
