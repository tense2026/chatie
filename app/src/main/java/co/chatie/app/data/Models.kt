package co.chatie.app.data

enum class Role(val wire: String) {
    SYSTEM("system"),
    USER("user"),
    ASSISTANT("assistant");

    companion object {
        fun from(value: String): Role = entries.first { it.wire == value }
    }
}

enum class MessageStatus(val wire: String) {
    STREAMING("streaming"),
    DONE("done"),
    ERROR("error");

    companion object {
        fun from(value: String): MessageStatus = entries.first { it.wire == value }
    }
}

enum class MemoryScope(val wire: String) {
    GLOBAL("global"),
    PROJECT("project"),
    CONVERSATION("conversation");

    companion object {
        fun from(value: String): MemoryScope = entries.first { it.wire == value }
    }
}

data class Project(
    val id: String,
    val name: String,
    val description: String,
    val instructions: String,
    val defaultModel: String?,
    /** Whether chats in this project may also see global memories. Off by default. */
    val useGlobalMemory: Boolean,
    val archived: Boolean,
    val createdAt: Long,
    val updatedAt: Long,
)

data class Conversation(
    val id: String,
    val projectId: String?,
    val title: String,
    val model: String,
    /** Replaces the whole composed system block when non-null and non-blank. */
    val systemOverride: String?,
    val useProjectInstructions: Boolean,
    val useProjectMemory: Boolean,
    /** null = inherit from the project, or from settings for a chat with no project. */
    val useGlobalMemory: Boolean?,
    val pinned: Boolean,
    val archived: Boolean,
    val createdAt: Long,
    val updatedAt: Long,
)

data class Message(
    val id: String,
    val conversationId: String,
    val seq: Int,
    val role: Role,
    val content: String,
    val reasoning: String?,
    val model: String?,
    val status: MessageStatus,
    val error: String?,
    val promptTokens: Int?,
    val completionTokens: Int?,
    val credits: Double?,
    val createdAt: Long,
)

data class Memory(
    val id: String,
    val scope: MemoryScope,
    val projectId: String?,
    val conversationId: String?,
    val text: String,
    val enabled: Boolean,
    val source: String,
    val createdAt: Long,
    val updatedAt: Long,
)

data class ConversationListItem(
    val conversation: Conversation,
    val projectName: String?,
    val messageCount: Int,
    val lastMessage: String?,
)
