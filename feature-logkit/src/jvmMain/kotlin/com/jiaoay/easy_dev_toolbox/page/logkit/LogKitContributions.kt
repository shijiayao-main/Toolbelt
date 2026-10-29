package com.jiaoay.easy_dev_toolbox.page.logkit

import dev.vicart.compose.material.symbols.MaterialSymbols

import com.jiaoay.easy_dev_toolbox.core.navigation.ParamCommandResult
import com.jiaoay.easy_dev_toolbox.core.navigation.ParamCommandSpec
import com.jiaoay.easy_dev_toolbox.core.navigation.ParamSpec
import com.jiaoay.easy_dev_toolbox.core.navigation.ParamType
import com.jiaoay.easy_dev_toolbox.core.navigation.ToolNavItem
import com.jiaoay.easy_dev_toolbox.core.navigation.ToolPageRegistration
import com.jiaoay.easy_dev_toolbox.core.navigation.ToolPageGroup
import com.jiaoay.easy_dev_toolbox.page.ToolPage
import com.jiaoay.easy_dev_toolbox.page.filesplit.FileSplitterPage
import com.jiaoay.easy_dev_toolbox.page.logtag.LogTagFilterPage
import com.jiaoay.easy_dev_toolbox.page.xlog.XLogDecoderPage
import com.jiaoay.xlog.LogDecode
import com.jiaoay.xlog.LogFormatDetector
import java.io.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * 日志分析域（XLog 解密 / Tag 过滤 / 文件分割）的导航注册出口。
 *
 * 导航入口收敛：XLog / Tag / 分割三页 navItem=null（tab 目录/dock 不再展示），
 * content 保留注册（palette 深入模式 openPage 跳页可达——WorkspaceTabs 临时 tab 承载；
 * 枚举值 S7 收敛）。页面能力以**带参命令**形态经 [paramCommands] 存活：palette 选中 →
 * 参数直填 → 直调 :log-tools 纯函数 → HUD 显示产物——产物语义与原页面 VM 逐字对齐
 * （xlog = `${name}.txt`，agent 工具的 `.log` 后缀刻意不混用）。
 *
 * performArgs 纪律（对齐 ToolAction）：只许薄转发/纯函数直调，禁捕获局部态——
 *    三件全部直调 :log-tools 静态 object，私钥经 LogKitConfigStore（object 门面）读取。
 */
object LogKitContributions {

    /** 带参命令：XLog 解密（产物 `${name}.txt`——对齐 XLogDecoderViewModel.process） */
    private val xlogDecryptCommand = ParamCommandSpec(
        id = "logkit.xlog-decrypt",
        label = "解密 XLog 设备日志",
        desc = ".xlog / .alog 加密日志解密为 .txt 纯文本（产物与源文件同目录）",
        icon = MaterialSymbols.TEXT_SNIPPET,
        keywords = listOf("xlog", "alog", "解密", "decrypt"),
        params = listOf(
            ParamSpec(
                id = "file_path",
                label = "加密日志路径",
                type = ParamType.FILE,
                placeholder = "拖入或填入 .xlog / .alog 文件绝对路径",
            ),
        ),
        openPage = ToolPage.XLogDecoder,
        performArgs = { args ->
            val path = args["file_path"].orEmpty()
            if (path.isBlank()) {
                return@ParamCommandSpec ParamCommandResult(success = false, summary = "未填写文件路径")
            }
            withContext(Dispatchers.IO) {
                val input = File(path)
                if (!input.isFile) {
                    return@withContext ParamCommandResult(success = false, summary = "文件不存在：$path")
                }
                val privateKey = LogKitConfigStore.getXLogPrivateKey()
                if (privateKey.isBlank()) {
                    return@withContext ParamCommandResult(
                        success = false,
                        // 私钥引导指向可达面——「工具」聚合页 dock 直达（旧文案指向
                        // palette-only 页，属高频摩擦面）
                        summary = "缺少 xlog 私钥：到「工具」页 XLog 解密签配置后重试",
                    )
                }
                // 产物语义对齐页面 VM（`${file.name}.txt`），非 agent 工具的 .log
                val output = File(input.parentFile, "${input.name}.txt")
                runCatching {
                    // 内容判型路由：明文（已解密文件误挂加密后缀 / 明文 mmap）跳过解密直接复制
                    if (LogFormatDetector.detect(input) == LogFormatDetector.Format.PLAINTEXT) {
                        input.copyTo(output, overwrite = true)
                    } else {
                        LogDecode().decodeFile(privateKey = privateKey, logFile = input, outFile = output)
                    }
                }.fold(
                    onSuccess = {
                        // 使用计数口径与页面 VM 一致（palette 直执行同为真实使用——漏报即 frecency 失真）
                        com.jiaoay.easy_dev_toolbox.core.navigation.ToolUsageSignal.report(
                            com.jiaoay.easy_dev_toolbox.core.navigation.ToolUsageSignal.PageId.XLOG_DECODER,
                        )
                        ParamCommandResult(
                            summary = "解密成功: ${output.absolutePath}",
                            artifacts = listOf(output.absolutePath),
                        )
                    },
                    onFailure = { e ->
                        // 中文分类映射与页面任务卡同源（describeFileTaskError——
                        // palette 直执行与页面路径错误面口径一致）
                        ParamCommandResult(
                            success = false,
                            summary = com.jiaoay.easy_dev_toolbox.widget.task.describeFileTaskError(e)
                                ?: "解密失败: ${e.message ?: e::class.simpleName}",
                        )
                    },
                )
            }
        },
    )

    /** 带参命令：Tag 正则过滤（直调 LogTagFilter.filterByTag——与 agent 工具同一函数） */
    private val tagFilterCommand = ParamCommandSpec(
        id = "logkit.tag-filter",
        label = "按 Tag 过滤日志",
        desc = "保留命中 Tag 正则的日志块，输出兄弟 .txt 文件",
        icon = MaterialSymbols.FILTER_LIST,
        keywords = listOf("tag", "过滤", "filter", "正则"),
        params = listOf(
            ParamSpec(
                id = "file_path",
                label = "日志文件路径",
                type = ParamType.FILE,
                placeholder = ".txt 纯文本日志绝对路径",
            ),
            ParamSpec(id = "tag_regex", label = "Tag 正则", type = ParamType.TEXT, placeholder = "如 MyTag|AnotherTag"),
            ParamSpec(id = "tag_name", label = "输出标记", type = ParamType.TEXT, placeholder = "用于输出文件命名，如 MyTag"),
        ),
        openPage = ToolPage.LogTagFilter,
        performArgs = { args ->
            val path = args["file_path"].orEmpty()
            val regex = args["tag_regex"].orEmpty().trim()
            val name = args["tag_name"].orEmpty().trim()
            when {
                path.isBlank() -> ParamCommandResult(success = false, summary = "未填写文件路径")
                regex.isEmpty() -> ParamCommandResult(success = false, summary = "Tag 正则不能为空")
                name.isEmpty() -> ParamCommandResult(success = false, summary = "输出标记不能为空")
                else -> withContext(Dispatchers.IO) {
                    val input = File(path)
                    if (!input.isFile) {
                        return@withContext ParamCommandResult(success = false, summary = "文件不存在：$path")
                    }
                    when (val result = com.jiaoay.log.LogTagFilter.filterByTag(input, regex, name)) {
                        is com.jiaoay.log.ToolsResult.Success -> {
                            // 使用计数口径与页面 VM 一致（palette 直执行同为真实使用）
                            com.jiaoay.easy_dev_toolbox.core.navigation.ToolUsageSignal.report(
                                com.jiaoay.easy_dev_toolbox.core.navigation.ToolUsageSignal.PageId.LOG_TAG_FILTER,
                            )
                            ParamCommandResult(
                                summary = "过滤完成，输出文件: ${result.outputFile.absolutePath}",
                                artifacts = listOf(result.outputFile.absolutePath),
                            )
                        }
                        is com.jiaoay.log.ToolsResult.Failure ->
                            ParamCommandResult(success = false, summary = "过滤失败: ${result.reason}")
                    }
                }
            }
        },
    )

    /** 带参命令：按大小分割（直调 LogSplitter.splitBySize） */
    private val fileSplitCommand = ParamCommandSpec(
        id = "logkit.file-split",
        label = "按大小分割日志",
        desc = "大日志文件按 MB 分卷，输出 <name>_000.txt 序列",
        icon = MaterialSymbols.CALL_SPLIT,
        keywords = listOf("分割", "split", "分卷"),
        params = listOf(
            ParamSpec(
                id = "file_path",
                label = "日志文件路径",
                type = ParamType.FILE,
                placeholder = "纯文本日志绝对路径",
            ),
            ParamSpec(id = "size_mb", label = "每卷大小（MB）", type = ParamType.NUMBER, default = "10"),
        ),
        openPage = ToolPage.FileSplitter,
        performArgs = { args ->
            val path = args["file_path"].orEmpty()
            val sizeMb = args["size_mb"]?.toIntOrNull() ?: 10
            if (path.isBlank()) {
                return@ParamCommandSpec ParamCommandResult(success = false, summary = "未填写文件路径")
            }
            if (sizeMb < 1) {
                return@ParamCommandSpec ParamCommandResult(success = false, summary = "分割大小需 ≥ 1 MB")
            }
            withContext(Dispatchers.IO) {
                val input = File(path)
                if (!input.isFile) {
                    return@withContext ParamCommandResult(success = false, summary = "文件不存在：$path")
                }
                when (val result = com.jiaoay.log.LogSplitter.splitBySize(input, sizeMb)) {
                    is com.jiaoay.log.ToolsResult.Success -> {
                        // 使用计数口径与页面 VM 一致（palette 直执行同为真实使用）
                        com.jiaoay.easy_dev_toolbox.core.navigation.ToolUsageSignal.report(
                            com.jiaoay.easy_dev_toolbox.core.navigation.ToolUsageSignal.PageId.FILE_SPLITTER,
                        )
                        ParamCommandResult(
                            summary = "分割完成，首卷: ${result.outputFile.absolutePath}",
                            artifacts = listOf(result.outputFile.absolutePath),
                        )
                    }
                    is com.jiaoay.log.ToolsResult.Failure ->
                        ParamCommandResult(success = false, summary = "分割失败: ${result.reason}")
                }
            }
        },
    )

    val registrations: List<ToolPageRegistration> = listOf(
        // 导航入口收敛：tab 目录/dock/应用中心不再展示（palette 深入模式
        // openPage 可达 + 命令直执行主通道）；content 保留注册（PageHomeContent 分发兜底）
        ToolPageRegistration(
            page = ToolPage.XLogDecoder,
            navItem = null,
            group = ToolPageGroup.TOOLS,
            paramCommands = listOf(xlogDecryptCommand),
        ) { XLogDecoderPage() },
        ToolPageRegistration(
            page = ToolPage.LogTagFilter,
            navItem = null,
            group = ToolPageGroup.TOOLS,
            paramCommands = listOf(tagFilterCommand),
        ) { LogTagFilterPage() },
        ToolPageRegistration(
            page = ToolPage.FileSplitter,
            navItem = null,
            group = ToolPageGroup.TOOLS,
            paramCommands = listOf(fileSplitCommand),
        ) { FileSplitterPage() },
    )
}
