package com.jiaoay.easy_dev_toolbox.page.settings

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.ColorScheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import com.jiaoay.easy_dev_toolbox.core.theme.AppAlpha
import com.jiaoay.easy_dev_toolbox.core.theme.AppShapes
import com.jiaoay.easy_dev_toolbox.core.theme.AppThemeSpec
import com.jiaoay.easy_dev_toolbox.core.theme.Dimens
import com.jiaoay.easy_dev_toolbox.core.theme.LocalToolboxSurfaceColors
import com.jiaoay.easy_dev_toolbox.core.theme.ThemeSpecimenPatternSample
import com.jiaoay.easy_dev_toolbox.core.theme.ThemeSpecimenTriad
import com.jiaoay.easy_dev_toolbox.core.theme.drawSpecimenPatternSample
import com.jiaoay.easy_dev_toolbox.core.theme.specimenPatternSample
import com.jiaoay.easy_dev_toolbox.core.theme.specimenTriad
import com.jiaoay.easy_dev_toolbox.widget.AppSymbolIcon
import com.jiaoay.easy_dev_toolbox.widget.interactions.HoverableRow
import dev.vicart.compose.material.symbols.MaterialSymbols

/**
 * 主题橱窗族：设置页「配色方案」卡的预设分组网格/单列行、样张条带、迷你窗条带与
 * 自定义入口件。装配段在 [PageSettings]（经同包 internal 可见性消费本族）；
 * 图案采样消费位封闭集由 SpecimenPatternConsumersGuardTest 按本文件路径锚定
 * （样张 base 块外的图案消费即红）。
 */
@Composable
internal fun ThemeOptionItem(
    name: String,
    selected: Boolean,
    onSelected: () -> Unit,
    leading: @Composable () -> Unit,
) {
    HoverableRow(
        modifier = Modifier
            .fillMaxWidth()
            .clip(AppShapes.medium)
            .border(
                // .theme-card inset 1px hairline / .on inset 2px primary
                width = if (selected) 2.dp else 1.dp,
                color = if (selected) MaterialTheme.colorScheme.primary
                else MaterialTheme.colorScheme.onSurface.copy(alpha = AppAlpha.borderHairlineLight),
                shape = AppShapes.medium
            )
            .clickable(onClick = onSelected)
            .padding(horizontal = Dimens.space16, vertical = Dimens.space14), // 14dp 归档 space14 token
        shape = AppShapes.medium,
    ) {
        leading()
        Spacer(modifier = Modifier.width(Dimens.space16))
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = name,
                style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.onBackground
            )
        }
        // CHECK 恒渲染占位（ThemeCard 同款契约）：恒渲染钉住字形自然行盒 24sp
        // （=iconLarge 20sp×1.2em，BasicText 无 lineHeight 回落字体 metrics）——条件渲染
        // 会令选中行 +2px（行盒压过 leading 色点双条 22dp）。未选中 = Color.Transparent
        // 占位 + 无读屏语义（防读屏把「已选」读进每张未选行）；选中可判定性由 2dp
        // primary 描边承担。
        AppSymbolIcon(
            icon = MaterialSymbols.CHECK,
            contentDescription = if (selected) "已选" else null,
            tint = if (selected) MaterialTheme.colorScheme.primary else Color.Transparent,
            size = Dimens.iconLarge
        )
    }
}

/** 预设分组标题行：labelMedium + onSurfaceFaint 弱字——轻量分组导航语义，网格/单列回落两态共用 */
@Composable
internal fun ThemeGroupLabel(label: String) {
    Text(
        text = label,
        style = MaterialTheme.typography.labelMedium,
        color = LocalToolboxSurfaceColors.current.onSurfaceFaint,
        modifier = Modifier.padding(top = Dimens.space4),
    )
}

/**
 * 主题预设网格卡：套级三块构成样张 + 名称行竖排（.theme-card 样张形态）——
 * [ThemeSpecimenBand]（亮/暗双条，每条 = 底/第二声部/强调三块连续色带，面积 3/2/1 递减）+
 * 名称行 + CHECK 恒渲染右缘（未选中透明占位，行盒契约见行内注释）；选中态 2dp 描边
 * （.theme-card.on inset 2px primary）配方不变。
 * 为什么三块取声部色域而非骨架三段：97.9 锚轨非涂装套的骨架段（背景/卡/深卡）近白、
 * 对白纸卡不可分——声部色域让非涂装套三块也两两可辨，兼「不对味」套指认诊断面
 * （取色契约见 [AppThemeSpec.specimenTriad]）。
 */
@Composable
internal fun ThemeCard(
    spec: AppThemeSpec,
    selected: Boolean,
    onSelected: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val surfaceColors = LocalToolboxSurfaceColors.current
    HoverableRow(
        modifier = modifier
            .clip(AppShapes.medium)
            // 选中卡面 = surfaceColors.selectedContainer（选中容器身份槽整面承载，
            // 与 SpaceBar 选中行/当前页签同源）；常态嵌套底 = 页面基底实底
            .background(
                if (selected) surfaceColors.selectedContainer else MaterialTheme.colorScheme.surface,
                AppShapes.medium,
            )
            .border(
                // 选中 2dp 描边与常态 1dp hairline 都落在选中容器配套链上——选中描边
                // 走 onSelectedContainer（两态同值锚，选中面对描边自成边界；primary 描边
                // 对实底选中面无配套保证）
                width = if (selected) 2.dp else 1.dp,
                color = if (selected) surfaceColors.onSelectedContainer
                else surfaceColors.borderHairline,
                shape = AppShapes.medium
            )
            .clickable(onClick = onSelected)
            .padding(horizontal = Dimens.space12, vertical = Dimens.space10),
        shape = AppShapes.medium,
    ) {
        Column(modifier = Modifier.fillMaxWidth()) {
            ThemeSpecimenBand(spec = spec)
            Spacer(modifier = Modifier.height(Dimens.space8))
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = spec.displayName,
                    style = MaterialTheme.typography.bodyMedium,
                    // 选中卡面文字走 onSelectedContainer（选中面配套字链）——onBackground 夜战
                    // 表两态异值，直取会在选中面上落不可读色
                    color = if (selected) surfaceColors.onSelectedContainer
                    else MaterialTheme.colorScheme.onBackground,
                    modifier = Modifier.weight(1f),
                )
                // CHECK 恒渲染占位契约：恒渲染钉住字形自然行盒 24sp（=iconLarge 20sp×
                // 1.2em——BasicText 无 lineHeight 回落字体 metrics），条件渲染会令选中卡
                // 名称行 +6px，经 chunked(2) 网格行取 max 传导为选中行以下整体位移。
                // 未选中 = Color.Transparent 占位 + 无读屏语义（防读屏把「已选」读进每张
                // 未选卡）；选中可判定性由 selectedContainer 卡面 + 2dp 描边双信号承担。
                AppSymbolIcon(
                    icon = MaterialSymbols.CHECK,
                    contentDescription = if (selected) "已选" else null,
                    // CHECK 与卡面文字同链（onSelectedContainer）——选中面上的一切前景
                    // 统一取配套字槽，不直取 onSurface；未选中透明纯为布局占位
                    tint = if (selected) surfaceColors.onSelectedContainer else Color.Transparent,
                    size = Dimens.iconLarge,
                )
            }
        }
    }
}

/**
 * 三块构成样张条带组：亮/暗两态各一条（每条 = 底/第二声部/强调三段连续色带，weight 3/2/1
 * 面积递减）——双态并置让 pageBaseDark 主权套的暗态身份在橱窗有像素在场，单亮条会使
 * 暗态主权与配色判定都被亮态取证面偏置污染。样张表达面积构成层级（底主导、强调小面积），
 * 不是等权色板。单条 hairline 描边 + AppShapes.small 圆角：白纸锚轨套的底块近白，无描边
 * 即在卡面上隐形（ColorDot borderSubtle 同理）。
 * 取色单源 [AppThemeSpec.specimenTriad]（与实机 colorSet 同源——直读 seed 会漏掉
 * 染色/覆写套的实值）。套级图案槽在场时底块叠加纹理采样（市松棋盘/警示斜纹——采样与
 * 绘制单源 ThemeSpecimen.kt；图案只进 base 块，消费位封闭集由 composeApp 源码锚守护）。
 */
@Composable
private fun ThemeSpecimenBand(spec: AppThemeSpec) {
    val lightTriad = remember(spec) { spec.specimenTriad(dark = false) }
    val darkTriad = remember(spec) { spec.specimenTriad(dark = true) }
    val lightPattern = remember(spec) { spec.specimenPatternSample(dark = false) }
    val darkPattern = remember(spec) { spec.specimenPatternSample(dark = true) }
    Column(
        modifier = Modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(Dimens.space2),
    ) {
        ThemeSpecimenStrip(triad = lightTriad, pattern = lightPattern)
        ThemeSpecimenStrip(triad = darkTriad, pattern = darkPattern)
    }
}

/**
 * 单条三块构成样张：底/第二声部/强调三段连续色带，weight 3/2/1 面积递减（三块语义与
 * 取色契约见 [ThemeSpecimenBand]）。pattern 非空时底块叠加纹理采样（null = 纯色
 * 逐位不变；图案槽申请制与消费位封闭集契约见 ThemeSlotOverrides.surfacePattern）。
 */
@Composable
private fun ThemeSpecimenStrip(triad: ThemeSpecimenTriad, pattern: ThemeSpecimenPatternSample?) {
    val hairline = LocalToolboxSurfaceColors.current.borderHairline
    Row(
        modifier = Modifier
            .fillMaxWidth()
            // 单条 15dp（=themeDotSize×1.5）：双条 + space2 分隔总高 32dp，比单亮条 30dp
            // 仅增 2dp（ThemeColorDots 深浅双条先例同构）
            .height(Dimens.themeDotSize * 1.5f)
            .clip(AppShapes.small)
            .border(Dimens.glassStrokeHairline, hairline, AppShapes.small),
    ) {
        Box(
            Modifier
                .weight(3f)
                .fillMaxHeight()
                .background(triad.base)
                // 图案采样只进底块（消费位封闭集 = 此处）：采样/绘制单源在 ThemeSpecimen.kt
                .drawBehind { if (pattern != null) drawSpecimenPatternSample(pattern) },
        )
        Box(
            Modifier
                .weight(2f)
                .fillMaxHeight()
                .background(triad.secondVoice),
        )
        Box(
            Modifier
                .weight(1f)
                .fillMaxHeight()
                .background(triad.accent),
        )
    }
}

/**
 * 主题迷你窗条带组：按主题规格（seed + style 档位 + skeletonStain 染色/双种子）派生的
 * 深浅两条预览条——每条 = 骨架条带（背景/卡片/深卡三段色温阶梯，骨架身份首次在色卡可见）
 * + 主/第二/第三强调三圆点（多种子显形）。与实机配色同源（必须走 spec.colorSet 派生——
 * 直调 dynamicColorScheme 不传 style 会预览偏灰、与实机不符）。
 * 消费点：Compact 单列回落行与自定义项 leading（网格 ThemeCard 用三块样张
 * [ThemeSpecimenBand]——骨架段对白纸卡不可分，网格档取声部色域样张）。
 */
@Composable
internal fun ThemeColorDots(spec: AppThemeSpec) {
    val lightScheme = remember(spec) { spec.colorSet(dark = false) }
    val darkScheme = remember(spec) { spec.colorSet(dark = true) }
    // .theme-dots：点 10px（themeDotSize）、段间 gap 2px（space2）；
    // 深浅两条纵向排布，总高与旧两排 6 点相同（10+2+10）
    Column(verticalArrangement = Arrangement.spacedBy(Dimens.space2)) {
        ThemeMiniWindowStrip(scheme = lightScheme)
        ThemeMiniWindowStrip(scheme = darkScheme)
    }
}

/**
 * 单条迷你窗条带：骨架条带宽 6×themeDotSize、高 themeDotSize，三段 weight 2.5/1.5/1
 * 直出背景→卡片→深卡的色温阶梯；条带外 space2 间隔 + 三强调圆点（CircleShape 先例 =
 * CustomThemeItem 占位点）。
 */
@Composable
private fun ThemeMiniWindowStrip(scheme: ColorScheme) {
    val hairline = LocalToolboxSurfaceColors.current.borderHairline
    Row(
        modifier = Modifier.height(Dimens.themeDotSize),
        horizontalArrangement = Arrangement.spacedBy(Dimens.space2),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Row(
            modifier = Modifier
                .width(Dimens.themeDotSize * 6)
                .clip(AppShapes.small)
                .border(Dimens.glassStrokeHairline, hairline, AppShapes.small),
        ) {
            Box(
                Modifier
                    .weight(2.5f)
                    .fillMaxHeight()
                    .background(scheme.background),
            )
            Box(
                Modifier
                    .weight(1.5f)
                    .fillMaxHeight()
                    .background(scheme.surfaceContainerLow),
            )
            Box(
                Modifier
                    .weight(1f)
                    .fillMaxHeight()
                    .background(scheme.surfaceContainerHigh),
            )
        }
        ColorDot(scheme.primary)
        ColorDot(scheme.secondary)
        ColorDot(scheme.tertiary)
    }
}

/** 自定义主题入口卡片：已设置时展示种子色预览色点，未设置时展示加号占位 */
@Composable
internal fun CustomThemeItem(
    currentScheme: AppThemeSpec,
    onClick: () -> Unit,
) {
    val custom = currentScheme as? AppThemeSpec.Custom
    ThemeOptionItem(
        name = if (custom != null) "自定义" else "自定义（点击挑选颜色）",
        selected = custom != null,
        onSelected = onClick,
        leading = {
            if (custom != null) {
                ThemeColorDots(custom)
            } else {
                Box(
                    modifier = Modifier
                        .size(Dimens.iconHero)
                        .clip(CircleShape)
                        .background(MaterialTheme.colorScheme.surfaceVariant),
                    contentAlignment = Alignment.Center,
                ) {
                    AppSymbolIcon(
                        icon = MaterialSymbols.ADD,
                        contentDescription = null,
                        size = Dimens.iconLarge,
                    )
                }
            }
        },
    )
}

@Composable
private fun ColorDot(color: Color) {
    Box(
        modifier = Modifier
            // .theme-dots：点 10px
            .size(Dimens.themeDotSize)
            .clip(CircleShape)
            .background(color)
            // 底色主权套的强调点是新形态的近白/荧光档（昼战 primary #EDE6FF 对浅纸 1.15:1）
            // ——无描边即色卡上隐形；borderSubtle（卡默认描边档）给 10px 圆点可辨边界
            .border(Dimens.glassStrokeHairline, LocalToolboxSurfaceColors.current.borderSubtle, CircleShape)
    )
}
