package com.jiaoay.easy_dev_toolbox.core.theme

import androidx.compose.runtime.Immutable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color

/**
 * 语义扩展色 token：主题系统未覆盖的成功/警告/控制台等场景色，
 * 由 AppTheme 按深浅模式提供，页面禁止再硬编码 Color(0xFF...)。
 */
@Immutable
data class EasyToolboxColors(
    /** 成功/在线状态 */
    val success: Color,
    /** 成功色的浅底（深色模式下用 container 提高可读性） */
    val successContainer: Color,
    /** 警告/多设备提醒 */
    val warning: Color,
    /** 警告色的浅底（状态容器条的 Warning 槽；勿手写 warning.copy(AppAlpha.bgSubtle) 合成底，统一走本 token） */
    val warningContainer: Color,
    /** 提示信息色（Info 槽对——StatusBanner Info 槽的正源；design-language.md §2.4：亮 = ia-v2 #0B57D0） */
    val info: Color,
    /** 提示信息色的浅底（亮 = ia-v2 --info-c #D3E3FD） */
    val infoContainer: Color,
    /**
     * 错误容器固定槽（亮 = M3 亮 errorContainer 本值、暗 = 裁决定值新实色——
     * 固定值语义，非动态派生）。scheme.errorContainer 动态派生（暗 ≈#8C1D18 系
     * 偏亮偏红）不承载容器语义——StatusBanner/PathConfirmCard 等容器语义消费点
     * 经本槽直取。具体色值见构造点注释。
     */
    val errorContainerFixed: Color,
    /** 错误主色固定槽（稿 --error 暗 #EF9A9E；亮侧 M3 error 本值已对上，仍入槽对单源） */
    val errorFixed: Color,
    /**
     * 日志/终端控制台背景。亮侧恒 [LightEasyToolboxColors] 本值；暗侧本字段是静态基值
     * #141416（豁免/无染色套的回落值）——非豁免染色套在 AppTheme 装配点经
     * [stainedDarkConsoleBg] 单槽覆写为骨架色温派生，控制台不再与近黑骨架融合。
     */
    val consoleBg: Color,
    /** 控制台默认文本 */
    val consoleText: Color,
    /** 控制台语义成功色（亮暗同值——consoleBg 恒深底上的完成勾/完成文案；主题派生 success 在深底 <3:1 禁用） */
    val consoleSuccess: Color,
    /** 控制台语义信息色（亮暗同值——consoleBg 恒深底上的运行态进度圈；主题派生 info 在深底 <3:1 禁用） */
    val consoleInfo: Color,
    /** 日志级别着色 */
    val logDebug: Color,
    val logInfo: Color,
    val logWarn: Color,
    val logError: Color,
    /**
     * 结构件身份绿（稳态指示/图标点缀/装饰绿瓣）：与 [success] 分工——success 管状态语义，
     * platingGreen 管结构件漆面。消费位约束：对浅色页面底不过图形线，浅色形态只准
     * 落深井（consoleBg 系）/芯片容器内；对深底 AA 由 ThemeDerivedContrastGuardTest
     * console 面与暗底图形档断言背书。套级覆写位见 [ThemeSlotOverrides.platingGreen]。
     */
    val platingGreen: Color,
    val aiAccent: Color,
    /** AI 语义色的浅底（块容器/角标底色；对 9 预设 surface 系可见——浅 ΔE=6.9~18.2、深 ΔE=72~81） */
    val aiAccentContainer: Color,
    val floatShadow: Color,
    /** 图表分类色板（环形图等多类目场景；6-8 档，与日志级别色独立的手工色值 token） */
    val chartPalette: List<Color>,
    /**
     * 布局预览纸（布局检查器示意渲染的根窗底色）：亮暗同值恒浅底——预览是「文档」
     * 语义，与 [consoleBg] 恒深底对称（深浅主题不改变设备界面的示意底色）。
     */
    val previewPaper: Color,
    /**
     * 布局预览墨（纸上线框/标签/文本的恒深值墨水，对 [previewPaper] ≥8:1 AA）。
     * 预览色族在浅纸上呈现，主题派生前景色在亮态是近黑墨水会黑吃黑，故恒值族
     * 与 console 墨水族同配方。
     */
    val previewInk: Color,
    /** 布局预览选中/悬停强调色（对 [previewPaper] ≥4.5:1 AA；选中描边与标签用） */
    val previewAccent: Color,
    /**
     * 布局预览类族色板（亮暗同值深调，纸上洗底可辨）：索引序由消费面（布局检查器
     * 的类族判定）定义，色板只承载色值单源；洗底透明度走 AppAlpha 档。
     */
    val previewPalette: List<Color>,
)

/** 浅色模式语义色 */
val LightEasyToolboxColors = EasyToolboxColors(
    // design-language.md §2.4 裁决亮值（ia-v2）；暗侧 #81C784 裁决「沿用现状」不动
    success = Color(0xFF1B6C4A),
    successContainer = Color(0xFFC8EED9),
    warning = Color(0xFF8A5300),
    warningContainer = Color(0xFFFCDFA6),
    info = Color(0xFF0B57D0),
    infoContainer = Color(0xFFD3E3FD),
    // 错误容器固定槽对：亮恰 = M3 亮 errorContainer 本值；
    // error 亮 = M3 error #B3261E 本值
    errorContainerFixed = Color(0xFFF9DEDC),
    errorFixed = Color(0xFFB3261E),
    consoleBg = Color(0xFF1E1E1E),
    consoleText = Color(0xFFB0BEC5),
    // 控制台语义色亮暗同值（对 #1E1E1E 8.28:1 / 7.53:1，对 #141416 9.14:1 / 8.31:1，双态 AA PASS）；
    // 值源 = 暗色 success/info 现值（console 恒深底语境本就用暗色档）
    consoleSuccess = Color(0xFF81C784),
    consoleInfo = Color(0xFF64B5F6),
    // 控制台背景：亮侧恒 #1E1E1E（本表直出）；暗侧静态基值 #141416，非豁免染色套
    // 在 AppTheme 装配点经 stainedDarkConsoleBg 单槽覆写为骨架色温派生（豁免套回落基值）。
    // 级别色两态同值不变：logDebug 原与 logInfo 同值（0xFFB0BEC5），浅色 DEBUG 行不可辨，
    // 对齐深色侧区分度
    logDebug = Color(0xFF90A4AE),
    logInfo = Color(0xFFB0BEC5),
    logWarn = Color(0xFFFFB74D),
    logError = Color(0xFFEF5350),
    // 结构件身份绿：亮暗同值（对亮态 consoleBg #1E1E1E 6.60:1、暗态染色井 7.19:1+ 全过 AA）
    platingGreen = Color(0xFF50B850),
    // AI 语义强调（浅色）：violet-600 主色 + violet-100 实色底（对 9 预设浅色
    // surface/surfaceContainer 对比度 4.89~5.44，无需微调）
    aiAccent = Color(0xFF7C3AED),
    aiAccentContainer = Color(0xFFEDE9FE),
    // 浮层软影（浅色）= --shadow-float 亮档 rgba(40,55,90,.16) 的色值部分
    floatShadow = Color(0xFF28375A),
    // 浅色模式图表分类色板：饱和度足够的分类色（浅底页面上需深色系保证对比）
    chartPalette = listOf(
        Color(0xFF1565C0), // 蓝
        Color(0xFF2E7D32), // 绿
        Color(0xFF8E24AA), // 紫
        Color(0xFFEF6C00), // 橙
        Color(0xFF00838F), // 青
        Color(0xFFC62828), // 红
        Color(0xFF5D4037), // 棕
        Color(0xFF455A64), // 蓝灰
    ),
    // 预览色族亮暗同值（纸 #F2F3F7 上：墨 #3C4043 8.9:1、强调 #1565C0 5.15:1，双槽 AA PASS）
    previewPaper = Color(0xFFF2F3F7),
    previewInk = Color(0xFF3C4043),
    previewAccent = Color(0xFF1565C0),
    previewPalette = listOf(
        Color(0xFF3F51B5), // 文本类
        Color(0xFF1E8E3E), // 按钮/交互类
        Color(0xFFE8710A), // 输入类
        Color(0xFF8E24AA), // 图像/引擎面
        Color(0xFF00838F), // 列表/滚动容器
        Color(0xFF5F6368), // 其余内容叶子
    ),
)

/** 深色模式语义色 */
val DarkEasyToolboxColors = EasyToolboxColors(
    // 文字色沿用 design-language.md §2.4 暗侧裁决 #81C784/#FFC107 零改动；
    // 容器色加深至槽对文字对比度达 AA 4.5:1（#81C784/#16421A = 5.71、#FFC107/#6E4D00 = 4.73，
    // 旧值 #1B5E20/#9A6B00 仅 3.91/2.88——SlotContrastGuardTest 钉死 8 对全过线）
    success = Color(0xFF81C784),
    successContainer = Color(0xFF16421A),
    warning = Color(0xFFFFC107),
    // 实色直译（非 alpha 合成底）；由 #9A6B00 加深——旧值对 #FFC107 文字 2.88:1 不可读
    warningContainer = Color(0xFF6E4D00),
    // Info 槽对：chartPalette 蓝 + concept-c #6aa7e0 同族对齐现状库内蓝（design-language.md §2.4 暗值）
    info = Color(0xFF64B5F6),
    infoContainer = Color(0xFF0D3B77),
    // 错误容器固定槽对：暗 #5C1A16/#EF9A9E 固定裁决定值
    // ——旧 scheme.errorContainer 动态派生（≈#8C1D18 系）偏亮偏红
    errorContainerFixed = Color(0xFF5C1A16),
    errorFixed = Color(0xFFEF9A9E),
    consoleBg = Color(0xFF141416),
    consoleText = Color(0xFFB0BEC5),
    consoleSuccess = Color(0xFF81C784),
    consoleInfo = Color(0xFF64B5F6),
    // 级别色亮暗同值不变：logDebug 原与 logInfo 同值（0xFFB0BEC5），浅色 DEBUG 行不可辨，
    // 对齐深色侧区分度。暗态 consoleBg 非豁免染色套为骨架派生底（豁免套 = 本表 #141416 基值）。
    logDebug = Color(0xFF90A4AE),
    logInfo = Color(0xFFB0BEC5),
    logWarn = Color(0xFFFFB74D),
    logError = Color(0xFFEF5350),
    // 结构件身份绿：亮暗同值（对暗态页面底 ≈7.3:1，稳态指示档）
    platingGreen = Color(0xFF50B850),
    // AI 语义强调（深色）：对齐原型 #a78bfa + violet-900 深底（深底页面上浅紫保证对比）。
    // 对 9 预设深色 surface/surfaceContainer 对比度 6.00~6.83；
    // 与最近对 Violet 深色 primary（0xFFCFBDFE）ΔE76=31.5、L* 差 15.5——muted 派生 vs
    // 高饱和常量可区分，色相恒紫不动、明度不微调（原型色即定稿色）
    aiAccent = Color(0xFFA78BFA),
    aiAccentContainer = Color(0xFF4C1D95),
    // 浮层软影（深色）= --shadow-float 暗档 rgba(0,0,0,.52) 的色值部分
    floatShadow = Color(0xFF000000),
    // 深色模式图表分类色板：Material 色板 300-400 档（深底页面上需浅色系保证对比）
    chartPalette = listOf(
        Color(0xFF64B5F6), // 蓝
        Color(0xFF81C784), // 绿
        Color(0xFFBA68C8), // 紫
        Color(0xFFFFB74D), // 橙
        Color(0xFF4DD0E1), // 青
        Color(0xFFE57373), // 红
        Color(0xFFA1887F), // 棕
        Color(0xFF90A4AE), // 蓝灰
    ),
    // 预览色族亮暗同值（预览纸是恒浅底「文档」语义，深浅主题不改设备界面示意色）
    previewPaper = Color(0xFFF2F3F7),
    previewInk = Color(0xFF3C4043),
    previewAccent = Color(0xFF1565C0),
    previewPalette = listOf(
        Color(0xFF3F51B5), // 文本类
        Color(0xFF1E8E3E), // 按钮/交互类
        Color(0xFFE8710A), // 输入类
        Color(0xFF8E24AA), // 图像/引擎面
        Color(0xFF00838F), // 列表/滚动容器
        Color(0xFF5F6368), // 其余内容叶子
    ),
)

/** 当前语义色，页面通过 LocalEasyToolboxColors.current 读取 */
val LocalEasyToolboxColors = staticCompositionLocalOf { LightEasyToolboxColors }
