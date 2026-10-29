package com.jiaoay.easy_dev_toolbox.widget

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import com.jiaoay.easy_dev_toolbox.core.theme.AppShapes
import com.jiaoay.easy_dev_toolbox.core.theme.Dimens

/**
 * 胶囊徽标共享件：非交互式状态/标签 pill 的全应用唯一配方
 * （`Row(background(container, AppShapes.capsule).padding(space8 × space2))` +
 * [AppSymbolIcon](iconSmall) + Text(labelSmall)——配方被 PillLabelTest 源码锚钉死）。
 *
 * 语义洗底必须 container 实色直出，禁 copy(alpha=…) 二次合成（container 定义已含预洗）；
 * 本件无交互态——交互选中底（微洗底三档）由交互件自持。
 * 设计条款（胶囊小件边界/洗底/微洗底）与消费方清单见 design-language.md §2.6/§4 与
 * docs/ui-style-guide.md 4.7n。
 */
@Composable
fun PillLabel(
    text: String,
    container: Color,
    contentColor: Color,
    modifier: Modifier = Modifier,
    icon: String? = null,
    style: TextStyle = MaterialTheme.typography.labelSmall,
) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(Dimens.space2),
        modifier = modifier
            .background(container, AppShapes.capsule)
            .padding(horizontal = Dimens.space8, vertical = Dimens.space2),
    ) {
        if (icon != null) {
            AppSymbolIcon(
                icon = icon,
                contentDescription = null,
                size = Dimens.iconSmall,
                tint = contentColor,
            )
        }
        Text(
            text = text,
            style = style,
            color = contentColor,
        )
    }
}
