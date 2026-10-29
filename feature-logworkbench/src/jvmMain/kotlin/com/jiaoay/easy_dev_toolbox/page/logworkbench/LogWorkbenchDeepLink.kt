package com.jiaoay.easy_dev_toolbox.page.logworkbench

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * 工作台深链槽。
 *
 * **object 门面单例 + pending 槽**：工具页产物卡「深度分析」按钮（宿主接线——
 * feature 互不依赖铁律下 feature-logkit 不 import 本 object）→
 * `offer(logFiles, prefillNote)` 暂存 → LogWorkbenchPage `LaunchedEffect` 消费 →
 * [consume] 取走即清空（一次性语义——重复进入页面不重复预填）。
 *
 * 不携带 UI 状态跨页（只传纯数据：文件路径 + 预填症状提示）。
 */
object LogWorkbenchDeepLink {

    /** 深链预填载荷（纯数据——文件路径列表 + 可选预填症状 + 可选空间归属 + 可选回看 run） */
    data class PendingInput(
        val logFiles: List<String>,
        val prefillNote: String? = null,
        /**
         * 预填问题时间点（null = 不触碰时间域——消费方既有值原样保留）。
         * 非 null 时消费方经 [WorkbenchViewModel.prefill] 的归一面写入目标时间框
         * （形态 = `yyyy-MM-dd hh:mm:ss.mmm` 或归一面可解析的宽松形态）。
         */
        val targetTime: String? = null,
        /** 调查空间归属（调查工作台 S1——null = 不写空间成员；非 null 时深链消费方
         *  应经 SpaceStore.addFiles 把文件写入该空间的成员集） */
        val spaceId: String? = null,
        /** 回看目标 run（null = 保持「预填不回看」语义；
         *  非 null 时消费方调 WorkbenchViewModel.openRun(runId)：restore 时间线 +
         *  报告回填。报告卡「去工作台」深链用；logFiles 可同时携带（openRun 内部
         *  会以 run 记录的 logFiles 覆盖表单，此处 logFiles 仅供 openRun 落空时的
         *  表单预填兜底） */
        val runId: String? = null,
    )

    private val _pending = MutableStateFlow<PendingInput?>(null)

    /** 待消费的深链输入（null = 无待处理深链） */
    val pending: StateFlow<PendingInput?> = _pending.asStateFlow()

    /** 工具页侧投递（产物卡「深度分析」——宿主接线调用） */
    fun offer(
        logFiles: List<String>,
        prefillNote: String? = null,
        targetTime: String? = null,
        spaceId: String? = null,
    ) {
        _pending.value = PendingInput(
            logFiles = logFiles,
            prefillNote = prefillNote,
            targetTime = targetTime,
            spaceId = spaceId,
        )
    }

    /** 报告卡深链投递（宿主 PageReport 装配点调用：runId 回看 + 切页） */
    fun offerRunReview(runId: String, logFiles: List<String> = emptyList()) {
        _pending.value = PendingInput(logFiles = logFiles, runId = runId)
    }

    /** 页面侧消费（取走即清空——一次性语义） */
    fun consume(): PendingInput? {
        val value = _pending.value
        _pending.value = null
        return value
    }

    /** 测试缝：清空槽（单测隔离用） */
    fun clearForTest() {
        _pending.value = null
    }
}
