package com.jiaoay.easy_dev_toolbox.page.screencast

import java.io.File
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import com.jiaoay.toolbelt.device.DeviceApkInstaller
import com.jiaoay.toolbelt.device.DeviceFilePusher
import com.jiaoay.toolbelt.device.DevicePushTargetExistsException

/**
 * 拖拽推送的设备目标目录：设备观测「文件」子页推送动作的既有默认目标 = 浏览起点根
 * `/sdcard/`。拖拽面无浏览栈，恒推送至同一起点根——与文件子页缺省目标一致，不造
 * 第二默认。值锚（与文件子页起点根逐字节一致）由单测钉死。
 */
internal const val SCREENCAST_DROP_PUSH_DEVICE_DIR = "/sdcard/"

/**
 * 拖入 `.apk` 判别（大小写后缀不敏感；internal 供单测钉死路由语义）：命中即走
 * 安装管线（恒过安装确认对话），未命中恒走推送管线——两类文件各走各的通道。
 */
internal fun isApkDrop(fileName: String): Boolean = fileName.lowercase().endsWith(".apk")

/** 单文件回执行上界：防一次大批量拖入把窗内回执行撑爆（会话缓存上界同纪律） */
internal const val DROP_PUSH_LINES_LIMIT = 20

/**
 * 拖拽推送/安装的单文件回执行（逐件持态行，窗内渲染面）：[Pushing]/[Pushed]/
 * [Installing]/[Installed]/[Rejected] 五形态与快照/录像回执行的 Saved/Failed 双
 * 形态同构——Rejected 是统称拒收行（确认取消 / 推送失败 / 安装失败人话），
 * message 为人话原文不翻译。
 */
internal sealed interface ScreencastDropPushLine {
    val fileName: String

    /** 推送在途（预检/传输/后置校验全程占位） */
    data class Pushing(override val fileName: String) : ScreencastDropPushLine

    /** 推送完成（[sizeBytes] 是设备侧校验通过的大小） */
    data class Pushed(
        override val fileName: String,
        val devicePath: String,
        val sizeBytes: Long,
    ) : ScreencastDropPushLine

    /**
     * 安装在途（安装确认对话挂起期与安装执行全程占位——与推送在途行同构：确认
     * 裁决挂起期持态行不翻面）。
     */
    data class Installing(override val fileName: String) : ScreencastDropPushLine

    /**
     * 安装完成（追溯档位与 :core [DeviceApkInstaller] 回执同语义）：
     * [sizeBytes] 本地大小；[adbEcho] adb 回显原文（Success 标记行及其前后行，
     * 不翻译不截断——装了什么的追溯底线）；[packageName]/[versionName]/
     * [identityNote] 是升档尽力而为档（升级覆盖/列举失败形态缺席如实注明，见
     * 内核回执契约）。
     */
    data class Installed(
        override val fileName: String,
        val sizeBytes: Long,
        val adbEcho: String,
        val packageName: String?,
        val versionName: String?,
        val identityNote: String?,
    ) : ScreencastDropPushLine

    /** 拒收/失败（[message] 人话原文；该文件未进设备或未改动设备侧） */
    data class Rejected(override val fileName: String, val message: String) : ScreencastDropPushLine
}

/** 覆盖确认对话数据（设备目标已存在、等待用户裁决两态的在场呈现面） */
internal data class ScreencastDropPushConfirm(
    val fileName: String,
    val devicePath: String,
)

/** 安装确认对话数据（本地 APK 待安装、等待用户裁决两态的在场呈现面） */
internal data class ScreencastApkInstallConfirm(
    val fileName: String,
    val sizeBytes: Long,
)

/**
 * 投屏独立窗拖拽推送/安装路由器：文件拖进独立窗 → 非 `.apk` 逐文件经 :core
 * [DeviceFilePusher] 既有覆盖确认管线推送（预检 → 已存在类型化拒绝 → 覆盖确认 →
 * 推送后 stat 校验），`.apk` 经 :core [DeviceApkInstaller] 走安装管线——本层不
 * 另起任何写入通道。
 *
 * 契约：
 * - **会话期限定**：router 由窗内容组合创建、scope 是窗组合的 UI scope——窗关
 *   （会话结束/停投自动关窗）即 scope 取消，在途推送/安装在途 adb 进程经
 *   [DeviceFilePusher.pushFile]/[DeviceApkInstaller.installApk] 的取消收割链终止，
 *   无会话残留路径；
 * - **安装确认不可省**：`.apk` 恒先经 [installConfirm] 暴露确认数据（文件名 +
 *   大小）等用户裁决，确认（[confirmInstall]）前零 adb install spawn——安装是
 *   不可逆动作（基线机被装未受控版本即被测环境被毁），静默安装零容忍；
 * - **两道确认独立分层**：安装确认与覆盖确认是两道独立确认（一个文件最多各过
 *   一道——`.apk` 只过安装确认、普通文件只可能过覆盖确认），裁决槽互不串道；
 * - **取消 = 零动作零写**：任一确认取消即该文件零设备侧改动、落拒收回执后继续
 *   后续文件（确认/取消只裁决当前文件）；
 * - **逐文件处理**：[lines] 逐件追加持态行（在途行原位翻面为完成/拒收），单件
 *   失败/拒收不中断后续文件；
 * - **批间互斥**：批处理在途时 [offer] 拒新批，拒绝人话由调用面落窗内瞬态文本；
 * - **批次回执**：每次受理的 [offer] 清空上一批回执行（回执面是「本次拖入」的
 *   呈现，跨批历史不驻留）。
 */
internal class ScreencastDropPushRouter(
    private val scope: CoroutineScope,
    runner: com.jiaoay.toolbelt.device.AdbProcessRunner,
    endpointReader: () -> com.jiaoay.toolbelt.adb.AdbEndpoint,
    private val deviceDir: String = SCREENCAST_DROP_PUSH_DEVICE_DIR,
    /** 测试注入缝：生产不传，按 [runner] 与 [endpointReader] 自建 */
    private val pusher: com.jiaoay.toolbelt.device.DeviceFilePusher =
        com.jiaoay.toolbelt.device.DeviceFilePusher(runner = runner, endpointReader = endpointReader),
    /** 测试注入缝：同上 */
    private val installer: com.jiaoay.toolbelt.device.DeviceApkInstaller =
        com.jiaoay.toolbelt.device.DeviceApkInstaller(
            runner = runner,
            packageBrowser = com.jiaoay.toolbelt.device.DevicePackageBrowser(runner = runner, endpointReader = endpointReader),
            endpointReader = endpointReader,
        ),
) {

    private val _lines = MutableStateFlow<List<ScreencastDropPushLine>>(emptyList())

    /** 本次拖入批的逐文件回执行（最近在上未过界淘汰——渲染面恒有界） */
    val lines: StateFlow<List<ScreencastDropPushLine>> = _lines.asStateFlow()

    private val _confirm = MutableStateFlow<ScreencastDropPushConfirm?>(null)

    /** 覆盖确认在场数据（null = 无待裁决覆盖；确认/取消即清） */
    val confirm: StateFlow<ScreencastDropPushConfirm?> = _confirm.asStateFlow()

    private val _installConfirm = MutableStateFlow<ScreencastApkInstallConfirm?>(null)

    /** 安装确认在场数据（null = 无待裁决安装；确认/取消即清） */
    val installConfirm: StateFlow<ScreencastApkInstallConfirm?> = _installConfirm.asStateFlow()

    /** 批处理在途标记（在途批未收尾前拒新批——推送/安装内核单飞行） */
    @Volatile
    private var batchActive = false

    /** 覆盖确认在途裁决槽（null = 无待裁决文件） */
    @Volatile
    private var pendingDecision: CompletableDeferred<Boolean>? = null

    /** 安装确认在途裁决槽（null = 无待裁决 APK；与覆盖裁决槽互不串道） */
    @Volatile
    private var pendingInstallDecision: CompletableDeferred<Boolean>? = null

    /**
     * 受理一批拖入文件（drop 事件的消费入口）。返回 null = 已受理（回执经
     * [lines]/[confirm] 落窗内呈现面）；非 null = 拒收人话（调用面落瞬态文本，
     * 零动作）。[serial] 是会话绑定的设备串号（Streaming 态快照值），空/缺席
     * 整批拒绝零 pusher 调用。
     */
    fun offer(files: List<File>, serial: String?): String? {
        if (files.isEmpty()) return "拖入内容不含本地普通文件——仅支持拖入普通文件"
        if (serial.isNullOrEmpty()) return "会话未绑定设备串号，未执行推送"
        if (batchActive) return "已有推送批在途，本次拖入未受理——等当前批收尾后再拖"
        batchActive = true
        _lines.value = emptyList()
        scope.launch {
            try {
                for (file in files) {
                    if (isApkDrop(file.name)) {
                        installOne(file, serial)
                    } else {
                        pushOne(file, serial)
                    }
                }
            } finally {
                batchActive = false
            }
        }
        return null
    }

    /** 确认覆盖推送（[confirm] 在场时的确认分支）：overwrite=true 重推当前文件 */
    fun confirmOverwrite() {
        pendingDecision?.complete(true)
    }

    /** 取消覆盖（零动作——设备侧目标未被触碰），当前文件落拒收回执后继续后续文件 */
    fun dismissConfirm() {
        pendingDecision?.complete(false)
    }

    /** 确认安装（[installConfirm] 在场时的确认分支）：放行当前 APK 的安装执行 */
    fun confirmInstall() {
        pendingInstallDecision?.complete(true)
    }

    /** 取消安装（零动作——设备侧未被触碰），当前 APK 落拒收回执后继续后续文件 */
    fun dismissInstall() {
        pendingInstallDecision?.complete(false)
    }

    /**
     * 单文件推送执行体：在途行占位 → [DeviceFilePusher.pushFile]（覆盖确认管线
     * 既有分层）→ 结果行原位翻面。存在拒绝是类型化回执，本层据此挂确认裁决
     * （await 挂起期间批保持 in-flight，新 drop 被拒）；其余失败人话原样进拒收行。
     */
    private suspend fun pushOne(file: File, serial: String) {
        val index = appendLine(ScreencastDropPushLine.Pushing(file.name))
        pusher.pushFile(file.absolutePath, deviceDir, serial, overwrite = false).fold(
            onSuccess = { receipt ->
                replaceLineAt(
                    index,
                    ScreencastDropPushLine.Pushed(file.name, receipt.devicePath, receipt.sizeBytes),
                )
            },
            onFailure = { e ->
                if (e is DevicePushTargetExistsException) {
                    settleOverwriteDecision(file, serial, index, e.devicePath)
                } else {
                    replaceLineAt(index, ScreencastDropPushLine.Rejected(file.name, e.message ?: "推送失败（未知错误）"))
                }
            },
        )
    }

    /**
     * 覆盖确认裁决收口：挂出 [ScreencastDropPushConfirm] 等 [confirmOverwrite]/
     * [dismissConfirm] 二选一——确认分支 overwrite=true 重推（跳过预检，用户已
     * 裁决）；取消分支零动作落拒收回执。scope 取消（窗关）时裁决槽随协程一并
     * 消亡，无会话残留路径。
     */
    private suspend fun settleOverwriteDecision(
        file: File,
        serial: String,
        lineIndex: Int,
        devicePath: String,
    ) {
        _confirm.value = ScreencastDropPushConfirm(file.name, devicePath)
        val deferred = CompletableDeferred<Boolean>()
        pendingDecision = deferred
        val confirmed = try {
            deferred.await()
        } finally {
            pendingDecision = null
            _confirm.value = null
        }
        if (!confirmed) {
            replaceLineAt(lineIndex, ScreencastDropPushLine.Rejected(file.name, "已取消覆盖——设备侧既有文件未改动"))
            return
        }
        pusher.pushFile(file.absolutePath, deviceDir, serial, overwrite = true).fold(
            onSuccess = { receipt ->
                replaceLineAt(
                    lineIndex,
                    ScreencastDropPushLine.Pushed(file.name, receipt.devicePath, receipt.sizeBytes),
                )
            },
            onFailure = { e ->
                replaceLineAt(lineIndex, ScreencastDropPushLine.Rejected(file.name, e.message ?: "推送失败（未知错误）"))
            },
        )
    }

    /**
     * 单 APK 安装执行体：在途行占位 → 安装确认裁决（恒过确认对话，确认前零
     * spawn——安装不可逆，静默安装零容忍）→ [DeviceApkInstaller.installApk] →
     * 结果行原位翻面。await 挂起期间批保持 in-flight（新 drop 被拒）。
     */
    private suspend fun installOne(file: File, serial: String) {
        val index = appendLine(ScreencastDropPushLine.Installing(file.name))
        val confirmed = awaitInstallDecision(file)
        if (!confirmed) {
            replaceLineAt(
                index,
                ScreencastDropPushLine.Rejected(file.name, "已取消安装——设备侧未改动，零动作"),
            )
            return
        }
        installer.installApk(file.absolutePath, serial).fold(
            onSuccess = { receipt ->
                replaceLineAt(
                    index,
                    ScreencastDropPushLine.Installed(
                        fileName = receipt.localFileName,
                        sizeBytes = receipt.sizeBytes,
                        adbEcho = receipt.adbOutput,
                        packageName = receipt.packageName,
                        versionName = receipt.versionName,
                        identityNote = receipt.identityNote,
                    ),
                )
            },
            onFailure = { e ->
                replaceLineAt(index, ScreencastDropPushLine.Rejected(file.name, e.message ?: "安装失败（未知错误）"))
            },
        )
    }

    /**
     * 安装确认裁决收口：挂出 [ScreencastApkInstallConfirm]（本地文件名 + 大小——
     * 确认对话文案的追溯基线）等 [confirmInstall]/[dismissInstall] 二选一——确认
     * 分支放行安装，取消分支零动作（设备侧未被触碰）。scope 取消（窗关）时裁决
     * 槽随协程一并消亡，在途 adb 进程由内核收割链终止，无会话残留路径。
     */
    private suspend fun awaitInstallDecision(file: File): Boolean {
        _installConfirm.value = ScreencastApkInstallConfirm(file.name, file.length())
        val deferred = CompletableDeferred<Boolean>()
        pendingInstallDecision = deferred
        return try {
            deferred.await()
        } finally {
            pendingInstallDecision = null
            _installConfirm.value = null
        }
    }

    /** 追加回执行并返回其下标（上界淘汰最旧；下标供原位翻面） */
    private fun appendLine(line: ScreencastDropPushLine): Int {
        _lines.update { current -> (current + line).takeLast(DROP_PUSH_LINES_LIMIT) }
        return _lines.value.lastIndex
    }

    /** 原位翻面（下标越界 = 该行已被上界淘汰，保持淘汰语义不补位） */
    private fun replaceLineAt(index: Int, line: ScreencastDropPushLine) {
        _lines.update { current ->
            if (index in current.indices) {
                current.toMutableList().also { it[index] = line }
            } else {
                current
            }
        }
    }
}
