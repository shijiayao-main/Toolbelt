package com.jiaoay.easy_dev_toolbox.page.larkbridge.security

import com.jiaoay.lark.bridge.config.BridgeConfig
import com.jiaoay.lark.bridge.confirm.ConfirmRuntimeStatus

/**
 * 桥接配置页「安全提示」卡的派生逻辑（UI 无关纯函数）。
 *
 * 「禁删失效」hint 已随 CLI 权限档位（permissionPreset/prohibitDelete）退役；
 * 剩余派生面：确认超时动态上限、确认链路三态展示、工作区空暗门警告。
 * （allowlist 空名单警告随准入白名单退役——准入已恒放行，该坑不存在了。）
 */
object BridgeSecurityCardLogic {

    /** 确认超时硬顶（秒）——`runtime.agentTimeoutMinutes` 上限 120 分钟已达 7200s，硬顶防极端值。 */
    const val MAX_CONFIRM_TIMEOUT_SECONDS: Long = 3600L

    /**
     * 确认超时的**动态上限**：`min(3600, agentTimeoutMinutes * 60)`，再 `coerceAtLeast(1)`。
     *
     * 🔒 逐字平移旧 `resolveConfirmTimeoutUpperBound`：确认超时不得超过 agent 任务超时（否则确认还在等、任务已超时），
     * 且硬顶 3600 秒。边界：60min → 3600s（不越界）；0 → `coerceAtLeast(1)` = 1s。
     *
     * @param agentTimeoutMinutes `runtime.agentTimeoutMinutes`（`Editor.Number(1, 120)`，单位分钟）
     */
    fun confirmTimeoutUpperBound(agentTimeoutMinutes: Long): Long {
        val fromAgentTimeout = agentTimeoutMinutes * 60L
        return minOf(MAX_CONFIRM_TIMEOUT_SECONDS, fromAgentTimeout).coerceAtLeast(1L)
    }

    /**
     * 运行时确认链路状态 → UI 展示。
     *
     * `when` **穷尽 [ConfirmRuntimeStatus] 的 4 个子类**（sealed，编译器背书；新增子类即红构建）。
     * 态集含 [ConfirmRuntimeStatus.Probing]（自检中间态，展示与告警判定均需感知）
     * （长连接建立后后台自检期），语义 = 「暂按不可用处理（fail-safe deny）但不报故障」→ 归 [ConfirmStatusDisplay.Info]（自检中）。
     */
    fun resolveConfirmStatusDisplay(status: ConfirmRuntimeStatus): ConfirmStatusDisplay = when (status) {
        ConfirmRuntimeStatus.Disabled -> ConfirmStatusDisplay.Hidden
        is ConfirmRuntimeStatus.Ready -> ConfirmStatusDisplay.Info("确认链路就绪（端口 ${status.port}）")
        is ConfirmRuntimeStatus.Probing -> ConfirmStatusDisplay.Info("确认链路自检中…")
        is ConfirmRuntimeStatus.Unavailable -> ConfirmStatusDisplay.Alert(
            "确认链路不可用：${status.reason}（期间写操作一律按拒绝处理）",
        )
    }

    /**
     * 工作区为空暗门警告（agent 任务依赖工作区沙箱——空 = 沙箱拒绝所有路径）。
     */
    fun workspaceRootEmptyWarning(config: BridgeConfig): String? {
        if (config.workspace.root.isNotBlank()) return null
        return "agent 已启用但工作区目录为空：沙箱将拒绝所有文件路径，任务会以「工作区缺失」失败。" +
            "请在「配置」页签「连接与准入」分区选择 agent 的根目录。"
    }
}

/**
 * 确认链路展示形态（S22 三态；[Hidden] 表示不渲染任何确认行）。
 */
sealed interface ConfirmStatusDisplay {

    /** 未开启运行时确认 → 不展示。 */
    data object Hidden : ConfirmStatusDisplay

    /** 正常信息（就绪 / 自检中），绿色或中性色。 */
    data class Info(val message: String) : ConfirmStatusDisplay

    /** 告警（不可用，fail-safe deny），红色。 */
    data class Alert(val message: String) : ConfirmStatusDisplay
}
