package com.jiaoay.easy_dev_toolbox.page.larkbridge.cockpit

import com.jiaoay.lark.bridge.observe.BridgeConnectionStatus
import com.jiaoay.lark.bridge.observe.BridgeStatusSnapshot
import com.jiaoay.lark.bridge.pipeline.PipelineCounters
import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * [CockpitLogic] 快速开始状态机契约（2026-09-09 产品优化；P5-5c 五步化）。
 *
 * 五步派生（第 0 步 = AI 端点就绪）+ 向导显隐（首次用户判定）+ 顺序陷阱警告显隐。纯函数，零 IO。
 */
class CockpitLogicTest {

    /** 构造快照的测试辅助：用真实 [PipelineCounters] 造快照（22 字段自动为 0，只改关心的）。 */
    private fun snapshot(
        running: Boolean = false,
        connection: BridgeConnectionStatus = BridgeConnectionStatus.Idle,
        intakeReceived: Long = 0L,
    ): BridgeStatusSnapshot {
        val counters = PipelineCounters()
        repeat(intakeReceived.toInt()) { counters.recordReceived() }
        return BridgeStatusSnapshot(
            running = running,
            connection = connection,
            confirm = com.jiaoay.lark.bridge.confirm.ConfirmRuntimeStatus.Disabled,
            counters = counters.snapshot(),
        )
    }

    // ── 向导显隐（LB-01 · 重设计批 9：存续改「后台清单未完成」驱动）──

    @Test
    fun freshUserSeesQuickStart() {
        assertTrue(
            CockpitLogic.showQuickStart(
                appId = "", appSecret = "", configFileExists = false,
                backendChecklistDone = true, firstMessageReceived = true,
            ),
            "凭据全空且无配置文件 = 全新用户 ⇒ 显示向导（两派生参对 freshUser 判定零影响）",
        )
    }

    @Test
    fun halfFilledCredentialsDoNotShowQuickStart() {
        // 旧「凭据已开始填写 ⇒ 收起」语义保留：半填（有 appId 无 secret）不构成存续
        assertFalse(
            CockpitLogic.showQuickStart(
                appId = "cli_x", appSecret = "", configFileExists = false,
                backendChecklistDone = false, firstMessageReceived = false,
            ),
            "凭据半填 ⇒ 不显示向导（存续只对凭据就绪成立）",
        )
        assertFalse(
            CockpitLogic.showQuickStart(
                appId = "", appSecret = "", configFileExists = true,
                backendChecklistDone = false, firstMessageReceived = false,
            ),
            "配置文件已存在但凭据全空 ⇒ 不显示向导（credentialsReady 不满足）",
        )
    }

    @Test
    fun credentialsReadyWithoutChecklistKeepsGuideCollapsedAlive() {
        // LB-01 验收：扫码回填凭据（credentialsReady）但后台清单未勾、无首条消息 ⇒ 向导折叠存续
        assertTrue(
            CockpitLogic.showQuickStart(
                appId = "cli_x", appSecret = "sec", configFileExists = false,
                backendChecklistDone = false, firstMessageReceived = false,
            ),
            "凭据就绪 + 清单未勾 + 无首条 ⇒ true（折叠存续态，清单与警告可展开查看）",
        )
    }

    @Test
    fun credentialsReadyWithChecklistDoneDismissesGuide() {
        // 清单勾完 ⇒ 向导消失（LB-01 存续出口一）
        assertFalse(
            CockpitLogic.showQuickStart(
                appId = "cli_x", appSecret = "sec", configFileExists = false,
                backendChecklistDone = true, firstMessageReceived = false,
            ),
            "凭据就绪 + 清单勾完 ⇒ false（向导完成）",
        )
        // 首条消息已收 ⇒ 向导消失（LB-01 存续出口二——即使清单没勾全，真实收发已验证）
        assertFalse(
            CockpitLogic.showQuickStart(
                appId = "cli_x", appSecret = "sec", configFileExists = false,
                backendChecklistDone = false, firstMessageReceived = true,
            ),
            "凭据就绪 + 首条已收 ⇒ false（真实收发验证即完成）",
        )
    }

    // ── 五步派生 ──

    @Test
    fun stepsDeriveFromConfigAndSnapshot() {
        // 端点未配：第 0 步未完成，其余四步全真也不算 allDone
        val endpointMissing = CockpitLogic.steps(
            "cli_x", "sec",
            snapshot = snapshot(
                running = true,
                connection = BridgeConnectionStatus.Connected(botOpenId = "ou_bot"),
                intakeReceived = 1L,
            ),
            backendChecklistDone = true,
            aiEndpointReady = false,
        )
        assertTrue(endpointMissing.credentialsReady)
        assertTrue(endpointMissing.started)
        assertTrue(endpointMissing.firstMessageReceived)
        assertFalse(endpointMissing.aiEndpointReady, "端点未配 ⇒ 第 0 步未完成")
        assertFalse(endpointMissing.allDone, "四步全真但端点未配 ⇒ allDone 仍为 false")

        // 端点就绪：五步全真 ⇒ allDone
        val allReady = CockpitLogic.steps(
            "cli_x", "sec",
            snapshot = snapshot(
                running = true,
                connection = BridgeConnectionStatus.Connected(botOpenId = "ou_bot"),
                intakeReceived = 1L,
            ),
            backendChecklistDone = true,
            aiEndpointReady = true,
        )
        assertTrue(allReady.aiEndpointReady)
        assertTrue(allReady.allDone, "五步全真 ⇒ allDone")
    }

    @Test
    fun firstMessageRequiresConnectedAndNonZeroIntake() {
        // 已连接但没收到过消息（后台订阅没配 ⇒ 石沉大海的形态）
        val connectedButSilent = CockpitLogic.steps(
            "cli_x", "sec",
            snapshot = snapshot(
                running = true,
                connection = BridgeConnectionStatus.Connected(botOpenId = "ou_bot"),
                intakeReceived = 0L,
            ),
            backendChecklistDone = true,
            aiEndpointReady = true,
        )
        assertFalse(connectedButSilent.firstMessageReceived, "已连接但 intakeReceived=0 ⇒ step4 未完成")

        // 运行中但连接态非 Connected（启动≠已连接）
        val notConnected = CockpitLogic.steps(
            "cli_x", "sec",
            snapshot = snapshot(running = true, connection = BridgeConnectionStatus.Connecting, intakeReceived = 5L),
            backendChecklistDone = true,
            aiEndpointReady = true,
        )
        assertFalse(notConnected.firstMessageReceived, "非 Connected 态 ⇒ step4 未完成（防误判完成）")
    }

    @Test
    fun aiEndpointStepIsPureInjectionNotDerived() {
        // 第 0 步是页面注入值，不随快照/凭据派生：快照全空但端点已配 ⇒ 该步为真
        val configuredOnly = CockpitLogic.steps(
            "", "",
            snapshot = null,
            backendChecklistDone = false,
            aiEndpointReady = true,
        )
        assertTrue(configuredOnly.aiEndpointReady, "端点就绪纯由注入值决定（未装配 false 由 VM 层保证）")
        assertFalse(configuredOnly.credentialsReady, "凭据空 ⇒ step1 未完成（注入值不影响其余步）")
        assertFalse(configuredOnly.started, "快照 null ⇒ step2 未完成")
        assertFalse(configuredOnly.allDone)
    }

    // ── 顺序陷阱警告 ──

    @Test
    fun orderTrapWarningVisibleUntilStarted() {
        val notStarted = CockpitLogic.steps(
            "cli_x", "sec",
            snapshot = null,
            backendChecklistDone = false,
            aiEndpointReady = false,
        )
        assertTrue(CockpitLogic.orderTrapWarningVisible(notStarted), "未启动 ⇒ 常驻顺序陷阱警告")

        val started = CockpitLogic.steps(
            "cli_x", "sec",
            snapshot = snapshot(running = true, connection = BridgeConnectionStatus.Connecting),
            backendChecklistDone = false,
            aiEndpointReady = false,
        )
        assertFalse(CockpitLogic.orderTrapWarningVisible(started), "已启动 ⇒ 警告消失（可安全去后台改订阅）")
    }

    @Test
    fun orderTrapWarningUnaffectedByEndpointStep() {
        // P5-5c 口径钉死：顺序陷阱警告仍只看 started，与第 0 步（端点）无关
        val endpointReadyButNotStarted = CockpitLogic.steps(
            "cli_x", "sec",
            snapshot = null,
            backendChecklistDone = false,
            aiEndpointReady = true,
        )
        assertTrue(CockpitLogic.orderTrapWarningVisible(endpointReadyButNotStarted), "端点就绪但未启动 ⇒ 警告仍在")
    }

    // ── checklist 内容 ──

    @Test
    fun backendChecklistHasSevenItemsMentioningKeyConfigs() {
        assertEquals(7, CockpitLogic.BACKEND_CHECKLIST.size, "后台核对清单须 7 项（与 docs 一致）")
        assertTrue(CockpitLogic.BACKEND_CHECKLIST.any { it.contains("im.message.receive_v1") }, "须含事件订阅项")
        assertTrue(CockpitLogic.BACKEND_CHECKLIST.any { it.contains("card.action.trigger") }, "须含回调订阅项")
        assertTrue(CockpitLogic.BACKEND_CHECKLIST.any { it.contains("长连接") }, "须含长连接字样")
        assertTrue(CockpitLogic.BACKEND_CHECKLIST.any { it.contains("发版") }, "须含发版项")
    }

    // ── LB-01（重设计批 9）：向导折叠化源码锚（渲染面契约）──

    @Test
    fun quickStartCardRendersCollapsibleWithDynamicTitle() {
        val text = File(cockpitPanelPath()).readText()
        // 折叠化：collapsible + initialExpanded 反凭据态（新用户展开 / 凭据就绪收起）
        assertTrue(
            "initialExpanded = !steps.credentialsReady" in text,
            "QuickStartCard 必须 initialExpanded = !steps.credentialsReady（LB-01 验收 2：新用户展开态）",
        )
        // 折叠态动态标题（卡面原文）
        assertTrue(
            "快速开始 · 后台配置未完成" in text,
            "折叠态动态标题「快速开始 · 后台配置未完成」缺失（LB-01 卡面验收）",
        )
        // 渲染门控必须传两派生参（存续判定新签名接线）
        assertTrue(
            "backendChecklistDone = quickStartSteps.backendConfigured" in text &&
                "firstMessageReceived = quickStartSteps.firstMessageReceived" in text,
            "CockpitPanel 渲染门控必须传 backendChecklistDone/firstMessageReceived（LB-01 存续判定接线）",
        )
    }

    // ── LB-16（重设计批 9）：待确认行「复制裁决短语」源码锚 ──

    @Test
    fun pendingConfirmRowHasDecisionPhraseCopyButton() {
        val text = File(cockpitPanelPath()).readText()
        // 短码单源：必须走 ConfirmService.shortId（批 5 已导出 public——勿手写 removePrefix 链）
        assertTrue(
            "ConfirmService.shortId" in text,
            "复制短语短码必须单源 ConfirmService.shortId（LB-16 卡面 notes：倾向 lib 导出已成立）",
        )
        // 短语格式「确认 <短码>」（DECISION_REGEX ^确认\s+hex$ 的输入口径）
        assertTrue(
            "\"确认 \" + ConfirmService.shortId" in text,
            "textProvider 必须「确认 <短码>」格式（DECISION_REGEX 通道输入口径）",
        )
        // 复制钮插行尾（已读之后）——不跨越 PillLabelGuardTest 卡头切片（:788-803 区）与
        // CockpitRemainingPillGuardTest pill 双态块（注释锚「DS-11+XA-16 同枚两态原子同改」起
        // 1000 字切片）：锚定法 = 复制钮代码必须出现在已读 Text 之后（同函数区内序校验）
        val anchor = "text = readCount?.let { \"已读 \$it 人\" }"
        val copyIdx = text.indexOf("CopyIconButton(\n                        textProvider = { \"确认 \"")
        val readIdx = text.indexOf(anchor)
        assertTrue(copyIdx > readIdx && readIdx > 0, "复制钮必须插在已读 N 人之后（行尾——两守护锚切片不碰）")
    }

    private companion object {
        const val COCKPIT_PANEL =
            "feature-larkbridge/src/jvmMain/kotlin/com/jiaoay/easy_dev_toolbox/page/larkbridge/cockpit/CockpitPanel.kt"
    }

    /** 源码锚文件定位（PillLabelGuardTest 同款：user.dir 向上找 settings.gradle.kts 定项目根）。 */
    private fun cockpitPanelPath(): String {
        var dir: File? = File(System.getProperty("user.dir")).absoluteFile
        var hops = 0
        while (dir != null && hops < 8) {
            if (File(dir, "settings.gradle.kts").isFile) return File(dir, COCKPIT_PANEL).path
            dir = dir.parentFile
            hops++
        }
        error("无法定位项目根（含 settings.gradle.kts）（user.dir=${System.getProperty("user.dir")}）")
    }
}
