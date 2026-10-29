package com.jiaoay.easy_dev_toolbox.core.theme

import androidx.compose.runtime.Composable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color

/**
 * 昼战形态判定与语义承载 helper（电光紫 主题昼战消费位核对的呈现层机械）。
 *
 * 昼战形态 = 亮模式（dark=false）且套声明了底色参数 [AppThemeSpec.pageBaseLight]——
 * 页面底是中明度高饱和深底（L*50.3），而 success/warning/error/info/errorFixed 等
 * 恒定语义槽亮值是为「浅纸底」设计的深档，直落昼底/昼卡对比度 1.1~1.5，
 * 全破图形线 3.0 与正文线 4.5（设计文档 design-doc.md §5.3/§7.1 实算）。
 *
 * 呈现路径裁定（C10-C13 立场不变：恒常量与语义槽色值一律不动，只修呈现配对）：
 * - 文字（4.5 线）→ 坐对应语义 container 面（槽对呈现，PillLabel/StatusBanner 先例）：
 *   warning×warningContainer 4.90 / errorFixed×errorContainerFixed 5.14 /
 *   success×successContainer 5.09 / info×infoContainer 4.93；
 * - 图形点/段/图标（3.0 线）→ [dayBattleTint] 换对应 container 档色
 *   （对昼底 3.4~3.5 / 对昼卡 4.4+，同族语义面档，值源全为既有 token）。
 *
 * 非昼战形态（其余 25 套 + EVA 夜战）消费点走原呈现，逐位不变。
 * 判定按底色参数存在性，不按套名硬编码（域拆纪律——design-doc.md §4.3 改法④）。
 *
 * 缺省回落 false/null = 非昼战呈现（现状）：未装配场景（@Preview 等）安全回落，
 * 不像 [com.jiaoay.easy_dev_toolbox.core.theme.LocalIsDarkTheme] 那样 fail fast——
 * 昼战承载缺席只会退回原呈现（夜战/浅纸套本就直落达标），不会产生错误配色。
 */
val LocalDayBattle = staticCompositionLocalOf { false }

/**
 * 昼战红绿灯底衬（design-doc.md §5.3#1）：昼战形态 = 昼战井色复用（套级 consoleBg 直配值，
 * AppTheme 装配点单源下发）；产品语义三色 #FF5F57/#FEBC2E/#28C840 直落昼底
 * 1.48/2.63/1.99 全破图形线，坐近黑底衬后 6.11/10.83/8.20 达标。
 * 非昼战 = null（圆点裸放现状——夜战直落夜底 6.17/10.92/8.27 本就达标，不消费底衬）。
 */
val LocalTrafficBackdrop = staticCompositionLocalOf<Color?> { null }

/**
 * 昼战图形档承载：昼战形态返回 [container] 档色（状态点/条形段/图标的 3.0 线承载），
 * 非昼战原样返回 [content]。文字（4.5 线）不经此 helper——文字一律坐 container 面
 * （槽对呈现，见 [LocalDayBattle] KDoc）；[container] 传 null 档的调用方不应调用本 helper。
 */
@Composable
fun dayBattleTint(content: Color, container: Color): Color =
    if (LocalDayBattle.current) container else content
