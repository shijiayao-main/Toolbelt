package com.jiaoay.easy_dev_toolbox.core.theme

import androidx.compose.material3.CardColors
import androidx.compose.material3.CardDefaults
import androidx.compose.runtime.Composable

/**
 * 卡片样式设计 token：统一卡片容器配色（[CardColors]）组合。
 *
 * 容器色取表面语义 slot [LocalToolboxSurfaceColors] 的 `surfaceRaised`
 * （M3 surfaceContainerLow 派生，深浅模式对比度由 dynamicColorScheme
 * 保证）——替代散写的 `surfaceVariant.copy(AppAlpha.bgTask)` 发灰 alpha 合成。
 * **单点改全应用生效**；新增 outlined 描边档（对话框内嵌卡等强调场景）。
 *
 * 配套用法（colors 之外的两个组合参数约定）：
 * ```kotlin
 * Card(
 *     colors = AppCardStyles.tonal(),
 *     elevation = CardDefaults.cardElevation(defaultElevation = 0.dp),
 *     shape = AppShapes.medium,
 * )
 * ```
 * 描边层次：TonalCard 默认带 `strokeThin + borderSubtle` 描边
 * （见 ui/widget/TonalCard.kt），tonal()/outlined() 只管容器色。
 */
object AppCardStyles {

    /** tonal 卡配色：surfaceRaised 容器底色，配套 0 elevation + 细描边使用 */
    @Composable
    fun tonal(): CardColors = CardDefaults.cardColors(
        containerColor = LocalToolboxSurfaceColors.current.surfaceRaised,
    )

    /** 描边强调卡配色：surface 基底（浮一层描边强调，容器色不再加深） */
    @Composable
    fun outlined(): CardColors = CardDefaults.cardColors(
        containerColor = LocalToolboxSurfaceColors.current.surface,
    )
}
