package co.chatie.app.ui.chat

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import co.chatie.app.baze.Gateway
import co.chatie.app.baze.GatewayError
import co.chatie.app.context.ContextAssembler
import co.chatie.app.data.ChatRepository
import co.chatie.app.data.Conversation
import co.chatie.app.data.Message
import co.chatie.app.data.MessageStatus
import co.chatie.app.data.Role
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Owns one conversation's send/stream loop.
 *
 * The thing that keeps this smooth: tokens arrive far faster than frames, so
 * deltas are appended to a plain StringBuilder and published to Compose state
 * at most once per [FLUSH_INTERVAL_MS]. A model emitting 80 tokens a second
 * therefore causes ~20 recompositions a second, not 80 — and only the one
 * streaming bubble recomposes, because the committed messages live in a
 * separate state object that does not change while a stream runs.
 */
class ChatViewModel(
    private val repo: ChatRepository,
    private val assembler: ContextAssembler,
    private val gateway: Gateway,
    val conversationId: String,
) : ViewModel() {

    data class Streaming(
        val messageId: String,
        val text: String,
        val reasoning: String,
        val waitingForFirstToken: Boolean,
    )

    /** Messages already written to SQLite. Replaced wholesale on change. */
    var messages by mutableStateOf<List<Message>>(emptyList())
        private set

    /** The in-flight answer. Only the last bubble reads this. */
    var streaming by mutableStateOf<Streaming?>(null)
        private set

    var conversation by mutableStateOf<Conversation?>(null)
        private set

    /** A banner-worthy problem: key missing, credits out, rate limited. */
    var errorMessage by mutableStateOf<String?>(null)
        private set

    /** Layer summary for the context chip above the composer. */
    var contextSummary by mutableStateOf<ContextSummary?>(null)
        private set

    data class ContextSummary(
        val includedLayers: List<String>,
        val historySent: Int,
        val historyOmitted: Int,
        val totalChars: Int,
        val projectName: String?,
    )

    private var streamJob: Job? = null
    private val buffer = StringBuilder()
    private val reasoningBuffer = StringBuilder()

    val isStreaming: Boolean get() = streamJob?.isActive == true

    init {
        reload()
    }

    fun reload() {
        conversation = repo.getConversation(conversationId)
        messages = repo.listMessages(conversationId)
        refreshContextSummary()
    }

    fun refreshContextSummary() {
        val ctx = assembler.assemble(conversationId) ?: return
        contextSummary = ContextSummary(
            includedLayers = ctx.layers.filter { it.included }.map { it.label },
            historySent = ctx.history.sent.size,
            historyOmitted = ctx.history.omitted,
            totalChars = ctx.totalChars,
            projectName = ctx.project?.name,
        )
    }

    fun dismissError() {
        errorMessage = null
    }

    /** Send a new user turn and stream the answer. */
    fun send(text: String) {
        val trimmed = text.trim()
        if (trimmed.isEmpty() || isStreaming) return

        val conv = repo.getConversation(conversationId) ?: return
        repo.insertMessage(conversationId, Role.USER, trimmed)
        if (conv.title.isBlank()) {
            repo.updateConversation(conversationId, title = deriveTitle(trimmed))
        }
        reload()
        runTurn(conv.model)
    }

    /**
     * Drop the last assistant turn and ask again. Used after an error and for
     * "regenerate", so a failed turn never lingers in the history that gets
     * sent back to the model.
     */
    fun retryLast() {
        if (isStreaming) return
        val conv = repo.getConversation(conversationId) ?: return
        val all = repo.listMessages(conversationId)
        val lastAssistant = all.lastOrNull { it.role == Role.ASSISTANT }
        if (lastAssistant != null && all.lastOrNull()?.id == lastAssistant.id) {
            repo.deleteMessage(lastAssistant.id)
        }
        reload()
        if (messages.none { it.role == Role.USER }) return
        runTurn(conv.model)
    }

    fun stop() {
        streamJob?.cancel()
    }

    fun changeModel(model: String) {
        repo.updateConversation(conversationId, model = model)
        conversation = repo.getConversation(conversationId)
    }

    private fun runTurn(model: String) {
        errorMessage = null
        buffer.setLength(0)
        reasoningBuffer.setLength(0)

        val ctx = assembler.assemble(conversationId)
        if (ctx == null) {
            errorMessage = "대화를 불러올 수 없습니다."
            return
        }
        val wire = ctx.toWireMessages()
        contextSummary = ContextSummary(
            includedLayers = ctx.layers.filter { it.included }.map { it.label },
            historySent = ctx.history.sent.size,
            historyOmitted = ctx.history.omitted,
            totalChars = ctx.totalChars,
            projectName = ctx.project?.name,
        )

        // The assistant row exists in the database from the first frame, so a
        // crash or a kill mid-stream leaves a visible partial answer, not a gap.
        val placeholder = repo.insertMessage(
            conversationId = conversationId,
            role = Role.ASSISTANT,
            content = "",
            model = model,
            status = MessageStatus.STREAMING,
        )
        messages = repo.listMessages(conversationId)
        streaming = Streaming(placeholder.id, "", "", waitingForFirstToken = true)

        streamJob = viewModelScope.launch {
            var lastCheckpoint = System.currentTimeMillis()
            var dirty = false

            fun publish() {
                if (!dirty) return
                dirty = false
                streaming = Streaming(
                    messageId = placeholder.id,
                    text = buffer.toString(),
                    reasoning = reasoningBuffer.toString(),
                    waitingForFirstToken = buffer.isEmpty() && reasoningBuffer.isEmpty(),
                )
            }

            // The only writer of Compose state for this stream. Deltas just mark
            // the buffer dirty; this ticker publishes at a steady frame-friendly
            // rate, which also bounds how long a lone delta can sit unseen.
            val ticker = launch {
                while (true) {
                    kotlinx.coroutines.delay(FLUSH_INTERVAL_MS)
                    publish()
                }
            }

            try {
                gateway.streamChat(
                    model = model,
                    messages = wire,
                    temperature = if (Gateway.acceptsTemperature(model)) null else 1.0,
                    maxTokens = if (Gateway.looksLikeReasoningModel(model)) {
                        Gateway.REASONING_SAFE_MAX_TOKENS
                    } else {
                        null
                    },
                ).collect { event ->
                    when (event) {
                        is Gateway.StreamEvent.Delta -> {
                            buffer.append(event.text)
                            dirty = true
                        }

                        is Gateway.StreamEvent.Reasoning -> {
                            reasoningBuffer.append(event.text)
                            dirty = true
                        }

                        is Gateway.StreamEvent.Completed -> {
                            dirty = true
                            publish()
                            withContext(Dispatchers.IO) {
                                repo.finishMessage(
                                    id = placeholder.id,
                                    content = buffer.toString(),
                                    status = MessageStatus.DONE,
                                    reasoning = reasoningBuffer.toString().ifBlank { null },
                                    promptTokens = event.promptTokens,
                                    completionTokens = event.completionTokens,
                                    credits = event.credits,
                                )
                            }
                        }
                    }

                    // Periodic checkpoint so a kill mid-answer loses seconds, not minutes.
                    val now = System.currentTimeMillis()
                    if (now - lastCheckpoint > CHECKPOINT_INTERVAL_MS) {
                        lastCheckpoint = now
                        val snapshot = buffer.toString()
                        withContext(Dispatchers.IO) {
                            repo.updateMessageContent(placeholder.id, snapshot)
                        }
                    }
                }
            } catch (cancel: CancellationException) {
                // Stopped by the user: keep what arrived.
                withContext(Dispatchers.IO + kotlinx.coroutines.NonCancellable) {
                    repo.finishMessage(
                        id = placeholder.id,
                        content = buffer.toString(),
                        status = MessageStatus.DONE,
                        reasoning = reasoningBuffer.toString().ifBlank { null },
                    )
                }
                throw cancel
            } catch (e: Throwable) {
                val message = (e as? GatewayError)?.userMessage
                    ?: e.message?.takeIf { it.isNotBlank() }
                    ?: "알 수 없는 오류로 응답을 받지 못했습니다."
                errorMessage = message
                withContext(Dispatchers.IO) {
                    if (buffer.isEmpty()) {
                        // Nothing arrived: drop the empty row rather than keep a blank turn.
                        repo.deleteMessage(placeholder.id)
                    } else {
                        repo.finishMessage(
                            id = placeholder.id,
                            content = buffer.toString(),
                            status = MessageStatus.ERROR,
                            error = message,
                        )
                    }
                }
            } finally {
                ticker.cancel()
                streaming = null
                messages = repo.listMessages(conversationId)
                refreshContextSummary()
            }
        }
    }

    private fun deriveTitle(firstMessage: String): String {
        val oneLine = firstMessage.lineSequence().firstOrNull { it.isNotBlank() } ?: firstMessage
        return oneLine.trim().take(40)
    }

    companion object {
        /** ~3 frames at 60Hz: fast enough to read as live, slow enough to be cheap. */
        private const val FLUSH_INTERVAL_MS = 48L
        private const val CHECKPOINT_INTERVAL_MS = 2_000L
    }
}
