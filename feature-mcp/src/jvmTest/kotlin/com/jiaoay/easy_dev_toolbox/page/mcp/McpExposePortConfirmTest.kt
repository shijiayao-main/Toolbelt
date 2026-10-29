package com.jiaoay.easy_dev_toolbox.page.mcp

import com.jiaoay.easy_dev_toolbox.core.data.FileUtils
import com.jiaoay.easy_dev_toolbox.core.data.LocalDataManager
import com.jiaoay.easy_dev_toolbox.core.llm.LlmConfigStore
import com.jiaoay.testfixtures.UserHomeIsolation
import java.io.File
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * SYS-08 · 重设计批 4：端口即时重启加断连确认（在途会话 >0）。
 *
 * 旧缺陷：运行中提交端口立即 stopNow→startNow——在途 agent 会话 >0 时静默掐断
 * 全部外部会话（runCancelHook 收口），用户与外部 client 两边都无预警。
 *
 * 新形态：确认 UI 落页面局部态（McpExposePage pendingPort + AppConfirmDialog——
 * AiSettingsPage pendingXxx 同款先例），判定 = `status.running && activeRuns > 0`：
 * - 在途 >0：onCommit 拦截进 pendingPort，弹确认（正文含在途数）；
 *   取消 = 丢弃 pendingPort，端口与运行态均不变；确认 = 走 VM commitPort 重启链；
 * - 在途 0 / 未运行：零摩擦直提（现行为回归）。
 *
 * 🔴 VM 零改动（scope 收窄如实上报）：卡面 scope 列 McpExposeViewModel.kt，但确认
 * 状态是 Compose 局部态、判定数据面（status/activeRunsFlow）批 1 SYS-04 已就绪——
 * VM 拆两段方案会把页面态漏进 VM，纯页面实现为最轻配方（侦察报告裁定）。
 *
 * 验收面（卡面 acceptance 两项）：
 * 1. 行为回归（VM 直测）：在途 0 时提交端口 = 现行为（无弹窗即时提交——本测试钉死
 *    commitPort 在无在途时不再有额外确认缝）；
 * 2. 拦截判定 + 弹窗两路（源码锚）：McpExposePage 含判定行 + AppConfirmDialog
 *    （onConfirm 走 commitPort / onDismiss 清 pendingPort = 取消零变化）。
 */
@OptIn(ExperimentalCoroutinesApi::class)
class McpExposePortConfirmTest {

    // ── ① 行为回归：在途 0 提交端口 = 即时提交（零确认缝） ──

    /** user.home 隔离纪律同 McpExposeViewModelLogStampTest（VM init 读 DataStore）。 */
    @Test
    fun `commitPort with zero active runs commits immediately without confirm gate`() {
        val home = UserHomeIsolation("edt-mcp-port-confirm-test")
        val dispatcher = StandardTestDispatcher()
        // DataStore IO 受控 scope（LocalDataManager 的 datastoreScope 注入缝）：Unconfined
        // 让 DataStore 文件 IO 在调用线程同步落定——drain 即全静默（见 LogStampTest 同款）
        val datastoreScope = CoroutineScope(Dispatchers.Unconfined + SupervisorJob())
        var originalStore: LocalDataManager? = null
        try {
            Dispatchers.setMain(dispatcher)
            home.redirect()
            originalStore = LlmConfigStore.store
            // redirect 窗口内 eager 解析并钉死目录（LocalDataManager 构造参数契约），
            // 还原 user.home 后的迟到首操作不得落错目录
            LlmConfigStore.store = LocalDataManager(
                dataStoreName = "test_mcp_port_confirm_${java.util.UUID.randomUUID()}",
                datastoreDir = FileUtils.getDatastoreDir(),
                datastoreScope = datastoreScope,
            )
            McpServerManager.resetForTest() // 状态面 STOPPED → 未运行分支（零真 server）
            McpRunRegistry.resetForTest()

            val vm = McpExposeViewModel()
            dispatcher.scheduler.advanceUntilIdle()
            assertEquals(0, McpRunRegistry.activeCount, "前置：在途数为 0")
            assertFalse(McpServerManager.status.value.running, "前置：服务未运行")

            vm.commitPort(9000)
            dispatcher.scheduler.advanceUntilIdle()
            val deadline = System.currentTimeMillis() + 5_000
            while (vm.logLines.value.isEmpty()) {
                check(System.currentTimeMillis() < deadline) { "等待超时：端口提交日志未落" }
                Thread.sleep(50)
                dispatcher.scheduler.advanceUntilIdle()
            }

            assertEquals(
                1,
                vm.logLines.value.size,
                "在途 0 时端口提交零摩擦——VM 直达（无任何确认拦截）",
            )
            assertTrue(
                "端口已保存（9000，服务未运行）" in vm.logLines.value.single(),
                "现行为回归：文案不变",
            )
            assertEquals(9000, vm.port.value, "端口已提交生效")
        } finally {
            // DataStore IO 已随 Unconfined datastoreScope 同步落定——排空即全静默
            dispatcher.scheduler.advanceUntilIdle()
            originalStore?.let { LlmConfigStore.store = it }
            Dispatchers.resetMain()
            home.restoreOnly()
            McpServerManager.resetForTest()
            McpRunRegistry.resetForTest()
        }
    }

    // ── ② 拦截判定 + 确认/取消两路（页面源码锚） ──

    @Test
    fun `page intercepts port commit when running with active runs`() {
        val text = pageSource()
        assertTrue(
            "if (status.running && activeRuns > 0) pendingPort = newPort else vm.commitPort(newPort)" in text,
            "onCommit 必须按「运行中 && 在途 >0」分流拦截（SYS-08 判定行）——" +
                "在途 0 / 未运行保持零摩擦直提。",
        )
    }

    @Test
    fun `page confirm dialog wires restart and cancel paths`() {
        val text = pageSource()
        val dialogAnchor = text.indexOf("AppConfirmDialog(")
        assertTrue(dialogAnchor >= 0, "McpExposePage 必须挂 AppConfirmDialog（断连确认）。")
        val dialogBlock = text.substring(dialogAnchor, minOf(dialogAnchor + 900, text.length))

        // 确认路：走 VM commitPort 重启链（VM 零改动——现重启链即确认后动作）
        assertTrue(
            "vm.commitPort(newPort)" in dialogBlock,
            "确认路必须走 vm.commitPort（现重启链零改动承接）。",
        )
        // 取消路：丢弃 pendingPort = 端口与运行态均不变（不触 VM）
        assertTrue(
            "onDismiss = { pendingPort = null }" in dialogBlock,
            "取消路必须只清 pendingPort（端口与运行态均不变——不触 commitPort）。",
        )
        // 弹窗正文含在途数（卡面文案：中止全部 N 个在途 agent 会话）
        assertTrue(
            "中止全部 \$activeRuns 个在途 agent 会话" in dialogBlock,
            "弹窗正文必须含在途数（「中止全部 N 个在途 agent 会话」——N 随 activeRuns 动态）。",
        )
        // 破坏性动词对齐卡面（confirmLabel = "重启"）
        assertTrue(
            "confirmLabel = \"重启\"" in dialogBlock,
            "确认钮文案必须「重启」（卡面：破坏性动词）。",
        )
    }

    // ── 源码锚工具（缺失即红——重命名后须同步） ──

    private fun pageSource(): String {
        val root = locateProjectRoot()
        val file = File(root, PAGE_REL_PATH)
        assertTrue(file.isFile, "源码锚文件缺失：$PAGE_REL_PATH（重命名/移动后请同步本守护）")
        return file.readText()
    }

    /** 双时钟轮询工具已在用例内联（awaitCondition 见 commitPort 用例）。 */
    private fun locateProjectRoot(): File {
        var dir = File(System.getProperty("user.dir")).absoluteFile
        var hops = 0
        while (dir != null && hops < 8) {
            if (File(dir, "settings.gradle.kts").isFile) return dir
            dir = dir.parentFile
            hops++
        }
        error("无法定位项目根（user.dir=${System.getProperty("user.dir")}）")
    }

    private companion object {
        const val PAGE_REL_PATH =
            "feature-mcp/src/jvmMain/kotlin/com/jiaoay/easy_dev_toolbox/page/mcp/McpExposePage.kt"
    }
}
