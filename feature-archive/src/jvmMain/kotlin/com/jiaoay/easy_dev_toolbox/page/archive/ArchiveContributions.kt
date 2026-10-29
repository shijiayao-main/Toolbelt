package com.jiaoay.easy_dev_toolbox.page.archive

import dev.vicart.compose.material.symbols.MaterialSymbols

import com.jiaoay.easy_dev_toolbox.core.navigation.ParamCommandResult
import com.jiaoay.easy_dev_toolbox.core.navigation.ParamCommandSpec
import com.jiaoay.easy_dev_toolbox.core.navigation.ParamSpec
import com.jiaoay.easy_dev_toolbox.core.navigation.ParamType
import com.jiaoay.easy_dev_toolbox.core.navigation.ToolPageRegistration
import com.jiaoay.easy_dev_toolbox.core.navigation.ToolPageGroup
import com.jiaoay.easy_dev_toolbox.page.ToolPage
import com.jiaoay.utils.SafeExtract
import java.io.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * 解压域的导航注册出口。
 *
 * 🔴 本页**永不被 AI 工具开关隐藏**：解压是纯本地能力（SafeExtract 的 JDK 实现），
 * 与 LLM/agent 零关系——故注册项不接 `navVisible`（保持默认恒可见）。
 *
 * 导航入口收敛：navItem=null（tab 目录/dock 不再展示），content 保留注册
 * （palette 深入模式 openPage 可达）；页面能力以带参命令形态存活。
 *
 * 命令面覆盖范围：ArchiveViewModel.process 含分卷族/7z/PKZip/
 *    串联还原四路分流（页面局部态），命令薄转发只覆盖**普通压缩包**（SafeExtract
 *    直调 = zip/tar/tar.gz/tgz/gz）；分卷/PKZip/7z 文件名识别后返回引导文案 +
 *    openPage 深入模式兜底（分卷组卡是页面局部态，命令面刻意不复制该建模）。
 */
object ArchiveContributions {

    /** 带参命令：解压压缩包（普通包直调 SafeExtract；分卷/7z 识别后引导进页面） */
    private val extractCommand = ParamCommandSpec(
        id = "archive.extract",
        label = "解压压缩包",
        desc = "zip / tar / tar.gz / tgz / gz 解压到同级 _extracted 目录（分卷与 7z 请进页面处理）",
        icon = MaterialSymbols.FOLDER_ZIP,
        keywords = listOf("解压", "extract", "unzip", "压缩", "zip", "tar", "gz"),
        params = listOf(
            ParamSpec(
                id = "file_path",
                label = "压缩包路径",
                type = ParamType.FILE,
                placeholder = "zip / tar / tar.gz / tgz / gz 文件绝对路径",
            ),
        ),
        openPage = ToolPage.Archive,
        performArgs = { args ->
            val path = args["file_path"].orEmpty()
            if (path.isBlank()) {
                return@ParamCommandSpec ParamCommandResult(success = false, summary = "未填写压缩包路径")
            }
            withContext(Dispatchers.IO) {
                val input = File(path)
                if (!input.isFile) {
                    return@withContext ParamCommandResult(success = false, summary = "文件不存在：$path")
                }
                // 分卷/7z/PKZip 族识别：页面四路分流是局部态，命令面引导进页面（openPage 兜底）
                if (SplitArchive.isSplitArchiveName(input.name) ||
                    VolumeAndSevenZip.isTier1ArchiveName(input.name)
                ) {
                    return@withContext ParamCommandResult(
                        success = false,
                        summary = "「${input.name}」是分卷/7z 压缩包——命令面板暂只支持普通包，" +
                            "请在参数表单点「打开页面」进解压页处理（支持分卷组与 7z）",
                    )
                }
                if (!SafeExtract.isArchive(input.name)) {
                    return@withContext ParamCommandResult(
                        success = false,
                        summary = "不是支持的压缩格式（${ArchiveViewModel.SUPPORTED_FORMATS}）：${input.name}",
                    )
                }
                runCatching {
                    val destDir = File(input.parentFile, "${input.name}${ArchiveViewModel.EXTRACTED_SUFFIX}")
                    val outputs = SafeExtract.extract(input, destDir)
                    if (outputs.isEmpty()) {
                        ParamCommandResult(success = false, summary = "压缩包内无可解出的条目（可能全被安全策略拒绝）")
                    } else {
                        ParamCommandResult(
                            summary = "解压完成，共 ${outputs.size} 个条目 → ${destDir.absolutePath}",
                            artifacts = listOf(destDir.absolutePath),
                        )
                    }
                }.getOrElse { e ->
                    ParamCommandResult(success = false, summary = "解压失败: ${e.message ?: e::class.simpleName}")
                }
            }
        },
    )

    val registrations: List<ToolPageRegistration> = listOf(
        // 导航入口收敛：tab 目录不再展示；content 保留（深入模式可达）
        ToolPageRegistration(
            page = ToolPage.Archive,
            navItem = null,
            group = ToolPageGroup.TOOLS,
            paramCommands = listOf(extractCommand),
        ) { ArchivePage() },
    )
}
