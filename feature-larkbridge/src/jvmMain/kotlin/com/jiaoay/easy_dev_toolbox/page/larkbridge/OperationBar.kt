package com.jiaoay.easy_dev_toolbox.page.larkbridge

import dev.vicart.compose.material.symbols.MaterialSymbols

import androidx.compose.animation.animateContentSize
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import com.jiaoay.easy_dev_toolbox.core.theme.LocalToolboxSurfaceColors
import com.jiaoay.easy_dev_toolbox.core.theme.AppShapes
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import com.jiaoay.easy_dev_toolbox.core.theme.AppMotion
import com.jiaoay.easy_dev_toolbox.widget.AppButton
import com.jiaoay.easy_dev_toolbox.widget.AppConfirmDialog
import com.jiaoay.easy_dev_toolbox.widget.AppSymbolIcon
import com.jiaoay.easy_dev_toolbox.widget.StatusBanner
import com.jiaoay.easy_dev_toolbox.widget.StatusBannerStatus
import com.jiaoay.easy_dev_toolbox.core.theme.Dimens
import com.jiaoay.easy_dev_toolbox.core.theme.LocalDayBattle
import com.jiaoay.easy_dev_toolbox.core.theme.LocalEasyToolboxColors
import com.jiaoay.easy_dev_toolbox.core.theme.dayBattleTint
import com.jiaoay.easy_dev_toolbox.page.larkbridge.status.carrier
import com.jiaoay.easy_dev_toolbox.page.larkbridge.status.color
import com.jiaoay.easy_dev_toolbox.page.larkbridge.status.label
import com.jiaoay.easy_dev_toolbox.widget.CommitTextField
import com.jiaoay.easy_dev_toolbox.widget.PillLabel
import com.jiaoay.lark.bridge.BridgeStartResult
import com.jiaoay.lark.bridge.ConfigApplyResult
import com.jiaoay.lark.bridge.observe.BridgeStatusSnapshot
import com.jiaoay.lark.bridge.observe.EnvironmentProbe
import java.io.File
import kotlinx.coroutines.delay

/**
 * 操作区：启动/停止/测试消息在驾驶舱 hero——本组件 = 连接态行 + 回显区 + 高级操作折叠区；
 * 导入/导出/打开目录三管理钮在页头 actions 槽——全生命周期动作不藏折叠区，
 * 折叠区收窄为测试消息输入 + 引导 + 回显。
 *
 * ## 视觉语言（本版落地）
 * - 连接态 = **状态圆点 + 文字**（Dimens.space10 + CircleShape + [BridgeStatusSnapshot.connection]
 *   的 color() 着色；DeviceStatusBar 同语言），不再是一行彩色文字；
 * - 启动/停止/测试消息按钮归驾驶舱 hero 卡（hero 行单源；本组件不再重复）——
 *   busy 时进度圈随按钮走 hero；
 * - 回显区（启动结果/需重启/预检/banner/dryRun）统一走 [StatusBanner]——
 *   「容器色 + 前缀图标 + 文字」三层（成功=successContainer/警告=warning 合成底/失败=errorContainer）；
 *
 * chatId/testMessage/expanded 状态由 [LarkBridgePage] 持有（外提共享）——hero 的「测试消息」
 * 与本折叠区的目标会话 ID 输入框共用同源状态：chatId 空时点 hero 按钮即展开本区引导填写。
 * Secret reveal：凭据字段的显形交互在配置页的 Secret 编辑器（ConfigFieldRenderer），
 * 本组件只管 chatId（测试目标，非机密）。
 * 🔴 挂起纪律：一切 suspend 转发都在 VM 的 viewModelScope.launch 内，本组件零 runBlocking
 * （组合内 runBlocking 会冻结 UI 线程）。
 * 零白名单：间距走 [Dimens]、圆点尺寸走 space10 档、进度圈走 progressInline/strokeThin token。
 */
@Composable
fun OperationBar(
    running: Boolean,
    busy: Boolean,
    busyAction: BusyAction?,
    snapshot: BridgeStatusSnapshot?,
    dryRun: Boolean,
    startResult: BridgeStartResult?,
    applyResult: ConfigApplyResult?,
    banner: String?,
    probe: EnvironmentProbe?,
    testExpanded: Boolean,
    testChatId: String,
    testMessage: String,
    pendingImport: File? = null,
    modifiedCount: Int = 0,
    chatIdFocusTicket: Int = 0,
    onTestExpandedChange: (Boolean) -> Unit,
    onSendTest: () -> Unit = {},
    onTestChatIdChange: (String) -> Unit,
    onTestMessageChange: (String) -> Unit,
    onRestart: () -> Unit,
    onConfirmImport: () -> Unit = {},
    onCancelImport: () -> Unit = {},
    onClearBanner: () -> Unit,
    /** 预检结果条行尾关闭钮（手动处置 probe——banner 5s 链不碰 probe）。 */
    onClearProbe: () -> Unit = {},
    modifier: Modifier = Modifier,
) {
    val colors = LocalEasyToolboxColors.current

    // chatId 焦点接线——hero 空值点按递票后焦点直落目标会话输入框
    val chatIdFocus = remember { FocusRequester() }
    LaunchedEffect(chatIdFocusTicket) {
        if (chatIdFocusTicket > 0) runCatching { chatIdFocus.requestFocus() }
    }

    // 一次性反馈自动清除：只管 banner——probe 是预检显式动作的
    // 持久结果，常驻至下次预检覆盖或手动关闭，不进 5s 清除链（共用一条 5s 链时
    // 预检三段式结果常读不完即消失）。
    LaunchedEffect(banner) {
        if (banner != null) {
            delay(5_000)
            onClearBanner()
        }
    }

    Column(
        modifier = modifier
            .fillMaxWidth()
            .animateContentSize(animationSpec = AppMotion.softSpring()),
        verticalArrangement = Arrangement.spacedBy(Dimens.space8),
    ) {
        // ── 常驻行：连接态（圆点 + 文字）+ 展开箭头（启停钮在 hero） ──
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(Dimens.space8),
        ) {
            val connection = snapshot?.connection
            // 昼战语义承载对（design-doc.md §5.3，BridgeConnectionStatus.carrier 单源）：点坐 container 档
            // （dayBattleTint 图形 3.0 线）、语义文字坐该面的胶囊；非昼战裸点/裸字现状
            val carrier = connection?.carrier()
            // 状态圆点：连接态的第一视觉信号（在/不在），文字做第二确认
            Box(
                modifier = Modifier
                    .size(Dimens.space10)
                    .clip(CircleShape)
                    .background(
                        if (carrier != null) {
                            dayBattleTint(carrier.first, carrier.second ?: carrier.first)
                        } else {
                            MaterialTheme.colorScheme.onSurfaceVariant
                        },
                    ),
            )
            if (carrier != null && carrier.second != null && LocalDayBattle.current) {
                PillLabel(
                    text = connection?.label() ?: "未装配",
                    container = carrier.second!!,
                    contentColor = carrier.first,
                    style = MaterialTheme.typography.bodyMedium.copy(
                        fontWeight = androidx.compose.ui.text.font.FontWeight.SemiBold,
                    ),
                )
                // 胶囊恒内容宽（不整行拉伸）——Spacer 补位使「操作区」恒贴行尾
                //（对照分支 Text weight(1f) 同效果；旧 PillLabel fill=false 无补位，
                // 展开钮悬在胶囊旁）
                Spacer(modifier = Modifier.weight(1f))
            } else {
                Text(
                    text = connection?.label() ?: "未装配",
                    // font-weight 600
                    style = MaterialTheme.typography.bodyMedium.copy(
                        fontWeight = androidx.compose.ui.text.font.FontWeight.SemiBold,
                    ),
                    color = connection?.color() ?: MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.weight(1f),
                )
            }
            // faint 文字「操作区 ▾」
            Text(
                text = if (testExpanded) "操作区 ▴" else "操作区 ▾",
                style = MaterialTheme.typography.labelSmall,
                color = LocalToolboxSurfaceColors.current.onSurfaceFaint,
                modifier = Modifier
                    .clip(AppShapes.small)
                    .clickable { onTestExpandedChange(!testExpanded) }
                    .padding(horizontal = Dimens.space4, vertical = Dimens.space2)
                    .semantics {
                        contentDescription = if (testExpanded) "收起操作区" else "展开操作区"
                    },
            )
        }

        // ── 启动结果横幅在折叠区外（常驻）——放折叠区内时操作区默认折叠，
        //    点启动被拒后界面零反馈。banner（提示性）一并外移。
        // 回显区（启动结果 / 预检 / banner）——统一 :ui 的 StatusBanner 容器条
        when (startResult) {
            BridgeStartResult.Started -> StatusBanner(
                status = StatusBannerStatus.Success,
                text = "桥接已启动（连接态见上方）",
            )

            BridgeStartResult.AlreadyRunning -> StatusBanner(
                status = StatusBannerStatus.Warning,
                text = "桥接已在运行",
            )

            is BridgeStartResult.Refused -> StatusBanner(
                status = StatusBannerStatus.Error,
                text = "启动被拒绝：${startResult.reason}",
            )

            null -> Unit
        }

        if (banner != null) {
            StatusBanner(
                status = StatusBannerStatus.Info,
                text = banner,
            )
        }

        // 预检结果常驻条——在折叠区外（折叠收起也可见）+ 移出 5s 清除链
        //    （常驻至下次预检覆盖或手动关闭）。行尾关闭钮 CLOSE 图标。
        if (probe != null) {
            val probeOk = probe.agentAvailable && probe.credentialReady
            StatusBanner(
                status = if (probeOk) StatusBannerStatus.Success else StatusBannerStatus.Warning,
                text = "预检结果：后端 ${probe.agentBackend}（${if (probe.agentAvailable) "可用" else "不可用"}）· " +
                    "凭据${if (probe.credentialReady) "就绪" else "未填写"} · ${if (probe.running) "运行中" else "未运行"}",
                trailing = {
                    IconButton(onClick = onClearProbe, modifier = Modifier.size(Dimens.iconMedium)) {
                        AppSymbolIcon(
                            icon = MaterialSymbols.CLOSE,
                            contentDescription = "关闭预检结果",
                            size = Dimens.iconSmall,
                        )
                    }
                },
            )
        }

        // 🔴 导入 JSON 的 L2 危险确认——导入是覆盖性操作（覆盖不可逆），
        //    选好文件只置 VM 挂起态；此处消费渲染确认框（对话框不受折叠态影响常驻可弹），
        //    确认才走真实导入，取消零副作用（draft/磁盘不动）。
        //    「已修改 N 项」复用配置页 @modified 徽标同源口径（P5FieldGroups.modifiedIds）。
        if (pendingImport != null) {
            AppConfirmDialog(
                title = "导入配置 JSON",
                text = "将用 ${pendingImport.name} 覆盖当前全部配置" +
                    "（${modifiedCount} 项已修改将被替换），保存后立即生效。",
                isDangerous = true,
                confirmLabel = "导入并覆盖",
                onConfirm = onConfirmImport,
                onDismiss = onCancelImport,
            )
        }

        if (testExpanded) {
            Column(
                modifier = Modifier.fillMaxWidth(),
                verticalArrangement = Arrangement.spacedBy(Dimens.space8),
            ) {
                // 导入/导出/打开目录三管理钮已迁页头 actions 槽
                //    （全生命周期动作不再藏折叠区）——本折叠区收窄为测试消息输入 + 引导 +
                //    回显（pendingImport 确认框保留在折叠区外常驻位）。

                // 发送动作主入口在驾驶舱 hero「测试消息」钮，此处为就近第二入口
                //    （免回驾驶舱 hero 二次点按——两入口并存，hero 钮负责未展开态的首触）；
                //    chatId 空时点 hero 钮即展开到本区

                // dryRun 语义标注：假发送显示「已发送」是旧版困惑源——此处显式标注当前语义
                if (dryRun) {
                    StatusBanner(
                        status = StatusBannerStatus.Warning,
                        text = "干跑模式：测试消息只走模拟网关，不会真发到飞书",
                    )
                }

                // chatId 空时显性引导
                if (testChatId.isBlank()) {
                    StatusBanner(
                        status = StatusBannerStatus.Info,
                        text = "目标会话 ID 未填：在飞书给机器人发 /whoami 即可查到；填入后点下方「发送测试消息」直达",
                    )
                }
                CommitTextField(
                    label = "目标会话 ID（发测试消息用，/whoami 可查）",
                    value = testChatId,
                    onCommit = onTestChatIdChange,
                    onValueChange = onTestChatIdChange,
                    modifier = Modifier
                        .fillMaxWidth()
                        .focusRequester(chatIdFocus),
                )
                CommitTextField(
                    label = "测试消息内容",
                    value = testMessage,
                    onCommit = onTestMessageChange,
                    onValueChange = onTestMessageChange,
                )

                // 发送钮就近（免回驾驶舱 hero 二次点按）
                AppButton(
                    label = "发送测试消息",
                    busy = busy,
                    onClick = onSendTest,
                    enabled = testChatId.isNotBlank(),
                    disabledReason = if (testChatId.isBlank()) "先填上方目标会话 ID" else null,
                )

                // 回显区（启动结果/banner/预检结果条均已移到折叠区外常驻位，此处只余需重启黄条）

                if (applyResult is ConfigApplyResult.RestartRequired) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(Dimens.space8),
                    ) {
                        StatusBanner(
                            status = StatusBannerStatus.Warning,
                            text = "以下改动需重启桥接才生效：${applyResult.restartFields.joinToString("、")}",
                            modifier = Modifier.weight(1f),
                        )
                        // 一键重启：仅桥接运行中显示（未运行时保存即下次启动生效）。
                        if (running && !busy) {
                            OutlinedButton(onClick = onRestart) {
                                if (busyAction == BusyAction.RESTARTING) {
                                    CircularProgressIndicator(
                                        modifier = Modifier.size(Dimens.progressInline),
                                        strokeWidth = Dimens.strokeThin,
                                    )
                                } else {
                                    Text("重启桥接")
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}
