package com.jiaoay.easy_dev_toolbox.widget

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.calculateEndPadding
import androidx.compose.foundation.layout.calculateStartPadding
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.LayoutDirection
import com.jiaoay.easy_dev_toolbox.core.theme.Dimens
import com.jiaoay.easy_dev_toolbox.core.theme.LocalScaffoldGlassInsets
import com.jiaoay.easy_dev_toolbox.core.theme.plus

/**
 * 页面密度档：
 * - [Comfortable] = screenPadding 24 / screenSpacing 16（**默认，现状取值**）；
 * - [Dense] = space16 / space12——信息密度优先页（Shell 工具 / 日志工作台 / 流程三页
 *   原裸用 PageHeader 的「密度例外」自样板）。
 *
 * 零回归配方：默认值即现状，存量调用点零改动；[Dense] 的 16/12 与三密度页的
 * 布局值一一对应（显式传参，视觉无变化）。
 */
enum class PageDensity(val padding: Dp, val spacing: Dp) {
    Comfortable(Dimens.screenPadding, Dimens.screenSpacing),
    Dense(Dimens.space16, Dimens.space12),
}

/**
 * 页面统一骨架：密度档内边距 + 顶层间距 + [PageHeader] 标题栏 + 内容区。
 *
 * 各功能页统一入口，替代页面手写的
 * `Column(fillMaxSize().padding(Dimens.screenPadding), spacedBy(Dimens.screenSpacing)) { PageHeader(...) ... }`
 * 样板。
 *
 * P0 扩参（全部带默认值 = 现状，存量调用零改动）：
 * - [density]：密度档；三页「信息密度例外」迁移后「裸用 PageHeader」归零；
 * - [scrollable]：true 时骨架自带 `verticalScroll` + 右缘桌面滚动条（整页滚动走此开关，
 *   页面不得再自挂 verticalScroll——单点化，修 VoicePage 内容超屏被裁剪）；
 * - [statusSlot]：标题下一行 labelSmall 页级状态摘要（可选；状态栏之外的页内状态语言）。
 *
 * 内容区恒满可用宽（不设限宽不居中）：Wide 档「限宽 1200dp + 居中」旧形态已废除
 * （大屏差异化 UI 未做之前，居中悬浮即缺陷；差异化留待
 * 后续真设计，不做假适配）。
 *
 * 需要内容区自滚动的吸顶页显式传 [PageScaffold.headerSticky] = true（滚动应放在 content 内部），
 * 此时 [modifier] 不得再带 `verticalScroll`。
 *
 * @param title 页面标题（透传 [PageHeader]，headlineSmall）
 * @param modifier 外部修饰（附加于骨架最外层）
 * @param actions 标题栏右侧操作区（透传 [PageHeader]）
 * @param density 页面密度档（默认 Comfortable = 现状 24/16）
 * @param headerSticky 吸顶模式：标题栏不随内容滚动，content 获得**有界高度**自行滚动
 * @param bottomPenetration 底部真穿透（仅 [headerSticky] 吸顶页有意义）：true 时
 *   包裹层**移除底部玻璃带让位**——内容列直达窗口底缘，页内滚动容器须自行以
 *   `LocalScaffoldGlassInsets` 底值作滚动 contentPadding（内容滑入状态栏玻璃下被折射，
 *   见 LarkBridgePage 页签滚动容器）；默认 false = 包裹层全让位（既有吸顶页零几何变化）
 * @param scrollable 骨架自带整页滚动 + 桌面滚动条（默认 false = 现状无滚动）
 * @param statusSlot 标题下页级状态摘要行（可选）
 * @param content 标题栏之下的页面内容
 */
@Composable
fun PageScaffold(
    title: String,
    modifier: Modifier = Modifier,
    actions: @Composable RowScope.() -> Unit = {},
    density: PageDensity = PageDensity.Comfortable,
    headerSticky: Boolean = false,
    bottomPenetration: Boolean = false,
    scrollable: Boolean = false,
    statusSlot: @Composable (() -> Unit)? = null,
    content: @Composable ColumnScope.() -> Unit,
) {
    // 嵌套滚动断言语义：headerSticky 与 scrollable 都给内容区提供滚动责任，二选一
    val outer = if (scrollable && !headerSticky) {
        Modifier.verticalScroll(rememberScrollState())
    } else {
        Modifier
    }

    // 玻璃带让位：窗级玻璃 chrome（tab 条/dock/状态栏）的占位由 PageHome
    // 经 LocalScaffoldGlassInsets 下发，与密度边距逐边相加。scrollable 页 padding 挂在
    // verticalScroll 之后 = 滚动 contentPadding 语义（内容滚出视口时穿过让位区、滑进
    // 玻璃带被 blur 折射——与 CSS 滚动容器内 padding 行为等价）；
    // 非 scrollable 页 = 普通包裹层让位（页头不进玻璃带、fade 带不错位）。
    //
    // 吸顶页 + bottomPenetration（真穿透，opt-in）：顶部/侧边让位仍由包裹层承担
    // （吸顶页头清 chrome），**底部让位从包裹层移除**——吸顶页内容列直达窗口底缘，
    // 页内滚动容器以 contentPadding 消费 LocalScaffoldGlassInsets 底值，内容滑入
    // 状态栏玻璃下被折射；消费点在页内（ opt-in 的原因：页内滚动容器归页面所有，
    // 不开穿透的吸顶页没有消费者，底部让位移除会让末行内容藏进状态栏后）。
    val glassInsets = LocalScaffoldGlassInsets.current
    val layoutDirection = LocalLayoutDirection.current
    val basePadding = PaddingValues(horizontal = density.padding, vertical = density.padding).plus(glassInsets)
    val combinedPadding = if (headerSticky && bottomPenetration) {
        PaddingValues(
            start = basePadding.calculateStartPadding(layoutDirection),
            top = basePadding.calculateTopPadding(),
            end = basePadding.calculateEndPadding(layoutDirection),
            bottom = 0.dp,
        )
    } else {
        basePadding
    }

    Column(
        modifier = modifier
            .fillMaxSize()
            .then(outer)
            .padding(combinedPadding),
        verticalArrangement = Arrangement.spacedBy(density.spacing),
    ) {
        PageHeader(title = title, actions = actions)
        statusSlot?.invoke()
        // 内容区恒满宽（Wide 限宽居中已废除，见类 KDoc）；吸顶分支 content 兄弟
        // 间距由本 weight(1f) Column 的 spacedBy 承载，非吸顶分支由外层 spacedBy
        // 承载——两路径语义对称（SegmentedGapGuardTest 像素钉死）
        if (headerSticky) {
            Column(
                modifier = Modifier.weight(1f),
                verticalArrangement = Arrangement.spacedBy(density.spacing),
                content = content,
            )
        } else {
            content()
        }
    }
}
