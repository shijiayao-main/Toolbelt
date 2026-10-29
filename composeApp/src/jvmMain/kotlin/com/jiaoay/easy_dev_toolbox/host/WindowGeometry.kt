package com.jiaoay.easy_dev_toolbox.host

import java.awt.GraphicsEnvironment

/**
 * 窗口几何记忆的持久化值（主窗与投屏独立窗共用载体）：AWT 物理像素（桌面密度 1.0，
 * Dp 与像素等值；跨不同缩放显示器移动属边缘场景，几何记忆为尽力而为语义）。
 * x/y 允许负值——多显示器副屏在主屏左侧/上方时坐标系为负，合法几何不得被符号判断误杀。
 */
internal data class SavedWindowGeometry(val x: Int, val y: Int, val width: Int, val height: Int)

/**
 * 几何四元组合法裁决（主窗与投屏独立窗读链单源）：null = 应回退默认/计算位。
 * 未记录哨兵 = width/height ≤ 0（默认 -1）；屏外防呆 = 左上角点不在任一可见屏幕内
 * （多显示器拔除后记忆位可能落全部屏幕外——回退优于恢复到屏外）。
 * [pointOnScreen] 为测试缝（几何校验单测注入；生产缺省 = [isPointOnAnyScreen]）。
 */
internal fun validatedWindowGeometryOrNull(
    x: Int,
    y: Int,
    width: Int,
    height: Int,
    pointOnScreen: (x: Int, y: Int) -> Boolean = ::isPointOnAnyScreen,
): SavedWindowGeometry? {
    if (width <= 0 || height <= 0) return null
    if (!pointOnScreen(x, y)) return null
    return SavedWindowGeometry(x = x, y = y, width = width, height = height)
}

/**
 * 判定点是否落在任一可见屏幕的可用区域内（防多显示器拔除后窗口恢复到屏外）。
 * headless JVM（测试环境）下 GraphicsEnvironment 构造抛 HeadlessException——
 * fail-closed 回 false（几何视为非法回退计算位）；桌面生产恒有屏，不受此影响。
 */
internal fun isPointOnAnyScreen(x: Int, y: Int): Boolean = runCatching {
    GraphicsEnvironment.getLocalGraphicsEnvironment()
        .screenDevices
        .any { device -> device.defaultConfiguration.bounds.contains(x, y) }
}.getOrDefault(false)
