package com.jiaoay.easy_dev_toolbox.page.larkbridge

import dev.vicart.compose.material.symbols.MaterialSymbols

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.size
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import com.jiaoay.easy_dev_toolbox.core.theme.Dimens
import com.jiaoay.easy_dev_toolbox.core.theme.LocalEasyToolboxColors
import com.jiaoay.easy_dev_toolbox.widget.AppDialogDefaults
import com.jiaoay.easy_dev_toolbox.widget.appDialogRim
import com.jiaoay.easy_dev_toolbox.widget.AppSymbolIcon
import com.jiaoay.easy_dev_toolbox.widget.interactions.CopyButton
import java.awt.Desktop
import java.net.URI

/**
 * 扫码建应用对话框（四态渲染：等待 / 二维码 / 成功 / 失败）。
 *
 * SDK 的 `onQRCode` 回调给的是**二维码图片 URL**（QRCodeInfo.getUrl），本项目无图片加载依赖
 * （不引 coil，见 AGENTS.md 依赖克制纪律）⇒ 交互降级为「在浏览器打开二维码 + 复制链接」，
 * 飞书手机端扫浏览器里的二维码即可完成应用创建。
 *
 * 状态四态（[RegisterAppState]）：Waiting（等 SDK 出码，进度圈）/ QrShowing（链接 + 有效期 +
 * OpenInNew 浏览器入口 + CopyButton 带已复制反馈）/ Success（CheckCircle 前缀）/ Failed
 * （Error 前缀 + 重试入口）。
 * SDK 轮询不可中断：对话框的「取消」只收起 UI，不杀流程——迟到结果会再次浮出。
 * 零白名单：图标尺寸走 [Dimens] 档、进度圈 strokeWidth 走 strokeThin token。
 */
@Composable
fun RegisterAppDialog(
    state: RegisterAppState,
    onRetry: () -> Unit,
    onDismiss: () -> Unit,
) {
    if (state is RegisterAppState.Idle) return
    val colors = LocalEasyToolboxColors.current

    AlertDialog(
        onDismissRequest = onDismiss,
        shape = AppDialogDefaults.shape,
        containerColor = AppDialogDefaults.containerColor(),
        modifier = Modifier.appDialogRim(),
        title = { Text("扫码建应用") },
        text = {
            Column(
                modifier = Modifier.fillMaxWidth(),
                verticalArrangement = Arrangement.spacedBy(Dimens.space8),
            ) {
                when (state) {
                    is RegisterAppState.Waiting -> Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(Dimens.space8),
                    ) {
                        CircularProgressIndicator(
                            modifier = Modifier.size(Dimens.iconMedium),
                            strokeWidth = Dimens.strokeThin,
                        )
                        Text(
                            text = "正在向飞书申请二维码…（约 1~3 秒）",
                            style = MaterialTheme.typography.bodyMedium,
                        )
                    }

                    is RegisterAppState.QrShowing -> {
                        Text(
                            text = "用飞书 App 扫码（二维码 ${state.expireInSec} 秒内有效）。",
                            style = MaterialTheme.typography.bodyMedium,
                        )
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(Dimens.space8),
                        ) {
                            OutlinedButton(onClick = {
                                runCatching { Desktop.getDesktop().browse(URI(state.qrUrl)) }
                            }) {
                                AppSymbolIcon(
                                    icon = MaterialSymbols.OPEN_IN_NEW,
                                    contentDescription = null,
                                    size = Dimens.iconSmall,
                                )
                                Text("在浏览器打开二维码")
                            }
                            // 复制反馈统一走共享 CopyButton（1.5s「已复制」+ Check 图标 Crossfade）
                            CopyButton(
                                text = state.qrUrl,
                                // 文案「复制链接」
                                label = "复制链接",
                            )
                        }
                    }

                    is RegisterAppState.Success -> Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(Dimens.space8),
                    ) {
                        AppSymbolIcon(
                            icon = MaterialSymbols.CHECK_CIRCLE,
                            contentDescription = null,
                            size = Dimens.iconMedium,
                            tint = colors.success,
                        )
                        Text(
                            text = "应用创建成功，App ID（${state.appId.take(8)}…）与 Secret 已自动填入配置。" +
                                "下一步：点「启动」连接飞书。",
                            style = MaterialTheme.typography.bodyMedium,
                            color = colors.success,
                        )
                    }

                    is RegisterAppState.Failed -> Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(Dimens.space8),
                    ) {
                        AppSymbolIcon(
                            icon = MaterialSymbols.ERROR,
                            contentDescription = null,
                            size = Dimens.iconMedium,
                            tint = MaterialTheme.colorScheme.error,
                        )
                        Text(
                            text = state.message,
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.error,
                        )
                    }

                    RegisterAppState.Idle -> Unit
                }
            }
        },
        confirmButton = {
            when (state) {
                is RegisterAppState.Failed -> TextButton(onClick = onRetry) { Text("重试") }
                // 对话框次钮措辞恒「取消」（design-language.md §4.2
                // 禁「关闭/不了/返回」混用）——Idle/进行中态单钮「取消」= 对话框整体关闭语义（M3 惯例）
                else -> TextButton(onClick = onDismiss) { Text("取消") }
            }
        },
        dismissButton = {
            if (state is RegisterAppState.Failed) {
                TextButton(onClick = onDismiss) { Text("取消") }
            }
        },
    )
}
