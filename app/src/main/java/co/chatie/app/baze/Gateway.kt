package co.chatie.app.baze

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.util.concurrent.TimeUnit

/**
 * The BAZE API Gateway: one key, one base URL, many providers.
 *
 * It is a stateless proxy — it keeps no conversations, projects or memories.
 * Everything the model sees is assembled on this device and sent in `messages`,
 * which is exactly why this app can define its own project/memory rules.
 */
class Gateway(
    private val apiKey: () -> String?,
    baseUrl: String = DEFAULT_BASE_URL,
) {
    private val base = baseUrl.trimEnd('/')

    private val json = Json {
        ignoreUnknownKeys = true
        explicitNulls = false
        encodeDefaults = true
    }

    private val client = OkHttpClient.Builder()
        .connectTimeout(20, TimeUnit.SECONDS)
        // Streaming responses stay open while the model thinks: no read timeout.
        .readTimeout(0, TimeUnit.SECONDS)
        .writeTimeout(30, TimeUnit.SECONDS)
        .retryOnConnectionFailure(true)
        .build()

    sealed interface StreamEvent {
        /** A piece of answer text. */
        data class Delta(val text: String) : StreamEvent
        /** A piece of the model's reasoning, where the model exposes it. */
        data class Reasoning(val text: String) : StreamEvent
        /** The stream ended cleanly; usage/credits arrive here when reported. */
        data class Completed(
            val promptTokens: Int?,
            val completionTokens: Int?,
            val credits: Double?,
            val finishReason: String?,
        ) : StreamEvent
    }

    /**
     * POST /chat/completions/ with `stream: true`, parsed as SSE.
     *
     * Deltas are emitted as they arrive; callers are expected to batch them
     * before touching UI state (see ChatViewModel) so a fast model cannot
     * force one recomposition per token.
     */
    fun streamChat(
        model: String,
        messages: List<WireMessage>,
        temperature: Double? = null,
        maxTokens: Int? = null,
    ): Flow<StreamEvent> = flow {
        val key = requireKey()
        val payload = ChatRequest(
            model = model,
            messages = messages,
            stream = true,
            streamOptions = StreamOptions(includeUsage = true),
            temperature = temperature,
            maxTokens = maxTokens,
        )

        val request = Request.Builder()
            .url("$base/chat/completions/")
            .post(json.encodeToString(payload).toRequestBody(JSON_MEDIA))
            .headers(commonHeaders(key))
            .header("Accept", "text/event-stream")
            .build()

        client.newCall(request).execute().use { response ->
            val body = response.body
            if (!response.isSuccessful) {
                val text = body.string()
                throw GatewayError.of(
                    status = response.code,
                    body = text,
                    retryAfter = response.header("Retry-After")?.toIntOrNull(),
                    json = json,
                )
            }

            var promptTokens: Int? = null
            var completionTokens: Int? = null
            var credits: Double? = null
            var finishReason: String? = null

            val source = body.source()
            while (true) {
                val line = source.readUtf8Line() ?: break
                if (line.isEmpty() || line.startsWith(":")) continue
                if (!line.startsWith("data:")) continue

                val data = line.removePrefix("data:").trim()
                if (data == "[DONE]") break

                val chunk = runCatching { json.decodeFromString<ChatChunk>(data) }.getOrNull()
                    ?: continue

                chunk.usage?.let {
                    promptTokens = it.promptTokens ?: promptTokens
                    completionTokens = it.completionTokens ?: completionTokens
                }
                chunk.credits?.let { credits = it }

                val choice = chunk.choices.firstOrNull()
                choice?.finishReason?.let { finishReason = it }
                choice?.delta?.reasoningContent
                    ?.takeIf { it.isNotEmpty() }
                    ?.let { emit(StreamEvent.Reasoning(it)) }
                choice?.delta?.content
                    ?.takeIf { it.isNotEmpty() }
                    ?.let { emit(StreamEvent.Delta(it)) }
            }

            emit(
                StreamEvent.Completed(
                    promptTokens = promptTokens,
                    completionTokens = completionTokens,
                    credits = credits,
                    finishReason = finishReason,
                ),
            )
        }
    }.flowOn(Dispatchers.IO)

    /** GET /models/?type=llm — the chat-capable models this key may address. */
    suspend fun listChatModels(): List<GatewayModel> = withContext(Dispatchers.IO) {
        val key = requireKey()
        val url = "$base/models/".toHttpUrl().newBuilder()
            .addQueryParameter("type", "llm")
            .build()
        val request = Request.Builder().url(url).get().headers(commonHeaders(key)).build()
        client.newCall(request).execute().use { response ->
            val text = response.body.string()
            if (!response.isSuccessful) {
                throw GatewayError.of(
                    response.code,
                    text,
                    response.header("Retry-After")?.toIntOrNull(),
                    json,
                )
            }
            json.decodeFromString<ModelListResponse>(text).data
                .filter { it.type == null || it.type == "llm" }
                .sortedBy { it.id }
        }
    }

    /** GET /credits/ — what is left on the account that owns the key. */
    suspend fun credits(): CreditBalance = withContext(Dispatchers.IO) {
        val key = requireKey()
        val request = Request.Builder()
            .url("$base/credits/")
            .get()
            .headers(commonHeaders(key))
            .build()
        client.newCall(request).execute().use { response ->
            val text = response.body.string()
            if (!response.isSuccessful) {
                throw GatewayError.of(
                    response.code,
                    text,
                    response.header("Retry-After")?.toIntOrNull(),
                    json,
                )
            }
            json.decodeFromString<CreditBalance>(text)
        }
    }

    private fun requireKey(): String =
        apiKey()?.takeIf { it.isNotBlank() }
            ?: throw GatewayError(
                status = 0,
                code = null,
                rawMessage = "missing api key",
                userMessage = "BAZE API 키가 없습니다. 설정에서 키를 넣어주세요.",
            )

    private fun commonHeaders(key: String) = okhttp3.Headers.Builder()
        .add("Authorization", "Bearer $key")
        .add("Content-Type", "application/json")
        // Cloudflare answers 403 "error code: 1010" to an empty User-Agent.
        .add("User-Agent", USER_AGENT)
        .build()

    companion object {
        const val DEFAULT_BASE_URL = "https://factchat-cloud.mindlogic.ai/v1/gateway"
        private const val USER_AGENT = "chatie-android/0.1 (+https://github.com/tense2026/chatie)"
        private val JSON_MEDIA = "application/json; charset=utf-8".toMediaType()

        /**
         * Reasoning models spend the output budget on hidden thinking, so a low
         * cap comes back empty. The docs ask for 16k or more.
         */
        const val REASONING_SAFE_MAX_TOKENS = 16_000

        private val REASONING_HINTS = listOf("gpt-5", "gpt-6", "o1", "o3", "o4", "-pro", "reasoning")

        fun looksLikeReasoningModel(model: String): Boolean {
            val m = model.lowercase()
            return REASONING_HINTS.any { m.contains(it) }
        }

        /** Models that reject any temperature other than 1. */
        private val FIXED_TEMPERATURE_MODELS = setOf(
            "gpt-5", "gpt-5-mini", "gpt-5.1-chat-latest", "gpt-5.2-chat-latest",
        )

        fun acceptsTemperature(model: String): Boolean = model !in FIXED_TEMPERATURE_MODELS
    }
}
