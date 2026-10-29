package com.jiaoay.easy_dev_toolbox.core.llm.workbench

/**
 * 深链挂钩（宿主接线）。
 *
 * **中立回调槽（:core 单点）**：工具页产物卡「深度分析」情境动作的回调槽——
 * feature-logkit / feature-logworkbench 互不依赖（feature 互不 import 铁律），
 * 两个 feature 都只 import 本槽：
 * - **生产侧**（feature-logkit XLog 页）：产物卡成功态暴露「深度分析」按钮，
 *   点击调 [hook]（null = 宿主未接线，按钮不渲染/不响应）；
 * - **消费侧**（宿主 composeApp）：AppViewModel init wire——
 *   `hook = { files, note, targetTime -> DeepLink.offer(files, note, targetTime); setCurrentPage(LogWorkbench) }`。
 *
 * 只传纯数据（产物文件路径 + 可选预填症状 + 可选预填问题时间点）——不携带 UI 状态跨页。
 */
object DeepAnalyzeHook {

    /**
     * 深链投递语义：产物文件路径列表 + 可选预填症状 + 可选预填问题时间点
     * （null = 无预填，工作台不触碰时间域）。
     *
     * 第三参的 null 缺省在 [offer] 上承载（fun 接口的抽象方法不允许默认参数值）——
     * 既有生产侧 offer 调用点（feature-logkit 各页）零改动吸收；新增载荷的生产侧
     * （logcat 标记锚点等）显式传入。
     */
    fun interface Callback {
        operator fun invoke(outputFiles: List<String>, prefillNote: String?, prefillTargetTime: String?)
    }

    @Volatile
    private var callback: Callback? = null

    /** 宿主接线（AppViewModel init——生产侧按钮渲染与响应的开关真源） */
    fun wire(callback: Callback?) {
        this.callback = callback
    }

    /**
     * 宿主是否已接线（生产侧按钮渲染开关——未接线时按钮不渲染；
     * [TaskPipelineHandoff.isWired] 同款查询口形态）。
     */
    val isWired: Boolean
        get() = callback != null

    /** 工具页侧调用（null 槽 = 宿主未接线——调用点静默返回 false 表示不可用） */
    fun offer(outputFiles: List<String>, prefillNote: String? = null, prefillTargetTime: String? = null): Boolean {
        val cb = callback ?: return false
        cb(outputFiles, prefillNote, prefillTargetTime)
        return true
    }

    /** 测试缝：清空接线（单测隔离用） */
    fun clearForTest() {
        callback = null
    }
}
