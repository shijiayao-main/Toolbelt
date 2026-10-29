package com.jiaoay.easy_dev_toolbox.widget

import androidx.compose.foundation.VerticalScrollbar
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.rememberScrollbarAdapter
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import com.jiaoay.easy_dev_toolbox.core.theme.AppAlpha
import com.jiaoay.easy_dev_toolbox.core.theme.Dimens

/**
 * 带桌面滚动条的列表容器：`Row { LazyColumn(weight 1f) + 右缘细滚动条 }`
 * 的收敛壳——Compose Desktop 的 LazyColumn 默认不渲染滚动条，对以「翻日志」为主业的
 * 桌面工具，位置/总量反馈是日用级导航件（调查报告 ui-inventory 项）。
 *
 * 滚动条宽度走 [Dimens.scrollBarWidth] token、颜色默认主题轨（unstyled：hover 前
 * outline 级低存在感、hover 渐显），非动画件、不触碰流式零动画铁律；贴底跟随
 * （BottomFollowScrollEffect）语义不受影响（scrollToItem 行为不变）。
 *
 * 用法：把原来的 `LazyColumn(state = listState, ...) { ... }` 换成
 * `ScrollableListRow(listState) { LazyColumn(state = listState, ...) { ... } }`，
 * 或对已有多层嵌套的布局用 [VerticalScrollbarSlot] 自行挂右缘。
 */
@Composable
fun ScrollableListRow(
    listState: LazyListState,
    modifier: Modifier = Modifier,
    showScrollbar: Boolean = true,
    content: @Composable () -> Unit,
) {
    Row(modifier = modifier.fillMaxWidth()) {
        Box(modifier = Modifier.weight(1f)) {
            content()
        }
        if (showScrollbar) {
            VerticalScrollbarSlot(listState = listState)
        }
    }
}

/** 右缘细滚动条（独立挂点：布局不便整体换壳时的最小接入件） */
@Composable
fun VerticalScrollbarSlot(
    listState: LazyListState,
    modifier: Modifier = Modifier,
) {
    VerticalScrollbar(
        adapter = rememberScrollbarAdapter(listState),
        modifier = modifier
            .fillMaxHeight()
            .width(Dimens.scrollBarWidth),
        style = androidx.compose.foundation.ScrollbarStyle(
            minimalHeight = Dimens.scrollBarWidth * 4,
            thickness = Dimens.scrollBarWidth,
            shape = androidx.compose.foundation.shape.CircleShape,
            hoverDurationMillis = 300,
            // 常态低存在感（contentFaint 透明档），hover 渐显至实色 outline
            unhoverColor = MaterialTheme.colorScheme.outline.copy(alpha = AppAlpha.contentFaint),
            hoverColor = MaterialTheme.colorScheme.outline,
        ),
    )
}
