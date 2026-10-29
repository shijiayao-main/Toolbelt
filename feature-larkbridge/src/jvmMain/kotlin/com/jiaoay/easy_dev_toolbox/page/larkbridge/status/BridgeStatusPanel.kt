package com.jiaoay.easy_dev_toolbox.page.larkbridge.status

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import com.jiaoay.easy_dev_toolbox.core.theme.AppShapes
import com.jiaoay.easy_dev_toolbox.core.theme.Dimens
import com.jiaoay.easy_dev_toolbox.core.theme.LocalDayBattle
import com.jiaoay.easy_dev_toolbox.core.theme.LocalEasyToolboxColors
import com.jiaoay.easy_dev_toolbox.core.theme.dayBattleTint
import com.jiaoay.easy_dev_toolbox.widget.CardHeader
import com.jiaoay.easy_dev_toolbox.widget.EmptyStateView
import com.jiaoay.easy_dev_toolbox.widget.PillLabel
import com.jiaoay.easy_dev_toolbox.widget.SectionCard
import com.jiaoay.easy_dev_toolbox.widget.StatusBanner
import com.jiaoay.easy_dev_toolbox.widget.StatusBannerStatus
import com.jiaoay.easy_dev_toolbox.widget.interactions.CopyIconButton
import com.jiaoay.lark.bridge.confirm.ConfirmRuntimeStatus
import com.jiaoay.lark.bridge.observe.BlockedReason
import com.jiaoay.lark.bridge.observe.BridgeConnectionStatus
import com.jiaoay.lark.bridge.observe.BridgeStatusSnapshot

/**
 * 运行状态面板：StatusRow 分层 + 状态圆点 + App ID 复制 + Blocked 容器条。
 *
 * ## 数据源口径
 * 三层错误码链 24→16→16 后，UI 无法仅凭 `JErrorCode` 区分「凭据错误」与「网络抖动」（都塌缩成
 * LarkConnectFailed）。故状态展示以 [BridgeStatusSnapshot] 的 `running` + [BridgeConnectionStatus] 为主，
 * 不用 `JErrorCode` 推断连接态。
 *
 * ## 布局
 * [StatusRow] = **标签置于值上方、各自满宽** 的 [Column]——不固定标签列宽，无裸 dp 字面量。
 *
 * ## 展示面
 * - 「单实例锁被占」（[BridgeConnectionStatus.Blocked] + [BlockedReason]，errorContainer 容器条）与
 *   「拒绝计数」（`accessRejected` / `policyRejected`）是新增展示项；
 * - 不渲染「运行」「连接」两行——驾驶舱 hero 主行 + OperationBar 圆点行已承载，三处同屏纯重复；
 *   运行状态卡从「连接自」起；
 * - 流水线计数拆三小节 [CardHeader]（接收/结果/拒绝与丢弃）+ 尾行「日志台行数」+「存活进程」
 *   归「接收」小节，共 12 行；
 * - [label]/[color] internal 共享件零改动（OperationBar 圆点与 hero 仍消费）。
 */
@Composable
fun BridgeStatusPanel(
    snapshot: BridgeStatusSnapshot?,
    modifier: Modifier = Modifier,
) {
    if (snapshot == null) {
        EmptyStateView(
            text = "桥接未装配 / 未运行，点击「启动」查看实时状态",
            modifier = modifier,
            fillParent = false,
        )
        return
    }

    val colors = LocalEasyToolboxColors.current
    Column(
        modifier = modifier.fillMaxWidth(),
        // 顶层卡距走卡距档 space14
        verticalArrangement = Arrangement.spacedBy(Dimens.space14),
    ) {
        SectionCard(title = "运行状态", modifier = Modifier.fillMaxWidth()) {
            // .grid2 双列——左列
            // 连接自/确认链路/存活进程、右列 App ID/最近异常/停止原因；1.15fr/1fr
            // → weight(1.15f)/weight(1f)
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(Dimens.space14),
            ) {
            Column(
                modifier = Modifier.weight(1.15f),
                verticalArrangement = Arrangement.spacedBy(Dimens.space8),
            ) {
                // 「运行」「连接」两行不渲染——驾驶舱 hero 主行
                // （CockpitPanel ConnectionHeroArea mainLine）+ OperationBar 圆点行三处同屏
                // 纯重复，hero 已承载运行/连接语义；运行状态卡从「连接自」起渲染。
                // 连接建立时刻（1Hz publishStatus 接线后运行中持续刷新）。
                snapshot.connectedSinceMillis?.let { since ->
                    val time = java.time.LocalTime.ofInstant(
                        java.time.Instant.ofEpochMilli(since),
                        java.time.ZoneId.systemDefault(),
                    ).format(java.time.format.DateTimeFormatter.ofPattern("HH:mm:ss"))
                    val up = snapshot.sampledAtMillis - since
                    StatusRow(label = "连接自", value = "$time（已 ${formatUptime(up)}）")
                }

                StatusRow(
                    label = "确认链路",
                    value = snapshot.confirm.label(),
                    valueColor = snapshot.confirm.color(),
                    valueContainer = snapshot.confirm.container().second,
                    showDot = true,
                )
            }
            // 右列（稿 :574-577：应用凭据/最近异常/停止原因）
            Column(
                modifier = Modifier.weight(1f),
                verticalArrangement = Arrangement.spacedBy(Dimens.space8),
            ) {
                AppIdRow(appId = snapshot.appId)
                StatusRow(
                    label = "最近异常",
                    value = snapshot.lastError ?: "无",
                    // 非空 error 红 / 停止原因 warn 橙（扫描信号），空态恒
                    // on-surface-var；昼战语义承载面与值色同源成对（design-doc.md §5.3，恒定槽值不动）
                    valueColor = if (snapshot.lastError != null) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant,
                    valueContainer = if (snapshot.lastError != null) colors.errorContainerFixed else null,
                )
                StatusRow(
                    label = "停止原因",
                    value = snapshot.stopReason ?: "—",
                    valueColor = if (snapshot.stopReason != null) colors.warning else MaterialTheme.colorScheme.onSurfaceVariant,
                    valueContainer = if (snapshot.stopReason != null) colors.warningContainer else null,
                )
            }
            }
            // Blocked 容器条：grid2 之下**全宽**（banner 在 grid2 外）。
            // `.grid2` `margin-bottom:var(--sp-10)`：
            // grid2 与 Blocked 条之间 10px（SectionCard 内容 Column 无 Arrangement，需显式 Spacer）
            val connection = snapshot.connection
            if (connection is BridgeConnectionStatus.Blocked) {
                Spacer(modifier = Modifier.height(Dimens.space10)) // 稿 :569 margin-bottom sp-10
                StatusBanner(
                    status = StatusBannerStatus.Error,
                    text = "单实例锁被占：${connection.reason.label()}" +
                        "${connection.detail.takeIf { it.isNotBlank() }?.let { " · $it" } ?: ""}",
                )
            }
        }

        // 流水线计数按三小节 CardHeader 分组（接收侧/结果侧/拒绝丢弃侧——扫读成本；
        // 「任务行为与限额」四小节同款先例）；「存活进程」归「接收」小节，共 12 行。
        SectionCard(title = "流水线计数", modifier = Modifier.fillMaxWidth()) {
            val c = snapshot.counters
            // .grid2 双列——左列=接收+结果+尾行日志台行数、
            // 右列=拒绝与丢弃三行；右列短、两列顶对齐
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(Dimens.space14),
            ) {
            Column(
                modifier = Modifier.weight(1.15f),
                verticalArrangement = Arrangement.spacedBy(Dimens.space8),
            ) {
                CardHeader(
                    title = "接收",
                    // .subhead fs-12/650 on-surface-var
                    style = MaterialTheme.typography.labelMedium.copy(
                        fontWeight = androidx.compose.ui.text.font.FontWeight.SemiBold,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    ),
                )
                StatusRow(label = "接收消息", value = c.intakeReceived.toString())
                StatusRow(label = "已提交任务", value = c.submitted.toString())
                StatusRow(label = "存活进程", value = snapshot.liveProcesses.toString())

                CardHeader(
                    title = "结果",
                    style = MaterialTheme.typography.labelMedium.copy(
                        fontWeight = androidx.compose.ui.text.font.FontWeight.SemiBold,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    ),
                )
                StatusRow(label = "完成", value = c.completed.toString())
                StatusRow(
                    label = "失败",
                    value = c.failed.toString(),
                    valueColor = if (c.failed > 0) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurface,
                    valueContainer = if (c.failed > 0) colors.errorContainerFixed else null,
                )

                // 尾行小节（「尾行」subhead 归左列）
                CardHeader(
                    title = "尾行",
                    style = MaterialTheme.typography.labelMedium.copy(
                        fontWeight = androidx.compose.ui.text.font.FontWeight.SemiBold,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    ),
                )
                StatusRow(label = "日志台行数", value = snapshot.logSize.toString())
            }
            // 右列（拒绝与丢弃——稿 :600-608）
            Column(
                modifier = Modifier.weight(1f),
                verticalArrangement = Arrangement.spacedBy(Dimens.space8),
            ) {
                CardHeader(
                    title = "拒绝与丢弃",
                    style = MaterialTheme.typography.titleSmall,
                )
                // R-14 新增能力：拒绝计数（访问控制 + 策略）
                StatusRow(
                    label = "访问拒绝",
                    value = c.accessRejected.toString(),
                    valueColor = if (c.accessRejected > 0) colors.warning else MaterialTheme.colorScheme.onSurface,
                    valueContainer = if (c.accessRejected > 0) colors.warningContainer else null,
                )
                // 不静默丢失：未匹配计数器（PipelineCounters.unmatched）在此露出。
                //    观测口径（agent 硬依赖化）：App 形态未命中 TEXT 恒进调度 agent（不可用时产出
                //    AGENT_CONFIG_INVALID 报错卡并计入 failedCount）⇒ 本行应趋零、失败数上升；持续非零
                //    说明有非 TEXT 未支持 kind（发图/贴纸，走显式指引文案），与任何回复模板配置无关。
                StatusRow(
                    label = "未匹配消息",
                    value = c.unmatched.toString(),
                    valueColor = if (c.unmatched > 0) colors.warning else MaterialTheme.colorScheme.onSurface,
                    valueContainer = if (c.unmatched > 0) colors.warningContainer else null,
                )
                StatusRow(
                    label = "策略拒绝",
                    value = c.policyRejected.toString(),
                    valueColor = if (c.policyRejected > 0) colors.warning else MaterialTheme.colorScheme.onSurface,
                    valueContainer = if (c.policyRejected > 0) colors.warningContainer else null,
                )
                StatusRow(label = "去重丢弃", value = c.duplicatesDropped.toString())
                StatusRow(label = "队列超时", value = c.queueTimeouts.toString())
                StatusRow(
                    label = "丢弃合计",
                    value = c.droppedTotal.toString(),
                    valueColor = if (c.droppedTotal > 0) colors.warning else MaterialTheme.colorScheme.onSurface,
                    valueContainer = if (c.droppedTotal > 0) colors.warningContainer else null,
                )
            }
            }
        }
    }
}

/**
 * 状态行（.srow 形态）：
 * **横排**（label 120dp 定宽 mut 色 + 值同行 bodyMedium）——
 * .srow display:flex + .mut width:120px。
 * [showDot] = true 时值前加状态圆点（dotok 6dp 语义）。
 *
 * [valueContainer] = 值的语义承载面（与 [valueColor] 同源成对传入：warning→warningContainer、
 * error→errorContainerFixed、success→successContainer；中性态传 null）。昼战形态
 * （design-doc.md §5.3#4，恒定语义槽值不动）值字坐该面的胶囊（PillLabel 槽对呈现）、状态点坐
 * 该档色（dayBattleTint 图形 3.0 线）；非昼战不消费——值字/点裸放现状逐位不变。
 */
@Composable
private fun StatusRow(
    label: String,
    value: String,
    valueColor: Color = MaterialTheme.colorScheme.onSurface,
    valueContainer: Color? = null,
    showDot: Boolean = false,
) {
    val dayBattle = LocalDayBattle.current
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(Dimens.space8),
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.width(Dimens.statusLabelWidth),
        )
        if (showDot) {
            Box(
                modifier = Modifier
                    .size(Dimens.dotIndicatorSize)
                    .clip(CircleShape)
                    .background(dayBattleTint(valueColor, valueContainer ?: valueColor)),
            )
        }
        if (dayBattle && valueContainer != null) {
            PillLabel(
                text = value,
                container = valueContainer,
                contentColor = valueColor,
                style = MaterialTheme.typography.bodyMedium,
            )
        } else {
            Text(text = value, style = MaterialTheme.typography.bodyMedium, color = valueColor)
        }
    }
}

/** App ID 行：值旁带复制按钮（App ID 需频繁与飞书后台核对/粘贴）。 */
@Composable
private fun AppIdRow(appId: String) {
    val colors = LocalEasyToolboxColors.current
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(Dimens.space8),
    ) {
        Text(
            text = "应用凭据 App ID",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.width(Dimens.statusLabelWidth),
        )
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(Dimens.space8),
        ) {
            // 未填写警示字昼战坐 warningContainer 面（design-doc.md §5.3#4，warning×warningContainer
            // 4.90 槽对呈现）；非昼战裸字现状
            if (LocalDayBattle.current && appId.isBlank()) {
                PillLabel(
                    text = "（未填写）",
                    container = colors.warningContainer,
                    contentColor = colors.warning,
                    style = MaterialTheme.typography.bodyMedium,
                )
                // 胶囊恒内容宽——Spacer 补位保持与对照分支（Text weight(1f)）同几何；
                // 本分支 appId 恒空、复制钮不渲染，补位仅撑满行宽
                Spacer(modifier = Modifier.weight(1f))
            } else {
                Text(
                    text = appId.ifBlank { "（未填写）" },
                    style = MaterialTheme.typography.bodyMedium,
                    color = if (appId.isBlank()) colors.warning else MaterialTheme.colorScheme.onSurface,
                    modifier = Modifier.weight(1f),
                )
            }
            if (appId.isNotBlank()) {
                CopyIconButton(
                    textProvider = { appId },
                    contentDescription = "复制 App ID",
                )
            }
        }
    }
}

internal fun BridgeConnectionStatus.label(): String = when (this) {
    BridgeConnectionStatus.Idle -> "空闲"
    BridgeConnectionStatus.Connecting -> "连接中"
    is BridgeConnectionStatus.Connected -> "已连接${botName?.let { " · $it" } ?: ""}"
    BridgeConnectionStatus.Reconnecting -> "重连中"
    is BridgeConnectionStatus.Disconnected -> "已断开：$msg"
    is BridgeConnectionStatus.Blocked -> "被阻塞"
    is BridgeConnectionStatus.Draining -> "排空中（$activeTasks）"
}

/** internal：同 [label]，驾驶舱与 OperationBar 复用。 */
@Composable
internal fun BridgeConnectionStatus.color(): Color = carrier().first

/**
 * 连接态语义承载对（昼战 design-doc.md §5.3 呈现路径的单源）：first = 语义原色（恒定槽值不动），
 * second = 昼战 container 承载面（null = 非语义态，昼战也不承载——中性档消费归
 * 卡域文字接线范畴）。昼战下文字坐 second 面（PillLabel 槽对呈现）、点/图标坐
 * second 档色（dayBattleTint）；非昼战各消费点只读 first，呈现逐位不变。
 */
@Composable
internal fun BridgeConnectionStatus.carrier(): Pair<Color, Color?> {
    val colors = LocalEasyToolboxColors.current
    return when (this) {
        is BridgeConnectionStatus.Connected -> colors.success to colors.successContainer
        is BridgeConnectionStatus.Disconnected, is BridgeConnectionStatus.Blocked ->
            MaterialTheme.colorScheme.error to colors.errorContainerFixed
        BridgeConnectionStatus.Connecting, BridgeConnectionStatus.Reconnecting,
        is BridgeConnectionStatus.Draining,
        -> colors.warning to colors.warningContainer
        BridgeConnectionStatus.Idle -> MaterialTheme.colorScheme.onSurfaceVariant to null
    }
}

private fun BlockedReason.label(): String = when (this) {
    BlockedReason.PEER_INSTANCE -> "同 App ID 已有另一实例在运行"
    BlockedReason.SINGLE_INSTANCE_LOCK -> "单实例锁被占用"
}

/** [ConfirmRuntimeStatus] → 中文态（`when` 穷尽，编译器背书）。 */
private fun ConfirmRuntimeStatus.label(): String = when (this) {
    ConfirmRuntimeStatus.Disabled -> "未启用"
    is ConfirmRuntimeStatus.Probing -> "自检中（端口 $port）"
    is ConfirmRuntimeStatus.Ready -> "就绪（端口 $port）"
    is ConfirmRuntimeStatus.Unavailable -> "不可用：$reason"
}

/** millis → `1h 23m` / `2d 3h`（连接时长人工可读格式；负值按 0 处理）。 */
private fun formatUptime(millis: Long): String {
    val totalMinutes = (millis.coerceAtLeast(0L) / 60_000L).toInt()
    val days = totalMinutes / (60 * 24)
    val hours = (totalMinutes / 60) % 24
    val minutes = totalMinutes % 60
    return when {
        days > 0 -> "${days}d ${hours}h"
        hours > 0 -> "${hours}h ${minutes}m"
        else -> "${minutes}m"
    }
}

@Composable
private fun ConfirmRuntimeStatus.color(): Color = container().first

/** 确认链路态昼战承载对（design-doc.md §5.3，语义与 [BridgeConnectionStatus.carrier] 同款纪律）：中性态 second = null。 */
@Composable
private fun ConfirmRuntimeStatus.container(): Pair<Color, Color?> {
    val colors = LocalEasyToolboxColors.current
    return when (this) {
        is ConfirmRuntimeStatus.Ready -> colors.success to colors.successContainer
        is ConfirmRuntimeStatus.Unavailable -> MaterialTheme.colorScheme.error to colors.errorContainerFixed
        is ConfirmRuntimeStatus.Probing -> colors.warning to colors.warningContainer
        ConfirmRuntimeStatus.Disabled -> MaterialTheme.colorScheme.onSurfaceVariant to null
    }
}
