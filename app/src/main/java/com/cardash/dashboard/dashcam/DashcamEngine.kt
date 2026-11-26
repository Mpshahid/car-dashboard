package com.cardash.dashboard.dashcam

import android.Manifest
import android.annotation.SuppressLint
import android.content.ContentUris
import android.content.ContentValues
import android.content.Context
import android.content.pm.PackageManager
import android.graphics.SurfaceTexture
import android.os.Build
import android.os.SystemClock
import android.provider.MediaStore
import android.util.Log
import android.view.Surface
import androidx.camera.core.Camera
import androidx.camera.core.CameraSelector
import androidx.camera.core.ConcurrentCamera
import androidx.camera.core.LayoutSettings
import androidx.camera.core.Preview
import androidx.camera.core.UseCaseGroup
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.video.FallbackStrategy
import androidx.camera.video.MediaStoreOutputOptions
import androidx.camera.video.QualitySelector
import androidx.camera.video.Recorder
import androidx.camera.video.Recording
import androidx.camera.video.VideoCapture
import androidx.camera.video.VideoRecordEvent
import androidx.core.content.ContextCompat
import androidx.lifecycle.LifecycleOwner
import com.cardash.dashboard.nav.NavManager
import com.cardash.dashboard.nav.SpeedLimitManager
import com.cardash.dashboard.obd.ConnectionStatus
import com.cardash.dashboard.obd.GearReading
import com.cardash.dashboard.obd.ObdManager
import com.cardash.dashboard.obd.Pid
import com.cardash.dashboard.ui.Fmt
import com.cardash.dashboard.ui.UiPrefs
import com.cardash.dashboard.ui.screens.DashcamVideoOverlay
import com.cardash.dashboard.ui.screens.VideoHudSnapshot
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.math.roundToInt

/**
 * App-wide dashcam: one camera session shared by the Drive tile and the Dashcam tab.
 *
 * - Back camera (zoomed out to the widest lens) plus, where the phone supports concurrent
 *   cameras, the front camera composed picture-in-picture into the same video.
 * - HUD burned into the video via [DashcamVideoOverlay].
 * - Clips are split into [SEGMENT_MS] segments; old clips are deleted per the retention setting.
 * - Sound from the microphone when "Record sound" is on and the mic permission is granted.
 * - Auto-record: starts once the car moves at [START_MPH] (the car's own speed when the OBD adapter
 *   is connected, GPS otherwise) and stops [ENGINE_OFF_STOP_MS] after the engine is switched off.
 *   With a car adapter set up, only the car counts: GPS drift at home doesn't start a clip, and an
 *   automatic clip always ends once the engine is off (or the adapter has gone quiet).
 */
object DashcamEngine {
    private const val TAG = "DashcamEngine"
    private const val SEGMENT_MS = 5 * 60_000L
    private const val FOLDER = "Movies/CarDashcam"

    /** Auto-record starts once the car is doing this speed. */
    const val START_MPH = 2.0
    /** ...and stops this long after the engine is switched off (rides out a stall). */
    const val ENGINE_OFF_STOP_MS = 10_000L
    /** Without engine data (no OBD adapter), an auto-started clip stops after this long parked. */
    private const val PARKED_STOP_MS = 3 * 60_000L

    // Front camera picture-in-picture, bottom-right corner (normalised composition coords).
    private const val PIP_X = 0.66f
    private const val PIP_Y = -0.6f
    private const val PIP_SIZE = 0.3f

    data class State(
        val bound: Boolean = false,
        val recording: Boolean = false,
        val startedAt: Long = 0L,
        val autoStarted: Boolean = false,
        val dualActive: Boolean = false,
        val dualSupported: Boolean = false,
        val overlayInVideo: Boolean = false,
        val zoom: Float = 1f,
        val clipCount: Int = 0,
        val clipBytes: Long = 0L,
        /** Size and length of the newest finished clip, so the settings can show the real MB a minute. */
        val lastClipBytes: Long = 0L,
        val lastClipMs: Long = 0L,
        /** The clip being recorded includes sound. */
        val withSound: Boolean = false,
        val error: String? = null,
    )

    private val _state = MutableStateFlow(State())
    val state: StateFlow<State> = _state.asStateFlow()

    lateinit var settings: DashcamSettings
        private set

    private lateinit var app: Context
    private var initialized = false
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val mainExecutor by lazy { ContextCompat.getMainExecutor(app) }
    private val overlay by lazy { DashcamVideoOverlay() }
    private val prefs by lazy { app.getSharedPreferences("cardash", Context.MODE_PRIVATE) }

    private var provider: ProcessCameraProvider? = null
    private var owner: LifecycleOwner? = null
    private var preview: Preview? = null
    private var videoCapture: VideoCapture<Recorder>? = null
    private var cameras: List<Camera> = emptyList()
    private var recording: Recording? = null
    private var viewProvider: Preview.SurfaceProvider? = null
    private var rotation = Surface.ROTATION_0
    private var pendingRebind = false
    private var restartAfterFinalize = false
    private var sawEngine = false
    private var autoSuppressed = false
    private var segmentJob: Job? = null

    fun init(context: Context) {
        if (initialized) return
        initialized = true
        app = context.applicationContext
        settings = DashcamSettings(app)
        scope.launch(Dispatchers.IO) { cleanupOld(); refreshStats() }
        scope.launch { autoRecordLoop() }
        scope.launch {
            while (isActive) {
                delay(250)
                if (_state.value.bound) pushSnapshot()
            }
        }
    }

    // ---------------------------------------------------------------- binding

    /** Last preview frame, shown dimmed on the Drive tile while the cameras are off. */
    val lastFrame = MutableStateFlow<androidx.compose.ui.graphics.ImageBitmap?>(null)

    // Battery saver: cameras run only while recording or while the Dashcam tab is on screen.
    private var liveRequested = false

    fun attach(lifecycleOwner: LifecycleOwner) {
        owner = lifecycleOwner
        val future = ProcessCameraProvider.getInstance(app)
        future.addListener({
            provider = runCatching { future.get() }.getOrNull()
            syncBinding()
        }, mainExecutor)
    }

    /** The Dashcam tab asks for a live view while it's visible. */
    fun setLive(v: Boolean) {
        liveRequested = v
        syncBinding()
    }

    private fun cameraWanted() = recording != null || restartAfterFinalize || liveRequested

    private fun syncBinding() {
        val bound = _state.value.bound
        if (cameraWanted() && !bound) bind()
        else if (!cameraWanted() && bound) unbindCameras()
    }

    private fun unbindCameras() {
        runCatching { provider?.unbindAll() }
        cameras = emptyList()
        _state.update { it.copy(bound = false) }
        Log.i(TAG, "Cameras off (battery saver)")
    }

    fun detach(lifecycleOwner: LifecycleOwner) {
        if (owner !== lifecycleOwner) return
        stopRecording()
        provider?.unbindAll()
        owner = null
        cameras = emptyList()
        _state.update { it.copy(bound = false, dualActive = false) }
    }

    fun setRotation(r: Int) {
        rotation = r
        preview?.targetRotation = r
        if (recording == null) videoCapture?.targetRotation = r
    }

    private fun bind() {
        val p = provider ?: return
        val o = owner ?: return
        if (recording != null) { pendingRebind = true; return }
        pendingRebind = false
        p.unbindAll()

        val q = settings.quality.value
        // A fresh VideoCapture per attempt: the frame rate is fixed when it's built.
        fun videoCaptureFor(fps: Int?): VideoCapture<Recorder> {
            val recorder = Recorder.Builder()
                .setQualitySelector(QualitySelector.from(q.quality, FallbackStrategy.lowerQualityOrHigherThan(q.quality)))
                .setTargetVideoEncodingBitRate(q.bitrate)
                .build()
            return VideoCapture.Builder(recorder)
                .apply { if (fps != null) setTargetFrameRate(android.util.Range(fps, fps)) }
                .build()
                .also { it.targetRotation = rotation }
        }
        val pv = Preview.Builder().build().also {
            it.targetRotation = rotation
            it.setSurfaceProvider(mainExecutor, currentSurfaceProvider())
        }
        preview = pv

        val pair = concurrentPair(p)
        val layouts = buildList {
            if (settings.dual.value && pair != null) { add(true to true); add(true to false) }
            add(false to true)
            add(false to false)
        }
        // The preset's lower frame rate first (CCTV), then the camera's normal rate if this phone won't do it.
        val rates: List<Int?> = if (q.fps != null) listOf(q.fps, null) else listOf(null)
        val attempts = layouts.flatMap { (d, f) -> rates.map { r -> Triple(d, f, r) } }
        var bound: List<Camera> = emptyList()
        var dual = false
        var fx = false
        var lastError: Throwable? = null
        var vc: VideoCapture<Recorder>? = null
        for ((d, withFx, fps) in attempts) {
            vc = videoCaptureFor(fps)
            try {
                val group = UseCaseGroup.Builder().addUseCase(pv).addUseCase(vc)
                    .apply { if (withFx) addEffect(overlay.effect) }
                    .build()
                bound = if (d) {
                    val (back, front) = pair!!
                    val full = LayoutSettings.Builder().setAlpha(1f).setOffsetX(0f).setOffsetY(0f)
                        .setWidth(1f).setHeight(1f).build()
                    val pip = LayoutSettings.Builder().setAlpha(1f).setOffsetX(PIP_X).setOffsetY(PIP_Y)
                        .setWidth(PIP_SIZE).setHeight(PIP_SIZE).build()
                    p.bindToLifecycle(
                        listOf(
                            ConcurrentCamera.SingleCameraConfig(back, group, full, o),
                            ConcurrentCamera.SingleCameraConfig(front, group, pip, o),
                        ),
                    ).cameras
                } else {
                    listOf(p.bindToLifecycle(o, CameraSelector.DEFAULT_BACK_CAMERA, group))
                }
                dual = d
                fx = withFx
                Log.i(TAG, "Bound dual=$d overlay=$withFx fps=${fps ?: "normal"} quality=${q.name}")
                break
            } catch (t: Throwable) {
                lastError = t
                Log.w(TAG, "Bind failed dual=$d overlay=$withFx fps=${fps ?: "normal"}", t)
                runCatching { p.unbindAll() }
            }
        }
        videoCapture = vc
        cameras = bound
        _state.update {
            it.copy(
                bound = bound.isNotEmpty(),
                dualActive = dual,
                dualSupported = pair != null,
                overlayInVideo = fx,
                error = if (bound.isEmpty()) (lastError?.message ?: "Camera unavailable") else null,
            )
        }
        scope.launch { delay(400); applyZoom() }
    }

    private fun concurrentPair(p: ProcessCameraProvider): Pair<CameraSelector, CameraSelector>? =
        runCatching {
            p.availableConcurrentCameraInfos.firstNotNullOfOrNull { combo ->
                val back = combo.firstOrNull { it.lensFacing == CameraSelector.LENS_FACING_BACK }
                val front = combo.firstOrNull { it.lensFacing == CameraSelector.LENS_FACING_FRONT }
                if (back != null && front != null) back.cameraSelector to front.cameraSelector else null
            }
        }.getOrNull()

    private fun applyZoom() {
        val cam = cameras.firstOrNull { it.cameraInfo.lensFacing == CameraSelector.LENS_FACING_BACK } ?: return
        val zs = cam.cameraInfo.zoomState.value
        val min = zs?.minZoomRatio ?: 1f
        val target = if (settings.wide.value) min else maxOf(1f, min)
        cam.cameraControl.setZoomRatio(target)
        _state.update { it.copy(zoom = target) }
    }

    // ---------------------------------------------------------------- preview surfaces

    /** Keeps the camera session alive when no preview is on screen (e.g. Status tab). */
    private val nullSurfaceProvider = Preview.SurfaceProvider { request ->
        val tex = SurfaceTexture(false).apply {
            setDefaultBufferSize(request.resolution.width, request.resolution.height)
        }
        val surface = Surface(tex)
        request.provideSurface(surface, mainExecutor) { surface.release(); tex.release() }
    }

    private fun currentSurfaceProvider(): Preview.SurfaceProvider = viewProvider ?: nullSurfaceProvider

    fun showPreviewOn(sp: Preview.SurfaceProvider) {
        viewProvider = sp
        preview?.setSurfaceProvider(mainExecutor, sp)
    }

    fun hidePreviewFrom(sp: Preview.SurfaceProvider) {
        if (viewProvider !== sp) return
        viewProvider = null
        preview?.setSurfaceProvider(mainExecutor, nullSurfaceProvider)
    }

    // ---------------------------------------------------------------- recording

    /** Voice: "start recording" / "stop recording". */
    fun startNow() { if (recording == null) startRecording(auto = false) }
    fun stopNow() { if (recording != null) stopRecording(manual = true) }

    fun toggle() {
        if (recording != null) stopRecording(manual = true) else startRecording(auto = false)
    }

    @SuppressLint("MissingPermission")
    private fun startRecording(auto: Boolean, continueSegment: Boolean = false) {
        if (recording != null) return
        if (!_state.value.bound) bind() // battery saver: cameras start on demand
        val vc = videoCapture ?: return
        if (!_state.value.bound) return
        vc.targetRotation = rotation
        val name = "dashcam_" + SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date())
        val values = ContentValues().apply {
            put(MediaStore.Video.Media.DISPLAY_NAME, name)
            put(MediaStore.Video.Media.MIME_TYPE, "video/mp4")
            if (Build.VERSION.SDK_INT >= 29) put(MediaStore.Video.Media.RELATIVE_PATH, FOLDER)
        }
        val output = MediaStoreOutputOptions
            .Builder(app.contentResolver, MediaStore.Video.Media.EXTERNAL_CONTENT_URI)
            .setContentValues(values)
            .build()
        if (!continueSegment) sawEngine = false
        val sound = settings.audio.value &&
            ContextCompat.checkSelfPermission(app, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED
        recording = try {
            vc.output.prepareRecording(app, output)
                .let { if (sound) it.withAudioEnabled() else it }
                .start(mainExecutor) { ev ->
                    if (ev is VideoRecordEvent.Finalize) onFinalized(ev)
                }
        } catch (t: Throwable) {
            Log.e(TAG, "Could not start recording", t)
            _state.update { it.copy(error = t.message) }
            syncBinding()
            return
        }
        _state.update {
            it.copy(
                recording = true,
                startedAt = if (continueSegment) it.startedAt else SystemClock.elapsedRealtime(),
                autoStarted = if (continueSegment) it.autoStarted else auto,
                withSound = sound,
                error = null,
            )
        }
        segmentJob?.cancel()
        segmentJob = scope.launch {
            delay(SEGMENT_MS)
            restartAfterFinalize = true
            recording?.stop()
            recording = null
        }
    }

    fun stopRecording(manual: Boolean = false) {
        segmentJob?.cancel()
        restartAfterFinalize = false
        recording?.stop()
        recording = null
        // Stopped by hand while driving: don't auto-start again until the car has been switched off.
        if (manual && (engineRunning() || (speedNow()?.mph ?: 0.0) >= START_MPH)) autoSuppressed = true
        _state.update { it.copy(recording = false, autoStarted = false, withSound = false) }
    }

    private fun onFinalized(ev: VideoRecordEvent.Finalize) {
        if (ev.hasError()) Log.w(TAG, "Recording finalized with error ${ev.error}", ev.cause)
        scope.launch(Dispatchers.IO) { cleanupOld(); refreshStats() }
        if (restartAfterFinalize) {
            restartAfterFinalize = false
            if (pendingRebind) bind()
            startRecording(auto = _state.value.autoStarted, continueSegment = true)
        } else {
            if (pendingRebind && cameraWanted()) bind()
            pendingRebind = false
            syncBinding() // turn the cameras off again if nobody needs them
        }
    }

    // ---------------------------------------------------------------- auto start / stop

    private fun obdLive(): Boolean = ObdManager.state.value.let { it.status is ConnectionStatus.Connected && !it.demo }

    /** Engine running: real OBD connection with a current RPM reading above stall level. */
    private fun engineRunning(): Boolean =
        obdLive() && (ObdManager.state.value.value(Pid.ENGINE_RPM) ?: 0.0) > 300

    private data class Speed(val mph: Double, val fromCar: Boolean)

    /** The car's own speed when the OBD adapter is connected, otherwise a recent GPS speed. */
    private fun speedNow(): Speed? {
        if (obdLive()) ObdManager.state.value.value(Pid.SPEED)?.let { return Speed(it / 1.609344, fromCar = true) }
        return NavManager.freshGpsSpeedMps()?.let { Speed(it * 2.236936, fromCar = false) }
    }

    /** The driver uses an OBD adapter (one is saved): then the engine, not GPS, decides when to record. */
    private val carSettings by lazy { com.cardash.dashboard.data.SettingsStore(app) }

    private suspend fun autoRecordLoop() {
        var movingSince = 0L
        var stillSince = 0L
        var offSince = 0L
        while (true) {
            delay(1000)
            val now = SystemClock.elapsedRealtime()
            val obd = obdLive()
            val running = engineRunning()
            val speed = speedNow()
            val carAdapter = carSettings.lastDeviceAddress != null
            // With an adapter only the car's own speed counts: GPS drifts while parked, and an asleep adapter
            // (engine off) mustn't hand the decision to GPS.
            val moving = speed != null && speed.mph >= START_MPH && (speed.fromCar || (!obd && !carAdapter))
            val still = speed == null || speed.mph < 1.0
            movingSince = if (moving) (movingSince.takeIf { it != 0L } ?: now) else 0L
            stillSince = if (still) (stillSince.takeIf { it != 0L } ?: now) else 0L
            offSince = if (running) 0L else (offSince.takeIf { it != 0L } ?: now)
            val engineOff = !running && now - offSince >= ENGINE_OFF_STOP_MS
            val parked = still && now - stillSince >= PARKED_STOP_MS

            val st = _state.value
            if (st.recording && running) sawEngine = true
            // A hand-stopped clip may auto-start again once the car has been switched off.
            if ((obd && engineOff) || (!obd && parked)) autoSuppressed = false

            val startAfter = if (speed?.fromCar == true) 1_000L else 3_000L
            if (settings.autoRecord.value && settings.enabled.value && provider != null && owner != null && !st.recording &&
                !autoSuppressed && moving && now - movingSince >= startAfter
            ) {
                Log.i(TAG, "Auto-start at %.1f mph (%s)".format(Locale.US, speed!!.mph, if (speed.fromCar) "car" else "GPS"))
                startRecording(auto = true)
            } else if (st.recording && engineOff && (sawEngine || (carAdapter && st.autoStarted))) {
                Log.i(TAG, "Auto-stop: engine switched off")
                stopRecording()
            } else if (st.recording && st.autoStarted && !sawEngine && parked) {
                Log.i(TAG, "Auto-stop: parked, no engine data")
                stopRecording()
            }
        }
    }

    // ---------------------------------------------------------------- burned-in HUD

    private fun pushSnapshot() {
        val s = ObdManager.state.value
        val ui = UiPrefs(imperial = prefs.getBoolean("imperial", true), tempF = prefs.getBoolean("tempF", false))
        val limit = SpeedLimitManager.limit.value
        overlay.snapshot.set(
            VideoHudSnapshot(
                speed = s.value(Pid.SPEED)?.let { Fmt.speedValue(it, ui.imperial).toString() } ?: "--",
                speedUnit = Fmt.speedUnit(ui.imperial),
                gear = when (val g = s.gear) {
                    is GearReading.Engaged -> g.gear.toString()
                    GearReading.Neutral -> "N"
                    GearReading.Unknown -> "-"
                },
                rpm = s.value(Pid.ENGINE_RPM)?.roundToInt()?.toString() ?: "--",
                coolant = s.value(Pid.COOLANT_TEMP)?.let { Fmt.reading(Pid.COOLANT_TEMP, it, ui) + Fmt.unit(Pid.COOLANT_TEMP, ui) } ?: "--",
                limit = limit?.let {
                    if (ui.imperial) (Math.round(it.kmh / 1.609344 / 5.0) * 5).toString() else it.kmh.toString()
                },
            ),
        )
    }

    // ---------------------------------------------------------------- settings actions

    // Only rebind if the cameras are currently on; otherwise the new setting applies next time they start.
    fun setDual(v: Boolean) { settings.setDual(v); if (_state.value.bound) bind() }
    fun setQuality(q: DashcamQuality) { settings.setQuality(q); if (_state.value.bound) bind() }
    fun setWide(v: Boolean) { settings.setWide(v); applyZoom() }
    fun setAutoRecord(v: Boolean) { settings.setAutoRecord(v) }
    /** Takes effect from the next clip (clips are split every 5 minutes). */
    fun setAudio(v: Boolean) { settings.setAudio(v) }
    fun setEnabled(v: Boolean) { if (!v) stopRecording(); settings.setEnabled(v) }
    fun setRetentionDays(d: Int) {
        settings.setRetentionDays(d)
        scope.launch(Dispatchers.IO) { cleanupOld(); refreshStats() }
    }

    fun refreshStatsAsync() { scope.launch(Dispatchers.IO) { refreshStats() } }

    /** Deletes every finished clip (the clip currently being written is left alone). */
    fun deleteAllClips(onDone: (Int) -> Unit = {}) {
        scope.launch(Dispatchers.IO) {
            val n = deleteWhere(null)
            refreshStats()
            launch(Dispatchers.Main) { onDone(n) }
        }
    }

    // ---------------------------------------------------------------- storage (MediaStore)

    private fun folderSelection(): Pair<String, Array<String>> =
        if (Build.VERSION.SDK_INT >= 29) {
            "${MediaStore.Video.Media.RELATIVE_PATH} LIKE ?" to arrayOf("$FOLDER%")
        } else {
            @Suppress("DEPRECATION")
            "${MediaStore.Video.Media.DATA} LIKE ?" to arrayOf("%/$FOLDER/%")
        }

    private fun refreshStats() {
        val (sel, args) = folderSelection()
        var count = 0
        var bytes = 0L
        var newest = -1L
        var lastBytes = 0L
        var lastMs = 0L
        runCatching {
            app.contentResolver.query(
                MediaStore.Video.Media.EXTERNAL_CONTENT_URI,
                arrayOf(
                    MediaStore.Video.Media._ID, MediaStore.Video.Media.SIZE,
                    MediaStore.Video.Media.DURATION, MediaStore.Video.Media.DATE_ADDED,
                ),
                sel, args, null,
            )?.use { c ->
                val sizeCol = c.getColumnIndexOrThrow(MediaStore.Video.Media.SIZE)
                val durCol = c.getColumnIndexOrThrow(MediaStore.Video.Media.DURATION)
                val addedCol = c.getColumnIndexOrThrow(MediaStore.Video.Media.DATE_ADDED)
                while (c.moveToNext()) {
                    count++
                    val size = c.getLong(sizeCol)
                    bytes += size
                    val ms = c.getLong(durCol)
                    val added = c.getLong(addedCol)
                    if (ms > 0 && added >= newest) { newest = added; lastBytes = size; lastMs = ms }
                }
            }
        }.onFailure { Log.w(TAG, "Stats query failed", it) }
        _state.update { it.copy(clipCount = count, clipBytes = bytes, lastClipBytes = lastBytes, lastClipMs = lastMs) }
    }

    private fun cleanupOld() {
        val days = settings.retentionDays.value
        if (days <= 0) return
        val cutoffSec = System.currentTimeMillis() / 1000 - days * 86_400L
        val n = deleteWhere(cutoffSec)
        if (n > 0) Log.i(TAG, "Deleted $n clip(s) older than $days day(s)")
    }

    /** Deletes clips in the dashcam folder, optionally only those added before [olderThanSec]. */
    private fun deleteWhere(olderThanSec: Long?): Int {
        val (sel, args) = folderSelection()
        val fullSel = if (olderThanSec != null) "$sel AND ${MediaStore.Video.Media.DATE_ADDED} < ?" else sel
        val fullArgs = if (olderThanSec != null) args + olderThanSec.toString() else args
        var deleted = 0
        runCatching {
            app.contentResolver.query(
                MediaStore.Video.Media.EXTERNAL_CONTENT_URI,
                arrayOf(MediaStore.Video.Media._ID),
                fullSel, fullArgs, null,
            )?.use { c ->
                val idCol = c.getColumnIndexOrThrow(MediaStore.Video.Media._ID)
                while (c.moveToNext()) {
                    val uri = ContentUris.withAppendedId(MediaStore.Video.Media.EXTERNAL_CONTENT_URI, c.getLong(idCol))
                    deleted += runCatching { app.contentResolver.delete(uri, null, null) }.getOrDefault(0)
                }
            }
        }.onFailure { Log.w(TAG, "Delete failed", it) }
        return deleted
    }
}
