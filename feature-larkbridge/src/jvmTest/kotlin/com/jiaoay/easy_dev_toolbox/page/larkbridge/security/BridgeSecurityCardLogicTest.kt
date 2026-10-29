package com.jiaoay.easy_dev_toolbox.page.larkbridge.security

import com.jiaoay.lark.bridge.confirm.ConfirmRuntimeStatus
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

/**
 * `BridgeSecurityCardLogic` 跨字段派生契约。
 *
 * 两条派生：确认超时动态上限、确认链路 3 态回显——全部纯函数，
 * 由 [SecurityHintsCard] 渲染。禁删保护派生已随 CLI 权限档位退役。
 */
class BridgeSecurityCardLogicTest {

    // ── BC-489..493：确认超时动态上限 min(3600, agentTimeoutMinutes*60).coerceAtLeast(1) ──

    /** BC-489：120min → 硬顶 3600s。 */
    @Test
    fun timeoutUpperBoundCapsAt3600() {
        assertEquals(3600L, BridgeSecurityCardLogic.confirmTimeoutUpperBound(120))
        assertEquals(3600L, BridgeSecurityCardLogic.MAX_CONFIRM_TIMEOUT_SECONDS)
    }

    /** BC-490：10min → 600s（agent 超时更小时取它）。 */
    @Test
    fun timeoutUpperBoundUsesAgentTimeoutWhenSmaller() {
        assertEquals(600L, BridgeSecurityCardLogic.confirmTimeoutUpperBound(10))
    }

    /** BC-491：60min → 3600s（边界不越）。 */
    @Test
    fun timeoutUpperBoundAtExactBoundary() {
        assertEquals(3600L, BridgeSecurityCardLogic.confirmTimeoutUpperBound(60))
    }

    /** BC-492：0 → coerceAtLeast(1) == 1s。 */
    @Test
    fun timeoutUpperBoundMinimumOneSecondForZero() {
        assertEquals(1L, BridgeSecurityCardLogic.confirmTimeoutUpperBound(0))
    }

    /** BC-493：1min → 60s。 */
    @Test
    fun timeoutUpperBoundForOneMinute() {
        assertEquals(60L, BridgeSecurityCardLogic.confirmTimeoutUpperBound(1))
    }

    // ── BC-494..497：确认链路运行态 → 展示形态 ──

    /** BC-494：Disabled → Hidden。 */
    @Test
    fun disabledMapsToHidden() {
        assertIs<ConfirmStatusDisplay.Hidden>(
            BridgeSecurityCardLogic.resolveConfirmStatusDisplay(ConfirmRuntimeStatus.Disabled),
        )
    }

    /** BC-495：Ready → Info，message 含端口 + 「就绪」。 */
    @Test
    fun readyMapsToInfoWithPort() {
        val display = BridgeSecurityCardLogic.resolveConfirmStatusDisplay(
            ConfirmRuntimeStatus.Ready(port = 8080, scriptPath = "/tmp/x.sh"),
        )
        assertIs<ConfirmStatusDisplay.Info>(display)
        assertTrue(display.message.contains("8080"), "回显必须含端口")
        assertTrue(display.message.contains("就绪"), "回显必须含「就绪」")
    }

    /** BC-496：Unavailable → Alert，含 reason + 「按拒绝处理」。 */
    @Test
    fun unavailableMapsToAlertWithReason() {
        val display = BridgeSecurityCardLogic.resolveConfirmStatusDisplay(
            ConfirmRuntimeStatus.Unavailable(reason = "端口被占用"),
        )
        assertIs<ConfirmStatusDisplay.Alert>(display)
        assertTrue(display.message.contains("端口被占用"), "Alert 必须含 reason")
        assertTrue(display.message.contains("按拒绝处理"), "Alert 必须声明 fail-safe 拒绝语义")
    }

    /** BC-497：Unavailable 的 Alert 文案含写操作拒绝提示。 */
    @Test
    fun unavailableAlertContainsRejectionWarning() {
        val display = BridgeSecurityCardLogic.resolveConfirmStatusDisplay(
            ConfirmRuntimeStatus.Unavailable(reason = "自检失败"),
        )
        assertIs<ConfirmStatusDisplay.Alert>(display)
        assertTrue(display.message.contains("写操作"), "Alert 必须指明受影响的操作面")
    }

    /** 补充（D14 新增态）：Probing → Info（自检中，暂按拒绝处理但不报故障）。 */
    @Test
    fun probingMapsToInfoSelfChecking() {
        val display = BridgeSecurityCardLogic.resolveConfirmStatusDisplay(
            ConfirmRuntimeStatus.Probing(port = -1, scriptPath = ""),
        )
        assertIs<ConfirmStatusDisplay.Info>(display)
        assertTrue(display.message.contains("自检"), "Probing 回显应说明自检中")
    }

    // ── 暗门警告：workspace.root 空（allowlist 空名单警告随准入恒放行退役）──

    private fun configOf(
        workspaceRoot: String = "",
    ) = com.jiaoay.lark.bridge.config.DefaultBridgeConfig.default().let {
        it.copy(workspace = it.workspace.copy(root = workspaceRoot))
    }

    /** 暗门：工作区空 ⇒ 警告（agent 恒为硬依赖，沙箱拒绝所有路径）。 */
    @Test
    fun workspaceRootEmptyWarningWhenRootBlank() {
        val warning = BridgeSecurityCardLogic.workspaceRootEmptyWarning(configOf())
        assertTrue(warning != null, "工作区空必须警告（agent 恒启用）")
        assertTrue(warning.contains("工作区"), "警告须指向工作区设置")
    }

    /** 暗门 2b：工作区已填 ⇒ 不警告（enabled 总闸已退役，「agent 禁用不警告」分支随之消失）。 */
    @Test
    fun noWorkspaceWarningWhenRootFilled() {
        assertTrue(
            BridgeSecurityCardLogic.workspaceRootEmptyWarning(configOf(workspaceRoot = "/ws")) == null,
        )
    }
}
