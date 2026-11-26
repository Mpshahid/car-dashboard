package com.cardash.dashboard.ai

import com.anthropic.client.AnthropicClient
import com.anthropic.client.okhttp.AnthropicOkHttpClient
import com.anthropic.core.JsonValue
import com.anthropic.errors.BadRequestException
import com.anthropic.models.messages.Message
import com.anthropic.models.messages.ContentBlockParam
import com.anthropic.models.messages.MessageCreateParams
import com.anthropic.models.messages.OutputConfig
import com.anthropic.models.messages.Tool
import com.anthropic.models.messages.ToolResultBlockParam
import com.cardash.dashboard.nav.Http
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject

/** One assistant back-end: answers a driver request, calling the car's tools as needed. */
interface ChatProvider {
    suspend fun reply(
        system: String,
        history: List<Pair<String, String>>,
        user: String,
        tools: List<AiTool>,
        exec: suspend (name: String, argsJson: String) -> String,
        onTool: (name: String) -> Unit = {},
    ): String

    suspend fun listModels(): List<String>

    /** One-shot, no tools, more thinking — used for the full car analysis. */
    suspend fun complete(system: String, user: String): String
}

private const val MAX_TOOL_ROUNDS = 6

/* ---------------------------------------------------------------- Claude (Anthropic Java SDK) */

class ClaudeProvider(apiKey: String, private val model: String) : ChatProvider {
    private val client: AnthropicClient = AnthropicOkHttpClient.builder().apiKey(apiKey).build()

    override suspend fun reply(
        system: String,
        history: List<Pair<String, String>>,
        user: String,
        tools: List<AiTool>,
        exec: suspend (String, String) -> String,
        onTool: (String) -> Unit,
    ): String = withContext(Dispatchers.IO) {
        val b = MessageCreateParams.builder()
            .model(model)
            .maxTokens(4096L)
            .system(system)
        tools.forEach { t -> b.addTool(t.toClaude()) }
        history.forEach { (u, a) -> b.addUserMessage(u); b.addAssistantMessage(a) }
        b.addUserMessage(user)

        repeat(MAX_TOOL_ROUNDS) {
            // Low effort keeps spoken replies quick.
            val resp = create(b, OutputConfig.Effort.LOW)
            val stop = resp.stopReason().map { it.toString() }.orElse("")
            if (stop.equals("refusal", true)) return@withContext "Sorry, I can't help with that one."
            b.addMessage(resp)

            val uses = resp.content().mapNotNull { it.toolUse().orElse(null) }
            if (uses.isEmpty()) {
                return@withContext resp.content().mapNotNull { it.text().orElse(null)?.text() }
                    .joinToString(" ").trim().ifEmpty { "Done." }
            }
            val results = uses.map { tu ->
                onTool(tu.name())
                val args = com.anthropic.core.jsonMapper().writeValueAsString(tu._input())
                ContentBlockParam.ofToolResult(
                    ToolResultBlockParam.builder().toolUseId(tu.id()).content(exec(tu.name(), args)).build(),
                )
            }
            b.addUserMessageOfContentBlockParams(results)
        }
        "Sorry, that took too many steps. Try asking more simply."
    }

    override suspend fun listModels(): List<String> = withContext(Dispatchers.IO) {
        client.models().list().autoPager().take(60).map { it.id() }
    }

    override suspend fun complete(system: String, user: String): String = withContext(Dispatchers.IO) {
        val params = MessageCreateParams.builder()
            .model(model)
            .maxTokens(8000L)
            .system(system)
            .addUserMessage(user)
        // A considered read of the car's data: medium effort.
        val resp = create(params, OutputConfig.Effort.MEDIUM)
        if (resp.stopReason().map { it.toString() }.orElse("").equals("refusal", true)) {
            throw IllegalStateException("The AI declined to analyse this data.")
        }
        resp.content().mapNotNull { it.text().orElse(null)?.text() }.joinToString("\n").trim()
    }

    // Not every Claude model accepts every option (e.g. Haiku 4.5 has no "effort"). Start from what
    // the model id suggests, and if the API still rejects an option, drop it and retry once.
    @Volatile private var useEffort = supportsEffort(model)
    @Volatile private var useFallback = true

    private fun create(b: MessageCreateParams.Builder, effort: OutputConfig.Effort): Message {
        while (true) {
            val p = b.build().toBuilder().apply {
                if (useEffort) outputConfig(OutputConfig.builder().effort(effort).build())
                if (useFallback) {
                    // If a safety classifier declines, let the API retry on a fallback model.
                    putAdditionalHeader("anthropic-beta", "server-side-fallback-2026-07-01")
                    putAdditionalBodyProperty("fallbacks", JsonValue.from("default"))
                }
            }.build()
            try {
                return client.messages().create(p)
            } catch (e: BadRequestException) {
                val msg = e.message.orEmpty().lowercase()
                when {
                    useEffort && "effort" in msg -> useEffort = false
                    useFallback && ("fallback" in msg || "beta" in msg) -> useFallback = false
                    else -> throw e
                }
            }
        }
    }

    private fun supportsEffort(m: String): Boolean {
        val id = m.lowercase()
        return when {
            "haiku" in id || id.startsWith("claude-3") || id.startsWith("claude-2") -> false
            id.startsWith("claude-sonnet-4-5") -> false
            Regex("claude-(opus|sonnet)-4(-[01])?(-\\d{8})?").matches(id) -> false
            else -> true
        }
    }

    private fun AiTool.toClaude(): Tool = Tool.builder()
        .name(name)
        .description(description)
        .inputSchema(
            Tool.InputSchema.builder()
                .properties(
                    Tool.InputSchema.Properties.builder().apply {
                        properties.forEach { (k, v) -> putAdditionalProperty(k, JsonValue.from(v)) }
                    }.build(),
                )
                .required(required)
                .build(),
        )
        .build()
}

/* ---------------------------------------------------------------- OpenAI-compatible (Gemini, OpenRouter, OpenCode, custom) */

class OpenAiCompatProvider(
    private val baseUrl: String,
    private val apiKey: String,
    private val model: String,
) : ChatProvider {

    private fun headers() = buildMap {
        put("Authorization", "Bearer $apiKey")
        if (baseUrl.contains("openrouter.ai")) put("X-Title", "CarDash")
    }

    override suspend fun reply(
        system: String,
        history: List<Pair<String, String>>,
        user: String,
        tools: List<AiTool>,
        exec: suspend (String, String) -> String,
        onTool: (String) -> Unit,
    ): String = withContext(Dispatchers.IO) {
        val messages = JSONArray().put(JSONObject().put("role", "system").put("content", system))
        history.forEach { (u, a) ->
            messages.put(JSONObject().put("role", "user").put("content", u))
            messages.put(JSONObject().put("role", "assistant").put("content", a))
        }
        messages.put(JSONObject().put("role", "user").put("content", user))
        val toolsJson = JSONArray().apply { tools.forEach { put(it.toOpenAi()) } }

        repeat(MAX_TOOL_ROUNDS) {
            val body = JSONObject()
                .put("model", model)
                .put("messages", messages)
                .put("tools", toolsJson)
                .put("tool_choice", "auto")
                .put("max_tokens", 1024)
            val resp = Http.post("$baseUrl/chat/completions", body.toString(), headers())
            if (!resp.ok) throw IllegalStateException(apiError(resp.code, resp.body))
            val msg = JSONObject(resp.body).optJSONArray("choices")?.optJSONObject(0)?.optJSONObject("message")
                ?: throw IllegalStateException("Empty reply from the AI")
            val calls = msg.optJSONArray("tool_calls")
            if (calls == null || calls.length() == 0) {
                return@withContext msg.optString("content").takeIf { it.isNotBlank() && it != "null" } ?: "Done."
            }
            messages.put(msg)
            for (i in 0 until calls.length()) {
                val c = calls.getJSONObject(i)
                val fn = c.optJSONObject("function") ?: continue
                val name = fn.optString("name")
                onTool(name)
                val out = exec(name, fn.optString("arguments", "{}"))
                messages.put(
                    JSONObject().put("role", "tool").put("tool_call_id", c.optString("id")).put("name", name).put("content", out),
                )
            }
        }
        "Sorry, that took too many steps. Try asking more simply."
    }

    override suspend fun complete(system: String, user: String): String = withContext(Dispatchers.IO) {
        val body = JSONObject()
            .put("model", model)
            .put(
                "messages",
                JSONArray()
                    .put(JSONObject().put("role", "system").put("content", system))
                    .put(JSONObject().put("role", "user").put("content", user)),
            )
            .put("max_tokens", 3000)
        val resp = Http.post("$baseUrl/chat/completions", body.toString(), headers())
        if (!resp.ok) throw IllegalStateException(apiError(resp.code, resp.body))
        JSONObject(resp.body).optJSONArray("choices")?.optJSONObject(0)?.optJSONObject("message")
            ?.optString("content").orEmpty().trim()
    }

    override suspend fun listModels(): List<String> = withContext(Dispatchers.IO) {
        val resp = Http.get("$baseUrl/models", headers())
        if (!resp.ok) throw IllegalStateException(apiError(resp.code, resp.body))
        val data = JSONObject(resp.body).optJSONArray("data") ?: return@withContext emptyList()
        (0 until data.length()).mapNotNull { data.optJSONObject(it)?.optString("id")?.removePrefix("models/") }.sorted()
    }

    private fun AiTool.toOpenAi() = JSONObject()
        .put("type", "function")
        .put(
            "function",
            JSONObject()
                .put("name", name)
                .put("description", description)
                .put(
                    "parameters",
                    JSONObject()
                        .put("type", "object")
                        .put("properties", JSONObject(properties))
                        .put("required", JSONArray(required)),
                ),
        )

    private fun apiError(code: Int, body: String): String {
        val msg = runCatching {
            val o = JSONObject(body)
            o.optJSONObject("error")?.optString("message") ?: o.optString("message")
        }.getOrNull().orEmpty()
        return when (code) {
            401, 403 -> "The API key was rejected (HTTP $code). Check it in Setup."
            404 -> "Model or address not found (HTTP 404). Pick a model with List models in Setup."
            429 -> "Rate limited or out of credit (HTTP 429)."
            else -> msg.ifBlank { "AI request failed (HTTP $code)" }
        }
    }
}

fun providerFor(settings: AiSettings, p: AiProvider = settings.provider.value): ChatProvider? {
    if (!settings.ready(p)) return null
    return when (p) {
        AiProvider.CLAUDE -> ClaudeProvider(settings.apiKey(p), settings.model(p))
        else -> OpenAiCompatProvider(settings.baseUrl(p), settings.apiKey(p), settings.model(p))
    }
}
