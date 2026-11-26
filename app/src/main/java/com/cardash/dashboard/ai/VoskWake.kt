package com.cardash.dashboard.ai

import android.content.Context
import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONObject
import org.vosk.Model
import org.vosk.Recognizer
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.util.zip.ZipInputStream

/**
 * Offline, on-device continuous speech recognition (Vosk, small English model ~40 MB).
 * Used for "Hey Dash" and hands-free commands: no audio leaves the phone, no restart gaps.
 * The model is downloaded once, when the user taps Download in Setup.
 */
object VoskWake {
    private const val TAG = "VoskWake"
    const val MODEL_URL = "https://alphacephei.com/vosk/models/vosk-model-small-en-us-0.15.zip"
    const val MODEL_SIZE_MB = 40

    enum class Status { NOT_INSTALLED, DOWNLOADING, UNPACKING, LOADING, READY, ERROR }

    data class State(val status: Status = Status.NOT_INSTALLED, val progress: Float = 0f, val message: String? = null)

    private val _state = MutableStateFlow(State())
    val state: StateFlow<State> = _state.asStateFlow()
    val ready: Boolean get() = _state.value.status == Status.READY

    private lateinit var app: Context
    private var initialised = false
    private val io = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var model: Model? = null
    val running: Boolean get() = capturing

    /** Called when the model becomes ready, so the assistant can switch engines. */
    var onReady: (() -> Unit)? = null

    private val modelDir get() = File(app.filesDir, "vosk-model-en")

    fun init(context: Context) {
        if (initialised) return
        initialised = true
        app = context.applicationContext
        if (File(modelDir, "conf/model.conf").exists() || File(modelDir, "am/final.mdl").exists()) load()
    }

    // ------------------------------------------------------------ model download / load

    /** Download (~40 MB) and unpack the model. Started by the user from Setup. */
    fun download() {
        if (!initialised) return
        val s = _state.value.status
        if (s == Status.DOWNLOADING || s == Status.UNPACKING || s == Status.LOADING || s == Status.READY) return
        io.launch {
            val zip = File(app.cacheDir, "vosk-model.zip")
            val tmp = File(app.filesDir, "vosk-model-tmp")
            try {
                _state.value = State(Status.DOWNLOADING, 0f)
                val conn = (URL(MODEL_URL).openConnection() as HttpURLConnection).apply {
                    connectTimeout = 20_000
                    readTimeout = 30_000
                }
                if (conn.responseCode !in 200..299) throw IllegalStateException("Download failed (HTTP ${conn.responseCode})")
                val total = conn.contentLengthLong.takeIf { it > 0 } ?: (MODEL_SIZE_MB * 1_000_000L)
                conn.inputStream.use { input ->
                    zip.outputStream().use { out ->
                        val buf = ByteArray(64 * 1024)
                        var done = 0L
                        while (true) {
                            val n = input.read(buf)
                            if (n < 0) break
                            out.write(buf, 0, n)
                            done += n
                            _state.update { it.copy(progress = (done.toFloat() / total).coerceIn(0f, 1f)) }
                        }
                    }
                }
                conn.disconnect()

                _state.value = State(Status.UNPACKING, 1f)
                tmp.deleteRecursively(); tmp.mkdirs()
                val root = tmp.canonicalPath + File.separator
                ZipInputStream(zip.inputStream().buffered()).use { zin ->
                    while (true) {
                        val e = zin.nextEntry ?: break
                        val out = File(tmp, e.name)
                        // Zip-slip guard: never write outside the target folder.
                        if (!out.canonicalPath.startsWith(root)) throw SecurityException("Bad entry in model archive")
                        if (e.isDirectory) out.mkdirs() else {
                            out.parentFile?.mkdirs()
                            out.outputStream().use { zin.copyTo(it) }
                        }
                    }
                }
                // The archive has one top-level folder (vosk-model-small-en-us-0.15/).
                val inner = tmp.listFiles()?.singleOrNull { it.isDirectory } ?: tmp
                modelDir.deleteRecursively()
                if (!inner.renameTo(modelDir)) inner.copyRecursively(modelDir, overwrite = true)
                tmp.deleteRecursively()
                zip.delete()
                load()
            } catch (e: Exception) {
                Log.w(TAG, "Model download failed", e)
                zip.delete(); tmp.deleteRecursively()
                _state.value = State(Status.ERROR, message = e.message ?: "Download failed")
            }
        }
    }

    private fun load() {
        io.launch {
            _state.value = State(Status.LOADING, 1f)
            try {
                model = Model(modelDir.absolutePath)
                _state.value = State(Status.READY, 1f)
                withContext(Dispatchers.Main) { onReady?.invoke() }
            } catch (e: Exception) {
                Log.w(TAG, "Model load failed", e)
                _state.value = State(Status.ERROR, message = "Couldn't load the speech model: ${e.message}")
            }
        }
    }

    fun delete() {
        stop()
        runCatching { model?.close() }
        model = null
        modelDir.deleteRecursively()
        _state.value = State(Status.NOT_INSTALLED)
    }

    // ------------------------------------------------------------ listening

    /**
     * What the listener can recognise: the wake word, the common commands (so "Dash, next song"
     * works in one breath), and "[unk]" for everything else. A small closed vocabulary is far
     * more robust over music and road noise than open dictation.
     */
    private val GRAMMAR = listOf(
        "dash", "hey dash", "okay dash",
        "next", "next song", "next track", "skip", "skip this song",
        "previous", "previous song", "last song", "go back",
        "pause", "pause the music", "stop the music", "play", "play music", "resume", "resume the music",
        "what's playing", "what is playing",
        "start recording", "stop recording",
        "take me home", "go home", "navigate home", "take me to work", "go to work",
        "cancel the route", "stop navigation", "cancel navigation",
        "mute", "mute directions", "unmute",
        "what's my speed", "what is my speed", "how long", "how far",
        "what's my coolant temperature", "how much fuel",
        "[unk]",
    )

    private val main = android.os.Handler(android.os.Looper.getMainLooper())
    @Volatile private var capturing = false
    private var captureThread: Thread? = null

    /**
     * Start continuous recognition from an echo-cancelled mic stream ("voice call" capture with
     * Android's echo canceller + noise suppressor), so music playing from the phone or car speakers
     * is largely removed before recognition. [onText] gets each finished phrase; [onLevel] a 0..1
     * loudness for the mic animation. Callbacks arrive on the main thread.
     */
    /**
     * Command mode: after "Dash", the same mic stream switches to full-vocabulary dictation for the
     * command, so the microphone never has to be handed to another recogniser (that handover caused
     * Google recogniser errors 11 / 5).
     */
    @Volatile var commandMode = false
        private set
    @Volatile private var resetFree = false
    @Volatile private var resetWake = false

    fun enterCommandMode() { resetFree = true; commandMode = true }
    fun exitCommandMode() { commandMode = false; resetWake = true }

    @android.annotation.SuppressLint("MissingPermission")
    fun start(
        onText: (text: String, command: Boolean) -> Unit,
        onLevel: (Float) -> Unit = {},
        onPartial: (String) -> Unit = {},
    ): Boolean {
        val m = model ?: return false
        if (capturing) return true
        return try {
            val rate = 16000
            val rec = Recognizer(m, rate.toFloat(), org.json.JSONArray(GRAMMAR).toString())
            val free = Recognizer(m, rate.toFloat())
            val minBuf = android.media.AudioRecord.getMinBufferSize(
                rate, android.media.AudioFormat.CHANNEL_IN_MONO, android.media.AudioFormat.ENCODING_PCM_16BIT,
            )
            val ar = android.media.AudioRecord(
                android.media.MediaRecorder.AudioSource.VOICE_COMMUNICATION,
                rate, android.media.AudioFormat.CHANNEL_IN_MONO, android.media.AudioFormat.ENCODING_PCM_16BIT,
                maxOf(minBuf, rate), // ~0.5 s
            )
            if (ar.state != android.media.AudioRecord.STATE_INITIALIZED) { ar.release(); rec.close(); free.close(); return false }
            val aec = if (android.media.audiofx.AcousticEchoCanceler.isAvailable())
                runCatching { android.media.audiofx.AcousticEchoCanceler.create(ar.audioSessionId)?.apply { enabled = true } }.getOrNull() else null
            val ns = if (android.media.audiofx.NoiseSuppressor.isAvailable())
                runCatching { android.media.audiofx.NoiseSuppressor.create(ar.audioSessionId)?.apply { enabled = true } }.getOrNull() else null
            Log.i(TAG, "Listening (echo canceller=${aec != null}, noise suppressor=${ns != null})")

            capturing = true
            ar.startRecording()
            captureThread = Thread({
                val buf = ShortArray(2048) // 128 ms
                try {
                    while (capturing) {
                        val n = ar.read(buf, 0, buf.size)
                        if (n <= 0) continue
                        var sum = 0.0
                        for (i in 0 until n) sum += buf[i] * buf[i].toDouble()
                        val level = (kotlin.math.sqrt(sum / n) / 2500.0).toFloat().coerceIn(0f, 1f)
                        main.post { onLevel(level) }
                        if (resetFree) { resetFree = false; free.reset() }
                        if (resetWake) { resetWake = false; rec.reset() }
                        val cmd = commandMode
                        val r = if (cmd) free else rec
                        if (r.acceptWaveForm(buf, n)) {
                            val t = runCatching { JSONObject(r.result).optString("text") }.getOrNull().orEmpty()
                                .replace("[unk]", "").trim()
                            if (t.isNotEmpty()) main.post { onText(t, cmd) }
                        } else if (cmd) {
                            val p = runCatching { JSONObject(r.partialResult).optString("partial") }.getOrNull().orEmpty().trim()
                            if (p.isNotEmpty()) main.post { onPartial(p) }
                        }
                    }
                } catch (e: Exception) {
                    Log.w(TAG, "Capture loop stopped", e)
                } finally {
                    runCatching { ar.stop() }
                    ar.release()
                    aec?.release(); ns?.release()
                    rec.close(); free.close()
                    commandMode = false
                    main.post { onLevel(0f) }
                }
            }, "VoskCapture").apply { start() }
            true
        } catch (e: Exception) {
            Log.w(TAG, "Couldn't start offline listening", e)
            capturing = false
            false
        }
    }

    fun stop() {
        if (!capturing) return
        capturing = false
        commandMode = false
        runCatching { captureThread?.join(1000) } // wait until the mic is really released
        captureThread = null
    }
}
