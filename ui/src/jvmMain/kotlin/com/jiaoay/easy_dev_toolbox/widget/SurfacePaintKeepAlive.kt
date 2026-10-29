package com.jiaoay.easy_dev_toolbox.widget

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.jiaoay.easy_dev_toolbox.core.theme.Dimens
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive

/**
 * 窗面空帧自愈看门狗（X11/Wayland 透底防线）：Compose 窗只在有失效时才重绘，
 * 窗面被窗口管理事件清空后若无任何失效则不再上屏——窗变成逐像素半透明、桌面
 * 直接透过（黑桌面下内容可见/白桌面下整面白）。本件以固定周期制造一次真实
 * 测量/布局/绘制失效，使空窗面在 ≤[KEEP_ALIVE_INTERVAL_MS] 内自愈。窗面正常时
 * 该周期帧只触发一次全窗重绘，无累积成本。机制与修复规格自足于本 KDoc（周期帧只触发一次全窗重绘，无累积成本）。
 * 下 screencast-window-transparency-root-cause.md（仓内唯一文件名）。
 *
 * 载体契约：失效盒封装在 0.dp 容器内——被裁剪到零视觉零布局足迹，不移动兄弟
 * 节点（尺寸交替若直接进布局会每周期顶动整个窗面 2dp）。
 *
 * 挂载约束：每窗一个、挂在窗内容根层；窗不组合（销毁/未开）则无协程零成本。
 */
@Composable
fun SurfacePaintKeepAlive() {
    val tick = remember { mutableIntStateOf(0) }
    LaunchedEffect(Unit) {
        while (isActive) {
            delay(KEEP_ALIVE_INTERVAL_MS)
            tick.intValue++
        }
    }
    Box(Modifier.size(0.dp)) {
        Box(
            Modifier.size(if (tick.intValue % 2 == 0) Dimens.space2 else Dimens.space4),
        )
    }
}

/** 自愈周期（毫秒）：空窗面最长恢复延迟；正常态下即一次全窗重绘的节流间隔 */
private const val KEEP_ALIVE_INTERVAL_MS = 2_000L
