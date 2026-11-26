package com.cardash.dashboard.data

import android.Manifest
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import android.os.SystemClock
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import com.cardash.dashboard.MainActivity
import com.cardash.dashboard.R
import com.cardash.dashboard.obd.ConnectionStatus
import com.cardash.dashboard.obd.ObdManager
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/**
 * Keeps a drive recording complete while the dashboard is off screen (screen off, or Google Maps or music in front).
 *
 * Android stops sending an app its location as soon as none of its screens is visible, unless the app runs a
 * foreground service (which comes with a notification). Without one, the car's readings kept being logged but the
 * GPS position froze where the dashboard left the screen, so the replay's car stood still for the rest of the drive.
 * The service does no work itself: while it runs, the app's existing GPS stream and car link keep going.
 */
class DriveService : Service() {

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (!goForeground()) {
            stopSelf()
            DriveKeeper.onServiceStopped()
        }
        // If Android ends it, don't restart it from the background: it couldn't use the GPS then anyway.
        return START_NOT_STICKY
    }

    override fun onDestroy() {
        DriveKeeper.onServiceStopped()
        super.onDestroy()
    }

    private fun goForeground(): Boolean {
        val note = notification()
        val gps = has(this, Manifest.permission.ACCESS_FINE_LOCATION) || has(this, Manifest.permission.ACCESS_COARSE_LOCATION)
        val link = Build.VERSION.SDK_INT < 31 || has(this, Manifest.permission.BLUETOOTH_CONNECT)
        val loc = ServiceInfo.FOREGROUND_SERVICE_TYPE_LOCATION
        val dev = ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE
        // Location is only allowed when the dashboard was on screen as the service started; the car link always is.
        val tries = listOfNotNull(
            if (gps && link) loc or dev else null,
            if (gps) loc else null,
            if (link) dev else null,
        )
        for (types in tries) {
            try {
                ServiceCompat.startForeground(this, NOTE_ID, note, types)
                DriveKeeper.onServiceForeground(gpsAllowed = types and loc != 0)
                return true
            } catch (e: Exception) {
                Log.w(TAG, "Foreground (types $types) refused: ${e.message}")
            }
        }
        return false
    }

    private fun notification(): Notification {
        val nm = getSystemService(NotificationManager::class.java)
        if (nm.getNotificationChannel(CHANNEL) == null) {
            nm.createNotificationChannel(
                NotificationChannel(CHANNEL, "Drive recording", NotificationManager.IMPORTANCE_LOW).apply {
                    description = "Shown while a drive is recorded with the dashboard off screen, so GPS keeps working."
                    setShowBadge(false)
                },
            )
        }
        val open = PendingIntent.getActivity(
            this, 0,
            Intent(this, MainActivity::class.java)
                .setAction(Intent.ACTION_MAIN)
                .addCategory(Intent.CATEGORY_LAUNCHER)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_RESET_TASK_IF_NEEDED),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        return NotificationCompat.Builder(this, CHANNEL)
            .setSmallIcon(R.drawable.ic_stat_drive)
            .setContentTitle("Recording your drive")
            .setContentText("GPS and car readings keep going while the dashboard is off screen.")
            .setContentIntent(open)
            .setOngoing(true)
            .setSilent(true)
            .setShowWhen(false)
            .setCategory(NotificationCompat.CATEGORY_SERVICE)
            .setForegroundServiceBehavior(NotificationCompat.FOREGROUND_SERVICE_IMMEDIATE)
            .build()
    }

    companion object {
        private const val TAG = "DriveService"
        private const val CHANNEL = "drive_recording"
        private const val NOTE_ID = 7301

        internal fun has(c: Context, p: String) = ContextCompat.checkSelfPermission(c, p) == PackageManager.PERMISSION_GRANTED
    }
}

/**
 * Decides when [DriveService] runs: while the car is on, and for [GRACE_MS] after it goes quiet (a stop for fuel
 * or a shop), so a short stop doesn't end the recording. Demo drives don't count. The service can only use the GPS
 * if it was started while the dashboard was on screen, so it's (re)started whenever the dashboard comes back.
 */
object DriveKeeper {
    private const val TAG = "DriveKeeper"
    private const val GRACE_MS = 15 * 60_000L
    private const val RETRY_MS = 60_000L        // from the background (usually refused until the dashboard is opened)
    private const val RETRY_VISIBLE_MS = 5_000L

    private lateinit var app: Context
    private var initialised = false
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    private var visible = false
    private var running = false
    private var gpsAllowed = false
    private var lastCarOn = 0L
    private var lastTry = 0L

    fun init(context: Context) {
        if (initialised) return
        initialised = true
        app = context.applicationContext
        scope.launch { ObdManager.state.collect { evaluate() } }
        scope.launch { DriveLog.enabled.collect { evaluate() } }
        scope.launch { while (isActive) { delay(30_000); evaluate() } }   // ends the grace period on time
    }

    /** The dashboard came on screen, or left it. */
    fun setVisible(v: Boolean) {
        if (!initialised) return
        visible = v
        evaluate()
    }

    internal fun onServiceForeground(gpsAllowed: Boolean) {
        running = true
        this.gpsAllowed = gpsAllowed
    }

    internal fun onServiceStopped() {
        running = false
        gpsAllowed = false
    }

    private fun evaluate() {
        val s = ObdManager.state.value
        val now = SystemClock.elapsedRealtime()
        if (!s.demo && s.connected && s.readings.isNotEmpty()) lastCarOn = now
        val linked = !s.demo && (s.status is ConnectionStatus.Connected || s.status is ConnectionStatus.Reconnecting)
        val want = DriveLog.enabled.value && linked && lastCarOn != 0L && now - lastCarOn < GRACE_MS
        val since = now - lastTry
        when {
            !want -> if (running) stop()
            // Start it (or restart it, so it may use the GPS) while the dashboard is on screen.
            visible && (!running || (!gpsAllowed && hasGps())) -> if (since > RETRY_VISIBLE_MS) start()
            !running -> if (since > RETRY_MS) start()
        }
    }

    private fun start() {
        lastTry = SystemClock.elapsedRealtime()
        val link = Build.VERSION.SDK_INT < 31 || DriveService.has(app, Manifest.permission.BLUETOOTH_CONNECT)
        if (!hasGps() && !link) return
        try {
            ContextCompat.startForegroundService(app, Intent(app, DriveService::class.java))
            running = true   // confirmed (or undone) by the service itself
        } catch (e: Exception) {
            // Android 12+ doesn't let a background app start one; it starts when the dashboard is opened.
            Log.i(TAG, "Not started now: ${e.message}")
        }
    }

    private fun hasGps() = DriveService.has(app, Manifest.permission.ACCESS_FINE_LOCATION) ||
        DriveService.has(app, Manifest.permission.ACCESS_COARSE_LOCATION)

    private fun stop() {
        running = false
        gpsAllowed = false
        runCatching { app.stopService(Intent(app, DriveService::class.java)) }   // also removes its notification
    }
}
