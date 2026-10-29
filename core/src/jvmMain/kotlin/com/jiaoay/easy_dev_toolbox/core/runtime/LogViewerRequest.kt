package com.jiaoay.easy_dev_toolbox.core.runtime

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * 应用内查看器打开请求槽（生产侧 = palette「查看日志文件」命令、日志对比差异行
 * 跳转、工作台证据流截断动作与报告依据超窗点击；消费侧 = 宿主根叠层 collect
 * [pending] 挂载查看 Dialog，关闭经 [consume] 清槽）。
 *
 * ## 为什么槽在 :core
 * 生产面横跨 composeApp 命令层与 feature-logworkbench，feature 互不依赖铁律下
 * 两侧唯一共同可见层是 :core（[ReportDelivery] / [TaskPipelineHandoff] 同款
 * 中立槽配方）。全应用单槽单真源：宿主 Dialog 挂载面与全部生产面共用本槽，
 * 勿再造第二套请求真源。
 *
 * ## 降级语义（宿主未挂载明示，不静默 no-op）
 * [offer] 只写槽、不自检消费方——宿主未 collect 时请求静默蒸发（编译期不可见、
 * UI 层不可测）。生产面动作前查 [hostMounted]：false 时不得静默 no-op，须降级
 * 到可见动作并明示原因。宿主组合经 [markHostMounted] 置位（挂载面存在的信号）。
 *
 * ## 线程契约
 * 纯内存 StateFlow 槽，任意协程/线程可读写；不持 scope、不自建协程。
 */
object LogViewerRequest {

    /**
     * 查看器打开请求：目标文件路径 + 可选初始定位。定位档两选一，**并存时
     * [initialLine] 优先**（行号是零歧义定位；时间戳是语义定位降级档——行号
     * 缺位时才生效）。裁决单源在宿主消费面（LogViewerDialog 的初始定位
     * 决策），[offer] 只透传不裁决：
     * - [initialLine]：初始定位行（1 基——查看器窗口分页到该行为窗口首行；
     *   null = 不定位）。差异行跳转是带行号请求的现网消费方；
     * - [initialTimestamp]：初始定位时间戳——查看器打开就绪后经引擎时间戳
     *   seek 定位到首个行首时间戳 ≥ 目标的行（落点恒窗口首行）。格式口径同
     *   log-tools `parseLogTimestampTarget`（年份格式与裸 `MM-dd` 均容忍）。
     *   工作台报告依据超窗点击是带时间戳请求的现网消费方。
     * palette 单文件打开与工作台截断动作不带定位。
     */
    data class Request(
        val path: String,
        val initialLine: Int? = null,
        val initialTimestamp: String? = null,
        /**
         * run 级书签持久化锚（null = 会话级书签现状，零持久化行为）。
         * 非空时查看器书签随标记/清空动作按 (runId, 文件路径) 快照持久化，开文件时
         * 按 (行号, 行文指纹) 双锚恢复（指纹不一致拒绝复活——run 级书签仓储在
         * :core RunBookmarkStore）。工作台 run 产物打开是现网消费方。
         */
        val runId: String? = null,
    )

    private val _pending = MutableStateFlow<Request?>(null)

    /** 待打开请求（null = 无查看器在台；宿主 collect 后挂载 Dialog） */
    val pending: StateFlow<Request?> = _pending.asStateFlow()

    /** 生产侧写入（覆盖语义：在台请求被新请求替换，Dialog 按 filePath 键重组） */
    fun offer(path: String, initialLine: Int? = null, initialTimestamp: String? = null, runId: String? = null) {
        _pending.value = Request(path, initialLine, initialTimestamp, runId)
    }

    /** 宿主关闭侧清槽（Dialog onDismiss——关闭后可再次打开） */
    fun consume() {
        _pending.value = null
    }

    private val _hostMounted = MutableStateFlow(false)

    /** 宿主挂载态（false = 宿主未 collect 本槽——生产面据此降级，不静默 no-op） */
    val hostMounted: StateFlow<Boolean> = _hostMounted.asStateFlow()

    /** 宿主组合置位（宿主根叠层挂载查看 Dialog 的存在信号；幂等，无摘除面） */
    fun markHostMounted() {
        _hostMounted.value = true
    }

    /** 测试缝：清槽并复位挂载态（单测隔离用） */
    fun clearForTest() {
        _pending.value = null
        _hostMounted.value = false
    }
}
