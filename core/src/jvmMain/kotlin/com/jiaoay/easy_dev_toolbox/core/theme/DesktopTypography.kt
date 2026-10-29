package com.jiaoay.easy_dev_toolbox.core.theme

import androidx.compose.material3.Typography
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp

/**
 * 桌面排版 token（Win11 Type ramp 阶梯 + JetBrains Mono 等宽档）：M3 缺省 Typography 是
 * 移动优先（bodyLarge 16sp 起），桌面生产力工具密度偏大——借 M3 主题机制整体替换为桌面
 * 密度体系（caption 12 / body 13-14 / subtitle 20 / title 28），AppTheme 统一装配、
 * 消费点零迁移。M3 slot → 桌面字号阶梯映射表见 docs/core/theme.md「排版 token」节。
 *
 * [mono] 供日志/时间戳/路径/十六进制等数据密集场景（系统等宽，JBR 下即 JetBrains 家族线）；
 * 新代码等宽场景一律 [mono] 具名档，禁写 `fontFamily = FontFamily.Monospace` 字面量
 * （docs/ui-style-guide.md 4.7 等宽收口条款）。
 */
object DesktopTypography {

    /** Win11 阶梯（epx → sp 桌面近似：12/13/14/16/20/28，行高 = 字号 + 8 或 4） */
    val typography: Typography = Typography(
        // 展示层：大页标题（displayLarge——当前生产代码零消费，保留档位应对未来全屏页头）
        displayLarge = TextStyle(fontSize = 36.sp, lineHeight = 44.sp, fontWeight = FontWeight.Normal),
        displayMedium = TextStyle(fontSize = 32.sp, lineHeight = 40.sp, fontWeight = FontWeight.Normal),
        displaySmall = TextStyle(fontSize = 28.sp, lineHeight = 36.sp, fontWeight = FontWeight.Normal),
        // 标题层
        headlineLarge = TextStyle(fontSize = 24.sp, lineHeight = 32.sp, fontWeight = FontWeight.SemiBold),
        headlineMedium = TextStyle(fontSize = 20.sp, lineHeight = 28.sp, fontWeight = FontWeight.SemiBold),
        headlineSmall = TextStyle(fontSize = 18.sp, lineHeight = 26.sp, fontWeight = FontWeight.SemiBold),
        titleLarge = TextStyle(fontSize = 20.sp, lineHeight = 28.sp, fontWeight = FontWeight.SemiBold),
        titleMedium = TextStyle(fontSize = 16.sp, lineHeight = 22.sp, fontWeight = FontWeight.Medium),
        titleSmall = TextStyle(fontSize = 14.sp, lineHeight = 20.sp, fontWeight = FontWeight.SemiBold),
        // 正文层
        bodyLarge = TextStyle(fontSize = 14.sp, lineHeight = 20.sp, fontWeight = FontWeight.Normal),
        bodyMedium = TextStyle(fontSize = 13.sp, lineHeight = 18.sp, fontWeight = FontWeight.Normal),
        bodySmall = TextStyle(fontSize = 12.sp, lineHeight = 16.sp, fontWeight = FontWeight.Normal),
        // 标签层（按钮/徽标/导航）
        labelLarge = TextStyle(fontSize = 14.sp, lineHeight = 20.sp, fontWeight = FontWeight.Medium),
        labelMedium = TextStyle(fontSize = 12.sp, lineHeight = 16.sp, fontWeight = FontWeight.Medium),
        labelSmall = TextStyle(fontSize = 11.sp, lineHeight = 16.sp, fontWeight = FontWeight.Medium),
    )

    /** 等宽档（日志/时间戳/路径/十六进制）：系统等宽（JBR 下即 JetBrains 家族线） */
    val mono: TextStyle = TextStyle(
        fontFamily = FontFamily.Monospace,
        fontSize = 12.sp,
        lineHeight = 16.sp,
        fontWeight = FontWeight.Normal,
    )

    // ── mono 具名副档（各语域等宽字形的单一定义点——行内一律引用本组，禁散写 copy）──

    /** 等宽标签档（monoTag）：日志 TAG/命令名/会话 id 等行内标签的单一定义点
     *  （= `mono.copy(fontSize = 11.sp, fontWeight = FontWeight.Medium)`；行内标签一律引用本档，禁散写 copy）。 */
    val monoTag: TextStyle = mono.copy(fontSize = 11.sp, fontWeight = FontWeight.Medium)

    /** 等宽输入档（monoInput）：命令输入框/路径输入等编辑态等宽文本的单一定义点
     *  （= `mono.copy(fontSize = …)`；编辑态等宽一律引用本档，禁散写 copy）；字号 14sp。 */
    val monoInput: TextStyle = mono.copy(fontSize = 14.sp, lineHeight = 19.sp)

    /** 控制台行档（monoConsole）：Shell 输出台等恒深底控制台输出行的单一定义点
     *  （行高 = 12px × 1.7 ≈ 20.4 的控制台行高惯例；输出条目行一律引用本档，
     *  禁止散写 `mono.copy(lineHeight = 20.4.sp)`）。 */
    val monoConsole: TextStyle = mono.copy(lineHeight = 20.4.sp)

    /** tab 行标签档（tabLabel）：文档 tab 文字 12sp SemiBold（bodySmall/labelMedium
     *  同 12sp 档）+ 选中态 font-weight 600 恒定（「选中/未选中两态字形一致」的验收口径）；
     *  行高 16（= 字号 + 4，bodySmall 同族）不破 tabBarHeight 34dp 胶囊行内布局。
     *  仅供 WorkspaceTabBar tab 行消费，勿挪作他用。 */
    val tabLabel: TextStyle = TextStyle(
        fontSize = 12.sp,
        lineHeight = 16.sp,
        fontWeight = FontWeight.SemiBold,
    )
}
