package co.chatie.app.context

import co.chatie.app.data.Conversation
import co.chatie.app.data.Memory
import co.chatie.app.data.Message
import co.chatie.app.data.MessageStatus
import co.chatie.app.data.Project
import co.chatie.app.data.Role

/**
 * The layering rules, as pure functions.
 *
 * Nothing here touches Android, the database or the network: given the pieces,
 * it decides which layers go out and why. [ContextAssembler] is the thin part
 * that reads those pieces from SQLite and calls in here, which keeps the rules
 * that decide what a model sees testable on their own.
 */
object ContextRules {

    /** Everything the rules need, gathered by the caller. */
    data class Input(
        val conversation: Conversation,
        val project: Project?,
        val baseInstructions: String,
        val projectMemories: List<Memory>,
        val globalMemories: List<Memory>,
        val chatMemories: List<Memory>,
        /** app default for chats that belong to no project */
        val looseChatsUseGlobalMemory: Boolean,
    )

    fun buildLayers(input: Input): List<ContextAssembler.Layer> {
        val override = input.conversation.systemOverride?.takeIf { it.isNotBlank() }
        return if (override != null) overrideLayers(override) else composedLayers(input)
    }

    private fun overrideLayers(override: String): List<ContextAssembler.Layer> = buildList {
        add(
            ContextAssembler.Layer(
                id = ContextAssembler.LayerId.SYSTEM_OVERRIDE,
                label = "이 대화 전용 시스템 프롬프트",
                included = true,
                reason = "직접 지정한 프롬프트가 아래 모든 레이어를 대체합니다",
                text = override,
                controlledBy = ContextAssembler.ControlledBy.CONVERSATION,
            ),
        )
        listOf(
            Triple(
                ContextAssembler.LayerId.BASE_INSTRUCTIONS,
                "기본 지시문",
                ContextAssembler.ControlledBy.SETTINGS,
            ),
            Triple(
                ContextAssembler.LayerId.PROJECT_INSTRUCTIONS,
                "프로젝트 지시문",
                ContextAssembler.ControlledBy.PROJECT,
            ),
            Triple(
                ContextAssembler.LayerId.PROJECT_MEMORY,
                "프로젝트 메모리",
                ContextAssembler.ControlledBy.PROJECT,
            ),
            Triple(
                ContextAssembler.LayerId.GLOBAL_MEMORY,
                "전역 메모리",
                ContextAssembler.ControlledBy.CONVERSATION,
            ),
            Triple(
                ContextAssembler.LayerId.CHAT_MEMORY,
                "대화 메모리",
                ContextAssembler.ControlledBy.CONVERSATION,
            ),
        ).forEach { (id, label, owner) ->
            add(
                ContextAssembler.Layer(
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

    private fun composedLayers(input: Input): List<ContextAssembler.Layer> = buildList {
        val conv = input.conversation
        val project = input.project

        // 1. base instructions
        val base = input.baseInstructions.trim()
        add(
            ContextAssembler.Layer(
                id = ContextAssembler.LayerId.BASE_INSTRUCTIONS,
                label = "기본 지시문",
                included = base.isNotEmpty(),
                reason = if (base.isNotEmpty()) "설정에 입력되어 있음" else "설정이 비어 있음",
                text = base,
                controlledBy = ContextAssembler.ControlledBy.SETTINGS,
            ),
        )

        // 2. project instructions
        val projInstr = (project?.instructions ?: "").trim()
        val projInstrOn = project != null && conv.useProjectInstructions && projInstr.isNotEmpty()
        add(
            ContextAssembler.Layer(
                id = ContextAssembler.LayerId.PROJECT_INSTRUCTIONS,
                label = "프로젝트 지시문",
                included = projInstrOn,
                reason = when {
                    project == null -> "이 대화는 프로젝트에 속하지 않음"
                    !conv.useProjectInstructions -> "이 대화에서 끔"
                    projInstr.isEmpty() -> "프로젝트 '${project.name}'의 지시문이 비어 있음"
                    else -> "프로젝트 '${project.name}'에서 가져옴"
                },
                text = if (projInstrOn) projInstr else "",
                controlledBy = ContextAssembler.ControlledBy.CONVERSATION,
            ),
        )

        // 3. project memories — only this project's, never another's
        val projMems = if (project != null) input.projectMemories.filter { it.enabled } else emptyList()
        val projMemOn = project != null && conv.useProjectMemory && projMems.isNotEmpty()
        add(
            ContextAssembler.Layer(
                id = ContextAssembler.LayerId.PROJECT_MEMORY,
                label = "프로젝트 메모리",
                included = projMemOn,
                reason = when {
                    project == null -> "이 대화는 프로젝트에 속하지 않음"
                    !conv.useProjectMemory -> "이 대화에서 끔"
                    projMems.isEmpty() -> "프로젝트 '${project.name}'에 켜진 메모리가 없음"
                    else -> "프로젝트 '${project.name}'의 메모리 ${projMems.size}개"
                },
                text = if (projMemOn) bulletList(projMems) else "",
                controlledBy = ContextAssembler.ControlledBy.CONVERSATION,
            ),
        )

        // 4. global memories — resolved through three explicit levels
        val switch = resolveGlobalMemory(conv, project, input.looseChatsUseGlobalMemory)
        val globalMems = if (switch.on) input.globalMemories.filter { it.enabled } else emptyList()
        val globalOn = switch.on && globalMems.isNotEmpty()
        add(
            ContextAssembler.Layer(
                id = ContextAssembler.LayerId.GLOBAL_MEMORY,
                label = "전역 메모리",
                included = globalOn,
                reason = when {
                    !switch.on -> switch.reason
                    globalMems.isEmpty() -> "켜져 있지만 전역 메모리가 없음"
                    else -> "${switch.reason} · 메모리 ${globalMems.size}개"
                },
                text = if (globalOn) bulletList(globalMems) else "",
                controlledBy = ContextAssembler.ControlledBy.CONVERSATION,
            ),
        )

        // 5. chat memories
        val chatMems = input.chatMemories.filter { it.enabled }
        add(
            ContextAssembler.Layer(
                id = ContextAssembler.LayerId.CHAT_MEMORY,
                label = "대화 메모리",
                included = chatMems.isNotEmpty(),
                reason = if (chatMems.isNotEmpty()) {
                    "이 대화에 붙인 메모리 ${chatMems.size}개"
                } else {
                    "이 대화에 붙인 메모리가 없음"
                },
                text = bulletList(chatMems),
                controlledBy = ContextAssembler.ControlledBy.CONVERSATION,
            ),
        )
    }

    /**
     * The global-memory switch has three levels. This names the one that
     * decided, so the inspector never has to say "somehow".
     */
    fun resolveGlobalMemory(
        conversation: Conversation,
        project: Project?,
        looseChatsUseGlobalMemory: Boolean,
    ): ContextAssembler.GlobalMemorySwitch {
        conversation.useGlobalMemory?.let { on ->
            return ContextAssembler.GlobalMemorySwitch(
                on = on,
                reason = if (on) "이 대화에서 켬" else "이 대화에서 끔",
            )
        }
        if (project != null) {
            return ContextAssembler.GlobalMemorySwitch(
                on = project.useGlobalMemory,
                reason = if (project.useGlobalMemory) {
                    "프로젝트 '${project.name}' 설정을 따름 (켬)"
                } else {
                    "프로젝트 '${project.name}' 설정을 따름 (끔 — 프로젝트는 기본적으로 격리됩니다)"
                },
            )
        }
        return ContextAssembler.GlobalMemorySwitch(
            on = looseChatsUseGlobalMemory,
            reason = if (looseChatsUseGlobalMemory) {
                "프로젝트 없는 대화 기본값을 따름 (켬)"
            } else {
                "프로젝트 없는 대화 기본값을 따름 (끔)"
            },
        )
    }

    /** Join the included layers, with the section headers the model sees. */
    fun composeSystem(layers: List<ContextAssembler.Layer>): String? {
        layers.firstOrNull { it.id == ContextAssembler.LayerId.SYSTEM_OVERRIDE && it.included }
            ?.let { return it.text }

        val parts = layers
            .filter { it.included && it.text.isNotBlank() }
            .map { "## ${it.label}\n${it.text.trim()}" }
        return parts.takeIf { it.isNotEmpty() }?.joinToString("\n\n")
    }

    /**
     * Newest-first fill of the character budget. The latest message is always
     * sent, even alone over budget, because dropping it would send a request
     * with nothing to answer.
     */
    fun windowHistory(messages: List<Message>, budget: Int): ContextAssembler.HistoryWindow {
        val usable = messages.filter { it.role != Role.SYSTEM && it.status != MessageStatus.ERROR }
        val sent = ArrayDeque<Message>()
        var chars = 0
        for (m in usable.asReversed()) {
            val size = m.content.length
            if (sent.isNotEmpty() && chars + size > budget) break
            sent.addFirst(m)
            chars += size
        }
        return ContextAssembler.HistoryWindow(
            sent = sent.toList(),
            omitted = usable.size - sent.size,
            chars = chars,
            budget = budget,
        )
    }

    private fun bulletList(memories: List<Memory>): String =
        memories.joinToString("\n") { "- ${it.text.trim()}" }
}
