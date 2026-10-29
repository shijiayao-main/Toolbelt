package com.jiaoay.easy_dev_toolbox.core.theme

import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.unit.dp

/**
 * 圆角设计 token：预构造 Shape 实例（非 Dp 值），取值与 Material 3 默认形状档位对齐（8/12/16dp）。
 *
 * 替代各处 `RoundedCornerShape(12.dp)` 字面量与未显式装配 Shapes 主题时的
 * `MaterialTheme.shapes.*` 隐式依赖；将来统一圆角策略（如改连续圆角）只需改本文件。
 */
object AppShapes {

    /** 紧凑卡片（任务卡、工具按钮等）——密度收敛：紧凑件更克制 */
    val small = RoundedCornerShape(6.dp)

    /** 面板/拖放区/分组卡，对齐 M3 shapes.medium 默认值 */
    val medium = RoundedCornerShape(12.dp)

    /** 大容器（对话框等），对齐 M3 shapes.large 默认值 */
    val large = RoundedCornerShape(16.dp)

    // ── 液态玻璃（圆角档 = design-language.md §2.9 圆角 5 档同值） ──
    // 圆角四级体系：玻璃大件 16 / sheet 20 / 内容卡 medium(12)→内容件 small(6)-medium 之间 / 控件行 small。
    // 胶囊（capsule）口径：toast 等「横条小件」+ 选中 pill/能力 chip + 投屏手势模式徽标
    // （消费面语义扩张登记见 docs/core/theme.md「有据保留」）；超长容器 >720dp 禁全圆
    // （「香肠感」边界保留）。

    /** 玻璃大件圆角（dock/inspector 浮动胶囊——悬浮感档，桌面收敛不过胖） */
    val glassPanel = RoundedCornerShape(16.dp)

    /** 浮层玻璃圆角（命令面板/导览卡——iOS sheet 档收敛值；值源 [Dimens.glassSheetRadius]，与自绘软影圆角同源） */
    val glassSheet = RoundedCornerShape(Dimens.glassSheetRadius)

    /** chrome 长条圆角（tab 条/状态栏浮动条——大圆角矩形而非全圆胶囊） */
    val glassBar = RoundedCornerShape(12.dp)

    /** 胶囊（percent 全圆——仅限小件：选中 pill/能力 chip/toast/投屏手势模式徽标） */
    val capsule = RoundedCornerShape(50)

    // ── 微档（勾选框/风险徽标专用微圆角；design-language.md §4-11 裁决）──

    /** 勾选框微档（5dp）：勾选框选中对勾容器专用，不挪作他档
     *  （design-language 强制条款 M19：微档只用于对应微件）。 */
    val check = RoundedCornerShape(5.dp)

    /** 风险徽标微档（4dp）：风险徽标（badge）专用。 */
    val badge = RoundedCornerShape(4.dp)
}
