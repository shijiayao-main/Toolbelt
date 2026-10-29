package com.jiaoay.easy_dev_toolbox.widget

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import com.jiaoay.easy_dev_toolbox.core.theme.Dimens
import com.jiaoay.easy_dev_toolbox.core.theme.LocalEasyToolboxColors
import com.jiaoay.easy_dev_toolbox.core.theme.dayBattleTint

/**
 * 行首三态状态点（.dotok/.dotrun/.doterr 三态类）：
 * 6dp 纯色圆点，三态语义——ok=success（.dotok）/ run=info（.dotrun）/ err=error（.doterr）。
 *
 * 消费面：工具行流 `.srow`（蒸馏运行态）+ 检索命中行 dotok。
 * 间距 margin-right --sp-6 → 调用方经外层 spacedBy(Dimens.space6) 或行内 Spacer 承担。
 *
 * 点色语义单源（DL 条款，见仓库 design-language.md）。
 */
@Composable
fun StatusDot(
    state: DotState,
    modifier: Modifier = Modifier,
) {
    val colors = LocalEasyToolboxColors.current
    // 昼战（design-doc.md §5.3，dayBattleTint 单源）：语义深档点直落昼底/昼卡 1.1~1.5 破图形线——
    // 点坐语义 container 档（对昼底 3.4~3.5 / 对昼卡 4.4+）；非昼战原值逐位不变
    val color = when (state) {
        DotState.OK -> dayBattleTint(colors.success, colors.successContainer)
        DotState.RUN -> dayBattleTint(colors.info, colors.infoContainer)
        DotState.ERR -> dayBattleTint(MaterialTheme.colorScheme.error, colors.errorContainerFixed)
    }
    Box(
        modifier = modifier
            .size(Dimens.space6)
            .background(color, CircleShape),
    )
}

/** 状态点三态（.dotok / .dotrun / .doterr 单源枚举） */
enum class DotState { OK, RUN, ERR }

/** 便捷重载：直接传色（罕见：调用方自带语义色非三态内时） */
@Composable
fun StatusDot(color: Color, modifier: Modifier = Modifier) {
    Box(
        modifier = modifier
            .size(Dimens.space6)
            .background(color, CircleShape),
    )
}
