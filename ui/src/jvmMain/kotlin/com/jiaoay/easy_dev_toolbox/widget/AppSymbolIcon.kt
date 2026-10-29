package com.jiaoay.easy_dev_toolbox.widget

import androidx.compose.foundation.layout.size
import androidx.compose.material3.LocalContentColor
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import com.jiaoay.easy_dev_toolbox.core.theme.Dimens
import dev.vicart.compose.material.symbols.MaterialSymbol

/**
 * Material Symbols 渲染组件（全仓图标唯一出口）。
 *
 * ## 为什么存在
 * material-icons-extended 已 deprecated（Google 停止新增），全量迁移至
 * Material Symbols（Google 当前图标集）。CMP Desktop 无官方 Symbols artifact，用
 * dev.vicart 字体形态库（ttf 可变字体渲染——非 ImageVector）。
 *
 * **导航契约连锁改型**：`ToolNavItem.icon` 从
 * [androidx.compose.ui.graphics.vector.ImageVector] 改为 `String`（symbol 蛇形名，如
 * "arrow_back"），渲染统一收口本组件。
 *
 * ## 与旧 Icon() 的参数映射
 * - `imageVector = Icons.Filled.Close` → `icon = MaterialSymbols.CLOSE`（常量值即蛇形名）
 * - `contentDescription`：dev.vicart 字体形态无此参（字形非语义组件），本组件经
 *   `Modifier.semantics` 补齐——a11y 不回退；
 * - `tint` 原样；默认 tint 对齐旧 `Icon()` 的 `LocalContentColor.current`
 *   （不是 onSurfaceVariant——批量替换后视觉零回归）；
 * - **尺寸必须传 [size]（vicart `size` 参数驱动字形 fontSize，字号即图标大小）**。
 *   `Modifier.size()` 只是布局盒约束、不缩放字形（字体形态与矢量裁剪的本质差异），
 *   两者混用会出现「盒子对、字形恒默认值」的尺寸漂移（真机反馈确认）；
 *   默认值 [Dimens.iconDefault]（24dp）= M3 `Icon()` 缺省框的等价迁移；
 * - 风格：默认 Filled；`outlined = true` 对应旧 `Icons.Outlined.*`。
 *
 * UiGuard：本组件走字体字形渲染，不触 Material Shapes/RAW_DP 规则。
 */
@Composable
fun AppSymbolIcon(
    icon: String,
    contentDescription: String?,
    size: Dp = Dimens.iconDefault,
    modifier: Modifier = Modifier,
    tint: Color = LocalContentColor.current,
    outlined: Boolean = false,
    bold: Boolean = false,
) {
    val desc = contentDescription
    val m = if (desc != null) modifier.semantics { this.contentDescription = desc } else modifier
    if (outlined) {
        MaterialSymbol.Outlined(
            icon = icon,
            weight = if (bold) FontWeight.Bold else FontWeight.Normal,
            size = size,
            tint = tint,
            modifier = m,
        )
    } else {
        MaterialSymbol.Filled(
            icon = icon,
            weight = if (bold) FontWeight.Bold else FontWeight.Normal,
            size = size,
            tint = tint,
            modifier = m,
        )
    }
}
