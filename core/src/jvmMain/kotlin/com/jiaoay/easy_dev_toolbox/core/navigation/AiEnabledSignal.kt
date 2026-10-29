package com.jiaoay.easy_dev_toolbox.core.navigation

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * AI 开关中立镜像槽。
 *
 * **问题**：开关真源 [AiToolsToggle] 在 feature-ai，feature 互不依赖铁律下
 * feature-logworkbench / feature-voice 不可 import 它——勿以「init 一次性快照读」
 * 替代（VM 窗口级存活，快照永不刷新，开关翻转后页面仍按陈旧值降级）。
 */
object AiEnabledSignal {

    private val _enabled = MutableStateFlow(false)

    /** AI 开关镜像流（真源：feature-ai AiToolsToggle——宿主写点同文件顺手镜像） */
    val enabled: StateFlow<Boolean> = _enabled.asStateFlow()

    /** 真源写点镜像入口（AiToolsToggle 内部调用——生产代码不得绕过真源直写） */
    fun mirror(value: Boolean) {
        _enabled.value = value
    }

    /** 测试缝：直写镜像（单测隔离用——不触真源） */
    fun setForTest(value: Boolean) {
        _enabled.value = value
    }
}
