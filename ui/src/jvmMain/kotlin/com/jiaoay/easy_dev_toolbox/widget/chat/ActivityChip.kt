package com.jiaoay.easy_dev_toolbox.widget.chat

import com.jiaoay.easy_dev_toolbox.core.llm.agent.ChatEntry
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.text.style.TextOverflow
import com.jiaoay.easy_dev_toolbox.core.theme.AppMotion
import com.jiaoay.easy_dev_toolbox.core.theme.AppShapes
import com.jiaoay.easy_dev_toolbox.core.theme.Dimens
import com.jiaoay.easy_dev_toolbox.core.theme.LocalEasyToolboxColors
import com.jiaoay.easy_dev_toolbox.core.theme.LocalToolboxSurfaceColors
import com.jiaoay.easy_dev_toolbox.core.theme.dayBattleTint

/**
 * 实时活动 chip（OpenHands deriveLiveActivity 模式）：
 *
 * 从事件流推导的「当前正在做什么」文案（非手写 phase 字符串），
 * running 时绿点 pulse 呼吸 + 文案，终态时静态呈现一小段时间。
 * pulse 是呼吸循环动画——不属于流式内容动画铁律管辖面（它是状态指示器，
 *    与 CircularProgressIndicator 同类）。
 * chip 底走全圆胶囊（轻标签胶囊档；本卡只动 shape，pulse 动画零动）。
 */
@Composable
fun ActivityChip(
    entry: ChatEntry.Activity,
    modifier: Modifier = Modifier,
) {
    val surface = LocalToolboxSurfaceColors.current
    // 运行点 = --info 语义蓝
    // 旧 primary（随主题紫）把「运行中」语义色错绑主题主色
    // 昼战（design-doc.md §5.3，dayBattleTint）：info 深档直落昼底/昼卡破图形线——点坐 infoContainer 档
    val dotColor = if (entry.running) {
        dayBattleTint(LocalEasyToolboxColors.current.info, LocalEasyToolboxColors.current.infoContainer)
    } else {
        surface.onSurfaceFaint
    }

    // running 态呼吸（1.2s 周期 alpha 0.35↔1.0；repeatable 规格经 AppMotion.pulse 工厂——白名单纪律）
    val transition = rememberInfiniteTransition(label = "activity-pulse")
    val pulse by transition.animateFloat(
        initialValue = 0.35f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(
            animation = AppMotion.pulse(),
            repeatMode = RepeatMode.Reverse,
        ),
        label = "pulse",
    )
    val dotAlpha = if (entry.running) pulse else 1f

    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = androidx.compose.foundation.layout.Arrangement.spacedBy(Dimens.space6),
        modifier = modifier
            .background(color = surface.surfaceDeep, shape = AppShapes.capsule)
            .padding(horizontal = Dimens.space8, vertical = Dimens.space4),
    ) {
        Box(
            modifier = Modifier
                .size(Dimens.dotIndicatorSize)
                .alpha(dotAlpha)
                .background(color = dotColor, shape = CircleShape),
        )
        Text(
            text = entry.text,
            style = MaterialTheme.typography.labelSmall,
            color = surface.onSurfaceMid,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}
