package com.jiaoay.easy_dev_toolbox.widget

import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect

/**
 * 控制台贴底跟随内核（ShellOutputPanel 与 BridgeLogConsole 同构逻辑的收敛，约 40 行 ×2）：
 * 内容追加时，若用户此前处于列表底部则即时滚到新底部（scrollToItem 零动画铁律）。
 *
 * 贴底判定（两路）：
 * 1. canScrollForward == false：旧布局快照上用户确实贴底（不能再向前滚），批量追加也跟随；
 * 2. 像素容差判定：最后一可见项底边接近视口底边（容差吸收亚像素/微偏移）。
 * 用户上翻查看历史或手动滚动中不跟随，避免劫持视点。
 *
 * [itemCount] 为本列表渲染条数（过滤后列表 size：过滤词变更同样仅在贴底时跟随）；
 * 导出/上限裁剪等差异逻辑由调用方自理。
 *
 * 🔴 性能热路径（贴底跟随三处同型）：滚动窗口**封顶后** itemCount
 *    恒等于容量上限 ⇒ `LaunchedEffect(itemCount)` 永不重启 ⇒ 新行不再自动贴底
 *    （功能坏了，非掉帧）。可选 [contentVersion] 传「最新一条的 seq/序号」（单调递增，
 *    每次追加必变）做 effect key——封顶后也能持续触发；不传则回退 itemCount（兼容旧调用方）。
 */
@Composable
fun BottomFollowScrollEffect(
    listState: LazyListState,
    itemCount: Int,
    enabled: Boolean = true,
    contentVersion: Long? = null,
) {
    // 封顶后 itemCount 不再变化，contentVersion（seq）才是真实的「有新内容」信号；
    // 两者都进 key：未传 version 的调用方仍按 itemCount 触发。
    LaunchedEffect(itemCount, contentVersion) {
        val last = itemCount - 1
        if (enabled && last >= 0 && !listState.isScrollInProgress) {
            val info = listState.layoutInfo
            val lastVisible = info.visibleItemsInfo.lastOrNull()
            val atBottom = !listState.canScrollForward ||
                (
                    lastVisible != null &&
                        lastVisible.offset + lastVisible.size >= info.viewportEndOffset - BOTTOM_FOLLOW_TOLERANCE_PX
                    )
            if (atBottom) listState.scrollToItem(last)
        }
    }
}

/** 贴底像素容差（吸收亚像素/微偏移） */
private const val BOTTOM_FOLLOW_TOLERANCE_PX = 8
