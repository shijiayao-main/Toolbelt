package com.jiaoay.easy_dev_toolbox.core.theme

/**
 * 透明度设计 token：语义命名的 alpha 阶梯（命名收口第一轮）。
 *
 * - **token 值 = 现状精确值**：取值来自全项目 21 处 `copy(alpha = ...)` 调用的逐处核实
 *   （各令牌注释即典型用例），第一轮只命名、不改值。
 * - **近值不归并**：0.3 / 0.35 / 0.4 等语义相近但数值不同的档位保持独立 token——
 *   透明度的观感漂移不可控（同一 alpha 在不同底色/色相下视觉差异非线性），
 *   归并即引入无法预见的视觉变化，违反本变更的值等价硬约束。
 * - 同值多场景（如 [borderFaint] 兼用于边框/轨道/卡背景）第一轮共用同一 token；
 *   语义细分留待后续替换波次按需拆分，不阻塞基建。
 */
object AppAlpha {

    /** 全透明（0.0）：hover 操作区隐形态（配置行「恢复默认」未 hover 时；alpha=0 常量非 0.x 字面量） */
    const val transparent = 0.0f

    /** 弱背景（0.3）：拖放区常态/拖拽背景（FileDropZone）、分组对话框非当前项背景 */
    const val bgSubtle = 0.3f

    /** 分段控件轨道底（0.08）：稿 `.seg{background:color-mix(txt 8%)}` 直译——SegmentedControl 容器胶囊底 */
    const val bgSegTrack = 0.08f

    /** 次弱背景（0.35）：自定义工具编辑器参数区（ShellCustomEditorDialog）、输出面板底色 */
    const val bgSubtleAlt = 0.35f

    /** 禁用态内容（0.38）：禁用图标/文字 tint，对齐 M3 disabled content alpha（如调试页发送按钮禁用态） */
    const val contentDisabled = 0.38f

    /** 弱描边（0.4）：主题卡边框（PageSettings）；兼用于进度条轨道、父节点卡背景（同值多场景，见类注释） */
    const val borderFaint = 0.4f

    /** 任务卡背景（0.5）：TaskCard 容器底色 */
    const val bgTask = 0.5f

    const val scrimSoft = 0.5f

    const val contentFaint = 0.6f

    const val contentFaintTextDark = 0.89f

    const val contentFaintTextLight = 0.72f

    /** 软内容（0.7）：辅助文字（工具节点徽标/描述、输出时间戳） */
    const val contentSoft = 0.7f

    // ── 液态玻璃（材质透明度档；明暗分治——近值不归并纪律延续）──
    // 取值依据与档位表见 docs/core/theme.md「玻璃材质 token」节。
    // chrome/panel 玻璃档恒实底——本组只留浮层两档 tint（sheet/thin）。

    /** sheet 玻璃 tint（暗 0.72）：命令面板/导览卡厚板（跨窗采样兜底时近实底成立） */
    const val glassTintSheetDark = 0.72f

    /** sheet 玻璃 tint（亮 0.92） */
    const val glassTintSheetLight = 0.92f

    /** thin 玻璃 tint（暗 0.85）：状态栏/toast（明暗分治纪律） */
    const val glassTintThinDark = 0.85f

    /** thin 玻璃 tint（亮 0.94）：亮色近实底档（与 sheet 0.92 同级） */
    const val glassTintThinLight = 0.94f

    /** 玻璃受光缘高光线·panel 档（暗 0.20 = `--panel-rim` 暗档） */
    const val glassRimPanelDark = 0.20f

    /**
     * 玻璃受光缘高光线·chrome/sheet/thin 档（暗 0.22）= `--chrome-rim`/`--sheet-rim`/
     * `--thin-rim` 暗档。防回退：勿回填「0.22×.45」换算产物 0.10（非档位直取值）。
     * panel 档单独走 [glassRimPanelDark] 0.20。
     */
    const val glassRimDark = 0.22f

    /**
     * 玻璃受光缘高光线·chrome/sheet/thin 档（亮 0.50；勿取 0.22——那是暗档值与换算产物）。
     * panel 档单独走 [glassRimPanelLight] 0.45。
     */
    const val glassRimLight = 0.50f

    /** 玻璃受光缘高光线·panel 档（亮 0.45 = `--panel-rim` 亮档） */
    const val glassRimPanelLight = 0.45f

    /** 玻璃外发丝描边（暗 0.10）= --glass-brd 暗 rgba(215,218,227,.10)——onSurface(#D7DAE3)×0.10 */
    const val glassBorderDark = 0.10f

    /** 玻璃外发丝描边（亮 0.08）= --glass-brd 亮 rgba(28,27,31,.08)——onSurface(#1C1B1F)×0.08（亮档不取 0.10——那是暗档值） */
    const val glassBorderLight = 0.08f

    // ── 标题栏组（取色/圆点色源改走 AppTitleBar 直配；本组 alpha 常量无消费点）──

    /** 浮层软影基强度（暗 0.52）：自绘软影最内层起始强度——稿 --shadow-float 暗档 rgba(0,0,0,.52) 直译 */
    const val floatShadowDark = 0.52f

    /** 浮层软影基强度（亮 0.16）：稿亮档 rgba(40,55,90,.16) 直译 */
    const val floatShadowLight = 0.16f

    const val auroraPrimaryDark = 0.10f

    /** aurora 主光斑峰值（亮 0.07） */
    const val auroraPrimaryLight = 0.07f

    /** aurora 副光斑峰值（暗 0.08） */
    const val auroraTertiaryDark = 0.08f

    /** aurora 副光斑峰值（亮 0.05） */
    const val auroraTertiaryLight = 0.05f

    // ── 发丝描边 alpha 档 + 交互微洗底三档（近值不归并）──

    const val borderHairlineLight = 0.08f

    const val borderHairlineDark = 0.10f

    const val hoverWash = 0.04f

    /** 交互微洗底·selected 档（0.08）——tab 选中/palette 命中行底（× primary） */
    const val selectedWash = 0.08f

    /** 交互微洗底·hot 档（0.10）——chip 激活态/高亮命中底（× primary） */
    const val hotWash = 0.10f

    /** 块头深洗底·中档（0.55）：stagebar 55% / blkhead.ai 55%（× surfaceDeep / aiAccentContainer） */
    const val blockHeadWash = 0.55f

    /** 块头深洗底·高档（0.60）：.blkhead 60% × surfaceDeep（中档 0.55 之上的加强档） */
    const val blockHeadWashStrong = 0.60f

    /** 边界弱描边叠加因子（0.14）= --border-subtle 亮 rgba(28,27,31,.14)（× onSurface） */
    const val borderSubtleOverlay = 0.14f

    /** 控制台表头分隔线（0.18）：console-text 18% color-mix 配比 */
    const val consoleHeadDivider = 0.18f

    // ── ghost 弱化卡预混 alpha 两档（design-language「ghost 固定值」条款，预混 White 系禁 blur）──

    const val ghostBgLight = 0.38f

    const val ghostBgDark = 0.05f

    const val consoleFieldBg = 0.08f

    /** 控制台语境容器描边（0.14）：同上（color-mix(console-text 14%)——cmdin/深底 search 描边档） */
    const val consoleFieldBorder = 0.14f

    const val switchThumbShadow = 0.20f
}
