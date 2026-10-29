package com.jiaoay.easy_dev_toolbox.page.screencast

import dev.vicart.compose.material.symbols.MaterialSymbols

import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.width
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.lifecycle.viewmodel.compose.viewModel
import com.jiaoay.easy_dev_toolbox.core.theme.Dimens
import com.jiaoay.easy_dev_toolbox.widget.AppButton
import com.jiaoay.easy_dev_toolbox.widget.AppButtonKind
import com.jiaoay.easy_dev_toolbox.widget.AppSymbolIcon
import com.jiaoay.easy_dev_toolbox.widget.StatusBanner
import com.jiaoay.easy_dev_toolbox.widget.StatusBannerStatus
import com.jiaoay.easy_dev_toolbox.widget.TonalCard
import com.jiaoay.toolbelt.device.DeviceStateSource

/**
 * 投屏面板（设备观测空间成员，宿主聚合页内容组件——:feature-logcat LogcatPanel 同配方）：
 * 纯启动器单行卡——投屏的展示与操作全部在独立视口窗（窗是投屏唯一的使用场景），本面板
 * 只承载启动入口与最低限度回执：图标 + 「投屏」标题 + 行尾主钮。行内不放状态展示
 * （fps/泊指——窗内状态条已有）与投屏控制（画质档位弹层在窗内顶栏）；开窗由
 * [ScreencastViewModel.startCast] 在会话受理后即直达（先窗后画——连接段反馈在窗内）。
 *
 * 点击语义（行尾主钮 = 唯一入口，行本体不可点——主钮承载全部动作与状态回显，行可点
 * 与主钮并存的冗余被真机终裁裁撤）：Idle/正常停止 = 「开始投屏」（startCast 链）；
 * Streaming/Connecting = 「聚焦窗口」（[onOpenDetached]，生产接线 = Controller::openOrFocus
 * 唯一入口——受理即开窗后连接段窗已在，行尾入口即聚焦面）；失败终态主钮变「重试」
 * （同 startCast 链）。未桥接 onOpenDetached 的 Connecting 期保留 busy 钮回显（无窗可聚焦
 * 形态）。窗关即停投：关窗路径全部汇入控制器 recall 统一停投——Streaming 期窗恒在。
 *
 * 回执细行（行卡下恒显，有错才现——「点了没反应」的诊断线非投屏展示，不依赖任何
 * 展开动作）：前置提示（[ScreencastHint]，含「去设置」导航）；失败终态（原因 + 已读
 * 按会话重置链）；连接段超时（[connectionTimedOut]——受理即开窗后的兜底回执，主呈现
 * 面在窗内超时态）。
 *
 * @param connectionTimedOut 连接段超时回执（宿主注入控制器单源；显示面按 status 为
 *   Connecting 门控）
 * @param onOpenDetached 开窗/聚焦动作（null = 入口不渲染——宿主未桥接形态）
 */
@Composable
fun ScreencastPanel(
    devices: DeviceStateSource,
    onGoSettings: (() -> Unit)? = null,
    sharedViewModel: ScreencastViewModel? = null,
    onOpenDetached: (() -> Unit)? = null,
    connectionTimedOut: Boolean = false,
) {
    // 宿主编排行（设备观测页复现记录编排）与面板须共享同一 VM 实例：编排转发
    // startCast 落的 hint/回执与面板同源，不共享即出现两份互不相见的实例
    val viewModel = sharedViewModel ?: viewModel(key = "screencast") { ScreencastViewModel(devices) }
    val status by viewModel.status.collectAsState()
    val hint by viewModel.hint.collectAsState()
    val dismissedFailure by viewModel.dismissedFailure.collectAsState()

    val failedTerminal = (status as? ScreencastStatus.Stopped)?.failed == true

    TonalCard(
        contentPaddingVertical = Dimens.space12,
        contentPaddingHorizontal = Dimens.space16,
        contentSpacing = Dimens.space8,
    ) {
        // 行本体不可点（真机终裁裁撤整行可点）：主钮是唯一入口与状态回显面，行本体
        // 无动作即无 hover 反馈（可点性反馈只给真实可点面，不给假可供性）
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            AppSymbolIcon(
                icon = MaterialSymbols.MONITOR,
                contentDescription = null,
                size = Dimens.iconMedium,
                tint = if (status is ScreencastStatus.Connecting || status is ScreencastStatus.Streaming) {
                    MaterialTheme.colorScheme.primary
                } else {
                    MaterialTheme.colorScheme.onSurfaceVariant
                },
            )
            Spacer(modifier = Modifier.width(Dimens.space8))
            Text(
                text = "投屏",
                style = MaterialTheme.typography.titleSmall,
            )
            // 纯启动器：行中段留白（无状态展示——fps/泊指文案在窗内状态条），主钮贴行尾
            Spacer(modifier = Modifier.weight(1f))
            when (status) {
                // 受理即开窗后连接段窗已在（窗内承载连接反馈）——行尾入口同为聚焦面；
                // 未桥接形态（无窗可聚焦）保留 busy 钮回显，不静默
                is ScreencastStatus.Connecting -> if (onOpenDetached != null) {
                    AppButton(
                        label = "聚焦窗口",
                        busy = false,
                        onClick = onOpenDetached,
                    )
                } else {
                    AppButton(
                        label = "连接中",
                        busy = true,
                        busyLabel = "连接中…",
                        onClick = viewModel::startCast,
                    )
                }

                is ScreencastStatus.Streaming -> if (onOpenDetached != null) {
                    AppButton(
                        label = "聚焦窗口",
                        busy = false,
                        onClick = onOpenDetached,
                    )
                }

                else -> AppButton(
                    label = if (failedTerminal) "重试" else "开始投屏",
                    busy = false,
                    onClick = viewModel::startCast,
                    leadingIcon = MaterialSymbols.PLAY_ARROW,
                )
            }
        }

        // 前置提示细行（无设备 / 检测失败 / 多设备未选定）：恒显有错才现，不静默
        val currentHint = hint
        if (currentHint != null) {
            StatusBanner(
                status = StatusBannerStatus.Error,
                text = currentHint.message,
                trailing = {
                    when (currentHint.action) {
                        ScreencastHint.Action.GO_SETTINGS -> onGoSettings?.let { go ->
                            TextButton(onClick = go) { Text("去设置配置 adb 路径") }
                        }

                        ScreencastHint.Action.SELECT_DEVICE ->
                            TextButton(onClick = viewModel::dismissHint) { Text("已选择") }

                        null -> TextButton(onClick = viewModel::dismissHint) { Text("知道了") }
                    }
                },
            )
        }

        // 失败终态回执细行（恒显不依赖展开；已读按会话重置——VM 在新会话起点清零，同
        // 文案失败重新可见），主钮同态变「重试」承载指路
        val failureMessage = (status as? ScreencastStatus.Stopped)?.takeIf { it.failed }?.message
        if (failureMessage != null && failureMessage != dismissedFailure) {
            StatusBanner(
                status = StatusBannerStatus.Error,
                text = "投屏失败：$failureMessage",
                trailing = {
                    TextButton(onClick = viewModel::dismissFailure) { Text("知道了") }
                },
            )
        }

        // 连接段超时回执细行（兜底位——受理即开窗后主呈现面在窗内超时态）：预算走完
        // 仍悬在连接段（未出画也未落终态）——显示面按 status 为 Connecting 门控（离开
        // 连接段即不再显示，回执由下一会话受理清）；落终态/出画后不叠加终态 banner
        if (connectionTimedOut && status is ScreencastStatus.Connecting) {
            StatusBanner(
                status = StatusBannerStatus.Error,
                text = "连接超时——画面未在等待预算内就绪（窗内可重试/取消）",
                trailing = {
                    TextButton(onClick = viewModel::startCast) { Text("重试") }
                },
            )
        }
    }
}

/**
 * 状态行文案：连接中 / 出图（设备名 · 分辨率 · 帧率 · 已泊 N 指）/ 终态。消费面 =
 * 独立窗状态条（主窗行卡是纯启动器，不渲染状态展示）。[parkedCount] = 在泊虚拟指
 * 数（0 = 不追加——泊指计数只在有泊指时可见）；泊指在停投/断连时清场，终态分支无需
 * 计数。
 */
internal fun statusLine(status: ScreencastStatus, fps: Int, parkedCount: Int = 0): String = when (status) {
    is ScreencastStatus.Idle -> "未在投屏"
    is ScreencastStatus.Connecting -> "连接中…"
    is ScreencastStatus.Streaming -> {
        val base = "${status.deviceName} · ${status.width}x${status.height} · ${fps} fps"
        if (parkedCount > 0) "$base · 已泊 $parkedCount 指" else base
    }
    is ScreencastStatus.Stopped ->
        if (status.failed) "已停止（失败）" else "已停止"
}

/**
 * 快照「送飞书」置灰判据：null = 可点；非 null = 置灰 hover 文案。
 * 真源 = :core ReportDelivery.ready 单源（未接线/未运行恒 false——不静默，hover 明示
 * 原因）。消费面 = 独立窗快照回执行。
 */
internal fun screencastLarkSendDisabledReason(larkDeliveryReady: Boolean): String? =
    if (larkDeliveryReady) null else "飞书桥接未运行，请先在飞书桥接页启动桥接"
