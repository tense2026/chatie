package co.chatie.app.context

import androidx.test.core.app.ApplicationProvider
import co.chatie.app.data.ChatRepository
import co.chatie.app.data.Db
import co.chatie.app.data.MemoryScope
import co.chatie.app.data.Role
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * These tests are the contract for "which context reaches the model".
 * Every rule the Context Inspector claims is asserted here.
 */
@RunWith(RobolectricTestRunner::class)
class ContextAssemblerTest {

    private lateinit var repo: ChatRepository
    private lateinit var assembler: ContextAssembler

    @Before
    fun setUp() {
        val db = Db(ApplicationProvider.getApplicationContext())
        repo = ChatRepository(db)
        assembler = ContextAssembler(repo)
    }

    private fun layer(
        assembled: ContextAssembler.Assembled,
        id: ContextAssembler.LayerId,
    ): ContextAssembler.Layer = assembled.layers.first { it.id == id }

    @Test
    fun `chat without a project gets base instructions and global memories`() {
        repo.setSetting(ContextAssembler.SETTING_BASE_INSTRUCTIONS, "항상 한국어로")
        repo.createMemory(MemoryScope.GLOBAL, "사용자는 안드로이드 개발자")
        val conv = repo.createConversation(model = "claude-sonnet-5")

        val assembled = assembler.assemble(conv.id)!!

        assertTrue(layer(assembled, ContextAssembler.LayerId.BASE_INSTRUCTIONS).included)
        assertTrue(layer(assembled, ContextAssembler.LayerId.GLOBAL_MEMORY).included)
        assertFalse(layer(assembled, ContextAssembler.LayerId.PROJECT_INSTRUCTIONS).included)
        assertFalse(layer(assembled, ContextAssembler.LayerId.PROJECT_MEMORY).included)
        assertTrue(assembled.system!!.contains("항상 한국어로"))
        assertTrue(assembled.system!!.contains("사용자는 안드로이드 개발자"))
    }

    @Test
    fun `a project is isolated from global memories by default`() {
        repo.createMemory(MemoryScope.GLOBAL, "전역 사실")
        val project = repo.createProject(name = "업무", instructions = "격식체로 답하라")
        repo.createMemory(MemoryScope.PROJECT, "이 프로젝트는 B2B SaaS", project.id)
        val conv = repo.createConversation(model = "claude-sonnet-5", projectId = project.id)

        val assembled = assembler.assemble(conv.id)!!

        assertTrue(layer(assembled, ContextAssembler.LayerId.PROJECT_INSTRUCTIONS).included)
        assertTrue(layer(assembled, ContextAssembler.LayerId.PROJECT_MEMORY).included)

        val global = layer(assembled, ContextAssembler.LayerId.GLOBAL_MEMORY)
        assertFalse(global.included)
        assertTrue(global.reason.contains("프로젝트는 기본적으로 격리"))
        assertFalse(assembled.system!!.contains("전역 사실"))
    }

    @Test
    fun `a project can opt in to global memories`() {
        repo.createMemory(MemoryScope.GLOBAL, "전역 사실")
        val project = repo.createProject(name = "업무", useGlobalMemory = true)
        val conv = repo.createConversation(model = "claude-sonnet-5", projectId = project.id)

        val assembled = assembler.assemble(conv.id)!!

        assertTrue(layer(assembled, ContextAssembler.LayerId.GLOBAL_MEMORY).included)
        assertTrue(assembled.system!!.contains("전역 사실"))
    }

    @Test
    fun `a conversation switch overrides the project's global memory setting`() {
        repo.createMemory(MemoryScope.GLOBAL, "전역 사실")
        val project = repo.createProject(name = "업무", useGlobalMemory = true)
        val conv = repo.createConversation(model = "claude-sonnet-5", projectId = project.id)
        repo.updateConversation(conv.id, useGlobalMemory = false)

        val assembled = assembler.assemble(conv.id)!!
        val global = layer(assembled, ContextAssembler.LayerId.GLOBAL_MEMORY)

        assertFalse(global.included)
        assertEquals("이 대화에서 끔", global.reason)
    }

    @Test
    fun `inherit can be restored after an explicit conversation switch`() {
        val project = repo.createProject(name = "업무", useGlobalMemory = true)
        val conv = repo.createConversation(model = "claude-sonnet-5", projectId = project.id)

        repo.updateConversation(conv.id, useGlobalMemory = false)
        assertEquals(false, repo.getConversation(conv.id)!!.useGlobalMemory)

        repo.updateConversation(conv.id, inheritGlobalMemory = true)
        assertNull(repo.getConversation(conv.id)!!.useGlobalMemory)

        val reason = layer(
            assembler.assemble(conv.id)!!,
            ContextAssembler.LayerId.GLOBAL_MEMORY,
        ).reason
        assertTrue(reason.contains("프로젝트 '업무' 설정을 따름"))
    }

    @Test
    fun `one project's memories never reach another project's chat`() {
        val a = repo.createProject(name = "A", instructions = "A 지시문")
        val b = repo.createProject(name = "B", instructions = "B 지시문")
        repo.createMemory(MemoryScope.PROJECT, "A 전용 사실", a.id)
        repo.createMemory(MemoryScope.PROJECT, "B 전용 사실", b.id)

        val convB = repo.createConversation(model = "claude-sonnet-5", projectId = b.id)
        val system = assembler.assemble(convB.id)!!.system!!

        assertTrue(system.contains("B 전용 사실"))
        assertFalse(system.contains("A 전용 사실"))
        assertTrue(system.contains("B 지시문"))
        assertFalse(system.contains("A 지시문"))
    }

    @Test
    fun `a disabled memory is kept but not sent`() {
        val memory = repo.createMemory(MemoryScope.GLOBAL, "잠시 끌 사실")
        val conv = repo.createConversation(model = "claude-sonnet-5")

        repo.updateMemory(memory.id, enabled = false)

        val assembled = assembler.assemble(conv.id)!!
        assertFalse(layer(assembled, ContextAssembler.LayerId.GLOBAL_MEMORY).included)
        assertNull(assembled.system)
        // still on disk, just excluded
        assertEquals(1, repo.listMemories(MemoryScope.GLOBAL).size)
    }

    @Test
    fun `a system override replaces every composed layer`() {
        repo.setSetting(ContextAssembler.SETTING_BASE_INSTRUCTIONS, "기본")
        val project = repo.createProject(name = "업무", instructions = "프로젝트 지시문")
        repo.createMemory(MemoryScope.PROJECT, "프로젝트 사실", project.id)
        val conv = repo.createConversation(model = "claude-sonnet-5", projectId = project.id)
        repo.updateConversation(conv.id, systemOverride = "너는 번역기다")

        val assembled = assembler.assemble(conv.id)!!

        assertEquals("너는 번역기다", assembled.system)
        assertTrue(layer(assembled, ContextAssembler.LayerId.SYSTEM_OVERRIDE).included)
        listOf(
            ContextAssembler.LayerId.BASE_INSTRUCTIONS,
            ContextAssembler.LayerId.PROJECT_INSTRUCTIONS,
            ContextAssembler.LayerId.PROJECT_MEMORY,
            ContextAssembler.LayerId.GLOBAL_MEMORY,
            ContextAssembler.LayerId.CHAT_MEMORY,
        ).forEach { id ->
            val skipped = layer(assembled, id)
            assertFalse("$id should be skipped", skipped.included)
            assertEquals("전용 시스템 프롬프트가 설정되어 건너뜀", skipped.reason)
        }
    }

    @Test
    fun `history trims the oldest messages to the budget and reports how many`() {
        repo.setSetting(ContextAssembler.SETTING_HISTORY_CHAR_BUDGET, "25")
        val conv = repo.createConversation(model = "claude-sonnet-5")
        repo.insertMessage(conv.id, Role.USER, "a".repeat(10))
        repo.insertMessage(conv.id, Role.ASSISTANT, "b".repeat(10))
        repo.insertMessage(conv.id, Role.USER, "c".repeat(10))

        val history = assembler.assemble(conv.id)!!.history

        assertEquals(2, history.sent.size)
        assertEquals(1, history.omitted)
        assertEquals("c".repeat(10), history.sent.last().content)
        assertTrue(history.chars <= 25)
    }

    @Test
    fun `the newest message is sent even when it alone exceeds the budget`() {
        repo.setSetting(ContextAssembler.SETTING_HISTORY_CHAR_BUDGET, "5")
        val conv = repo.createConversation(model = "claude-sonnet-5")
        repo.insertMessage(conv.id, Role.USER, "x".repeat(100))

        val history = assembler.assemble(conv.id)!!.history

        assertEquals(1, history.sent.size)
        assertEquals(0, history.omitted)
    }

    @Test
    fun `wire messages put the system block first and keep turn order`() {
        repo.setSetting(ContextAssembler.SETTING_BASE_INSTRUCTIONS, "기본")
        val conv = repo.createConversation(model = "claude-sonnet-5")
        repo.insertMessage(conv.id, Role.USER, "첫 질문")
        repo.insertMessage(conv.id, Role.ASSISTANT, "첫 답변")
        repo.insertMessage(conv.id, Role.USER, "두 번째 질문")

        val wire = assembler.assemble(conv.id)!!.toWireMessages()

        assertEquals(listOf("system", "user", "assistant", "user"), wire.map { it.role })
        assertEquals("두 번째 질문", wire.last().content)
    }

    @Test
    fun `no layers means no system message at all`() {
        val conv = repo.createConversation(model = "claude-sonnet-5")
        assertNull(assembler.assemble(conv.id)!!.system)
    }

    @Test
    fun `deleting a project keeps its chats and drops its memories`() {
        val project = repo.createProject(name = "업무", instructions = "지시문")
        repo.createMemory(MemoryScope.PROJECT, "사실", project.id)
        val conv = repo.createConversation(model = "claude-sonnet-5", projectId = project.id)

        repo.deleteProject(project.id)

        val kept = repo.getConversation(conv.id)
        assertNotNull(kept)
        assertNull(kept!!.projectId)
        assertEquals(0, repo.listMemories(MemoryScope.PROJECT, project.id).size)

        val assembled = assembler.assemble(conv.id)!!
        assertNull(assembled.project)
        assertEquals(
            "이 대화는 프로젝트에 속하지 않음",
            layer(assembled, ContextAssembler.LayerId.PROJECT_INSTRUCTIONS).reason,
        )
    }

    @Test
    fun `chat memories apply only to their own conversation`() {
        val a = repo.createConversation(model = "claude-sonnet-5")
        val b = repo.createConversation(model = "claude-sonnet-5")
        repo.createMemory(MemoryScope.CONVERSATION, "A 대화 메모", a.id)

        assertTrue(assembler.assemble(a.id)!!.system!!.contains("A 대화 메모"))
        assertNull(assembler.assemble(b.id)!!.system)
    }

    @Test
    fun `an errored turn is excluded from the history that goes back to the model`() {
        val conv = repo.createConversation(model = "claude-sonnet-5")
        repo.insertMessage(conv.id, Role.USER, "질문")
        val failed = repo.insertMessage(conv.id, Role.ASSISTANT, "부분 응답")
        repo.finishMessage(
            id = failed.id,
            content = "부분 응답",
            status = co.chatie.app.data.MessageStatus.ERROR,
            error = "끊김",
        )

        val history = assembler.assemble(conv.id)!!.history

        assertEquals(1, history.sent.size)
        assertEquals(Role.USER, history.sent.single().role)
    }
}
