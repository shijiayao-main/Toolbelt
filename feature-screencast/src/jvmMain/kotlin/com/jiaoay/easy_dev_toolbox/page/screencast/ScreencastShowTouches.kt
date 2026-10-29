package com.jiaoay.easy_dev_toolbox.page.screencast

import com.jiaoay.easy_dev_toolbox.core.data.AppDataStore
import com.jiaoay.easy_dev_toolbox.core.data.DomainConfigStore
import com.jiaoay.easy_dev_toolbox.core.data.LocalDataManager
import com.jiaoay.easy_dev_toolbox.core.log.JLog
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * 显示触点持久标记仓库（feature 域 ConfigStore——恒经 :core [AppDataStore] 进程唯一实例，
 * 读写样板继承 [DomainConfigStore] 共享骨架）。
 *
 * 标记不变量：active=true ⟺ 设备端 `show_touches` 当前值 ≠ 会话捕获原值。标记只在
 * 「即将改写设备值」前落档（崩溃安全序，见 [writeMarker]）、在设备值回到原值时清档；
 * 据此 App 次启动核对（[ScreencastShowTouches.restoreResidueAtStartup]）能精确判定
 * 是否存在需要恢复的残留改写。
 */
object ScreencastShowTouchesStore : DomainConfigStore() {

    /** 残留改写标记（false = 无残留——设备值与原值一致或本进程从未改写） */
    internal const val KEY_SHOW_TOUCHES_ACTIVE = "screencast_show_touches_active"

    /** 被改写前的设备原值（"0"/"1"；仅在 active=true 时有意义） */
    internal const val KEY_SHOW_TOUCHES_ORIGINAL = "screencast_show_touches_original"

    /** 改写发生时锁定的设备串号（恢复命令的目标设备；空串 = 无串号） */
    internal const val KEY_SHOW_TOUCHES_SERIAL = "screencast_show_touches_serial"

    /** 测试缝：jvmTest 可整体替换为隔离实例（对齐各域 ConfigStore 既有约定） */
    internal var store: LocalDataManager = AppDataStore.store

    override val activeStore: LocalDataManager
        get() = store

    suspend fun isActive(): Boolean = read(KEY_SHOW_TOUCHES_ACTIVE, false)

    suspend fun readOriginal(): String = read(KEY_SHOW_TOUCHES_ORIGINAL, ScreencastShowTouches.VALUE_OFF)

    suspend fun readSerial(): String = read(KEY_SHOW_TOUCHES_SERIAL, "")

    /**
     * 落档残留意图：original/serial 先写、active 最后写——标记先于设备值改写落盘
     * （崩溃安全序）：两写之间被杀时 active 未置位（设备未被改写，无须恢复）；
     * 设备改写成功后被杀时标记已在档，次启动核对按标记恢复。active=true 而设备
     * 实际未被改写的形态下，恢复写是幂等 no-op，无害。
     */
    suspend fun writeMarker(original: String, serial: String?) {
        write(KEY_SHOW_TOUCHES_ORIGINAL, original)
        write(KEY_SHOW_TOUCHES_SERIAL, serial.orEmpty())
        write(KEY_SHOW_TOUCHES_ACTIVE, true)
    }

    /** 清档残留意图（设备值已回到原值；original/serial 残值留在档上无害，active 是唯一判定位） */
    suspend fun clearMarker() {
        write(KEY_SHOW_TOUCHES_ACTIVE, false)
    }
}

/**
 * 显示触点会话控制器（每次投屏会话一实例，[ScreencastEngine.start] 建造）：
 * 会话期临时打开设备系统「显示点按操作」（`settings put system show_touches 1`），
 * 令复现录像里可见真人手指轨迹——与日志时间轴对齐。
 *
 * 恢复语义三件（缺一即红，单测逐面守护）：
 * 1. **原值捕获**：首次开启先 `settings get` 读回设备原值——原值已是 "1" 则直通置
 *    「已开」态、不写 1（设备未被改写，无恢复面）；
 * 2. **会话收口恢复**：[restoreOnSessionEnd] 由引擎会话协程 finally 的 NonCancellable
 *    段调用——停投/关窗/断连/退出序全部经引擎 [ScreencastEngine.stop] 或会话自灭汇入
 *    该单点，恢复动作不散点；
 * 3. **强杀残留兜底**：改写设备前先经 [ScreencastShowTouchesStore.writeMarker] 落持久
 *    标记（原值 + 串号）；正常恢复清标记；恢复失败（设备拔线等）保留标记，App 次启动
 *    经 [restoreResidueAtStartup] 按标记恢复原值并清标记——设备常显触摸圈的残留场景
 *    由该核对兜底，非可选项。
 *
 * 命令通道恒经 [ScrcpyAdbGateway]（生产实现 SimpleShell + AdbCommandRewriter 注入
 * adb 路径与串号），不另起第二执行通道。
 *
 * 线程模型：[enable]/[disable] 由调用方在 IO 调度器调用（adb 子进程往返）；
 * [restoreOnSessionEnd] 在会话协程 finally 内调用。会话存续期内调用方串行
 * （开关钮点击经 UI 串行分发），无并发防护诉求。
 *
 * @param adb 会话绑定的 adb 网关（与引擎会话同源 adbPath/serial）
 * @param persistence 持久标记仓库（默认进程唯一实例；测试注入隔离实例）
 * @param serial 会话绑定的设备串号（进残留标记——次启动恢复命令的目标设备）
 * @param enabledState 开关态宿主（引擎级单一流实例跨会话复用——会话收口后回落 false）
 */
internal class ScreencastShowTouches(
    private val adb: ScrcpyAdbGateway,
    private val persistence: ScreencastShowTouchesStore = ScreencastShowTouchesStore,
    private val serial: String? = null,
    private val enabledState: MutableStateFlow<Boolean> = MutableStateFlow(false),
) {

    /** 开关态（true = 设备端显示触点已开/直通；会话收口恒回落 false） */
    val enabled: StateFlow<Boolean> = enabledState.asStateFlow()

    /** 会话捕获的设备原值（null = 本会话未触达设备——无恢复面） */
    private var originalValue: String? = null

    /** 对设备当前值的信念（最近一次读/写成功值；null = 未知） */
    private var deviceValue: String? = null

    /**
     * 请求开启显示触点。返回 null = 成功；非 null = 失败原因（不静默）。
     *
     * 失败不翻开关态、不残留标记（标记只在设备改写前落档，写入失败即清——设备未被
     * 改写）。标记已在档（上一进程强杀残留）时先自愈：按标记恢复原值并清标记后才
     * 捕获原值——防止把残留开态误捕为原值（残留会永久固化）。
     */
    suspend fun enable(): String? {
        if (persistence.isActive()) {
            val residueOriginal = persistence.readOriginal()
            val residueFailure = executePut(adb, residueOriginal)
            if (residueFailure != null) {
                return "清理上一会话显示触点残留失败：$residueFailure"
            }
            persistence.clearMarker()
            deviceValue = residueOriginal
            originalValue = residueOriginal
        }
        if (deviceValue == null) {
            val current = executeGet(adb) ?: return "读取设备 show_touches 当前值失败"
            originalValue = current
            deviceValue = current
        }
        if (deviceValue != VALUE_ON) {
            // 崩溃安全序：意图标记先于设备写入落档（见 ScreencastShowTouchesStore.writeMarker）
            persistence.writeMarker(originalValue!!, serial)
            val failure = executePut(adb, VALUE_ON)
            if (failure != null) {
                persistence.clearMarker()
                return "写入设备 show_touches 失败：$failure"
            }
            deviceValue = VALUE_ON
        }
        enabledState.value = true
        return null
    }

    /**
     * 请求关闭显示触点（写 "0"——关闭是用户显式语义，非恢复原值）。返回 null = 成功；
     * 非 null = 失败原因。原值为 "1" 时关闭构成对原值的偏离，标记保持活跃——会话收口
     * 与次启动核对按原值恢复。
     */
    suspend fun disable(): String? {
        val original = originalValue ?: return "设备 show_touches 原值未捕获——开关态未知"
        if (deviceValue != VALUE_OFF) {
            persistence.writeMarker(original, serial)
            val failure = executePut(adb, VALUE_OFF)
            if (failure != null) {
                persistence.clearMarker()
                return "写入设备 show_touches 失败：$failure"
            }
            deviceValue = VALUE_OFF
        }
        enabledState.value = false
        if (deviceValue == original) persistence.clearMarker()
        return null
    }

    /**
     * 会话收口恢复（引擎会话 finally 单点调用）：本会话未触达设备（原值未捕获）即
     * no-op；设备值偏离原值时写回原值并清标记。恢复失败（设备拔线等）保留标记——
     * App 次启动核对续试。开关态恒回落 false（会话已终）。
     */
    suspend fun restoreOnSessionEnd() {
        val original = originalValue ?: return
        if (deviceValue != original) {
            val failure = executePut(adb, original)
            if (failure != null) {
                JLog.e(TAG) { "show touches restore on session end failed: $failure (marker kept)" }
            } else {
                deviceValue = original
                persistence.clearMarker()
            }
        }
        enabledState.value = false
    }

    companion object {

        private const val TAG = "ScreencastShowTouches"

        /** 开启档（settings put system show_touches 1） */
        const val VALUE_ON = "1"

        /** 关闭档（settings put system show_touches 0；未设置键的 settings get 回显 "null" 亦归一为本档） */
        const val VALUE_OFF = "0"

        /** 读回命令（经网关重写注入 adb 路径与串号） */
        internal fun buildGetCommand(): String = "adb shell settings get system show_touches"

        /** 写入命令（[value] 恒 "0"/"1"——常量面收口，不接受任意串） */
        internal fun buildPutCommand(value: String): String =
            "adb shell settings put system show_touches $value"

        /**
         * App 次启动残留核对（兜底验收面）：标记 active=true（上一进程开启显示触点后
         * 被强杀，设备端残留开启态）→ 按标记原值 + 串号恢复并清标记；恢复失败（设备
         * 未连等）保留标记，下次启动再试。返回 true = 恢复成功且标记已清；false =
         * 无残留（标记本就不在档）或本次恢复失败（标记保留续试）。
         *
         * @param gatewayFactory 串号 → 会话网关（生产 = SimpleShellScrcpyAdbGateway +
         *   adbPath；测试注入 fake，零真 adb）
         */
        suspend fun restoreResidueAtStartup(
            gatewayFactory: (serial: String?) -> ScrcpyAdbGateway,
            persistence: ScreencastShowTouchesStore = ScreencastShowTouchesStore,
        ): Boolean {
            if (!persistence.isActive()) return false
            val original = persistence.readOriginal()
            val serial = persistence.readSerial().ifBlank { null }
            val failure = executePut(gatewayFactory(serial), original)
            if (failure == null) {
                persistence.clearMarker()
                JLog.d(TAG) { "show touches residue restored at startup: original=$original serial=$serial" }
                return true
            }
            JLog.e(TAG) { "show touches residue restore failed at startup: $failure (marker kept)" }
            return false
        }

        /** 写入命令执行：返回 null = 成功；非 null = 失败原因（进程失败/退出码非 0） */
        private suspend fun executePut(adb: ScrcpyAdbGateway, value: String): String? {
            val result = adb.execute(buildPutCommand(value))
            result.exceptionOrNull()?.let { return it.message ?: "adb 进程启动失败" }
            val shell = result.getOrNull() ?: return "adb 执行无回执"
            if (shell.exitCode != 0) {
                return "adb settings put 退出码 ${shell.exitCode}：${shell.stderr.trim().ifBlank { "无 stderr 输出" }}"
            }
            return null
        }

        /**
         * 读回命令执行：返回 "0"/"1"；null = 失败（进程失败/退出码非 0/空回显）。
         * Android `settings get` 对未设置键回显字面量 "null"——归一为关闭档。
         */
        private suspend fun executeGet(adb: ScrcpyAdbGateway): String? {
            val result = adb.execute(buildGetCommand())
            if (result.isFailure) return null
            val shell = result.getOrNull() ?: return null
            if (shell.exitCode != 0) return null
            val value = shell.stdout.trim()
            if (value.isEmpty()) return null
            return if (value == "null") VALUE_OFF else value
        }
    }
}
