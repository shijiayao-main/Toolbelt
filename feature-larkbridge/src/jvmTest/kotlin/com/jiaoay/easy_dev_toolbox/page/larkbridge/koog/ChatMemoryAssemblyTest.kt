// 桥接链长期记忆装配面（memory 四件进飞书会话）：
// 1. memoryDir 派生纯函数（per-chatId 多用户隔离 + chatId 清洗防路径注入 + 惰性不落盘）；
// 2. 装配门控（dataRoot 接线 + chatId 非空才注册；条件注册不进 ALWAYS_REGISTERED_TOOLS）；
// 3. append → view 读回闭环（经注册表装配的工具真实读写 chatId 子目录）；
// 4. 提示词面（memory 行列在会话工具段、文件围栏计数句不含 memory、listed() 单源门控）；
// 5. 注入链端到端（homeDataRootProvider → run → build → 注册表/system 提示词）。
package com.jiaoay.easy_dev_toolbox.page.larkbridge.koog

import ai.koog.prompt.Prompt
import ai.koog.prompt.executor.llms.MultiLLMPromptExecutor
import ai.koog.prompt.executor.model.ResolvedModel
import ai.koog.prompt.message.Message
import ai.koog.prompt.message.ResponseMetaInfo
import ai.koog.utils.time.KoogClock
import ai.koog.agents.core.tools.ToolDescriptor
import com.jiaoay.easy_dev_toolbox.core.data.LocalDataManager
import com.jiaoay.easy_dev_toolbox.core.llm.KoogAgentSafetyPreset
import com.jiaoay.easy_dev_toolbox.core.llm.LlmConfigStore
import com.jiaoay.easy_dev_toolbox.core.llm.tools.MemoryTools
import com.jiaoay.lark.bridge.agent.AgentRequest
import java.io.File
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.coroutines.runBlocking

class ChatMemoryAssemblyTest {

    private lateinit var originalStore: LocalDataManager
    private val homeIsolation = com.jiaoay.testfixtures.UserHomeIsolation("chat-memory-test")

    @BeforeTest
    fun setUpIsolatedStore() {
        homeIsolation.redirect()
        originalStore = LlmConfigStore.store
        LlmConfigStore.store = LocalDataManager(dataStoreName = "test_chatmem_${java.util.UUID.randomUUID()}")
        com.jiaoay.easy_dev_toolbox.core.data.db.Database.resetForTest()
    }

    @AfterTest
    fun tearDownStore() {
        LlmConfigStore.store = originalStore
        homeIsolation.restore()
    }

    // ── memoryDir 派生（纯函数直测） ──

    @Test
    fun `memoryDir 按 chatId 派生到 lark-memory 子目录且纯函数不落盘`() {
        val root = tempRoot()
        assertEquals(
            File(File(File(root, "lark"), "memory"), "oc_abc123"),
            BridgeToolRegistry.chatMemoryDir(root, "oc_abc123"),
            "派生路径必须是 <dataRoot>/lark/memory/<chatId>",
        )
        assertNull(BridgeToolRegistry.chatMemoryDir(null, "oc_1"), "dataRoot 未接线 ⇒ null（fail-closed）")
        assertNull(BridgeToolRegistry.chatMemoryDir(root, "  "), "空 chatId（2 参入口/存量测试）⇒ null")
        // 纯函数惰性：派生后不创建任何目录（未用 memory 的会话不落盘）
        BridgeToolRegistry.chatMemoryDir(root, "oc_lazy")
        assertTrue(!File(File(File(root, "lark"), "memory"), "oc_lazy").exists(), "派生不得创建目录（惰性创建归 append 首写）")
        assertTrue(!File(root, "lark").exists(), "连 lark 父目录也不得预建")
    }

    @Test
    fun `非法 chatId 折叠清洗防路径注入且超长截断`() {
        val root = tempRoot()
        val dirty = BridgeToolRegistry.chatMemoryDir(root, "oc_a/b\\c d")
        assertEquals(
            File(File(File(root, "lark"), "memory"), "oc_a_b_c_d"),
            dirty,
            "非 ASCII 字母数字/下划线/连字符字符须折叠为 _（保形防碰撞）",
        )
        // 路径注入防御：`..` 与分隔符全部折叠，派生结果恒在 memory 根内（不逃逸成父目录）
        val injected = BridgeToolRegistry.chatMemoryDir(root, "../../etc/passwd")
        assertTrue(injected != null, "异常 chatId 仍须派生（折叠而非拒绝），供确定性定位")
        assertEquals(File(File(root, "lark"), "memory"), injected.parentFile, "清洗后目录必须落在 memory 根内")
        assertTrue(injected.name.none { it == '.' || it == '/' || it == '\\' }, "清洗后段名不得残留 . 或路径分隔符：${injected.name}")
        // 超长截断（上限 64）：目录段不超文件名安全长度
        val longId = "oc_" + "a".repeat(100)
        assertEquals(64, BridgeToolRegistry.chatMemoryDir(root, longId)!!.name.length, "超长 chatId 须截断到上限")
    }

    @Test
    fun `不同 chatId 派生不同目录（多用户隔离锚）`() {
        val root = tempRoot()
        val a = BridgeToolRegistry.chatMemoryDir(root, "oc_user_a")
        val b = BridgeToolRegistry.chatMemoryDir(root, "oc_user_b")
        assertTrue(a != b, "白名单不同成员的聊天背景必须目录隔离（单一目录 = 跨用户泄漏）")
        assertEquals(a, BridgeToolRegistry.chatMemoryDir(root, "oc_user_a"), "同 chatId 跨 run 派生稳定（记忆跨会话存续的前提）")
    }

    // ── 装配门控 + append → view 读回闭环（经 BridgeToolRegistry.build） ──

    @Test
    fun `dataRoot 接线且 chatId 非空才注册 memory 四件且不进常备清单`() {
        val root = tempRoot()
        fun names(homeDataRoot: File?, chatId: String): Set<String> = BridgeToolRegistry.build(
            endpointConfig = endpoint(),
            request = request(opaque = BareReplySink()),
            chatId = chatId,
            homeDataRoot = homeDataRoot,
        ).tools.map { it.name }.toSet()

        val on = names(root, "oc_gate")
        assertTrue(
            listOf("view_memory", "append_memory", "replace_memory", "delete_memory").all { it in on },
            "接线 + chatId 非空 ⇒ memory 四件注册（纯聊天消息同样装配）：${on.sorted()}",
        )
        assertTrue("view_memory" !in names(null, "oc_gate"), "dataRoot 未接线 ⇒ 不注册（fail-closed）")
        assertTrue("view_memory" !in names(root, ""), "空 chatId ⇒ 不注册")
        assertTrue(
            BridgeToolRegistry.ALWAYS_REGISTERED_TOOLS.none { it.endsWith("_memory") },
            "条件注册不进常备清单（remind_user/L2 同口径）",
        )
    }

    @Test
    fun `append 后 view 经注册表读回闭环且产物落 chatId 子目录`() = runBlocking {
        val root = tempRoot()
        val registry = BridgeToolRegistry.build(
            endpointConfig = endpoint(),
            request = request(opaque = BareReplySink()),
            chatId = "oc_roundtrip",
            homeDataRoot = root,
        )
        val chatDir = File(File(File(root, "lark"), "memory"), "oc_roundtrip")

        val append = registry.tools.filterIsInstance<MemoryTools.AppendMemoryTool>().single()
        val receipt = append.execute(
            MemoryTools.AppendMemoryTool.Args(
                fileName = "device-profile.md",
                title = "设备背景",
                hook = "目标设备是VendorX Demo-1",
                content = "这台目标设备是VendorX Demo-1；看 alog 先解密再按 Tag 过滤。",
            ),
        )
        assertTrue("已" in receipt, "append 应成功：$receipt")
        assertTrue(File(chatDir, "device-profile.md").isFile, "记忆文件必须落在 chatId 派生目录内")

        val view = registry.tools.filterIsInstance<MemoryTools.ViewMemoryTool>().single()
        val index = view.execute(MemoryTools.ViewMemoryTool.Args())
        assertTrue("device-profile.md" in index && "Demo-1" in index, "view 索引须能读回登记行：$index")
        val entry = view.execute(MemoryTools.ViewMemoryTool.Args(fileName = "device-profile.md"))
        assertTrue("VendorX Demo-1" in entry, "view 正文须能读回记忆全文：$entry")

        // 闭环的隔离面：另一 chatId 的注册表读不到本会话记忆
        val otherRegistry = BridgeToolRegistry.build(
            endpointConfig = endpoint(),
            request = request(opaque = BareReplySink()),
            chatId = "oc_other",
            homeDataRoot = root,
        )
        val otherView = otherRegistry.tools.filterIsInstance<MemoryTools.ViewMemoryTool>().single()
        val otherIndex = otherView.execute(MemoryTools.ViewMemoryTool.Args())
        assertTrue("Demo-1" !in otherIndex, "跨 chatId 不得读到其他会话的记忆（多用户隔离）")
    }

    // ── 提示词面（会话工具段 + 计数句不含 memory + listed 门控） ──

    @Test
    fun `memory 提示词行挂 listed 门控且带跨会话语义句`() {
        val off = buildKoogSystemPrompt(
            request = request(),
            knowledgeAvailable = false,
            stylePrompt = null,
            safetyPrompt = KoogAgentSafetyPreset.DEFAULT.prompt,
            registeredToolNames = BridgeToolRegistry.ALWAYS_REGISTERED_TOOLS.toSet(),
        )
        assertTrue("view_memory" !in off, "未注册的 memory 工具不得出现在提示词（listed 单源门控）")
        val on = buildKoogSystemPrompt(
            request = request(),
            knowledgeAvailable = false,
            stylePrompt = null,
            safetyPrompt = KoogAgentSafetyPreset.DEFAULT.prompt,
            registeredToolNames = BridgeToolRegistry.ALWAYS_REGISTERED_TOOLS.toSet() +
                setOf("view_memory", "append_memory", "replace_memory", "delete_memory"),
        )
        assertTrue("view_memory(file_name)" in on, "注册后须列出")
        assertTrue("跨会话存续" in on, "memory 行须带「长期记忆跨会话存续」语义句")
        assertTrue("会话开始时先无参读索引" in on, "view_memory 行须指引会话开始先读索引")
        assertTrue("临时上下文不要写进长期记忆" in on, "须写清何时不用（临时上下文不写）")
    }

    @Test
    fun `memory 行不进文件处理工具段-围栏计数句仍为二十件`() {
        val ws = File(java.nio.file.Files.createTempDirectory("chatmem-ws").toFile(), "referenced.log")
            .apply { writeText("x"); deleteOnExit() }
        val prompt = buildKoogSystemPrompt(
            request = request(
                opaque = com.jiaoay.lark.bridge.pipeline.DispatchPayload(
                    reply = BareReplySink(),
                    allowedFiles = listOf(ws),
                    xlogPrivateKey = "",
                    messageId = "om_mem",
                    chatId = "oc_mem",
                    quotedMessageId = null,
                    replyLedger = null,
                ),
            ),
            knowledgeAvailable = false,
            stylePrompt = null,
            safetyPrompt = null,
        )
        // memory 四件非文件围栏族：文件段计数句与列出行数保持二十件、段内不含 memory 行
        val section = prompt.substringAfter("文件处理工具").substringBefore("文件围栏")
        val listedLines = section.lineSequence().count { it.startsWith("- ") }
        assertEquals(20, listedLines, "文件处理工具段列出行数与围栏句「二十件」必须一致（memory 行不得进本段）")
        assertTrue("以上二十件读类工具" in prompt, "围栏计数句不变")
        assertTrue("view_memory" !in section, "memory 行必须列在会话工具段而非文件处理工具段")
        assertTrue("- view_memory(file_name)" in prompt, "会话工具段须列出 memory 行")
    }

    // ── 注入链端到端（homeDataRootProvider → run → build → 注册表/system 提示词） ──

    @Test
    fun `homeDataRootProvider 经真实 run 链路装配 memory 且空 chatId 不装配`() = runBlocking {
        LlmConfigStore.setApiKey("sk-test")
        LlmConfigStore.setModelId("gpt-test")
        val root = tempRoot()
        val captured = ToolCapturingExecutor()
        val runner = KoogAgentRunner(
            homeDataRootProvider = { root },
            executorFactory = { captured to com.jiaoay.easy_dev_toolbox.core.llm.LlmClientFactory.model("fake-model") },
        )
        runner.refreshEndpoint()
        runner.run(request(opaque = BareReplySink()), chatId = "oc_e2e", senderOpenId = "ou_1", progress = null) { }
        assertTrue("view_memory" in captured.lastToolNames, "provider 接线 + chatId 非空 ⇒ run 链路须装配 memory（删任一跳透传本条即红）")
        assertTrue("view_memory(file_name)" in captured.lastSystemPrompt, "system 提示词须同步列出 memory 行")
        // 2 参入口（chatId 空）⇒ 不装配（fail-closed 与提示词同步省）
        runner.run(request(opaque = BareReplySink())) { }
        assertTrue("view_memory" !in captured.lastToolNames, "空 chatId ⇒ 不注册")
        assertTrue("view_memory" !in captured.lastSystemPrompt, "空 chatId ⇒ 提示词不列（能力面与注册表一致）")
    }

    // ── 测试件 ──

    /** 每用例独立数据根（deleteOnExit 兜底，不触 ~/.easy_dev_toolbox）。 */
    private fun tempRoot(): File =
        File(System.getProperty("java.io.tmpdir"), "chatmem-root-${java.util.UUID.randomUUID()}").apply { deleteOnExit() }

    private fun endpoint() = com.jiaoay.easy_dev_toolbox.core.llm.LlmEndpointConfig(
        baseUrl = "http://localhost:1",
        apiKey = "sk-test",
        modelId = "fake-model",
        timeoutSeconds = 30,
    )

    private fun request(
        prompt: String = "这台目标设备是VendorX Demo-1，帮我记住",
        opaque: Any? = null,
    ) = AgentRequest(
        sessionKey = "sess-chatmem",
        prompt = prompt,
        totalTimeoutMillis = 30_000,
        idleTimeoutMillis = 120_000,
        opaque = opaque,
        persona = "",
        systemPrompt = "",
    )

    /** 最小 ReplySink fake（纯聊天消息形态；memory 装配与 opaque 形态无关）。 */
    private class BareReplySink : com.jiaoay.lark.bridge.pipeline.ReplySink {
        override val target: com.jiaoay.lark.gateway.LarkTarget = com.jiaoay.lark.gateway.LarkTarget.Chat("oc_mem")
        override val chatId: String? = "oc_mem"

        override suspend fun text(body: String) = com.jiaoay.lark.gateway.SendResult(ok = true, messageId = "m")
        override suspend fun markdown(body: String) = com.jiaoay.lark.gateway.SendResult(ok = true, messageId = "m")
        override suspend fun card(spec: com.jiaoay.lark.gateway.CardSpec) =
            com.jiaoay.lark.gateway.SendResult(ok = true, messageId = "m")
        override suspend fun file(file: java.nio.file.Path, displayName: String?) =
            com.jiaoay.lark.gateway.SendResult(ok = true, messageId = "m")
        override suspend fun image(source: java.nio.file.Path) =
            com.jiaoay.lark.gateway.SendResult(ok = true, messageId = "m")
        override suspend fun replyInThread(body: String) =
            com.jiaoay.lark.gateway.SendResult(ok = true, messageId = "m")
        override suspend fun recallMessage(messageId: String): Boolean = true
        override fun stream(initial: com.jiaoay.lark.gateway.CardSpec): com.jiaoay.lark.gateway.CardStreamSink =
            throw UnsupportedOperationException("本测试不覆盖流式卡")
        override suspend fun react(messageId: String, emojiKey: String, add: Boolean): Boolean = true
        override suspend fun updateExistingCard(messageId: String, spec: com.jiaoay.lark.gateway.CardSpec) =
            com.jiaoay.lark.gateway.SendResult(ok = true, messageId = messageId)
    }

    /** 捕获注册表名集与 system 提示词的 fake executor（重写 ResolvedModel 重载——绕开真 client）。 */
    private class ToolCapturingExecutor : MultiLLMPromptExecutor(
        ai.koog.prompt.executor.clients.openai.OpenAILLMClient(
            apiKey = "sk-test",
            settings = ai.koog.prompt.executor.clients.openai.OpenAIClientSettings(
                baseUrl = "http://localhost:1",
                timeoutConfig = ai.koog.prompt.executor.clients.ConnectionTimeoutConfig(requestTimeoutMillis = 1_000),
            ),
        ),
    ) {
        var lastToolNames: Set<String> = emptySet()
        var lastSystemPrompt: String = ""

        override suspend fun execute(
            prompt: Prompt,
            model: ResolvedModel,
            tools: List<ToolDescriptor>,
        ): Message.Assistant {
            lastToolNames = tools.map { it.name }.toSet()
            lastSystemPrompt = prompt.messages
                .firstOrNull { it.role == Message.Role.System }
                ?.textContent().orEmpty()
            return Message.Assistant(
                content = "好的，我记住了。",
                metaInfo = ResponseMetaInfo.create(KoogClock.System),
            )
        }
    }
}
