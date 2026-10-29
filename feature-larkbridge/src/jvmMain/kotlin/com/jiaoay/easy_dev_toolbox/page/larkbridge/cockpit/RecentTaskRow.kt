package com.jiaoay.easy_dev_toolbox.page.larkbridge.cockpit

import androidx.compose.animation.Crossfade
import androidx.compose.animation.animateColorAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.style.TextOverflow
import dev.vicart.compose.material.symbols.MaterialSymbols
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import com.jiaoay.easy_dev_toolbox.core.theme.AppAlpha
import com.jiaoay.easy_dev_toolbox.core.theme.AppMotion
import com.jiaoay.easy_dev_toolbox.core.theme.AppShapes
import com.jiaoay.easy_dev_toolbox.core.theme.Dimens
import com.jiaoay.easy_dev_toolbox.core.theme.LocalEasyToolboxColors
import com.jiaoay.easy_dev_toolbox.core.theme.LocalToolboxSurfaceColors
import com.jiaoay.easy_dev_toolbox.page.larkbridge.TaskErrorHint
import com.jiaoay.easy_dev_toolbox.page.larkbridge.stats.UsageStats
import com.jiaoay.easy_dev_toolbox.widget.AppSymbolIcon
import com.jiaoay.lark.bridge.observe.AuditEntryJson
import com.jiaoay.lark.gateway.ReactionBrief

/**
 * 最近任务单行（TaskCard 同视觉语法：状态图标 iconMedium + 主文 bodyMedium + 副文 labelSmall）：
 * 三态图标带 Crossfade fast 过渡（状态变更时的状态认知动效；audit 固定 5 条非流式，不违反零动画铁律）。
 * FAILURE 条目经 [TaskErrorHint] 渲染人话与修复建议。
 *
 * IA 屏⑤：行尾表情徽标（[reactions] 非空且 [botOpenId] 可得时，过滤机器人自点后显示
 * 「👍 ×N」pill——THUMBSUP=success / CROSS_MARK=error；null = 无数据/能力缺席/锚点缺席不渲染）。
 *
 * 改 **internal** 供统计签「任务明细」卡复用（同模块跨包）——
 * 统计签零传 [reactions]/[botOpenId]（默认 null = 无表情徽标，诚实缺席）。
 *
 * meta 行补「kind 人话 + 耗时」。kind 标签走 [UsageStats.kindLabel]
 */
@Composable
internal fun RecentTaskRow(
    entry: AuditEntryJson,
    modifier: Modifier = Modifier,
    /** 该行 userMessageId 的表情回应（null = 旧行无锚点/未刷新——不渲染徽标，诚实缺席） */
    reactions: List<ReactionBrief>? = null,
    /** 机器人自身 openId（非 Connected 态 = null ⇒ 无法过滤机器人自点 ⇒ 不渲染徽标） */
    botOpenId: String? = null,
) {
    val colors = LocalEasyToolboxColors.current
    val time = remember(entry.ts) {
        DateTimeFormatter.ofPattern("HH:mm") /* HH:mm 无日期前缀 */.withZone(ZoneId.systemDefault()).format(Instant.ofEpochMilli(entry.ts))
    }
    // kind 人话 + 耗时（meta 行 muted 段；按 entry 缓存防 1Hz 重算）
    val kindAndElapsed = remember(entry.kind, entry.elapsedMillis) {
        buildString {
            append(UsageStats.kindLabel(entry.kind))
            append(" · ")
            append(UsageStats.formatElapsed(entry.elapsedMillis))
        }
    }
    val prompt = entry.prompt.take(40).ifBlank { entry.kind ?: "-" }
    val statusIcon: String
    val statusTint: Color
    when (entry.status) {
        "SUCCESS" -> {
            statusIcon = MaterialSymbols.CHECK_CIRCLE
            statusTint = colors.success
        }

        "SKIPPED" -> {
            statusIcon = MaterialSymbols.SKIP_NEXT
            statusTint = MaterialTheme.colorScheme.onSurfaceVariant
        }

        else -> {
            statusIcon = MaterialSymbols.ERROR
            statusTint = MaterialTheme.colorScheme.error
        }
    }
    // 状态图标 tint 过渡（fast 档；TaskCard 同配方）
    val tint by animateColorAsState(
        targetValue = statusTint,
        animationSpec = AppMotion.fast(),
        label = "recentTaskTint",
    )

    Row(
        modifier = modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(Dimens.space10),
    ) {
        Crossfade(
            targetState = statusIcon,
            animationSpec = AppMotion.fast(),
            label = "recentTaskStatusIcon",
        ) { icon ->
            AppSymbolIcon(
                icon = icon,
                contentDescription = null,
                size = Dimens.iconMedium,
                tint = tint,
            )
        }
        Column(
            modifier = Modifier.weight(1f),
            verticalArrangement = Arrangement.spacedBy(Dimens.space2),
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(Dimens.space8),
            ) {
                Text(
                    text = time,
                    style = MaterialTheme.typography.labelSmall,
                    // meta 行弱文字走 onSurfaceFaint 语义槽——contentSoft 透明合成
                    // （onSurfaceVariant×0.7）暗色深底对比度低于 AA；onSurfaceFaint 是
                    // 明暗分治档（暗 5.9:1 / 亮 5.13:1）。
                    color = LocalToolboxSurfaceColors.current.onSurfaceFaint,
                )
                // kind 人话 + 耗时（muted 小字，紧跟时间）
                Text(
                    text = kindAndElapsed,
                    style = MaterialTheme.typography.labelSmall,
                    color = LocalToolboxSurfaceColors.current.onSurfaceFaint,
                    maxLines = 1,
                )
                Text(
                    text = prompt,
                    style = MaterialTheme.typography.bodyMedium,
                    modifier = Modifier.weight(1f),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            if (entry.status == "FAILURE") {
                val hint = TaskErrorHint.of(entry.errorCode, backend = entry.backend)
                Text(
                    text = "${hint.plain} → ${hint.fix}",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.error,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
        // ── 行尾表情徽标（「👍 ×N」——过滤机器人自点后的用户表情计数）──
        val badge = CockpitPanelLogic.userReactionBadge(reactions, botOpenId)
        if (badge != null) {
            // 徽标配色：CROSS_MARK（用户点踩）= error；THUMBSUP（用户点赞）= success
            val badgeTint = if (badge.emojiKey == "CROSS_MARK") MaterialTheme.colorScheme.error else colors.success
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(Dimens.space2),
                modifier = Modifier
                    .background(
                        badgeTint.copy(alpha = AppAlpha.bgSubtle),
                        AppShapes.capsule,
                    )
                    .padding(horizontal = Dimens.space8, vertical = Dimens.space2),
            ) {
                AppSymbolIcon(
                    icon = MaterialSymbols.THUMB_UP,
                    contentDescription = null,
                    size = Dimens.iconSmall,
                    tint = badgeTint,
                )
                Text(
                    text = "×${badge.count}",
                    style = MaterialTheme.typography.labelSmall,
                    color = badgeTint,
                )
            }
        }
    }
}
