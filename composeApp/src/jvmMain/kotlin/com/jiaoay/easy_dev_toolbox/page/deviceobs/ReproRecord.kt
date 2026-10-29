package com.jiaoay.easy_dev_toolbox.page.deviceobs

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.style.TextOverflow
import com.jiaoay.easy_dev_toolbox.core.runtime.StatusSignals
import com.jiaoay.easy_dev_toolbox.core.theme.Dimens
import com.jiaoay.easy_dev_toolbox.core.theme.LocalEasyToolboxColors
import com.jiaoay.easy_dev_toolbox.core.theme.dayBattleTint
import com.jiaoay.easy_dev_toolbox.page.logcat.LogcatCaptureStatus
import com.jiaoay.easy_dev_toolbox.page.logcat.LogcatViewModel
import com.jiaoay.easy_dev_toolbox.page.screencast.ScreencastStatus
import com.jiaoay.easy_dev_toolbox.page.screencast.ScreencastViewModel
import com.jiaoay.easy_dev_toolbox.widget.TonalCard
import com.jiaoay.toolbelt.device.DeviceState
import com.jiaoay.toolbelt.device.DeviceStateSource
import com.jiaoay.toolbelt.device.PerfSampler
import com.jiaoay.toolbelt.device.PerfSessionState

/**
 * 复现场次一键联动采集的编排器（纯转发 + 回执聚合）。
 *
 * 零新状态契约：本类无可变字段——在跑判定与启动/停止都在动作时刻经 [Member]
 * 端口直读成员既有状态源（各引擎/VM 的 StateFlow 读值），不缓存成员会话态、
 * 不做聚合会话概念；竞态窗口内成员状态的变迁仍由各引擎既有的重入拒绝与世代闸
 * 自管（引擎契约不因编排而放宽或复制）。成员状态徽标由 [ReproRecordRow] 各读
 * 各的既有 StateFlow 呈现，不经本类（编排器引入镜像状态即产生漂移面）。
 *
 * 回执契约：一次编排动作恰好一条聚合回执经 [showReceipt]（生产接线 = 与成员
 * 回执同通道 [StatusSignals.show]，防成员逐个 toast 刷屏）。在跑成员跳过且
 * 单列明示（不重复起、不静默吞）；单成员同步失败只进聚合的失败行，不中断
 * 其余成员的转发。成员自身的异步失败（adb 断开、握手超时等）仍由成员面板以
 * 各自既有回执语言呈现——编排回执只承载编排动作本身的去向。
 */
class ReproRecordOrchestrator(
    private val members: List<Member>,
    private val showReceipt: (String) -> Unit,
    private val startGate: () -> String? = { null },
) {

    /**
     * 编排成员端口：采集通道（logcat 抓取 / 性能采样 / 投屏）的宿主适配形态。
     *
     * [isRunning] 必须即时读成员状态源，不得缓存（缓存即镜像状态漂移）；
     * [start] 只会在 [isRunning] 为 false 时被调（成员自身的重入拒绝兜竞态），
     * 同步抛异常即进聚合失败行；[stop] 须幂等（已停成员无副作用——三引擎
     * 既有 stop 语义天然满足）。
     */
    interface Member {
        /** 聚合回执用的成员名 */
        val label: String

        /** 在跑跳过时的单列回执文案（含成员名与动作语义，如「logcat 已在抓取，跳过」） */
        val skipReceipt: String

        /** 当前是否在跑（动作时刻即时判定） */
        fun isRunning(): Boolean

        /** 请求启动 */
        fun start()

        /** 请求停止（幂等） */
        fun stop()
    }

    /**
     * 一键齐起：启动门（设备域公共前置）拦截时只回执门消息、不向任何成员转发
     * （三成员的前置同为设备域三流判定，一次判全——拦下时逐成员启动只会重复落
     * 三份同因提示）；否则对每个成员——在跑跳过、空闲启动、同步异常进失败行。
     */
    fun startAll() {
        startGate()?.let { message ->
            showReceipt(message)
            return
        }
        val started = mutableListOf<String>()
        val skipped = mutableListOf<String>()
        val failed = mutableListOf<String>()
        for (member in members) {
            if (member.isRunning()) {
                skipped += member.skipReceipt
                continue
            }
            try {
                member.start()
                started += member.label
            } catch (e: Exception) {
                failed += "${member.label}（${e.message ?: e::class.simpleName ?: "未知异常"}）"
            }
        }
        startReceipt(started, skipped, failed)?.let(showReceipt)
    }

    /**
     * 一键全停：对每个在跑成员调用 [stop]（已停成员不调不进停止行——三引擎
     * stop 幂等，回执按动作时刻的在跑快照如实分列）；任一成员停止异常不拖累
     * 其余成员收割。
     */
    fun stopAll() {
        val stopped = mutableListOf<String>()
        val idle = mutableListOf<String>()
        val failed = mutableListOf<String>()
        for (member in members) {
            if (!member.isRunning()) {
                idle += member.label
                continue
            }
            try {
                member.stop()
                stopped += member.label
            } catch (e: Exception) {
                failed += "${member.label}（${e.message ?: e::class.simpleName ?: "未知异常"}）"
            }
        }
        stopReceipt(stopped, idle, failed)?.let(showReceipt)
    }

    companion object {

        /**
         * 开始动作的聚合回执（纯函数）：「已开始」行 + 在跑跳过单列行 + 失败行，
         * 分号连接。null = 三行全空（零成员转发零回执——无可奉告时不刷屏）。
         */
        internal fun startReceipt(
            started: List<String>,
            skipped: List<String>,
            failed: List<String>,
        ): String? {
            if (started.isEmpty() && skipped.isEmpty() && failed.isEmpty()) return null
            return buildList {
                if (started.isNotEmpty()) add("复现记录已开始：" + started.joinToString("、"))
                addAll(skipped)
                if (failed.isNotEmpty()) {
                    // 全军覆没时升格为动作级失败头；部分成功时失败只是行内事实
                    val head = if (started.isEmpty()) "复现记录启动失败：" else "启动失败："
                    add(head + failed.joinToString("、"))
                }
            }.joinToString("；")
        }

        /**
         * 停止动作的聚合回执（纯函数）：「已停止」行 + 空闲成员如实分列 + 停止
         * 失败行。全部空闲时回「无在跑成员」（可见钮不静默死点）。
         */
        internal fun stopReceipt(
            stopped: List<String>,
            idle: List<String>,
            failed: List<String>,
        ): String? {
            if (stopped.isEmpty() && idle.isEmpty() && failed.isEmpty()) return null
            if (stopped.isEmpty() && failed.isEmpty()) return "无在跑成员，无需停止"
            return buildList {
                if (stopped.isNotEmpty()) add("复现记录已停止：" + stopped.joinToString("、"))
                if (idle.isNotEmpty() && stopped.isNotEmpty()) {
                    add(idle.joinToString("、") + " 本就空闲")
                }
                if (failed.isNotEmpty()) add("停止失败：" + failed.joinToString("、"))
            }.joinToString("；")
        }
    }
}

/**
 * 编排启动门（设备域公共前置检查，纯函数）：三成员的启动前置同为设备域三流
 * 判定（检测失败 / 无设备 / 多设备未选定），一次判全。文案与成员面板的
 * precheck 同族口径（logcat / 投屏 VM 的前置提示）；null = 可启动。
 */
internal fun reproRecordGateMessage(
    deviceState: DeviceState,
    detectError: String?,
    selectedSerial: String?,
): String? = when {
    detectError != null -> "$detectError——请检查设置页 ADB 路径"
    deviceState is DeviceState.None -> "未检测到设备——连接设备后将自动检测"
    deviceState is DeviceState.Multi && selectedSerial == null ->
        "检测到多台设备，请先在上方设备栏选择目标设备"

    else -> null
}

/**
 * 编排成员装配（三件）：logcat 抓取经 [LogcatViewModel]（命令组装/产物路径/过滤
 * 定型都在 VM，编排不复制该链）；性能采样经 [PerfSampler] 默认路径（目标 =
 * 当前选定设备，与 PerfPanel 的开始钮同口径——单会话投影即本成员的在跑面，
 * 批量勾选起的多台会话不属本成员，停也不误伤）；投屏经 [ScreencastViewModel]。
 */
internal fun reproRecordMembers(
    logcat: LogcatViewModel,
    screencast: ScreencastViewModel,
    sampler: PerfSampler,
): List<ReproRecordOrchestrator.Member> = listOf(
    object : ReproRecordOrchestrator.Member {
        override val label = "logcat"
        override val skipReceipt = "logcat 已在抓取，跳过"

        override fun isRunning(): Boolean = logcat.status.value is LogcatCaptureStatus.Running
        override fun start() = logcat.startCapture()
        override fun stop() = logcat.stopCapture()
    },
    object : ReproRecordOrchestrator.Member {
        override val label = "性能"
        override val skipReceipt = "性能已在采样，跳过"

        override fun isRunning(): Boolean = sampler.state.value.session is PerfSessionState.Active
        override fun start() = sampler.startSampling()

        override fun stop() {
            // 只收编排所起的默认路径会话（选定设备），批量勾选的其它 serial 会话不误伤
            val session = sampler.state.value.session
            if (session is PerfSessionState.Active) sampler.stopSampling(session.serial)
        }
    },
    object : ReproRecordOrchestrator.Member {
        override val label = "投屏"
        override val skipReceipt = "投屏已在进行，跳过"

        override fun isRunning(): Boolean = screencast.status.value.let {
            it is ScreencastStatus.Connecting || it is ScreencastStatus.Streaming
        }

        override fun start() = screencast.startCast()
        override fun stop() = screencast.stopCast()
    },
)

/**
 * 复现记录编排行（设备观测空间聚合钮行）：一键同起/同停三成员 + 三成员
 * 在跑徽标。徽标各读各的既有 StateFlow（编排器零镜像状态），聚合钮状态 =
 * 三徽标的纯组合（任一在跑即停态）。视觉层为保守默认，真机手感由维护者终裁。
 */
@Composable
fun ReproRecordRow(
    devices: DeviceStateSource,
    logcat: LogcatViewModel,
    screencast: ScreencastViewModel,
    sampler: PerfSampler,
) {
    val orchestrator = remember(logcat, screencast, sampler, devices) {
        ReproRecordOrchestrator(
            members = reproRecordMembers(logcat, screencast, sampler),
            showReceipt = StatusSignals::show,
            startGate = {
                reproRecordGateMessage(
                    deviceState = devices.deviceState.value,
                    detectError = devices.detectError.value,
                    selectedSerial = devices.selectedSerial.value,
                )
            },
        )
    }

    val logcatStatus by logcat.status.collectAsState()
    val screencastStatus by screencast.status.collectAsState()
    val samplerState by sampler.state.collectAsState()
    val logcatRunning = logcatStatus is LogcatCaptureStatus.Running
    val screencastRunning = screencastStatus is ScreencastStatus.Connecting ||
        screencastStatus is ScreencastStatus.Streaming
    val perfRunning = samplerState.session is PerfSessionState.Active
    val anyRunning = logcatRunning || perfRunning || screencastRunning

    TonalCard(
        contentPaddingVertical = Dimens.space12,
        contentPaddingHorizontal = Dimens.space16,
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(Dimens.space12),
        ) {
            // 头部簇恒内容宽——勿与行尾 Spacer 同挂 weight（双 weight 配额平分，
            // 徽标堆行中部、右缘悬空）
            Column {
                Text(
                    text = "复现记录",
                    style = MaterialTheme.typography.titleSmall,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(
                    text = "logcat / 性能 / 投屏 一键同起同停",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            Spacer(modifier = Modifier.weight(1f))

            ReproMemberBadge(label = "logcat", running = logcatRunning)
            ReproMemberBadge(label = "性能", running = perfRunning)
            ReproMemberBadge(label = "投屏", running = screencastRunning)

            if (anyRunning) {
                OutlinedButton(onClick = { orchestrator.stopAll() }) {
                    Text("停止复现记录")
                }
            } else {
                Button(onClick = { orchestrator.startAll() }) {
                    Text("开始复现记录")
                }
            }
        }
    }
}

/** 单成员在跑徽标：状态点（在跑 = success 语义色）+ 成员名 + 态文字 */
@Composable
private fun ReproMemberBadge(label: String, running: Boolean) {
    val toolboxColors = LocalEasyToolboxColors.current
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(Dimens.space4),
    ) {
        Box(
            modifier = Modifier
                .size(Dimens.dotIndicatorSize)
                .clip(CircleShape)
                .background(
                    if (running) {
                        dayBattleTint(toolboxColors.success, toolboxColors.successContainer)
                    } else {
                        MaterialTheme.colorScheme.onSurfaceVariant
                    },
                ),
        )
        Text(
            text = "$label ${if (running) "在跑" else "空闲"}",
            style = MaterialTheme.typography.labelMedium,
            color = if (running) {
                MaterialTheme.colorScheme.onBackground
            } else {
                MaterialTheme.colorScheme.onSurfaceVariant
            },
            maxLines = 1,
        )
    }
}
