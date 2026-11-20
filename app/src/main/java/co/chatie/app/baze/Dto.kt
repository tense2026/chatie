package co.chatie.app.baze

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonElement

/* ------------------------------------------------------------------- models */

@Serializable
data class ModelListResponse(val data: List<GatewayModel> = emptyList())

@Serializable
data class GatewayModel(
    val id: String,
    @SerialName("owned_by") val ownedBy: String? = null,
    val type: String? = null,
    @SerialName("profile_image_url") val profileImageUrl: String? = null,
    val created: Long? = null,
)

/* ------------------------------------------------------- chat completions */

@Serializable
data class WireMessage(val role: String, val content: String)

@Serializable
data class ChatRequest(
    val model: String,
    val messages: List<WireMessage>,
    val stream: Boolean = true,
    @SerialName("stream_options") val streamOptions: StreamOptions? = null,
    val temperature: Double? = null,
    @SerialName("max_tokens") val maxTokens: Int? = null,
)

@Serializable
data class StreamOptions(@SerialName("include_usage") val includeUsage: Boolean = true)

@Serializable
data class ChatChunk(
    val choices: List<ChunkChoice> = emptyList(),
    val usage: Usage? = null,
    val credits: Double? = null,
)

@Serializable
data class ChunkChoice(
    val delta: Delta? = null,
    @SerialName("finish_reason") val finishReason: String? = null,
)

@Serializable
data class Delta(
    val content: String? = null,
    @SerialName("reasoning_content") val reasoningContent: String? = null,
)

@Serializable
data class Usage(
    @SerialName("prompt_tokens") val promptTokens: Int? = null,
    @SerialName("completion_tokens") val completionTokens: Int? = null,
    @SerialName("total_tokens") val totalTokens: Int? = null,
)

/* --------------------------------------------------------------- credits */

@Serializable
data class CreditBalance(
    @SerialName("monthly_allocated") val monthlyAllocated: CreditBucket? = null,
    val purchased: CreditBucket? = null,
    @SerialName("org_granted") val orgGranted: CreditBucket? = null,
    val total: CreditBucket? = null,
)

@Serializable
data class CreditBucket(
    val quota: Double? = null,
    val used: Double? = null,
    val remaining: Double? = null,
    @SerialName("renewal_date") val renewalDate: String? = null,
)

/* ----------------------------------------------------------------- errors */

@Serializable
data class ErrorEnvelope(val detail: ErrorDetail? = null)

@Serializable
data class ErrorDetail(
    val code: Int? = null,
    val message: String? = null,
    val data: JsonElement? = null,
)
