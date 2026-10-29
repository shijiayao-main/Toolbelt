package com.jiaoay.easy_dev_toolbox.page.ai

import dev.vicart.compose.material.symbols.MaterialSymbols

import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import com.jiaoay.easy_dev_toolbox.widget.AppSymbolIcon
import com.jiaoay.easy_dev_toolbox.core.theme.AppShapes
import com.jiaoay.easy_dev_toolbox.core.theme.Dimens
import com.jiaoay.easy_dev_toolbox.core.theme.LocalEasyToolboxColors
import com.jiaoay.easy_dev_toolbox.widget.PillLabel
import com.jiaoay.easy_dev_toolbox.widget.interactions.HoverableRow

/**
 * 模型清单结果区（拉取成功后展开的候选列表）。
 *
 * 独立于页面组合，便于测试与复用；渲染策略沿用飞书桥接页 [ChoiceField] 的多选分支
 * （选项数不可预期 ⇒ 不用 SegmentedButton，改「可滚动行列表 + Check 选中标记」）：
 * - 容器恒普通 [Column] + verticalScroll，**禁用 Lazy 系容器**：本组件住在
 *   `ExposedDropdownMenu` 菜单内，M3 菜单展开时按锚点宽度做固有尺寸测量，Lazy 布局
 *   基于 SubcomposeLayout 不支持固有测量——固有测量链触达即 IllegalStateException
 *   抛在渲染循环（未捕获 = 整进程退出）。限高封顶 [Dimens.consoleHeightMin] 内
 *   全量组合，候选上限为端点模型数（百级），组合成本可接受；
 * - 选中项打勾 + 成功色，未选中项悬停高亮（[HoverableRow]）；
 * - 当前已填的模型 ID 不在列表中时同样展示（用户可手工填任意 ID，不该因拉取而丢失）。
 *
 * 零白名单：间距/尺寸走 [Dimens]，圆角走 [AppShapes]，颜色走 [LocalEasyToolboxColors]。
 */
@Composable
fun ModelCatalogList(
    modelIds: List<String>,
    selected: String,
    onPick: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    val colors = LocalEasyToolboxColors.current
    Column(
        modifier = modifier
            .fillMaxWidth()
            .heightIn(max = Dimens.consoleHeightMin)
            .verticalScroll(rememberScrollState()),
    ) {
        modelIds.forEach { id ->
            val isSelected = id == selected
            HoverableRow(
                modifier = Modifier
                    .fillMaxWidth()
                    // hover 反馈归 HoverableRow 洗底独占，抑制默认矩形 indication
                    .clickable(
                        interactionSource = remember { MutableInteractionSource() },
                        indication = null,
                    ) { onPick(id) }
                    .padding(horizontal = Dimens.space12, vertical = Dimens.space8),
                shape = AppShapes.small,
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(Dimens.space8),
                ) {
                    // 固定占位：选中标记出现/消失不引起文本横向位移
                    Box(modifier = Modifier.size(Dimens.iconSmall)) {
                        if (isSelected) {
                            AppSymbolIcon(
                                icon = MaterialSymbols.CHECK,
                                contentDescription = null,
                                size = Dimens.iconSmall,
                                tint = colors.success,
                            )
                        }
                    }
                    Column(verticalArrangement = Arrangement.spacedBy(Dimens.space2)) {
                        Text(
                            text = id,
                            style = MaterialTheme.typography.bodyMedium,
                            color = if (isSelected) colors.success else MaterialTheme.colorScheme.onSurface,
                        )
                        if (isSelected && id == selected) {
                            // 「当前使用」= successContainer 槽底 + success 全色字
                            //（语义 container 对直出——文字透明合成低对比禁令，
                            // PillLabel 现成件同款语言）
                            PillLabel(
                                text = "当前使用",
                                container = colors.successContainer,
                                contentColor = colors.success,
                            )
                        }
                    }
                }
            }
        }
    }
}
