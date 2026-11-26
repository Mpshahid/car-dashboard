package com.cardash.dashboard.ai

import android.content.Context
import android.content.SharedPreferences
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKeys
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/** AI back-ends the voice assistant can use. All but Claude speak the OpenAI chat-completions API. */
enum class AiProvider(
    val label: String,
    val defaultBaseUrl: String,
    val defaultModel: String,
    val keyHint: String,
) {
    CLAUDE("Claude", "", "claude-opus-5-5", "sk-ant-…  (console.anthropic.com)"),
    GEMINI("Gemini", "https://generativelanguage.googleapis.com/v1beta/openai", "gemini-2.5-flash", "AIza…  (aistudio.google.com)"),
    OPENROUTER("OpenRouter", "https://openrouter.ai/api/v1", "openrouter/auto", "sk-or-…  (openrouter.ai)"),
    OPENCODE("OpenCode", "https://opencode.ai/zen/v1", "", "OpenCode Zen key  (opencode.ai)"),
    CUSTOM("Custom", "", "", "API key for your OpenAI-compatible service"),
}

/**
 * Voice-assistant settings. API keys live in EncryptedSharedPreferences on this phone only
 * and are only ever sent to the chosen provider.
 */
class AiSettings(context: Context) {
    private val plain = context.getSharedPreferences("cardash_ai", Context.MODE_PRIVATE)
    private val secure: SharedPreferences = runCatching {
        EncryptedSharedPreferences.create(
            "cardash_ai_keys",
            MasterKeys.getOrCreate(MasterKeys.AES256_GCM_SPEC),
            context,
            EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
            EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM,
        )
    }.getOrElse { context.getSharedPreferences("cardash_ai_keys_fallback", Context.MODE_PRIVATE) }

    private val _provider = MutableStateFlow(
        runCatching { AiProvider.valueOf(plain.getString("provider", null) ?: "") }.getOrDefault(AiProvider.CLAUDE),
    )
    val provider: StateFlow<AiProvider> = _provider.asStateFlow()

    private val _wakeWord = MutableStateFlow(plain.getBoolean("wake", true))
    val wakeWord: StateFlow<Boolean> = _wakeWord.asStateFlow()

    private val _speak = MutableStateFlow(plain.getBoolean("speak", true))
    val speak: StateFlow<Boolean> = _speak.asStateFlow()

    /** Bumped whenever a key/model/url changes so the UI can refresh. */
    private val _rev = MutableStateFlow(0)
    val rev: StateFlow<Int> = _rev.asStateFlow()

    fun setProvider(p: AiProvider) { plain.edit().putString("provider", p.name).apply(); _provider.value = p }
    fun setWakeWord(v: Boolean) { plain.edit().putBoolean("wake", v).apply(); _wakeWord.value = v }
    fun setSpeak(v: Boolean) { plain.edit().putBoolean("speak", v).apply(); _speak.value = v }

    fun apiKey(p: AiProvider): String = secure.getString("key_${p.name}", "").orEmpty()
    fun setApiKey(p: AiProvider, key: String) { secure.edit().putString("key_${p.name}", key.trim()).apply(); _rev.value++ }

    fun model(p: AiProvider): String = plain.getString("model_${p.name}", null) ?: p.defaultModel
    fun setModel(p: AiProvider, m: String) { plain.edit().putString("model_${p.name}", m.trim()).apply(); _rev.value++ }

    fun baseUrl(p: AiProvider): String = (plain.getString("url_${p.name}", null) ?: p.defaultBaseUrl).trimEnd('/')
    fun setBaseUrl(p: AiProvider, u: String) { plain.edit().putString("url_${p.name}", u.trim()).apply(); _rev.value++ }

    /** True when the current provider has what it needs to answer free-form requests. */
    fun ready(p: AiProvider = provider.value): Boolean =
        apiKey(p).isNotBlank() && model(p).isNotBlank() && (p == AiProvider.CLAUDE || baseUrl(p).isNotBlank())
}
