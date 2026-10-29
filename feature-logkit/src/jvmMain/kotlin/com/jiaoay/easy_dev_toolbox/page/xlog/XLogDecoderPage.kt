package com.jiaoay.easy_dev_toolbox.page.xlog

import dev.vicart.compose.material.symbols.MaterialSymbols

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
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
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.sp
import com.jiaoay.easy_dev_toolbox.core.llm.workbench.DeepAnalyzeHook
import com.jiaoay.easy_dev_toolbox.core.navigation.GoXLogDecryptHook
import com.jiaoay.easy_dev_toolbox.core.navigation.SmartPrefillHook
import com.jiaoay.easy_dev_toolbox.core.navigation.TaskPipelineHandoff
import com.jiaoay.easy_dev_toolbox.core.navigation.ToolUsageSignal
import com.jiaoay.easy_dev_toolbox.core.runtime.StatusSignals
import com.jiaoay.easy_dev_toolbox.core.theme.Dimens
import com.jiaoay.easy_dev_toolbox.core.theme.DesktopTypography
import com.jiaoay.easy_dev_toolbox.widget.AppButton
import com.jiaoay.easy_dev_toolbox.widget.AppButtonKind
import com.jiaoay.easy_dev_toolbox.widget.StatusBanner
import com.jiaoay.easy_dev_toolbox.widget.StatusBannerStatus
import com.jiaoay.easy_dev_toolbox.widget.expandDroppedEntries
import com.jiaoay.easy_dev_toolbox.widget.task.FileDropZoneSpec
import com.jiaoay.easy_dev_toolbox.widget.task.SendToNextMenuEntry
import com.jiaoay.easy_dev_toolbox.widget.task.TaskPageTemplate
import java.io.File

/**
 * XLog 解析页（骨架收敛至 TaskPageTemplate）：参数区 = 私钥输入框
 * （带显隐切换）。
 *
 * 产物卡「深度分析」情境动作（P4-C2 6.2）：成功态产物经 [DeepAnalyzeHook]
 * 中立槽投递——宿主接线后跳工作台并预填产物路径（feature 互不依赖铁律下
 * 本页不 import feature-logworkbench；宿主未接线时按钮不渲染）。
 *
 * 产物卡「送往 Tag 过滤」条目（管线衔接）：解密产物（`.txt`）即 Tag 过滤的
 * 输入类型——目标查 [TaskPipelineHandoff] 单张表（本页不自持映射），产物路径
 * 经同一 :core 槽投递进命令面板参数直填态；宿主未接线或产物无映射时条目不出现。
 *
 * 跳页槽消费（解压产物多文件分支的承接面）：组合期 [GoXLogDecryptHook.consumePendingDirectory]
 * 取目录路径，按目录展开粗滤口径（[isXLogFile]，与拖放区目录枚举一致；直拖/点击
 * 入口已放宽为内容判型）展开后 [addFiles] 批量入卡（含覆盖裁决）；embedded 聚合页
 * 子签形态同体消费——组合即触发。
 *
 * 剪贴板预填消费（palette 推荐行跳页链）：`.xlog` 路径载荷驻
 * [SmartPrefillHook] 槽，本页按槽流渲染「待确认」行——用户点「入卡解析」才
 * 消费落地（与跳页槽共用 [landDroppedPath] 落地分流单源），点「忽略」即丢弃。
 * 组合期不消费预填载荷（入卡即执行，预填不自动执行）。
 *
 * [embedded] 参数——「工具」聚合页页内子签复用
 * 无头形态；私钥状态显性化——未配置时参数区顶置 Warning 引导
 *（解析是「拖入失败才知道」的隐形硬前置，须前置可见）。
 */
@Composable
fun XLogDecoderPage(embedded: Boolean = false) {
    val viewModel = XLogDecoderViewModel.getViewModel()
    val privateKey by viewModel.privateKey.collectAsState()

    var showKey by remember { mutableStateOf(false) }

    // palette 剪贴板预填的待确认面：载荷驻槽不读走（入卡即按拖放语义立即提交任务
    // = 执行——预填不自动执行红线要求用户点「入卡解析」确认后才消费落地）
    val pendingPrefill =
        SmartPrefillHook.pending.collectAsState().value as? SmartPrefillHook.Payload.XLogFilePath

    // 跳页槽与预填槽共用落地（分流单源）：路径按目录展开粗滤（[isXLogFile]，与
    // 拖放区目录枚举同口径；直拖/点击入口已放宽为内容判型，不经此粗滤）展开后
    // [addFiles] 批量入卡（含覆盖裁决）；载荷时刻与落地时刻间目录内容可能漂移，
    // 零匹配/超限就地 toast 回执（拒绝必带原因），不静默丢弃
    fun landDroppedPath(path: String) {
        val outcome = expandDroppedEntries(
            entries = listOf(File(path)),
            accepts = ::isXLogFile,
            expandDirectories = true,
            fallbackRejectText = NO_XLOG_MATCH_TEXT,
        )
        if (outcome.files.isNotEmpty()) {
            viewModel.addFiles(outcome.files)
        } else {
            StatusSignals.show(outcome.rejectReason ?: NO_XLOG_MATCH_TEXT)
        }
    }

    // 跳页槽消费（读即清一次性载荷）：解压页「送 XLog 解密」多文件分支跳来后
    // 即时落地；embedded 聚合页子签形态同体消费——组合即触发
    LaunchedEffect(Unit) {
        GoXLogDecryptHook.consumePendingDirectory()?.let { landDroppedPath(it) }
    }

    TaskPageTemplate(
        title = "XLog 解密",
        viewModel = viewModel,
        taskTitle = "解析任务",
        emptyHint = "还没有解析任务", // 页别空态文案
        embedded = embedded,
        onDeepAnalyze = { outputPath ->
            DeepAnalyzeHook.offer(listOf(outputPath), "XLog 解密产物深度分析")
        },
        // 管线衔接：宿主接线（条目开关真源）+ 产物来源映射查表——两缺一即条目缺席
        sendToNext = if (TaskPipelineHandoff.isWired) {
            TaskPipelineHandoff.nextToolTargetOf(ToolUsageSignal.PageId.XLOG_DECODER)?.let { target ->
                SendToNextMenuEntry(target.label) { productPath ->
                    TaskPipelineHandoff.offer(target, productPath)
                }
            }
        } else {
            null
        },
        paramsSlot = {
            // 剪贴板预填待确认行（置顶——先于私钥引导；确认/忽略都是显式点击，
            // 消费只发生在点击点，组合期不消费预填载荷）
            pendingPrefill?.let { prefill ->
                StatusBanner(
                    status = StatusBannerStatus.Info,
                    text = "剪贴板识别到 ${File(prefill.path).name}——已预填，确认后入卡解析",
                    trailing = {
                        Row(
                            horizontalArrangement = Arrangement.spacedBy(Dimens.space8),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            AppButton(
                                label = "入卡解析",
                                busy = false,
                                kind = AppButtonKind.Primary,
                                onClick = {
                                    SmartPrefillHook.consumeIfType<SmartPrefillHook.Payload.XLogFilePath>()
                                        ?.let { landDroppedPath(it.path) }
                                },
                            )
                            AppButton(
                                label = "忽略",
                                busy = false,
                                kind = AppButtonKind.Secondary,
                                onClick = {
                                    SmartPrefillHook.consumeIfType<SmartPrefillHook.Payload.XLogFilePath>()
                                },
                            )
                        }
                    },
                )
            }
            // 私钥未配置显性引导（加密日志解析的硬前置，拖入前可见）
            if (privateKey.isBlank()) {
                StatusBanner(
                    status = StatusBannerStatus.Warning,
                    text = "尚未配置 xlog 私钥——加密日志解析前必填；输入一次即持久保存",
                )
            }
            OutlinedTextField(
                value = privateKey,
                onValueChange = viewModel::onPrivateKeyChange,
                label = { Text("私钥") }, // placeholder/KDoc 场景词「xlog 私钥」保留
                placeholder = { Text("输入 xlog 私钥（加密日志必填）") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
                visualTransformation = if (showKey) VisualTransformation.None else PasswordVisualTransformation(),
                trailingIcon = {
                    IconButton(onClick = { showKey = !showKey }) {
                        Text(
                            text = if (showKey) "隐藏" else "显示",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.primary
                        )
                    }
                },
                textStyle = DesktopTypography.monoInput
            )
        },
        dropZone = FileDropZoneSpec(
            icon = MaterialSymbols.FILE_UPLOAD,
            title = "拖入或点击选择日志文件（支持多选与目录）",
            dragTitle = "松开以解析文件",
            desc = "直拖/点击的文件按内容自动判型：加密 xlog/alog 走解密，明文日志直接入卡；目录按 .xlog / .alog 后缀枚举",
            // 双轨过滤：目录展开粗滤口径与跳页槽一致（[isXLogFile] 单源——两入口目录
            // 语义不漂移）；直拖/点击是用户点名入口，恒放行交 VM 内容判型
            // （明文不产新文件、未知格式明示拒绝，见 [XLogDecoderViewModel]）
            accepts = ::isXLogFile,
            acceptsDroppedFiles = { true },
            // 目录一等输入：解压产物目录常含几十个日志文件，整目录拖入即批量入任务
            acceptsDirectories = true,
        ),
    )
}

/**
 * `.xlog` / `.alog` 扩展名判定：本页目录展开场景的粗滤单源——拖放区目录枚举与
 * 跳页槽目录展开共用，两入口的「什么算 XLog 目录输入」语义不漂移；`.XLOG` 等
 * 大写扩展名不拒。直拖/点击入口不经过本判定（拖放区 [FileDropZone] 的
 * acceptsDroppedFiles 通道恒放行）——用户点名的文件交 [XLogDecoderViewModel]
 * 内容判型三态路由（加密/明文/未知），后缀只作目录批量场景的高效粗滤。
 */
internal fun isXLogFile(file: File): Boolean =
    file.extension.equals("xlog", ignoreCase = true) ||
        file.extension.equals("alog", ignoreCase = true)

/** 跳页槽展开零匹配回退文案（零匹配目录的常规原因由 [expandDroppedEntries] 给足） */
private const val NO_XLOG_MATCH_TEXT = "目录内没有可处理的 .xlog / .alog 文件"
