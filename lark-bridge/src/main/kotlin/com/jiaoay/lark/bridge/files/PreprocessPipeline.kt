// 下载后预处理管道。
// 固定顺序（代码即序）：AutoExtract → XlogDecode → CustomCommand；各自按扩展名自作用域。
// 🔴 每步 runCatching 失败降级不中断（预处理是尽力而为；主文件路径恒在 payload 里）。
// 🔴 CustomCommand：argv 模板直喂 ProgramLauncher（不起 shell，与 agent 子进程同级安全面）。
package com.jiaoay.lark.bridge.files

import com.jiaoay.lark.bridge.BridgeLog
import com.jiaoay.lark.bridge.config.BridgeConfig
import com.jiaoay.lark.bridge.exec.LaunchResult
import com.jiaoay.utils.SafeExtract
import java.io.File

/**
 * 外部命令执行 seam：生产装配注入真 ProgramLauncher（共享 ProcessRegistry，取消/看门狗/登记语义
 * 免费获得）；测试注入 fake 免真进程。
 */
fun interface ProgramRunner {
    suspend fun run(argv: List<String>, workDir: File, totalTimeoutMs: Long, idleTimeoutMs: Long): LaunchResult
}

/**
 * 预处理管道（装配期单例）。
 *
 * ## 规则表（固定序）
 * | 步 | 开关 | 作用域 | 输出 |
 * |---|---|---|---|
 * | 1 AutoExtract | `fileMessage.autoExtract` | `SafeExtract.isArchive` | `workDir` 下的 `extracted` 子目录 |
 * | 2 XlogDecode | 恒启用（私钥空则跳过） | `.xlog` / `.alog` | `workDir` 下的 `decoded` 子目录 |
 * | 3 CustomCommand | `fileMessage.preprocessCommand` 非空 | `preprocessExtensions`（空=全部） | `{output}` 指定，缺省 `workDir` 下的 `preprocessed` 子目录 |
 *
 * @param xlogDecoder XLog 解密 seam（默认委托 :log-tools 的 LogDecode；测试注入 fake）。
 * @param runProgram 外部命令执行 seam（生产装配必须注入；null = 自定义命令被跳过并 warn）。
 */
class PreprocessPipeline(
    private val configProvider: () -> BridgeConfig,
    private val log: BridgeLog,
    private val xlogDecoder: (privateKey: String, logFile: File, outFile: File) -> Unit =
        { key, input, output -> com.jiaoay.xlog.LogDecode().decodeFile(key, input, output) },
    private val runProgram: ProgramRunner? = null,
) {

    /**
     * 对已落盘的主文件跑一遍管道。
     * @param file 主文件（引用下载的原始文件）
     * @param workDir 该文件的专属工作目录（per-message 目录）
     * @return 产物绝对路径列表（artifacts，按规则序）；全失败/无规则命中 = 空列表（主文件路径由调用方保底）
     */
    suspend fun process(file: File, workDir: File): List<File> {
        val artifacts = mutableListOf<File>()
        val cfg = configProvider().fileMessage

        // 1) AutoExtract（既有开关语义平移：解压产物留在消息目录内，不再用完即删）
        if (cfg.autoExtract && SafeExtract.isArchive(file.name)) {
            val extractDir = File(workDir, EXTRACTED_DIR)
            runCatching {
                val outputs = SafeExtract.extract(file, extractDir)
                if (outputs.isNotEmpty()) artifacts.addAll(outputs)
            }.onFailure { log.warn("$TAG 解压失败 file=${file.name}: ${it.message}") }
        }

        // 2) XlogDecode（.xlog/.alog → 可读 .log；内容判型——明文（已解密误挂加密后缀/明文 mmap）跳过）
        if (isXlogFile(file.name)) {
            if (cfg.xlogPrivateKey.isBlank()) {
                log.warn("$TAG 加密日志私钥为空（桥接配置 fileMessage.xlogPrivateKey 与宿主回落源均未提供），跳过解密 file=${file.name}")
            } else if (com.jiaoay.xlog.LogFormatDetector.detect(file) == com.jiaoay.xlog.LogFormatDetector.Format.PLAINTEXT) {
                log.info("$TAG 内容已是明文（无需解密），跳过解密 file=${file.name}")
            } else {
                val decodeDir = File(workDir, DECODED_DIR)
                runCatching {
                    decodeDir.mkdirs()
                    val out = File(decodeDir, "${file.nameWithoutExtension}.log")
                    xlogDecoder(cfg.xlogPrivateKey, file, out)
                    if (out.isFile) artifacts.add(out)
                }.onFailure { log.warn("$TAG xlog 解密失败 file=${file.name}: ${it.message}") }
            }
        }

        // 3) CustomCommand（argv 模板，不起 shell）
        if (cfg.preprocessCommand.isNotBlank() && matchesExtensions(file.name, cfg.preprocessExtensions)) {
            val runner = runProgram ?: run {
                log.warn("$TAG 未注入 runProgram seam，跳过自定义预处理 file=${file.name}")
                return artifacts.toList()
            }
            val outDir = File(workDir, PREPROCESSED_DIR)
            val output = File(outDir, "${file.nameWithoutExtension}.out")
            runCatching {
                outDir.mkdirs()
                val argv = tokenize(cfg.preprocessCommand)
                    .map { it.replace(INPUT_PLACEHOLDER, file.absolutePath).replace(OUTPUT_PLACEHOLDER, output.absolutePath) }
                val runtime = configProvider().runtime
                val result = runner.run(argv, workDir, runtime.taskTimeoutSeconds * 1000L, runtime.agentIdleTimeoutSeconds * 1000L)
                if (result.exitCode != 0) {
                    log.warn("$TAG 自定义预处理命令退出码 ${result.exitCode} file=${file.name}")
                }
                if (output.isFile) artifacts.add(output)
            }.onFailure { log.warn("$TAG 自定义预处理命令失败 file=${file.name}: ${it.message}") }
        }

        return artifacts.toList()
    }

    private fun isXlogFile(name: String): Boolean =
        name.lowercase().endsWith(".xlog") || name.lowercase().endsWith(".alog")

    /** 扩展名作用域判定：空白 = 全部；否则逗号分隔逐项匹配（补 `.` 前缀——
     *    裸 `endsWith("log")` 会误命中 `catalog`/`dialog`；用户配 `log` 的意图恒是 `.log`）。 */
    private fun matchesExtensions(fileName: String, csv: String): Boolean {
        val list = csv.split(',', '，').map { it.trim().lowercase() }.filter { it.isNotEmpty() }
        if (list.isEmpty()) return true
        val lower = fileName.lowercase()
        return list.any { ext ->
            // 用户写 `log` 或 `.log` 都按 `.log` 匹配（normalize 后比较，双形态都兼容）
            val needle = if (ext.startsWith('.')) ext else ".$ext"
            lower.endsWith(needle)
        }
    }

    companion object {
        const val EXTRACTED_DIR: String = "extracted"
        const val DECODED_DIR: String = "decoded"
        const val PREPROCESSED_DIR: String = "preprocessed"
        const val INPUT_PLACEHOLDER: String = "{input}"
        const val OUTPUT_PLACEHOLDER: String = "{output}"

        /**
         * 最小 shell 风格 argv tokenizer：空白分隔、尊重单/双引号（引号内空白不拆、转义引号原样保留）。
         * 🔴 只做拆分不做展开——产物直喂 ProgramLauncher（argv 语义），不存在 shell 注入面。
         */
        fun tokenize(template: String): List<String> {
            val tokens = mutableListOf<String>()
            val current = StringBuilder()
            var quote: Char? = null
            var hasToken = false
            template.forEach { ch ->
                when {
                    quote != null -> {
                        if (ch == quote) quote = null else current.append(ch)
                    }
                    ch == '\'' || ch == '"' -> {
                        quote = ch
                        hasToken = true
                    }
                    ch.isWhitespace() -> {
                        if (hasToken || current.isNotEmpty()) {
                            tokens.add(current.toString())
                            current.setLength(0)
                            hasToken = false
                        }
                    }
                    else -> {
                        current.append(ch)
                        hasToken = true
                    }
                }
            }
            if (hasToken || current.isNotEmpty()) tokens.add(current.toString())
            return tokens
        }

        private const val TAG = "PreprocessPipeline"
    }
}
