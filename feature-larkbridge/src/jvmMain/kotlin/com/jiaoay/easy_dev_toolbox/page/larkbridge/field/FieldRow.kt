package com.jiaoay.easy_dev_toolbox.page.larkbridge.field

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.RectangleShape
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import com.jiaoay.easy_dev_toolbox.core.theme.AppAlpha
import com.jiaoay.easy_dev_toolbox.core.theme.AppShapes
import com.jiaoay.easy_dev_toolbox.core.theme.Dimens
import com.jiaoay.easy_dev_toolbox.core.theme.LocalEasyToolboxColors
import com.jiaoay.easy_dev_toolbox.core.theme.LocalToolboxSurfaceColors
import com.jiaoay.easy_dev_toolbox.widget.AppSymbolIcon
import com.jiaoay.easy_dev_toolbox.widget.AppSwitchRow
import com.jiaoay.easy_dev_toolbox.widget.ChoiceField
import com.jiaoay.easy_dev_toolbox.widget.CommitTextField
import com.jiaoay.easy_dev_toolbox.widget.PillLabel
import com.jiaoay.easy_dev_toolbox.widget.TagsField
import com.jiaoay.easy_dev_toolbox.widget.interactions.HoverableRow
import com.jiaoay.easy_dev_toolbox.widget.pickDirectory
import com.jiaoay.lark.bridge.config.BridgeConfig
import com.jiaoay.lark.bridge.config.BridgeField
import com.jiaoay.lark.bridge.config.Editor
import dev.vicart.compose.material.symbols.MaterialSymbols
import kotlinx.coroutines.launch

/**
 * 配置字段行共用渲染器家族：[FieldRow] 主行 + 行内三段与 modified 装饰件 + Number/Directory
 * 两编辑器包装。消费方 = 同包编排层（ConfigFieldRenderer 的快速上手卡/五分区/过滤结果三路）
 * 与 BotCapabilityCard（机器人主卡身份字段行），故 [FieldRow] internal 跨文件可见。
 */
@Composable
internal fun FieldRow(
    field: BridgeField<Any?>,
    config: BridgeConfig,
    issue: String?,
    modified: Boolean,
    onCommit: (Any?) -> Unit,
    onResetToDefault: ((BridgeField<Any?>) -> Unit)? = null,
    contextLabel: String? = null,
    focusRequester: FocusRequester? = null,
) {
    val isError = issue != null
    // hint 作行中段弱说明（.frow .hint 位置）；有校验错误时错误消息优先且
    // 走 hint-err 红字（.hint-err font-size fs-11 color error）
    val restartPill = field.applyMode == com.jiaoay.lark.bridge.config.ApplyMode.RESTART

    // 「恢复默认」回调：缺省 = 提交字段的默认值（与 modified 判定同源 DefaultBridgeConfig.default()，
    // 提交后行自动退 decor）；显式传 null 语义不需要——参数保留为调用方覆盖缝（当前无覆盖方）
    val resetAction: (BridgeField<Any?>) -> Unit =
        onResetToDefault ?: { f -> onCommit(f.get(DefaultBridgeConfigDefault)) }

    // 行主体（8 编辑器穷尽 when）——modified 装饰包在外层，编辑器组合零改动；
    // focusRequester 经外传 modifier 挂链首（CommitTextField 内部 modifier.fillMaxWidth()
    // 在其后，focusRequester 挂 OutlinedTextField 生效），不改 :ui 的 CommitTextField 本体。
    // 编辑器 label 恒 null（外置——行容器 .lab 段承载，横排三段）。
    val fieldModifier = focusRequester?.let { Modifier.focusRequester(it) } ?: Modifier
    val editorRow: @Composable () -> Unit = {
        when (val editor = field.editor) {
            Editor.Text -> CommitTextField(
                label = null,
                value = field.get(config) as String,
                onCommit = onCommit,
                isError = isError,
                modifier = fieldModifier,
            )

            Editor.Multiline -> CommitTextField(
                label = null,
                value = field.get(config) as String,
                onCommit = onCommit,
                isError = isError,
                singleLine = false,
                minLines = 3,
                modifier = fieldModifier,
            )

            Editor.Secret -> {
                // 主动 reveal（「全掩码+主动 reveal」契约三处背书：ConfigCheckFormatter
                // 掩码输出 / 本文件 Editor.Secret 语义 / docs/pages/lark-bridge.md——密钥
                // 自查是真实排障需求）。eye 钮恒 enabled：不挂 hover 门控（disabled 钮
                // 不进焦点链，键盘用户零 reveal 通路——HoverableRow 契约同源教训）
                var showSecret by remember(field.id) { mutableStateOf(false) }
                CommitTextField(
                    label = null,
                    value = field.get(config) as String,
                    onCommit = onCommit,
                    isError = isError,
                    visualTransformation = if (showSecret) VisualTransformation.None else PasswordVisualTransformation(),
                    modifier = fieldModifier,
                    trailingIcon = {
                        IconButton(onClick = { showSecret = !showSecret }) {
                            AppSymbolIcon(
                                icon = if (showSecret) MaterialSymbols.VISIBILITY_OFF else MaterialSymbols.VISIBILITY,
                                contentDescription = if (showSecret) "隐藏密钥" else "显示密钥",
                                size = Dimens.iconSmall,
                                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    },
                )
            }

            Editor.Switch -> AppSwitchRow(
                label = "",
                checked = field.get(config) as Boolean,
                onCheckedChange = onCommit,
                rowClickable = false,
            )

            Editor.Directory -> DirectoryField(
                label = field.label,
                value = field.get(config) as String,
                onCommit = onCommit,
                isError = isError,
                titleOnly = true,
                focusRequester = focusRequester,
            )

            is Editor.Number -> NumberField(
                field = field,
                min = editor.min,
                max = editor.max,
                displayScale = editor.displayScale,
                unitSuffix = editor.unitSuffix,
                config = config,
                isError = isError,
                onCommit = onCommit,
            )

            is Editor.Choice -> ChoiceField(
                label = null,
                options = editor.options,
                selected = field.get(config) as String,
                onSelect = onCommit,
                labels = editor.labels,
            )

            is Editor.Tags -> {
                @Suppress("UNCHECKED_CAST")
                val tags = field.get(config) as List<String>
                TagsField(
                    label = null,
                    tags = tags,
                    onTagsChange = onCommit,
                    allowPickDirectory = editor.pickDirectory,
                    focusRequester = focusRequester,
                )
            }
        }
    }

    // ── 横排三段行容器（.frow：lab 190px 定宽 + hint 中段 +
    // ctl 右缘 flex:1；hint-err 红 / hint 弱；supportingText 不走竖排形态）──
    val hintText: String? = issue ?: field.hint.ifBlank { null }
    val isHintError = issue != null

    if (modified) {
        // 已修改行：HoverableRow 包裹 + 左缘竖条 + hover 渐显「恢复默认」
        HoverableRow(
            modifier = Modifier
                .fillMaxWidth()
                .clip(AppShapes.small),
            shape = AppShapes.small,
            actionContent = { isHovered ->
                // 行尾操作区：重启 pill + hover 渐显「恢复默认」（固定占位防行宽跳动）
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(Dimens.space4),
                ) {
                    if (restartPill) RestartPillLabel()
                    Text(
                        text = "恢复默认",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.primary,
                        modifier = Modifier
                            .resetActionAlpha(visible = isHovered)
                            .clickable(
                                interactionSource = remember { MutableInteractionSource() },
                                indication = null,
                                enabled = isHovered,
                            ) { resetAction(field) },
                    )
                }
            },
        ) {
            ModifiedBar()
            FieldRowLayout(
                label = field.label,
                hintText = hintText,
                isHintError = isHintError,
                // 重启 pill 已由 actionContent 行尾区承载（悬停操作区），行内不重复
                restartPill = false,
                modifier = Modifier.weight(1f),
                editorRow = editorRow,
            )
        }
    } else {
        Column(Modifier.fillMaxWidth()) {
            if (contextLabel != null) {
                Text(
                    text = contextLabel,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = AppAlpha.contentSoft),
                )
            }
            // 未修改行也保留 RESTART pill（重启生效是字段的恒定属性，与是否修改无关）
            FieldRowLayout(
                label = field.label,
                hintText = hintText,
                isHintError = isHintError,
                restartPill = restartPill,
                editorRow = editorRow,
            )
        }
    }
}

/**
 * frow 行容器（横排三段 / 窄行纵排双形态）：
 * 横排 = lab 190 定宽 + hint（宽上限 [Dimens.fieldHintMaxWidth]）+ ctl 右缘 weight 段 + 重启 pill；
 * 窄行（可用宽 < [Dimens.fieldRowStackBelowWidth]）降级纵排 = lab+pill 行 + hint 整行 + ctl 整行。
 * 降级不可省：窗口最小尺寸按物理像素钳制，HiDPI 缩放下 dp 可用宽可低至阈值的一半以下，
 * 横排三段在该档位互相挤压必破版；纵排保底每个字段可读、可编辑。
 */
@Composable
private fun FieldRowLayout(
    label: String,
    hintText: String?,
    isHintError: Boolean,
    restartPill: Boolean,
    modifier: Modifier = Modifier,
    editorRow: @Composable () -> Unit,
) {
    BoxWithConstraints(modifier = modifier) {
        if (maxWidth < Dimens.fieldRowStackBelowWidth) {
            // 纵排形态：三段各占整行，hint 不限宽（整行换行承载长说明）
            Column(
                modifier = Modifier.fillMaxWidth(),
                verticalArrangement = Arrangement.spacedBy(Dimens.space4),
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    FieldRowLabel(label)
                    Box(Modifier.weight(1f))
                    if (restartPill) RestartPillLabel()
                }
                if (hintText != null) FieldRowHint(hintText, isHintError, capWidth = false)
                Box(Modifier.fillMaxWidth()) { editorRow() }
            }
        } else {
            // 横排三段（.frow）：lab + hint + ctl（编辑器右缘）
            Row(verticalAlignment = Alignment.CenterVertically) {
                FieldRowLabel(label)
                FieldRowHint(hintText, isHintError, capWidth = true)
                Box(Modifier.weight(1f), contentAlignment = Alignment.CenterEnd) { editorRow() }
                if (restartPill) RestartPillLabel()
            }
        }
    }
}

/** 横排三段·lab 段（.frow .lab：width 190px 定宽 + fs-13 + SemiBold，flex:none） */
@Composable
private fun FieldRowLabel(label: String) {
    Text(
        text = label,
        style = MaterialTheme.typography.bodyMedium.copy(
            // .frow .lab font-weight: 550（CSS 任意值）——Compose 最近档 = Medium 500
            // 或 SemiBold 600；取 SemiBold（600）贴 lab 的视觉重量偏重侧
            fontWeight = androidx.compose.ui.text.font.FontWeight.SemiBold,
        ),
        modifier = Modifier.width(Dimens.fieldLabelWidth),
    )
}

@Composable
private fun FieldRowHint(hint: String?, isError: Boolean, capWidth: Boolean) {
    if (hint == null) return
    Text(
        text = hint,
        style = MaterialTheme.typography.labelSmall,
        color = if (isError) {
            MaterialTheme.colorScheme.error
        } else {
            MaterialTheme.colorScheme.onSurfaceVariant
        },
        modifier = Modifier
            .padding(horizontal = Dimens.space8)
            // capWidth（横排形态）时限宽：hint 无权重，软换行 Text 会吃满剩余宽
            // 把 ctl 权重段压到零宽——上限兜住编辑器段最小可用宽
            .then(if (capWidth) Modifier.widthIn(max = Dimens.fieldHintMaxWidth) else Modifier),
    )
}

/** 左缘「已修改」竖条（.frow.mod inset 3px primary；宽 = configModifiedBarWidth token）。 */
@Composable
private fun ModifiedBar() {
    Box(
        modifier = Modifier
            .width(Dimens.configModifiedBarWidth)
            .background(MaterialTheme.colorScheme.primary, RectangleShape),
    )
}

@Composable
private fun RestartPillLabel() {
    val colors = LocalEasyToolboxColors.current
    PillLabel(
        text = "重启生效",
        container = colors.warningContainer,
        contentColor = colors.warning,
    )
}

// hover 渐显 alpha（HoverableRow 的 actionAlpha 已承担整体渐入，此处补「恢复默认」文字的
// 二态 alpha：隐形态 transparent、显形态不透明——alpha 值走 AppAlpha token，非 0.x 字面量）
private fun Modifier.resetActionAlpha(visible: Boolean): Modifier =
    if (visible) {
        this
    } else {
        this.then(Modifier.alpha(AppAlpha.transparent))
    }

/** 默认配置实例（isModified 判定与恢复默认共用；DefaultBridgeConfig.default() 的同模块短引用）。 */
private val DefaultBridgeConfigDefault: BridgeConfig get() = com.jiaoay.lark.bridge.config.DefaultBridgeConfig.default()

@Composable
private fun NumberField(
    field: BridgeField<Any?>,
    min: Long,
    max: Long,
    displayScale: Long,
    unitSuffix: String,
    config: BridgeConfig,
    isError: Boolean,
    onCommit: (Any?) -> Unit,
) {
    val raw = field.get(config) as Number
    val isInt = raw is Int
    val scale = if (displayScale > 0) displayScale else 1L
    ScaledNumberField(
        label = null,
        value = raw.toLong() / scale,
        unitSuffix = unitSuffix,
        isError = isError,
        onCommit = { displayed ->
            val stored = (displayed * scale).coerceIn(min, max)
            onCommit(if (isInt) stored.toInt() else stored)
        },
    )
}

@Composable
private fun DirectoryField(
    label: String,
    value: String,
    onCommit: (String) -> Unit,
    isError: Boolean,
    /** true = FieldRow 横排三段消费（label 只作 pickDirectory title，输入框无浮动 label） */
    titleOnly: Boolean = false,
    focusRequester: FocusRequester? = null,
) {
    val scope = rememberCoroutineScope()
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(Dimens.space8),
    ) {
        CommitTextField(
            label = if (titleOnly) null else label,
            value = value,
            onCommit = onCommit,
            modifier = (focusRequester?.let { Modifier.focusRequester(it) } ?: Modifier).weight(1f),
            isError = isError,
        )
        // chipbtn「浏览…」：raised 底
        // 胶囊 + 发丝边 + fs-12/600 onSurfaceVar 字（TaskCard「深度分析」chipbtn 同配方先例）
        Text(
            text = "浏览…",
            style = MaterialTheme.typography.labelMedium.copy(
                fontWeight = androidx.compose.ui.text.font.FontWeight.SemiBold, // .chipbtn 600
            ),
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier
                .clip(AppShapes.capsule)
                .background(LocalToolboxSurfaceColors.current.surfaceRaised, AppShapes.capsule)
                .border(
                    Dimens.glassStrokeHairline,
                    LocalToolboxSurfaceColors.current.borderHairline,
                    AppShapes.capsule,
                )
                .clickable {
                    scope.launch {
                        pickDirectory(title = label)?.let { onCommit(it.absolutePath) }
                    }
                }
                .padding(horizontal = Dimens.space14, vertical = Dimens.space6), // .chipbtn padding 6px 14px
        )
    }
}
