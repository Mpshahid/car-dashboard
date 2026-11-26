package com.cardash.dashboard.ai

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.media.AudioAttributes
import android.os.Build
import android.os.Bundle
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import android.util.Log
import androidx.core.content.ContextCompat
import com.cardash.dashboard.obd.ObdManager
import com.cardash.dashboard.obd.Pid
import com.cardash.dashboard.ui.Fmt
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.util.Locale

/**
 * "Dash" — the in-car voice assistant.
 *
 * Mic button or wake word ("Hey Dash") → Android speech-to-text → offline phrases handled on the
 * phone (dashcam, home/work, stop/mute, quick car readings) → anything else goes to the chosen AI
 * provider with the car's tools → the reply is spoken.
 */
object VoiceAssistant {
    private const val TAG = "VoiceAssistant"

    enum class Phase { IDLE, LISTENING, THINKING, SPEAKING, ERROR }

    data class State(
        val phase: Phase = Phase.IDLE,
        val heard: String = "",
        val reply: String = "",
        val detail: String = "",
        val wakeListening: Boolean = false,
        val needsMicPermission: Boolean = false,
        /** Live mic loudness 0..1 while listening (drives the mic animation). */
        val level: Float = 0f,
    )

    private fun rmsToLevel(rmsdB: Float) = ((rmsdB + 2f) / 12f).coerceIn(0f, 1f)

    private val _state = MutableStateFlow(State())
    val state: StateFlow<State> = _state.asStateFlow()

    lateinit var settings: AiSettings
        private set
    private lateinit var app: Context
    private lateinit var tools: AssistantTools
    private var initialised = false
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    private var tts: TextToSpeech? = null
    private var ttsReady = false
    private var commandRec: SpeechRecognizer? = null
    private var wakeRec: SpeechRecognizer? = null
    private var foreground = false
    private var handleJob: Job? = null
    private var idleJob: Job? = null
    private val history = ArrayDeque<Pair<String, String>>()

    // Speech-to-text often hears "Dash" as dosh/dush/tash/dashy — accept those after "hey"/"ok".
    /**
     * Wake word "Dash" (optionally "hey/ok Dash"). Online dictation may render a spoken "dash" as
     * the punctuation "-"/"–", so a leading dash mark counts too.
     */
    private val WAKE = Regex(
        """(?:^|\b(?:hey|hi|hay|ok|okay)\s*)(?:[-–—]+(?=\s|$)|\b(?:dash|dashboard|dash board|dashy|dosh|dush)\b)""",
        RegexOption.IGNORE_CASE,
    )

    /** After "Dash", listen for a command for this long, then go back to waiting. */
    private const val ACTIVE_WINDOW_MS = 10_000L
    private var windowUntil = 0L

    /**
     * Wake-word recogniser to try: 0 = on-device en-GB, 1 = on-device en-US, 2 = Google online.
     * Falls through when the phone lacks the offline language pack.
     */
    private var wakeMode = 0

    fun init(context: Context) {
        if (initialised) return
        initialised = true
        app = context.applicationContext
        settings = AiSettings(app)
        tools = AssistantTools(app)
        tts = TextToSpeech(app) { status ->
            if (status == TextToSpeech.SUCCESS) {
                tts?.language = Locale.UK
                tts?.setAudioAttributes(
                    AudioAttributes.Builder()
                        .setUsage(AudioAttributes.USAGE_ASSISTANT)
                        .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                        .build(),
                )
                tts?.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
                    override fun onStart(id: String?) {}
                    override fun onDone(id: String?) { scope.launch { finishSoon() } }
                    @Deprecated("Deprecated in Java")
                    override fun onError(id: String?) { scope.launch { finishSoon() } }
                })
                ttsReady = true
            }
        }
        scope.launch { settings.wakeWord.collect { syncWake() } }
        VoskWake.init(app)
        VoskWake.onReady = { stopGoogleWake(); syncWake() }
        // Duck the music while Dash is listening, thinking or speaking; restore it when idle.
        scope.launch {
            var last: Phase? = null
            state.collect { st -> if (st.phase != last) { last = st.phase; duck(st.phase != Phase.IDLE) } }
        }
    }

    private var focusRequest: android.media.AudioFocusRequest? = null

    private fun duck(on: Boolean) {
        val am = app.getSystemService(Context.AUDIO_SERVICE) as android.media.AudioManager
        if (on && focusRequest == null) {
            val req = android.media.AudioFocusRequest.Builder(android.media.AudioManager.AUDIOFOCUS_GAIN_TRANSIENT_MAY_DUCK)
                .setAudioAttributes(
                    AudioAttributes.Builder()
                        .setUsage(AudioAttributes.USAGE_ASSISTANT)
                        .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                        .build(),
                )
                .build()
            if (am.requestAudioFocus(req) == android.media.AudioManager.AUDIOFOCUS_REQUEST_GRANTED) focusRequest = req
        } else if (!on) {
            focusRequest?.let { am.abandonAudioFocusRequest(it) }
            focusRequest = null
        }
    }

    private fun chime() {
        runCatching {
            android.media.ToneGenerator(android.media.AudioManager.STREAM_MUSIC, 55)
                .startTone(android.media.ToneGenerator.TONE_PROP_ACK, 150)
        }
    }

    // ------------------------------------------------------------ lifecycle

    /**
     * The wake word only runs while the app is on screen. Counted, because when Android recreates
     * the activity the old one's ON_STOP can arrive after the new one's ON_START.
     */
    private var startedScreens = 0

    fun setForeground(fg: Boolean) {
        startedScreens = (startedScreens + if (fg) 1 else -1).coerceAtLeast(0)
        val now = startedScreens > 0
        if (now == foreground) return
        foreground = now
        if (!now) cancel()
        syncWake()
    }

    private fun micAllowed() =
        ContextCompat.checkSelfPermission(app, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED

    // ------------------------------------------------------------ listening (command)

    /** Start listening for one command (mic button, or after the wake word). */
    private var windowJob: Job? = null
    private var googleRetries = 0

    /**
     * Start listening for one command (mic button, or after the wake word).
     * [windowMs] > 0 (wake word): keep listening through silence until the window ends, then stop quietly.
     */
    /** Mute: Dash stops listening for "Dash" entirely (the mic is released). Same switch as "Hey Dash" in Setup. */
    fun setMuted(muted: Boolean) {
        if (muted) cancel()
        settings.setWakeWord(!muted)
    }

    fun listen(windowMs: Long = 0L) {
        if (!initialised) return
        // Offline engine installed: use it for the command too (no recogniser handover).
        if (VoskWake.ready && micAllowed()) { startVoskWindow(if (windowMs > 0) windowMs else ACTIVE_WINDOW_MS); return }
        googleRetries = 0
        if (windowMs > 0) {
            windowUntil = android.os.SystemClock.elapsedRealtime() + windowMs
            windowJob?.cancel()
            windowJob = scope.launch {
                delay(windowMs)
                // Window over and nothing said: stop and go back to waiting for "Dash".
                if (_state.value.phase == Phase.LISTENING && _state.value.heard.isBlank()) closeWindow()
            }
        }
        startCommandRecognizer()
    }

    private fun inWindow() = android.os.SystemClock.elapsedRealtime() < windowUntil

    private fun closeWindow() {
        windowUntil = 0L
        windowJob?.cancel()
        commandRec?.cancel()
        _state.update { it.copy(phase = Phase.IDLE, heard = "", reply = "", detail = "", level = 0f) }
        syncWake()
    }

    private fun startCommandRecognizer() {
        if (!micAllowed()) { _state.update { it.copy(phase = Phase.ERROR, needsMicPermission = true, reply = "Allow the microphone to use voice control.") }; return }
        if (!SpeechRecognizer.isRecognitionAvailable(app)) { error("Speech recognition isn't available on this phone."); return }
        stopWake()
        tts?.stop()
        handleJob?.cancel()
        idleJob?.cancel()
        commandRec?.destroy()
        commandRec = SpeechRecognizer.createSpeechRecognizer(app).apply {
            setRecognitionListener(object : SimpleListener() {
                override fun onRmsChanged(rmsdB: Float) { _state.update { it.copy(level = rmsToLevel(rmsdB)) } }
                override fun onEndOfSpeech() { _state.update { it.copy(level = 0f) } }
                override fun onPartialResults(b: Bundle?) { text(b)?.let { t -> _state.update { it.copy(heard = t) } } }
                override fun onResults(b: Bundle?) {
                    val t = text(b).orEmpty()
                    when {
                        t.isNotBlank() -> { windowUntil = 0L; windowJob?.cancel(); handle(t) }
                        inWindow() -> startCommandRecognizer()
                        windowUntil > 0 -> closeWindow()
                        else -> error("I didn't catch that.")
                    }
                }
                override fun onError(error: Int) {
                    Log.i(TAG, "Command recogniser error $error (inWindow=${inWindow()})")
                    // 11 = service disconnected, 5 = client error: transient — retry quietly once.
                    if ((error == 11 || error == SpeechRecognizer.ERROR_CLIENT) && googleRetries < 1) {
                        googleRetries++
                        scope.launch { delay(400); startCommandRecognizer() }
                        return
                    }
                    val silence = error == SpeechRecognizer.ERROR_NO_MATCH || error == SpeechRecognizer.ERROR_SPEECH_TIMEOUT
                    if (silence && inWindow()) { startCommandRecognizer(); return } // keep listening within the 10 s
                    if (silence && windowUntil > 0) { closeWindow(); return }        // window over: stop quietly
                    error(
                        when (error) {
                            SpeechRecognizer.ERROR_NO_MATCH, SpeechRecognizer.ERROR_SPEECH_TIMEOUT -> "I didn't catch that."
                            SpeechRecognizer.ERROR_NETWORK, SpeechRecognizer.ERROR_NETWORK_TIMEOUT -> "Speech recognition needs a connection."
                            SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS -> "Allow the microphone to use voice control."
                            else -> "Voice input failed (code $error)."
                        },
                    )
                }
            })
            startListening(recognizerIntent())
        }
        _state.update { it.copy(phase = Phase.LISTENING, heard = "", reply = "", detail = "", needsMicPermission = false) }
    }

    /** Something short to say out loud instead of a raw API error. */
    private fun friendlyError(e: Exception): String {
        val m = (e.message ?: "").lowercase()
        return when {
            "401" in m || "authentication" in m || "api key" in m || "unauthorized" in m ->
                "Sorry, the AI key was rejected. Check it in Setup."
            "404" in m || "not_found" in m || ("model" in m && "not" in m && "found" in m) ->
                "Sorry, that AI model isn't available. Pick another one in Setup."
            "429" in m || "rate" in m || "quota" in m || "credit" in m || "billing" in m ->
                "Sorry, the AI account is out of credit or busy. Try again later."
            "timeout" in m || "unable to resolve" in m || "failed to connect" in m || "unknownhost" in m ->
                "Sorry, I can't reach the AI right now. Check the internet connection."
            else -> "Sorry, the AI couldn't answer that."
        }
    }

    fun cancel() {
        windowUntil = 0L
        windowJob?.cancel()
        VoskWake.exitCommandMode()
        handleJob?.cancel()
        commandRec?.cancel()
        tts?.stop()
        _state.update { it.copy(phase = Phase.IDLE, heard = "", reply = "", detail = "") }
        syncWake()
    }

    fun dismissPermissionNote() = _state.update { it.copy(needsMicPermission = false, phase = Phase.IDLE) }

    // ------------------------------------------------------------ handling

    /** Handle a typed or spoken request. */
    fun handle(text: String) {
        handleJob?.cancel()
        Log.i(TAG, "Command: \"$text\"")
        stopWake() // pause listening while Dash thinks and speaks; it restarts when idle
        _state.update { it.copy(phase = Phase.THINKING, heard = text, reply = "", detail = "", level = 0f) }
        handleJob = scope.launch {
            val reply = try {
                offline(text) ?: askAi(text)
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (e: Exception) {
                Log.w(TAG, "Assistant failed", e)
                friendlyError(e)
            }
            history.addLast(text to reply)
            while (history.size > 4) history.removeFirst()
            speak(reply)
        }
    }

    private suspend fun askAi(text: String): String {
        val p = settings.provider.value
        val provider = providerFor(settings, p)
            ?: return "I can do that with an AI provider. Add a ${p.label} API key in Setup, voice assistant."
        _state.update { it.copy(detail = "Asking ${p.label}…") }
        return provider.reply(
            system = SYSTEM_PROMPT,
            history = history.toList(),
            user = text,
            tools = tools.all,
            exec = { name, args -> tools.execute(name, args) },
            onTool = { name -> _state.update { it.copy(detail = toolLabel(name)) } },
        )
    }

    /** Fixed phrases that work without any AI (and mostly without internet). */
    private suspend fun offline(raw: String): String? {
        val t = raw.lowercase(Locale.UK).trim().trimEnd('.', '!', '?')
        fun has(re: String) = Regex(re).containsMatchIn(t)
        val ui = tools.prefs()
        val s = ObdManager.state.value

        return when {
            // Music
            has("""^(pause|stop)(\s+(the\s+)?(music|song|track|playback))?$""") -> tools.music("pause")
            has("""^(resume|play|played|plays|continue|start)(\s+(the|some|my|a)?\s*(music|song|track|songs|tunes))?$|^unpause|^play something$""") -> tools.music("play")
            has("""^(next|skip)(\s+(this\s+)?(song|track|one))?$""") -> tools.music("next")
            has("""^(previous|last|go back)(\s+(song|track))?$""") -> tools.music("previous")
            has("""what('?s| is)\s+(this\s+song|playing|this track)""") -> tools.music("status")
            has("""^(play|played|plays)\s+(.+)""") -> tools.music(
                "search_and_play",
                t.replace(Regex("""^(play|played|plays)\s+"""), "").removeSuffix(" on youtube music").removeSuffix(" on youtube"),
            )
            // Dashcam
            has("""^(start|begin)\s+(recording|the dashcam|dashcam|the camera)|^record\b|dashcam on""") -> tools.dashcam("start")
            has("""^(stop|end)\s+(recording|the dashcam|dashcam|the camera)|dashcam off""") -> tools.dashcam("stop")
            has("""^(take me|navigate|go|drive|directions|get me)\s*(back\s+)?(to\s+)?home$""") -> tools.navigateTo("home")
            has("""^(take me|navigate|go|drive|directions|get me)\s+(to\s+)?work$""") -> tools.navigateTo("work")
            // Navigation is handed to the Google Maps app (opens beside the dashboard).
            has("""^(open|show)\s+(google\s+)?maps$""") ->
                if (com.cardash.dashboard.nav.NavManager.openGoogleMaps()) "Opening Google Maps." else "Google Maps isn't installed."
            has("""^(navigate|take me|directions|drive|go|get me)\s+to\s+(.+)""") ->
                tools.navigateTo(Regex("""to\s+(.+)$""").find(t)?.groupValues?.get(1).orEmpty())
            has("""^(find|show me|show|search for)\s+(the\s+)?(nearest|closest|nearby)?\s*(.+)""") -> {
                val q = Regex("""^(find|show me|show|search for)\s+(the\s+)?(nearest|closest|nearby)?\s*""").replace(t, "")
                    .removeSuffix(" nearby").trim()
                tools.findNearby(q)
            }
            has("""(what'?s|what is)\s+(my\s+)?speed|how fast""") ->
                s.value(Pid.SPEED)?.let { "You're doing ${Fmt.speedValue(it, ui.imperial)} ${Fmt.speedUnit(ui.imperial)}." } ?: "No speed reading yet."
            has("""coolant|engine temp""") ->
                s.value(Pid.COOLANT_TEMP)?.let { "Coolant is ${Fmt.reading(Pid.COOLANT_TEMP, it, ui)} ${Fmt.unit(Pid.COOLANT_TEMP, ui)}." } ?: "No coolant reading yet."
            has("""fuel level|how much fuel|fuel left""") ->
                s.value(Pid.FUEL_LEVEL)?.let { "Fuel is at ${it.toInt()} percent." } ?: "The car isn't reporting fuel level."
            has("""(car\s+)?battery voltage|car battery""") ->
                s.value(Pid.MODULE_VOLTAGE)?.let { "Car battery is %.1f volts.".format(it) } ?: "No battery voltage reading yet."
            has("""\brpm\b|revs""") ->
                s.value(Pid.ENGINE_RPM)?.let { "Engine is at ${it.toInt()} RPM." } ?: "No RPM reading yet."
            else -> null
        }
    }

    // ------------------------------------------------------------ speaking

    private fun speak(text: String) {
        val clean = text.replace(Regex("""[*#_`>]"""), "").replace(Regex("""\s+"""), " ").trim()
        Log.i(TAG, "Reply: \"$clean\"")
        _state.update { it.copy(phase = Phase.SPEAKING, reply = clean, detail = "") }
        if (settings.speak.value && ttsReady) {
            tts?.speak(clean, TextToSpeech.QUEUE_FLUSH, null, "dash")
        } else {
            scope.launch { finishSoon() }
        }
    }

    /** Keep the reply on screen briefly after speaking, then go back to idle / wake listening. */
    private suspend fun finishSoon() {
        idleJob?.cancel()
        idleJob = scope.launch {
            delay(4000)
            if (_state.value.phase == Phase.SPEAKING) _state.update { it.copy(phase = Phase.IDLE) }
            syncWake()
        }
    }

    private fun error(msg: String) {
        _state.update { it.copy(phase = Phase.ERROR, reply = msg) }
        scope.launch { delay(3500); if (_state.value.phase == Phase.ERROR) _state.update { it.copy(phase = Phase.IDLE) }; syncWake() }
    }

    // ------------------------------------------------------------ wake word ("Hey Dash")

    private fun syncWake() {
        if (!initialised) return
        val want = settings.wakeWord.value && foreground && micAllowed() && _state.value.phase == Phase.IDLE
        Log.d(TAG, "syncWake want=$want vosk=${VoskWake.ready} running=${wakeRec != null || VoskWake.running}")
        // Never cut the mic during an offline command window.
        if (VoskWake.commandMode && _state.value.phase == Phase.LISTENING && foreground) return
        if (!want) { if (wakeRec != null || VoskWake.running) stopWake(); return }
        // Offline model still loading (takes ~2 s at launch): wait for it rather than starting Google too.
        if (VoskWake.state.value.status == VoskWake.Status.LOADING) return
        if (VoskWake.ready) {
            // Offline engine: continuous, on-device, no restart gaps.
            if (wakeRec != null) stopGoogleWake()
            if (!VoskWake.running) {
                val ok = startVosk()
                _state.update { it.copy(wakeListening = ok) }
                if (!ok && wakeRec == null) startWake() // fall back to Google if the mic couldn't open
            }
        } else if (wakeRec == null) {
            startWake()
        }
    }

    private fun startWake() {
        if (!SpeechRecognizer.isRecognitionAvailable(app)) return
        val onDeviceOk = Build.VERSION.SDK_INT >= 31 && SpeechRecognizer.isOnDeviceRecognitionAvailable(app)
        if (!onDeviceOk && wakeMode < 2) wakeMode = 2
        val rec = if (wakeMode < 2) SpeechRecognizer.createOnDeviceSpeechRecognizer(app) else SpeechRecognizer.createSpeechRecognizer(app)
        val lang = if (wakeMode == 1) "en-US" else "en-GB"
        wakeRec = rec
        _state.update { it.copy(wakeListening = true) }
        var ready = false
        rec.setRecognitionListener(object : SimpleListener() {
            override fun onReadyForSpeech(params: Bundle?) { if (wakeRec === rec) ready = true }
            // Let the idle mic button flicker with your voice so you can see it's listening for "Hey Dash".
            override fun onRmsChanged(rmsdB: Float) {
                if (_state.value.phase == Phase.IDLE) _state.update { it.copy(level = rmsToLevel(rmsdB)) }
            }
            override fun onPartialResults(b: Bundle?) { if (wakeRec === rec) checkWake(text(b), final = false) }
            override fun onResults(b: Bundle?) { if (wakeRec === rec && !checkWake(text(b), final = true)) restartWake() }
            override fun onError(error: Int) {
                if (wakeRec !== rec) return // a stale listener (e.g. replaced by the offline engine)
                Log.i(TAG, "Wake listener error $error (mode $wakeMode, ready=$ready)")
                when (error) {
                    // 12 = language not supported, 13 = language pack missing: use Google's online recogniser.
                    12, 13 -> if (wakeMode < 2) {
                        wakeMode = 2
                        Log.i(TAG, "Offline wake recogniser unavailable (error $error); using online recogniser")
                        restartWake(100)
                    } else restartWake(5000)
                    10 -> restartWake(4000) // too many requests
                    SpeechRecognizer.ERROR_RECOGNIZER_BUSY -> restartWake(1500)
                    SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS -> stopWake()
                    else -> restartWake(60) // keep the gap short so the start of a phrase isn't lost
                }
            }
        })
        val intent = recognizerIntent(preferOffline = wakeMode < 2).apply {
            putExtra(RecognizerIntent.EXTRA_LANGUAGE, lang)
            // Android 13+: nudge the recogniser towards our wake phrase.
            if (Build.VERSION.SDK_INT >= 33) {
                putStringArrayListExtra(RecognizerIntent.EXTRA_BIASING_STRINGS, arrayListOf("hey dash", "dash", "hey dashboard"))
            }
        }
        runCatching { rec.startListening(intent) }.onFailure { Log.w(TAG, "Wake start failed", it); stopWake() }
        // Watchdog: some recognisers never start and never report an error — don't stall silently.
        scope.launch {
            delay(4000)
            if (wakeRec === rec && !ready) {
                if (wakeMode < 2) wakeMode = 2
                Log.i(TAG, "Wake recogniser didn't start; retrying with mode $wakeMode")
                restartWake(100)
            }
        }
    }

    /** Returns true if the wake word was heard (and a command is now being handled). */
    private fun checkWake(heard: String?, final: Boolean): Boolean {
        // Nothing is acted on until the wake word "Dash" is heard.
        val h = heard ?: return false
        if (final) Log.d(TAG, "Wake listener heard: \"$h\"")
        val m = WAKE.find(h) ?: return false
        Log.i(TAG, "Wake word detected")
        val rest = h.substring(m.range.last + 1).trim().trimStart(',', '.', '-', '–', '—').trim()
        if (!final && rest.isNotEmpty()) return false // wait for the full sentence
        // "Dash, next song" in one breath → act on it; just "Dash" → chime and listen for 10 s.
        if (rest.split(" ").size >= 2) { stopWake(); handle(rest); return true }
        chime()
        if (VoskWake.running) startVoskWindow(ACTIVE_WINDOW_MS)   // same mic stream, no handover
        else { stopWake(); listen(windowMs = ACTIVE_WINDOW_MS) }
        return true
    }

    private fun restartWake(delayMs: Long = 60) {
        stopWake()
        scope.launch { delay(delayMs); syncWake() }
    }

    /** Start the offline mic stream: wake-word mode, switching to command mode for the 10 s window. */
    private fun startVosk(): Boolean = VoskWake.start(
        onText = { t, command ->
            Log.d(TAG, "Vosk heard${if (command) " (command)" else ""}: \"$t\"")
            if (command) onVoskCommand(t) else checkWake(t, final = true)
        },
        onLevel = { l ->
            val p = _state.value.phase
            if (p == Phase.IDLE || p == Phase.LISTENING) _state.update { it.copy(level = l) }
        },
        onPartial = { p -> if (_state.value.phase == Phase.LISTENING) _state.update { it.copy(heard = p) } },
    )

    /** "Dash" (or the mic button) with the offline engine: listen for a command on the same mic stream. */
    private fun startVoskWindow(windowMs: Long) {
        if (!VoskWake.running && !startVosk()) { startCommandRecognizer(); return }
        tts?.stop()
        handleJob?.cancel()
        idleJob?.cancel()
        VoskWake.enterCommandMode()
        windowUntil = android.os.SystemClock.elapsedRealtime() + windowMs
        _state.update { it.copy(phase = Phase.LISTENING, heard = "", reply = "", detail = "", needsMicPermission = false, wakeListening = true) }
        windowJob?.cancel()
        windowJob = scope.launch {
            delay(windowMs)
            // Still mid-sentence? give it a moment to finish, then stop quietly.
            if (_state.value.phase == Phase.LISTENING && _state.value.heard.isNotBlank()) delay(3000)
            if (_state.value.phase == Phase.LISTENING) {
                VoskWake.exitCommandMode()
                windowUntil = 0L
                _state.update { it.copy(phase = Phase.IDLE, heard = "", level = 0f) }
                syncWake()
            }
        }
    }

    private fun onVoskCommand(text: String) {
        if (_state.value.phase != Phase.LISTENING) return
        VoskWake.exitCommandMode()
        windowUntil = 0L
        windowJob?.cancel()
        handle(text)
    }

    private fun stopWake() {
        stopGoogleWake()
        VoskWake.stop()
        _state.update { it.copy(wakeListening = false, level = 0f) }
    }

    private fun stopGoogleWake() {
        wakeRec?.let { runCatching { it.cancel() }; runCatching { it.destroy() } }
        wakeRec = null
    }

    // ------------------------------------------------------------ helpers

    private fun recognizerIntent(preferOffline: Boolean = false) = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
        putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
        putExtra(RecognizerIntent.EXTRA_LANGUAGE, "en-GB")
        putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true)
        putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 1)
        if (preferOffline) putExtra(RecognizerIntent.EXTRA_PREFER_OFFLINE, true)
    }

    private fun text(b: Bundle?): String? =
        b?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)?.firstOrNull()?.takeIf { it.isNotBlank() }

    private fun toolLabel(name: String) = when (name) {
        "navigate_to" -> "Opening Google Maps…"
        "find_nearby" -> "Searching in Google Maps…"
        "car_status" -> "Reading the car…"
        "dashcam" -> "Dashcam…"
        "music" -> "Music…"
        else -> "Working…"
    }

    private open class SimpleListener : RecognitionListener {
        override fun onReadyForSpeech(params: Bundle?) {}
        override fun onBeginningOfSpeech() {}
        override fun onRmsChanged(rmsdB: Float) {}
        override fun onBufferReceived(buffer: ByteArray?) {}
        override fun onEndOfSpeech() {}
        override fun onError(error: Int) {}
        override fun onResults(results: Bundle?) {}
        override fun onPartialResults(partialResults: Bundle?) {}
        override fun onEvent(eventType: Int, params: Bundle?) {}
    }

    private val SYSTEM_PROMPT = """
        You are Dash, the voice assistant built into the driver's car dashboard app (a 2012 Vauxhall Meriva, UK).
        The driver is driving and hears your reply through the speaker, so answer in one or two short spoken
        sentences: plain words, no lists, no markdown, no emojis, no URLs.
        Use the tools to act (directions and nearby search in the Google Maps app, music, dashcam) and to read live
        car data. Never guess car values: call car_status. For "take me to the nearest X", call navigate_to with
        "nearest X". Google Maps gives the spoken turn-by-turn directions, so don't read routes out yourself.
        If car data says DEMO MODE, say the figures are simulated. Use the units the tools give you.
        If something isn't possible from the car, say so briefly. Keep the driver's attention on the road.
    """.trimIndent()
}
