package com.jiaoay.easy_dev_toolbox.widget

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.jiaoay.easy_dev_toolbox.core.theme.AppAlpha
import com.jiaoay.easy_dev_toolbox.core.theme.AppShapes
import com.jiaoay.easy_dev_toolbox.core.theme.Dimens
import com.jiaoay.easy_dev_toolbox.core.theme.LocalIsDarkTheme
import com.jiaoay.easy_dev_toolbox.core.theme.LocalToolboxSurfaceColors
import com.jiaoay.easy_dev_toolbox.widget.glass.appPanelRimTop

/**
 * ghost 弱化卡：提示/示意/占位说明卡专用容器。
 *
 * 配方 = **弱化实底**（ghost 预混档：亮 `White×0.38` / 暗 `White×0.05`，
 * [AppAlpha.ghostBgLight]/[AppAlpha.ghostBgDark] 固定值）+ **1dp 发丝描边**
 * （[Dimens.glassStrokeHairline] ×
 * [com.jiaoay.easy_dev_toolbox.core.theme.ToolboxSurfaceColors.borderHairline]，
 * 与 TonalCard 同款明暗分治发丝语言）+ **零 blur**——chrome 恒实底纪律
 * （blur 只属于浮层档 sheet/thin；GhostCard 是内容区卡，不上玻璃）。
 *
 * 明暗通道经 [LocalIsDarkTheme]（AppTheme 恒 provides、缺装配 fail fast——
 * GlassMaterials 同款消费先例）；色源是预混 White 系（非 slot 派生）——
 * ghost 实底预混档，不随主题 seed 染色。
 *
 * 语义边界：**提示卡/示意区/占位说明专用；
 * 正文数据卡禁用**（数据卡恒 TonalCard/SectionCard）。无交互态（占位/提示专用），
 * 可承载禁用内容（内容侧 [AppAlpha.contentDisabled] 0.38 由消费方自带）。
 *
 * 消费方：FlowPage「最近会话」空态提示卡、AI 未配置降级说明卡（AiSettingsPage）。
 *
 * 内边距默认纵 16 × 横 20（[Dimens.space16]/[Dimens.space20]，与 TonalCard 默认对齐）。
 *
 * @param contentPaddingVertical 内容区纵向内边距（默认 space16）
 * @param contentPaddingHorizontal 内容区横向内边距（默认 space20）
 */
@Composable
fun GhostCard(
    modifier: Modifier = Modifier,
    contentPaddingVertical: Dp = Dimens.space16,
    contentPaddingHorizontal: Dp = Dimens.space20,
    content: @Composable ColumnScope.() -> Unit,
) {
    val surface = LocalToolboxSurfaceColors.current
    // 预混 White 系：明暗分治 alpha 两档（token 承载，非 0.x 字面量）
    val containerColor = if (LocalIsDarkTheme.current) {
        Color.White.copy(alpha = AppAlpha.ghostBgDark)
    } else {
        Color.White.copy(alpha = AppAlpha.ghostBgLight)
    }
    Card(
        modifier = modifier
            .fillMaxWidth()
            // .card.ghost 只覆写 background、继承 .card 双影：rim 跟随
            .appPanelRimTop(AppShapes.large),
        colors = CardDefaults.cardColors(containerColor = containerColor),
        elevation = CardDefaults.cardElevation(defaultElevation = 0.dp),
        shape = AppShapes.large,
        border = BorderStroke(Dimens.glassStrokeHairline, surface.borderHairline),
    ) {
        Column(
            modifier = Modifier.padding(
                PaddingValues(
                    start = contentPaddingHorizontal,
                    top = contentPaddingVertical,
                    end = contentPaddingHorizontal,
                    bottom = contentPaddingVertical,
                ),
            ),
            verticalArrangement = Arrangement.spacedBy(Dimens.space8),
            content = content,
        )
    }
}
