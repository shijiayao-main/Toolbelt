package com.jiaoay.easy_dev_toolbox.page.timestamp

import dev.vicart.compose.material.symbols.MaterialSymbols

import com.jiaoay.easy_dev_toolbox.core.extensions.ConversionResult
import com.jiaoay.easy_dev_toolbox.core.extensions.Timestamps
import com.jiaoay.easy_dev_toolbox.core.navigation.ParamCommandResult
import com.jiaoay.easy_dev_toolbox.core.navigation.ParamCommandSpec
import com.jiaoay.easy_dev_toolbox.core.navigation.ParamSpec
import com.jiaoay.easy_dev_toolbox.core.navigation.ParamType
import com.jiaoay.easy_dev_toolbox.core.navigation.ToolPageRegistration
import com.jiaoay.easy_dev_toolbox.core.navigation.ToolPageGroup
import com.jiaoay.easy_dev_toolbox.page.ToolPage

/**
 * 时间戳页的导航注册出口：宿主 ToolPageRegistry 单点拼接（模块化注册契约）。
 *
 * 导航入口收敛：navItem=null（tab 目录/dock 不再展示），content 保留注册
 * （palette 深入模式 openPage 可达）；页面能力以带参命令形态存活——palette 直填
 * 即时出结果（Timestamps.convert 纯函数零成本，与页面同一实现）。
 *
 * 页面是即时反馈型（输入即出结果），命令形态体验降级是已知取舍
 *    （plan 阶段记录）；HUD 输出 = 转换结果双向文案。
 */
object TimestampContributions {

    /** 带参命令：时间戳双向转换（直调 :core Timestamps.convert——页面同一实现） */
    private val convertCommand = ParamCommandSpec(
        id = "timestamp.convert",
        label = "转换时间戳",
        desc = "时间戳 ↔ 日期时间双向转换（10 位秒 / 13 位毫秒 ↔ 日期时间字符串）",
        icon = MaterialSymbols.SCHEDULE,
        keywords = listOf("时间戳", "timestamp", "转换", "convert", "毫秒", "日期"),
        params = listOf(
            ParamSpec(
                id = "value",
                label = "时间戳或日期时间",
                type = ParamType.TEXT,
                required = false,
                placeholder = "如 1726032000000 或 2026-09-11 12:00:00",
            ),
        ),
        openPage = ToolPage.Timestamp,
        performArgs = { args ->
            val input = args["value"].orEmpty().trim()
            when (val result = Timestamps.convert(input, "yyyy-MM-dd HH:mm:ss.SSS")) {
                is ConversionResult.Empty -> ParamCommandResult(success = false, summary = "输入为空")
                is ConversionResult.FromTimestamp -> ParamCommandResult(
                    summary = "${result.millis} → ${result.formatted}",
                )
                is ConversionResult.FromDate -> ParamCommandResult(
                    summary = "$input → 毫秒 ${result.millis} / 秒 ${result.seconds}",
                )
                is ConversionResult.Invalid -> ParamCommandResult(success = false, summary = result.message)
            }
        },
    )

    val registrations: List<ToolPageRegistration> = listOf(
        // 导航入口收敛：tab 目录不再展示；content 保留（深入模式可达）
        ToolPageRegistration(
            page = ToolPage.Timestamp,
            navItem = null,
            group = ToolPageGroup.TOOLS,
            paramCommands = listOf(convertCommand),
        ) {
            TimestampPage()
        },
    )
}
