package com.jiaoay.easy_dev_toolbox.page.filesplit

import dev.vicart.compose.material.symbols.MaterialSymbols

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.jiaoay.easy_dev_toolbox.core.llm.workbench.DeepAnalyzeHook
import com.jiaoay.easy_dev_toolbox.core.theme.Dimens
import com.jiaoay.easy_dev_toolbox.widget.CommitNumberField
import com.jiaoay.easy_dev_toolbox.widget.task.FileDropZoneSpec
import com.jiaoay.easy_dev_toolbox.widget.task.TaskPageTemplate

/**
 * 文件分割页（骨架收敛至 TaskPageTemplate）：参数区 = 分割大小数字框。
 *
 * [embedded] 参数——「工具」聚合页页内子签复用无头形态。
 *
 * 成功卡「深度分析」情境动作——**全卷**经 [DeepAnalyzeHook]
 * 中立槽投递跳工作台预填（XLog 页同款配方；只投首卷会把深度分析静默限死在
 * 片段 1/N——下游 prefill 本就支持多文件，按分卷名形扫同目录兄弟卷投全量）。
 */
@Composable
fun FileSplitterPage(embedded: Boolean = false) {
    val viewModel = FileSplitterViewModel.getViewModel()
    val sizeMb by viewModel.sizeMb.collectAsState()

    TaskPageTemplate(
        title = "文件分割",
        viewModel = viewModel,
        taskTitle = "分割任务",
        // 页别空态文案（旧单要素文案字面零残留由 TaskEmptyHintGuardTest 钉死）
        emptyHint = "还没有分割任务",
        embedded = embedded,
        // 成功卡「深度分析」→ 工作台预填（全卷；XLog 页同款配方）
        onDeepAnalyze = { outputPath ->
            val volumes = allVolumesOf(outputPath)
            val note = if (volumes.size > 1) "（${volumes.size} 卷）" else ""
            DeepAnalyzeHook.offer(volumes, "文件分割产物深度分析$note")
        },
        paramsSlot = {
            // CommitNumberField：外部 value 即重置语义，DataStore 恢复真实值后自动回填
            //（旧 remember { sizeMb.toString() } 只取首组合瞬间值，恢复后显示值与生效值不一致）；
            // 失焦提交 + 非法终值不提交，配合 VM 的 Validation.Error 拒绝整批拖入
            // frow 三段 + mono 值 + 框尾「MB」pill mute 单位徽标
            com.jiaoay.easy_dev_toolbox.widget.FormRow(
                label = "分割大小",
                hint = "每个分片不超过此大小（MB），需 ≥ 1",
            ) {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(Dimens.space4)) {
                    CommitNumberField(
                        label = "分割大小",
                        value = sizeMb,
                        onCommit = viewModel::onSizeMbChange,
                        labelInline = false,
                        monoText = true,
                        isValid = { it >= 1 },
                        modifier = Modifier.width(Dimens.fieldControlNarrowWidth),
                    )
                    com.jiaoay.easy_dev_toolbox.widget.PillLabel(
                        text = "MB",
                        container = com.jiaoay.easy_dev_toolbox.core.theme.LocalToolboxSurfaceColors.current.surfaceDeep,
                        contentColor = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        },
        dropZone = FileDropZoneSpec(
            icon = MaterialSymbols.CALL_SPLIT,
            title = "拖入或点击选择 .txt 文件（支持多选）",
            dragTitle = "松开以分割文件",
            desc = "将按设定大小分割为多个文件",
            accepts = { it.extension.equals("txt", ignoreCase = true) },   // .TXT 等大写扩展名不拒
        ),
    )
}

/**
 * 首卷路径 → 同目录同前缀全卷（卷名字典序 = 三位零填充卷号数值序，≤999 卷有序）。
 * 路径非分卷名形（单文件语义或外部产物）→ 原样单元素返回（零投递面回归）。
 * 顶层 internal fun：feature-logkit jvmTest 同包直测缝。
 */
internal fun allVolumesOf(firstVolumePath: String): List<String> {
    val file = java.io.File(firstVolumePath)
    val prefix = file.nameWithoutExtension.substringBeforeLast("_")
    val pattern = Regex("^${Regex.escape(prefix)}_\\d{3}\\.txt$")
    val siblings = file.parentFile
        ?.listFiles { f -> f.isFile && pattern.matches(f.name) }
        .orEmpty()
    if (siblings.isEmpty()) return listOf(firstVolumePath)
    return siblings.sortedBy { it.name }.map { it.absolutePath }
}
