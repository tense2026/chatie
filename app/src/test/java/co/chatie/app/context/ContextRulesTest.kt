package co.chatie.app.context

import co.chatie.app.data.Conversation
import co.chatie.app.data.Memory
import co.chatie.app.data.MemoryScope
import co.chatie.app.data.Message
import co.chatie.app.data.MessageStatus
import co.chatie.app.data.Project
import co.chatie.app.data.Role
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The contract for "which context reaches the model".
 * Every rule the Context Inspector claims on screen is asserted here.
 */
class ContextRulesTest {

    /* ------------------------------------------------------------ fixtures */

    private fun conversation(
        projectId: String? = null,
        systemOverride: String? = null,
        useProjectInstructions: Boolean = true,
        useProjectMemory: Boolean = true,
        useGlobalMemory: Boolean? = null,
    ) = Conversation(
        id = "cnv_1",
        projectId = projectId,
        title = "",
        model = "claude-sonnet-5",
        systemOverride = systemOverride,
        useProjectInstructions = useProjectInstructions,
        useProjectMemory = useProjectMemory,
        useGlobalMemory = useGlobalMemory,
        pinned = false,
        archived = false,
        createdAt = 0,
        updatedAt = 0,
    )

    private fun project(
        id: String = "prj_1",
        name: String = "업무",
        instructions: String = "",
        useGlobalMemory: Boolean = false,
    ) = Project(
        id = id,
        name = name,
        description = "",
        instructions = instructions,
        defaultModel = null,
        useGlobalMemory = useGlobalMemory,
        archived = false,
        createdAt = 0,
        updatedAt = 0,
    )

    private fun memory(text: String, scope: MemoryScope, enabled: Boolean = true) = Memory(
        id = "mem_${text.hashCode()}",
        scope = scope,
        projectId = if (scope == MemoryScope.PROJECT) "prj_1" else null,
        conversationId = if (scope == MemoryScope.CONVERSATION) "cnv_1" else null,
        text = text,
        enabled = enabled,
        source = "user",
        createdAt = 0,
        updatedAt = 0,
    )

    private fun message(
        role: Role,
        content: String,
        seq: Int,
        status: MessageStatus = MessageStatus.DONE,
    ) = Message(
        id = "msg_$seq",
        conversationId = "cnv_1",
        seq = seq,
        role = role,
        content = content,
        reasoning = null,
        model = null,
        status = status,
        error = null,
        promptTokens = null,
        completionTokens = null,
        credits = null,
        createdAt = seq.toLong(),
    )

    private fun input(
        conversation: Conversation = conversation(),
        project: Project? = null,
        baseInstructions: String = "",
        projectMemories: List<Memory> = emptyList(),
        globalMemories: List<Memory> = emptyList(),
        chatMemories: List<Memory> = emptyList(),
        looseChatsUseGlobalMemory: Boolean = true,
    ) = ContextRules.Input(
        conversation = conversation,
        project = project,
        baseInstructions = baseInstructions,
        projectMemories = projectMemories,
        globalMemories = globalMemories,
        chatMemories = chatMemories,
        looseChatsUseGlobalMemory = looseChatsUseGlobalMemory,
    )

    private fun layersOf(input: ContextRules.Input) = ContextRules.buildLayers(input)

    private fun layer(
        layers: List<ContextAssembler.Layer>,
        id: ContextAssembler.LayerId,
    ) = layers.first { it.id == id }

    private fun systemOf(input: ContextRules.Input) =
        ContextRules.composeSystem(ContextRules.buildLayers(input))

    /* --------------------------------------------------------------- tests */

    @Test
    fun `a chat with no project gets base instructions and global memories`() {
        val layers = layersOf(
            input(
                baseInstructions = "항상 한국어로",
                globalMemories = listOf(memory("사용자는 안드로이드 개발자", MemoryScope.GLOBAL)),
            ),
        )

        assertTrue(layer(layers, ContextAssembler.LayerId.BASE_INSTRUCTIONS).included)
        assertTrue(layer(layers, ContextAssembler.LayerId.GLOBAL_MEMORY).included)
        assertFalse(layer(layers, ContextAssembler.LayerId.PROJECT_INSTRUCTIONS).included)
        assertFalse(layer(layers, ContextAssembler.LayerId.PROJECT_MEMORY).included)

        val system = ContextRules.composeSystem(layers)!!
        assertTrue(system.contains("항상 한국어로"))
        assertTrue(system.contains("사용자는 안드로이드 개발자"))
    }

    @Test
    fun `a project is isolated from global memories by default`() {
        val layers = layersOf(
            input(
                conversation = conversation(projectId = "prj_1"),
                project = project(instructions = "격식체로 답하라"),
                projectMemories = listOf(memory("이 프로젝트는 B2B SaaS", MemoryScope.PROJECT)),
                globalMemories = listOf(memory("전역 사실", MemoryScope.GLOBAL)),
            ),
        )

        assertTrue(layer(layers, ContextAssembler.LayerId.PROJECT_INSTRUCTIONS).included)
        assertTrue(layer(layers, ContextAssembler.LayerId.PROJECT_MEMORY).included)

        val global = layer(layers, ContextAssembler.LayerId.GLOBAL_MEMORY)
        assertFalse(global.included)
        assertTrue(global.reason.contains("프로젝트는 기본적으로 격리"))
        assertFalse(ContextRules.composeSystem(layers)!!.contains("전역 사실"))
    }

    @Test
    fun `a project can opt in to global memories`() {
        val system = systemOf(
            input(
                conversation = conversation(projectId = "prj_1"),
                project = project(useGlobalMemory = true),
                globalMemories = listOf(memory("전역 사실", MemoryScope.GLOBAL)),
            ),
        )!!
        assertTrue(system.contains("전역 사실"))
    }

    @Test
    fun `a conversation switch overrides the project setting`() {
        val off = layer(
            layersOf(
                input(
                    conversation = conversation(projectId = "prj_1", useGlobalMemory = false),
                    project = project(useGlobalMemory = true),
                    globalMemories = listOf(memory("전역 사실", MemoryScope.GLOBAL)),
                ),
            ),
            ContextAssembler.LayerId.GLOBAL_MEMORY,
        )
        assertFalse(off.included)
        assertEquals("이 대화에서 끔", off.reason)

        val on = layer(
            layersOf(
                input(
                    conversation = conversation(projectId = "prj_1", useGlobalMemory = true),
                    project = project(useGlobalMemory = false),
                    globalMemories = listOf(memory("전역 사실", MemoryScope.GLOBAL)),
                ),
            ),
            ContextAssembler.LayerId.GLOBAL_MEMORY,
        )
        assertTrue(on.included)
        assertTrue(on.reason.startsWith("이 대화에서 켬"))
    }

    @Test
    fun `null on a conversation means inherit, and names the deciding level`() {
        val inProject = ContextRules.resolveGlobalMemory(
            conversation = conversation(projectId = "prj_1"),
            project = project(name = "업무", useGlobalMemory = true),
            looseChatsUseGlobalMemory = false,
        )
        assertTrue(inProject.on)
        assertTrue(inProject.reason.contains("프로젝트 '업무' 설정을 따름"))

        val loose = ContextRules.resolveGlobalMemory(
            conversation = conversation(),
            project = null,
            looseChatsUseGlobalMemory = false,
        )
        assertFalse(loose.on)
        assertTrue(loose.reason.contains("프로젝트 없는 대화 기본값"))
    }

    @Test
    fun `turning a project layer off is reported as this chat's choice`() {
        val layers = layersOf(
            input(
                conversation = conversation(
                    projectId = "prj_1",
                    useProjectInstructions = false,
                    useProjectMemory = false,
                ),
                project = project(instructions = "격식체로"),
                projectMemories = listOf(memory("프로젝트 사실", MemoryScope.PROJECT)),
            ),
        )

        listOf(
            ContextAssembler.LayerId.PROJECT_INSTRUCTIONS,
            ContextAssembler.LayerId.PROJECT_MEMORY,
        ).forEach { id ->
            val l = layer(layers, id)
            assertFalse(l.included)
            assertEquals("이 대화에서 끔", l.reason)
        }
        assertNull(ContextRules.composeSystem(layers))
    }

    @Test
    fun `an empty project instruction says so rather than looking switched off`() {
        val l = layer(
            layersOf(
                input(
                    conversation = conversation(projectId = "prj_1"),
                    project = project(name = "업무", instructions = "   "),
                ),
            ),
            ContextAssembler.LayerId.PROJECT_INSTRUCTIONS,
        )
        assertFalse(l.included)
        assertEquals("프로젝트 '업무'의 지시문이 비어 있음", l.reason)
    }

    @Test
    fun `project layers report that a chat has no project, not that they are off`() {
        val layers = layersOf(input(conversation = conversation(projectId = null)))
        listOf(
            ContextAssembler.LayerId.PROJECT_INSTRUCTIONS,
            ContextAssembler.LayerId.PROJECT_MEMORY,
        ).forEach { id ->
            assertEquals("이 대화는 프로젝트에 속하지 않음", layer(layers, id).reason)
        }
    }

    @Test
    fun `a disabled memory contributes nothing`() {
        val layers = layersOf(
            input(
                globalMemories = listOf(
                    memory("켠 사실", MemoryScope.GLOBAL),
                    memory("끈 사실", MemoryScope.GLOBAL, enabled = false),
                ),
            ),
        )
        val global = layer(layers, ContextAssembler.LayerId.GLOBAL_MEMORY)

        assertTrue(global.included)
        assertTrue(global.text.contains("켠 사실"))
        assertFalse(global.text.contains("끈 사실"))
        assertTrue(global.reason.endsWith("메모리 1개"))
    }

    @Test
    fun `all memories disabled is distinguished from the switch being off`() {
        val l = layer(
            layersOf(
                input(
                    globalMemories = listOf(
                        memory("끈 사실", MemoryScope.GLOBAL, enabled = false),
                    ),
                ),
            ),
            ContextAssembler.LayerId.GLOBAL_MEMORY,
        )
        assertFalse(l.included)
        assertEquals("켜져 있지만 전역 메모리가 없음", l.reason)
    }

    @Test
    fun `a system override replaces every composed layer`() {
        val layers = layersOf(
            input(
                conversation = conversation(projectId = "prj_1", systemOverride = "너는 번역기다"),
                project = project(instructions = "프로젝트 지시문"),
                baseInstructions = "기본",
                projectMemories = listOf(memory("프로젝트 사실", MemoryScope.PROJECT)),
                globalMemories = listOf(memory("전역 사실", MemoryScope.GLOBAL)),
                chatMemories = listOf(memory("대화 사실", MemoryScope.CONVERSATION)),
            ),
        )

        assertEquals("너는 번역기다", ContextRules.composeSystem(layers))
        assertTrue(layer(layers, ContextAssembler.LayerId.SYSTEM_OVERRIDE).included)
        listOf(
            ContextAssembler.LayerId.BASE_INSTRUCTIONS,
            ContextAssembler.LayerId.PROJECT_INSTRUCTIONS,
            ContextAssembler.LayerId.PROJECT_MEMORY,
            ContextAssembler.LayerId.GLOBAL_MEMORY,
            ContextAssembler.LayerId.CHAT_MEMORY,
        ).forEach { id ->
            val skipped = layer(layers, id)
            assertFalse("$id should be skipped", skipped.included)
            assertEquals("전용 시스템 프롬프트가 설정되어 건너뜀", skipped.reason)
        }
    }

    @Test
    fun `a blank override does not count as an override`() {
        val layers = layersOf(
            input(
                conversation = conversation(systemOverride = "   "),
                baseInstructions = "기본",
            ),
        )
        assertTrue(layers.none { it.id == ContextAssembler.LayerId.SYSTEM_OVERRIDE })
        assertTrue(layer(layers, ContextAssembler.LayerId.BASE_INSTRUCTIONS).included)
    }

    @Test
    fun `layer order is fixed and every layer is always reported`() {
        val layers = layersOf(input())
        assertEquals(
            listOf(
                ContextAssembler.LayerId.BASE_INSTRUCTIONS,
                ContextAssembler.LayerId.PROJECT_INSTRUCTIONS,
                ContextAssembler.LayerId.PROJECT_MEMORY,
                ContextAssembler.LayerId.GLOBAL_MEMORY,
                ContextAssembler.LayerId.CHAT_MEMORY,
            ),
            layers.map { it.id },
        )
    }

    @Test
    fun `the composed system block keeps layer order with visible headers`() {
        val system = systemOf(
            input(
                conversation = conversation(projectId = "prj_1"),
                project = project(instructions = "프로젝트 지시문"),
                baseInstructions = "기본 지시",
                chatMemories = listOf(memory("대화 사실", MemoryScope.CONVERSATION)),
            ),
        )!!
        val order = listOf("## 기본 지시문", "## 프로젝트 지시문", "## 대화 메모리")
            .map { system.indexOf(it) }
        assertTrue(order.all { it >= 0 })
        assertEquals(order.sorted(), order)
    }

    @Test
    fun `no applicable layers means no system message at all`() {
        assertNull(systemOf(input()))
    }

    @Test
    fun `chat memories do not need a project`() {
        val system = systemOf(
            input(chatMemories = listOf(memory("이 대화 한정 사실", MemoryScope.CONVERSATION))),
        )!!
        assertTrue(system.contains("이 대화 한정 사실"))
    }

    @Test
    fun `history trims the oldest messages to the budget and reports how many`() {
        val window = ContextRules.windowHistory(
            messages = listOf(
                message(Role.USER, "a".repeat(10), 1),
                message(Role.ASSISTANT, "b".repeat(10), 2),
                message(Role.USER, "c".repeat(10), 3),
            ),
            budget = 25,
        )

        assertEquals(2, window.sent.size)
        assertEquals(1, window.omitted)
        assertEquals("c".repeat(10), window.sent.last().content)
        assertTrue(window.chars <= 25)
    }

    @Test
    fun `the newest message is sent even when it alone exceeds the budget`() {
        val window = ContextRules.windowHistory(
            messages = listOf(message(Role.USER, "x".repeat(100), 1)),
            budget = 5,
        )
        assertEquals(1, window.sent.size)
        assertEquals(0, window.omitted)
    }

    @Test
    fun `an errored turn is excluded from the history sent back`() {
        val window = ContextRules.windowHistory(
            messages = listOf(
                message(Role.USER, "질문", 1),
                message(Role.ASSISTANT, "부분 응답", 2, status = MessageStatus.ERROR),
            ),
            budget = 1_000,
        )
        assertEquals(1, window.sent.size)
        assertEquals(Role.USER, window.sent.single().role)
        // the failed turn is not counted as "omitted for budget" either
        assertEquals(0, window.omitted)
    }

    @Test
    fun `history preserves turn order oldest to newest`() {
        val window = ContextRules.windowHistory(
            messages = listOf(
                message(Role.USER, "1", 1),
                message(Role.ASSISTANT, "2", 2),
                message(Role.USER, "3", 3),
            ),
            budget = 1_000,
        )
        assertEquals(listOf("1", "2", "3"), window.sent.map { it.content })
    }

    @Test
    fun `wire messages put the system block first and keep turn order`() {
        val layers = layersOf(input(baseInstructions = "기본"))
        val assembled = ContextAssembler.Assembled(
            conversation = conversation(),
            project = null,
            layers = layers,
            system = ContextRules.composeSystem(layers),
            history = ContextRules.windowHistory(
                messages = listOf(
                    message(Role.USER, "첫 질문", 1),
                    message(Role.ASSISTANT, "첫 답변", 2),
                    message(Role.USER, "두 번째 질문", 3),
                ),
                budget = 1_000,
            ),
        )

        val wire = assembled.toWireMessages()
        assertEquals(listOf("system", "user", "assistant", "user"), wire.map { it.role })
        assertEquals("두 번째 질문", wire.last().content)
    }

    @Test
    fun `with no system block the wire carries only the turns`() {
        val assembled = ContextAssembler.Assembled(
            conversation = conversation(),
            project = null,
            layers = layersOf(input()),
            system = null,
            history = ContextRules.windowHistory(
                messages = listOf(message(Role.USER, "질문", 1)),
                budget = 1_000,
            ),
        )
        assertEquals(listOf("user"), assembled.toWireMessages().map { it.role })
    }
}
