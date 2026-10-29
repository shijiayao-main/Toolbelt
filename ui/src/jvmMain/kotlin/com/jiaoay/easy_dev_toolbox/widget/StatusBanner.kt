package com.jiaoay.easy_dev_toolbox.widget

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import com.jiaoay.easy_dev_toolbox.core.theme.AppShapes
import com.jiaoay.easy_dev_toolbox.core.theme.Dimens
import com.jiaoay.easy_dev_toolbox.core.theme.LocalEasyToolboxColors
import dev.vicart.compose.material.symbols.MaterialSymbols

/**
 * 通用状态容器条：容器色 + 前缀图标 + 文字三层语言（docs/ui-style-guide.md 6.4）的组件化。
 *
 * 四个语义槽，容器/图标/文字三色成套（深浅模式经 [LocalEasyToolboxColors] 自适应）：
 * - [Status.Success]：successContainer 底 + CheckCircle + success 色文字
 * - [Status.Warning]：warningContainer 底 + WarningAmber + warning 色文字
 * - [Status.Error]：errorContainer 底 + Error + onErrorContainer
 * - [Status.Info]：infoContainer 底 + Info + info 色文字（Info 槽对齐全，不借用 warningContainer 弱黄）
 *
 * [trailing] 可选行尾槽（如附操作按钮）；[details] 可选明细槽——内容渲染在容器色内、
 * 标题行下方，明细不得散落在条外页面背景上（6.4 分组语义）。
 * 槽位色对表/图标蛇形名/与 TaskCard 的协同见 docs/ui-style-guide.md 4.7e。
 */
@Composable
fun StatusBanner(
    status: StatusBannerStatus,
    text: String,
    modifier: Modifier = Modifier,
    trailing: (@Composable () -> Unit)? = null,
    details: (@Composable ColumnScope.() -> Unit)? = null,
) {
    val colors = LocalEasyToolboxColors.current
    // 正文恒 onSurface（槽色由 icon 承载）——四槽一致
    val (container, icon, iconOutlined, iconTint, textColor) = when (status) {
        StatusBannerStatus.Success -> BannerColors(
            container = colors.successContainer,
            icon = MaterialSymbols.CHECK_CIRCLE,
            iconOutlined = false,
            iconTint = colors.success,
            textColor = MaterialTheme.colorScheme.onSurface,
        )

        StatusBannerStatus.Warning -> BannerColors(
            container = colors.warningContainer,
            icon = MaterialSymbols.WARNING_AMBER,
            iconOutlined = false,
            iconTint = colors.warning,
            textColor = MaterialTheme.colorScheme.onSurface,
        )

        StatusBannerStatus.Error -> BannerColors(
            // Error 槽暗态容器用固定槽 errorContainerFixed（M3 动态派生偏亮偏红）
            container = LocalEasyToolboxColors.current.errorContainerFixed,
            icon = MaterialSymbols.ERROR,
            iconOutlined = false,
            iconTint = MaterialTheme.colorScheme.error,
            textColor = MaterialTheme.colorScheme.onSurface,
        )

        // Info 槽正源 = info/infoContainer 槽对，不再借用 Warning 弱黄。
        StatusBannerStatus.Info -> BannerColors(
            container = colors.infoContainer,
            icon = MaterialSymbols.INFO,
            iconOutlined = false,
            iconTint = colors.info,
            textColor = MaterialTheme.colorScheme.onSurface,
        )
    }

    Surface(
        modifier = modifier.fillMaxWidth(),
        // 行级容器 12dp——design-language「行级容器/banner/输入」= r-md
        shape = AppShapes.medium,
        color = container,
    ) {
        // 统一 Column 外壳（单行时与裸 Row 布局等价）：padding 收口在 Column，
        // 明细行落在容器色内、与头行行距 space2
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = Dimens.space16, vertical = Dimens.space12),
            verticalArrangement = androidx.compose.foundation.layout.Arrangement.spacedBy(Dimens.space2),
        ) {
            BannerRow(
                text = text,
                textColor = textColor,
                icon = icon,
                iconOutlined = iconOutlined,
                iconTint = iconTint,
                trailing = trailing,
            )
            if (details != null) details()
        }
    }
}

/** 头行（图标 + 标题文字 + 可选行尾槽）；有明细槽时作为 Column 首行，行距 space2 */
@Composable
private fun BannerRow(
    text: String,
    textColor: Color,
    icon: String,
    iconOutlined: Boolean,
    iconTint: Color,
    trailing: (@Composable () -> Unit)? = null,
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = androidx.compose.foundation.layout.Arrangement.spacedBy(Dimens.space8),
    ) {
        AppSymbolIcon(
            icon = icon,
            contentDescription = null, // 语义由旁侧文字承载（6.1 装饰性图标规则）
            size = Dimens.iconSmall,
            tint = iconTint,
            outlined = iconOutlined,
        )
        Text(
            text = text,
            // 字号 bodyMedium（fs-13）、色恒 onSurface（icon 保留语义色四槽分档）——
            // textColor 参数仅承载语义色传路径
            style = MaterialTheme.typography.bodyMedium,
            color = textColor,
            modifier = Modifier.weight(1f),
        )
        if (trailing != null) trailing()
    }
}

/** 状态容器条语义槽 */
enum class StatusBannerStatus { Success, Warning, Error, Info }

private data class BannerColors(
    val container: Color,
    val icon: String,
    val iconOutlined: Boolean,
    val iconTint: Color,
    val textColor: Color,
)
