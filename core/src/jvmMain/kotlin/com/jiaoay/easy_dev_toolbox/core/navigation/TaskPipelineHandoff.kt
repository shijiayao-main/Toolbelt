package com.jiaoay.easy_dev_toolbox.core.navigation

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * 工具链「送往下一步」衔接契约（标准日志分析管线：
 * 解压 → XLog 解密 → Tag 过滤 → 分割）。
 *
 * ## 定位
 * 工具页产物卡成功态情境动作「送往 &lt;下一级工具&gt;」的单源契约：
 * - **生产侧**（feature 工具页）：按产物来源页查 [nextToolTargetOf] 得目标，
 *   产物路径经 [offer] 投递——目标命令 + 产物路径预填进命令面板参数直填态，
 *   用户只补剩余参数（如 Tag 正则）即执行；
 * - **消费侧**（宿主）：palette 打开接线（[wire]，palette 可见态归属 main.kt，
 *   借道宿主打开命令面板）+ [consumeSeed] 打开瞬间取载荷进参数直填态；
 *   消费契约双路径（槽 = [pendingSeed] StateFlow）：① 打开瞬间——palette
 *   关→开时宿主组合全新起播，初值承载一次取走槽内既有载荷；② 面板已开——
 *   palette 保持组合期间新 offer 到达（唯一悬于已开面板之上的生产面 = 查看器
 *   独立窗口，宿主 open 同值写回、effect 不重跑），消费面订阅 [pendingSeed]
 *   流即时收到新载荷。两路径同用 [consumeSeed] 读即清收口，同一 seed 恰消费
 *   一次，不滞留迟发；
 * - **表单化而非静默直执行**：下一级工具可能带必填用户参数（Tag 过滤的正则
 *   与输出标记），静默直执行无参数可取——palette 参数直填态是既有命令执行面，
 *   必填参数留白由用户补齐。
 *
 * ## 产物类型 → 目标映射（单张表 [PIPELINE]，语义判据）
 * - **XLog 解密产物**（`${name}.txt` 纯文本）→ Tag 过滤：过滤页输入类型即
 *   `.txt` 纯文本日志，管线主干衔接；
 * - **logcat 抓取产物**（`logcat-<时间戳>.txt` 纯文本）→ Tag 过滤：抓取的
 *   落点即「过滤定位」，行形态与过滤页输入同型（管线支线衔接）；
 * - **查看器打开的文件**（可读文本日志，外来文件无生产页身份）→ Tag 过滤：
 *   读面读到可疑 Tag 后一键投递，文件即过滤页输入类型（读面侧衔接）；
 * - **查看器拒收的加密件**（XLOG_SEGMENTS 判型拒绝）→ XLog 解密：加密件即
 *   解密页输入类型，Failed 态一键投递回管线主干前半段（解密后产物再进 Tag 过滤）；
 * - **Tag 过滤产物**：已按 Tag 收敛的小文件，主去向是深度分析（既有条目），
 *   不再映射分割；
 * - **分割产物**（`<name>_000.txt` 分卷序列）：管线终点，无下一级；
 * - **解压产物**（`<name>_extracted` 目录）→ XLog 解密：两分支契约——调用方
 *   （解压页）在条目动作内先以 `.xlog`/`.alog` accepts 枚举产物目录，
 *   **单文件**分支经 [offer] 直投该文件进 palette 参数直填态（表行承载目标
 *   与落点）；**多文件**分支经跳页槽 [GoXLogDecryptHook] 携**目录路径**（非
 *   预展开清单）去 XLog 解密页——消费侧组合期 [GoXLogDecryptHook.consumePendingDirectory]
 *   后以页内同款 accepts 展开并批量入任务卡（accepts 过滤语义单源留在 XLog
 *   解密域）；**零匹配**分支不投递（调用方就地回执原因）。
 * - **设备文件拉取产物**（拉取到本地的设备文件）→ 按后缀分立两条：`.txt`/无后缀
 *   （可读文本）→ Tag 过滤；`.xlog`/`.alog`（加密件）→ XLog 解密——调用方（设备
 *   文件面板）按产物后缀查对应来源常量，[nextToolTargetOf] 对未映射后缀返回
 *   null（条目不出现）。
 * 未登记的产物来源页一律返回 null（调用方条目不出现，无静默兜底目标）。
 *
 * 管线衔接之外另有**横向直投目标**（表外常量 [VIEWER_TIME_SLICE]）：目标产物
 * 是本级产物的子集而非下一级管线段的输入，不进 [PIPELINE] 表，由调用方显式
 * 点名常量直投，`nextToolTargetOf` 查表恒不返回。
 *
 * [commandId] 是字符串锚：目标命令 id 改名时 palette 解析落空 → 静默回落
 *    列表态（不炸面板），但衔接失效——[TaskPipelineHandoffTest] 断言 id 在
 *    LogKitContributions 注册表内防漂移。
 */
object TaskPipelineHandoff {

    /** 下一级工具目标（带参命令形态——palette 参数直填态消费） */
    data class NextToolTarget(
        /** 情境菜单条目文案（动作动词开头，如「送往 Tag 过滤」） */
        val label: String,
        /** 目标带参命令 id（[ParamCommandSpec.id]——palette 全量目录内解析） */
        val commandId: String,
        /** 产物路径落入的目标参数 id（须与目标命令 params 内字段逐字一致） */
        val fileParamId: String,
    )

    /**
     * 产物来源 key 登记处（寄宿于其他工具页、无独立 [ToolUsageSignal.PageId]
     * 枚举页的内嵌面板）。来源 key 按产物语义登记专属常量——勿复用宿主页的
     * [ToolUsageSignal.PageId] 常量（宿主页其他产物共用同 key 会让映射表
     * 从「按产物」漂移成「按页」歧义）。
     */
    object SourcePageId {
        /** logcat 实时抓取面板（寄宿 Shell 工具页；产物 = 落盘 .txt 纯文本日志） */
        const val LOGCAT_CAPTURE = "LogcatCapture"

        /**
         * 应用内查看器（:ui 共享消费面 Dialog；打开对象 = 可读文本日志）。
         * 查看器打开的文件无生产页身份（palette / 对比页 / 证据流等外来文件
         * 与各工具页产物共用同一读面），按「读面产物」语义登记专属常量。
         */
        const val LOG_VIEWER = "LogViewer"

        /**
         * 查看器拒收的加密件（:ui Failed 态；打开对象 = XLOG_SEGMENTS 判型拒绝的
         * `.xlog`/`.alog`）。与 [LOG_VIEWER] 同一读面但产物语义不同（加密件不可读，
         * 下一级是解密而非过滤），按产物语义登记专属常量。
         */
        const val LOG_VIEWER_ENCRYPTED = "LogViewerEncrypted"

        /**
         * 设备文件拉取的文本产物（设备观测页文件子页；产物 = 拉取到本地的
         * `.txt` 纯文本/无后缀日志）。与 [DEVICE_FILE_PULL_ENCRYPTED] 同一拉取面
         * 但产物语义不同（可读文本的下一级是过滤），按产物语义登记专属常量——
         * 同款先例见 [LOG_VIEWER] 与 [LOG_VIEWER_ENCRYPTED] 的分立。
         */
        const val DEVICE_FILE_PULL = "DeviceFilePull"

        /**
         * 设备文件拉取的加密件（`.xlog`/`.alog` 拉取产物）：加密件不可读，下一级
         * 是解密而非过滤（回管线主干前半段，解密后产物再进 Tag 过滤）。
         */
        const val DEVICE_FILE_PULL_ENCRYPTED = "DeviceFilePullEncrypted"
    }

    /** 产物来源页 → 下一级工具目标（单张表；null 值缺席 = 不出现条目） */
    private val PIPELINE: Map<String, NextToolTarget> = mapOf(
        ToolUsageSignal.PageId.XLOG_DECODER to NextToolTarget(
            label = "送往 Tag 过滤",
            commandId = "logkit.tag-filter",
            fileParamId = "file_path",
        ),
        SourcePageId.LOGCAT_CAPTURE to NextToolTarget(
            label = "送往 Tag 过滤",
            commandId = "logkit.tag-filter",
            fileParamId = "file_path",
        ),
        SourcePageId.LOG_VIEWER to NextToolTarget(
            label = "送往 Tag 过滤",
            commandId = "logkit.tag-filter",
            fileParamId = "file_path",
        ),
        SourcePageId.LOG_VIEWER_ENCRYPTED to NextToolTarget(
            label = "送 XLog 解密",
            commandId = "logkit.xlog-decrypt",
            fileParamId = "file_path",
        ),
        // 解压产物两分支的「单文件」分支消费面；多文件分支走 GoXLogDecryptHook
        ToolUsageSignal.PageId.ARCHIVE to NextToolTarget(
            label = "送 XLog 解密",
            commandId = "logkit.xlog-decrypt",
            fileParamId = "file_path",
        ),
        // 设备文件拉取的文本产物（.txt/无后缀）：拉到本地即过滤页输入类型
        SourcePageId.DEVICE_FILE_PULL to NextToolTarget(
            label = "送往 Tag 过滤",
            commandId = "logkit.tag-filter",
            fileParamId = "file_path",
        ),
        // 设备文件拉取的加密件（.xlog/.alog）：拉到本地即解密页输入类型
        SourcePageId.DEVICE_FILE_PULL_ENCRYPTED to NextToolTarget(
            label = "送 XLog 解密",
            commandId = "logkit.xlog-decrypt",
            fileParamId = "file_path",
        ),
    )

    /**
     * 产物来源页查下一级工具目标。
     * @param sourcePageId [ToolUsageSignal.PageId] 常量；未登记来源返回 null
     *        （调用方不渲染「送往」条目）
     */
    fun nextToolTargetOf(sourcePageId: String): NextToolTarget? = PIPELINE[sourcePageId]

    /**
     * 查看器「送时间截取」横向目标（表外常量——刻意不进 [PIPELINE]）。
     *
     * 语义判据：时间区间截取的切出物仍是**本级**产物的子集（同文件的时间切片，
     * 不是下一级管线段的输入形态），不构成「送往下一级工具」的管线衔接，故不占
     * 映射表行——[nextToolTargetOf] 查表恒不返回本目标，调用方（查看器底部动作）
     * 显式点名常量直投 [offer]（额外预填槽经 `extraArgs` 扩参承载）。
     *
     * id 字符串锚同 [PIPELINE] 条目：目标命令 id 改名时 palette 解析落空 → 静默
     * 回落列表态（不炸面板），但衔接失效——ParamCommandCatalogTest（:composeApp）
     * 断言 id 在 catalog 内且参数表含三槽防漂移。
     */
    val VIEWER_TIME_SLICE: NextToolTarget = NextToolTarget(
        label = "送时间截取",
        commandId = "tools.slice-time",
        fileParamId = "file_path",
    )

    /** 衔接载荷：目标命令 id + 预填参数（产物路径落在目标命令的文件参数上） */
    data class HandoffSeed(
        val commandId: String,
        val args: Map<String, String>,
    )

    @Volatile
    private var openPalette: (() -> Unit)? = null

    private val _pendingSeed = MutableStateFlow<HandoffSeed?>(null)

    /**
     * 待消费衔接载荷槽（null = 槽空）。StateFlow 形态承载双消费路径：
     * 打开瞬间路径读 [consumeSeed]（初值承载）；面板已开路径订阅本流
     * （非空即消费——查看器独立窗口在面板已开时 offer 的新载荷经流发射
     * 即时到达订阅面，不滞留槽内迟发旧参数表单）。
     */
    val pendingSeed: StateFlow<HandoffSeed?> = _pendingSeed.asStateFlow()

    /**
     * 宿主接线（palette 打开动作；null = 未接线）。生产侧条目渲染与响应的
     * 开关真源（[isWired]，深度分析按钮同款门控语义——未接线条目不出现）。
     */
    fun wire(openPalette: (() -> Unit)?) {
        this.openPalette = openPalette
    }

    /** 宿主是否已接线（生产侧条目渲染开关） */
    val isWired: Boolean
        get() = openPalette != null

    /**
     * 工具页侧投递：目标命令 + 产物路径（+ 可选额外预填槽）→ 预填载荷 + 打开
     * 命令面板。HandoffSeed.args = 单路径 map + [extraArgs]。
     *
     * @param extraArgs 额外预填槽（横向直投场景的伴生参数，如 start_time）；
     *        key 须与目标命令 params 内字段 id 逐字一致（落点漂移即静默不预填）；
     *        与产物路径槽 [NextToolTarget.fileParamId] 撞键时本参值胜出——调用方
     *        契约 = extraArgs 不含产物路径槽（产物路径恒经 productPath 传递）
     * @return false = 宿主未接线（投递被拒绝，载荷不残留）
     */
    fun offer(
        target: NextToolTarget,
        productPath: String,
        extraArgs: Map<String, String> = emptyMap(),
    ): Boolean {
        val open = openPalette ?: return false
        _pendingSeed.value = HandoffSeed(
            commandId = target.commandId,
            args = mapOf(target.fileParamId to productPath) + extraArgs,
        )
        open()
        return true
    }

    /**
     * palette 打开瞬间取衔接载荷（读即清——一次性载荷，仅本次打开消费）。
     * 目标命令 id 解析失败（改名/退役）由调用方静默回落列表态。
     *
     * 读清语义对双消费路径共用：订阅面在 collect 内收到载荷后同样经本方法
     * 清槽（防下次打开初值承载重读已消费旧载荷）；槽内真值以 [pendingSeed]
     * 流发射为准。
     */
    fun consumeSeed(): HandoffSeed? {
        val seed = _pendingSeed.value
        _pendingSeed.value = null
        return seed
    }

    /** 测试缝：清空接线与残留载荷（单测隔离用） */
    fun clearForTest() {
        openPalette = null
        _pendingSeed.value = null
    }
}
