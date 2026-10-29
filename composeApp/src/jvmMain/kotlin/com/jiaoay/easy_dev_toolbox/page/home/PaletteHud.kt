package com.jiaoay.easy_dev_toolbox.page.home

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.ui.draw.clip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import com.jiaoay.easy_dev_toolbox.core.data.DesktopUtils
import com.jiaoay.easy_dev_toolbox.core.llm.workbench.DeepAnalyzeHook
import com.jiaoay.easy_dev_toolbox.core.navigation.ParamCommandResult
import com.jiaoay.easy_dev_toolbox.core.navigation.TaskPipelineHandoff
import com.jiaoay.easy_dev_toolbox.core.navigation.TaskPipelineHandoff.NextToolTarget
import com.jiaoay.easy_dev_toolbox.core.navigation.ToolUsageSignal
import com.jiaoay.easy_dev_toolbox.core.runtime.LogViewerRequest
import com.jiaoay.easy_dev_toolbox.core.theme.AppAlpha
import com.jiaoay.easy_dev_toolbox.core.theme.AppShapes
import com.jiaoay.easy_dev_toolbox.core.theme.Dimens
import com.jiaoay.easy_dev_toolbox.widget.AppSymbolIcon
import dev.vicart.compose.material.symbols.MaterialSymbols
import java.io.File
import com.jiaoay.easy_dev_toolbox.core.theme.LocalToolboxSurfaceColors

/**
 * 文本日志形态产物的扩展名清单（产物 chip 点击动作的唯一判定表——产物类型→
 * 动作分流单点收敛在此，勿在他处再列扩展名判断）：清单内产物点击进应用内
 * 查看器，清单外产物保留系统默认程序打开。判据取扩展名清单而非内容探测：
 * HUD 产物恒为本应用自产（解密/过滤/分割/时间截取产出 .txt/.log、日志对比
 * 导出 .diff、文本报告 .md），扩展名即产物形态的可靠信号；加密产物
 * （.xlog/.alog）不在清单内，进查看器的先解密引导由查看器 Failed 态自带。
 * 扩展名大小写不敏感。internal：动作分流判据单测锚。
 */
internal val TEXT_LOG_ARTIFACT_EXTENSIONS = setOf("txt", "log", "md", "diff")

/**
 * 产物是否文本日志形态（chip 点击动作分流依据——判定表单源
 * [TEXT_LOG_ARTIFACT_EXTENSIONS]）。无扩展名路径（如解压产物目录）恒非文本形态。
 * internal：动作分流判据单测锚。
 */
internal fun isTextLogArtifact(path: String): Boolean =
    path.substringAfterLast('.').lowercase() in TEXT_LOG_ARTIFACT_EXTENSIONS

/**
 * HUD 产物续跑动作形态（[nextStepOfCommand] 的产物——按当前命令 id 桥接映射）。
 */
internal sealed interface HudNextStep {
    /**
     * 管线衔接（[TaskPipelineHandoff] PIPELINE 表目标）：投递衔接槽后 palette
     * 立即切到目标命令参数直填态（产物路径预填）。
     */
    data class Pipeline(val target: NextToolTarget) : HudNextStep

    /** 深度分析直投（[DeepAnalyzeHook] 中立槽——工作台预填，palette 退场） */
    data object DeepAnalyze : HudNextStep
}

/**
 * 命令 id → 产物续跑动作（palette 直跑产物「送往下一步」的唯一映射面；
 * 纯函数——单测锚）。映射语义**复用** [TaskPipelineHandoff] 的 PIPELINE 表：
 * 桥接 = 命令 id → 产物来源页 id 查表，不复制第二张映射表；Tag 过滤产物主
 * 去向是深度分析（PIPELINE KDoc 既有条目语义），走 [DeepAnalyzeHook] 中立槽。
 * 未映射命令返回 null（HUD 不出现续跑钮——管线终点/无产物身份不虚标，
 * 与 PIPELINE「未登记来源不出现条目」同口径）。
 */
internal fun nextStepOfCommand(commandId: String): HudNextStep? = when (commandId) {
    // XLog 解密产物（.txt）→ Tag 过滤（管线主干衔接——按产物来源页查表）
    "logkit.xlog-decrypt" -> TaskPipelineHandoff
        .nextToolTargetOf(ToolUsageSignal.PageId.XLOG_DECODER)
        ?.let(HudNextStep::Pipeline)
    // 时间截取产物（.txt 切片）→ Tag 过滤（切片是本级产物子集，读面产物同语义
    // ——经 LOG_VIEWER 行查表）
    "tools.slice-time" -> TaskPipelineHandoff
        .nextToolTargetOf(TaskPipelineHandoff.SourcePageId.LOG_VIEWER)
        ?.let(HudNextStep::Pipeline)
    // Tag 过滤产物（已按 Tag 收敛）→ 深度分析（工作台 AI 研判主料直投）
    "logkit.tag-filter" -> HudNextStep.DeepAnalyze
    else -> null
}

/**
 * 续跑钮动作标签（单源——Pipeline 取目标情境文案、DeepAnalyze 固定直投语义）。
 * internal：单测锚。
 */
internal fun hudNextStepLabel(step: HudNextStep): String = when (step) {
    is HudNextStep.Pipeline -> step.target.label
    HudNextStep.DeepAnalyze -> "送工作台分析"
}

/**
 * 带参命令的 HUD 输出区（命令执行结果面板）。
 *
 * 形态：结果 summary（成功 primary / 失败 error）+ 产物 chips（文本日志形态产物
 * 点击进应用内查看器——经 [LogViewerRequest] 写槽、宿主根叠层挂载查看 Dialog，
 * 行号跳转/级别导航/文本搜索不出应用；其余产物点击系统默认程序打开——
 * DesktopUtils.openFile，失败降级所在目录）+ 操作行（「返回」
 * 回列表态 Esc 同源 / 产物续跑钮（[nextStepOfCommand] 桥接非空且成功单产物——
 * Pipeline 走衔接槽投递切下一命令参数直填态、DeepAnalyze 走深链槽工作台预填）/
 * openPage 非 null 时「打开工具页」深入模式钮——路由归并聚合页）。
 *
 * HUD 即回执：命令执行完面板不自动关（用户要看产物/复制路径）；Esc/点击外部
 *    照旧关面板（Popup onDismiss 通道不变）。
 * HUD 即回执不自动闪退：执行结果含产物路径/复制入口，自动闪退会剥夺
 *    用户查看产物的机会，故为「HUD 即回执，
 *    显式返回/Esc 才退」——有意偏离早期「一闪即走」草案，非遗漏。
 */
@Composable
internal fun PaletteHud(
    result: ParamCommandResult,
    onBack: () -> Unit,
    onOpenPage: (() -> Unit)?,
    /**
     * 产物续跑动作（[nextStepOfCommand] 按当前命令 id 桥接——null = 无映射，
     * 不出现续跑钮；管线终点/未登记命令不虚标）。
     */
    nextStep: HudNextStep?,
    /** 续跑执行（单产物路径入参——Pipeline 走衔接槽 / DeepAnalyze 走深链槽） */
    onSendToNext: (String) -> Unit,
) {
    Column(
        verticalArrangement = Arrangement.spacedBy(Dimens.space8),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Text(
            text = result.summary,
            style = MaterialTheme.typography.bodyMedium,
            color = if (result.success) {
                MaterialTheme.colorScheme.onSurface
            } else {
                MaterialTheme.colorScheme.error
            },
            modifier = Modifier.padding(vertical = Dimens.space4),
        )
        if (result.artifacts.isNotEmpty()) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(Dimens.space4),
            ) {
                Text(
                    text = "产物",
                    style = MaterialTheme.typography.labelSmall,
                    color = LocalToolboxSurfaceColors.current.onSurfaceFaint,
                )
                result.artifacts.forEach { path ->
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(Dimens.space4),
                        modifier = Modifier
                            // chip 点击按产物形态分流（判定单源 [isTextLogArtifact]）：
                            // 文本日志产物进应用内查看器（打开失败含加密产物先解密
                            // 引导由查看器 Failed 态自带），其余产物系统默认程序打开
                            // （openFile 失败降级所在目录）——视觉文案保持文件名，
                            // chip 语义经 contentDescription 无障碍描述自明
                            .clip(AppShapes.capsule)
                            .clickable {
                                if (isTextLogArtifact(path)) {
                                    LogViewerRequest.offer(path)
                                } else {
                                    DesktopUtils.openFile(path)
                                }
                            }
                            .padding(horizontal = Dimens.space4, vertical = Dimens.space4),
                    ) {
                        AppSymbolIcon(
                            icon = MaterialSymbols.FOLDER_OPEN,
                            contentDescription = "打开产物",
                            size = Dimens.iconSmall,
                            tint = MaterialTheme.colorScheme.primary,
                        )
                        Text(
                            text = path.substringAfterLast(File.separatorChar),
                            style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.primary,
                        )
                    }
                }
            }
        }
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(Dimens.space12),
        ) {
            Text(
                text = "← 返回列表",
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.primary,
                modifier = Modifier
                    .clip(AppShapes.capsule)
                    .clickable(onClick = onBack)
                    .padding(vertical = Dimens.space4),
            )
            // 产物续跑钮（palette 直跑产物「送往下一步」——页面产物卡管线衔接面
            // 的对偶面）。渲染门：成功态 + 恰单产物（映射命令皆单产物生产者，
            // 多产物/失败不虚标续跑目标）
            if (nextStep != null && result.success && result.artifacts.size == 1) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(Dimens.space4),
                    modifier = Modifier
                        .clip(AppShapes.capsule)
                        .clickable { onSendToNext(result.artifacts.single()) }
                        .padding(vertical = Dimens.space4),
                ) {
                    AppSymbolIcon(
                        icon = MaterialSymbols.CHEVRON_RIGHT,
                        contentDescription = "送往下一步",
                        size = Dimens.iconSmall,
                        tint = MaterialTheme.colorScheme.primary,
                    )
                    Text(
                        text = hudNextStepLabel(nextStep),
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.primary,
                    )
                }
            }
            if (onOpenPage != null) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(Dimens.space4),
                    modifier = Modifier
                        .clip(AppShapes.capsule)
                        .clickable(onClick = onOpenPage)
                        .padding(vertical = Dimens.space4),
                ) {
                    AppSymbolIcon(
                        icon = MaterialSymbols.OPEN_IN_NEW,
                        contentDescription = null,
                        size = Dimens.iconSmall,
                        tint = MaterialTheme.colorScheme.primary,
                    )
                    Text(
                        // 深入模式路由经 hostOf 归并落聚合页——
                        // 文案对齐落地语义（HUD/参数直填态/结果行三处同步，防并存）
                        text = "打开工具页",
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.primary,
                    )
                }
            }
        }
    }
}
