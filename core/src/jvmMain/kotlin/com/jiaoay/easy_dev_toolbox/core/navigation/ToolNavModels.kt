package com.jiaoay.easy_dev_toolbox.core.navigation

import com.jiaoay.easy_dev_toolbox.page.ToolPage

/**
 * 导航工具项：页面、图标、名称与描述（描述供命令面板搜索候选）。
 *
 * 契约形态：[icon] 为 **Material Symbol 蛇形名**（如 "apps"）——
 * 图标渲染统一收口 `:ui` 的 `AppSymbolIcon`（字体形态，无 ImageVector 实体可传）。
 * :core 不依赖 dev.vicart 库（纯字符串契约，渲染归 :ui/宿主侧）。
 */
data class ToolNavItem(
    val page: ToolPage,
    val icon: String,
    val label: String,
    val desc: String,
)

/** 导航分组：分组名 + 组内工具项（组间加分隔） */
data class ToolNavSection(
    val title: String,
    val items: List<ToolNavItem>,
)
