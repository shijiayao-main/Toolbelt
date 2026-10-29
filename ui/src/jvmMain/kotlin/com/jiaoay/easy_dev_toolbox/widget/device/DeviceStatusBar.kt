package com.jiaoay.easy_dev_toolbox.widget.device

import dev.vicart.compose.material.symbols.MaterialSymbols

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.withStyle
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import com.jiaoay.toolbelt.device.DeviceIssue
import com.jiaoay.toolbelt.device.DeviceState
import com.jiaoay.easy_dev_toolbox.core.theme.AppAlpha
import com.jiaoay.easy_dev_toolbox.core.theme.AppShapes
import com.jiaoay.easy_dev_toolbox.core.theme.Dimens
import com.jiaoay.easy_dev_toolbox.core.theme.LocalDayBattle
import com.jiaoay.easy_dev_toolbox.core.theme.LocalEasyToolboxColors
import com.jiaoay.easy_dev_toolbox.core.theme.LocalToolboxSurfaceColors
import com.jiaoay.easy_dev_toolbox.core.theme.dayBattleTint
import com.jiaoay.easy_dev_toolbox.widget.AppSymbolIcon

/**
 * 设备状态栏（三分渲染；胶囊化）：
 * - 容器胶囊化：Surface(AppShapes.capsule + surfaceVariant/bgSubtle 合成底)——
 *   行级状态语言对齐词汇表 .pill（状态点 6dp 纯色，dotIndicatorSize）；
 * - 检测失败（[detectError] 非空）：错误色圆点 +「检测失败」+ 异常信息 +
 *   「去设置配置 adb 路径」入口（[onGoSettings] null 时入口不渲染——宿主未接线）；
 * - 无设备且无错误（None）：主文案「未连接」+ 副行小字引导（插入自动检测/设置页配 adb）
 *   + 同款「去设置配置 adb 路径」入口（primary 引导色，与失败态
 *   错误色入口区分；[onGoSettings] null 时入口不渲染——宿主未接线）；
 * - Single：型号直显「已连接 · <型号>」（型号缺失回退串号——Single 无 displayLabel
 *   字段，主行自行派生 model.ifEmpty { serial }，口径对齐 DeviceInfo.displayLabel）；
 * - Multi：≤3 台状态栏内联 FilterChip 直选（零对话框打断——核心动线）；
 *   >3 台降级为「选择设备」入口钮走 [DeviceSelectDialog]（[onSelectDevice]）。
 *   已选定时展示「已选定: <label>」+「重新选择」/「清除选择」。
 * - [detecting] = true（手动刷新/进页即刷在途）时主行状态
 *   文字追加「（检测中…）」后缀 + 刷新钮禁用（AppAlpha.contentDisabled 洗底，
 *   连点防抖）；轮询路径恒 false（busy 只服务用户可感知入口）。
 * - 一键转无线动作（[onGoWireless] 非空时渲染，null = 宿主未接线不渲染）：有设备
 *   在台即可点，目标解析（选定优先/单设备回退）与每步人话回执由内核承接、经既有
 *   toast 通道由宿主落地；[wirelessConnecting] = true（三步编排在途）时按钮禁用
 *   （连点防抖，同刷新钮禁用口径）。
 *
 * 共享渲染件（:ui widget 层）：消费 :core 设备域模型（[DeviceState]），设备域
 * 单源的各消费页面（Shell 工具页 / 后续设备观测空间）共用本件，不重写第二份
 * 三分渲染。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DeviceStatusBar(
    deviceState: DeviceState,
    detectError: String?,
    detecting: Boolean,
    selectedSerial: String?,
    onRefresh: () -> Unit,
    onSelectDevice: () -> Unit,
    onPickDevice: (String) -> Unit,
    onClearSelection: () -> Unit,
    onGoSettings: (() -> Unit)? = null,
    onGoWireless: (() -> Unit)? = null,
    wirelessConnecting: Boolean = false,
    /**
     * 连接了但不可用的设备（未授权 / offline / 无 USB 权限）。
     *
     * 这些设备不能执行任何命令，故不进 [deviceState]；但它们是「插上了却连不上」的
     * 全部答案——用户在「未连接」态最需要的正是这里的原文，而不是「请插入设备」。
     */
    issues: List<DeviceIssue> = emptyList(),
) {
    val toolboxColors = LocalEasyToolboxColors.current
    val surfaceColors = LocalToolboxSurfaceColors.current
    Surface(
        shape = AppShapes.capsule,
        // .devbar：surfaceDeep 实底 + hairline 内描边（alpha 合成底
        // 属合成路径——「合成底也属 alpha 合成」对拍定罪）；**内容宽** inline-flex
        // 语义（fillMaxWidth 铺满整行与 .devbar display:inline-flex 不符）
        color = surfaceColors.surfaceDeep,
        modifier = Modifier
            .border(
                width = Dimens.glassStrokeHairline,
                color = surfaceColors.borderHairline,
                shape = AppShapes.capsule,
            ),
    ) {
        // .devbar padding 6×14
        Row(
            modifier = Modifier.padding(horizontal = Dimens.space14, vertical = Dimens.space6),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            val dotColor: Color
            val statusText: String
            /** 行内次级提示（弱化 span）：null = 该态无提示——替代旧「主行+副行+CTA」三段堆叠 */
            var inlineHint: Pair<String, Color>? = null
            var mainColor: Color = MaterialTheme.colorScheme.onBackground
            // 昼战语义承载（design-doc.md §5.3#4，恒定槽值不动）：语义色点坐对应 container 档
            // （dayBattleTint）、语义色 span 坐 container 实底（SpanStyle.background——
            // devbar 底 = surfaceDeep 深紫，warning/success/error 深档直落 1.1~1.5 不可见）；
            // 中性档（onSurfaceVariant/onBackground）昼战承载属卡域文字接线（S3-①）范畴，不在此
            var mainCarrier: Color? = null
            var hintCarrier: Color? = null

            if (detectError != null) {
                // 三分之一：检测失败（优先级最高——失败态下 DeviceState 已坍缩 None，
                // detectError 是唯一的失败信号源，不得被 None 分支吞掉）
                dotColor = dayBattleTint(MaterialTheme.colorScheme.error, toolboxColors.errorContainerFixed)
                statusText = "检测失败"
                mainColor = MaterialTheme.colorScheme.error
                mainCarrier = toolboxColors.errorContainerFixed
                inlineHint = detectError to MaterialTheme.colorScheme.error
                hintCarrier = toolboxColors.errorContainerFixed
            } else {
                // 三分之二/三：正常态（None / Single / Multi 既有语义不变）
                when (deviceState) {
                    is DeviceState.None -> {
                        dotColor = MaterialTheme.colorScheme.onSurfaceVariant
                        statusText = "未连接"
                        // 有「连上但不可用」的设备时，原文比「请插入设备」有用得多：
                        // 用户此刻最需要知道的是「为什么插上了却连不上」
                        inlineHint = if (issues.isNotEmpty()) {
                            val first = issues.first()
                            val extra = if (issues.size > 1) "（另有 ${issues.size - 1} 台）" else ""
                            "${first.displayLabel}：${first.state}$extra" to MaterialTheme.colorScheme.error
                        } else {
                            // 轮询语义：插拔自动更新——提示不教「点刷新」；adb 路径指引由行尾 CTA 承载
                            "插入设备后将自动检测" to MaterialTheme.colorScheme.onSurfaceVariant
                        }
                    }

                    is DeviceState.Single -> {
                        dotColor = dayBattleTint(toolboxColors.success, toolboxColors.successContainer)
                        // Single 态型号直显（「已连接 (1台)」对多设备用户无辨识度
                        // ——型号优先、串号兜底，口径对齐 DeviceInfo.displayLabel）
                        val single = deviceState
                        statusText = "已连接 · ${single.model.ifEmpty { single.serial }}"
                    }

                    is DeviceState.Multi -> {
                        if (selectedSerial != null) {
                            // 已选定：展示已选设备标识（型号优先，串号兜底）
                            dotColor = dayBattleTint(toolboxColors.success, toolboxColors.successContainer)
                            val label = deviceState.devices
                                .firstOrNull { it.serial == selectedSerial }?.displayLabel
                                ?: selectedSerial
                            statusText = "已选定: $label"
                        } else {
                            // 未选定：黄色警示 + 选择入口（替换原"多设备可能导致命令执行失败"文字）
                            dotColor = dayBattleTint(toolboxColors.warning, toolboxColors.warningContainer)
                            statusText = "已连接 (${deviceState.count}台)"
                        }
                    }
                }
            }

            Box(
                modifier = Modifier
                    .size(Dimens.dotIndicatorSize)
                    .clip(CircleShape)
                    .background(dotColor),
            )
            Spacer(modifier = Modifier.width(Dimens.space8))
            // 单行状态条（未连接/检测失败单行呈现（非「主行+副行+CTA」三段堆叠）：
            // 主状态加粗 + 行内弱化提示，胶囊高度从 ~90px 表单式块回落到一行）；
            // 文字列 weight(1f, fill = false)：fill=false 时 Row 保持内容宽（胶囊
            // 不拉伸，页头 weighted 标题保位）——禁改 fill=true：会把整条
            // actions 槽宽吃光、把 PageHeader 的 weighted 标题压成 0 宽
            // 无头渲染实证：fill=true 标题 size=0×113、fill=false 标题 1345px）
            // weight 上限仅约束长报错在配额内 ellipsis，不挤压行尾动作区
            Text(
                text = buildAnnotatedString {
                    // 昼战（design-doc.md §5.3）：语义色主行坐 container 实底（error×errorContainerFixed 5.14
                    // 槽对承载——devbar 深紫面上语义深档字 1.1~1.5 不可读）；非昼战
                    // Unspecified = 无背景现状
                    val dayBattle = LocalDayBattle.current
                    withStyle(
                        SpanStyle(
                            fontWeight = FontWeight.Bold,
                            color = mainColor,
                            // SpanStyle.background 非空缺省——非昼战/非语义态 = Unspecified（无背景现状）
                            background = if (dayBattle && mainCarrier != null) mainCarrier else Color.Unspecified,
                        ),
                    ) {
                        // 检测在途追加「（检测中…）」后缀（busy 只加缀——检测完成后
                        // 主文案随 deviceState/detectError 落定）
                        append(if (detecting) "$statusText（检测中…）" else statusText)
                    }
                    inlineHint?.let { (hint, hintColor) ->
                        append(" · ")
                        withStyle(
                            SpanStyle(
                                fontWeight = FontWeight.Normal,
                                fontSize = MaterialTheme.typography.labelSmall.fontSize,
                                color = hintColor,
                                // 昼战语义 hint 坐 container 实底（同 mainCarrier 纪律）
                                background = if (dayBattle && hintCarrier != null) hintCarrier else Color.Unspecified,
                            ),
                        ) { append(hint) }
                    }
                },
                modifier = Modifier.weight(1f, fill = false),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )

            // 去设置 CTA（检测失败/未连接两态）：紧凑文字钮（旧 TextButton 默认
            // 内边距把胶囊撑成表单块）——失败态错误色、空态 primary 引导色
            when {
                detectError != null && onGoSettings != null -> {
                    TextButton(
                        onClick = onGoSettings,
                        contentPadding = PaddingValues(horizontal = Dimens.space8, vertical = Dimens.space2),
                    ) {
                        Text(
                            "去设置配置 adb 路径",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.error,
                        )
                    }
                }

                deviceState is DeviceState.None && onGoSettings != null -> {
                    TextButton(
                        onClick = onGoSettings,
                        contentPadding = PaddingValues(horizontal = Dimens.space8, vertical = Dimens.space2),
                    ) {
                        Text(
                            "去设置配置 adb 路径",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.primary,
                        )
                    }
                }
            }

            // Multi 态行尾动作区——≤3 台内联 chip 直选（替换原「选择设备」
            // TextButton + 自动弹窗两跳动线）；>3 台 chip 流溢出，降级保留对话框入口。
            // 门令：选中 chip 与未选 chips +「清除选择」同屏并存，直选对已选定态恒在
            // （恒渲染不按 selectedSerial==null 隐藏——隐藏会使 selected 参数成死参数、
            // 直选动线断裂）。
            if (deviceState is DeviceState.Multi && deviceState.count <= 3) {
                Spacer(modifier = Modifier.width(Dimens.space8))
                deviceState.devices.forEach { device ->
                    FilterChip(
                        selected = device.serial == selectedSerial,
                        onClick = { onPickDevice(device.serial) },
                        // .chipbtn.hot
                        // 选中态；设备直选 chip 是第 8 处直用消费点）
                        colors = FilterChipDefaults.filterChipColors(
                            selectedContainerColor = MaterialTheme.colorScheme.primary
                                .copy(alpha = com.jiaoay.easy_dev_toolbox.core.theme.AppAlpha.hotWash),
                            selectedLabelColor = MaterialTheme.colorScheme.primary,
                        ),
                        shape = AppShapes.capsule,
                        label = {
                            Text(
                                text = device.displayLabel,
                                style = MaterialTheme.typography.labelMedium,
                            )
                        },
                    )
                }
                if (selectedSerial != null) {
                    Spacer(modifier = Modifier.width(Dimens.space6))
                    TextButton(onClick = onClearSelection) { Text("清除选择") }
                }
            } else if (deviceState is DeviceState.Multi) {
                Spacer(modifier = Modifier.width(Dimens.space6))
                if (selectedSerial != null) {
                    TextButton(onClick = onSelectDevice) { Text("重新选择") }
                    TextButton(onClick = onClearSelection) { Text("清除选择") }
                } else {
                    TextButton(onClick = onSelectDevice) { Text("选择设备") }
                }
            }

            // 一键转无线动作（[onGoWireless] null = 宿主未接线不渲染）：有设备在台
            // 即可点——多设备未选定的目标解析与人话回执由内核承接（零 spawn 拒绝
            // 经 toast 送达），设备栏只挂动作入口不复制内核文案。
            if (onGoWireless != null && detectError == null && deviceState !is DeviceState.None) {
                Spacer(modifier = Modifier.width(Dimens.space8))
                IconButton(
                    onClick = onGoWireless,
                    enabled = !wirelessConnecting,
                    modifier = Modifier.size(Dimens.minimumInteractiveSize),
                ) {
                    AppSymbolIcon(
                        icon = MaterialSymbols.WIFI,
                        contentDescription = "转无线调试",
                        size = Dimens.iconLarge,
                        tint = if (wirelessConnecting) {
                            MaterialTheme.colorScheme.onSurface.copy(alpha = AppAlpha.contentDisabled)
                        } else {
                            MaterialTheme.colorScheme.onSurfaceVariant
                        },
                    )
                }
            }

            // .devbar inline-flex 对齐（
            // 内容宽——weight 子项在有界约束下吃满行宽使 Surface 恒满宽，定宽间距承载）：
            Spacer(modifier = Modifier.width(Dimens.space8))
            // 检测在途刷新钮禁用（连点防抖——busy 期间 adb devices 在跑，
            // 连发只会排队叠进程）；禁用态图标洗 contentDisabled（M3 禁用内容档，
            // 与调试页发送钮同款——enabled=false 不会自动洗 AppSymbolIcon tint）
            IconButton(
                onClick = onRefresh,
                enabled = !detecting,
                modifier = Modifier.size(Dimens.minimumInteractiveSize),
            ) {
                AppSymbolIcon(
                    icon = MaterialSymbols.REFRESH,
                    contentDescription = "刷新设备",
                    size = Dimens.iconLarge,
                    tint = if (detecting) {
                        MaterialTheme.colorScheme.onSurface.copy(alpha = AppAlpha.contentDisabled)
                    } else {
                        MaterialTheme.colorScheme.onSurfaceVariant
                    },
                )
            }
        }
    }
}
