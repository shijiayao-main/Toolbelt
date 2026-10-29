package com.jiaoay.easy_dev_toolbox.widget.evidence

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import com.jiaoay.easy_dev_toolbox.core.evidence.TIMESTAMP_PATTERN
import com.jiaoay.easy_dev_toolbox.core.theme.AppAlpha
import com.jiaoay.easy_dev_toolbox.core.theme.AppShapes
import com.jiaoay.easy_dev_toolbox.core.theme.DesktopTypography
import com.jiaoay.easy_dev_toolbox.core.theme.Dimens

/**
 * Markdown 表格渲染块：报告「## 完整时间线」段的 `|`
 * 分割行（[rows] 首行 = header）渲染为列对齐网格——报告五部分中最核心的逐行核对面
 * 不再以管道符裸文本呈现（prompt 铁律「表格-日志配对」的对读端）。
 *
 * - header 行：labelMedium + surfaceVariant 微洗底；
 * - 数据行：mono（[DesktopTypography]——时间戳列可读性优先），首列 primary 色
 *   （时间戳列 = 跳转锚的视觉语言，与报告关键依据引用同款）；
 * - 列宽：每列 weight 均分（时间戳列窄内容列宽——对齐语义达标的最简配方；
 *   IntrinsicSize 方案超必要，施工裁定见卡面 notes）；
 * - [onJumpToTimestamp]：行内时间戳命中 [TIMESTAMP_PATTERN] 时整行可点——
 *   复用报告引用的跳转链（切产物文件 + scrollToItem + 高亮）。
 *
 * 零 dp 字面量（UiGuard RAW_DP）：间距全走 Dimens token。
 */
@Composable
fun MarkdownTableBlock(
    /** 表格行（首行 = header；数据行含时间戳与事件列） */
    rows: List<List<String>>,
    /** 行时间戳跳转回调（null 或行无时间戳 = 该行不可点） */
    onJumpToTimestamp: ((String) -> Unit)? = null,
    modifier: Modifier = Modifier,
) {
    if (rows.isEmpty()) return
    val header = rows.first()
    val body = rows.drop(1)
    val columnCount = rows.maxOf { it.size }

    Column(
        modifier = modifier
            .background(
                MaterialTheme.colorScheme.surfaceVariant.copy(AppAlpha.contentFaint),
                AppShapes.small,
            )
            .padding(Dimens.space8),
    ) {
        // header 行：surface-deep 实底 + 600 字重 + onSurface
        Row(
            horizontalArrangement = Arrangement.spacedBy(Dimens.space8),
            modifier = Modifier
                .background(
                    com.jiaoay.easy_dev_toolbox.core.theme.LocalToolboxSurfaceColors.current.surfaceDeep,
                    AppShapes.small,
                )
                .padding(horizontal = Dimens.space4, vertical = Dimens.space2),
        ) {
            header.forEachIndexed { index, cell ->
                Text(
                    cell,
                    style = MaterialTheme.typography.labelMedium.copy(
                        fontWeight = androidx.compose.ui.text.font.FontWeight.SemiBold,
                    ),
                    color = MaterialTheme.colorScheme.onSurface,
                    modifier = tableColModifier(index, columnCount),
                )
            }
            // 列数对齐补位（header 列少于数据行最大列数时防错位）
            repeat(columnCount - header.size) { Spacer(Modifier.weight(1f)) }
        }
        // 数据行（mono；时间戳行可点跳中栏对应日志行）
        body.forEach { row ->
            val timestamp = row.firstNotNullOfOrNull { cell -> TIMESTAMP_PATTERN.find(cell)?.value }
            val rowModifier = if (timestamp != null && onJumpToTimestamp != null) {
                Modifier.clickable { onJumpToTimestamp(timestamp) }
            } else {
                Modifier
            }
            Row(
                modifier = rowModifier.padding(vertical = Dimens.space2),
                horizontalArrangement = Arrangement.spacedBy(Dimens.space8),
            ) {
                row.forEachIndexed { index, cell ->
                    Text(
                        cell,
                        style = DesktopTypography.mono,
                        color = if (index == 0) {
                            MaterialTheme.colorScheme.primary
                        } else {
                            MaterialTheme.colorScheme.onSurface
                        },
                        modifier = tableColModifier(index, columnCount),
                    )
                }
                repeat(columnCount - row.size) { Spacer(Modifier.weight(1f)) }
            }
        }
    }
}

/** 列宽规则（grid-template-columns: 96px 60px 1fr）：
 *  首列 96dp / 次列 60dp（仅 ≥3 列时）/ 其余列均分 weight（RowScope 内调 weight——
 *  Modifier.weight 是 RowScope 成员扩展，须在 Row 内容 lambda 里调用） */
private fun RowScope.tableColModifier(index: Int, columnCount: Int): Modifier = when {
    index == 0 -> Modifier.width(Dimens.reportTableColTs)
    index == 1 && columnCount >= 3 -> Modifier.width(Dimens.reportTableColLvl)
    else -> Modifier.weight(1f)
}
