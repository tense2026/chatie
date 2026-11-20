package co.chatie.app.data

import android.content.ContentValues
import android.database.Cursor
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import java.util.UUID

/**
 * Every read is a plain synchronous query against a local SQLite file, which is
 * fast enough to call straight from composition; every write bumps [revision]
 * so screens re-query without a subscription framework.
 */
class ChatRepository(private val db: Db) {

    private val _revision = MutableStateFlow(0L)
    val revision: StateFlow<Long> = _revision

    private fun changed() {
        _revision.value = _revision.value + 1
    }

    private fun now() = System.currentTimeMillis()

    private fun newId(prefix: String) =
        "${prefix}_${UUID.randomUUID().toString().replace("-", "").take(16)}"

    /* --------------------------------------------------------------- settings */

    fun getSetting(key: String): String? =
        db.readableDatabase.rawQuery("SELECT value FROM settings WHERE key = ?", arrayOf(key))
            .use { if (it.moveToFirst()) it.getString(0) else null }

    fun setSetting(key: String, value: String) {
        db.writableDatabase.execSQL(
            "INSERT INTO settings (key, value) VALUES (?, ?) " +
                "ON CONFLICT(key) DO UPDATE SET value = excluded.value",
            arrayOf(key, value),
        )
        changed()
    }

    /* --------------------------------------------------------------- projects */

    fun listProjects(includeArchived: Boolean = false): List<Project> =
        db.readableDatabase.rawQuery(
            "SELECT * FROM projects ${if (includeArchived) "" else "WHERE archived = 0"} " +
                "ORDER BY updated_at DESC",
            null,
        ).use { c -> c.mapAll(::readProject) }

    fun getProject(id: String): Project? =
        db.readableDatabase.rawQuery("SELECT * FROM projects WHERE id = ?", arrayOf(id))
            .use { if (it.moveToFirst()) readProject(it) else null }

    fun createProject(
        name: String,
        description: String = "",
        instructions: String = "",
        defaultModel: String? = null,
        useGlobalMemory: Boolean = false,
    ): Project {
        val id = newId("prj")
        val ts = now()
        db.writableDatabase.insertOrThrow(
            "projects",
            null,
            ContentValues().apply {
                put("id", id)
                put("name", name.take(100))
                put("description", description)
                put("instructions", instructions)
                put("default_model", defaultModel)
                put("use_global_memory", if (useGlobalMemory) 1 else 0)
                put("archived", 0)
                put("created_at", ts)
                put("updated_at", ts)
            },
        )
        changed()
        return getProject(id)!!
    }

    fun updateProject(
        id: String,
        name: String? = null,
        description: String? = null,
        instructions: String? = null,
        defaultModel: String? = null,
        clearDefaultModel: Boolean = false,
        useGlobalMemory: Boolean? = null,
        archived: Boolean? = null,
    ) {
        val values = ContentValues().apply {
            name?.let { put("name", it.take(100)) }
            description?.let { put("description", it) }
            instructions?.let { put("instructions", it) }
            if (clearDefaultModel) putNull("default_model") else defaultModel?.let { put("default_model", it) }
            useGlobalMemory?.let { put("use_global_memory", if (it) 1 else 0) }
            archived?.let { put("archived", if (it) 1 else 0) }
            put("updated_at", now())
        }
        db.writableDatabase.update("projects", values, "id = ?", arrayOf(id))
        changed()
    }

    /**
     * Conversations survive: `project_id` becomes NULL, so deleting a project
     * never silently takes its chats with it. Project memories do go.
     */
    fun deleteProject(id: String) {
        db.writableDatabase.delete("projects", "id = ?", arrayOf(id))
        changed()
    }

    /* ---------------------------------------------------------- conversations */

    fun listConversations(
        projectId: String? = null,
        onlyWithoutProject: Boolean = false,
        limit: Int = 300,
    ): List<ConversationListItem> {
        val where = StringBuilder("c.archived = 0")
        val args = mutableListOf<String>()
        when {
            projectId != null -> {
                where.append(" AND c.project_id = ?")
                args += projectId
            }
            onlyWithoutProject -> where.append(" AND c.project_id IS NULL")
        }
        return db.readableDatabase.rawQuery(
            """
            SELECT c.*, p.name AS project_name,
                   (SELECT COUNT(*) FROM messages m WHERE m.conversation_id = c.id) AS message_count,
                   (SELECT m.content FROM messages m WHERE m.conversation_id = c.id
                     ORDER BY m.seq DESC LIMIT 1) AS last_message
              FROM conversations c
              LEFT JOIN projects p ON p.id = c.project_id
             WHERE $where
             ORDER BY c.pinned DESC, c.updated_at DESC
             LIMIT ?
            """.trimIndent(),
            (args + limit.toString()).toTypedArray(),
        ).use { c ->
            c.mapAll {
                ConversationListItem(
                    conversation = readConversation(it),
                    projectName = it.stringOrNull("project_name"),
                    messageCount = it.getInt(it.getColumnIndexOrThrow("message_count")),
                    lastMessage = it.stringOrNull("last_message"),
                )
            }
        }
    }

    fun getConversation(id: String): Conversation? =
        db.readableDatabase.rawQuery("SELECT * FROM conversations WHERE id = ?", arrayOf(id))
            .use { if (it.moveToFirst()) readConversation(it) else null }

    fun createConversation(model: String, projectId: String? = null, title: String = ""): Conversation {
        val id = newId("cnv")
        val ts = now()
        db.writableDatabase.insertOrThrow(
            "conversations",
            null,
            ContentValues().apply {
                put("id", id)
                put("project_id", projectId)
                put("title", title)
                put("model", model)
                putNull("system_override")
                put("use_project_instructions", 1)
                put("use_project_memory", 1)
                putNull("use_global_memory")
                put("pinned", 0)
                put("archived", 0)
                put("created_at", ts)
                put("updated_at", ts)
            },
        )
        changed()
        return getConversation(id)!!
    }

    fun updateConversation(
        id: String,
        title: String? = null,
        model: String? = null,
        projectId: String? = null,
        clearProject: Boolean = false,
        systemOverride: String? = null,
        clearSystemOverride: Boolean = false,
        useProjectInstructions: Boolean? = null,
        useProjectMemory: Boolean? = null,
        useGlobalMemory: Boolean? = null,
        inheritGlobalMemory: Boolean = false,
        pinned: Boolean? = null,
        archived: Boolean? = null,
    ) {
        val values = ContentValues().apply {
            title?.let { put("title", it) }
            model?.let { put("model", it) }
            if (clearProject) putNull("project_id") else projectId?.let { put("project_id", it) }
            if (clearSystemOverride) putNull("system_override")
            else systemOverride?.let { put("system_override", it) }
            useProjectInstructions?.let { put("use_project_instructions", if (it) 1 else 0) }
            useProjectMemory?.let { put("use_project_memory", if (it) 1 else 0) }
            if (inheritGlobalMemory) putNull("use_global_memory")
            else useGlobalMemory?.let { put("use_global_memory", if (it) 1 else 0) }
            pinned?.let { put("pinned", if (it) 1 else 0) }
            archived?.let { put("archived", if (it) 1 else 0) }
            put("updated_at", now())
        }
        db.writableDatabase.update("conversations", values, "id = ?", arrayOf(id))
        changed()
    }

    fun deleteConversation(id: String) {
        db.writableDatabase.delete("conversations", "id = ?", arrayOf(id))
        changed()
    }

    private fun touchConversation(id: String) {
        db.writableDatabase.execSQL(
            "UPDATE conversations SET updated_at = ? WHERE id = ?",
            arrayOf<Any>(now(), id),
        )
    }

    /* --------------------------------------------------------------- messages */

    fun listMessages(conversationId: String): List<Message> =
        db.readableDatabase.rawQuery(
            "SELECT * FROM messages WHERE conversation_id = ? ORDER BY seq ASC",
            arrayOf(conversationId),
        ).use { c -> c.mapAll(::readMessage) }

    fun nextSeq(conversationId: String): Int =
        db.readableDatabase.rawQuery(
            "SELECT MAX(seq) FROM messages WHERE conversation_id = ?",
            arrayOf(conversationId),
        ).use { if (it.moveToFirst() && !it.isNull(0)) it.getInt(0) + 1 else 1 }

    fun insertMessage(
        conversationId: String,
        role: Role,
        content: String,
        model: String? = null,
        status: MessageStatus = MessageStatus.DONE,
    ): Message {
        val id = newId("msg")
        val ts = now()
        val seq = nextSeq(conversationId)
        db.writableDatabase.insertOrThrow(
            "messages",
            null,
            ContentValues().apply {
                put("id", id)
                put("conversation_id", conversationId)
                put("seq", seq)
                put("role", role.wire)
                put("content", content)
                putNull("reasoning")
                put("model", model)
                put("status", status.wire)
                putNull("error")
                put("created_at", ts)
            },
        )
        touchConversation(conversationId)
        changed()
        return db.readableDatabase.rawQuery("SELECT * FROM messages WHERE id = ?", arrayOf(id))
            .use { it.moveToFirst(); readMessage(it) }
    }

    /** Checkpoint a streaming message's text without ending the stream. */
    fun updateMessageContent(id: String, content: String) {
        db.writableDatabase.execSQL(
            "UPDATE messages SET content = ? WHERE id = ?",
            arrayOf<Any>(content, id),
        )
    }

    fun finishMessage(
        id: String,
        content: String,
        status: MessageStatus,
        reasoning: String? = null,
        error: String? = null,
        promptTokens: Int? = null,
        completionTokens: Int? = null,
        credits: Double? = null,
    ) {
        db.writableDatabase.update(
            "messages",
            ContentValues().apply {
                put("content", content)
                put("reasoning", reasoning)
                put("status", status.wire)
                put("error", error)
                put("prompt_tokens", promptTokens)
                put("completion_tokens", completionTokens)
                put("credits", credits)
            },
            "id = ?",
            arrayOf(id),
        )
        changed()
    }

    fun deleteMessage(id: String) {
        db.writableDatabase.delete("messages", "id = ?", arrayOf(id))
        changed()
    }

    /** Used when retrying: drop the failed turn and everything after it. */
    fun deleteMessagesFrom(conversationId: String, seq: Int) {
        db.writableDatabase.delete(
            "messages",
            "conversation_id = ? AND seq >= ?",
            arrayOf(conversationId, seq.toString()),
        )
        changed()
    }

    /* --------------------------------------------------------------- memories */

    fun listMemories(scope: MemoryScope, parentId: String? = null): List<Memory> {
        val (sql, args) = when (scope) {
            MemoryScope.GLOBAL ->
                "SELECT * FROM memories WHERE scope = 'global' ORDER BY created_at DESC" to emptyArray<String>()
            MemoryScope.PROJECT ->
                "SELECT * FROM memories WHERE scope = 'project' AND project_id = ? ORDER BY created_at DESC" to
                    arrayOf(parentId.orEmpty())
            MemoryScope.CONVERSATION ->
                "SELECT * FROM memories WHERE scope = 'conversation' AND conversation_id = ? ORDER BY created_at DESC" to
                    arrayOf(parentId.orEmpty())
        }
        return db.readableDatabase.rawQuery(sql, args).use { c -> c.mapAll(::readMemory) }
    }

    fun createMemory(
        scope: MemoryScope,
        text: String,
        parentId: String? = null,
        source: String = "user",
    ): Memory {
        val id = newId("mem")
        val ts = now()
        db.writableDatabase.insertOrThrow(
            "memories",
            null,
            ContentValues().apply {
                put("id", id)
                put("scope", scope.wire)
                put("project_id", if (scope == MemoryScope.PROJECT) parentId else null)
                put("conversation_id", if (scope == MemoryScope.CONVERSATION) parentId else null)
                put("text", text)
                put("enabled", 1)
                put("source", source)
                put("created_at", ts)
                put("updated_at", ts)
            },
        )
        changed()
        return db.readableDatabase.rawQuery("SELECT * FROM memories WHERE id = ?", arrayOf(id))
            .use { it.moveToFirst(); readMemory(it) }
    }

    fun updateMemory(id: String, text: String? = null, enabled: Boolean? = null) {
        db.writableDatabase.update(
            "memories",
            ContentValues().apply {
                text?.let { put("text", it) }
                enabled?.let { put("enabled", if (it) 1 else 0) }
                put("updated_at", now())
            },
            "id = ?",
            arrayOf(id),
        )
        changed()
    }

    fun deleteMemory(id: String) {
        db.writableDatabase.delete("memories", "id = ?", arrayOf(id))
        changed()
    }

    /* ------------------------------------------------------------- mapping */

    private fun readProject(c: Cursor) = Project(
        id = c.string("id"),
        name = c.string("name"),
        description = c.string("description"),
        instructions = c.string("instructions"),
        defaultModel = c.stringOrNull("default_model"),
        useGlobalMemory = c.bool("use_global_memory"),
        archived = c.bool("archived"),
        createdAt = c.long("created_at"),
        updatedAt = c.long("updated_at"),
    )

    private fun readConversation(c: Cursor) = Conversation(
        id = c.string("id"),
        projectId = c.stringOrNull("project_id"),
        title = c.string("title"),
        model = c.string("model"),
        systemOverride = c.stringOrNull("system_override"),
        useProjectInstructions = c.bool("use_project_instructions"),
        useProjectMemory = c.bool("use_project_memory"),
        useGlobalMemory = c.boolOrNull("use_global_memory"),
        pinned = c.bool("pinned"),
        archived = c.bool("archived"),
        createdAt = c.long("created_at"),
        updatedAt = c.long("updated_at"),
    )

    private fun readMessage(c: Cursor) = Message(
        id = c.string("id"),
        conversationId = c.string("conversation_id"),
        seq = c.int("seq"),
        role = Role.from(c.string("role")),
        content = c.string("content"),
        reasoning = c.stringOrNull("reasoning"),
        model = c.stringOrNull("model"),
        status = MessageStatus.from(c.string("status")),
        error = c.stringOrNull("error"),
        promptTokens = c.intOrNull("prompt_tokens"),
        completionTokens = c.intOrNull("completion_tokens"),
        credits = c.doubleOrNull("credits"),
        createdAt = c.long("created_at"),
    )

    private fun readMemory(c: Cursor) = Memory(
        id = c.string("id"),
        scope = MemoryScope.from(c.string("scope")),
        projectId = c.stringOrNull("project_id"),
        conversationId = c.stringOrNull("conversation_id"),
        text = c.string("text"),
        enabled = c.bool("enabled"),
        source = c.string("source"),
        createdAt = c.long("created_at"),
        updatedAt = c.long("updated_at"),
    )
}

/* ------------------------------------------------------------- cursor helpers */

private inline fun <T> Cursor.mapAll(read: (Cursor) -> T): List<T> {
    val out = ArrayList<T>(count)
    while (moveToNext()) out += read(this)
    return out
}

private fun Cursor.string(name: String): String = getString(getColumnIndexOrThrow(name)) ?: ""
private fun Cursor.stringOrNull(name: String): String? =
    getColumnIndexOrThrow(name).let { if (isNull(it)) null else getString(it) }
private fun Cursor.long(name: String): Long = getLong(getColumnIndexOrThrow(name))
private fun Cursor.int(name: String): Int = getInt(getColumnIndexOrThrow(name))
private fun Cursor.intOrNull(name: String): Int? =
    getColumnIndexOrThrow(name).let { if (isNull(it)) null else getInt(it) }
private fun Cursor.doubleOrNull(name: String): Double? =
    getColumnIndexOrThrow(name).let { if (isNull(it)) null else getDouble(it) }
private fun Cursor.bool(name: String): Boolean = getInt(getColumnIndexOrThrow(name)) == 1
private fun Cursor.boolOrNull(name: String): Boolean? =
    getColumnIndexOrThrow(name).let { if (isNull(it)) null else getInt(it) == 1 }
