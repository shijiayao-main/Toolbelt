package com.jiaoay.easy_dev_toolbox.page.mcp

import com.jiaoay.easy_dev_toolbox.core.navigation.ToolNavItem
import com.jiaoay.easy_dev_toolbox.core.navigation.ToolPageGroup
import com.jiaoay.easy_dev_toolbox.core.navigation.ToolPageRegistration
import com.jiaoay.easy_dev_toolbox.page.ToolPage
import dev.vicart.compose.material.symbols.MaterialSymbols

/**
 * MCP 外放域的导航注册出口。
 *
 * 入口组第二成员（组内注册序 [飞书桥接, MCP 服务]——featureRegistrations 拼接点
 * 在 LarkBridge 后）。归组判据见 [ToolPageGroup.ENTRY]：把本地工具/agent 能力
 * 递给外部消费（agent 协议外放）。
 *
 * 页面实现 = McpExposePage（任务 4.1 已落地——本文件是纯注册出口，非占位骨架）。
 */
object McpContributions {

    private val navItem = ToolNavItem(
        page = ToolPage.McpExpose,
        // SHARE → HUB——与 dock 系统区同字形（图标=页面指纹）
        icon = MaterialSymbols.HUB,
        label = "MCP 服务",
        desc = "本地工具与 agent 能力经 MCP 协议外放（127.0.0.1，Claude Code / IDE 等可连）",
    )

    val registrations: List<ToolPageRegistration> = listOf(
        ToolPageRegistration(
            page = ToolPage.McpExpose,
            navItem = navItem,
            group = ToolPageGroup.ENTRY,
        ) {
            McpExposePage()
        },
    )
}
