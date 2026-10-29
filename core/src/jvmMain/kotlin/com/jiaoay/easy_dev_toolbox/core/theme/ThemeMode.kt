package com.jiaoay.easy_dev_toolbox.core.theme

/**
 * 主题外观模式：跟随系统 / 手动浅色 / 手动深色。
 */
enum class ThemeMode(val displayName: String) {
    System("跟随系统"),
    Light("浅色"),
    Dark("深色");
}
