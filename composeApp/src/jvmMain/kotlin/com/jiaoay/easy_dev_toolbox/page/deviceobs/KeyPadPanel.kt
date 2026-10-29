package com.jiaoay.easy_dev_toolbox.page.deviceobs

import dev.vicart.compose.material.symbols.MaterialSymbols

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.input.pointer.isPrimaryPressed
import androidx.compose.ui.input.pointer.pointerInput
import com.jiaoay.easy_dev_toolbox.core.theme.AppAlpha
import com.jiaoay.easy_dev_toolbox.core.theme.AppMotion
import com.jiaoay.easy_dev_toolbox.core.theme.AppShapes
import com.jiaoay.easy_dev_toolbox.core.theme.DesktopTypography
import com.jiaoay.easy_dev_toolbox.core.theme.Dimens
import com.jiaoay.easy_dev_toolbox.widget.AppSymbolIcon
import com.jiaoay.easy_dev_toolbox.widget.CommitTextField
import com.jiaoay.easy_dev_toolbox.widget.SearchField
import com.jiaoay.easy_dev_toolbox.widget.StatusBanner
import com.jiaoay.easy_dev_toolbox.widget.StatusBannerStatus
import com.jiaoay.easy_dev_toolbox.widget.TonalCard
import com.jiaoay.toolbelt.device.AndroidKeyCodes
import com.jiaoay.toolbelt.device.DeviceKey
import com.jiaoay.toolbelt.device.DeviceKeys
import com.jiaoay.toolbelt.device.DeviceState
import com.jiaoay.toolbelt.device.DeviceStateSource
import com.jiaoay.toolbelt.device.KeyEventInjector
import com.jiaoay.toolbelt.device.KeyInjectionBatch

/**
 * 注入回执行文本（人话）：成功行按台列出串号，失败行逐台带原因
 * （成功/失败分行——批量部分失败时成功与失败各显其面，不混一行）。
 */
internal fun keyPadReceiptLines(batch: KeyInjectionBatch): List<String> = buildList {
    val ok = batch.receipts.filter { it.success }
    val failed = batch.receipts.filterNot { it.success }
    if (ok.isNotEmpty()) {
        add("「${batch.key.label}」注入成功：${ok.joinToString("、") { it.serial }}")
    }
    failed.forEach { receipt ->
        add("「${batch.key.label}」${receipt.serial} 失败：${receipt.message}")
    }
    if (isEmpty()) {
        // 空回执集仅经注入缝预置可达（分发内核目标集非空恒有回执）——防御行非静默
        add("「${batch.key.label}」无回执")
    }
}

/** 回执整体色调：全成功 = true（success 色），否则 error 色（部分失败不冒充成功） */
internal fun keyPadBatchAllOk(batch: KeyInjectionBatch): Boolean =
    batch.receipts.isNotEmpty() && batch.receipts.all { it.success }

/** 长按连发在途行文本（人话）：连发键 + 节流间隔（间隔读注入器单源常量，防文案失联） */
internal fun keyPadRepeatLine(key: DeviceKey, intervalMs: Long): String =
    "「${key.label}」长按连发中，间隔 $intervalMs ms"

/**
 * 键值查询命中提示文本：零命中返回 null（「无命中」由查询区空态行承载——提示与
 * 空态分离防双行同义）；命中数超显示上限时如实标注总数（不静默截断）。
 */
internal fun keyPadQueryResultHint(total: Int, shownLimit: Int): String? =
    if (total <= 0) {
        null
    } else if (total > shownLimit) {
        "命中 $total 项，显示前 $shownLimit 项"
    } else {
        "命中 $total 项"
    }

/** 键值查询命中行显示上限（防长查询命中几十项铺满面板；总数仍如实提示） */
private const val KEY_QUERY_ROW_MAX = 8

/**
 * 按键遥控面板（设备观测空间第四成员）：方向十字 + 常用按键网格 + 自定义
 * keycode 数字输入，把 `adb shell input keyevent` GUI 化；多设备态可开
 * 「批量注入」对勾选设备集逐台分发。
 *
 * - 注入器消费 :core 设备域 [KeyEventInjector]（App 级单源，设备三流与选定态
 *   同源）；注入器无常驻环——零 spawn 门控由构造保证（见其头注释），
 *   故本面板不做可见性接线（[PerfPanel] 的接线只服务其采样环）；
 * - 目标集交互（保守默认 + 显式多选）：默认只对当前选定设备注入（未选定时
 *   单设备回退，解析在注入器）；「批量注入」开（仅多设备态呈现）后目标集 =
 *   手动勾选设备集，开关不隐式广播到全部设备——进入批量态时预勾选当前
 *   选定台（勾选集与设备栏选定态同源起步），其余台须用户显式勾选；
 * - 无可用目标提示复用 [perfPanelHint]（无设备/检测失败/多设备未选定三态
 *   与性能面板同文案同动作入口；批量态下目标集自足，提示不出）；
 * - 注入回执：成功/失败逐台人话（[keyPadReceiptLines]），拒绝（无目标/空
 *   勾选集）人话呈现不静默吞点击；
 * - 长按连发：所有按键钮长按（系统长按时长档）成立即起 [KeyEventInjector]
 *   连发（默认态对默认目标、批量态对勾选集，与单击同路由），抬起即停
 *   （[keyPadRepeatLine] 在途行回显间隔）；连发在途时其余按键钮禁用、
 *   单击由注入器保守拒绝；
 * - 键值查询行：[AndroidKeyCodes] 全量表过滤（数字/名称），命中行点按注入
 *   /长按连发复用同一通道（与注入同域同页零导航）。
 *
 * 视觉层为保守默认（token 化按键网格 + 等宽回执行），真机手感由维护者终裁。
 */
@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
fun KeyPadPanel(
    devices: DeviceStateSource,
    injector: KeyEventInjector,
    onGoSettings: (() -> Unit)? = null,
) {
    val deviceState by devices.deviceState.collectAsState()
    val detectError by devices.detectError.collectAsState()
    val selectedSerial by devices.selectedSerial.collectAsState()
    val injectorState by injector.state.collectAsState()

    val multi = deviceState as? DeviceState.Multi
    var expanded by remember { mutableStateOf(true) }
    var batchMode by remember { mutableStateOf(false) }
    val batchTargets = remember { mutableStateListOf<String>() }
    var customText by remember { mutableStateOf("") }
    var keyQuery by remember { mutableStateOf("") }

    // 批量目标集与设备态对齐：设备消失即从勾选集剔除（防对空串号分发）
    multi?.devices?.map { it.serial }?.let { serials ->
        batchTargets.removeAll { it !in serials }
    }

    val hint = perfPanelHint(deviceState, detectError, selectedSerial)
    val injecting = injectorState.injecting
    val repeatingKey = injectorState.repeatingKey

    fun inject(key: DeviceKey) {
        if (batchMode) {
            injector.injectToTargets(key, batchTargets.toList())
        } else {
            injector.injectKey(key)
        }
    }

    /** 长按连发起动（与单击同路由：批量态对勾选集、默认态对默认目标） */
    fun startRepeat(key: DeviceKey): Boolean =
        if (batchMode) {
            injector.startRepeatToTargets(key, batchTargets.toList())
        } else {
            injector.startRepeat(key)
        }

    fun stopRepeat() {
        injector.stopRepeat()
    }

    val keysEnabled = !injecting && repeatingKey == null && (
        if (batchMode) {
            batchTargets.isNotEmpty()
        } else {
            hint == null
        }
        )

    val customKey = DeviceKeys.customKey(customText)
    val queryHits = AndroidKeyCodes.matches(keyQuery)

    TonalCard(
        contentPaddingVertical = Dimens.space12,
        contentPaddingHorizontal = Dimens.space16,
        contentSpacing = Dimens.space8,
    ) {
        // 头部行：图标 + 标题 + 注入中态 + 展开/收起
        Row(verticalAlignment = Alignment.CenterVertically) {
            AppSymbolIcon(
                icon = MaterialSymbols.SETTINGS_REMOTE,
                contentDescription = null,
                size = Dimens.iconMedium,
                tint = if (injecting || repeatingKey != null) {
                    MaterialTheme.colorScheme.primary
                } else {
                    MaterialTheme.colorScheme.onSurfaceVariant
                },
            )
            Spacer(modifier = Modifier.width(Dimens.space8))
            Text(
                text = when {
                    injecting -> "按键遥控 · 注入中"
                    repeatingKey != null -> "按键遥控 · 连发中"
                    else -> "按键遥控"
                },
                style = MaterialTheme.typography.titleSmall,
                // 标题必须吃满剩余配额（weight 默认 fill）：fill=false 时未用配额悬空在
                // 行尾，会把展开钮推离右缘（窗口越大/标题越短越明显）
                modifier = Modifier.weight(1f),
            )
            IconButton(
                onClick = { expanded = !expanded },
                modifier = Modifier.size(Dimens.minimumInteractiveSize),
            ) {
                AppSymbolIcon(
                    icon = if (expanded) MaterialSymbols.EXPAND_LESS else MaterialSymbols.EXPAND_MORE,
                    contentDescription = if (expanded) "收起按键遥控面板" else "展开按键遥控面板",
                    size = Dimens.iconLarge,
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }

        AnimatedVisibility(
            visible = expanded,
            enter = fadeIn(animationSpec = AppMotion.standard()) +
                expandVertically(animationSpec = AppMotion.standard()),
            exit = fadeOut(animationSpec = AppMotion.standard()) +
                shrinkVertically(animationSpec = AppMotion.standard()),
        ) {
            Column(verticalArrangement = Arrangement.spacedBy(Dimens.space8)) {
                // 无可用目标提示（默认态专用；批量态目标集自足不出提示）
                if (!batchMode) {
                    hint?.let { h ->
                        StatusBanner(
                            status = StatusBannerStatus.Error,
                            text = h.message,
                            trailing = {
                                when (h.action) {
                                    PerfPanelHintAction.GO_SETTINGS -> onGoSettings?.let { go ->
                                        TextButton(onClick = go) { Text("去设置配置 adb 路径") }
                                    }

                                    // 选设备/刷新的动作在观测页设备栏（面板外资源，消息文案自指引）
                                    PerfPanelHintAction.SELECT_DEVICE,
                                    PerfPanelHintAction.REFRESH -> Unit
                                }
                            },
                        )
                    }
                }

                Row(verticalAlignment = Alignment.Top) {
                    DPadCross(
                        enabled = keysEnabled,
                        onInject = ::inject,
                        onRepeatStart = ::startRepeat,
                        onRepeatStop = ::stopRepeat,
                    )
                    Spacer(modifier = Modifier.width(Dimens.space16))
                    Column(verticalArrangement = Arrangement.spacedBy(Dimens.space6)) {
                        Text(
                            "常用按键",
                            style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        FlowRow(
                            horizontalArrangement = Arrangement.spacedBy(Dimens.space6),
                            verticalArrangement = Arrangement.spacedBy(Dimens.space6),
                        ) {
                            DeviceKeys.COMMON_KEYS.forEach { key ->
                                KeyHoldButton(
                                    key = key,
                                    enabled = keysEnabled,
                                    onInject = ::inject,
                                    onRepeatStart = ::startRepeat,
                                    onRepeatStop = ::stopRepeat,
                                )
                            }
                        }
                    }
                }

                // 自定义 keycode：十进制 1..999，坏输入即时人话不静默
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(Dimens.space8),
                ) {
                    CommitTextField(
                        label = "自定义 keycode",
                        value = customText,
                        onCommit = { customKey?.let(::inject) },
                        modifier = Modifier.weight(1f),
                        onValueChange = { customText = it },
                        isError = customText.isNotEmpty() && customKey == null,
                        supportingText = if (customText.isNotEmpty() && customKey == null) {
                            "键值须为 1 到 ${DeviceKeys.MAX_CUSTOM_KEYCODE} 的十进制整数"
                        } else {
                            null
                        },
                    )
                    // 自定义键注入钮同享长按连发（连发键 = 长按成立时的自定义键，长按中改文本不换发）
                    val customSuppress = remember { mutableStateOf(false) }
                    Button(
                        onClick = {
                            if (customSuppress.value) {
                                customSuppress.value = false
                            } else {
                                customKey?.let(::inject)
                            }
                        },
                        enabled = keysEnabled && customKey != null,
                        modifier = Modifier.holdRepeatGesture(
                            enabled = keysEnabled,
                            suppress = customSuppress,
                            onEngage = { customKey != null && startRepeat(customKey) },
                            onRelease = ::stopRepeat,
                        ),
                    ) {
                        Text("注入")
                    }
                }

                // 键值查询行：全量 KeyEvent 常量表过滤（数字/名称），命中行注入/长按连发同通道。
                // 内距 space8：SearchField 为贴边描边矮框，与标签行间隙须 >3px（场景守护带间隙判据）
                Column(verticalArrangement = Arrangement.spacedBy(Dimens.space8)) {
                    Text(
                        "键值查询（键值数字或常量名，如 24 / volume）",
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    // 无 placeholder：示例自足写在上方常显标签里——SearchField 空态的
                    // 边框带与占位文带相距仅 3px，会踩场景守护的带间隙判据
                    // （占位文本与边框同为控件内部解剖，非内容带粘连）
                    SearchField(
                        value = keyQuery,
                        onValueChange = { keyQuery = it },
                        modifier = Modifier.fillMaxWidth(),
                    )
                    if (keyQuery.isNotBlank()) {
                        if (queryHits.isEmpty()) {
                            Text(
                                "无命中（键值超域或常量名不存在）",
                                style = MaterialTheme.typography.labelMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        } else {
                            keyPadQueryResultHint(queryHits.size, KEY_QUERY_ROW_MAX)?.let { hintLine ->
                                Text(
                                    hintLine,
                                    style = MaterialTheme.typography.labelMedium,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                            FlowRow(
                                horizontalArrangement = Arrangement.spacedBy(Dimens.space6),
                                verticalArrangement = Arrangement.spacedBy(Dimens.space6),
                            ) {
                                queryHits.take(KEY_QUERY_ROW_MAX).forEach { hit ->
                                    KeyHoldButton(
                                        key = DeviceKey(hit.code, "${hit.code} · ${hit.name}"),
                                        enabled = keysEnabled,
                                        onInject = ::inject,
                                        onRepeatStart = ::startRepeat,
                                        onRepeatStop = ::stopRepeat,
                                    )
                                }
                            }
                        }
                    }
                }

                // 批量注入（仅多设备态呈现；目标集 = 手动勾选集，不隐式广播）
                if (multi != null) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(Dimens.space8),
                    ) {
                        FilterChip(
                            selected = batchMode,
                            onClick = {
                                batchMode = !batchMode
                                batchTargets.clear()
                                // 批量态起步勾选 = 当前选定台（未选定为空集，其余台显式勾选）
                                if (batchMode) {
                                    selectedSerial?.let { selected ->
                                        if (multi.devices.any { it.serial == selected }) {
                                            batchTargets.add(selected)
                                        }
                                    }
                                }
                            },
                            colors = FilterChipDefaults.filterChipColors(
                                selectedContainerColor = MaterialTheme.colorScheme.primary
                                    .copy(alpha = AppAlpha.hotWash),
                                selectedLabelColor = MaterialTheme.colorScheme.primary,
                            ),
                            shape = AppShapes.capsule,
                            label = {
                                Text("批量注入", style = MaterialTheme.typography.labelMedium)
                            },
                        )
                        if (batchMode) {
                            Text(
                                if (batchTargets.isEmpty()) "勾选目标设备后生效" else "目标 ${batchTargets.size} 台",
                                style = MaterialTheme.typography.labelMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
                    if (batchMode) {
                        FlowRow(
                            horizontalArrangement = Arrangement.spacedBy(Dimens.space6),
                            verticalArrangement = Arrangement.spacedBy(Dimens.space6),
                        ) {
                            multi.devices.forEach { device ->
                                FilterChip(
                                    selected = device.serial in batchTargets,
                                    onClick = {
                                        if (device.serial in batchTargets) {
                                            batchTargets.remove(device.serial)
                                        } else {
                                            batchTargets.add(device.serial)
                                        }
                                    },
                                    colors = FilterChipDefaults.filterChipColors(
                                        selectedContainerColor = MaterialTheme.colorScheme.primary
                                            .copy(alpha = AppAlpha.hotWash),
                                        selectedLabelColor = MaterialTheme.colorScheme.primary,
                                    ),
                                    shape = AppShapes.capsule,
                                    label = {
                                        Text(device.displayLabel, style = MaterialTheme.typography.labelMedium)
                                    },
                                )
                            }
                        }
                    }
                }

                // 注入回执：连发在途行 + 拒绝人话（不静默吞点击）+ 最近批量逐台成功/失败行
                repeatingKey?.let { rk ->
                    Text(
                        text = keyPadRepeatLine(rk, KeyEventInjector.REPEAT_INTERVAL_MS),
                        style = DesktopTypography.mono,
                        color = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.fillMaxWidth(),
                        maxLines = 1,
                    )
                }
                injectorState.rejectError?.let { reject ->
                    Text(
                        text = reject,
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.error,
                        maxLines = 2,
                    )
                }
                injectorState.lastBatch?.let { batch ->
                    val allOk = keyPadBatchAllOk(batch)
                    keyPadReceiptLines(batch).forEach { line ->
                        Text(
                            text = line,
                            style = DesktopTypography.mono,
                            color = if (allOk) {
                                MaterialTheme.colorScheme.primary
                            } else {
                                MaterialTheme.colorScheme.error
                            },
                            modifier = Modifier.fillMaxWidth(),
                            maxLines = 2,
                        )
                    }
                }
            }
        }
    }
}

/** 方向十字（上/左/确认/右/下 + 角占位）：十字中央为确认键（DPAD_CENTER），长按连发同通道 */
@Composable
private fun DPadCross(
    enabled: Boolean,
    onInject: (DeviceKey) -> Unit,
    onRepeatStart: (DeviceKey) -> Boolean,
    onRepeatStop: () -> Unit,
) {
    val cell = Dimens.minimumInteractiveSize
    Column(verticalArrangement = Arrangement.spacedBy(Dimens.space4)) {
        Row(horizontalArrangement = Arrangement.spacedBy(Dimens.space4)) {
            Spacer(modifier = Modifier.size(cell))
            DPadKeyButton(MaterialSymbols.ARROW_UPWARD, "上", DeviceKeys.DPAD_UP, enabled, onInject, onRepeatStart, onRepeatStop)
            Spacer(modifier = Modifier.size(cell))
        }
        Row(horizontalArrangement = Arrangement.spacedBy(Dimens.space4)) {
            DPadKeyButton(MaterialSymbols.ARROW_BACK, "左", DeviceKeys.DPAD_LEFT, enabled, onInject, onRepeatStart, onRepeatStop)
            DPadKeyButton(MaterialSymbols.CHECK, "确认", DeviceKeys.DPAD_CENTER, enabled, onInject, onRepeatStart, onRepeatStop)
            DPadKeyButton(MaterialSymbols.ARROW_FORWARD, "右", DeviceKeys.DPAD_RIGHT, enabled, onInject, onRepeatStart, onRepeatStop)
        }
        Row(horizontalArrangement = Arrangement.spacedBy(Dimens.space4)) {
            Spacer(modifier = Modifier.size(cell))
            DPadKeyButton(MaterialSymbols.ARROW_DOWNWARD, "下", DeviceKeys.DPAD_DOWN, enabled, onInject, onRepeatStart, onRepeatStop)
            Spacer(modifier = Modifier.size(cell))
        }
    }
}

@Composable
private fun DPadKeyButton(
    icon: String,
    desc: String,
    key: DeviceKey,
    enabled: Boolean,
    onInject: (DeviceKey) -> Unit,
    onRepeatStart: (DeviceKey) -> Boolean,
    onRepeatStop: () -> Unit,
) {
    val suppress = remember { mutableStateOf(false) }
    IconButton(
        onClick = {
            if (suppress.value) {
                suppress.value = false
            } else {
                onInject(key)
            }
        },
        enabled = enabled,
        modifier = Modifier
            .size(Dimens.minimumInteractiveSize)
            .holdRepeatGesture(
                enabled = enabled,
                suppress = suppress,
                onEngage = { onRepeatStart(key) },
                onRelease = onRepeatStop,
            ),
    ) {
        AppSymbolIcon(
            icon = icon,
            contentDescription = desc,
            size = Dimens.iconLarge,
            tint = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

/** 按键钮（文本形态）：单击注入 + 长按连发 + 抬起即停（手势语义见 [holdRepeatGesture]） */
@Composable
private fun KeyHoldButton(
    key: DeviceKey,
    enabled: Boolean,
    onInject: (DeviceKey) -> Unit,
    onRepeatStart: (DeviceKey) -> Boolean,
    onRepeatStop: () -> Unit,
) {
    val suppress = remember { mutableStateOf(false) }
    OutlinedButton(
        onClick = {
            if (suppress.value) {
                suppress.value = false
            } else {
                onInject(key)
            }
        },
        enabled = enabled,
        shape = AppShapes.capsule,
        modifier = Modifier.holdRepeatGesture(
            enabled = enabled,
            suppress = suppress,
            onEngage = { onRepeatStart(key) },
            onRelease = onRepeatStop,
        ),
    ) {
        Text(key.label, style = MaterialTheme.typography.labelMedium)
    }
}

/**
 * 长按连发手势（零消费原始手势环，自判长按成立/松手）：
 * - 主键按下后系统长按时长档（ViewConfiguration.longPressTimeoutMillis，与系统
 *   长按感知一致档）内未抬起 → 调用 onEngage（返回 true = 连发已起）；此后等
 *   任意抬起/取消事件 → 调用 onRelease（停止连发，注入器侧幂等）。快速单击
 *   （时长档内抬起）零动作——单击注入走宿主 clickable
 *   的 onClick 既有路径；
 * - 零消费（不 consume 任何 change）——与宿主按钮的 clickable 同面共存；
 *   （不可改用 detectTapGestures：它会 consume down，饿死同面宿主手势——
 *   AppTitleBar 已有同结论先例）；
 * - [suppress] 为「长按成立后的单击抑制」：长按松手时宿主 clickable 仍会触发
 *   onClick（clickable 自身无长按语义），宿主 onClick 须读 [suppress] 吞掉
 *   连发结束的那次单击（true = 吞并复位；suppress 只在下一次按下时复位——
 *   长按后 pointer 移出按钮松开导致 clickable 取消时 onClick 不来，悬置的
 *   true 由下次按下复位，不误吞后续真单击）。
 */
@Composable
private fun Modifier.holdRepeatGesture(
    enabled: Boolean,
    suppress: MutableState<Boolean>,
    onEngage: () -> Boolean,
    onRelease: () -> Unit,
): Modifier {
    val enabledNow = rememberUpdatedState(enabled)
    val suppressNow = rememberUpdatedState(suppress)
    val engageNow = rememberUpdatedState(onEngage)
    val releaseNow = rememberUpdatedState(onRelease)
    // 按钮离开组合（切页/收面板）时手势环被动消亡——抬起回执不会再来，这里兜底停发
    DisposableEffect(Unit) {
        onDispose { releaseNow.value() }
    }
    return this.pointerInput(Unit) {
        awaitPointerEventScope {
            while (true) {
                val down = awaitPointerEvent()
                if (down.type != PointerEventType.Press) continue
                if (!down.buttons.isPrimaryPressed) continue
                suppressNow.value.value = false
                val released = withTimeoutOrNull(viewConfiguration.longPressTimeoutMillis) {
                    var event = awaitPointerEvent()
                    while (event.type != PointerEventType.Release && event.buttons.isPrimaryPressed) {
                        event = awaitPointerEvent()
                    }
                    event
                }
                // 时长档内抬起 = 快速单击，零动作（onClick 由宿主 clickable 处理）
                if (released != null) continue
                val engaged = enabledNow.value && engageNow.value()
                suppressNow.value.value = engaged
                // 连发期等待抬起（不限时；零消费不饿死宿主压感态。
                // 抬起判定双锚：Release 事件类型或主键已不在按下位（失焦合成/多指针边缘））
                while (true) {
                    val event = awaitPointerEvent()
                    if (event.type == PointerEventType.Release || !event.buttons.isPrimaryPressed) break
                }
                releaseNow.value()
            }
        }
    }
}
