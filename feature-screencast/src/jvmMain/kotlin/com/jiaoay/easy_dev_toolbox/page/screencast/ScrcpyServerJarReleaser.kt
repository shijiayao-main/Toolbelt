package com.jiaoay.easy_dev_toolbox.page.screencast

import java.io.File
import java.io.IOException
import java.io.InputStream
import java.security.MessageDigest

/**
 * scrcpy server jar 随包释放器：模块 resources 内置官方 server jar
 * （v[ScrcpyServerDeployer.SERVER_VERSION]），引擎首启投屏时释放到目标落点，开箱即用
 * （免「去官方 release 手动下载置入」）。
 *
 * 路径解析顺序（引擎侧同一顺序，此处钉死）：
 * 1. **用户自置优先**：目标路径已有文件（用户手动置入或历史释放产物）→ 不覆盖、
 *    不改写，但**内容经 SHA256 校验**（[EXPECTED_SHA256]）——放行失配文件会在
 *    serve 段被 server 端硬抛（宿主先白等连接超时再落误导文案），校验把失败提前到
 *    会话起点并给人话指路；失配 → [Outcome.Failed]（文件仍不动，删否归用户）；
 * 2. 目标缺失且同目录无其他版本残留 → 从随包资源释放（半写残留下次按缺失重释，
 *    无原子性要求）；
 * 3. 目标缺失但同目录存在其他版本 server jar → [Outcome.Conflict]：不写不删任何
 *    文件，人话提示删除旧文件后重试——用户文件不静默覆盖/清理。
 *
 * 版本比对语义 = 文件名内嵌版本字面比对（`scrcpy-server-v<版本>` 前缀 + 与
 * [ScrcpyServerDeployer.SERVER_VERSION] 同源的文件名）：jar 内容与其文件名版本由官方
 * release 绑定（验签链见 spike runbook §1 判据 0）。升级 [ScrcpyServerDeployer.SERVER_VERSION]
 * 必须同步替换 resources 内 jar 与本常量派生的资源路径。
 *
 * 随包产物完整性契约：SHA256 `764eb6f79811d5211fe9df341120882ba9994c7a61b897d7bf3fb662e53bc536`
 * （733930 B，官方 v5.0.1 SHA256SUMS 验签值——与 spike runbook §1 判据 0 同源），
 * 由单测对释放产物重算钉死。
 */
object ScrcpyServerJarReleaser {

    /** 随包资源路径（classloader 根相对；文件名内嵌版本，与 [ScrcpyServerDeployer.SERVER_VERSION] 同批升级） */
    const val RESOURCE_PATH = "/scrcpy/scrcpy-server-v${ScrcpyServerDeployer.SERVER_VERSION}"

    /**
     * 随包 server jar 期望 SHA256（官方 v5.0.1 SHA256SUMS 验签值——单测对常量与随包
     * 资源双向钉死）。已在位文件按它校验：失配（异版本自置/半写/损坏）显式失败——
     * server 端对 args[0] 版本硬校验，放行失配文件 = 宿主白等连接超时后落误导文案。
     */
    const val EXPECTED_SHA256 = "764eb6f79811d5211fe9df341120882ba9994c7a61b897d7bf3fb662e53bc536"

    /** server jar 文件名版本前缀（同目录残留版本比对的形态判据） */
    private const val SERVER_JAR_NAME_PREFIX = "scrcpy-server-v"

    /**
     * 释放结果四态（全部不抛异常；失败真因经 [Conflict.hint] / [Failed.reason] 文字化，
     * 引擎接到会话级显式失败通道——不静默）。
     */
    sealed class Outcome {
        /** 已释放（本次写入产物） */
        data object Released : Outcome()

        /** 目标已存在（用户自置或历史释放产物）——未做任何写 */
        data object AlreadyPresent : Outcome()

        /** 同目录存在不同版本残留——未写未删；[hint] 为人话提示（删除旧文件后重试） */
        data class Conflict(val staleFiles: List<File>, val hint: String) : Outcome()

        /** 随包资源缺失或写失败——[reason] 为显式失败原因 */
        data class Failed(val reason: String) : Outcome()
    }

    /**
     * 释放 [target]（三序语义见类 KDoc）。[resourceLoader] 为单测注入面（生产恒走
     * 随包资源；单测注入缺失流/假流做隔离）。
     */
    fun release(
        target: File,
        resourceLoader: () -> InputStream? = ::loadBundledResource,
    ): Outcome {
        // 已在位文件：用户文件不覆盖不改写，但内容须与随包版本一致（见类 KDoc 第 1 序）
        if (target.isFile) {
            return if (sha256Of(target) == EXPECTED_SHA256) {
                Outcome.AlreadyPresent
            } else {
                Outcome.Failed(
                    "目标文件与随包版本不一致（SHA256 校验不符）——请删除该文件后重试，" +
                        "或按官方 release 重新下载置入（文件：${target.absolutePath}）",
                )
            }
        }
        val stale = staleVersionFiles(target)
        if (stale.isNotEmpty()) {
            val dir = target.parentFile?.absolutePath ?: target.absolutePath
            return Outcome.Conflict(
                stale,
                "检测到 scrcpy 目录存在不同版本 server jar" +
                    "（${stale.joinToString { it.name }}）——不会自动覆盖你手动置入的文件，" +
                    "请删除旧文件后重试（目录：$dir）",
            )
        }
        val resource = try {
            resourceLoader()
        } catch (e: IOException) {
            return Outcome.Failed(
                "随包 server jar 资源读取失败：${e.message ?: e::class.simpleName ?: "IO 异常"}",
            )
        } ?: return Outcome.Failed(
            "随包 server jar 资源缺失（$RESOURCE_PATH）——安装包不完整，请重新安装，" +
                "或按官方 release 下载置入 ${target.absolutePath}",
        )
        try {
            target.parentFile?.mkdirs()
            resource.use { input ->
                target.outputStream().use { output -> input.copyTo(output) }
            }
        } catch (e: IOException) {
            // 半写残留清理尽力而为（清理失败不掩盖写失败真因）
            runCatching { target.delete() }
            return Outcome.Failed(
                "server jar 释放失败：${e.message ?: e::class.simpleName ?: "IO 异常"}" +
                    "（目标：${target.absolutePath}）",
            )
        }
        return Outcome.Released
    }

    /** 同目录内文件名版本 ≠ 目标（即 [ScrcpyServerDeployer.SERVER_VERSION]）的 server jar 残留 */
    private fun staleVersionFiles(target: File): List<File> {
        val dir = target.parentFile ?: return emptyList()
        val files = dir.listFiles() ?: return emptyList()
        return files
            .filter { it.isFile && it.name.startsWith(SERVER_JAR_NAME_PREFIX) && it.name != target.name }
            .sortedBy { it.name }
    }

    private fun loadBundledResource(): InputStream? =
        ScrcpyServerJarReleaser::class.java.getResourceAsStream(RESOURCE_PATH)

    /** 文件 SHA256 十六进制小写（734KB 量级一次性读入 MessageDigest，会话起点一次可接受） */
    private fun sha256Of(file: File): String =
        MessageDigest.getInstance("SHA-256").digest(file.readBytes())
            .joinToString("") { "%02x".format(it) }
}
