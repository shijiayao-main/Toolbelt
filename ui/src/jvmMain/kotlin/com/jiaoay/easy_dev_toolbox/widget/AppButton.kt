package com.jiaoay.easy_dev_toolbox.widget

import androidx.compose.foundation.hoverable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TooltipAnchorPosition
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import com.jiaoay.easy_dev_toolbox.core.theme.AppShapes
import com.jiaoay.easy_dev_toolbox.core.theme.Dimens
import com.jiaoay.easy_dev_toolbox.widget.interactions.HoverTooltipBox
import dev.vicart.compose.material.symbols.MaterialSymbols

/**
 * P3 交互重设计 · 进行中反馈唯一规范（AppButton 单件 + 统一停止钮）。
 *
 * 响应时间三阈值（NN/g 0.1s/1s/10s）的唯一形态表：
 * - < 100ms：调用方直接用普通按钮（**零反馈不闪圈**——不进本组件）
 * - 100ms–1s：[busy] = true → 按钮内 [Dimens.progressInline] 圈 + 进行时措辞 + disabled
 * - 1s–10s：同上 + [cancel] 非空 → 旁侧渲染统一停止钮（STOP_CIRCLE + 「停止」+ error tint）
 * - \> 10s：确定性总量的任务走 determinate 进度行（TaskProgressRow 规划态未落地，
 *    现状按模型下载先例 LinearProgressIndicator），不进本组件
 *
 * 三语义参数：
 * - [kind]：Primary（实底）/ Secondary（描边）/ Destructive（error 实底）/
 *   Hot（.chipbtn.hot——primary 字 + primary×0.10 洗底胶囊，无描边）
 * - [disabledReason]：非空 → 置灰 + tooltip 说明原因与解除方式（禁止静默置灰——
 *   计划校验时机规则：复合表单提交须报原因而非哑按钮）
 * - [cancel]：非空 → busy 态旁出现统一停止钮（淘汰 5 种旧停止形态的收口点）
 *
 * 唯一例外（计划裁定）：ChatComposer 发送↔停止翻钮保留（输入框内 affordance，
 *    chat 惯例优先）——视觉与停止态同源（STOP_CIRCLE + error tint），docs 登记。
 */
enum class AppButtonKind { Primary, Secondary, Destructive, Hot }

@Composable
fun AppButton(
    label: String,
    busy: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    busyLabel: String? = null,
    kind: AppButtonKind = AppButtonKind.Primary,
    enabled: Boolean = true,
    disabledReason: String? = null,
    cancel: (() -> Unit)? = null,
    leadingIcon: String? = null,
    tooltipPositioning: TooltipAnchorPosition? = null,
) {
    val effectiveEnabled = enabled && !busy && disabledReason == null
    // tooltip 文案：置灰原因 > 进行中措辞（busy 但可解释时也提示，免感知卡顿）
    val tip = disabledReason ?: if (busy) busyLabel ?: "$label 中…" else null

    Row(
        modifier = modifier,
        horizontalArrangement = Arrangement.spacedBy(Dimens.space8),
    ) {
        if (tip != null) {
            // 配方单源 = interactions.HoverTooltipBox（hover 检测必须走 hoverable：
            // disabled 按钮不向 interactionSource 发 HoverInteraction，置灰原因
            // tooltip 才可达——契约与配方详见该件 KDoc）。
            // [tooltipPositioning]：贴窗缘落位的调用点（如投屏窗顶栏右端钮组）
            // 须显式给 Below/Above——plain 默认定位器无水平钳制，长文案越出窗缘
            HoverTooltipBox(
                tooltip = { Text(text = tip, style = MaterialTheme.typography.labelSmall) },
                positioning = tooltipPositioning,
            ) { interactionSource ->
                // modifier 必须传到按钮本体——TooltipBox 分支若把 modifier 留在外层 Row，
                // fillMaxWidth 全失效（busy 按钮仅内容宽贴左）。两分支统一经本链下发。
                // hover 检测：hoverable 挂本 Row（不受按钮 enabled 门控——disabled
                // 钮 hover 事件由此到达 source）；interactionSource 仍透传按钮本体
                // （enabled 态 press/hover 语义照常）。
                Row(
                    modifier = Modifier
                        .weight(1f, fill = false)
                        .hoverable(interactionSource = interactionSource),
                ) {
                    AppButtonInner(
                        label = label,
                        busy = busy,
                        busyLabel = busyLabel,
                        kind = kind,
                        enabled = effectiveEnabled,
                        onClick = onClick,
                        interactionSource = interactionSource,
                        leadingIcon = leadingIcon,
                    )
                }
            }
        } else {
            AppButtonInner(
                label = label,
                busy = busy,
                busyLabel = busyLabel,
                kind = kind,
                enabled = effectiveEnabled,
                onClick = onClick,
                leadingIcon = leadingIcon,
                modifier = Modifier.weight(1f, fill = false),
            )
        }

        // ── 统一停止钮（唯一形态：STOP_CIRCLE + 「停止」+ error tint）──
        if (busy && cancel != null) {
            TextButton(onClick = cancel) {
                AppSymbolIcon(
                    icon = MaterialSymbols.STOP_CIRCLE,
                    contentDescription = "停止",
                    size = Dimens.iconSmall,
                    tint = MaterialTheme.colorScheme.error,
                )
                Text("停止", color = MaterialTheme.colorScheme.error)
            }
        }
    }
}

/** 按钮本体：kind 分派实底/描边形态；busy 时行内圈 + 进行时措辞（modifier 直达本体） */
@Composable
private fun AppButtonInner(
    label: String,
    busy: Boolean,
    busyLabel: String?,
    kind: AppButtonKind,
    enabled: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    interactionSource: MutableInteractionSource? = null,
    leadingIcon: String? = null,
) {
    if (kind == AppButtonKind.Primary) {
        // .fbtn 几何三件——fs-13/600（bodyMedium + SemiBold）、
        // contentPadding 6×16（sp-6/sp-16 token 直译）、r-pill 胶囊。
        // 高度不强制（稿 ~32px = 内容撑出——6px 纵 padding + 13px 字行高 18 ≈ 30px）。
        fbtnButton(
            onClick = onClick,
            enabled = enabled,
            interactionSource = interactionSource,
            modifier = modifier,
            container = MaterialTheme.colorScheme.primary,
            content = MaterialTheme.colorScheme.onPrimary,
        ) { ButtonContent(label, busy, busyLabel, leadingIcon) }
    } else if (kind == AppButtonKind.Destructive) {
        // 危险钮 = **实底 error 胶囊主钮**（与 AppConfirmDialog isDangerous 分支同配方——
        // containerColor=error / contentColor=onPrimary；「OutlinedButton + error 字」
        // 是危险伪装成次钮）
        fbtnButton(
            onClick = onClick,
            enabled = enabled,
            interactionSource = interactionSource,
            modifier = modifier,
            container = MaterialTheme.colorScheme.error,
            content = MaterialTheme.colorScheme.onError,
        ) { ButtonContent(label, busy, busyLabel, leadingIcon) }
    } else if (kind == AppButtonKind.Hot) {
        // .chipbtn.hot：primary 字 + primary×0.10 洗底 + r-pill 全圆——
        // 无描边（M3 OutlinedButton 默认描边与 chipbtn 形态不符）；busy 圈随字 primary。
        // Row import 是 layout.Row，M3 Button 须全限定名（本文件 Row 名被遮蔽）
        fbtnButton(
            onClick = onClick,
            enabled = enabled,
            interactionSource = interactionSource,
            modifier = modifier,
            container = MaterialTheme.colorScheme.primary
                .copy(alpha = com.jiaoay.easy_dev_toolbox.core.theme.AppAlpha.hotWash),
            content = MaterialTheme.colorScheme.primary,
        ) { ButtonContent(label, busy, busyLabel, leadingIcon) }
    } else {
        // Secondary 同走 fbtn 几何（.fbtn 通档——描边承载次钮分域；
        // 字形经 ButtonContent 统一 fs-13/600）
        OutlinedButton(
            onClick = onClick,
            enabled = enabled,
            interactionSource = interactionSource,
            modifier = modifier,
            contentPadding = fbtnContentPadding(),
            colors = androidx.compose.material3.ButtonDefaults.outlinedButtonColors(
                contentColor = MaterialTheme.colorScheme.primary,
            ),
        ) {
            ButtonContent(label, busy, busyLabel, leadingIcon)
        }
    }
}

/**
 * .fbtn 几何三件共享（fs-13/600 + padding 6×16 + r-pill）——
 * Primary/Destructive/Hot 三实底分支共用容器；contentPadding 抽函数供 Secondary
 * OutlinedButton 同档复用。
 */
private fun fbtnContentPadding() = androidx.compose.foundation.layout.PaddingValues(
    horizontal = Dimens.space16,
    vertical = Dimens.space6,
)

@Composable
private fun fbtnButton(
    onClick: () -> Unit,
    enabled: Boolean,
    interactionSource: MutableInteractionSource?,
    modifier: Modifier,
    container: androidx.compose.ui.graphics.Color,
    content: androidx.compose.ui.graphics.Color,
    buttonContent: @Composable androidx.compose.foundation.layout.RowScope.() -> Unit,
) {
    androidx.compose.material3.Button(
        onClick = onClick,
        enabled = enabled,
        interactionSource = interactionSource,
        modifier = modifier,
        shape = AppShapes.capsule,
        colors = androidx.compose.material3.ButtonDefaults.buttonColors(
            containerColor = container,
            contentColor = content,
        ),
        contentPadding = fbtnContentPadding(),
    ) {
        buttonContent()
    }
}

/** 按钮内容：busy 时行内圈 + 进行时措辞；否则前导图标（可选）+ 静态 label */
@Composable
private fun ButtonContent(label: String, busy: Boolean, busyLabel: String?, leadingIcon: String?) {
    if (busy) {
        CircularProgressIndicator(
            modifier = Modifier.size(Dimens.progressInline),
            strokeWidth = Dimens.strokeThin,
        )
    } else if (leadingIcon != null) {
        AppSymbolIcon(
            icon = leadingIcon,
            contentDescription = null,
            size = Dimens.iconSmall,
        )
    }
    Text(
        busyLabel ?: label,
        // .fbtn 按钮字档 = bodyMedium(13sp) +
        // SemiBold（labelLarge 14sp/Medium 与按钮档漂移，勿取）
        style = MaterialTheme.typography.bodyMedium.copy(
            fontWeight = FontWeight.SemiBold,
        ),
    )
}
