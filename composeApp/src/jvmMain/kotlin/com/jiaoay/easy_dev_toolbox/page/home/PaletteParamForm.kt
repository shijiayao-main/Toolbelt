package com.jiaoay.easy_dev_toolbox.page.home

import androidx.compose.material3.FilterChipDefaults
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import com.jiaoay.easy_dev_toolbox.widget.pickOpenFiles
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.text.font.FontWeight
import com.jiaoay.easy_dev_toolbox.core.navigation.ParamSpec
import com.jiaoay.easy_dev_toolbox.core.navigation.ParamType
import com.jiaoay.easy_dev_toolbox.core.theme.AppAlpha
import com.jiaoay.easy_dev_toolbox.core.theme.AppShapes
import com.jiaoay.easy_dev_toolbox.core.theme.DesktopTypography
import com.jiaoay.easy_dev_toolbox.core.theme.Dimens
import java.io.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import com.jiaoay.easy_dev_toolbox.core.theme.LocalToolboxSurfaceColors

/**
 * 带参命令的参数直填区（palette 选中 ParamCommand 后的表单态）。
 *
 * 形态（[ParamSpec.type] 四型分派）：
 * - FILE：路径文本 + **存在性校验**（File.exists 实时反馈）+ 行尾「浏览…」钮
 *   （AWT FileDialog 系统文件选择器——选完回填路径字段，触发既有存在性校验链）；
 * - TEXT：路径文本（placeholder 提示格式）；
 * - NUMBER：数字文本（放开小数/负数；非法串不吞键，isError +
 *   supportingText「请输入有效数字」实时反馈，与 FILE 存在性校验同风格；执行门
 *   [paramFormReady] 拦非法串，防 Enter 直执行被 argsToJson toDoubleOrNull 静默丢参）；
 * - CHOICE：FilterChip 单选组。
 *
 * FILE 用「浏览…」按钮而非拖入——选完回填即解「手输绝对路径」痛点
 *    （所选即所填，零键盘输入）；Popup 内 DnD（拖文件
 *    图标进浮层）后置二期（Compose Desktop Popup 的外来 DnD 目标支持未验证，
 *    与工作台 FileDropZone 的页内 DnD 是两条链路）。
 *
 * 键盘契约：表单态 Enter 执行收口在 CommandPalette 根拦截层（preview 隧道先于焦点
 * 组件——焦点在本表单参数框时根层先处理 Enter 转执行判定），本 Composable 只管收集。
 *
 * 视觉全走既有 token（零白名单纪律）。
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun PaletteParamForm(
    commandLabel: String,
    params: List<ParamSpec>,
    values: Map<String, String>,
    onValueChange: (id: String, value: String) -> Unit,
    /** 首参数框抢焦（进表单态焦点自搜索框转移——palette 键盘链三件套之一；null = 不抢） */
    focusRequester: androidx.compose.ui.focus.FocusRequester? = null,
) {
    val scope = rememberCoroutineScope()
    Column(
        verticalArrangement = Arrangement.spacedBy(Dimens.space8),
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(max = Dimens.paletteListMaxHeight),
    ) {
        // 命令标题行（表单态锚点——用户随时知道在为哪个命令填参）
        Row(horizontalArrangement = Arrangement.spacedBy(Dimens.space4)) {
            Text(
                text = commandLabel,
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.SemiBold,
                color = MaterialTheme.colorScheme.onSurface,
            )
            Text(
                text = "填写参数",
                style = MaterialTheme.typography.labelSmall,
                color = LocalToolboxSurfaceColors.current.onSurfaceFaint,
            )
        }
        params.forEach { spec ->
            when (spec.type) {
                ParamType.CHOICE -> {
                    Text(
                        text = spec.label,
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    // CHOICE 选项数量/文案不可预期（自定义命令）：窄面板 FlowRow 换行兜底
                    FlowRow(
                        horizontalArrangement = Arrangement.spacedBy(Dimens.space4),
                        verticalArrangement = Arrangement.spacedBy(Dimens.space4),
                    ) {
                        spec.options.forEach { option ->
                            FilterChip(
                                selected = (values[spec.id] ?: spec.default) == option,
                                onClick = { onValueChange(spec.id, option) },
                                                                colors = FilterChipDefaults.filterChipColors(
                                    selectedContainerColor = MaterialTheme.colorScheme.primary
                                        .copy(alpha = AppAlpha.hotWash),
                                    selectedLabelColor = MaterialTheme.colorScheme.primary,
                                ),
shape = AppShapes.capsule, // 轻标签胶囊档（M3 默认 8dp 角显式归一）
                                label = { Text(option) },
                            )
                        }
                    }
                }
                else -> {
                    val current = values[spec.id] ?: spec.default.orEmpty()
                    val isFile = spec.type == ParamType.FILE
                    val fileExists = !isFile || current.isBlank() || File(current).exists()
                    // NUMBER 合法性（isError 反馈语义——放开小数/负数后的
                    // 非法串防线）：与 FILE 存在性同款「错误提示不吞键」风格，
                    // 非法输入照常进入编辑态，isError + supportingText 实时反馈
                    val isNumber = spec.type == ParamType.NUMBER
                    val numberInvalid = isNumber && current.isNotBlank() && current.toDoubleOrNull() == null
                    val invalid = (isFile && current.isNotBlank() && !fileExists) || numberInvalid
                    Row(horizontalArrangement = Arrangement.spacedBy(Dimens.space4)) {
                        OutlinedTextField(
                            value = current,
                            onValueChange = { input -> onValueChange(spec.id, input) },
                            label = { Text(if (spec.required) spec.label else "${spec.label}（可选）") },
                            placeholder = spec.placeholder?.let { { Text(it) } },
                            singleLine = true,
                            textStyle = DesktopTypography.mono,
                            isError = invalid,
                            supportingText = when {
                                invalid && isFile -> {
                                    { Text("文件不存在（检查路径后重试）") }
                                }
                                invalid -> {
                                    { Text("请输入有效数字") }
                                }
                                else -> null
                            },
                            modifier = Modifier
                                .weight(1f)
                                // 首参数框挂抢焦链（palette 进表单态焦点转移——Enter 执行
                                // 收口在面板根拦截层，参数框零键处理仍可达）
                                .then(
                                    if (spec == params.firstOrNull() && focusRequester != null) {
                                        Modifier.focusRequester(focusRequester)
                                    } else {
                                        Modifier
                                    },
                                ),
                        )
                        if (isFile) {
                            OutlinedButton(
                                onClick = {
                                    // AWT FileDialog 模态阻塞，放 IO 协程避免冻结 Compose UI 线程
                                    // （PageSettings「浏览 adb」/FileDropZone pickFiles 同款先例）
                                    scope.launch(Dispatchers.IO) {
                                        pickOpenFiles(title = "选择${spec.label}").firstOrNull()?.let { file ->
                                            onValueChange(spec.id, file.absolutePath)
                                        }
                                    }
                                },
                            ) { Text("浏览…") }
                        }
                    }
                }
            }
        }
    }
}

/**
 * AWT 系统文件选择器（FileDialog——各平台原生外观；本仓文件选择统一先例：
 * FileDropZone.pickFiles / PageSettings.pickAdbExecutable / WorkbenchViewModel）。
 * 选择器标题带参数标签（多 FILE 参数可区分）；取消返回 null。
 * 🔴 必须在 IO 线程调用（模态阻塞——UI 线程调用即冻结界面至选择器关闭）；调用方协程承接。
 */


/**
 * 必填参数是否填齐（执行按钮使能判定——Enter 执行前同一谓词）。
 * FILE 参数额外要求存在性校验通过（文件不存在 = 未就绪）。
 * NUMBER 参数额外要求合法性（非空且 toDoubleOrNull 解析失败 = 未就绪——
 * 防非法串 Enter 直执行被 argsToJson 的 toDoubleOrNull 静默丢参）。
 */
internal fun paramFormReady(params: List<ParamSpec>, values: Map<String, String>): Boolean =
    params.all { spec ->
        if (!spec.required) {
            // 可选参数非法串也拦（非空即须合法）；空串保持「未填」语义走默认值链
            val optionalValue = values[spec.id].orEmpty()
            if (spec.type == ParamType.NUMBER && optionalValue.isNotBlank() && optionalValue.toDoubleOrNull() == null) {
                return@all false
            }
            return@all true
        }
        val value = values[spec.id]?.takeIf { it.isNotBlank() } ?: spec.default?.takeIf { it.isNotBlank() }
            ?: return@all false
        if (spec.type == ParamType.FILE && !File(value).exists()) return@all false
        if (spec.type == ParamType.NUMBER && value.toDoubleOrNull() == null) return@all false
        true
    }
