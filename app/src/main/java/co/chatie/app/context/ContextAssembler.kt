package co.chatie.app.context

import co.chatie.app.baze.WireMessage
import co.chatie.app.data.ChatRepository
import co.chatie.app.data.Conversation
import co.chatie.app.data.MemoryScope
import co.chatie.app.data.Message
import co.chatie.app.data.Project
import co.chatie.app.data.Role

/**
 * Deterministic context assembly.
 *
 * Every request to the gateway is built here, and the Context Inspector screen
 * renders exactly what this returns. There is no hidden layer, no implicit
 * inheritance and no server-side state: if a line is not listed here, it was
 * not sent.
 *
 * Layer order (first = earliest in the system block):
 *   1. base instructions    — app-wide, from Settings
 *   2. project instructions — only when the chat is in a project
 *   3. project memories     — only that project's, never another's
 *   4. global memories      — opt-in; off by default inside a project
 *   5. chat memories        — notes attached to this one conversation
 *
 * A chat belongs to zero or one project. Nothing crosses between projects.
 *
 * This class only gathers rows and hands them to [ContextRules], which holds
 * the decisions.
 */
class ContextAssembler(private val repo: ChatRepository) {

    enum class LayerId {
        SYSTEM_OVERRIDE,
        BASE_INSTRUCTIONS,
        PROJECT_INSTRUCTIONS,
        PROJECT_MEMORY,
        GLOBAL_MEMORY,
        CHAT_MEMORY,
    }

    /** Where the switch for a layer lives, so the inspector can point at it. */
    enum class ControlledBy { SETTINGS, PROJECT, CONVERSATION }

    data class Layer(
        val id: LayerId,
        val label: String,
        val included: Boolean,
        /** why it was included or skipped, in plain language */
        val reason: String,
        /** the exact text contributed; empty when not included */
        val text: String,
        val controlledBy: ControlledBy,
    ) {
        val chars: Int get() = text.length
    }

    data class GlobalMemorySwitch(val on: Boolean, val reason: String)

    data class HistoryWindow(
        val sent: List<Message>,
        /** how many older messages were dropped to fit the budget */
        val omitted: Int,
        val chars: Int,
        val budget: Int,
    )

    data class Assembled(
        val conversation: Conversation,
        val project: Project?,
        val layers: List<Layer>,
        /** the composed system prompt, or null when nothing applies */
        val system: String?,
        val history: HistoryWindow,
    ) {
        val totalChars: Int get() = (system?.length ?: 0) + history.chars

        /** Exactly the `messages` array that goes on the wire. */
        fun toWireMessages(): List<WireMessage> = buildList {
            system?.let { add(WireMessage("system", it)) }
            history.sent.forEach { m ->
                if (m.role == Role.USER || m.role == Role.ASSISTANT) {
                    add(WireMessage(m.role.wire, m.content))
                }
            }
        }
    }

    fun assemble(conversationId: String): Assembled? {
        val conv = repo.getConversation(conversationId) ?: return null
        val project = conv.projectId?.let { repo.getProject(it) }

        val input = ContextRules.Input(
            conversation = conv,
            project = project,
            baseInstructions = repo.getSetting(SETTING_BASE_INSTRUCTIONS) ?: "",
            projectMemories = project
                ?.let { repo.listMemories(MemoryScope.PROJECT, it.id) }
                ?: emptyList(),
            globalMemories = repo.listMemories(MemoryScope.GLOBAL),
            chatMemories = repo.listMemories(MemoryScope.CONVERSATION, conv.id),
            looseChatsUseGlobalMemory = looseChatsUseGlobalMemory(),
        )

        val layers = ContextRules.buildLayers(input)

        return Assembled(
            conversation = conv,
            project = project,
            layers = layers,
            system = ContextRules.composeSystem(layers),
            history = ContextRules.windowHistory(repo.listMessages(conv.id), historyCharBudget()),
        )
    }

    /** Used by the chat-settings screen to explain the inherited value. */
    fun resolveGlobalMemory(conversation: Conversation, project: Project?): GlobalMemorySwitch =
        ContextRules.resolveGlobalMemory(conversation, project, looseChatsUseGlobalMemory())

    private fun looseChatsUseGlobalMemory(): Boolean =
        repo.getSetting(SETTING_LOOSE_USE_GLOBAL_MEMORY) != "0"

    private fun historyCharBudget(): Int =
        repo.getSetting(SETTING_HISTORY_CHAR_BUDGET)
            ?.toIntOrNull()
            ?.takeIf { it > 0 }
            ?: DEFAULT_HISTORY_CHAR_BUDGET

    companion object {
        const val SETTING_BASE_INSTRUCTIONS = "base_instructions"
        const val SETTING_LOOSE_USE_GLOBAL_MEMORY = "loose_use_global_memory"
        const val SETTING_HISTORY_CHAR_BUDGET = "history_char_budget"
        const val SETTING_DEFAULT_MODEL = "default_model"

        const val DEFAULT_HISTORY_CHAR_BUDGET = 24_000

        /** Mirrors BAZE's own limits so text does not surprise you later. */
        const val MAX_INSTRUCTIONS_CHARS = 10_000
        const val MAX_MEMORY_CHARS = 5_000

        const val DEFAULT_MODEL = "claude-sonnet-5"
    }
}
