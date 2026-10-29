package com.jiaoay.easy_dev_toolbox.widget

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import com.jiaoay.easy_dev_toolbox.core.theme.AppAlpha
import com.jiaoay.easy_dev_toolbox.core.theme.AppShapes
import com.jiaoay.easy_dev_toolbox.core.theme.Dimens
import com.jiaoay.easy_dev_toolbox.core.theme.WindowSizeClass
import com.jiaoay.easy_dev_toolbox.core.theme.currentWindowSizeClass
import com.jiaoay.easy_dev_toolbox.widget.interactions.HoverableRow

/**
 * 设置双栏分区数据：一个左侧导航分类 = 一组右舱内容。
 *
 * @param title 分类名（左舱行文字 + a11y 语义）
 * @param icon Material Symbols 蛇形名（[AppSymbolIcon] 消费，iconMedium 18dp 档）
 * @param content 该分类的右舱内容（既有 SettingsSection/SectionCard 原样平移进舱）
 */
data class SettingsPaneSection(
    val title: String,
    val icon: String,
    val content: @Composable () -> Unit,
)

/**
 * 设置页双栏容器（`.pane/.pane-nav/.navrow` 三类骨架）：
 * 左舱分类导航恒窄列（[Dimens.settingsNavWidth]）+ 右舱选中分类内容局部滚动。
 *
 * - **Wide 档（>1760dp）双栏**：左舱行 = HoverableRow + 选中态
 *   [AppAlpha.selectedWash] primary 系洗底 + AppShapes.medium + 分类图标 + 标题；
 *   右舱 = 选中分类的 Column(verticalScroll)（滚动责任从整页迁到右舱局部）；
 * - **窄窗（非 Wide）回落**：sections 依序全部渲染为单列——调用方 else 分支语义
 *   由本组件承担（各 section.content 依序直出，与现状单列逐像素等价；
 *   滚动责任仍在调用方 PageScaffold modifier 整页滚——回落分支不内置滚动）；
 * - **进页初值取首分类**（`remember` 态，窗口 resize 不重置、无跨窗竞态；
 *   「记住上次分类」未定持久化语义）。
 *
 * 分类计数 ≤4（设置页三类 / AI 设置页四类）。
 *
 * @param sections 分类列表（顺序即左舱导航序；首项 = 进页初值）
 * @param modifier 外部修饰
 */
@Composable
fun SettingsTwoPane(
    sections: List<SettingsPaneSection>,
    modifier: Modifier = Modifier,
) {
    var selectedIndex by remember { mutableStateOf(0) }
    val paneScrollState = rememberScrollState()

    // 分类切换回顶：右舱滚动位置是组件自持的——切分类后停在中部会直接落进新分类
    // 中段（用户预期 = 进分类见其首行）
    LaunchedEffect(selectedIndex) {
        paneScrollState.animateScrollTo(0)
    }

    if (currentWindowSizeClass() == WindowSizeClass.Wide) {
        Row(
            modifier = modifier,
            horizontalArrangement = Arrangement.spacedBy(Dimens.space20),
        ) {
            // ── 左舱：分类导航恒窄列（.pane-nav width:200px 直译）──
            Column(
                modifier = Modifier
                    .width(Dimens.settingsNavWidth)
                    .fillMaxHeight(),
                verticalArrangement = Arrangement.spacedBy(Dimens.space2),
            ) {
                sections.forEachIndexed { index, section ->
                    val selected = index == selectedIndex
                    HoverableRow(
                        modifier = Modifier
                            .clip(AppShapes.medium)
                            .then(
                                // 选中态微洗底（selectedWash 只用于选中底，
                                // 恒 primary 系；hover 层由 HoverableRow 自绘）
                                if (selected) {
                                    Modifier.background(
                                        color = MaterialTheme.colorScheme.primary.copy(
                                            alpha = AppAlpha.selectedWash,
                                        ),
                                        shape = AppShapes.medium,
                                    )
                                } else {
                                    Modifier
                                },
                            )
                            .clickable { selectedIndex = index }
                            .padding(horizontal = Dimens.space12, vertical = Dimens.space10),
                        shape = AppShapes.medium,
                    ) {
                        AppSymbolIcon(
                            icon = section.icon,
                            contentDescription = null,
                            // .ico 18×18 —— iconMedium 档即对应值
                            size = Dimens.iconMedium,
                            tint = if (selected) {
                                MaterialTheme.colorScheme.onSurface
                            } else {
                                MaterialTheme.colorScheme.onSurfaceVariant
                            },
                        )
                        Spacer(modifier = Modifier.width(Dimens.space10))
                        Text(
                            text = section.title,
                            // 选中态 color onSurface + font-weight:600（非只换字色）
                            style = MaterialTheme.typography.bodyMedium.copy(
                                fontWeight = if (selected) FontWeight.SemiBold else null,
                            ),
                            color = if (selected) {
                                MaterialTheme.colorScheme.onSurface
                            } else {
                                MaterialTheme.colorScheme.onSurfaceVariant
                            },
                        )
                    }
                }
            }

            // ── 右舱：选中分类内容局部滚动（滚动责任从整页迁到舱内）──
            Column(
                modifier = Modifier
                    .weight(1f)
                    .verticalScroll(paneScrollState),
                verticalArrangement = Arrangement.spacedBy(Dimens.space16),
            ) {
                sections.getOrNull(selectedIndex)?.content?.invoke()
            }
        }
    } else {
        // 窄窗回落：单列依序全量渲染（与现状逐像素等价；整页滚动责任在调用方）
        Column(modifier = modifier, verticalArrangement = Arrangement.spacedBy(Dimens.space16)) {
            sections.forEach { it.content() }
        }
    }
}
