package com.jiaoay.easy_dev_toolbox.page.screencast

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * 布局子页 → 投屏帧的选中节点高亮共享载荷：坐标 = 设备屏幕像素（布局树节点的
 * globalBounds——写入侧拆解传入，值类型自包含不引 core 设备模型进本状态面，防
 * feature 模块反向感知布局域语义）。[serial] = 快照来源串号（null = 来源不明，
 * 帧侧不画）；[screenWidth]/[screenHeight] = 投屏会话屏幕尺寸真源（Streaming 状态
 * 自带）。
 */
data class ScreencastLayoutHighlightFrame(
    val serial: String?,
    val left: Int,
    val top: Int,
    val right: Int,
    val bottom: Int,
    val screenWidth: Int,
    val screenHeight: Int,
)

/**
 * 布局子页选中节点高亮的跨模块共享状态件（单例 StateFlow；先例 =
 * [ScreencastDetachedWindowController] 单例）：null = 无高亮。
 *
 * 契约：写入侧（宿主 DeviceObsPage 布局子页组合）经 Streaming + 串号一致门控后
 * [set]，帧侧（[ScreencastFrameView] 高亮叠层）按会话串号独立守卫才绘制——双
 * 保险防跨会话残留（写入侧离组合即 [clear]，切子页/切页高亮不残留在帧上）。
 */
object ScreencastLayoutHighlight {

    private val _state = MutableStateFlow<ScreencastLayoutHighlightFrame?>(null)

    /** 当前高亮载荷（null = 无高亮） */
    val state: StateFlow<ScreencastLayoutHighlightFrame?> = _state.asStateFlow()

    /** 写入高亮（调用方自证门控：Streaming + 串号一致 + dumpSerial 非 null） */
    fun set(frame: ScreencastLayoutHighlightFrame) {
        _state.value = frame
    }

    /** 清除高亮（幂等；写入侧组合退出/失配路径统一收口） */
    fun clear() {
        _state.value = null
    }
}
