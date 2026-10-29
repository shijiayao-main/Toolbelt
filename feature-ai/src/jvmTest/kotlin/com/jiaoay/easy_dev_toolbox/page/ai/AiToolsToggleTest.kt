package com.jiaoay.easy_dev_toolbox.page.ai

import com.jiaoay.easy_dev_toolbox.core.data.LocalDataManager
import kotlinx.coroutines.runBlocking
import java.nio.file.Files
import java.util.UUID
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import com.jiaoay.easy_dev_toolbox.core.llm.LlmConfigStore
import com.jiaoay.easy_dev_toolbox.core.navigation.AiEnabledSignal

/**
 * [AiToolsToggle] 状态流转与持久化（临时目录隔离，不触真实用户配置）。
 *
 * 钉死三件事：
 * 1. 默认 = 端点就绪度派生（未配置 ⇒ 关，已配置 ⇒ 开；显式写入值恒优先）；
 * 2. `setEnabled` 后状态流即时翻转且落盘；
 * 3. `restore()` 从持久化值回填——这是「跨重启保持」的核心断言（initializer 调它）。
 */
class AiToolsToggleTest {

    /** user.home 隔离（共享件 UserHomeIsolation——审计批 2026-09-27 收敛） */
    private val homeIsolation = com.jiaoay.testfixtures.UserHomeIsolation("edt-ai-test")

    private lateinit var originalStore: LocalDataManager

    @BeforeTest
    fun setUp() {
        homeIsolation.redirect()
                originalStore = LlmConfigStore.store
        // 隔离配方（单源见 AiSettingsViewModelTest.isolatedStore）：redirect 窗口内 eager
        // 解析数据目录并注入构造——DataStore 落盘路径在实例首读写才解析，不注入的话
        // 还原 user.home 后才执行的首操作会把 test_* 文件落进真实数据目录
        LlmConfigStore.store = LocalDataManager(
            dataStoreName = "test_toggle_${UUID.randomUUID()}",
            datastoreDir = com.jiaoay.easy_dev_toolbox.core.data.FileUtils.getDatastoreDir(),
        )
        AiToolsToggle.resetForTest()
    }

    @AfterTest
    fun tearDown() {
        homeIsolation.restore()
        AiToolsToggle.resetForTest()
        LlmConfigStore.store = originalStore
    }

    @Test
    fun `enabled starts false`() {
        // 🔴 fail-closed 初值：与持久层派生默认同向（未回填前不得显示为已启用）。
        //    首帧不可见该值——AiToolsRestoreInitializer 是高优层，App 在其全绿后才组合页面。
        assertEquals(false, AiToolsToggle.enabled.value)
    }

    @Test
    fun `restore reads back persisted value`() = runBlocking {
        // 🔴 反向锚（判别力关键）：显式落盘 true（端点留空，证明不是派生结果）⇒ restore 必须回填 true。
        //    若把 restore() 函数体删掉，本断言会红——这正是「跨重启保持」的可判别形态。
        LlmConfigStore.setAiEnabled(true)
        AiToolsToggle.resetForTest()
        assertEquals(false, AiToolsToggle.enabled.value, "重置后为默认值（fail-closed）")

        AiToolsToggle.restore()

        assertEquals(true, AiToolsToggle.enabled.value, "restore() 应从持久化值回填（跨重启保持）")
    }

    @Test
    fun `restore keeps explicit false despite later endpoint config`() = runBlocking {
        // 显式 false ⇒ 先落盘关闭；其后用户配好端点，restore 仍须保持关闭（显式值恒优先于派生）
        LlmConfigStore.setAiEnabled(false)
        LlmConfigStore.setApiKey("sk-test-123")
        LlmConfigStore.setModelId("deepseek-chat")
        AiToolsToggle.setEnabledForTest(true) // 先制造「开着」的状态，restore 才有判别力

        AiToolsToggle.restore()

        assertEquals(false, AiToolsToggle.enabled.value, "显式 false 不得被端点派生覆盖")
    }

    @Test
    fun `restore keeps false when endpoint unconfigured`() = runBlocking {
        // 空库（未写过开关 key、端点也未配）⇒ 派生为关
        AiToolsToggle.restore()
        assertEquals(false, AiToolsToggle.enabled.value, "无配置的新用户不得被显示为已启用")
    }

    @Test
    fun `restore derives true when endpoint configured`() = runBlocking {
        // 🔴 升级用户不丢入口的直接锚：配好端点但从未碰过开关 ⇒ restore 后为开
        LlmConfigStore.setApiKey("sk-test-123")
        LlmConfigStore.setModelId("deepseek-chat")

        AiToolsToggle.restore()

        assertEquals(true, AiToolsToggle.enabled.value, "已配置端点且从未碰开关 ⇒ 派生为开")
    }

    @Test
    fun `restore is idempotent`() = runBlocking {
        LlmConfigStore.setAiEnabled(false)

        AiToolsToggle.restore()
        AiToolsToggle.restore()

        assertEquals(false, AiToolsToggle.enabled.value, "重复恢复不应改变结果")
    }

    @Test
    fun `toggle round trip`() = runBlocking {
        AiToolsToggle.setEnabled(false)
        assertEquals(false, AiToolsToggle.enabled.value)
        assertEquals(false, LlmConfigStore.getAiEnabled())

        AiToolsToggle.setEnabled(true)

        assertEquals(true, AiToolsToggle.enabled.value)
        assertEquals(true, LlmConfigStore.getAiEnabled(), "显式 true 须落盘并保持（端点未配也不被派生覆盖）")
    }

    // ── WB-01（重设计批 1 · 2026-09-30）：真源 → :core 中立镜像槽 AiEnabledSignal ──

    @Test
    fun `setEnabled mirrors into AiEnabledSignal`() = runBlocking {
        AiToolsToggle.setEnabled(true)
        assertEquals(
            true,
            AiEnabledSignal.enabled.value,
            "真源翻转须同步镜像进 :core 槽（feature-logworkbench 等互不依赖域的实时消费面）",
        )
        AiToolsToggle.setEnabled(false)
        assertEquals(false, AiEnabledSignal.enabled.value, "关断同样同步镜像")
    }

    @Test
    fun `restore mirrors persisted value into AiEnabledSignal`() = runBlocking {
        LlmConfigStore.setAiEnabled(true)
        AiToolsToggle.resetForTest()
        assertEquals(false, AiEnabledSignal.enabled.value, "重置后镜像同为 fail-closed")

        AiToolsToggle.restore()

        assertEquals(
            true,
            AiEnabledSignal.enabled.value,
            "启动恢复须镜像回填（首帧前工作台 VM 构造即见正确值——AiToolsRestoreInitializer 时序锚）",
        )
    }
}
