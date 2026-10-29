package com.jiaoay.easy_dev_toolbox.host

import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Popup
import androidx.compose.ui.window.PopupProperties
import com.jiaoay.easy_dev_toolbox.core.theme.AppShapes
import com.jiaoay.easy_dev_toolbox.core.theme.DesktopTypography
import com.jiaoay.easy_dev_toolbox.core.theme.Dimens
import com.jiaoay.easy_dev_toolbox.widget.glass.appFloatShadow
import com.jiaoay.easy_dev_toolbox.widget.glass.appGlassSheet
import kotlinx.coroutines.flow.asStateFlow

/**
 * 快捷键参考卡：[HotkeyHub.HOTKEY_TABLE] 的渲染面（零维护——表即真源，
 * 禁止文档与代码二次维护）。可搜索过滤；Esc 关闭（popup 内经 onPreviewKeyEvent，
 * 兼经 [HotkeyHub.closeOverlays] 栈——参考卡注册进 main.kt 的 closeOverlays 链兜底）。
 *
 * ## 浮层语言统一
 * **非模态 Popup + glassSheet 圆角 + appFloatShadow 软影 + 点外/Esc 关闭**——与命令面板浮层同语言（勿改回 `androidx.compose.ui.window.Dialog`：会带 M3 平台模态遮罩全窗调光，与 palette 浮层语言分裂）；卡宽复用 [Dimens.paletteWidth]（omnibar 家族同宽）。
 */
object HotkeyHelp {

    private val _visible = kotlinx.coroutines.flow.MutableStateFlow(false)
    val visible: kotlinx.coroutines.flow.StateFlow<Boolean> = _visible.asStateFlow()

    fun open() {
        _visible.value = true
    }

    fun dismiss() {
        _visible.value = false
    }

    fun toggle() {
        _visible.value = !_visible.value
    }
}

/** 快捷键参考卡渲染（App 组合层挂载；visible false 零渲染） */
@Composable
fun HotkeyHelpCard() {
    val visible by HotkeyHelp.visible.collectAsState()
    if (!visible) return

    // 浮层入场动效：scale+fade（palette 同款——Popup 挂载即窗存在，
    // 出场无法拦窗销毁；入场动画达成「不再硬切」，模态动效规范组合）
    var appeared by remember { mutableStateOf(false) }
    LaunchedEffect(Unit) { appeared = true }
    val appearFraction by androidx.compose.animation.core.animateFloatAsState(
        targetValue = if (appeared) 1f else 0f,
        animationSpec = com.jiaoay.easy_dev_toolbox.core.theme.AppMotion.fast(),
        label = "helpCardAppear",
    )

    var query by remember { mutableStateOf("") }
    val focusRequester = remember { FocusRequester() }

    val filtered = remember(query) {
        if (query.isBlank()) {
            HotkeyHub.HOTKEY_TABLE
        } else {
            HotkeyHub.HOTKEY_TABLE.filter { (keys, label) ->
                keys.contains(query, ignoreCase = true) || label.contains(query)
            }
        }
    }

    // 非模态 Popup（CommandPalette 同配方）：focusable 抢焦点；点外关闭走 CMP
    // 窗口级外部点击检测（PopupProperties 默认 dismissOnClickOutside）；
    // Esc 经 Box onPreviewKeyEvent（preview 链先于焦点组件，恒可达）
    Popup(
        onDismissRequest = HotkeyHelp::dismiss,
        properties = PopupProperties(focusable = true),
        alignment = Alignment.Center,
    ) {
        Box(
            modifier = Modifier
                .onPreviewKeyEvent { event ->
                    if (event.type != KeyEventType.KeyDown) return@onPreviewKeyEvent false
                    if (event.key == Key.Escape) {
                        HotkeyHelp.dismiss()
                        true
                    } else {
                        false
                    }
                }
                .pointerInput(Unit) {
                    detectTapGestures { HotkeyHelp.dismiss() }
                },
            contentAlignment = Alignment.Center,
        ) {
            Surface(
                shape = AppShapes.glassSheet,
                color = Color.Transparent,
                tonalElevation = 0.dp,
                border = null,
                modifier = Modifier
                    .graphicsLayer {
                        scaleX = 0.96f + 0.04f * appearFraction
                        scaleY = 0.96f + 0.04f * appearFraction
                        alpha = appearFraction
                    }
                    .appFloatShadow(cornerRadius = Dimens.glassSheetRadius)
                    // sheet 档真玻璃（palette 同款——App 级根已可达）
                    .appGlassSheet(shape = AppShapes.glassSheet),
            ) {
                Column(
                    modifier = Modifier
                        .padding(Dimens.space24)
                        .width(Dimens.paletteWidth)
                        .heightIn(max = Dimens.helpCardMaxHeight),
                    verticalArrangement = Arrangement.spacedBy(Dimens.space12),
                ) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(Dimens.space12),
                    ) {
                        Text("快捷键", style = MaterialTheme.typography.titleMedium)
                        Spacer(modifier = Modifier.weight(1f))
                        Text(
                            "Esc 关闭",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    OutlinedTextField(
                        value = query,
                        onValueChange = { query = it },
                        modifier = Modifier
                            .fillMaxWidth()
                            .focusRequester(focusRequester),
                        placeholder = { Text("搜索快捷键或动作…") },
                        singleLine = true,
                    )
                    LazyColumn(verticalArrangement = Arrangement.spacedBy(Dimens.space4)) {
                        items(filtered) { (keys, label) ->
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(horizontal = Dimens.space4),
                                horizontalArrangement = Arrangement.spacedBy(Dimens.space8),
                            ) {
                                Surface(
                                    shape = AppShapes.small,
                                    color = MaterialTheme.colorScheme.surfaceContainerHigh,
                                ) {
                                    Text(
                                        text = keys,
                                        // 键帽走 DesktopTypography
                                        // .mono 档（键帽施工口径：.kbd 键帽走 DesktopTypography
                                        // mono 档）——键名是键面符号串，等宽字形对齐键帽语义；原比例
                                        // 字体档退役。键帽底色/圆角（surfaceContainerHigh+small）
                                        // 已合规不动。
                                        style = DesktopTypography.mono,
                                        modifier = Modifier.padding(
                                            horizontal = Dimens.space8,
                                            vertical = Dimens.space2,
                                        ),
                                    )
                                }
                                Spacer(modifier = Modifier.width(Dimens.space8))
                                Text(
                                    text = label,
                                    style = MaterialTheme.typography.bodyMedium,
                                    modifier = Modifier.weight(1f),
                                )
                            }
                        }
                    }
                    Spacer(modifier = Modifier.height(Dimens.space4))
                }
            }
        }
    }

    // 进入抢焦（搜索框即时可达）
    LaunchedEffect(visible) {
        if (visible) runCatching { focusRequester.requestFocus() }
    }
}
