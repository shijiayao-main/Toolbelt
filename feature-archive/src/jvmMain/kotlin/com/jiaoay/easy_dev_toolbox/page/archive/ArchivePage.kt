package com.jiaoay.easy_dev_toolbox.page.archive

import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import com.jiaoay.easy_dev_toolbox.core.navigation.GoXLogDecryptHook
import com.jiaoay.easy_dev_toolbox.core.navigation.TaskPipelineHandoff
import com.jiaoay.easy_dev_toolbox.core.navigation.ToolUsageSignal
import com.jiaoay.easy_dev_toolbox.core.runtime.StatusSignals
import com.jiaoay.easy_dev_toolbox.widget.expandDroppedEntries
import com.jiaoay.easy_dev_toolbox.widget.task.FileDropZoneSpec
import com.jiaoay.easy_dev_toolbox.widget.task.SendToNextMenuEntry
import com.jiaoay.easy_dev_toolbox.widget.task.TaskPageTemplate
import com.jiaoay.utils.SafeExtract
import dev.vicart.compose.material.symbols.MaterialSymbols
import java.io.File

/**
 * 压缩包解压页：拖入 zip / tar / tar.gz / tgz / gz → 解到同级 `${文件名}_extracted/`。
 *
 * 🔴 **零 AI 依赖**（本页存在的意义）：解压走 [SafeExtract] 的纯 JDK 实现，
 * 不调用任何系统命令、不依赖本机环境、不需要配置任何模型服务或 agent——
 * 未配置 LLM 的用户同样完整可用。
 *
 * 页面骨架收敛至 [TaskPageTemplate]（四胞胎模板）：本页无参数区
 * （paramsSlot 不传），拖放区差异经 [FileDropZoneSpec] 参数化。
 *
 * 产物卡「送 XLog 解密」条目（管线衔接两分支契约，[TaskPipelineHandoff]
 * ARCHIVE 行）：解压产物是**目录**，条目动作内先枚举目录内 `.xlog`/`.alog`
 * 分流——**单文件**经 [TaskPipelineHandoff.offer] 直投该文件进命令面板参数
 * 直填态；**多文件**经 [GoXLogDecryptHook] 跳页槽携目录路径去 XLog 解密页
 * 批量入卡；**零匹配**就地 toast 回执原因（不投递）。
 *
 * [embedded] 参数——「工具」聚合页页内子签复用无头形态。
 */
@Composable
fun ArchivePage(embedded: Boolean = false) {
    val viewModel = ArchiveViewModel.getViewModel()

    TaskPageTemplate(
        title = "解压",
        viewModel = viewModel,
        taskTitle = "解压任务",
        emptyHint = "还没有解压任务", // 页别空态文案
        embedded = embedded,
        // 管线衔接两分支：映射查表 + 「单文件直投 | 多文件跳页」两路宿主接线——
        // 两路均未接线或映射缺行时条目缺席（XLog 解密页 sendToNext 同款门控形态）
        sendToNext = TaskPipelineHandoff.nextToolTargetOf(ToolUsageSignal.PageId.ARCHIVE)
            ?.takeIf { TaskPipelineHandoff.isWired || GoXLogDecryptHook.isWired }
            ?.let { target ->
                SendToNextMenuEntry(target.label) { productPath ->
                    sendArchiveProductToXLogDecrypt(productPath, target)
                }
            },
        dropZone = FileDropZoneSpec(
            icon = MaterialSymbols.FOLDER_ZIP,
            title = "拖入压缩包或分卷（支持多选）",
            dragTitle = "松开以解压",
            desc = "支持 ${ArchiveViewModel.SUPPORTED_FORMATS}，解到同级 ${ArchiveViewModel.EXTRACTED_SUFFIX} 目录",
            // 拖放区放行分卷后缀（本地判定扩展）——SafeExtract.isArchive 是
            // 三域共享判定集（feature-ai extract_archive / lark-bridge autoExtract），
            // 不动；分卷识别走 SplitArchive；.z01 族与 .7z 走本页本地判定
            // （VolumeAndSevenZip）——共享判定集仍不扩（桥接域预处理行为不因本页扩容而变）。
            accepts = {
                SafeExtract.isArchive(it.name) ||
                    SplitArchive.isSplitArchiveName(it.name) ||
                    VolumeAndSevenZip.isTier1ArchiveName(it.name)
            },
        ),
    )
}

/**
 * 解压产物「送 XLog 解密」三分支分流（产物目录 → XLog 解密域）。
 *
 * 先以 `.xlog`/`.alog` accepts 同步枚举产物目录（[expandDroppedEntries]——
 * 上限闸与整批判拒语义与拖入目录一等输入同源），再按命中数分流：
 * - **1 个**：[TaskPipelineHandoff.offer] 直投该文件——单值 palette 参数直填态
 *   的对口形态，用户补私钥即执行；
 * - **≥2 个**：[GoXLogDecryptHook.offer] 携**目录路径**（非预展开清单——accepts
 *   过滤语义单源留在 XLog 解密域，消费侧页内同款 accepts 展开）跳 XLog 解密页
 *   批量入任务卡（含覆盖裁决）；
 * - **0 个**：不投递，toast 回执原因（[expandDroppedEntries] 零匹配/超限原因
 *   原样呈现，回退文案兜底路径缺失等展开层给不出原因的分支）。
 *
 * internal = 单测三分支断言入口（组合期条目动作只做透传）。
 */
internal fun sendArchiveProductToXLogDecrypt(
    productPath: String,
    target: TaskPipelineHandoff.NextToolTarget,
) {
    val outcome = expandDroppedEntries(
        entries = listOf(File(productPath)),
        accepts = ::isXLogOrAlogFile,
        expandDirectories = true,
        fallbackRejectText = NO_XLOG_MATCH_TEXT,
    )
    when {
        outcome.files.size == 1 ->
            TaskPipelineHandoff.offer(target, outcome.files.single().absolutePath)
        outcome.files.isNotEmpty() ->
            GoXLogDecryptHook.offer(File(productPath).absolutePath)
        else ->
            // 零匹配（含超限整批判拒）：展开层原因回执 + 替代去向指引（纯文本日志包
            // 走 Tag 过滤页拖目录——消费面 acceptsDirectories 已在；指引静态拼接不做
            // 第二次目录枚举，宁拒不猜「用户想要过滤」）；rejectReason null = 展开层
            // 给不出原因的分支（如产物路径已不在盘）
            StatusSignals.show((outcome.rejectReason ?: NO_XLOG_MATCH_TEXT) + NO_MATCH_GUIDANCE)
    }
}

/** `.xlog` / `.alog` 扩展名判定（ignoreCase；与 XLog 解密页拖放 accepts 同一扩展名集） */
private fun isXLogOrAlogFile(file: File): Boolean =
    file.extension.equals("xlog", ignoreCase = true) ||
        file.extension.equals("alog", ignoreCase = true)

/** 零匹配回退文案（展开层给不出原因时的兜底；零匹配目录的常规原因由展开层给足） */
private const val NO_XLOG_MATCH_TEXT = "目录内没有可处理的 .xlog / .alog 文件"

/** 零匹配回执的去向指引段（静态拼接——Tag 过滤页整目录拖入是其既有能力，回执指路免出应用回找） */
private const val NO_MATCH_GUIDANCE = "——纯文本 .txt 日志可整目录拖入「Tag 过滤」页"
