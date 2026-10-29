package com.jiaoay.easy_dev_toolbox.page.shelltools.config

import com.jiaoay.easy_dev_toolbox.core.data.FileUtils
import com.jiaoay.easy_dev_toolbox.core.log.JLog
import com.jiaoay.easy_dev_toolbox.entity.JErrorCode
import com.jiaoay.easy_dev_toolbox.entity.JResult
import com.jiaoay.easy_dev_toolbox.page.shelltools.ShellToolsConfigRepository
import java.io.File

/**
 * 从用户目录加载 shell_tools.json 配置文件（主配置，只读语义）。
 *
 * 文件目标可通过 [load] 注入，供 [ShellToolsConfigRepository] 与单测复用。
 */
object ShellToolsConfigLoader {

    private const val TAG = "ShellToolsConfigLoader"

    suspend fun load(): JResult<List<ToolNode>> = load(FileUtils.getShellToolsFile())

    suspend fun load(configFile: File): JResult<List<ToolNode>> {
        if (!configFile.exists()) {
            JLog.d(TAG) { "Config file not found: ${configFile.absolutePath}" }
            return JResult.Failed(
                code = JErrorCode.ShellConfigNotFound,
                msg = configFile.absolutePath,
            )
        }

        val json = try {
            // 🔴 剥离 UTF-8 BOM（Windows 记事本保存的 JSON 恒带 BOM——不剥则 JSON
            // 解析直接失败，用户改一个值即整个 shell_tools.json 读不出）
            com.jiaoay.easy_dev_toolbox.core.data.BomStripper.readTextWithoutBom(configFile)
        } catch (e: Exception) {
            JLog.e(TAG) { "Failed to read config file: ${e.message}" }
            return JResult.Failed(code = JErrorCode.ShellConfigParseFailed, msg = e.message)
        }

        if (json.isBlank()) {
            return JResult.Failed(code = JErrorCode.ShellConfigEmpty)
        }

        val configs = ShellToolsConfigParser.decodeConfigs(json)
            ?: run {
                JLog.e(TAG) { "Failed to parse config: illegal JSON or non-array root" }
                return JResult.Failed(
                    code = JErrorCode.ShellConfigParseFailed,
                    msg = "JSON 非法或顶层不是数组",
                )
            }

        val nodes = ShellToolsConfigParser.toToolNodes(configs)
        JLog.d(TAG) { "Loaded ${nodes.size} root nodes from config" }
        return JResult.Success(data = nodes)
    }
}
