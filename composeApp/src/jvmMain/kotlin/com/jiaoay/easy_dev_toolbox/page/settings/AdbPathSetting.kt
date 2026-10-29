package com.jiaoay.easy_dev_toolbox.page.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import com.jiaoay.easy_dev_toolbox.core.device.DeviceHub
import com.jiaoay.easy_dev_toolbox.core.extensions.SimpleShell
import com.jiaoay.easy_dev_toolbox.core.shelltools.AdbCommandRewriter
import com.jiaoay.easy_dev_toolbox.widget.AppButton
import com.jiaoay.easy_dev_toolbox.widget.AppButtonKind
import com.jiaoay.easy_dev_toolbox.widget.FormRow
import com.jiaoay.easy_dev_toolbox.widget.StatusBanner
import com.jiaoay.easy_dev_toolbox.widget.StatusBannerStatus
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

/**
 * ADB 子卡：展示随包内置 adb 的状态 + 可用性检测钮 + 结果反馈。
 *
 * 没有路径输入框：内置 adb 是唯一来源，解析失败时这里展示人话原因与处置建议。
 */
private data class AdbCheckResult(
    val success: Boolean,
    val message: String,
)

@Composable
internal fun AdbPathSetting() {
    val scope = rememberCoroutineScope()
    var checking by remember { mutableStateOf(false) }
    var checkResult by remember { mutableStateOf<AdbCheckResult?>(null) }

    FormRow(
        label = "内置 adb",
        hint = "随应用分发，无需自行安装；检测可用性会显示版本号",
    ) {
        Row(
            horizontalArrangement = Arrangement.spacedBy(com.jiaoay.easy_dev_toolbox.core.theme.Dimens.space8),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            AppButton(
                label = "检测可用性",
                busyLabel = "检测中…",
                kind = AppButtonKind.Secondary,
                busy = checking,
                onClick = {
                    checking = true
                    checkResult = null
                    scope.launch(Dispatchers.IO) {
                        val result = checkBuiltinAdb()
                        checking = false
                        checkResult = result
                    }
                },
            )
        }
    }

    // 内置 adb 不可用时的原因（解析失败才有）——与检测结果分开呈现：
    // 前者是「根本没解出二进制」，后者是「解出来了但跑不起来」
    DeviceHub.endpointUnavailableReason?.let { reason ->
        StatusBanner(status = StatusBannerStatus.Error, text = reason)
    }

    checkResult?.let { result ->
        StatusBanner(
            status = if (result.success) StatusBannerStatus.Success else StatusBannerStatus.Error,
            text = result.message,
        )
    }
}

/** 执行 `<内置 adb> version` 检测可用性并取版本串 */
private suspend fun checkBuiltinAdb(): AdbCheckResult {
    val endpoint = DeviceHub.currentEndpoint()
    val command = AdbCommandRewriter.rewrite("adb version", endpoint.executablePath, serial = null)
    return SimpleShell.execute(command).fold(
        onSuccess = { shellResult ->
            if (shellResult.exitCode == 0) {
                val versionLine = shellResult.stdout.lineSequence()
                    .firstOrNull { it.isNotBlank() }
                    ?.trim()
                if (versionLine != null) {
                    AdbCheckResult(success = true, message = versionLine)
                } else {
                    AdbCheckResult(success = true, message = "可用（无版本输出）")
                }
            } else {
                // 命令存在但执行失败（内置二进制损坏、被外部删除等）
                val detail = shellResult.stderr.ifBlank { shellResult.stdout }.trim()
                AdbCheckResult(success = false, message = "不可用：${detail.ifBlank { "退出码 ${shellResult.exitCode}" }}")
            }
        },
        onFailure = { e -> AdbCheckResult(success = false, message = "执行失败：${e.message ?: "未知异常"}") },
    )
}
