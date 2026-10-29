package com.jiaoay.easy_dev_toolbox.widget.interactions

import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import com.jiaoay.easy_dev_toolbox.core.theme.AppAlpha

/**
 * 选中/悬停行洗底色的单源解析（交互微洗底恒 primary 系——档位真源 = core/theme
 * AppAlpha）。
 *
 * 优先序 = 选中压过悬停（selectedWash 0.08 > hoverWash 0.04，层级序即优先序；
 * 同 hover 的选中行不叠加两档洗底）。
 *
 * [selectedColor] 承载选中档的落位差异，两族各有主：
 * - **微洗底档**（默认）：primary×[AppAlpha.selectedWash]——列表行/证据行高亮族；
 * - **选中容器实底档**：调用方传 surfaceColors.selectedContainer——页签选中
 *   胶囊/空间选中行族（实底选中面，配 onSelectedContainer 字链）。
 *
 * 纯悬停行（无选中语义）走 [selected] 缺省 false。行落位（shape/padding/
 * clickable 挂载）留调用方——本函数只管颜色三态。
 */
@Composable
fun selectionHoverWashColor(
    hovered: Boolean,
    selected: Boolean = false,
    selectedColor: Color = MaterialTheme.colorScheme.primary.copy(alpha = AppAlpha.selectedWash),
): Color = when {
    selected -> selectedColor
    hovered -> MaterialTheme.colorScheme.primary.copy(alpha = AppAlpha.hoverWash)
    else -> Color.Transparent
}
