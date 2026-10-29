package com.jiaoay.easy_dev_toolbox.page.larkbridge.security

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import com.jiaoay.easy_dev_toolbox.core.theme.Dimens
import com.jiaoay.easy_dev_toolbox.widget.SectionCard
import com.jiaoay.easy_dev_toolbox.widget.StatusBanner
import com.jiaoay.easy_dev_toolbox.widget.StatusBannerStatus
import com.jiaoay.lark.bridge.config.BridgeConfig
import com.jiaoay.lark.bridge.confirm.ConfirmRuntimeStatus

/**
 * 权限与安全**实时派生提示卡**（S18 / S21 / S22，承载 [BridgeSecurityCardLogic] 的跨字段派生；
 * 全部提示容器条化（:ui StatusBanner），
 * 私有 WarningBanner/SuccessBanner/ErrorBanner/HintBanner 四件套随之消解）。
 *
 * 这些提示无法由 `BridgeFields.ALL` 数据驱动自动生成（它们是**跨字段**派生：禁删失效取决于档位、
 * 确认超时上限取决于 agent 任务超时、确认链路回显取决于运行态），故手写本卡，使纯逻辑「活」在 UI 上而非死代码。
 *
 * - **S18 禁删失效**：`prohibitDelete` 开启但档位是 workspace/all（允许 shell）→ warning 条。
 * - **S21 确认超时动态上限**：`confirm.enabled` 时回显上限；越界红条。
 * - **S22 确认链路 3 态回显**：Info（success 条）/ Alert（error 条）/ Hidden。
 * - **暗门警告**：allowlist 空名单 fail-closed / agent 启用但工作区为空 → warning 条。
 *
 * 无任何提示时**不渲染**（避免空卡）。零裸 `.dp`；容器条配方统一在 StatusBanner。
 */
@Composable
fun SecurityHintsCard(
    config: BridgeConfig,
    confirmStatus: ConfirmRuntimeStatus?,
    modifier: Modifier = Modifier,
) {
    val timeoutSeconds = config.agent.confirm.timeoutSeconds
    val agentTimeoutMinutes = config.runtime.agentTimeoutMinutes

    // 确认链恒启用（CLI 确认开关已随委托链退役）——超时上限 hint 无开关门控
    val timeoutUpperBound = BridgeSecurityCardLogic.confirmTimeoutUpperBound(agentTimeoutMinutes)
    val timeoutExceeded = timeoutSeconds > timeoutUpperBound
    val confirmDisplay = confirmStatus?.let { BridgeSecurityCardLogic.resolveConfirmStatusDisplay(it) }
    val showConfirmDisplay = confirmDisplay != null && confirmDisplay !is ConfirmStatusDisplay.Hidden
    val workspaceWarning = BridgeSecurityCardLogic.workspaceRootEmptyWarning(config)

    if (!timeoutExceeded && !showConfirmDisplay && workspaceWarning == null) return

    SectionCard(title = "权限与安全提示", modifier = modifier.fillMaxWidth()) {
        Column(
            modifier = Modifier.fillMaxWidth(),
            verticalArrangement = Arrangement.spacedBy(Dimens.space8),
        ) {
            // agent 启用但工作区为空 = 沙箱拒绝所有路径
            if (workspaceWarning != null) {
                StatusBanner(status = StatusBannerStatus.Warning, text = workspaceWarning)
            }

            // S21：确认超时动态上限（中性信息行；越界升级红条）
            run {
                Text(
                    text = "确认超时上限 $timeoutUpperBound 秒（不超过 agent 任务超时 $agentTimeoutMinutes 分钟）",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                if (timeoutExceeded) {
                    StatusBanner(
                        status = StatusBannerStatus.Error,
                        text = "当前确认超时 $timeoutSeconds 秒已超过上限，运行时将被截断为 $timeoutUpperBound 秒",
                    )
                }
            }

            // S22：确认链路运行态回显（穷尽 ConfirmStatusDisplay 的 3 个子类）
            when (confirmDisplay) {
                ConfirmStatusDisplay.Hidden -> Unit
                is ConfirmStatusDisplay.Info -> StatusBanner(
                    status = StatusBannerStatus.Success,
                    text = confirmDisplay.message,
                )
                is ConfirmStatusDisplay.Alert -> StatusBanner(
                    status = StatusBannerStatus.Error,
                    text = confirmDisplay.message,
                )
                null -> Unit
            }
        }
    }
}
