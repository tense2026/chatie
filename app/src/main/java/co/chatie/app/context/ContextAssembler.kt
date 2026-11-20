package co.chatie.app.context

import co.chatie.app.baze.WireMessage
import co.chatie.app.data.ChatRepository
import co.chatie.app.data.Conversation
import co.chatie.app.data.MemoryScope
import co.chatie.app.data.Message
import co.chatie.app.data.MessageStatus
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

    /** Where the switch for a layer lives, so the inspector can link to it. */
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

        val override = conv.systemOverride?.takeIf { it.isNotBlank() }
        val layers = if (override != null) overrideLayers(override) else composedLayers(conv, project)

        return Assembled(
            conversation = conv,
            project = project,
            layers = layers,
            system = composeSystem(layers),
            history = historyWindow(conv.id),
        )
    }

    /* ------------------------------------------------------------- layers */

    private fun overrideLayers(override: String): List<Layer> = buildList {
        add(
            Layer(
                id = LayerId.SYSTEM_OVERRIDE,
                label = "이 대화 전용 시스템 프롬프트",
                included = true,
                reason = "직접 지정한 프롬프트가 아래 모든 레이어를 대체합니다",
                text = override,
                controlledBy = ControlledBy.CONVERSATION,
            ),
        )
        val skipped = listOf(
            Triple(LayerId.BASE_INSTRUCTIONS, "기본 지시문", ControlledBy.SETTINGS),
            Triple(LayerId.PROJECT_INSTRUCTIONS, "프로젝트 지시문", ControlledBy.PROJECT),
            Triple(LayerId.PROJECT_MEMORY, "프로젝트 메모리", ControlledBy.PROJECT),
            Triple(LayerId.GLOBAL_MEMORY, "전역 메모리", ControlledBy.CONVERSATION),
            Triple(LayerId.CHAT_MEMORY, "대화 메모리", ControlledBy.CONVERSATION),
        )
        skipped.forEach { (id, label, owner) ->
            add(
                Layer(
                    id = id,
                    label = label,
                    included = false,
                    reason = "전용 시스템 프롬프트가 설정되어 건너뜀",
                    text = "",
                    controlledBy = owner,
                ),
            )
        }
    }

    private fun composedLayers(conv: Conversation, project: Project?): List<Layer> = buildList {
        // 1. base instructions
        val base = (repo.getSetting(SETTING_BASE_INSTRUCTIONS) ?: "").trim()
        add(
            Layer(
                id = LayerId.BASE_INSTRUCTIONS,
                label = "기본 지시문",
                included = base.isNotEmpty(),
                reason = if (base.isNotEmpty()) "설정에 입력되어 있음" else "설정이 비어 있음",
                text = base,
                controlledBy = ControlledBy.SETTINGS,
            ),
        )

        // 2. project instructions
        val projInstr = (project?.instructions ?: "").trim()
        val projInstrOn = project != null && conv.useProjectInstructions && projInstr.isNotEmpty()
        add(
            Layer(
                id = LayerId.PROJECT_INSTRUCTIONS,
                label = "프로젝트 지시문",
                included = projInstrOn,
                reason = when {
                    project == null -> "이 대화는 프로젝트에 속하지 않음"
                    !conv.useProjectInstructions -> "이 대화에서 끔"
                    projInstr.isEmpty() -> "프로젝트 '${project.name}'의 지시문이 비어 있음"
                    else -> "프로젝트 '${project.name}'에서 가져옴"
                },
                text = if (projInstrOn) projInstr else "",
                controlledBy = ControlledBy.CONVERSATION,
            ),
        )

        // 3. project memories — only this project's
        val projMems = project
            ?.let { repo.listMemories(MemoryScope.PROJECT, it.id).filter(::enabled) }
            ?: emptyList()
        val projMemOn = project != null && conv.useProjectMemory && projMems.isNotEmpty()
        add(
            Layer(
                id = LayerId.PROJECT_MEMORY,
                label = "프로젝트 메모리",
                included = projMemOn,
                reason = when {
                    project == null -> "이 대화는 프로젝트에 속하지 않음"
                    !conv.useProjectMemory -> "이 대화에서 끔"
                    projMems.isEmpty() -> "프로젝트 '${project.name}'에 켜진 메모리가 없음"
                    else -> "프로젝트 '${project.name}'의 메모리 ${projMems.size}개"
                },
                text = if (projMemOn) bulletList(projMems.map { it.text }) else "",
                controlledBy = ControlledBy.CONVERSATION,
            ),
        )

        // 4. global memories — resolved through three explicit levels
        val globalSwitch = resolveGlobalMemory(conv, project)
        val globalMems = if (globalSwitch.on) {
            repo.listMemories(MemoryScope.GLOBAL).filter(::enabled)
        } else {
            emptyList()
        }
        val globalOn = globalSwitch.on && globalMems.isNotEmpty()
        add(
            Layer(
                id = LayerId.GLOBAL_MEMORY,
                label = "전역 메모리",
                included = globalOn,
                reason = when {
                    !globalSwitch.on -> globalSwitch.reason
                    globalMems.isEmpty() -> "켜져 있지만 전역 메모리가 없음"
                    else -> "${globalSwitch.reason} · 메모리 ${globalMems.size}개"
                },
                text = if (globalOn) bulletList(globalMems.map { it.text }) else "",
                controlledBy = ControlledBy.CONVERSATION,
            ),
        )

        // 5. chat memories
        val chatMems = repo.listMemories(MemoryScope.CONVERSATION, conv.id).filter(::enabled)
        add(
            Layer(
                id = LayerId.CHAT_MEMORY,
                label = "대화 메모리",
                included = chatMems.isNotEmpty(),
                reason = if (chatMems.isNotEmpty()) {
                    "이 대화에 붙인 메모리 ${chatMems.size}개"
                } else {
                    "이 대화에 붙인 메모리가 없음"
                },
                text = bulletList(chatMems.map { it.text }),
                controlledBy = ControlledBy.CONVERSATION,
            ),
        )
    }

    data class GlobalMemorySwitch(val on: Boolean, val reason: String)

    /** The three levels, named, so the inspector can say which one decided. */
    fun resolveGlobalMemory(conv: Conversation, project: Project?): GlobalMemorySwitch {
        conv.useGlobalMemory?.let { on ->
            return GlobalMemorySwitch(on, if (on) "이 대화에서 켬" else "이 대화에서 끔")
        }
        if (project != null) {
            return GlobalMemorySwitch(
                on = project.useGlobalMemory,
                reason = if (project.useGlobalMemory) {
                    "프로젝트 '${project.name}' 설정을 따름 (켬)"
                } else {
                    "프로젝트 '${project.name}' 설정을 따름 (끔 — 프로젝트는 기본적으로 격리됩니다)"
                },
            )
        }
        val loose = repo.getSetting(SETTING_LOOSE_USE_GLOBAL_MEMORY) != "0"
        return GlobalMemorySwitch(
            on = loose,
            reason = if (loose) {
                "프로젝트 없는 대화 기본값을 따름 (켬)"
            } else {
                "프로젝트 없는 대화 기본값을 따름 (끔)"
            },
        )
    }

    /** Join the included layers, with the section headers the model sees. */
    fun composeSystem(layers: List<Layer>): String? {
        layers.firstOrNull { it.id == LayerId.SYSTEM_OVERRIDE && it.included }
            ?.let { return it.text }

        val parts = layers
            .filter { it.included && it.text.isNotBlank() }
            .map { "## ${it.label}\n${it.text.trim()}" }
        return parts.takeIf { it.isNotEmpty() }?.joinToString("\n\n")
    }

    /* ------------------------------------------------------------ history */

    private fun historyWindow(conversationId: String): HistoryWindow {
        val budget = repo.getSetting(SETTING_HISTORY_CHAR_BUDGET)
            ?.toIntOrNull()
            ?.takeIf { it > 0 }
            ?: DEFAULT_HISTORY_CHAR_BUDGET

        val all = repo.listMessages(conversationId)
            .filter { it.role != Role.SYSTEM && it.status != MessageStatus.ERROR }

        val sent = ArrayDeque<Message>()
        var chars = 0
        for (m in all.asReversed()) {
            val c = m.content.length
            if (sent.isNotEmpty() && chars + c > budget) break
            sent.addFirst(m)
            chars += c
        }
        return HistoryWindow(
            sent = sent.toList(),
            omitted = all.size - sent.size,
            chars = chars,
            budget = budget,
        )
    }

    private fun enabled(m: co.chatie.app.data.Memory) = m.enabled

    private fun bulletList(texts: List<String>): String =
        texts.joinToString("\n") { "- ${it.trim()}" }

    companion object {
        const val SETTING_BASE_INSTRUCTIONS = "base_instructions"
        const val SETTING_LOOSE_USE_GLOBAL_MEMORY = "loose_use_global_memory"
        const val SETTING_HISTORY_CHAR_BUDGET = "history_char_budget"
        const val SETTING_DEFAULT_MODEL = "default_model"

        const val DEFAULT_HISTORY_CHAR_BUDGET = 24_000
        const val DEFAULT_MODEL = "claude-sonnet-5"

        /** App-level limits mirroring BAZE's own, so text does not surprise later. */
        const val MAX_INSTRUCTIONS_CHARS = 10_000
        const val MAX_MEMORY_CHARS = 5_000
    }
}
