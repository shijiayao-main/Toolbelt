package com.jiaoay.easy_dev_toolbox.widget

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.offset
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.isSecondaryPressed
import androidx.compose.ui.input.pointer.pointerInput
import kotlin.math.roundToInt

/**
 * 桌面右键菜单容器：包在页面区域外层，右键该区域内任意处
 * 弹出 [items] 指定的上下文动作。
 *
 * items 在右键按下瞬间求值一次（与命令面板 visibleWhen 同款「打开瞬间快照」
 * 语义——菜单开着时状态变化不闪现增删条目）；空列表时右键不弹菜单、不吞事件。
 *
 * 右键检测：pointerInput 内循环 awaitPointerEvent（Initial pass——事件到达
 * 子组件前先看一眼，不消费不拦截，子组件正常收事件）。菜单锚 = 按下处指针位：
 * 右键按下时捕获指针坐标，[DropdownMenu] 经零尺寸锚 Box 承载（offset 定位）——
 * 保留 M3 DropdownMenu 全部内建语义（窗口钳制/dismiss/焦点/键盘），调用方无感；
 * 指针未记录（键盘触发等程序化展开路径）时锚回落整域左上（M3 默认形态）。
 *
 * 用法（页面骨架顶层）：
 * ```
 * AppContextMenu(items = { listOf(ContextMenuEntry("刷新") { ... }) }) {
 *     PageScaffold(...) { ... }
 * }
 * ```
 *
 * 视觉走 M3 DropdownMenu 原生形态（tonal 扁平语言一致），不另自绘 popup。
 */
@Composable
fun AppContextMenu(
    items: () -> List<ContextMenuEntry>,
    modifier: Modifier = Modifier,
    content: @Composable () -> Unit,
) {
    var expanded by remember { mutableStateOf(false) }
    // 打开瞬间快照（菜单开着时 items 状态变化不闪现增删）
    val snapshot = remember { mutableStateOf<List<ContextMenuEntry>>(emptyList()) }
    // 菜单锚点 = 右键按下处的指针坐标（外层 Box 本地系）；打开期间保留、下次右键
    // 覆盖（关闭不重置——每次展开前都先落账，残留值无消费窗口）。null = 未记录
    // （键盘触发等无指针路径），锚回落整域左上
    var anchorOffset by remember { mutableStateOf<Offset?>(null) }
    // C2-#3 · pointerInput(Unit) + rememberUpdatedState：items 是每次重组都新建的
    // lambda（闭包捕获页面状态），作 key 会让指针协程每重组即重启；Unit 键长驻 +
    // latest 引用读取，弹层语义不变（打开瞬间仍是 items() 求值快照）
    val latestItems by rememberUpdatedState(items)

    Box(
        modifier = modifier.pointerInput(Unit) {
            awaitPointerEventScope {
                while (true) {
                    val event = awaitPointerEvent(PointerEventPass.Initial)
                    val change = event.changes.firstOrNull()
                    val down = change?.pressed ?: false
                    // 右键按下：不消费（事件继续给子组件/菜单框架），只触发弹层
                    if (down && event.buttons.isSecondaryPressed) {
                        // 指针位先落账再弹层——同帧内锚与 expanded 一致，菜单开在按下处
                        anchorOffset = change?.position
                        snapshot.value = latestItems()
                        if (snapshot.value.isNotEmpty()) expanded = true
                    }
                }
            }
        },
    ) {
        content()
        // 浅色对比：容器色 surfaceDeep + 发丝描边——M3 默认
        // surfaceContainerHigh 在浅色下与页面底色近同（盲像素审计实拍：菜单文字
        // 「平铺」在页面上无浮起感）；surfaceDeep 深/浅两档均与页面底有档位差
        val surfaceColors = com.jiaoay.easy_dev_toolbox.core.theme.LocalToolboxSurfaceColors.current
        // 零尺寸锚 Box：M3 DropdownMenu（Popup）锚定父布局节点——锚 = 整域 Box 时
        // 菜单开在区域角落而非指针处。offset 只改定位不占布局空间（content() 兄弟
        // 面零影响）；零尺寸因 DropdownMenu 的 Popup 内容不入父布局测量（无须显式
        // size(0)——那会是裸 dp 字面量）。未记录指针位时归零偏移 = 回落整域锚
        Box(
            modifier = Modifier.offset {
                anchorOffset?.let { IntOffset(it.x.roundToInt(), it.y.roundToInt()) }
                    ?: IntOffset.Zero
            },
        ) {
            DropdownMenu(
                expanded = expanded,
                onDismissRequest = { expanded = false },
                containerColor = surfaceColors.surfaceDeep,
                tonalElevation = 0.dp,
                // 菜单容器描边归一 borderHairline（与 TonalCard 同族发丝）
                border = BorderStroke(
                    com.jiaoay.easy_dev_toolbox.core.theme.Dimens.glassStrokeHairline,
                    surfaceColors.borderHairline,
                ),
            ) {
                snapshot.value.forEach { entry ->
                    DropdownMenuItem(
                        text = {
                            Text(
                                text = entry.label,
                                color = if (entry.danger) {
                                    MaterialTheme.colorScheme.error
                                } else {
                                    MaterialTheme.colorScheme.onSurface
                                },
                            )
                        },
                        onClick = {
                            expanded = false
                            if (entry.enabled) entry.perform()
                        },
                        enabled = entry.enabled,
                    )
                }
            }
        }
    }
}

/**
 * 右键菜单条目。
 * @param label 菜单文案
 * @param enabled 不可用置灰（不隐藏——HIG 同款可发现性优先）
 * @param danger 危险动作（error 色文案——与命令面板动作项同款视觉语言）
 * @param perform 执行体
 */
data class ContextMenuEntry(
    val label: String,
    val enabled: Boolean = true,
    val danger: Boolean = false,
    val perform: () -> Unit,
)
